package termbridge;

import java.io.*;
import java.nio.charset.Charset;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;

/** 一个进程终端会话：spawn 子进程、全量捕获 stdout/stderr、可写 stdin */
public final class TermSession {
    public interface OutputListener {
        void onOutput(String session, long seq, String data);
        void onExit(String session, int code);
    }

    private static final int MAX_BUFFER = 2000; // 环形缓冲最大行数

    public final String name;
    public final List<String> command;
    private final Process process;
    private final Charset charset;
    private final OutputListener listener;
    private final Deque<String> buffer = new ArrayDeque<>();
    private final AtomicLong seq = new AtomicLong(0);
    private final ConcurrentLinkedQueue<String> pendingWrites = new ConcurrentLinkedQueue<>();
    private volatile boolean exited = false;

    public TermSession(String name, List<String> command, String cwd, String encoding, OutputListener listener) throws IOException {
        this.name = name;
        this.command = command;
        this.listener = listener;
        this.charset = encoding == null || encoding.isBlank()
            ? Charset.defaultCharset()
            : Charset.forName(encoding);

        ProcessBuilder pb = new ProcessBuilder(command);
        if (cwd != null && !cwd.isBlank()) {
            pb.directory(new File(cwd));
        }
        pb.redirectErrorStream(true); // stdout+stderr 合并, 保持输出顺序
        this.process = pb.start();

        // 读取线程
        Thread reader = new Thread(this::readLoop, "term-" + name + "-reader");
        reader.setDaemon(true);
        reader.start();

        // 写线程（串行化 stdin 写入）
        Thread writer = new Thread(this::writeLoop, "term-" + name + "-writer");
        writer.setDaemon(true);
        writer.start();

        // 退出监控
        Thread watcher = new Thread(() -> {
            try {
                int code = process.waitFor();
                exited = true;
                listener.onExit(name, code);
            } catch (InterruptedException ignored) {}
        }, "term-" + name + "-watcher");
        watcher.setDaemon(true);
        watcher.start();
    }

    public boolean isAlive() { return process.isAlive(); }

    public long pid() { return process.pid(); }

    public void write(String data) {
        pendingWrites.add(data);
    }

    public void stop() {
        process.destroy();
    }

    public void kill() {
        process.destroyForcibly();
    }

    public List<String> recentLogs(int limit) {
        synchronized (buffer) {
            List<String> all = new ArrayList<>(buffer);
            int from = Math.max(0, all.size() - Math.max(1, limit));
            return all.subList(from, all.size());
        }
    }

    public long currentSeq() { return seq.get(); }

    private void readLoop() {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), charset))) {
            StringBuilder chunk = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                emit(line);
                chunk.setLength(0);
            }
        } catch (IOException ignored) {
        } finally {
            // 残留输出（进程退出后缓冲中的）
        }
    }

    private void emit(String line) {
        long s = seq.incrementAndGet();
        synchronized (buffer) {
            buffer.addLast(line);
            while (buffer.size() > MAX_BUFFER) buffer.removeFirst();
        }
        listener.onOutput(name, s, line);
    }

    private void writeLoop() {
        try (OutputStream out = process.getOutputStream()) {
            while (process.isAlive()) {
                String data = pendingWrites.poll();
                if (data == null) {
                    try { Thread.sleep(10); } catch (InterruptedException ignored) {}
                    continue;
                }
                out.write(data.getBytes(charset));
                out.flush();
            }
        } catch (IOException ignored) {
        }
    }
}
