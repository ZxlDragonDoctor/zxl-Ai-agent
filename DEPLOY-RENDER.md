# Render 免费部署指南

本文档说明如何将 zxl-ai-agent 项目免费部署到 [Render](https://render.com) 平台。

## 为什么选 Render

- **永久免费**，每月 750 小时（够 1 个后端服务跑满全月）
- **不需要绑信用卡**，注册即用
- 支持 Docker 部署（后端）和静态站点（前端）
- 前端静态站点**不休眠**，后端 15 分钟无访问会休眠（唤醒约 1 分钟）

## 免费层限制

| 限制项 | 说明 |
| --- | --- |
| 后端内存 | 512MB（已通过 JVM 参数 `-Xmx350m` 优化） |
| 后端休眠 | 15 分钟无访问自动休眠，首次请求唤醒约 1 分钟 |
| 带宽 | 每月 5GB |
| 构建时长 | 每月 500 分钟 |
| 文件系统 | 临时性的，重启后丢失（不影响代码，仅影响运行时文件） |

---

## 前置准备

1. 注册 [Render 账号](https://render.com)（可用 GitHub / Google 登录）。
2. 将项目推送到 GitHub 仓库（Render 从 GitHub 拉取代码自动构建）。
3. 准备好以下 API Key：
   - `DASHSCOPE_API_KEY` — 阿里云通义千问 API Key
   - `SEARCH_API_KEY` — 搜索 API Key

---

## 部署方式一：Blueprint 一键部署（推荐）

项目根目录已包含 `render.yaml` 配置文件，Render 会自动识别并创建前后端两个服务。

### 步骤

1. 将项目代码推送到 GitHub。
2. 登录 Render 控制台 → 点击 **New** → **Blueprint**。
3. 选择你的 GitHub 仓库。
4. Render 会读取 `render.yaml`，自动创建：
   - `zxl-ai-agent-backend`（Web Service, Docker）
   - `zxl-ai-agent-frontend`（Static Site）
5. **关键：在服务创建后，手动填入环境变量**（见下方环境变量配置）。

---

## 部署方式二：手动创建服务

### 第一步：部署后端

1. Render 控制台 → **New** → **Web Service**。
2. 连接 GitHub 仓库。
3. 配置：
   - **Name**：`zxl-ai-agent-backend`
   - **Runtime**：Docker
   - **Region**：Singapore（新加坡，国内访问较快）
   - **Plan**：Free
   - **Dockerfile Path**：根目录（留空即可）
4. **环境变量**（关键步骤，在 Advanced 设置中添加）：

   | Key | Value |
   | --- | --- |
   | `DASHSCOPE_API_KEY` | 你的阿里云 DashScope API Key |
   | `SEARCH_API_KEY` | 你的搜索 API Key |
   | `MCP_SERVER_URL` | `http://ldlqepdn.zxl-image-search-mcp.77mounfk.rpjqduwt.com:8127` |
   | `DASHSCOPE_MODEL` | `qwen-plus` |
   | `JAVA_OPTS` | `-Xmx350m -Xss256k -XX:+UseContainerSupport` |

5. 点击 **Create Web Service**，等待构建（首次约 5-10 分钟）。
6. 构建完成后，记下后端地址（形如 `https://zxl-ai-agent-backend-xxxx.onrender.com`）。

### 第二步：部署前端

1. Render 控制台 → **New** → **Static Site**。
2. 连接同一个 GitHub 仓库。
3. 配置：
   - **Name**：`zxl-ai-agent-frontend`
   - **Region**：Singapore
   - **Build Command**：`cd zxl-Ai-agent-fronted && npm install && npm run build`
   - **Publish Directory**：`zxl-Ai-agent-fronted/dist`
   - **Plan**：Free
4. **环境变量**：

   | Key | Value |
   | --- | --- |
   | `VITE_API_BASE` | `https://你的后端地址.onrender.com/api` |

   > 将上一步获得的后端地址填入，**注意末尾带 `/api`**。
   > **重要**：Vite 的环境变量在**构建时**注入，修改后需要重新部署才生效。

5. **路由配置**（解决 Vue Router 刷新 404）：
   - 在 Static Site 的 **Redirects/Rewrites** 设置中添加：
   - **Source**：`/*` → **Destination**：`/index.html` → **Action**：Rewrite
6. 点击 **Create Static Site**，等待构建完成（约 2-3 分钟）。

### 第三步：验证

1. 打开前端地址，确认页面正常加载。
2. 在页面中尝试发送一条对话消息。
3. 如果后端处于休眠状态，首次请求可能需要等待约 1 分钟唤醒。
4. 后端唤醒后，API 应正常响应。

---

## 环境变量汇总

### 后端服务

| 变量名 | 说明 | 必需 |
| --- | --- | --- |
| `DASHSCOPE_API_KEY` | 阿里云 DashScope API Key | 是 |
| `SEARCH_API_KEY` | 搜索 API Key | 是 |
| `MCP_SERVER_URL` | MCP SSE 服务地址 | 否（有默认值） |
| `DASHSCOPE_MODEL` | 模型名称 | 否（默认 qwen-plus） |
| `JAVA_OPTS` | JVM 参数 | 否（Dockerfile 已内置） |

### 前端服务

| 变量名 | 说明 | 必需 |
| --- | --- | --- |
| `VITE_API_BASE` | 后端 API 完整地址（含 /api） | 是 |

---

## 常见问题

### Q: 后端启动报 OutOfMemoryError？

Dockerfile 已内置 JVM 参数 `-Xmx350m`。如果仍报 OOM，可能是依赖（如 Spring AI、MCP 客户端）占用过多内存。可尝试：
- 在 Render 控制台的环境变量中覆盖 `JAVA_OPTS`，设置更小的堆：`-Xmx300m -Xss256k`
- 或升级到付费计划（$7/月，1GB 内存）

### Q: 后端休眠后首次请求很慢？

Render 免费层后端 15 分钟无访问会自动休眠。唤醒需要约 1 分钟（JVM 启动 + Spring Boot 初始化）。解决方案：
- 前端加一个加载提示，告知用户"服务正在唤醒中"
- 或使用免费的外部定时器（如 [cron-job.org](https://cron-job.org)）每 14 分钟 ping 一次后端健康检查端点 `/api/swagger-ui.html`
- 或升级到付费计划（不休眠）

### Q: 前端 API 请求跨域（CORS）报错？

后端 Spring Boot 需要配置 CORS 允许前端域名访问。如果遇到跨域问题，在后端添加 CORS 配置：

```java
@Configuration
public class CorsConfig implements WebMvcConfigurer {
    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**")
                .allowedOrigins("*")
                .allowedMethods("*")
                .allowedHeaders("*");
    }
}
```

### Q: 前端修改了 VITE_API_BASE 但没生效？

Vite 的环境变量在**构建时**注入，不是运行时。修改环境变量后，需要在 Render 控制台手动触发 **Manual Deploy**。

### Q: 如何更新部署？

推送代码到 GitHub 主分支，Render 会自动检测并重新部署（如果开启了 auto-deploy）。

---

## 参考文档

- [Render 官方文档](https://render.com/docs)
- [Render Docker 部署](https://render.com/docs/deploy-docker)
- [Render 静态站点](https://render.com/docs/deploy-static-site)
- [Render 免费层限制](https://render.com/docs/free)
