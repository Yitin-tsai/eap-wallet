-- 初始化測試資料庫
CREATE SCHEMA IF NOT EXISTS wallet_service;
GRANT ALL PRIVILEGES ON SCHEMA wallet_service TO test;
GRANT ALL PRIVILEGES ON ALL TABLES IN SCHEMA wallet_service TO test;
GRANT ALL PRIVILEGES ON ALL SEQUENCES IN SCHEMA wallet_service TO test;

-- 設定搜尋路徑
ALTER USER test SET search_path TO wallet_service, public;
