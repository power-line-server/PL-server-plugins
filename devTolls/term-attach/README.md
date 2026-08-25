# term-attach — Mindustry 服务器控制台接管工具

Windows 下附加到**用户自己启动**的服务器进程(run.bat → cmd → java)的控制台,
读取终端内容、注入命令。解决代理/脚本无法"看到"用户终端的问题。

## 原理

- `AttachConsole(pid)` 附加到服务器进程的控制台(同用户会话内可用)
- 读取:`CreateFile("CONOUT$")` + `ReadConsoleOutputCharacter` 读**屏幕缓冲区任意坐标**
  (读缓冲区不需要滚动窗口,不会打扰用户终端显示)
- 注入:`CreateFile("CONIN$")` + `WriteConsoleInput` 构造 KEY_EVENT 事件,
  服务器 console.kts(jline)会像真实键盘输入一样回显并执行
- **附加是短连接**:attach → 操作 → FreeConsole,每次调用互不干扰

## 用法

```powershell
# 1. 自动定位服务器进程(6567 监听 + loader + run.bat cmd 进程树)
.\console.ps1 find
# => SERVER_PID=2632 (java, java.exe)

# 2. 读取终端末尾 15 行(带视角元信息)
.\console.ps1 read -TargetPid 2632 -Tail 15

# 3. 增量读取:只返回 410 行之后的新内容(配合上次的 cursor 行号使用)
.\console.ps1 read -TargetPid 2632 -From 410

# 4. 注入命令(自动追加回车; -CheckIdle 时输入缓冲区非空则跳过, 避免覆盖手动输入)
.\console.ps1 send -TargetPid 2632 -Text "status" -CheckIdle

# 5. 清理输入残留后注入(先退格 N 个字符)
.\console.ps1 send -TargetPid 2632 -Text "status" -Backspaces 3
```

## 视角元信息(防止"误以为服务器未启动")

`read` 每次输出视角头,代理据此判断看到的是不是最新输出:

```
== VIEW: buffer=120x9001 | window(visible)=390..419 | cursor=(2,419)
        | read range=412..419 | windowAtBottom=True | inputMode=0x9 events=0 ==
```

| 字段 | 含义 |
|---|---|
| `buffer=WxH` | 屏幕缓冲区尺寸(120 列 × 9001 行滚动历史) |
| `window(visible)=a..b` | 用户终端窗口当前可见的缓冲区行区间(只读不滚动,与读取无关) |
| `cursor=(x,y)` | 光标位置;**新输出写到光标行,光标行 = 最后输出行** |
| `read range=a..b` | 本次实际读取的缓冲区行区间 |
| `windowAtBottom` | 窗口是否在缓冲区底部(接近最新输出)。**若为 False,说明窗口停留在历史位置,不代表服务器没输出** |
| `inputMode=0x9` | 控制台输入模式(0x9 = jline 原始模式) |
| `events=N` | 输入缓冲区待处理事件数(N>0 说明有人在手动输入) |

**判断服务器是否在跑/有新输出:看 `cursor` 行号和两次读取之间是否变化,不要依赖窗口内容。**

## 已知限制

- **中文注入**:`WriteConsoleInput` 注入非 ASCII 字符会在 Windows 控制台**入队时**被截断为低字节
  (实测注入 `U+4E2D` 读回 `U+002D`,与控制台模式/API 变体无关,已定位为控制台 API 层行为)。
  **`send` 已自动处理:文本含非 ASCII 时改走服务器本地命令管道**(127.0.0.1:6568, UTF-8 无损,
  需要服务器 console.kts 开启命令管道,配置 `coreMindustry.console.cmdPipePort`),纯 ASCII 仍走控制台注入。
- AttachConsole 仅限**同一用户会话**;附加期间目标进程退出会报错(重试即可)。
- `-CheckIdle` 只检查输入事件数,无法区分"用户正在打字"与"残留事件",必要时配合 `-Backspaces`。
- 读取的是屏幕缓冲区(滚动历史上限 9001 行),更早的输出不在其中。
- 注入后命令的执行结果在后续 read 中查看(建议 send 后 sleep 1-2s 再 read)。
- **服务器启动方式注意**:若由自动化 shell(如 Reasonix/agent 环境)通过 `Start-Process` 启动服务器,
  服务器进程可能继承 shell 的 **Job Object**,shell 命令结束时服务器被连带终止(表现为"窗口弹出一会就自动关闭")。
  应使用脱离 Job 的方式启动(WMI: `Invoke-CimMethod Win32_Process Create`、计划任务,或用户手动启动)。
  本机现网服务器一直由用户手动 `run.bat` 启动,无此问题。

## 调试备忘(踩过的坑)

- `AttachConsole` 成功后 `GetStdHandle` 返回无效句柄(err=6)→ 必须 `CreateFile("CONOUT$"/"CONIN$")`
- PowerShell 直接给 `INPUT_RECORD`(Explicit layout)字段赋值不可靠 → 必须在 C#(Add-Type)里构造事件
- PowerShell 5.1 读取脚本必须 **UTF-8 with BOM**(否则中文按 GBK 解析导致语法错误)
- 参数名不能用 `$Pid`(PowerShell 内置只读变量)
- bash → powershell 传中文参数会乱码 → `send` 支持 `-TextB64`(base64 传参)
- 读取完整历史:读 `dwSize.Y` 全部行,而不是窗口可见区(srWindow 只有 30 行,会误判"服务器没输出")
