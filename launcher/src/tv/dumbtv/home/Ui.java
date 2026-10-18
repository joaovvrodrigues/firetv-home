package tv.dumbtv.home;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.util.TypedValue;
import android.widget.TextView;

final class Ui {
    static final int TEXT = 0xFFF2F2F3;
    static final int MUTED = 0x99FFFFFF;
    static final int FAINT = 0x66FFFFFF;
    static final int SURFACE = 0xFF1C1D21;
    static final int DIALOG = android.R.style.Theme_DeviceDefault_Dialog_Alert;
    static final Typeface LIGHT = Typeface.create("sans-serif-light", Typeface.NORMAL);
    static final Typeface MEDIUM = Typeface.create("sans-serif-medium", Typeface.NORMAL);

    private Ui() {}

    static int dp(Context c, float v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    /** Black on light accents, white on dark ones. */
    static int ink(int color) {
        return Color.luminance(color) > 0.5f ? 0xFF000000 : 0xFFFFFFFF;
    }

    static TextView text(Context c, float sp, int color, Typeface face) {
        TextView v = new TextView(c);
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        v.setTextColor(color);
        v.setTypeface(face);
        v.setIncludeFontPadding(false);
        return v;
    }

    static GradientDrawable round(int color, float radius) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(radius);
        return d;
    }

    /** Vivid-pixel sampling adapted from tv-home, MIT, Copyright (c) 2026 Mihir Gupta. */
    static int sampleColor(Drawable drawable) {
        if (drawable == null) return 0xFF3D8BFF;
        Drawable.ConstantState state = drawable.getConstantState();
        if (state != null) drawable = state.newDrawable().mutate();
        Bitmap bitmap = Bitmap.createBitmap(24, 14, Bitmap.Config.ARGB_8888);
        android.graphics.Rect previous = new android.graphics.Rect(drawable.getBounds());
        try {
            drawable.setBounds(0, 0, 24, 14);
            drawable.draw(new Canvas(bitmap));
            float[] hsv = new float[3];
            double red = 0, green = 0, blue = 0, total = 0;
            for (int y = 0; y < 14; y++) for (int x = 0; x < 24; x++) {
                int pixel = bitmap.getPixel(x, y);
                if (Color.alpha(pixel) < 128) continue;
                Color.colorToHSV(pixel, hsv);
                double weight = 0.05 + hsv[1] * hsv[2];
                red += Color.red(pixel) * weight;
                green += Color.green(pixel) * weight;
                blue += Color.blue(pixel) * weight;
                total += weight;
            }
            if (total > 0) {
                Color.colorToHSV(Color.rgb((int) (red / total), (int) (green / total), (int) (blue / total)), hsv);
                if (hsv[1] > 0.12f) { hsv[1] = Math.max(hsv[1], 0.65f); hsv[2] = 0.8f; }
                else { hsv[1] = 0; hsv[2] = 0.45f; }
                return Color.HSVToColor(hsv);
            }
            return 0xFF3D8BFF;
        } finally {
            drawable.setBounds(previous);
            bitmap.recycle();
        }
    }

    /** Accent fill when focused, a faint fill otherwise. */
    static void focusStyle(TextView v, int accent, int idle, float radius) {
        StateListDrawable bg = new StateListDrawable();
        bg.addState(new int[] {android.R.attr.state_focused}, round(accent, radius));
        bg.addState(new int[0], round(idle, radius));
        v.setBackground(bg);
        v.setTextColor(new ColorStateList(
                new int[][] {{android.R.attr.state_focused}, {}},
                new int[] {ink(accent), 0xD9FFFFFF}));
    }
}
