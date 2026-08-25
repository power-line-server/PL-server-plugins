package termbridge;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

/** TCP 控制服务器：JSON 行协议（每行一个 JSON 消息） */
public final class ControlServer implements SessionManager.EventSink {
    private final SessionManager manager;
    private final ServerSocket server;
    private final ExecutorService pool = Executors.newCachedThreadPool();
    private final ConcurrentLinkedQueue<Client> clients = new ConcurrentLinkedQueue<>();

    public ControlServer(int port, SessionManager manager) throws IOException {
        this.manager = manager;
        this.server = new ServerSocket(port, 50, InetAddress.getLoopbackAddress());
        manager.addSink(this);
        Thread acceptor = new Thread(this::acceptLoop, "term-bridge-acceptor");
        acceptor.setDaemon(true);
        acceptor.start();
    }

    private void acceptLoop() {
        while (!server.isClosed()) {
            try {
                Socket sock = server.accept();
                Client c = new Client(sock);
                clients.add(c);
                manager.addSink(c);
                pool.execute(() -> handleClient(c));
            } catch (IOException e) {
                if (!server.isClosed()) System.err.println("accept error: " + e.getMessage());
                break;
            }
        }
    }

    public void close() throws IOException {
        server.close();
        for (Client c : clients) c.close();
    }

    private void handleClient(Client c) {
        try (BufferedReader in = new BufferedReader(new InputStreamReader(c.socket.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = in.readLine()) != null) {
                if (line.isBlank()) continue;
                try {
                    handleMessage(c, Json.asObject(Json.parse(line)));
                } catch (Exception e) {
                    c.send(Map.of("ok", false, "error", "bad message: " + e.getMessage()));
                }
            }
        } catch (IOException ignored) {
        } finally {
            clients.remove(c);
            manager.removeSink(c);
            try { c.close(); } catch (IOException ignored) {}
        }
    }

    private void handleMessage(Client c, Map<String, Object> msg) {
        String op = Json.str(msg, "op");
        String id = Json.str(msg, "id");
        if (op == null) {
            c.send(Map.of("id", id, "ok", false, "error", "missing op"));
            return;
        }
        switch (op) {
            case "session_start" -> {
                String name = Json.str(msg, "name");
                Object cmdObj = msg.get("cmd");
                List<String> cmd = new ArrayList<>();
                if (cmdObj instanceof List<?> l) for (Object o : l) cmd.add(String.valueOf(o));
                else cmd.add(String.valueOf(cmdObj));
                String encoding = Json.str(msg, "encoding");
                String cwd = Json.str(msg, "cwd");
                try {
                    if (name == null || cmd.isEmpty()) {
                        c.send(Map.of("id", id, "ok", false, "error", "name and cmd required"));
                        return;
                    }
                    TermSession s = manager.start(name, cmd, cwd, encoding);
                    c.send(Map.of("id", id, "ok", true, "result", Map.of("session", name, "pid", s.pid())));
                } catch (Exception e) {
                    c.send(Map.of("id", id, "ok", false, "error", e.getMessage()));
                }
            }
            case "write" -> {
                String name = Json.str(msg, "session");
                String data = Json.str(msg, "data");
                TermSession s = manager.get(name);
                if (s == null) { c.send(Map.of("id", id, "ok", false, "error", "no such session: " + name)); return; }
                s.write(data == null ? "" : data);
                c.send(Map.of("id", id, "ok", true));
            }
            case "logs" -> {
                String name = Json.str(msg, "session");
                TermSession s = manager.get(name);
                if (s == null) { c.send(Map.of("id", id, "ok", false, "error", "no such session: " + name)); return; }
                long limit = msg.get("limit") instanceof Number n ? n.longValue() : 100;
                c.send(Map.of("id", id, "ok", true, "result", Map.of("session", name, "seq", s.currentSeq(), "logs", s.recentLogs((int) limit))));
            }
            case "stop" -> {
                String name = Json.str(msg, "session");
                manager.stop(name);
                c.send(Map.of("id", id, "ok", true));
            }
            case "kill" -> {
                String name = Json.str(msg, "session");
                TermSession s = manager.get(name);
                if (s == null) { c.send(Map.of("id", id, "ok", false, "error", "no such session: " + name)); return; }
                s.kill();
                c.send(Map.of("id", id, "ok", true));
            }
            case "sessions" -> c.send(Map.of("id", id, "ok", true, "result", manager.list()));
            case "ping" -> c.send(Map.of("id", id, "ok", true, "result", "pong"));
            default -> c.send(Map.of("id", id, "ok", false, "error", "unknown op: " + op));
        }
    }

    @Override
    public void sendEvent(Map<String, Object> event) {
        // 广播给所有客户端
        String text = Json.stringify(event);
        for (Client c : clients) {
            c.sendRaw(text);
        }
    }

    private static final class Client implements SessionManager.EventSink {
        final Socket socket;
        private final PrintWriter out;
        private final Object lock = new Object();

        Client(Socket socket) throws IOException {
            this.socket = socket;
            this.out = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true);
        }

        void send(Map<String, Object> msg) {
            sendRaw(Json.stringify(msg));
        }

        void sendRaw(String line) {
            synchronized (lock) {
                out.println(line);
            }
        }

        void close() throws IOException { socket.close(); }

        @Override
        public void sendEvent(Map<String, Object> event) {
            sendRaw(Json.stringify(event));
        }
    }
}
