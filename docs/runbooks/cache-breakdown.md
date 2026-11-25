---
title: 缓存穿透、击穿与雪崩
category: 缓存
---
# 缓存穿透、击穿与雪崩（缓存三兄弟）

## 典型症状
cacheHitRate 从 95% 跌至 60% 以下，dbRtMs 与 QPS 同步放大（回源 DB），日志出现大量 CACHE_MISS 模式（「缓存未命中，回源 DB 查询订单」）。P95 延迟上升但错误率不一定升高。

## 三者区分
穿透：查询不存在的数据，缓存与 DB 都不命中，恶意刷 id 时常见，CACHE_MISS 集中在新 key。
击穿：单个热点 key 过期瞬间，大量请求同时回源，DB QPS 尖刺。
雪崩：大量 key 同时过期或缓存集群宕机，命中率整体崩盘，DB 被打满。

## 排查步骤
1. query_metrics 看 cacheHitRate 与 dbRtMs 的拐点是否同步。
2. search_logs 统计 CACHE_MISS 的 key 分布：集中单 key 是击穿，均匀分布是雪崩，不存在 id 是穿透。
3. get_recent_changes 确认是否有批量清缓存或缓存配置变更。

## 处置动作
紧急：执行 CLEAR_CACHE 配合预热脚本重建缓存；DB 压力大时 ENABLE_RATE_LIMIT 限流兜底。
根治：穿透用布隆过滤器或空值缓存（TTL 60s）；击穿用互斥锁/逻辑过期；雪崩给 TTL 加随机抖动（基础值 ±20%）。

## 预防措施
核心缓存监控命中率告警阈值设 60%；热点 key 永不过期 + 异步刷新；缓存集群主从 + 哨兵。
