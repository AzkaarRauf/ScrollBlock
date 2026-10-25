# ScrollBlock

Blocks short-video feeds on Android: **YouTube Shorts, Instagram Reels and Facebook Reels**, in the
apps and in browsers (Chrome, Brave, Edge, Firefox, Samsung Internet, Opera, Vivaldi, Kiwi, DuckDuckGo).
When a feed opens it presses Back (or Home) for you.

## Why it's light

- Plain Java on framework APIs only: no AndroidX, Kotlin runtime or Compose. The APK is about 30 KB.
- It is an AccessibilityService, which the system keeps running. No foreground service, no wakelocks,
  no polling, no network permission.
- The service tells the system to deliver events **only from enabled apps/browsers**. While you're in
  any other app, it does nothing at all.
- Events are coalesced by the system (150 ms), then throttled to at most one check every 400 ms per app,
  with a trailing check so the end state is never missed. A check is 1–3 view-id lookups that run inside
  the target app; only the matching nodes come back.
- Rules are parsed once, when they change. The per-event path is map lookups only.
- Supports Android 5.0+ (API 21).

## Install

1. Copy `ScrollBlock.apk` to the phone and install it (allow "install unknown apps").
2. Open ScrollBlock → **Enable in accessibility settings** → ScrollBlock → On.
   - **Android 13+:** if the toggle is greyed out, go to App info → ⋮ → **Allow restricted settings**
     (the app has a button for this), then try again.
3. On Xiaomi, Huawei, Oppo, Vivo, Samsung and similar phones, open **Battery optimization settings** and
   exclude ScrollBlock. Some vendors kill accessibility services anyway; locking the app in the recents
   screen helps.

## Settings

- **Block**: per feed, choose whether to block it in the app, in browsers, or both.
- **Free swipes before blocking**: 0 blocks as soon as a feed opens. N lets you swipe N times, then
  blocks. Leaving the feed resets the count.
- **When blocked**: go back, or go to the home screen.
- **Pause for 15 minutes**.
- **Blocked today** counter.

## When an app update breaks detection

Apps rename their internal view ids from time to time. You can fix this on the phone without rebuilding:

1. **Capture a screen (in 6 s)**, then open the feed. Blocking pauses for a few seconds while it captures.
2. **View last capture** shows the screen's view tree: `Class #view-id "text" [description] (v)`.
3. Find an id that only appears on that feed, then **Edit detection rules** and add a line such as
   `app yt com.google.android.youtube id:the_new_id`.

Rule format (the defaults are in `Rules.java` and documented at the top of the editor):

```
target yt YouTube Shorts
app yt com.google.android.youtube id:reel_recycler           # visible view with this id
app fb com.facebook.katana text:Reels & text:Create          # all matchers on a line must match
url yt youtube.com/shorts                                    # browser URL contains this
browser com.brave.browser url_bar                            # browser package + URL bar view id
```

You can add your own targets, for example TikTok (`target tt TikTok`, `app tt com.zhiliaoapp.musically id:...`,
`url tt tiktok.com`). New targets get their own toggles automatically.

## Known limits

- **Facebook** builds most of its UI without resource ids, so the default FB rule is a text heuristic.
  It's the most likely to need tuning with the capture tool.
- **Browsers** are detected from the URL bar text. If a browser shows only the domain and hides the
  path, it can't tell Shorts apart from the rest of YouTube. Current Chrome and Brave show the path, but check on your device.
- Detection runs in steps of a few hundred ms, so a feed can be on screen for a moment before it closes.

## Building

- **Android Studio / Gradle:** open the folder, then run `./gradlew assembleRelease`. Release builds use R8.
- **No SDK needed:** run `./build.sh`. It uses only `aapt`, `javac`, `dx`/`d8`, `zipalign` and `apksigner`
  (on Ubuntu: `apt install aapt dalvik-exchange zipalign apksigner`) plus an API 33 `android.jar`
  (set `ANDROID_JAR`). Because `dx` can't handle lambdas, the code uses anonymous classes.

Signing keys are not in the repo (`signing/` is git-ignored):

- `build.sh` uses `signing/scrollblock.jks` and the password in `KS_PASS`. If the file is missing, it
  creates a new key.
- Gradle reads `scrollblock.keystore` and `scrollblock.password` from `~/.gradle/gradle.properties` and
  uses the debug key if they aren't set.

**Back up your keystore.** Android only installs an update signed with the same key.
