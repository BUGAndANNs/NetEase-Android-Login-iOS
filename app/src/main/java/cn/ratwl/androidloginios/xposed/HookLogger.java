package cn.ratwl.androidloginios.xposed;

import de.robv.android.xposed.XposedBridge;

/**
 * 模块侧的日志与「可见性」处理。
 * <p>
 * 面具 / LSPosed 里看 XposedBridge 日志；虚拟空间（元萝卜等）里日志很难拿到，
 * 所以注入结果同时用 Toast 喊一次——这是判断模块是否真的进了进程的唯一可靠信号。
 */
public final class HookLogger {

    public static final String TAG = "WYLoginIOS";

    public static final String KEY_SYSTEM = "system";
    public static final String KEY_JSON = "json";
    public static final String KEY_CHANNEL = "channel";
    public static final String KEY_PLATFORM = "platform";

    private static boolean systemOk;
    private static boolean jsonOk;
    private static boolean channelOk;
    private static boolean platformOk;
    private static String systemError = "";
    private static String jsonError = "";
    private static String channelError = "";
    private static String platformError = "";

    private HookLogger() {
    }

    /**
     * 两条出口同时打：{@code XposedBridge.log}（面具 / LSPosed 日志页）和 {@code android.util.Log}
     * （{@code adb logcat -s WYLoginIOS}）。
     * <p>
     * 容器里 XposedBridge 的那条经常被吞掉、日志页也翻不到，但 logcat 是系统级的，只要能连 adb 就一定看得见。
     * 这是文件写不出去时的最后一条取证通道。
     */
    public static void log(String message) {
        try {
            XposedBridge.log(TAG + ": " + message);
        } catch (Throwable ignored) {
        }
        try {
            android.util.Log.i(TAG, message);
        } catch (Throwable ignored) {
        }
    }

    public static void log(Throwable t) {
        try {
            XposedBridge.log(t);
        } catch (Throwable ignored) {
        }
    }

    public static synchronized void mark(String key, boolean ok, String error) {
        if (KEY_SYSTEM.equals(key)) {
            systemOk = ok;
            systemError = error == null ? "" : error;
        } else if (KEY_JSON.equals(key)) {
            jsonOk = ok;
            jsonError = error == null ? "" : error;
        } else if (KEY_CHANNEL.equals(key)) {
            channelOk = ok;
            channelError = error == null ? "" : error;
        } else if (KEY_PLATFORM.equals(key)) {
            platformOk = ok;
            platformError = error == null ? "" : error;
        }
    }

    public static synchronized String summary() {
        return "机型=" + state(systemOk, systemError)
                + " 三端=" + state(jsonOk, jsonError)
                + " 渠道=" + state(channelOk, channelError)
                + " 平台=" + state(platformOk, platformError);
    }

    /** 四类 Hook 是否都正常（被有意跳过也算正常）。 */
    public static synchronized boolean allOk() {
        return systemOk && jsonOk && channelOk && platformOk;
    }

    private static String state(boolean ok, String error) {
        if (ok) {
            // skip:开头表示「这个 Hook 被有意跳过」，比如光遇不再强制 getPlatform
            return error.startsWith("skip:") ? error.substring(5) : "OK";
        }
        return error.isEmpty() ? "FAIL" : "FAIL(" + shortError(error) + ")";
    }

    private static String shortError(String error) {
        String text = error.replace('\n', ' ');
        return text.length() > 42 ? text.substring(0, 42) : text;
    }
}
