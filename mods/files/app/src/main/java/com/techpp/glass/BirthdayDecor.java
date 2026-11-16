package com.techpp.glass;

import android.animation.Animator;
import android.animation.ObjectAnimator;
import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AnimationUtils;
import android.view.animation.LinearInterpolator;
import android.widget.FrameLayout;
import android.widget.TextView;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Random;

/**
 * The birthday look of the home screen, from the moment the surprise is started until midnight: flags across the top,
 * a greeting, soft colour behind everything, a few balloons drifting up, the pop-up characters' speech bubbles and party
 * hat (see Pop.setBirthday), and the cat, who walks along the dock to the selected app and sits on it. The birthday
 * girl is the character chosen in Talkies settings; her own picture (picked there too) replaces that character.
 * The cat's pictures are optional: assets/cat/walk.webp, sit.webp, idle.webp (frames 190 x 160 px side by side;
 * 9, 20 and 17 of them). Without them everything else still shows.
 */
final class BirthdayDecor {
    static final String PREFS = "birthday";
    private static final int[] COLORS = {0xFFFF4FA3, 0xFFFFD23F, 0xFF2EC4B6, 0xFF7C6CFF, 0xFFFF7A45, 0xFF4CC9F0};

    // ---- the switch: on until the next midnight ------------------------------------------------------------------
    static boolean active(Context c) {
        return System.currentTimeMillis() < c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong("until", 0);
    }

    static void startToday(Context c) {
        Calendar m = Calendar.getInstance();
        m.add(Calendar.DAY_OF_YEAR, 1);
        m.set(Calendar.HOUR_OF_DAY, 0);
        m.set(Calendar.MINUTE, 0);
        m.set(Calendar.SECOND, 0);
        m.set(Calendar.MILLISECOND, 0);
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putLong("until", m.getTimeInMillis()).apply();
    }

    static void stop(Context c) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putLong("until", 0).apply();
    }

    // ---- the decorations -----------------------------------------------------------------------------------------
    private final Activity act;
    private final FrameLayout root, page;
    private final View dockBand;
    private final Pop pop;
    private final float dp;
    private boolean shown;
    private View bokeh;
    private Bunting bunting;
    private TextView greeting;
    private CatView cat;
    private boolean catTried, catOn = true;
    private int lastHat = Integer.MIN_VALUE;
    private final List<View> balloons = new ArrayList<>();
    private final List<ObjectAnimator> drifting = new ArrayList<>();

    BirthdayDecor(Activity a, FrameLayout root, FrameLayout page, View dockBand, Pop pop) {
        this.act = a;
        this.root = root;
        this.page = page;
        this.dockBand = dockBand;
        this.pop = pop;
        this.dp = a.getResources().getDisplayMetrics().density;
    }

    private int px(float v) { return Math.round(v * dp); }

    /** Call when the home screen comes back: shows or hides everything to match the switch. */
    void refresh() {
        SharedPreferences p = act.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        boolean on = active(act);
        String name = p.getString("name", "Birthday Girl");
        int hat = p.getInt("hat", 0);
        if (hat != lastHat) { lastHat = hat; pop.reloadArt(); }   // another character is the birthday girl now
        pop.setBirthday(on, name, hat);
        if (on) {
            if (!shown) build();
            greeting.setText("HAPPY BIRTHDAY, " + name.toUpperCase() + "!");
            show();
        } else if (shown) {
            hide();
        }
        shown = on;
    }

    /** Talkies settings → Cat animation: off hides the cat until it is switched on again. */
    void setCatOn(boolean on) {
        catOn = on;
        if (cat != null && !on) { cat.pause(); cat.setVisibility(View.GONE); }
    }

    private void build() {
        bokeh = new Bokeh(act);
        root.addView(bokeh, 1, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        Random r = new Random(14);
        for (int i = 0; i < 4; i++) {
            View b = new View(act);
            GradientDrawable g = new GradientDrawable();
            g.setShape(GradientDrawable.OVAL);
            g.setGradientType(GradientDrawable.RADIAL_GRADIENT);
            g.setGradientRadius(px(46));
            g.setGradientCenter(0.35f, 0.28f);
            g.setColors(new int[]{0xFFFFFFFF, COLORS[(i * 2) % COLORS.length], darker(COLORS[(i * 2) % COLORS.length])});
            b.setBackground(g);
            b.setAlpha(0.35f);
            int w = px(46 + r.nextInt(18));
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(w, Math.round(w * 1.25f), Gravity.TOP | Gravity.START);
            lp.leftMargin = px(70 + i * 220 + r.nextInt(60));
            root.addView(b, 2 + i, lp);
            balloons.add(b);
        }
        bunting = new Bunting(act);
        page.addView(bunting, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(12), Gravity.TOP));
        greeting = new TextView(act);
        greeting.setTextSize(12);
        greeting.setTypeface(Typeface.DEFAULT_BOLD);
        greeting.setLetterSpacing(0.15f);
        greeting.setTextColor(0xFFFFB4D9);
        greeting.setShadowLayer(px(4), 0, 0, 0xFFFF4FA3);
        FrameLayout.LayoutParams gl = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.START);
        gl.leftMargin = px(40);
        gl.topMargin = px(11);
        page.addView(greeting, gl);
        if (!catTried) {
            catTried = true;
            cat = CatView.create(act, dp);
            if (cat != null) {
                page.addView(cat, new FrameLayout.LayoutParams(cat.width(), cat.height(), Gravity.TOP | Gravity.START));
                cat.setVisibility(View.INVISIBLE);
            }
        }
    }

    private void show() {
        bokeh.setVisibility(View.VISIBLE);
        bunting.setVisibility(View.VISIBLE);
        greeting.setVisibility(View.VISIBLE);
        if (drifting.isEmpty()) {
            int h = Math.max(root.getHeight(), px(540));
            for (int i = 0; i < balloons.size(); i++) {
                View b = balloons.get(i);
                b.setVisibility(View.VISIBLE);
                ObjectAnimator a = ObjectAnimator.ofFloat(b, "translationY", h + px(40), -px(140));
                a.setDuration(19000 + i * 4500L);
                a.setStartDelay(i * 3000L);
                a.setRepeatCount(ObjectAnimator.INFINITE);
                a.setInterpolator(new LinearInterpolator());
                b.setTranslationY(h + px(40));
                a.start();
                drifting.add(a);
            }
        }
        if (cat != null && catOn) cat.resume();
    }

    private void hide() {
        stopDrifting();
        for (View b : balloons) b.setVisibility(View.GONE);
        if (bokeh != null) bokeh.setVisibility(View.GONE);
        if (bunting != null) bunting.setVisibility(View.GONE);
        if (greeting != null) greeting.setVisibility(View.GONE);
        if (cat != null) { cat.pause(); cat.setVisibility(View.GONE); }
        pop.setBirthday(false, "", 0);
    }

    private void stopDrifting() {
        for (ObjectAnimator a : drifting) a.cancel();
        drifting.clear();
    }

    /** The home screen stopped: nothing may keep animating in the background. */
    void release() {
        stopDrifting();
        if (cat != null) cat.pause();
    }

    /** Focus is on dock app {@code tile}: the cat walks there and sits on it. {@code cx} is the tile's centre in page pixels. */
    void focus(View tile, int cx) {
        if (!shown || cat == null || !catOn) return;
        int top = dockBand.getTop() + ((View) tile.getParent()).getTop() + tile.getTop();
        cat.setVisibility(View.VISIBLE);
        cat.goTo(cx - 0.35f * tile.getWidth(), top, 0.09f * tile.getHeight());
    }

    /** Focus left the dock: the cat steps down from the tile and stays where it is. */
    void blur() {
        if (cat != null) cat.drop();
    }

    private static int darker(int c) {
        return Color.rgb(Color.red(c) * 45 / 100, Color.green(c) * 45 / 100, Color.blue(c) * 45 / 100);
    }

    // ---- bits that draw themselves -------------------------------------------------------------------------------
    /** A row of little coloured flags. */
    private static final class Bunting extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();

        Bunting(Context c) { super(c); setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO); }

        @Override protected void onDraw(Canvas canvas) {
            float w = getHeight() * 0.8f, step = w * 1.35f;
            paint.setColor(0x73FFFFFF);
            canvas.drawRect(0, getHeight() * 0.08f, getWidth(), getHeight() * 0.14f, paint);
            for (int i = 0; i * step < getWidth(); i++) {
                paint.setColor(COLORS[i % COLORS.length]);
                float x = i * step + step * 0.2f;
                path.reset();
                path.moveTo(x, getHeight() * 0.1f);
                path.lineTo(x + w, getHeight() * 0.1f);
                path.lineTo(x + w / 2, getHeight() * 0.95f);
                path.close();
                canvas.drawPath(path, paint);
            }
        }
    }

    /** Soft pink, gold and violet glows behind everything. */
    private static final class Bokeh extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        Bokeh(Context c) { super(c); setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO); }

        private void glow(Canvas c, float x, float y, float r, int color) {
            paint.setShader(new RadialGradient(x, y, r, (color & 0x00FFFFFF) | 0x59000000, color & 0x00FFFFFF, Shader.TileMode.CLAMP));
            c.drawCircle(x, y, r, paint);
        }

        @Override protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight(), r = Math.max(w, h) * 0.3f;
            glow(c, w * 0.10f, h * 0.20f, r, 0xFFFF7AB8);
            glow(c, w * 0.90f, h * 0.28f, r * 1.1f, 0xFFFFD23F);
            glow(c, w * 0.68f, h * 0.90f, r, 0xFF7C6CFF);
        }
    }

    /** The cat: walks (9 frames), sits down (20), then idles (17, forwards and back). */
    static final class CatView extends View {
        private static final int FW = 190, FH = 160, PAD = 16, WALK = 0, SIT = 1, IDLE = 2;
        private static final int[] COUNT = {9, 20, 17};
        private static final float[] PERIOD = {0.1f, 1f / 16f, 1f / 6f};   // seconds per frame

        private final Bitmap[] strip;
        private final float scale, density;
        private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
        private final Rect src = new Rect(), dst = new Rect();
        private int mode = IDLE, frame, dir = 1, face = 1;
        private float acc, x, tx, ground, lift, yo, targetYo;
        private boolean placed, running;
        private long last;

        private CatView(Context c, Bitmap[] strip, float density) {
            super(c);
            this.strip = strip;
            this.density = density;
            this.scale = density * 0.5f;   // 190 x 160 "design pixels" on a 960 dp wide screen
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        }

        /** The cat, or null when its pictures aren't in the app. */
        static CatView create(Context c, float density) {
            try {
                AssetManager am = c.getAssets();
                Bitmap[] s = {load(am, "cat/walk.webp"), load(am, "cat/sit.webp"), load(am, "cat/idle.webp")};
                for (Bitmap b : s) if (b == null) return null;
                return new CatView(c, s, density);
            } catch (Throwable t) {
                return null;
            }
        }

        private static Bitmap load(AssetManager am, String name) {
            try (InputStream in = am.open(name)) {
                return BitmapFactory.decodeStream(in);
            } catch (Exception e) {
                return null;
            }
        }

        int width() { return Math.round(FW * scale); }

        int height() { return Math.round((FH + PAD) * scale); }

        void goTo(float targetX, float groundY, float liftPx) {
            tx = targetX;
            ground = groundY;
            lift = liftPx;
            if (!placed) { placed = true; x = -width(); yo = 0; }   // walks in from the left edge
            if (Math.abs(tx - x) > 2) enter(WALK); else targetYo = -lift;
            resume();
        }

        void drop() {
            targetYo = 0;
            resume();
        }

        void pause() { running = false; }

        void resume() {
            if (running) return;
            running = true;
            last = 0;
            postOnAnimation(tick);
        }

        private void enter(int m) {
            mode = m;
            frame = 0;
            acc = 0;
            dir = 1;
        }

        private final Runnable tick = new Runnable() {
            @Override public void run() {
                if (!running) return;
                long now = AnimationUtils.currentAnimationTimeMillis();
                float dt = last == 0 ? 0.016f : Math.min(0.05f, (now - last) / 1000f);
                last = now;
                step(dt);
                postOnAnimation(this);
            }
        };

        private void step(float dt) {
            if (mode == WALK) {
                float d = tx - x;
                if (Math.abs(d) > 1) {
                    face = d > 0 ? 1 : -1;
                    float speed = (Math.abs(d) > 260 * density ? 190f : 75f) * density;
                    x += Math.signum(d) * Math.min(Math.abs(d), speed * dt);
                    targetYo = 0;
                } else {
                    x = tx;
                    targetYo = -lift;
                    face = 1;
                    enter(SIT);
                }
            }
            float dy = targetYo - yo;
            if (Math.abs(dy) > 0.5f) yo += Math.signum(dy) * Math.min(Math.abs(dy), 80f * density * dt);
            acc += dt;
            while (acc >= PERIOD[mode]) {
                acc -= PERIOD[mode];
                if (mode == WALK) {
                    frame = (frame + 1) % COUNT[WALK];
                } else if (mode == SIT) {
                    if (++frame >= COUNT[SIT]) enter(IDLE);
                } else {
                    frame += dir;
                    if (frame >= COUNT[IDLE] - 1) { frame = COUNT[IDLE] - 1; dir = -1; }
                    else if (frame <= 0) { frame = 0; dir = 1; }
                }
            }
            setTranslationX(x - width() / 2f);
            setTranslationY(ground - height() + yo);
            invalidate();
        }

        @Override protected void onDraw(Canvas canvas) {
            int f = Math.min(frame, COUNT[mode] - 1);
            src.set(f * FW, 0, (f + 1) * FW, FH);
            dst.set(0, Math.round(PAD * scale), Math.round(FW * scale), Math.round((FH + PAD) * scale));
            canvas.save();
            if (face < 0 && mode == WALK) canvas.scale(-1, 1, getWidth() / 2f, getHeight() / 2f);
            canvas.drawBitmap(strip[mode], src, dst, paint);
            canvas.restore();
        }
    }
}
