package com.opspilot.server.rag;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.opspilot.server.entity.Runbook;
import com.opspilot.server.entity.RunbookChunk;
import com.opspilot.server.mapper.RunbookChunkMapper;
import com.opspilot.server.mapper.RunbookMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Runbook 混合检索：CJK bigram + 拉丁词关键词评分，与向量余弦分数按 RRF(k=60) 融合。
 * chunk 全量缓存内存（规模小），refresh() 重建索引。
 */
@Service
public class RunbookSearchService {

    private static final Logger log = LoggerFactory.getLogger(RunbookSearchService.class);
    private static final int RRF_K = 60;

    private final RunbookMapper runbookMapper;
    private final RunbookChunkMapper chunkMapper;
    private final OllamaClient ollamaClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** chunkId -> 索引条目 */
    private final Map<Long, ChunkEntry> index = new ConcurrentHashMap<>();

    public RunbookSearchService(RunbookMapper runbookMapper, RunbookChunkMapper chunkMapper,
                                OllamaClient ollamaClient) {
        this.runbookMapper = runbookMapper;
        this.chunkMapper = chunkMapper;
        this.ollamaClient = ollamaClient;
    }

    private record ChunkEntry(Long chunkId, Long runbookId, String runbookTitle,
                              String category, int chunkNo, String content,
                              Set<String> terms, List<Double> vector) {}

    /** 从 DB 重建内存索引 */
    public synchronized void refresh() {
        index.clear();
        List<RunbookChunk> chunks = chunkMapper.selectList(new QueryWrapper<>());
        Map<Long, Runbook> books = new HashMap<>();
        runbookMapper.selectList(new QueryWrapper<>()).forEach(b -> books.put(b.getId(), b));
        for (RunbookChunk c : chunks) {
            Runbook b = books.get(c.getRunbookId());
            if (b == null) {
                continue;
            }
            List<Double> vec = null;
            if (c.getVector() != null) {
                try {
                    List<?> raw = objectMapper.readValue(c.getVector(), List.class);
                    vec = new ArrayList<>(raw.size());
                    for (Object o : raw) {
                        vec.add(((Number) o).doubleValue());
                    }
                } catch (Exception e) {
                    log.warn("解析向量失败 chunkId={}", c.getId());
                }
            }
            index.put(c.getId(), new ChunkEntry(c.getId(), b.getId(), b.getTitle(),
                    b.getCategory(), c.getChunkNo(), c.getContent(), tokenize(c.getContent()), vec));
        }
        log.info("runbook 检索索引重建完成，{} 个 chunk", index.size());
    }

    public int chunkCount() {
        return index.size();
    }

    /** 分词：CJK 连续串切 bigram（单字也保留），拉丁/数字按词保留，统一小写 */
    static Set<String> tokenize(String text) {
        Set<String> terms = new HashSet<>();
        String lower = text.toLowerCase();
        StringBuilder latin = new StringBuilder();
        StringBuilder cjk = new StringBuilder();
        Runnable flush = () -> {
            if (latin.length() > 0) {
                terms.add(latin.toString());
                latin.setLength(0);
            }
            if (cjk.length() == 1) {
                terms.add(cjk.toString());
            } else if (cjk.length() > 1) {
                for (int i = 0; i < cjk.length() - 1; i++) {
                    terms.add(cjk.substring(i, i + 2));
                }
                if (cjk.length() <= 6) {
                    terms.add(cjk.toString());
                }
            }
            cjk.setLength(0);
        };
        for (int i = 0; i < lower.length(); i++) {
            char ch = lower.charAt(i);
            if (isCjk(ch)) {
                if (latin.length() > 0) {
                    terms.add(latin.toString());
                    latin.setLength(0);
                }
                cjk.append(ch);
            } else if (Character.isLetterOrDigit(ch)) {
                if (cjk.length() > 0) {
                    flush.run();
                }
                latin.append(ch);
            } else {
                flush.run();
            }
        }
        flush.run();
        return terms;
    }

    private static boolean isCjk(char ch) {
        Character.UnicodeBlock ub = Character.UnicodeBlock.of(ch);
        return ub == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS
                || ub == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A
                || ub == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS;
    }

    /**
     * 混合检索 topK。
     * @return 有序结果列表：chunkId/runbookTitle/chunkNo/snippet/score
     */
    public List<Map<String, Object>> search(String query, int topK) {
        topK = Math.min(Math.max(topK, 1), 6);
        if (index.isEmpty()) {
            refresh();
        }
        if (index.isEmpty()) {
            return List.of();
        }
        Set<String> qTerms = tokenize(query);
        List<Double> qVec = ollamaClient.embed(query);

        // 关键词排名
        List<Map.Entry<Long, Double>> kwRank = new ArrayList<>();
        for (ChunkEntry e : index.values()) {
            double hit = 0;
            for (String t : qTerms) {
                if (e.terms().contains(t)) {
                    hit += t.length() > 1 ? 2.0 : 0.5;
                }
            }
            if (hit > 0) {
                // 覆盖率加权：命中词数 / 查询词数
                double score = hit * (1.0 + hit / Math.max(qTerms.size(), 1) * 0.5);
                kwRank.add(Map.entry(e.chunkId(), score));
            }
        }
        kwRank.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));

        // 向量排名
        List<Map.Entry<Long, Double>> vecRank = new ArrayList<>();
        if (qVec != null) {
            for (ChunkEntry e : index.values()) {
                if (e.vector() != null) {
                    double sim = OllamaClient.cosine(qVec, e.vector());
                    if (sim > 0.3) {
                        vecRank.add(Map.entry(e.chunkId(), sim));
                    }
                }
            }
            vecRank.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));
        }

        // RRF 融合
        Map<Long, Double> fused = new HashMap<>();
        for (int i = 0; i < kwRank.size(); i++) {
            fused.merge(kwRank.get(i).getKey(), 1.0 / (RRF_K + i + 1), Double::sum);
        }
        for (int i = 0; i < vecRank.size(); i++) {
            fused.merge(vecRank.get(i).getKey(), 1.0 / (RRF_K + i + 1), Double::sum);
        }

        return fused.entrySet().stream()
                .sorted((a, b) -> Double.compare(b.getValue(), a.getValue()))
                .limit(topK)
                .map(en -> {
                    ChunkEntry e = index.get(en.getKey());
                    String snippet = e.content().length() > 220
                            ? e.content().substring(0, 220) + "…" : e.content();
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("chunkId", e.chunkId());
                    m.put("runbookId", e.runbookId());
                    m.put("title", e.runbookTitle());
                    m.put("category", e.category());
                    m.put("chunkNo", e.chunkNo());
                    m.put("score", Math.round(en.getValue() * 10000) / 10000.0);
                    m.put("snippet", snippet);
                    return m;
                })
                .toList();
    }
}
