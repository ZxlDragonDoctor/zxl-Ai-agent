# 使用预装 Maven 和 JDK21 的镜像
FROM maven:3.9-amazoncorretto-21 AS builder
WORKDIR /app

# 只复制必要的源代码和配置文件
COPY pom.xml .
COPY src ./src

# 使用 Maven 执行打包
RUN mvn clean package -DskipTests

# 运行阶段：使用精简 JRE 镜像，减小镜像体积
FROM amazoncorretto:21-alpine-jdk
WORKDIR /app

# 复制构建产物
COPY --from=builder /app/target/zxl-Ai-agent-0.0.1-SNAPSHOT.jar app.jar

# Render 通过 PORT 环境变量指定端口（默认 10000）
# Spring Boot 的 relaxed binding 会将 PORT 映射到 server.port
ENV PORT=10000
EXPOSE 10000

# JVM 内存优化：适配 Render 免费 512MB 限制
# -Xmx350m：最大堆 350MB，留出空间给 JVM 自身和线程栈
# -Xss256k：减小线程栈大小
# -XX:+UseContainerSupport：容器感知内存限制
# 使用 prod profile，敏感配置通过环境变量注入
CMD ["java", \
     "-Xmx350m", \
     "-Xss256k", \
     "-XX:+UseContainerSupport", \
     "-jar", "/app/app.jar", \
     "--spring.profiles.active=prod"]
