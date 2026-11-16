package cn.ratwl.androidloginios.ui;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

/** 纯平台 View 的样式工具（不依赖 Material Components / AppCompat）。 */
public final class UiKit {

    private UiKit() {
    }

    public static int dp(Context context, float value) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                context.getResources().getDisplayMetrics()));
    }

    public static TextView text(Context context, CharSequence content, float sp, int color,
                                boolean bold) {
        TextView view = new TextView(context);
        view.setText(content);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        view.setTextColor(color);
        if (bold) {
            view.setTypeface(Typeface.DEFAULT_BOLD);
        }
        view.setLineSpacing(dp(context, 2), 1f);
        return view;
    }

    public static LinearLayout column(Context context) {
        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    public static LinearLayout row(Context context, int verticalGravity) {
        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.HORIZONTAL);
        layout.setGravity(verticalGravity);
        return layout;
    }

    public static View spacer(Context context, int heightDp) {
        View view = new View(context);
        view.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(context, heightDp)));
        return view;
    }

    public static View horizontalSpacer(Context context, int widthDp) {
        View view = new View(context);
        view.setLayoutParams(new LinearLayout.LayoutParams(
                dp(context, widthDp), ViewGroup.LayoutParams.MATCH_PARENT));
        return view;
    }

    public static GradientDrawable solid(Context context, int color, float radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setShape(GradientDrawable.RECTANGLE);
        drawable.setColor(color);
        drawable.setCornerRadius(dp(context, radiusDp));
        return drawable;
    }

    public static GradientDrawable stroked(Context context, int fill, int strokeColor,
                                           float radiusDp, float strokeDp) {
        GradientDrawable drawable = solid(context, fill, radiusDp);
        drawable.setStroke(Math.max(1, dp(context, strokeDp)), strokeColor);
        return drawable;
    }

    public static GradientDrawable verticalGradient(Context context, int topColor, int bottomColor,
                                                    float radiusDp) {
        GradientDrawable drawable = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM, new int[]{topColor, bottomColor});
        drawable.setShape(GradientDrawable.RECTANGLE);
        drawable.setCornerRadius(dp(context, radiusDp));
        return drawable;
    }

    public static GradientDrawable pill(Context context, int fill, int strokeColor, float radiusDp) {
        return stroked(context, fill, strokeColor, radiusDp, 1f);
    }

    /** 圆角卡片容器。 */
    public static LinearLayout card(Context context, Palette palette, float radiusDp) {
        LinearLayout layout = column(context);
        layout.setBackground(stroked(context, palette.card, palette.outline, radiusDp, 1f));
        return layout;
    }

    public static TextView chip(Context context, String label, int fg, int bg) {
        TextView view = text(context, label, 11f, fg, true);
        view.setBackground(solid(context, bg, 6f));
        view.setPadding(dp(context, 6), dp(context, 2), dp(context, 6), dp(context, 2));
        return view;
    }

    /** 带圆点的状态胶囊。 */
    public static TextView statusPill(Context context, String label, int fg, int bg) {
        TextView view = text(context, "● " + label, 11.5f, fg, true);
        view.setBackground(solid(context, bg, 11f));
        view.setPadding(dp(context, 9), dp(context, 6), dp(context, 9), dp(context, 6));
        view.setSingleLine(true);
        view.setGravity(Gravity.CENTER);
        return view;
    }

    public static LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    public static LinearLayout.LayoutParams wrapWrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    public static LinearLayout.LayoutParams weightedWrap(float weight) {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, weight);
    }

    public static void setMargins(View view, int left, int top, int right, int bottom) {
        ViewGroup.LayoutParams params = view.getLayoutParams();
        if (params instanceof ViewGroup.MarginLayoutParams) {
            ((ViewGroup.MarginLayoutParams) params).setMargins(left, top, right, bottom);
            view.setLayoutParams(params);
        }
    }
}
