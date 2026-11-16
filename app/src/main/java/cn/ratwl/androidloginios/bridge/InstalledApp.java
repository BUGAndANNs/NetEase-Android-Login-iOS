package cn.ratwl.androidloginios.bridge;

import android.graphics.drawable.Drawable;

/** 已安装应用（用于目标游戏选择器）。 */
public final class InstalledApp {
    public final GameSpec spec;
    public final String versionName;
    public final Drawable icon;
    public final boolean installed;
    public final boolean systemApp;

    public InstalledApp(GameSpec spec, String versionName, Drawable icon,
                        boolean installed, boolean systemApp) {
        this.spec = spec;
        this.versionName = versionName == null ? "" : versionName;
        this.icon = icon;
        this.installed = installed;
        this.systemApp = systemApp;
    }

    public String packageName() {
        return spec.packageName;
    }

    public String label() {
        return spec.displayName;
    }
}
