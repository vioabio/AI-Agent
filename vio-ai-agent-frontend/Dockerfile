# 前端 Dockerfile — 多阶段构建
# Stage 1: Node 编译 Vue 项目
FROM node:20-alpine AS build
WORKDIR /app
COPY . .
RUN npm install
RUN npm run build

# Stage 2: Nginx 托管静态文件
FROM nginx:alpine

# 复制编译产物
COPY --from=build /app/dist /usr/share/nginx/html

# 复制 Nginx 配置
COPY nginx.conf /etc/nginx/conf.d/default.conf

EXPOSE 80

CMD ["nginx", "-g", "daemon off;"]