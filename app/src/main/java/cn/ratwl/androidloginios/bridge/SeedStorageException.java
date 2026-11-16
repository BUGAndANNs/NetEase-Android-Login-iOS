package cn.ratwl.androidloginios.bridge;

import java.io.IOException;

/** 准备文件通道不可用 / 写入失败。 */
public class SeedStorageException extends IOException {
    public SeedStorageException(String message) {
        super(message);
    }
}
