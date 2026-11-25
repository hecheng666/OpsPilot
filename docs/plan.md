# OpsPilot 项目计划方案（实施依据）

## 0. 背景与目标

面向 Spring Boot 服务的 AI 故障排查与值班 Copilot。Demo 服务可注入真实故障；OpsPilot Agent 通过工具调用完成「发现异常 → 收集证据 → 推断根因 → 提出处置 → 人工审批 → 复盘报告」闭环，全程可回放、可评测。

目标岗位：AI Agent 开发 / Java 后端。核心卖点：工具链 + 证据链 + 权限审批 + 20 场景评测。

## 1. 范围与边界

做：单 Demo 服务、4 类故障注入、指标/日志采集、阈值告警、8 个 Agent 工具、ReAct 循环、SSE、结构化报告、审批审计、20 场景评测、Vue 控制台。

不做：K8s、Prometheus/Grafana、ELK、真实分布式追踪（用 traceId 模拟）、多租户、任意 SQL/Shell 执行。

## 2. 技术选型与环境

- 后端：Java 17、Spring Boot 3.5.7、MyBatis-Plus 3.5.5、MySQL 8、Lombok、jjwt（如需登录可后置）
- 前端：Vue 3.5、Vite、Element Plus、ECharts、Pinia、vue-router、marked、axios
- 模型：Ollama。向量 `bge-m3`（已装）；对话 `qwen2.5:7b`（默认，env 可覆盖；机器吃力换 `qwen2.5:3b`）
- 已验证环境：Windows、Java 17、Maven 3.9.4（阿里云镜像可用）、Node 26、MySQL 3306 在跑、Ollama 11434 在跑、Docker 不可用
- MySQL 默认 `root/123456`（未验证，连接失败时换密码；均可用环境变量覆盖）
- 不用 Docker；提供 `.bat` 启动脚本
- Maven 控制台为 GBK，中文报错会乱码，属正常现象

## 3. 工程结构

```
ops-pilot/
├── pom.xml                      # parent，聚合两个模块
├── ops-demo-service/            # :8081 被观测订单服务 + 故障注入
├── ops-pilot-server/            # :8090 Agent 平台
├── ops-pilot-web/               # :5173 Vue 控制台
├── scripts/                     # start-demo.bat / start-server.bat / start-web.bat / init-db.sql
└── docs/                        # plan.md、runbooks（10 篇 md）
```

数据库：`ops_pilot`（平台）、`ops_demo`（订单数据，5 万行）。

## 4. Demo Service 设计（:8081）

业务接口：
- `GET /api/orders/{id}`、`GET /api/orders?page=&size=`（列表接口是慢 SQL 的载体）
- 每个请求经过一个专用线程池 `orderQueryExecutor`（core 8 / max 16 / queue 50），线程池指标由此暴露

故障注入（内存态 `FaultManager`，注入/恢复均为幂等）：
| 故障 | 实现 |
|---|---|
| `SLOW_SQL` | 列表查询切换为无索引 `remark LIKE '%kw%'` 或深分页 `LIMIT 20000,10`；附 DB RT 上升 |
| `CACHE_BREAKDOWN` | 模拟缓存层（Caffeine）命中率从 95% 掉到 30%，回源 DB 放大 QPS |
| `THREAD_POOL_EXHAUSTION` | 订单任务 sleep 变长占满线程池，active/queue 上升、rejected 出现 |
| `DOWNSTREAM_TIMEOUT` | 调用内部 mock 下游接口 `Thread.sleep(3000)`，错误率与 P95 上升 |

内部接口（仅本机调用）：
- `POST /internal/faults/inject {type, intensity, durationSeconds}` / `POST /internal/faults/recover {type?}`
- `GET /internal/faults/status`
- `GET /internal/metrics` → 当前窗口聚合：qps、p50/p95/p99、errorRate、dbRtMs、cacheHitRate、threadActive/threadQueue/threadRejected、downstreamRtMs
- `GET /internal/logs?sinceId=` → 结构化日志（id、level、traceId、pattern、message、ts），内存环形缓冲 5000 条
- `GET /internal/changes` → 预置 + 运行时追加的变更事件
- `POST /internal/actions/execute {actionType, params}` → 处置动作：`RECOVER_FAULT`/`ENABLE_RATE_LIMIT`/`CLEAR_CACHE`/`ROLLBACK_CONFIG`/`SCALE_THREAD_POOL`（模拟生效）
- `GET /actuator/health`

指标由请求过滤器 + Micrometer Timer/Counter + 定时采样器生成；注入故障时按参数劣化。日志用中文写真实运维风格的 message（如「订单列表查询耗时 1823ms，超过阈值」「缓存命中率降至 31%」）。

## 5. OpsPilot Server 设计（:8090）

### 5.1 采集与告警
- `MetricCollector`：每 5s 拉 `/internal/metrics`，写入 `metric_point`（每个指标一行）
- `LogCollector`：每 5s 拉 `/internal/logs?sinceId=`，按自增 id 去重落 `log_entry`
- `ChangeCollector`：每 30s 同步变更事件
- `AlertEvaluator`：每 10s 评估最近 1 分钟窗口：p95>800ms、errorRate>5%、threadActive/max>80%、cacheHitRate<60%、dbRt>500ms。命中 → 创建/合并 `incident`（同服务同特征 10 分钟内合并），状态 OPEN
- Demo Service 掉线：连续 3 次采集失败 → 服务 DOWN 告警

### 5.2 Runbook 检索
- `docs/runbooks/` 10 篇中文 md（慢 SQL、缓存三兄弟、线程池、下游超时、GC、连接池、索引失效、限流降级、灰度回滚、告警治理）
- 启动时按 300-500 字切 chunk，bge-m3 向量存 `runbook_chunk.vector`（JSON）
- 检索：关键词（CJK bigram + 拉丁词）+ 向量余弦，RRF(k=60) 融合，返回 topK

### 5.3 Agent Runtime（自研，简历要点）
- `Tool` 接口：name/description/parametersSchema/riskLevel/execute(args)
- 8 个工具：

| 工具 | 说明 | 风险 |
|---|---|---|
| query_metrics(service, metric, windowMinutes≤60) | 序列 + 均值/峰值/环比 + anomalyScore | READ |
| search_logs(service, keyword?, level?, windowMinutes, limit≤200) | 按 pattern 聚合 + 样本 traceId | READ |
| get_recent_changes(service, hours≤72) | 发布/配置/SQL 变更 | READ |
| get_service_topology(service) | 依赖 + 下游健康 | READ |
| search_runbooks(query, topK≤6) | RRF 片段 + 引用 | READ |
| get_incident_context(incidentId) | 告警 + 指标摘要 + 故障状态 | READ |
| propose_remediation(incidentId, actionType, target, params, reason, riskLevel) | 创建 PENDING 动作 | WRITE |
| execute_remediation(actionId) | 仅 APPROVED 可执行，调用 demo 内部接口，写审计 | CRITICAL |

- ReAct 循环：最多 6 步；每步存 `agent_step`；工具结果生成 `evidence`（E-xxxx）
- 模型不调工具 → 自动执行基线排查（metrics → logs → changes → runbooks）后让模型只做综合；Ollama 不可用 → 规则引擎出报告，`degraded=1`
- 报告 JSON 契约（模型最终输出，代码兜底解析）：

```json
{
  "summary": "现象一句话",
  "root_cause": {"title": "...", "confidence": 0.0, "evidence_ids": ["E-..."]},
  "alternatives": [{"title": "...", "confidence": 0.0, "evidence_ids": []}],
  "impact": "...",
  "actions": [{"actionId": 0, "risk": "HIGH", "status": "PENDING_APPROVAL"}],
  "prevention": ["..."]
}
```

### 5.4 审批与审计
- `propose_remediation` 只创建 PENDING；前端批准后 `execute_remediation` 才生效
- 审批人/时间/参数/结果写 `audit_log`；Agent 永不自批

### 5.5 评测
- `eval_scenario` 预置 20 条（见第 8 节），含 faultType/config/expectedRootCause/expectedAction
- 评测流程：recover 全部故障 → 注入场景故障 → 等待 2 个采集窗口 → 跑 Agent → 评分 → 恢复
- 基线：`RULE`（阈值+关键词）、`RAG_ONLY`（Runbook 检索 + 单轮 LLM）、`FULL_AGENT`
- 指标：Top-1/Top-3 命中率、平均步数、平均耗时、证据准确率、报告完整率、未授权写次数（应为 0）
- 支持抽样跑（scenarioIds 子集）与完整跑

## 6. 数据库 DDL（核心表）

```sql
CREATE DATABASE IF NOT EXISTS ops_pilot DEFAULT CHARSET utf8mb4;
CREATE DATABASE IF NOT EXISTS ops_demo DEFAULT CHARSET utf8mb4;
USE ops_pilot;

CREATE TABLE IF NOT EXISTS service_instance (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  name VARCHAR(64) NOT NULL, base_url VARCHAR(255) NOT NULL,
  status VARCHAR(20) NOT NULL DEFAULT 'UP', last_heartbeat DATETIME NULL
);
CREATE TABLE IF NOT EXISTS metric_point (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  service VARCHAR(64) NOT NULL, metric VARCHAR(64) NOT NULL,
  value DOUBLE NOT NULL, ts DATETIME(3) NOT NULL,
  KEY idx_s_m_t (service, metric, ts)
);
CREATE TABLE IF NOT EXISTS log_entry (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  service VARCHAR(64) NOT NULL, level VARCHAR(10) NOT NULL,
  trace_id VARCHAR(64) NULL, pattern VARCHAR(128) NULL,
  message TEXT NOT NULL, ts DATETIME(3) NOT NULL,
  KEY idx_s_l_t (service, level, ts), KEY idx_trace (trace_id)
);
CREATE TABLE IF NOT EXISTS change_event (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  service VARCHAR(64) NOT NULL, type VARCHAR(20) NOT NULL,
  title VARCHAR(255) NOT NULL, detail TEXT NULL, operator VARCHAR(64) NULL,
  created_at DATETIME NOT NULL
);
CREATE TABLE IF NOT EXISTS runbook (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  title VARCHAR(200) NOT NULL, category VARCHAR(50) NOT NULL,
  content MEDIUMTEXT NOT NULL, updated_at DATETIME NOT NULL
);
CREATE TABLE IF NOT EXISTS runbook_chunk (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  runbook_id BIGINT NOT NULL, chunk_no INT NOT NULL,
  content TEXT NOT NULL, model VARCHAR(60) NULL, dim INT NULL, vector LONGTEXT NULL,
  KEY idx_runbook (runbook_id)
);
CREATE TABLE IF NOT EXISTS incident (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  service VARCHAR(64) NOT NULL, title VARCHAR(255) NOT NULL,
  severity VARCHAR(10) NOT NULL DEFAULT 'P2',
  status VARCHAR(20) NOT NULL DEFAULT 'OPEN',
  root_cause VARCHAR(255) NULL, report MEDIUMTEXT NULL,
  started_at DATETIME NOT NULL, resolved_at DATETIME NULL, created_at DATETIME NOT NULL
);
CREATE TABLE IF NOT EXISTS agent_run (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  incident_id BIGINT NULL, type VARCHAR(20) NOT NULL,
  input TEXT NOT NULL, status VARCHAR(20) NOT NULL,
  report MEDIUMTEXT NULL, prompt_tokens INT DEFAULT 0, completion_tokens INT DEFAULT 0,
  latency_ms INT DEFAULT 0, degraded TINYINT DEFAULT 0, created_at DATETIME NOT NULL,
  KEY idx_incident (incident_id)
);
CREATE TABLE IF NOT EXISTS agent_step (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  run_id BIGINT NOT NULL, step_no INT NOT NULL,
  type VARCHAR(20) NOT NULL, tool_name VARCHAR(64) NULL,
  args TEXT NULL, result MEDIUMTEXT NULL, evidence_id VARCHAR(40) NULL,
  status VARCHAR(20) NOT NULL, elapsed_ms INT DEFAULT 0, created_at DATETIME NOT NULL,
  KEY idx_run (run_id)
);
CREATE TABLE IF NOT EXISTS evidence (
  id VARCHAR(40) PRIMARY KEY,
  run_id BIGINT NOT NULL, source_type VARCHAR(16) NOT NULL,
  source_id VARCHAR(64) NULL, title VARCHAR(255) NOT NULL,
  summary VARCHAR(500) NULL, payload MEDIUMTEXT NULL, created_at DATETIME NOT NULL,
  KEY idx_run (run_id)
);
CREATE TABLE IF NOT EXISTS remediation_action (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  incident_id BIGINT NULL, run_id BIGINT NULL,
  action_type VARCHAR(40) NOT NULL, target VARCHAR(128) NULL, params TEXT NULL,
  risk_level VARCHAR(10) NOT NULL, reason VARCHAR(500) NULL,
  status VARCHAR(20) NOT NULL DEFAULT 'PENDING', result VARCHAR(500) NULL,
  created_by VARCHAR(64) NOT NULL DEFAULT 'agent', created_at DATETIME NOT NULL,
  approved_by VARCHAR(64) NULL, approved_at DATETIME NULL, executed_at DATETIME NULL
);
CREATE TABLE IF NOT EXISTS audit_log (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  actor VARCHAR(64) NOT NULL, action VARCHAR(64) NOT NULL,
  target VARCHAR(128) NULL, detail TEXT NULL, created_at DATETIME NOT NULL
);
CREATE TABLE IF NOT EXISTS eval_scenario (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  name VARCHAR(100) NOT NULL, fault_type VARCHAR(40) NOT NULL,
  config TEXT NULL, expected_root_cause VARCHAR(255) NOT NULL,
  expected_action VARCHAR(64) NULL, enabled TINYINT DEFAULT 1
);
CREATE TABLE IF NOT EXISTS eval_run (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  baseline VARCHAR(20) NOT NULL, status VARCHAR(20) NOT NULL,
  total INT DEFAULT 0, hit_top1 INT DEFAULT 0, hit_top3 INT DEFAULT 0,
  avg_steps DOUBLE DEFAULT 0, avg_latency_ms DOUBLE DEFAULT 0,
  evidence_accuracy DOUBLE DEFAULT 0, report_completeness DOUBLE DEFAULT 0,
  started_at DATETIME NOT NULL, finished_at DATETIME NULL
);
CREATE TABLE IF NOT EXISTS eval_result (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  eval_run_id BIGINT NOT NULL, scenario_id BIGINT NOT NULL,
  detected_root_cause VARCHAR(255) NULL, hit_rank INT DEFAULT 0,
  steps INT DEFAULT 0, latency_ms INT DEFAULT 0,
  evidence_ok TINYINT DEFAULT 0, report_score DOUBLE DEFAULT 0,
  report MEDIUMTEXT NULL, KEY idx_run (eval_run_id)
);
```

`ops_demo.demo_order`：id、order_no、user_id、amount、status、created_at、remark(500)；5 万行，索引只建在 status/order_no，remark 无索引（慢 SQL 素材）。

## 7. API 契约（:8090）

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | /api/faults/inject | {type, intensity, durationSeconds} 转发 demo |
| POST | /api/faults/recover | {type?} |
| GET | /api/faults/status | 当前故障 |
| GET | /api/overview | KPI 卡片 + 时序 + 活跃事故 + 服务状态 |
| GET | /api/metrics/query?service&metric&windowMinutes | 时序数据 |
| GET | /api/logs/search?service&level&keyword&windowMinutes&limit | 日志检索 |
| GET | /api/incidents?page&size&status | 事故分页 |
| GET | /api/incidents/{id} | 详情：时间线 + 证据 + 报告 + 动作 |
| POST | /api/agent/investigate/stream | SSE，body {incidentId? 或 question} |
| GET | /api/agent/runs/{id} | trace 回放（run + steps + evidences） |
| POST | /api/actions/{id}/approve | 审批并执行 |
| POST | /api/actions/{id}/reject | 驳回 |
| GET | /api/actions?status=PENDING | 待审批列表 |
| GET | /api/audit?page&size | 审计日志 |
| GET | /api/runbooks | 知识库列表 |
| POST | /api/eval/run | {baseline, scenarioIds?} |
| GET | /api/eval/runs / /api/eval/runs/{id} | 评测列表/详情 |
| GET | /api/agent/status | Ollama/采集状态 |

SSE 事件：`run` `step` `evidence` `delta`(phase=report) `action` `report` `done` `error`。前端用 fetch + ReadableStream 解析（POST SSE）。

## 8. 评测场景清单（20 条）

慢 SQL：缺索引、深分页、N+1、锁等待、大结果集
缓存：热点 key 过期、批量过期、穿透、大 key、集群切换
线程池：下游超时、CPU 飙高、锁竞争、GC、队列满
下游：超时、5xx、DNS、连接池耗尽、限流

`expected_root_cause` 用关键词数组评分（如 ["索引","慢查询","深分页"]），命中其一即算；语义近似后续可加 LLM 评委。

## 9. 前端（ops-pilot-web）

深色运维控制台主题：背景 `#0f1216`，面板 `#171c22`，主色 `#4fd1c5`，警告 `#f59e0b`，危险 `#f87171`，等宽数字（"JetBrains Mono", Consolas）。Element Plus dark 模式。

页面：
1. 总览 `/`：KPI 卡片（QPS/P95/错误率/线程池）、ECharts 时序、活跃事故横幅、当前故障标签
2. 故障演练 `/faults`：4 故障卡片，注入/恢复、强度与时长、效果说明、实时指标预览
3. 事故中心 `/incidents`、`/incidents/:id`：时间线、证据卡片、Agent 报告、待审批动作（批准/驳回）
4. Agent 排查 `/agent`：对话输入 + SSE 实时步骤 + 证据 + 报告渲染
5. 评测中心 `/eval`：选基线/场景、进度、结果表格 + 对比柱状图、导出 JSON

组件：MetricCard、TimeSeriesChart、StepTimeline、EvidenceCard、ReportView、ApprovalDialog。

## 10. 分阶段与验收

| 阶段 | 内容 | 验收 |
|---|---|---|
| P0 | 多模块骨架、DDL、demo 4 故障、采集、告警、总览页 | 注入任一故障，指标在 2 个采集窗口内异常可见，可恢复 |
| P1 | 5 个只读工具、10 篇 Runbook 入库、混合检索、证据模型 | 工具单测/接口可调用，检索有引用 |
| P2 | ReAct、SSE、结构化报告、trace、降级 | 慢 SQL 场景 Top-1 命中，报告引用 ≥2 证据 |
| P3 | 审批/审计、20 场景、3 基线、评测中心 | 未授权写为 0，可导出评测报告 |
| P4 | 启动脚本、README、截图、简历条目 | 一条命令起三端，演示全流程 |

## 11. 风险与对策

- 7B 工具调用不稳 → schema 精简、提示词给 few-shot、≤6 步、自动基线排查、规则降级
- 故障不可复现 → 固定 5 万行数据、固定种子、独立线程池、采样窗口平滑
- 无 Redis → Caffeine 模拟缓存层，接口与指标不变，README 注明可替换
- 评测慢 → 抽样跑 + 完整跑离线，结果落库
- 范围膨胀 → 冻结第 1 节边界

## 12. 简历条目（2026-11-25 实测）

> 实测环境：Ollama `qwen2.5:3b`，3 轮 20 场景全量复跑，Top-1 均为 19/20；最新一轮 Top-3 20/20、0 条降级；未命中在边界样本间波动。

1. 设计并实现 OpsPilot 故障排查 Agent：ReAct 多步循环 + 8 个运维工具（指标/日志/变更/拓扑/Runbook/处置/执行），输出带证据链的结构化根因报告；20 场景 Top-1 命中率 95%（19/20）、Top-3 100%，平均 5.7 步、7.6s，证据准确率与报告完整率 100%。
2. 建立工具权限分级与人工审批机制（READ/WRITE/CRITICAL），危险动作审批后执行并全量审计，20 场景 × 3 基线评测中未授权写操作 0 次。
3. 搭建指标/日志采集、4 类故障注入演练台与可观测性看板（Actuator/Micrometer + MySQL + ECharts）。
4. 自研轻量 Agent Runtime：工具注册、参数校验、最大步数/超时、SSE 流式、trace 回放与模型降级。
5. 建立评测体系：20 场景 × 3 组基线（规则/RAG/全工具），Top-1 由 RAG_ONLY 的 45%（9/20）提升至全工具 95%（19/20），Top-3 由 90% 提升至 100%。

## 13. 开工检查清单

- [ ] `ollama pull qwen2.5:7b`（或 3b）后台执行
- [ ] MySQL 连通（root/123456，失败换密码，env 可覆盖）
- [ ] 创建 `ops_pilot`、`ops_demo`
- [ ] 三端可启动：8081 / 8090 / 5173
