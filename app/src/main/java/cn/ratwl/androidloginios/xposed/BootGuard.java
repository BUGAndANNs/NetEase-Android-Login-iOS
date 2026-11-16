package cn.ratwl.androidloginios.xposed;

import android.content.Context;

import java.io.File;

/**
 * 「上一次是不是死在直连挂载上」的记忆。
 * <p>
 * 直连（{@link PlatformHook#install}）是面具 / Dual Space 上唯一赶得上 SDK 初始化的做法，
 * 但它要按类名去拿加固包里的类；某些容器（实测元萝卜）在这个时刻被强行驱动 ClassLoader 会整进程崩，
 * 而这种崩是 native 层的，模块内的 try/catch 拦不住。
 * <p>
 * 所以每次直连前先落一个锁文件，挂完就删。如果下次启动时锁文件还在，说明上一次进程死在了
 * 直连过程中——这一轮就只走 {@link ClassWatcher} 轮询，宁可功能弱一点，也不让宿主继续闪退。
 * 这个自愈是「一次性的」：只要本轮没崩，下一轮直连又会重新打开。
 */
public final class BootGuard {

    private static final String LOCK = "wy_boot.lock";

    private BootGuard() {
    }

    /** 上一次启动是否死在直连挂载里。 */
    public static boolean suspect(Context context) {
        try {
            return lockFile(context).exists();
        } catch (Throwable t) {
            return false;
        }
    }

    public static void begin(Context context) {
        try {
            File file = lockFile(context);
            file.getParentFile().mkdirs();
            java.io.FileOutputStream out = new java.io.FileOutputStream(file);
            try {
                out.write(String.valueOf(System.currentTimeMillis()).getBytes("UTF-8"));
            } finally {
                out.close();
            }
        } catch (Throwable ignored) {
        }
    }

    public static void end(Context context) {
        try {
            File file = lockFile(context);
            if (file.exists()) {
                file.delete();
            }
        } catch (Throwable ignored) {
        }
    }

    private static File lockFile(Context context) {
        return new File(context.getFilesDir(), LOCK);
    }
}
