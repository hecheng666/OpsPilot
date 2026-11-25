---
title: 慢 SQL 排查与处置
category: 数据库
---
# 慢 SQL 排查与处置

## 典型症状
接口 P95 延迟突增超过 800ms，dbRtMs 指标超过 500ms，日志出现 SLOW_QUERY 模式（如「订单列表查询耗时 1823ms，超过阈值」），错误率可能同步上升。QPS 通常不变或略降，区别于流量突增。

## 排查步骤
1. 用 query_metrics 查看 p95 与 dbRtMs 曲线，确认延迟抬升的起始时间点。
2. 用 search_logs 检索 SLOW_QUERY 关键字，定位具体 SQL 与耗时。
3. 用 get_recent_changes 核对抬升时间点前后是否有发布或 SQL 变更。
4. 分析 SQL：是否全表扫描（无索引 LIKE '%kw%'）、深分页（LIMIT 20000,10）、N+1 循环查询、大结果集。
5. EXPLAIN 验证执行计划，确认 type=ALL、rows 接近全表。

## 处置动作
紧急：对无索引字段查询执行限流（ENABLE_RATE_LIMIT）保护数据库；确认是注入故障时执行 RECOVER_FAULT。
根治：为 WHERE/ORDER BY 字段建索引；深分页改用游标（WHERE id > last_id）；大结果集改异步导出。

## 预防措施
上线前 SQL Review 强制 EXPLAIN；接入慢查询日志（long_query_time=500ms）；列表接口默认分页上限 100；定期巡检未命中索引的查询。
