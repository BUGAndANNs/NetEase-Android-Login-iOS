package cn.ratwl.androidloginios.bridge;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Build;
import android.util.LruCache;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;

/** 目标游戏的解析与枚举。 */
public final class GameTarget {
    private static final int ICON_CACHE_SIZE = 64;
    private static final LruCache<String, Drawable> ICON_CACHE =
            new LruCache<String, Drawable>(ICON_CACHE_SIZE) {
                @Override
                protected int sizeOf(String key, Drawable value) {
                    return 1;
                }
            };

    public final GameSpec spec;
    public final String appLabel;
    public final String dataDir;
    public final String versionName;
    public final String gmActivityClass;
    public final String cacheUriPrefix;

    private GameTarget(GameSpec spec, String appLabel, String dataDir, String versionName) {
        this.spec = spec;
        this.appLabel = appLabel;
        this.dataDir = dataDir;
        this.versionName = versionName;
        this.gmActivityClass = spec.gmActivity;
        this.cacheUriPrefix = spec.cacheProviderUriPrefix();
    }

    public Uri cacheUri(String fileName) {
        return Uri.parse(cacheUriPrefix + fileName);
    }

    public String localCacheFilePath(String fileName) {
        return dataDir + "/cache/" + fileName;
    }

    /** 校验游戏是否可用（必须已安装且能读到 dataDir）。 */
    public static GameTarget verify(Context context, GameSpec spec) throws Exception {
        PackageInfo info = getPackageInfo(context.getPackageManager(), spec.packageName);
        ApplicationInfo appInfo = info.applicationInfo;
        if (appInfo == null || appInfo.dataDir == null) {
            throw new IllegalStateException("game-interface-missing");
        }
        CharSequence label = appInfo.loadLabel(context.getPackageManager());
        String resolved = label == null ? "" : label.toString().trim();
        if (resolved.isEmpty()) {
            resolved = spec.displayName;
        }
        String gmActivity = resolveGmActivity(context, spec.packageName, spec.gmActivity);
        GameSpec tuned = gmActivity == null ? spec : spec.withGmActivity(gmActivity);
        return new GameTarget(tuned.withDisplayName(resolved), resolved, appInfo.dataDir,
                info.versionName == null ? "" : info.versionName);
    }

    /**
     * 探测目标游戏真正可用的网页桥活动。
     *
     * <p>UniSDK 的桥活动在不同大版本里类名并不统一（gmbridge5 / gmbridge4 / 老包名各有差异），
     * 只认 {@code gmbridge5} 会让老架构游戏在拉起阶段直接失败。这里先验证首选类名是否真的
     * 存在且已导出，不成立再枚举该包所有导出活动，挑最匹配的一个。</p>
     *
     * @return 可用的活动类名；一个都没找到时返回 {@code null}，调用方保留原值。
     */
    public static String resolveGmActivity(Context context, String packageName, String preferred) {
        PackageManager pm = context.getPackageManager();
        if (activityExists(pm, packageName, preferred)) {
            return preferred;
        }

        ActivityInfo[] activities;
        try {
            activities = activitiesOf(pm, packageName);
        } catch (Throwable t) {
            return null;
        }
        if (activities == null) {
            return null;
        }

        String best = null;
        int bestRank = Integer.MAX_VALUE;
        for (ActivityInfo info : activities) {
            if (info == null || info.name == null || !info.exported) {
                continue;
            }
            int rank = gmActivityRank(info.name);
            if (rank < bestRank) {
                bestRank = rank;
                best = info.name;
            }
        }
        return best;
    }

    /** 越小越优先；{@code Integer.MAX_VALUE} 表示不是网页桥活动。 */
    private static int gmActivityRank(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        if (!lower.endsWith("gmwebviewactivity")) {
            return Integer.MAX_VALUE;
        }
        if (lower.contains("gmbridge5")) {
            return 0;
        }
        if (lower.contains("gmbridge4")) {
            return 1;
        }
        return lower.contains("gmbridge") ? 2 : 3;
    }

    /**
     * 首选类名是否存在。只判断「有没有」，不判断导出状态：光·遇等已验证游戏走这条分支时
     * 行为必须与之前完全一致，不能因为导出声明的差异被换成别的活动。
     */
    private static boolean activityExists(PackageManager pm, String packageName, String activity) {
        if (activity == null || activity.isEmpty()) {
            return false;
        }
        try {
            activityInfoOf(pm, packageName, activity);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static ActivityInfo activityInfoOf(PackageManager pm, String packageName, String activity)
            throws PackageManager.NameNotFoundException {
        ComponentName cn = new ComponentName(packageName, activity);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return pm.getActivityInfo(cn, PackageManager.ComponentInfoFlags.of(0L));
        }
        return pm.getActivityInfo(cn, 0);
    }

    private static ActivityInfo[] activitiesOf(PackageManager pm, String packageName)
            throws PackageManager.NameNotFoundException {
        PackageInfo info;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            info = pm.getPackageInfo(packageName,
                    PackageManager.PackageInfoFlags.of(PackageManager.GET_ACTIVITIES));
        } else {
            info = pm.getPackageInfo(packageName, PackageManager.GET_ACTIVITIES);
        }
        return info.activities;
    }

    /** 读取当前安装状态（不会抛异常）。 */
    public static InstalledApp inspect(Context context, GameSpec spec) {
        PackageManager pm = context.getPackageManager();
        PackageInfo info;
        try {
            info = getPackageInfo(pm, spec.packageName);
        } catch (Throwable t) {
            return new InstalledApp(spec, "", null, false, false);
        }
        ApplicationInfo appInfo = info.applicationInfo;
        String label = spec.displayName;
        boolean system = false;
        if (appInfo != null) {
            try {
                CharSequence cs = appInfo.loadLabel(pm);
                if (cs != null && cs.toString().trim().length() > 0) {
                    label = cs.toString().trim();
                }
            } catch (Throwable ignored) {
                // 保留 spec 中的名字
            }
            system = (appInfo.flags
                    & (ApplicationInfo.FLAG_SYSTEM | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0;
        }
        return new InstalledApp(spec.withDisplayName(label),
                info.versionName == null ? "" : info.versionName,
                ICON_CACHE.get(spec.packageName), true, system);
    }

    /**
     * 列出本机所有带启动图标的应用，用户自行安装的排在前面，其余按名称排序。
     * 图标按需加载，避免一次性解码全部图片。
     */
    public static List<InstalledApp> listLauncherApps(Context context) {
        PackageManager pm = context.getPackageManager();
        String self = context.getPackageName();
        Intent launcher = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);

        List<android.content.pm.ResolveInfo> resolved;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            resolved = pm.queryIntentActivities(launcher, PackageManager.ResolveInfoFlags.of(0L));
        } else {
            resolved = pm.queryIntentActivities(launcher, 0);
        }
        if (resolved == null) {
            return Collections.emptyList();
        }

        HashSet<String> seen = new HashSet<>();
        List<InstalledApp> apps = new ArrayList<>(resolved.size());
        for (android.content.pm.ResolveInfo ri : resolved) {
            if (ri.activityInfo == null) {
                continue;
            }
            String pkg = ri.activityInfo.packageName;
            if (pkg == null || pkg.equals(self) || !seen.add(pkg)) {
                continue;
            }
            ApplicationInfo appInfo = ri.activityInfo.applicationInfo;
            String label = pkg;
            if (appInfo != null) {
                try {
                    CharSequence cs = appInfo.loadLabel(pm);
                    if (cs != null && cs.toString().trim().length() > 0) {
                        label = cs.toString().trim();
                    }
                } catch (Throwable ignored) {
                    // 用包名兜底
                }
            }
            boolean system = appInfo != null && (appInfo.flags
                    & (ApplicationInfo.FLAG_SYSTEM | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0;
            String version = "";
            try {
                version = getPackageInfo(pm, pkg).versionName;
                if (version == null) {
                    version = "";
                }
            } catch (Throwable ignored) {
                // 个别应用读不到版本，忽略
            }
            apps.add(new InstalledApp(GameSpec.of(pkg).withDisplayName(label), version,
                    ICON_CACHE.get(pkg), true, system));
        }

        final Collator collator = Collator.getInstance(Locale.CHINA);
        Collections.sort(apps, new Comparator<InstalledApp>() {
            @Override
            public int compare(InstalledApp a, InstalledApp b) {
                if (a.systemApp != b.systemApp) {
                    return a.systemApp ? 1 : -1;
                }
                return collator.compare(a.label(), b.label());
            }
        });
        return apps;
    }

    /** 是否为网易系应用（按包名判定，com.netease.*）。 */
    public static boolean isNetease(String packageName) {
        if (packageName == null) {
            return false;
        }
        String pkg = packageName.toLowerCase(Locale.ROOT);
        return pkg.equals("com.netease") || pkg.startsWith("com.netease.");
    }

    public static Drawable loadIcon(Context context, String packageName) {
        Drawable cached = ICON_CACHE.get(packageName);
        if (cached != null) {
            return cached;
        }
        try {
            Drawable drawable = context.getPackageManager().getApplicationIcon(packageName);
            if (drawable != null) {
                ICON_CACHE.put(packageName, drawable);
            }
            return drawable;
        } catch (Throwable t) {
            return null;
        }
    }

    public static boolean open(Context context, String packageName) {
        Intent intent = context.getPackageManager().getLaunchIntentForPackage(packageName);
        if (intent == null) {
            return false;
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(intent);
        return true;
    }

    public static PackageInfo getPackageInfo(PackageManager pm, String packageName)
            throws PackageManager.NameNotFoundException {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return pm.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0L));
        }
        return pm.getPackageInfo(packageName, 0);
    }
}
