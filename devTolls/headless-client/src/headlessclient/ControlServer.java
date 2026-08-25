package headlessclient;

import arc.util.Log;
import headlessclient.ClientApi;
import headlessclient.Json;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

/** 无头客户端控制端口：TCP + JSON 行协议（纯文本驱动） */
public final class ControlServer {
    private final ClientApi client;
    private final ServerSocket server;
    private final ConcurrentLinkedQueue<String> eventQueue = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<String> eventBuffer = new ConcurrentLinkedQueue<>(); // 历史事件缓冲(供 events 命令拉取)
    private static final int MAX_EVENT_BUFFER = 200;
    private final java.io.PrintWriter eventLog; // events.log 文件(代理可 tail 实时查看)
    private final ConcurrentLinkedQueue<Runnable> commandQueue = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<Client> clients = new ConcurrentLinkedQueue<>();
    private volatile boolean closed = false;
    private volatile java.util.function.Consumer<String> chatListener = null; // chat 事件 → 行为规则触发
    private volatile java.util.function.Function<String, Integer> menuAutoConfirm = null; // 常规菜单自动确认(title -> option)

    /** 设置 chat 事件监听(行为脚本触发用) */
    public void setChatListener(java.util.function.Consumer<String> listener) {
        this.chatListener = listener;
    }

    /** 设置常规菜单自动确认映射(title -> option, 如语言设置→0 时区设置→10; 返回 null 不自动应答) */
    public void setMenuAutoConfirm(java.util.function.Function<String, Integer> fn) {
        this.menuAutoConfirm = fn;
    }

    public ControlServer(int port, ClientApi client) throws IOException {
        this.client = client;
        this.server = new ServerSocket(port, 10, InetAddress.getLoopbackAddress());
        this.eventLog = new java.io.PrintWriter(new java.io.OutputStreamWriter(
            new java.io.FileOutputStream(new java.io.File("logs", "events.log"), true), StandardCharsets.UTF_8), true);
        System.out.println("headless-client control listening on 127.0.0.1:" + port + " (events -> logs/events.log)");
        Thread acceptor = new Thread(this::acceptLoop, "control-acceptor");
        acceptor.setDaemon(true);
        acceptor.start();
    }

    private void acceptLoop() {
        while (!closed) {
            try {
                Socket s = server.accept();
                Client c = new Client(s);
                clients.add(c);
                Thread t = new Thread(() -> handleClient(c), "control-client");
                t.setDaemon(true);
                t.start();
            } catch (IOException e) {
                if (!closed) System.err.println("accept error: " + e.getMessage());
                break;
            }
        }
    }

    private void handleClient(Client c) {
        try (BufferedReader in = new BufferedReader(new InputStreamReader(c.socket.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = in.readLine()) != null) {
                if (line.isBlank()) continue;
                final String msg = line;
                commandQueue.add(() -> {
                    try {
                        Map<String, Object> req = Json.asObject(Json.parse(msg));
                        Map<String, Object> resp = new LinkedHashMap<>();
                        String id = Json.str(req, "id");
                        if (id != null) resp.put("id", id);
                        try {
                            resp.put("ok", true);
                            resp.put("result", dispatch(Json.str(req, "op"), req));
                        } catch (Exception e) {
                            resp.put("ok", false);
                            resp.put("error", String.valueOf(e.getMessage()));
                        }
                        c.send(resp);
                    } catch (Exception e) {
                        c.send(Map.of("ok", false, "error", "bad json: " + e.getMessage()));
                    }
                });
            }
        } catch (IOException ignored) {
        } finally {
            clients.remove(c);
            try { c.close(); } catch (IOException ignored) {}
        }
    }

    /** 在游戏线程处理指令队列（由 HeadlessClient.update 调用） */
    public void processQueue() {
        Runnable r;
        while ((r = commandQueue.poll()) != null) {
            try { r.run(); } catch (Exception e) { Log.warn("command error: " + e); }
        }
        String ev;
        while ((ev = eventQueue.poll()) != null) {
            for (Client c : clients) c.sendRaw(ev);
        }
    }

    public void pushEvent(String event, Map<String, Object> data) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("event", event);
        m.putAll(data);
        String line = Json.stringify(m);
        eventQueue.add(line);
        // 缓冲 + 文件: 短连接错过的事件可通过 events 命令/events.log 补拉
        eventBuffer.add(line);
        while (eventBuffer.size() > MAX_EVENT_BUFFER) eventBuffer.poll();
        eventLog.println(System.currentTimeMillis() + " " + line);
        // chat 事件 → 行为脚本触发（sa reload 规则）
        if (event.equals("chat")) {
            String text = Json.str(m, "message");
            if (text != null) {
                var listener = chatListener;
                if (listener != null) listener.accept(text);
            }
        }
        // 常规菜单自动确认(进服弹窗: 语言设置→0, 时区设置→10 跳过; 由 fn 返回 null 不自动应答)
        if (event.equals("menu")) {
            String title = Json.str(m, "title");
            Long menuId = Json.lng(m, "menuId");
            var fn = menuAutoConfirm;
            if (fn != null && title != null && menuId != null) {
                Integer opt = fn.apply(title);
                if (opt != null) {
                    final int o = opt;
                    commandQueue.add(() -> {
                        try {
                            client.menu(menuId.intValue(), o);
                            System.out.println("[menu-auto] auto menu '" + title + "' -> option " + o);
                        } catch (Exception e) {
                            System.out.println("[menu-auto] failed: " + e);
                        }
                    });
                }
            }
        }
    }

    private Map<String, Object> dispatch(String op, Map<String, Object> msg) {
        return switch (op == null ? "" : op) {
            case "connect" -> {
                String host = Json.str(msg, "host");
                int port = Json.lng(msg, "port") == null ? 6567 : Json.lng(msg, "port").intValue();
                String name = Json.str(msg, "name");
                if (name != null && !name.isBlank()) client.setPlayerName(name);
                client.requestConnect(host == null ? "127.0.0.1" : host, port);
                yield Map.of("connecting", (host == null ? "127.0.0.1" : host) + ":" + port);
            }
            case "disconnect" -> {
                client.requestDisconnect();
                yield Map.of();
            }
            case "status" -> {
                var p = client.player();
                var unit = p.unit();
                var m = new LinkedHashMap<String, Object>();
                m.put("connected", client.isConnected());
                m.put("name", p.name);
                m.put("uuid", p.uuid() == null ? "" : p.uuid());
                if (unit == null) {
                    m.put("unit", null);
                } else {
                    var um = new LinkedHashMap<String, Object>();
                    um.put("x", unit.x);
                    um.put("y", unit.y);
                    um.put("type", unit.type == null ? "" : unit.type.name);
                    um.put("plans", unit.plans().size);
                    m.put("unit", um);
                }
                m.put("team", p.team() == null ? "" : p.team().name);
                yield m;
            }
            case "chat" -> {
                client.chat(Json.str(msg, "message") == null ? "" : Json.str(msg, "message"));
                yield Map.of();
            }
            case "move" -> {
                client.move(
                    Json.dbl(msg, "x") == null ? 0 : Json.dbl(msg, "x").floatValue(),
                    Json.dbl(msg, "y") == null ? 0 : Json.dbl(msg, "y").floatValue()
                );
                yield Map.of();
            }
            case "stop" -> {
                client.stopMoving();
                yield Map.of();
            }
            case "moveTo" -> {
                String target = Json.str(msg, "target");
                if (target == null || target.isBlank()) throw new IllegalArgumentException("moveTo needs target");
                boolean persistent = "true".equalsIgnoreCase(Json.str(msg, "persistent"));
                yield client.moveTo(target, persistent);
            }
            case "setPath" -> {
                List<Object> arr = Json.asArray(msg.get("waypoints"));
                List<float[]> wps = new ArrayList<>();
                for (Object o : arr) {
                    List<Object> p = Json.asArray(o);
                    wps.add(new float[]{((Number) p.get(0)).floatValue(), ((Number) p.get(1)).floatValue()});
                }
                client.setPath(wps);
                yield Map.of("count", wps.size());
            }
            case "rotate" -> {
                Object angle = msg.get("angle");
                String target = Json.str(msg, "target");
                if (target != null && !target.isBlank()) yield client.rotate(target);
                if (angle instanceof Number n) yield client.rotate(n);
                yield client.rotate("off");
            }
            case "stopRotate" -> {
                client.stopRotate();
                yield Map.of();
            }
            case "perceive" -> client.perceive(Json.dbl(msg, "radius"));
            case "tile" -> client.tile();
            case "scan" -> {
                String what = Json.str(msg, "what");
                if (what == null) throw new IllegalArgumentException("scan needs what (enemyUnit/enemyBuild/ore/team:<名>/unit:<类型>)");
                yield client.scan(what);
            }
            case "pathfind" -> {
                String target = Json.str(msg, "target");
                if (target == null) throw new IllegalArgumentException("pathfind needs target");
                yield client.pathfind(target);
            }
            case "mine" -> client.mine(Json.str(msg, "target"));
            case "attack" -> client.attack(Json.str(msg, "target"));
            case "possess" -> client.possess(Json.str(msg, "target"));
            case "mark" -> {
                String target = Json.str(msg, "target");
                if (target == null) throw new IllegalArgumentException("mark needs target");
                client.ping(target, Json.str(msg, "message"));
                yield Map.of();
            }
            case "beh" -> {
                String sub = Json.str(msg, "cmd");
                if (sub == null || sub.equals("reload")) yield client.behReload();
                if (sub.equals("list")) yield client.behList();
                if (sub.equals("status")) yield client.behStatus();
                throw new IllegalArgumentException("beh: reload | list | status");
            }
            case "follow" -> {
                client.follow(Json.str(msg, "name") == null ? "" : Json.str(msg, "name"));
                yield Map.of();
            }
            case "unfollow" -> {
                client.unfollow();
                yield Map.of();
            }
            case "build" -> {
                client.build(
                    Json.lng(msg, "x").intValue(),
                    Json.lng(msg, "y").intValue(),
                    Json.str(msg, "block"),
                    Json.lng(msg, "rotation") == null ? 0 : Json.lng(msg, "rotation").intValue()
                );
                yield Map.of();
            }
            case "deconstruct" -> {
                client.deconstruct(Json.lng(msg, "x").intValue(), Json.lng(msg, "y").intValue());
                yield Map.of();
            }
            case "menu" -> {
                client.menu(Json.lng(msg, "menuId").intValue(), Json.lng(msg, "option").intValue());
                yield Map.of();
            }
            case "textInput" -> {
                client.textInput(Json.lng(msg, "id").intValue(), Json.str(msg, "text"));
                yield Map.of();
            }
            case "events" -> {
                List<String> evs = new ArrayList<>(eventBuffer);
                yield Map.of("count", evs.size(), "events", evs);
            }
            case "ping" -> Map.of("pong", true);
            // 诊断: 查询客户端资产/音乐注册状态 (验证 DataAudioLoader 是否把下载的资产注册为可播放音乐)
            case "queryMusic" -> {
                String name = Json.str(msg, "name");
                Map<String, Object> r = new java.util.LinkedHashMap<>();
                r.put("assetCacheCount", mindustry.Vars.assetCacheDirectory == null ? -1 :
                    mindustry.Vars.assetCacheDirectory.list().length);
                var all = arc.Core.assets.getAll(arc.audio.Music.class, new arc.struct.Seq<arc.audio.Music>());
                java.util.List<String> names = new java.util.ArrayList<>();
                for (var m : all) names.add(String.valueOf(m));
                r.put("musicCount", names.size());
                r.put("musics", names);
                if (name != null && !name.isEmpty()) {
                    r.put("findMusic", mindustry.Vars.control.sound.findMusic(name) != null);
                    r.put("asset", arc.Core.assets.getOrNull(name, arc.audio.Music.class) != null);
                }
                yield r;
            }
            default -> throw new IllegalArgumentException("unknown op: " + op);
        };
    }

    private static final class Client {
        final Socket socket;
        private final PrintWriter out;
        private final Object lock = new Object();

        Client(Socket s) throws IOException {
            this.socket = s;
            this.out = new PrintWriter(new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8), true);
        }

        void send(Map<String, Object> m) { sendRaw(Json.stringify(m)); }

        void sendRaw(String line) {
            synchronized (lock) { out.println(line); }
        }

        void close() throws IOException { socket.close(); }
    }
}
