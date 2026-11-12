# 容器中的固定文本摘录

这个实验把 Agent 交给工具的内容限制为文本。启动器从标准输入读取 UTF-8，容器执行固定的摘录程序，结果经标准输出返回。模型和普通调用方都不能选择容器镜像、命令、网络地址或宿主目录。

实验使用独立镜像。镜像构建由开发者触发，运行时依赖本机已经构建的镜像。容器配置为无外部网络、只读根文件系统、非 root 用户和无宿主目录挂载。进程、内存和 CPU 受容器限制，启动器限制输入、输出和等待时间。[Docker 容器运行参数](https://docs.docker.com/engine/containers/run/) · [Docker 无网络模式](https://docs.docker.com/engine/network/drivers/none/)

```powershell
docker build -t zxl-lesson18-excerpt:local scripts/lesson18-runtime
'Lesson 18 example' | python scripts/lesson18-runtime/run_excerpt.py
```

用合成文本检查摘录结果，随后用超限输入和无可用容器引擎验证失败状态。容器侧还需观测外部网络不可达、根文件系统拒绝写入及超时容器被清理。这些检查需要可连接的 Linux Docker 引擎。

运行时先将固定标签解析为本地镜像 ID，再用该 ID 创建容器，禁用自动拉取。输入限 8 KiB，摘录限 240 字符，标准输出限 2 KiB。容器配额为 1 CPU、128 MiB 内存和 32 个进程。运行等待上限为 10 秒。

本机 Docker CLI 29.7.2 可用。Docker Desktop 4.89.0 启动时无法重命名 `sailor-ingest.sock`，Linux 引擎管道未建立。真实启动器返回退出码 1，标准输出为空。正向容器运行和限制实测待引擎恢复。

四项单测覆盖 UTF-8 输入和限额、启动参数和超时清理、镜像缺失、清理失败时不返回摘录。Docker 调用相关单测使用替身。构建文件当前使用 `python:3.12-alpine` 标签，首次成功构建后还需记录基础镜像摘要。

[图谱验收截图](../images/lesson18-runtime-excerpt-2026-11-13/README.md)
