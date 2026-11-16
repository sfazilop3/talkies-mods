package com.techpp.glass;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.Keyframe;
import android.animation.ObjectAnimator;
import android.animation.PropertyValuesHolder;
import android.animation.TimeInterpolator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import java.io.File;

/**
 * The pop-up characters: whenever focus lands on a dock app, a character rises from behind its tile like a balloon
 * (stretch, squash, a little overshoot, a few sparkles) and the previous one floats away.
 *
 * Dock position 1 to 7 each get their own character; further positions repeat the last four
 * (see {@link #slotFor}). The bundled pictures are res/drawable-nodpi/pop_char1..7.webp; the birthday girl's own
 * picture (files/bday/girl.png, picked in Talkies settings) replaces the character she was chosen for (see
 * {@link #loadImage}). Views are built when first needed, nothing is allocated while moving, and every animator is
 * cancelled when the home screen stops (see {@link #release}).
 */
final class Pop {
    private static final int GIRLS = 3;
    private static final int[] IMAGES = {R.drawable.pop_char1, R.drawable.pop_char2, R.drawable.pop_char3,
            R.drawable.pop_char4, R.drawable.pop_char5, R.drawable.pop_char6, R.drawable.pop_char7};
    /** Height of each character on the 960 × 540 dp screen. */
    private static final int[] HEIGHT_DP = {250, 250, 200, 190, 190, 190, 190};
    private static final int BOX_W = 240, BOX_H = 330, HALO_W = 190, HALO_H = 240, SPARKS = 8;

    private static final class Actor {
        FrameLayout box, pose;
        View halo, string;
        ImageView person;
        TextView[] sparks = new TextView[SPARKS];
        GradientDrawable haloShape;
        HatView hat;
        TextView bubble;
        AnimatorSet enter;
        ObjectAnimator bob, sway;
        int token;
    }

    private final Context ctx;
    private final float dp;
    private final FrameLayout layer;
    private final Actor[] actors = new Actor[IMAGES.length];
    private int shown = -1, shownX = Integer.MIN_VALUE;
    private boolean bday;
    private String bname = "";
    private int hatSlot, sayN;
    private static final String[] SAY = {"Happy birthday, NAME!", "Let's party, NAME!", "You're 8 today!", "Make a wish!",
            "Cake time soon!", "You're awesome, NAME!", "Pop some balloons!", "Hip hip hooray!"};

    /** {@code layer} is added to {@code page} just before {@code before} (the dock band), so characters rise from behind it. */
    Pop(Context c, FrameLayout page, View before) {
        ctx = c;
        dp = c.getResources().getDisplayMetrics().density;
        layer = new FrameLayout(c);
        layer.setClipChildren(false);
        layer.setClipToPadding(false);
        layer.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        page.addView(layer, Math.max(0, page.indexOfChild(before)),
                new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    private int px(float v) { return Math.round(v * dp); }

    /** Birthday on: the character in dock position {@code hatSlot} + 1 wears a party hat, and each says a line with the name. */
    void setBirthday(boolean on, String name, int hatSlot) {
        bday = on;
        bname = name == null ? "" : name;
        this.hatSlot = hatSlot;
        if (!on) {
            for (Actor a : actors) {
                if (a == null) continue;
                a.hat.setVisibility(View.GONE);
                a.bubble.animate().cancel();
                a.bubble.setAlpha(0f);
            }
        }
    }

    private void decorate(final Actor a, final int slot) {
        a.hat.setVisibility(slot == hatSlot ? View.VISIBLE : View.GONE);
        a.bubble.animate().cancel();
        a.bubble.setText(SAY[sayN++ % SAY.length].replace("NAME", bname));
        a.bubble.setAlpha(0f);
        a.bubble.setScaleX(0.3f);
        a.bubble.setScaleY(0.3f);
        a.bubble.animate().setStartDelay(800).alpha(1f).scaleX(1f).scaleY(1f).setDuration(250).start();
        a.bubble.postDelayed(() -> {
            if (shown == slot) a.bubble.animate().setStartDelay(0).alpha(0f).setDuration(300).start();
        }, 3300);
    }

    /** Dock position → character: 0..2 the girls, then the pups, round and round. */
    static int slotFor(int dockIdx) {
        return dockIdx < GIRLS ? dockIdx : GIRLS + (dockIdx - GIRLS) % (IMAGES.length - GIRLS);
    }

    /**
     * Pops the character for dock position {@code dockIdx} up behind its tile.
     * {@code cx}: the tile's centre, {@code bottom}: where the character's feet sit (both in page pixels).
     * {@code dir}: 1 when focus moved right, -1 when it moved left. {@code color}: the app's glow colour.
     */
    void show(int dockIdx, int color, int cx, int bottom, int dir) {
        int slot = slotFor(dockIdx);
        if (slot == shown && Math.abs(cx - shownX) < 2) return;
        if (shown >= 0 && shown != slot) hide(dir);
        Actor a = actor(slot);
        shown = slot;
        shownX = cx;
        a.token++;
        stopIdle(a);
        if (a.enter != null) a.enter.cancel();
        a.pose.animate().cancel();
        resetPose(a);

        a.box.setTranslationX(cx - px(BOX_W) / 2f);
        a.box.setTranslationY(bottom - px(BOX_H));
        a.haloShape.setColors(new int[]{mix(color, Color.WHITE, 0.55f), color, mix(color, Color.BLACK, 0.5f)});
        a.box.setVisibility(View.VISIBLE);
        a.pose.setPivotX(px(BOX_W) / 2f);
        a.pose.setPivotY(px(BOX_H));
        a.halo.setScaleX(0f);
        a.halo.setScaleY(0f);
        float d = dir;
        float rise = px(HEIGHT_DP[slot] + 60);

        TimeInterpolator dec = new DecelerateInterpolator(1.4f);
        ObjectAnimator pose = ObjectAnimator.ofPropertyValuesHolder(a.pose,
                PropertyValuesHolder.ofKeyframe("translationY", kf(0f, rise, dec), kf(0.42f, -px(14), dec),
                        kf(0.62f, px(3), dec), kf(0.8f, -px(2), dec), kf(1f, 0f, dec)),
                PropertyValuesHolder.ofKeyframe("scaleX", kf(0f, 0.3f, dec), kf(0.42f, 0.92f, dec), kf(0.62f, 1.07f, dec),
                        kf(0.8f, 0.98f, dec), kf(1f, 1f, dec)),
                PropertyValuesHolder.ofKeyframe("scaleY", kf(0f, 0.18f, dec), kf(0.42f, 1.16f, dec), kf(0.62f, 0.9f, dec),
                        kf(0.8f, 1.04f, dec), kf(1f, 1f, dec)),
                PropertyValuesHolder.ofKeyframe("rotation", kf(0f, d * 10f, dec), kf(0.42f, -d * 4f, dec),
                        kf(0.62f, d * 2f, dec), kf(1f, 0f, dec)),
                PropertyValuesHolder.ofKeyframe("alpha", kf(0f, 0f, dec), kf(0.14f, 1f, dec), kf(1f, 1f, dec)));
        pose.setDuration(900);
        ObjectAnimator halo = ObjectAnimator.ofPropertyValuesHolder(a.halo,
                PropertyValuesHolder.ofKeyframe("scaleX", kf(0f, 0f, dec), kf(0.55f, 1.18f, dec), kf(0.75f, 0.96f, dec), kf(1f, 1f, dec)),
                PropertyValuesHolder.ofKeyframe("scaleY", kf(0f, 0f, dec), kf(0.55f, 1.18f, dec), kf(0.75f, 0.96f, dec), kf(1f, 1f, dec)));
        halo.setDuration(950);
        halo.setStartDelay(110);

        final int mine = a.token;
        AnimatorSet set = new AnimatorSet();
        set.playTogether(pose, halo);
        set.addListener(new AnimatorListenerAdapter() {
            private boolean cancelled;
            @Override public void onAnimationCancel(Animator an) { cancelled = true; }
            @Override public void onAnimationEnd(Animator an) {
                if (!cancelled && a.token == mine) startIdle(a, slot);
            }
        });
        a.enter = set;
        a.pose.setAlpha(0f);
        set.start();
        burst(a, slot);
        if (bday) decorate(a, slot);
    }

    /** The current character floats away (on a move to another app, or when focus leaves the dock). */
    void hide(int dir) {
        if (shown < 0) return;
        final Actor a = actors[shown];
        shown = -1;
        shownX = Integer.MIN_VALUE;
        if (a == null) return;
        a.bubble.animate().cancel();
        a.bubble.setAlpha(0f);
        final int mine = ++a.token;
        if (a.enter != null) a.enter.cancel();
        stopIdle(a);
        a.pose.animate().cancel();
        a.pose.animate().translationX(-dir * px(50)).translationY(-px(40)).rotation(-dir * 14f).scaleX(0.6f).scaleY(0.6f)
                .alpha(0f).setDuration(250).setInterpolator(new AccelerateInterpolator(1.5f))
                .withEndAction(() -> {
                    if (a.token != mine) return;    // popped up again meanwhile
                    a.box.setVisibility(View.INVISIBLE);
                    resetPose(a);
                }).start();
    }

    /** Stops everything and hides every character (home screen stopped, or the dock was rebuilt). */
    void release() {
        for (Actor a : actors) {
            if (a == null) continue;
            a.token++;
            if (a.enter != null) a.enter.cancel();
            stopIdle(a);
            a.pose.animate().cancel();
            for (TextView s : a.sparks) s.animate().cancel();
            a.bubble.animate().cancel();
            a.bubble.setAlpha(0f);
            a.box.setVisibility(View.INVISIBLE);
            resetPose(a);
        }
        shown = -1;
        shownX = Integer.MIN_VALUE;
    }

    // ------------------------------------------------------------------ building

    private Actor actor(int slot) {
        if (actors[slot] != null) return actors[slot];
        Actor a = new Actor();
        a.box = new FrameLayout(ctx);
        a.box.setClipChildren(false);
        a.box.setVisibility(View.INVISIBLE);
        layer.addView(a.box, new FrameLayout.LayoutParams(px(BOX_W), px(BOX_H), Gravity.TOP | Gravity.START));
        a.pose = new FrameLayout(ctx);
        a.pose.setClipChildren(false);
        a.box.addView(a.pose, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // The balloon behind the character, in the app's colour.
        a.halo = new View(ctx);
        a.haloShape = new GradientDrawable();
        a.haloShape.setShape(GradientDrawable.OVAL);
        a.haloShape.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        a.haloShape.setGradientCenter(0.35f, 0.28f);
        a.haloShape.setGradientRadius(px(150));
        a.haloShape.setStroke(px(1.5f), 0x80FFFFFF);
        a.halo.setBackground(a.haloShape);
        a.halo.setPivotX(px(HALO_W) / 2f);
        a.halo.setPivotY(px(HALO_H));
        FrameLayout.LayoutParams hp = new FrameLayout.LayoutParams(px(HALO_W), px(HALO_H), Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        hp.bottomMargin = px(14);
        a.pose.addView(a.halo, hp);

        // Its string.
        a.string = new View(ctx);
        a.string.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0xB4FFFFFF, 0x0AFFFFFF}));
        a.string.setPivotX(px(0.5f));
        a.string.setPivotY(0f);
        FrameLayout.LayoutParams sp = new FrameLayout.LayoutParams(Math.max(1, px(1)), px(60), Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        sp.bottomMargin = -px(46);
        a.pose.addView(a.string, sp);

        // The character.
        a.person = new ImageView(ctx);
        loadImage(a.person, slot);
        a.person.setAdjustViewBounds(true);
        a.person.setScaleType(ImageView.ScaleType.FIT_CENTER);
        a.person.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        a.pose.addView(a.person, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, px(HEIGHT_DP[slot]),
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL));

        // Sparkles, built once and reused on every pop.
        for (int i = 0; i < SPARKS; i++) {
            TextView s = new TextView(ctx);
            s.setText("\u2605");
            s.setTextColor(Color.WHITE);
            s.setTextSize(11 + (i % 3) * 3);
            s.setShadowLayer(px(4), 0, 0, 0xFFFFD27A);
            s.setAlpha(0f);
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
            lp.bottomMargin = px(HEIGHT_DP[slot] * 0.45f);
            a.pose.addView(s, lp);
            a.sparks[i] = s;
        }
        // Birthday extras, hidden until Pop.setBirthday(true, ...).
        a.hat = new HatView(ctx);
        a.hat.setVisibility(View.GONE);
        a.hat.setRotation(10f);
        a.hat.setTranslationX(px(6));
        FrameLayout.LayoutParams hl = new FrameLayout.LayoutParams(px(40), px(52), Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        hl.bottomMargin = px(HEIGHT_DP[slot]) - px(14);
        a.pose.addView(a.hat, hl);
        a.bubble = new TextView(ctx);
        a.bubble.setTextColor(0xFF222222);
        a.bubble.setTextSize(11);
        a.bubble.setTypeface(Typeface.DEFAULT_BOLD);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFFFFFFFF);
        bg.setCornerRadius(px(12));
        a.bubble.setBackground(bg);
        a.bubble.setPadding(px(9), px(5), px(9), px(5));
        a.bubble.setAlpha(0f);
        a.bubble.setTranslationX(px(66));
        FrameLayout.LayoutParams bl = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        bl.bottomMargin = px(HEIGHT_DP[slot] * 0.66f);
        a.pose.addView(a.bubble, bl);
        actors[slot] = a;
        return a;
    }

    /**
     * Your own picture wins over the bundled one: put a transparent PNG or WebP (about 400 x 640, head near the top)
     * on the TV as Android/data/com.techpp.glass/files/pop/char1.png ... char7.png, e.g.
     * adb push mine.png /sdcard/Android/data/com.techpp.glass/files/pop/char1.png
     * Nothing personal ever has to be in the app or in the repository.
     * The birthday girl's picture (picked in Talkies settings) comes before all of them, for her character only.
     */
    private void loadImage(ImageView v, int slot) {
        if (slot == bdayChar()) {
            File girl = bdayPhoto();
            Bitmap b = girl != null ? decode(girl, slot) : null;
            if (b != null) { v.setImageBitmap(b); return; }
        }
        File dir = ctx.getExternalFilesDir(null);
        if (dir != null) {
            for (String ext : new String[]{"png", "webp"}) {
                File f = new File(dir, "pop/char" + (slot + 1) + "." + ext);
                if (!f.isFile()) continue;
                Bitmap b = decode(f, slot);
                if (b != null) { v.setImageBitmap(b); return; }
            }
        }
        v.setImageResource(IMAGES[slot]);
    }

    private Bitmap decode(File f, int slot) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getPath(), o);
        int want = px(HEIGHT_DP[slot]) * 2, sample = 1;
        while (o.outHeight / (sample * 2) >= want) sample *= 2;
        o = new BitmapFactory.Options();
        o.inSampleSize = sample;
        return BitmapFactory.decodeFile(f.getPath(), o);
    }

    /** The character the birthday girl was chosen for (Talkies settings), 0 when nothing was chosen. */
    private int bdayChar() {
        int n = ctx.getSharedPreferences(BirthdayDecor.PREFS, Context.MODE_PRIVATE).getInt("hat", 0);
        return n < 0 || n >= IMAGES.length ? 0 : n;
    }

    /** Her own picture, when one was picked on this TV. */
    private File bdayPhoto() {
        File dir = ctx.getExternalFilesDir(null);
        if (dir == null) return null;
        for (String ext : new String[]{"png", "jpg", "webp"}) {
            File f = new File(dir, "bday/girl." + ext);
            if (f.isFile()) return f;
        }
        return null;
    }

    /** A picture or character was changed: read the pictures again (the views stay as they are). */
    void reloadArt() {
        for (int i = 0; i < actors.length; i++) {
            if (actors[i] != null) loadImage(actors[i].person, i);
        }
    }

    private static Keyframe kf(float fraction, float value, TimeInterpolator in) {
        Keyframe k = Keyframe.ofFloat(fraction, value);
        k.setInterpolator(in);
        return k;
    }

    private void resetPose(Actor a) {
        a.pose.setTranslationX(0f);
        a.pose.setTranslationY(0f);
        a.pose.setRotation(0f);
        a.pose.setScaleX(1f);
        a.pose.setScaleY(1f);
        a.pose.setAlpha(1f);
    }

    /** Floating, with the string swaying, until it is hidden. */
    private void startIdle(Actor a, int slot) {
        stopIdle(a);
        a.bob = ObjectAnimator.ofFloat(a.person, "translationY", 0f, -px(5));
        a.bob.setDuration(1500);
        a.bob.setRepeatCount(ValueAnimator.INFINITE);
        a.bob.setRepeatMode(ValueAnimator.REVERSE);
        a.bob.setInterpolator(new AccelerateDecelerateInterpolator());
        a.sway = ObjectAnimator.ofFloat(a.string, "rotation", -4f, 4f);
        a.sway.setDuration(1300);
        a.sway.setRepeatCount(ValueAnimator.INFINITE);
        a.sway.setRepeatMode(ValueAnimator.REVERSE);
        a.sway.setInterpolator(new AccelerateDecelerateInterpolator());
        a.bob.start();
        a.sway.start();
    }

    private void stopIdle(Actor a) {
        if (a.bob != null) { a.bob.cancel(); a.bob = null; }
        if (a.sway != null) { a.sway.cancel(); a.sway = null; }
        if (a.person != null) a.person.setTranslationY(0f);
        if (a.string != null) a.string.setRotation(0f);
    }

    private void burst(Actor a, int slot) {
        for (int k = 0; k < SPARKS; k++) {
            final TextView s = a.sparks[k];
            s.animate().cancel();
            s.setAlpha(0f);
            s.setTranslationX(0f);
            s.setTranslationY(0f);
            s.setRotation(0f);
            double ang = -Math.PI / 2 + (k / (double) SPARKS - 0.5) * Math.PI * 1.7;
            float dist = px(70 + 12 * ((k * 3) % 5));
            s.animate().setStartDelay(170 + k * 20L).withStartAction(() -> s.setAlpha(1f))
                    .translationX((float) (Math.cos(ang) * dist)).translationY((float) (Math.sin(ang) * dist))
                    .rotation(180f).alpha(0f).setDuration(750).start();
        }
    }

    /** A party hat. */
    private static final class HatView extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();

        HatView(Context c) { super(c); }

        @Override protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight();
            path.reset();
            path.moveTo(w / 2, h * 0.05f);
            path.lineTo(w * 0.04f, h * 0.97f);
            path.lineTo(w * 0.96f, h * 0.97f);
            path.close();
            p.setStyle(Paint.Style.FILL);
            p.setColor(0xFFFF4FA3);
            c.drawPath(path, p);
            p.setColor(0xFFFFD23F);
            c.drawRect(w * 0.27f, h * 0.5f, w * 0.73f, h * 0.6f, p);
            c.drawRect(w * 0.16f, h * 0.76f, w * 0.84f, h * 0.86f, p);
            c.drawCircle(w / 2, h * 0.07f, w * 0.1f, p);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(w * 0.04f);
            p.setColor(0xFF7A1B52);
            c.drawPath(path, p);
        }
    }

    private static int mix(int c1, int c2, float t) {
        return Color.rgb(
                (int) (Color.red(c1) + (Color.red(c2) - Color.red(c1)) * t),
                (int) (Color.green(c1) + (Color.green(c2) - Color.green(c1)) * t),
                (int) (Color.blue(c1) + (Color.blue(c2) - Color.blue(c1)) * t));
    }
}
