package app.shoosh;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** Saves the user's own modes, e.g. "Church" for 2 hours. */
final class PresetStore {

    static final class Preset {
        final String name;
        final int minutes;

        Preset(String name, int minutes) {
            this.name = name;
            this.minutes = minutes;
        }
    }

    private static final String PREFS = "shoosh_presets";
    private static final String KEY_LIST = "presets";
    private static final String KEY_SEEDED = "seeded";

    private PresetStore() {
    }

    static List<Preset> load(Context c) {
        SharedPreferences p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (!p.getBoolean(KEY_SEEDED, false)) {
            // A couple of examples so the screen isn't empty on first launch.
            List<Preset> starter = new ArrayList<>();
            starter.add(new Preset("Church", 120));
            starter.add(new Preset("Meeting", 60));
            save(c, starter);
            p.edit().putBoolean(KEY_SEEDED, true).apply();
            return starter;
        }
        List<Preset> list = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(p.getString(KEY_LIST, "[]"));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                list.add(new Preset(o.getString("name"), o.getInt("minutes")));
            }
        } catch (JSONException ignored) {
            // Corrupt data: start fresh rather than crash.
        }
        return list;
    }

    /** Saves and refreshes everything that shows modes: shortcuts and widgets. */
    static void saveAndSync(Context c, List<Preset> list) {
        save(c, list);
        Shortcuts.update(c, list);
        ShooshWidgets.updateAll(c);
    }

    static void save(Context c, List<Preset> list) {
        JSONArray arr = new JSONArray();
        try {
            for (Preset preset : list) {
                arr.put(new JSONObject().put("name", preset.name).put("minutes", preset.minutes));
            }
        } catch (JSONException ignored) {
            return;
        }
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY_LIST, arr.toString()).apply();
    }
}
