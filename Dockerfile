# 使用官方的 .NET 8 SDK 镜像进行构建
FROM mcr.microsoft.com/dotnet/sdk:8.0 AS build

# 设置工作目录
WORKDIR /app

# 将项目文件复制到容器中
COPY . ./

# 恢复项目依赖项
RUN dotnet restore

# 编译项目
RUN dotnet publish ./OnlineMsgServer.csproj -c Release -o out

# 使用更小的运行时镜像
FROM mcr.microsoft.com/dotnet/runtime:8.0 AS base

# 设置工作目录
WORKDIR /app

# 运行时安全配置默认值（可在 docker run 时覆盖）
ENV REQUIRE_WSS=false \
    MAX_CONNECTIONS=1000 \
    MAX_MESSAGE_BYTES=65536 \
    RATE_LIMIT_COUNT=30 \
    RATE_LIMIT_WINDOW_SECONDS=10 \
    IP_BLOCK_SECONDS=120 \
    CHALLENGE_TTL_SECONDS=120 \
    MAX_CLOCK_SKEW_SECONDS=60 \
    REPLAY_WINDOW_SECONDS=120

# 创建非 root 用户
RUN addgroup --system appgroup && adduser --system --ingroup appgroup appuser

# 暴露端口
EXPOSE 13173

# 从构建镜像复制发布的应用到当前镜像
COPY --from=build /app/out .

# 收敛运行权限
RUN chown -R appuser:appgroup /app
USER appuser

# 设置容器启动命令
ENTRYPOINT ["dotnet", "OnlineMsgServer.dll"]
