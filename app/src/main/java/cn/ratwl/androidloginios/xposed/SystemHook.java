package cn.ratwl.androidloginios.xposed;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;

/**
 * 机型伪装：把 ro.build.id 改成 MuMu 模拟器的 V417IR（W528JS 也可）。
 * 与 Naptila/netease-mumu 的做法一致。
 */
public final class SystemHook {

    private static final String BUILD_ID = "V417IR";
    private static final String KEY = "ro.build.id";

    private static boolean installed;

    private SystemHook() {
    }

    public static void install(ClassLoader classLoader) {
        if (installed) {
            return;
        }
        try {
            Class<?> cls = XposedHelpers.findClass("android.os.SystemProperties", classLoader);

            XC_MethodHook callback = new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    HookGuard.safe("system.buildId", new HookGuard.Body() {
                        @Override
                        public void run() {
                            if (param.args == null || param.args.length < 1 || param.args[0] == null) {
                                return;
                            }
                            if (KEY.equals(String.valueOf(param.args[0]))) {
                                param.setResult(BUILD_ID);
                                Diag.hit("system");
                            }
                        }
                    });
                }
            };

            boolean twoArg = true;
            try {
                XposedHelpers.findAndHookMethod(cls, "get", String.class, String.class, callback);
            } catch (Throwable t) {
                twoArg = false;
                HookLogger.log("SystemProperties.get(String,String) 不可用: " + t);
            }
            try {
                XposedHelpers.findAndHookMethod(cls, "get", String.class, callback);
            } catch (Throwable t) {
                if (!twoArg) {
                    throw t;
                }
                HookLogger.log("SystemProperties.get(String) 不可用: " + t);
            }

            installed = true;
            HookLogger.mark(HookLogger.KEY_SYSTEM, true, null);
            HookLogger.log("SystemHook 已安装");
            // 兜底：SDK 的 native 层可能绕过 Java 的 SystemProperties 直接读字段
            Diag.note("SystemHook: Build.ID 直改 -> " + spoofBuildField());
        } catch (Throwable t) {
            HookLogger.mark(HookLogger.KEY_SYSTEM, false, String.valueOf(t));
            HookLogger.log("SystemHook 安装失败: " + t);
        }
    }

    /**
     * 除了 hook 属性读取，再把 {@code android.os.Build.ID} 这个静态字段直接改掉。
     * <p>
     * 原因：SDK 的机型信息很大一部分在 native 层采集，native 走 JNI 读静态字段时不会经过
     * Java 方法，hook {@code SystemProperties.get} 对它无效；而 JNI 读字段是运行时解析的，
     * 先改掉字段值就能被 native 读到。改不动（部分 ROM 的 static final）也不影响主路径。
     */
    private static String spoofBuildField() {
        try {
            java.lang.reflect.Field field = android.os.Build.class.getField("ID");
            field.setAccessible(true);
            try {
                field.set(null, BUILD_ID);
                return "ok:" + field.get(null);
            } catch (Throwable first) {
                try {
                    java.lang.reflect.Field modifiers =
                            java.lang.reflect.Field.class.getDeclaredField("modifiers");
                    modifiers.setAccessible(true);
                    modifiers.setInt(field, field.getModifiers()
                            & ~java.lang.reflect.Modifier.FINAL);
                    field.set(null, BUILD_ID);
                    return "ok2:" + field.get(null);
                } catch (Throwable second) {
                    return "fail:" + first.getClass().getSimpleName();
                }
            }
        } catch (Throwable t) {
            return "fail:" + t.getClass().getSimpleName();
        }
    }

    /** 供调试：直接读当前伪装值。 */
    public static String spoofedBuildId() {
        return BUILD_ID;
    }
}
