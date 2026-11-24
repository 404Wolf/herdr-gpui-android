package dev.herdr.android.dictation;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import org.json.JSONObject;

/**
 * The phone's Whisper model: a separate file the administrator provisions beside the settings
 * (the app's external files directory, which only adb or the app can write; phone-config pushes
 * it from the devbox, pinned by URL and SHA-256), never downloaded by the app. Provisioned as
 * {@code "dictationLocalModel": {"file": "ggml-base.en-q5_1.bin", "sha256": "<64 hex>"}}; it is
 * used only when the file is there and its SHA-256 is that one. A verified file's size and time
 * are remembered so it is hashed again only when it changes.
 */
public final class LocalModel {
    /** What the setting asks for. */
    public static final class Spec {
        public final String file;
        public final String sha256;

        Spec(String file, String sha256) {
            this.file = file;
            this.sha256 = sha256;
        }

        /** The setting, or null when it is absent or malformed (a path, a short checksum). */
        public static Spec parse(JSONObject o) {
            if (o == null) return null;
            String file = o.optString("file", "");
            String sha = o.optString("sha256", "").toLowerCase(Locale.ROOT);
            if (file.isEmpty() || file.length() > 128 || file.startsWith(".")
                    || !file.matches("[A-Za-z0-9._-]+")) return null;
            if (!sha.matches("[0-9a-f]{64}")) return null;
            return new Spec(file, sha);
        }
    }

    public enum State {
        /** No model is provisioned: the phone has no transcriber of its own. */
        NOT_PROVISIONED,
        /** Provisioned, but the file is not on the phone. */
        MISSING,
        /** The file is there but is not the pinned one: never used. */
        MISMATCH,
        OK
    }

    public static final class Result {
        public final State state;
        /** The verified model; null unless {@link State#OK}. */
        public final File file;

        Result(State state, File file) {
            this.state = state;
            this.file = file;
        }
    }

    /** Files hashed this process (tests). */
    static int hashes;

    private static final String VERIFIED = "local-model-verified";

    private LocalModel() {}

    /**
     * Checks {@code spec}'s file in {@code files} (blocking: may hash ~60 MB; never on the main
     * thread). {@code state} keeps the verified size and time.
     */
    public static Result check(File files, Spec spec, File state) {
        if (spec == null) return new Result(State.NOT_PROVISIONED, null);
        if (files == null) return new Result(State.MISSING, null);
        File model = new File(files, spec.file);
        if (!model.isFile()) return new Result(State.MISSING, null);
        String stamp = spec.sha256 + " " + model.length() + " " + model.lastModified();
        File mark = state == null ? null : new File(state, VERIFIED);
        if (mark != null && stamp.equals(read(mark))) return new Result(State.OK, model);
        String actual = sha256(model);
        if (!spec.sha256.equals(actual)) return new Result(State.MISMATCH, null);
        if (mark != null) write(mark, stamp);
        return new Result(State.OK, model);
    }

    private static String sha256(File file) {
        hashes++;
        try (InputStream in = new FileInputStream(file)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[1 << 16];
            for (int n; (n = in.read(buffer)) > 0; ) digest.update(buffer, 0, n);
            StringBuilder hex = new StringBuilder();
            for (byte b : digest.digest()) hex.append(String.format(Locale.ROOT, "%02x", b));
            return hex.toString();
        } catch (IOException | NoSuchAlgorithmException e) {
            return null;
        }
    }

    private static String read(File f) {
        if (!f.isFile() || f.length() > 512) return null;
        try (InputStream in = new FileInputStream(f)) {
            byte[] b = new byte[(int) f.length()];
            int n = in.read(b);
            return n <= 0 ? null : new String(b, 0, n, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
    }

    /** Written atomically; a failed write only means hashing again next time. */
    private static void write(File f, String s) {
        File tmp = new File(f.getPath() + ".tmp");
        try (java.io.FileOutputStream out = new java.io.FileOutputStream(tmp)) {
            out.write(s.getBytes(StandardCharsets.UTF_8));
            out.getFD().sync();
        } catch (IOException e) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            return;
        }
        //noinspection ResultOfMethodCallIgnored
        tmp.renameTo(f);
    }
}
