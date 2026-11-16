package cn.ratwl.androidloginios.bridge;

import android.content.Context;
import android.content.SharedPreferences;

/** 桥接状态持久化（模块进程与 UI 共用）。 */
public final class BridgePrefs {
    public static final String PREFS = "netease_mpay_bridge";

    public static final String KEY_STATE = "state";
    public static final String KEY_STATE_PACKAGE = "state_package";
    public static final String KEY_OPERATION = "operation";
    public static final String KEY_COMPLETED_OPERATION = "completed_operation";
    public static final String KEY_LAST_ERROR = "last_error";
    public static final String KEY_SELECTED_PACKAGE = "selected_package";
    public static final String KEY_SELECTED_NAME = "selected_name";
    public static final String KEY_TEMP_URIS = "temporary_uris";

    private BridgePrefs() {
    }

    public static SharedPreferences get(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static String seedModeKey(String packageName) {
        return "seed_mode_" + packageName;
    }

    public static String mediaTreeKey(String packageName) {
        return "media_tree_" + packageName;
    }

    public static String lastGameIdKey(String packageName) {
        return "last_game_id_" + packageName;
    }
}
