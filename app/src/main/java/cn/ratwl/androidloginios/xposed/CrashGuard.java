package cn.ratwl.androidloginios.xposed;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 宿主（光遇）进程内的崩溃记录器。
 * <p>
 * 免 ROOT 容器里拿 logcat 基本不可能，所以把未捕获异常的现场写到宿主自己能写到的目录：
 * 1) 宿主 files 目录（容器内可见）；
 * 2) 宿主外部 files 目录（多数文件管理器能翻到）；
 * 3) XposedBridge 日志。
 * <p>
 * 只做记录，永远把异常原样交回给上一个 handler，不改变宿主既有的崩溃行为。
 */
public final class CrashGuard {

    private static final String FILE_NAME = "wy_crash.log";

    private static volatile boolean installed;

    private CrashGuard() {
    }

    public static synchronized void install(final Context context) {
        if (installed || context == null) {
            return;
        }
        installed = true;
        try {
            final Thread.UncaughtExceptionHandler previous =
                    Thread.getDefaultUncaughtExceptionHandler();
            Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
                @Override
                public void uncaughtException(Thread thread, Throwable error) {
                    record(context, thread, error);
                    if (previous != null) {
                        previous.uncaughtException(thread, error);
                    }
                }
            });
            HookLogger.log("CrashGuard 已安装: " + context.getPackageName());
        } catch (Throwable t) {
            HookLogger.log("CrashGuard 安装失败: " + t);
        }
    }

    private static void record(Context context, Thread thread, Throwable error) {
        String stamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date());
        StringWriter buffer = new StringWriter();
        PrintWriter writer = new PrintWriter(buffer);
        writer.println("time=" + stamp);
        writer.println("thread=" + (thread == null ? "?" : thread.getName()));
        writer.println("package=" + safePackage(context));
        writer.println("hooks=" + HookLogger.summary());
        if (error != null) {
            error.printStackTrace(writer);
        }
        writer.flush();
        String text = buffer.toString();

        HookLogger.log("宿主崩溃:\n" + text);
        write(new File(safeDir(context, false), FILE_NAME), text);
        write(new File(safeDir(context, true), FILE_NAME), text);
        showCrashToast(context, error);
    }

    /**
     * 容器里基本拿不到日志文件，所以崩溃瞬间也喊一次 Toast——至少能看到是哪类异常。
     */
    private static void showCrashToast(Context context, Throwable error) {
        try {
            String name = error == null ? "?" : error.getClass().getSimpleName();
            String message = error == null ? "" : String.valueOf(error.getMessage());
            if (message.length() > 90) {
                message = message.substring(0, 90);
            }
            android.widget.Toast.makeText(context.getApplicationContext(),
                    "WY 崩溃记录: " + name + "\n" + message, android.widget.Toast.LENGTH_LONG).show();
        } catch (Throwable ignored) {
        }
    }

    private static String safePackage(Context context) {
        try {
            return context.getPackageName();
        } catch (Throwable t) {
            return "?";
        }
    }

    private static File safeDir(Context context, boolean external) {
        try {
            File dir = external ? context.getExternalFilesDir(null) : context.getFilesDir();
            if (dir != null) {
                return dir;
            }
        } catch (Throwable ignored) {
        }
        return new File("/data/local/tmp");
    }

    private static void write(File file, String text) {
        FileOutputStream out = null;
        try {
            File parent = file.getParentFile();
            if (parent != null && !parent.exists()) {
                //noinspection ResultOfMethodCallIgnored
                parent.mkdirs();
            }
            out = new FileOutputStream(file, true);
            out.write(("\n" + text + "\n").getBytes("UTF-8"));
            out.flush();
        } catch (Throwable ignored) {
        } finally {
            if (out != null) {
                try {
                    out.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }
}
