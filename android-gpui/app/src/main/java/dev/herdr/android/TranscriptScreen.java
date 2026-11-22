package dev.herdr.android;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Typeface;
import android.view.Window;
import android.view.WindowManager;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * An agent's transcript, read offline (stage 2 of the offline design): the text the core's
 * bounded read gave while the link was up, scrollable, opening at its newest line, under a title
 * that says when it was read and that it may be outdated. Shown only for a transcript whose
 * identity the last direct list proved ({@link Transcripts#proven}); nothing here is live.
 */
final class TranscriptScreen {
    private TranscriptScreen() { }

    static void show(Activity activity, GpuiChrome chrome, String pane, String when, Transcripts.Transcript t) {
        if (activity.isFinishing()) return;
        Dialog dialog = new Dialog(activity);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout card = ActionSheet.card(activity, chrome);
        int pad = dp(activity, 12);
        TextView title = ActionSheet.label(activity, Transcripts.title(pane, when, t), 12, chrome.warning);
        title.setPadding(pad, pad, pad, dp(activity, 6));
        card.addView(title);
        TextView text = new TextView(activity);
        text.setText(t.text);
        text.setTypeface(Typeface.MONOSPACE);
        text.setTextSize(11);
        text.setTextColor(chrome.onSurface);
        text.setTextIsSelectable(true);
        text.setPadding(pad, 0, pad, pad);
        HorizontalScrollView wide = new HorizontalScrollView(activity);
        wide.addView(text);
        ScrollView scroll = new ScrollView(activity);
        scroll.addView(wide);
        card.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        dialog.setContentView(card);
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0));
            window.setLayout(activity.getResources().getDisplayMetrics().widthPixels - dp(activity, 16),
                    (int) (activity.getResources().getDisplayMetrics().heightPixels * 0.85f));
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
        }
        dialog.show();
        // It opens at the newest line, as the pane would.
        scroll.post(() -> scroll.fullScroll(ScrollView.FOCUS_DOWN));
    }

    private static int dp(Activity activity, float value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }
}
