# 后端 Dockerfile — 运行时打包（Maven 容器内编译）
# 适用于：平台构建环境支持 Maven 的场景，无需手动打 jar 包
FROM maven:3.9-amazoncorretto-21
WORKDIR /app

# 复制 pom.xml 并单独下载依赖（利用 Docker 层缓存，加速重复构建）
COPY pom.xml .
COPY .mvn .mvn
COPY mvnw .
COPY mvnw.cmd .
RUN mvn dependency:go-offline -B -q || true

# 复制源码并构建
COPY src ./src
RUN mvn clean package -DskipTests -q

EXPOSE 8123

CMD ["java", "-jar", "/app/target/vio-ai-agent-0.0.1-SNAPSHOT.jar", "--spring.profiles.active=prod"]