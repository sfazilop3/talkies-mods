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

HOOKS = [
    ("home", "fields", "private boolean inFront;", "after",
     "// balloon-mods: pop-up characters over the dock (Pop), their setting, and the dock position they last popped for.\n"
     "private Pop pop;\nprivate BirthdayDecor bday;\nprivate boolean popOn = true, catOn = true, holdMenuOn = true;\nprivate int popLast = -1;\n"
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
     '}, true);'),
    ("home", "hold menu", "if (zone == Z_DOCK || zone == Z_GRID) openAppMenu();", "sub",
     "if (zone == Z_DOCK || zone == Z_GRID) {\n"
     "    if (holdMenuOn) openAppMenu();   // balloon-mods: Talkies settings can switch this off\n"
     "}"),
    ("home", "stop", "protected void onStop() { super.onStop();", "after",
     "if (pop != null) pop.release();\nif (bday != null) bday.release();\npicksRow.removeCallbacks(popEase);\npicksRow.setAlpha(1f);\npopLast = -1;"),
    ("home", "resume", "root.post(this::snapRing);", "after",
     "if (bday != null) bday.refresh();   // the birthday look, until midnight\nroot.post(this::popSync);   // back on the home screen: pop the character up again"),
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
