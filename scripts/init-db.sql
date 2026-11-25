-- OpsPilot 一键初始化 SQL（也可由两个服务启动时自动建表，此脚本用于手动初始化/重置）
CREATE DATABASE IF NOT EXISTS ops_pilot DEFAULT CHARSET utf8mb4;
CREATE DATABASE IF NOT EXISTS ops_demo DEFAULT CHARSET utf8mb4;

USE ops_demo;
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

USE ops_pilot;
-- 平台各表结构与 ops-pilot-server/src/main/resources/schema.sql 一致，启动时自动创建（幂等）。
-- 本脚本仅确保两个数据库存在；表结构以服务内 schema.sql 为准。
