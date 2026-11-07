# 腾讯云 CloudBase 云托管部署指南

本文档说明如何将 zxl-ai-agent 项目（Spring Boot 后端 + Vue 3 前端）部署到腾讯云 CloudBase 云托管。

## 背景

- 旧版 CloudBase 资源套餐（按量付费/免费版）已于 **2026-11-09** 停止服务。
- 现网 CloudBase 云托管（CloudRun）仍正常支持 Java Docker 部署。
- 需要升级到现网套餐（个人版 / 标准版 / 企业版 / 企业高级版）。

## 前置条件

1. 拥有腾讯云账号并完成实名认证。
2. 已开通 CloudBase 云开发环境（个人版套餐起，约 19.9 元/月起）。
3. 准备好以下 API Key（在控制台环境变量中配置）：
   - `DASHSCOPE_API_KEY` — 阿里云通义千问 API Key
   - `SEARCH_API_KEY` — 搜索 API Key
   - `MCP_SERVER_URL` — MCP SSE 服务地址（可选，有默认值）

## 配置变更说明

已完成的配置调整：

| 文件 | 变更内容 |
| --- | --- |
| `Dockerfile`（后端） | 多阶段构建优化；端口从 8123 改为 80（云托管要求） |
| `application-prod.yml` | API Key 改为环境变量注入（`${DASHSCOPE_API_KEY}` 等）；端口改为 80 |
| `.dockerignore`（新增） | 排除不必要文件，减小上传体积 |
| `zxl-Ai-agent-fronted/Dockerfile` | 使用模板化 nginx 配置，支持环境变量注入后端地址 |
| `zxl-Ai-agent-fronted/nginx.conf.template`（新增） | Nginx 配置模板，含 `${BACKEND_URL}` 占位符 |
| `zxl-Ai-agent-fronted/entrypoint.sh`（新增） | 启动脚本，根据 `BACKEND_URL` 环境变量动态生成 nginx 配置 |

---

## 部署步骤

### 第一步：创建 CloudBase 环境

1. 登录 [CloudBase 控制台](https://console.cloud.tencent.com/tcb)。
2. 如果没有现网环境，点击「新建环境」。
3. 选择套餐（个人版起步即可，后续可升级）。
4. 记下环境 ID（形如 `zxl-ai-agent-xxxxx`）。

### 第二步：部署后端服务

1. 在 CloudBase 控制台进入你的环境 → 左侧菜单「云托管」→「服务」。
2. 点击「新建服务」：
   - **服务名称**：`zxl-ai-agent-backend`
   - **上传方式**：本地代码（选择项目根目录 `zxl-Ai-agent/`）
   - **端口**：80
   - **Dockerfile 路径**：根目录（留空即可）
3. **环境变量配置**（关键步骤）：
   - `DASHSCOPE_API_KEY` = `你的阿里云 DashScope API Key`
   - `SEARCH_API_KEY` = `你的搜索 API Key`
   - `MCP_SERVER_URL` = `你的 MCP 服务地址`（可选）
   - `SERVER_PORT` = `80`
4. 资源配置建议：
   - CPU：1 核（最低），推荐 2 核
   - 内存：2 GB（最低），推荐 4 GB（Java 应用需要足够内存）
   - 实例数：1（最小），0-10（自动伸缩）
5. 点击「部署」，等待构建完成（首次构建约 5-10 分钟）。
6. 部署完成后，记下服务访问地址（形如 `https://zxl-ai-agent-backend-xxxxx.sh.run.tcloudbase.com`）。

### 第三步：部署前端服务

1. 回到「云托管」→「服务」，点击「新建服务」：
   - **服务名称**：`zxl-ai-agent-frontend`
   - **上传方式**：本地代码（选择 `zxl-Ai-agent-fronted/` 目录）
   - **端口**：80
   - **Dockerfile 路径**：根目录（留空即可）
2. **环境变量配置**（关键步骤）：
   - `BACKEND_URL` = `https://你的后端服务地址.sh.run.tcloudbase.com`
     （将上一步获得的后端地址填入，**不要带末尾斜杠**）
3. 资源配置建议：
   - CPU：0.25 核即可（前端为静态资源）
   - 内存：256 MB
   - 实例数：1
4. 点击「部署」，等待构建完成（约 3-5 分钟）。
5. 部署完成后，记下前端访问地址。

### 第四步：验证

1. 打开前端服务地址，确认页面正常加载。
2. 在页面中尝试发送一条对话消息，验证 API 请求能正确代理到后端。
3. 如果 API 报错，检查：
   - 后端服务是否正常运行（控制台查看日志）。
   - `BACKEND_URL` 环境变量是否正确设置。
   - 后端环境变量 `DASHSCOPE_API_KEY` 等是否正确。

### 第五步（可选）：绑定自定义域名

生产环境建议绑定已备案的自定义域名：

1. CloudBase 控制台 → 环境设置 → 自定义域名。
2. 按指引添加域名解析和备案。
3. 将域名关联到对应的服务。

---

## 环境变量汇总

### 后端服务

| 变量名 | 说明 | 示例 |
| --- | --- | --- |
| `DASHSCOPE_API_KEY` | 阿里云 DashScope API Key（必需） | `sk-xxxxxxxxxxxx` |
| `SEARCH_API_KEY` | 搜索 API Key（必需） | `EpF8xxxxxxxx` |
| `MCP_SERVER_URL` | MCP SSE 服务地址（可选） | `http://xxx.xxx.com:8127` |
| `SERVER_PORT` | 服务端口（默认 80） | `80` |

### 前端服务

| 变量名 | 说明 | 示例 |
| --- | --- | --- |
| `BACKEND_URL` | 后端云托管服务地址（必需） | `https://zxl-ai-agent-backend-xxxxx.sh.run.tcloudbase.com` |

---

## 常见问题

### Q: 旧环境已停止服务，数据还能找回吗？

旧环境在停止服务后有 60 天隔离期，隔离期内可导出数据。隔离期满后将被销毁。如果已过隔离期，数据不可恢复，需要重新创建环境。

### Q: 部署后 API 返回 502/504？

- 检查后端容器是否成功启动（控制台 → 服务 → 日志）。
- Spring Boot 应用启动较慢，等待 30 秒左右再试。
- 确认端口配置为 80，且 `SERVER_PORT=80`。

### Q: 前端页面加载但 API 不通？

- 检查前端服务的 `BACKEND_URL` 环境变量是否正确。
- 确保地址不带末尾斜杠。
- 后端 `context-path` 为 `/api`，nginx 会自动代理 `/api/` 到后端。

### Q: SSE 流式响应断开？

nginx.conf.template 中已配置 SSE 相关参数（`proxy_buffering off` 等）。如仍有问题，检查 CloudBase 网关是否有超时限制。

### Q: 如何更新部署？

修改代码后，在 CloudBase 控制台对应服务中点击「重新部署」即可，会自动重新构建和发布。地址保持不变。

---

## 参考文档

- [CloudBase 云托管文档](https://docs.cloudbase.net/run/introduce)
- [Java 快速开始](https://docs.cloudbase.net/run/quick-start/dockerize-java)
- [从源码部署](https://docs.cloudbase.net/run/deploy/deploy/deploying-source-code)
- [CloudBase 定价](https://cloud.tencent.com/document/buy-guide/876/127357)
