package cn.ratwl.androidloginios.bridge;

import java.util.regex.Pattern;

/** MPay / GMBridge5 通用协议常量与校验规则。 */
public final class BridgeProtocol {
    public static final String DEFAULT_GM_ACTIVITY = "com.netease.unisdk.gmbridge5.activity.GMWebviewActivity";
    public static final String DEFAULT_CACHE_AUTHORITY_SUFFIX = ".widget_file_provider";
    public static final String DEFAULT_CACHE_PATH = "widget_file_cache";
    public static final String DEFAULT_LOGIN_CHANNEL = "netease";
    public static final int DEFAULT_LOGIN_TYPE = 1;
    public static final int IOS_CLIENT_TYPE = 2;

    private static final Pattern GAME_ID = Pattern.compile("^[a-zA-Z0-9_.-]{1,64}$");
    private static final Pattern PACKAGE_NAME =
            Pattern.compile("^[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z0-9_]+)+$");

    private BridgeProtocol() {
    }

    public static boolean isValidPackageName(String packageName) {
        return packageName != null
                && packageName.length() <= 160
                && PACKAGE_NAME.matcher(packageName).matches();
    }

    /**
     * 从 {@code uid@channel} 形式的账号标识里取出 uid。
     *
     * @throws IllegalArgumentException 标识为空、过长或含控制字符/空白
     */
    public static String uidFromQualifiedName(String full) {
        if (full == null || full.trim().isEmpty() || full.length() > 512) {
            throw new IllegalArgumentException("identity-missing");
        }
        for (int i = 0; i < full.length(); i++) {
            char c = full.charAt(i);
            if (Character.isISOControl(c) || Character.isWhitespace(c)) {
                throw new IllegalArgumentException("identity-format");
            }
        }
        int at = full.indexOf('@');
        return at > 0 ? full.substring(0, at) : full;
    }

    public static boolean supportedSource(String game, String expectedGameId) {
        if (game == null || game.trim().isEmpty() || !GAME_ID.matcher(game).matches()) {
            return false;
        }
        return expectedGameId == null || expectedGameId.trim().isEmpty() || game.equals(expectedGameId);
    }
}
