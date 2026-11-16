package cn.ratwl.androidloginios.xposed;

/**
 * 诊断开关。
 *
 * <p>1.19 里这些是 Gradle 的 {@code BuildConfig} 字段（{@code -PwyForcePlatform=true}
 * 之类），但本工程关掉了 {@code buildFeatures.buildConfig}，所以改成常量。
 * 需要做对照实验时直接改这里的值重新打包即可。</p>
 */
final class HookFlags {

    /** 空白诊断：只证明「模块进了这个进程」，一个 Hook 都不装。 */
    static final boolean DISABLE_ALL_HOOKS = false;

    /** 跳过 {@code ro.build.id} 伪装。 */
    static final boolean DISABLE_SYSTEM_HOOK = false;

    /** 跳过登录响应里的三端开关（{@code select_platform} / {@code select_platforms}）。 */
    static final boolean DISABLE_JSON_HOOK = false;

    /** 跳过 {@code Class.getDeclaredMethod} 反射代理。 */
    static final boolean DISABLE_REFLECT_HOOK = false;

    /**
     * 强制平台（对照开关）。
     *
     * <p>默认 {@code false}：光遇<b>不</b> hook {@code getPlatform}，三端由玩家自选；
     * 第五人格由 {@link PlatformHook#configure} 内部按包名单独强制，不受这里影响。</p>
     */
    static final boolean FORCE_PLATFORM = false;

    private HookFlags() {
    }
}
