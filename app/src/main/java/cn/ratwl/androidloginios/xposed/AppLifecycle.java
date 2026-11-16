package cn.ratwl.androidloginios.xposed;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.widget.Toast;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;

/**
 * 宿主 Application 生命周期的统一挂载点。
 * <p>
 * 两个时机各有用处：
 * <ul>
 *   <li><b>{@code attachBaseContext}</b>（{@code onCreate} 之前）——挂平台 / 渠道 Hook。
 *       SDK 初始化几乎都在 {@code onCreate} 里，只有赶在它前面挂上，{@code getPlatform()} 才会被改掉。</li>
 *   <li><b>{@code onCreate}</b>——崩溃记录 + 弹一次提示。</li>
 * </ul>
 * <p>
 * <b>提示为什么要限流：</b>容器（Dual Space / 元萝卜）里一个游戏会拉起好几个进程，每个进程都是独立的
 * 虚拟机、模块都会注入一次，于是每开一次游戏就弹一串同样的 Toast。这里做两道闸：
 * <ol>
 *   <li>只在「主进程」（进程名不带 {@code :}）提示，子进程一律闭嘴；</li>
 *   <li>落一个时间戳文件：一切正常时 24 小时只提示一次，有 Hook 失败时 10 分钟提示一次，方便排查。</li>
 * </ol>
 * 所有回调体统一走 {@link HookGuard#safe}，异常绝不给宿主。
 */
public final class AppLifecycle {

    private static final AtomicBoolean STARTED = new AtomicBoolean();
    private static final AtomicBoolean ONCE = new AtomicBoolean();
    private static final AtomicBoolean FLUSHER = new AtomicBoolean();
    private static volatile String PROCESS = "?";
    /** 注入时拿到的 loader（元萝卜里通常只是「外壳」loader，只带 base.apk）。 */
    private static volatile ClassLoader INSTALLED_LOADER;
    /** install 时存下来，供「应用就绪」的备用触发点使用。 */
    private static volatile String BANNER;
    private static volatile boolean CAN_TOAST;
    private static final java.util.concurrent.atomic.AtomicInteger LOADER_HITS =
            new java.util.concurrent.atomic.AtomicInteger();
    /** 已经补挂过的 loader，避免同一个 loader 反复跑。 */
    private static final Set<ClassLoader> SEEN_LOADERS = Collections.synchronizedSet(
            Collections.newSetFromMap(new java.util.WeakHashMap<ClassLoader, Boolean>()));

    private static final String SP_NAME = "wy_toast";
    /**
     * 提示间隔。
     * <p>
     * 容器里一个游戏会拉起 3～4 个进程，它们几乎同时走到 {@code onAppReady}，两分钟的窗口足够把
     * 「同一次启动」的其余进程压掉，只留一条；而用户重新开一次游戏通常远超两分钟，所以每次都能看到。
     * <p>
     * 早先这里是「正常态 24 小时」，本意是少打扰，结果调试时第二次启动就完全静默——用户看到的现象
     * 正是「文件有了、提示没了」。诊断期不能这么省。
     */
    private static final long TOAST_GAP_MS = 2L * 60 * 1000;
    /** SharedPreferences 也读写失败时的兜底：进程内只弹一次，绝不刷屏。 */
    private static final Set<String> TOASTED = Collections.synchronizedSet(new HashSet<String>());

    private AppLifecycle() {
    }

    public static void install(final ClassLoader classLoader, final String banner,
                               final boolean canToast, final String processName) {
        PROCESS = processName == null ? "?" : processName;
        INSTALLED_LOADER = classLoader;
        BANNER = banner;
        CAN_TOAST = canToast;
        if (!STARTED.compareAndSet(false, true)) {
            return;
        }
        hookAttachBaseContext(classLoader);
        hookOnCreate(banner, canToast);
        hookRealLoader();
        startReadyPoll();
    }

    /**
     * 找出「真正装载应用类」的 ClassLoader，并在它上面补挂一次。
     * <p>
     * 元萝卜里 {@code handleLoadPackage} 给的 loader 只带 {@code base.apk}；
     * 加固壳随后另建一个 loader（{@code base.apk + app_split/.../classes.dex}）去装应用类。
     * {@code com.netease.mpay.server.request.k}（改 {@code select_platforms} 的那个）就在后者里，
     * 只用前者轮询会一直等不到——现象是「渠道 hook 全 ok、登录页却没有三端选择」。
     * <p>
     * {@code Instrumentation.newActivity} 的第一个参数正好是「用哪个 loader 实例化 Activity」，
     * 从登录 Activity 这里能拿到那个真正的 loader。
     */
    private static void hookRealLoader() {
        try {
            XposedHelpers.findAndHookMethod("android.app.Instrumentation", null,
                    "newActivity", ClassLoader.class, String.class, android.content.Intent.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(final MethodHookParam param) {
                            if (LOADER_HITS.getAndIncrement() < 3) {
                                HookLogger.log("newActivity 触发 loader="
                                        + (param.args != null && param.args.length > 0
                                        && param.args[0] != null
                                        ? param.args[0].getClass().getName() : "null"));
                            }
                            if (param.args != null && param.args.length > 0
                                    && param.args[0] instanceof ClassLoader) {
                                onLoader((ClassLoader) param.args[0]);
                            }
                            // 元萝卜里 Application.onCreate / callApplicationOnCreate 这两层回调都不进，
                            // 这里是最早能确定「应用真的起来了」的地方，用它兜住 onAppReady。
                            onAppReadyIfPossible("newActivity");
                        }
                    });
            HookLogger.log("AppLifecycle.newActivity 已挂载（发现真实 ClassLoader）");
        } catch (Throwable t) {
            HookLogger.log("newActivity 挂载失败: " + t);
        }
    }

    /**
     * 等「应用起来了」的兜底轮询。
     * <p>
     * 容器（元萝卜）里 {@code Application.onCreate} 与 {@code callApplicationOnCreate} 两层 hook
     * 都显示「已挂载」，但回调一次都不进——它的 guest Application 是容器自己的子类，
     * 覆写了 {@code onCreate} 且不调 {@code super}，Pine 只 hook 精确方法，于是永远等不到。
     * 结果就是 {@code onAppReady} 一条都没执行：mpay 的 {@code select_platforms} hook 从来没装上，
     * 登录页自然不出三端。
     */
    private static void startReadyPoll() {
        try {
            Thread thread = new Thread(new Runnable() {
                @Override
                public void run() {
                    for (int i = 0; i < 90 && !ONCE.get(); i++) {
                        try {
                            Thread.sleep(1000L);
                        } catch (InterruptedException ignored) {
                            return;
                        }
                        onAppReadyIfPossible("poll");
                    }
                }
            }, "wy-ready-wait");
            thread.setDaemon(true);
            thread.start();
        } catch (Throwable t) {
            HookLogger.log("就绪轮询启动失败: " + t);
        }
    }

    /** 拿到 Application 实例就跑一次 {@link #onAppReady}；拿不到就等下一次。 */
    private static void onAppReadyIfPossible(final String from) {
        if (ONCE.get()) {
            return;
        }
        HookGuard.safe("app.ready." + from, new HookGuard.Body() {
            @Override
            public void run() {
                Context ctx = currentApplication();
                if (ctx == null) {
                    return;
                }
                onAppReady(ctx, BANNER, CAN_TOAST, from);
            }
        });
    }

    /** {@code ActivityThread.currentApplication()}，拿不到返回 null。 */
    private static Context currentApplication() {
        try {
            Object app = XposedHelpers.callStaticMethod(
                    XposedHelpers.findClass("android.app.ActivityThread", null),
                    "currentApplication");
            return app instanceof Context ? (Context) app : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static void onLoader(final ClassLoader loader) {
        if (loader == null) {
            return;
        }
        if (loader == INSTALLED_LOADER) {
            return;
        }
        if (!SEEN_LOADERS.add(loader)) {
            return;
        }
        HookLogger.log("发现真实 ClassLoader，补挂: " + loader.getClass().getName());
        HookGuard.safe("loader.upgrade", new HookGuard.Body() {
            @Override
            public void run() {
                PlatformHook.patchLoaded(loader);
                // 这个 loader 才装得下 mpay 的类，登录响应靠它改
                JsonHook.installMpayHelper(loader);
                ClassWatcher.start(loader);
            }
        });
    }

    /** attachBaseContext：早于 SDK 初始化，是挂平台 / 渠道 Hook 的唯一可靠时机。 */
    private static void hookAttachBaseContext(final ClassLoader classLoader) {
        try {
            XposedHelpers.findAndHookMethod(Application.class, "attachBaseContext",
                    Context.class, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(final MethodHookParam param) {
                            HookGuard.safe("app.attachBase", new HookGuard.Body() {
                                @Override
                                public void run() {
                                    Context context = null;
                                    if (param.args != null && param.args.length > 0
                                            && param.args[0] instanceof Context) {
                                        context = (Context) param.args[0];
                                    }
                                    ClassLoader loader = param.thisObject instanceof Context
                                            ? ((Context) param.thisObject).getClassLoader()
                                            : classLoader;
                                    if (loader == null) {
                                        loader = classLoader;
                                    }
                                    if (context != null) {
                                        Diag.init(context);
                                    }
                                    if (context != null && BootGuard.suspect(context)) {
                                        // 上一轮死在直连挂载 → 这一轮只轮询，不再强拉类
                                        HookLogger.log("检测到上次启动中断，本次仅用轮询（安全模式）");
                                        HookLogger.mark(HookLogger.KEY_PLATFORM, false, "安全模式");
                                    } else {
                                        if (context != null) {
                                            BootGuard.begin(context);
                                        }
                                        PlatformHook.install(loader);
                                        if (context != null) {
                                            BootGuard.end(context);
                                        }
                                    }
                                    // 兜底：万一类还没解密出来，等宿主自己加载它
                                    ClassWatcher.start(loader);
                                }
                            });
                        }
                    });
            HookLogger.log("AppLifecycle.attachBaseContext 已挂载");
        } catch (Throwable t) {
            HookLogger.log("attachBaseContext 挂载失败: " + t);
        }
    }

    private static void hookOnCreate(final String banner, final boolean canToast) {
        try {
            XposedHelpers.findAndHookMethod(Application.class, "onCreate", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(final MethodHookParam param) {
                    HookGuard.safe("app.onCreate", new HookGuard.Body() {
                        @Override
                        public void run() {
                            Object self = param.thisObject;
                            if (!(self instanceof Context)) {
                                return;
                            }
                            onAppReady((Context) self, banner, canToast, "onCreate");
                        }
                    });
                }
            });
            HookLogger.log("AppLifecycle.onCreate 已挂载");
        } catch (Throwable t) {
            HookLogger.log("Application.onCreate 挂载失败: " + t);
        }
        hookInstrumentation(banner, canToast);
    }

    /**
     * 第二条「应用起来了」的入口。
     * <p>
     * 容器的注入时机不一定落在 {@code Application.onCreate} 之前：如果它对 {@code Application}
     * 这个类的 hook 不生效（或者 Application 实例已经创建完），我们就永远等不到 onCreate，
     * 表现就是「模块在跑、但什么都没做、也不崩」。
     * {@code Instrumentation.callApplicationOnCreate} 是系统创建 Application 时的必经调用，
     * 换一个入口挂同一个回调，成功率更高；两条路都用 {@link #ONCE} 去重。
     */
    private static void hookInstrumentation(final String banner, final boolean canToast) {
        try {
            XposedHelpers.findAndHookMethod("android.app.Instrumentation", null,
                    "callApplicationOnCreate", Application.class, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(final MethodHookParam param) {
                            HookGuard.safe("instrumentation.create", new HookGuard.Body() {
                                @Override
                                public void run() {
                                    if (param.args == null || param.args.length == 0
                                            || !(param.args[0] instanceof Context)) {
                                        return;
                                    }
                                    onAppReady((Context) param.args[0], banner, canToast,
                                            "Instrumentation");
                                }
                            });
                        }
                    });
            HookLogger.log("AppLifecycle.Instrumentation 已挂载");
        } catch (Throwable t) {
            HookLogger.log("Instrumentation 挂载失败: " + t);
        }
    }

    /** 两条入口共用的主体，只执行一次。 */
    private static void onAppReady(Context context, String banner, boolean canToast, String from) {
        if (context == null || !ONCE.compareAndSet(false, true)) {
            return;
        }
        Diag.note("应用就绪入口: " + from);
        CrashGuard.install(context);
        Diag.init(context);
        if (!PlatformHook.isPlatformHooked()) {
            PlatformHook.install(context.getClassLoader());
        }
        // 晚一点再补一次（此时 SDK 已初始化，类都加载完了）；只碰已加载的类
        PlatformHook.patchLoaded(context.getClassLoader());
        // 应用层兜底：org.json 挂不上时，靠 SDK 自己的解析入口顶上
        JsonHook.installMpayHelper(context.getClassLoader());
        // 自检 + 定时落盘（容器里 Toast / 日志都拿不到，只能靠文件）
        Diag.runSelfTest(context.getClassLoader(), context);
        startDiagFlusher();
        if (!canToast || banner == null) {
            return;
        }
        if (!looksLikeTargetApp(context)) {
            // 容器里同一份容器包名会承载所有虚拟应用，不能光看包名就弹
            HookLogger.log("不是目标应用，跳过提示 proc=" + PROCESS + "：" + HookLogger.summary());
            return;
        }
        if (!shouldToast(context, PROCESS)) {
            HookLogger.log("提示已限流，本次静默 proc=" + PROCESS + "：" + HookLogger.summary());
            return;
        }
        try {
            StringBuilder text = new StringBuilder(banner);
            text.append('\n').append(HookLogger.summary());
            String selfTest = Diag.selfTestLine();
            if (!selfTest.isEmpty()) {
                text.append('\n').append(selfTest);
            }
            text.append("\n进程 ").append(PROCESS)
                    .append(" · 文件 ").append(Diag.publicVisible()
                            ? Diag.pathHint()
                            : "写不进公共目录（" + Diag.statusText() + "）");
            Toast.makeText(context.getApplicationContext(), text.toString(), Toast.LENGTH_LONG).show();
            // 详细版已经弹出去了，压制 6 秒后那条「仅注入证明」的补发
            Diag.markCreatedToast();
            HookLogger.log("Toast 已弹 proc=" + PROCESS + " 文件=" + Diag.pathHint());
        } catch (Throwable t) {
            HookLogger.log("Toast 失败: " + t);
        }
    }

    /**
     * 定时把 hook 命中次数落盘。
     * <p>
     * 用户点完三端选择、走完登录之后，我们才知道这些 hook 有没有被宿主调用过；
     * 容器里又拿不到日志，所以每隔一段时间把计数写进 {@code wy_diag.txt}。
     * 只跑 10 分钟、daemon 线程，跑完自己退出。
     */
    private static void startDiagFlusher() {
        if (!FLUSHER.compareAndSet(false, true)) {
            return;
        }
        try {
            Thread thread = new Thread(new Runnable() {
                @Override
                public void run() {
                    for (int i = 0; i < 40; i++) {
                        try {
                            Thread.sleep(15000L);
                        } catch (InterruptedException ignored) {
                            return;
                        }
                        Diag.flush(false);
                    }
                }
            }, "wy-diag");
            thread.setDaemon(true);
            thread.start();
        } catch (Throwable t) {
            HookLogger.log("诊断线程启动失败: " + t);
        }
    }

    /**
     * 判断当前进程是不是真的目标游戏。
     * <p>
     * 容器（Dual Space 等）里，<b>所有</b>虚拟应用上报的 {@code packageName} 都是容器自己的包名，
     * 于是「包的过滤」在这类环境里等于没有——每开一个虚拟应用都会注入一次、弹一次提示。
     * 这里退一步看 APK / 数据 / so 库的路径：虚拟引擎会把 guest 的 APK 解包到
     * {@code .../virtual/data/app/<游戏包名>-N/base.apk} 这类路径下，路径里通常还留着游戏包名。
     */
    private static boolean looksLikeTargetApp(Context context) {
        String pkg = null;
        try {
            pkg = context.getPackageName();
            if (pkg != null && pkg.startsWith("com.netease.")) {
                return true;
            }
        } catch (Throwable ignored) {
        }
        // 容器场景只能看「痕迹」：解包路径、so 目录、DexClassLoader 的 dex 路径里通常还留着游戏包名
        StringBuilder probe = new StringBuilder();
        probe.append(PROCESS).append('|');
        try {
            android.content.pm.ApplicationInfo info = context.getApplicationInfo();
            probe.append(info.sourceDir).append('|')
                    .append(info.dataDir).append('|')
                    .append(info.nativeLibraryDir).append('|')
                    .append(info.publicSourceDir);
        } catch (Throwable ignored) {
        }
        try {
            ClassLoader loader = context.getClassLoader();
            if (loader != null) {
                probe.append('|').append(loader);
            }
        } catch (Throwable ignored) {
        }
        return probe.toString().contains("netease");
    }

    /**
     * 限流：<b>按包名</b>，两分钟内只弹一条。
     * <p>
     * 早期按「主进程 / 子进程」一刀切，元萝卜（guest 进程名带 {@code :}）永远被判成子进程，一条都不弹。
     * 改成按进程名之后，一个游戏 3～4 个进程会各弹一条，又多得烦人。现在改成按<b>包名</b>跨进程共享时间戳：
     * 同一次启动只留第一条，重开游戏又能看到。状态从「正常」变「有失败」时 key 变化，会立刻再弹一条。
     * <p>
     * 时间戳走 {@link SharedPreferences}（容器内一定可写），比写文件可靠；连它都失败时退回进程内静态集合。
     */
    private static boolean shouldToast(Context context, String process) {
        boolean allOk = HookLogger.allOk();
        String pkg = "?";
        try {
            pkg = String.valueOf(context.getPackageName());
        } catch (Throwable ignored) {
        }
        String key = "t_" + pkg + (allOk ? "_ok" : "_bad");
        try {
            SharedPreferences sp = context.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE);
            long now = System.currentTimeMillis();
            if (now - sp.getLong(key, 0L) < TOAST_GAP_MS) {
                Diag.note("提示跳过：2 分钟内已弹过（proc=" + process + "）");
                return false;
            }
            sp.edit().putLong(key, now).commit();
            Diag.note("提示放行 allOk=" + allOk + " proc=" + process);
            return true;
        } catch (Throwable t) {
            boolean first = TOASTED.add(key);
            Diag.note("限流降级（SP 不可用 " + t.getClass().getSimpleName() + "）放行=" + first);
            return first;
        }
    }
}
