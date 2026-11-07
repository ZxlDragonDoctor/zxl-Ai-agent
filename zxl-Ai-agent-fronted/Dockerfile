# 前端构建阶段
FROM node:20-alpine AS build
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm install
COPY . .
RUN npm run build

# 运行阶段 - 使用 nginx 托管静态文件
FROM nginx:alpine
# 安装 gettext（提供 envsubst 命令，用于启动时替换环境变量）
RUN apk add --no-cache gettext
# 复制构建产物到 nginx 静态文件目录
COPY --from=build /app/dist /usr/share/nginx/html
# 复制 nginx 配置模板（启动时根据环境变量生成实际配置）
COPY nginx.conf.template /etc/nginx/conf.d/default.conf.template
# 删除默认配置，避免冲突
RUN rm -f /etc/nginx/conf.d/default.conf
# 复制启动脚本
COPY entrypoint.sh /entrypoint.sh
RUN chmod +x /entrypoint.sh

# 暴露端口（CloudBase 云托管要求 80）
EXPOSE 80

# 启动时通过环境变量 BACKEND_URL 生成 nginx 配置并启动
# 部署时在 CloudBase 控制台设置：BACKEND_URL=https://你的后端服务地址.sh.run.tcloudbase.com
CMD ["/entrypoint.sh"]
