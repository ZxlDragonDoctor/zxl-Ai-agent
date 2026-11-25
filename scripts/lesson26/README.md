# 第26课：恢复证据快照

`snapshotRun({origin,cookie,runId})`只读取一个已终止的正式多Agent运行，不提交目标、不调用模型、不取消任务、不操作数据库或进程。origin限定明确端口HTTP127.0.0.1，runId必须UUID；拒绝重定向，HTTP连同响应读取限制5秒/128KiB。cookie从私有登录会话传入，禁止写入命令行、仓库、截图或记录。

```js
import {snapshotRun, compareRecovery} from './scripts/lesson26/recovery-snapshot.mjs'
// origin/cookie/runId由私有验收会话提供；不要输出cookie。
const before = await snapshotRun({origin, cookie, runId})
// 在独立受控步骤实施并记录真实故障与恢复。
const after = await snapshotRun({origin, cookie, runId})
const observation = compareRecovery(before, after)
```

输出只有UUID、固定终态、序列、回答摘要、错误码摘要（兼容字段名errorCode，值为SHA256或null）。不包含回答、目标、凭据、URL和原始异常。摘要是字节一致性线索，不是来源签名，不防止对低熵内容猜测，不应用于发布敏感正文的替代方案。`compareRecovery`分别核对同run、终态、结果、错误和序列不倒退；running不能作为已保存终态基线。

此工具只证明两次读取的这些字段一致，不能证明数据库重启、故障确实发生、业务写入恰好一次、供应商停止或资源归零。注入前环境、进程身份、故障时错误、恢复动作、新请求成功及独立PG查询必须另外记录。只读旧结果成功也不等于恢复后新写入正常。

测试：`node --test scripts/lesson26/*.test.mjs`。本地4项通过：真实HTTP夹具、正文/凭据不输出、字段改变/序列倒退拒绝、非终态和配置拒绝。新增模块前测试缺模块失败，随后实现通过；它不是生产故障复现或真实模型证据。独立Worker/PG故障恢复由Java真实进程用例另行验收。
