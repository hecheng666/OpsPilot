@echo off
rem 启动被观测订单服务（:8081）。首次启动会自动初始化 5 万行订单数据。
cd /d %~dp0..
java -jar ops-demo-service\target\ops-demo-service-1.0.0.jar
