# 构建阶段：只编译 ops-demo-service
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /build
COPY docker/maven-settings.xml /root/.m2/settings.xml
COPY pom.xml .
COPY ops-demo-service/pom.xml ops-demo-service/pom.xml
COPY ops-pilot-server/pom.xml ops-pilot-server/pom.xml
RUN mvn -B -q -pl ops-demo-service -am dependency:go-offline
COPY ops-demo-service/src ops-demo-service/src
RUN mvn -B -q -pl ops-demo-service -am package -DskipTests

# 运行阶段
FROM eclipse-temurin:17-jre-jammy
WORKDIR /app
ENV TZ=Asia/Shanghai
COPY --from=build /build/ops-demo-service/target/*.jar /app.jar
EXPOSE 8081
ENTRYPOINT ["java", "-jar", "/app.jar"]
