package com.opspilot.server.rag;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.opspilot.server.entity.Runbook;
import com.opspilot.server.entity.RunbookChunk;
import com.opspilot.server.mapper.RunbookChunkMapper;
import com.opspilot.server.mapper.RunbookMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Runbook 加载器：启动时读取 docs/runbooks/*.md，解析 front matter，
 * 按 300-500 字切 chunk，bge-m3 向量入库。内容未变化则跳过（幂等）。
 */
@Order(10)
@Component
public class RunbookLoader implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(RunbookLoader.class);

    private static final int CHUNK_MIN = 300;
    private static final int CHUNK_MAX = 500;

    private final RunbookMapper runbookMapper;
    private final RunbookChunkMapper chunkMapper;
    private final OllamaClient ollamaClient;
    private final RunbookSearchService searchService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${opspilot.runbook-dir:docs/runbooks}")
    private String runbookDir;

    public RunbookLoader(RunbookMapper runbookMapper, RunbookChunkMapper chunkMapper,
                         OllamaClient ollamaClient, RunbookSearchService searchService) {
        this.runbookMapper = runbookMapper;
        this.chunkMapper = chunkMapper;
        this.ollamaClient = ollamaClient;
        this.searchService = searchService;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        Path dir = Path.of(runbookDir);
        if (!Files.isDirectory(dir)) {
            dir = Path.of("..", runbookDir).normalize();
        }
        if (!Files.isDirectory(dir)) {
            log.warn("runbook 目录不存在: {}，跳过加载", runbookDir);
            return;
        }

        boolean embedUp = ollamaClient.embedAvailable();
        log.info("runbook 加载目录: {}，embedding 可用: {}", dir.toAbsolutePath(), embedUp);

        List<Path> files;
        try (Stream<Path> s = Files.list(dir)) {
            files = s.filter(p -> p.toString().endsWith(".md")).sorted().toList();
        }
        for (Path file : files) {
            loadOne(file, embedUp);
        }
        searchService.refresh();
        log.info("runbook 加载完成，共 {} 篇", files.size());
    }

    private void loadOne(Path file, boolean embedUp) throws Exception {
        String raw = Files.readString(file, StandardCharsets.UTF_8);
        String title = file.getFileName().toString().replace(".md", "");
        String category = "通用";
        String content = raw;
        // 解析 front matter
        if (raw.startsWith("---")) {
            int end = raw.indexOf("\n---", 3);
            if (end > 0) {
                String fm = raw.substring(3, end);
                for (String line : fm.split("\n")) {
                    int colon = line.indexOf(':');
                    if (colon > 0) {
                        String k = line.substring(0, colon).trim();
                        String v = line.substring(colon + 1).trim();
                        if ("title".equals(k)) {
                            title = v;
                        } else if ("category".equals(k)) {
                            category = v;
                        }
                    }
                }
                content = raw.substring(end + 4).trim();
            }
        }

        Runbook existing = runbookMapper.selectOne(new QueryWrapper<Runbook>().eq("title", title));
        if (existing != null && existing.getContent().equals(content)) {
            Integer vecCount = chunkMapper.selectCount(new QueryWrapper<RunbookChunk>()
                    .eq("runbook_id", existing.getId()).isNotNull("vector")).intValue();
            Integer total = chunkMapper.selectCount(new QueryWrapper<RunbookChunk>()
                    .eq("runbook_id", existing.getId())).intValue();
            if (total > 0 && (vecCount.equals(total) || !embedUp)) {
                return; // 幂等：内容未变且已向量化（或 embed 不可用保持现状）
            }
        }

        // upsert runbook
        Runbook rb = existing == null ? new Runbook() : existing;
        rb.setTitle(title);
        rb.setCategory(category);
        rb.setContent(content);
        rb.setUpdatedAt(LocalDateTime.now());
        if (rb.getId() == null) {
            runbookMapper.insert(rb);
        } else {
            runbookMapper.updateById(rb);
            chunkMapper.delete(new QueryWrapper<RunbookChunk>().eq("runbook_id", rb.getId()));
        }

        List<String> chunks = splitChunks(content);
        int no = 0;
        for (String c : chunks) {
            RunbookChunk chunk = new RunbookChunk();
            chunk.setRunbookId(rb.getId());
            chunk.setChunkNo(no++);
            chunk.setContent(title + "｜" + c);
            if (embedUp) {
                List<Double> vec = ollamaClient.embed(chunk.getContent());
                if (vec != null) {
                    chunk.setModel(ollamaClient.getEmbedModel());
                    chunk.setDim(vec.size());
                    chunk.setVector(objectMapper.writeValueAsString(vec));
                }
            }
            chunkMapper.insert(chunk);
        }
        log.info("runbook [{}] 入库，{} 个 chunk", title, chunks.size());
    }

    /**
     * 按标题/空行切段，聚合到 300-500 字：段落累加超过 CHUNK_MIN 即切，
     * 单段超过 CHUNK_MAX 按句号硬切。
     */
    static List<String> splitChunks(String content) {
        String[] sections = content.split("\n(?=#)|\n\\s*\n");
        List<String> chunks = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (String sec : sections) {
            String t = sec.trim();
            if (t.isEmpty()) {
                continue;
            }
            if (cur.length() + t.length() > CHUNK_MAX && cur.length() >= CHUNK_MIN) {
                chunks.add(cur.toString().trim());
                cur = new StringBuilder();
            }
            if (t.length() > CHUNK_MAX) {
                if (cur.length() > 0) {
                    chunks.add(cur.toString().trim());
                    cur = new StringBuilder();
                }
                // 长段落按句号硬切
                int start = 0;
                while (start < t.length()) {
                    int endIdx = Math.min(start + CHUNK_MAX, t.length());
                    if (endIdx < t.length()) {
                        int dot = t.lastIndexOf('。', endIdx);
                        if (dot > start + CHUNK_MIN) {
                            endIdx = dot + 1;
                        }
                    }
                    chunks.add(t.substring(start, endIdx).trim());
                    start = endIdx;
                }
                continue;
            }
            cur.append(t).append("\n");
        }
        if (cur.length() > 0) {
            chunks.add(cur.toString().trim());
        }
        return chunks;
    }
}
