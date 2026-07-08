# ============================================================
# 后端 Dockerfile — 预打包模式
#
# 使用前先构建 JAR：
#   ./mvnw clean package -DskipTests
#
# 构建镜像：
#   docker build -t vio-ai-agent .
#
# 运行容器：
#   docker run -d -p 8123:8123 -e AI_DASHSCOPE_API_KEY=your-key --name vio-ai-agent vio-ai-agent
# ============================================================
FROM eclipse-temurin:21-jre

WORKDIR /app

# 复制预构建的 JAR（通配符匹配版本号）
COPY target/vio-ai-agent-*.jar app.jar

EXPOSE 8123

CMD ["java", "-jar", "app.jar", "--spring.profiles.active=prod"]
