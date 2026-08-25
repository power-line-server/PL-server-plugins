# devTolls — 服务器附属开发工具集

Mindustry 服务器（MindustryX 改端）的附属开发工具，供代理（AI）与开发者测试服务器插件使用。
纯文本驱动（TCP + JSON 行协议），跨平台（Linux 为主，Windows 兼容），Java 实现无外部依赖。

## 目录

- `term-bridge/` — 进程终端桥：由它启动 `java -jar server.jar` 并全量接管真实终端（stdout/stderr 从启动首字节捕获，stdin 可写入），可同时管理多个会话（服务器、任意 shell）
- `headless-client/` — 无头客户端（真实网络连接）：模拟真实玩家连接服务器，支持发送信息/移动/建造/拆除/菜单响应/文本输入等；多实例 = 多进程（每实例独立控制端口）
- `term-attach/` — 服务器控制台接管（Windows）：附加到用户自己启动的服务器进程，读取终端内容、注入命令（中文经 6568 命令管道走 term-bridge 通道无损注入）

---

## 开发工作流（服务器插件开发）

服务器脚本（`config/scripts/**/*.kts`）由 ScriptAgent 4 管理，**支持热重载，改脚本无需重启服务器**。

### 快速迭代流程

```bash
# 1. 修改脚本后热重载（几秒生效）
sa reload wayzer/ext/messageFilter        # 重载单个脚本
sa reload wayzer                          # 重载整个模块（wayzer 下全部脚本）

# 2. 新建脚本后重新扫描
sa scan                                   # 扫描新文件（新建 .kts 后需要）

# 3. 编译检查（不影响运行中实例）
sa compile wayzer/ext/xxx --async         # 实验性：只编译不生效

# 4. 查看故障/状态
sa listFailed                             # 列出故障脚本
sa list                                   # 列出模块/脚本
sa retry                                  # 重试失败的事务
```

> 命令经服务器控制台输入（终端直接输入，或经 6568 命令管道 `term-bridge`/`console.kts` 注入）。

### SA4 命令速查（`sa help` 两页）

| 命令 | 说明 |
|---|---|
| `sa reload <module[/script]>` | 重载脚本/模块（`--noCache` 忽略缓存、`--async` 异步） |
| `sa scan` | 重新扫描脚本（新建脚本后使用） |
| `sa compile <module[/script]>` | 实验性：编译单个脚本（不影响运行中） |
| `sa enable/disable <module[/script]>` | 启用/关闭脚本 |
| `sa unload <module[/script]>` | 卸载脚本 |
| `sa listFailed` | 列出故障脚本 |
| `sa list` | 列出模块/脚本 |
| `sa hotReload` | 开关自动热重载（文件变化自动重载，默认关） |
| `sa genMetadata` | 生成开发元数据 |
| `sa packModule <module>` | 打包模块 |
| `sa permission(pm)` | 权限系统配置 |
| `sa vars` | 列出模板变量 |
| `sa lang` | 语言管理(由 coreLibrary/lang.kts 注册)：`load`/`save`(lang.ini 读写)、`set <语言>`(控制台语言)、`reload`(改 bundle_*.properties 后热生效,无需重启) |
| `sa retry` | 重试失败事务 |

### 测试流程（插件功能验证）

1. 改脚本 → `sa reload <script>` 热重载
2. 启动 `headless-client` 假玩家连接服务器，用 `chat`/`menu`/`textInput` 驱动交互验证
3. 需要观察控制台输出：`term-attach read`（用户启动的服务器）或 `term-bridge logs`（term-bridge 启动的服务器）
4. 需要注入命令（含中文）：6568 命令管道（`console.kts` 的 `cmdPipePort`，UTF-8 无损）

---

## term-bridge（进程终端桥）

### 构建

```bash
cd term-bridge
./build.sh        # Linux/macOS（javac 编译到 out/）
build.bat         # Windows
```

### 运行

```bash
./run.sh [--listen <端口>]     # 默认 127.0.0.1:9090
```

### 协议（TCP + JSON 行协议，每行一个 JSON）

连接控制端口后发送指令，响应 `{id, ok, result}`，进程输出实时推送 `{event:"output", session, seq, data}`，进程退出推送 `{event:"exit", session, code}`。

| 指令 | 参数 | 说明 |
|---|---|---|
| `session_start` | `name`, `cmd[]`, `cwd?`, `encoding?` | 启动进程会话（任意命令，如 `java -jar server.jar`） |
| `write` | `session`, `data` | 向进程 stdin 写入（命令后带 `\n`） |
| `logs` | `session`, `limit?` | 获取历史输出（从启动首字节起，环形保留 2000 行） |
| `stop` / `kill` | `session` | 停止 / 强制杀死进程 |
| `sessions` | - | 会话列表 |
| `ping` | - | 存活检查 |

**启动服务器示例**：

```json
{"id":1,"op":"session_start","name":"server","cmd":["java","--enable-native-access=ALL-UNNAMED","-jar","server.jar"],"cwd":"/path/to/server","encoding":"GBK"}
{"id":2,"op":"write","session":"server","data":"help\n"}
{"id":3,"op":"logs","session":"server","limit":100}
```

**注意**：服务器必须由 term-bridge 启动（或由它接管），代理才能读取到从启动开始的全部输出；`encoding` 用于进程输出的平台编码（Windows 控制台通常 GBK，Linux 通常 UTF-8）。

---

## headless-client（无头客户端）

### 变体

- `mindustryx` 变体：依赖 `server.jar`（MindustryX 改端 core，NetClient 已移除 UI 依赖），协议与改端服务器天然一致
- `vanilla` 变体：依赖 Mindustry-159.7 core（构建 `:core:jar` 产出）

### 构建与运行

```bash
cd headless-client
./build.sh [server.jar 路径]    # 默认 ../../server/server.jar
./run.sh --listen 9090          # 第一个假玩家（控制端口 9090）
./run.sh --listen 9091          # 第二个假玩家（多实例=多进程）
```

### 协议（TCP + JSON 行协议）

响应 `{id, ok, result}`；事件推送：`ready`（启动完成）、`joined`（进入世界）、`disconnected`（断开）、`chat`（收到聊天 `{message}`）。

| 指令 | 参数 | 说明 |
|---|---|---|
| `connect` | `host?`, `port?`, `name?` | 连接服务器（默认 127.0.0.1:6567） |
| `disconnect` | - | 断开连接 |
| `status` | - | 连接状态/玩家名/单位坐标/队伍 |
| `chat` | `message` | 发送聊天消息 |
| `move` | `x`, `y` | 移动单位到坐标 |
| `stop` | - | 停止移动 |
| `build` | `x`, `y`, `block`, `rotation?` | 建造方块（加入建造计划） |
| `deconstruct` | `x`, `y` | 拆除方块 |
| `menu` | `menuId`, `option` | 响应服务器菜单（如 `/anon` 的菜单） |

> 完整命令表见 `headless-client/README.md`：新增 `moveTo`（目标移动/持续跟随）、`rotate`、
> `setPath`（路径点）、`perceive`（感知环境）、`scan`（搜索敌军/矿脉）、`pathfind`（A* 绕障寻路）、
> `mine`（采矿）、`attack`（射击/锁定）、`possess`（附身 AI 单位）、`mark`（地图标记）、
> `events`（事件缓冲）、`beh`（行为脚本热重载，sa reload 式）。
> 一键构建+重启：`build+restart.bat [端口]`。
| `ping` | - | 存活检查 |

### 与 term-bridge 配合的完整测试流程

1. `term-bridge/run.sh` → 控制端口 9090
2. 通过 term-bridge 启动服务器（`session_start`，见上）
3. 启动若干 headless-client（9090/9091/...），`connect` 到服务器
4. 用 `chat`/`move`/`build`/`menu` 驱动假玩家，验证插件行为（如 PVP 匿名：`/anon` 菜单弹出 → `menu` 响应 → 聊天匿名转发）

## 开发说明

- 全部工具 Java 实现、无外部依赖（mini JSON 手写），`javac` 即可构建
- 已知问题：JDK 的 javac 在同包类型解析上存在异常，源码中已通过显式 `import headlessclient.*` 规避
- **mindustryx 无头客户端连接状态**（2026-08-08 验证通过）：
  - 根因（已定位并修复）：`ConnectPacket.write` 在 uuid 后多写 8 字节 CRC32（`Writes.l`），而 `read` 端不消费这 8 字节（159.7 原版与改端 B481 相同，GitHub master 亦然）→ 任何"直接 write 产物"的 ConnectPacket 被服务器解析时必然字段错位（`mods.size` 读到 crc 随机字节 → `NegativeArraySizeException` → 反序列化失败断连，服务器端仅在 debug 日志级别可见 `Error during deserialization`）。官方链路正常是因为该包 ≥36 字节必走 LZ4 压缩分支，解压后 read 未消费的残留被下一次复用缓冲覆盖，不触发帧长度检查——但 read 本身仍以错位字节解析（`color`/`mods.size` 从 crc 读取），真实客户端 uuid 恰好让 `mods.size` 非负且字符串解析碰巧对齐（概率性，非可靠路径）
  - 修复：`DebugNet.send` 拦截 `ConnectPacket`，手工构造"服务器 read 期望布局"（无 crc）的帧（`[short len][id][short len][comp][data]`，≥36B 走 LZ4），经反射获取 `ArcNetProvider.client` 用 `sendTCPBuffer` 直发（复用 arc-net 已建立的 TCP 注册链路）
  - headless 补齐：`Vars.control`（Unsafe 绕过构造器，`Saves` 依赖 Core.assets）+ `control.input` stub（`sync()` 需要 `isBuilding`/`getSyncedPlans`）、`ui.join`（WorldStream 流程调 `hide()`）、`Core.camera`（sync 上传 snapshot 需要）；收到 `StateSnapshot` 后反射清除 `NetClient.connecting`（快照模式无 `WorldStream`，`finishConnecting` 不触发）
  - 客户端发聊天走 `Call.sendChatMessage(String)`（改端签名；`NetClient.sendMessage` 是服务器→客户端显示路径且改端 hook `onHandleSendMessage`→UIExt，headless 下抛异常）；聊天接收走改端 `SendMessageCallPacket2`（其 `read` 只存 DATA 不解析字段，需反射 DATA 反序列化出 message）
  - `move` 直接设置 `unit.x/y`（headless 无 Logic 模拟移动，snapshot 以 unit 位置上传）；`build`/`deconstruct` 加入 `unit.plans`，客户端 update 自动走向第一个计划目标格（否则单位位置被 snapshot 固定，服务器单位无法走到计划位置执行）
  - uuid 固定（`--uuid` 参数可覆盖）：服务器有 uuid 变更检测（同 IP 变化 uuid 超限自动 ban IP），每次随机 uuid 会导致 IP 被封
  - 联调验证：连接稳定（>10 分钟不踢）、`chat` 收发回环、`move` 位置同步、`build`（copper-wall 建成 `WallBuild`）、`deconstruct`（拆除完成 tile 变 air）、`menu`（语言设置/测试菜单弹出 + `MenuOptionChooseEvent` 服务器收到选择）全部通过
- 已知限制：
  - `KickCallPacket`/kick 流程在 headless 下 `NetClient.kick` 调 `Vars.logic.reset()` 会 NPE（`Vars.logic` 未初始化），被 DebugNet try-catch 捕获不影响主流程；正常连接不会走到 kick
  - 客户端进程被强杀（taskkill）时服务器可能将 IP 加入 ban 列表（异常断开相关），测试后需 `unban` 清理；正常 `stop` 会话退出无此问题
  - arc 旧版 `Client.connect` 的 UDP 注册竞态（`UdpConnection.send "Connection is closed"`）在快速重连时偶发，重试即可
- 双变体说明：同一份源码可编译出两个变体——`build.bat`（依赖 `server.jar` 改端）与 `build-vanilla.sh`（依赖 `server-release.jar` 原版）。改端专属 API（`LogicExt.mockProtocol`/`Net.allPacketClasses`）全部经 `MindustryXHooks` 反射访问，原版 jar 无 `mindustryX` 包时自动降级；`ConnectPacket` 兼容帧不引用改端独有字段（直接写 `Version.build` + `"official"`）。2026-08-08 已实测：vanilla 变体连接原版服务器（server-release.jar）连接稳定、玩家存活、chat 回环正常
