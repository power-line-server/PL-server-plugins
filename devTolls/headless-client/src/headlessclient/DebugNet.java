package headlessclient;

import arc.net.Client;
import arc.util.io.ByteBufferOutput;
import arc.util.io.Writes;
import arc.util.serialization.Base64Coder;
import mindustry.io.TypeIO;
import mindustry.net.Net;
import mindustry.net.Net.NetProvider;
import mindustry.net.Packets;
import mindustry.net.Packets.ConnectPacket;
import net.jpountz.lz4.LZ4Compressor;
import net.jpountz.lz4.LZ4Factory;

import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.util.Map;

/**
 * 调试用 Net 包装：打印 connect/send 调用（headless 客户端联调）。
 *
 * ConnectPacket 兼容帧：
 * 改端/原版 159.7 的 ConnectPacket.write 在 uuid 后多写 8 字节 CRC32（buffer.l(crc.getValue())），
 * 而 read 端不消费这 8 字节 —— 直接发送 write 产物必然字段错位（mobile/color/mods.size 从 crc
 * 字节解析，mods.size 读到随机值 → NegativeArraySizeException → 服务器反序列化失败断连）。
 * 本类拦截 ConnectPacket 发送，手工构造“服务器 read 期望布局”（无 crc）的帧，
 * 经 arc-net 底层 sendTCPBuffer 直发（复用 client 已建立的 TCP 注册链路）。
 */
public final class DebugNet extends Net {
    private final NetProvider provider;
    private Client clientCache;
    private ControlServer control;

    public DebugNet(NetProvider provider) {
        super(provider);
        this.provider = provider;
    }

    /** 绑定控制端口（用于推送 chat 等事件；由 HeadlessClient 构造时注入） */
    public void setControl(ControlServer control) {
        this.control = control;
    }

    @Override
    public void connect(String address, int port, Runnable success) {
        System.out.println("[net-debug] connect(" + address + ":" + port + ")");
        super.connect(address, port, success);
    }

    @Override
    public void send(Object object, boolean reliable) {
        System.out.println("[net-debug] send(" + object.getClass().getSimpleName() + ", reliable=" + reliable + ")");
        try {
            if (object instanceof ConnectPacket cp) {
                sendConnectPacketCompat(cp);
                return;
            }
            super.send(object, reliable);
            System.out.println("[net-debug] send OK: " + object.getClass().getSimpleName());
            // 协议映射时序: ConnectPacket 必须用连接阶段的映射(v153, 与服务器初始一致)发送;
            // 发送完成后服务器会切到 Version.build(159), 客户端同步切换以匹配后续包。
            if (object instanceof mindustry.net.Packets.ConnectPacket) {
                MindustryXHooks.setMockProtocol(mindustry.core.Version.build);
                System.out.println("[net-debug] mockProtocol switched to " + mindustry.core.Version.build + " (after ConnectPacket sent)");
            }
        } catch (Throwable t) {
            System.err.println("[net-debug] send threw: " + t);
            t.printStackTrace();
        }
    }

    /** 发送无 crc 的 ConnectPacket 兼容帧（与服务器 read 布局一致） */
    private void sendConnectPacketCompat(ConnectPacket cp) throws Exception {
        // 1) 序列化数据: 与 ConnectPacket.write 一致(改端 clientVersion=0 时等价), 跳过 uuid 后的 8 字节 crc
        //    (双变体通用: 不引用改端独有的 clientVersion 静态字段)
        ByteBuffer data = ByteBuffer.allocate(1024);
        Writes w = new Writes(new ByteBufferOutput(data));
        w.i(mindustry.core.Version.build);
        TypeIO.writeString(w, "official");
        TypeIO.writeString(w, cp.name);
        TypeIO.writeString(w, cp.locale);
        TypeIO.writeString(w, cp.usid);
        w.b(Base64Coder.decode(cp.uuid));
        // (crc 省略: 服务器 read 不消费, 写了必然错位)
        w.b(cp.mobile ? 1 : 0);
        w.i(cp.color);
        w.b((byte) cp.mods.size);
        for (String s : cp.mods) TypeIO.writeString(w, s);
        int dataLen = data.position();
        byte[] dataArr = new byte[dataLen];
        System.arraycopy(data.array(), 0, dataArr, 0, dataLen);

        // 2) 构造帧 [short serializerLen][id][short dataLen][comp][data]
        //    serializerLen = PacketSerializer 输出长度(不含帧长度头): id(1)+len(2)+comp(1)+N
        byte id = Net.getPacketId(cp);
        byte comp = (byte) (dataLen < 36 ? 0 : 1);
        int compLen = 0;
        byte[] compArr = null;
        if (comp == 1) {
            LZ4Compressor compressor = LZ4Factory.fastestInstance().fastCompressor();
            compArr = new byte[compressor.maxCompressedLength(dataLen)];
            compLen = compressor.compress(dataArr, 0, dataLen, compArr, 0, compArr.length);
        }
        int n = comp == 1 ? compLen : dataLen;
        ByteBuffer frame = ByteBuffer.allocate(2 + 4 + n);
        frame.putShort((short) (4 + n));
        frame.put(id);
        frame.putShort((short) dataLen);
        frame.put(comp);
        if (comp == 1) {
            frame.put(compArr, 0, compLen);
        } else {
            frame.put(dataArr);
        }
        frame.flip();

        // 3) 经 arc-net 底层 client 直发(复用 TCP 注册链路, 帧自带长度头)
        Client client = getClient();
        client.sendTCPBuffer(frame);
        System.out.println("[net-debug] ConnectPacket compat frame sent: dataLen=" + dataLen
            + " comp=" + comp + " frame=" + frame.limit() + "B");

        MindustryXHooks.setMockProtocol(mindustry.core.Version.build);
        System.out.println("[net-debug] mockProtocol switched to " + mindustry.core.Version.build + " (after ConnectPacket sent)");
    }

    private Client getClient() throws Exception {
        if (clientCache != null) return clientCache;
        Field f = mindustry.net.ArcNetProvider.class.getDeclaredField("client");
        f.setAccessible(true);
        clientCache = (Client) f.get(provider);
        System.out.println("[net-debug] ArcNetProvider.client resolved: " + clientCache);
        return clientCache;
    }

    @Override
    public void disconnect() {
        System.out.println("[net-debug] disconnect()");
        super.disconnect();
    }

    @Override
    public void reset() {
        System.out.println("[net-debug] reset()");
        super.reset();
    }

    @Override
    public void handleException(Throwable t) {
        System.err.println("[net-debug] handleException: " + t);
        t.printStackTrace();
        super.handleException(t);
    }

    @Override
    public void handleClientReceived(mindustry.net.Packet packet) {
        System.out.println("[net-debug] received packet: " + packet.getClass().getSimpleName());
        // headless 下改端 UI 扩展不可用: 聊天包(SendMessageCallPacket2)走 UIExt 会抛异常, 弹窗(InfoPopup)需 Core.scene。
        // 拦截并直接转发/忽略, 不调用 handleClient()。
        if (packet instanceof mindustry.gen.SendMessageCallPacket2 sm) {
            // 改端 SendMessageCallPacket2.read 只存 DATA 原始字节(不解析字段), 需从 DATA 反序列化:
            // write 布局 = [message][unformatted][playersender(entity)]
            String msg = sm.message;
            if (msg == null) {
                try {
                    java.lang.reflect.Field df = mindustry.gen.SendMessageCallPacket2.class.getDeclaredField("DATA");
                    df.setAccessible(true);
                    byte[] data = (byte[]) df.get(sm);
                    if (data != null) {
                        arc.util.io.Reads r = new arc.util.io.Reads(new arc.util.io.ByteBufferInput(java.nio.ByteBuffer.wrap(data)));
                        msg = mindustry.io.TypeIO.readString(r);
                    }
                } catch (Throwable t) {
                    System.err.println("[net-debug] chat2 DATA parse failed: " + t);
                }
            }
            System.out.println("[net-debug] chat2: " + msg);
            if (control != null) control.pushEvent("chat", Map.of("message", String.valueOf(msg)));
            return;
        }
        // 公告横幅(AnnounceCallPacket): headless 无字体资源, UI.announce 构造 Label 会 NPE 导致 JVM 退出。
        // 拦截并转成 chat 事件, 不调用 handleClient()。
        if (packet instanceof mindustry.gen.AnnounceCallPacket ann) {
            String amsg = ann.message;
            System.out.println("[net-debug] announce (ignored UI, as chat): " + amsg);
            if (control != null && amsg != null) control.pushEvent("chat", Map.of("message", amsg));
            return;
        }
        if (packet instanceof mindustry.gen.InfoPopupCallPacket ip) {
            System.out.println("[net-debug] info-popup (ignored, no scene): " + ip.message);
            return;
        }
        try {
            super.handleClientReceived(packet);
            System.out.println("[net-debug] handled OK: " + packet.getClass().getSimpleName());
        } catch (Throwable t) {
            System.err.println("[net-debug] handleClientReceived threw: " + t);
            t.printStackTrace();
        }
    }

    @Override
    public void showError(Throwable t) {
        System.err.println("[net-debug] showError: " + t);
        t.printStackTrace();
        super.showError(t);
    }
}
