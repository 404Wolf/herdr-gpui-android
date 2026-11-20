package dev.herdr.android;

import android.app.Activity;
import android.content.ClipData;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * "Share to Herdr": the share sheet's images. A sender's URIs are untrusted: only content:// URIs
 * the sender granted for reading are opened ({@link ShareUris}); each is streamed into the app's
 * cache and kept only if a decoder reads it as an image. Nothing is uploaded here: the images wait
 * ({@link SharedImages}) until the user picks an agent for them, in the terminal already open
 * (which asks) or in Home. Shows nothing of its own.
 */
public final class ShareTargetActivity extends Activity {
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        Intent intent = getIntent();
        int[] refused = new int[1];
        List<Uri> uris = sharedUris(intent, getPackageName(), refused,
                uri -> checkUriPermission(uri, android.os.Process.myPid(), android.os.Process.myUid(),
                        ShareUris.GRANT_READ) == android.content.pm.PackageManager.PERMISSION_GRANTED);
        Thread copier = new Thread(() -> {
            File dir = new File(getCacheDir(), "shared");
            File[] old = dir.listFiles();
            if (old != null) for (File stale : old) stale.delete();
            List<File> copies = new ArrayList<>();
            int unusable = refused[0];
            for (Uri uri : uris) {
                try {
                    copies.add(AttachImages.copyValidated(getContentResolver(), uri, dir));
                } catch (ImageAttach.AttachException | RuntimeException unreadable) {
                    unusable++;
                }
            }
            int bad = unusable;
            new Handler(Looper.getMainLooper()).post(() -> routed(copies, bad));
        }, "herdr-share");
        copier.setDaemon(true);
        copier.start();
    }

    /**
     * The shared URIs that may be opened: only those Android granted with the intent (its data and
     * ClipData), each authorized on its own by {@code granted} and the scheme/authority rules
     * ({@link ShareUris}). A stream extra is never opened by itself: Android does not grant it when
     * a clip is present, so one outside the grant, or malformed, is only counted in {@code refused[0]}.
     */
    static List<Uri> sharedUris(Intent intent, String ownPackage, int[] refused,
            java.util.function.Predicate<Uri> granted) {
        List<Uri> candidates = new ArrayList<>();
        if (intent == null) return candidates;
        if (intent.getData() != null) candidates.add(intent.getData());
        ClipData clip = intent.getClipData();
        for (int i = 0; clip != null && i < clip.getItemCount(); i++) {
            Uri uri = clip.getItemAt(i).getUri();
            if (uri != null && !candidates.contains(uri)) candidates.add(uri);
        }
        try {
            Object stream = intent.getExtras() == null ? null : intent.getExtras().get(Intent.EXTRA_STREAM);
            List<Object> entries = new ArrayList<>();
            if (stream instanceof List) entries.addAll((List<?>) stream);
            else if (stream != null) entries.add(stream);
            for (Object entry : entries) {
                if (!(entry instanceof Uri) || !candidates.contains(entry)) refused[0]++;
            }
        } catch (RuntimeException malformed) {
            refused[0]++;
        }
        List<String[]> parts = new ArrayList<>();
        for (Uri uri : candidates) parts.add(new String[] {uri.getScheme(), uri.getAuthority()});
        List<Integer> ok = ShareUris.filter(parts, intent.getFlags(), ownPackage, i -> granted.test(candidates.get(i)));
        refused[0] += candidates.size() - ok.size();
        List<Uri> allowed = new ArrayList<>();
        for (int i : ok) allowed.add(candidates.get(i));
        if (allowed.size() > ImageAttach.MAX_IMAGES) {
            refused[0] += allowed.size() - ImageAttach.MAX_IMAGES;
            return new ArrayList<>(allowed.subList(0, ImageAttach.MAX_IMAGES));
        }
        return allowed;
    }

    private void routed(List<File> copies, int unusable) {
        if (copies.isEmpty() && unusable == 0) {
            finish();
            return;
        }
        SharedImages.offer(copies, unusable, System.currentTimeMillis());
        if (GpuiTerminalActivity.isOpen()) {
            // The terminal asks about waiting images, or says why everything was refused.
            startActivity(new Intent(this, GpuiTerminalActivity.class)
                    .putExtra(GpuiTerminalActivity.EXTRA_ATTACH_SHARED, true)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP
                            | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));
        } else {
            startActivity(new Intent(this, HomeActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        }
        finish();
    }
}
