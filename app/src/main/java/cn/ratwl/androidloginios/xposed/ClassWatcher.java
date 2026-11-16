package cn.ratwl.androidloginios.xposed;

import java.lang.reflect.Method;

/**
 * 兜底：等宿主自己把 SDK 的类加载出来，再交给 {@link PlatformHook#patch}。
 * <p>
 * 用 {@code ClassLoader.findLoadedClass} 只查「已经加载过的类」，<b>不会触发任何加载</b>，
 * 所以它在免 ROOT 容器里是安全的（反过来，强行 {@code Class.forName} 会让元萝卜的桩
 * ClassLoader 提前初始化，实测直接闪退）。
 * <p>
 * 注意它是<b>兜底</b>而不是主路径：轮询有几百毫秒的粒度，赶不上 SDK 初始化的窗口
 * （1.8 版就是把它当主路径，结果面具里三端能显示、选完却没用）。主路径是
 * {@link PlatformHook#install}——在 {@code Application.attachBaseContext} 之后立刻挂。
 */
public final class ClassWatcher {

    private static final long FAST_INTERVAL_MS = 50L;
    private static final long FAST_WINDOW_MS = 3_000L;
    private static final long SLOW_INTERVAL_MS = 300L;
    /**
     * 轮询总时长。
     * <p>
     * 原来是 30 秒，赌的是「SDK 会在启动 30 秒内加载完」。但容器（元萝卜）里 guest 的
     * 加固包解密 + SDK 初始化可能拖到几十秒之后，一旦超过这个窗口，轮询就自己放弃了，
     * 表现是「不崩、但一直没有平台/渠道 hook」。拉长到 6 分钟，覆盖到进登录页为止；
     * 命中后线程会立刻退出，不会白跑。
     */
    private static final long TOTAL_MS = 360_000L;

    private static Method findLoadedClass;

    /**
     * 每个 ClassLoader 都要单独起一条轮询线程。
     * <p>
     * 元萝卜里 {@code handleLoadPackage} 给的 loader 只带 {@code base.apk}，而加固壳之后会用
     * 另一个 loader（{@code base.apk + app_split/.../classes.dex}）装载真正的应用类。
     * 只盯第一个 loader 的话，{@code mpay.*}、{@code SdkNetease} 这些类永远等不到。
     */
    private static final java.util.Set<ClassLoader> STARTED =
            java.util.Collections.synchronizedSet(
                    java.util.Collections.newSetFromMap(
                            new java.util.WeakHashMap<ClassLoader, Boolean>()));

    private ClassWatcher() {
    }

    static {
        try {
            findLoadedClass = ClassLoader.class.getDeclaredMethod("findLoadedClass", String.class);
            findLoadedClass.setAccessible(true);
        } catch (Throwable t) {
            findLoadedClass = null;
            HookLogger.log("findLoadedClass 不可用: " + t);
        }
    }

    public static void start(final ClassLoader classLoader) {
        if (classLoader == null) {
            return;
        }
        synchronized (STARTED) {
            if (!STARTED.add(classLoader)) {
                return;
            }
        }
        if (findLoadedClass == null) {
            HookLogger.mark(HookLogger.KEY_PLATFORM, false, "无 findLoadedClass");
            return;
        }
        Thread worker = new Thread(new Runnable() {
            @Override
            public void run() {
                // 只等 bindApplication 走完就开工。
                // 原来这里是 1500ms，理由是「1.18 在第一个 tick 就贴 hook 会闪退」。
                // 但实测元萝卜里 SDK 在注入后 1 秒内就把渠道算好了（PlatformHook 打出来的
                // DeviceUtils.appChannel 已经是 taptap_cps_dev），1500ms 等于永远晚一步。
                // 折中到 250ms：既避开加固初始化校验最敏感的头几百毫秒，又能赶在渠道落定之前。
                sleep(250L);
                // 先探一次：只做 findLoadedClass，不会触发任何类加载，没有副作用
                PlatformHook.patchLoaded(classLoader);
                long begin = System.currentTimeMillis();
                long deadline = begin + TOTAL_MS;
                while (System.currentTimeMillis() < deadline) {
                    // 不能一「全都挂过」就收工：容器里同一个类名可能有多份副本，
                    // 前一份挂上后 allHandled() 就为真，真正执行的那份反而再也没人管。
                    // 至少观察 15 秒，确保后面出现的副本也能被挂上。
                    if (System.currentTimeMillis() - begin > 15_000L
                            && PlatformHook.allHandled()) {
                        return;
                    }
                    PlatformHook.patchLoaded(classLoader);
                    long elapsed = System.currentTimeMillis() - begin;
                    sleep(elapsed < FAST_WINDOW_MS ? FAST_INTERVAL_MS : SLOW_INTERVAL_MS);
                }
                if (!PlatformHook.allHandled()) {
                    HookLogger.log("ClassWatcher 超时，仍有目标类未就绪（SDK 可能没被加载）");
                }
            }
        }, "wy-class-watch");
        worker.setDaemon(true);
        try {
            worker.start();
            HookLogger.log("ClassWatcher 兜底轮询已启动");
        } catch (Throwable t) {
            HookLogger.log("ClassWatcher 启动失败: " + t);
        }
    }

    /** 供 {@link PlatformHook#patchLoaded} 使用：只查已加载的类，绝不触发加载。 */
    public static Class<?> loaded(ClassLoader loader, String name) {
        if (findLoadedClass == null || loader == null) {
            return null;
        }
        return lookup(loader, name);
    }

    /** 只查已加载的类，绝不触发加载。 */
    private static Class<?> lookup(ClassLoader loader, String name) {
        ClassLoader cursor = loader;
        while (cursor != null) {
            try {
                Object found = findLoadedClass.invoke(cursor, name);
                if (found instanceof Class) {
                    return (Class<?>) found;
                }
            } catch (Throwable ignored) {
            }
            ClassLoader parent;
            try {
                parent = cursor.getParent();
            } catch (Throwable t) {
                parent = null;
            }
            cursor = parent;
        }
        return null;
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }
}
