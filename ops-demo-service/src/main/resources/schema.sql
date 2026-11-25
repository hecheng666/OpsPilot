-- ops_demo 业务库 DDL（由 demo-service 启动时自动执行，scripts/init-db.sql 为同一份）
CREATE TABLE IF NOT EXISTS demo_order (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  order_no VARCHAR(32) NOT NULL,
  user_id BIGINT NOT NULL,
  amount DECIMAL(10,2) NOT NULL,
  status INT NOT NULL DEFAULT 0,
  created_at DATETIME NOT NULL,
  remark VARCHAR(500) NULL,
  KEY idx_status (status),
  KEY idx_order_no (order_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
