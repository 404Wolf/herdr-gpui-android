package dev.herdr.android;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

/**
 * Version, licences and the privacy policy, from pages bundled in the app.
 * The view never loads the network, runs scripts or reads files or content
 * providers; links that leave the bundled pages open in another app.
 */
public final class AboutActivity extends Activity {
    private static final String PAGES = "file:///android_asset/about/";
    private WebView web;
    private final BackOwner backOwner = new BackOwner(this, () -> {
        if (web != null && web.canGoBack()) web.goBack();
    });

    @Override protected void onCreate(Bundle state) {
        super.onCreate(null);
        FrameLayout root = new FrameLayout(this);
        web = new WebView(this);
        WebSettings settings = web.getSettings();
        settings.setJavaScriptEnabled(false);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setBlockNetworkLoads(true);
        // The window's themed background shows until the page paints its own.
        web.setBackgroundColor(Color.TRANSPARENT);
        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri url = request.getUrl();
                if (url.toString().startsWith(PAGES)) return false;
                openElsewhere(url);
                return true;
            }

            @Override public void doUpdateVisitedHistory(WebView view, String url, boolean reload) {
                backOwner.setOwned(view.canGoBack());
            }
        });
        root.addView(web, new FrameLayout.LayoutParams(-1, -1));
        if (Build.VERSION.SDK_INT >= 30) {
            getWindow().setDecorFitsSystemWindows(false);
            root.setOnApplyWindowInsetsListener((view, insets) -> {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars()
                        | WindowInsets.Type.displayCutout());
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
                return WindowInsets.CONSUMED;
            });
        } else {
            root.setFitsSystemWindows(true);
        }
        setContentView(root);
        applyBarAppearance();
        web.loadUrl(PAGES + "index.html");
    }

    @Override public void onConfigurationChanged(Configuration configuration) {
        super.onConfigurationChanged(configuration);
        applyBarAppearance();
    }

    @SuppressWarnings("deprecation")
    @Override public void onBackPressed() {
        if (web != null && web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }

    @Override protected void onDestroy() {
        if (web != null) {
            web.destroy();
            web = null;
        }
        super.onDestroy();
    }

    /** Dark bar icons over a light page, light ones over a dark page. */
    private void applyBarAppearance() {
        if (Build.VERSION.SDK_INT < 30) return;
        WindowInsetsController controller = getWindow().getInsetsController();
        if (controller == null) return;
        boolean light = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                != Configuration.UI_MODE_NIGHT_YES;
        int bars = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
        controller.setSystemBarsAppearance(light ? bars : 0, bars);
    }

    private void openElsewhere(Uri url) {
        String scheme = url.getScheme();
        if (!"https".equals(scheme) && !"http".equals(scheme) && !"mailto".equals(scheme)) return;
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, url));
        } catch (ActivityNotFoundException none) {
            // No browser: the link simply does nothing (no pop-ups from Herdr).
        }
    }
}
