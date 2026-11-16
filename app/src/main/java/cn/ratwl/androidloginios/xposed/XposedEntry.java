package cn.ratwl.androidloginios.xposed;

import cn.ratwl.androidloginios.core.DeviceEnv;
import cn.ratwl.androidloginios.core.HookConfig;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * 模块入口（assets/xposed_init 指向这里）。
 * <p>
 * 关于「门禁」的取舍，值得记一笔：
 * <ul>
 *   <li>面具 / LSPosed 里 {@code packageName} 就是游戏自己的包名，按 {@code com.netease.*} 过滤完全够用；</li>
 *   <li>免 ROOT 容器里不一样。实测 <b>Dual Space（com.xunijun.app.gp）</b> 的 guest 进程
 *       上报的 {@code packageName} 是<b>容器自己的包名</b>，不是 com.netease.sky，
 *       1.3 版之所以能在 Dual Space 里弹窗生效，纯粹是因为那一版「不做门禁、哪个进程都装」。</li>
 * </ul>
 * <p>
 * <b>本工程的口径分两套，按设备环境自动切换：</b>
 * <ul>
 *   <li><b>面具直连</b>（有面具 / root，<b>真机与模拟器都算</b>，见 {@link DeviceEnv#mode()}）：
 *       模块被直接注入游戏进程，此时按 {@code com.netease.*} 放行，<b>所有网易游戏都能用</b>
 *       ——这一段与 {@code Naptila/netease-mumu} 的做法一致（它压根不做门禁）。非游戏类网易应用
 *       在 {@link #NON_GAME_PACKAGES} 里排除掉，避免给网易云音乐之类改 {@code ro.build.id}。</li>
 *   <li><b>框架限定</b>（没有面具，含所有免 ROOT 容器）：只适配《光·遇》《第五人格》《猫和老鼠》
 *       三款，走三款精确包名 + 容器包名前缀两条路。</li>
 * </ul>
 * 容器前缀那一支<b>永远</b>走限定：宿主进程里 guest 包名被抹掉，一旦放大范围就等于
 * 给容器宿主的每个进程都装一遍 hook，得不偿失。
 *
 * <p><b>「全量」不等于「每款都做了适配」，这里必须分清：</b>全量放行时装进去的 hook 是两层——
 * <ul>
 *   <li><b>通用层</b>：{@link SystemHook} 的 {@code ro.build.id = V417IR} 与
 *       {@link ChannelHook} 的渠道反射。它<b>不依赖任何游戏内部字段</b>，只把运行环境改得像 MuMu，
 *       让游戏自己去判渠道。{@code netease-mumu} 的全部实现就是这一层，所以任何网易游戏都吃得到。</li>
 *   <li><b>逐游戏层</b>：{@link JsonHook} 的 {@code select_platform} 与 {@link PlatformHook}
 *       的平台值。这两处改的是<b>游戏内部的业务字段</b>，字段名、取值、所在类每款游戏都不同——
 *       光·遇是「自选三端」、第五人格是「PC」，别的游戏大概率对不上。对不上时类找不到就跳过、
 *       字段不存在就静默失败，<b>不崩、也不会误改</b>。</li>
 * </ul>
 * 所以「只适配三款」不是因为模块只能跑三款，而是因为<b>只有这三款的注入被实测确认过</b>（其中
 * 三端自选字段更是只有光·遇、第五人格两款；猫和老鼠靠登录桥 + 通用伪装生效）。
 * 而免 ROOT 容器里连通用层都不成立：guest 进程冒充不了 MuMu，且部分容器引擎在 Android 16/17 上
 * 整体失效——这才是容器必须走限定的原因。
 */
public class XposedEntry implements IXposedHookLoadPackage {

    /** 适配范围内的三款游戏。 */
    private static final String PKG_SKY = "com.netease.sky";   // 光·遇
    private static final String PKG_DWRG = "com.netease.dwrg"; // 第五人格
    private static final String PKG_TOM = "com.netease.tom";   // 猫和老鼠

    /** 面具直连模式下按这些前缀放行（网易游戏发行用的两个主前缀）。 */
    private static final String[] NETEASE_PREFIXES = {
            "com.netease.",
            "com.godlike."
    };

    /**
     * 这些是<b>网易的非游戏应用</b>，面具直连模式下也绝不注入。
     * <p>理由：模块会给目标进程改 {@code ro.build.id}，对音乐/新闻/邮箱这类应用没有任何收益，
     * 却平白改了它们的机型上报。命中即跳过，连 {@code bootMarker} 都不写。</p>
     */
    private static final String[] NON_GAME_PACKAGES = {
            "com.netease.cloudmusic",   // 网易云音乐
            "com.netease.newsreader",   // 网易新闻
            "com.netease.mail",         // 网易邮箱
            "com.netease.mobimail",
            "com.netease.yanxuan",      // 网易严选
            "com.netease.snailreader",  // 蜗牛读书
            "com.netease.uuremote",     // UU 远程
            "com.netease.mobistore",    // 应用汇（网易渠道）
            "com.netease.gl",           // 网易大神
            "com.netease.mkey",         // 将军令
            "com.netease.nim",          // 云信 SDK
            "com.netease.epay"
    };

    /** 实测 / 常见的免 ROOT 容器包名前缀（guest 进程会以这些包名上报）。 */
    private static final String[] CONTAINER_PREFIXES = {
            "com.xunijun.app",     // Dual Space / 虚拟君
            "top.bienvenido.",     // 元萝卜（SlimVXposed）
            "com.katana",          // SPatch 系
            "io.va.exposed",       // VirtualXposed
            "com.lbe.parallel",    // 平行空间
            "com.excelliance.dualaid",
            "com.polestar.domultiple",
            "com.parallel.space",
            "com.dual.space",
            "com.vphone"
    };

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (lpparam == null || lpparam.packageName == null) {
            return;
        }
        if ("android".equals(lpparam.packageName)) {
            return;
        }
        // 自己的 App 进程不注入，避免自伤
        if ("cn.ratwl.androidloginios".equals(lpparam.packageName)) {
            return;
        }
        // 运行模式：有面具 → 直连全量（真机 / 模拟器一视同仁）；没有面具 → 限定三款。
        // DeviceEnv 只看文件与 Build 字段，不会弹授权框、不会拖慢启动。
        final boolean direct = DeviceEnv.mode() == DeviceEnv.Mode.ROOT;
        if (!looksLikeTarget(lpparam, direct)) {
            return;
        }

        try {
            final String scope = scopeLabel(lpparam, direct);
            // 先落一份「注入证明」：此刻还没有 Context，只能靠包名拼路径。
            // 后面 Application hook 若在容器里完全没生效，这就是唯一能拿到的证据。
            Diag.bootMarker(lpparam.packageName, lpparam.processName);
            // 再排一条不依赖 Application 的兜底提示：6 秒内详细版没弹出来就由它顶上
            Diag.loadToast(lpparam.processName);
            Diag.note("注入 pkg=" + lpparam.packageName
                    + " proc=" + lpparam.processName
                    + " first=" + lpparam.isFirstApplication
                    + " loader=" + lpparam.classLoader
                    + " 模式=" + (direct ? "面具直连" : "框架限定")
                    + " 设备=" + DeviceEnv.isEmulator() + "/" + DeviceEnv.isRooted());

            if (HookFlags.DISABLE_ALL_HOOKS) {
                // 空白诊断包：只证明「模块被加载进了这个进程」，一个 Hook 都不装
                HookLogger.log("空白诊断模式：不安装任何 Hook");
                AppLifecycle.install(lpparam.classLoader,
                        banner(lpparam, scope) + "\n空白诊断包（无 Hook）",
                        canToast(lpparam), lpparam.processName);
                return;
            }

            // 界面上的两个功能开关。XSharedPreferences 在容器里往往读不到，
            // HookConfig 内部会回落到「全开」，所以不会因为读配置失败而什么都不装。
            final boolean wantSysProp = HookConfig.enableSysProp();
            final boolean wantChannel = HookConfig.enableChannel();

            if (HookFlags.DISABLE_SYSTEM_HOOK || !wantSysProp) {
                HookLogger.log("跳过 SystemHook（ro.build.id）："
                        + (wantSysProp ? "诊断开关" : "界面开关") + "关闭");
                HookLogger.mark(HookLogger.KEY_SYSTEM, false, "off");
            } else {
                SystemHook.install(lpparam.classLoader);
            }

            if (HookFlags.DISABLE_JSON_HOOK) {
                HookLogger.log("诊断模式：已跳过 JsonHook（三端字段）");
                HookLogger.mark(HookLogger.KEY_JSON, false, "diagnostic-off");
            } else {
                JsonHook.install(lpparam.classLoader);
            }

            // 渠道整层（PlatformHook + 反射代理 + 轮询）受同一个开关控制
            PlatformHook.setEnabled(wantChannel);

            if (HookFlags.DISABLE_REFLECT_HOOK || !wantChannel) {
                HookLogger.log("跳过 ChannelHook（反射 hook）："
                        + (wantChannel ? "诊断开关" : "界面开关") + "关闭");
                HookLogger.mark(HookLogger.KEY_CHANNEL, false, "off");
            } else {
                ChannelHook.install(lpparam.classLoader);
            }

            // 平台/渠道的 Hook 不在这里挂：这个时刻加固包还没解密，强拉类会让容器崩。
            // 只记下「这个包该用哪个平台值」，真正的挂载在 Application.attachBaseContext 之后。
            PlatformHook.configure(targetProbe(lpparam), HookFlags.FORCE_PLATFORM);

            // 容器（元萝卜）里 {@code Application.attachBaseContext} 那层 hook 可能压根进不了回调，
            // 一旦如此，attachBaseContext 里的安装就永远不会执行——表现正是「不崩、也没效果」。
            // 这里直接从注入点起一条「只查已加载类、绝不触发加载」的轮询兜底，
            // 不依赖任何系统类的 hook：SDK 的类一露头就挂上。
            if (wantChannel) {
                ClassWatcher.start(lpparam.classLoader);
            }

            // 提示文案在 onCreate 时才拼（那时各 Hook 的结果已经确定，能直接看到 平台=OK / FAIL）
            AppLifecycle.install(lpparam.classLoader, banner(lpparam, scope),
                    canToast(lpparam), lpparam.processName);
        } catch (Throwable t) {
            // 入口本身绝不向外抛异常，否则宿主直接起不来。
            // 带栈：容器里的失败大多是「类解析到了宿主那一份」，只看异常类型判断不出来。
            HookLogger.log("注入过程异常: " + t);
            try {
                java.io.StringWriter sw = new java.io.StringWriter();
                t.printStackTrace(new java.io.PrintWriter(sw));
                for (String line : sw.toString().split("\n")) {
                    if (line.trim().isEmpty()) {
                        continue;
                    }
                    HookLogger.log("  at " + line.trim());
                }
            } catch (Throwable ignored) {
            }
        }
    }

    /**
     * 提示开关：现在<b>一律开着</b>，去重交给 {@link AppLifecycle}。
     * <p>
     * 早先这里是「子进程不弹」，本意是躲掉容器里一串重复 Toast。但元萝卜（SlimVXposed）的 guest
     * 进程名带冒号（实测 {@code …:p0}），于是它<b>永远</b>被判成子进程 —— 不崩、也不弹、文件又写进
     * 容器沙箱，等于完全没有信号，只能靠猜。这轮改成按「进程名 + 状态」各弹一次：
     * 一个游戏 3～4 个进程各一次就结束，既不会刷屏，也保证每个进程都能自证「模块进来了」。
     */
    private static boolean canToast(XC_LoadPackage.LoadPackageParam lpparam) {
        return true;
    }

    private static String banner(XC_LoadPackage.LoadPackageParam lpparam, String scope) {
        return "WY 模块已注入\n" + lpparam.packageName + "\n" + scope;
    }

    /** 这一进程是走「全量」还是「限定」，放在提示里方便一眼看出走对了没有。 */
    private static String scopeLabel(XC_LoadPackage.LoadPackageParam lpparam, boolean direct) {
        if (direct && isNeteaseGame(lpparam.packageName)) {
            return "面具直连 · 网易游戏全量";
        }
        if (isContainerHost(lpparam)) {
            return "框架限定 · 容器宿主";
        }
        return "框架限定 · 三款适配";
    }

    private static boolean looksLikeTarget(XC_LoadPackage.LoadPackageParam lpparam,
                                          boolean direct) {
        String pkg = lpparam.packageName;
        String proc = lpparam.processName == null ? "" : lpparam.processName;
        // 只认这三款（框架 / LSPatch 下走这条；猫和老鼠 v5.21 双路实测通过）
        if (PKG_SKY.equals(pkg) || PKG_DWRG.equals(pkg) || PKG_TOM.equals(pkg)
                || proc.startsWith(PKG_SKY) || proc.startsWith(PKG_DWRG)
                || proc.startsWith(PKG_TOM)) {
            return true;
        }
        // 容器里 guest 进程上报的是容器包名，只能按前缀放行。
        // 这一支<b>不参与全量放行</b>：容器宿主进程装着所有 guest，范围一放大就会污染其他应用。
        for (String prefix : CONTAINER_PREFIXES) {
            if (pkg.startsWith(prefix) || proc.startsWith(prefix)) {
                return true;
            }
        }
        // 面具直连：有面具时按网易游戏包名放行（与 netease-mumu 的「不设门禁」等价，
        // 这里多做了两层过滤：只认网易游戏前缀 + 显式排除非游戏类网易应用）
        return direct && isNeteaseGame(pkg);
    }

    private static boolean isContainerHost(XC_LoadPackage.LoadPackageParam lpparam) {
        String pkg = lpparam.packageName == null ? "" : lpparam.packageName;
        String proc = lpparam.processName == null ? "" : lpparam.processName;
        for (String prefix : CONTAINER_PREFIXES) {
            if (pkg.startsWith(prefix) || proc.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    /** 这个包名是不是「网易游戏」（直连模式下才用）。 */
    private static boolean isNeteaseGame(String pkg) {
        if (pkg == null || pkg.isEmpty()) {
            return false;
        }
        for (String skip : NON_GAME_PACKAGES) {
            if (pkg.equals(skip) || pkg.startsWith(skip + ".")) {
                return false;
            }
        }
        for (String prefix : NETEASE_PREFIXES) {
            if (pkg.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 拼一个尽量能露出「这是哪款游戏」的探测串。
     *
     * <p>容器里 {@code packageName} / {@code processName} 往往是容器自己的，真正带游戏包名的是
     * {@code appInfo.packageName} 和 {@code appInfo.sourceDir}（APK 的安装路径），
     * 所以这里把它们一起拼进去，交给 {@link PlatformHook#configure} 判游戏。</p>
     */
    private static String targetProbe(XC_LoadPackage.LoadPackageParam lpparam) {
        StringBuilder sb = new StringBuilder();
        sb.append(lpparam.packageName == null ? "" : lpparam.packageName);
        sb.append('|').append(lpparam.processName == null ? "" : lpparam.processName);
        try {
            if (lpparam.appInfo != null) {
                sb.append('|').append(lpparam.appInfo.packageName == null ? ""
                        : lpparam.appInfo.packageName);
                sb.append('|').append(lpparam.appInfo.sourceDir == null ? ""
                        : lpparam.appInfo.sourceDir);
                sb.append('|').append(lpparam.appInfo.nativeLibraryDir == null ? ""
                        : lpparam.appInfo.nativeLibraryDir);
            }
        } catch (Throwable ignored) {
        }
        return sb.toString();
    }
}
