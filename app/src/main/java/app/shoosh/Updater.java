package app.shoosh;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInstaller;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Checks the project's GitHub Releases for a newer Shoosh and installs it when
 * the user taps Update. Releases carry an APK named shoosh-VERSIONCODE.apk.
 * Android only accepts the update if it is signed with the same key, so a
 * tampered download can't replace the app.
 */
final class Updater {

    static final String ACTION_INSTALL_STATUS = "app.shoosh.INSTALL_STATUS";

    static final class Release {
        final int versionCode;
        final String versionName;
        final String apkUrl;

        Release(int versionCode, String versionName, String apkUrl) {
            this.versionCode = versionCode;
            this.versionName = versionName;
            this.apkUrl = apkUrl;
        }
    }

    interface Listener {
        void onUpdateAvailable(Release release);
    }

    interface ErrorCallback {
        void onError(String message);
    }

    private static final String PREFS = "shoosh_update";
    private static final String KEY_LAST_CHECK = "last_check";
    private static final String KEY_CODE = "code";
    private static final String KEY_NAME = "name";
    private static final String KEY_URL = "url";
    /** Check GitHub at most this often (opening the app more often reuses the last answer). */
    private static final long CHECK_EVERY_MS = 6 * 60 * 60 * 1000L;
    private static final int TIMEOUT_MS = 15_000;
    private static final Pattern APK_NAME = Pattern.compile("shoosh-(\\d+)\\.apk");
    private static final int RC_INSTALL = 950;

    private Updater() {
    }

    /** Off for builds that don't know their GitHub repository (e.g. local test builds). */
    static boolean enabled() {
        return !BuildConfig.UPDATE_REPO.isEmpty();
    }

    /** Calls the listener (on the main thread) if a newer version is available. */
    static void check(Context c, Listener listener) {
        if (!enabled()) {
            return;
        }
        Context app = c.getApplicationContext();
        Release known = cached(app);
        if (known != null && known.versionCode > BuildConfig.VERSION_CODE) {
            listener.onUpdateAvailable(known);
        }
        if (System.currentTimeMillis() - prefs(app).getLong(KEY_LAST_CHECK, 0L) < CHECK_EVERY_MS) {
            return;
        }
        Handler main = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            Release latest = fetchLatest();
            if (latest == null) {
                return; // offline or GitHub unavailable; try again next time
            }
            prefs(app).edit()
                    .putLong(KEY_LAST_CHECK, System.currentTimeMillis())
                    .putInt(KEY_CODE, latest.versionCode)
                    .putString(KEY_NAME, latest.versionName)
                    .putString(KEY_URL, latest.apkUrl)
                    .apply();
            boolean isNew = known == null || known.versionCode != latest.versionCode;
            if (isNew && latest.versionCode > BuildConfig.VERSION_CODE) {
                main.post(() -> listener.onUpdateAvailable(latest));
            }
        }, "shoosh-update-check").start();
    }

    /**
     * Downloads and installs the release. Returns false if the user first has
     * to allow Shoosh to install apps (the settings screen is opened for them).
     */
    static boolean install(Activity activity, Release release, ErrorCallback onError) {
        if (Build.VERSION.SDK_INT >= 26
                && !activity.getPackageManager().canRequestPackageInstalls()) {
            activity.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + activity.getPackageName())));
            return false;
        }
        Context app = activity.getApplicationContext();
        Handler main = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            try {
                downloadAndInstall(app, release);
            } catch (IOException | RuntimeException e) {
                String msg = e.getMessage() != null ? e.getMessage() : e.toString();
                main.post(() -> onError.onError(msg));
            }
        }, "shoosh-update-install").start();
        return true;
    }

    // ---- internals ----

    private static void downloadAndInstall(Context c, Release release) throws IOException {
        PackageInstaller installer = c.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(
                PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setAppPackageName(c.getPackageName());
        if (Build.VERSION.SDK_INT >= 31) {
            // Once Shoosh has installed one update itself, later ones can skip the prompt.
            params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED);
        }
        int sessionId = installer.createSession(params);
        try (PackageInstaller.Session session = installer.openSession(sessionId)) {
            HttpURLConnection con = open(release.apkUrl);
            try (InputStream in = con.getInputStream();
                 OutputStream out = session.openWrite("shoosh.apk", 0, -1)) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                }
                session.fsync(out);
            } finally {
                con.disconnect();
            }
            // Android reports progress back to MainActivity (and asks it to show
            // the "Update Shoosh?" confirmation when needed).
            Intent status = new Intent(c, MainActivity.class)
                    .setAction(ACTION_INSTALL_STATUS)
                    .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT
                    | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
            session.commit(PendingIntent.getActivity(c, RC_INSTALL, status, flags).getIntentSender());
        } catch (IOException | RuntimeException e) {
            installer.abandonSession(sessionId);
            throw e;
        }
    }

    private static Release fetchLatest() {
        HttpURLConnection con = null;
        try {
            con = open("https://api.github.com/repos/" + BuildConfig.UPDATE_REPO + "/releases/latest");
            con.setRequestProperty("Accept", "application/vnd.github+json");
            if (con.getResponseCode() != 200) {
                return null;
            }
            JSONObject json = new JSONObject(readAll(con.getInputStream()));
            String tag = json.optString("tag_name", "");
            String name = tag.startsWith("v") ? tag.substring(1) : tag;
            JSONArray assets = json.optJSONArray("assets");
            if (assets == null) {
                return null;
            }
            for (int i = 0; i < assets.length(); i++) {
                JSONObject asset = assets.getJSONObject(i);
                Matcher m = APK_NAME.matcher(asset.optString("name", ""));
                if (m.matches()) {
                    return new Release(Integer.parseInt(m.group(1)), name,
                            asset.getString("browser_download_url"));
                }
            }
            return null;
        } catch (IOException | JSONException | NumberFormatException e) {
            return null;
        } finally {
            if (con != null) {
                con.disconnect();
            }
        }
    }

    private static HttpURLConnection open(String url) throws IOException {
        HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
        con.setConnectTimeout(TIMEOUT_MS);
        con.setReadTimeout(TIMEOUT_MS);
        con.setInstanceFollowRedirects(true);
        con.setRequestProperty("User-Agent", "Shoosh/" + BuildConfig.VERSION_NAME);
        return con;
    }

    private static String readAll(InputStream in) throws IOException {
        try (InputStream is = in; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            return out.toString(StandardCharsets.UTF_8.name());
        }
    }

    private static Release cached(Context c) {
        SharedPreferences p = prefs(c);
        String url = p.getString(KEY_URL, null);
        if (url == null) {
            return null;
        }
        return new Release(p.getInt(KEY_CODE, 0), p.getString(KEY_NAME, ""), url);
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
