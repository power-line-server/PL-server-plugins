package termbridge;

/** term-bridge 入口：进程终端桥（真实终端全量 IO 捕获 + stdin 写入），TCP+JSON 纯文本驱动
 *  用法: java -cp <dir> termbridge.Main [--listen <port>] [--encoding <charset>] */
public final class Main {
    public static void main(String[] args) throws Exception {
        int port = 9090;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--listen" -> port = Integer.parseInt(args[++i]);
                case "--help", "-h" -> {
                    System.out.println("用法: java -cp <dir> termbridge.Main [--listen <port>]");
                    System.out.println("默认监听 127.0.0.1:" + port + "，JSON 行协议");
                    return;
                }
            }
        }
        SessionManager manager = new SessionManager();
        ControlServer server = new ControlServer(port, manager);
        System.out.println("term-bridge listening on 127.0.0.1:" + port);
        // 主线程挂起
        Thread.currentThread().join();
    }
}
