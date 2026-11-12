package dev.herdr.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** A destructive control confirms on its own second tap, within a short window. */
public final class ConfirmInPlaceTest {
    @Test public void theFirstTapArmsAndASecondWithinTheWindowConfirms() {
        ConfirmInPlace.Arm arm = new ConfirmInPlace.Arm();
        assertFalse(arm.tap(1_000));
        assertTrue(arm.armed(1_500));
        assertTrue(arm.tap(1_000 + ConfirmInPlace.WINDOW_MS));
        // Confirmed: the next tap starts over.
        assertFalse(arm.tap(1_000 + ConfirmInPlace.WINDOW_MS + 1));
    }

    @Test public void aLateSecondTapOnlyArmsAgain() {
        ConfirmInPlace.Arm arm = new ConfirmInPlace.Arm();
        assertFalse(arm.tap(0));
        assertFalse(arm.tap(ConfirmInPlace.WINDOW_MS + 1));
        assertTrue(arm.tap(ConfirmInPlace.WINDOW_MS + 2));
    }

    @Test public void theArmedLabelSaysWhatTheSecondTapDoes() {
        assertEquals("Tap again to close terminal", ConfirmInPlace.again("Close terminal"));
        assertEquals("Tap again to delete profile", ConfirmInPlace.again("Delete profile"));
    }
}
