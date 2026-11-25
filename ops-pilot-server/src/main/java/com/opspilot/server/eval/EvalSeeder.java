package com.opspilot.server.eval;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.opspilot.server.entity.EvalScenario;
import com.opspilot.server.mapper.EvalScenarioMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 20 条评测场景种子数据（幂等，仅表为空时写入）。
 * demo 仅支持 4 类故障，场景通过强度/时长/命名体现不同故障形态，期望根因用关键词数组评分。
 */
@Component
@Order(20)
public class EvalSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(EvalSeeder.class);

    private final EvalScenarioMapper scenarioMapper;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public EvalSeeder(EvalScenarioMapper scenarioMapper) {
        this.scenarioMapper = scenarioMapper;
    }

    @Override
    public void run(ApplicationArguments args) {
        Long count = scenarioMapper.selectCount(new QueryWrapper<>());
        if (count != null && count > 0) {
            log.info("eval 场景已存在 {} 条，跳过种子", count);
            return;
        }
        List<EvalScenario> list = buildScenarios();
        for (EvalScenario s : list) {
            scenarioMapper.insert(s);
        }
        log.info("eval 场景种子写入完成：{} 条", list.size());
    }

    private List<EvalScenario> buildScenarios() {
        List<EvalScenario> list = new ArrayList<>();
        // ---- 慢 SQL 5 条 ----
        list.add(scenario("慢 SQL-轻度过载", "SLOW_SQL", 3, 90,
                List.of("慢 SQL", "慢查询", "索引", "延迟"), "RECOVER_FAULT"));
        list.add(scenario("慢 SQL-深分页全表扫描", "SLOW_SQL", 6, 90,
                List.of("慢 SQL", "深分页", "全表扫描", "索引"), "RECOVER_FAULT"));
        list.add(scenario("慢 SQL-缺索引模糊匹配", "SLOW_SQL", 8, 90,
                List.of("慢 SQL", "索引", "模糊", "LIKE"), "RECOVER_FAULT"));
        list.add(scenario("慢 SQL-高峰期压力叠加", "SLOW_SQL", 10, 120,
                List.of("慢 SQL", "数据库", "延迟", "压力"), "RECOVER_FAULT"));
        list.add(scenario("慢 SQL-持续劣化", "SLOW_SQL", 5, 180,
                List.of("慢 SQL", "DB", "延迟", "数据库"), "RECOVER_FAULT"));
        // ---- 缓存 5 条 ----
        list.add(scenario("缓存-命中率骤降", "CACHE_BREAKDOWN", 4, 90,
                List.of("缓存", "击穿", "命中率", "回源"), "CLEAR_CACHE"));
        list.add(scenario("缓存-热点 key 失效", "CACHE_BREAKDOWN", 6, 90,
                List.of("缓存", "热点", "击穿", "命中率"), "CLEAR_CACHE"));
        list.add(scenario("缓存-批量过期雪崩", "CACHE_BREAKDOWN", 8, 90,
                List.of("缓存", "雪崩", "过期", "回源"), "CLEAR_CACHE"));
        list.add(scenario("缓存-回源压力放大", "CACHE_BREAKDOWN", 10, 120,
                List.of("缓存", "回源", "数据库", "击穿"), "CLEAR_CACHE"));
        list.add(scenario("缓存-集群抖动", "CACHE_BREAKDOWN", 5, 180,
                List.of("缓存", "命中率", "抖动", "回源"), "CLEAR_CACHE"));
        // ---- 线程池 5 条 ----
        list.add(scenario("线程池-队列积压", "THREAD_POOL_EXHAUSTION", 4, 90,
                List.of("线程池", "队列", "积压", "打满"), "SCALE_THREAD_POOL"));
        list.add(scenario("线程池-活跃线程飙升", "THREAD_POOL_EXHAUSTION", 6, 90,
                List.of("线程池", "打满", "线程", "活跃"), "SCALE_THREAD_POOL"));
        list.add(scenario("线程池-请求被拒绝", "THREAD_POOL_EXHAUSTION", 8, 90,
                List.of("线程池", "拒绝", "打满", "队列"), "SCALE_THREAD_POOL"));
        list.add(scenario("线程池-下游阻塞传导", "THREAD_POOL_EXHAUSTION", 7, 120,
                List.of("线程池", "下游", "阻塞", "打满"), "SCALE_THREAD_POOL"));
        list.add(scenario("线程池-持续高负载", "THREAD_POOL_EXHAUSTION", 10, 180,
                List.of("线程池", "打满", "压力", "队列"), "SCALE_THREAD_POOL"));
        // ---- 下游 5 条 ----
        list.add(scenario("下游-超时传导", "DOWNSTREAM_TIMEOUT", 4, 90,
                List.of("下游", "超时", "依赖", "库存"), "ENABLE_RATE_LIMIT"));
        list.add(scenario("下游-错误率升高", "DOWNSTREAM_TIMEOUT", 6, 90,
                List.of("下游", "错误", "5xx", "错误率"), "ENABLE_RATE_LIMIT"));
        list.add(scenario("下游-连接积压", "DOWNSTREAM_TIMEOUT", 8, 90,
                List.of("下游", "超时", "连接", "积压"), "ENABLE_RATE_LIMIT"));
        list.add(scenario("下游-熔断触发", "DOWNSTREAM_TIMEOUT", 9, 120,
                List.of("下游", "熔断", "超时", "错误率"), "ENABLE_RATE_LIMIT"));
        list.add(scenario("下游-级联影响", "DOWNSTREAM_TIMEOUT", 10, 180,
                List.of("下游", "级联", "超时", "错误率"), "ENABLE_RATE_LIMIT"));
        return list;
    }

    private EvalScenario scenario(String name, String faultType, int intensity, int durationSeconds,
                                  List<String> keywords, String expectedAction) {
        EvalScenario s = new EvalScenario();
        s.setName(name);
        s.setFaultType(faultType);
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("intensity", intensity);
        config.put("durationSeconds", durationSeconds);
        try {
            s.setConfig(objectMapper.writeValueAsString(config));
            s.setExpectedRootCause(objectMapper.writeValueAsString(keywords));
        } catch (Exception e) {
            s.setConfig("{}");
            s.setExpectedRootCause("[]");
        }
        s.setExpectedAction(expectedAction);
        s.setEnabled(1);
        return s;
    }
}
