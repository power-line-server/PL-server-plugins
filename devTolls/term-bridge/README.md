# term-bridge — 进程终端桥

启动并全量接管子进程的真实终端（stdout/stderr 从启动首字节捕获，stdin 可写入），
支持同时管理多个会话（服务器、任意 shell）。供代理与开发者远程驱动服务器控制台。

## 构建

```bash
cd term-bridge
build.bat        # Windows
./build.sh       # Linux/macOS（javac 编译到 out/）
```

## 运行

```bash
run.bat [--listen <端口>]      # 默认 127.0.0.1:9090
./run.sh [--listen <端口>]     # 默认 127.0.0.1:9090
```

## 协议（TCP + JSON 行协议，每行一个 JSON）

连接控制端口后发送指令，响应 `{id, ok, result}`；
进程输出实时推送 `{event:"output", session, seq, data}`，进程退出推送 `{event:"exit", session, code}`。

| 指令 | 参数 | 说明 |
|---|---|---|
| `session_start` | `name`, `cmd[]`, `cwd?`, `encoding?` | 启动进程会话（任意命令，如 `java -jar server.jar`） |
| `write` | `session`, `data` | 向进程 stdin 写入（命令后带 `\n`） |
| `logs` | `session`, `limit?` | 获取历史输出（从启动首字节起，环形保留 2000 行） |
| `stop` / `kill` | `session` | 停止 / 强制杀死进程 |
| `sessions` | - | 会话列表 |
| `ping` | - | 存活检查 |

## 启动服务器示例

```json
{"id":1,"op":"session_start","name":"server","cmd":["java","--enable-native-access=ALL-UNNAMED","-jar","server.jar"],"cwd":"/path/to/server","encoding":"GBK"}
{"id":2,"op":"write","session":"server","data":"help\n"}
{"id":3,"op":"logs","session":"server","limit":100}
```

**注意**：服务器必须由 term-bridge 启动（或由它接管），代理才能读取到从启动开始的全部输出；
`encoding` 用于进程输出的平台编码（Windows 控制台通常 GBK，Linux 通常 UTF-8）。

## 相关

- 服务器 6568 命令管道（`console.kts` 的 `cmdPipePort`）：监听 127.0.0.1，UTF-8 逐行命令，
  用于无损注入中文命令（WriteConsoleInput 注入中文会被 Windows 控制台截断）；本工具注入中文命令时可走该管道。
- 服务器热重载：见根 README「开发工作流」——`sa reload <script>` 无需重启服务器。
