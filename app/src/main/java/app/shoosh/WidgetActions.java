package app.shoosh;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Handles the widget buttons that don't open a screen: −, + and "Sound back on". */
public class WidgetActions extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (ShooshWidgets.ACTION_RESTORE.equals(action)) {
            RestoreService.start(context);
        } else if (ShooshWidgets.ACTION_DIAL_MINUS.equals(action)) {
            ShooshWidgets.adjustDial(context, -ShooshWidgets.dialStep());
        } else if (ShooshWidgets.ACTION_DIAL_PLUS.equals(action)) {
            ShooshWidgets.adjustDial(context, ShooshWidgets.dialStep());
        }
        ShooshWidgets.updateAll(context);
    }
}
