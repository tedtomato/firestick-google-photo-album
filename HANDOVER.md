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
- Repo: https://github.com/tedtomato/firestick-google-photo-album (public). CI builds every branch and
  publishes the "latest" release from the default branch:
  https://github.com/tedtomato/firestick-google-photo-album/releases/latest/download/PhotoFrame.apk
  (short link for Downloader: tinyurl.com/tedframe)
- It compiles and the unit tests pass on CI (first try, no fixes needed).
- Parser verified against a real album of the user's with the "Check album parsing" workflow
  (`LiveAlbumTest`, opt-in via `PHOTOFRAME_TEST_ALBUM`): title found, 38 photos, images load at
  `=w1920-h1080`. Paging (`snAcKc`) verified on the user's Firestick with an album of ~1800 photos
  (1829 found).
- Tested by the user on their Firestick: installs via tinyurl.com/tedframe, slideshow, slow zoom,
  portrait pairs and the moiré fix all work, with no performance problems.

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
                       flags videos (contain "76647426"); regex fallback; paging token; batchexecute parsing
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

## Done since the first handover
All the original next steps are done: repo created, CI green, parser checked on a real album, paging
checked on a 1,800-photo album, installed and used on the Firestick. Added on the user's request:
- Slow zoom on every photo (`Settings.slowZoom`, on by default).
- Two portrait photos side by side (`Settings.pairPortraits`, on by default). A slide is one or two
  photos; each slot has two panes.
- Moiré fix for zooming photos: `SlideshowActivity.loadScale` loads moving photos 1.3–1.5× the screen
  resolution and scales them down with mipmaps, or loads them a bit softer when the photo has too few
  pixels, so the on-screen scale never sits near 1:1. `largeHeap` is on for the bigger bitmaps.
- Short install link `tinyurl.com/tedframe` (owned by the user) → the GitHub "latest" release.
- Videos (`Settings.videos`: off by default, muted, or with sound), played with Media3 ExoPlayer in a
  TextureView over the poster frame. URLs tried in order: `=m37` (1080p), `=m22` (720p), `=m18` (360p),
  all H.264 MP4s from googlevideo.com, then `=dv` (original file). Probed on the user's album: `=m37` 404
  for a 720p video, `=m22`/`=m18` 206 MP4, `=dv` 200 MP4 without range support.

If the album page format changes, run the "Check album parsing" workflow with a link; its report shows
the page shape, parsed counts and whether an image loads, without printing the link or any URLs.

## Known limitations / ideas not done
- HEIC and other photo formats are served by Google as JPEG/WebP through lh3, so they're fine.
- Not registered as a Fire TV screensaver (DreamService). Possible later, but Fire OS makes choosing it
  awkward (adb `settings put secure screensaver_components …`).
- No auto-start on boot.
