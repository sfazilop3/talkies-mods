package com.techpp.glass;

import android.app.Activity;
import android.content.Intent;
import android.os.SystemClock;
import android.view.KeyEvent;

/**
 * The hidden switch for the birthday surprise. From the home screen press, each key within a few seconds of the last:
 *   up, up, down, down, then OK    the whole surprise
 *   left, left, right, right, then OK    "Cake time!" only
 * Nothing shows in any menu. The OK that finishes a code is swallowed, so it doesn't also open the app under the focus.
 */
final class BirthdaySecret {
    private static final int U = KeyEvent.KEYCODE_DPAD_UP, D = KeyEvent.KEYCODE_DPAD_DOWN;
    private static final int L = KeyEvent.KEYCODE_DPAD_LEFT, R = KeyEvent.KEYCODE_DPAD_RIGHT;
    private static final int[] FULL = {U, U, D, D}, CAKE = {L, L, R, R};
    private static final long WINDOW_MS = 4000;

    private static final int[] seen = new int[4];
    private static int count;
    private static long last;
    private static int swallow;

    private BirthdaySecret() {}

    /** Returns true when this key event belongs to a completed code and must not reach Talkies. */
    static boolean feed(Activity a, KeyEvent e) {
        int k = e.getKeyCode();
        if (e.getAction() == KeyEvent.ACTION_UP) {
            if (k == swallow) { swallow = 0; return true; }
            return false;
        }
        if (e.getAction() != KeyEvent.ACTION_DOWN || e.getRepeatCount() > 0) return false;
        long now = SystemClock.uptimeMillis();
        if (now - last > WINDOW_MS) count = 0;
        last = now;
        if (k == U || k == D || k == L || k == R) {
            if (count == seen.length) { System.arraycopy(seen, 1, seen, 0, seen.length - 1); count--; }
            seen[count++] = k;
            return false;
        }
        boolean ok = k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER
                || k == KeyEvent.KEYCODE_NUMPAD_ENTER || k == KeyEvent.KEYCODE_BUTTON_A;
        String scene = null;
        if (ok && count == 4) {
            if (java.util.Arrays.equals(seen, FULL)) scene = "full";
            else if (java.util.Arrays.equals(seen, CAKE)) scene = "alert";
        }
        count = 0;
        if (scene == null) return false;
        swallow = k;
        a.startActivity(new Intent(a, BirthdayActivity.class).putExtra("scene", scene));
        return true;
    }
}
