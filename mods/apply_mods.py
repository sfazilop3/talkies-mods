#!/usr/bin/env python3
"""Applies the Balloon Dock mods to a Talkies source folder (any version whose anchor lines still exist).

  python apply_mods.py <talkies folder> [--repo user/talkies-mods] [--cert <sha256 of your signing certificate>] [--build N]

What it does
  1. copies the new files (Pop.java and the bundled characters) into the folder;
  2. adds a handful of one-line hooks to HomeActivity.java and AndroidManifest.xml, found by the code around them,
     not by line number;
  3. --repo / --cert: points Talkies' own updater (Settings -> Updates) at your releases instead of the original ones;
  4. --build N: makes versionName "<original>.N" and versionCode "<original>*1000+N", so every build is a real update.
Safe to run twice. If a hook can't be placed it says which one and changes nothing.
"""
import argparse, pathlib, re, shutil, sys

HERE = pathlib.Path(__file__).resolve().parent
MARK = "balloon-mods"
JAVA = "app/src/main/java/com/techpp/glass"


def loose(anchor):
    """The anchor as a pattern that ignores differences in spacing and line breaks."""
    return re.compile(r"\s+".join(re.escape(t) for t in anchor.split()))


def indent_of(text, pos):
    start = text.rfind("\n", 0, pos) + 1
    return re.match(r"[ \t]*", text[start:]).group(0)


def put(text, name, anchor, kind, code):
    found = list(loose(anchor).finditer(text))
    if len(found) != 1:
        raise LookupError(f"{name}: anchor found {len(found)} times (needs exactly 1): {anchor}")
    m = found[0]
    ind = indent_of(text, m.start())
    lines = code.split("\n")
    rest = "".join("\n" + (ind + l if l.strip() else "") for l in lines[1:])
    if kind == "after":      # the matched code, then the new lines
        new = m.group(0) + "".join("\n" + (ind + l if l.strip() else "") for l in lines)
    elif kind == "before":   # the new lines, then the matched code
        new = lines[0] + rest + "\n" + ind + m.group(0)
    else:                    # "sub": the new lines replace the matched code
        new = lines[0] + rest
    return text[:m.start()] + new + text[m.end():]


MANIFEST_ACTIVITY = '''<!-- balloon-mods: the birthday surprise screen -->
<activity
    android:name=".BirthdayActivity"
    android:exported="true"
    android:launchMode="singleTop"
    android:screenOrientation="landscape"
    android:theme="@android:style/Theme.DeviceDefault.NoActionBar.Fullscreen"
    android:configChanges="keyboard|keyboardHidden|navigation|screenSize|smallestScreenSize|screenLayout|orientation|uiMode" />'''

POPSYNC = '''/**
 * balloon-mods: the pop-up characters follow the dock. Focus on a dock app pops its character up behind the tile;
 * anywhere else (the icons, picks, All apps, a menu, or while moving an app) the character floats away. The picks
 * fade back while a character is up, then come back to readable after a few seconds.
 */
private void popSync() {
    if (pop == null) return;
    boolean on = popOn && zone == Z_DOCK && !moving && dockIdx < dockTiles.size();
    View t = on ? dockTiles.get(dockIdx) : null;
    if (t == null || t.getWidth() == 0) {
        pop.hide(1);
        if (bday != null) bday.blur();
        picksRow.removeCallbacks(popEase);
        picksRow.animate().setStartDelay(0).alpha(1f).setDuration(220).start();
        return;
    }
    int dir = popLast < 0 || dockIdx >= popLast ? 1 : -1;
    popLast = dockIdx;
    int cx = dockBand.getLeft() + dock.getLeft() + t.getLeft() + t.getWidth() / 2;
    int bottom = dockBand.getTop() + dock.getTop() + t.getTop() + t.getHeight() / 4;
    int color = dockIdx < apps.size() ? apps.get(dockIdx).glowColor(pm) : 0xFF8A8F98;
    pop.show(dockIdx, color, cx, bottom, dir);
    if (bday != null) bday.focus(t, cx);
    picksRow.removeCallbacks(popEase);
    picksRow.animate().setStartDelay(0).alpha(0.18f).setDuration(220).start();
    picksRow.postDelayed(popEase, 3000);
}
'''

BDAYTOOLS = '''// balloon-mods: the birthday girl — her name, her picture from this TV, and which character is her.
private static final String BDAY_PHOTO = "#birthday-girl";

private android.content.SharedPreferences birthdayPrefs() {
    return getSharedPreferences(BirthdayDecor.PREFS, MODE_PRIVATE);
}

private int birthdayChar() {
    int n = birthdayPrefs().getInt("hat", 0);
    return n < 0 || n > 6 ? 0 : n;
}

private String birthdayName() {
    return birthdayPrefs().getString("name", "Birthday Girl");
}

/** Her own picture (files/bday/girl.png), or null while the app's picture is used. */
private File birthdayPhoto() {
    File dir = getExternalFilesDir(null);
    if (dir == null) return null;
    for (String ext : new String[]{"png", "jpg", "webp"}) {
        File f = new File(dir, "bday/girl." + ext);
        if (f.isFile()) return f;
    }
    return null;
}

private static String birthdayCharName(int i) {
    return i < 3 ? "Girl " + (i + 1) : "Pup " + (i - 2);
}

private String birthdayGirlLabel() {
    String n = birthdayName();
    return (n.isEmpty() || "Birthday Girl".equals(n) ? "" : n + "  ·  ") + birthdayCharName(birthdayChar());
}

private int birthdayCharArt(int i) {
    switch (i) {
        case 1: return R.drawable.pop_char2;
        case 2: return R.drawable.pop_char3;
        case 3: return R.drawable.pop_char4;
        case 4: return R.drawable.pop_char5;
        case 5: return R.drawable.pop_char6;
        case 6: return R.drawable.pop_char7;
        default: return R.drawable.pop_char1;
    }
}

/** Talkies settings → Birthday girl: her name, a picture of her from this TV, and which character wears the hat. */
private void openBirthdayGirl() {
    beginMenu(settingsAt, "Birthday girl  ·  hat, name and picture");
    final View[] nm = new View[1];
    nm[0] = addRow("Name", birthdayName(), () -> askText("Birthday girl's name",
            "The greeting and the characters' speech bubbles say her name.",
            birthdayName(), false, !"Birthday Girl".equals(birthdayName()), text -> {
                if (text.isEmpty()) text = "Birthday Girl";
                birthdayPrefs().edit().putString("name", text).apply();
                setStatus(nm[0], text);
                if (bday != null) bday.refresh();
                if (birthdayRow != null) setStatus(birthdayRow, birthdayGirlLabel());
            }), true);
    File own = birthdayPhoto();
    final View[] pr = new View[1];
    pr[0] = addRow("Picture", own != null ? "your picture" : "the app's picture", () -> {
        pictureFor = BDAY_PHOTO;
        openTvPictures();
    }, false);
    if (own != null) addRow("Use the app's picture again", null, () -> {
        own.delete();
        setStatus(pr[0], "the app's picture");
        if (pop != null) pop.reloadArt();
        if (bday != null) bday.refresh();
        Toast.makeText(this, "The app's picture is back", Toast.LENGTH_SHORT).show();
    }, true);
    addMenuHeading("Who wears the party hat");
    final View[] rows = new View[7];
    for (int i = 0; i < rows.length; i++) {
        final int n = i;
        rows[i] = addRow(birthdayCharName(i), n == birthdayChar() ? "On" : null, () -> {
            birthdayPrefs().edit().putInt("hat", n).apply();
            for (int j = 0; j < rows.length; j++) setStatus(rows[j], j == n ? "On" : null);
            if (pop != null) pop.reloadArt();
            if (bday != null) bday.refresh();
            if (birthdayRow != null) setStatus(birthdayRow, birthdayGirlLabel());
        }, true);
        addThumb(rows[i], Uri.parse("android.resource://" + getPackageName() + "/" + birthdayCharArt(i)));
    }
    showMenu(440);
}

/**
 * Saves the picture picked for the birthday girl as files/bday/girl.png: 640 × 880, the whole picture with
 * transparent edges, so it floats behind the dock tile like the bundled characters.
 */
private boolean saveBirthdayPhoto(java.util.function.Supplier<java.io.InputStream> open) {
    final int w = 640, h = 880;
    try {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        try (java.io.InputStream in = open.get()) { BitmapFactory.decodeStream(in, null, o); }
        if (o.outWidth <= 0 || o.outHeight <= 0) return false;
        o.inSampleSize = 1;
        while (o.outWidth / (o.inSampleSize * 2) >= w && o.outHeight / (o.inSampleSize * 2) >= h) o.inSampleSize *= 2;
        o.inJustDecodeBounds = false;
        Bitmap src;
        try (java.io.InputStream in = open.get()) { src = BitmapFactory.decodeStream(in, null, o); }
        if (src == null) return false;
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        float k = Math.min(w / (float) src.getWidth(), h / (float) src.getHeight());
        float sw = src.getWidth() * k, sh = src.getHeight() * k;
        new android.graphics.Canvas(out).drawBitmap(src, null,
                new RectF((w - sw) / 2, (h - sh) / 2, (w + sw) / 2, (h + sh) / 2),
                new android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG));
        src.recycle();
        File base = getExternalFilesDir(null);
        if (base == null) return false;
        File dir = new File(base, "bday");
        if (!dir.isDirectory() && !dir.mkdirs()) return false;
        File tmp = new File(dir, "girl.tmp");
        try (java.io.FileOutputStream f = new java.io.FileOutputStream(tmp)) {
            out.compress(Bitmap.CompressFormat.PNG, 100, f);
        }
        out.recycle();
        for (String ext : new String[]{"jpg", "webp", "png"}) new File(dir, "girl." + ext).delete();
        return tmp.renameTo(new File(dir, "girl.png"));
    } catch (Exception | OutOfMemoryError e) {
        Log.w(TAG, "Couldn't save her picture: " + e);
        return false;
    }
}
'''


HOOKS = [
    ("home", "fields", "private boolean inFront;", "after",
     "// balloon-mods: pop-up characters over the dock (Pop), their setting, and the dock position they last popped for.\n"
     "private Pop pop;\nprivate BirthdayDecor bday;\nprivate boolean popOn = true, catOn = true, holdMenuOn = true;\nprivate int popLast = -1;\nprivate View birthdayRow;\n"
     "private final Runnable popEase = () -> {\n"
     "    if (pop != null && popOn && zone == Z_DOCK) picksRow.animate().setStartDelay(0).alpha(0.5f).setDuration(400).start();\n"
     "};"),
    ("home", "create", "ring = findViewById(R.id.ring);", "after",
     'popOn = prefs.getBoolean("pop_on", true);\ncatOn = prefs.getBoolean("cat_on", true);\n'
     'holdMenuOn = prefs.getBoolean("hold_menu_on", true);\n'
     'pop = new Pop(this, page, dockBand);\nbday = new BirthdayDecor(this, (FrameLayout) root, page, dockBand, pop);\n'
     'bday.setCatOn(catOn);'),
    ("home", "focus", "focusedView = v;", "after", "popSync();"),
    ("home", "dock rebuilt", "dock.removeAllViews(); dockTiles.clear();", "after",
     "if (pop != null) pop.release();   // the tiles are new: pop the character up again where they are now\npopLast = -1;"),
    ("home", "menu opens", "zone = Z_MENU; focusMenu(0);", "sub", "zone = Z_MENU;\npopSync();\nfocusMenu(0);"),
    ("home", "move starts", "holdFired = true; moving = true;", "sub", "holdFired = true;\nmoving = true;\npopSync();"),
    ("home", "move ends", "if (!moving) return; moving = false;", "sub", "if (!moving) return;\nmoving = false;\npopSync();"),
    ("home", "popSync", "private void focusDock(int i) {", "before", POPSYNC),
    ("home", "setting", 'setStatus(da, dockCount + " in the dock"); }, true);', "after",
     'final View[] pp = new View[1];\n'
     'pp[0] = addRow("Pop-up characters", popOn ? "On" : "Off", () -> {\n'
     '    popOn = !popOn;\n'
     '    prefs.edit().putBoolean("pop_on", popOn).apply();\n'
     '    setStatus(pp[0], popOn ? "On" : "Off");\n'
     '}, true);\n'
     'final View[] hp = new View[1];\n'
     'hp[0] = addRow("Long-press app menu", holdMenuOn ? "On" : "Off", () -> {\n'
     '    holdMenuOn = !holdMenuOn;\n'
     '    prefs.edit().putBoolean("hold_menu_on", holdMenuOn).apply();\n'
     '    setStatus(hp[0], holdMenuOn ? "On" : "Off");\n'
     '}, true);\n'
     'final View[] cp = new View[1];\n'
     'cp[0] = addRow("Cat animation", catOn ? "On" : "Off", () -> {\n'
     '    catOn = !catOn;\n'
     '    prefs.edit().putBoolean("cat_on", catOn).apply();\n'
     '    setStatus(cp[0], catOn ? "On" : "Off");\n'
     '    if (bday != null) bday.setCatOn(catOn);\n'
     '}, true);\n'
     'birthdayRow = addRow("Birthday girl", birthdayGirlLabel(), this::openBirthdayGirl, false);'),
    ("home", "hold menu", "if (zone == Z_DOCK || zone == Z_GRID) openAppMenu();", "sub",
     "if (zone == Z_DOCK || zone == Z_GRID) {\n"
     "    if (holdMenuOn) openAppMenu();   // balloon-mods: Talkies settings can switch this off\n"
     "}"),
    ("home", "stop", "protected void onStop() { super.onStop();", "after",
     "if (pop != null) pop.release();\nif (bday != null) bday.release();\npicksRow.removeCallbacks(popEase);\npicksRow.setAlpha(1f);\npopLast = -1;"),
    ("home", "resume", "root.post(this::snapRing);", "after",
     "if (bday != null) bday.refresh();   // the birthday look, until midnight\nroot.post(this::popSync);   // back on the home screen: pop the character up again"),
    # the birthday girl's picture goes through the same "From this TV" picker as the wallpaper and tile images
    ("home", "picture label", "return a != null ? a.label : pkg;", "sub",
     "return a != null ? a.label : BDAY_PHOTO.equals(pkg) ? \"Birthday girl\" : pkg;"),
    ("home", "picture saved", "private void pictureSaved(String pkg, boolean ok) {", "after",
     "if (BDAY_PHOTO.equals(pkg)) {\n"
     "    Toast.makeText(this, ok ? \"Her picture is on the home screen now\" : \"Couldn't use that picture\", Toast.LENGTH_SHORT).show();\n"
     "    if (ok) {\n"
     "        if (pop != null) pop.reloadArt();\n"
     "        if (bday != null) bday.refresh();\n"
     "    }\n"
     "    return;\n"
     "}"),
    ("home", "picture save", "private boolean savePicture(java.util.function.Supplier<java.io.InputStream> open, String pkg) {", "after",
     "if (BDAY_PHOTO.equals(pkg)) return saveBirthdayPhoto(open);"),
    ("home", "birthday girl", "/** Talkies settings, by holding OK on the gear or from a remote button. */", "before", BDAYTOOLS),
    # the secret remote codes that start the birthday surprise (BirthdaySecret): up up down down OK, left left right right OK
    ("home", "secret code", "public boolean dispatchKeyEvent(KeyEvent e) {", "after",
     "if (BirthdaySecret.feed(this, e)) return true;   // balloon-mods"),
    ("manifest", "birthday screen", "</application>", "before", MANIFEST_ACTIVITY),
]


def read(path):
    raw = path.read_bytes().decode("utf-8")
    return raw.replace("\r\n", "\n"), "\r\n" in raw


def write(path, text, crlf):
    path.write_bytes((text.replace("\n", "\r\n") if crlf else text).encode("utf-8"))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("talkies")
    ap.add_argument("--repo")
    ap.add_argument("--cert")
    ap.add_argument("--build", type=int)
    a = ap.parse_args()
    root = pathlib.Path(a.talkies)
    home = root / JAVA / "HomeActivity.java"
    if not home.is_file():
        sys.exit(f"{home} not found: is {root} a Talkies source folder?")

    # 1. hooks first, so nothing is touched when one fails
    targets = {"home": (home, MARK), "manifest": (root / "app/src/main/AndroidManifest.xml", "BirthdayActivity")}
    done, failed = {}, []
    for key, (path, marker) in targets.items():
        if not path.is_file():
            sys.exit(f"{path} not found: is {root} a Talkies source folder?")
        text, crlf = read(path)
        if marker in text:
            print(f"{path.name}: hooks already there")
            continue
        n = 0
        for f, name, anchor, kind, code in HOOKS:
            if f != key:
                continue
            try:
                text = put(text, name, anchor, kind, code)
                n += 1
            except LookupError as e:
                failed.append(str(e))
        done[key] = (path, text, crlf, n)
    if failed:
        print("These hooks could not be placed (Talkies changed these lines). Nothing was changed.")
        for f in failed:
            print("  -", f)
        sys.exit(2)
    for path, text, crlf, n in done.values():
        write(path, text, crlf)
        print(f"{path.name}: {n} hooks added")

    # 2. new files
    for src in sorted((HERE / "files").rglob("*")):
        if src.is_file():
            dst = root / src.relative_to(HERE / "files")
            dst.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(src, dst)
    print("files copied")

    # 3. Talkies' own updater follows your releases
    upd = root / JAVA / "SelfUpdate.java"
    if upd.is_file() and (a.repo or a.cert):
        s, c = read(upd)
        if a.repo:
            s = s.replace("rajupp/talkies", a.repo)
        if a.cert:
            s, n = re.subn(r'(RELEASE_CERT\s*=\s*")[0-9a-fA-F]{64}(")', lambda m: m.group(1) + a.cert.lower() + m.group(2), s)
            if n != 1 and a.cert.lower() not in s:
                sys.exit("SelfUpdate.java: RELEASE_CERT line not found")
        write(upd, s, c)
        print("SelfUpdate.java: updates now come from", a.repo or "(unchanged)")

    # 4. version: every build is newer than the last one and than the original
    if a.build is not None:
        g = root / "app" / "build.gradle"
        s, c = read(g)
        if MARK in s:
            print("build.gradle: version already set")
        else:
            code = re.search(r"versionCode\s+(\d+)", s)
            name = re.search(r"versionName\s+'([^']+)'", s)
            if not code or not name:
                sys.exit("build.gradle: versionCode / versionName not found")
            s = s.replace(code.group(0), f"versionCode {int(code.group(1)) * 1000 + a.build}   // {MARK}")
            s = s.replace(name.group(0), f"versionName '{name.group(1)}.{a.build}'")
            write(g, s, c)
            print(f"build.gradle: version {name.group(1)}.{a.build}")


if __name__ == "__main__":
    main()
