package headlessclient;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * 行为脚本管理器（参考服务器 sa reload 的热重载体验）：
 * behaviors/*.json 定义规则（chat 触发 / timer 定时，可带 scan 条件 + 动作原语列表），
 * `beh reload` 重新扫描加载，热生效不重启进程。
 *
 * 规则格式：
 * {
 *   "name": "demo",
 *   "rules": [
 *     {"id": "greet", "on": "chat", "match": "你好", "do": [{"chat": "你好！"}]},
 *     {"id": "patrol", "on": "timer", "every": 5000,
 *      "if": {"scan": "enemyUnit", "dist_lt": 800},
 *      "do": [{"log": "发现敌军"}, {"attack": "auto"}]}
 *   ]
 * }
 * 动作原语：chat / wait(ms) / log / moveTo(目标) / attack(目标) / mine(目标) / possess(目标) / mark(目标+text)
 */
public class BehaviorManager {
    /** 宿主回调：log 输出 + 客户端能力访问 */
    public interface Sink {
        void log(String line);
        ClientApi client();
    }

    private final Path dir;
    private final Sink sink;
    private final List<Rule> rules = new ArrayList<>();
    private final List<Runner> runners = new ArrayList<>();
    private volatile long loadedAt = 0;

    static class Rule {
        String id, on, match;
        long every; // timer 间隔 ms
        Map<String, Object> cond;
        List<Object> actions;
        long lastRun;
    }

    static class Runner {
        final Rule rule;
        String triggerMsg; // 触发消息(chat 规则), 供 {msg} 占位符
        int step = 0;
        long waitUntil = 0;

        Runner(Rule rule) {
            this.rule = rule;
        }
    }

    public BehaviorManager(Path dir, Sink sink) {
        this.dir = dir;
        this.sink = sink;
    }

    /** 重新加载 behaviors/*.json（sa reload 语义：失败文件跳过不影响其余） */
    public synchronized Map<String, Object> reload() {
        int files = 0, rulesCount = 0;
        rules.clear();
        runners.clear();
        try {
            Files.createDirectories(dir);
            try (var stream = Files.list(dir)) {
                for (var p : stream.filter(f -> f.toString().endsWith(".json")).toList()) {
                    try {
                        String text = Files.readString(p, StandardCharsets.UTF_8);
                        Object v = Json.parse(text);
                        if (!(v instanceof Map<?, ?> root)) {
                            sink.log("[beh] skip " + p.getFileName() + ": not an object");
                            continue;
                        }
                        List<Object> rs = Json.asArray(root.get("rules"));
                        if (rs == null) {
                            sink.log("[beh] skip " + p.getFileName() + ": no rules");
                            continue;
                        }
                        files++;
                        for (Object ro : rs) {
                            Map<String, Object> rm = Json.asObject(ro);
                            Rule rule = new Rule();
                            rule.id = Json.str(rm, "id");
                            if (rule.id == null) rule.id = p.getFileName().toString();
                            rule.on = Json.str(rm, "on");
                            if (rule.on == null) rule.on = "chat";
                            rule.match = Json.str(rm, "match");
                            Long every = Json.lng(rm, "every");
                            rule.every = every == null ? 0 : every;
                            rule.cond = asMap(rm.get("if"));
                            rule.actions = Json.asArray(rm.get("do"));
                            if (rule.actions == null) {
                                sink.log("[beh] skip rule " + rule.id + ": no do");
                                continue;
                            }
                            rules.add(rule);
                            rulesCount++;
                        }
                    } catch (Exception e) {
                        sink.log("[beh] load " + p.getFileName() + " failed: " + e);
                    }
                }
            }
        } catch (IOException e) {
            sink.log("[beh] reload error: " + e);
        }
        loadedAt = System.currentTimeMillis();
        sink.log("[beh] reloaded: files=" + files + " rules=" + rulesCount);
        return Map.of("files", files, "rules", rulesCount, "loadedAt", loadedAt);
    }

    public Map<String, Object> list() {
        var out = new ArrayList<Map<String, Object>>();
        for (var r : rules) {
            var m = new LinkedHashMap<String, Object>();
            m.put("id", r.id);
            m.put("on", r.on);
            m.put("match", r.match == null ? "" : r.match);
            m.put("every", r.every);
            m.put("actions", r.actions.size());
            out.add(m);
        }
        return Map.of("count", rules.size(), "rules", out);
    }

    public Map<String, Object> status() {
        return Map.of("loadedAt", loadedAt, "rules", rules.size(), "running", runners.size());
    }

    /** 每 tick 调用（游戏线程）：timer 规则触发 + 执行器推进 */
    public void tick() {
        long now = System.currentTimeMillis();
        for (var r : rules) {
            if (!r.on.equals("timer") || r.every <= 0) continue;
            if (now - r.lastRun < r.every) continue;
            r.lastRun = now;
            if (checkCond(r.cond)) runners.add(new Runner(r));
        }
        var it = runners.iterator();
        while (it.hasNext()) {
            Runner run = it.next();
            if (run.waitUntil > now) continue;
            if (run.step >= run.rule.actions.size()) {
                it.remove();
                continue;
            }
            Object act = run.rule.actions.get(run.step++);
            run.waitUntil = execAction(act, run);
        }
    }

    /** 聊天触发（DebugNet 收到 chat 时调用） */
    public void onChat(String message) {
        for (var r : rules) {
            if (!r.on.equals("chat") || r.match == null) continue;
            if (message.contains(r.match) && checkCond(r.cond)) {
                var run = new Runner(r);
                run.triggerMsg = stripColors(message);
                runners.add(run);
            }
        }
    }

    /** 去掉 Mindustry 颜色码标签 [red]/[#FFFFFF] 等 */
    static String stripColors(String text) {
        if (text == null) return "";
        String t = text.replaceAll("\\[[a-zA-Z#0-9]*\\]", "");
        // 去掉发送者前缀 [name]: 部分（保留冒号后内容）
        int idx = t.indexOf(": ");
        return idx >= 0 ? t.substring(idx + 2) : t;
    }

    /** 条件: {"scan": "enemyUnit", "dist_lt": 800} 等（scan 结果 found 且 dist 满足） */
    private boolean checkCond(Map<String, Object> cond) {
        if (cond == null) return true;
        try {
            String scanWhat = Json.str(cond, "scan");
            if (scanWhat != null) {
                Map<String, Object> res = sink.client().scan(scanWhat);
                if (!Boolean.TRUE.equals(res.get("found"))) return false;
                Double distLt = Json.dbl(cond, "dist_lt");
                if (distLt != null) {
                    Double dist = Json.dbl(res, "dist");
                    if (dist == null || dist >= distLt) return false;
                }
            }
            return true;
        } catch (Exception e) {
            sink.log("[beh] cond error: " + e);
            return false;
        }
    }

    /** 执行单个动作；返回 0（下 tick 继续）或未来时间戳（wait 阻塞） */
    private long execAction(Object act, Runner run) {
        if (!(act instanceof Map<?, ?>)) return 0;
        Map<String, Object> a = asMap(act);
        try {
            String chat = Json.str(a, "chat");
            if (chat != null) {
                // {msg} 触发消息 / {time} 当前时间占位符(带时间可避开服务器防刷屏)
                String text = chat
                    .replace("{msg}", run.triggerMsg == null ? "" : run.triggerMsg)
                    .replace("{time}", new java.text.SimpleDateFormat("HH:mm:ss").format(new java.util.Date()));
                sink.client().chat(text);
                return 0;
            }
            Long wait = Json.lng(a, "wait");
            if (wait != null) return System.currentTimeMillis() + wait;
            String log = Json.str(a, "log");
            if (log != null) {
                sink.log("[beh] " + log);
                return 0;
            }
            String moveTo = Json.str(a, "moveTo");
            if (moveTo != null) {
                sink.client().moveTo(moveTo, false);
                return 0;
            }
            String attack = Json.str(a, "attack");
            if (attack != null) {
                sink.client().attack(attack);
                return 0;
            }
            String mine = Json.str(a, "mine");
            if (mine != null) {
                sink.client().mine(mine);
                return 0;
            }
            String possess = Json.str(a, "possess");
            if (possess != null) {
                sink.client().possess(possess);
                return 0;
            }
            String mark = Json.str(a, "mark");
            if (mark != null) {
                String target = mark;
                if (mark.equals("auto")) {
                    var res = sink.client().scan("enemyUnit");
                    if (!Boolean.TRUE.equals(res.get("found"))) {
                        sink.log("[beh] mark auto: no enemy");
                        return 0;
                    }
                    Double mx = Json.dbl(res, "x"), my = Json.dbl(res, "y");
                    target = mx + "," + my;
                }
                sink.client().ping(target, Json.str(a, "text"));
                return 0;
            }
            String unknown = String.valueOf(a.keySet());
            sink.log("[beh] unknown action: " + unknown);
        } catch (Exception e) {
            sink.log("[beh] action error: " + e);
        }
        return 0;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object v) {
        return v instanceof Map<?, ?> m ? (Map<String, Object>) m : null;
    }
}
