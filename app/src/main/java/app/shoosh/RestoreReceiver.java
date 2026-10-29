package app.shoosh;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Fires when the timer runs out, or when "Turn sound back on" is tapped in the notification. */
public class RestoreReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (SilentController.ACTION_RESTORE.equals(intent.getAction())) {
            RestoreService.start(context);
        }
    }
}
