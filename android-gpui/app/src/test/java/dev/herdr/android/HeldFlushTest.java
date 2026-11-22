package dev.herdr.android;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Wolf 11-22: an empty Send types the held messages now, the harness's own way. */
public final class HeldFlushTest {
    @Test public void onlyClaudeCodeWantsItsSendNowKeyAfterTheMessages() {
        assertTrue(HeldFlush.sendNowKey("claude"));
        assertFalse("Codex steers each typed message itself", HeldFlush.sendNowKey("codex"));
        assertFalse(HeldFlush.sendNowKey(""));
        assertFalse(HeldFlush.sendNowKey("agy"));
    }

    @Test public void anEmptySendFlushesFirstAndTheNextGoesOnlyAfterTheOneBeforeWasWritten() throws Exception {
        String composer = new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(
                "src/main/java/dev/herdr/android/ComposerBar.java")), java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(composer.contains("if (agent && host.flushHeld()) return;\n            host.enterKey();"));
        String gta = new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(
                "src/main/java/dev/herdr/android/GpuiTerminalActivity.java")), java.nio.charset.StandardCharsets.UTF_8);
        int next = gta.indexOf("private void flushNext(OfferGate.Tap tap, int sent) {");
        String body = gta.substring(next, gta.indexOf("private String paneKind(Here here) {", next));
        assertTrue("the next only after WRITTEN", body.contains("if (outcome == Outbox.Outcome.WRITTEN) {\n                flushNext(tap, sent + 1);"));
        assertTrue("the pane must still be the one tapped", body.contains("!here.mayType(tap, covered())"));
        assertTrue(body.contains("sent >= HeldFlush.MAX"));
    }
}
