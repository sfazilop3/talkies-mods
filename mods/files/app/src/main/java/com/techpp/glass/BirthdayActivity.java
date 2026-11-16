package com.techpp.glass;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.WindowManager;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

/**
 * The birthday surprise: a full-screen page (assets/birthday.html) with the intro, the balloon game, the gift, three
 * riddles, "Level 8 unlocked" with the parade and the song, and the cake. It works offline; the remote's keys are
 * passed straight to it. Back asks it to step out; pressing Back twice quickly always leaves.
 *
 * Start it with the secret code (BirthdaySecret) or from a PC:
 *   adb shell am start -n com.techpp.glass/.BirthdayActivity --es name "Her name" --es time "18:30" --ez only true   (just saves)
 *   adb shell am start -n com.techpp.glass/.BirthdayActivity                                                       (the whole surprise)
 *   adb shell am start -n com.techpp.glass/.BirthdayActivity --es scene alert         (game | riddle | level | cake | alert)
 *   adb shell am start -n com.techpp.glass/.BirthdayActivity --ez decor false --ez only true   (home screen back to normal)
 *   ... --ei hat 1 --ez only true     (which dock position's character wears the hat: 0 = first)
 */
public class BirthdayActivity extends Activity {
    private WebView web;
    private long lastBack;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        SharedPreferences p = getSharedPreferences("birthday", MODE_PRIVATE);
        Intent in = getIntent();
        if (in.hasExtra("name")) p.edit().putString("name", in.getStringExtra("name")).apply();
        if (in.hasExtra("time")) p.edit().putString("time", in.getStringExtra("time")).apply();
        if (in.hasExtra("hat")) p.edit().putInt("hat", in.getIntExtra("hat", 0)).apply();
        if (in.hasExtra("decor")) {
            if (in.getBooleanExtra("decor", false)) BirthdayDecor.startToday(this); else BirthdayDecor.stop(this);
        }
        if (in.getBooleanExtra("only", false)) { finish(); return; }
        BirthdayDecor.startToday(this);   // once the surprise starts, the home screen dresses up until midnight

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        String scene = in.getStringExtra("scene");
        String url = "file:///android_asset/birthday.html?tv=1&scene=" + Uri.encode(scene == null ? "full" : scene)
                + "&name=" + Uri.encode(p.getString("name", "Birthday Girl"))
                + "&time=" + Uri.encode(p.getString("time", "18:30"));
        web = new WebView(this);
        web.setBackgroundColor(Color.BLACK);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, String u) {
                if (u.startsWith("talkies-done:")) { finish(); return true; }   // the page says it is finished
                return true;                                                     // it never needs to go anywhere else
            }
        });
        setContentView(web);
        web.requestFocus();
        web.loadUrl(url);
    }

    /** The remote's keys go to the page as its own key names, so nothing depends on how WebView maps them. */
    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        String key = keyName(e.getKeyCode());
        if (key == null || web == null) return super.dispatchKeyEvent(e);
        if (e.getAction() == KeyEvent.ACTION_DOWN) {
            if (!"BACK".equals(key)) press(key);          // Back acts when it is released
        } else if (e.getAction() == KeyEvent.ACTION_UP && "BACK".equals(key)) {
            onBackPressed();
        }
        return true;
    }

    @Override
    public void onBackPressed() {
        long now = SystemClock.uptimeMillis();
        if (web == null || now - lastBack < 1500) { finish(); return; }
        lastBack = now;
        press("BACK");
    }

    @Override
    protected void onDestroy() {
        if (web != null) { web.destroy(); web = null; }
        super.onDestroy();
    }

    private void press(String key) {
        web.evaluateJavascript("window.press&&press('" + key + "')", null);
    }

    private static String keyName(int k) {
        switch (k) {
            case KeyEvent.KEYCODE_DPAD_UP: return "U";
            case KeyEvent.KEYCODE_DPAD_DOWN: return "D";
            case KeyEvent.KEYCODE_DPAD_LEFT: return "L";
            case KeyEvent.KEYCODE_DPAD_RIGHT: return "R";
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
            case KeyEvent.KEYCODE_BUTTON_A: return "OK";
            case KeyEvent.KEYCODE_BACK:
            case KeyEvent.KEYCODE_ESCAPE: return "BACK";
            default: return null;
        }
    }
}
