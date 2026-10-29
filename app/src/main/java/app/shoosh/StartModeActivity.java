package app.shoosh;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Toast;

/**
 * Invisible screen used by shortcuts and widgets: starts the timer, shows a
 * short message and closes straight away. If Shoosh doesn't have
 * Do Not Disturb access yet, it opens the app instead so the user can grant it.
 */
public class StartModeActivity extends Activity {

    private boolean handled;

    @Override
    protected void onResume() {
        super.onResume();
        // Done in onResume (not onCreate) so Android 17 sees a visible screen
        // when the ringer is changed.
        if (handled) {
            return;
        }
        handled = true;
        Intent in = getIntent();
        String name = in.getStringExtra(Shortcuts.EXTRA_NAME);
        int minutes = in.getIntExtra(Shortcuts.EXTRA_MINUTES, 0);

        if (minutes <= 0 || !SilentController.hasDndAccess(this)) {
            startActivity(new Intent(this, MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } else if (SilentController.start(this, minutes * 60_000L, name)) {
            String msg = name != null
                    ? getString(R.string.mode_started, name)
                    : getString(R.string.now_silent);
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(this, R.string.start_failed, Toast.LENGTH_LONG).show();
        }
        finish();
        overridePendingTransition(0, 0);
    }
}
