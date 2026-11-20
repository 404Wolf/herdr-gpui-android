package dev.herdr.android;

import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ContentResolver;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
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
    /**
     * Most bytes read from one source before it is refused. A bound on the input, never the only
     * one: decoding and encoding are bounded on their own ({@link ImageAttach#MAX_DECODE_PIXELS}).
     */
    static final int MAX_READ = 24 * 1024 * 1024;
    static final int PICK_REQUEST = 42, CAMERA_REQUEST = 43;
    private static final int SHRUNK_SIDE = 4096;
    private static final int[] JPEG_QUALITIES = {90, 75, 60, 45, 30};

    private AttachImages() { }

    /** Android gives MIME types for the whole clip, so accept only an unambiguously image clip. */
    static boolean isImageClip(ClipData clip) {
        ClipDescription types = clip.getDescription();
        if (types.getMimeTypeCount() == 0 || clip.getItemCount() == 0) return false;
        for (int i = 0; i < types.getMimeTypeCount(); i++) {
            if (!isImageMime(types.getMimeType(i))) return false;
        }
        for (int i = 0; i < clip.getItemCount(); i++) {
            ClipData.Item item = clip.getItemAt(i);
            if (item.getUri() == null || item.getText() != null || item.getHtmlText() != null
                    || item.getIntent() != null) return false;
        }
        return true;
    }

    static boolean isImageMime(String type) {
        return type != null && ClipDescription.compareMimeTypes(type, "image/*");
    }

    /** The system picker for images only: the photo picker on Android 13+, the document picker before. */
    static Intent pickIntent() {
        if (Build.VERSION.SDK_INT >= 33) {
            return new Intent(MediaStore.ACTION_PICK_IMAGES).setType("image/*")
                    .putExtra(MediaStore.EXTRA_PICK_IMAGES_MAX, ImageAttach.MAX_IMAGES);
        }
        return new Intent(Intent.ACTION_GET_CONTENT).setType("image/*")
                .addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
    }

    /**
     * The camera app's intent, writing into this app's private capture file through a URI granted to
     * it for this one capture ({@link CaptureProvider}); nothing reaches the gallery.
     */
    static Intent cameraIntent(Uri target) {
        Intent intent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE).putExtra(MediaStore.EXTRA_OUTPUT, target)
                .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
        intent.setClipData(ClipData.newRawUri("capture", target));
        return intent;
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

    /** At most {@link #MAX_READ} bytes; an allocation failure is a refusal, not a crash. */
    static byte[] readBounded(InputStream in) throws IOException, ImageAttach.AttachException {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream(64 * 1024);
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) > 0) {
                out.write(buffer, 0, read);
                if (out.size() > MAX_READ) throw new ImageAttach.AttachException("That image is too large to attach");
            }
            return out.toByteArray();
        } catch (OutOfMemoryError tooBig) {
            throw new ImageAttach.AttachException("That image is too large to attach", tooBig);
        }
    }

    /**
     * Decodes the whole image (the first frame of an animation), sampled so it never holds more than
     * {@link ImageAttach#MAX_DECODE_PIXELS} pixels: a truncated or damaged body fails here, as a
     * bounds-only read would not.
     */
    private static final android.graphics.ImageDecoder.OnHeaderDecodedListener WHOLE_IMAGE =
            (decoder, info, source) -> {
                decoder.setAllocator(android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE);
                decoder.setTargetSampleSize(ImageAttach.sampleFor(info.getSize().getWidth(),
                        info.getSize().getHeight(), Integer.MAX_VALUE, ImageAttach.MAX_DECODE_PIXELS));
            };

    /**
     * Copies {@code uri} to a new file in {@code dir}, streaming (never held whole in memory), at
     * most {@link #MAX_READ} bytes, and keeps it only if a decoder reads it as an image.
     */
    static File copyValidated(ContentResolver resolver, Uri uri, File dir) throws ImageAttach.AttachException {
        dir.mkdirs();
        File file = new File(dir, "img-" + System.nanoTime());
        try (InputStream in = resolver.openInputStream(uri)) {
            if (in == null) throw new ImageAttach.AttachException("That image could not be opened");
            long total = 0;
            try (FileOutputStream sink = new FileOutputStream(file)) {
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = in.read(buffer)) > 0) {
                    total += read;
                    if (total > MAX_READ) throw new ImageAttach.AttachException("That image is too large to attach");
                    sink.write(buffer, 0, read);
                }
            }
            if (total == 0) throw new ImageAttach.AttachException("That image is empty");
            try {
                android.graphics.ImageDecoder.decodeBitmap(android.graphics.ImageDecoder.createSource(file), WHOLE_IMAGE).recycle();
            } catch (IOException | RuntimeException | OutOfMemoryError unreadable) {
                throw new ImageAttach.AttachException("That file is not a readable image", unreadable);
            }
            return file;
        } catch (IOException | SecurityException | IllegalArgumentException failed) {
            file.delete();
            throw new ImageAttach.AttachException("That image could not be read", failed);
        } catch (ImageAttach.AttachException refused) {
            file.delete();
            throw refused;
        }
    }

    /**
     * Anything but png, jpeg, gif or webp (bmp, heic, avif, tiff) becomes a png; a photo over the
     * bound is scaled to 4096 px on its long side and saved as a jpeg, its quality lowered until it
     * fits. Decoding is sampled so that no image holds more than
     * {@link ImageAttach#MAX_DECODE_PIXELS} pixels, whatever its encoded size.
     */
    static final class Reencoder implements ImageAttach.Converter {
        @Override public boolean decodable(byte[] raw) {
            try {
                android.graphics.ImageDecoder.decodeBitmap(
                        android.graphics.ImageDecoder.createSource(java.nio.ByteBuffer.wrap(raw)), WHOLE_IMAGE).recycle();
                return true;
            } catch (IOException | RuntimeException | OutOfMemoryError unreadable) {
                return false;
            }
        }

        @Override public byte[] reencode(byte[] raw, boolean oversized) throws ImageAttach.AttachException {
            Bitmap bitmap = null;
            try {
                BitmapFactory.Options bounds = new BitmapFactory.Options();
                bounds.inJustDecodeBounds = true;
                BitmapFactory.decodeByteArray(raw, 0, raw.length, bounds);
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                    throw new ImageAttach.AttachException("That is not an image Herdr can attach");
                }
                BitmapFactory.Options options = new BitmapFactory.Options();
                options.inSampleSize = ImageAttach.sampleFor(bounds.outWidth, bounds.outHeight,
                        oversized ? SHRUNK_SIDE : Integer.MAX_VALUE, ImageAttach.MAX_DECODE_PIXELS);
                bitmap = BitmapFactory.decodeByteArray(raw, 0, raw.length, options);
                if (bitmap == null) throw new ImageAttach.AttachException("That is not an image Herdr can attach");
                if (!oversized) {
                    ByteArrayOutputStream png = new ByteArrayOutputStream();
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, png);
                    if (png.size() <= ImageAttach.MAX_BYTES) return png.toByteArray();
                }
                for (int quality : JPEG_QUALITIES) {
                    ByteArrayOutputStream jpeg = new ByteArrayOutputStream();
                    bitmap.compress(Bitmap.CompressFormat.JPEG, quality, jpeg);
                    if (jpeg.size() <= ImageAttach.MAX_BYTES) return jpeg.toByteArray();
                }
                throw new ImageAttach.AttachException("That image is too large to attach");
            } catch (OutOfMemoryError | RuntimeException failed) {
                throw new ImageAttach.AttachException("That image is too large to process", failed);
            } finally {
                if (bitmap != null) bitmap.recycle();
            }
        }
    }

    static final ImageAttach.Converter CONVERTER = new Reencoder();
}
