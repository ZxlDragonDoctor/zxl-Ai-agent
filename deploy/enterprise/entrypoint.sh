#!/bin/sh
set -eu
fail() { echo "$1" >&2; exit 1; }
[ -n "${LESSON12_DB_URL:-}" ] || fail DB_URL_REQUIRED
case "$LESSON12_DB_URL" in jdbc:postgresql://*) ;; *) fail POSTGRES_REQUIRED;; esac
[ -n "${LESSON12_DB_USER:-}" ] || fail DB_USER_REQUIRED
[ -n "${LESSON12_DB_PASSWORD:-}" ] || fail DB_PASSWORD_REQUIRED
[ -n "${LESSON17_INTERNAL_SERVICE_TOKEN:-}" ] || fail INTERNAL_TOKEN_REQUIRED
case "${1:-}" in
 main)
 [ "${AGENT_SECURITY_MODE:-}" = required ] || fail FORMAL_MODE_REQUIRED
 [ -n "${SPRING_AI_DASHSCOPE_API_KEY:-}" ] || fail MODEL_KEY_REQUIRED
 exec java -Dloader.main=com.zxl.enterprise.EnterpriseAgentApplication -cp /opt/agent/app.jar org.springframework.boot.loader.launch.PropertiesLauncher --spring.profiles.active=enterprise ;;
 worker)
 [ "${LESSON12_EMBEDDING_MODE:-}" = http ] || fail HTTP_EMBEDDING_REQUIRED
 [ -n "${LESSON12_EMBEDDING_API_KEY:-}" ] || fail EMBEDDING_KEY_REQUIRED
 [ -n "${LESSON12_EMBEDDING_ENDPOINT:-}" ] || fail EMBEDDING_ENDPOINT_REQUIRED
 [ -n "${LESSON12_EMBEDDING_MODEL:-}" ] || fail EMBEDDING_MODEL_REQUIRED
 [ -n "${LESSON12_EMBEDDING_REVISION:-}" ] || fail EMBEDDING_REVISION_REQUIRED
 [ -n "${LESSON12_EMBEDDING_DIMENSIONS:-}" ] || fail EMBEDDING_DIMENSIONS_REQUIRED
 [ "${LESSON13_RERANK_MODE:-}" = rule ] || fail RULE_RERANK_REQUIRED
 exec java -Dloader.main=com.zxl.zxlaiagent.knowledge.scoped.FormalKnowledgeWorker -cp /opt/agent/app.jar org.springframework.boot.loader.launch.PropertiesLauncher ;;
 mcp) exec java -Dloader.main=com.zxl.zxlaiagent.knowledge.mcp.FormalKnowledgeMcpWorker -cp /opt/agent/app.jar org.springframework.boot.loader.launch.PropertiesLauncher ;;
 *) fail ROLE_INVALID ;;
esac
