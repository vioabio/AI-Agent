# Vue 前端 v1.0：npm ci 保证 lockfile 可复现。
FROM node:20-alpine AS build
WORKDIR /app

COPY package.json package-lock.json ./
RUN npm ci --ignore-scripts

COPY . .
RUN npm run build

# 非 root Nginx 运行时；该镜像默认监听 8080，并保留官方模板 envsubst
# entrypoint，避免为了注入后端代理密钥而回到 root 进程。
FROM nginxinc/nginx-unprivileged:1.27-alpine AS runtime
COPY --from=build /app/dist /usr/share/nginx/html
# Nginx entrypoint 在启动时把模板渲染为 default.conf；只替换
# VIO_BACKEND_API_KEY，避免把其他 Nginx 变量误替换为空。
COPY nginx.conf.template /etc/nginx/templates/default.conf.template

EXPOSE 8080

# unprivileged alpine 内置 BusyBox wget，避免依赖未安装的 curl。
HEALTHCHECK --interval=15s --timeout=5s --start-period=10s --retries=3 \
  CMD wget --quiet --spider http://127.0.0.1:8080/ || exit 1

CMD ["nginx", "-g", "daemon off;"]
