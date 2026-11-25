---
title: GC 频繁与停顿排查
category: JVM
---
# GC 频繁与 STW 停顿

## 典型症状
接口 P99 毛刺明显但 P50 正常，呈周期性抖动；errorRate 偶发尖刺；jstat 显示 YGC 频率 >1 次/秒或 FGC 次数持续增长，单次 Full GC 停顿超过 1s。CPU 使用率同步偏高（GC 线程占用）。

## 常见原因
堆内存不足或新生代过小；内存泄漏导致老年代持续增长；大对象直接进老年代（大结果集查询、大缓存 value）；元空间或 humongous 对象（G1）问题。

## 排查步骤
1. query_metrics 看 p99 与 p50 的差值是否拉大，毛刺是否周期性。
2. jstat -gcutil pid 1s 观察各代使用率与 GC 频次；jmap -histo 找大对象。
3. 关联业务峰值：大查询、批量任务、缓存加载时段是否与毛刺对齐。
4. 开启 GC 日志（-Xlog:gc*）分析停顿原因（Allocation Failure / Metadata / Humongous）。

## 处置动作
紧急：流量大时先扩容实例分摊；确认内存泄漏迹象（老年代只涨不跌）则摘流量重启该实例并保留 dump。
根治：堆从 2G 调到 4G 并固定 Xms=Xmx；大结果集查询改流式/分页；缓存大 value 拆分或压缩；升级 G1 并设 MaxGCPauseMillis=200。

## 预防措施
GC 频次与停顿接入监控（YGC>1/s、FGC>0.1/h 告警）；发布前做内存基准；禁止在请求链路加载全表数据。
