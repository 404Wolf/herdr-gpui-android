package dev.herdr.android;

import android.app.Activity;
import android.content.ClipData;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * "Share to Herdr": the share sheet's images. They are copied into the app's cache at once (the
 * sender's permission ends with this screen), then handed to the terminal already open, which
 * attaches them to the agent on screen; with no terminal open they wait ({@link SharedImages})
 * while Home asks for an agent. Shows nothing of its own.
 */
public final class ShareTargetActivity extends Activity {
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        List<Uri> uris = sharedUris(getIntent());
        if (uris.isEmpty()) {
            finish();
            return;
        }
        Thread copier = new Thread(() -> {
            List<File> copies = copyToCache(uris);
            new Handler(Looper.getMainLooper()).post(() -> routed(copies));
        }, "herdr-share");
        copier.setDaemon(true);
        copier.start();
    }

    /** The shared images' URIs, from the stream extras or the clip, at most one attach's worth. */
    static List<Uri> sharedUris(Intent intent) {
        List<Uri> out = new ArrayList<>();
        if (intent == null) return out;
        String action = intent.getAction();
        if (Intent.ACTION_SEND.equals(action)) {
            Uri one = intent.getParcelableExtra(Intent.EXTRA_STREAM);
            if (one != null) out.add(one);
        } else if (Intent.ACTION_SEND_MULTIPLE.equals(action)) {
            List<Uri> many = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
            if (many != null) out.addAll(many);
        }
        ClipData clip = intent.getClipData();
        for (int i = 0; out.isEmpty() && clip != null && i < clip.getItemCount(); i++) {
            if (clip.getItemAt(i).getUri() != null) out.add(clip.getItemAt(i).getUri());
        }
        return out.size() > ImageAttach.MAX_IMAGES ? new ArrayList<>(out.subList(0, ImageAttach.MAX_IMAGES)) : out;
    }

    private List<File> copyToCache(List<Uri> uris) {
        File dir = new File(getCacheDir(), "shared");
        File[] old = dir.listFiles();
        if (old != null) for (File stale : old) stale.delete();
        dir.mkdirs();
        List<File> out = new ArrayList<>();
        for (int i = 0; i < uris.size(); i++) {
            File file = new File(dir, "share-" + System.nanoTime() + "-" + i);
            try (InputStream in = getContentResolver().openInputStream(uris.get(i));
                    FileOutputStream sink = new FileOutputStream(file)) {
                if (in == null) continue;
                byte[] bytes = AttachImages.readBounded(in);
                sink.write(bytes);
                out.add(file);
            } catch (IOException | SecurityException | ImageAttach.AttachException unreadable) {
                file.delete();
            }
        }
        return out;
    }

    private void routed(List<File> copies) {
        if (copies.isEmpty()) {
            finish();
            return;
        }
        SharedImages.offer(copies, System.currentTimeMillis());
        if (GpuiTerminalActivity.isOpen()) {
            startActivity(new Intent(this, GpuiTerminalActivity.class)
                    .putExtra(GpuiTerminalActivity.EXTRA_ATTACH_SHARED, true)
                    .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));
        } else {
            startActivity(new Intent(this, HomeActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        }
        finish();
    }
}
