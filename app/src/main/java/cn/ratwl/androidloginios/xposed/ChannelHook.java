package cn.ratwl.androidloginios.xposed;

import java.io.File;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * 渠道 / 平台伪装。
 * <p>
 * 老做法是 hook {@code Class.getDeclaredMethod} 后把结果<b>换成模块自己的静态方法</b>，
 * 好处是目标类不用先加载；坏处有两个，在免 ROOT 容器里都会致命：
 * <ul>
 *   <li>返回的是一个来自「模块 ClassLoader」的 Method，宿主要 {@code invoke} 它，
 *       跨 ClassLoader 的 Method 在 SHook / 容器里可能解析不到声明类（NoClassDefFoundError / IllegalAccessError）；</li>
 *   <li>{@code getDeclaredMethod} 是全进程最热的反射入口，容器自己（虚拟化、桩分发、SO 加载）
 *       和 Unity 运行时都重度依赖它，我们的回调会插进它们的每一次调用里。</li>
 * </ul>
 * 新做法：识别到 SDK 正在反射查找目标方法时，<b>就地把找到的那个 Method 挂上 hook</b>，
 * 然后把原方法原样交还给 SDK——不产生任何跨 ClassLoader 对象，SDK 的行为与没装模块时完全一致，
 * 只是返回值被改掉。同时加两道过滤：
 * <ol>
 *   <li>只处理 {@code com.netease.*} 下的类，Unity / AndroidX / Kotlin / 容器自身的反射一律放行；</li>
 *   <li>同一个方法只挂一次，重复查找直接跳过。</li>
 * </ol>
 */
public final class ChannelHook {

    private static final String CHANNEL = "netease.mumu2_free_dev";
    private static final String NET_EASE_PREFIX = "com.netease.";
    private static final String SDK_CLASS_PREFIX = "com.netease.ntunisdk.Sdk";
    private static final String APK_CHANNELING_CLASS =
            "com.netease.ntunisdk.base.utils.cps.ApkChanneling";

    private static final Set<String> PATCHED = Collections.synchronizedSet(new HashSet<String>());

    private static boolean installed;

    private ChannelHook() {
    }

    public static void install(ClassLoader classLoader) {
        if (installed) {
            return;
        }
        try {
            Method getDeclaredMethod = Class.class.getDeclaredMethod(
                    "getDeclaredMethod", String.class, Class[].class);
            Method getMethod = Class.class.getDeclaredMethod(
                    "getMethod", String.class, Class[].class);

            XC_MethodHook callback = new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(final MethodHookParam param) {
                    if (param.hasThrowable()) {
                        return;
                    }
                    HookGuard.safe("channel.reflect", new HookGuard.Body() {
                        @Override
                        public void run() {
                            inspect(param);
                        }
                    });
                }
            };

            XposedBridge.hookMethod(getDeclaredMethod, callback);
            XposedBridge.hookMethod(getMethod, callback);

            installed = true;
            HookLogger.mark(HookLogger.KEY_CHANNEL, true, null);
            HookLogger.log("ChannelHook 已安装（就地挂载，channel=" + CHANNEL + "）");
        } catch (Throwable t) {
            HookLogger.mark(HookLogger.KEY_CHANNEL, false, String.valueOf(t));
            HookLogger.log("ChannelHook 安装失败: " + t);
        }
    }

    private static void inspect(XC_MethodHook.MethodHookParam param) {
        Object self = param.thisObject;
        Object result = param.getResult();
        if (!(self instanceof Class) || !(result instanceof Method)) {
            return;
        }
        Method found = (Method) result;
        String owner = ((Class<?>) self).getName();
        // 只碰网易 SDK，别的一律放行（Unity / AndroidX / Kotlin / 容器自身）
        if (!owner.startsWith(NET_EASE_PREFIX)) {
            return;
        }
        if (found.getReturnType() != String.class) {
            return;
        }
        String name = found.getName();
        Class<?>[] types = found.getParameterTypes();
        Diag.hit("reflect");

        if ("getChannel".equals(name) && types.length == 1 && types[0] == File.class
                && APK_CHANNELING_CLASS.equals(owner)) {
            patch(found, CHANNEL);
        } else if ("getCustomCpsChannel".equals(name) && types.length == 1
                && types[0] == Object[].class && owner.startsWith(SDK_CLASS_PREFIX)) {
            patch(found, CHANNEL);
        } else if ("getPlatform".equals(name) && types.length == 0
                && owner.startsWith(SDK_CLASS_PREFIX)
                && PlatformHook.isForcePlatform()) {
            // 只有需要强制平台的目标（第五人格）才动它；
            // 光遇的三端是玩家自选，改这个值等于把选择作废
            patch(found, PlatformHook.currentPlatform());
        }
    }

    private static void patch(Method method, final String value) {
        String key = method.getDeclaringClass().getName() + "#" + method.getName()
                + "#" + method.getParameterTypes().length;
        if (!PATCHED.add(key)) {
            return;
        }
        try {
            XposedBridge.hookMethod(method, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    param.setResult(value);
                    Diag.hit("chan");
                }
            });
            HookLogger.log("已就地伪装: " + method.getDeclaringClass().getName()
                    + "." + method.getName() + " -> " + value);
            HookLogger.mark(HookLogger.KEY_CHANNEL, true, null);
        } catch (Throwable t) {
            // native 方法 / 被加固的方法可能挂不上，交给 PlatformHook 的直接 hook 兜底
            HookLogger.mark(HookLogger.KEY_CHANNEL, false, "patch-failed");
            HookLogger.log("就地伪装失败 " + method + ": " + t);
        }
    }

    /** 兼容旧调用：直接返回渠道名。 */
    public static String getChannel(File apk) {
        return CHANNEL;
    }

    /** 兼容旧调用：直接返回渠道名。 */
    public static String getCustomCpsChannel(Object[] arguments) {
        return CHANNEL;
    }
}
