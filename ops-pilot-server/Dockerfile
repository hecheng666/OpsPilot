# 构建阶段：只编译 ops-pilot-server
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /build
COPY docker/maven-settings.xml /root/.m2/settings.xml
COPY pom.xml .
COPY ops-demo-service/pom.xml ops-demo-service/pom.xml
COPY ops-pilot-server/pom.xml ops-pilot-server/pom.xml
RUN mvn -B -q -pl ops-pilot-server -am dependency:go-offline
COPY ops-pilot-server/src ops-pilot-server/src
RUN mvn -B -q -pl ops-pilot-server -am package -DskipTests

# 运行阶段：带 runbook 目录（启动时切块并向量化）
FROM eclipse-temurin:17-jre-jammy
WORKDIR /app
ENV TZ=Asia/Shanghai
COPY --from=build /build/ops-pilot-server/target/*.jar app.jar
COPY docs/runbooks ./docs/runbooks
EXPOSE 8090
ENTRYPOINT ["java", "-jar", "/app.jar"]
