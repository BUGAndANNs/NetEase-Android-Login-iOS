package cn.ratwl.androidloginios.ui;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

/** 主题色板（跟随系统深/浅色）。 */
public final class Palette {
    public final boolean dark;

    public final int bg;
    public final int card;
    public final int cardAlt;
    public final int outline;
    public final int text;
    public final int textDim;
    public final int primary;
    public final int onPrimary;
    public final int primaryContainer;
    public final int onPrimaryContainer;
    public final int success;
    public final int successBg;
    public final int warn;
    public final int warnBg;
    public final int error;
    public final int errorBg;

    private Palette(boolean dark) {
        this.dark = dark;
        if (dark) {
            bg = 0xFF0B1220;
            card = 0xFF141E31;
            cardAlt = 0xFF1B2740;
            outline = 0xFF27354E;
            text = 0xFFE8EEF9;
            textDim = 0xFF98A7C0;
            primary = 0xFF7CA6FF;
            onPrimary = 0xFF08152E;
            primaryContainer = 0xFF1C2B49;
            onPrimaryContainer = 0xFFC3D6FF;
            success = 0xFF4ADE80;
            successBg = 0xFF123024;
            warn = 0xFFFBBF24;
            warnBg = 0xFF3A2A0E;
            error = 0xFFF87171;
            errorBg = 0xFF3A1717;
        } else {
            bg = 0xFFFAFBFE;
            card = 0xFFFFFFFF;
            cardAlt = 0xFFF1F5FF;
            outline = 0xFFE1E8F5;
            text = 0xFF14213D;
            textDim = 0xFF64748B;
            primary = 0xFF1E3A8A;
            onPrimary = 0xFFFFFFFF;
            primaryContainer = 0xFFEAF1FF;
            onPrimaryContainer = 0xFF16367F;
            success = 0xFF15803D;
            successBg = 0xFFE9F9EF;
            warn = 0xFFB45309;
            warnBg = 0xFFFFF7ED;
            error = 0xFFB91C1C;
            errorBg = 0xFFFEF2F2;
        }
    }

    public static Palette of(Context context) {
        int mode = context.getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK;
        return new Palette(mode == Configuration.UI_MODE_NIGHT_YES);
    }

    public int toneFg(StatusTone tone) {
        switch (tone) {
            case SUCCESS:
                return success;
            case WARNING:
                return warn;
            case ERROR:
                return error;
            default:
                return textDim;
        }
    }

    public int toneBg(StatusTone tone) {
        switch (tone) {
            case SUCCESS:
                return successBg;
            case WARNING:
                return warnBg;
            case ERROR:
                return errorBg;
            default:
                return cardAlt;
        }
    }
}
