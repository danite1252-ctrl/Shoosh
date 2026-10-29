package app.shoosh;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.HapticFeedbackConstants;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.NumberPicker;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity {

    private static final int STEP_MINUTES = 5;
    private static final int MIN_MINUTES = 5;
    private static final int MAX_MINUTES = 12 * 60;
    /** Idle ring is full at this many minutes. */
    private static final float RING_FULL_MINUTES = 180f;
    private static final String STATE_MINUTES = "minutes";

    private int selectedMinutes = 30;

    private View permissionGroup;
    private View idleGroup;
    private DialView dial;
    private TextView dialLabel;
    private TextView dialTime;
    private TextView dialSub;
    private TextView mainButton;
    private TextView extendButton;
    private TextView[] chips;
    private ViewGroup modesContainer;
    private List<PresetStore.Preset> modes;
    private View updateBanner;
    private TextView updateText;
    private TextView updateButton;
    private Updater.Release pendingUpdate;
    private final int[] chipMinutes = {15, 30, 60, 120};

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            refresh();
            handler.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        if (savedInstanceState != null) {
            selectedMinutes = savedInstanceState.getInt(STATE_MINUTES, selectedMinutes);
        }

        permissionGroup = findViewById(R.id.permission_group);
        idleGroup = findViewById(R.id.idle_group);
        dial = findViewById(R.id.dial);
        dialLabel = findViewById(R.id.dial_label);
        dialTime = findViewById(R.id.dial_time);
        dialSub = findViewById(R.id.dial_sub);
        mainButton = findViewById(R.id.main_button);
        extendButton = findViewById(R.id.extend_button);
        modesContainer = findViewById(R.id.modes_container);
        updateBanner = findViewById(R.id.update_banner);
        updateText = findViewById(R.id.update_text);
        updateButton = findViewById(R.id.update_button);
        updateButton.setOnClickListener(v -> startUpdate());
        if (savedInstanceState == null) {
            handleInstallStatus(getIntent());
        }

        modes = PresetStore.load(this);
        renderModes();
        // Keep long-press shortcuts and widgets in step with the saved modes.
        Shortcuts.update(this, modes);
        ShooshWidgets.updateAll(this);
        findViewById(R.id.new_mode_button).setOnClickListener(v -> showModeDialog(-1));

        chips = new TextView[] {
                findViewById(R.id.preset_15),
                findViewById(R.id.preset_30),
                findViewById(R.id.preset_60),
                findViewById(R.id.preset_120)
        };
        for (int i = 0; i < chips.length; i++) {
            final int minutes = chipMinutes[i];
            chips[i].setOnClickListener(v -> {
                v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                setMinutes(minutes);
            });
        }

        findViewById(R.id.minus_button).setOnClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
            setMinutes(selectedMinutes - STEP_MINUTES);
        });
        findViewById(R.id.plus_button).setOnClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
            setMinutes(selectedMinutes + STEP_MINUTES);
        });

        mainButton.setOnClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
            if (SilentController.isActive(this)) {
                SilentController.restore(this);
            } else if (SilentController.start(this, selectedMinutes * 60_000L, null)) {
                Toast.makeText(this, R.string.now_silent, Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, R.string.start_failed, Toast.LENGTH_LONG).show();
            }
            refresh();
        });

        extendButton.setOnClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
            SilentController.extend(this, 15 * 60_000L);
            refresh();
        });

        findViewById(R.id.grant_button).setOnClickListener(v ->
                startActivity(new Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)));

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] {Manifest.permission.POST_NOTIFICATIONS}, 1);
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt(STATE_MINUTES, selectedMinutes);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleInstallStatus(intent);
    }

    @Override
    protected void onResume() {
        super.onResume();
        Updater.check(this, this::showUpdate);
        // Safety net: if the app was force-stopped, the alarm may never have fired.
        if (SilentController.isActive(this)
                && SilentController.endTime(this) <= System.currentTimeMillis()) {
            SilentController.restore(this);
        }
        handler.post(tick);
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(tick);
    }

    // ---- updates ----

    private void showUpdate(Updater.Release release) {
        if (isFinishing() || isDestroyed()) {
            return;
        }
        pendingUpdate = release;
        updateText.setText(getString(R.string.update_available, release.versionName));
        updateButton.setText(R.string.update_install);
        updateButton.setEnabled(true);
        updateBanner.setVisibility(View.VISIBLE);
    }

    private void startUpdate() {
        if (pendingUpdate == null) {
            return;
        }
        boolean started = Updater.install(this, pendingUpdate, message -> {
            Toast.makeText(this, getString(R.string.update_failed, message), Toast.LENGTH_LONG).show();
            updateButton.setText(R.string.update_install);
            updateButton.setEnabled(true);
        });
        if (started) {
            updateButton.setText(R.string.update_downloading);
            updateButton.setEnabled(false);
        } else {
            Toast.makeText(this, R.string.update_allow_installs, Toast.LENGTH_LONG).show();
        }
    }

    /** Android reports install progress here, and asks us to show its confirmation. */
    private void handleInstallStatus(Intent intent) {
        if (intent == null || !Updater.ACTION_INSTALL_STATUS.equals(intent.getAction())) {
            return;
        }
        int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            Intent confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT);
            if (confirm != null) {
                startActivity(confirm);
            }
        } else if (status != PackageInstaller.STATUS_SUCCESS) {
            String message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
            Toast.makeText(this, getString(R.string.update_failed, message != null ? message : ""),
                    Toast.LENGTH_LONG).show();
            if (updateButton != null) {
                updateButton.setText(R.string.update_install);
                updateButton.setEnabled(true);
            }
        }
    }

    // ---- modes ----

    private void renderModes() {
        modesContainer.removeAllViews();
        LayoutInflater inflater = LayoutInflater.from(this);
        for (int i = 0; i < modes.size(); i++) {
            final int index = i;
            final PresetStore.Preset mode = modes.get(i);
            View row = inflater.inflate(R.layout.item_mode, modesContainer, false);
            ((TextView) row.findViewById(R.id.mode_name)).setText(mode.name);
            ((TextView) row.findViewById(R.id.mode_duration)).setText(formatDuration(mode.minutes));
            row.setOnClickListener(v -> {
                v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                startMode(mode);
            });
            row.setOnLongClickListener(v -> {
                v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                showModeDialog(index);
                return true;
            });
            modesContainer.addView(row);
        }
    }

    private void startMode(PresetStore.Preset mode) {
        if (SilentController.start(this, mode.minutes * 60_000L, mode.name)) {
            Toast.makeText(this, getString(R.string.mode_started, mode.name),
                    Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(this, R.string.start_failed, Toast.LENGTH_LONG).show();
        }
        refresh();
    }

    /** index -1 creates a new mode; otherwise edits that one. */
    private void showModeDialog(int index) {
        final boolean editing = index >= 0;
        PresetStore.Preset existing = editing ? modes.get(index) : null;

        View form = LayoutInflater.from(this).inflate(R.layout.dialog_mode, null);
        EditText nameInput = form.findViewById(R.id.mode_name_input);
        NumberPicker hours = form.findViewById(R.id.mode_hours);
        NumberPicker minutes = form.findViewById(R.id.mode_minutes);
        hours.setMinValue(0);
        hours.setMaxValue(12);
        minutes.setMinValue(0);
        minutes.setMaxValue(59);
        int start = existing != null ? existing.minutes : 60;
        hours.setValue(start / 60);
        minutes.setValue(start % 60);
        if (existing != null) {
            nameInput.setText(existing.name);
            nameInput.setSelection(existing.name.length());
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(this)
                .setTitle(editing ? R.string.edit_mode_title : R.string.new_mode_title)
                .setView(form)
                .setPositiveButton(R.string.save, null)
                .setNegativeButton(R.string.cancel, null);
        if (editing) {
            builder.setNeutralButton(R.string.delete, (d, w) -> {
                modes.remove(index);
                PresetStore.saveAndSync(this, modes);
                renderModes();
            });
        }
        AlertDialog dialog = builder.create();
        dialog.show();
        // Validate before closing, so a bad entry doesn't lose what was typed.
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String name = nameInput.getText().toString().trim();
            int total = Math.min(MAX_MINUTES, hours.getValue() * 60 + minutes.getValue());
            if (name.isEmpty()) {
                Toast.makeText(this, R.string.mode_needs_name, Toast.LENGTH_SHORT).show();
                return;
            }
            if (total < 1) {
                Toast.makeText(this, R.string.mode_needs_time, Toast.LENGTH_SHORT).show();
                return;
            }
            PresetStore.Preset updated = new PresetStore.Preset(name, total);
            if (editing) {
                modes.set(index, updated);
            } else {
                modes.add(updated);
            }
            PresetStore.saveAndSync(this, modes);
            renderModes();
            dialog.dismiss();
        });
    }

    private void setMinutes(int minutes) {
        selectedMinutes = Math.max(MIN_MINUTES, Math.min(MAX_MINUTES, minutes));
        refresh();
    }

    private void refresh() {
        boolean access = SilentController.hasDndAccess(this);
        permissionGroup.setVisibility(access ? View.GONE : View.VISIBLE);

        boolean active = SilentController.isActive(this);
        long now = System.currentTimeMillis();
        if (active && SilentController.endTime(this) <= now) {
            SilentController.restore(this);
            active = false;
        }

        if (active) {
            long start = SilentController.startTime(this);
            long end = SilentController.endTime(this);
            long remaining = end - now;
            long total = Math.max(1L, end - start);

            String label = SilentController.label(this);
            String activeText = getString(R.string.label_active);
            dialLabel.setText(label == null ? activeText
                    : label.toUpperCase(Locale.getDefault()) + " · " + activeText);
            dialTime.setText(formatCountdown(remaining));
            dialSub.setText(getString(R.string.ends_at, SilentController.formatTime(this, end)));
            dial.setProgress((float) remaining / total);

            idleGroup.setVisibility(View.GONE);
            extendButton.setVisibility(View.VISIBLE);
            mainButton.setText(R.string.restore_now);
            mainButton.setEnabled(true);
        } else {
            dialLabel.setText(R.string.label_idle);
            dialTime.setText(formatDuration(selectedMinutes));
            long end = now + selectedMinutes * 60_000L;
            dialSub.setText(getString(R.string.ends_at, SilentController.formatTime(this, end)));
            dial.setProgress(Math.min(1f, selectedMinutes / RING_FULL_MINUTES));

            idleGroup.setVisibility(View.VISIBLE);
            extendButton.setVisibility(View.GONE);
            mainButton.setText(R.string.go);
            mainButton.setEnabled(access);
            for (int i = 0; i < chips.length; i++) {
                chips[i].setSelected(chipMinutes[i] == selectedMinutes);
            }
        }
    }

    /** "45m", "1h", "1h 30m" */
    static String formatDuration(int minutes) {
        int h = minutes / 60;
        int m = minutes % 60;
        if (h == 0) {
            return String.format(Locale.getDefault(), "%dm", m);
        }
        if (m == 0) {
            return String.format(Locale.getDefault(), "%dh", h);
        }
        return String.format(Locale.getDefault(), "%dh %02dm", h, m);
    }

    /** "42:07" or "1:42:07" */
    private static String formatCountdown(long ms) {
        long totalSeconds = (ms + 999) / 1000;
        long h = totalSeconds / 3600;
        long m = (totalSeconds % 3600) / 60;
        long s = totalSeconds % 60;
        if (h > 0) {
            return String.format(Locale.getDefault(), "%d:%02d:%02d", h, m, s);
        }
        return String.format(Locale.getDefault(), "%d:%02d", m, s);
    }
}
