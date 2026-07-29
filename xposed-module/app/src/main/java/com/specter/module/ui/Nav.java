package com.specter.module.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Shared navigation affordances so every screen's "back" reads the same. Before this, each screen rolled
 * its own: a "‹ Back" pill on the app picker, a tiny "←" on the live trace, a rotated chevron on the vault
 * drill-downs. {@link #backRow} is the ONE back control — a larger, nice gold chevron in a tappable
 * touch target, with an optional label — used everywhere a screen needs a way out.
 */
final class Nav {
    private Nav() {}

    /** A left-pointing CHEVRON ("‹") drawn in gold, sized to px — the traditional Android back look.
     *  Self-contained (doesn't depend on any Activity's inner icon classes) so every screen can use it. */
    static Drawable arrow(final int px, final int color) {
        return new Drawable() {
            final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            { p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(Math.max(2.5f, px * 0.10f));
              p.setStrokeCap(Paint.Cap.ROUND); p.setStrokeJoin(Paint.Join.ROUND); p.setColor(color); }
            @Override public void draw(Canvas c) {
                Rect b = getBounds();
                float s = Math.min(b.width(), b.height());
                c.save(); c.translate(b.left, b.top);
                // A single "‹": top-right -> mid-left tip -> bottom-right. Narrower than the head span so it
                // reads as a chevron, not an arrowhead.
                Path chev = new Path();
                chev.moveTo(s * 0.60f, s * 0.28f);
                chev.lineTo(s * 0.38f, s * 0.50f);
                chev.lineTo(s * 0.60f, s * 0.72f);
                c.drawPath(chev, p);
                c.restore();
            }
            @Override public void setAlpha(int a) {}
            @Override public void setColorFilter(android.graphics.ColorFilter cf) {}
            @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
            @Override public int getIntrinsicWidth() { return px; }
            @Override public int getIntrinsicHeight() { return px; }
        };
    }

    /** A back header row: a gold chevron (44dp touch target) + an optional gold label. Tapping runs
     *  onBack. Callers may add more views to the returned row after the label (e.g. an app icon). */
    static LinearLayout backRow(Context ctx, String label, Runnable onBack) {
        float density = ctx.getResources().getDisplayMetrics().density;
        int dp8 = (int) (8 * density + 0.5f), dp2 = (int) (2 * density + 0.5f);
        int arrowPx = (int) (26 * density + 0.5f), tapPx = (int) (44 * density + 0.5f);

        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp2, dp2, dp8, dp2);

        ImageView arrow = new ImageView(ctx);
        arrow.setImageDrawable(arrow(arrowPx, Theme.GOLD));
        arrow.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(tapPx, tapPx);
        arrow.setLayoutParams(alp);
        row.addView(arrow);

        if (label != null && !label.isEmpty()) {
            TextView t = new TextView(ctx);
            t.setText(label);
            t.setTextColor(Theme.GOLD);
            t.setTextSize(19);
            t.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
            LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            t.setLayoutParams(tlp);
            row.addView(t);
        }
        // Whole row is the tap target (bigger than the arrow alone), so a near-miss still goes back.
        row.setOnClickListener(v -> onBack.run());
        return row;
    }
}
