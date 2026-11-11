package dev.herdr.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import org.json.JSONObject;
import org.junit.Test;

public class FileDocumentTest {
    @Test public void splitsLinesKeepingNoExtraLineAndExpandingTabs() throws Exception {
        assertEquals(Arrays.asList("a", "", "b"), FileDocument.split("a\n\nb\n"));
        assertEquals(Arrays.asList("a", "b"), FileDocument.split("a\r\nb"));
        assertEquals(Arrays.asList("    x"), FileDocument.split("\tx\n"));
        assertEquals(Arrays.asList(), FileDocument.split(""));
    }

    @Test public void aReadIsHighlightedAndKnowsWhenItWasCut() throws Exception {
        FileDocument doc = FileDocument.parse("src/a.rs", 2,
                "{\"queued\":true,\"path\":\"/w/src/a.rs\",\"text\":\"fn a() {}\\nlet b = 1;\\n\",\"lines\":40000,\"last\":2}");
        assertNull(doc.error);
        assertEquals("/w/src/a.rs", doc.path);
        assertEquals("a.rs", doc.name());
        assertEquals(2, doc.lines.size());
        assertEquals(40000, doc.total);
        assertEquals(SyntaxHighlighter.KEYWORD, doc.spans.get(0).get(0).kind);
        assertEquals(0, doc.scrollRow());
    }

    @Test public void failuresBecomeReadableReasons() throws Exception {
        FileDocument doc = FileDocument.parse("/bin/sh", 0, "{\"queued\":false,\"error\":\"\\\"not_text\\\": binary\"}");
        assertEquals("Not a text file", doc.error);
        assertEquals("No such file", FileDocument.parse("x", 0, "{\"queued\":false,\"error\":\"\\\"not_found\\\": x\"}").error);
        assertEquals("unreadable reply", FileDocument.parse("x", 0, "not json").error);
    }

    @Test public void theMarkedLineScrollsWithContextAbove() throws Exception {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < 100; i++) text.append("l").append(i).append('\n');
        String raw = new JSONObject().put("queued", true).put("text", text.toString()).toString();
        assertEquals(46, FileDocument.parse("/a", 50, raw).scrollRow());
        assertEquals(0, FileDocument.parse("/a", 2, raw).scrollRow());
        assertEquals(96, FileDocument.parse("/a", 900, raw).scrollRow());
    }

    @Test public void onlyANewerEventForTheShownTerminalOpens() throws Exception {
        JSONObject event = new JSONObject().put("terminal", "t7").put("path", "/w/a.rs").put("line", 3).put("seq", 12);
        assertTrue(FileDocument.shouldOpen(event, "t7", 11));
        assertFalse(FileDocument.shouldOpen(event, "t7", 12));
        assertFalse(FileDocument.shouldOpen(event, "t8", 0));
        assertFalse(FileDocument.shouldOpen(event, "", 0));
        assertFalse(FileDocument.shouldOpen(null, "t7", 0));
        assertFalse(FileDocument.shouldOpen(new JSONObject(event.toString()).put("path", "rel.rs"), "t7", 0));
    }
}
