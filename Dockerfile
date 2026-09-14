# syntax=docker/dockerfile:1
# ai-ilink 应用镜像：多阶段构建（Maven 打包 → JRE 运行）
#
# 为什么构建阶段跳过测试：测试需要 MySQL + Redis（见 .github/workflows/ci.yml 的服务容器），
# 镜像构建里起不了这些依赖；所以「构建镜像」和「跑测试」是两条独立的路，
# CI 里两条都跑（build 跑测试 + 产出评测报告，docker-build 只验证镜像能构建出来）。
#
# 依赖来源：全部可从 Maven Central 解析（含 wechat-ilink-sdk），所以全新环境的镜像构建不需要任何凭据。

# ---------- 构建阶段 ----------
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /workspace
COPY pom.xml .
COPY src ./src
# BuildKit 缓存挂到 ~/.m2：重复构建不会重新下载依赖（CI 里配合 cache-from / cache-to 用）
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -DskipTests package

# ---------- 运行阶段 ----------
FROM eclipse-temurin:17-jre
WORKDIR /app
# curl 只给容器 healthcheck 用（/api/health 需要 X-API-Token 头）
RUN apt-get update \
 && apt-get install -y --no-install-recommends curl \
 && rm -rf /var/lib/apt/lists/* \
 && useradd -r -u 10001 appuser
COPY --from=build /workspace/target/*.jar /app/app.jar
# 应用会把聊天记录/语音缓存写到 /app/data（compose 里挂卷持久化）
RUN mkdir -p /app/data && chown -R appuser:appuser /app
USER appuser
EXPOSE 8080
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -Dfile.encoding=UTF-8"
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
