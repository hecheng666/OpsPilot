---
title: 数据库连接池耗尽
category: 数据库
---
# 数据库连接池耗尽（Connection Pool Exhaustion）

## 典型症状
接口大面积超时，日志出现「Could not get JDBC Connection」或「Connection is not available, request timed out after 30000ms」；dbRtMs 飙高但数据库本身 CPU 不高；active 连接数等于 maxPoolSize 且持续不降。

## 常见原因
慢 SQL 长时间占住连接（最常见）；连接泄漏（未关闭 ResultSet/Statement）；事务过长（大事务包住 RPC 调用）；池子配小了对不上并发；下游 DB 网络抖动导致连接存活时间变长。

## 排查步骤
1. query_metrics 看 dbRtMs 与 errorRate，区分是 DB 慢还是池子满。
2. search_logs 查 connection timeout 关键字，统计出现频率与起始时间。
3. 查数据库 PROCESSLIST：大量 Sleep 连接是泄漏，大量 Query 是慢 SQL。
4. jstack 看线程是否都阻塞在 getConnection。

## 处置动作
紧急：kill 掉数据库侧的长时间 Sleep/查询会话；服务侧摘流量重启释放泄漏连接；入口 ENABLE_RATE_LIMIT 保护。
根治：慢 SQL 治理见「慢 SQL 排查」；连接池 maximumPoolSize 按 DB 承载力设置（通常 CPU 核数×2 起）；开启 leakDetectionThreshold=30000 定位泄漏点；事务内禁止 RPC。

## 预防措施
连接池 active/idle/wait 指标全部监控，wait>0 持续 1 分钟告警；代码评审检查 try-with-resources；定期演练 DB 慢查询下的池耗尽场景。
