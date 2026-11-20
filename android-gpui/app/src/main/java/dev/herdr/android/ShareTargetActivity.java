package dev.herdr.android;

import android.app.Activity;
import android.content.ClipData;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Parcelable;
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
        List<Uri> uris = sharedUris(intent, getPackageName(), refused);
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
     * The shared URIs that may be opened: from the stream extras and the clip (every image of a
     * multiple share, whichever carries it), malformed extras ignored, at most one attach's worth.
     * {@code refused[0]} counts the ones that may not be (wrong scheme, no read grant, our own provider).
     */
    static List<Uri> sharedUris(Intent intent, String ownPackage, int[] refused) {
        List<Uri> found = new ArrayList<>();
        if (intent == null) return found;
        try {
            Object stream = intent.getExtras() == null ? null : intent.getExtras().get(Intent.EXTRA_STREAM);
            if (stream instanceof Uri) found.add((Uri) stream);
            if (stream instanceof List) {
                for (Object item : (List<?>) stream) if (item instanceof Parcelable && item instanceof Uri) found.add((Uri) item);
            }
        } catch (RuntimeException malformed) {
            // A bundle that cannot be read has no usable stream.
        }
        ClipData clip = intent.getClipData();
        for (int i = 0; clip != null && i < clip.getItemCount(); i++) {
            Uri uri = clip.getItemAt(i).getUri();
            if (uri != null && !found.contains(uri)) found.add(uri);
        }
        List<Uri> allowed = new ArrayList<>();
        for (Uri uri : found) {
            if (ShareUris.allowed(uri.getScheme(), uri.getAuthority(), intent.getFlags(), ownPackage)) allowed.add(uri);
            else refused[0]++;
        }
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
            startActivity(new Intent(this, GpuiTerminalActivity.class)
                    .putExtra(GpuiTerminalActivity.EXTRA_ATTACH_SHARED, true)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));
        } else {
            startActivity(new Intent(this, HomeActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        }
        finish();
    }
}
