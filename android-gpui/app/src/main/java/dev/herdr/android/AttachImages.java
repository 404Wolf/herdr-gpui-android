package dev.herdr.android;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * The Android side of attaching an image ({@link ImageAttach} holds the rules): the intents that
 * pick or capture one, reading a content URI within a bound, and re-encoding what an agent cannot
 * read. Nothing here touches the connection.
 */
final class AttachImages {
    /** Most bytes read from one source before it is refused (a shrunk photo still fits 8 MB). */
    static final int MAX_READ = 48 * 1024 * 1024;
    static final int PICK_REQUEST = 42, CAMERA_REQUEST = 43;
    private static final int SHRUNK_SIDE = 4096;

    private AttachImages() { }

    /** The system picker: the photo picker on Android 13+, the document picker before. */
    static Intent pickIntent() {
        if (Build.VERSION.SDK_INT >= 33) {
            return new Intent(MediaStore.ACTION_PICK_IMAGES)
                    .putExtra(MediaStore.EXTRA_PICK_IMAGES_MAX, ImageAttach.MAX_IMAGES);
        }
        return new Intent(Intent.ACTION_GET_CONTENT).setType("image/*")
                .addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
    }

    /**
     * A row in the media store for the camera to write into (no permission needed for one's own);
     * it is deleted once its bytes are read, so the photo never lands in the gallery.
     */
    static Uri cameraTarget(ContentResolver resolver, long now) {
        ContentValues values = new ContentValues();
        values.put(MediaStore.Images.Media.DISPLAY_NAME, "herdr-camera-" + now + ".jpg");
        values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
        values.put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Herdr");
        return resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
    }

    static Intent cameraIntent(Uri target) {
        return new Intent(MediaStore.ACTION_IMAGE_CAPTURE).putExtra(MediaStore.EXTRA_OUTPUT, target)
                .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
    }

    /** The URIs a picker returned: its clip, or its single data. */
    static List<Uri> pickedUris(Intent data) {
        List<Uri> out = new ArrayList<>();
        if (data == null) return out;
        if (data.getClipData() != null) {
            for (int i = 0; i < data.getClipData().getItemCount() && out.size() < ImageAttach.MAX_IMAGES; i++) {
                Uri uri = data.getClipData().getItemAt(i).getUri();
                if (uri != null) out.add(uri);
            }
        } else if (data.getData() != null) {
            out.add(data.getData());
        }
        return out;
    }

    static ImageAttach.Source uriSource(ContentResolver resolver, Uri uri) {
        return () -> {
            try (InputStream in = resolver.openInputStream(uri)) {
                if (in == null) throw new ImageAttach.AttachException("That image could not be opened");
                return readBounded(in);
            } catch (IOException | SecurityException | IllegalArgumentException failed) {
                throw new ImageAttach.AttachException("That image could not be read", failed);
            }
        };
    }

    static ImageAttach.Source fileSource(File file) {
        return () -> {
            try (InputStream in = new FileInputStream(file)) {
                return readBounded(in);
            } catch (IOException failed) {
                throw new ImageAttach.AttachException("That image could not be read", failed);
            }
        };
    }

    static byte[] readBounded(InputStream in) throws IOException, ImageAttach.AttachException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[64 * 1024];
        int read;
        while ((read = in.read(buffer)) > 0) {
            out.write(buffer, 0, read);
            if (out.size() > MAX_READ) throw new ImageAttach.AttachException("That image is too large to attach");
        }
        return out.toByteArray();
    }

    /**
     * Anything but png, jpeg, gif or webp (bmp, heic, avif, tiff) becomes a png; a photo over the
     * bound is scaled to 4096 px on its long side and saved as a jpeg, its quality lowered until it fits.
     */
    static final ImageAttach.Converter CONVERTER = (raw, oversized) -> {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(raw, 0, raw.length, bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw new ImageAttach.AttachException("That is not an image Herdr can attach");
        }
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = oversized ? ImageAttach.sampleSize(bounds.outWidth, bounds.outHeight, SHRUNK_SIDE) : 1;
        Bitmap bitmap = BitmapFactory.decodeByteArray(raw, 0, raw.length, options);
        if (bitmap == null) throw new ImageAttach.AttachException("That is not an image Herdr can attach");
        try {
            for (int quality = 90; ; quality -= 15) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                boolean png = !oversized;
                bitmap.compress(png ? Bitmap.CompressFormat.PNG : Bitmap.CompressFormat.JPEG, png ? 100 : quality, out);
                if (out.size() <= ImageAttach.MAX_BYTES || png || quality <= 30) return out.toByteArray();
            }
        } finally {
            bitmap.recycle();
        }
    };
}
