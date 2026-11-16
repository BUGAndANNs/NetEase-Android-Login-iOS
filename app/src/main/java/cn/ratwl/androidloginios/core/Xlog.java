package cn.ratwl.androidloginios.core;

import de.robv.android.xposed.XposedBridge;

/** 统一的 Xposed 日志出口。 */
public final class Xlog {
    public static final String TAG = "NetEaseMPayMod";

    private Xlog() {
    }

    public static void i(String message) {
        XposedBridge.log(TAG + ": " + message);
    }

    public static void e(String message, Throwable t) {
        if (t == null) {
            XposedBridge.log(TAG + ": " + message);
            return;
        }
        XposedBridge.log(TAG + ": " + message + " - " + t);
        StackTraceElement[] stack = t.getStackTrace();
        int limit = Math.min(stack.length, 12);
        for (int i = 0; i < limit; i++) {
            XposedBridge.log(TAG + ":     at " + stack[i]);
        }
    }
}
