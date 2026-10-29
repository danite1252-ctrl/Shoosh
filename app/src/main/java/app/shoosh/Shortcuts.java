package app.shoosh;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.graphics.drawable.Icon;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Puts the user's modes in the long-press menu on the Shoosh icon. */
final class Shortcuts {

    static final String ACTION_START_MODE = "app.shoosh.START_MODE";
    static final String EXTRA_NAME = "name";
    static final String EXTRA_MINUTES = "minutes";

    private static final int MAX_SHORTCUTS = 4;

    private Shortcuts() {
    }

    static void update(Context c, List<PresetStore.Preset> modes) {
        ShortcutManager sm = c.getSystemService(ShortcutManager.class);
        if (sm == null) {
            return;
        }
        int max = Math.min(modes.size(),
                Math.min(MAX_SHORTCUTS, sm.getMaxShortcutCountPerActivity()));
        List<ShortcutInfo> list = new ArrayList<>();
        for (int i = 0; i < max; i++) {
            PresetStore.Preset mode = modes.get(i);
            list.add(new ShortcutInfo.Builder(c, idFor(mode.name))
                    .setShortLabel(mode.name)
                    .setLongLabel(c.getString(R.string.shortcut_long,
                            mode.name, MainActivity.formatDuration(mode.minutes)))
                    .setIcon(Icon.createWithResource(c, R.mipmap.ic_shortcut))
                    .setIntent(startIntent(c, mode.name, mode.minutes))
                    .setRank(i)
                    .build());
        }
        try {
            sm.setDynamicShortcuts(list);
        } catch (IllegalStateException | IllegalArgumentException ignored) {
            // Rate-limited or rejected by the launcher; the app still works.
        }
    }

    /** Opens the invisible StartModeActivity, which starts the timer and closes. */
    static Intent startIntent(Context c, String name, int minutes) {
        Intent i = new Intent(c, StartModeActivity.class)
                .setAction(ACTION_START_MODE)
                .putExtra(EXTRA_MINUTES, minutes)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        if (name != null) {
            i.putExtra(EXTRA_NAME, name);
        }
        return i;
    }

    /** Stable per name, so a shortcut pinned to the home screen keeps meaning the same mode. */
    private static String idFor(String name) {
        return "mode_" + name.trim().toLowerCase(Locale.ROOT);
    }
}
