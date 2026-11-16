package cn.ratwl.androidloginios;

import android.content.Context;

/**
 * 模块自身信息与对外常量。
 */
public final class ModuleInfo {
    private ModuleInfo() {
    }

    /** 公众号 */
    public static final String WECHAT_PUBLIC_ACCOUNT = "CNRatwl";

    /** 开源仓库（GPL-3.0）。界面底部的 Github 入口指向这里。 */
    public static final String GITHUB_URL = "https://github.com/BUGAndANNs/NetEase-Android-Login-iOS";

    /** 模块自身版本名，读不到时返回 "--"。 */
    public static String versionName(Context context) {
        try {
            return context.getPackageManager()
                    .getPackageInfo(context.getPackageName(), 0).versionName;
        } catch (Throwable t) {
            return "--";
        }
    }
}
