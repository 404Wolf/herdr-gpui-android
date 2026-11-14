package dev.herdr.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import org.junit.Test;

public final class GpuiChromeTest {
    private static int[] roles() {
        int[] roles = new int[GpuiChrome.ROLE_COUNT];
        for (int i = 0; i < roles.length - 1; i++) roles[i] = 0xff000000 | (i + 1);
        return roles;
    }

    @Test
    public void rejectsExportsThatDoNotMatchTheRendererOrder() {
        assertThrows(IllegalStateException.class, () -> GpuiChrome.of(null));
        assertThrows(IllegalStateException.class, () -> GpuiChrome.of(new int[GpuiChrome.ROLE_COUNT - 1]));
        assertThrows(IllegalStateException.class, () -> GpuiChrome.of(new int[GpuiChrome.ROLE_COUNT + 1]));
    }

    @Test
    public void agentStatesMapToTheirRolesAndUnknownStatesStayNeutral() {
        GpuiChrome chrome = GpuiChrome.of(roles());
        assertEquals(chrome.working, chrome.status("working"));
        assertEquals(chrome.danger, chrome.status("blocked"));
        assertEquals(chrome.info, chrome.status("done"));
        assertEquals(chrome.success, chrome.status("idle"));
        assertEquals(chrome.onSurfaceVariant, chrome.status("unknown"));
        assertEquals(chrome.onSurfaceVariant, chrome.status(null));
    }

    @Test
    public void alphaReplacesOnlyTheAlphaChannel() {
        assertEquals(0x29123456, GpuiChrome.withAlpha(0xff123456, 0x29));
        assertEquals(0x00abcdef, GpuiChrome.withAlpha(0x80abcdef, 0));
    }

    @Test public void mixTintsTheSurfaceOpaquely() {
        assertEquals(0xff000000, GpuiChrome.mix(0xff000000, 0xffF07178, 0f));
        assertEquals(0xff261213, GpuiChrome.mix(0xff000000, 0xffF07178, 0.16f));
    }
}
