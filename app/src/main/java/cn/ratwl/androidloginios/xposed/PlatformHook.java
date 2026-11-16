package cn.ratwl.androidloginios.xposed;

import android.content.Context;

import java.io.File;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * 渠道 / 平台伪装的真正实现。
 * <p>
 * <b>为什么必须「尽早」挂上：</b>{@code SdkNetease.getPlatform()} 的返回值在 SDK 初始化时就被读走，
 * 用来决定登录页给哪些入口、以及点下去走哪条通道。一旦晚于初始化（1.7 的 1.8s 延时、1.8 的
 * 200ms 轮询都可能晚），登录页照样能显示，但用户选完还是走默认通道——表现就是「能看见按钮，点了没用」。
 * 所以挂载点放在 {@code Application.attachBaseContext} 之后，早于 {@code onCreate} 里的 SDK 初始化。
 * <p>
 * <b>为什么不能强行 {@code Class.forName}：</b>光遇是加固包，在 {@code handleLoadPackage} 那一刻
 * 真实 dex 还没解密完，强行拉 {@code SdkNetease} 会驱动容器的桩 ClassLoader，元萝卜（SlimVXposed）
 * 下直接闪退（实测）。因此这里分两条路：
 * <ol>
 *   <li>主路径：{@link #install(ClassLoader)} 在 attachBaseContext 后直接按类名挂——面具 / Dual Space 可用；</li>
 *   <li>兜底路径：{@link ClassWatcher} 用 {@code findLoadedClass} 轮询（不触发加载），
 *       万一类还没解密出来，等它被宿主加载出来的那一刻补挂。</li>
 * </ol>
 * 只有「返回 String」的方法才会被改结果，避免把 String 塞给非 String 的返回值把宿主搞崩。
 */
public final class PlatformHook {

    public static final String SDK_CLASS = "com.netease.ntunisdk.SdkNetease";
    public static final String APK_CHANNELING_CLASS =
            "com.netease.ntunisdk.base.utils.cps.ApkChanneling";
    /** 渠道的另一个出口：smali 里 `AppChannel.getChannel(Context)` 会被其它模块直接调用。 */
    public static final String APP_CHANNEL_CLASS =
            "com.netease.ntunisdk.modules.deviceinfo.AppChannel";
    /**
     * 设备信息模块。渠道在这里被<b>缓存</b>进静态字段 {@code appChannel}（默认 "unknown"），
     * 之后所有上报都读缓存——所以只 hook 方法不够，还得把缓存本身改掉。
     */
    public static final String DEVICE_UTILS_CLASS =
            "com.netease.ntunisdk.modules.deviceinfo.DeviceUtils";

    public static final String CHANNEL = "netease.mumu2_free_dev";

    /** 第五人格的平台值：本项目要求「钉死 PC」。 */
    private static final String PLATFORM_DWRG = "pc";

    /**
     * 光遇的平台值。
     * <p>正常情况下<b>不会被写进</b> {@code getPlatform}（{@code forcePlatform=false}），
     * 只在 {@link HookFlags#FORCE_PLATFORM} 打开做对照实验时才会用到。</p>
     */
    private static final String PLATFORM_SKY = "pc";

    private static final Class<?>[] NO_ARGS = new Class<?>[0];

    /** 已经处理过的类（成功挂上或确认不存在都算处理过，不重复尝试）。 */
    /**
     * 已挂过的目标类，<b>按 Class 对象身份</b>去重。
     * <p>
     * 原来按「类名」去重，在元萝卜里踩了坑：注入时拿到的 loader 与真正执行应用的 loader
     * 各自装着一份同名类，第一次挂在前者上就把类名记进 DONE，后者再进来直接被跳过——
     * 结果是 hook 装得「成功」、实际执行的代码一份没改，日志里 `hits=0`、登录页不出三端。
     * 换成 Class 身份去重后，每个副本都会被单独挂上。
     */
    private static final Set<Class<?>> DONE = Collections.newSetFromMap(
            Collections.synchronizedMap(new java.util.IdentityHashMap<Class<?>, Boolean>()));
    private static volatile boolean sdkDone;
    private static volatile boolean apkDone;
    private static volatile boolean appDone;
    private static volatile boolean deviceDone;

    private static volatile String platform = "pc";
    private static volatile boolean forcePlatform;
    private static volatile boolean platformHooked;
    private static volatile boolean channelHooked;

    /**
     * 渠道伪装总开关，对应界面上的「模块功能 → 渠道伪装」。
     * <p>关掉之后 {@link #install} / {@link #patchLoaded} 整层直接返回，
     * {@link ClassWatcher} 也会自然找不到可挂的目标。</p>
     */
    private static volatile boolean enabled = true;

    public static void setEnabled(boolean value) {
        enabled = value;
    }

    private PlatformHook() {
    }

    /**
     * 按探测串决定返回值与是否强制。
     * <p>
     * <b>光遇不能强制平台</b>：`SdkNetease.getPlatform()` 的返回值就是「玩家选的是哪一端」，
     * 三端选择框的结果最终会沿这条路走进登录请求。把它钉死成 "pc"，等于无论玩家选什么，
     * 登录都按 pc 走 —— 表现正是「有切换按钮、点了没用、账号没变」（1.7/1.8/1.9 实测）。
     * 上游那版能用，恰恰是因为它在目标版本上<b>根本不 hook getPlatform</b>。
     * <p>
     * <b>第五人格相反</b>：它的三端入口不靠这个值，所以直接钉死。本工程的口径是
     * {@code pc}（与上游 1.19 的 {@code ad} 不同，按项目需求定）。
     * <p>
     * {@code probe} 由调用方拼好，通常包含 packageName / processName / appInfo.packageName
     * / appInfo.sourceDir —— 容器里前两项往往是容器自己的包名，只有后两项才可能露出游戏的影子。
     */
    public static void configure(String probe, boolean force) {
        boolean dwrg = probe != null && probe.contains("dwrg");
        platform = dwrg ? PLATFORM_DWRG : PLATFORM_SKY;
        forcePlatform = force || dwrg;
    }

    public static String currentPlatform() {
        return platform;
    }

    public static boolean isForcePlatform() {
        return forcePlatform;
    }

    /** 被 SDK 通过反射调用时使用（ChannelHook 反射代理路径的兜底）。 */
    public static String getPlatform() {
        return platform;
    }

    public static boolean isPlatformHooked() {
        return platformHooked;
    }

    /** 三个目标类是否都已处理（轮询据此退出）。 */
    public static boolean allHandled() {
        return sdkDone && apkDone && appDone && deviceDone;
    }

    /**
     * 只在<b>已经加载</b>的类上补挂（用 {@code findLoadedClass}，绝不触发加载）。
     * <p>
     * 用在 {@code Application.onCreate} 这种「SDK 已经在初始化、强拉类有风险」的时机，
     * 以及 {@link ClassWatcher} 的轮询里。
     */
    public static void patchLoaded(ClassLoader classLoader) {
        if (!enabled || classLoader == null) {
            return;
        }
        Class<?> sdk = loaded(classLoader, SDK_CLASS);
        if (sdk != null) {
            patch(sdk);
        }
        Class<?> channeling = loaded(classLoader, APK_CHANNELING_CLASS);
        if (channeling != null) {
            patch(channeling);
        }
        Class<?> appChannel = loaded(classLoader, APP_CHANNEL_CLASS);
        if (appChannel != null) {
            patch(appChannel);
        }
        Class<?> deviceUtils = loaded(classLoader, DEVICE_UTILS_CLASS);
        if (deviceUtils != null) {
            patch(deviceUtils);
        }
    }

    private static Class<?> loaded(ClassLoader classLoader, String name) {
        // 不做「按类名一次性跳过」：不同 loader 的同名类是不同的 Class，各挂各的
        return ClassWatcher.loaded(classLoader, name);
    }

    /**
     * 主路径：按类名直接挂。类拿不到（加固未解密 / 容器不允许强拉）时静默退让，
     * 由 {@link ClassWatcher} 在类被宿主加载出来之后补挂。
     */
    public static void install(ClassLoader classLoader) {
        if (!enabled || classLoader == null) {
            return;
        }
        Class<?> sdk = find(classLoader, SDK_CLASS);
        if (sdk != null) {
            patch(sdk);
        }
        Class<?> channeling = find(classLoader, APK_CHANNELING_CLASS);
        if (channeling != null) {
            patch(channeling);
        }
        Class<?> appChannel = find(classLoader, APP_CHANNEL_CLASS);
        if (appChannel != null) {
            patch(appChannel);
        }
        // DeviceUtils 不强拉（它在加固 dex 里，强拉在容器里会崩），只处理已经加载出来的
        patchLoaded(classLoader);
        if (!platformHooked) {
            HookLogger.mark(HookLogger.KEY_PLATFORM, false, "等加载");
        }
    }

    private static Class<?> find(ClassLoader classLoader, String name) {
        try {
            return XposedHelpers.findClass(name, classLoader);
        } catch (Throwable t) {
            HookLogger.log("PlatformHook: " + name + " 暂不可用（" + t + "）");
            return null;
        }
    }

    /** 类已经出现（不管是直连拿到的还是 ClassWatcher 等到的），就地挂 Hook。 */
    public static void patch(Class<?> owner) {
        if (owner == null) {
            return;
        }
        String name = owner.getName();
        if (!DONE.add(owner)) {
            return;
        }
        // 打出身份哈希：同一个类名出现不同哈希，就说明容器里确实有多份副本，
        // 而「挂了却不生效」正是挂在了不执行的那一份上。
        HookLogger.log("PlatformHook: 挂 " + name + " @" + System.identityHashCode(owner));
        if (SDK_CLASS.equals(name)) {
            sdkDone = true;
            if (hookReturnString(owner, "getCustomCpsChannel",
                    new Class<?>[]{Object[].class}, CHANNEL, "cps")) {
                channelHooked = true;
            }
            if (!forcePlatform) {
                // 光遇：三端由玩家自选，绝不动 getPlatform（动了就等于没得选）
                platformHooked = true;
                HookLogger.mark(HookLogger.KEY_PLATFORM, true, "skip:自选");
                HookLogger.log("PlatformHook: 不强制 getPlatform（三端由玩家自选）");
                return;
            }
            boolean ok = hookReturnString(owner, "getPlatform", NO_ARGS, platform, "platform");
            if (ok) {
                platformHooked = true;
                HookLogger.mark(HookLogger.KEY_PLATFORM, true, null);
                HookLogger.log("PlatformHook: SdkNetease.getPlatform -> " + platform);
            } else {
                DONE.remove(owner);
                sdkDone = false;
                HookLogger.mark(HookLogger.KEY_PLATFORM, false, "no-getPlatform");
            }
        } else if (APK_CHANNELING_CLASS.equals(name)) {
            apkDone = true;
            boolean ok = hookReturnString(owner, "getChannel",
                    new Class<?>[]{File.class}, CHANNEL, "apkch");
            markChannel(ok, "ApkChanneling");
        } else if (APP_CHANNEL_CLASS.equals(name)) {
            appDone = true;
            // getAppChannel 是 DeviceUtils 组包时真正取渠道的入口，getChannel 是另一条（渠道 id）
            boolean ok = hookReturnString(owner, "getChannel",
                    new Class<?>[]{Context.class}, CHANNEL, "appch");
            if (hookReturnString(owner, "getAppChannel",
                    new Class<?>[]{Context.class}, CHANNEL, "appch")) {
                ok = true;
            }
            markChannel(ok, "AppChannel");
        } else if (DEVICE_UTILS_CLASS.equals(name)) {
            deviceDone = true;
            boolean ok = seedAppChannel(owner);
            if (hookReturnString(owner, "getAppChannel",
                    new Class<?>[]{Context.class}, CHANNEL, "appch")) {
                ok = true;
            }
            markChannel(ok, "DeviceUtils");
        } else {
            DONE.remove(owner);
        }
    }

    private static void markChannel(boolean ok, String who) {
        if (ok) {
            channelHooked = true;
            HookLogger.mark(HookLogger.KEY_CHANNEL, true, null);
        }
        HookLogger.log("PlatformHook: " + who + ".getChannel -> " + CHANNEL + " ok=" + ok);
    }

    /**
     * 直接把 {@code DeviceUtils.appChannel} 这个静态缓存改成 MuMu 渠道。
     * <p>
     * 光 hook 方法在容器里有个时间窗问题：SDK 可能在我们挂上之前就把渠道读进缓存了，
     * 之后设备信息一律走缓存，再 hook 也没用。把缓存本身写掉，就和「启动时就是 MuMu」等价了。
     */
    private static boolean seedAppChannel(Class<?> owner) {
        try {
            Object before = XposedHelpers.getStaticObjectField(owner, "appChannel");
            XposedHelpers.setStaticObjectField(owner, "appChannel", CHANNEL);
            HookLogger.log("PlatformHook: DeviceUtils.appChannel " + before + " -> " + CHANNEL);
            return true;
        } catch (Throwable t) {
            HookLogger.log("PlatformHook: DeviceUtils.appChannel 改不动: " + t);
            return false;
        }
    }

    /**
     * 只在「方法存在且返回 String」时挂，返回值直接替换成 {@code value}。
     * <p>
     * 先按精确签名找；找不到再用一小串<b>候选参数表</b>逐个探测。
     * <p>
     * <b>绝不能用 {@code Class.getDeclaredMethods()} 遍历方法表。</b>1.18 试过一次：元萝卜里直接闪退——
     * 加固包的方法表里有被抽走的 native 方法，全表解析会触发它的完整性校验。
     * 而 {@code getDeclaredMethod(name, params)} 只按名字+参数查一个，没有就抛 NoSuchMethodException，
     * 不碰其它方法，在加固类上是安全的（1.9～1.17 一直这么用，从没崩过）。
     */
    private static boolean hookReturnString(Class<?> owner, String methodName,
                                            Class<?>[] paramTypes, final String value,
                                            final String hitKey) {
        try {
            Method method = resolve(owner, methodName, paramTypes);
            if (method != null && method.getReturnType() == String.class) {
                hookOne(method, value, hitKey);
                return true;
            }
            if (method != null) {
                HookLogger.log("PlatformHook: " + method.getDeclaringClass().getName() + "."
                        + methodName + " 返回 " + method.getReturnType().getSimpleName()
                        + "，改用候选签名探测");
            }
            Method fallback = probeSignatures(owner, methodName);
            if (fallback != null) {
                hookOne(fallback, value, hitKey);
                HookLogger.log("PlatformHook: " + owner.getName() + "." + methodName
                        + " 精确签名没命中，改用候选签名 "
                        + java.util.Arrays.toString(fallback.getParameterTypes()));
                return true;
            }
            HookLogger.log("PlatformHook: " + owner.getName() + " 没有可用的 " + methodName);
            return false;
        } catch (Throwable t) {
            HookLogger.log("PlatformHook: 挂 " + owner.getName() + "." + methodName + " 失败: " + t);
            return false;
        }
    }

    /** 逐个试一小串常见参数表，命中即返回。全程只用 getDeclaredMethod，不枚举方法表。 */
    private static Method probeSignatures(Class<?> owner, String methodName) {
        Class<?>[][] candidates = {
                new Class<?>[0],
                {Context.class},
                {File.class},
                {String.class},
                {Object[].class},
                {Context.class, String.class},
        };
        for (Class<?>[] params : candidates) {
            try {
                Method found = resolve(owner, methodName, params);
                if (found != null && found.getReturnType() == String.class) {
                    return found;
                }
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private static void hookOne(Method method, final String value, final String hitKey) {
        XposedBridge.hookMethod(method, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                param.setResult(value);
                Diag.hit(hitKey);
            }
        });
    }

    /** 先在本类找，再沿父类链找——有的包把实现放在 SdkBase 里。 */
    private static Method resolve(Class<?> owner, String methodName, Class<?>[] paramTypes) {
        Class<?> cursor = owner;
        while (cursor != null && cursor != Object.class) {
            try {
                return XposedHelpers.findMethodExact(cursor, methodName, paramTypes);
            } catch (Throwable ignored) {
            }
            try {
                Method declared = cursor.getDeclaredMethod(methodName, paramTypes);
                declared.setAccessible(true);
                return declared;
            } catch (Throwable ignored) {
            }
            cursor = cursor.getSuperclass();
        }
        return null;
    }
}
