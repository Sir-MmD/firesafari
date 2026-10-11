# FireSafari

An Android app that opens websites the way an iPhone opens home-screen web apps, and tells
every site it is Safari on iPhone. Use it for sites that refuse to work in Android browsers
and push you to install their app.

It is built on GeckoView (the engine inside Firefox for Android), not Chromium. Firefox's
engine has no User-Agent Client Hints and no Chrome-only APIs, so there is less to hide.

## How it works

The iPhone identity is applied at three levels, so it can't leak the way a browser
extension does (extensions only cover tabs, not installed web apps or service workers):

1. **Engine prefs** (`IosProfile.kt`, passed to GeckoView through its config file): the
   user agent, `navigator.platform` and `navigator.appVersion`. These cover every HTTP
   request, every frame, Web Workers and service workers.
2. **A built-in extension** (`assets/ios-profile/spoof.js`) that runs in the page's own
   JavaScript world, in every frame, before any page script. It adds what Safari on iPhone
   has (`navigator.standalone`, `navigator.vendor`, `DeviceMotionEvent.requestPermission`,
   `CSS.supports('-webkit-touch-callout', ...)`, gesture event handlers) and removes what
   only Firefox or Android have (`navigator.oscpu`, `buildID`, `vibrate`, `doNotTrack`,
   `-moz-` CSS support, ...). Replaced functions print as `[native code]`.
3. **Standalone display mode**: each site runs full screen with no browser UI, and
   `@media (display-mode: standalone)` matches, like a home-screen web app on iOS.

Each site gets its own storage partition: cookies and site data are never shared between
sites, and you can clear or delete them per site.

### Verified on an Android 16 emulator

| Where a site looks | What it sees |
| --- | --- |
| HTTP `User-Agent` on page, worker and service worker requests | iPhone Safari UA |
| `Sec-CH-UA*` Client Hints headers | none sent |
| `navigator.userAgent` / `platform` / `appVersion` (page, iframe, Worker, ServiceWorker) | iPhone values |
| `navigator.vendor` / `productSub` / `maxTouchPoints` | `Apple Computer, Inc.` / `20030107` / `5` |
| `navigator.standalone`, `display-mode: standalone` | `true` |
| `navigator.userAgentData`, `oscpu`, `buildID`, `vibrate`, `connection` | absent |
| The first `<script>` in `<head>`, and a freshly created `about:blank` iframe | already spoofed |

### What it can't hide

It is still Firefox's engine underneath. A site doing deep fingerprinting could notice
engine-level differences: rendering and fonts, form control styling, screen size and pixel
ratio, WebGL details, JavaScript engine quirks, and the TLS/HTTP2 connection fingerprint.
Sites that only want to push you to their app don't look that deep.

## Using it

- **Add a site**: tap *Add site*, or share a link to FireSafari from any app.
- **Open it**: tap it in the list. Each site opens in its own task, like a separate app.
- **Home screen icon**: long-press a site, or use the menu inside it, then *Add to home
  screen*. It uses the site's own icon (web app manifest or `apple-touch-icon`).
- **Menu inside a site**: tap the small grey handle on the right edge (reload, start page,
  copy or share the link, add to home screen, close).
- **Back** goes back in the site's history, closes popups, and at the start leaves the app.
- **Update the user agent**: top-right menu, *iPhone user agent*. Copy the current one from
  Safari on a real iPhone when Safari's version moves on. The app restarts to apply it.

Notifications are always refused: this app doesn't deliver web push.

## Building

Requirements: JDK 17 or 21 (Gradle can't run on the newest JDKs yet), Android SDK with
platform `android-37.2` and build tools.

```sh
echo "sdk.dir=$HOME/Android/Sdk" > local.properties
JAVA_HOME=/path/to/jdk-21 ./gradlew assembleRelease
```

APKs land in `app/build/outputs/apk/release/`, one per CPU type. Almost every current phone
needs `app-arm64-v8a-release.apk`. Install it with `adb install`, or copy it to the phone
and open it.

Release builds are signed with this machine's debug key, so later builds install over
earlier ones. Keep `~/.android/debug.keystore`: without it you'd have to uninstall (and
lose site data) to update.

## Keeping it safe

GeckoView ships inside the APK, so it only gets security fixes when you rebuild. Bump
`geckoviewVersion` in `app/build.gradle.kts` every month or so (latest versions:
https://maven.mozilla.org/maven2/org/mozilla/geckoview/geckoview/maven-metadata.xml) and
reinstall.

## Privacy notes

- No telemetry or crash reporting is wired up.
- The engine's connectivity checks to Mozilla's servers are turned off.
- Tracking protection is on, and third-party cookies are isolated per site.
- Other engine defaults (security lists, Safe Browsing) are left as in GeckoView.
- GeckoView depends on Google's FIDO library for passkeys. It talks to Play Services on the
  device only if a site asks for a passkey.
