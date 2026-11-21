package cn.ratwl.androidloginios.ui;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

import cn.ratwl.androidloginios.ModuleInfo;
import cn.ratwl.androidloginios.R;
import cn.ratwl.androidloginios.bridge.BridgePrefs;
import cn.ratwl.androidloginios.bridge.DirectoryAccessRequired;
import cn.ratwl.androidloginios.bridge.GameSpec;
import cn.ratwl.androidloginios.bridge.GameTarget;
import cn.ratwl.androidloginios.bridge.InstalledApp;
import cn.ratwl.androidloginios.bridge.MpayBridgeSession;
import cn.ratwl.androidloginios.bridge.SeedStorage;
import cn.ratwl.androidloginios.bridge.SeedTransportMode;
import cn.ratwl.androidloginios.core.DeviceEnv;
import cn.ratwl.androidloginios.core.GameProfile;
import cn.ratwl.androidloginios.core.ModuleConfig;

/**
 * 主界面：iOS 登录桥（默认）+ 模块与版本面板。
 *
 * <p>用纯平台 View 复刻原 Compose 界面，去掉 AppCompat / Material3 依赖，
 * 模块体积因此从 4.7MB 降到数百 KB。</p>
 */
public class MainActivity extends Activity {

    private static final int REQ_DIRECTORY = 0x51;

    private final Handler main = new Handler(Looper.getMainLooper());

    private Palette p;
    private MpayBridgeSession session;
    private GameSpec directorySpec;
    private boolean resumePrepareAfterGrant;

    // ---- 状态 ----
    private InstalledApp current;
    private boolean busy;
    private String message = "请先选择目标网易游戏。";
    private StatusTone tone = StatusTone.NEUTRAL;
    private SeedTransportMode transportMode = SeedTransportMode.STANDARD;
    private boolean nonEmptyToken;
    private boolean rememberAccount;
    private boolean safAuthorized;
    private boolean submittedCurrent;
    private boolean unconfirmedCurrent;
    private String detectedGameId;

    private List<InstalledApp> installedApps = new ArrayList<>();
    private boolean loadingApps;

    // ---- 视图 ----
    private TextView segmentIos;
    private TextView segmentModule;
    private LinearLayout panelIos;
    private LinearLayout panelModule;
    private LinearLayout gameCard;
    private LinearLayout banner;
    private LinearLayout optionStandard;
    private LinearLayout optionSaf;
    private LinearLayout safRow;
    private TextView safLabel;
    private TextView safActionButton;
    private LinearLayout tokenRow;
    private ToggleView tokenToggle;
    private LinearLayout rememberRow;
    private ToggleView rememberToggle;
    private TextView prepareButton;
    private LinearLayout scopeBox;
    private LinearLayout versionBox;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        p = Palette.of(this);
        applyWindowChrome();

        MpayBridgeSession.cleanup(this);
        restoreSelectedGame();
        restoreDirectoryState(savedInstanceState);
        buildUi();
        if (directorySpec != null) {
            showMessage("目录授权流程已中断，请重新点击「授权」。", StatusTone.WARNING);
        }
        refreshState(true);
        renderAll();
        loadInstalledApps();
    }

    private void restoreDirectoryState(Bundle savedInstanceState) {
        if (savedInstanceState == null) {
            return;
        }
        String pkg = savedInstanceState.getString("directory_package");
        if (pkg == null || pkg.isEmpty()) {
            return;
        }
        String name = savedInstanceState.getString("directory_name", pkg);
        directorySpec = new GameSpec(pkg, name, null);
        resumePrepareAfterGrant = savedInstanceState.getBoolean("directory_resume_prepare", false);
        busy = false;
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (session == null && !busy) {
            MpayBridgeSession.cleanup(this);
        }
        if (current != null) {
            current = GameTarget.inspect(this, current.spec);
        }
        refreshState(!busy);
        renderAll();
    }

    @Override
    protected void onDestroy() {
        if (session != null) {
            session.close();
            session = null;
        }
        super.onDestroy();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        if (directorySpec != null) {
            outState.putString("directory_package", directorySpec.packageName);
            outState.putString("directory_name", directorySpec.displayName);
            outState.putBoolean("directory_resume_prepare", resumePrepareAfterGrant);
            outState.putBoolean("directory_busy", busy);
        }
    }

    private void applyWindowChrome() {
        Window window = getWindow();
        window.setStatusBarColor(p.bg);
        window.setNavigationBarColor(p.bg);
        View decor = window.getDecorView();
        int flags = decor.getSystemUiVisibility();
        if (p.dark) {
            flags &= ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
        } else {
            flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
        }
        decor.setSystemUiVisibility(flags);
    }

    // ==================================================================
    // 布局
    // ==================================================================

    private void buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackground(UiKit.verticalGradient(this,
                p.dark ? 0xFF16233C : 0xFFE7F0FF, p.bg, 0f));

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);

        LinearLayout content = UiKit.column(this);
        int padH = UiKit.dp(this, 15);
        content.setPadding(padH, UiKit.dp(this, 16), padH, UiKit.dp(this, 30));

        content.addView(buildHeader(), UiKit.matchWrap());
        content.addView(UiKit.spacer(this, 14));
        content.addView(buildSegment(), UiKit.matchWrap());
        content.addView(UiKit.spacer(this, 14));

        panelIos = buildIosPanel();
        content.addView(panelIos, UiKit.matchWrap());

        panelModule = buildModulePanel();
        panelModule.setVisibility(View.GONE);
        content.addView(panelModule, UiKit.matchWrap());

        scroll.addView(content, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(scroll, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);
    }

    /**
     * 头部链接 chip：GitHub 用主题色、BiliBili 用粉色，样式对齐上游 HeaderLinkChip。
     * 没有浏览器时退回复制链接，至少让用户能拿到地址。
     */
    private View headerLinkChip(String label, int iconRes, int accent, String url) {
        LinearLayout chip = UiKit.row(this, Gravity.CENTER_VERTICAL);
        chip.setBackground(UiKit.stroked(this,
                withAlpha(accent, p.dark ? 0.14f : 0.09f),
                withAlpha(accent, p.dark ? 0.35f : 0.25f), 12f, 1f));
        chip.setPadding(UiKit.dp(this, 10), UiKit.dp(this, 7),
                UiKit.dp(this, 10), UiKit.dp(this, 7));

        ImageView icon = new ImageView(this);
        icon.setImageResource(iconRes);
        icon.setColorFilter(accent, PorterDuff.Mode.SRC_IN);
        chip.addView(icon, new LinearLayout.LayoutParams(
                UiKit.dp(this, 15), UiKit.dp(this, 15)));

        TextView name = UiKit.text(this, label, 12f, p.text, true);
        name.setSingleLine(true);
        name.setPadding(UiKit.dp(this, 6), 0, 0, 0);
        chip.addView(name, UiKit.weightedWrap(1f));

        ImageView external = new ImageView(this);
        external.setImageResource(R.drawable.ic_open_external);
        external.setColorFilter(withAlpha(accent, 0.8f), PorterDuff.Mode.SRC_IN);
        chip.addView(external, new LinearLayout.LayoutParams(
                UiKit.dp(this, 12), UiKit.dp(this, 12)));

        chip.setOnClickListener(v -> openLink(url, label));
        return chip;
    }

    /** 按给定透明度复制颜色，用于 chip 的浅色底与描边。 */
    private static int withAlpha(int color, float ratio) {
        return Color.argb((int) (255 * ratio),
                Color.red(color), Color.green(color), Color.blue(color));
    }

    /** 打开外链；没有浏览器时退回剪贴板。 */
    private void openLink(String url, String label) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        } catch (Throwable t) {
            ClipboardManager clipboard =
                    (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboard != null) {
                clipboard.setPrimaryClip(ClipData.newPlainText(label, url));
                toast("没有可用浏览器，已复制" + label + "地址");
            } else {
                toast("没有可用浏览器");
            }
        }
    }

    private View buildHeader() {
        LinearLayout card = UiKit.card(this, p, 24f);
        int pad = UiKit.dp(this, 17);
        card.setPadding(pad, pad, pad, UiKit.dp(this, 15));
        card.setBackground(UiKit.verticalGradient(this,
                p.dark ? 0xFF19284A : 0xFFFFFFFF,
                p.dark ? 0xFF121C31 : 0xFFF5F8FF, 24f));

        LinearLayout titleRow = UiKit.row(this, Gravity.CENTER_VERTICAL);
        TextView logo = UiKit.text(this, "iOS", 15f, p.primary, true);
        logo.setGravity(Gravity.CENTER);
        logo.setBackground(UiKit.solid(this, p.primaryContainer, 14f));
        titleRow.addView(logo, new LinearLayout.LayoutParams(
                UiKit.dp(this, 46), UiKit.dp(this, 46)));

        LinearLayout titleBox = UiKit.column(this);
        titleBox.setPadding(UiKit.dp(this, 12), 0, 0, 0);
        titleBox.addView(UiKit.text(this, "网易iOS登录", 19f, p.text, true));
        titleBox.addView(UiKit.text(this, "在安卓端登录 iOS 角色", 11.5f,
                p.textDim, false));
        titleRow.addView(titleBox, UiKit.weightedWrap(1f));
        card.addView(titleRow, UiKit.matchWrap());

        // 头部双入口：GitHub 开源仓库 + BiliBili 空间（对齐上游 HeaderLinkChip）
        LinearLayout linkRow = UiKit.row(this, Gravity.CENTER_VERTICAL);
        linkRow.addView(headerLinkChip("GitHub", R.drawable.ic_github, p.primary,
                ModuleInfo.GITHUB_URL), UiKit.weightedWrap(1f));
        linkRow.addView(UiKit.horizontalSpacer(this, 8));
        linkRow.addView(headerLinkChip("BiliBili", R.drawable.ic_bilibili,
                p.dark ? 0xFFFF85AD : 0xFFE0487B, ModuleInfo.BILIBILI_URL),
                UiKit.weightedWrap(1f));
        card.addView(linkRow, UiKit.matchWrap());

        card.addView(UiKit.spacer(this, 13));

        gameCard = UiKit.column(this);
        card.addView(gameCard, UiKit.matchWrap());
        return card;
    }

    private View buildSegment() {
        LinearLayout bar = UiKit.row(this, Gravity.CENTER_VERTICAL);
        bar.setBackground(UiKit.solid(this, p.dark ? 0xFF16223A : 0xFFEDF2FC, 14f));
        int pad = UiKit.dp(this, 4);
        bar.setPadding(pad, pad, pad, pad);

        segmentIos = segmentTab("iOS 登录", true);
        segmentModule = segmentTab("模块 / 版本", false);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                0, UiKit.dp(this, 36), 1f);
        bar.addView(segmentIos, params);
        LinearLayout.LayoutParams params2 = new LinearLayout.LayoutParams(
                0, UiKit.dp(this, 36), 1f);
        bar.addView(segmentModule, params2);

        segmentIos.setOnClickListener(v -> switchTab(true));
        segmentModule.setOnClickListener(v -> switchTab(false));
        return bar;
    }

    private TextView segmentTab(String label, boolean active) {
        TextView tab = UiKit.text(this, label, 13f, p.text, active);
        tab.setGravity(Gravity.CENTER);
        tab.setBackground(UiKit.solid(this,
                active ? (p.dark ? 0xFF25334F : 0xFFFFFFFF) : Color.TRANSPARENT, 11f));
        return tab;
    }

    private void switchTab(boolean ios) {
        panelIos.setVisibility(ios ? View.VISIBLE : View.GONE);
        panelModule.setVisibility(ios ? View.GONE : View.VISIBLE);
        styleTab(segmentIos, ios);
        styleTab(segmentModule, !ios);
        if (!ios) {
            renderVersionPanel();
        }
    }

    private void styleTab(TextView tab, boolean active) {
        tab.setTextColor(active ? p.text : p.textDim);
        tab.setTypeface(active
                ? android.graphics.Typeface.DEFAULT_BOLD
                : android.graphics.Typeface.DEFAULT);
        tab.setBackground(UiKit.solid(this,
                active ? (p.dark ? 0xFF25334F : 0xFFFFFFFF) : Color.TRANSPARENT, 11f));
    }

    private LinearLayout buildIosPanel() {
        LinearLayout panel = UiKit.column(this);

        banner = UiKit.column(this);
        panel.addView(banner, UiKit.matchWrap());
        panel.addView(UiKit.spacer(this, 12));

        LinearLayout card = UiKit.card(this, p, 22f);
        int pad = UiKit.dp(this, 16);
        card.setPadding(pad, pad, pad, pad);

        card.addView(UiKit.text(this, "准备 iOS 登录", 16f, p.text, true));
        card.addView(UiKit.text(this,
                "先在游戏主页登好账号 → 点下方准备 → 回游戏「切换账号」重登", 11.5f,
                p.textDim, false));
        card.addView(UiKit.spacer(this, 12));

        optionStandard = transportRow("标准缓存（默认）", "适用于光·遇等大多数官服游戏",
                SeedTransportMode.STANDARD);
        optionSaf = transportRow("共享目录（SAF · 阴阳师适用）",
                "授权 Android/media 写入，适配无缓存接口游戏", SeedTransportMode.SAF_MEDIA);
        card.addView(optionStandard, UiKit.matchWrap());
        card.addView(UiKit.spacer(this, 8));
        card.addView(optionSaf, UiKit.matchWrap());

        safRow = UiKit.row(this, Gravity.CENTER_VERTICAL);
        safRow.setBackground(UiKit.stroked(this, p.cardAlt, p.outline, 13f, 1f));
        safLabel = UiKit.text(this, "待授权 Android/media", 11.5f, p.textDim, false);
        LinearLayout.LayoutParams safEdit = UiKit.weightedWrap(1f);
        safRow.setPadding(UiKit.dp(this, 11), UiKit.dp(this, 4),
                UiKit.dp(this, 6), UiKit.dp(this, 4));
        safRow.addView(safLabel, safEdit);
        TextView safAction = UiKit.text(this, "授权", 12.5f, p.primary, true);
        safActionButton = safAction;
        safAction.setPadding(UiKit.dp(this, 10), UiKit.dp(this, 6),
                UiKit.dp(this, 8), UiKit.dp(this, 6));
        safAction.setOnClickListener(v -> authorizeDirectory());
        safRow.addView(safAction);
        safRow.setVisibility(View.GONE);
        card.addView(UiKit.spacer(this, 8));
        card.addView(safRow, UiKit.matchWrap());

        // 兼容开关：默认关闭，按游戏独立记忆（对齐上游 v1.3.1 / v1.3.3）
        tokenRow = compatibilityRow("非空占位凭证（新版阴阳师适用）",
                "默认关闭；重登仍是安卓号时再开启");
        tokenRow.setOnClickListener(v -> toggleNonEmptyToken(!nonEmptyToken));
        tokenToggle = (ToggleView) tokenRow.getTag();
        card.addView(UiKit.spacer(this, 8));
        card.addView(tokenRow, UiKit.matchWrap());

        rememberRow = compatibilityRow("旧版组件记住标记（决战平安京适用）",
                "默认关闭；其他游戏开启会导致切号列表隐藏记录需重登");
        rememberRow.setOnClickListener(v -> toggleRememberAccount(!rememberAccount));
        rememberToggle = (ToggleView) rememberRow.getTag();
        card.addView(UiKit.spacer(this, 8));
        card.addView(rememberRow, UiKit.matchWrap());

        card.addView(UiKit.spacer(this, 14));

        prepareButton = UiKit.text(this, "一键准备 iOS 登录", 15f, p.onPrimary, true);
        prepareButton.setGravity(Gravity.CENTER);
        prepareButton.setBackground(UiKit.solid(this, p.primary, 15f));
        prepareButton.setPadding(0, UiKit.dp(this, 14), 0, UiKit.dp(this, 14));
        prepareButton.setOnClickListener(v -> {
            if (current == null) {
                openPicker();
            } else {
                beginPrepare();
            }
        });
        card.addView(prepareButton, UiKit.matchWrap());

        card.addView(UiKit.spacer(this, 12));
        card.addView(warnBox("若准备前已点开登录弹窗，回游戏必须先点右上角「×」关闭再重开，"
                + "直接点旧列表会一直转圈或触发 1311 登录失效。"), UiKit.matchWrap());

        card.addView(UiKit.spacer(this, 8));
        LinearLayout tips = UiKit.column(this);
        tips.setBackground(UiKit.solid(this, p.dark ? 0xFF16223A : 0xFFF3F6FC, 13f));
        tips.setPadding(UiKit.dp(this, 12), UiKit.dp(this, 10),
                UiKit.dp(this, 12), UiKit.dp(this, 10));
        tips.addView(UiKit.text(this,
                "• 切号后若变回安卓号：在「切换账号」里再点登一次即恢复 iOS。", 11.5f,
                p.textDim, false));
        tips.addView(UiKit.text(this,
                "• 想切回安卓角色：在「切换账号」列表删掉该账号记录后重登。", 11.5f,
                p.textDim, false));
        card.addView(tips, UiKit.matchWrap());

        panel.addView(card, UiKit.matchWrap());
        return panel;
    }

    private LinearLayout transportRow(final String title, String desc, final SeedTransportMode value) {
        LinearLayout row = UiKit.row(this, Gravity.CENTER_VERTICAL);
        row.setBackground(UiKit.stroked(this, p.cardAlt, p.outline, 14f, 1f));
        row.setPadding(UiKit.dp(this, 12), UiKit.dp(this, 10),
                UiKit.dp(this, 12), UiKit.dp(this, 10));

        LinearLayout textBox = UiKit.column(this);
        textBox.addView(UiKit.text(this, title, 13.5f, p.text, true));
        textBox.addView(UiKit.text(this, desc, 11f, p.textDim, false));
        row.addView(textBox, UiKit.weightedWrap(1f));

        TextView indicator = UiKit.text(this, "○", 15f, p.textDim, true);
        indicator.setTag("indicator");
        row.addView(indicator);

        row.setOnClickListener(v -> selectTransportMode(value));
        return row;
    }

    /** 兼容性开关行：返回的行以 tag 持有开关视图，点击整行即切换。 */
    private LinearLayout compatibilityRow(String title, String desc) {
        LinearLayout row = UiKit.row(this, Gravity.CENTER_VERTICAL);
        row.setBackground(UiKit.stroked(this, p.cardAlt, p.outline, 13f, 1f));
        row.setPadding(UiKit.dp(this, 11), UiKit.dp(this, 8),
                UiKit.dp(this, 11), UiKit.dp(this, 8));
        LinearLayout textBox = UiKit.column(this);
        textBox.addView(UiKit.text(this, title, 12.5f, p.text, true));
        textBox.addView(UiKit.text(this, desc, 10.5f, p.textDim, false));
        row.addView(textBox, UiKit.weightedWrap(1f));
        ToggleView toggle = new ToggleView(this, p, false);
        toggle.setTapEnabled(false); // 点击由整行统一处理，避免开关与行双触发抵消
        row.setTag(toggle);
        row.addView(toggle);
        return row;
    }

    private View warnBox(String text) {
        LinearLayout box = UiKit.row(this, Gravity.TOP);
        box.setBackground(UiKit.stroked(this, p.warnBg, withAlpha(p.warn, 0x50), 13f, 1f));
        box.setPadding(UiKit.dp(this, 11), UiKit.dp(this, 9),
                UiKit.dp(this, 11), UiKit.dp(this, 9));
        TextView icon = UiKit.text(this, "!", 13f, p.warn, true);
        icon.setPadding(0, 0, UiKit.dp(this, 8), 0);
        box.addView(icon);
        box.addView(UiKit.text(this, text, 11.5f, p.warn, true), UiKit.weightedWrap(1f));
        return box;
    }

    private LinearLayout buildModulePanel() {
        LinearLayout panel = UiKit.column(this);

        // ---- 适配范围：第一位，各适配游戏 + 本机版本 ----
        scopeBox = UiKit.column(this);
        panel.addView(scopeBox, UiKit.matchWrap());
        panel.addView(UiKit.spacer(this, 12));

        // ---- 功能开关 ----
        LinearLayout card = UiKit.card(this, p, 22f);
        int pad = UiKit.dp(this, 16);
        card.setPadding(pad, pad, pad, UiKit.dp(this, 10));
        card.addView(UiKit.text(this, "模块功能", 16f, p.text, true));
        card.addView(UiKit.text(this,
                "把游戏伪装成 MuMu 模拟器；开关写入模块配置，重启游戏后生效", 11.5f,
                p.textDim, false));
        card.addView(UiKit.spacer(this, 6));

        card.addView(switchRow("MuMu 渠道",
                "渠道 → netease.mumu2_free_dev（getChannel / getCustomCpsChannel）",
                ModuleConfig.KEY_CHANNEL, true), UiKit.matchWrap());
        card.addView(switchRow("MuMu 设备标识",
                "SystemProperties.get(ro.build.id) → V417IR",
                ModuleConfig.KEY_SYSPROP, true), UiKit.matchWrap());

        panel.addView(card, UiKit.matchWrap());
        panel.addView(UiKit.spacer(this, 12));

        // ---- 版本 ----
        versionBox = UiKit.column(this);
        panel.addView(versionBox, UiKit.matchWrap());
        return panel;
    }

    /**
     * 适配范围卡片。
     * <p>第一块是<b>运行环境</b>：真机/模拟器、有没有面具、由此决定的运行模式。
     * 面具直连时模块按网易游戏包名全量放行；框架（Dual Space 等）下只认下面列出的这几款。</p>
     */
    private void renderScopeCard() {
        if (scopeBox == null) {
            return;
        }
        scopeBox.removeAllViews();
        DeviceEnv.Mode mode = DeviceEnv.mode(this);
        boolean wide = mode == DeviceEnv.Mode.ROOT;

        LinearLayout card = UiKit.card(this, p, 22f);
        int pad = UiKit.dp(this, 16);
        card.setPadding(pad, pad, pad, pad);
        card.addView(UiKit.text(this, "适配范围", 16f, p.text, true));
        card.addView(UiKit.text(this,
                "注入范围由本机环境自动决定（见下）", 11f, p.textDim, false));
        card.addView(UiKit.spacer(this, 10));

        LinearLayout env = UiKit.row(this, Gravity.CENTER_VERTICAL);
        env.setBackground(UiKit.solid(this, p.dark ? 0xFF16223A : 0xFFF4F7FD, 13f));
        env.setPadding(UiKit.dp(this, 12), UiKit.dp(this, 9),
                UiKit.dp(this, 12), UiKit.dp(this, 9));
        LinearLayout envText = UiKit.column(this);
        envText.addView(UiKit.text(this, DeviceEnv.describe(this), 12.5f, p.text, true));
        envText.addView(UiKit.text(this, mode.label + " · " + DeviceEnv.explain(this), 10.5f,
                p.textDim, false));
        env.addView(envText, UiKit.weightedWrap(1f));
        env.addView(UiKit.statusPill(this, wide ? "全量" : "限定",
                p.primary, p.primaryContainer));
        card.addView(env, UiKit.matchWrap());
        card.addView(UiKit.spacer(this, 10));

        card.addView(warnBox(wide
                ? "面具直连：所有网易游戏都会注入（通用 MuMu 伪装）；三端选择仅《光·遇》《第五人格》已验证"
                : "仅《光·遇》《第五人格》《猫和老鼠》会注入模块，请勿在其它应用上启用本模块"));
        card.addView(UiKit.spacer(this, 8));
        card.addView(UiKit.text(this,
                "「一键准备 iOS 登录」不受这里限制：任意开放网页桥接口的网易官服游戏都能在本页直接准备。",
                11f, p.textDim, false));
        card.addView(UiKit.spacer(this, 10));
        for (GameProfile profile : GameProfile.all()) {
            card.addView(adaptRow(profile, true), UiKit.matchWrap());
            card.addView(UiKit.spacer(this, 8));
        }
        card.addView(UiKit.text(this, "模块版本：" + ModuleInfo.versionName(this), 11f,
                p.textDim, false));
        scopeBox.addView(card, UiKit.matchWrap());
    }

    /** 一行适配游戏：名称 + 勾选标记 + 包名与本机版本。 */
    private View adaptRow(GameProfile profile, boolean fitted) {
        LinearLayout row = UiKit.row(this, Gravity.CENTER_VERTICAL);
        row.setBackground(UiKit.solid(this, p.dark ? 0xFF16223A : 0xFFF4F7FD, 13f));
        row.setPadding(UiKit.dp(this, 12), UiKit.dp(this, 8),
                UiKit.dp(this, 12), UiKit.dp(this, 8));

        LinearLayout box = UiKit.column(this);
        LinearLayout titleRow = UiKit.row(this, Gravity.CENTER_VERTICAL);
        titleRow.addView(UiKit.text(this, profile.label, 13f, p.text, true),
                UiKit.weightedWrap(1f));
        if (fitted) {
            TextView tag = UiKit.text(this, "已适配", 9.5f, p.primary, true);
            tag.setBackground(UiKit.solid(this, p.primaryContainer, 8f));
            tag.setPadding(UiKit.dp(this, 6), UiKit.dp(this, 2),
                    UiKit.dp(this, 6), UiKit.dp(this, 2));
            titleRow.addView(tag);
        }
        box.addView(titleRow, UiKit.matchWrap());
        box.addView(UiKit.text(this, profile.packageName + " · 本机 "
                        + installedVersion(profile.packageName), 10.5f, p.textDim, false));
        row.addView(box, UiKit.weightedWrap(1f));
        return row;
    }

    private View switchRow(final String title, String desc, final String key, boolean fallback) {
        LinearLayout row = UiKit.row(this, Gravity.CENTER_VERTICAL);
        row.setPadding(0, UiKit.dp(this, 6), 0, UiKit.dp(this, 6));

        LinearLayout textBox = UiKit.column(this);
        textBox.addView(UiKit.text(this, title, 13.5f, p.text, true));
        textBox.addView(UiKit.text(this, desc, 10.5f, p.textDim, false));
        row.addView(textBox, UiKit.weightedWrap(1f));

        ToggleView toggle = new ToggleView(this, p,
                ModuleConfig.editable(this).getBoolean(key, fallback));
        toggle.setOnCheckedChange(new ToggleView.OnCheckedChange() {
            @Override
            public void onCheckedChanged(boolean checked) {
                ModuleConfig.editable(MainActivity.this).edit()
                        .putBoolean(key, checked).apply();
            }
        });
        row.addView(toggle);
        return row;
    }

    // ==================================================================
    // 渲染
    // ==================================================================

    private void renderAll() {
        renderGameCard();
        renderBanner();
        renderTransport();
        renderScopeCard();
        if (panelModule != null && panelModule.getVisibility() == View.VISIBLE) {
            renderVersionPanel();
        }
    }

    private void renderGameCard() {
        if (gameCard == null) {
            return;
        }
        gameCard.removeAllViews();

        LinearLayout selector = UiKit.row(this, Gravity.CENTER_VERTICAL);
        boolean has = current != null;
        selector.setBackground(UiKit.stroked(this, p.cardAlt,
                has ? p.primary : p.outline, 16f, has ? 1.3f : 1f));
        selector.setPadding(UiKit.dp(this, 11), UiKit.dp(this, 9),
                UiKit.dp(this, 11), UiKit.dp(this, 9));
        selector.setOnClickListener(v -> openPicker());

        if (has) {
            ImageView icon = new ImageView(this);
            icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
            Drawable drawable = GameTarget.loadIcon(this, current.packageName());
            if (drawable != null) {
                icon.setImageDrawable(drawable);
            } else {
                icon.setImageDrawable(UiKit.solid(this, p.primaryContainer, 10f));
            }
            selector.addView(icon, new LinearLayout.LayoutParams(
                    UiKit.dp(this, 40), UiKit.dp(this, 40)));

            LinearLayout box = UiKit.column(this);
            box.setPadding(UiKit.dp(this, 10), 0, 0, 0);

            LinearLayout nameRow = UiKit.row(this, Gravity.CENTER_VERTICAL);
            TextView name = UiKit.text(this, current.label(), 14.5f, p.text, true);
            name.setSingleLine(true);
            nameRow.addView(name);
            String gameId = detectedGameId;
            if (gameId != null && !gameId.isEmpty()) {
                TextView chip = UiKit.chip(this, gameId, p.onPrimaryContainer, p.primaryContainer);
                LinearLayout.LayoutParams chipParams = UiKit.wrapWrap();
                chipParams.leftMargin = UiKit.dp(this, 6);
                nameRow.addView(chip, chipParams);
            }
            box.addView(nameRow, UiKit.matchWrap());
            String sub = current.packageName()
                    + (current.versionName.isEmpty() ? "" : " · v" + current.versionName);
            TextView subView = UiKit.text(this, sub, 11f, p.textDim, false);
            subView.setSingleLine(true);
            box.addView(subView, UiKit.matchWrap());
            selector.addView(box, UiKit.weightedWrap(1f));
        } else {
            TextView placeholder = UiKit.text(this, "＋", 17f, p.primary, true);
            placeholder.setGravity(Gravity.CENTER);
            placeholder.setBackground(UiKit.solid(this, p.primaryContainer, 10f));
            selector.addView(placeholder, new LinearLayout.LayoutParams(
                    UiKit.dp(this, 40), UiKit.dp(this, 40)));
            LinearLayout box = UiKit.column(this);
            box.setPadding(UiKit.dp(this, 10), 0, 0, 0);
            box.addView(UiKit.text(this, "点击选择目标游戏", 14f, p.primary, true));
            box.addView(UiKit.text(this, "从本机已安装应用中选择", 11f, p.textDim, false));
            selector.addView(box, UiKit.weightedWrap(1f));
        }

        TextView action = UiKit.text(this, has ? "更换 ▾" : "选择 ▾", 12f, p.primary, true);
        selector.addView(action);
        gameCard.addView(selector, UiKit.matchWrap());

        if (!has) {
            return;
        }

        LinearLayout pills = UiKit.row(this, Gravity.CENTER_VERTICAL);
        pills.setPadding(0, UiKit.dp(this, 10), 0, 0);

        boolean installed = current.installed;
        pills.addView(UiKit.statusPill(this, installed ? "已安装" : "已卸载",
                        installed ? p.success : p.error,
                        installed ? p.successBg : p.errorBg),
                UiKit.weightedWrap(1f));

        String modeLabel;
        int modeFg;
        int modeBg;
        if (submittedCurrent) {
            modeLabel = "已准备就绪";
            modeFg = p.success;
            modeBg = p.successBg;
        } else if (unconfirmedCurrent) {
            modeLabel = "已发送 · 待核对";
            modeFg = p.warn;
            modeBg = p.warnBg;
        } else if (transportMode == SeedTransportMode.SAF_MEDIA) {
            modeLabel = safAuthorized ? "目录已授权" : "目录待授权";
            modeFg = safAuthorized ? p.success : p.warn;
            modeBg = safAuthorized ? p.successBg : p.warnBg;
        } else {
            modeLabel = "标准缓存";
            modeFg = p.textDim;
            modeBg = p.cardAlt;
        }
        LinearLayout.LayoutParams pillParams = UiKit.weightedWrap(1f);
        pillParams.leftMargin = UiKit.dp(this, 8);
        pills.addView(UiKit.statusPill(this, modeLabel, modeFg, modeBg), pillParams);

        TextView open = UiKit.text(this, "打开游戏", 12f, p.primary, true);
        open.setGravity(Gravity.CENTER);
        open.setBackground(UiKit.solid(this, p.primaryContainer, 11f));
        int padH = UiKit.dp(this, 10);
        open.setPadding(padH, UiKit.dp(this, 8), padH, UiKit.dp(this, 8));
        open.setOnClickListener(v -> openGame());
        LinearLayout.LayoutParams openParams = UiKit.wrapWrap();
        openParams.leftMargin = UiKit.dp(this, 8);
        pills.addView(open, openParams);

        gameCard.addView(pills, UiKit.matchWrap());
    }

    private void renderBanner() {
        if (banner == null) {
            return;
        }
        banner.removeAllViews();
        int fg = busy ? p.onPrimaryContainer : p.toneFg(tone);
        int bg = busy ? p.primaryContainer : p.toneBg(tone);
        banner.setBackground(UiKit.stroked(this, bg, withAlpha(fg, 0x2E), 18f, 1f));

        int padH = UiKit.dp(this, 14);
        int padV = UiKit.dp(this, 12);
        banner.setPadding(padH, padV, padH, padV);
        banner.setOrientation(LinearLayout.HORIZONTAL);
        banner.setGravity(Gravity.CENTER_VERTICAL);

        TextView dot = UiKit.text(this, busy ? "…" : "●", 13f, fg, true);
        dot.setPadding(0, 0, UiKit.dp(this, 10), 0);
        banner.addView(dot);
        TextView text = UiKit.text(this, message, 12.5f, fg, true);
        banner.addView(text, UiKit.weightedWrap(1f));
    }

    private void renderTransport() {
        styleOption(optionStandard, transportMode == SeedTransportMode.STANDARD);
        styleOption(optionSaf, transportMode == SeedTransportMode.SAF_MEDIA);
        if (tokenToggle != null) {
            tokenToggle.setChecked(nonEmptyToken);
            tokenRow.setAlpha(busy ? 0.55f : 1f);
        }
        if (rememberToggle != null) {
            rememberToggle.setChecked(rememberAccount);
            rememberRow.setAlpha(busy ? 0.55f : 1f);
        }
        if (safRow != null) {
            safRow.setVisibility(transportMode == SeedTransportMode.SAF_MEDIA
                    ? View.VISIBLE : View.GONE);
            if (safLabel != null) {
                safLabel.setText(safAuthorized
                        ? "已授权 Android/media" : "待授权 Android/media");
                safLabel.setTextColor(safAuthorized ? p.success : p.textDim);
            }
            if (safActionButton != null) {
                safActionButton.setText(safAuthorized ? "更换" : "授权");
            }
        }
        if (prepareButton != null) {
            boolean enabled = !busy;
            prepareButton.setAlpha(enabled ? 1f : 0.55f);
            prepareButton.setEnabled(enabled);
            prepareButton.setText(busy ? "准备中…"
                    : (current == null ? "先选择目标游戏" : "一键准备 iOS 登录"));
        }
    }

    private void styleOption(LinearLayout row, boolean selected) {
        if (row == null) {
            return;
        }
        row.setBackground(UiKit.stroked(this,
                selected ? withAlpha(p.primary, p.dark ? 0x33 : 0x1F) : p.cardAlt,
                selected ? p.primary : p.outline, 14f, selected ? 1.4f : 1f));
        View indicator = row.findViewWithTag("indicator");
        if (indicator instanceof TextView) {
            ((TextView) indicator).setText(selected ? "●" : "○");
            ((TextView) indicator).setTextColor(selected ? p.primary : p.textDim);
        }
    }

    // ==================================================================
    // 版本 / 模块面板
    // ==================================================================

    private void renderVersionPanel() {
        if (versionBox == null) {
            return;
        }
        versionBox.removeAllViews();
        int pad = UiKit.dp(this, 16);

        // 使用说明
        DeviceEnv.Mode mode = DeviceEnv.mode(this);
        LinearLayout help = UiKit.card(this, p, 22f);
        help.setPadding(pad, pad, pad, pad);
        help.addView(UiKit.text(this, "使用说明", 15f, p.text, true));
        help.addView(UiKit.spacer(this, 8));
        help.addView(UiKit.text(this,
                "当前运行模式：" + mode.label + "（" + DeviceEnv.describe(this) + "）\n\n"
                        + "① 面具 + LSPosed（最彻底的一条）：启用模块 → 勾选要用的游戏 → "
                        + "强制停止再启动。只要有面具就全量生效，真机、模拟器都一样，不需要额外容器。\n\n"
                        + "② 免 ROOT · 推荐 Dual Space：把模块和游戏都装进空间 → 长按游戏 → "
                        + "「管理插件」→ 启用本模块 → 作用域选「当前应用」→ 启动游戏。"
                        + "已在 Dual Space 1.94 + Android 16/17 实测可用。\n\n"
                        + "③ 免 ROOT · 元萝卜：能力受限——它内置的引擎在 Android 16/17 上不生效，"
                        + "只在它官方支持的 Android 12～15 上有机会，所以排在最后，不作为推荐路径。\n\n"
                        + "模块做的事：把游戏伪装成 MuMu 模拟器（渠道 + 设备标识），"
                        + "让游戏放出平台选择 / 扫码入口。\n\n"
                        + (mode == DeviceEnv.Mode.ROOT
                        ? "面具直连模式：所有网易游戏都会被注入，改的是「MuMu 伪装」这一层"
                        + "（渠道 + 设备标识），任何网易游戏都吃得到；而「三端自选」属于逐游戏字段，"
                        + "只有《光·遇》《第五人格》实测确认过。非游戏类网易应用已在排除表里，不会被注入。\n\n"
                        : "框架限定模式：只有《光·遇》《第五人格》《猫和老鼠》会注入，"
                        + "其它网易应用一律不动。猫和老鼠 v5.21 在 Dual Space 实测可用。\n\n")
                        + "多开 / 虚拟空间（Dual Space、元萝卜）：游戏跑在宿主进程里，"
                        + "类由沙盒自己的 ClassLoader 加载，模块会在沙盒创建游戏时再注入一次；"
                        + "前提是模块也被装进了沙盒（并勾选宿主作用域）。克隆空间建议先清一次游戏数据，"
                        + "避免旧的渠道值被缓存。\n\n"
                        + "iOS 登录不需要框架配合，任何情况下都可以直接在本页准备 iOS 登录。",
                12f, p.textDim, false));
        help.addView(UiKit.spacer(this, 10));
        TextView account = UiKit.text(this, "微信公众号：" + ModuleInfo.WECHAT_PUBLIC_ACCOUNT,
                12.5f, p.primary, true);
        account.setOnClickListener(v -> {
            ClipboardManager clipboard =
                    (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboard != null) {
                clipboard.setPrimaryClip(ClipData.newPlainText("微信公众号",
                        ModuleInfo.WECHAT_PUBLIC_ACCOUNT));
                toast("已复制公众号：" + ModuleInfo.WECHAT_PUBLIC_ACCOUNT);
            }
        });
        help.addView(account);
        versionBox.addView(help, UiKit.matchWrap());
    }

    private void renderVersionPanelQuiet() {
        if (panelModule != null && panelModule.getVisibility() == View.VISIBLE) {
            renderVersionPanel();
        }
    }

    /** 本机安装版本；未安装时给出明确文案。 */
    private String installedVersion(String packageName) {
        try {
            String version = GameTarget.getPackageInfo(getPackageManager(), packageName).versionName;
            return (version == null || version.isEmpty()) ? "--" : version;
        } catch (Throwable t) {
            return "未安装";
        }
    }


    // ==================================================================
    // 交互
    // ==================================================================

    private void openPicker() {
        if (busy) {
            return;
        }
        AppPickerDialog dialog = new AppPickerDialog(this, p,
                current == null ? null : current.packageName(),
                new AppPickerDialog.Listener() {
                    @Override
                    public void onSelect(InstalledApp app) {
                        selectGame(app);
                    }

                    @Override
                    public void onRefresh() {
                        loadInstalledApps();
                    }
                });
        pickerDialog = dialog;
        dialog.setApps(installedApps, loadingApps);
        dialog.show();
        if (installedApps.isEmpty() && !loadingApps) {
            loadInstalledApps();
        }
    }

    private AppPickerDialog pickerDialog;

    private void loadInstalledApps() {
        if (loadingApps) {
            return;
        }
        loadingApps = true;
        if (pickerDialog != null) {
            pickerDialog.setApps(installedApps, true);
        }
        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                final List<InstalledApp> apps = GameTarget.listLauncherApps(MainActivity.this);
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        installedApps = new ArrayList<>(apps);
                        loadingApps = false;
                        if (pickerDialog != null) {
                            pickerDialog.setApps(installedApps, false);
                        }
                    }
                });
            }
        }, "InstalledApps");
        thread.setDaemon(true);
        thread.start();
    }

    private void selectGame(InstalledApp app) {
        if (busy) {
            return;
        }
        current = GameTarget.inspect(this, app.spec);
        BridgePrefs.get(this).edit()
                .putString(BridgePrefs.KEY_SELECTED_PACKAGE, current.spec.packageName)
                .putString(BridgePrefs.KEY_SELECTED_NAME, current.spec.displayName)
                .apply();
        refreshState(true);
        renderAll();
    }

    private void openGame() {
        if (current == null) {
            showMessage("请先选择目标游戏。", StatusTone.WARNING);
            return;
        }
        try {
            if (!GameTarget.open(this, current.packageName())) {
                showMessage("无法打开「" + current.label() + "」。", StatusTone.ERROR);
            }
        } catch (Throwable t) {
            showMessage("无法打开「" + current.label() + "」，请手动启动。", StatusTone.WARNING);
        }
    }

    private void selectTransportMode(SeedTransportMode value) {
        if (busy) {
            return;
        }
        transportMode = value;
        if (current != null) {
            SeedStorage.setTransportMode(this, current.packageName(), value);
            safAuthorized = SeedStorage.hasValidDirectoryGrant(this, current.packageName());
        }
        renderTransport();
        renderGameCard();
    }

    private void toggleNonEmptyToken(boolean enabled) {
        if (busy) {
            return;
        }
        nonEmptyToken = enabled;
        if (current != null) {
            SeedStorage.setNonEmptyTokenEnabled(this, current.packageName(), enabled);
        }
        renderTransport();
    }

    private void toggleRememberAccount(boolean enabled) {
        if (busy) {
            return;
        }
        rememberAccount = enabled;
        if (current != null) {
            SeedStorage.setRememberAccountEnabled(this, current.packageName(), enabled);
        }
        renderTransport();
    }

    private void authorizeDirectory() {
        if (busy) {
            return;
        }
        if (current == null) {
            showMessage("请先选择目标游戏。", StatusTone.WARNING);
            return;
        }
        launchDirectoryPicker(current.spec, false);
    }

    private void launchDirectoryPicker(GameSpec spec, boolean resumePrepare) {
        if (isFinishing() || isDestroyed()) {
            return;
        }
        if (session != null) {
            session.close();
            session = null;
        }
        directorySpec = spec;
        resumePrepareAfterGrant = resumePrepare;
        setBusy(true);
        showMessage("请选择内部存储 Android/media（或 " + spec.packageName + "）目录。",
                StatusTone.WARNING);
        try {
            startActivityForResult(SeedStorage.directoryIntent(), REQ_DIRECTORY);
        } catch (Throwable t) {
            directorySpec = null;
            resumePrepareAfterGrant = false;
            setBusy(false);
            showMessage("系统目录选择器不可用。", StatusTone.ERROR);
        }
    }

    private void beginPrepare() {
        if (busy) {
            return;
        }
        if (current == null) {
            showMessage("请先选择目标游戏。", StatusTone.WARNING);
            return;
        }
        final GameSpec spec = current.spec;
        final SeedTransportMode selectedMode = transportMode;
        final boolean selectedNonEmptyToken = nonEmptyToken;
        final boolean selectedRememberAccount = rememberAccount;
        setBusy(true);
        showMessage("正在连接「" + spec.displayName + "」准备 iOS 登录…", StatusTone.NEUTRAL);

        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    final GameTarget target = GameTarget.verify(MainActivity.this, spec);
                    final MpayBridgeSession next = new MpayBridgeSession(MainActivity.this,
                            target, selectedMode, selectedNonEmptyToken, selectedRememberAccount,
                            new MpayBridgeSession.Listener() {
                        @Override
                        public void onSubmitted(String gameId, boolean confirmed, boolean usedSaf) {
                            String text = confirmed
                                    ? "准备完成，回「" + target.appLabel + "」点「切换账号」重登即可。"
                                    : "请求已发送（无独立回调），回「" + target.appLabel
                                    + "」点「切换账号」重登核对即可。";
                            complete(text, confirmed ? StatusTone.SUCCESS : StatusTone.WARNING);
                        }

                        @Override
                        public void onFailed(String errorMessage) {
                            complete(errorMessage, StatusTone.ERROR);
                        }
                    });
                    main.post(new Runnable() {
                        @Override
                        public void run() {
                            if (isFinishing() || isDestroyed()) {
                                next.close();
                                return;
                            }
                            if (session != null) {
                                session.close();
                            }
                            session = next;
                            refreshState(false);
                            renderAll();
                            next.start();
                        }
                    });
                } catch (final DirectoryAccessRequired e) {
                    main.post(new Runnable() {
                        @Override
                        public void run() {
                            launchDirectoryPicker(spec, true);
                        }
                    });
                } catch (final Exception e) {
                    main.post(new Runnable() {
                        @Override
                        public void run() {
                            String text = e.getMessage();
                            if (text == null || text.isEmpty()) {
                                text = "准备失败，请检查目录授权或剩余空间后重试。";
                            }
                            complete(text, StatusTone.ERROR);
                        }
                    });
                }
            }
        }, "NeteaseBridgeSetup");
        thread.setDaemon(true);
        thread.start();
    }

    private void complete(String text, StatusTone resultTone) {
        if (session != null) {
            session.close();
            session = null;
        }
        setBusy(false);
        refreshState(false);
        showMessage(text, resultTone);
        renderAll();
    }

    private void setBusy(boolean value) {
        busy = value;
        renderTransport();
        renderGameCard();
    }

    private void showMessage(String text, StatusTone resultTone) {
        message = text;
        tone = resultTone;
        renderBanner();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_DIRECTORY) {
            return;
        }
        final GameSpec spec = directorySpec;
        final boolean resume = resumePrepareAfterGrant;
        directorySpec = null;
        resumePrepareAfterGrant = false;

        Uri uri = data == null ? null : data.getData();
        if (spec == null || resultCode != RESULT_OK || uri == null) {
            setBusy(false);
            showMessage(resume ? "未授权目录，本次准备已取消。" : "已取消目录授权。",
                    resume ? StatusTone.WARNING : StatusTone.NEUTRAL);
            return;
        }

        final Uri treeUri = uri;
        final int flags = data.getFlags();
        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    SeedStorage.saveDirectoryGrant(MainActivity.this, spec.packageName, treeUri, flags);
                    main.post(new Runnable() {
                        @Override
                        public void run() {
                            setBusy(false);
                            refreshState(false);
                            renderAll();
                            if (resume) {
                                if (current != null
                                        && current.packageName().equals(spec.packageName)) {
                                    beginPrepare();
                                } else {
                                    showMessage("已保存目录授权，请重新选择目标游戏。",
                                            StatusTone.WARNING);
                                }
                            } else {
                                showMessage("已保存「" + spec.displayName + "」目录授权。",
                                        StatusTone.SUCCESS);
                            }
                        }
                    });
                } catch (final Exception e) {
                    main.post(new Runnable() {
                        @Override
                        public void run() {
                            setBusy(false);
                            String text = e.getMessage();
                            showMessage(text == null || text.isEmpty() ? "目录授权失败，请重试。" : text,
                                    StatusTone.WARNING);
                        }
                    });
                }
            }
        }, "DirectoryGrant");
        thread.setDaemon(true);
        thread.start();
    }

    // ==================================================================
    // 状态同步
    // ==================================================================

    private void restoreSelectedGame() {
        SharedPreferences prefs = BridgePrefs.get(this);
        String pkg = prefs.getString(BridgePrefs.KEY_SELECTED_PACKAGE, null);
        if (pkg == null || pkg.isEmpty()) {
            return;
        }
        String name = prefs.getString(BridgePrefs.KEY_SELECTED_NAME, pkg);
        current = GameTarget.inspect(this, new GameSpec(pkg, name, null));
    }

    private void refreshState(boolean updateMessage) {
        if (current == null) {
            detectedGameId = null;
            submittedCurrent = false;
            unconfirmedCurrent = false;
            safAuthorized = false;
            if (updateMessage && !busy) {
                showMessage("请先选择目标网易游戏。", StatusTone.NEUTRAL);
            }
            return;
        }

        String pkg = current.packageName();
        SharedPreferences prefs = BridgePrefs.get(this);
        detectedGameId = prefs.getString(BridgePrefs.lastGameIdKey(pkg), null);

        boolean samePackage = pkg.equals(prefs.getString(BridgePrefs.KEY_STATE_PACKAGE, null));
        String state = samePackage ? prefs.getString(BridgePrefs.KEY_STATE, "") : "";
        if (state == null) {
            state = "";
        }
        boolean operationMatches = String.valueOf(prefs.getString(BridgePrefs.KEY_OPERATION, "new"))
                .equals(String.valueOf(prefs.getString(BridgePrefs.KEY_COMPLETED_OPERATION, "old")));
        submittedCurrent = "submitted".equals(state) && operationMatches;
        unconfirmedCurrent = "unconfirmed".equals(state) && operationMatches;

        transportMode = SeedStorage.getTransportMode(this, pkg);
        nonEmptyToken = SeedStorage.isNonEmptyTokenEnabled(this, pkg);
        rememberAccount = SeedStorage.isRememberAccountEnabled(this, pkg);
        safAuthorized = SeedStorage.hasValidDirectoryGrant(this, pkg);

        if (!updateMessage || busy) {
            return;
        }
        String gameName = current.label();
        if (submittedCurrent) {
            showMessage("准备完成，回「" + gameName + "」点「切换账号」重登即可。", StatusTone.SUCCESS);
        } else if (unconfirmedCurrent) {
            showMessage("请求已发送（无独立回调），回「" + gameName + "」点「切换账号」重登核对即可。",
                    StatusTone.WARNING);
        } else if ("failed".equals(state)) {
            String lastError = prefs.getString(BridgePrefs.KEY_LAST_ERROR,
                    "操作未完成，请重试。");
            showMessage(lastError, StatusTone.ERROR);
        } else if ("preparing".equals(state)) {
            showMessage("操作未完成；若已离开网页，请关闭网页后重试。", StatusTone.WARNING);
        } else {
            showMessage("先在「" + gameName + "」登录账号，再点下方按钮一键准备。", StatusTone.NEUTRAL);
        }
    }

    private static int withAlpha(int color, int alpha) {
        return (color & 0x00FFFFFF) | ((alpha & 0xFF) << 24);
    }

    private void toast(String text) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show();
    }
}
