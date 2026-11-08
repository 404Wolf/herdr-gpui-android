package dev.herdr.android;

import android.app.Activity;
import android.app.Dialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * The one long-press menu every list in the app uses (tabs, rail rows, Home's
 * machines and agents): a compact dark card titled with what it acts on, one
 * row per action, destructive ones in the danger colour.
 */
final class ActionSheet {
    private final Activity activity;
    private final GpuiChrome chrome;
    private final String title;
    private final List<Object[]> items = new ArrayList<>();

    ActionSheet(Activity activity, GpuiChrome chrome, String title) {
        this.activity = activity;
        this.chrome = chrome;
        this.title = title;
    }

    ActionSheet add(String label, Runnable action) {
        items.add(new Object[] {label, action, false});
        return this;
    }

    /** Closing or stopping something: shown in the danger colour. */
    ActionSheet danger(String label, Runnable action) {
        items.add(new Object[] {label, action, true});
        return this;
    }

    void show() {
        if (activity.isFinishing() || items.isEmpty()) return;
        Dialog dialog = new Dialog(activity);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout list = card(activity, chrome);
        TextView heading = label(activity, title, 12, chrome.onSurfaceVariant);
        heading.setPadding(dp(16), dp(12), dp(16), dp(6));
        heading.setSingleLine(true);
        heading.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        list.addView(heading);
        for (Object[] item : items) {
            TextView row = label(activity, (String) item[0], 15,
                    (Boolean) item[2] ? chrome.danger : chrome.onSurface);
            row.setMinHeight(dp(44));
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(16), 0, dp(16), 0);
            row.setBackground(ripple(chrome));
            row.setFocusable(true);
            Runnable action = (Runnable) item[1];
            row.setOnClickListener(v -> { dialog.dismiss(); action.run(); });
            list.addView(row, new LinearLayout.LayoutParams(-1, -2));
        }
        ScrollView scroll = new ScrollView(activity);
        scroll.addView(list);
        dialog.setContentView(scroll);
        size(dialog);
        dialog.show();
    }

    /** A one-line text prompt, prefilled and selected, for renames. */
    static void prompt(Activity activity, GpuiChrome chrome, String title, String initial,
            Consumer<String> done) {
        if (activity.isFinishing()) return;
        Dialog dialog = new Dialog(activity);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout card = card(activity, chrome);
        card.setPadding(dp(activity, 16), dp(activity, 12), dp(activity, 16), dp(activity, 8));
        card.addView(label(activity, title, 12, chrome.onSurfaceVariant));
        EditText field = new EditText(activity);
        field.setText(initial);
        field.setSingleLine(true);
        field.setTextSize(16);
        field.setTextColor(chrome.onSurface);
        field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        field.setImeOptions(EditorInfo.IME_ACTION_DONE | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        field.selectAll();
        card.addView(field, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout buttons = new LinearLayout(activity);
        buttons.setGravity(Gravity.END);
        TextView cancel = button(activity, chrome, "Cancel", chrome.onSurfaceVariant);
        TextView save = button(activity, chrome, "Rename", chrome.primary);
        buttons.addView(cancel);
        buttons.addView(save);
        card.addView(buttons, new LinearLayout.LayoutParams(-1, -2));
        Runnable submit = () -> {
            String value = field.getText().toString().trim();
            dialog.dismiss();
            if (!value.isEmpty() && !value.equals(initial)) done.accept(value);
        };
        cancel.setOnClickListener(v -> dialog.dismiss());
        save.setOnClickListener(v -> submit.run());
        field.setOnEditorActionListener((v, action, event) -> { submit.run(); return true; });
        dialog.setContentView(card);
        size(dialog);
        if (dialog.getWindow() != null) {
            dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
        }
        dialog.show();
        field.requestFocus();
    }

    static void copy(Context context, String label, String text) {
        ClipboardManager clipboard = context.getSystemService(ClipboardManager.class);
        if (clipboard == null || text == null || text.isEmpty()) return;
        clipboard.setPrimaryClip(ClipData.newPlainText(label, text));
        Toast.makeText(context, "Copied " + label.toLowerCase(java.util.Locale.ROOT), Toast.LENGTH_SHORT).show();
    }

    private static LinearLayout card(Context context, GpuiChrome chrome) {
        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(0, 0, 0, dp(context, 6));
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(chrome.surface);
        shape.setStroke(dp(context, 1), chrome.outline);
        shape.setCornerRadius(dp(context, 10));
        card.setBackground(shape);
        return card;
    }

    private static TextView label(Context context, String text, float size, int color) {
        TextView view = new TextView(context);
        view.setText(text);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        return view;
    }

    private static TextView button(Context context, GpuiChrome chrome, String text, int color) {
        TextView view = label(context, text, 14, color);
        view.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        view.setGravity(Gravity.CENTER);
        view.setMinHeight(dp(context, 40));
        view.setPadding(dp(context, 14), 0, dp(context, 14), 0);
        view.setBackground(ripple(chrome));
        return view;
    }

    private static RippleDrawable ripple(GpuiChrome chrome) {
        return new RippleDrawable(android.content.res.ColorStateList.valueOf(chrome.selected), null,
                new ColorDrawable(0xffffffff));
    }

    private static void size(Dialog dialog) {
        Window window = dialog.getWindow();
        if (window == null) return;
        window.setBackgroundDrawable(new ColorDrawable(0));
        int width = Math.min(dp(dialog.getContext(), 340),
                dialog.getContext().getResources().getDisplayMetrics().widthPixels - dp(dialog.getContext(), 32));
        window.setLayout(width, WindowManager.LayoutParams.WRAP_CONTENT);
    }

    private int dp(float value) { return dp(activity, value); }

    private static int dp(Context context, float value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
}
