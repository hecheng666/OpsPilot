-- OpsPilot 平台库全量 DDL（与 docs/plan.md 第 6 节一致，幂等）
CREATE TABLE IF NOT EXISTS service_instance (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  name VARCHAR(64) NOT NULL,
  base_url VARCHAR(255) NOT NULL,
  status VARCHAR(20) NOT NULL DEFAULT 'UP',
  last_heartbeat DATETIME NULL
);
CREATE TABLE IF NOT EXISTS metric_point (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  service VARCHAR(64) NOT NULL,
  metric VARCHAR(64) NOT NULL,
  value DOUBLE NOT NULL,
  ts DATETIME(3) NOT NULL,
  KEY idx_s_m_t (service, metric, ts)
);
CREATE TABLE IF NOT EXISTS log_entry (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  service VARCHAR(64) NOT NULL,
  level VARCHAR(10) NOT NULL,
  trace_id VARCHAR(64) NULL,
  pattern VARCHAR(128) NULL,
  message TEXT NOT NULL,
  ts DATETIME(3) NOT NULL,
  KEY idx_s_l_t (service, level, ts),
  KEY idx_trace (trace_id)
);
CREATE TABLE IF NOT EXISTS change_event (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  service VARCHAR(64) NOT NULL,
  type VARCHAR(20) NOT NULL,
  title VARCHAR(255) NOT NULL,
  detail TEXT NULL,
  operator VARCHAR(64) NULL,
  created_at DATETIME NOT NULL
);
CREATE TABLE IF NOT EXISTS runbook (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  title VARCHAR(200) NOT NULL,
  category VARCHAR(50) NOT NULL,
  content MEDIUMTEXT NOT NULL,
  updated_at DATETIME NOT NULL
);
CREATE TABLE IF NOT EXISTS runbook_chunk (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  runbook_id BIGINT NOT NULL,
  chunk_no INT NOT NULL,
  content TEXT NOT NULL,
  model VARCHAR(60) NULL,
  dim INT NULL,
  vector LONGTEXT NULL,
  KEY idx_runbook (runbook_id)
);
CREATE TABLE IF NOT EXISTS incident (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  service VARCHAR(64) NOT NULL,
  title VARCHAR(255) NOT NULL,
  severity VARCHAR(10) NOT NULL DEFAULT 'P2',
  status VARCHAR(20) NOT NULL DEFAULT 'OPEN',
  root_cause VARCHAR(255) NULL,
  report MEDIUMTEXT NULL,
  started_at DATETIME NOT NULL,
  resolved_at DATETIME NULL,
  created_at DATETIME NOT NULL
);
CREATE TABLE IF NOT EXISTS agent_run (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  incident_id BIGINT NULL,
  type VARCHAR(20) NOT NULL,
  input TEXT NOT NULL,
  status VARCHAR(20) NOT NULL,
  report MEDIUMTEXT NULL,
  prompt_tokens INT DEFAULT 0,
  completion_tokens INT DEFAULT 0,
  latency_ms INT DEFAULT 0,
  degraded TINYINT DEFAULT 0,
  created_at DATETIME NOT NULL,
  KEY idx_incident (incident_id)
);
CREATE TABLE IF NOT EXISTS agent_step (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  run_id BIGINT NOT NULL,
  step_no INT NOT NULL,
  type VARCHAR(20) NOT NULL,
  tool_name VARCHAR(64) NULL,
  args TEXT NULL,
  result MEDIUMTEXT NULL,
  evidence_id VARCHAR(40) NULL,
  status VARCHAR(20) NOT NULL,
  elapsed_ms INT DEFAULT 0,
  created_at DATETIME NOT NULL,
  KEY idx_run (run_id)
);
CREATE TABLE IF NOT EXISTS evidence (
  id VARCHAR(40) PRIMARY KEY,
  run_id BIGINT NOT NULL,
  source_type VARCHAR(16) NOT NULL,
  source_id VARCHAR(64) NULL,
  title VARCHAR(255) NOT NULL,
  summary VARCHAR(500) NULL,
  payload MEDIUMTEXT NULL,
  created_at DATETIME NOT NULL,
  KEY idx_run (run_id)
);
CREATE TABLE IF NOT EXISTS remediation_action (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  incident_id BIGINT NULL,
  run_id BIGINT NULL,
  action_type VARCHAR(40) NOT NULL,
  target VARCHAR(128) NULL,
  params TEXT NULL,
  risk_level VARCHAR(10) NOT NULL,
  reason VARCHAR(500) NULL,
  status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
  result VARCHAR(500) NULL,
  created_by VARCHAR(64) NOT NULL DEFAULT 'agent',
  created_at DATETIME NOT NULL,
  approved_by VARCHAR(64) NULL,
  approved_at DATETIME NULL,
  executed_at DATETIME NULL
);
CREATE TABLE IF NOT EXISTS audit_log (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  actor VARCHAR(64) NOT NULL,
  action VARCHAR(64) NOT NULL,
  target VARCHAR(128) NULL,
  detail TEXT NULL,
  created_at DATETIME NOT NULL
);
CREATE TABLE IF NOT EXISTS eval_scenario (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  name VARCHAR(100) NOT NULL,
  fault_type VARCHAR(40) NOT NULL,
  config TEXT NULL,
  expected_root_cause VARCHAR(255) NOT NULL,
  expected_action VARCHAR(64) NULL,
  enabled TINYINT DEFAULT 1
);
CREATE TABLE IF NOT EXISTS eval_run (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  baseline VARCHAR(20) NOT NULL,
  status VARCHAR(20) NOT NULL,
  total INT DEFAULT 0,
  hit_top1 INT DEFAULT 0,
  hit_top3 INT DEFAULT 0,
  avg_steps DOUBLE DEFAULT 0,
  avg_latency_ms DOUBLE DEFAULT 0,
  evidence_accuracy DOUBLE DEFAULT 0,
  report_completeness DOUBLE DEFAULT 0,
  started_at DATETIME NOT NULL,
  finished_at DATETIME NULL
);
CREATE TABLE IF NOT EXISTS eval_result (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  eval_run_id BIGINT NOT NULL,
  scenario_id BIGINT NOT NULL,
  detected_root_cause VARCHAR(255) NULL,
  hit_rank INT DEFAULT 0,
  steps INT DEFAULT 0,
  latency_ms INT DEFAULT 0,
  evidence_ok TINYINT DEFAULT 0,
  report_score DOUBLE DEFAULT 0,
  report MEDIUMTEXT NULL,
  KEY idx_run (eval_run_id)
);
