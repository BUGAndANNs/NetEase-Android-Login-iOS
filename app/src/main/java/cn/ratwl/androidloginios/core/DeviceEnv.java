package cn.ratwl.androidloginios.core;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;

import java.io.File;
import java.util.Locale;

/**
 * 设备环境探测：真机 / 模拟器、有没有面具（root）。
 *
 * <p>用途只有一个：决定模块<b>走哪一套门禁</b>。</p>
 * <ul>
 *   <li><b>面具直连</b>（有面具 / root，<b>真机与模拟器都算</b>）：模块被 LSPosed 直接注入
 *       游戏进程，此时按 {@code com.netease.*} 放行，所有网易游戏都能用，与
 *       {@code Naptila/netease-mumu} 的做法一致；</li>
 *   <li><b>框架限定</b>（没有面具，含所有免 ROOT 容器）：只认已验证的三款游戏。</li>
 * </ul>
 *
 * <p><b>为什么不排除模拟器：</b>早先这里多写过一条 {@code !isEmulator()}，理由是「在 MuMu 里
 * 冒充 MuMu 没意义」。但这个理由只在 MuMu 本体上成立——雷电、BlueStacks、AOSP 这些模拟器跑的
 * 仍是安卓版游戏、上报的仍是安卓渠道，冒充 MuMu 一样能放出三端选择；给模拟器装 Magisk +
 * LSPosed 也本来就是常见玩法。所以判据只留一条：<b>有没有面具</b>。模拟器信息仍会被
 * {@link #describe} 显示出来，但只作提示、不参与分流。</p>
 *
 * <p><b>为什么要分两套口径：</b>{@link #mode()} 会被注入进游戏进程的 hook 层调用，所以它
 * 只看文件系统与 {@code Build} 字段——绝不 {@code exec}、绝不碰 {@code Context}、绝不弹授权框。
 * 界面那边用 {@link #mode(Context)}，额外再看一眼面具管理器包名（有
 * {@code QUERY_ALL_PACKAGES} 权限，能查到）。</p>
 *
 * <p><b>误判的代价是可控的：</b>面具判定只会「漏判」不会「误伤」——容器宿主进程在
 * {@code XposedEntry} 里优先按容器前缀走严格门禁，不参与这里的放行。</p>
 */
public final class DeviceEnv {

    /** 模块的运行模式。 */
    public enum Mode {
        /** 有面具：直连注入，按网易游戏包名全量放行（真机 / 模拟器都一样）。 */
        ROOT("面具直连", "有面具，所有网易游戏都可用"),
        /** 未检测到面具：走免 ROOT 框架，只对已验证的三款游戏注入。 */
        FRAMEWORK("框架限定", "未检测到面具，仅《光·遇》《第五人格》《猫和老鼠》生效");

        public final String label;
        public final String desc;

        Mode(String label, String desc) {
            this.label = label;
            this.desc = desc;
        }
    }

    /** su 常见落点。 */
    private static final String[] SU_PATHS = {
            "/system/bin/su",
            "/system/xbin/su",
            "/sbin/su",
            "/su/bin/su",
            "/system/sd/xbin/su",
            "/system/bin/failsafe/su",
            "/system/xbin/daemonsu",
            "/data/local/su",
            "/data/local/bin/su",
            "/data/local/xbin/su",
            "/debug_ramdisk/su"
    };

    /** 面具 / KernelSU / APatch 的目录落点。 */
    private static final String[] ROOT_PATHS = {
            "/data/adb/magisk",
            "/data/adb/magisk.db",
            "/data/adb/modules",
            "/sbin/.magisk",
            "/dev/.magisk",
            "/debug_ramdisk/.magisk",
            "/data/adb/ksu",
            "/data/adb/ap",
            "/data/adb/apd"
    };

    /** 面具管理器包名（界面侧判定用；模块侧没有 Context，不查这个）。 */
    private static final String[] MANAGER_PACKAGES = {
            "com.topjohnwu.magisk",          // Magisk 官方
            "io.github.huskydg.magisk",      // Magisk Delta
            "io.github.vvb2060.magisk",      // Magisk Alpha
            "me.weishu.kernelsu",            // KernelSU
            "me.bmax.apatch",                // APatch
            "com.kingroot.kinguser",
            "eu.chainfire.supersu",
            "com.koushikdutta.superuser"
    };

    /** 命中即判为模拟器（子串匹配）。 */
    private static final String[] EMULATOR_HINTS = {
            "goldfish", "ranchu", "vbox86", "genymotion", "bluestacks",
            "mumu", "nox", "ldplayer", "memu", "andy_", "android sdk built for",
            "droid4x", "tiantian", "microvirt", "emulator"
    };

    /** 整值相等才判为模拟器（避免误伤把 brand 写成 unknown 的杂牌真机）。 */
    private static final String[] EMULATOR_EXACT = {
            "generic", "generic_x86", "google_sdk", "sdk", "sdk_x86",
            "sdk_gphone", "emulator", "unknown"
    };

    private static volatile Boolean sRooted;
    private static volatile Boolean sEmulator;
    private static volatile String sRootHint = "";

    private DeviceEnv() {
    }

    /** 有没有 root / 面具痕迹（只看文件与 PATH，注入进程里也能安全调用）。 */
    public static boolean isRooted() {
        Boolean cached = sRooted;
        if (cached != null) {
            return cached;
        }
        boolean rooted = false;
        String hint = "";
        for (String path : SU_PATHS) {
            if (exists(path)) {
                rooted = true;
                hint = "su " + path;
                break;
            }
        }
        if (!rooted) {
            for (String path : ROOT_PATHS) {
                if (exists(path)) {
                    // /data/adb 对普通应用是 700，无 root 时 exists() 直接 false，
                    // 所以这里命中基本就意味着本体确实在。
                    rooted = true;
                    hint = "adb " + path;
                    break;
                }
            }
        }
        if (!rooted) {
            String env = getenvPath();
            if (env.contains("magisk") || env.contains("/sbin")) {
                rooted = true;
                hint = "PATH";
            }
        }
        sRootHint = hint;
        sRooted = rooted;
        return rooted;
    }

    /** 是否运行在模拟器里（MuMu、雷电、BlueStacks、AOSP 模拟器等）。 */
    public static boolean isEmulator() {
        Boolean cached = sEmulator;
        if (cached != null) {
            return cached;
        }
        boolean emulator = x86Abi()
                || looksLikeEmulator(Build.FINGERPRINT)
                || looksLikeEmulator(Build.MODEL)
                || looksLikeEmulator(Build.HARDWARE)
                || looksLikeEmulator(Build.PRODUCT)
                || looksLikeEmulator(Build.BRAND)
                || looksLikeEmulator(Build.MANUFACTURER)
                || looksLikeEmulator(Build.DEVICE)
                || exactEmulator(Build.PRODUCT)
                || exactEmulator(Build.DEVICE)
                || exists("/dev/qemu_pipe")
                || exists("/dev/goldfish_pipe")
                || exists("/system/lib/libc_malloc_debug_qemu.so")
                || exists("/sys/qemu_trace");
        sEmulator = emulator;
        return emulator;
    }

    /**
     * CPU 架构是不是 x86。
     *
     * <p><b>这是判模拟器最硬的一条：</b>现在的 PC 模拟器（MuMu 12、雷电、BlueStacks）默认都会伪装
     * 机型——把 {@code ro.product.model/brand/manufacturer} 改成红米、把 {@code ro.build.id}
     * 直接写成 {@code V417IR}，所以靠 {@code Build} 字段判断会一律漏判。但 ABI 很难伪装：
     * 宿主 CPU 是 x86_64，同时又要能跑 arm 的 so，于是上报
     * {@code ["x86_64","arm64-v8a","x86",...]} 这种混合表——真机不会出现 x86。</p>
     */
    private static boolean x86Abi() {
        try {
            String[] abis = Build.SUPPORTED_ABIS;
            if (abis != null) {
                for (String abi : abis) {
                    if (isX86(abi)) {
                        return true;
                    }
                }
            }
            if (isX86(Build.CPU_ABI)) {
                return true;
            }
            String[] abis32 = Build.SUPPORTED_32_BIT_ABIS;
            if (abis32 != null) {
                for (String abi : abis32) {
                    if (isX86(abi)) {
                        return true;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private static boolean isX86(String abi) {
        return abi != null && (abi.startsWith("x86") || abi.startsWith("i386") || abi.startsWith("i686"));
    }

    /** 当前该走哪套门禁（注入进程用；不查面具管理器包名）。 */
    public static Mode mode() {
        return mode(null);
    }

    /** 当前该走哪套门禁；有 {@code Context} 时把「装了面具管理器」也算作 root 证据。 */
    public static Mode mode(Context context) {
        boolean rooted = isRooted() || hasManagerApp(context);
        // 判据只有一条：有没有面具。真机、模拟器一视同仁——模拟器里跑的是安卓版游戏，
        // 冒充 MuMu 一样能放出三端选择；容器宿主由 XposedEntry 前置拦截，不会走到这里的放行。
        return rooted ? Mode.ROOT : Mode.FRAMEWORK;
    }

    /** 面具管理器是否装着（需要包可见性；本工程有 QUERY_ALL_PACKAGES）。 */
    public static boolean hasManagerApp(Context context) {
        if (context == null) {
            return false;
        }
        try {
            PackageManager pm = context.getPackageManager();
            for (String pkg : MANAGER_PACKAGES) {
                try {
                    pm.getPackageInfo(pkg, 0);
                    return true;
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    /** 一行环境描述，给界面显示（短，别把路径塞进来，卡片宽度不够会折行）。 */
    public static String describe(Context context) {
        StringBuilder sb = new StringBuilder();
        sb.append(isEmulator() ? "模拟器" : "真机");
        sb.append(isRooted() || hasManagerApp(context) ? " · 已检测到面具" : " · 无面具");
        return sb.toString();
    }

    /** 为什么落到这个模式，一句话说明（界面显示用）。 */
    public static String explain(Context context) {
        boolean rooted = isRooted() || hasManagerApp(context);
        if (rooted) {
            return isEmulator()
                    ? "模拟器 + 面具：网易游戏全量放行"
                    : "真机 + 面具：网易游戏全量放行";
        }
        return isEmulator() ? "模拟器但无面具：只认三款" : "未检测到面具：只认三款";
    }

    /** 调试用：把判定依据全打出来。 */
    public static String debugInfo(Context context) {
        return "root=" + isRooted() + "(" + sRootHint + ")"
                + " manager=" + hasManagerApp(context)
                + " emulator=" + isEmulator()
                + " mode=" + mode(context).name();
    }

    // ------------------------------------------------------------------

    private static boolean exists(String path) {
        try {
            return new File(path).exists();
        } catch (Throwable t) {
            return false;
        }
    }

    private static String getenvPath() {
        try {
            String path = System.getenv("PATH");
            return path == null ? "" : path.toLowerCase(Locale.US);
        } catch (Throwable t) {
            return "";
        }
    }

    private static boolean looksLikeEmulator(String value) {
        if (value == null || value.isEmpty()) {
            return false;
        }
        String lower = value.toLowerCase(Locale.US);
        for (String hint : EMULATOR_HINTS) {
            if (lower.contains(hint)) {
                return true;
            }
        }
        return false;
    }

    private static boolean exactEmulator(String value) {
        if (value == null || value.isEmpty()) {
            return false;
        }
        String lower = value.trim().toLowerCase(Locale.US);
        for (String hint : EMULATOR_EXACT) {
            if (lower.equals(hint)) {
                return true;
            }
        }
        return false;
    }
}
