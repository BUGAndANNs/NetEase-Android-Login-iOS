package cn.ratwl.androidloginios.xposed;

import org.json.JSONArray;
import org.json.JSONObject;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;

/**
 * 结果层伪装：把登录响应里的三端选择相关字段强制打开。
 * <p>
 * select_platform → true；select_platforms → [1, 2, 8]（1=Android、2=iOS、8=模拟器）。
 * 与 Naptila/netease-mumu 的「老 HOOK 方案」一致。
 */
public final class JsonHook {

    private static final String FIELD_PLATFORM = "select_platform";
    private static final String FIELD_PLATFORMS = "select_platforms";

    private static boolean installed;
    private static boolean anyOk;

    private JsonHook() {
    }

    public static void install(ClassLoader classLoader) {
        if (installed) {
            return;
        }
        anyOk = false;
        try {
            Class<?> jsonObject = XposedHelpers.findClass("org.json.JSONObject", classLoader);

            XC_MethodHook booleanCallback = new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(final MethodHookParam param) {
                    if (param.args == null || param.args.length < 1) {
                        return;
                    }
                    if (!FIELD_PLATFORM.equals(String.valueOf(param.args[0]))) {
                        return;
                    }
                    HookGuard.safe("json.select_platform", new HookGuard.Body() {
                        @Override
                        public void run() {
                            param.setResult(Boolean.TRUE);
                            Diag.hit("json");
                        }
                    });
                }
            };
            XC_MethodHook arrayCallback = new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(final MethodHookParam param) {
                    if (param.args == null || param.args.length < 1) {
                        return;
                    }
                    if (!FIELD_PLATFORMS.equals(String.valueOf(param.args[0]))) {
                        return;
                    }
                    HookGuard.safe("json.select_platforms", new HookGuard.Body() {
                        @Override
                        public void run() {
                            JSONArray array = new JSONArray();
                            array.put(1);
                            array.put(2);
                            array.put(8);
                            param.setResult(array);
                            Diag.hit("json");
                        }
                    });
                }
            };

            hookQuietly(jsonObject, "optBoolean", new Object[]{String.class, boolean.class, booleanCallback});
            hookQuietly(jsonObject, "optBoolean", new Object[]{String.class, booleanCallback});
            hookQuietly(jsonObject, "getBoolean", new Object[]{String.class, booleanCallback});
            hookQuietly(jsonObject, "optJSONArray", new Object[]{String.class, arrayCallback});

            installed = true;
            HookLogger.mark(HookLogger.KEY_JSON, anyOk, anyOk ? null : "no-method");
            HookLogger.log("JsonHook 已安装, anyOk=" + anyOk);
        } catch (Throwable t) {
            HookLogger.mark(HookLogger.KEY_JSON, false, String.valueOf(t));
            HookLogger.log("JsonHook 安装失败: " + t);
        }
    }

    private static boolean mpayHooked;
    /**
     * 每个 ClassLoader 一个等待线程。
     * <p>
     * 元萝卜里第一个 loader 只带 {@code base.apk}，{@code mpay.*} 在加固壳后来建的那个 loader 里。
     * 原来用单个布尔量去重，导致「第一个 loader 等不到就把名额占死」，第二个 loader 进来直接被丢掉——
     * 表现正是「渠道全 ok、登录页却不出三端」。
     */
    private static final java.util.Set<ClassLoader> MPAY_WAITING =
            java.util.Collections.synchronizedSet(
                    java.util.Collections.newSetFromMap(
                            new java.util.WeakHashMap<ClassLoader, Boolean>()));
    private static final String MPAY_HELPER_CLASS = "com.netease.mpay.server.request.k";

    /**
     * 二级兜底：{@code org.json.JSONObject} 在引导类路径上，容器（元萝卜的 SHook）里未必挂得上；
     * 而 SDK 自己解析登录响应时用的是 {@code com.netease.mpay.server.request.k.i(JSONObject, String)}，
     * 这是 <b>应用自己的 dex</b> 里的方法，容器对它的 hook 支持要好得多。
     * <p>
     * 这两个位置是同一件事：登录响应里的 {@code select_platforms} 决定三端选项，谁先被调用就由谁改。
     * <b>不强拉这个类</b>——它也在加固 dex 里，强拉会让元萝卜的桩 ClassLoader 崩；
     * 改成等它被宿主自己加载出来（{@code findLoadedClass} 轮询），登录前挂上就够。
     */
    public static void installMpayHelper(ClassLoader classLoader) {
        if (mpayHooked || classLoader == null) {
            return;
        }
        Class<?> helper = ClassWatcher.loaded(classLoader, MPAY_HELPER_CLASS);
        if (helper != null) {
            hookMpay(helper);
            return;
        }
        if (!MPAY_WAITING.add(classLoader)) {
            return;
        }
        Diag.note("JsonHook: 开始等 mpay 类 loader=" + classLoader.getClass().getName());
        try {
            Thread thread = new Thread(new Runnable() {
                @Override
                public void run() {
                    for (int i = 0; i < 120 && !mpayHooked; i++) {
                        try {
                            Thread.sleep(500L);
                        } catch (InterruptedException ignored) {
                            return;
                        }
                        Class<?> found = ClassWatcher.loaded(classLoader, MPAY_HELPER_CLASS);
                        if (found != null) {
                            hookMpay(found);
                            return;
                        }
                    }
                }
            }, "wy-mpay-wait");
            thread.setDaemon(true);
            thread.start();
        } catch (Throwable t) {
            Diag.note("JsonHook: mpay 等待线程启动失败 " + t.getClass().getSimpleName());
        }
    }

    private static void hookMpay(Class<?> helper) {
        if (mpayHooked) {
            return;
        }
        try {
            XposedHelpers.findAndHookMethod(helper, "i", JSONObject.class, String.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (param.args == null || param.args.length < 2) {
                                return;
                            }
                            if (!FIELD_PLATFORMS.equals(String.valueOf(param.args[1]))) {
                                return;
                            }
                            HookGuard.safe("json.mpay.select_platforms", new HookGuard.Body() {
                                @Override
                                public void run() {
                                    JSONArray array = new JSONArray();
                                    array.put(1);
                                    array.put(2);
                                    array.put(8);
                                    param.setResult(array);
                                    Diag.hit("mpay");
                                }
                            });
                        }
                    });
            mpayHooked = true;
            Diag.note("JsonHook: mpay.server.request.k.i 已挂上（应用层兜底）");
        } catch (Throwable t) {
            Diag.note("JsonHook: mpay k.i 跳过（" + t.getClass().getSimpleName() + "）");
        }
    }

    private static void hookQuietly(Class<?> owner, String name, Object[] parameterTypesAndCallback) {
        try {
            XposedHelpers.findAndHookMethod(owner, name, parameterTypesAndCallback);
            anyOk = true;
        } catch (Throwable t) {
            HookLogger.log("hook " + owner.getSimpleName() + "." + name + " 跳过: " + t);
        }
    }
}
