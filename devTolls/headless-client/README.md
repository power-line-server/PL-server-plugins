# headless-client — 无头玩家客户端（假玩家）

模拟真实玩家通过**真实网络协议**连接 Mindustry 服务器（MindustryX 改端 / 原版 159.7），
供代理与开发者测试服务器插件（聊天/菜单/封禁/投票/建造等交互验证）。
多实例 = 多进程，每实例独立控制端口。

## 特性

- 真实 TCP 连接（ConnectPacket 兼容帧，改端/原版通用）
- 完整菜单交互：普通菜单 + 跟随菜单（`FollowUpMenuCallPacket`）+ 文本输入框（`TextInputCallPacket`）+ 弹窗（`InfoMessageCallPacket`）
- 自动重连：服务器重启后 10 秒自动回服（被动断开；主动 `disconnect` 不重连）
- 双变体构建：`build.bat`（依赖 `server.jar` 改端）/ `build-vanilla.sh`（依赖原版 core）
- 固定 uuid（`--uuid` 可覆盖）：服务器有 uuid 变更检测，随机 uuid 会导致 IP 被封

## 构建

```bash
cd headless-client
build.bat                # Windows（依赖 ../../server/server.jar）
./build.sh               # Linux/macOS
```

## 运行

```bash
cd headless-client
build+restart.bat 9090     # 一键：杀旧进程(按 pid 文件) + 编译 + 启动（cmd 窗口运行）
run.bat --listen 9090                # 第一个假玩家（控制端口 9090）
run.bat --listen 9091 --uuid <b64>   # 第二个假玩家（不同 uuid，避免 idInUse/ipLimit）
```

| 参数 | 说明 |
|---|---|
| `--listen <port>` | 控制端口（默认 9090） |
| `--uuid <base64>` | 玩家 uuid（默认固定值；多实例需不同 uuid） |

> 多假玩家注意：服务器 ipLimit 白名单 `config/scripts/data/ipLimit_whitelist.txt`
> 需加入每个测试 uuid（每行一个 base64）。

## 协议（TCP + JSON 行协议，每行一个 JSON）

请求 `{id, op, ...}` → 响应 `{id, ok, result}`；事件推送（`{event: ...}`）：

- `ready` 启动完成、`joined` 进入世界、`disconnected {reason}`、`chat {message}`、
  `menu {menuId,title,message,options}`、`textInput {id,title,...}`、`info {message}`、`debug {step}`

| 指令 | 参数 | 说明 |
|---|---|---|
| `connect` | `host?`, `port?`, `name?` | 连接服务器（默认 127.0.0.1:6567） |
| `disconnect` | - | 主动断开（不触发自动重连） |
| `status` | - | 连接状态/玩家名/单位坐标/队伍 |
| `chat` | `message` | 发送聊天消息（服务器 `/命令` 同样经此发送） |
| `move` | `x`, `y` | 移动单位到坐标（持续上报，避免被 snapshot 拉回） |
| `stop` | - | 停止移动 |
| `follow` | `name` | 持续跟随指定名字的玩家 |
| `unfollow` | - | 取消跟随 |
| `moveTo` | `target`, `persistent?` | 移动到目标：`"x,y"` 坐标 / 玩家名 / `player:名` / `team:名`；`persistent=true` 持续跟踪目标当前位置 |
| `rotate` | `angle` 或 `target` | 旋转：固定角度 / 朝向目标（玩家名/坐标）；`"off"` 关闭 |
| `setPath` | `waypoints` | 设置路径点队列（`[[x,y],...]`，由移动状态机逐点前进，走完停止） |
| `stopRotate` | - | 停止旋转 |
| `build` | `x`, `y`, `block`, `rotation?` | 建造方块（加入建造计划，单位自动走位） |
| `deconstruct` | `x`, `y` | 拆除方块 |
| `menu` | `menuId`, `option` | 响应服务器菜单（普通/跟随菜单通用） |
| `textInput` | `id`, `text` | 响应服务器文本输入框（如 banX 时长/原因） |
| `perceive` | `radius?` | 感知环境：半径内玩家/单位/建筑列表（名字/类型/队伍/坐标/距离/敌我） |
| `scan` | `what` | 搜索最近目标：`enemyUnit` / `enemyBuild` / `ore` / `team:队伍名` / `unit:类型` |
| `pathfind` | `target` | 寻路到目标：地面单位本地 A* 绕障自动移动；飞行单位直线（返回路径点数） |
| `tile` | - | 查询脚下地形：`floor`/`block`/`deep`/`solid`/`build` |
| `mine` | `target` | 采矿：`off` 停止 / `auto` 自动找最近矿脉 / `x,y` 指定坐标（持续采矿） |
| `attack` | `target` | 攻击：`off` 停止 / `auto` 最近敌军单位（持续瞄准射击）/ `x,y`/玩家名 |
| `possess` | `target` | 附身：`off`/`clear` 回核心 / `auto` 最近同队 AI 单位 / `unit:类型` / `build:x,y` 进入炮塔/可控制建筑（`ControlBlock.unit()` 驾驶，与玩家 Ctrl+点击一致；核心等走 `buildingControlSelect` 兜底）（受服务器规则限制） |
| `mark` | `target`, `message?` | 地图标记点位（`Call.pingLocation`，全服可见） |
| `events` | - | 拉取最近 200 条事件缓冲（短连接错过的事件可补拉；`events.log` 文件同内容可 tail） |
| `beh` | `cmd` | 行为脚本：`reload`（热重载 behaviors/*.json）/ `list` / `status` |
| `ping` | - | 存活检查 |

## 行为脚本（sa reload 式热重载）

`behaviors/*.json` 定义规则（chat 触发 / timer 定时 + 可选 scan 条件 + 动作原语），
改文件后 `{"op":"beh","cmd":"reload"}` 热生效，**不重启进程**（参考服务器 sa reload 体验）。

```json
{
  "name": "demo",
  "rules": [
    {"id": "greet", "on": "chat", "match": "你好",
     "do": [{"chat": "你好！我是 bot"}, {"wait": 600}, {"chat": "行为脚本驱动"}]},
    {"id": "report-enemy", "on": "timer", "every": 20000,
     "if": {"scan": "enemyUnit", "dist_lt": 1500},
     "do": [{"log": "附近发现敌军"}, {"mark": {"target": "auto", "text": "敌军!!"}}]}
  ]
}
```

- 触发 `on`：`chat`（消息包含 `match`）/ `timer`（每 `every` ms）
- 条件 `if`：`{"scan": "enemyUnit", "dist_lt": 800}`（scan 结果 found 且 dist < 阈值）
- 动作 `do`（顺序执行，`wait` 阻塞）：`chat` / `wait` / `log` / `moveTo` / `attack` /
  `mine` / `possess` / `mark`（`target` 支持 `auto`=最近敌军，`text` 为标记文本）

## 完整菜单流程示例（banX 封禁类型菜单）

```json
{"id":1,"op":"connect","host":"127.0.0.1","port":6567,"name":"bot"}
// ← 事件 menu: {title:"选择封禁类型", menuId:..., options:["在线玩家|UID|UUID|IP","关闭"]}
{"id":2,"op":"menu","menuId":1084606990,"option":0}
// ← 事件 menu: {title:"选择目标玩家", ...}
{"id":3,"op":"menu","menuId":... ,"option":0}
// ← 事件 textInput: {id:..., title:"请输入封禁时长(分钟)"}
{"id":4,"op":"textInput","id":..., "text":"30"}
```

## 已知限制

- `KickCallPacket`/kick 流程在 headless 下 `NetClient.kick` 调 `Vars.logic.reset()` 会 NPE（`Vars.logic` 未初始化），被 DebugNet try-catch 捕获不影响主流程
- 进程被强杀（taskkill）时服务器可能将 IP 加入 ban 列表，测试后需 `unban` 清理；正常断开无此问题
- 快速重连时 arc 旧版 UDP 注册竞态偶发，重试即可
- 服务器防刷屏会拒绝重复聊天消息（"You may not send the same message twice"），行为脚本避免短时重复消息
- 官方 `Astar` 的 PQueue 固定容量 10000（≤200x200 地图），大地图寻路失败——已自实现 A*（无容量限制）
- 服务器端换单位（`player.unit(new)`）客户端不可见（重连才生效）；`possess` 是客户端感知单位变化的途径

## 实现要点（备忘）

- 连接：`DebugNet.send` 拦截 `ConnectPacket`，手工构造服务器 read 期望布局（无 crc 的 LZ4 帧）
- headless 补齐：`Vars.control`/`control.input`/`ui.join`/`Core.camera` stub、快照后反射清除 `NetClient.connecting`
- 断开保护：`Core.scene` stub（NetClient 断开时 `UI.showErrorMessage` 构造 Dialog 会 NPE）+ Disconnect 清理 + 清零 `lastSnapshotTimestamp`（防快照超时弹窗）
- 聊天：发送走 `Call.sendChatMessage(String)`（改端签名）；接收走 `SendMessageCallPacket` hook
- 移动：直接设置 `unit.x/y` + `moveAt(0,0)` 持续上报（snapshot 以单位位置覆盖）
- 实体同步：headless 下 `Vars.logic` 为 null，实体 update 循环不执行（其他玩家位置冻结在创建值）——手动驱动 `Groups.sync` 实体 `interpolate()`（仅非本地实体），修复实时位置同步与 persistent 跟随
