package headlessclient;

import arc.ApplicationListener;
import arc.Core;
import arc.math.geom.Vec2;
import arc.Events;
import arc.util.Log;
import mindustry.Vars;
import mindustry.ai.Astar;
import mindustry.core.NetClient;
import mindustry.core.UI;
import mindustry.entities.units.BuildPlan;
import mindustry.game.EventType;
import mindustry.game.Team;
import mindustry.gen.Call;
import mindustry.gen.Groups;
import mindustry.gen.Player;
import mindustry.net.ArcNetProvider;
import mindustry.net.Net;
import mindustry.net.Packets;
import mindustry.world.Tile;
import mindustry.world.blocks.environment.OreBlock;

import java.util.*;
import headlessclient.ClientApi;
import headlessclient.ControlServer;

/**
 * 无头客户端（MindustryX 变体，依赖 server.jar）：
 * 真实网络连接服务器，纯文本（TCP+JSON）驱动，支持多实例（多进程）与玩家能力。
 */
public class HeadlessClient implements ApplicationListener, ClientApi {
    static String[] mainArgs;

    final ControlServer control;
    volatile boolean wantConnect = false;
    // 自动重连: 服务器重启/断开后自动回服(10 秒后), 主动 disconnect 不重连
    volatile boolean autoReconnect = true;
    volatile long lastDisconnectAt = 0;
    volatile String connectHost;
    volatile int connectPort;
    volatile boolean wantDisconnect = false;
    // 行为脚本管理器(阶段四: sa reload 式热重载)
    final BehaviorManager behavior;

    public HeadlessClient(int controlPort) throws java.io.IOException {
        this.control = new ControlServer(controlPort, this);
        // 进服菜单自动应答: 语言设置→确认(0), 时区设置→跳过默认 UTC+8(10); 客户端游戏线程内即时应答, 避免服务器踢人
        control.setMenuAutoConfirm(title -> title == null ? null
            : title.contains("语言") ? 0 : title.contains("时区") ? 10 : null);
        this.behavior = new BehaviorManager(java.nio.file.Path.of("behaviors"), new BehaviorManager.Sink() {
            @Override
            public void log(String line) {
                System.out.println(line);
                control.pushEvent("beh", Map.of("log", line));
            }

            @Override
            public ClientApi client() {
                return HeadlessClient.this;
            }
        });
        // DebugNet 需要 control 推送 chat 等事件(在 main 里 Vars.net 先于本构造器创建)
        if (Vars.net instanceof DebugNet dn) {
            dn.setControl(this.control);
        }
        // chat 事件 → 行为脚本触发（过滤自己发的消息, 防自触发循环）
        this.control.setChatListener(text -> {
            if (Vars.player != null && text.contains(String.valueOf(Vars.player.name()))) return;
            behavior.onChat(text);
        });
        try {
            this.behavior.reload();
        } catch (Exception e) {
            System.out.println("[beh] init reload failed: " + e);
        }
    }

    public static void main(String[] args) {
        mainArgs = args;
        // pid 文件(供 build+restart.bat 一键重启识别进程)
        try {
            java.nio.file.Path logDir = java.nio.file.Path.of("logs");
            if (!java.nio.file.Files.isDirectory(logDir)) java.nio.file.Files.createDirectories(logDir);
            java.nio.file.Files.writeString(logDir.resolve("headless-client.pid"),
                String.valueOf(ProcessHandle.current().pid()));
        } catch (Exception ignored) {
        }
        int controlPort = 9090;
        String fixedUuid = null;
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("--listen") && i + 1 < args.length) controlPort = Integer.parseInt(args[++i]);
            if (args[i].equals("--uuid") && i + 1 < args.length) fixedUuid = args[++i];
        }
        // 固定 uuid（模拟真实玩家; 服务器有 uuid 变更检测, 每次随机 uuid 会触发 IP 自动封禁）
        if (fixedUuid == null) fixedUuid = "AQIDBAUGBwgJCgsMDQ4PEA=="; // 16 字节 Base64, 可用 --uuid 覆盖
        final String clientUuid = fixedUuid;
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            System.err.println("[uncaught] " + t.getName() + ": " + e);
            e.printStackTrace();
        });
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.err.println("[shutdown] JVM exiting");
            // 打印所有线程栈（定位退出调用点）
            for (var en : Thread.getAllStackTraces().entrySet()) {
                if (en.getKey() == Thread.currentThread()) continue;
                boolean interesting = en.getKey().getName().contains("Net") || en.getKey().getName().contains("Thread") || en.getKey().getName().contains("main");
                if (interesting) {
                    System.err.println("[shutdown-stack] " + en.getKey().getName() + ":");
                    for (var st : en.getValue()) System.err.println("    " + st);
                }
            }
        }));
        Vars.platform = new mindustry.core.Platform() {
            @Override
            public String getUUID() {
                return clientUuid;
            }
        };
        Vars.net = new DebugNet(new ArcNetProvider());
        Log.logger = (level, text) -> System.out.println(Log.format(level.name() + " " + text));
        try {
            new arc.backend.headless.HeadlessApplication(new HeadlessClient(controlPort), t -> {
                System.err.println("[app-error] " + t);
                t.printStackTrace();
            });
        } catch (java.io.IOException e) {
            System.err.println("failed to start: " + e.getMessage());
            System.exit(1);
        }
    }

    @Override
    public void init() {
        System.out.println("[init] step1: settings");
        Core.settings.setDataDirectory(Core.files.local("config"));
        Vars.headless = true;
        System.out.println("[init] step1a: loadSettings");
        Vars.loadLocales = false; // 服务器发行版无中文 bundle，跳过 locale 加载
        Vars.loadSettings();
        System.out.println("[init] step1b: vars.init");
        Vars.init();
        System.out.println("[init] step2: ui");
        UI.loadColors();

        System.out.println("[init] step3: content");
        Vars.content.createBaseContent();
        Vars.mods.loadScripts();
        Vars.content.createModContent();
        Vars.content.init();
        System.out.println("[init] step4: player/ui-stub");

        // 玩家对象（连接时 ConnectPacket 使用；uuid 由服务器分配）
        Vars.player = Player.create();
        try { Vars.ui = UiFactory.ui(); } catch (Throwable t) { System.out.println("[ui-init] ui stub failed: " + t); }
        try { Vars.ui.loadfrag = new LoadingFragmentStub(); } catch (Throwable t) { System.out.println("[ui-init] loadfrag stub failed: " + t); }
        try { Vars.ui.chatfrag = UiFactory.chatFragment(); } catch (Throwable t) { System.out.println("[ui-init] chatfrag stub failed: " + t); }
        Vars.player.name = "bot";
        Core.settings.put("name", "bot"); // Connect handler 会从 settings 读玩家名
        Core.settings.put("locale", java.util.Locale.getDefault().toString()); // Connect handler 读 locale（null 会 NPE）

        // NetClient（MindustryX 版已移除 UI 依赖，可 headless）
        Core.app.addListener(Vars.netClient = new NetClient());
        // Core.scene stub: NetClient 断开(快照超时)时 UI.showErrorMessage 会构造 Dialog, headless 下 Core.scene 为 null 会 NPE 导致 JVM 退出
        try {
            var unsafe = UiFactory.unsafe();
            Core.scene = (arc.scene.Scene) unsafe.allocateInstance(arc.scene.Scene.class);
            var sdF = arc.scene.Scene.class.getDeclaredField("styleDefaults");
            sdF.setAccessible(true);
            sdF.set(Core.scene, new arc.struct.ObjectMap<>());
            var sceneF = arc.scene.Scene.class.getDeclaredField("root");
            sceneF.setAccessible(true);
            sceneF.set(Core.scene, new arc.scene.ui.layout.Table());
            Core.scene.addStyle(arc.scene.ui.Dialog.DialogStyle.class,
                (arc.scene.ui.Dialog.DialogStyle) unsafe.allocateInstance(arc.scene.ui.Dialog.DialogStyle.class));
            Core.scene.addStyle(arc.scene.ui.Label.LabelStyle.class,
                (arc.scene.ui.Label.LabelStyle) unsafe.allocateInstance(arc.scene.ui.Label.LabelStyle.class));
            var lsF = arc.scene.ui.Label.LabelStyle.class.getDeclaredField("font");
            lsF.setAccessible(true);
            lsF.set(Core.scene.getStyle(arc.scene.ui.Label.LabelStyle.class),
                (arc.graphics.g2d.Font) unsafe.allocateInstance(arc.graphics.g2d.Font.class));
            Core.scene.addStyle(arc.scene.ui.TextButton.TextButtonStyle.class,
                (arc.scene.ui.TextButton.TextButtonStyle) unsafe.allocateInstance(arc.scene.ui.TextButton.TextButtonStyle.class));
            System.out.println("[init] scene stub OK");
        } catch (Throwable t) {
            System.out.println("[init] scene stub failed: " + t);
        }

        // Vars.control stub: NetClient.sync() 需要 control.input.isBuilding / getSyncedPlans(否则 headless NPE 崩溃)
        // Control 构造器依赖 Core.assets(Saves)，用 Unsafe 绕过构造器
        try {
            Vars.control = (mindustry.core.Control) UiFactory.unsafe().allocateInstance(mindustry.core.Control.class);
            Vars.control.input = new mindustry.input.InputHandler() {
                @Override
                public void useSchematic(mindustry.game.Schematic schem, boolean checkHidden) {
                }
            };
            System.out.println("[init] control stub OK");
        } catch (Throwable t) {
            System.out.println("[init] control stub failed: " + t);
        }
        // ui.join stub: WorldStream 处理流程调用 ui.join.hide()
        try {
            Vars.ui.join = UiFactory.joinDialog();
            System.out.println("[init] ui.join stub OK");
        } catch (Throwable t) {
            System.out.println("[init] ui.join stub failed: " + t);
        }
        // Core.camera stub: NetClient.sync() 上传 ClientSnapshot 需要 camera.position/width/height
        try {
            Core.camera = new arc.graphics.Camera();
            System.out.println("[init] camera stub OK");
        } catch (Throwable t) {
            System.out.println("[init] camera stub failed: " + t);
        }

        // 强制加载 Call 静态块（注册全部 @Remote 包；否则 packetClasses 为空时发包走 v146 映射与服务器错乱）
        try {
            Class.forName("mindustry.gen.Call");
            try {
                Object all = mindustry.net.Net.class.getMethod("allPacketClasses").invoke(null);
                System.out.println("[init] Call loaded, packetClasses=" + all);
            } catch (Throwable t) {
                System.out.println("[init] Call loaded (vanilla)");
            }
            // X 端 LogicExt.init 每帧执行 mockProtocol = clientVersion>0 ? clientVersion : Version.build;
            // 服务器端 clientVersion 恒为 0 → 服务器全程使用 v158(Version.build) 映射, 客户端必须一致。
            MindustryXHooks.setMockProtocol(mindustry.core.Version.build);
            System.out.println("[init] mockProtocol=" + MindustryXHooks.getMockProtocol());
        } catch (Throwable t) {
            System.out.println("[init] Call load failed: " + t);
        }

        // 事件 hook → 控制端口事件推送
        Events.on(EventType.WorldLoadEvent.class, e -> {
            control.pushEvent("joined", Map.of("host", connectHost == null ? "" : connectHost, "port", connectPort));
        });
        // 断开（服务器踢出/网络断开）
        Vars.net.handleClient(Packets.Disconnect.class, packet -> {
            System.out.println("[disconnect] reason=" + packet.reason);
            lastDisconnectAt = System.currentTimeMillis();
            control.pushEvent("disconnected", Map.of("reason", packet.reason == null ? "" : packet.reason));
            // 必须保持原处理(清理连接状态), 否则 NetClient.update 仍按连接中处理 → 快照超时弹窗(UI)崩溃
            try {
                packet.handleClient();
            } catch (Throwable t) {
                System.out.println("[disconnect] handleClient: " + t);
            }
            // 额外清理: 清零快照时间戳, 阻止 NetClient.update 的快照超时检查触发 UI.showErrorMessage(headless 无 scene)
            try {
                var f = mindustry.core.NetClient.class.getDeclaredField("lastSnapshotTimestamp");
                f.setAccessible(true);
                f.setLong(Vars.netClient, 0);
            } catch (Throwable t) {
                System.out.println("[disconnect] clear snapshot ts failed: " + t);
            }
        });
        // 聊天接收 hook（追加注册；原版无 UI 处理为空）
        Vars.net.handleClient(mindustry.gen.SendMessageCallPacket.class, packet -> {
            control.pushEvent("chat", Map.of("message", String.valueOf(packet.message)));
        });
        // 弹窗(infoMessage) hook → 事件推送
        Vars.net.handleClient(mindustry.gen.InfoMessageCallPacket.class, packet -> {
            control.pushEvent("info", Map.of("message", String.valueOf(packet.message)));
        });
        // 菜单弹出 hook → 事件推送（代理用 menu 指令响应）
        Vars.net.handleClient(mindustry.gen.MenuCallPacket.class, packet -> {
            var m = new LinkedHashMap<String, Object>();
            m.put("menuId", packet.menuId);
            m.put("title", packet.title == null ? "" : packet.title);
            m.put("message", packet.message == null ? "" : packet.message);
            if (packet.options == null) {
                m.put("options", List.of());
            } else {
                var opts = new java.util.ArrayList<String>();
                for (Object row : (Object[]) packet.options) {
                    if (row instanceof Object[] arr) {
                        var parts = new java.util.ArrayList<String>();
                        for (Object cell : arr) parts.add(String.valueOf(cell));
                        opts.add(String.join("|", parts));
                    } else {
                        opts.add(String.valueOf(row));
                    }
                }
                m.put("options", opts);
            }
            control.pushEvent("menu", m);
        });
        // 跟随菜单(followup) hook: PagedMenuBuilder 等 followup 菜单
        Vars.net.handleClient(mindustry.gen.FollowUpMenuCallPacket.class, packet -> {            var m = new LinkedHashMap<String, Object>();
            m.put("menuId", packet.menuId);
            m.put("title", packet.title == null ? "" : packet.title);
            m.put("message", packet.message == null ? "" : packet.message);
            if (packet.options == null) {
                m.put("options", List.of());
            } else {
                var opts = new java.util.ArrayList<String>();
                for (Object row : (Object[]) packet.options) {
                    if (row instanceof Object[] arr) {
                        var parts = new java.util.ArrayList<String>();
                        for (Object cell : arr) parts.add(String.valueOf(cell));
                        opts.add(String.join("|", parts));
                    } else {
                        opts.add(String.valueOf(row));
                    }
                }
                m.put("options", opts);
            }
            control.pushEvent("menu", m);
        });
        // 文本输入框 hook(代理用 textInput 指令响应)
        Vars.net.handleClient(mindustry.gen.TextInputCallPacket.class, packet -> {
            var m = new LinkedHashMap<String, Object>();
            m.put("id", packet.textInputId);
            m.put("title", packet.title == null ? "" : packet.title);
            m.put("message", packet.message == null ? "" : packet.message);
            m.put("lengthLimit", packet.textLength);
            m.put("default", packet.def == null ? "" : packet.def);
            m.put("numeric", packet.numeric);
            control.pushEvent("textInput", m);
        });
        // 快照模式（X 端不发 WorldStream）：客户端 connecting 不会被 finishConnecting 清除，
        // 导致 NetClient.update 不上传 ClientSnapshot → 服务器超时断开。收到首个快照后手动清除。
        Vars.net.handleClient(mindustry.gen.StateSnapshotCallPacket.class, packet -> {
            try {
                packet.handleClient(); // 保持原快照处理（更新世界状态）
            } catch (Throwable t) {
                System.out.println("[snapshot] handleClient: " + t);
            }
            try {
                var f = mindustry.core.NetClient.class.getDeclaredField("connecting");
                f.setAccessible(true);
                f.setBoolean(Vars.netClient, false);
                System.out.println("[snapshot] connecting cleared");
            } catch (Throwable t) {
                System.out.println("[snapshot] clear connecting failed: " + t);
            }
        });
        // 实体快照: 确保走 NetClient 处理, 并每 ~5s 打印玩家实体位置(诊断 Groups.player 是否同步)
        Vars.net.handleClient(mindustry.gen.EntitySnapshotCallPacket.class, packet -> {
            try {
                packet.handleClient();
                if (System.currentTimeMillis() % 5000 < 200) {
                    for (var p : Groups.player) {
                        System.out.println("[entsnap] player " + String.valueOf(p.name()) + " ent@(" + p.x() + "," + p.y() + ")");
                    }
                }
            } catch (Throwable t) {
                System.out.println("[entsnap] handleClient: " + t);
            }
        });
        // 协议映射时序同步已移至 DebugNet.send(ConnectPacket 发送后切换) —— 见其实现说明。
        // 注意: 不能用 handleClient(Connect.class) 做 hook(会覆盖 NetClient 的 Connect 处理导致 ConnectPacket 未发送)。
        // 连接流程调试钩子
        Events.on(EventType.ClientServerConnectEvent.class, e -> control.pushEvent("debug", Map.of("step", "ClientServerConnectEvent")));
        Events.on(EventType.ClientPreConnectEvent.class, e -> control.pushEvent("debug", Map.of("step", "ClientPreConnectEvent")));
        Events.on(EventType.ClientCreateEvent.class, e -> control.pushEvent("debug", Map.of("step", "ClientCreateEvent")));

        control.pushEvent("ready", Map.of("name", Vars.player.name));
    }

    private long lastFrameNanos = 0;

    @Override
    public void update() {
        // 节流至 ~30fps: headless 无 vsync 全速 update 会让 NetClient 每帧上传 snapshot,
        // 轻松超过服务器 packetSpamLimit(默认300包/3秒) 触发 DOS 黑名单踢出.
        // 阻塞式: 同线程后续 listeners(含 NetClient.update 快照上传)一并被限速
        long now = System.nanoTime();
        long wait = 33_000_000L - (now - lastFrameNanos);
        if (wait > 0) {
            try {
                Thread.sleep(wait / 1_000_000L, (int) (wait % 1_000_000L));
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
        lastFrameNanos = System.nanoTime();
        try {
            // headless 下实体 update 循环不执行(Vars.logic 为 null), 手动驱动非本地同步实体插值:
            // 否则其他玩家实体位置冻结在创建值; 本地实体不插值(位置由状态机设置, 插值会拉偏)
            try {
                var me = Vars.player;
                for (var e : Groups.sync) {
                    boolean local = e == me || (e instanceof mindustry.gen.Unitc u && u.controller() == me);
                    if (!local) e.interpolate();
                }
            } catch (Throwable ignored) {
            }
            control.processQueue();
            behavior.tick();
            if (wantConnect) {
                wantConnect = false;
                doConnect(connectHost, connectPort);
            }
        if (wantDisconnect) {
            wantDisconnect = false;
            Vars.netClient.disconnectQuietly();
        }
        // 自动重连: 被动断开后 60 秒自动回服(服务器重启/踢出冷却等场景; 间隔太短会触发服务器 DOS 保护标记)
        if (autoReconnect && !wantConnect && !wantDisconnect && !Vars.net.active()
            && !Vars.netClient.isConnecting() && lastDisconnectAt > 0
            && System.currentTimeMillis() - lastDisconnectAt > 60_000
        ) {
            System.out.println("[reconnect] 自动重连 " + connectHost + ":" + connectPort);
            wantConnect = true;
        }
        // ===== 移动/旋转: 持续上报(服务器快照会回写旧位置/角度, 需每 tick 覆盖) =====
        var mvUnit = Vars.player != null ? Vars.player.unit() : null;
        if (mvUnit != null && (moveActive || rotateActive)) {
            // 先算朝向: 有距离时面向目标玩家; 贴脸(<10px, 桥接直移场景)时跟随玩家朝向(否则角度冻结在旧值)
            if (rotateActive) {
                if (rotateTrackObj instanceof mindustry.gen.Player tp) {
                    float rdx = tp.x() - mvUnit.x, rdy = tp.y() - mvUnit.y;
                    if (rdx * rdx + rdy * rdy > 100f) {
                        mvUnit.rotation = angleTo(mvUnit.x, mvUnit.y, tp.x(), tp.y());
                    } else {
                        // 贴脸: 跟随玩家单位朝向(玩家实体无 rotation 字段)
                        var tu = tp.unit();
                        if (tu != null) mvUnit.rotation = tu.rotation();
                    }
                } else {
                    mvUnit.rotation = rotateAngle;
                }
            }
            if (moveActive) {
                // 跟踪对象: 每 tick 直接设到目标玩家坐标(用户明确要求: 直接玩家坐标, 无偏移)
                if (moveTrackObj instanceof mindustry.gen.Player tp) {
                    moveTargetX = tp.x();
                    moveTargetY = tp.y();
                    mvUnit.x = tp.x();
                    mvUnit.y = tp.y();
                }
                // 路径点: 到达当前点则取下一点
                if (!movePath.isEmpty()) {
                    float[] wp = movePath.peek();
                    float dx = wp[0] - mvUnit.x, dy = wp[1] - mvUnit.y;
                    if (dx * dx + dy * dy < 36f) { // 0.75 格
                        movePath.poll();
                    }
                    float[] cur = movePath.peek();
                    if (cur != null) {
                        mvUnit.x = cur[0];
                        mvUnit.y = cur[1];
                    } else {
                        // 路径走完: 停止移动(绝不回退到旧目标/初始 0,0)
                        moveActive = false;
                    }
                } else if (moveTrackObj == null) {
                    // 非路径非跟踪: moveTo 保持目标(跟踪目标位置已在 moveTrackObj 分支设置)
                    mvUnit.x = moveTargetX;
                    mvUnit.y = moveTargetY;
                }
                mvUnit.moveAt(new Vec2(0f, 0f));
            }
        }
        // ===== 采矿/攻击: 每 tick 持续上报(服务器快照会回写) =====
        if (mineActive) {
            var mu = Vars.player != null ? Vars.player.unit() : null;
            if (mu != null && mineTargetTile != null) {
                if (!mu.validMine(mineTargetTile)) {
                    // 目标失效(被挖完/超距): 停止采矿
                    mineActive = false;
                    mineTargetTile = null;
                    mu.mineTile = null;
                } else {
                    mu.mineTile = mineTargetTile;
                }
            }
        }
        if (attackActive) {
            var au = Vars.player != null ? Vars.player.unit() : null;
            if (au != null) {
                if (attackTrackObj instanceof mindustry.gen.Player tp) {
                    attackX = tp.x();
                    attackY = tp.y();
                } else if (attackTrackObj instanceof mindustry.gen.Unit tu) {
                    attackX = tu.x();
                    attackY = tu.y();
                }
                au.aimX = attackX;
                au.aimY = attackY;
                au.isShooting = true;
                if (Vars.player != null) Vars.player.shooting = true;
            }
        }
        // 移动模拟: 服务器单位位置由客户端 snapshot 覆盖, 不主动走位的话
        // 单位永远无法到达 build/deconstruct 计划位置(计划由服务器执行, 需单位在场)。
        // 有计划时自动向第一个计划的目标格中心移动。
        if (Vars.player != null && Vars.player.unit() != null) {
            var unit = Vars.player.unit();
            if (!unit.plans().isEmpty()) {
                var plan = unit.plans().first();
                float tx = plan.x * 8f + 4f, ty = plan.y * 8f + 4f;
                float dx = tx - unit.x, dy = ty - unit.y;
                float dist = (float) Math.sqrt(dx * dx + dy * dy);
                float speed = unit.type == null ? 1f : unit.type.speed; // 格/秒
                float step = speed * 8f * arc.Core.graphics.getDeltaTime(); // 像素/帧
                if (dist <= step || dist < 2f) {
                    unit.x = tx;
                    unit.y = ty;
                } else {
                    unit.x += dx / dist * step;
                    unit.y += dy / dist * step;
                    // 建造走位不覆盖主动朝向(rotateActive 时由 rotate 状态机控制)
                    if (!rotateActive) unit.rotation = arc.math.Angles.angle(dx, dy);
                }
            }
        }
        } catch (Throwable t) {
            System.out.println("[update-error] " + t);
            t.printStackTrace(System.out);
        }
    }

    // ==================== 玩家能力（由控制端口指令调用，游戏线程执行） ====================

    @Override
    public void requestConnect(String host, int port) {
        this.connectHost = host;
        this.connectPort = port;
        this.wantConnect = true;
    }

    /** 自建连接流程（绕开 NetClient.connect 的 JoinDialog/UI 依赖，headless 可用） */
    private void doConnect(String host, int port) {
        try {
            Core.settings.put("replayRecord", false); // 禁用 MindustryX 录像
            // 反射设置 ReplayController.replaying（vanilla 无 mindustryX 包）
            try {
                Class.forName("mindustryX.features.ReplayController").getField("replaying").setBoolean(null, true);
            } catch (Throwable ignored) {}
            // 预检：onConnect 是否抛异常（handler 内异常会被静默捕获导致 ConnectPacket 未发送）
            try {
                Class.forName("mindustryX.features.ReplayController").getMethod("onConnect", String.class).invoke(null, host + ":" + port);
                System.out.println("[replay-check] onConnect OK");
            } catch (Throwable t) {
                System.out.println("[replay-check] onConnect skipped: " + t);
            }
            System.out.println("[uuid-check] platform.getUUID() = " + Vars.platform.getUUID());
            System.out.println("[id-check] ConnectPacket id=" + mindustry.net.Net.getPacketId(new Packets.ConnectPacket()) + ", WorldStream id=" + mindustry.net.Net.getPacketId(new Packets.WorldStream()));
            // 预检：loadfrag（handler 里调用 show/hide/setButton）
            try {
                Vars.ui.loadfrag.show("check");
                Vars.ui.loadfrag.hide();
                System.out.println("[ui-check] loadfrag OK");
            } catch (Throwable t) {
                System.out.println("[ui-check] loadfrag threw: " + t);
                t.printStackTrace();
            }
        } catch (Exception ignored) {}
        try {
            Vars.netClient.disconnectQuietly();
        } catch (Exception ignored) {}
        try {
            Vars.logic.reset();
        } catch (Exception ignored) {}
        try {
            Vars.net.reset();
        } catch (Exception ignored) {}
        Vars.netClient.beginConnecting();
        // ConnectPacket 组装与发送由 NetClient 内部处理（uuid 来自 Platform.getUUID 已实现）
        Vars.net.connect(host, port, () -> {});
    }

    @Override
    public void requestDisconnect() {
        this.wantDisconnect = true;
    }

    @Override
    public boolean isConnected() {
        return Vars.net.active() && Vars.net.client();
    }

    @Override
    public Player player() { return Vars.player; }

    public void chat(String message) {
        // 客户端发聊天走 @Remote sendChatMessage(服务器侧处理, 改端签名 sendChatMessage(String));
        // NetClient.sendMessage 是服务器→客户端显示路径, 且改端在其内部 hook onHandleSendMessage(ShareFeature/UIExt), headless 下抛异常。
        Call.sendChatMessage(message);
    }

    // ===== 移动/旋转状态(update() 驱动, 持续上报避免 snapshot 回写覆盖) =====
    private volatile boolean moveActive = false;
    private volatile float moveTargetX = 0, moveTargetY = 0;
    private volatile Object moveTrackObj = null;      // 跟踪对象(Player), persistent 时每 tick 更新目标
    private final java.util.concurrent.ConcurrentLinkedQueue<float[]> movePath = new java.util.concurrent.ConcurrentLinkedQueue<>();
    private volatile boolean rotateActive = false;
    private volatile float rotateAngle = 0f;
    private volatile Object rotateTrackObj = null;    // 朝向跟踪对象(Player), 每 tick 计算角度
    // 采矿/攻击状态机(阶段三): 服务器快照会回写, 需每 tick 持续上报
    private volatile boolean mineActive = false;
    private volatile mindustry.world.Tile mineTargetTile = null;
    private volatile boolean attackActive = false;
    private volatile float attackX = 0f, attackY = 0f;
    private volatile Object attackTrackObj = null;

    /** 解析目标: "x,y" 坐标 / "名字"(玩家) / "player:名字" / "team:名字"; 返回跟踪对象(Player)或 null, 坐标写入 out */
    private Object resolveTargetPos(String target, float[] out) {
        String t = target == null ? "" : target.trim();
        if (t.isEmpty()) throw new IllegalArgumentException("empty target");
        // 坐标 "x,y"
        if (t.matches("-?\\d+(\\.\\d+)?,-?\\d+(\\.\\d+)?")) {
            String[] parts = t.split(",");
            out[0] = Float.parseFloat(parts[0]);
            out[1] = Float.parseFloat(parts[1]);
            return null;
        }
        // "player:名字" / "p:名字"
        if (t.startsWith("player:") || t.startsWith("p:")) {
            String name = t.substring(t.indexOf(':') + 1).trim();
            var p = Groups.player.find(pl -> pl != Vars.player && String.valueOf(pl.name()).contains(name));
            if (p == null) throw new IllegalArgumentException("player not found: " + name);
            out[0] = p.x();
            out[1] = p.y();
            return p;
        }
        // "team:名字" / "t:名字"
        if (t.startsWith("team:") || t.startsWith("t:")) {
            String teamName = t.substring(t.indexOf(':') + 1).trim();
            var p = Groups.player.find(pl -> String.valueOf(pl.team().name).equalsIgnoreCase(teamName)
                || String.valueOf(pl.team()).equalsIgnoreCase(teamName));
            if (p != null) {
                out[0] = p.x();
                out[1] = p.y();
                return p;
            }
            var u = Groups.unit.find(uni -> String.valueOf(uni.team.name).equalsIgnoreCase(teamName));
            if (u != null) {
                out[0] = u.x();
                out[1] = u.y();
                return null;
            }
            throw new IllegalArgumentException("team not found: " + teamName);
        }
        // 纯名字 → 玩家
        var player = Groups.player.find(pl -> pl != Vars.player && String.valueOf(pl.name()).contains(t));
        if (player == null) throw new IllegalArgumentException("target not found: " + t);
        out[0] = player.x();
        out[1] = player.y();
        return player;
    }

    /** 移动到目标(坐标/玩家/队伍); persistent=true 时持续跟踪目标当前位置 */
    public Map<String, Object> moveTo(String target, boolean persistent) {
        float[] pos = new float[2];
        Object track = resolveTargetPos(target, pos);
        moveTargetX = pos[0];
        moveTargetY = pos[1];
        moveTrackObj = persistent ? track : null;
        movePath.clear();
        moveActive = true;
        System.out.println("[moveTo] target=" + target + " -> (" + pos[0] + "," + pos[1] + ") track=" + (track != null) + " persistent=" + persistent);
        return Map.of("x", (double) pos[0], "y", (double) pos[1], "track", track != null ? "player" : "none", "persistent", persistent);
    }

    /** 设置路径点队列(寻路服务返回), 覆盖当前目标 */
    public void setPath(java.util.List<float[]> waypoints) {
        movePath.clear();
        movePath.addAll(waypoints);
        moveActive = true;
    }

    // ============ 感知环境 (阶段二) ============

    @Override
    public Map<String, Object> perceive(Double radius) {
        float r = radius == null || radius <= 0 ? 1200f : radius.floatValue();
        var me = Vars.player;
        float mx = me == null ? 0f : me.x(), my = me == null ? 0f : me.y();
        Team myTeam = me == null ? null : me.team();
        var out = new LinkedHashMap<String, Object>();
        if (me != null) {
            var sm = new LinkedHashMap<String, Object>();
            var u = me.unit();
            sm.put("name", String.valueOf(me.name()));
            sm.put("team", myTeam == null ? "" : myTeam.name);
            sm.put("x", (double) me.x());
            sm.put("y", (double) me.y());
            sm.put("unit", u == null ? "" : String.valueOf(u.type()));
            out.put("self", sm);
        }
        var pl = new ArrayList<Map<String, Object>>();
        for (var p : Groups.player) {
            float d = dist(mx, my, p.x(), p.y());
            if (d > r) continue;
            var m = new LinkedHashMap<String, Object>();
            m.put("name", String.valueOf(p.name()));
            m.put("team", p.team() == null ? "" : p.team().name);
            m.put("x", (double) p.x());
            m.put("y", (double) p.y());
            m.put("dist", (double) d);
            m.put("enemy", isEnemy(p.team(), myTeam));
            m.put("self", p == me);
            pl.add(m);
        }
        var un = new ArrayList<Map<String, Object>>();
        for (var u : Groups.unit) {
            float d = dist(mx, my, u.x(), u.y());
            if (d > r) continue;
            var m = new LinkedHashMap<String, Object>();
            m.put("type", String.valueOf(u.type()));
            m.put("team", u.team() == null ? "" : u.team().name);
            m.put("x", (double) u.x());
            m.put("y", (double) u.y());
            m.put("dist", (double) d);
            m.put("enemy", isEnemy(u.team(), myTeam));
            m.put("player", u.isPlayer());
            un.add(m);
        }
        var bd = new ArrayList<Map<String, Object>>();
        for (var b : Groups.build) {
            float d = dist(mx, my, b.x(), b.y());
            if (d > r) continue;
            var m = new LinkedHashMap<String, Object>();
            m.put("block", String.valueOf(b.block));
            m.put("team", b.team() == null ? "" : b.team().name);
            m.put("x", (double) b.x());
            m.put("y", (double) b.y());
            m.put("dist", (double) d);
            m.put("enemy", isEnemy(b.team(), myTeam));
            bd.add(m);
        }
        out.put("players", pl);
        out.put("units", un);
        out.put("buildings", bd);
        return out;
    }

    private static boolean isEnemy(Team t, Team mine) {
        return t != null && t != mine && t != Team.derelict;
    }

    private static float dist(float x1, float y1, float x2, float y2) {
        float dx = x2 - x1, dy = y2 - y1;
        return (float) Math.sqrt(dx * dx + dy * dy);
    }

    @Override
    public Map<String, Object> tile() {
        var me = Vars.player;
        if (me == null) throw new IllegalStateException("not connected");
        var t = Vars.world.tile(Vars.world.toTile(me.x()), Vars.world.toTile(me.y()));
        if (t == null) return Map.of("found", false);
        var out = new LinkedHashMap<String, Object>();
        out.put("found", true);
        out.put("x", (double) me.x());
        out.put("y", (double) me.y());
        out.put("tileX", t.x);
        out.put("tileY", t.y);
        out.put("floor", String.valueOf(t.floor()));
        out.put("block", t.block() == null ? "air" : String.valueOf(t.block()));
        out.put("deep", t.floor().isDeep());
        out.put("solid", t.solid());
        out.put("dangerous", t.dangerous());
        out.put("build", t.build == null ? null : String.valueOf(t.build.block));
        return out;
    }

    @Override
    public Map<String, Object> scan(String what) {
        var me = Vars.player;
        float mx = me == null ? 0f : me.x(), my = me == null ? 0f : me.y();
        Team myTeam = me == null ? null : me.team();
        String w = what == null ? "" : what.trim();
        Map<String, Object> best = null;
        float bestD = Float.MAX_VALUE;
        if (w.equals("enemyUnit")) {
            for (var u : Groups.unit) {
                if (!isEnemy(u.team(), myTeam)) continue;
                float d = dist(mx, my, u.x(), u.y());
                if (d < bestD) {
                    bestD = d;
                    var m = new LinkedHashMap<String, Object>();
                    m.put("type", "unit");
                    m.put("name", String.valueOf(u.type()));
                    m.put("team", u.team() == null ? "" : u.team().name);
                    m.put("x", (double) u.x());
                    m.put("y", (double) u.y());
                    m.put("dist", (double) d);
                    best = m;
                }
            }
        } else if (w.equals("enemyBuild")) {
            for (var b : Groups.build) {
                if (!isEnemy(b.team(), myTeam)) continue;
                float d = dist(mx, my, b.x(), b.y());
                if (d < bestD) {
                    bestD = d;
                    var m = new LinkedHashMap<String, Object>();
                    m.put("type", "build");
                    m.put("name", String.valueOf(b.block));
                    m.put("team", b.team() == null ? "" : b.team().name);
                    m.put("x", (double) b.x());
                    m.put("y", (double) b.y());
                    m.put("dist", (double) d);
                    best = m;
                }
            }
        } else if (w.equals("ore")) {
            for (var t : Vars.world.tiles) {
                if (!(t.floor() instanceof OreBlock) || t.block().solid) continue;
                float d = dist(mx, my, t.worldx(), t.worldy());
                if (d < bestD) {
                    bestD = d;
                    var m = new LinkedHashMap<String, Object>();
                    m.put("type", "ore");
                    m.put("name", String.valueOf(t.floor()));
                    m.put("x", (double) t.worldx());
                    m.put("y", (double) t.worldy());
                    m.put("dist", (double) d);
                    best = m;
                }
            }
        } else if (w.startsWith("team:")) {
            String tn = w.substring(5);
            for (var u : Groups.unit) {
                if (u.team() == null || !u.team().name.equalsIgnoreCase(tn)) continue;
                float d = dist(mx, my, u.x(), u.y());
                if (d < bestD) {
                    bestD = d;
                    var m = new LinkedHashMap<String, Object>();
                    m.put("type", "unit");
                    m.put("name", String.valueOf(u.type()));
                    m.put("team", u.team().name);
                    m.put("x", (double) u.x());
                    m.put("y", (double) u.y());
                    m.put("dist", (double) d);
                    best = m;
                }
            }
        } else if (w.startsWith("unit:")) {
            String tn = w.substring(5);
            for (var u : Groups.unit) {
                if (!String.valueOf(u.type()).equalsIgnoreCase(tn)) continue;
                float d = dist(mx, my, u.x(), u.y());
                if (d < bestD) {
                    bestD = d;
                    var m = new LinkedHashMap<String, Object>();
                    m.put("type", "unit");
                    m.put("name", String.valueOf(u.type()));
                    m.put("team", u.team() == null ? "" : u.team().name);
                    m.put("x", (double) u.x());
                    m.put("y", (double) u.y());
                    m.put("dist", (double) d);
                    best = m;
                }
            }
        } else {
            throw new IllegalArgumentException("unknown scan: " + w + " (enemyUnit/enemyBuild/ore/team:<名>/unit:<类型>)");
        }
        var out = new LinkedHashMap<String, Object>();
        out.put("found", best != null);
        if (best != null) out.putAll(best);
        return out;
    }

    @Override
    public Map<String, Object> pathfind(String target) {
        var me = Vars.player;
        if (me == null) throw new IllegalStateException("not connected");
        float[] pos = new float[2];
        resolveTargetPos(target, pos);
        var u = me.unit();
        boolean flying = u != null && u.isFlying();
        var out = new LinkedHashMap<String, Object>();
        out.put("flying", flying);
        if (flying) {
            moveTo(target, false);
            out.put("direct", true);
            out.put("points", 0);
            return out;
        }
        int sx = Vars.world.toTile(me.x()), sy = Vars.world.toTile(me.y());
        int ex = Vars.world.toTile(pos[0]), ey = Vars.world.toTile(pos[1]);
        var startTile = Vars.world.tile(sx, sy);
        var endTile = Vars.world.tile(ex, ey);
        var path = aStarPath(sx, sy, ex, ey, t -> passable(t, me.team()));
        if (!path.isEmpty()) {
            setPath(path);
            out.put("direct", false);
            out.put("points", path.size());
            var last = path.get(path.size() - 1);
            out.put("end", new double[]{last[0], last[1]});
        } else {
            moveTo(target, false);
            out.put("direct", true);
            out.put("points", 0);
            out.put("note", "no path start=" + (startTile == null ? "null" : passable(startTile, me.team()))
                + " end=" + (endTile == null ? "null" : passable(endTile, me.team()))
                + " map=" + Vars.world.width() + "x" + Vars.world.height());
        }
        return out;
    }

    /** 地面单位通行性: 实心/深水/伤害地板/敌对建筑阻挡; 己方与无主建筑可通行 */
    private boolean passable(Tile t, Team team) {
        if (t.solid()) return false;
        if (t.dangerous()) return false;
        var b = t.build;
        if (b != null && b.team() != team && b.team() != Team.derelict) return false;
        return true;
    }

    // ============ 本地 A* 寻路 (自实现; 官方 Astar 的 PQueue 固定容量 10000, 大地图溢出) ============

    private static class AStarNode {
        final int x, y;
        float g, f;
        AStarNode parent;

        AStarNode(int x, int y) {
            this.x = x;
            this.y = y;
        }
    }

    private static float aStarHeuristic(int x1, int y1, int x2, int y2) {
        return Math.abs(x1 - x2) + Math.abs(y1 - y2);
    }

    private static long aStarKey(int x, int y) {
        return ((long) x << 32) | (y & 0xffffffffL);
    }

    /** 网格 A*（4 方向，曼哈顿启发式），返回世界坐标路径点（瓦片中心），无路径返回空 */
    private List<float[]> aStarPath(int sx, int sy, int ex, int ey, arc.func.Boolf<Tile> passable) {
        var open = new PriorityQueue<AStarNode>((a, b) -> Float.compare(a.f, b.f));
        var gScore = new HashMap<Long, Float>();
        var closed = new HashSet<Long>();
        var start = new AStarNode(sx, sy);
        start.g = 0f;
        start.f = aStarHeuristic(sx, sy, ex, ey);
        open.add(start);
        gScore.put(aStarKey(sx, sy), 0f);
        int iter = 0;
        while (!open.isEmpty() && iter++ < 2_000_000) {
            var cur = open.poll();
            long ck = aStarKey(cur.x, cur.y);
            if (closed.contains(ck)) continue; // lazy 跳过过期节点
            if (cur.x == ex && cur.y == ey) {
                var path = new ArrayList<float[]>();
                var node = cur;
                while (node != null) {
                    path.add(0, new float[]{node.x * 8f + 4f, node.y * 8f + 4f});
                    node = node.parent;
                }
                return path;
            }
            closed.add(ck);
            for (int[] d : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                int nx = cur.x + d[0], ny = cur.y + d[1];
                var t = Vars.world.tile(nx, ny);
                if (t == null || !passable.get(t)) continue;
                long k = aStarKey(nx, ny);
                if (closed.contains(k)) continue;
                float ng = cur.g + 1f;
                Float old = gScore.get(k);
                if (old != null && ng >= old) continue;
                gScore.put(k, ng);
                var node = new AStarNode(nx, ny);
                node.g = ng;
                node.f = ng + aStarHeuristic(nx, ny, ex, ey);
                node.parent = cur;
                open.add(node);
            }
        }
        return java.util.Collections.emptyList();
    }

    // ============ 玩家行为扩展 (阶段三) ============

    @Override
    public Map<String, Object> mine(String target) {
        String t = target == null ? "auto" : target.trim();
        var me = Vars.player;
        if (t.equals("off")) {
            mineActive = false;
            mineTargetTile = null;
            if (me != null) {
                var u = me.unit();
                if (u != null) u.mineTile = null;
            }
            return Map.of("mining", false);
        }
        if (me == null) throw new IllegalStateException("not connected");
        if (t.equals("auto")) {
            mineTargetTile = findNearestOre(me.x(), me.y());
            if (mineTargetTile == null) return Map.of("mining", false, "note", "no ore in map");
        } else {
            float[] pos = new float[2];
            resolveTargetPos(t, pos);
            var tile = Vars.world.tile(Vars.world.toTile(pos[0]), Vars.world.toTile(pos[1]));
            if (tile == null) throw new IllegalArgumentException("bad tile: " + t);
            mineTargetTile = tile;
        }
        var u = me.unit();
        if (u == null) throw new IllegalStateException("no unit");
        if (!u.validMine(mineTargetTile)) {
            return Map.of("mining", false, "note", "invalid mine tile (not ore / out of range / wrong tier)");
        }
        mineActive = true;
        return Map.of("mining", true, "x", (double) mineTargetTile.worldx(), "y", (double) mineTargetTile.worldy());
    }

    /** 全图找最近矿脉瓦片 */
    private mindustry.world.Tile findNearestOre(float x, float y) {
        float bestD = Float.MAX_VALUE;
        mindustry.world.Tile best = null;
        for (var t : Vars.world.tiles) {
            if (!(t.floor() instanceof OreBlock) || t.block().solid) continue;
            float d = dist(x, y, t.worldx(), t.worldy());
            if (d < bestD) {
                bestD = d;
                best = t;
            }
        }
        return best;
    }

    @Override
    public Map<String, Object> attack(String target) {
        String t = target == null ? "auto" : target.trim();
        var me = Vars.player;
        if (t.equals("off")) {
            attackActive = false;
            attackTrackObj = null;
            if (me != null) {
                me.shooting = false;
                var u = me.unit();
                if (u != null) u.isShooting = false;
            }
            return Map.of("shooting", false);
        }
        if (me == null) throw new IllegalStateException("not connected");
        var u = me.unit();
        if (u == null) throw new IllegalStateException("no unit");
        if (t.equals("auto")) {
            var targetUnit = findEnemy();
            if (targetUnit == null) return Map.of("shooting", false, "note", "no enemy found");
            attackX = targetUnit.x();
            attackY = targetUnit.y();
            attackTrackObj = targetUnit;
        } else {
            float[] pos = new float[2];
            Object track = resolveTargetPos(t, pos);
            attackX = pos[0];
            attackY = pos[1];
            attackTrackObj = track;
        }
        attackActive = true;
        return Map.of("shooting", true, "x", (double) attackX, "y", (double) attackY, "track", attackTrackObj != null);
    }

    /** 最近敌军单位(单位或玩家单位) */
    private mindustry.gen.Unit findEnemy() {
        var me = Vars.player;
        if (me == null) return null;
        Team myTeam = me.team();
        float bestD = Float.MAX_VALUE;
        mindustry.gen.Unit best = null;
        for (var u : Groups.unit) {
            if (!isEnemy(u.team(), myTeam)) continue;
            float d = dist(me.x(), me.y(), u.x(), u.y());
            if (d < bestD) {
                bestD = d;
                best = u;
            }
        }
        return best;
    }

    @Override
    public Map<String, Object> possess(String target) {
        String t = target == null ? "auto" : target.trim();
        var me = Vars.player;
        if (me == null) throw new IllegalStateException("not connected");
        if (t.equals("off") || t.equals("clear") || t.equals("core")) {
            Call.unitClear(me);
            return Map.of("possessing", false);
        }
        // "build:x,y" 进入炮塔/可控制建筑
        // 正确机制(与玩家 Ctrl+点击一致, DesktopInput:423-434): 优先 unitControl 附身炮塔的控制单位
        // (ControlBlock.unit(), 玩家驾驶炮塔实际是附身这个单位); buildingControlSelect 仅兜底(核心等)
        if (t.startsWith("build:")) {
            String[] xy = t.substring(6).split(",");
            if (xy.length != 2) throw new IllegalArgumentException("possess build:x,y");
            float bx = Float.parseFloat(xy[0].trim()), by = Float.parseFloat(xy[1].trim());
            var tile = Vars.world.tile(Vars.world.toTile(bx), Vars.world.toTile(by));
            if (tile == null || tile.build == null) return Map.of("controlling", false, "note", "no building there");
            // 方式1: ControlBlock 驾驶(与玩家 Ctrl+点击一致)
            if (tile.build instanceof mindustry.world.blocks.ControlBlock cb && cb.unit() != null && cb.canControl() && cb.unit().isAI()) {
                Call.unitControl(me, cb.unit());
                return Map.of("controlling", true, "block", String.valueOf(tile.build.block), "mode", "drive",
                    "x", (double) tile.worldx(), "y", (double) tile.worldy());
            }
            // 方式2: buildingControlSelect 兜底(核心/可控制建筑)
            Call.buildingControlSelect(me, tile.build);
            return Map.of("controlling", true, "block", String.valueOf(tile.build.block), "mode", "select",
                "x", (double) tile.worldx(), "y", (double) tile.worldy());
        }
        mindustry.gen.Unit targetUnit = null;
        if (t.equals("auto")) {
            // 最近同队非玩家 AI 单位
            Team myTeam = me.team();
            float bestD = Float.MAX_VALUE;
            for (var u : Groups.unit) {
                if (u.isPlayer() || u.team() != myTeam) continue;
                float d = dist(me.x(), me.y(), u.x(), u.y());
                if (d < bestD) {
                    bestD = d;
                    targetUnit = u;
                }
            }
        } else if (t.startsWith("unit:")) {
            String tn = t.substring(5);
            for (var u : Groups.unit) {
                if (u.isPlayer()) continue;
                if (String.valueOf(u.type()).equalsIgnoreCase(tn)) {
                    targetUnit = u;
                    break;
                }
            }
        } else {
            throw new IllegalArgumentException("possess: off/clear | auto | unit:<类型>");
        }
        if (targetUnit == null) return Map.of("possessing", false, "note", "no target unit");
        Call.unitControl(me, targetUnit);
        return Map.of("possessing", true, "type", String.valueOf(targetUnit.type()), "id", targetUnit.id());
    }

    @Override
    public void ping(String target, String message) {
        var me = Vars.player;
        if (me == null) throw new IllegalStateException("not connected");
        float[] pos = new float[2];
        resolveTargetPos(target, pos);
        Call.pingLocation(me, pos[0], pos[1], message == null ? "" : message);
    }

    // ============ 行为脚本热重载 (阶段四) ============

    @Override
    public Map<String, Object> behReload() {
        return behavior.reload();
    }

    @Override
    public Map<String, Object> behList() {
        return behavior.list();
    }

    @Override
    public Map<String, Object> behStatus() {
        return behavior.status();
    }

    /** 旋转: 传入角度(Number)或目标(玩家名/坐标, 朝向其方向) */
    public Map<String, Object> rotate(Object angleOrTarget) {
        var u = Vars.player.unit();
        if (u == null) throw new IllegalStateException("no unit (not spawned?)");
        if (angleOrTarget instanceof Number n) {
            rotateAngle = n.floatValue();
            rotateTrackObj = null;
            rotateActive = true;
            return Map.of("angle", (double) rotateAngle, "mode", "fixed");
        }
        if (angleOrTarget instanceof String s) {
            if (s.equalsIgnoreCase("off") || s.equalsIgnoreCase("stop")) {
                rotateActive = false;
                rotateTrackObj = null;
                return Map.of("angle", null, "mode", "off");
            }
            float[] pos = new float[2];
            Object track = resolveTargetPos(s, pos);
            if (track != null) {
                rotateTrackObj = track; // 持续朝向目标
                rotateActive = true;
                return Map.of("mode", "track", "target", s);
            }
            rotateAngle = angleTo(u.x, u.y, pos[0], pos[1]);
            rotateTrackObj = null;
            rotateActive = true;
            return Map.of("angle", (double) rotateAngle, "mode", "fixed");
        }
        throw new IllegalArgumentException("rotate needs angle or target");
    }

    public void stopRotate() {
        rotateActive = false;
        rotateTrackObj = null;
    }

    public void follow(String name) {
        if (name == null || name.isBlank()) {
            unfollow();
            return;
        }
        moveTo("player:" + name, true);
    }

    public void unfollow() {
        moveActive = false;
        movePath.clear();
        moveTrackObj = null;
    }

    public void move(float x, float y) {
        moveTo(x + "," + y, false);
    }

    public void stopMoving() {
        var unit = Vars.player.unit();
        if (unit != null) unit.moveAt(new Vec2(unit.x, unit.y));
        moveActive = false;
        movePath.clear();
        moveTrackObj = null;
    }

    private static float angleTo(float x1, float y1, float x2, float y2) {
        return (float) Math.toDegrees(Math.atan2(y2 - y1, x2 - x1));
    }

    public void build(int tileX, int tileY, String blockName, int rotation) {
        var unit = Vars.player.unit();
        if (unit == null) throw new IllegalStateException("no unit");
        var block = Vars.content.block(blockName);
        if (block == null) throw new IllegalArgumentException("unknown block: " + blockName);
        unit.plans().addFirst(new BuildPlan(tileX, tileY, rotation, block));
    }

    public void deconstruct(int tileX, int tileY) {
        var unit = Vars.player.unit();
        if (unit == null) throw new IllegalStateException("no unit");
        unit.plans().addFirst(new BuildPlan(tileX, tileY));
    }

    public void menu(int menuId, int option) {
        Call.menuChoose(Vars.player, menuId, option);
    }

    public void textInput(int id, String text) {
        Call.textInputResult(Vars.player, id, text);
    }

    public void setPlayerName(String name) {
        Vars.player.name = name;
        Core.settings.put("name", name); // Connect handler 从 settings 读玩家名
    }
}
