# PL-server-plugins

基于[微泽插件3.4.0](https://github.com/way-zer/ScriptAgent4MindustryExt/releases/tag/v3.4.0)开发的 Mindustry 服务器插件，当前适配 **Mindustry 160.5 + MindustryX 2026.10.02.B502**。

本插件大量使用AI制作，依旧感谢D老师神力。

我们的QQ群：1034687528

我们的Discord服务器：https://discord.gg/g7VSe4f9PV

这是一个开箱即用的服务器整合包：插件脚本、一键安装脚本、Windows/Linux 守护进程都有。装好 JDK 27 和游戏服务端就能跑。当前基线：**Mindustry 160.5 + MindustryX 2026.10.02.B502**。

## 快速开始

### 一键安装（推荐）

Windows（PowerShell 里执行，Win10 及以上）：

```powershell
irm https://raw.githubusercontent.com/power-line-server/PL-server-plugins/main/OneKeyInstall.ps1 | iex
```

Linux / Termux：

```bash
curl -fsSL https://raw.githubusercontent.com/power-line-server/PL-server-plugins/main/OneKeyInstall.sh | bash
```

脚本会依次做这几件事，每步都幂等，中途 Ctrl-C、断网、重新运行都不会搞坏，已完成的部分会自动跳过：

1. 装 git（Linux 用系统包管理器，Windows 用 winget）
2. 下载便携 JDK27 到 `~/.pls/jdk`（Linux）或 `.\pls\jdk`（Windows），免 root 免安装
3. 克隆本仓库（Linux 放 `~/PL-server-plugins`，Windows 放当前目录下的 `PL-server-plugins`）
4. 从 [MindustryX](https://github.com/TinyLake/MindustryX) 最新发行版下载 server 文件，改名 `server.jar`
5. 下载 [Mindustry 游戏源码](https://github.com/Anuken/Mindustry) 到 `~/mindustrySourceDir`（或当前目录下同名文件夹）
6. 把 `config/scripts/data/config.conf` 里的 `mindustrySourceDir` 指过去

GitHub 下载会先对所有镜像站（ghfast.top、gh-proxy.com、ghproxy.net 等）测速，自动挑最快的。国内网络也不用折腾。

装完运行 `bash ~/PL-server-plugins/run.sh`（Linux）或双击 `PL-server-plugins\run.bat`（Windows）即可开服。

### 手动安装

1. 安装 JDK27：[Oracle 官网](https://www.oracle.com/java/technologies/downloads/) 或任意发行版
2. 安装 git：[Windows 下载](https://git-scm.com/download/win)，Linux 直接 `sudo apt install git`（或对应包管理器）
3. `git clone https://github.com/power-line-server/PL-server-plugins.git`
4. 下载 [MindustryX 最新发行版](https://github.com/TinyLake/MindustryX/releases) 的 server 文件，重命名为 `server.jar` 放进仓库目录
5. `git clone https://github.com/Anuken/Mindustry.git`，把目录改名为 `mindustrySourceDir`，放在 `server.jar` 同级
6. 编辑 `config/scripts/data/config.conf`，把 `mindustrySourceDir` 的值改成源码路径（相对路径以 `server.jar` 所在目录为起点，比如 `../mindustrySourceDir`）
7. 运行 `run.bat`（Windows）或 `bash run.sh`（Linux）

初次启动要编译全部插件，等 10 分钟以上都正常。看到 `Server loaded` 和 `Opened a server on port 6567.` 就开服成功了，局域网直接能搜到，公网就在客户端里添加你的公网 IP。

## 目录结构

```
PL-server-plugins/
├── server.jar                    # MindustryX 游戏服务端（下载后放入，仓库不带）
├── run.bat / run.sh              # 启动脚本
├── watchdog.bat / watchdog.sh    # 守护启动器（restart 自动重启、卡死自动恢复）
├── OneKeyInstall.bat / .sh / .ps1  # 一键安装脚本
├── AGENTS.md                     # 给 AI 代理看的开发规范（对本仓库的修改要求）
├── README.md                     # 使用说明（安装 / 常见问题 / 开源说明）
├── LICENSE
├── coding_plan/                  # 开发规范与技能库（给 AI 代理用，跑服务器不需要）
├── devTolls/                     # 开发调试工具（bot 压测、终端桥接）
└── config/                       # 服务器配置与数据根目录
    ├── mods/                     # 服务端模组
    │   └── ScriptAgent4MindustryExt-3.4.0.jar   # 微泽插件本体
    └── scripts/                  # 全部插件脚本
        ├── bootStrap/            # 引导脚本
        ├── coreLibrary/          # 核心库
        ├── coreMindustry/        # 游戏核心扩展
        ├── wayzer/               # 业务插件
        ├── mapScript/            # 地图脚本
        ├── godpatches/           # 超级数据包
        └── data/                 # 配置文件、语言包、数据
```

### 根目录文件

| 文件 | 作用 |
|---|---|
| `run.bat` / `run.sh` | 启动脚本。Windows 直接双击，Linux `bash run.sh`。JVM 参数已配好 OOM 堆转储和 GC 日志（写到 `dumps/`） |
| `watchdog.bat` / `watchdog.sh` | 守护启动器，用它们代替 run 脚本启动可以获得：`/restart` 命令后自动重新拉起；服务器卡死（心跳文件 45 秒不更新）自动强杀重启；`exit` 正常关服则不重启。服务器每 30 秒写一次 `config/scripts/data/heartbeat.txt` 作为心跳 |
| `OneKeyInstall.*` | 一键安装脚本（见快速开始） |
| `AGENTS.md` | 代理工作指南。想给这个仓库贡献代码的 AI 代理先读这个 |
| `server.jar` | MindustryX 服务端本体，从官方 release 下载放入，仓库里没有 |
| `dumps/` | （运行时生成）OOM 堆转储和 GC 日志，排查内存问题用 |

### config/scripts 各模块

**bootStrap/** —— 引导脚本
- `default.kts` 启动时把所有脚本 enable 起来
- `generate.kts` 生成初始模块文件

**coreLibrary/** —— 核心库，别的插件都依赖它
- `commands/` 命令框架：`configCmd`（/config 在线改配置）、`control`（服务器控制）、`helpful`（辅助命令）、`hotReload`（sa reload 热重载）、`permissionCmd`（权限组管理）、`varsCmd`（变量查看）
- `db/` 数据库：H2 内嵌数据库（封禁、静默等数据的存储），`lib/DBApi.kt` 是表定义基类
- `extApi/` 可选扩展 API：KVStore（键值存储）、Mongo、Redis、RPC、远程事件——按需启用
- `lib/` 框架底层：`CommandApi`（命令 DSL）、`ConfigApi`（config.conf 读取）、`PlaceHoldApi`（`{tr}` 多语言与占位符渲染）、`PermissionApi`（权限节点）、`ColorApi`/`ColorExtractor`（颜色系统，ColorExtractor 需要 mindustrySourceDir 指向源码才能生成颜色数据）、`TimeHelper`、`ServicesExt` 等
- `lang.kts` / `lang.api.kt` 多语言服务（按玩家语言加载 bundle，mindustrySourceDir 的 bundles 优先）
- `langSync.kts` 语言包自动同步：启动时扫描所有脚本的 `{tr key}` 引用，新增 key 自动补进语言包，没人引用的 key 自动删掉
- `time.kts` 时区解析、`variables.kts` 玩家/世界变量注册

**coreMindustry/** —— 游戏核心扩展
- `console.kts` 控制台：终端交互、`exit` 命令（死锁防护，不可删）、本地命令管道（6568 端口，供工具注入命令）、日志颜色链路
- `menu.lib.kt` / `menu.new.kt` / `menu.kts` 菜单系统（MenuBuilder / MenuV2，弹窗菜单、分页、关闭按钮自动追加）
- `scoreboard.kts` 左侧计分板：`/board` 菜单开关显示与"自适应层级"（玩家开服务器菜单时计分板自动淡出让位）
- `searchCmd.kts` 命令搜索：`/search 关键字` 玩家弹菜单点执行，终端直接列文本
- `lib/` 命令注册实现、`variables.kts` 状态变量（`{state.wave}` 等）、`util/` 工具（newContent 注册新内容、nextChat 捕获下条聊天、packetHelper 数据包、textInput 文本输入框、spawnAround 环绕生成）

**wayzer/** —— 业务插件
- `cmds/` 管理命令：`clearUnit`（清除单位，可指定队伍）、`helpfulCmd`、`jsCmd`（JS 执行，管理员）、`mapsCmd`（地图列表/换图）、`permissionList`（权限节点导出）、`restart`（计划重启，带自拉起）、`saveMgr`（存档管理）、`serverStatus`、`share`（物品分享）、`vote*`（投票踢人/换图/观察/火力）
- `ext/` 扩展功能：`alert`（定时公告）、`announcements`（公告管理）、`atMention`（@提醒）、`commandBlock`（指令拦截）、`disableVanillaBan`（禁用原版封禁）、`gameInfo`（游戏记录）、`goServer`（跨服传送）、`godPatches`（超级数据包投票）、`heartbeat`（心跳文件，供 watchdog 检测卡死）、`ipLimit`（同 IP 拦截+白名单）、`mapBlacklist`（地图黑名单）、`messageFilter`（聊天过滤）、`moderator`（风纪委员）、`observer`（观战模式）、`perf`（性能分析）、`privateChat`（私聊）、`profiler`（async-profiler 采样）、`renderMap`（全图渲染成图片）、`schematicShare`（蓝图分享）、`serverLog`（服务器事件日志）、`tips`（小贴士）、`vanillaLocalize`（原版内容本地化）、`vip`（VIP 名单，按日期自动过期）、`welcomeMsg`（入服欢迎）
- `map/` 地图相关：`autoHost`（自动开服）、`autoSave`（自动存档，每 5 分钟）、`backCompatibility`（旧版本兼容）、`betterTeam`（PVP 队伍均分）、`mapInfo`（地图信息）、`mapSnap`（地图缩略图）、`pvpProtect`（PVP 开局保护期）、`resourceHelper`（资源站）
- `pvp/` PVP 专用：`pvpAlert`（PVP 行为警报）、`pvpChat`（PVP 队伍聊天）、`autoGameover`（空队自动判负）
- `reGrief/` 反破坏：`bugFixer`（地图 bug 修复）、`history`（方块操作历史记录）、`autoChangeMap`（无人游玩自动换图）
- `user/` 玩家系统：`ban`+`banStore`（banX 封禁系统，替代原版）、`banConnectCheck`（封禁检查）、`mute`（禁言）、`lang`+`langSetup`（玩家语言）、`nameExt`（名字处理）、`shortID`（短 ID）、`timezone`（时区）、`trChat`（AI 翻译聊天）、`tutorial`（新手指引）、`ext/chatPing`（聊天 @ 提醒）
- `lib/` 玩家数据类与事件定义、`Demolition.kts`（拆除检测）、`aiProvider.kts`（AI 服务）、`maps.kts`（地图管理器）、`module.kts`（模块定义）、`vote.kts`（投票系统）

**mapScript/** —— 地图脚本
- `lib/` 地图脚本公共库（内容扩展、标签支持、地图生成器、PosMark 标记格式）
- `shared/` 共享逻辑（posMark 世界信息版标记解析、hexed 十六进制地图）
- `tags/` 按标签启用的功能（creeper 粘液流体引擎、autoExchange 自动兑换、limitAir 空军限制、TDDrop 塔防掉落、towerDefend 塔防、mapRule 地图规则）
- 数字命名的文件（`999.kts`、`1001.kts` 等）是具体地图的专属脚本

**godpatches/** —— 超级数据包（仙古整合），玩家投票后生效

**data/** —— 配置与数据
- `config.conf` 主配置文件（HOCON 格式，所有可调项都在这，有注释）
- `global.properties` 全局变量（QQ 群号、discord 链接等，供踢人提示引用）
- `lang/bundle*.properties` 语言包（6 种语言），zh_CN 维护最全，其余由 langSync 自动同步


## 服务器运行后生成的东西

以下目录和文件是运行时产生的，不在仓库里，删了也会重新生成：

| 路径 | 是什么 |
|---|---|
| `config/saves/` | 自动存档（`.msav`，编号 100~199 循环覆盖，每 5 分钟存一次，可在 config.conf 调） |
| `config/logs/` | 运行日志（`log-0.txt`，滚动覆盖） |
| `config/maps/` | 原版内置地图数据 |
| `config/settings.bin` | 原版二进制设置（端口、名称等） |
| `config/settings_backups/` | 设置历史备份 |
| `config/assetCache/` | 资产缓存（图标、贴图提取） |
| `config/scripts/cache/compiled/` | 插件编译缓存（`.ktc` 字节码）。改脚本后删对应缓存才会重新编译 |
| `config/scripts/metadata/` | 模块元数据缓存 |
| `config/scripts/data/h2DB*.mv.db` | H2 数据库：封禁、禁言、VIP 等持久数据 |
| `config/scripts/data/kvStore.mv` | 键值存储（各插件的设置项） |
| `config/scripts/data/ipLimit_whitelist.txt` | 同 IP 限制白名单（每行一个 UUID，`#` 开头是注释） |
| `config/scripts/data/permissions.txt` | 权限节点清单（启动时自动生成） |
| `config/scripts/data/lang.ini`、`lang_backups/` | 语言缓存与备份 |
| `config/scripts/data/restart.flag` | 重启标记（`/restart` 命令写入，watchdog 据此自动拉起） |
| `config/scripts/data/heartbeat.txt` | 心跳文件（heartbeat 插件每 30 秒刷新，watchdog 据此判断卡死） |
| `dumps/` | OOM 堆转储（`.hprof`）与 GC 日志 |
| `_creeper_debug.txt` | creeper 地图调试输出（`/creeper` 调试命令写） |

## 地图脚本与参数（面向地图作者）

在**地图简介**里写 `[@标签]` 就能启用/配置对应的地图功能。地图简介就是地图的描述文本：单机编辑器里建图时写的描述，或存档 meta 的 description 字段。

标签写法规则：

- 启停：`[@xxx]` 写出来=开；`[@xxx=false]` 或 `[@xxx=off]` 关。
- 参数：`[@键=值]` 写在同一个方括号里，多个用空格分隔。
- 不是每个标签都等于"启用脚本"：有些只是参数（如 `[@creeperEmit=20]`），只在对应脚本已经启用时才生效。

示例：

```
[@CreeperWorld] [@creeperTeam=blue] [@creeperEmit=20] [@creeperViscosity=0.15]
```

### 可用标签总览

| 标签 | 功能 | 启用脚本 |
|---|---|---|
| `[@CreeperWorld]` | CreeperWorld 式粘稠流体引擎 | 是 |
| `[@autoExchange]` | 等价交换（CoreWar 物资体系） | 是 |
| `[@limitAir]` | 禁空军 | 是 |
| `[@mapRule]` | 地图权限组 | 是 |
| `[@permission]` | 地图自定义权限白名单 | 否（只读权限） |
| `[@TDDrop]` | 打怪掉落 | 是 |
| `[@towerDefend]` | 塔防模式 | 是 |
| `[@mapScript=ID]` | 强制加载指定地图脚本 | 否（加载调度） |

### CreeperWorld —— 粘稠流体引擎

Creeper World 风格的流体玩法：地图上铺一种粘稠流体，会向低处缓慢蔓延聚积，泡在里面的建筑和单位持续掉血，可以建方块挡水。玩法参数全部可调。

| 标签 | 默认 | 可用值 | 作用 |
|---|---|---|---|
| `[@CreeperWorld]` | 开 | `false`/`off` 停用 | 开关 |
| `[@creeperTeam=blue]` | `blue` | 逗号/花括号/空格分隔的队伍名 | 水源队伍：该队的建筑是"泉源"，且不怕水 |
| `[@creeperSource=core-nucleus]` | `core-nucleus` | 逗号分隔的方块名 | 泉源建筑类型（哪些建筑会吐水） |
| `[@creeperEmit=20]` | 20 | 0~200 | 源头深度。20=源头满级，10=50% 钍区，4=20% 钛区 |
| `[@creeperViscosity=0.15]` | 0.15 | 0.01~0.9 | 粘度系数，越小越粘稠、流得越慢 |
| `[@creeperEvaporation=0]` | 0 | ≥0 | 每 tick 每格蒸发量；0=封闭空间会灌满，>0 拆掉泉眼后会慢慢退水 |
| `[@creeperTps=2]` | 2 | 1~60 | 每秒计算 tick 数 |
| `[@creeperThreads=0]` | 0 | 0~16 | 并行线程数，0=自动（min(核数,4)） |
| `[@creeperMaxTiles=0]` | 0 | ≥0 | 最大活跃流体格数，0=无限 |
| `[@creeperTiers=12]` | 12 | 1~12 | 可视化作墙的档位数（按防御强度取前 N 档） |
| `[@creeperBuildDamage=10]` | 10 | ≥0 | 建筑满流体时每 tick 伤害 |
| `[@creeperUnitDamage=5]` | 5 | ≥0 | 单位满流体时每 tick 伤害 |
| `[@creeperMinWallDamage=0.5]` | 0.5 | ≥0 | 生成墙所需的最低伤害/格/tick，低于此不生成墙 |

调试命令（终端输入）：`creeperDebug pause|resume|step [N]|tps N|fluid x y`。

### autoExchange —— 等价交换

Core War 风格的物资体系：核心内所有物品按价值折算成统一分数，再等值换回物品（任意资源可看作等价）。物品评分：沙/裂变物质=0，钛/石墨/硅/火石=2，钍/塑钢/氧化物/爆炸化合物=3，钨/碳化/相位织物/合金=4，其余=1。启用后核心容量 ×10。无参数，`[@autoExchange]` 即可。

### limitAir —— 禁空军

`[@limitAir]`，无参数。每 3 秒把靠近敌方核心的飞行单位直接击杀并提示，禁止建造空军工厂。

### mapRule —— 地图权限组

- `[@mapRule=组名]`：把该权限组挂到权限系统，并让它的权限排到其他 `@` 组之前。适合做"这张图只能用某些指令"的特殊图。
- `[@permission=权限1;权限2;...]`：分号分隔的权限白名单，仅以下项生效：`-wayzer.vote.skipwave`、`-wayzer.ext.gather`（以 `-` 开头表示禁用白名单项，实现"这张图不让用某个技能/指令"）。

### TDDrop —— 打怪掉落

- `[@TDDrop]`：仅敌方波次（waveTeam）单位掉落
- `[@TDDrop=all]`：所有队伍单位都掉落
- 掉落物自动上缴到击杀方最近的敌方核心。

### towerDefend —— 塔防模式

`[@towerDefend]`，无参数。开局禁用弧线/长枪/空军工厂/修理台；出生点周围的地板是"路径"禁止建筑；波次单位出厂后走塔防 AI（飞行直扑核心，地面走迷宫）。常和 `[@TDDrop]` 一起用。

### 强制加载指定地图脚本

`[@mapScript=脚本ID]`：某些地图脚本不靠地图 ID 自动匹配（比如自制图想挂上 CoreWar 玩法），用这个标签强制加载。例：`[@mapScript=13545]` 让任意图启用 CoreWar 菜单玩法。

### 具体地图脚本

以下内置地图（`mapScript/` 下数字命名的文件）基本不用地图作者配参数，规则已写死，列出供参考怎么改：

| 地图 | 玩法 | 可配参数 |
|---|---|---|
| 999 | 周年庆沙盒六边形（认领地块） | 无 |
| 1001 | 基础六边形 pvp | 无 |
| 1002 | 小六边形 pvp | 简介写 `[@autoExchange]` 可开等价交换 |
| 1003 | 熔岩六边形 pvp | 无 |
| 1004 | 竞技场 pvp（禁止生产） | 无 |
| 1005 | 填海造陆 pvp | 无 |
| 1009 | erekir 六边形 pvp | 无 |
| 13545 | CoreWar 战争图 | `[@unitCost=1.5]` 单位价格倍率；`[@noConvertCopper]` 禁资源转换 |
| 14562 | 填海造陆玩法模块（随 1005 启用） | `[@waterFloor=deepwater]` 指定海水地板类型 |
| 14668 | Lord of War（拉斯战争） | 见下表 |

**14668（Lord of War）参数：**

| 标签 | 默认 | 作用 |
|---|---|---|
| `[@T1UC=8]` | 8 | T1 单位价格（dagger/nova/merui/elude/stell） |
| `[@T2UC=32]` | 32 | T2 单位价格（pulsar/poly/atrax/avert/locus） |
| `[@T3UC=128]` | 128 | T3 单位价格（mace/mega/cleroi/zenith/precept） |
| `[@T4UC=512]` | 512 | T4 单位价格（spiroct/cyerce/anthicus/antumbra/vanquish） |
| `[@T5UC=2048]` | 2048 | T5 单位价格（arkyid/vela/tecta/sei/scepter） |
| `[@LUC=65536]` | 65536 | Lord 单位价格（toxopid/aegires/collaris/eclipse/conquer/disrupt） |
| `[@TechDifficult=1]` | 1 | 科技难度系数 |
| `[@noSpecialFog]` | 关 | 关闭特殊战争迷雾 |

另有一个全局配置项 `mapScript.14668.sunsetModeP = 10`（落日计划启动概率，百分比，0~100），写在 `config.conf` 的 `mapScript.14668` 节。

### 世界信息版标记（预留接口）

server 支持用**世界信息版（worldMessage）**放标记：方块配置文本里每行写一条，行首必须是 `@`，格式 `@类型 键=值 键=值`。但目前**没有任何内置脚本消费这些标记**（接口已就绪，留给后续功能脚本注册使用）。地图作者现在写 `@xxx` 不会产生效果，等有对应脚本再说。

## 常见问题

**初次启动很慢？** 正常，要编译全部插件，等 10 分钟以上。之后再启动有缓存就快了。

**端口被占用？** `Opened a server on port 6567` 报 already in use 说明有别的服务器占着 6567 或控制管道 6568，先停掉旧的。

**想开第二个服务器实例？** 把 config.conf 里 `coreMindustry.console.cmdPipePort` 改个端口（如 6570），存档目录分开即可。


**改完脚本没生效？** 删 `config/scripts/cache/compiled/` 里对应目录的 `.ktc` 再重启；运行中的服务器可以用 `sa reload 脚本id --noCache` 热重载。

**服务器半夜挂了？** 用 `watchdog.bat`/`watchdog.sh` 启动（而不是 run 脚本），卡死会自动重启，`/restart` 也能真重启。

## 开源说明

开源仓库包含微泽插件本体（`config/mods/ScriptAgent4MindustryExt-3.4.0.jar`，版权延续上游，见 [LICENSE](LICENSE)），以及在其之上开发的**已开源**插件脚本；`server.jar`（Mindustry 160.5 + MindustryX 2026.10.02.B502）不在仓库内，由一键安装脚本从官方渠道下载。

**本开源包不包含以下闭源部分**（它们只存在于作者的开发仓库与主服）：

- WebUI 网页管理后台（及配套前端、HTTPS 证书脚本、API 密钥系统）
- 技能系统（`skills` / `skillsAdvanced` / `skillSwitch`）
- 单位工厂（`unitFactory`）
- 音乐系统（`music`）
- 后缀/头衔（`suffix` / `title`）
- 玩家信息数据层（`playerInfo`）、集合传送（`gatherTp`）、每日一言（`dailyQuote`）、服务器简介（`introduce`）
- 防炸核心（`antiCoreGrief`）、反逻辑病毒（`antiLogicVirus`）、匿名 PVP（`pvpAnonymous`）

因此开源包启动后的脚本数量与功能会少于主服；脚本缺失导致的报错已在开源侧清理（见本仓库提交记录）。
