package com.opspilot.server.agent;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.opspilot.server.entity.Evidence;
import com.opspilot.server.mapper.EvidenceMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 证据服务：工具产出的证据统一落 evidence 表，编号可读（E-1001 起），
 * 启动时从 DB 已有最大编号恢复序号，保证重启后不重复。
 */
@Service
public class EvidenceService {

    private static final Logger log = LoggerFactory.getLogger(EvidenceService.class);

    private final EvidenceMapper evidenceMapper;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AtomicInteger seq = new AtomicInteger(1000);

    public EvidenceService(EvidenceMapper evidenceMapper) {
        this.evidenceMapper = evidenceMapper;
    }

    @PostConstruct
    void init() {
        Evidence last = evidenceMapper.selectOne(new QueryWrapper<Evidence>()
                .select("id")
                .orderByDesc("CAST(SUBSTRING(id, 3) AS UNSIGNED)")
                .last("LIMIT 1"));
        if (last != null && last.getId() != null && last.getId().startsWith("E-")) {
            try {
                seq.set(Integer.parseInt(last.getId().substring(2)));
            } catch (NumberFormatException ignored) {
                // 保持默认 1000
            }
        }
        log.info("evidence 序号初始化完成，下一个编号 E-{}", seq.get() + 1);
    }

    /** 创建证据，返回证据编号（如 E-1001） */
    public String create(Long runId, String sourceType, String sourceId,
                         String title, String summary, Object payload) {
        Evidence e = new Evidence();
        e.setId("E-" + seq.incrementAndGet());
        e.setRunId(runId == null ? 0L : runId);
        e.setSourceType(sourceType);
        e.setSourceId(sourceId);
        e.setTitle(title);
        e.setSummary(summary != null && summary.length() > 500 ? summary.substring(0, 500) : summary);
        try {
            e.setPayload(payload == null ? null : objectMapper.writeValueAsString(payload));
        } catch (Exception ex) {
            e.setPayload(String.valueOf(payload));
        }
        e.setCreatedAt(LocalDateTime.now());
        evidenceMapper.insert(e);
        return e.getId();
    }
}
