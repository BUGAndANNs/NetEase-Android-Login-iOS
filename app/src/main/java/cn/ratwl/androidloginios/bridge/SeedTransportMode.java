package cn.ratwl.androidloginios.bridge;

/** 准备文件的写入通道。 */
public enum SeedTransportMode {
    /** 通过游戏自带的 {@code <package>.widget_file_provider} 写缓存目录（大多数官服游戏）。 */
    STANDARD("standard"),
    /** 通过 SAF 授权写入 {@code Android/media/<package>}（阴阳师等未开放缓存接口的游戏）。 */
    SAF_MEDIA("saf_media");

    public final String prefValue;

    SeedTransportMode(String prefValue) {
        this.prefValue = prefValue;
    }

    public static SeedTransportMode fromPref(String value) {
        for (SeedTransportMode mode : values()) {
            if (mode.prefValue.equals(value)) {
                return mode;
            }
        }
        return STANDARD;
    }
}
