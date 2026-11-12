package dev.herdr.android;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.text.Spannable;
import android.text.style.ReplacementSpan;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Shows each {@link DictationToken} in the composer's field as a chip, a
 * clock and "0:12 waiting for desktop" outlined in the warning colour, so the
 * raw token never shows. The token stays in the text: it is what the desktop's
 * transcript replaces later.
 */
final class DraftChips {
    /** How a chip is drawn; built from the chrome each time, so a theme change applies. */
    static final class Style {
        final int color;
        final float density;
        final Map<String, Long> lengths;
        final Drawable clock;

        Style(int color, float density, Map<String, Long> lengths, Drawable clock) {
            this.color = color;
            this.density = density;
            this.lengths = lengths;
            this.clock = clock;
        }
    }

    /** What is left of a token the editor cut into: its start without its end, or its end without its start. */
    private static final Pattern BROKEN = Pattern.compile(
            "⟦(?:dictation)?(?: [0-9A-Za-z-]*)?|(?:dictation )?[0-9A-Za-z-]*⟧");

    private DraftChips() { }

    /**
     * {@code text} without the remains of a chip the user deleted into: a
     * backspace takes the whole chip, never leaving half a token as text.
     */
    static String repair(String text) {
        if (text.indexOf('⟦') < 0 && text.indexOf('⟧') < 0) return text;
        // Whole tokens stay; only the text between them is cleaned.
        StringBuilder out = new StringBuilder(text.length());
        Matcher whole = DictationToken.PATTERN.matcher(text);
        int at = 0;
        while (whole.find()) {
            out.append(BROKEN.matcher(text.substring(at, whole.start())).replaceAll("")).append(whole.group());
            at = whole.end();
        }
        return out.append(BROKEN.matcher(text.substring(at)).replaceAll("")).toString();
    }

    /** The words on a chip. */
    static String label(Long durationMs) {
        return durationMs == null ? "waiting for desktop"
                : MicButton.clock(durationMs) + " waiting for desktop";
    }

    /** Puts a chip over every token in {@code text}, replacing the old ones. */
    static void apply(Spannable text, Style style) {
        for (Chip old : text.getSpans(0, text.length(), Chip.class)) text.removeSpan(old);
        Matcher m = DictationToken.PATTERN.matcher(text);
        while (m.find()) {
            text.setSpan(new Chip(label(style.lengths.get(m.group(1))), style),
                    m.start(), m.end(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
    }

    private static final class Chip extends ReplacementSpan {
        private final String label;
        private final Style style;

        Chip(String label, Style style) {
            this.label = label;
            this.style = style;
        }

        private float pad() { return 6 * style.density; }

        private float icon(Paint paint) { return paint.getTextSize() * 0.95f; }

        @Override public int getSize(Paint paint, CharSequence text, int start, int end,
                Paint.FontMetricsInt fm) {
            float size = paint.getTextSize();
            paint.setTextSize(size * 0.86f);
            float width = pad() + icon(paint) + 4 * style.density + paint.measureText(label) + pad();
            paint.setTextSize(size);
            if (fm != null) paint.getFontMetricsInt(fm);
            return Math.round(width + 4 * style.density);
        }

        @Override public void draw(Canvas canvas, CharSequence text, int start, int end, float x,
                int top, int y, int bottom, Paint paint) {
            float size = paint.getTextSize();
            int color = paint.getColor();
            Paint.Style paintStyle = paint.getStyle();
            float stroke = paint.getStrokeWidth();
            paint.setTextSize(size * 0.86f);
            Paint.FontMetrics metrics = paint.getFontMetrics();
            float iconSize = icon(paint);
            float width = pad() + iconSize + 4 * style.density + paint.measureText(label) + pad();
            float height = (metrics.descent - metrics.ascent) + 4 * style.density;
            float centre = y + (metrics.ascent + metrics.descent) / 2f;
            RectF box = new RectF(x + 1, centre - height / 2f, x + 1 + width, centre + height / 2f);
            paint.setColor(style.color);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(Math.max(1f, style.density));
            canvas.drawRoundRect(box, 6 * style.density, 6 * style.density, paint);
            paint.setStyle(Paint.Style.FILL);
            if (style.clock != null) {
                int left = Math.round(box.left + pad());
                int iconTop = Math.round(centre - iconSize / 2f);
                Drawable clock = style.clock.mutate();
                clock.setTint(style.color);
                clock.setBounds(left, iconTop, left + Math.round(iconSize), iconTop + Math.round(iconSize));
                clock.draw(canvas);
            }
            canvas.drawText(label, box.left + pad() + iconSize + 4 * style.density, y, paint);
            paint.setTextSize(size);
            paint.setColor(color);
            paint.setStyle(paintStyle);
            paint.setStrokeWidth(stroke);
        }
    }
}
