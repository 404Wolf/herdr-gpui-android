package dev.herdr.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

/** Delayed session actions and focus replies must honor Home's latest navigation choice. */
public final class HomeNavigationTest {
    @Test public void aWorktreeReplyCannotReturnToHostAAfterPickingHostBWithNoDeacon() {
        HomeNavigation navigation = new HomeNavigation();
        long worktreeOnA = navigation.begin();
        long stillConnectedA = 17;
        String[] shownHost = {"A"};
        navigation.begin(); // pickMachine(B), which stays on Home because B has no Deacon.
        shownHost[0] = "B";
        assertFalse(navigation.complete(worktreeOnA, stillConnectedA, stillConnectedA, () -> shownHost[0] = "A"));
        assertEquals("B", shownHost[0]);
    }

    @Test public void bothTheWorktreeAndFocusStagesUseTheSameIntent() {
        HomeNavigation navigation = new HomeNavigation();
        long intent = navigation.begin();
        List<Runnable> focusReplies = new ArrayList<>();
        List<String> opened = new ArrayList<>();
        assertTrue(navigation.complete(intent, 17, 17,
                () -> focusReplies.add(() -> navigation.complete(intent, 17, 17, () -> opened.add("A/t1")))));
        navigation.begin(); // A newer choice between space.open and RemoteNative.focus replies.
        focusReplies.get(0).run();
        assertTrue(opened.isEmpty());
    }

    @Test public void aCurrentIntentCanFinishBothStagesWhileAnOldSessionCannotFinishEither() {
        HomeNavigation navigation = new HomeNavigation();
        long intent = navigation.begin();
        List<String> steps = new ArrayList<>();
        assertFalse(navigation.complete(intent, 17, 18, () -> steps.add("wrong session")));
        assertFalse(navigation.complete(intent, 0, 0, () -> steps.add("no session")));
        assertTrue(navigation.complete(intent, 17, 17, () -> steps.add("space.open")));
        assertTrue(navigation.complete(intent, 17, 17, () -> steps.add("focus")));
        assertEquals(Arrays.asList("space.open", "focus"), steps);
    }

    @Test public void aNewTerminalPickOnTheSameHostWinsOverAnEarlierOpen() {
        HomeNavigation navigation = new HomeNavigation();
        long first = navigation.begin();
        long second = navigation.begin();
        List<String> opened = new ArrayList<>();
        assertTrue(navigation.complete(second, 17, 17, () -> opened.add("t2")));
        assertFalse(navigation.complete(first, 17, 17, () -> opened.add("t1")));
        assertEquals(Arrays.asList("t2"), opened);
    }

    @Test public void homeChoicesAdvanceTheEpochAndAsyncOpensShareOneCompletionGate() throws IOException {
        String source = new String(Files.readAllBytes(Paths.get("src/main/java/dev/herdr/android/HomeActivity.java")),
                StandardCharsets.UTF_8);
        for (String method : Arrays.asList("private void pickMachine(", "private void openAgent(Agent agent)",
                "private void openWorktree(", "private void openHosts(", "private void openPicker(",
                "private void showMachinePicker(", "private void showWorktrees(", "private void toggleFold(",
                "protected void onPause(")) {
            assertTrue(method, body(source, method).contains("beginNavigation()"));
        }
        for (String method : Arrays.asList("private void openWorktree(", "private void openTerminal(",
                "private void sendToDeacon(", "private void openAgent(Agent agent, long intent)")) {
            assertTrue(method, body(source, method).contains("completeNavigation(machine, handle, intent"));
        }
        assertTrue(body(source, "private void completeNavigation(")
                .contains("navigation.complete(intent, handle, machine.handle, action)"));
        assertTrue(body(source, "private void startDeacon(")
                .contains("openTerminal(machine, address, \"Deacon\", handle, intent)"));
        assertTrue(body(source, "private void checkDeaconStart(")
                .contains("machine.deaconHandle, machine.deaconNavigation"));
        // An async completion must not mint itself a newer intent and defeat the guard.
        assertFalse(body(source, "private void openTerminal(").contains("beginNavigation()"));
        assertFalse(body(source, "private void openAgent(Agent agent, long intent)").contains("beginNavigation()"));
    }

    private static String body(String source, String declaration) {
        int start = source.indexOf(declaration);
        assertTrue(declaration, start >= 0);
        int end = source.indexOf("\n    }", start);
        assertTrue(declaration, end > start);
        return source.substring(start, end);
    }
}
