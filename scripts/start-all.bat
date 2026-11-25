@echo off
rem 一键启动三端：demo-service(8081) / ops-pilot-server(8090) / ops-pilot-web(5173)
rem 前置条件：MySQL 已启动、ops_pilot 与 ops_demo 已建库、两端 jar 已打包、前端已 npm install
cd /d %~dp0..

start "OpsPilot Demo :8081" cmd /k "java -jar ops-demo-service\target\ops-demo-service-1.0.0.jar"
echo 等待 demo-service 启动...
timeout /t 10 /nobreak >nul

start "OpsPilot Server :8090" cmd /k "java -jar ops-pilot-server\target\ops-pilot-server-1.0.0.jar"
echo 等待 ops-pilot-server 启动...
timeout /t 8 /nobreak >nul

start "OpsPilot Web :5173" cmd /k "cd ops-pilot-web && npm run dev"

echo.
echo 三端已启动：
echo   被观测服务  http://localhost:8081
echo   平台后端    http://localhost:8090
echo   控制台      http://localhost:5173
