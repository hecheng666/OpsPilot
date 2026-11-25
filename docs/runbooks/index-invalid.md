---
title: 索引失效场景与修复
category: 数据库
---
# 索引失效场景与修复

## 典型症状
已有索引但查询仍全表扫描，dbRtMs 在数据量增长后缓慢爬升，某天突然超过 500ms；EXPLAIN 显示 key=NULL 或 type=ALL，rows 接近表行数。日志 SLOW_QUERY 出现但 SQL 看起来「明明有索引」。

## 高频失效场景
对列做函数/运算：WHERE UPPER(remark) LIKE、WHERE DATE(created_at)=；隐式类型转换：varchar 字段传数字；前导模糊：LIKE '%kw'；联合索引不满足最左前缀；OR 连接无索引列；索引列参与 != 或 NOT IN；优化器误判（数据倾斜时选错索引）。

## 排查步骤
1. search_logs 找到慢 SQL 原文，query_metrics 确认 dbRtMs 抬升时间点。
2. EXPLAIN 该 SQL，重点看 key、rows、Extra（Using filesort / Using temporary）。
3. 对照上表检查是否命中失效场景；ANALYZE TABLE 更新统计信息后重试。
4. get_recent_changes 确认最近是否有 DDL 变更（删索引、改字段类型）。

## 处置动作
紧急：FORCE INDEX 强制走正确索引止血；大表加索引用 gh-ost/在线 DDL 低峰执行。
根治：函数条件改写为范围查询或建函数索引；前导模糊改全文索引或 ES；联合索引按区分度排序；字段类型统一避免隐式转换。

## 预防措施
SQL 评审 checklist 内置失效场景；CI 集成 EXPLAIN 卡口（全表扫描拦截）；每季度核对索引使用率，清理冗余索引降低写放大。
