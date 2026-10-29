package app.shoosh;

import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;

/** Home-screen widget with the ring timer: −/+ to set, zzz to start. */
public class DialWidget extends AppWidgetProvider {
    @Override
    public void onUpdate(Context context, AppWidgetManager manager, int[] ids) {
        ShooshWidgets.updateAll(context);
    }
}
