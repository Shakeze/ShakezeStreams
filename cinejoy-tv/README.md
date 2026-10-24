# Cinejoy for Android TV

A lightweight Android TV app that opens https://cinejoy.pk/ full screen and makes it usable with a normal TV remote.

## Using it with the remote

| Button | What it does |
|---|---|
| **D-pad (arrows)** | Moves an on-screen pointer. Hold to speed up. Push against the top/bottom edge to scroll. |
| **OK / Select** | Clicks wherever the pointer is. |
| **Hold OK** (or **Menu**) | Options: Home, Reload, Back/Forward, Zoom in/out, Pointer on/off, Pop-up blocker on/off, Exit. |
| **Back** | Leaves fullscreen video → goes back a page → press twice on the home page to exit. |
| **Play/Pause, FF, Rewind** | Control the video (falls back to clicking the middle of the player if the video is in an embedded player). |
| **CH+ / CH−** | Scroll the page up / down. |

Other details:
- Pop-ups and redirects to other websites (the usual ads on streaming sites) are blocked; the player inside the page still works. Turn the blocker off from the menu if something legitimate gets blocked.
- Over fullscreen video the pointer hides itself after 4 seconds; the first OK press brings it back without clicking.
- Zoom level, pointer mode and blocker setting are remembered.
- The screen stays awake while the app is open.

## Getting the APK

### Option A — GitHub (no software to install)
1. Create a free GitHub account and a new **private** repository.
2. Upload the contents of this folder (drag and drop on the repo page, including the hidden `.github` folder — or use GitHub Desktop).
3. Open the **Actions** tab. The *Build APK* workflow runs automatically (about 3–5 minutes).
4. Open the finished run and download **CinejoyTV-apk** under *Artifacts*. Unzip it to get `CinejoyTV-1.0-release.apk`.

### Option B — Android Studio
1. Install Android Studio, then **File → Open** this folder.
2. Let it sync, then **Build → Build App Bundle(s) / APK(s) → Build APK(s)**.
3. The APK is in `app/build/outputs/apk/`.

## Installing on the TV

**Easiest: Downloader app**
1. On the TV: Settings → Device Preferences → About → click **Build** 7 times to enable Developer options.
2. Settings → Device Preferences → Security & restrictions → **Unknown sources** → allow **Downloader** (on Google TV: Settings → System → Developer options / Apps → Security → Install unknown apps).
3. Upload the APK somewhere with a direct link (Google Drive with a direct-download link, Dropbox with `?dl=1`, etc.), open it in **Downloader** and install.

**Alternatives**
- **Send Files to TV** app (phone → TV over Wi-Fi), then open the APK with a file manager on the TV.
- **USB stick**: copy the APK, plug it into the TV, open it with a file manager (e.g. *File Commander*).
- **ADB** from a computer on the same Wi-Fi: enable *Network debugging* in Developer options, then
  `adb connect <TV-IP>` and `adb install -r CinejoyTV-1.0-release.apk`.

The app then appears in the TV's Apps row with its banner.

## Changing things

- Website / allowed domains: `HOME_URL` and `ALLOWED_HOSTS` at the top of `app/src/main/java/pk/cinejoy/tv/MainActivity.java` (add a mirror domain there if the site moves).
- Pointer speed: the `6f`, `0.9f` and `32f` values in the `mover` runnable.
- Every build is signed with `app/cinejoy-tv.keystore` (password `cinejoytv`), so new versions install over old ones. Bump `versionCode` in `app/build.gradle` when you update.
