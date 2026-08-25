package termbridge;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

/** 会话注册表 + 输出事件分发（多个控制客户端共享） */
public final class SessionManager implements TermSession.OutputListener {
    public interface EventSink {
        void sendEvent(Map<String, Object> event);
    }

    private final Map<String, TermSession> sessions = new LinkedHashMap<>();
    private final List<EventSink> sinks = new CopyOnWriteArrayList<>();

    public synchronized TermSession start(String name, List<String> command, String cwd, String encoding) throws IOException {
        if (sessions.containsKey(name)) {
            throw new IOException("session already exists: " + name);
        }
        TermSession s = new TermSession(name, command, cwd, encoding, this);
        sessions.put(name, s);
        return s;
    }

    public synchronized TermSession get(String name) { return sessions.get(name); }

    public synchronized void stop(String name) {
        TermSession s = sessions.remove(name);
        if (s != null) s.stop();
    }

    public synchronized List<Map<String, Object>> list() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (TermSession s : sessions.values()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", s.name);
            m.put("alive", s.isAlive());
            m.put("seq", s.currentSeq());
            out.add(m);
        }
        return out;
    }

    public void addSink(EventSink sink) { sinks.add(sink); }
    public void removeSink(EventSink sink) { sinks.remove(sink); }

    @Override
    public void onOutput(String session, long seq, String data) {
        Map<String, Object> ev = new LinkedHashMap<>();
        ev.put("event", "output");
        ev.put("session", session);
        ev.put("seq", seq);
        ev.put("data", data);
        broadcast(ev);
    }

    @Override
    public void onExit(String session, int code) {
        Map<String, Object> ev = new LinkedHashMap<>();
        ev.put("event", "exit");
        ev.put("session", session);
        ev.put("code", code);
        broadcast(ev);
    }

    private void broadcast(Map<String, Object> ev) {
        String text = Json.stringify(ev);
        for (EventSink sink : sinks) {
            try { sink.sendEvent(ev); } catch (Exception ignored) {}
        }
    }
}
