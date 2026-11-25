# 第25课：正式HTTP有界负载探针

已有测试身份登录后，从自己的会话取得cookie和CSRF，只放入进程环境：`LESSON25_COOKIE`、`LESSON25_CSRF`、`LESSON25_GOAL`。不要把值写进命令行、提交文件、截图或终端输出。实验会创建真实运行并可能消耗模型额度；使用隔离课堂租户。

```sh
node --test scripts/lesson25/capacity-probe.test.mjs
node scripts/lesson25/capacity-probe.mjs --origin http://127.0.0.1:5219 --requests 4 --concurrency 2 --run-budget-ms 45000 --observe-timeout-ms 60000
```

仅允许明确端口的HTTP 127.0.0.1，拒绝localhost、外部域名、路径、重定向。此工具不验证TLS，不能向HTTPS演示环境发送Secure cookie。最多16次提交、4个并发虚拟用户；每个用户等本次运行终态后再提交下一项，不自动重试提交。单次HTTP含响应读取上限5秒/128KiB，运行观察上限120秒；每项还可能有一次最长5秒的尽力取消。先测1项低负载，再逐步增加，不默认进行大规模收费测试。

输出保留不含身份信息的UUID、固定结果分类、参数、样本数和计时。不输出目标、回答、凭据、来源URL或原始错误。JSON中的提交分位数包含所有HTTP尝试，不能称作成功响应延迟；终态分位数按结果分别计算，观察超时和网络错误不混进完成延迟。分位数使用最近秩法，小样本P95/P99通常只是最大值，不能据此承诺SLA。

入口HTTP429=`httpRejected`；202后报告AGENT_CAPACITY_EXCEEDED=`capacityFailed`；CAPACITY_QUEUE_TIMEOUT=`queueTimedOut`。这些不是模型服务商429。其他failed统一分类，不把未知原始错误码写入输出。观察超时记录`observationTimedOut`，尝试一次cancel；确认收到cancel回执不代表已停掉供应商请求。没有终态就不生成终态耗时，最近观察到cleanupPending仍保留，不能据此证明资源已归零。

退出2表示参数错误；退出1表示观察超时、协议或传输失败；退出0表示实验观察完成，**不表示所有业务请求成功**。仍须检查counts，包括httpFailed、failed、拒绝和排队超时。工具不会自动接受或拒绝某个吞吐/延迟指标。需要另行记录运行版本、池与队列配置、CPU/内存/数据库/模型版本和实际负载，禁止把闭环少量结果外推为生产峰值。

本机HTTP夹具测试覆盖并发上限、终态等待、同步/异步容量区别、观察时限/一次取消、禁止重定向、超大/停滞响应、配置拒绝和输出脱敏。夹具不是真实应用、PG或模型证据；实际验收单独归档。

2026-11-25云端run 45628366592暴露观察截止与HTTP超时分类竞争：Java成功，交付工具143/144通过，观察超时用例失败。新增实际挂起HTTP轮询的用例先失败，再将由观察剩余时长约束的Abort明确分类为观察超时；独立HTTP时限/网络故障仍属传输失败，不扩大观察窗口。修复后本地9项通过，0失败/跳过；云端重跑与真实应用负载另行核对。
