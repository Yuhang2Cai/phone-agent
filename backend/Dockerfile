# 构建只使用交付源码，测试由独立验收另行实际执行，不携带开发数据库。
FROM maven:3.9.11-eclipse-temurin-17 AS build
WORKDIR /build/backend
COPY backend/pom.xml ./
COPY backend/src ./src
# Maven从仓库唯一contracts目录打包，不能仅拷贝backend而遗漏权威协议。
COPY contracts /build/contracts
# 缓存仅含公开Maven依赖，下载中断可定向重试，不依赖宿主私有.m2设置。
RUN --mount=type=cache,target=/root/.m2 mvn -B -Dmaven.wagon.http.retryHandler.count=5 -DskipTests package
FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /build/backend/target/*.jar ./backend.jar
RUN useradd --create-home backend
USER backend
EXPOSE 8080
# 城市目录读取实际数据库；状态不是200时容器不健康，不用仅探测TCP代替。
HEALTHCHECK --interval=15s --timeout=5s --start-period=90s CMD bash -c 'exec 3<>/dev/tcp/127.0.0.1/8080; printf "GET /api/stores/cities HTTP/1.0\r\nHost: localhost\r\n\r\n" >&3; head -1 <&3 | grep "200"'
CMD ["java", "-jar", "backend.jar"]
