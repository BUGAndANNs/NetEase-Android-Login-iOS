package cn.ratwl.androidloginios.bridge;

import java.io.IOException;

/** 共享目录授权缺失/失效，需要回到助手重新选择 Android/media 目录。 */
public class DirectoryAccessRequired extends IOException {
    public DirectoryAccessRequired() {
        super("directory-access-required");
    }
}
