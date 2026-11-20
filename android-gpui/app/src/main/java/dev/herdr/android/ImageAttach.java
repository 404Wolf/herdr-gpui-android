package dev.herdr.android;

import java.util.ArrayList;
import java.util.List;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Putting a phone image where an agent can read it, as the desktop's paste does: the image becomes
 * a file named {@code herdr-paste-<nanos>.<ext>} on the host (the core's {@code terminal.put}
 * lands it beside the pane's terminal), and its shell-quoted path is typed into the composer, never
 * sent. Pure: the bytes' source, conversion and upload are injected, so every rule is a JVM test.
 */
final class ImageAttach {
    /** terminal.put's own bound: 8 MiB of raw bytes. */
    static final int MAX_BYTES = 8 * 1024 * 1024;
    /** One attach takes at most this many images (a gallery pick of more keeps the first). */
    static final int MAX_IMAGES = 8;

    private ImageAttach() { }

    enum Kind { PNG, JPEG, GIF, WEBP, BMP, TIFF, UNKNOWN }

    /** What the bytes are, from their header: a content type from a picker can be wrong or absent. */
    static Kind sniff(byte[] b) {
        if (b == null) return Kind.UNKNOWN;
        if (b.length >= 8 && (b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G') return Kind.PNG;
        if (b.length >= 3 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF) return Kind.JPEG;
        if (b.length >= 6 && b[0] == 'G' && b[1] == 'I' && b[2] == 'F' && b[3] == '8') return Kind.GIF;
        if (b.length >= 12 && b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F'
                && b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P') return Kind.WEBP;
        if (b.length >= 2 && b[0] == 'B' && b[1] == 'M') return Kind.BMP;
        if (b.length >= 4 && ((b[0] == 'I' && b[1] == 'I' && b[2] == 42 && b[3] == 0)
                || (b[0] == 'M' && b[1] == 'M' && b[2] == 0 && b[3] == 42))) return Kind.TIFF;
        return Kind.UNKNOWN;
    }

    /** The extensions the desktop's paste writes; anything it re-encodes is a png. */
    static String extension(Kind kind) {
        switch (kind) {
            case JPEG: return "jpg";
            case GIF: return "gif";
            case WEBP: return "webp";
            default: return "png";
        }
    }

    /** Formats an agent reads as they are; the rest are re-encoded to png first. */
    static boolean passesThrough(Kind kind) {
        return kind == Kind.PNG || kind == Kind.JPEG || kind == Kind.GIF || kind == Kind.WEBP;
    }

    /** The desktop's name: {@code herdr-paste-<nanos>.<ext>}, so its folder and naming match. */
    static String fileName(Kind kind, long nanos) {
        return "herdr-paste-" + nanos + "." + extension(kind);
    }

    /** Re-encodes what an agent cannot read, or shrinks what is over the bound. */
    interface Converter {
        /** The image as a png (or a smaller one) of at most {@link #MAX_BYTES}; throws when it cannot. */
        byte[] reencode(byte[] raw, boolean oversized) throws AttachException;

        /**
         * Whether a decoder reads these bytes as an image: a file with a png's signature and a
         * broken body is refused before anything is sent.
         */
        default boolean decodable(byte[] raw) {
            return true;
        }
    }

    /** Where the bytes go: the core's terminal.put for one pane; returns the reply JSON. */
    interface Put {
        String put(String name, byte[] bytes) throws AttachException;
    }

    /** One image to attach: its bytes are read only when it is its turn. */
    interface Source {
        byte[] read() throws AttachException;
    }

    /** Why an image did not arrive; {@link #getMessage} is fit to show. */
    static final class AttachException extends Exception {
        AttachException(String message) { super(message); }
        AttachException(String message, Throwable cause) { super(message, cause); }
    }

    /** A named file ready to put. */
    static final class Prepared {
        final String name;
        final byte[] bytes;
        Prepared(String name, byte[] bytes) {
            this.name = name;
            this.bytes = bytes;
        }
    }

    static Prepared prepare(byte[] raw, Converter converter, long nanos) throws AttachException {
        if (raw == null || raw.length == 0) throw new AttachException("That image is empty");
        Kind kind = sniff(raw);
        if (!converter.decodable(raw)) throw new AttachException("That file is not a readable image");
        byte[] bytes = raw;
        if (!passesThrough(kind) || raw.length > MAX_BYTES) {
            bytes = converter.reencode(raw, raw.length > MAX_BYTES);
            // A re-encode is a png or, for a shrunk photo, a jpeg: say what it is.
            kind = sniff(bytes);
            if (!passesThrough(kind)) throw new AttachException("That is not an image Herdr can attach");
        }
        if (bytes.length == 0 || bytes.length > MAX_BYTES) throw new AttachException("That image is too large to attach");
        return new Prepared(fileName(kind, nanos), bytes);
    }

    /** The paths that arrived and the images that did not, each with its reason. */
    static final class Outcome {
        final List<String> paths = new ArrayList<>();
        final List<String> failures = new ArrayList<>();
    }

    /**
     * Puts each source in turn (a failure on one does not stop the rest). {@code nanos} names each
     * file, and is strictly increasing across them, so two images of one pick never share a name.
     */
    static Outcome attach(List<Source> sources, Converter converter, Put put, java.util.function.LongSupplier nanos) {
        Outcome out = new Outcome();
        long last = Long.MIN_VALUE;
        int count = Math.min(sources.size(), MAX_IMAGES);
        for (int i = 0; i < count; i++) {
            try {
                long stamp = Math.max(nanos.getAsLong(), last + 1);
                last = stamp;
                Prepared file = prepare(sources.get(i).read(), converter, stamp);
                out.paths.add(parsePath(put.put(file.name, file.bytes)));
            } catch (AttachException failed) {
                out.failures.add(failed.getMessage());
            }
        }
        return out;
    }

    /** The path from terminal.put's reply, or why the core refused it. */
    static String parsePath(String reply) throws AttachException {
        try {
            JSONObject json = new JSONObject(reply);
            if (json.optBoolean("queued", false) && json.optString("path", "").startsWith("/")) {
                return json.getString("path");
            }
            throw new AttachException(refusal(json.optString("kind", "")));
        } catch (JSONException bad) {
            throw new AttachException("The host's answer could not be read", bad);
        }
    }

    static String refusal(String kind) {
        switch (kind) {
            // A core that predates terminal.put answers "unknown_method" directly; a relay says
            // "needs_update" for the same thing.
            case "unknown_method":
            case "needs_update": return "Herdr on this computer is too old to take images; update it";
            case "not_found": return "That terminal is gone; open the agent again";
            case "unavailable":
            case "machine_unknown": return "That computer is not reachable right now";
            // A refusal after bytes started to arrive can leave part of the file there.
            default: return "The host could not save the image; a partial file may be left on it";
        }
    }

    /**
     * Paths as a shell reads them, space-separated with a trailing space: bare when plain, else
     * single-quoted ({@code '} escaped as {@code '\''}), as the desktop's paste types them.
     */
    static String quotePaths(List<String> paths) {
        StringBuilder out = new StringBuilder();
        for (String path : paths) {
            out.append(plain(path) ? path : "'" + path.replace("'", "'\\''") + "'").append(' ');
        }
        return out.toString();
    }

    private static boolean plain(String path) {
        if (path.isEmpty()) return false;
        for (int i = 0; i < path.length(); i++) {
            char c = path.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                    || "/._-+=,:@%~".indexOf(c) >= 0;
            if (!ok) return false;
        }
        return true;
    }

    /** Pixels the decoder may hold for one image: 16 MP is 64 MiB of bitmap. */
    static final int MAX_DECODE_PIXELS = 16_000_000;

    /**
     * The power-of-two sampling that keeps both the longer side within {@code maxSide} and the
     * decoded pixels within {@code maxPixels}, whatever the encoded size was.
     */
    static int sampleFor(int width, int height, int maxSide, long maxPixels) {
        int sample = sampleSize(width, height, maxSide);
        while (width > 0 && height > 0 && ((long) (width / sample)) * (height / sample) > maxPixels) sample *= 2;
        return sample;
    }

    /** The sampling factor (a power of two) that brings the longer side to at most {@code maxSide}. */
    static int sampleSize(int width, int height, int maxSide) {
        int longest = Math.max(width, height);
        int sample = 1;
        while (longest / sample > maxSide) sample *= 2;
        return sample;
    }
}
