package dev.herdr.android;

import android.content.SharedPreferences;

final class AgentFooterPreference {
    private static final String SHOW = "show_agent_footer";

    private AgentFooterPreference() { }

    static boolean show(SharedPreferences preferences) {
        return preferences.getBoolean(SHOW, false);
    }

    static SharedPreferences.Editor save(SharedPreferences.Editor editor, boolean show) {
        return editor.putBoolean(SHOW, show);
    }
}
