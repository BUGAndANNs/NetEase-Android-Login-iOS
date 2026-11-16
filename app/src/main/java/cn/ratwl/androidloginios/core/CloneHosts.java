package cn.ratwl.androidloginios.core;

/**
 * 多开 / 虚拟空间宿主白名单。
 *
 * <p>沙盒类框架（VirtualApp 系、Dual Space 之类）会把游戏跑在<b>自己的进程</b>里，
 * 此时模块拿到的进程包名是宿主（如 {@code com.xunijun.app.gp}）而不是
 * {@code com.netease.sky}，包名白名单会因此把整个沙盒挡在外面。</p>
 *
 * <p>这里只放「已知会把游戏装进自己进程」的宿主包名，命中后仍会再做一次
 * 「本进程是否真的加载了网易 UniSDK」的探测，不会污染宿主自身界面进程。</p>
 */
public final class CloneHosts {

    private static final String[] HOSTS = {
            "com.xunijun.app.gp",          // Dual Space: Multi App Clone
            "top.bienvenido.saas.i18n",    // 元萝卜（Katana 框架版）
            "com.senserobot.all",          // 元萝卜虚拟助手（新版）
            "com.qihoo.magic",             // 双开大师
            "com.lbe.dual.space",
            "com.lbe.parallel.intl",
            "com.parallel.space.lite",
            "com.excelliance.dualaid",
            "com.excelliance.multiaccount",
            "io.va.exposed",
    };

    private CloneHosts() {
    }

    /** 是否为已知的多开 / 虚拟空间宿主。 */
    public static boolean isHost(String packageName) {
        if (packageName == null || packageName.isEmpty()) {
            return false;
        }
        for (String host : HOSTS) {
            if (host.equals(packageName)) {
                return true;
            }
        }
        return false;
    }
}
