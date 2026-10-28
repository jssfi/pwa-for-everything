<img src="assets/icon.svg" width="64" height="64" alt="">

# PWA for Everything

An Android app that turns any website into a fullscreen app.

Some sites can't be installed as a real PWA. Others show Chrome's URL bar as soon as they redirect to another domain, for example a login page. This app opens each site in its own fullscreen WebView instead, with no browser UI.

## Two ways to open a site

Long-press a site in the app:

- **Add shortcut to home screen** works on every launcher and needs no install. Android badges these shortcuts with the PWA for Everything icon, and the taskbar shows PWA for Everything.
- **Install as app** builds a separate small app for that site on the device and installs it. It gets its own icon with no badge, its own splash screen, recents entry and taskbar icon, and its own cookies and storage. Android asks you to confirm each install, and you need to allow "Install unknown apps" once. The same menu offers **Update** and **Uninstall** afterwards.

The per-site apps are generated from a template APK (the `site` module) embedded in the main app. On the device, the main app patches the template's package name, label, icon and site settings, then signs it with a key kept in the Android Keystore. Updates must use the same key, so uninstalling PWA for Everything means installed site apps can no longer be updated, only reinstalled.

## Features

- Each site gets its own entry in the app switcher. The status bar and navigation bar can be hidden per site.
- Logins and redirects to other domains stay inside the app. OAuth popups, such as "Sign in with Google", open in an overlay.
- File uploads and downloads both work, including `blob:` downloads such as Excalidraw's "Save to file".
- Camera, microphone and location permissions are passed through to the site.
- Site icons are fetched from the site automatically.
- To add a site, use **Share → Add as app** in your browser, or tap **+** in the app.
- On a site's first page, press Back to open a menu with Reload, Start page, Settings and Exit.

## Project layout

- `core`: the fullscreen web app shell, shared by both apps.
- `app`: the main app, which holds the site list, shortcuts and per-site app generator.
- `site`: the per-site app template.

## Build

You need JDK 17+ and the Android SDK (API 36).

```sh
./gradlew :app:assembleDebug
```

For a signed release build, create a `keystore.properties` file in the repo root:

```properties
storeFile=/path/to/key.jks
storePassword=...
keyAlias=...
keyPassword=...
```

Then build it:

```sh
./gradlew :app:assembleRelease
```

Requires Android 10 or newer.
