# Photo Frame for Fire TV

Turns a Fire TV / Firestick into a digital photo frame for your **Google Photos shared albums**.
Photos added to an album later show up on the TV automatically, much like a Google Nest Hub.

- Random (or album-order) slideshow from one or more albums
- Change photo every 3 s … 5 min
- Transitions: crossfade, Ken Burns (slow zoom and pan), slide, zoom, fade through black, or a random mix
- A slight, very slow zoom on every photo (can be turned off)
- Two portrait photos side by side, like a Nest Hub (can be turned off)
- Optionally plays the videos in your albums, muted or with sound
- Display modes: whole photo on a blurred background, fill the screen, or smart (fill landscape, fit portrait)
- Optional clock and "date taken" overlays
- Checks albums for new photos every 15 min to 12 h
- Add albums by scanning a QR code on the TV and pasting the link on your phone

## How it works

Google removed third-party access to album contents from its Photos API in March 2025.
Instead, this app reads the public web page behind an album's **share link**, the same page anyone with the
link sees in a browser. Nothing to sign in to, no Google Cloud project.

Trade-offs to be aware of:

- **Anyone with an album's link can view that album.** The link isn't listed anywhere, but treat it like a key.
- **It's not an official API.** If Google changes that page, the app may stop finding photos until the parser
  (`AlbumParser.kt`) is updated. Photos already cached keep playing in the meantime.
- Albums of more than about 300 photos are loaded page by page with a second, unofficial request
  (tested with an album of 1,800+ photos). If that request ever breaks, the app keeps the first ~300.

## Getting a shared album link

In Google Photos (phone or web): open the album → **Share** → **Create link** / **Copy link**.
It looks like `https://photos.app.goo.gl/AbCd1234`.

## Installing on the Fire TV

Every push to the default branch builds the APK with GitHub Actions and publishes it as the **latest** release.

**Option A — Downloader app (public repo):**
1. On the Fire TV: *Settings → My Fire TV → Developer options → Install unknown apps* → allow **Downloader**.
   (If Developer options is hidden: *Settings → My Fire TV → About*, click the device name 7 times.)
2. In Downloader, enter `tinyurl.com/tedframe`.
   It points to `https://github.com/tedtomato/firestick-google-photo-album/releases/latest/download/PhotoFrame.apk`,
   which always serves the newest build, so the same short link also installs updates.

**Option B — adb from a computer (works with a private repo):**
1. On the Fire TV: *Developer options → ADB debugging* → On. Note its IP under *About → Network*.
2. Download `PhotoFrame.apk` from the latest release (or the Actions run), then:
   ```
   adb connect <fire-tv-ip>:5555
   adb install -r PhotoFrame.apk
   ```

Updates install over the old version and keep your albums and settings.

## Using it

- First launch opens the settings screen. Scan the QR code with your phone and paste album links, or type one
  with the remote.
- Remote during the slideshow: **◀ ▶** previous / next, **OK** pause, **☰ Menu** or **▼** settings, **Back** exit.

## Checking that an album still parses

If photos stop appearing, run **Actions → Check album parsing → Run workflow** with an album link.
It fetches the album on GitHub's servers and reports how many photos were found and whether one loads.
The link is masked in the logs and only counts are printed.

## Building locally

Open the folder in Android Studio, or with Gradle 8.11 and JDK 17: `gradle assembleRelease`.
`app/photoframe.jks` is a throwaway signing key included so CI builds can upgrade each other.
