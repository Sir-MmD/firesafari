# FireSafari

Firefox for Android, rebuilt so that every website sees **Safari on iPhone** by default:
in normal tabs, private tabs, custom tabs and installed web apps. Everything else is the
full Firefox browser: tabs, address bar, bookmarks, history, passwords, sync, add-ons,
"Add to home screen" / install for web apps, and so on.

Use it for sites that refuse to work in Android browsers and push you to their app.

## What's different from Firefox

| | Firefox | FireSafari |
| --- | --- | --- |
| User agent, `navigator.platform`, `navigator.appVersion` | Firefox on Android | Safari on iPhone, as engine defaults: every request, page, frame, worker and service worker |
| Page-visible details | Firefox | Safari's: `navigator.vendor`, `navigator.standalone` (true only in installed web apps), `DeviceMotionEvent.requestPermission`, `-webkit-touch-callout`, no Firefox-only properties |
| Request desktop site | Firefox on Linux | Safari on Mac, as on an iPhone |
| Web-compat fixes | rewrite the user agent for some sites | kept, but leave the Safari user agent alone |
| `about:config` | Beta and Nightly only | available |
| Telemetry, usage ping, studies | on by default | off by default (Settings > Data collection) |
| Sponsored shortcuts | on by default | off by default |
| Package | `org.mozilla.firefox` | `app.firesafari`, installs next to Firefox |

### Changing the user agent

Open `about:config` and change `general.useragent.override` (plus
`general.appversion.override` and `general.platform.override` if you switch to a different
device). Your value overrides FireSafari's default and survives restarts. Reset it to go back.

The default is Safari 26.3's string. From iOS 26 on, Safari always reports the OS as
`18_6`; only the `Version/` part follows the Safari release.

## How it's built

`patches/` holds the changes against Firefox's source. `build.sh` checks out the Firefox
release, applies them, and builds the Android app with Gradle on Mozilla's prebuilt
GeckoView (the engine) from maven.mozilla.org. No Gecko C++ is compiled.

Where the identity comes from (see `patches/`):

- `FireSafariProfile.kt` (in the GeckoView engine module): the user agents, and the engine
  prefs that Fenix passes to GeckoView at startup through its config file.
- `fenix/app/src/main/assets/extensions/ios-profile/`: a built-in extension that runs in each
  page's own JavaScript world, in every frame, before any page script.
- `GeckoEngineSession.toggleDesktopMode`: the Safari on Mac user agent for desktop sites.

### Requirements

- JDK 17 or 21 (`JAVA_HOME`; newer JDKs are too new for Gradle).
- Android SDK at `ANDROID_HOME` (default `~/Android/Sdk`) with platform `android-37.1`,
  build-tools, and the command-line tools also reachable as `cmdline-tools/21.0` (Mozilla's
  configure looks for that exact folder; a symlink to `cmdline-tools/latest` works).
- `~/.mozbuild/bundletool.jar`: bundletool 1.18.3 from
  https://github.com/google/bundletool/releases
- Python 3.10 to 3.14, git, and about 10 GB of disk.

### Build

```sh
./build.sh
```

The first run clones Firefox (about 4 GB), runs Mozilla's `mach configure` (which downloads
two small prebuilt tools into `~/.mozbuild`), generates the few sources that come from
Mozilla's build system, and builds with Gradle. APKs land in
`firefox/objdir-firesafari/gradle/build/mobile/android/fenix/app/outputs/apk/release/`, one
per CPU type. Almost every current phone needs the `arm64-v8a` one.

The build is tuned to fit in about 6 GB of free RAM: a 5 GB Gradle heap, 2 workers, and no
R8 shrinking (so the APK is larger than Mozilla's). On a bigger machine:
`GRADLE_HEAP=8g GRADLE_WORKERS=8 SHRINK=1 ./build.sh`. Closing memory-hungry apps while it
runs helps.

APKs are signed with this machine's debug key (`~/.android/debug.keystore`). Keep that file:
updates must be signed with the same key, or you'd have to uninstall and lose your data.

### Updating to a newer Firefox

Firefox ships security fixes about every four weeks, and FireSafari only gets them when you
rebuild. Set `FIREFOX_TAG` and `GECKOVIEW_VERSION` in `build.sh` to the new release (tags:
`git ls-remote --tags https://github.com/mozilla-firefox/firefox 'FIREFOX_*_RELEASE'`),
delete `firefox/`, and run `./build.sh`. If a patch no longer applies, fix it in the new
checkout and regenerate it with `git format-patch`.

## Limits

- It is still Firefox's engine underneath. A site doing deep fingerprinting can notice
  engine-level differences (rendering, fonts, form controls, WebGL, the TLS fingerprint).
  Sites that only want to push you to their app don't look that deep.
- In desktop mode, the page and its requests report Safari on Mac, but Web Workers and
  service workers keep the iPhone user agent: the engine gives workers its global default,
  and changing that per tab needs a Gecko change (this build uses Mozilla's prebuilt engine).
- `navigator.standalone` is true in web apps installed with a web app manifest (Firefox's
  "Install"). Sites that only use Apple's `apple-mobile-web-app-capable` meta tag get a plain
  home-screen shortcut, which opens as a normal tab.
- This is a personal build. Don't distribute it as Firefox; Mozilla's trademarks don't
  allow that, which is also why it carries its own name and icon.

The previous standalone web-app launcher lives in git history at tag `webapp-launcher-0.1`.
