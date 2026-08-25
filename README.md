# PL-server-plugins

基于[微泽插件3.4.0](https://github.com/way-zer/ScriptAgent4MindustryExt/releases/tag/v3.4.0)开发的 Mindustry 服务器插件。

本插件大量使用AI制作，依旧感谢D老师神力。

我们的QQ群：1034687528

我们的Discord服务器：https://discord.gg/g7VSe4f9PV

这是一个开箱即用的服务器整合包：插件脚本、WebUI 管理后台、一键安装脚本、Windows/Linux 守护进程都有。装好 JDK 和游戏服务端就能跑。

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
2. 下载便携 JDK26 到 `~/.pls/jdk`（Linux）或 `.\pls\jdk`（Windows），免 root 免安装
3. 克隆本仓库（Linux 放 `~/PL-server-plugins`，Windows 放当前目录下的 `PL-server-plugins`）
4. 从 [MindustryX](https://github.com/TinyLake/MindustryX) 最新发行版下载 server 文件，改名 `server.jar`
5. 下载 [Mindustry 游戏源码](https://github.com/Anuken/Mindustry) 到 `~/mindustrySourceDir`（或当前目录下同名文件夹）
6. 把 `config/scripts/data/config.conf` 里的 `mindustrySourceDir` 指过去

GitHub 下载会先对所有镜像站（ghfast.top、gh-proxy.com、ghproxy.net 等）测速，自动挑最快的。国内网络也不用折腾。

装完运行 `bash ~/PL-server-plugins/run.sh`（Linux）或双击 `PL-server-plugins\run.bat`（Windows）即可开服。

### 手动安装

1. 安装 JDK26：[Oracle 官网](https://www.oracle.com/java/technologies/downloads/) 或任意发行版
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
├── gen_keystore.bat / .sh        # WebUI HTTPS 自签证书生成
├── AGENTS.md                     # 给 AI 代理看的开发规范（对本仓库的修改要求）
├── WEBUI_README.md               # WebUI 管理后台使用说明
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
        └── data/                 # 配置文件、语言包、WebUI 前端
```

### 根目录文件

| 文件 | 作用 |
|---|---|
| `run.bat` / `run.sh` | 启动脚本。Windows 直接双击，Linux `bash run.sh`。JVM 参数已配好 OOM 堆转储和 GC 日志（写到 `dumps/`） |
| `watchdog.bat` / `watchdog.sh` | 守护启动器，用它们代替 run 脚本启动可以获得：`/restart` 命令后自动重新拉起；服务器卡死（心跳文件 45 秒不更新）自动强杀重启；`exit` 正常关服则不重启。服务器每 30 秒写一次 `config/scripts/data/heartbeat.txt` 作为心跳 |
| `OneKeyInstall.*` | 一键安装脚本（见快速开始） |
| `gen_keystore.bat` / `gen_keystore.sh` | 给 WebUI 的 HTTPS 生成自签证书。自动探测公网 IP、自动找 keytool、自动读 config.conf 里的证书密码。服务器换公网 IP 后重新跑一次并重启 |
| `AGENTS.md` | 代理工作指南。想给这个仓库贡献代码的 AI 代理先读这个 |
| `WEBUI_README.md` | WebUI 管理后台的面向使用者的说明：访问、证书导入、登录、换 IP、FAQ |
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
- `langSync.kts` 语言包自动同步：启动时扫描所有脚本的 `{tr key}` 和 WebUI 前端的 i18n 引用，新增 key 自动补进语言包，没人引用的 key 自动删掉
- `time.kts` 时区解析、`variables.kts` 玩家/世界变量注册

**coreMindustry/** —— 游戏核心扩展
- `console.kts` 控制台：终端交互、`exit` 命令（死锁防护，不可删）、本地命令管道（6568 端口，供工具注入命令）、日志颜色链路
- `menu.lib.kt` / `menu.new.kt` / `menu.kts` 菜单系统（MenuBuilder / MenuV2，弹窗菜单、分页、关闭按钮自动追加）
- `scoreboard.kts` 左侧计分板：`/board` 菜单开关显示与"自适应层级"（玩家开服务器菜单时计分板自动淡出让位）
- `searchCmd.kts` 命令搜索：`/search 关键字` 玩家弹菜单点执行，终端直接列文本
- `lib/` 命令注册实现、`variables.kts` 状态变量（`{state.wave}` 等）、`util/` 工具（newContent 注册新内容、nextChat 捕获下条聊天、packetHelper 数据包、textInput 文本输入框、spawnAround 环绕生成）

**wayzer/** —— 业务插件
- `cmds/` 管理命令：`clearUnit`（清除单位，可指定队伍）、`gatherTp`（集合传送）、`helpfulCmd`、`jsCmd`（JS 执行，管理员）、`mapsCmd`（地图列表/换图）、`permissionList`（权限节点导出）、`restart`（计划重启，带自拉起）、`saveMgr`（存档管理）、`serverStatus`、`share`（物品分享）、`vote*`（投票踢人/换图/观察/火力）
- `ext/` 扩展功能：`alert`（定时公告）、`announcements`（公告管理）、`atMention`（@提醒）、`commandBlock`（指令拦截）、`disableVanillaBan`（禁用原版封禁）、`gameInfo`（游戏记录）、`goServer`（跨服传送）、`godPatches`（超级数据包投票）、`heartbeat`（心跳文件，供 watchdog 检测卡死）、`ipLimit`（同 IP 拦截+白名单）、`mapBlacklist`（地图黑名单）、`messageFilter`（聊天过滤）、`moderator`（风纪委员）、`observer`（观战模式）、`perf`（性能分析）、`playerInfo`（玩家信息）、`privateChat`（私聊）、`profiler`（async-profiler 采样）、`renderMap`（全图渲染成图片）、`schematicShare`（蓝图分享）、`serverLog`（服务器事件日志）、`tips`（小贴士）、`vanillaLocalize`（原版内容本地化）、`vip`（VIP 名单，按日期自动过期）、`welcomeMsg`（入服欢迎）
- `map/` 地图相关：`autoHost`（自动开服）、`autoSave`（自动存档，每 5 分钟）、`backCompatibility`（旧版本兼容）、`betterTeam`（PVP 队伍均分）、`mapInfo`（地图信息）、`mapSnap`（地图缩略图）、`pvpProtect`（PVP 开局保护期）、`resourceHelper`（资源站）
- `pvp/` PVP 专用：`pvpAlert`（PVP 行为警报）、`pvpChat`（PVP 队伍聊天）、`autoGameover`（空队自动判负）
- `reGrief/` 反破坏：`bugFixer`（地图 bug 修复）、`history`（方块操作历史记录）、`autoChangeMap`（无人游玩自动换图）
- `user/` 玩家系统：`ban`+`banStore`（banX 封禁系统，替代原版）、`banConnectCheck`（封禁检查）、`mute`（禁言）、`lang`+`langSetup`（玩家语言）、`nameExt`（名字处理）、`shortID`（短 ID）、`timezone`（时区）、`trChat`（AI 翻译聊天）、`tutorial`（新手指引）、`ext/chatPing`（聊天 @ 提醒）
- `lib/` 玩家数据类与事件定义、`Demolition.kts`（拆除检测）、`aiProvider.kts`（AI 服务）、`maps.kts`（地图管理器）、`module.kts`（模块定义）、`vote.kts`（投票系统）、`dailyQuote` 等

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
- `webui/` WebUI 前端（页面 HTML + assets 里的 app.js 等，直接由后端服务）

### config/scripts/data/webui 前端

登录、仪表盘、玩家管理（封禁/解封/详情）、地图存档管理、控制台（实时日志+命令执行）、公告管理、用户管理（WebUI 账号/权限节点/限速配置）、设置（主题/背景/限流）。界面语言跟随浏览器语言，支持中/英/日/韩/俄/繁中。详细用法见根目录 `WEBUI_README.md`。

## 服务器运行后生成的东西

以下目录和文件是运行时产生的，不在仓库里，删了也会重新生成：

| 路径 | 是什么 |
|---|---|
| `config/saves/` | 自动存档（`.msav`，编号 100~199 循环覆盖，每 5 分钟存一次，可在 config.conf 调） |
| `config/logs/` | 运行日志（`log-0.txt`，滚动覆盖） |
| `config/maps/` | 原版内置地图数据 |
| `config/settings.bin` | 原版二进制设置（端口、名称等） |
| `config/settings_backups/` | 设置历史备份 |
| `config/assetCache/` | 资产缓存（图标、贴图提取，WebUI 内容图标用） |
| `config/scripts/cache/compiled/` | 插件编译缓存（`.ktc` 字节码）。改脚本后删对应缓存才会重新编译 |
| `config/scripts/metadata/` | 模块元数据缓存 |
| `config/scripts/data/h2DB*.mv.db` | H2 数据库：封禁、禁言、VIP 等持久数据 |
| `config/scripts/data/kvStore.mv` | 键值存储（各插件的设置项） |
| `config/scripts/data/ipLimit_whitelist.txt` | 同 IP 限制白名单（每行一个 UUID，`#` 开头是注释） |
| `config/scripts/data/permissions.txt` | 权限节点清单（启动时自动生成） |
| `config/scripts/data/lang.ini`、`lang_backups/` | 语言缓存与备份 |
| `config/scripts/data/users.json` | WebUI 登录用户与 API 密钥 |
| `config/scripts/data/webuiBackground/` | WebUI 用户自定义背景图 |
| `config/scripts/data/webui/keystore.p12` | WebUI HTTPS 证书（用 gen_keystore 脚本生成） |
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
- `[@permission=权限1;权限2;...]`：分号分隔的权限白名单，仅以下项生效：`-wayzer.user.skills.*`、`-wayzer.vote.skipwave`、`-wayzer.ext.gather`（以 `-` 开头表示禁用白名单项，实现"这张图不让用某个技能/指令"）。

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

## WebUI 管理面板（使用说明）

WebUI 是服务器的网页管理面板：看在线玩家、封禁/解封、换图、回档、发公告、执行控制台命令、看实时日志、管理用户与 API 密钥。**不用装任何客户端，浏览器打开就行；所有功能都有 REST API，脚本/机器人也能调用。**

---

## 1. 访问地址

服务器启动后，浏览器打开：

```
https://服务器公网IP:8080
```

（本机访问用 `https://127.0.0.1:8080`；局域网内用 `https://内网IP:8080`）

> 注意是 **https** 不是 http——WebUI 已启用 TLS 加密，抓包也看不到内容。用 http 会连接失败（这是正常的，防止明文传输）。

## 2. 首次使用（只需要做一次）

浏览器第一次访问会提示"**您的连接不是私密连接 / 证书不受信任**"——这是自签证书的正常现象，不是出问题了。按下面导入证书后，以后访问就完全正常：

**Windows（Chrome/Edge）：**
1. 在服务器上找到 `config\scripts\data\webui\keystore.p12`
2. 双击它 → "下一步" → 输入证书库密码（就是 `config.conf` 里 `sslKeystorePass` 的值）→ 存储位置选"**受信任的根证书颁发机构**" → 完成
3. 重启浏览器，再访问就是绿色锁 🔒，不再有任何提示

**macOS（Safari/Chrome）：**
1. 把 `keystore.p12` 拷到 Mac 上双击 → 钥匙串访问 → 输入密码
2. 打开"钥匙串访问"App → 找到 Mindustry WebUI 证书 → 双击 → 信任 → 改为"始终信任"

**手机（Android/iOS）：** 浏览器访问后会提示证书不受信任，一般有"高级 → 继续访问"入口；要彻底消除提示需要把证书安装到系统信任库（Android 需"CA 证书"安装，iOS 需描述文件，比较麻烦，一般用"继续访问"即可）。

## 3. 登录与用户系统

打开后进入登录页，输入**用户名和密码**：

- **超级管理员**（全部权限，唯一）：用户名 `admin`（config.conf 的 `webui.adminUser`），密码 = config.conf 的 `webui.token` 的值（默认 `AAA`，**强烈建议改掉**）
- **普通用户**：由管理员在"用户管理"页创建（见下文），可自由分配权限节点
- **游客**（默认禁用）：在"用户管理"页打开"游客模式"开关后，**未登录访问自动变成游客身份**——无需登录即可看仪表盘（仅服务器状态）；游客没有密码、不需要登录。禁用后未登录访问一律 401 跳登录页
- 登录成功后浏览器记住会话，不用每次输

### 用户管理（管理员专属）

管理员登录后，侧边栏会出现 **用户管理** 页面：

1. **新建用户**：填用户名 + 密码（至少 6 位），勾选权限节点——19 个细粒度权限（服务器状态/小地图/玩家查看/封禁/解封/换图/回档/删档/控制台命令/日志/公告等自由组合，见第 10.4 节）
2. **编辑用户**：改密码（留空不改）、启用/禁用、调整权限
3. **游客开关**：页面顶部的开关（唯一开关），开启后未登录访问自动降级为游客（仅仪表盘）；默认关闭，状态存 `users.json`
4. 管理员和游客两个内置角色不可删除；普通用户无"用户管理/改 token"权限（这两个节点不对外分配）
5. **背景图**：每个用户的背景独立（主题在前端本地保存）。任何登录用户都可在"设置"页选择自己的背景，背景图存放于 `data/webui/webuiBackground/`；上传/删除图库需要 `webui.api.backgroundUpload` 权限

## 4. 服务器换 IP 了怎么办

证书是和 IP 绑定的（防别人冒充），换 IP 后需要重新生成一次证书：

**Windows：** 在服务器目录（和 `run.bat` 同级的 `gen_keystore.bat`）双击运行，或命令行：

```
gen_keystore.bat
```

**Linux：**

```bash
./gen_keystore.sh
```

脚本会自动完成三件事：探测新公网 IP、自动找 JDK 的 keytool、读取 config.conf 里的证书密码，然后生成新证书。**然后重启服务器，并把新证书重新导入一次浏览器**（第 2 节步骤）。

如果自动探测失败（服务器出不了网），手动指定 IP：

```
gen_keystore.bat 1.2.3.4
```

## 5. 换管理员密码/token（强烈建议）

`config.conf` 里：

```
webui {
  adminUser = "admin"     # ← 管理员用户名
  token = "AAA"           # ← 管理员登录密码 + API 管理 token，换成你自己的
}
```

改完重启服务器生效。管理员网页登录密码和 API 管理 token 是同一个值（`token`）；也可以设成 `auto`：每次启动自动生成新 token 并打印在服务器日志里（更安全，但每次启动都要去日志里找）。

## 6. API 密钥（给第三方/脚本用）

**场景**：你想给一个机器人、群友、第三方程序开 WebUI 的某些功能，但不想给管理员 token（给了 = 把服务器钥匙给了别人）。在 **设置页 → API 密钥** 生成一个**密钥**：

- **独立权限**：勾选要给的权限节点（比如只给"查看服务器状态 + 玩家列表"），密钥就只能干这些
- **独立限速**：可以给密钥单独设置限速（次/秒），防止第三方刷爆（见第 11 节）
- **可选过期**：可以设 N 天后自动失效（0 = 永不过期）
- **可编辑**：生成后随时改名字/权限/限速/过期时间，secret 不变
- **可删除**：删除后立即失效

生成时返回的 `secret` **只显示这一次**，服务器只存它的 SHA-256 哈希——丢了只能删除重建。调用方式：`Authorization: Bearer <secret>`，与登录 session 用法相同。详见第 10.5 节。

## 7. 常见问题

| 问题 | 解决 |
|---|---|
| 打不开页面 | 确认服务器已启动、端口 8080 没被防火墙挡、用的是 `https://` |
| 一直提示证书不受信任 | 证书没导入对，重做第 2 节；换过 IP 的话重跑第 4 节脚本 |
| 忘记 token | 打开 `config.conf` 看 `webui.token`，或直接改一个新值重启 |
| 提示 IP 被锁定 15 分钟 | 输错 token 太多次，等 15 分钟或重启服务器 |
| 换 IP 后提示"主机名不匹配" | 证书 SAN 是旧 IP，重跑第 4 节脚本重新生成 |
| 未登录打不开仪表盘 | 游客模式默认关闭，去"用户管理"页打开"游客模式"开关 |
| 设置页保存限速提示失败 | 检查填的值是否合法（速率 ≥ 0，突发容量 ≥ 1）；数值越界会被后端纠正 |
| API 调用返回 429 | 被限速了：整体/写操作/节点级/API 级/密钥级任一打满（见第 11 节） |

## 8. 安全须知（重要）

- **`keystore.p12` 包含私钥，千万不要公开**（不要上传网盘/发群/提交到公开仓库）——拿到它的人能解密你的流量、冒充你的服务器。它已加入 `.gitignore` 不会进仓库。
- **token 就是服务器的钥匙**：拿到 token = 能在 WebUI 上执行服务器命令。不要发给别人。要给第三方功能，用 API 密钥（第 6 节）。
- **API 密钥的 secret 也只显示一次**，发给第三方后如果泄露，删除重建即可。
- WebUI 的所有操作都有审计日志（`config\scripts\data\webui\audit.log`），可疑操作可以查。
- 连续输错密码 5 次，该用户名会被锁定 15 分钟（防爆破）。
- `users.json` 存的是 PBKDF2 加盐哈希，`keys.json` 存的是 SHA-256 哈希，都不存明文。

## 9. 关闭 HTTPS（不推荐）

实在想退回明文 HTTP：`config.conf` 里 `webui { sslEnabled = false }`，重启服务器。抓包就能看到所有流量，包括 token。

---

## 10. API 调用参考（脚本 / 机器人 / 第三方程序）

WebUI 全部功能都有 REST API。基础地址：`https://服务器地址:8080`。

### 10.1 认证方式（四种身份）

| 请求头 | 身份 | 权限 |
|---|---|---|
| `Authorization: Bearer <管理token>` | 超级管理员 | 全部权限（最简单） |
| `Authorization: Bearer <sessionToken>` | 对应用户 | 该用户勾选的权限节点 |
| `Authorization: Bearer <API密钥secret>` | 密钥身份 | 该密钥分配的权限节点 |
| 无 token（游客模式开启时） | 游客 | 仅 `webui.api.status`（仪表盘） |
| 无 token（游客模式关闭时） | 无 | HTTP **401** |

**管理 token**：即 `config.conf` 里 `webui.token` 的值（默认 `AAA`）。`Bearer` 头直接带上就是管理员，适合你自己的脚本。

**用户 session**：先 `POST /api/login` 换 sessionToken，再带 `Bearer <sessionToken>`，权限与网页登录一致。

**API 密钥**：设置页生成（见 10.5），适合发给第三方。

### 10.2 登录与登出

```
POST /api/login          # 登录，返回 sessionToken
POST /api/logout         # 登出（删 session）
GET  /api/session/check  # 当前会话是否有效
GET  /api/me             # 当前身份信息 {username, role, permissions}
```

**登录请求体（推荐）：**

```json
{ "username": "admin", "password": "AAA" }
```

**登录请求体（旧版兼容，token 当管理员密码）：**

```json
{ "token": "AAA" }
```

**成功响应：**

```json
{ "code": 0, "msg": "ok", "data": { "sessionToken": "a1b2...", "username": "admin", "role": 2 } }
```

登录限流：按用户名连续失败 5 次锁 15 分钟（HTTP 429）。`/api/me` 可用于检查当前身份与权限列表（前端侧边栏按它过滤菜单）。

### 10.3 公开 API（无需凭证）

| 端点 | 说明 |
|---|---|
| `POST /api/login` | 登录 |
| `POST /api/logout` | 登出（幂等，无凭证调用也无害） |
| `GET /api/auth/guest-status` | 游客模式是否开启（登录页用） |
| `GET /api/session/check` | 会话是否有效 |
| `GET /api/colors` | 颜色表（前端初始化） |
| `GET /api/i18n?lang=xx` | 语言包（前端初始化） |
| `GET /api/i18n/langs` | 可用语言列表 |
| `GET /api/content-icon/*.png` | 方块图标（页面渲染用） |
| `GET /api/me` | 当前身份（无凭证时游客降级或 401） |

> 注意：**游客模式开启时**，无 token 的请求会**自动变成游客身份**（只能访问 status/背景等游客权限内的接口）；游客模式关闭时无 token 一律 401。也就是说上面这些"公开"接口里，`/api/me` 在游客模式下会返回游客身份而不是 401。

### 10.4 权限节点与 API 全表

每个 API 挂在一个**权限节点**下（19 个可分配 + 2 个管理员专属）。用户/密钥勾选了节点，就能调用该节点下的所有接口。**管理员（管理 token 或 admin 登录）拥有全部权限**；请求到达时先认证（无身份 401），再检查权限（无权限 403）。

| 权限节点 | 端点 | 说明 |
|---|---|---|
| `webui.api.status` | `GET /api/status` | 服务器状态（TPS/内存/在线/地图/波次等，游客固定拥有） |
| `webui.api.status` | `GET /api/daily-quote` | 每日一言 |
| `webui.api.status` | `GET /api/background` | 当前用户背景设置 |
| `webui.api.snapshot` | `GET /api/world/snapshot` | 当前世界小地图截图（PNG；有 status 权限的用户也可访问） |
| `webui.api.players` | `GET /api/players` | 在线玩家列表 |
| `webui.api.players` | `GET /api/players/search?q=xxx` | 搜索玩家（支持离线，匹配名称/uid/uuid） |
| `webui.api.players` | `GET /api/player/detail?uuid=xxx` | 玩家详情（历史名/IP/封禁次数/在线信息） |
| `webui.api.bans` | `GET /api/bans` | 封禁列表 |
| `webui.api.ban` | `POST /api/ban` `{"target":"玩家名或#id或短id","time":30,"reason":"炸核心","banIp":false}` | 封禁玩家（详情见下） |
| `webui.api.unban` | `DELETE /api/bans/:id` | 解封 |
| `webui.api.maps` | `GET /api/maps` | 地图列表 |
| `webui.api.maps` | `GET /api/maps/current` | 当前地图信息 |
| `webui.api.mapSwitch` | `PUT /api/maps/:id` | 切换地图 |
| `webui.api.saves` | `GET /api/saves` | 存档列表 |
| `webui.api.saveLoad` | `PUT /api/saves/:id` | 加载存档（回档） |
| `webui.api.saveDelete` | `DELETE /api/saves/:id` | 删除存档 |
| `webui.api.console` | `POST /api/command` `{"command":"status"}` | 执行控制台命令（**高危**，等于在服务器后台敲命令） |
| `webui.api.logs` | `GET /api/logs?type=chat\|command&limit=200` | 历史日志（按类型过滤，见 10.6） |
| `webui.api.logs` | `GET /api/logs/stream` | 实时日志流（SSE 长连接） |
| `webui.api.logsChat` | 同上两个端点 | 仅聊天信息 |
| `webui.api.logsCommand` | 同上两个端点 | 仅命令及结果 |
| `webui.api.announcements` | `GET /api/announcements` | 公告列表 |
| `webui.api.announceSave` | `POST /api/announcements` | 保存公告 |
| `webui.api.announceSave` | `POST /api/announcements/delete` | 删除公告 |
| `webui.api.announceNotify` | `POST /api/announcements/notify` | 公告通知在线玩家 |
| `webui.api.backgroundUpload` | `POST /api/background/upload`（multipart `file`） | 上传背景图到图库 |
| `webui.api.backgroundUpload` | `POST /api/background/delete` `{"name":"xx.png"}` | 从图库删除背景图 |
| 无节点（任何已登录身份） | `GET /api/background/list` | 背景图库列表 |
| 无节点（任何已登录身份） | `POST /api/background/set` `{"name":"xx.png"}` | 设置自己的背景（空 name=清除） |
| `webui.api.users`（**管理员专属**） | `GET/POST /api/users`、`PUT/DELETE /api/users/:name`、`GET /api/users/perm-nodes` | 用户管理 |
| `webui.api.settings`（**管理员专属**） | `POST /api/config/token` | 修改管理 token |
| `webui.api.settings`（**管理员专属**） | `GET/POST /api/config/limits` | 限速/并发配置（见第 11 节） |
| `webui.api.settings`（**管理员专属**） | `GET/POST /api/keys`、`PUT/DELETE /api/keys/:id` | API 密钥管理（见 10.5） |

**封禁接口详情**（`POST /api/ban`）：

```json
{ "target": "玩家名或#id或短id", "time": 30, "reason": "炸核心", "banIp": false }
```

- `time` 单位分钟，`0` = 永久；`reason` **必填**；`banIp` 可选（true 时连同 IP 一起封）。封禁来源会记录为 WebUI。

**背景图说明**：背景是**每用户独立**的（`users.json` 的 `background` 字段，图片在 `data/webui/webuiBackground/`）。任何已登录身份（含游客）都能查看图库、更换自己的背景；**上传/删除图库需要 `webui.api.backgroundUpload` 权限**。

**`users`/`settings` 两个节点不对外分配**（防提权）：普通用户和 API 密钥都勾选不到它们，只有管理员能用。

### 10.5 API 密钥详解

密钥在 **设置页 → API 密钥** 管理（等价接口需要 `webui.api.settings` 即管理员）。

**生成密钥：**

```bash
curl -sk -X POST "$BASE/api/keys" -H "Authorization: Bearer AAA" -H "Content-Type: application/json" \
  -d '{"name":"群机器人","permissions":["webui.api.status","webui.api.players"],"expireDays":30,"rateLimit":5}'
```

| 字段 | 说明 |
|---|---|
| `name` | 密钥名称（唯一，1-100 字符，不能含 `/\..` 控制字符） |
| `permissions` | 权限节点数组（可分配节点，不能含 users/settings） |
| `expireDays` | 过期天数（0 = 永不过期，最大 3650） |
| `rateLimit` | 独立限速（次/秒，0 = 跟随全局限速；见第 11 节） |

**响应**（`secret` 仅此一次，务必保存）：

```json
{ "code": 0, "msg": "ok", "data": {
  "id": "k1a2b3c4d5e", "name": "群机器人",
  "secret": "eU6CXFFyXWTSXkq179mCPaS23JEzkVGoVkLZ3FOFR0A",
  "permissions": ["webui.api.status", "webui.api.players"],
  "expiresAt": 1789311510321, "rateLimit": 5 } }
```

**用密钥调用**（与 session 用法相同）：

```bash
curl -sk "$BASE/api/players" -H "Authorization: Bearer eU6CXFFyXWTSXkq179mCPaS23JEzkVGoVkLZ3FOFR0A"
```

**管理端点：**

| 端点 | 说明 |
|---|---|
| `GET /api/keys` | 密钥列表（id/名称/权限/创建/过期/限速，不含 secret） |
| `POST /api/keys` | 生成密钥（返回 secret 仅一次） |
| `PUT /api/keys/:id` | 编辑（改名/权限/过期/限速，secret 不变） |
| `DELETE /api/keys/:id` | 删除（立即失效） |

**行为要点**：
- 服务器只存 secret 的 SHA-256 哈希，`keys.json` 里看不到明文——丢了只能删除重建
- 过期密钥自动拒绝（401）；删除立即失效
- 密钥身份**不是管理员**：即使权限配全也不包含 users/settings 管理功能
- 密钥配置了独立限速时，**按密钥整体计数**（不区分调用方 IP）——防第三方共享密钥刷爆

### 10.6 日志与日志类型

日志分三种类型：

| 类型 | 特征 | 内容 |
|---|---|---|
| `chat` | 行内含 `<T>` / `<A>` 标记 | 玩家聊天 |
| `command` | 行内含 `<WEBUI>` / `<CMD>` 标记 | WebUI 控制台执行记录及结果 |
| `event` | 其他 | 游戏事件、系统消息等 |

- `GET /api/logs?type=chat` 只取聊天；`type=command` 只取命令；不传取全部。`limit` 控制条数（默认 200）。
- SSE 流 `GET /api/logs/stream` 推送新日志（含心跳注释行）。
- **权限细分**：只有 `logsChat` 权限的用户，后端只推送聊天日志；只有 `logsCommand` 只推送命令日志；`logs`（或管理员）全部。控制台页面可切换"全部/聊天/命令"过滤，并可一键切换"纯文本/颜色码渲染"。

### 10.7 HTTP 状态码与业务错误码

**HTTP 状态码：**

| 状态码 | 含义 |
|---|---|
| `200` | 请求成功（业务结果看响应体 `code` 字段） |
| `401` | 未认证（无凭证且游客模式关闭，或密钥过期/不存在） |
| `403` | 已认证但无该权限节点 |
| `429` | 限速打满（登录失败锁定 / 速率限制 / 并发超限） |

**响应体结构：**

```json
{ "code": 0, "msg": "ok", "data": { ... } }
```

| `code` | 含义 |
|---|---|
| `0` | 成功（`data` 为业务数据） |
| `1` | 业务错误（`msg` 为原因，HTTP 仍是 200） |
| `401` | 未认证（`msg` = Unauthorized） |
| `403` | 无权限（`msg` = Forbidden: <节点名>） |
| `404` | 路径不存在（`msg` = Not Found） |

> 约定：**业务错误返回 HTTP 200 + `code:1`**（与成功同 HTTP 状态），只有认证/权限/限速类错误才用 HTTP 401/403/429。脚本判断成功请检查 `code == 0`，不要只看 HTTP 状态。

### 10.8 常见错误消息

| 后端消息 | 含义 |
|---|---|
| `Invalid username or password` | 用户名或密码错误 |
| `Too many failures, account locked for 15 minutes` | 登录失败过多，锁定 15 分钟 |
| `Invalid username` | 用户名不合法（仅字母数字 `_.-`，最多 32 位） |
| `Username already exists` | 用户名已存在 |
| `Password too short (min 6)` | 密码至少 6 位 |
| `User not found` | 用户不存在 |
| `Cannot modify admin` / `Cannot delete guest` | 内置角色不可改/删 |
| `Forbidden: <节点名>` | 缺少该权限节点 |
| `Invalid key name` / `Key name already exists` | 密钥名称不合法/重复 |
| `Key not found` | 密钥 id 不存在 |
| `Invalid expireDays (0-3650)` | 过期天数越界 |
| `Rate limit exceeded, slow down` | 请求被限速（见第 11 节） |
| `Too many concurrent requests, try later` | 全局并发超限，稍后重试 |
| `Unauthorized` | 无身份/密钥过期 |

### 10.9 完整调用示例（curl）

```bash
BASE="https://IP:8080"

# ---------- 方式一：管理 token 直通（最简单，你自己的脚本用） ----------
curl -sk "$BASE/api/status" -H "Authorization: Bearer AAA"

# ---------- 方式二：用户登录换 session（模拟网页用户） ----------
TOKEN=$(curl -sk -X POST "$BASE/api/login" -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"AAA"}' | python3 -c "import sys,json;print(json.load(sys.stdin)['data']['sessionToken'])")
curl -sk "$BASE/api/players" -H "Authorization: Bearer $TOKEN"

# ---------- 方式三：API 密钥（给第三方） ----------
curl -sk "$BASE/api/status" -H "Authorization: Bearer <密钥secret>"

# ---------- 各功能调用 ----------
# 服务器状态
curl -sk "$BASE/api/status" -H "Authorization: Bearer AAA"

# 小地图截图（存成 PNG）
curl -sk "$BASE/api/world/snapshot" -H "Authorization: Bearer AAA" -o minimap.png

# 在线玩家
curl -sk "$BASE/api/players" -H "Authorization: Bearer AAA"

# 搜索玩家（离线也行）
curl -sk "$BASE/api/players/search?q=xxx" -H "Authorization: Bearer AAA"

# 封禁玩家 30 分钟（reason 必填；time=0 永久；banIp 可选）
curl -sk -X POST "$BASE/api/ban" -H "Authorization: Bearer AAA" -H "Content-Type: application/json" \
  -d '{"target":"玩家名","time":30,"reason":"炸核心"}'

# 解封（id 来自 /api/bans 列表）
curl -sk -X DELETE "$BASE/api/bans/123" -H "Authorization: Bearer AAA"

# 地图列表 + 换图
curl -sk "$BASE/api/maps" -H "Authorization: Bearer AAA"
curl -sk -X PUT "$BASE/api/maps/25143" -H "Authorization: Bearer AAA"

# 存档列表 + 回档 + 删档
curl -sk "$BASE/api/saves" -H "Authorization: Bearer AAA"
curl -sk -X PUT "$BASE/api/saves/122" -H "Authorization: Bearer AAA"
curl -sk -X DELETE "$BASE/api/saves/122" -H "Authorization: Bearer AAA"

# 执行控制台命令（高危，需要 webui.api.console）
curl -sk -X POST "$BASE/api/command" -H "Authorization: Bearer AAA" \
  -H "Content-Type: application/json" -d '{"command":"status"}'

# 历史日志（仅聊天）
curl -sk "$BASE/api/logs?type=chat&limit=100" -H "Authorization: Bearer AAA"

# 实时日志流（SSE，持续输出）
curl -sk -N "$BASE/api/logs/stream" -H "Authorization: Bearer AAA"

# 公告：读/存/删/通知
curl -sk "$BASE/api/announcements" -H "Authorization: Bearer AAA"
curl -sk -X POST "$BASE/api/announcements" -H "Authorization: Bearer AAA" -H "Content-Type: application/json" \
  -d '{"title":"维护通知","content":"今晚 12 点维护"}'
curl -sk -X POST "$BASE/api/announcements/notify" -H "Authorization: Bearer AAA" \
  -H "Content-Type: application/json" -d '{}'

# 用户管理（管理员专属）
curl -sk "$BASE/api/users" -H "Authorization: Bearer AAA"
curl -sk -X POST "$BASE/api/users" -H "Authorization: Bearer AAA" -H "Content-Type: application/json" \
  -d '{"username":"bot","password":"123456","permissions":["webui.api.status","webui.api.players"]}'
curl -sk -X PUT "$BASE/api/users/bot" -H "Authorization: Bearer AAA" -H "Content-Type: application/json" \
  -d '{"disabled":true}'

# 密钥管理（管理员专属）
curl -sk -X POST "$BASE/api/keys" -H "Authorization: Bearer AAA" -H "Content-Type: application/json" \
  -d '{"name":"群机器人","permissions":["webui.api.status"],"rateLimit":5}'
curl -sk -X PUT "$BASE/api/keys/k1a2b3c4d5e" -H "Authorization: Bearer AAA" -H "Content-Type: application/json" \
  -d '{"rateLimit":2}'
curl -sk -X DELETE "$BASE/api/keys/k1a2b3c4d5e" -H "Authorization: Bearer AAA"

# 背景图（任何登录身份）
curl -sk "$BASE/api/background/list" -H "Authorization: Bearer $TOKEN"
curl -sk -X POST "$BASE/api/background/set" -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"name":"mybg.png"}'
curl -sk -X POST "$BASE/api/background/upload" -H "Authorization: Bearer $TOKEN" -F "file=@bg.png"
```

> `-k` 是跳过自签证书校验；脚本长期使用建议把证书导入系统信任库后去掉 `-k`。

---

## 11. 速率限制与并发（可在 WebUI 设置页配置）

**入口**：设置页 → **API 访问限制**。所有项保存**立即生效并持久化**（等价接口 `GET/POST /api/config/limits`，管理员专属），不用改文件。总开关 `rateLimitEnabled` 默认**关闭**；关闭时其余限速项不生效。

开启后请求会依次经过**五层限速**（任一打满即返回 HTTP 429），按 **user@IP** 维度计数（游客每 IP 独立，单个 IP 打满不影响别人）：

```
① 密钥级   → ② 整体请求 → ③ 写操作 → ④ 节点级 → ⑤ API 级
```

| 层级 | 配置项 | 默认 | 单位 | 说明 |
|---|---|---|---|---|
| ① 密钥级 | （每个密钥的 `rateLimit` 字段） | 0 | 次/秒 | 密钥配置了独立限速时**按密钥整体计数**（不区分 IP），防止第三方共享密钥刷爆；0 = 跳过此层 |
| ② 整体 | `rateLimitPerSec` | 10 | 次/秒 | 每个 user@IP 每秒最多请求数 |
| ② 整体 | `rateLimitBurst` | 40 | 个 | 令牌桶容量：允许瞬间的突发量（比如限 10/秒但允许一口气打 40 个） |
| ③ 写操作 | `rateOpPerSec` | 3 | 次/秒 | POST/PUT/DELETE（封禁/换图/回档/命令等危险操作）单独限速 |
| ③ 写操作 | `rateOpBurst` | 10 | 个 | 写操作令牌桶容量 |
| ④ 节点级 | `rateLimitPerNode` | `{}` | 次/秒 | 按**权限节点**覆盖：如 `{"webui.api.console": 1}` 让控制台命令 1 次/秒 |
| ⑤ API 级 | `rateLimitPerApi` | `{}` | 次/秒 | 按**具体端点**覆盖（比节点级更细）：如 `{"/api/command": 1}` |
| 并发 | `maxConcurrentRequests` | 32 | 个 | 全局同时处理的请求数上限，超过返回 429（SSE 长连接不计入） |

**配置示例**（设置页填或直接 POST）：

```bash
curl -sk -X POST "$BASE/api/config/limits" -H "Authorization: Bearer AAA" -H "Content-Type: application/json" \
  -d '{
    "rateLimitEnabled": true,
    "rateLimitPerSec": 10, "rateLimitBurst": 40,
    "rateOpPerSec": 3, "rateOpBurst": 10,
    "rateLimitPerNode": {"webui.api.console": 1, "webui.api.status": 2},
    "rateLimitPerApi": {"/api/ban": 1},
    "maxConcurrentRequests": 32
  }'
```

**理解"令牌桶"**：`rateLimitPerSec=10` + `burst=40` 意思是——桶里最多攒 40 个令牌，每 0.1 秒补 1 个；请求消耗 1 个令牌，桶空就 429。效果：平时 10 次/秒，但允许瞬时突发 40 个（比如刚启动时）。**要严格限速就把 burst 设小**（如限 1 次/秒就设 `burst=1~3`）。

**配置文件方式**（`config.conf` 的 `webui` 节，改后重启或 `sa reload`）：

```
webui {
  rateLimitEnabled = false        # 总开关
  rateLimitPerSec = 10            # 整体限速（次/秒）
  rateLimitBurst = 40             # 整体突发容量
  rateOpPerSec = 3                # 写操作限速（次/秒）
  rateOpBurst = 10                # 写操作突发容量
  rateLimitPerNode = {}           # 节点级覆盖 { "webui.api.console" = 1 }
  rateLimitPerApi = {}            # API 级覆盖 { "/api/command" = 1 }
  maxConcurrentRequests = 32      # 全局并发上限
}
```

## 12. 配置文件 webui 节全参数

`config.conf` 里 `webui { ... }` 的全部配置项：

| 配置项 | 默认 | 说明 |
|---|---|---|
| `webuiEnabled` | `true` | 是否启用 WebUI |
| `host` | `0.0.0.0` | 监听地址（一般不用改） |
| `port` | `8080` | 监听端口 |
| `token` | `auto` | 管理员 token（= 管理员登录密码 + API 管理 token）。`auto` 每次启动随机生成并打印在日志 |
| `tokenExpire` | `86400` | session 有效期（秒）与 auto 模式 token 刷新周期 |
| `adminUser` | `admin` | 超级管理员用户名 |
| `snapshotCacheSec` | `30` | 小地图截图缓存时间（秒） |
| `sslEnabled` | `false` | 是否启用 HTTPS（生产强烈建议 true） |
| `sslKeystore` | `webui/keystore.p12` | 证书库路径（相对 data 目录） |
| `sslKeystorePass` | — | 证书库密码 |
| `rateLimitEnabled` | `false` | 限速总开关（见第 11 节） |
| `rateLimitPerSec` | `10` | 整体限速（次/秒） |
| `rateLimitBurst` | `40` | 整体突发容量 |
| `rateOpPerSec` | `3` | 写操作限速（次/秒） |
| `rateOpBurst` | `10` | 写操作突发容量 |
| `rateLimitPerNode` | `{}` | 节点级限速覆盖 |
| `rateLimitPerApi` | `{}` | API 级限速覆盖 |
| `maxConcurrentRequests` | `32` | 全局并发请求上限 |

**运行时数据文件**（都在 `config/scripts/data/webui/`）：

| 文件 | 内容 |
|---|---|
| `users.json` | 用户账号（PBKDF2 密码哈希、权限、背景），含内置 guest 游客记录 |
| `keys.json` | API 密钥（仅 SHA-256 哈希，无明文） |
| `audit.log` | 审计日志（每次操作记 IP + token 哈希 + 内容） |
| `keystore.p12` | TLS 证书库（**含私钥，勿公开**） |
| `webuiBackground/` | 背景图库 |

## 常见问题

**初次启动很慢？** 正常，要编译全部插件，等 10 分钟以上。之后再启动有缓存就快了。

**端口被占用？** `Opened a server on port 6567` 报 already in use 说明有别的服务器占着 6567 或控制管道 6568，先停掉旧的。

**想开第二个服务器实例？** 把 config.conf 里 `coreMindustry.console.cmdPipePort` 改个端口（如 6570），存档目录分开即可。

**WebUI 打不开？** 默认 `http://IP:8080`，登录页用 `admin` + 管理 token（config.conf 的 `webui.token`，`auto` 表示首次启动自动生成，看日志）。想上 HTTPS 跑一下 gen_keystore 脚本。

**改完脚本没生效？** 删 `config/scripts/cache/compiled/` 里对应目录的 `.ktc` 再重启；运行中的服务器可以用 `sa reload 脚本id --noCache` 热重载。

**服务器半夜挂了？** 用 `watchdog.bat`/`watchdog.sh` 启动（而不是 run 脚本），卡死会自动重启，`/restart` 也能真重启。

## 开源说明

仓库包含微泽插件本体（`config/mods/ScriptAgent4MindustryExt-3.4.0.jar`，GPL 协议，来自[官方 release](https://github.com/way-zer/ScriptAgent4MindustryExt/releases)），以及在其之上开发的全部插件脚本。`server.jar`（MindustryX）不在仓库内，由一键安装脚本从官方渠道下载。

有一堆插件（单位工厂、音乐系统、技能系统、AI 翻译等）不在此仓库，别问，问就是没开源。开源了我们吃什么。