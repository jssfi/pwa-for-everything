<img src="assets/icon.svg" width="64" height="64" alt="">

# PWA for Everything

An Android app that turns any website into a fullscreen app.

Some sites can't be installed as a real PWA. Others show Chrome's URL bar as soon as they redirect to another domain, for example a login page. This app opens each site in its own fullscreen WebView instead, with no browser UI.

## Features

- Each site gets its own entry in the app switcher. The status bar and navigation bar can be hidden per site.
- Logins and redirects to other domains stay inside the app. OAuth popups, such as "Sign in with Google", open in an overlay.
- File uploads and downloads both work, including `blob:` downloads such as Excalidraw's "Save to file".
- Camera, microphone and location permissions are passed through to the site.
- You can pin a home screen shortcut for any site. Its icon is fetched from the site automatically.
- To add a site, use **Share → Add as app** in your browser, or tap **+** in the app.
- On a site's first page, press Back to open a menu with Reload, Start page, Settings and Exit.

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
