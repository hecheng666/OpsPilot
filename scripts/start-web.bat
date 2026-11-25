@echo off
rem 启动前端控制台（:5173）。首次需先执行 npm install
cd /d %~dp0..\ops-pilot-web
npx vite --port 5173
