# VIO AI Agent v1.0：可复现的多阶段镜像
#
# 构建阶段使用固定 major/minor 的 Maven + JDK 21；运行阶段只携带
# Spring Boot fat-jar，并以非 root 用户运行。发布流水线还会记录最终
# image digest，不能把 tag 当成不可变发布凭据。

FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /workspace

# 先复制依赖描述，尽量复用 Maven 缓存层。
COPY pom.xml ./
COPY .mvn ./.mvn
COPY mvnw ./mvnw
RUN mvn -B -ntp dependency:go-offline

# 生产镜像只需要主源码。不能复制整个 src：Docker build context 不遵循
# .gitignore，本地 application-local.yml / mcp-servers.json 即使未被 Git
# 跟踪，也可能被带入构建层并永久留在镜像历史中。
COPY src/main ./src/main
# pom.xml packages these frozen server rules from outside src/main.
# Copy only the two declared public inputs, never evaluation runs or local data.
COPY evaluation/v1.1/manifest.json evaluation/v1.1/metric-dictionary.json ./evaluation/v1.1/
RUN mvn -B -ntp -DskipTests package \
    && cp target/vio-ai-agent-*.jar /workspace/app.jar \
    && jar tf /workspace/app.jar > /workspace/jar-entries.txt \
    && grep -Fxq 'BOOT-INF/classes/evaluation/v1.1/manifest.json' /workspace/jar-entries.txt \
    && grep -Fxq 'BOOT-INF/classes/evaluation/v1.1/metric-dictionary.json' /workspace/jar-entries.txt

FROM eclipse-temurin:21-jre-jammy AS runtime
WORKDIR /app

# curl 用于镜像内健康探针；不保留 apt 缓存。
RUN apt-get update \
    && apt-get install --no-install-recommends -y curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --system --gid 10001 vio \
    && useradd --system --uid 10001 --gid 10001 --home-dir /nonexistent --shell /usr/sbin/nologin vio \
    && mkdir -p /app/tmp /app/logs \
    && chown -R 10001:10001 /app

COPY --from=build --chown=10001:10001 /workspace/app.jar /app/app.jar

ENV JAVA_OPTS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0 -Djava.security.egd=file:/dev/urandom"
EXPOSE 8123

USER 10001:10001

HEALTHCHECK --interval=15s --timeout=5s --start-period=45s --retries=5 \
  CMD curl --fail --silent http://127.0.0.1:8123/api/actuator/health/readiness || exit 1

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar --spring.profiles.active=${SPRING_PROFILES_ACTIVE:-prod}"]
