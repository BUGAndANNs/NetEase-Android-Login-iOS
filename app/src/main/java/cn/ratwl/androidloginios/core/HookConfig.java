package cn.ratwl.androidloginios.core;

import de.robv.android.xposed.XSharedPreferences;

/**
 * 宿主（游戏）进程读取模块开关。
 *
 * <p>只有被注入的进程才会加载本类；读取失败时一律回落到「功能全开」，
 * 保持与旧版模块一致的默认行为。</p>
 */
public final class HookConfig {

    private static final Object LOCK = new Object();
    private static XSharedPreferences sPrefs;
    private static boolean sLoaded;

    private HookConfig() {
    }

    private static XSharedPreferences reader() {
        synchronized (LOCK) {
            if (!sLoaded) {
                sLoaded = true;
                try {
                    sPrefs = new XSharedPreferences(ModuleConfig.MODULE_PACKAGE,
                            ModuleConfig.PREFS_FILE);
                } catch (Throwable t) {
                    Xlog.e("无法定位模块配置，使用默认值", t);
                    sPrefs = null;
                }
            }
            if (sPrefs != null) {
                try {
                    sPrefs.reload();
                } catch (Throwable ignored) {
                    // 配置暂不可读时沿用上次内存值
                }
            }
            return sPrefs;
        }
    }

    private static boolean flag(String key, boolean fallback) {
        XSharedPreferences p = reader();
        if (p == null) {
            return fallback;
        }
        try {
            return p.getBoolean(key, fallback);
        } catch (Throwable t) {
            return fallback;
        }
    }

    /** 是否伪装 MuMu 渠道。 */
    public static boolean enableChannel() {
        return flag(ModuleConfig.KEY_CHANNEL, true);
    }

    /** 是否伪装 ro.build.id。 */
    public static boolean enableSysProp() {
        return flag(ModuleConfig.KEY_SYSPROP, true);
    }

}
