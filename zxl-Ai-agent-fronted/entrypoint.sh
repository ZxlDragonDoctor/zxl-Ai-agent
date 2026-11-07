#!/bin/sh
# entrypoint.sh - 容器启动时根据环境变量生成 nginx.conf
# 使用方法：在 CloudBase 云托管控制台设置环境变量 BACKEND_URL

set -e

TEMPLATE="/etc/nginx/conf.d/default.conf.template"
OUTPUT="/etc/nginx/conf.d/default.conf"

# 如果未设置 BACKEND_URL，使用默认值（部署后请替换为实际后端地址）
BACKEND_URL="${BACKEND_URL:-https://your-backend-service.sh.run.tcloudbase.com}"

# 从 BACKEND_URL 中提取 host（去掉 https:// 或 http:// 前缀和路径）
BACKEND_HOST=$(echo "$BACKEND_URL" | sed -E 's|^https?://||' | sed -E 's|/.*$||')

echo "=== Nginx 配置生成 ==="
echo "BACKEND_URL: $BACKEND_URL"
echo "BACKEND_HOST: $BACKEND_HOST"
echo "======================"

# 替换模板中的占位符
envsubst '${BACKEND_URL} ${BACKEND_HOST}' < "$TEMPLATE" > "$OUTPUT"

echo "nginx.conf 已生成，启动 Nginx..."

# 启动 nginx
exec nginx -g 'daemon off;'
