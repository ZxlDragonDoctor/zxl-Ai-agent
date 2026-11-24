# 第24课：HTTPS、代理信任与流式传输

HTTPS不只是把地址改成https。本项目需要一起验证证书链、SAN和有效期，代理转发后的scheme/port，登录Cookie的Secure属性，以及Origin/CSRF和SSE分段。

## 项目中的请求路径

浏览器HTTPS → nginx443终止TLS → 同网络命名空间loopback18301的正式Java入口。main、worker、MCP和frontend仍共享单机namespace；PG在专用Docker网络。只有loopback5443映射到443，Java HTTP和PG没有主机发布端口。这不等于内部调用、PG连接或模型供应商连接都由该nginx配置保护。

nginx覆盖来自客户端的X-Forwarded-Proto/Port/Host/For。Tomcat native forwarding仅信任loopback代理，正确识别外部https与端口，现有AuthCookies才会设置Secure。WebSecurityFilter仍检查Origin、CSRF与身份权限，TLS不会代替这些检查。

## 信任不能用关闭校验代替

本次本地CA直接签发含IP127.0.0.1和DNS localhost的叶子证书，私钥和实际CA文件仅位于忽略提交的target。Node客户端显式传入CA，保持rejectUnauthorized=true和默认主机名校验；未知CA、错误主机名均实际失败。TLS1.2/1.3握手成功，TLS1.1收到服务端protocol version拒绝，明文访问TLS端口返回400。

浏览器UI验收在独立Chrome上下文仅允许本次叶子SPKI，ignoreHTTPSErrors=false；没有修改Windows或用户浏览器信任库。该公钥例外用于操作页面，不能当成系统已信任证书的证据。严格链与主机名验证由单独Node请求提供。普通浏览器仍需按自己的信任策略处理本地CA。

## 为什么还要在Docker前检查证书

真实负例发现：Docker Desktop的compose run即使配置create_host_path:false，仍为缺失cert.pem/key.pem创建空目录，然后才失败。不能由配置字段存在就宣称缺文件不会建目录。

新增tls-start先只读检查实际regular证书和私钥、有效期、IP/DNS SAN、SPKI是否匹配，给定CA时再检查直接签发者签名；成功后把canonical TLS_CERT_DIR注入子进程，才调用Compose。全新缺文件目录实测被提前拒绝且仍为空，五个运行容器不变。签名检查不是完整中间链PKIX/EKU/path-length验证，后续真实HTTPS握手仍是必要证据。

```powershell
node deploy/enterprise/tls-start.mjs --project lesson24-tls --env-file target/private.env --compose deploy/enterprise/compose-tls-recovery.json --tls-dir target/private-certs --ca-file target/private-certs/ca.pem
```

启动返回started/readinessVerified=false，不能直接当作验收通过。证书只读单文件挂载，未烘焙进镜像。重启使用显式CA探针：

```powershell
node deploy/enterprise/restart-apps.mjs --project lesson24-tls --env-file target/private.env --compose deploy/enterprise/compose-tls-recovery.json --origin https://127.0.0.1:5443 --ca-file target/private-certs/ca.pem
```

必须同时传入该组实际使用的卷/镜像覆盖文件。不会自动迁移、删除PG或覆盖会话卷；外层客户端超时不证明Docker服务端任务已取消。

## 怎样证明流式链路可用

nginx关闭proxy_buffering和proxy_cache，再检查真实SSE数据到达时间。本次收到20个分段，首段138ms、末段1062ms，存在RUN_STARTED和RUN_COMPLETED；保存后为2条消息、busy=false。它验证快速确定性拒答的分段传输，不等于新的真实回答模型测试或吞吐/背压验收。

页面覆盖登录、历史、展开记录、中英文、选择Agent、新建、发送、刷新、取消删除/确认删除，以及重启后读取和退出。退出后Session Cookie移除，未登录读取会话返回401。

## 当前验收与边界

66项部署回归通过，真实nginx语法、TLS负例、Secure/HttpOnly/SameSite Cookie、Origin/CSRF、SSE、文件写入与HTTPS有序重启均有独立证据。临时对话通过正常API清理；归属/审计按既有策略保留。其他运行组、个人学习进度和笔记未改。

本地CA不是公网信任。本次没有公网域名签发/续期、证书轮换、HSTS生产策略、mTLS或内部PG TLS的验收主张。证书为短期教学证书，过期后客户端应拒绝，需重新生成有效材料，不应关闭验证。第24课的完整业务和CI仍待补齐。

[配置与命令](../../deploy/enterprise/tls-recovery-README.md) · [实际证据与截图](../images/lesson24-tls-2026-11-24/README.md)

## 面试复盘

代理终止TLS后，Java为什么可能仍认为请求是HTTP？为什么Secure Cookie正确不能替代CSRF？为什么配置测试通过仍要实际验证Docker挂载？证书预检通过与真实TLS握手通过各证明什么？怎样区分流式正确性与负载能力？

## 参考与固定运行版本

项目为Spring Boot3.4.6；属性含义对照[Boot3.4官方属性参考](https://docs.spring.io/spring-boot/3.4/appendix/application-properties/)。实际nginx1.30.7，基础镜像固定digest caf4e1e032a20f9c3c5e28fc7f25ffd768ad8677b4975c54193075997b3a97e3；TLS和缓冲配置参考[nginx SSL模块](https://nginx.org/en/docs/http/ngx_http_ssl_module.html)与[proxy模块](https://nginx.org/en/docs/http/ngx_http_proxy_module.html)。nginx官方模块页无固定版本URL，记录本次查阅日2026-11-24，并以固定镜像实际测试为验收依据。
