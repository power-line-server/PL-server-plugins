package headlessclient;

/**
 * 改端(MindustryX)专用 API 的反射访问器。
 *
 * 原版 server-release.jar 没有 mindustryX 包, 源码直接引用会导致 vanilla 变体无法编译;
 * 这里全部走反射, 类不存在时静默降级(不设置 / 返回 -1), 使同一份源码可编译出双变体。
 */
public final class MindustryXHooks {
    private static Class<?> logicExt;

    private MindustryXHooks() {
    }

    private static Class<?> logicExtClass() {
        if (logicExt == null) {
            try {
                logicExt = Class.forName("mindustryX.features.LogicExt");
            } catch (Throwable t) {
                logicExt = NoSuch.class; // 哨兵: 避免每次都尝试
            }
        }
        return logicExt == NoSuch.class ? null : logicExt;
    }

    /** 设置改端协议映射 mockProtocol(vanilla 无改端包时无操作) */
    public static void setMockProtocol(int value) {
        Class<?> c = logicExtClass();
        if (c == null) return;
        try {
            c.getField("mockProtocol").setInt(null, value);
        } catch (Throwable ignored) {
        }
    }

    /** 读取改端协议映射 mockProtocol; vanilla 返回 -1(不可用) */
    public static int getMockProtocol() {
        Class<?> c = logicExtClass();
        if (c == null) return -1;
        try {
            return c.getField("mockProtocol").getInt(null);
        } catch (Throwable ignored) {
            return -1;
        }
    }

    static final class NoSuch {
    }
}
