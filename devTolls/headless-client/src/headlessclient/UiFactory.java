package headlessclient;

import arc.struct.Seq;
import mindustry.ui.fragments.ChatFragment;
import sun.misc.Unsafe;

import java.lang.reflect.Field;

/** 绕过 scene 依赖创建 UI 组件实例（headless 无 Core.scene，构造函数会 NPE） */
public final class UiFactory {
    private UiFactory() {}

    private static final Unsafe UNSAFE;

    /** 供外部使用的 Unsafe 访问器（headless 绕过构造器） */
    public static Unsafe unsafe() {
        return UNSAFE;
    }

    static {
        try {
            Field f = Unsafe.class.getDeclaredField("theUnsafe");
            f.setAccessible(true);
            UNSAFE = (Unsafe) f.get(null);
        } catch (Exception e) {
            throw new RuntimeException("Unsafe unavailable", e);
        }
    }

    /** UI stub：headless 无字体资源，覆写所有会构造控件的方法为空实现（NetClient 错误路径 showErrorMessage、公告 announce 等） */
    public static class UiStub extends mindustry.core.UI {
        @Override public void showErrorMessage(String text) {
            System.out.println("[ui-stub] showErrorMessage ignored: " + text);
        }
        @Override public void showException(Throwable t) {
            System.out.println("[ui-stub] showException ignored: " + t);
        }
        @Override public void showException(String text, Throwable t) {
            System.out.println("[ui-stub] showException ignored: " + text);
        }
        @Override public void announce(String message) {
            System.out.println("[ui-stub] announce ignored: " + message);
        }
        @Override public void announce(String message, float duration) {
            System.out.println("[ui-stub] announce ignored: " + message);
        }
    }

    /** 创建 UI 实例（不跑构造；headless 下 new UI() 会因 Core.scene 缺失失败） */
    public static mindustry.core.UI ui() {
        try {
            return (mindustry.core.UI) UNSAFE.allocateInstance(UiStub.class);
        } catch (Exception e) {
            throw new RuntimeException("ui create failed", e);
        }
    }

    /** 创建 ChatFragment 实例（不跑构造，补 messages/history 字段） */
    public static ChatFragment chatFragment() {
        try {
            ChatFragment cf = (ChatFragment) UNSAFE.allocateInstance(ChatFragment.class);
            setField(cf, "messages", new Seq<>());
            setField(cf, "history", new Seq<>());
            return cf;
        } catch (Exception e) {
            throw new RuntimeException("chatfragment create failed", e);
        }
    }

    /** 创建 LoadingFragment 实例（不跑构造） */
    public static mindustry.ui.fragments.LoadingFragment loadingFragment() {
        try {
            return (mindustry.ui.fragments.LoadingFragment) UNSAFE.allocateInstance(mindustry.ui.fragments.LoadingFragment.class);
        } catch (Exception e) {
            throw new RuntimeException("loadingfragment create failed", e);
        }
    }

    /** JoinDialog stub：WorldStream 处理流程调用 ui.join.hide()（headless 无 scene，覆写为空） */
    public static class JoinStub extends mindustry.ui.dialogs.JoinDialog {
        @Override public void hide() {}
        @Override public void hide(arc.scene.Action action) {}
    }

    /** 创建 JoinDialog stub 实例（Unsafe 绕过构造器，hide/show 已覆写为空） */
    public static mindustry.ui.dialogs.JoinDialog joinDialog() {
        try {
            return (mindustry.ui.dialogs.JoinDialog) UNSAFE.allocateInstance(JoinStub.class);
        } catch (Exception e) {
            throw new RuntimeException("joindialog create failed", e);
        }
    }

    private static void setField(Object obj, String name, Object value) throws Exception {
        Field f = obj.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(obj, value);
    }
}
