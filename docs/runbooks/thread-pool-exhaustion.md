---
title: 线程池打满排查
category: 并发
---
# 线程池打满（Thread Pool Exhaustion）

## 典型症状
threadActive 达到 threadMax 的 80% 以上并持续，threadQueue 排队任务数上涨，出现 threadRejected 拒绝计数，接口 P95 与错误率同步上升。请求并非变慢而是排队等待，严重时直接失败。

## 常见原因
下游依赖变慢拖住工作线程（最常见）；任务本身耗时变长（慢 SQL、大计算）；线程池参数过小；突发流量超过池容量；死锁导致线程永久占用。

## 排查步骤
1. query_metrics 看 threadActive、threadQueue、threadRejected 三个指标的时序，确认是渐进打满还是瞬时打满。
2. search_logs 查是否有 RejectedExecutionException 或任务超时日志。
3. 关联下游指标 downstreamRtMs：下游慢 → 线程被占住 → 池打满，这是经典传导链。
4. jstack 抓线程快照，看工作线程都阻塞在哪个方法。

## 处置动作
紧急：执行 SCALE_THREAD_POOL 临时扩容（max 翻倍、queue 适当加大），同时对入口 ENABLE_RATE_LIMIT 限流；下游故障时先熔断下游。
根治：按下游 RT 设置合理超时与池大小（线程数 ≈ QPS × RT）；核心与非核心接口隔离线程池（Bulkhead）；拒绝策略改为 CallerRuns 或落 MQ 削峰。

## 预防措施
线程池指标全部接入监控，active/max>80% 告警；定期压测确定容量水位；禁止在公共池提交无超时任务。
