package app.shoosh;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.drawable.Icon;
import android.media.AudioManager;
import android.os.Build;
import android.text.format.DateFormat;

import java.util.Date;

/**
 * All of Shoosh's logic lives here: remembering how the phone was set,
 * switching to silent, scheduling the restore, and putting things back.
 */
final class SilentController {

    static final String ACTION_RESTORE = "app.shoosh.ACTION_RESTORE";

    private static final String PREFS = "shoosh";
    private static final String KEY_ACTIVE = "active";
    private static final String KEY_START = "start_time";
    private static final String KEY_END = "end_time";
    private static final String KEY_LABEL = "label";
    private static final String KEY_PREV_RINGER = "prev_ringer";
    private static final String KEY_PREV_FILTER = "prev_filter";
    private static final String KEY_PREV_RING_VOL = "prev_ring_volume";
    private static final String KEY_PREV_NOTIF_VOL = "prev_notification_volume";
    private static final String KEY_SET_MODE = "set_mode";

    private static final String CHANNEL_ID = "shoosh_timer";
    private static final int NOTIFICATION_ID = 1;
    private static final int RC_ALARM = 0;
    private static final int RC_NOTIFICATION_ACTION = 1;
    private static final int RC_OPEN_APP = 2;

    private SilentController() {
    }

    static boolean hasDndAccess(Context c) {
        return notifications(c).isNotificationPolicyAccessGranted();
    }

    static boolean isActive(Context c) {
        return prefs(c).getBoolean(KEY_ACTIVE, false);
    }

    static long endTime(Context c) {
        return prefs(c).getLong(KEY_END, 0L);
    }

    static long startTime(Context c) {
        return prefs(c).getLong(KEY_START, 0L);
    }

    /** The mode name the timer was started from, or null. */
    static String label(Context c) {
        return prefs(c).getString(KEY_LABEL, null);
    }

    /**
     * Puts the phone on vibrate (no sounds, vibration stays on) for the given
     * duration. If the phone is already fully silent it is left silent, so
     * Shoosh never makes it louder. If a timer is already
     * running, the new duration replaces it but the originally saved sound
     * setting is kept, so "before" always means before the first timer.
     */
    static boolean start(Context c, long durationMs, String label) {
        if (!hasDndAccess(c)) {
            return false;
        }
        AudioManager audio = audio(c);
        SharedPreferences p = prefs(c);
        SharedPreferences.Editor e = p.edit();
        int target = AudioManager.RINGER_MODE_VIBRATE;
        if (!p.getBoolean(KEY_ACTIVE, false)) {
            if (audio.getRingerMode() == AudioManager.RINGER_MODE_SILENT) {
                target = AudioManager.RINGER_MODE_SILENT;
            }
            e.putInt(KEY_SET_MODE, target);
            e.putInt(KEY_PREV_RINGER, audio.getRingerMode());
            e.putInt(KEY_PREV_FILTER, notifications(c).getCurrentInterruptionFilter());
            e.putInt(KEY_PREV_RING_VOL, audio.getStreamVolume(AudioManager.STREAM_RING));
            e.putInt(KEY_PREV_NOTIF_VOL, audio.getStreamVolume(AudioManager.STREAM_NOTIFICATION));
        } else {
            target = p.getInt(KEY_SET_MODE, AudioManager.RINGER_MODE_VIBRATE);
        }
        try {
            audio.setRingerMode(target);
        } catch (SecurityException ex) {
            return false;
        }
        long now = System.currentTimeMillis();
        long end = now + durationMs;
        e.putBoolean(KEY_ACTIVE, true).putLong(KEY_START, now).putLong(KEY_END, end)
                .putString(KEY_LABEL, label).commit();
        scheduleAlarm(c, end);
        showNotification(c, end);
        ShooshWidgets.updateAll(c);
        return true;
    }

    /** Adds time to a running timer. */
    static void extend(Context c, long extraMs) {
        SharedPreferences p = prefs(c);
        if (!p.getBoolean(KEY_ACTIVE, false)) {
            return;
        }
        long now = System.currentTimeMillis();
        long end = Math.max(p.getLong(KEY_END, now), now) + extraMs;
        // Restart the ring from "now" so it shows the full new stretch.
        p.edit().putLong(KEY_START, now).putLong(KEY_END, end).commit();
        scheduleAlarm(c, end);
        showNotification(c, end);
        ShooshWidgets.updateAll(c);
    }

    /**
     * Puts the sound back to how it was before the timer started. Returns false
     * if Android ignored the change (Android 17 does this for apps in the
     * background); the timer is then kept so opening Shoosh or tapping the
     * notification retries.
     */
    static boolean restore(Context c) {
        SharedPreferences p = prefs(c);
        NotificationManager nm = notifications(c);
        if (p.getBoolean(KEY_ACTIVE, false)) {
            int prevRinger = p.getInt(KEY_PREV_RINGER, AudioManager.RINGER_MODE_NORMAL);
            int prevFilter = p.getInt(KEY_PREV_FILTER, NotificationManager.INTERRUPTION_FILTER_UNKNOWN);
            int prevRingVol = p.getInt(KEY_PREV_RING_VOL, -1);
            int prevNotifVol = p.getInt(KEY_PREV_NOTIF_VOL, -1);
            int setMode = p.getInt(KEY_SET_MODE, AudioManager.RINGER_MODE_VIBRATE);
            AudioManager audio = audio(c);
            // Only undo our change if the phone is still how we left it. If the
            // user switched it themselves in the meantime, respect their choice.
            if (audio.getRingerMode() == setMode) {
                try {
                    audio.setRingerMode(prevRinger);
                    if (prevRinger != setMode && audio.getRingerMode() == setMode) {
                        showRetryNotification(c);
                        ShooshWidgets.updateAll(c);
                        return false;
                    }
                    // Put the exact volume levels back too (e.g. 3/4), since not
                    // every phone restores them on its own after vibrate mode.
                    if (prevRinger == AudioManager.RINGER_MODE_NORMAL) {
                        setVolume(audio, AudioManager.STREAM_RING, prevRingVol);
                        setVolume(audio, AudioManager.STREAM_NOTIFICATION, prevNotifVol);
                    }
                    if (prevFilter != NotificationManager.INTERRUPTION_FILTER_UNKNOWN
                            && nm.isNotificationPolicyAccessGranted()
                            && nm.getCurrentInterruptionFilter() != prevFilter) {
                        nm.setInterruptionFilter(prevFilter);
                    }
                } catch (SecurityException ignored) {
                    // Access was revoked mid-timer; nothing more we can do.
                }
            }
        }
        p.edit().clear().commit();
        cancelAlarm(c);
        nm.cancel(NOTIFICATION_ID);
        ShooshWidgets.updateAll(c);
        return true;
    }

    /** Called after a reboot or app update, which clear scheduled alarms. */
    static void resume(Context c) {
        if (!isActive(c)) {
            return;
        }
        long end = endTime(c);
        if (end <= System.currentTimeMillis()) {
            RestoreService.start(c);
        } else {
            scheduleAlarm(c, end);
            showNotification(c, end);
            ShooshWidgets.updateAll(c);
        }
    }

    static String formatTime(Context c, long when) {
        return DateFormat.getTimeFormat(c).format(new Date(when));
    }

    // ---- internals ----

    /** Shown only if Android blocked the automatic restore: one tap fixes it. */
    private static void showRetryNotification(Context c) {
        NotificationManager nm = notifications(c);
        Intent openIntent = new Intent(c, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent open = PendingIntent.getActivity(c, RC_OPEN_APP, openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Action restoreAction = new Notification.Action.Builder(
                Icon.createWithResource(c, R.drawable.ic_volume),
                c.getString(R.string.restore_now),
                restoreIntent(c, RC_NOTIFICATION_ACTION)).build();
        Notification n = new Notification.Builder(c, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_shoosh)
                .setColor(c.getColor(R.color.accent))
                .setContentTitle(c.getString(R.string.retry_title))
                .setContentText(c.getString(R.string.retry_text))
                .setContentIntent(open)
                .setAutoCancel(true)
                .addAction(restoreAction)
                .build();
        try {
            nm.notify(NOTIFICATION_ID, n);
        } catch (SecurityException ignored) {
            // Notifications off; opening Shoosh still restores the sound.
        }
    }

    private static void setVolume(AudioManager audio, int stream, int level) {
        if (level <= 0) {
            return;
        }
        try {
            if (audio.getStreamVolume(stream) != level) {
                audio.setStreamVolume(stream, level, 0);
            }
        } catch (SecurityException ignored) {
            // Some phones lock individual streams; the ringer mode is still restored.
        }
    }

    private static void scheduleAlarm(Context c, long end) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        PendingIntent pi = restoreIntent(c, RC_ALARM);
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, end, pi);
        } else {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, end, pi);
        }
    }

    private static void cancelAlarm(Context c) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        am.cancel(restoreIntent(c, RC_ALARM));
    }

    private static PendingIntent restoreIntent(Context c, int requestCode) {
        Intent i = new Intent(c, RestoreReceiver.class).setAction(ACTION_RESTORE);
        return PendingIntent.getBroadcast(c, requestCode, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private static void showNotification(Context c, long end) {
        NotificationManager nm = notifications(c);
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, c.getString(R.string.channel_name), NotificationManager.IMPORTANCE_LOW);
        channel.setShowBadge(false);
        nm.createNotificationChannel(channel);

        Intent openIntent = new Intent(c, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent open = PendingIntent.getActivity(c, RC_OPEN_APP, openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification.Action restoreAction = new Notification.Action.Builder(
                Icon.createWithResource(c, R.drawable.ic_volume),
                c.getString(R.string.restore_now),
                restoreIntent(c, RC_NOTIFICATION_ACTION)).build();

        String until = c.getString(R.string.silent_until, formatTime(c, end));
        String label = label(c);
        String title = label == null ? until : label + " · " + until;

        Notification n = new Notification.Builder(c, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_shoosh)
                .setColor(c.getColor(R.color.accent))
                .setContentTitle(title)
                .setContentText(c.getString(R.string.notif_text))
                .setWhen(end)
                .setShowWhen(true)
                .setUsesChronometer(true)
                .setChronometerCountDown(true)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(open)
                .addAction(restoreAction)
                .build();
        try {
            nm.notify(NOTIFICATION_ID, n);
        } catch (SecurityException ignored) {
            // Notifications not allowed; the timer still works without one.
        }
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static AudioManager audio(Context c) {
        return (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
    }

    private static NotificationManager notifications(Context c) {
        return (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
    }
}
