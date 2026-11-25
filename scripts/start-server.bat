@echo off
rem 启动 OpsPilot 平台（:8090）。环境变量可覆盖：DB_USER / DB_PASSWORD / DEMO_BASE_URL / CHAT_MODEL
cd /d %~dp0..
java -jar ops-pilot-server\target\ops-pilot-server-1.0.0.jar
