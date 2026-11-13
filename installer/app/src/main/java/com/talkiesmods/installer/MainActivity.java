package com.talkiesmods.installer;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Installs (and updates) the Talkies build with the mods. Android doesn't let one app change another, so this app does
 * what Android allows: it finds the newest release in the mods repository (built there from the newest Talkies),
 * downloads it, checks it against GitHub's size and SHA-256, and hands it to Android's installer, which asks you once.
 * After that, Talkies' own Settings > Updates follows the same releases, so this app is only needed the first time.
 */
public class MainActivity extends Activity {
    static final String TALKIES = "com.techpp.glass";
    private static final String ASSET = "talkies.apk";
    private static final long MAX_SIZE = 40L << 20;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private TextView installedView, latestView, statusView;
    private Button installBtn;
    private volatile boolean busy;
    private JSONObject release;
    private String latest = "";

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        String r = getIntent().getStringExtra("repo");       // adb shell am start -n ... --es repo user/repo
        if (r != null && r.contains("/")) prefs().edit().putString("repo", r.trim()).apply();

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_VERTICAL);
        root.setPadding(dp(90), dp(30), dp(90), dp(30));
        root.setBackgroundColor(Color.rgb(16, 13, 28));
        root.addView(label("Talkies Mods", 34, Color.WHITE));
        root.addView(label("from github.com/" + repo(), 16, Color.argb(160, 255, 255, 255)));
        installedView = label("", 22, Color.WHITE);
        latestView = label("", 22, Color.WHITE);
        statusView = label("", 20, Color.rgb(255, 210, 63));
        ((LinearLayout.LayoutParams) installedView.getLayoutParams()).topMargin = dp(18);
        root.addView(installedView);
        root.addView(latestView);
        root.addView(statusView);
        installBtn = button("Install / update", v -> install());
        root.addView(installBtn);
        root.addView(button("Check again", v -> check()));
        root.addView(button("Open Talkies", v -> openTalkies()));
        root.addView(button("Uninstall Talkies (to switch from the original)", v -> startActivity(
                new Intent(Intent.ACTION_DELETE, Uri.parse("package:" + TALKIES)))));
        TextView tip = label("First time over the original Talkies? Android needs it removed once, because the mods are signed "
                + "with another key. Back up in Talkies first. Your own character pictures: adb push mine.png "
                + "/sdcard/Android/data/com.techpp.glass/files/pop/char1.png", 15, Color.argb(150, 255, 255, 255));
        ((LinearLayout.LayoutParams) tip.getLayoutParams()).topMargin = dp(16);
        root.addView(tip);
        setContentView(root);
        installBtn.requestFocus();
    }

    @Override protected void onResume() {
        super.onResume();
        refresh();
        String last = prefs().getString("last", null);
        if (last != null) {
            prefs().edit().remove("last").apply();
            say(last);
        } else {
            check();
        }
    }

    // ---- looking -------------------------------------------------------------------------------------------------

    private void check() {
        if (busy) return;
        busy = true;
        say("Checking…");
        new Thread(() -> {
            try {
                JSONObject r = new JSONObject(get("https://api.github.com/repos/" + repo() + "/releases/latest"));
                String v = r.optString("tag_name").replaceFirst("^[vV]", "");
                if (v.isEmpty()) throw new IOException("the latest release has no tag");
                release = r;
                latest = v;
                String mine = installed();
                say(mine == null ? "Talkies isn't installed yet. Press Install."
                        : compare(v, mine) > 0 ? v + " is available. Press Install / update."
                        : "You have the latest mods build.");
            } catch (Exception e) {
                say(offline(e) ? "No connection." : "Couldn't check: " + e.getMessage());
            }
            busy = false;
            ui.post(this::refresh);
        }, "check").start();
    }

    private void refresh() {
        String mine = installed();
        installedView.setText("Installed Talkies: " + (mine == null ? "none" : mine));
        latestView.setText("Newest mods build: " + (latest.isEmpty() ? "…" : latest));
        installBtn.setText(mine == null ? "Install" : "Update / reinstall");
    }

    private void say(String s) { ui.post(() -> statusView.setText(s)); }

    // ---- installing ----------------------------------------------------------------------------------------------

    private void install() {
        if (release == null) { check(); return; }
        if (!getPackageManager().canRequestPackageInstalls()) {
            say("Allow \"install unknown apps\" for Talkies Mods on the screen that opens, then press Install again.");
            try {
                startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + getPackageName())));
            } catch (RuntimeException e) {
                say("Open Settings > Apps > Special access > Install unknown apps, and allow Talkies Mods.");
            }
            return;
        }
        if (busy) return;
        busy = true;
        final JSONObject r = release;
        final String v = latest;
        new Thread(() -> {
            try {
                say("Downloading " + v + "…");
                File apk = download(r, v);
                String wrong = wrongApp(apk);
                if (wrong != null) { apk.delete(); throw new IOException("the download " + wrong); }
                say("Installing " + v + "… Android will ask you to confirm.");
                commit(apk, v);
            } catch (Exception e) {
                say(offline(e) ? "No connection." : "Couldn't install: " + e.getMessage());
            }
            busy = false;
        }, "install").start();
    }

    /** The release's APK, checked against GitHub's size and SHA-256 as it downloads. */
    private File download(JSONObject r, String version) throws Exception {
        JSONObject asset = null;
        JSONArray assets = r.optJSONArray("assets");
        for (int i = 0; assets != null && i < assets.length(); i++) {
            if (ASSET.equals(assets.getJSONObject(i).optString("name"))) asset = assets.getJSONObject(i);
        }
        if (asset == null) throw new IOException(ASSET + " isn't in release " + version);
        String url = asset.optString("browser_download_url"), digest = asset.optString("digest");
        long size = asset.optLong("size");
        if (!url.startsWith("https://github.com/" + repo() + "/releases/download/") || size <= 0 || size > MAX_SIZE) {
            throw new IOException("unexpected asset: " + url);
        }
        File dir = new File(getCacheDir(), "dl");
        File[] old = dir.listFiles();
        for (int i = 0; old != null && i < old.length; i++) old[i].delete();
        dir.mkdirs();
        File to = new File(dir, "talkies-" + version + ".apk"), part = new File(to.getPath() + ".part");
        MessageDigest sha = MessageDigest.getInstance("SHA-256");
        long total = 0;
        HttpURLConnection h = open(url);
        try {
            if (h.getResponseCode() != 200) throw new IOException("GitHub returned HTTP " + h.getResponseCode());
            try (InputStream in = h.getInputStream(); OutputStream out = new FileOutputStream(part)) {
                byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) > 0) {
                    total += n;
                    if (total > size) throw new IOException("the download is larger than GitHub says");
                    sha.update(buf, 0, n);
                    out.write(buf, 0, n);
                }
            }
            if (total != size || digest.startsWith("sha256:") && !digest.substring(7).equalsIgnoreCase(hex(sha.digest()))) {
                throw new IOException("the download doesn't match GitHub's size or SHA-256");
            }
            if (!part.renameTo(to)) throw new IOException("couldn't save the download");
            return to;
        } catch (Exception e) {
            part.delete();
            throw e;
        } finally {
            h.disconnect();
        }
    }

    private String wrongApp(File apk) {
        PackageInfo info = getPackageManager().getPackageArchiveInfo(apk.getPath(), 0);
        if (info == null) return "can't be read";
        return TALKIES.equals(info.packageName) ? null : "is another app";
    }

    /** Hands the APK to Android's installer; InstallReceiver gets its answer. */
    private void commit(File apk, String version) throws IOException {
        PackageInstaller pi = getPackageManager().getPackageInstaller();
        for (PackageInstaller.SessionInfo s : pi.getMySessions()) pi.abandonSession(s.getSessionId());
        PackageInstaller.SessionParams p = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        p.setAppPackageName(TALKIES);
        p.setSize(apk.length());
        // Android 12+: an update of an app this one installed before can go through without a question.
        if (Build.VERSION.SDK_INT >= 31) p.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED);
        int id = pi.createSession(p);
        try (PackageInstaller.Session s = pi.openSession(id)) {
            try (InputStream in = new FileInputStream(apk); OutputStream out = s.openWrite(ASSET, 0, apk.length())) {
                byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                s.fsync(out);
            }
            Intent done = new Intent(this, InstallReceiver.class).putExtra("version", version);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
            s.commit(PendingIntent.getBroadcast(this, id, done, flags).getIntentSender());
        }
    }

    private void openTalkies() {
        PackageManager pm = getPackageManager();
        Intent i = pm.getLeanbackLaunchIntentForPackage(TALKIES);
        if (i == null) i = pm.getLaunchIntentForPackage(TALKIES);
        if (i == null) i = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).setPackage(TALKIES);
        try {
            startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (RuntimeException e) {
            say("Talkies isn't installed.");
        }
    }

    // ---- helpers -------------------------------------------------------------------------------------------------

    private SharedPreferences prefs() { return getSharedPreferences("mods", MODE_PRIVATE); }

    private String repo() { return prefs().getString("repo", BuildConfig.MODS_REPO); }

    /** The installed Talkies' version name, or null when it isn't installed. */
    private String installed() {
        try {
            return getPackageManager().getPackageInfo(TALKIES, 0).versionName;
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }
    }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    private TextView label(String text, int sp, int color) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        return t;
    }

    private Button button(String text, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(20);
        b.setOnClickListener(l);
        paint(b, false);
        b.setOnFocusChangeListener((v, f) -> paint((Button) v, f));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(560), dp(52));
        lp.topMargin = dp(10);
        b.setLayoutParams(lp);
        return b;
    }

    private void paint(Button b, boolean focused) {
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(14));
        g.setColor(focused ? Color.WHITE : Color.rgb(52, 46, 78));
        b.setBackground(g);
        b.setTextColor(focused ? Color.BLACK : Color.WHITE);
    }

    /** "1.1.1.10" vs "1.1.1.9": numbers compared one by one, missing ones as 0 (the same rule Talkies uses). */
    static int compare(String a, String b) {
        String[] x = a.split("[.\\-]"), y = b.split("[.\\-]");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int p = i < x.length ? number(x[i]) : 0, q = i < y.length ? number(y[i]) : 0;
            if (p != q) return Integer.compare(p, q);
        }
        return 0;
    }

    private static int number(String s) {
        try {
            return Integer.parseInt(s.replaceAll("\\D.*", ""));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder hex = new StringBuilder();
        for (byte b : bytes) hex.append(String.format("%02x", b));
        return hex.toString();
    }

    private static boolean offline(Exception e) {
        return e instanceof java.net.UnknownHostException || e instanceof java.net.SocketException
                || e instanceof java.net.SocketTimeoutException || e instanceof javax.net.ssl.SSLException;
    }

    private static HttpURLConnection open(String url) throws IOException {
        HttpURLConnection h = (HttpURLConnection) new URL(url).openConnection();
        h.setConnectTimeout(10000);
        h.setReadTimeout(30000);
        h.setRequestProperty("User-Agent", "TalkiesMods");
        return h;
    }

    private static String get(String url) throws IOException {
        HttpURLConnection h = open(url);
        h.setRequestProperty("Accept", "application/vnd.github+json");
        try {
            if (h.getResponseCode() != 200) throw new IOException("GitHub returned HTTP " + h.getResponseCode());
            try (InputStream in = h.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                return out.toString(StandardCharsets.UTF_8.name());
            }
        } finally {
            h.disconnect();
        }
    }
}
