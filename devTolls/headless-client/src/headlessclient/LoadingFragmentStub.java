package headlessclient;

import arc.func.Floatp;
import arc.graphics.Color;
import arc.scene.Group;
import mindustry.ui.fragments.LoadingFragment;

/** LoadingFragment 的 headless stub（NetClient 连接流程调用 show/hide/setButton/setText/进度条） */
public final class LoadingFragmentStub extends LoadingFragment {
    private boolean shown = false;
    private boolean progressShown = false;

    @Override public void show() { shown = true; }
    @Override public void show(String text) { shown = true; }
    @Override public void hide() { shown = false; progressShown = false; }
    @Override public void setButton(Runnable run) {}
    @Override public void setText(String text) {}
    @Override public void setText(String text, Color color) {}
    @Override public void setProgress(Floatp progress) {}
    @Override public void setProgress(float progress) {}
    @Override public void snapProgress() {}
    @Override public void showProgressBar() { progressShown = true; }
    @Override public boolean showingProgress() { return progressShown; }
    @Override public boolean shown() { return shown; }
    @Override public void build(Group parent) {}
    @Override public void toFront() {}
}
