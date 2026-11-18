package dev.herdr.android.dictation;

import static org.junit.Assert.assertArrayEquals;

import org.json.JSONObject;
import org.junit.Test;

/** The desktop's "unverified_cuts" (server.py): code point offsets, read as offsets in the Java text. */
public final class UnverifiedCutsTest {
    private static int[] cuts(String json, String text) throws Exception {
        return UnverifiedCuts.of(new JSONObject(json), text);
    }

    @Test public void absentOrNotAListMeansNone() throws Exception {
        assertArrayEquals(new int[0], cuts("{\"type\":\"final\",\"text\":\"hello there\"}", "hello there"));
        assertArrayEquals(new int[0], cuts("{\"unverified_cuts\":5}", "hello there"));
        assertArrayEquals(new int[0], cuts("{\"unverified_cuts\":[]}", "hello there"));
        assertArrayEquals(new int[0], UnverifiedCuts.of(null, "hello"));
        assertArrayEquals(new int[0], cuts("{\"unverified_cuts\":[1]}", ""));
    }

    @Test public void offsetsInsideTheTextAreKeptInOrderOnce() throws Exception {
        assertArrayEquals(new int[] {3, 7}, cuts("{\"unverified_cuts\":[7, 3, 7]}", "hello there"));
    }

    @Test public void theEndsPastTheTextAndNonWholeNumbersAreDropped() throws Exception {
        assertArrayEquals(new int[] {5}, cuts("{\"unverified_cuts\":[0, 11, 12, -1, 2.5, \"4\", null, 5]}", "hello there"));
    }

    @Test public void codePointsBecomeJavaOffsets() throws Exception {
        // "a😀b|c": the desktop counts 3 code points before the cut; in Java the emoji is two chars.
        assertArrayEquals(new int[] {4}, cuts("{\"unverified_cuts\":[3]}", "a😀bc"));
    }

    @Test public void atMostMaxAreRead() throws Exception {
        StringBuilder list = new StringBuilder();
        for (int i = 1; i <= 40; i++) list.append(i == 1 ? "" : ",").append(i);
        String text = "x".repeat(100);
        int[] got = cuts("{\"unverified_cuts\":[" + list + "]}", text);
        assertArrayEquals(java.util.stream.IntStream.rangeClosed(1, UnverifiedCuts.MAX).toArray(), got);
    }
}
