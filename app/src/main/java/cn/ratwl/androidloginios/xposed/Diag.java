package cn.ratwl.androidloginios.xposed;

import android.content.Context;
import android.os.Build;
import android.os.Environment;

import java.io.File;
import java.io.FileOutputStream;
import java.io.RandomAccessFile;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 落盘自检。
 * <p>
 * 容器（元萝卜）里 Toast 弹不出来、XposedBridge 日志也拿不到，于是「到底是没注入、还是 hook 挂了但没生效」
 * 永远只能靠猜——前面好几轮就是这么走弯路的。这里换成最笨但最可靠的办法：把结论写进文件，
 * 用户用系统文件管理器就能看。
 * <p>
 * 写两份内容：
 * <ul>
 *   <li><b>自检</b>：装完 hook 之后主动调用一次被 hook 的方法（{@code SystemProperties.get("ro.build.id")}、
 *       {@code JSONObject.optJSONArray("select_platforms")}、{@code AppChannel.getAppChannel(ctx)}），
 *       看返回值有没有被改掉——一次就能分清「hook 挂不上」和「hook 挂上了但宿主没走这条路」；</li>
 *   <li><b>命中计数</b>：每个 hook 的调用次数。登录走完还全是 0，说明宿主根本没调这些方法。</li>
 * </ul>
 * 目录优先外部（{@code /sdcard/Android/data/<宿主包名>/files/}、{@code /sdcard/Download/}），拿不到再退回内部。
 * 任何写入失败都静默，绝不影响宿主。
 */
public final class Diag {

    public static final String FILE_NAME = "wy_diag.txt";

    /** 超过这个大小就轮转（改名，不删除），避免常年累月把用户存储写爆。 */
    private static final long MAX_BYTES = 256 * 1024;

    private static final Map<String, AtomicInteger> HITS = new LinkedHashMap<>();
    private static final List<String> PENDING = new ArrayList<>();
    private static final List<File> DIRS = new ArrayList<>();
    /** 每条候选目录的写入结果，例如 {@code Download=ok, media=PermissionDenied}。 */
    private static final List<String> DIR_REPORT = new ArrayList<>();
    /** 本轮真正写成功的目录（绝对路径），用于 {@link #pathHint()}。 */
    private static final List<File> OK_DIRS = new ArrayList<>();

    private static volatile boolean ready;
    private static volatile long lastFlush;
    private static volatile String selfTest = "";

    private static final AtomicBoolean LOAD_TOAST = new AtomicBoolean();
    /** onCreate 那条详细提示是否真的弹出去了——弹了就不再补发注入证明。 */
    private static volatile boolean createdToastShown;

    private Diag() {
    }

    public static void hit(String key) {
        try {
            boolean first = false;
            synchronized (HITS) {
                AtomicInteger counter = HITS.get(key);
                if (counter == null) {
                    counter = new AtomicInteger();
                    HITS.put(key, counter);
                    first = true;
                }
                counter.incrementAndGet();
            }
            // 每个 key 只打第一条：容器里文件写不进去，logcat 是唯一能看到
            // 「hook 到底有没有被宿主调用」的地方。没有这行，只能靠计数文件，而它在容器里必然为空。
            if (first) {
                HookLogger.log("首次命中: " + key);
            }
        } catch (Throwable ignored) {
        }
    }

    /** Context 还没拿到时先缓存，等 init 一起写出去。 */
    public static void note(String line) {
        try {
            synchronized (PENDING) {
                if (PENDING.size() < 40) {
                    PENDING.add(stamp() + " " + line);
                }
            }
        } catch (Throwable ignored) {
        }
        HookLogger.log(line);
    }

    /**
     * 注入瞬间的「在场证明」，不需要 Context。
     * <p>
     * {@link #init(Context)} 只能挂在 {@code Application.attachBaseContext} 上。万一容器（元萝卜）
     * 那一层 hook 根本没进到我们的回调里，{@code init} 永不执行 → 一个文件都写不出来 → 用户看到的现象
     * 和「模块没注入」一模一样。这里在 {@code handleLoadPackage} 就先用包名拼出路径写一份，
     * 于是「连这份都没有」＝真的没注入，「有这份但没有后续」＝注入了但 Application hook 没生效。
     * 两者处置办法完全不同，值得单独落一条。
     */
    public static void bootMarker(String packageName, String processName) {
        try {
            ensureDirs(packageName);
            append("\n===== " + stamp() + " 注入证明 =====\n"
                    + "pkg=" + packageName + " proc=" + processName + '\n'
                    + "stage=handleLoadPackage（Application 还没创建）\n");
        } catch (Throwable ignored) {
        }
    }

    /** 没有 Context 时的最小目录集：靠包名拼，能写就写，写不进不影响宿主。 */
    private static void ensureDirs(String packageName) {
        synchronized (DIRS) {
            if (!DIRS.isEmpty()) {
                return;
            }
            if (packageName != null && !packageName.isEmpty()) {
                DIRS.add(new File("/storage/emulated/0/Android/media/" + packageName));
                DIRS.add(new File("/sdcard/Android/media/" + packageName));
            }
            DIRS.add(new File("/sdcard/Download"));
            DIRS.add(new File("/sdcard"));
        }
    }

    /**
     * 注入瞬间的兜底提示，<b>完全不依赖 {@code Application} 的任何 hook</b>。
     * <p>
     * 元萝卜里出现过极端情况：文件一条都写不出来（存储被完全隔离），{@code attachBaseContext}
     * 的回调也没进（SHook 对 Application 的 hook 不生效）——于是不崩、不弹、不写文件，零信号。
     * 这条走的是 {@code ActivityThread.getSystemContext()} 拿系统 Context 再弹 Toast，只要有 Looper 就能弹，
     * 不需要宿主 Application、不需要 App 自己的任何类。
     * <p>
     * 延迟 6 秒是因为：正常流程下 {@code Application.onCreate} 那条详细提示会在这之前弹出来，
     * 一旦弹了就把标志置上，这条自动放弃，不会重复打扰。
     */
    public static void loadToast(String processName) {
        if (!shouldLoudOnLoad(processName)) {
            return;
        }
        if (!LOAD_TOAST.compareAndSet(false, true)) {
            return;
        }
        try {
            final String proc = shortProc(processName);
            // 关键：**不碰主线程 Looper**。1.16 用的是 Handler.postDelayed，那是唯一新增的
            // 「会在宿主主线程上执行代码」的路径——如果容器里 system Context 的 Toast 在 Binder 侧
            // 炸掉，整个游戏就跟着被杀，表现正是「1.16 连 txt 都没了」（崩得太早，文件还没落盘）。
            // 现在改成自己开一条 daemon 线程 + 自备 Looper，炸也只炸这个线程。
            Thread thread = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        Thread.sleep(6000L);
                    } catch (InterruptedException ignored) {
                        return;
                    }
                    if (createdToastShown) {
                        return;
                    }
                    if (publicVisible()) {
                        // 文件已经能落到公共目录 —— 说明这个环境不需要这条兜底，
                        // 也就不必去碰 system Context 的 Toast（少一个崩溃面）。
                        return;
                    }
                    try {
                        Class<?> activityThread = Class.forName("android.app.ActivityThread");
                        Object at = activityThread.getMethod("currentActivityThread").invoke(null);
                        Context sys = (Context) activityThread.getMethod("getSystemContext").invoke(at);
                        if (sys == null) {
                            HookLogger.log("loadToast: 拿不到系统 Context，放弃");
                            return;
                        }
                        android.os.Looper.prepare();
                        android.widget.Toast.makeText(sys,
                                "WY 已注入（仅此一条，说明 Application hook 没生效）\n" + proc,
                                android.widget.Toast.LENGTH_LONG).show();
                        HookLogger.log("loadToast 已补发 proc=" + proc);
                        final android.os.Looper looper = android.os.Looper.myLooper();
                        new android.os.Handler().postDelayed(new Runnable() {
                            @Override
                            public void run() {
                                if (looper != null) {
                                    looper.quitSafely();
                                }
                            }
                        }, 4000L);
                        android.os.Looper.loop();
                    } catch (Throwable t) {
                        HookLogger.log("loadToast 失败: " + t);
                    }
                }
            }, "wy-load-toast");
            thread.setDaemon(true);
            thread.start();
        } catch (Throwable t) {
            HookLogger.log("loadToast 排队失败: " + t);
        }
    }

    /** onCreate 的详细提示真的弹了，压制 {@link #loadToast} 的补发。 */
    public static void markCreatedToast() {
        createdToastShown = true;
    }

    /**
     * 只在「guest 主进程」上补发，避免容器里 3～4 个进程各弹一条。
     * <p>
     * 启发式：进程名不带冒号（面具 / 原生）算主进程；带冒号的按虚拟引擎的惯例，
     * {@code :p0} 是 guest 主进程，{@code :push}、{@code :remote}、{@code :service} 之类都不是。
     * 猜错也不要紧——最坏情况是元萝卜里这条不出现，那就只剩 logcat 一条路了。
     */
    private static boolean shouldLoudOnLoad(String processName) {
        if (processName == null || processName.isEmpty()) {
            return true;
        }
        int index = processName.indexOf(':');
        if (index < 0) {
            return true;
        }
        return "p0".equals(processName.substring(index + 1));
    }

    private static String shortProc(String proc) {
        if (proc == null || proc.isEmpty()) {
            return "?";
        }
        int index = proc.indexOf(':');
        return index < 0 ? proc : proc.substring(index);
    }

    /** 在 Application.attachBaseContext 里调用一次。 */
    public static void init(Context context) {
        if (ready || context == null) {
            return;
        }
        ready = true;
        try {
            resolveDirs(context);
            StringBuilder header = new StringBuilder();
            header.append("\n===== ").append(stamp()).append(" 新会话 =====\n");
            header.append("pkg=").append(context.getPackageName())
                    .append(" proc=").append(currentProcess())
                    .append(" sdk=").append(Build.VERSION.SDK_INT).append('\n');
            try {
                android.content.pm.ApplicationInfo info = context.getApplicationInfo();
                header.append("src=").append(info.sourceDir).append('\n');
                header.append("data=").append(info.dataDir).append('\n');
            } catch (Throwable ignored) {
            }
            header.append("build.id=").append(Build.ID).append('\n');
            synchronized (PENDING) {
                for (String line : PENDING) {
                    header.append(line).append('\n');
                }
                PENDING.clear();
            }
            append(header.toString());
        } catch (Throwable ignored) {
        }
    }

    /**
     * 主动验证：直接调一次被 hook 的方法，看结果有没有被替换。
     * 由 {@link AppLifecycle} 在 onCreate（hook 都装完之后）调用一次。
     */
    public static void runSelfTest(ClassLoader classLoader, Context context) {
        StringBuilder out = new StringBuilder();
        out.append("自检 ");
        out.append("prop=").append(probeProperty(classLoader));
        out.append(" json=").append(probeJson(classLoader));
        out.append(" channel=").append(probeChannel(classLoader, context));
        selfTest = out.toString();
        note(selfTest);
        flush(true);
    }

    private static String probeProperty(ClassLoader classLoader) {
        try {
            Class<?> cls = Class.forName("android.os.SystemProperties", false, classLoader);
            java.lang.reflect.Method get = cls.getMethod("get", String.class);
            Object value = get.invoke(null, "ro.build.id");
            return String.valueOf(value);
        } catch (Throwable t) {
            return "err:" + shortName(t);
        }
    }

    private static String probeJson(ClassLoader classLoader) {
        try {
            Class<?> cls = Class.forName("org.json.JSONObject", false, classLoader);
            Object json = cls.getConstructor().newInstance();
            Object array = cls.getMethod("optJSONArray", String.class)
                    .invoke(json, "select_platforms");
            return String.valueOf(array);
        } catch (Throwable t) {
            return "err:" + shortName(t);
        }
    }

    private static String probeChannel(ClassLoader classLoader, Context context) {
        if (context == null) {
            return "skip";
        }
        Class<?> cls = null;
        String[] names = {
                "com.netease.ntunisdk.modules.deviceinfo.AppChannel",
                "com.netease.ntunisdk.base.utils.cps.ApkChanneling"
        };
        for (String name : names) {
            try {
                cls = Class.forName(name, false, classLoader);
                break;
            } catch (Throwable ignored) {
            }
        }
        if (cls == null) {
            return "noclass";
        }
        try {
            java.lang.reflect.Method method = cls.getMethod("getAppChannel", Context.class);
            return String.valueOf(method.invoke(null, context));
        } catch (Throwable ignored) {
        }
        try {
            java.lang.reflect.Method method = cls.getMethod("getChannel", Context.class);
            return String.valueOf(method.invoke(null, context));
        } catch (Throwable t) {
            return "err:" + shortName(t);
        }
    }

    /** 写快照；{@code force=false} 时至少间隔 5 秒，避免频繁 IO。 */
    public static void flush(boolean force) {
        if (!ready) {
            return;
        }
        long now = System.currentTimeMillis();
        if (!force && now - lastFlush < 5000L) {
            return;
        }
        lastFlush = now;
        try {
            StringBuilder body = new StringBuilder();
            body.append(stamp()).append(" 结论: 模块已运行 | ")
                    .append(HookLogger.summary())
                    .append(" | hits=").append(hitsText())
                    .append('\n');
            body.append("  paths=").append(statusText()).append('\n');
            if (!selfTest.isEmpty()) {
                body.append("  ").append(selfTest).append('\n');
            }
            append(body.toString());
            // 兜底：文件全军覆没时，logcat 里至少还有一行完整结论
            HookLogger.log("diag 结论=" + HookLogger.summary()
                    + " hits=" + hitsText() + " paths=" + statusText());
        } catch (Throwable ignored) {
        }
    }

    /** 最近一次自检结论（一行）。 */
    public static String selfTestLine() {
        return selfTest;
    }

    /** 给 Toast / README 用的可读路径。 */
    public static String pathHint() {
        synchronized (OK_DIRS) {
            if (!OK_DIRS.isEmpty()) {
                return OK_DIRS.get(0).getAbsolutePath() + "/" + FILE_NAME;
            }
        }
        synchronized (DIRS) {
            if (DIRS.isEmpty()) {
                return "（无可用目录）";
            }
            return DIRS.get(0).getAbsolutePath() + "/" + FILE_NAME + "（未写成）";
        }
    }

    /**
     * 至少有一条落在了用户能看见的地方（非 {@code /data} 沙箱）。
     * 容器把 {@code /sdcard} 重定向之后，写成功的也只是私有目录，此时用户照样找不到文件——要区分开。
     */
    public static boolean publicVisible() {
        synchronized (OK_DIRS) {
            for (File dir : OK_DIRS) {
                String path = dir.getAbsolutePath();
                if (!path.startsWith("/data/") && !path.contains("/virtual/")) {
                    return true;
                }
            }
        }
        return false;
    }

    private static String hitsText() {
        StringBuilder out = new StringBuilder();
        synchronized (HITS) {
            for (Map.Entry<String, AtomicInteger> entry : HITS.entrySet()) {
                if (out.length() > 0) {
                    out.append(',');
                }
                out.append(entry.getKey()).append('=').append(entry.getValue().get());
            }
        }
        return out.length() == 0 ? "0" : out.toString();
    }

    private static void resolveDirs(Context context) {
        List<File> candidates = new ArrayList<>();
        String pkg = null;
        try {
            pkg = context.getPackageName();
        } catch (Throwable ignored) {
        }
        // ① /sdcard/Android/media/<包名>：Android 11+ 免权限，且文件管理器不屏蔽（不像 Android/data）。
        //    容器里 /sdcard 常被重定向，所以下面还会写绝对路径 + 其它出口，多留几条路。
        if (pkg != null) {
            candidates.add(new File("/storage/emulated/0/Android/media/" + pkg));
            candidates.add(new File("/sdcard/Android/media/" + pkg));
        }
        try {
            File[] media = context.getExternalMediaDirs();
            if (media != null) {
                for (File dir : media) {
                    if (dir != null) {
                        candidates.add(dir);
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        // ② Download：老路径，容器不重定向时最好找
        try {
            candidates.add(new File(Environment.getExternalStorageDirectory(), "Download"));
        } catch (Throwable ignored) {
        }
        candidates.add(new File("/sdcard/Download"));
        try {
            File external = context.getExternalFilesDir(null);
            if (external != null) {
                candidates.add(external);
            }
        } catch (Throwable ignored) {
        }
        // ③ 容器私有目录：一定可写，但用户翻不到——只用来验证「模块到底跑没跑」
        try {
            candidates.add(context.getFilesDir());
        } catch (Throwable ignored) {
        }
        synchronized (DIRS) {
            DIRS.clear();
            DIRS.addAll(candidates);
        }
    }

    /**
     * 把同一段内容写进<b>所有</b>候选目录，并记下每条的结果。
     * <p>
     * 早期版本是「第一个写成功就 return」，这在容器里恰好是坑：元萝卜（VirtualApp 系）会把 guest 的
     * {@code /sdcard} 重定向到自己的私有虚拟目录，{@link FileOutputStream} 返回成功、文件却落进容器沙箱，
     * 用户拿文件管理器永远翻不到，于是「模块没跑」和「文件写丢了」彻底分不清。
     * 现在改成全写 + 逐条记录，只要有一条真的落在公共目录里就能拿到；一条都没落进公共目录，
     * 这个 {@code paths=} 报告本身也能说明问题（全是容器内路径 = 被重定向了）。
     */
    private static void append(String text) {
        List<File> targets;
        synchronized (DIRS) {
            targets = new ArrayList<>(DIRS);
        }
        StringBuilder report = new StringBuilder();
        List<File> okDirs = new ArrayList<>();
        for (File dir : targets) {
            String label = label(dir);
            try {
                if (dir == null) {
                    continue;
                }
                if (!dir.exists() && !dir.mkdirs()) {
                    report.append(label).append("=nodir,");
                    continue;
                }
                if (!dir.canWrite()) {
                    report.append(label).append("=nowrite,");
                    continue;
                }
                File file = new File(dir, FILE_NAME);
                if (file.exists() && file.length() > MAX_BYTES) {
                    // 超限只改名、绝不 delete：容器里 3～4 个进程会同时往同一个文件追加，
                    // 一旦某个进程删了、另一个正握着句柄写、第三个又删一次，文件就凭空消失
                    // （1.15→1.16 实测就是这样「连 txt 都没了」）。renameTo 是原子操作，最坏只是交错。
                    File old = new File(dir, FILE_NAME + ".1");
                    //noinspection ResultOfMethodCallIgnored
                    old.delete();
                    //noinspection ResultOfMethodCallIgnored
                    file.renameTo(old);
                }
                FileOutputStream out = new FileOutputStream(file, true);
                try {
                    out.write(text.getBytes("UTF-8"));
                    out.flush();
                } finally {
                    out.close();
                }
                report.append(label).append("=ok,");
                okDirs.add(dir);
            } catch (Throwable t) {
                report.append(label).append('=').append(shortName(t)).append(',');
            }
        }
        synchronized (DIR_REPORT) {
            DIR_REPORT.clear();
            DIR_REPORT.add(report.length() == 0 ? "（无候选目录）" : report.toString());
        }
        synchronized (OK_DIRS) {
            OK_DIRS.clear();
            OK_DIRS.addAll(okDirs);
        }
        if (okDirs.isEmpty()) {
            HookLogger.log("Diag 一个目录都没写进去: " + report);
        }
    }

    /** 目录名简写：取最后两级，够区分而且 Toast 放得下。 */
    private static String label(File dir) {
        if (dir == null) {
            return "null";
        }
        String path = dir.getAbsolutePath();
        path = path.replace("/storage/emulated/0", "~");
        int cut = path.lastIndexOf('/', path.length() - 2);
        if (cut > 0 && path.length() - cut < 24) {
            return path.substring(cut + 1);
        }
        return path.length() > 22 ? "…" + path.substring(path.length() - 21) : path;
    }

    /** 每条候选目录的写入结果，给 Toast / 文件用。 */
    public static String statusText() {
        synchronized (DIR_REPORT) {
            return DIR_REPORT.isEmpty() ? "（未写）" : DIR_REPORT.get(0);
        }
    }

    private static String currentProcess() {
        try {
            RandomAccessFile file = new RandomAccessFile("/proc/self/cmdline", "r");
            try {
                byte[] buffer = new byte[128];
                int read = file.read(buffer);
                if (read <= 0) {
                    return "?";
                }
                int end = 0;
                while (end < read && buffer[end] != 0) {
                    end++;
                }
                return new String(buffer, 0, end, "UTF-8").trim();
            } finally {
                file.close();
            }
        } catch (Throwable t) {
            return "?";
        }
    }

    private static String stamp() {
        try {
            return new SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(new Date());
        } catch (Throwable t) {
            return String.valueOf(System.currentTimeMillis());
        }
    }

    private static String shortName(Throwable t) {
        String name = t.getClass().getSimpleName();
        return name.isEmpty() ? t.getClass().getName() : name;
    }
}
