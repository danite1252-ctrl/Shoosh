package app.shoosh;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.SweepGradient;
import android.os.SystemClock;
import android.view.View;
import android.widget.RemoteViews;

import java.util.List;

/** Draws both home-screen widgets and keeps them in sync with the app. */
final class ShooshWidgets {

    static final String ACTION_RESTORE = "app.shoosh.widget.RESTORE";
    static final String ACTION_DIAL_MINUS = "app.shoosh.widget.DIAL_MINUS";
    static final String ACTION_DIAL_PLUS = "app.shoosh.widget.DIAL_PLUS";

    private static final String PREFS = "shoosh_widget";
    private static final String KEY_DIAL_MINUTES = "dial_minutes";
    private static final int DIAL_STEP = 15;
    private static final int DIAL_MIN = 15;
    private static final int DIAL_MAX = 12 * 60;
    private static final int DIAL_DEFAULT = 30;
    /** The ring is full at this many minutes, matching the app. */
    private static final float RING_FULL_MINUTES = 180f;
    private static final int MAX_MODE_ROWS = 4;

    // Distinct request codes so each button keeps its own PendingIntent.
    private static final int RC_OPEN = 900;
    private static final int RC_RESTORE = 901;
    private static final int RC_MINUS = 902;
    private static final int RC_PLUS = 903;
    private static final int RC_GO = 904;
    private static final int RC_MODE_BASE = 920;

    private ShooshWidgets() {
    }

    /** Redraws every Shoosh widget on the home screen. Safe to call often. */
    static void updateAll(Context c) {
        AppWidgetManager m = AppWidgetManager.getInstance(c);
        if (m == null) {
            return;
        }
        int[] modeIds = m.getAppWidgetIds(new ComponentName(c, ModesWidget.class));
        if (modeIds.length > 0) {
            m.updateAppWidget(modeIds, buildModes(c));
        }
        int[] dialIds = m.getAppWidgetIds(new ComponentName(c, DialWidget.class));
        if (dialIds.length > 0) {
            m.updateAppWidget(dialIds, buildDial(c));
        }
    }

    static void adjustDial(Context c, int deltaMinutes) {
        int next = Math.max(DIAL_MIN, Math.min(DIAL_MAX, dialMinutes(c) + deltaMinutes));
        prefs(c).edit().putInt(KEY_DIAL_MINUTES, next).apply();
    }

    static int dialStep() {
        return DIAL_STEP;
    }

    // ---- modes widget ----

    private static RemoteViews buildModes(Context c) {
        RemoteViews v = new RemoteViews(c.getPackageName(), R.layout.widget_modes);
        v.setOnClickPendingIntent(R.id.widget_header, openApp(c));
        v.removeAllViews(R.id.widget_modes_list);

        if (SilentController.isActive(c)) {
            String label = SilentController.label(c);
            String until = c.getString(R.string.silent_until,
                    SilentController.formatTime(c, SilentController.endTime(c)));
            v.setTextViewText(R.id.widget_active_text,
                    label == null ? until : label + " · " + until);
            v.setOnClickPendingIntent(R.id.widget_restore, broadcast(c, ACTION_RESTORE, RC_RESTORE));
            v.setViewVisibility(R.id.widget_active, View.VISIBLE);
            v.setViewVisibility(R.id.widget_modes_list, View.GONE);
            v.setViewVisibility(R.id.widget_empty, View.GONE);
            return v;
        }

        v.setViewVisibility(R.id.widget_active, View.GONE);
        v.setViewVisibility(R.id.widget_modes_list, View.VISIBLE);
        List<PresetStore.Preset> modes = PresetStore.load(c);
        int count = Math.min(MAX_MODE_ROWS, modes.size());
        v.setViewVisibility(R.id.widget_empty, count == 0 ? View.VISIBLE : View.GONE);
        v.setOnClickPendingIntent(R.id.widget_empty, openApp(c));
        for (int i = 0; i < count; i++) {
            PresetStore.Preset mode = modes.get(i);
            RemoteViews row = new RemoteViews(c.getPackageName(), R.layout.widget_mode_row);
            row.setTextViewText(R.id.widget_mode_name, mode.name);
            row.setTextViewText(R.id.widget_mode_time, MainActivity.formatDuration(mode.minutes));
            row.setOnClickPendingIntent(R.id.widget_mode_row,
                    startActivity(c, RC_MODE_BASE + i, mode.name, mode.minutes));
            v.addView(R.id.widget_modes_list, row);
        }
        return v;
    }

    // ---- timer (ring) widget ----

    private static RemoteViews buildDial(Context c) {
        RemoteViews v = new RemoteViews(c.getPackageName(), R.layout.widget_dial);
        float progress;

        if (SilentController.isActive(c)) {
            long now = System.currentTimeMillis();
            long end = SilentController.endTime(c);
            long start = SilentController.startTime(c);
            progress = (float) (end - now) / Math.max(1L, end - start);

            v.setViewVisibility(R.id.widget_dial_time, View.GONE);
            v.setViewVisibility(R.id.widget_dial_chrono, View.VISIBLE);
            v.setChronometer(R.id.widget_dial_chrono,
                    SystemClock.elapsedRealtime() + Math.max(0L, end - now), null, true);
            v.setChronometerCountDown(R.id.widget_dial_chrono, true);
            v.setViewVisibility(R.id.widget_dial_minus, View.GONE);
            v.setViewVisibility(R.id.widget_dial_plus, View.GONE);
            // While running, the centre opens Shoosh (to extend or end it).
            v.setOnClickPendingIntent(R.id.widget_dial_zzz, openApp(c));
        } else {
            int minutes = dialMinutes(c);
            progress = Math.min(1f, minutes / RING_FULL_MINUTES);

            v.setChronometer(R.id.widget_dial_chrono, SystemClock.elapsedRealtime(), null, false);
            v.setViewVisibility(R.id.widget_dial_chrono, View.GONE);
            v.setViewVisibility(R.id.widget_dial_time, View.VISIBLE);
            v.setTextViewText(R.id.widget_dial_time, MainActivity.formatDuration(minutes));
            v.setViewVisibility(R.id.widget_dial_minus, View.VISIBLE);
            v.setViewVisibility(R.id.widget_dial_plus, View.VISIBLE);
            v.setOnClickPendingIntent(R.id.widget_dial_minus, broadcast(c, ACTION_DIAL_MINUS, RC_MINUS));
            v.setOnClickPendingIntent(R.id.widget_dial_plus, broadcast(c, ACTION_DIAL_PLUS, RC_PLUS));
            v.setOnClickPendingIntent(R.id.widget_dial_zzz, startActivity(c, RC_GO, null, minutes));
        }
        v.setImageViewBitmap(R.id.widget_dial_ring, ringBitmap(c, progress));
        return v;
    }

    /** Same look as the in-app dial: grey track with a purple gradient arc. */
    private static Bitmap ringBitmap(Context c, float progress) {
        float density = c.getResources().getDisplayMetrics().density;
        int size = Math.round(120 * density);
        float stroke = 10 * density;
        Bitmap bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bmp);
        float inset = stroke / 2f + density;
        RectF oval = new RectF(inset, inset, size - inset, size - inset);

        Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
        track.setStyle(Paint.Style.STROKE);
        track.setStrokeWidth(stroke);
        track.setColor(c.getColor(R.color.track));
        canvas.drawArc(oval, 0f, 360f, false, track);

        float p = Math.max(0f, Math.min(1f, progress));
        if (p > 0f) {
            int startColor = c.getColor(R.color.accent_end);
            int endColor = c.getColor(R.color.accent);
            SweepGradient shader = new SweepGradient(size / 2f, size / 2f,
                    new int[] {startColor, endColor, startColor}, new float[] {0f, 0.5f, 1f});
            Matrix m = new Matrix();
            m.setRotate(-90f, size / 2f, size / 2f);
            shader.setLocalMatrix(m);

            Paint arc = new Paint(Paint.ANTI_ALIAS_FLAG);
            arc.setStyle(Paint.Style.STROKE);
            arc.setStrokeWidth(stroke);
            arc.setStrokeCap(Paint.Cap.ROUND);
            arc.setShader(shader);
            canvas.drawArc(oval, -90f, 360f * p, false, arc);
        }
        return bmp;
    }

    // ---- helpers ----

    private static int dialMinutes(Context c) {
        return prefs(c).getInt(KEY_DIAL_MINUTES, DIAL_DEFAULT);
    }

    private static PendingIntent openApp(Context c) {
        Intent i = new Intent(c, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return PendingIntent.getActivity(c, RC_OPEN, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private static PendingIntent startActivity(Context c, int requestCode, String name, int minutes) {
        return PendingIntent.getActivity(c, requestCode, Shortcuts.startIntent(c, name, minutes),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private static PendingIntent broadcast(Context c, String action, int requestCode) {
        Intent i = new Intent(c, WidgetActions.class).setAction(action);
        return PendingIntent.getBroadcast(c, requestCode, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
