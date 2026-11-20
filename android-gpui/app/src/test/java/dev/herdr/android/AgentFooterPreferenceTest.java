package dev.herdr.android;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.SharedPreferences;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;
import org.junit.Test;

public class AgentFooterPreferenceTest {
    @Test
    public void footerIsHiddenByDefaultAndBothChoicesSurviveReload() {
        Map<String, Object> disk = new HashMap<>();
        SharedPreferences preferences = preferences(disk);
        assertFalse(AgentFooterPreference.show(preferences));
        AgentFooterPreference.save(preferences.edit(), true).apply();
        assertTrue(AgentFooterPreference.show(preferences(disk)));
        AgentFooterPreference.save(preferences.edit(), false).apply();
        assertFalse(AgentFooterPreference.show(preferences(disk)));
    }

    private static SharedPreferences preferences(Map<String, Object> disk) {
        Map<String, Object> pending = new HashMap<>();
        SharedPreferences.Editor editor = (SharedPreferences.Editor) Proxy.newProxyInstance(
                SharedPreferences.Editor.class.getClassLoader(), new Class<?>[]{SharedPreferences.Editor.class},
                (self, method, args) -> {
                    switch (method.getName()) {
                        case "putBoolean":
                            pending.put((String) args[0], args[1]);
                            return self;
                        case "apply":
                            disk.putAll(pending);
                            pending.clear();
                            return null;
                        default: throw new AssertionError(method);
                    }
                });
        return (SharedPreferences) Proxy.newProxyInstance(SharedPreferences.class.getClassLoader(),
                new Class<?>[]{SharedPreferences.class}, (self, method, args) -> {
                    switch (method.getName()) {
                        case "getBoolean": return disk.getOrDefault(args[0], args[1]);
                        case "edit": return editor;
                        default: throw new AssertionError(method);
                    }
                });
    }
}
