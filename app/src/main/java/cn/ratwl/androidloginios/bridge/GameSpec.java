package cn.ratwl.androidloginios.bridge;

/**
 * 目标网易游戏的 MPay 规格。
 *
 * <p>{@link #gameId} 为 MPay/UniSDK 的游戏代号（如 ma75）。为 null 时由桥接页从
 * gameContextInfo 里自动读取并回填。</p>
 */
public final class GameSpec {
    public final String packageName;
    public final String displayName;
    public final String gameId;
    public final String gmActivity;
    public final String cacheAuthority;

    public GameSpec(String packageName, String displayName, String gameId) {
        this(packageName,
                displayName == null || displayName.isEmpty() ? packageName : displayName,
                gameId,
                BridgeProtocol.DEFAULT_GM_ACTIVITY,
                packageName + BridgeProtocol.DEFAULT_CACHE_AUTHORITY_SUFFIX);
    }

    public GameSpec(String packageName, String displayName, String gameId,
                    String gmActivity, String cacheAuthority) {
        this.packageName = packageName;
        this.displayName = displayName == null || displayName.isEmpty() ? packageName : displayName;
        this.gameId = gameId;
        this.gmActivity = gmActivity == null || gmActivity.isEmpty()
                ? BridgeProtocol.DEFAULT_GM_ACTIVITY : gmActivity;
        this.cacheAuthority = cacheAuthority == null || cacheAuthority.isEmpty()
                ? packageName + BridgeProtocol.DEFAULT_CACHE_AUTHORITY_SUFFIX : cacheAuthority;
    }

    public static GameSpec of(String packageName) {
        return new GameSpec(packageName, packageName, null);
    }

    public GameSpec withDisplayName(String name) {
        return new GameSpec(packageName, name, gameId, gmActivity, cacheAuthority);
    }

    /** 换用探测到的网页桥活动（不同 bridge 大版本类名不一样）。 */
    public GameSpec withGmActivity(String activity) {
        return new GameSpec(packageName, displayName, gameId, activity, cacheAuthority);
    }

    public String cacheProviderUriPrefix() {
        return "content://" + cacheAuthority + "/" + BridgeProtocol.DEFAULT_CACHE_PATH + "/";
    }
}
