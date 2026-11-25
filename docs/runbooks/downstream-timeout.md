---
title: 下游服务超时
category: 依赖
---
# 下游服务超时（Downstream Timeout）

## 典型症状
downstreamRtMs 从个位数毫秒涨到数百甚至数千毫秒，errorRate 超过 5%，日志出现 DOWNSTREAM_SLOW（「调用库存服务耗时…接近超时阈值」）与 DOWNSTREAM_ERROR（「connection read timeout，触发熔断计数」）。本服务 P95 被下游拖长，线程池占用率上升。

## 排查步骤
1. query_metrics 对比 downstreamRtMs 与 p95 曲线，确认延迟由下游贡献而非本服务逻辑。
2. search_logs 按 DOWNSTREAM_ERROR 聚合，看错误率与目标服务名。
3. get_service_topology 确认依赖链，联系下游值班同学核对对端容量与发布。
4. 检查本服务超时配置是否过长（超时 > 上游重试间隔会放大风暴）。

## 处置动作
紧急：对下游开启熔断/降级（返回兜底数据）；入口 ENABLE_RATE_LIMIT 防止线程池被拖垮；确认是演练故障用 RECOVER_FAULT 恢复。
根治：超时时间按下游 P99 的 1.5 倍设置；重试必须幂等且最多 2 次并带抖动；核心链路准备降级预案（缓存兜底、默认值兜底）。

## 预防措施
每个下游依赖登记 Owner 与 SLA；熔断器（Resilience4j）阈值:errorRate>50% 持续 10s 触发；定期混沌演练下游慢调用。
