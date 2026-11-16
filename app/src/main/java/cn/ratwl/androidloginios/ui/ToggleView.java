package cn.ratwl.androidloginios.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

/**
 * 轻量开关：自绘胶囊 + 圆点，颜色完全跟随模块配色。
 * <p>不用平台 Switch 是为了避免各 ROM 默认配色（青色/系统强调色）破坏整体视觉。</p>
 */
public final class ToggleView extends View {

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF track = new RectF();
    private final Palette palette;

    private boolean checked;
    private boolean listenerEnabled = true;

    public ToggleView(Context context, Palette palette, boolean checked) {
        super(context);
        this.palette = palette;
        this.checked = checked;
        setClickable(true);
        setFocusable(true);
        setContentDescription(checked ? "已开启" : "已关闭");
    }

    public interface OnCheckedChange {
        void onCheckedChanged(boolean checked);
    }

    private OnCheckedChange onChange;

    public void setOnCheckedChange(OnCheckedChange listener) {
        this.onChange = listener;
    }

    public boolean isChecked() {
        return checked;
    }

    public void setChecked(boolean value) {
        if (this.checked == value) {
            return;
        }
        this.checked = value;
        setContentDescription(value ? "已开启" : "已关闭");
        invalidate();
    }

    private void toggle() {
        setChecked(!checked);
        if (listenerEnabled && onChange != null) {
            onChange.onCheckedChanged(checked);
        }
    }

    @Override
    public boolean performClick() {
        toggle();
        return true;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = UiKit.dp(getContext(), 42);
        int height = UiKit.dp(getContext(), 25);
        // 扩大点击热区
        setPadding(UiKit.dp(getContext(), 4), UiKit.dp(getContext(), 4),
                UiKit.dp(getContext(), 4), UiKit.dp(getContext(), 4));
        setMeasuredDimension(width + getPaddingLeft() + getPaddingRight(),
                height + getPaddingTop() + getPaddingBottom());
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float padX = getPaddingLeft();
        float padY = getPaddingTop();
        float w = getWidth() - getPaddingLeft() - getPaddingRight();
        float h = getHeight() - getPaddingTop() - getPaddingBottom();
        float radius = h / 2f;

        track.set(padX, padY, padX + w, padY + h);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(checked
                ? palette.primary
                : (palette.dark ? 0xFF2E3E5C : 0xFFD4DCEA));
        canvas.drawRoundRect(track, radius, radius, paint);

        float knobRadius = radius - UiKit.dp(getContext(), 3);
        float knobCx = checked
                ? padX + w - radius
                : padX + radius;
        paint.setColor(checked
                ? (palette.dark ? 0xFF0B1220 : 0xFFFFFFFF)
                : (palette.dark ? 0xFFA8B6CE : 0xFFFFFFFF));
        canvas.drawCircle(knobCx, padY + radius, knobRadius, paint);
    }

    /** 只显示状态，不响应点击（用于只读展示）。 */
    public void setTapEnabled(boolean enabled) {
        listenerEnabled = enabled;
        setClickable(enabled);
    }
}
