package cn.ratwl.androidloginios.ui;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import cn.ratwl.androidloginios.bridge.GameTarget;
import cn.ratwl.androidloginios.bridge.InstalledApp;

/** 已安装应用选择器（搜索 + 图标 + 包名）。 */
public final class AppPickerDialog {

    public interface Listener {
        void onSelect(InstalledApp app);

        void onRefresh();
    }

    private static final ExecutorService ICON_LOADER = Executors.newFixedThreadPool(2);

    private static final String PREFS = "app_picker";
    private static final String KEY_ONLY_NETEASE = "only_netease";

    private final Activity activity;
    private final Palette palette;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final android.content.SharedPreferences prefs;

    private final Dialog dialog;
    private final EditText search;
    private final ListView list;
    private final TextView emptyView;
    private final TextView refreshButton;
    private final ToggleView neteaseToggle;
    private final TextView countView;

    private final List<InstalledApp> all = new ArrayList<>();
    private final List<InstalledApp> filtered = new ArrayList<>();
    private final AppAdapter adapter = new AppAdapter();
    private String currentPackage;
    private boolean loading;
    private boolean onlyNetease;

    public AppPickerDialog(Activity activity, Palette palette, String currentPackage,
                           Listener listener) {
        this.activity = activity;
        this.palette = palette;
        this.listener = listener;
        this.currentPackage = currentPackage;
        this.prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        this.onlyNetease = prefs.getBoolean(KEY_ONLY_NETEASE, false);

        dialog = new Dialog(activity);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        LinearLayout root = UiKit.column(activity);
        root.setBackground(UiKit.stroked(activity, palette.card, palette.outline, 22f, 1f));
        int pad = UiKit.dp(activity, 18);
        root.setPadding(pad, pad, pad, UiKit.dp(activity, 12));

        LinearLayout headerRow = UiKit.row(activity, Gravity.CENTER_VERTICAL);
        LinearLayout titleBox = UiKit.column(activity);
        titleBox.addView(UiKit.text(activity, "选择已安装的应用", 16f, palette.text, true));
        titleBox.addView(UiKit.text(activity, "选择要登录 iOS 角色的网易游戏", 11.5f,
                palette.textDim, false));
        headerRow.addView(titleBox, UiKit.weightedWrap(1f));

        refreshButton = UiKit.text(activity, "刷新", 13f, palette.primary, true);
        refreshButton.setPadding(UiKit.dp(activity, 10), UiKit.dp(activity, 6),
                UiKit.dp(activity, 4), UiKit.dp(activity, 6));
        refreshButton.setOnClickListener(v -> {
            if (!loading) {
                listener.onRefresh();
            }
        });
        headerRow.addView(refreshButton);
        root.addView(headerRow, UiKit.matchWrap());

        root.addView(UiKit.spacer(activity, 10));

        search = new EditText(activity);
        search.setHint("搜索应用名称或包名…");
        search.setSingleLine(true);
        search.setTextSize(13.5f);
        search.setTextColor(palette.text);
        search.setHintTextColor(palette.textDim);
        search.setBackground(UiKit.stroked(activity, palette.cardAlt, palette.outline, 13f, 1f));
        search.setPadding(UiKit.dp(activity, 12), UiKit.dp(activity, 8),
                UiKit.dp(activity, 12), UiKit.dp(activity, 8));
        search.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                applyFilter(s == null ? "" : s.toString());
            }

            @Override
            public void afterTextChanged(Editable s) {
            }
        });
        root.addView(search, UiKit.matchWrap());

        root.addView(UiKit.spacer(activity, 8));

        // ---- 只显示网易应用（按包名前缀过滤）----
        LinearLayout filterRow = UiKit.row(activity, Gravity.CENTER_VERTICAL);
        filterRow.setBackground(UiKit.stroked(activity, palette.cardAlt,
                palette.outline, 13f, 1f));
        int fPadH = UiKit.dp(activity, 12);
        filterRow.setPadding(fPadH, UiKit.dp(activity, 5), fPadH, UiKit.dp(activity, 5));

        LinearLayout filterBox = UiKit.column(activity);
        filterBox.addView(UiKit.text(activity, "只显示网易应用", 12.5f, palette.text, true));
        countView = UiKit.text(activity, "包名以 com.netease 开头", 10.5f,
                palette.textDim, false);
        filterBox.addView(countView);
        filterRow.addView(filterBox, UiKit.weightedWrap(1f));

        neteaseToggle = new ToggleView(activity, palette, onlyNetease);
        neteaseToggle.setOnCheckedChange(new ToggleView.OnCheckedChange() {
            @Override
            public void onCheckedChanged(boolean checked) {
                onlyNetease = checked;
                prefs.edit().putBoolean(KEY_ONLY_NETEASE, checked).apply();
                applyFilter(search.getText() == null ? "" : search.getText().toString());
            }
        });
        filterRow.addView(neteaseToggle);
        filterRow.setOnClickListener(v -> neteaseToggle.performClick());
        root.addView(filterRow, UiKit.matchWrap());

        root.addView(UiKit.spacer(activity, 10));

        FrameLayout listHolder = new FrameLayout(activity);
        list = new ListView(activity);
        list.setDivider(null);
        list.setDividerHeight(UiKit.dp(activity, 6));
        list.setVerticalScrollBarEnabled(false);
        list.setAdapter(adapter);
        listHolder.addView(list, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, UiKit.dp(activity, 330)));

        emptyView = UiKit.text(activity, "正在读取本机已安装应用…", 12f, palette.textDim, false);
        emptyView.setGravity(Gravity.CENTER);
        emptyView.setBackground(UiKit.solid(activity, palette.cardAlt, 13f));
        emptyView.setPadding(UiKit.dp(activity, 16), UiKit.dp(activity, 22),
                UiKit.dp(activity, 16), UiKit.dp(activity, 22));
        emptyView.setVisibility(View.GONE);
        listHolder.addView(emptyView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(listHolder, UiKit.matchWrap());

        root.addView(UiKit.spacer(activity, 10));

        TextView close = UiKit.text(activity, "关闭", 13.5f, palette.primary, true);
        close.setGravity(Gravity.END);
        close.setPadding(0, UiKit.dp(activity, 8), 0, UiKit.dp(activity, 8));
        close.setOnClickListener(v -> dialog.dismiss());
        root.addView(close, UiKit.matchWrap());

        dialog.setContentView(root);
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(0x00000000));
            window.setLayout(WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.WRAP_CONTENT);
            window.setGravity(Gravity.CENTER);
        }
    }

    public void show() {
        dialog.show();
        Window window = dialog.getWindow();
        if (window != null) {
            int width = (int) (activity.getResources().getDisplayMetrics().widthPixels * 0.92f);
            window.setLayout(width, WindowManager.LayoutParams.WRAP_CONTENT);
        }
    }

    public void dismiss() {
        dialog.dismiss();
    }

    public void setCurrentPackage(String packageName) {
        this.currentPackage = packageName;
        adapter.notifyDataSetChanged();
    }

    public void setApps(List<InstalledApp> apps, boolean loading) {
        this.loading = loading;
        this.all.clear();
        if (apps != null) {
            this.all.addAll(apps);
        }
        refreshButton.setText(loading ? "刷新中…" : "刷新");
        refreshButton.setAlpha(loading ? 0.5f : 1f);
        String query = search.getText() == null ? "" : search.getText().toString();
        applyFilter(query);
    }

    private void applyFilter(String query) {
        String trimmed = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        filtered.clear();
        for (InstalledApp app : all) {
            if (onlyNetease && !GameTarget.isNetease(app.packageName())) {
                continue;
            }
            if (!trimmed.isEmpty()
                    && !app.label().toLowerCase(Locale.ROOT).contains(trimmed)
                    && !app.packageName().toLowerCase(Locale.ROOT).contains(trimmed)) {
                continue;
            }
            filtered.add(app);
        }
        adapter.notifyDataSetChanged();

        if (countView != null) {
            String scope = onlyNetease ? "仅网易应用 · " : "";
            countView.setText(scope + "共 " + filtered.size() + " 个应用");
        }

        boolean empty = filtered.isEmpty();
        if (empty) {
            if (loading) {
                emptyView.setText("正在读取本机已安装应用…");
            } else if (onlyNetease) {
                emptyView.setText("本机没有找到网易应用（包名以 com.netease 开头）。\n"
                        + "可以关闭上面的开关查看全部应用。");
            } else {
                emptyView.setText("没有找到匹配的应用。");
            }
            emptyView.setVisibility(View.VISIBLE);
            list.setVisibility(View.GONE);
        } else {
            emptyView.setVisibility(View.GONE);
            list.setVisibility(View.VISIBLE);
        }
    }

    private final class AppAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return filtered.size();
        }

        @Override
        public Object getItem(int position) {
            return filtered.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            final InstalledApp app = filtered.get(position);
            LinearLayout row = UiKit.row(activity, Gravity.CENTER_VERTICAL);
            boolean selected = app.packageName().equals(currentPackage);
            row.setBackground(UiKit.stroked(activity,
                    selected ? palette.primaryContainer : palette.cardAlt,
                    selected ? palette.primary : palette.outline, 13f, selected ? 1.4f : 1f));
            int padH = UiKit.dp(activity, 11);
            int padV = UiKit.dp(activity, 9);
            row.setPadding(padH, padV, padH, padV);
            row.setOnClickListener(v -> {
                listener.onSelect(app);
                dialog.dismiss();
            });

            ImageView icon = new ImageView(activity);
            icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
            LinearLayout.LayoutParams iconParams =
                    new LinearLayout.LayoutParams(UiKit.dp(activity, 34), UiKit.dp(activity, 34));
            row.addView(icon, iconParams);
            icon.setTag(app.packageName());
            loadIconAsync(icon, app.packageName());

            LinearLayout textBox = UiKit.column(activity);
            textBox.setPadding(UiKit.dp(activity, 10), 0, 0, 0);
            LinearLayout nameRow = UiKit.row(activity, Gravity.CENTER_VERTICAL);
            TextView name = UiKit.text(activity, app.label(), 14f, palette.text, true);
            name.setSingleLine(true);
            nameRow.addView(name);
            if (GameTarget.isNetease(app.packageName())) {
                TextView tag = UiKit.text(activity, "网易", 9.5f, palette.primary, true);
                tag.setBackground(UiKit.solid(activity, palette.primaryContainer, 8f));
                tag.setPadding(UiKit.dp(activity, 5), UiKit.dp(activity, 1),
                        UiKit.dp(activity, 5), UiKit.dp(activity, 1));
                LinearLayout.LayoutParams tagParams = UiKit.wrapWrap();
                tagParams.leftMargin = UiKit.dp(activity, 6);
                nameRow.addView(tag, tagParams);
            }
            TextView pkg = UiKit.text(activity, app.packageName()
                            + (app.versionName.isEmpty() ? "" : " · v" + app.versionName),
                    11f, palette.textDim, false);
            pkg.setSingleLine(true);
            textBox.addView(nameRow, UiKit.matchWrap());
            textBox.addView(pkg, UiKit.matchWrap());
            row.addView(textBox, UiKit.weightedWrap(1f));

            TextView indicator = UiKit.text(activity, selected ? "✓" : "", 15f,
                    palette.primary, true);
            row.addView(indicator);
            return row;
        }
    }

    private void loadIconAsync(final ImageView view, final String packageName) {
        Context context = activity.getApplicationContext();
        Drawable cached = GameTarget.loadIcon(context, packageName);
        if (cached != null) {
            view.setImageDrawable(cached);
            return;
        }
        view.setImageDrawable(UiKit.solid(activity,
                palette.dark ? 0xFF22304A : 0xFFE6ECF8, 9f));
        ICON_LOADER.execute(new Runnable() {
            @Override
            public void run() {
                final Drawable drawable = GameTarget.loadIcon(context, packageName);
                if (drawable == null) {
                    return;
                }
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        if (packageName.equals(view.getTag())) {
                            view.setImageDrawable(drawable);
                        }
                    }
                });
            }
        });
    }
}
