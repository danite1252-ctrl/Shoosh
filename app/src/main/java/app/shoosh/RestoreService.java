package app.shoosh;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

/**
 * Turns the sound back on from the background.
 *
 * Android 17 silently ignores ringer and volume changes from an app that has
 * no visible screen, unless it is running a foreground service. So when the
 * timer ends (or "Sound back on" is tapped in the notification or widget),
 * Shoosh starts this service for a moment, restores the sound, and stops.
 * It finishes in well under a second, so Android normally never shows its
 * notification.
 */
public class RestoreService extends Service {

    private static final String CHANNEL_ID = "shoosh_restore";
    private static final int FGS_NOTIFICATION_ID = 2;
    /** Lets the system register the foreground state before we change audio. */
    private static final long SETTLE_MS = 300;

    /** Restores the sound via the foreground service, falling back to a direct call. */
    static void start(Context c) {
        try {
            c.startForegroundService(new Intent(c, RestoreService.class));
        } catch (RuntimeException notAllowed) {
            // Background start refused; try directly. If Android ignores it,
            // SilentController keeps the timer state and asks the user to tap.
            SilentController.restore(c);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        goForeground();
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            SilentController.restore(this);
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf(startId);
        }, SETTLE_MS);
        return START_NOT_STICKY;
    }

    private void goForeground() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                getString(R.string.restore_channel_name), NotificationManager.IMPORTANCE_LOW);
        channel.setShowBadge(false);
        nm.createNotificationChannel(channel);

        Notification n = new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_shoosh)
                .setColor(getColor(R.color.accent))
                .setContentTitle(getString(R.string.restoring))
                .build();
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(FGS_NOTIFICATION_ID, n,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            } else {
                startForeground(FGS_NOTIFICATION_ID, n);
            }
        } catch (RuntimeException ignored) {
            // Couldn't go foreground; restore() still runs and retries later if ignored.
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
