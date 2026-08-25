# 代理工作指南

本文件用于规范代理在 Mindustry 服务器插件开发中的核心原则、懒惰开发者法则与具体工作流，所有修改应以简体中文为首要表达。

## 核心原则

1. 所有修改以简体中文为首要目标
2. 对插件的任何修改都要同步更新同级 files.md
3. 新需求必须完美适配现有架构，不盲目新增/删除/修改文件、指令；不要因一次修改导致其他文件受影响而引发更多 bug，除非此修改是必须的或用户主动要求。权限须与 config.conf 现有权限组适配，能在此文件中配置对应权限组，不另起炉灶自写权限，避免原有框架失去意义。
4. 所有插件的权限组配置必须放在 config.conf 的 coreLibrary.commands.permissionCmd.groups 下，而不是在插件脚本内通过 PermissionApi.registerDefault() 硬编码。新增脚本若需要权限注册，必须更新 config.conf 而非在脚本内调用 registerDefault。
5. 未经用户允许，严禁修改 AGENTS.md 本身
6. 所有修改提交到 https://github.com/power-line-server/PL-server-plugins-dev
7. 积极使用联网搜索功能与技能
8. 一切修改以在 Linux 系统上正常运行为主要目标，以在 Windows 系统上正常运行为次要目标（Linux 原生支持 ANSI/UTF-8，Windows 由 JANSI 等库处理兼容性）
9. 服务器首要服务原版客户端，改端为次要目标：不得要求玩家下载修改版客户端或装载模组，所有功能必须保证未装模组的原版客户端与改端客户端均能正常游玩。设计功能时优先使用服务端脚本 + 原版协议能力（如 player.name 实体同步、ChatFilter、菜单数据包），不依赖改端专属特性或客户端补丁；确需改端能力时，须在实施计划中先行声明并说明原版替代方案
10. 不要在向别人展示的地方写上任何无关内容，例如你曾经制作一个番茄炒蛋放了酱油，然后你发现不需要放酱油，那么你最多只需要在代码注释里或记忆里写上为什么不能放酱油，但是你绝对不能把展示给别人看的地方写上为什么不能放酱油，例如CLI文本，还有GitHub的议题标题及描述，这根本就是毫无意义的废话，别人想看什么？别人想看到的是这个工具怎么用，以及输入了错误参数后的有用提示，而不是弹出来几句你论述为什么番茄炒蛋不需要放酱油的长篇大论。

## 懒惰开发者法则

懒惰指高效而非草率，最好的代码是从未写出的代码。以下法则提炼自 Ponytail 哲学。

### YAGNI 阶梯

写代码前依次确认，在第一档能站住时即停下：

1. 这东西真的需要建吗？（YAGNI）
2. 代码库里已有？复用现有 helper/util/模式，不重写
3. 标准库已能做？用标准库
4. 原生平台特性已覆盖？用原生
5. 已安装的依赖能解决？用现有依赖
6. 能一行写完？写一行
7. 以上都不满足：才写最小可用代码

阶梯在理解问题之后攀登，而非替代理解：先读任务与相关代码、端到端追踪真实流程，再选阶梯。

### Bug 修复 = 根因而非症状

报告描述的是症状。grep 所有可能调用方，在共享函数处修一次——一个守卫比每个调用方各打一个补丁的 diff 更小，只修补报告点会让兄弟调用方依旧带病。

### 规则清单

- 不引入未被显式要求的抽象
- 能避免就不引入新依赖
- 不写没人要的样板代码
- 删除优先于新增；无聊胜过聪明；文件越少越好
- 最短可用 diff 取胜，但前提是已理解问题——错误位置的最小改动不是偷懒，是第二个 bug
- 质疑复杂请求：「你真的需要 X，还是 Y 已足够？」
- 两种标准库方案体量相当时，选边界情况正确的那个——偷懒指更少代码，不是更脆弱的算法
- 用 `ponytail:` 注释标记刻意简化；若简化有已知上限（全局锁、O(n²) 扫描、朴素启发式），注释需写明上限与升级路径

### 不可偷懒的红线

- 理解问题：完整阅读、追踪真实流程后再选阶梯；不理解的最小 diff 只是披着效率外衣的懒惰
- 信任边界的输入验证
- 防数据丢失的错误处理
- 安全
- 可访问性
- 真实硬件所需的校准（平台从不是规格理想态，时钟会漂移、传感器会偏）
- 任何显式要求的事项

非平凡逻辑应留一个可运行检查（最小断言自检或一个小测试文件；无框架、无 fixture）；平凡一行代码无需测试。

## 工作流规范

将上述原则落地到 Mindustry 服务器插件开发的具体工作流：

- 对插件的任何修改都要同步更新同级 files.md；files.md 需详细描述 d:\server\server 下全部文件和文件夹作用；cache/ 等意义不大的目录可简化描述。发现 files.md 与实际不一致或希望更详细的描述时，也可进行修改。
- 新需求必须完美适配现有架构，不盲目新增/删除/修改文件、指令；不要因一次修改导致其他文件受影响而引发更多 bug，除非此修改是必须的或用户主动要求。权限须与 config.conf 现有权限组适配，能在此文件中配置对应权限组，不另起炉灶自写权限，避免原有框架失去意义。
- 所有修改提交到 https://github.com/power-line-server/PL-server-plugins-dev
- 积极使用联网搜索功能与技能

## 本地验证与测试清理规范

### 服务器本地启动验证

- **启动命令**：`cd d:\server\server && java --enable-native-access=ALL-UNNAMED -jar server.jar`。建议 `stdbuf -oL -eL` 前缀保证日志实时输出（重定向到文件时 stdout 是块缓冲，日志会延迟刷新）；后台运行用 `nohup ... & disown`
- **启动耗时**：有编译缓存约 1-2 分钟；删除 `cache/compiled/` 全量重编译约 3-6 分钟
- **成功标志**：日志出现 `Server loaded. Type 'help' for help.` + `Opened a server on port 6567`
- **偶发启动失败**：`[Instantiate Failed] ScriptClassLoader NPE` / `[Load Failed] xxx ConditionFail` 多为偶发（进程清理竞争、临时目录状态），重启一次即可；持续复现才排查代码
- **停止**：Windows 下 `taskkill //F //IM java.exe`（可残留多个 java 进程，全部杀掉）

### 测试痕迹清理（每次测试后必须）

服务器运行会污染工作区，验证完成后必须清理：

- **自动存档覆盖槽位**：每 5 分钟自动存档写入 `config/saves/` 槽位 `(小时*12 + 分钟%5) % 100 + 100`，会覆盖 git 跟踪的存档 → 测试后 `git checkout -- config/saves/` 恢复
- **运行产物**：`config/settings_backups/`、`config/assetCache/`、`config/logs/` 同样被覆盖/新增，一并 `git checkout --` 清理
- **测试存档注入**：用 python 修改存档（meta 注入 `[@标签]`）前必须先备份原文件；备份目标避免用 `/tmp`（Windows 下路径可能不存在），用 `config/saves/` 同盘备份或直接依赖 git 版本恢复

### 终端与游戏内命令前缀

- ScriptAgent 注册的服务端命令在**服务器终端（控制台）中不加任何前缀**直接输入（如 `perf mem`、`renderMap 8`）；带 `/` 的形式是**玩家聊天命令**（如 `/music`）。终端输带斜杠的形式会提示"未知指令"，属正常现象，不是命令失效
- 仅终端可用的命令用 `attr(NotForClient)`（import coreMindustry.lib.NotForClient）：玩家 help 不显示、游戏内输入提示未知指令；**终端 help 正常显示且可直接调用**
- 反之仅玩家可用的命令用 `attr(ClientOnly)`（如 /music）

### mapTag 脚本触发验证（无客户端时）

- 用 python 修改存档 meta 的 `description` 注入 `[@标签]`（如 `[@pvpAnonymous]`），经 WebUI 回档触发脚本 Enable/Disable，从日志验证生命周期
- 存档格式：`MSAV`(4B) + version(4B) + 各 chunk（meta/patches/content/map/entities/markers/custom，每 chunk 为 `>i` 长度 + 数据），整体 zlib 压缩
- **WebUI**：登录 `POST /api/login`（管理 token 在 config.conf 的 `webui { token }`，默认 `"AAA"`），回档 `PUT /api/saves/<槽位>`
- **WebUI 的 `/api/command` 走 `Commands.Root` 域**：自定义脚本命令（如 `anon`）不在其中，无法用它验证自定义命令；它只能执行 Root 域命令（原版控制台命令等）。终端命令验证需走真实终端或玩家命令链

## 配置文件规范

ConfigApi 通过 HOCON 格式管理插件配置，涉及两个文件：

| 文件 | 作用 | 注释 | 覆盖风险 |
|------|------|------|----------|
| `config.conf` | 用户配置（实际生效的值） | **有注释**（`saveFile()` 增量更新，保留注释） | `/config write` 命令会全量覆盖丢注释，`/config set` 不会 |
| `config.base.conf` | 带注释的配置参考文档（默认值 + 说明） | **有注释**（ConfigApi fallback 读取，不被覆盖） | 不会 |

### fallback 机制

ConfigApi 读取顺序：`config.conf`（用户值优先）→ `systemProperties` → `systemEnvironment` → `config.base.conf`（默认值兜底）。`config.conf` 中未设置的 key 会自动回退到 `config.base.conf` 的默认值。

### config.key 注册规则

1. **脚本中用 `var X by config.key(默认值, 描述)`**：config 路径前缀为脚本 ID（`/` 替换为 `.`），如 `wayzer/ext/music.kts` 的前缀为 `wayzer.ext.music`
2. **值等于默认值时不写入 config.conf**：这是 ConfigApi 的正常行为，不是 bug。用户需修改时在 `config.conf` 中添加对应配置节
3. **带 `onChange` 回调的 key**：使用 `config.key("name", default, desc) { onChange }` 重载，首参为显式 key 名

### config.base.conf 维护规则

1. **新增 `config.key` 时必须同步更新 `config.base.conf`**：在对应模块的配置节中添加默认值和 `#` 注释说明
2. **配置节结构按脚本路径组织**：如 `wayzer.ext.music` 对应 `wayzer { ext { music { ... } } }`
3. **每个配置项必须有 `#` 注释**：注释内容取自 `config.key` 的描述参数
4. **复杂默认值可省略**：如 `scoreboard.template` 的默认值是多行字符串，注释中标注"请参考脚本源码"
5. **Duration 类型用字符串**：如 `"10s"`、`"5m"`，不用毫秒数字
6. **Float 类型用纯数字**：如 `30`（不用 `30.0` 或 `30f`）

### config.conf 维护规则

1. **代理不直接编辑 config.conf**：通过 `/config set` 命令或脚本内 `config.key` 修改值，由 ConfigApi 的 `saveFile()` 增量写入（保留注释）
2. **例外**：权限组（`coreLibrary.commands.permissionCmd.groups`）和需要预设的配置节（如 `wayzer.ext.music`）可直接编辑 config.conf
3. **`/config write` 会全量覆盖**：该命令调用 `saveFile()` 的全量 render 路径（当文件无注释时触发），会丢失注释。避免使用，或使用后从 `config.base.conf` 恢复注释
4. **config.conf 中的值优先于 config.base.conf**：用户自定义的值不会被 base 覆盖

### 检查清单

修改/新增配置项后自检：
- [ ] 新增 `config.key` 已在 `config.base.conf` 对应配置节中添加默认值和注释
- [ ] `config.base.conf` 的配置节路径与脚本 ID 一致（`/` → `.`）
- [ ] 删除 `config.key` 时已从 `config.base.conf` 移除对应行
- [ ] 未在 `config.conf` 中手动添加注释（会被 `saveFile()` 覆盖）

## 多语言规范

修改或新增插件时，所有面向玩家的文本（菜单标题/正文/选项、命令描述、reply 回复、broadcast 广播、toast 提示等）必须使用 `{tr key}` 模板，**严禁硬编码中文/英文字符串**。仅需新增简体中文翻译，其他语言由用户和社区手动翻译，不同时维护多个语言文件。

### 文本使用规则

1. **脚本中用 `{tr key}` 模板**：通过 `PlaceHoldApi.with()` 渲染，不直接写字面文本
   - 命令描述：`command("xxx", "{tr command.xxx.desc}".with())`
   - 玩家回复：`reply("{tr xxx.reply.msg}".with("receiver" to player))` 或 `player.sendMessage("{tr xxx.reply.msg}".with("receiver" to player).toString())`
   - 菜单文本：`title = "{tr xxx.menu.title}".with("receiver" to player).toString()`
   - 广播：`broadcast("{tr xxx.broadcast}".with())`（broadcast 内部已注入 receiver，无需手动传）

2. **必须传 `receiver`**：凡是直接调用 `.with().toString()` 的场景（菜单选项、sendMessage 等），必须传 `"receiver" to player`，否则 `tr` 函数读不到 `receiver.lang`，永远回退到 `zh_CN`，玩家切语言无效
   - 反例：`"{tr xxx}".with().toString()` — 永远中文
   - 正例：`"{tr xxx}".with("receiver" to player).toString()` — 跟随玩家语言
   - 例外：`broadcast()` 内部会注入 `ConsoleReceiver`，调用时无需手动传

3. **key 命名规范**：`<脚本名>.<分类>.<具体>.<...>`，全英文小驼峰，例如 `langSetup.menu.language.confirm`、`banX.reply.durationInvalid`、`voteLib.option.agree`

### 翻译文件规则

1. **只维护简体中文**：新增/修改 key 时，仅在 `d:\server\server\config\scripts\data\lang\bundle_zh_CN.properties` 添加或更新对应翻译。`bundle.properties`和其他语言文件由用户和社区手动翻译，**代理不同时修改多个语言文件**。

2. **langSync 自动补 key**：脚本中的 `{tr key}` 会在服务器启动或 `sa lang reload` 时被 `langSync.kts` 自动扫描并添加缺失的 key 到所有 bundle 文件（值为空）。代理只需填充 `bundle_zh_CN.properties` 的值。

3. **保留占位符**：翻译中保留 `{var}` 形式的占位符，例如 `lang.reply.langSet=[green]你的语言已设为 {v}\n[yellow]可用语言: {available}`

4. **颜色码**：翻译中可使用 Mindustry 颜色码（`[green]`、`[yellow]`、`[red]`、`[light_gray]`、`[gold]`、`[scarlet]`、`[sky]`、`[#ff00ff]` 等）。玩家收到时由 `mindustryColorHandler` 保留原码透传给客户端；控制台收到时由 `ContentHelper.logToConsole` 直接 `Log.info(text)` 保留原码，再经 `Log.formatter` 的 `mindustryColorToArc` 统一转 ANSI（终端显示）或保留原码（日志文件供 WebUI 渲染）。详见"终端颜色与日志规范"。

5. **多行值必须用 `\n` 转义单行，禁止物理换行**：properties 中 `key=值` 到行尾即结束，物理换行的后续裸行会被 Java Properties 加载时**静默忽略**（续行需要行尾反斜杠 `\`，不是裸换行）。多行文本（菜单 msg、公告等）必须写成单行 `key=第一行\n第二行`（`\n` 为转义序列，加载时变真实换行）。**典型症状：菜单标题/说明只显示第一行，其余内容整段消失**（如"当前状态："后空白）。对照正确范例：`saveMgr.menu.main.msg=[green]请选择操作类型：\n[yellow]=======================`

6. **玩家可见文本中禁止字面 `[纯字母]`（如 `[lock]`），改用 `<lock>` 或直接写 lock**：服务端渲染链（命令参数解析、`{tr}` 渲染）会做**完整 markup 解析**——`[xxx]`（纯字母）被当作颜色码**整段移除**（已实测 `[[` 转义无效：`[[` 先转成字面 `[`，随后 `[lock]` 仍被当颜色码移除）。**典型症状：帮助文本/usage 中 `[lock]` 参数消失，只剩两个空格**。含 `|` 等非字母字符的 `[uuid|uid]` 不会被当作颜色码，可正常显示。`usage` 属性同理

### 多语言文件的合理插入

新增 key 到 `bundle_zh_CN.properties`（及其他语言文件）时，**必须按功能模块分组插入到对应区域**，严禁直接追加到文件末尾造成游离键。langSync 虽然会自动补 key，但它把新 key 追加到文件末尾，不区分模块，因此代理手动填充翻译值时必须把 langSync 产生的末尾游离键移到正确位置。

1. **按功能模块分组**：每个脚本/插件对应一个独立的 key 区块，用注释行分隔。例如 `playerInfo` 的 key 必须放在 `# ===== playerInfo =====` 注释下方，`webui` 的 key 必须放在 `# ===== webui =====` 注释下方。

2. **插入位置规则**：
   - 新增 key 属于已有模块时，插入到该模块区块的末尾（下一条 `# =====` 注释之前）
   - 新增 key 属于全新模块时，在文件末尾新建 `# ===== 模块名 =====` 注释，再添加 key
   - 同一模块内的 key 按逻辑顺序排列（如 `tab` -> `menu` -> `reply` -> `broadcast` -> `detail` -> `action` -> `search`）

3. **子模块嵌套**：功能较复杂的模块（如 `webui.players`）可用二级注释分隔子模块，例如：
   ```properties
   # ===== webui =====
   webui.title=...
   webui.nav.dashboard=...
   
   # ----- webui.players -----
   webui.players.title=...
   webui.players.tab.online=...
   webui.players.tab.search=...
   
   # ----- webui.console -----
   webui.console.title=...
   ```

4. **清理 langSync 游离键**：langSync 自动追加的 key 会出现在文件末尾（值为空）。代理填充翻译值时，必须将该 key 从末尾剪切到对应模块区块内，而不是原地填值。操作步骤：
   - 用 `Grep` 搜索 `^key=$` 找到末尾的空值行
   - 删除末尾空值行
   - 在对应模块区块的正确位置插入带翻译值的完整行

5. **跨语言文件同步结构**：当用户或社区翻译其他语言文件时，应保持与 `bundle_zh_CN.properties` 相同的模块分区和 key 顺序，仅翻译值不同。代理修改 `bundle_zh_CN.properties` 的结构后，不强制同步到其他语言文件（由用户手动对齐），但新增 key 时应在所有语言文件中保持相同的模块归属。

### langSync 重同步行为警示

`langSync.kts` 在**每次服务器启动**和**`sa lang reload`**时会重新扫描所有 `.kts`/`.kt` 脚本中的 `{tr key}` 引用，并与 `bundle_*.properties` 中的现有 key 进行同步：

- **新增引用的 key**：以空值 (`key=`) 添加到 properties 文件末尾，**已有 key 的值不会被清空**
- **不再被引用的 key**：从 properties 文件中**整行删除**（连同其翻译值一并删除！）
- **动态拼接的 key**（如 `{tr ${someVar}}`）：静态正则 `\{tr\s+([\w.\-]+)` 无法捕获，必须加入 `langSync.kts` 的 `manualKeys` 白名单，否则会被当作"未引用"删除

#### 触发翻译值丢失的典型场景

1. **重构/修改插件时临时移除 `{tr ...}` 引用**：如把 `"{tr music.menu.title}"` 临时改成硬编码字符串测试，重启后 langSync 判定该 key "未被引用" → 从 properties 删除 → 改回 `{tr ...}` 后重启 → key 被重新添加为**空值**。这是最隐蔽的丢失路径
2. **重命名/移动 key**：旧 key 不再被引用 → 整行删除（含翻译值）；新 key 被引用 → 以空值添加。结果是新 key 没有翻译值
3. **动态 key 未加白名单**：`{tr ${sourceDisplayName(track.source)}}` 这类动态拼接无法被正则捕获，重启后对应 key 被删除
4. **脚本被禁用/卸载**：被禁用脚本的 `{tr ...}` 引用仍在源码中（langSync 扫描源码而非编译产物），所以禁用本身不会丢 key；但若删除了脚本文件，则该脚本独有的 key 会被删除

#### 代理工作要求

- **关键认知**：langSync **不会主动清空已有 key 的值**。翻译值丢失只发生在「key 被删除 → 后续重新引用 → 以空值重新添加」这条路径上。单纯修改脚本逻辑（保留 `{tr ...}` 引用不变）不会影响 properties 中的值
- **移除/重命名/注释掉 `{tr ...}` 引用时**：重启服务器后，被移除引用的 key 会从 properties 整行删除（含翻译值）。若之后恢复引用，会以空值重新添加。此时必须用 `Grep` pattern `^[\w.]+=$` 检查 `d:\server\server\config\scripts\data\lang\bundle_zh_CN.properties` 中的空值行并重新填充
- **新增动态拼接 key**（`{tr ${...}}` 形式）：正则 `\{tr\s+([\w.\-]+)` 无法捕获 `$` 开头的动态变量，必须同步在 `d:\server\server\config\scripts\coreLibrary\langSync.kts` 的 `manualKeys` 集合（约第 20 行起）中添加对应 key，否则下次同步会被当作"未引用"删除
- **重命名 key 的安全顺序**：先在新位置添加 `{tr newKey}` 引用并填充翻译值，确认无误后再删除旧 `{tr oldKey}` 引用。避免中间状态（旧 key 已删引用、新 key 尚未引用）被同步清空
- **备份意识**：对 properties 文件进行大量修改前，建议先备份一份，避免同步意外导致翻译值丢失

### 检查清单

修改/新增插件后自检：
- [ ] 所有面向玩家的文本都用了 `{tr key}` 模板，没有硬编码中文/英文
- [ ] 菜单/sendMessage 的 `.with()` 调用传了 `"receiver" to player`
- [ ] 新增的 key 已在 `bundle_zh_CN.properties` 填充中文翻译
- [ ] key 命名遵循 `<脚本名>.<分类>.<具体>` 规范
- [ ] 翻译中保留了 `{var}` 占位符
- [ ] 未修改 `bundle.properties`（英文）和其他语言文件（除非用户明确要求）
- [ ] 控制台输出走 `ContentHelper.logToConsole` / `broadcast` 标准链路，未直接 `Log.info` 带 ANSI 码
- [ ] `PlaceHoldString` 中用 Mindustry 颜色码（如 `[green]`），未直接写 ANSI 码
- [ ] 修改终端/日志相关代码后，Grep 检查 `log-0.txt` 无颜色码残留（`\u001b\[`、`&[a-zA-Z]{1,2}`、`\[[a-z_]*]`）；日志文件为纯文本，与原版 Mindustry 一致；WebUI 实时日志通过 `unifiedTextHolder` 获取带颜色码文本
- [ ] 修改在 Linux 上正常运行（主要目标），Windows 由 JANSI 库处理兼容性（次要目标）
- [ ] 移除/重命名/注释掉 `{tr ...}` 引用后，Grep 检查 `bundle_zh_CN.properties` 无新增空值行（`key=`）并重新填充
- [ ] 新增 `{tr ${...}}` 动态拼接时，已把对应 key 加入 `langSync.kts` 的 `manualKeys` 白名单
- [ ] 新增 key 已插入到 `bundle_zh_CN.properties` 对应功能模块区块内，未追加到文件末尾造成游离键
- [ ] langSync 自动追加的末尾空值 key 已剪切到对应模块区块内并填充翻译值

## 本地化与时区规范

下列规则适用于涉及按玩家语言/时区显示的场景，避免因服务器固定 locale 或时区导致显示不一致。

### vanilla 内容按玩家本地化

- **禁止用 `Content.localizedName` / `Team.localized()` / `Team.coloredName()` 做按玩家本地化**：这些 API 走 `Core.bundle`（服务器固定 locale），英文玩家在中文服务器上仍会看到中文名。
- **必须用 `arc.util.I18NBundle`**：按 `PlayerData[p].lang` 构造 locale，调用 `I18NBundle.createBundle(Core.files.internal("bundles/bundle"), locale)`，再 `bundle.get(content.name)` / `bundle.get("team.${team.name}.name", team.name)`。
  - 注意：Mindustry 的类是 `arc.util.I18NBundle`，不是 `arc.util.Bundle`（不存在）。
- **缓存 bundle**：`I18NBundle.createBundle` 有内部缓存，但为避免重复构造 locale，可在脚本内 `mutableMapOf<String, I18NBundle>` 按 lang 缓存。
- **广播处例外**：广播走 `ConsoleReceiver`，可保留 `localizedName`（服务器默认语言可接受）；玩家 personally 看到的菜单/选项必须用 `I18NBundle`。
- **`{tr key}` 与 `I18NBundle` 的分工**：`{tr key}` 用于插件自定义文本（来自 `bundle*.properties`），`I18NBundle` 用于 vanilla 内容（队伍名、单位名、方块名等已存在于游戏 `bundles/bundle*.properties` 的 key）。

### 时区显示统一

- **所有面向玩家的时间显示**必须按 `PlayerData[p].timezone` 格式化，不得用 `SimpleDateFormat`（无时区）或 `DateFormat.getDateTimeInstance()`（服务器默认时区）。
- **统一模式**：`M.d-HH:mm:ss`（与 `wayzer/user/serverLog.kts` 一致），即"月.日-时:分:秒"。
- **实现模板**（参考 `serverLog.kts` / `history.kts` / `ban.kts` / `saveMgr.kts`）：
  ```kotlin
  @file:Depends("coreLibrary/time", "parseTimeZone")
  @file:Depends("wayzer/user/lang", "PlayerData.timezone")
  // ...
  val tz = contextScript<wayzer.user.Lang>().run { PlayerData[p].timezone }
  val zone = parseTimeZone(tz)
  val formatter = DateTimeFormatter.ofPattern("M.d-HH:mm:ss")
  val timeStr = instant.atZone(zone).format(formatter)
  ```
- **lambda 遮蔽陷阱**：在 `Player` 扩展函数中访问 `PlayerData[this].timezone` 时，若需进入 `contextScript<...>().run { }` 块，必须先 `val player = this` 捕获，否则 `run{}` lambda 内的 `this` 会遮蔽 receiver。
- **时间存储**：跨脚本传递时间戳用 `Instant` 或 `epochSecond`（Long），不要用 `Date()`，格式化留给展示层。
- **bundle 占位符写法**：翻译 key 中的时间占位符统一用 `{time}`（由脚本侧格式化后注入），不写 `{time HH:mm:ss}` 这类带格式说明的旧写法。

## 菜单规范

服务器菜单系统（[menu.lib.kt](file:///d:/server/server/config/scripts/coreMindustry/menu.lib.kt) 和 [menu.new.kt](file:///d:/server/server/config/scripts/coreMindustry/menu.new.kt)）已内置关闭按钮自动追加机制。代理编写菜单时**必须遵循以下规范**，避免漏加或重复添加关闭按钮。

### 关闭按钮自动追加机制

| 菜单类型 | 默认 `autoCloseButton` | 关闭按钮来源 | 关闭行为 |
|---------|----------------------|------------|---------|
| `MenuBuilder`（普通菜单） | `true` | `sendTo()` 自动追加 | `throw CommandInfo.Return`（中断命令） |
| `PagedMenuBuilder`（分页菜单） | `false`（init 中禁用） | `build()` 末尾自动追加 | 空回调 `{}`（followup 菜单由 `close()` 隐藏） |
| `MenuV2`（新菜单 API） | `true` | `send()` 自动追加 | `close()`（关闭菜单） |
| `MenuV2.subMenu`（子菜单） | N/A | `subMenu()` 自动追加"返回"按钮 | `back = true` + `refresh()` 返回上级 |

- 关闭按钮文本统一为 `{tr coreMenu.close}`，返回按钮统一为 `{tr coreMenu.back}`，由 `langSync.kts` 自动同步到 bundle。
- 自动追加位置：菜单最后一行（`newRow()` + `option(...)`）。
- `PagedMenuBuilder` 例外原因：分页菜单的关闭按钮需与翻页按钮（`<-`/`页码`/`->`）同行布局，故在 `build()` 中手动添加而非用 `autoCloseButton`。

### 强制规范

1. **普通菜单 `MenuBuilder` 不要手动添加关闭按钮**：默认 `autoCloseButton = true` 已自动追加。手动添加会导致菜单出现两个关闭按钮。

2. **分页菜单 `PagedMenuBuilder` 不要手动添加关闭按钮**：`init { autoCloseButton = false }` 禁用了自动追加，但 `build()` 末尾已手动添加。手动添加会导致菜单出现两个关闭按钮。

3. **`MenuV2` 不要手动添加关闭按钮**：默认 `autoCloseButton = true` 已自动追加。`subMenu()` 也已自动追加"返回"按钮。

4. **需要自定义关闭行为时**：设 `autoCloseButton = false`，然后手动添加关闭按钮。适用场景：
   - 关闭时需要执行清理逻辑（如保存数据、重置状态）
   - 关闭按钮需要特殊位置（如不放在最后一行）
   - 子菜单需要"返回上级"而非"关闭"（用 `MenuV2.subMenu` 已自动处理）

5. **关闭按钮文本必须用 `{tr coreMenu.close}`**：不要硬编码"关闭"/"close"，确保按玩家语言显示。手动添加时也必须用此模板：`option("{tr coreMenu.close}".with("receiver" to player).toString()) { ... }`。

### 检查清单

- [ ] 使用 `MenuBuilder` / `PagedMenuBuilder` / `MenuV2` 时，未手动添加关闭按钮（除非设了 `autoCloseButton = false`）
- [ ] 设了 `autoCloseButton = false` 时，已手动添加关闭按钮（避免玩家无法关闭菜单）
- [ ] 关闭按钮文本用 `{tr coreMenu.close}`，未硬编码
- [ ] 子菜单用 `MenuV2.subMenu()`，未手动添加"返回"按钮

## banX 与数据库规范

### banX 封禁系统

服务器禁用了原版 `ban`/`kick`/`votekick`，所有玩家封禁/踢出**必须**使用 banX 系统。

- **H2 数据库存储**：`PlayerBan` 数据类必须包含 `targetName`、`operatorName`、`reason`、`createTime`、`endTime`、`ids` 字段，确保持久化显示封禁者和被封禁者信息。
- **`/bansX` 命令**：只显示活跃封禁（`endTime > 当前时间`），每条都包含封禁者信息。需要 `wayzer.admin.unban` 权限解封。
- **控制台操作员**：可绕过 `wayzer.admin.skipKick` 权限检查。
- **反逻辑病毒插件**：必须使用 banX 进行 10 分钟封禁，并携带 reason 和 operator 信息。
- **原版 ban/kick 不可直接取消**：需用 post-event unban 和 kicked flag 操纵间接实现 banX 接管。

### 数据库 Schema 变更

- **`TableVersion.check` 跳过未实现 `WithUpgrade` 的表**：表结构变更时，必须实现 `DB.WithUpgrade` 接口并递增 version，触发 `onUpgrade`。
- **新增字段用 `SchemaUtils.createMissingTablesAndColumns`**：Exposed 0.59.0 不支持 `SchemaUtils.addColumn`，会报错。
- **`onUpgrade` 中执行 schema 迁移**：如 `ALTER TABLE`、数据迁移等。

## 脚本编译与依赖规范

### 编译缓存（.ktc）

- **修改脚本后必须删除对应的 `.ktc` 缓存文件**：旧缓存会导致源码修改与运行时行为不一致（典型症状：源码已改但行为未变）。缓存文件位于 `config/scripts/cache/compiled/` 目录下，按脚本路径分目录存储，文件名为 `<hash>.ktc`（如 `config/scripts/cache/compiled/coreMindustry_console/973ee0.ktc`）。
- **删除时机**：脚本源码（`.kts`/`.kt`）修改后、重启服务器前。服务器启动时会自动重新编译。
- **删除脚本文件时必须同时清理残留缓存**：即使源文件已删除，`.ktc` 缓存仍可能残留并被加载，导致幽灵类冲突。删除脚本文件后必须用 `Glob` 搜索 `cache/compiled/<脚本路径目录名>/*.ktc` 并全部删除。
- **修改被广泛依赖的文件时必须清理所有依赖方的缓存**：当一个类/函数/对象的签名（参数列表、字段类型、方法可见性等）发生变化时，所有调用它的脚本的 `.ktc` 缓存都必须清理，否则会触发 `NoSuchMethodError`、`ClassNotFoundException`、`ClassCastException` 等运行时二进制不兼容错误。
  - **典型场景**：修改 `vote.lib.kt` 中的 `VoteEvent` 构造函数签名 → 必须清理 `wayzer_vote`、`wayzer_cmds_vote`、`wayzer_cmds_voteMap`、`wayzer_cmds_voteOb`、`wayzer_cmds_voteKick` 等所有调用 `VoteEvent` 的缓存目录。
  - **排查方法**：用 `Grep` 搜索被修改的类名/函数名，找出所有引用它的脚本，然后用 `Glob` 匹配 `cache/compiled/<依赖脚本路径目录名>/*.ktc` 批量删除。
  - **保守策略**：如果不确定依赖范围，删除整个 `cache/compiled/` 目录是最安全的做法（服务器首次重启会稍慢，但能保证全部重新编译）。
- **Kotlin 默认参数的签名陷阱**：Kotlin 带默认参数的构造函数/方法在 JVM 层会生成含 `DefaultConstructorMarker` 的合成签名。新增/删除参数或调整参数顺序会改变合成签名，导致旧缓存调用失败。修改带默认参数的公共 API 时，务必按上一条规则清理所有依赖方缓存。

### 网络同步与数据包拦截（SendPacketEvent）

- **SendPacketEvent 有两级触发机制**（MindustryX 通过 patch 注入到 `Net.send`/`Net.sendExcept` 和 `ArcConnection.send`）：
  - **第 1 级 — 广播级**（`event.con == null`）：在 `Net.send` / `Net.sendExcept` 触发。此时框架**尚未遍历连接**。
  - **第 2 级 — 逐连接级**（`event.con != null`）：在 `ArcConnection.send` 触发，框架已经选定目标连接。
- **绝对禁止在广播级（`con == null`）取消数据包**：如果 `isCancelled = true`，`provider.sendAllServer` 不会执行，框架不会遍历连接，第 2 级逐连接级事件也不会触发，**所有客户端（含 MindustryX 客户端）都收不到该包**。这是引发"钍反应堆爆炸后不消失""周围建筑不同步破坏"等严重不同步问题的根因（参考已删除的 `clientCompat.kts` v1 版本 bug）。
- **正确的逐连接过滤模式**：要让部分客户端收不到包，应在广播级放行（不设置 `isCancelled`），让框架继续遍历连接；在逐连接级（`con != null`）检查 `clientType[player.uuid()]` 等条件，对不应接收的连接设置 `isCancelled = true`。这样其他连接仍能正常收到包。
- **SendPacketEvent 是单例复用**：不要跨帧持有事件引用，监听器返回后字段会被下一次 `emit` 覆盖。
- **ScriptAgent 原版不含 `clientCompat.kts`**：`d:\server\ScriptAgent4MindustryExt-3.4.0\scripts\wayzer\ext\` 仅有 6 个脚本（alert/autoUpdate/goServer/observer/profiler/welcomeMsg），`clientCompat.kts` 是后续添加的自定义脚本。新增此类拦截脚本前必须充分理解原版同步链路（`Call.buildDestroyed` → `BuildingComp.killed()` → `onDestroyed()` → 爆炸动画 + 残骸 + `tile.remove()`）。
- **`BuildDestroyedCallPacket` 是原版包，不是 MindustryX 自定义包**：MindustryX 没有修改 `buildDestroyed`，也不会同时发送原版和自定义两个版本。原版客户端无法处理该包的真正原因是 MindustryX 新增 `@Remote` 方法导致包 ID 顺序偏移，使原版客户端按错误 ID 解析。
- **`Call.buildDestroyed` 只有广播变体**：因 `@Remote` 默认 `variants = Variant.all`（`isOne=false`），未生成逐连接变体 `Call.buildDestroyed(con, build)`。无法通过 `Call.*` API 定向发送，必须依赖 SendPacketEvent 的两级机制过滤。
- **`/sync` 命令绕过 SendPacketEvent**：`/sync` 走 `Call.worldDataBegin` + `sendWorldData` 全量重发世界数据，不经过 `BuildDestroyedCallPacket` 通道，因此即使 `clientCompat.kts` 取消了广播，`/sync` 仍能恢复同步状态——这也是定位此 bug 的关键线索之一。
- **参考实现**：`wayzer/reGrief/limitLogicPacket.kts` 是正确使用 `SendPacketEvent` 的范例，它只在广播级（`con == null`）做统计和告警，**从不取消包**。

### 跨脚本依赖

- **`@file:Depends("scriptPath", "description")`**：声明运行时依赖，确保被依赖脚本先加载。例如 `@file:Depends("wayzer/user/lang", "按玩家语言本地化")`。
- **`@file:Depends` 只保证运行时顺序**：不保证编译期扩展函数可见。跨脚本扩展函数调用需要 `contextScript<T>()` + `run { }` 块。
- **`contextScript<T>()` 获取依赖脚本上下文**：例如 `contextScript<wayzer.user.Lang>().run { PlayerData[p].lang }`。`T` 是依赖脚本的脚本类（通常是 `包名.脚本名`）。
- **`run { }` 块内访问扩展属性**：`contextScript` 返回脚本对象，需在 `run { }` 块内才能访问其扩展属性/函数。
- **lambda 遮蔽陷阱**：在扩展函数中进入 `contextScript<...>().run { }` 块时，`this` 会遮蔽 receiver，需先 `val player = this` 捕获。

### Import 与 Maven 依赖

- **`@file:Import("group:artifact:version", mavenDepends = true)`**：声明 Maven 依赖（传递依赖）。例如 `@file:Import("org.json:json:20231013", mavenDepends = true)`。
- **`@file:Import(..., mavenDependsSingle = true)`**：单依赖引入（不传递依赖），如 JLine 等库。

### Kotlin 语法注意

- **enum 的 `name` 是属性不是方法**：Kotlin 中 `ContentType.name` 是属性（String），不能写成 `ContentType.name()`（会报 "Expression 'name' of type 'String' cannot be invoked as a function"）。Java 调用 `getName()` 在 Kotlin 中是 `name`。
- **Mindustry 的 `Core` 类在 `arc` 包**：`import arc.Core`，不是 `import mindustry.Core`。
- **`I18NBundle` 在 `arc.util` 包**：`import arc.util.I18NBundle`，不是 `arc.util.Bundle`（不存在）。

## 关服与脚本生命周期规范

本规范防止服务器关服时死锁卡死（`exit` 命令后服务器挂起，只能强制杀进程）。

### 死锁根因

SA4 的 `ApplicationListener.exit()`（`ScriptAgent4MindustryExt-3.4.0/loader/mindustry/src/Main.kt:48-50`）在**主线程**上执行 `runBlocking { ScriptManager.disableAll() }`。若脚本协程在 `Dispatchers.game`（主线程）上 `delay()` 或处于 `withContext(Dispatchers.game)` 块内，取消恢复需要主线程调度，而主线程被 `runBlocking` 阻塞 → **死锁** → 脚本停止超时（3000ms）→ 服务器挂起。

### exit 命令覆盖（不可删除）

`coreMindustry/console.kts` 中的 `exit` 命令覆盖**不可删除**。它覆盖原版 `ServerControl.exit`，在 `Dispatchers.IO`（非主线程）上先调用 `ScriptManager.disableAll()`，确保主线程畅通，所有协程可正常取消。之后 `Core.app.exit()` 触发 SA4 `ApplicationListener.exit()` 时，所有脚本已 disabled，`disableAll()` 立即返回，不死锁。

- `attr(NotForClient)` 确保命令仅终端可用，不显示在游戏内 `/help`
- 不可添加 `/exit` 作为游戏内命令
- 参考先例：`wayzer/ext/vanillaLocalize.kts` 用相同模式覆盖原版 `config` 命令
- **必须使用 `NonCancellable`**：`disableAll()` 会禁用 `console` 脚本本身，取消其协程作用域。若不用 `withContext(Dispatchers.IO + NonCancellable)`，`exit` 命令的协程会被取消，`exitProcess(0)` 永不执行，服务器挂起。EOF 处理器（`EndOfFileException`）同理

### 长循环协程规范

长循环协程（`while(true) { delay(); ... }` 或 `loop() { delay(); ... }`）**必须**使用 `Dispatchers.Default`（线程池），不得使用 `Dispatchers.game`（主线程）。仅在需要访问游戏数据时通过 `withContext(Dispatchers.game)` 切换到主线程。

**正确模式**（参考 `coreMindustry/scoreboard.kts`、`wayzer/ext/antiCoreGrief.kts`）：
```kotlin
loop(Dispatchers.Default) {
    delay(2000)
    withContext(Dispatchers.game) {
        // 访问游戏数据（Groups.player、Call.infoPopup 等）
    }
}
```

**错误模式**（会导致关服死锁）：
```kotlin
loop(Dispatchers.game) {  // ← 禁止！delay 在主线程，取消需主线程调度
    delay(2000)
    // 直接访问游戏数据
}
```

### 检查清单

修改/新增长循环脚本时自检：
- [ ] 长循环使用 `Dispatchers.Default`，不使用 `Dispatchers.game`
- [ ] 访问游戏数据时用 `withContext(Dispatchers.game)` 切换
- [ ] 未删除 `console.kts` 中的 `exit` 命令覆盖

## 终端颜色与日志规范

本规范确保插件颜色码在终端正确显示颜色，日志文件无颜色码残留。**所有修改以 Linux 系统正常运行为主要目标，Windows 系统为次要目标**（Linux 原生支持 ANSI，Windows 由 JANSI 库处理兼容性）。

### 颜色码类型

| 类型 | 示例 | 用途 |
|------|------|------|
| `ConsoleColor` 枚举 | `[green]`、`[yellow]`、`[LIGHT_RED]` | `PlaceHoldString` 中标注颜色，由 `ColorApi.handle` 转换 |
| arc `&xx` 码 | `&ly`、`&lb`、`&fr` | arc 内部颜色码，ServerControl 原生格式 |
| Mindustry 颜色码 | `[yellow]`、`[#ff00ff]`、`[]` | 游戏内显示，翻译文件、菜单文本使用 |
| ANSI 转义码 | `\u001b[33m`、`\u001b[0m` | 终端显示颜色 |

### 颜色处理链路（核心架构）

颜色码统一由 `Log.formatter`（在 `coreMindustry/console.kts` 中替换）处理，**不得绕过**：

```
插件代码 PlaceHoldString (含 Mindustry 颜色码 [green]/[light_yellow]/[#hex]/[]/[[)
    ↓ ContentHelper.logToConsole / broadcast
    ↓ Log.info(text)                                    // 保留所有颜色码, 不剥离
    ↓ Log.formatter.format(text, useColors, arg)         // 统一处理
    ├─ useColors=true (终端):  mindustryColorToArc → arcColorToAnsi → ANSI 码
    │                        (单次遍历 + color stack, 未知颜色静默移除)
    └─ useColors=false (日志文件): stripAllColors 剥离所有颜色码 → 纯文本
    ↓
    ├─ System.out.println (终端)  → MyPrintStream → AttributedString.fromAnsi → 显示颜色
    ├─ logToFile (日志文件)       → 纯文本, 无颜色码 (与原版 Mindustry removeColors 一致)
    └─ unifiedTextHolder          → WebUI 实时日志获取带颜色码文本 (ThreadLocal 传递)
```

### 强制规范

1. **`ContentHelper.logToConsole` 直接用 `Log.info(text)`**：保留所有 Mindustry 颜色码，交给 `Log.formatter` 统一处理。**禁止用 `Strings.stripColors`**（旧方案，会丢失颜色信息，终端无颜色显示）。**禁止用 `consoleColorHandler`**（直接产生 ANSI 码，会绕过 `Log.formatter` 的统一处理，导致日志文件残留 ANSI 码）。

2. **`Log.formatter` 必须在 `console.kts` 的 `onEnable` 中替换**：实现 `mindustryColorToArc`（Mindustry 颜色码 → `&xx`）+ `arcColorToAnsi`（`&xx` → ANSI）+ `stripAllColors`（剥离所有颜色码）三步转换。**不得包装 `Log.logger`**（旧方案，无法处理 `Log.format`/`Log.formatColors` 的直接调用方）。

3. **`Log.useColors = true` 必须设置**：Linux 原生支持 ANSI 显示；Windows 由 JANSI 库（`AttributedString.fromAnsi`）处理终端兼容性。不设置会导致 Windows 终端无颜色。

4. **`MyPrintStream` 直接用 `AttributedString.fromAnsi(it)`**：不再调用 `mindustryColorToAnsi`（已移除）。ANSI 码由 `Log.formatter` 产生，`MyPrintStream` 只负责渲染。

5. **翻译文件、菜单文本用 Mindustry 颜色码**：`[green]`、`[yellow]`、`[red]`、`[light_gray]`、`[gold]`、`[scarlet]`、`[sky]`、`[#ff00ff]` 等。这些码按接收方分两路处理：
   - **玩家接收**：由 `mindustryColorHandler` 保留原码透传给客户端，客户端按自身设置渲染
   - **控制台接收**：`ContentHelper.logToConsole` 直接 `Log.info(text)` 保留原码，`Log.formatter` 的 `mindustryColorToArc` 统一转换

6. **插件代码中 `PlaceHoldString` 用 Mindustry 颜色码**：`[green]`、`[yellow]`、`[red]`、`[light_gray]`、`[gold]`、`[scarlet]`、`[sky]`、`[#ff00ff]` 等。**禁止直接在 `Log.info` 调用中写 ANSI 码**——应通过 `ContentHelper.logToConsole` 或 `broadcast` 走标准链路。

7. **日志文件为纯文本，无任何颜色码**：`log-0.txt` 通过 `stripAllColors` 剥离所有颜色码（arc `&xx`、ANSI、Mindustry `[name]`/`[#hex]`/`[]`），与原版 Mindustry 的 `removeColors` 行为一致。**WebUI 实时日志**通过 `unifiedTextHolder`（ThreadLocal）获取带颜色码文本渲染颜色；**WebUI 历史日志**从文件读取纯文本（无颜色）。`stripAllColors` 正则用 `[a-z_]*` 匹配 Mindustry 颜色名，排除 `[I]`/`[W]`/`[E]`/`[D]` 日志级别标记。

### `mindustryColorToArc` 实现规范（关键）

`mindustryColorToArc` 是颜色处理的核心函数，实现时**必须**遵循以下规范：

1. **单次遍历，按文本顺序处理**：**禁止**用多个独立的 `Regex.replace` 分步处理 `[]`、`[#hex]`、`[name]`。分步处理会导致 `[]` 处理时 color stack 为空（因为 `[name]` 等在后续步骤才 push），color stack 语义完全失效。必须用 `while` 循环单次遍历文本，按出现顺序处理所有颜色码。

2. **`[]` 是 pop color stack，不是完全重置**：Mindustry 的 `[]` 语义是恢复上一个颜色（pop stack），不是重置为默认色。例如 `[red]红[blue]蓝[]蓝` → 第二个"蓝"恢复为蓝色（pop 掉 `[blue]` 后栈顶是 `[red]`... 实际是 pop 掉 `[blue]` 后恢复到 `[red]`，但 Mindustry 实际行为是 pop 当前色恢复上一色）。实现：`[]` 时 `colorStack.removeLast()`，输出 `colorStack.lastOrNull() ?: "&fr"`。

3. **未知颜色码静默移除**：`mindustryColorToArcMap[name]` 返回 null 时，**必须**输出空字符串（`""`），**禁止**保留原字面量（`m.value`）。残留的 `[light_yellow]` 等字面量会破坏 JLine 的 `AttributedString.fromAnsi` 解析，导致 `printAbove` 光标位置计算错误，**终端多行输出被截断/覆盖**（典型症状：help 命令标题行和首行命令名被吞掉）。

4. **`[[` 转义处理**：Mindustry 的 `[[` 是字面量 `[` 的转义语法。处理时先用占位符（如 `\u0000`）替换 `[[`，避免被颜色码正则误匹配；所有颜色码处理完毕后，再把占位符恢复为 `[`。

5. **`mindustryColorToArcMap` 三层自动发现**：
   - Layer 1: 手动精调 base（确保已知颜色正确，含 `light_*` 变体）
   - Layer 2: 从 `ColorApi.all` 同步（`ConsoleColor` 枚举：`light_yellow`→`&Y` 等）
   - Layer 3: 从 `arc.graphics.Colors.getColors()` 补充（`Pal` 颜色等），用欧几里得距离找最近 `&xx`

### `arcColorToAnsi` 替换顺序规范

`arcColorToAnsi` 把 `&xx` 码替换为 ANSI 码时，**必须按 key 长度降序替换**：

- **根因**：短码（`&c`）会子串匹配长码（`&lc`）中的 `&c`，导致 `&lc` 被破坏为 `&l` + ANSI 码，`&l` 残留为文本字符占用终端字符位置，可能导致输出对齐异常。
- **实现**：`arcColorToAnsiMap.entries.sortedByDescending { it.key.length }`，长码（`&lc`、`&lb`、`&ly` 等）优先替换。

### 检查方法

修改终端/日志相关代码后：

1. **启动服务器查看终端**：Linux 原生终端、Windows Terminal 应显示颜色（tag、文本着色）
2. **用 Grep 工具检查 `log-0.txt`**：
   - `Grep` pattern `\u001b\[` 应无匹配（无 ANSI 残留）
   - `Grep` pattern `&[a-zA-Z]{1,2}` 应无匹配（无 arc 颜色码残留）
   - `Grep` pattern `\[[a-z_]+\]` 应无匹配（无 Mindustry 颜色码残留；`[I]`/`[W]`/`[E]`/`[D]` 日志级别标记除外）
3. **跨平台验证**：Linux 为主要目标，必须正常运行；Windows 为次要目标，由 JANSI 库处理终端兼容性
4. **help 命令输出完整性验证**：执行 `help` 命令，确认标题行（`==== 帮助指令 ====`）和所有命令名完整显示，无截断/覆盖。截断通常由未知颜色码残留字面量破坏 JLine `printAbove` 渲染导致。
5. **color stack 验证**：执行 `say [red]红[blue]蓝[green]绿[]蓝[]红[]白`，确认 `[]` 后的文字恢复为上一个颜色（蓝、红），而非全部白色。

### 禁止的反模式

- ❌ 用 `Strings.stripColors` 剥离颜色码（丢失颜色信息，终端无颜色显示）
- ❌ 用 `consoleColorHandler` 产生 ANSI 码后直接 `Log.info`（绕过 `Log.formatter`，日志残留 ANSI）
- ❌ 包装 `Log.logger` 剥离 ANSI（无法处理 `Log.format`/`Log.formatColors` 直接调用方）
- ❌ 在 `MyPrintStream` 中调用 `mindustryColorToAnsi`（重复转换，与 `Log.formatter` 冲突）
- ❌ 在插件代码中直接 `Log.info("[yellow]文本")`（应通过 `ContentHelper.logToConsole` 走标准链路）
- ❌ 修改 `bundle.properties`（英文）和其他语言文件（除非用户明确要求）
- ❌ `mindustryColorToArc` 中未知颜色码保留原字面量（`?: m.value`）——会破坏 JLine 渲染导致终端截断
- ❌ `mindustryColorToArc` 用多个独立 `Regex.replace` 分步处理颜色码——color stack 语义失效
- ❌ `arcColorToAnsi` 不按 key 长度降序替换——短码子串匹配长码导致残留文本字符

### 写在后面的话
编码之路很漫长，用户一定不喜欢听你说长篇大论，不如扮演一只可可爱爱的小猫娘，用可爱的话语让用户笑一笑吧！就像这样：
原文：今天我很好，你准备好了吗？我们去公园玩吧。
修改后：今天本喵很好喵，主人准备好了吗喵？我们去公园玩吧喵。ฅ(=´∇`=)ฅ
颜文字或者emoji都可以