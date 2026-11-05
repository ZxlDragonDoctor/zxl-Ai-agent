# 第十七课：为本地正式模式创建账号

## 概念与边界

身份服务从 PostgreSQL 读取可信主体、租户成员和会话。正式模式没有默认万能账号，也不自动把旧会话绑定给第一个登录用户。`ProvisionAccountCommand` 是有数据库管理权限的开发者在本机终端运行的初始化入口，不是公开注册接口。

命令复用 `AuthenticationService.provision` 的单事务与 BCrypt 哈希。指定主体或登录名已存在时失败，不改原密码、角色或成员，不悄悄给既有账号增加权限。创建命令要求至少12字符且不超过72个UTF-8字节；不改变已有账号的登录验证规则。

## 操作

先按第十二课配置真实数据库环境变量 `LESSON12_DB_URL`、`LESSON12_DB_USER`、`LESSON12_DB_PASSWORD`。使用 JDK17、Maven 和可交互 PowerShell 终端执行：

```powershell
.\tools\provision-account.ps1 -Subject local-user-01 -LoginName local-user-01 -Tenant local-workspace-01 -Role employee
```

需要管理知识的账号明确选择 `-Role knowledge-maintainer`。这是创建新账号时的角色选择，不是提升已有账号的命令。使用自定义 Maven 缓存时可传 `-MavenRepository 'D:\your-maven-repo'`。

Java 在终端提示时读取密码并隐藏输入，不接受 `--password`、密码环境变量或管道明文。没有可交互控制台时拒绝执行；不要为了绕过此限制把密码写进脚本、命令历史或仓库。脚本只构建运行时 classpath 并启动命令，不启动 Agent 服务、不运行模型、不创建登录会话。

成功后在统一页面输入租户、登录名和刚设置的密码。退出码0表示创建确认，2表示参数/输入未接受，1表示未确认创建。重复创建会失败；不要把失败当成功，也不要通过改数据库角色绕过它。

## 安全与证据

验证先于密码读取和数据库连接；异常文本不输出驱动细节或密码。输入字符缓冲区最终清零，数据库 owner 关闭；Java 与 BCrypt 过程中仍存在不可原地清零的 String，不宣称进程堆内完全没有明文。

测试覆盖参数/角色/重复参数拒绝、无密码命令行入口、密码清理、成功/失败关闭以及真实PG下重复创建不覆盖原角色/密码、不遗留另一个租户。当前验收命令和结果在 `17-implementation.md` 记录。自动化中使用合成密码，不把实际人的密码作为测试材料。

2026-11-06：4项命令单元测试、1项真实PG用例以及6项连接池/正式工厂回归通过，0失败、错误、跳过。[精确测试范围](evidence/17-provision-tests-2026-11-06.json)，日志 `target/lesson17-provision-pg-green-20261106.log`；缺命令类型的编译红灯为 `target/lesson17-provision-red-20261106.log`。PowerShell脚本已通过解析检查；未将解析检查或注入输入测试宣称为人工控制台隐藏输入的完整验收。
