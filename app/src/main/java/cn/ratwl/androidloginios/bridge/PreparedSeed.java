package cn.ratwl.androidloginios.bridge;

import android.net.Uri;

/** 已写入的准备文件信息；{@link #sdkPath} 必须是真实文件路径，MPay 不接受 content URI。 */
public final class PreparedSeed {
    public final String sdkPath;
    public final Uri cleanupUri;
    public final boolean usedSaf;

    public PreparedSeed(String sdkPath, Uri cleanupUri, boolean usedSaf) {
        this.sdkPath = sdkPath;
        this.cleanupUri = cleanupUri;
        this.usedSaf = usedSaf;
    }
}
