package cn.ratwl.androidloginios.core;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 本模块<b>当前</b>适配的游戏清单，同时也就是模块的注入作用范围。
 *
 * <p>不在表里的包（包括其他网易应用）一律不注入任何 Hook，界面上也必须如实告知用户。</p>
 *
 * <p>两款游戏用的是同一套 MuMu 伪装（渠道 + 设备标识），以后验证过新的网易游戏，
 * 只要在这里 {@code register} 一行就会同时出现在界面「适配范围」里。</p>
 *
 * <p>只按包名匹配，不绑定任何游戏版本：游戏升级后无需同步改模块，所有版本一视同仁。</p>
 */
public final class GameProfile {

    private static final Map<String, GameProfile> GAMES = new LinkedHashMap<>();

    /** 光·遇。 */
    public static final GameProfile SKY = new GameProfile(
            "com.netease.sky", "光·遇", true, true);

    /** 第五人格。 */
    public static final GameProfile IDV = new GameProfile(
            "com.netease.dwrg", "第五人格", true, true);

    static {
        register(SKY);
        register(IDV);
    }

    public final String packageName;
    public final String label;
    /** 是否伪装 MuMu 设备标识（ro.build.id → V417IR）。 */
    public final boolean fakeBuildId;
    /** 是否伪装 MuMu 渠道（netease.mumu2_free_dev）。 */
    public final boolean mumuChannel;

    private GameProfile(String packageName, String label,
                        boolean fakeBuildId, boolean mumuChannel) {
        this.packageName = packageName;
        this.label = label;
        this.fakeBuildId = fakeBuildId;
        this.mumuChannel = mumuChannel;
    }

    private static void register(GameProfile profile) {
        GAMES.put(profile.packageName, profile);
    }

    /** 已适配的游戏列表（顺序固定：光·遇、第五人格）。 */
    public static Collection<GameProfile> all() {
        return Collections.unmodifiableCollection(GAMES.values());
    }

    public static GameProfile find(String packageName) {
        return packageName == null ? null : GAMES.get(packageName);
    }

    /** 是否是本模块适配的包名。 */
    public static boolean isSupported(String packageName) {
        return find(packageName) != null;
    }
}
