package cn.ratwl.androidloginios.core;

/**
 * 模块开关的键名与写入入口。
 *
 * <p><b>只放常量与普通 SharedPreferences 访问</b>：这个类会被模块自身的界面使用，
 * 因此绝不能引用只有 Xposed 框架才提供的类，否则不装框架直接打开模块会崩溃。
 * 宿主进程的读取逻辑在 {@link HookConfig}。</p>
 */
public final class ModuleConfig {
    public static final String MODULE_PACKAGE = "cn.ratwl.androidloginios";
    public static final String PREFS_FILE = "module";

    /** MuMu 渠道：渠道固定为 netease.mumu2_free_dev。 */
    public static final String KEY_CHANNEL = "hook_channel";
    /** MuMu 设备标识：SystemProperties.get(ro.build.id) → V417IR。 */
    public static final String KEY_SYSPROP = "hook_sysprop";

    private ModuleConfig() {
    }

    /** 模块进程内的配置读写句柄。 */
    public static android.content.SharedPreferences editable(android.content.Context context) {
        return context.getSharedPreferences(PREFS_FILE, android.content.Context.MODE_PRIVATE);
    }
}
