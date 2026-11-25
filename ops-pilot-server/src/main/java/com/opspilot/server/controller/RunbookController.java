package com.opspilot.server.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.opspilot.server.entity.Runbook;
import com.opspilot.server.entity.RunbookChunk;
import com.opspilot.server.mapper.RunbookChunkMapper;
import com.opspilot.server.mapper.RunbookMapper;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Runbook 知识库查看。
 */
@RestController
@RequestMapping("/api/runbooks")
public class RunbookController {

    private final RunbookMapper runbookMapper;
    private final RunbookChunkMapper chunkMapper;

    public RunbookController(RunbookMapper runbookMapper, RunbookChunkMapper chunkMapper) {
        this.runbookMapper = runbookMapper;
        this.chunkMapper = chunkMapper;
    }

    @GetMapping
    public List<Map<String, Object>> list() {
        List<Runbook> books = runbookMapper.selectList(new QueryWrapper<Runbook>().orderByAsc("id"));
        List<Map<String, Object>> list = new ArrayList<>();
        for (Runbook b : books) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", b.getId());
            m.put("title", b.getTitle());
            m.put("category", b.getCategory());
            m.put("updatedAt", b.getUpdatedAt() == null ? null : b.getUpdatedAt().toString());
            Long chunks = chunkMapper.selectCount(new QueryWrapper<RunbookChunk>().eq("runbook_id", b.getId()));
            m.put("chunkCount", chunks);
            list.add(m);
        }
        return list;
    }

    @GetMapping("/{id}")
    public Map<String, Object> detail(@PathVariable Long id) {
        Runbook b = runbookMapper.selectById(id);
        Map<String, Object> m = new LinkedHashMap<>();
        if (b == null) {
            m.put("error", "runbook 不存在");
            return m;
        }
        m.put("id", b.getId());
        m.put("title", b.getTitle());
        m.put("category", b.getCategory());
        m.put("content", b.getContent());
        m.put("updatedAt", b.getUpdatedAt() == null ? null : b.getUpdatedAt().toString());
        return m;
    }
}
