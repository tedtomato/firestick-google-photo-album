# Handover: Photo Frame for Fire TV

## Goal
An Android APK for an Amazon Firestick / Fire TV that shows a random slideshow of photos from one or more
**Google Photos albums**, with transitions and a configurable interval (e.g. 3 s). New photos added to an
album must appear automatically, like on a Google Nest Hub. The user wants **GitHub** for the repo and
for builds (GitHub Actions builds the APK).

## Key decisions already made (don't re-litigate)
- **No Google Photos API.** Since 31 March 2025 the Library API only exposes media the app itself
  created. The Picker API needs manual re-picking and has no live album sync. The user rejected both.
- **Approach: parse the public page of each album's share link** (`https://photos.app.goo.gl/…` or
  `https://photos.google.com/share/…?key=…`). The user confirmed they're happy to share albums by link.
  Trade-offs: it's unofficial and Google could change the page, and anyone with the link can see the album.
- **Native Kotlin app, plain Android Views** (no Compose, no Leanback library), minSdk 22, so it also runs
  on old Fire OS 5.
- **Builds on GitHub Actions only.** The local PC has Android Studio, but its bundled JDK is 25, there's no
  Gradle, and AGP 8.7 doesn't run on JDK 25. CI uses JDK 17 + Gradle 8.11.1 (installed by
  `gradle/actions/setup-gradle`, so the repo has **no Gradle wrapper**).

## Status
- All code is written. It has **never been compiled or run**. The first CI run is the first compile, so
  expect to fix a few compile errors.
- No git repo yet, and no GitHub repo yet.
- Still open (ask the user):
  1. Public or private repo?
     - Public: the Firestick's Downloader app can fetch
       `https://github.com/<user>/<repo>/releases/latest/download/PhotoFrame.apk`.
     - Private: install from a PC with `adb install -r PhotoFrame.apk` after turning on ADB debugging on
       the Fire TV.
  2. How to push: the session with GitHub access decides.

## Where the files are
The project currently sits in a temporary folder that belongs to the original Claude session. **It must be
copied to a permanent folder or pushed before that session is deleted.** The project root is the
repository root.

```
.github/workflows/build.yml   CI: gradle testReleaseUnitTest assembleRelease → artifact + "latest" release
settings.gradle.kts, build.gradle.kts, gradle.properties
  AGP 8.7.3, Kotlin 2.0.21, compileSdk/targetSdk 35
app/build.gradle.kts          versionCode = GITHUB_RUN_NUMBER; signs release+debug with app/photoframe.jks
app/photoframe.jks            throwaway PKCS12 key (store/key password + alias = "photoframe"),
                              committed on purpose so CI builds install over each other
app/src/main/AndroidManifest.xml   LAUNCHER + LEANBACK_LAUNCHER, banner, INTERNET
app/src/main/java/app/photoframe/tv/
  Photo.kt             data class; sizedUrl(w,h,crop) appends "=wW-hH[-c]" to the lh3 base URL
  AlbumParser.kt       pure parsing (unit-tested): AF_initDataCallback blocks → finds the array with the
                       most ["AF1Qip…",["https://lh3.googleusercontent.com/…",w,h,…],takenAt,…] items;
                       skips videos (contain "76647426"); regex fallback; paging token; batchexecute parsing
  AlbumFetcher.kt      OkHttp; follows short-link redirect; desktop UA + "SOCS=CAI; CONSENT=YES+" cookie
                       (EU consent wall); best-effort paging via POST /_/PhotosUi/data/batchexecute
                       rpcid "snAcKc" args [albumId, pageToken, null, key]  ← UNVERIFIED, failures ignored
  Library.kt           singleton: albums list in SharedPreferences (JSON), photo cache in filesDir/photos.json,
                       refreshAlbum/refreshAll (coroutines, IO), announces changes by bumping pref "libraryVersion"
  Settings.kt          interval, transition, fit mode, shuffle, refresh minutes, clock, date (+ option lists)
  SlideshowActivity.kt two stacked slots (blurred backdrop + photo), Glide loads into the hidden slot then
                       animates; transitions fade/kenburns/slide/zoom/black/random; shuffle-bag + history
                       for ◀/▶; OK = pause; Menu/Up/Down = settings; refresh loop every minute checks if due;
                       FLAG_KEEP_SCREEN_ON
  SettingsActivity.kt  TV-friendly focusable rows + AlertDialog pickers; add/remove albums; shows a QR code
  ConfigServer.kt      tiny HTTP server on port 8765–8770 (only while the settings screen is open), phone
                       web page to add/remove albums (GET /, POST /add, /remove, /refresh)
  QrCode.kt            zxing core 3.3.3 QR bitmap + Net.localIpv4()
app/src/main/res/      layouts, strings, themes, row/badge drawables, generated launcher PNGs + 320x180 banner
app/src/test/java/app/photoframe/tv/AlbumParserTest.kt   synthetic HTML/batch fixtures (uses org.json:json)
README.md              user-facing setup and install instructions
```

## Next steps
1. Create the GitHub repo (public or private, per the user's answer), commit everything including
   `app/photoframe.jks`, and push to `main`.
2. Watch the Actions run, then fix compile, test or lint errors until it's green. Likely trouble spots:
   - Glide 4.16 `RequestListener` override signatures in `SlideshowActivity.display()`. If they fight you,
     switch to `CustomTarget`, but clear old targets so bitmaps aren't leaked.
   - Kotlin overload or nullability nits.
   - `gradle/actions/setup-gradle@v4` `gradle-version` input.
3. Test the parser on a real album. Ask the user for a share link, or check that one of their albums
   parses (title, photo count, URLs load at `=w1920-h1080`). The format reference was the actively
   maintained WordPress plugin "Shared Albums for Google Photos" (JanZeman), `includes/class-data-provider.php`.
   Per that plugin, the album page exposes about 300 items.
4. Test paging on an album with more than 300 photos. If `snAcKc` doesn't work, either find the right rpc
   format or document the ~300 limit (the app already handles the failure gracefully).
5. Install on the Firestick and check: first launch opens settings, the QR page works from a phone, the
   slideshow and transitions run, the remote keys work, and the screensaver doesn't kick in.

## Known limitations / ideas not done
- Videos are skipped. HEIC and other formats are served by Google as JPEG/WebP through lh3, so they're fine.
- Not registered as a Fire TV screensaver (DreamService). Possible later, but Fire OS makes choosing it
  awkward (adb `settings put secure screensaver_components …`).
- No auto-start on boot.
