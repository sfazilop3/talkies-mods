# Talkies mods

Pop-up characters for [Talkies](https://github.com/rajupp/talkies), kept apart from Talkies itself so they survive
its updates. This repository holds only the mods; GitHub builds the rest.

```
 you push mods ─┐
                ├─►  GitHub builds the newest Talkies + mods, signs it, publishes a release   (every night)
 Talkies update ┘
                                   │
   TV: "Talkies Mods" app ◄────────┘   installs it (first time)       Talkies > Settings > Updates  (afterwards)
```

Android doesn't let one app change another, so the mods can't be added to Talkies on the TV itself. What works: the
modded Talkies is built here, and an app on the TV installs it. Android asks you to confirm once per install.

## Set it up (once, about 30 minutes, all on Windows)

**1. Make the repository.** On github.com create a **public** repository named `talkies-mods` (public, so the TV can read its
releases without a login). Then, in this folder:

```
git init -b main
git add .
git commit -m "Talkies mods"
git remote add origin https://github.com/YOUR_USER/talkies-mods.git
git push -u origin main
```

**2. Make your signing key.** Every build is signed with it, so each new build installs over the last one and keeps your
settings. (`keytool` is in Android Studio's folder: `C:\Program Files\Android\Android Studio\jbr\bin`.)

```
keytool -genkeypair -v -keystore mods.jks -alias mods -keyalg RSA -keysize 2048 -validity 10000
```

**Back `mods.jks` up somewhere safe.** If it is lost, every TV has to uninstall and reinstall once.
Then on GitHub: repository > Settings > Secrets and variables > Actions > New repository secret, four times:

| Name | Value |
|---|---|
| `KEYSTORE_B64` | the file as text: in PowerShell `[Convert]::ToBase64String([IO.File]::ReadAllBytes("mods.jks")) \| Set-Clipboard` |
| `KEYSTORE_PASSWORD` | the keystore password you typed |
| `KEY_ALIAS` | `mods` |
| `KEY_PASSWORD` | the key password you typed (the same, if you pressed Enter) |
| `ASSETS_SSH_KEY` | the private half of a read-only deploy key for your **private** pictures repository (next step) |

**Your own pictures live in a private repository**, so this one can stay public without exposing them. Create a private
repository (for example `talkies-mods-private`) laid out like the app tree:

```
app/src/main/res/drawable-nodpi/pop_char1..7.webp   the pop-up characters
app/src/main/assets/cat/walk.webp, sit.webp, idle.webp
app/src/main/assets/birthday.html                   the birthday page
```

In that repository: Settings > Deploy keys > Add deploy key, paste a new public key and leave **Allow write access**
off. Then add the matching private key here as `ASSETS_SSH_KEY`. Every build clones the pictures over SSH and copies the
`app/` tree over Talkies after the mods; without the key the build stops rather than quietly publishing the generic drawings.

**3. Build.** Repository > Actions: run **Build Talkies with the mods**, then **Build the Talkies Mods installer app**
(Run workflow). Each takes about 10 minutes. Then Releases has:
- `talkies.apk` in the newest release "Talkies x.y.z.n with mods";
- `TalkiesMods.apk` in the pre-release "Talkies Mods installer".

**4. Put it on the TV.** Install `TalkiesMods.apk` (with `adb install TalkiesMods.apk`, or open its release link in a
browser/Downloader app on the TV and allow "install unknown apps"). Open **Talkies Mods**, press **Install**, confirm.
Over the original Talkies the first time: Android needs the original removed once, because the mods are signed with your
key. Back up in Talkies first, press **Uninstall Talkies**, then **Install**.

**5. After that** you do nothing. GitHub checks for a new Talkies every night, builds it with the mods and publishes it,
and Talkies > Settings > Updates (or the bell) installs it with one OK. The `Talkies Mods` app is only needed to put
Talkies on a new TV.

## Sharing it

- **To someone who just wants it:** send the link to `TalkiesMods.apk` (Releases > Talkies Mods installer). They sideload
  one app and press Install. It installs whatever *this* repository last built.
- **To someone who wants their own:** send the repository link. They fork it, add their own five secrets (the signing four
  plus `ASSETS_SSH_KEY` for their private pictures repository), run the two workflows; their installer app then points at
  their fork by itself.

## The birthday surprise

Hidden until you start it. Nothing shows in any menu. From Talkies' home screen, with each key within 4 seconds of the last:

- **up, up, down, down, then OK**: the whole surprise: intro, the balloon game (the letters of her name), the gift, three riddles,
  "Level 8 unlocked" with the parade and the Happy Birthday song, then the cake to blow out.
- **left, left, right, right, then OK**: "Cake time!" with the countdown, then the cake.

From a PC (set her name once, then start it any time):

```
adb shell am start -n com.techpp.glass/.BirthdayActivity --es name "Her name" --es time "18:30" --ez only true
adb shell am start -n com.techpp.glass/.BirthdayActivity
adb shell am start -n com.techpp.glass/.BirthdayActivity --es scene alert
```

`scene` can be `game`, `riddle`, `level`, `cake` or `alert`. Back steps out; Back twice quickly always leaves. It works offline.
The page is `mods/files/app/src/main/assets/birthday.html`; the parade uses the bundled characters unless you replace them
(the page is one file; edit it freely). The riddles and the song lines are inside it.

**The home screen dresses up too**, from the moment the surprise starts until midnight: flags across the top, a greeting with her
name, soft colour behind everything, a few balloons drifting up, a speech bubble with her name from each pop-up character, a party hat
on one character (the first dock app's; `--ei hat 1 --ez only true` picks another), and the cat (walks along the dock to the selected app
and sits on it). Turn it off early: `adb shell am start -n com.techpp.glass/.BirthdayActivity --ez decor false --ez only true`.

The cat's pictures are optional and live in the private pictures repository (see step 2): `walk.webp` (9 frames), `sit.webp` (20) and `idle.webp` (17), each frame 190 x 160 px side by
side, in `app/src/main/assets/cat/`. Without them everything else still shows.

## Your own pictures

The characters in this public repository (`mods/files/app/src/main/res/drawable-nodpi/pop_char1..7.webp`) are generic
drawings, so forks and public builds carry nothing personal. Your own characters go in the private pictures repository.
To try one picture without a rebuild, push it straight to the TV (transparent PNG or WebP, about 400 x 640, the head near the top):

```
adb push mine.png /sdcard/Android/data/com.techpp.glass/files/pop/char1.png
```

`char1` is the first dock app, `char2` the second, and so on up to `char7`. Delete the file to go back to the bundled one.
Switch the characters off or on in Talkies > Settings > Pop-up characters. Two more switches sit in the same menu:
**Long-press app menu** (off: holding OK on the dock or in All apps does nothing) and **Cat animation** (off: the birthday cat stays away).

## If a Talkies update breaks a hook

`mods/apply_mods.py` finds its hook points by the code around them, not by line number, and checks every one before
changing anything. When Talkies rewrites one, the build stops with a message like `focus: anchor found 0 times`, **nothing
is published, and your TVs keep the last good version.** Send me that message and the new Talkies version and I'll fix the anchor.

Try it on your PC any time: `python mods/apply_mods.py path\to\talkies` (safe to run twice).

## Good to know

- Scheduled builds stop after 60 days with no activity in the repository; press Run workflow to wake them.
- Android 12 and newer can sometimes update an app its installer put there without a question; older TVs always ask.
- Talkies itself is MIT licensed (see its repository); the mods here are MIT too. Nothing from Talkies is stored in this repo.
