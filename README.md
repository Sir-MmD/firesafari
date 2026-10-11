# FireSafari

**Firefox for Android that tells every website it's Safari on iPhone.**

Some sites refuse to work in Android browsers and push you to install their app, while the
same site works fine as a web app on an iPhone. FireSafari is the full Firefox browser (tabs,
bookmarks, history, passwords, sync, add-ons, installable web apps) that presents itself as
Safari on iPhone by default, everywhere: in normal tabs, private tabs, custom tabs and the web
apps you install to your home screen.

FireSafari is an unofficial build. It is not made, endorsed or supported by Mozilla or Apple.

## Download

Grab the APK for your phone from [Releases](https://github.com/Sir-MmD/firesafari/releases):

| File | For |
| --- | --- |
| `firesafari-<version>-arm64-v8a.apk` | Almost every current Android phone |
| `firesafari-<version>-armeabi-v7a.apk` | Older 32-bit phones |
| `firesafari-<version>-x86_64.apk` | Emulators and x86 devices |

Needs Android 8.0 or newer. It installs next to Firefox (package `app.firesafari`), so you can
keep both. Check the download against `SHA256SUMS` from the same release.

## What's different from Firefox

| | Firefox | FireSafari |
| --- | --- | --- |
| User agent, `navigator.platform`, `navigator.appVersion` | Firefox on Android | Safari on iPhone, on every request, page, frame, worker and service worker |
| Page-visible details | Firefox | Safari's: `navigator.vendor`, `navigator.standalone` (true only in installed web apps), `DeviceMotionEvent.requestPermission`, `-webkit-touch-callout`, no Firefox-only properties |
| Request desktop site | Firefox on Linux | Safari on Mac, as on an iPhone |
| Web-compat fixes | rewrite the user agent for some sites | kept, but leave the Safari user agent alone |
| `about:config` | Beta and Nightly only | available |
| Telemetry, usage ping, studies | on by default | off by default |
| Sponsored shortcuts | on by default | off by default |

What a site sees, as tested on an emulator:

| Where | Normal tab | Installed web app | Desktop site |
| --- | --- | --- | --- |
| HTTP `User-Agent` | iPhone Safari | iPhone Safari | Mac Safari |
| `Sec-CH-UA` client hints | none | none | none |
| `navigator.platform` | `iPhone` | `iPhone` | `MacIntel` |
| `navigator.vendor` | `Apple Computer, Inc.` | `Apple Computer, Inc.` | `Apple Computer, Inc.` |
| `navigator.standalone` | `false` | `true` | `false` |
| `display-mode: standalone` | no | yes | no |
| Web Worker and service worker | iPhone Safari | iPhone Safari | iPhone Safari |

### Changing the user agent

Open `about:config` and set `general.useragent.override` (and `general.appversion.override` /
`general.platform.override` if you switch device type). Your value wins over FireSafari's
default and survives restarts; reset it to go back.

The default is Safari 26.3's. From iOS 26 on, Safari always reports the OS as `18_6`, and only
the `Version/` part follows the Safari release.

## Building

FireSafari is four patches on top of Firefox for Android, built with Gradle on Mozilla's
prebuilt GeckoView engine from maven.mozilla.org. No Gecko C++ is compiled.

### Requirements

- Linux with git, Python 3.10 to 3.14, make, and about 20 GB of free disk
- About 6 GB of free RAM
- JDK 17 or 21 (`JAVA_HOME`); newer JDKs are too new for Gradle
- Android SDK (`ANDROID_HOME`, default `~/Android/Sdk`) with platform `android-37.1` and
  build-tools. Mozilla's configure looks for `cmdline-tools/21.0`; a symlink to
  `cmdline-tools/latest` works.
- [bundletool 1.18.3](https://github.com/google/bundletool/releases) at `~/.mozbuild/bundletool.jar`

### Build

```sh
./build.sh
```

The first run clones Firefox (about 4 GB), runs Mozilla's `mach configure` (which downloads two
small prebuilt tools into `~/.mozbuild`), and builds. Later runs reuse all of that. The APKs and
a `SHA256SUMS` file land in `dist/`, and the full tool output in `build.log`.

In a terminal it looks like `makepkg`, in color:

```
:: FireSafari: Firefox for Android, presenting as Safari on iPhone
==> Checking build requirements...
  -> JDK 21.0.12.1, Android SDK at /home/you/Android/Sdk
==> Retrieving sources...
  -> Found firefox/
==> Making package: firesafari 157.0.1 (Sun 11 Oct 2026 06:01:59 PM +0330)
==> Applying patches...
  -> (1/4) Build on Mozilla's prebuilt release GeckoView
  -> (2/4) Present as Safari on iPhone
  -> (3/4) Telemetry and sponsored shortcuts off by default
  -> (4/4) Name, package, link scheme and icon
==> Configuring...
  -> Running mach configure
==> Generating sources...
  -> Crash report annotations and the web-compat add-on
==> Building FireSafari 157.0.1 (this takes a while)...
  -> GeckoView 157.0.20261005135250, Gradle heap 5g, 2 workers
==> Copying release APKs to dist/...
  -> firesafari-157.0.1-arm64-v8a.apk (144 MB)
  -> firesafari-157.0.1-armeabi-v7a.apk (141 MB)
  -> firesafari-157.0.1-x86_64.apk (149 MB)
  -> SHA256SUMS
==> Finished making: firesafari 157.0.1 (Sun 11 Oct 2026 06:06:35 PM +0330)
:: Install on a phone: adb install dist/firesafari-157.0.1-arm64-v8a.apk
    Full build output: build.log
```

| Option | |
| --- | --- |
| `-s`, `--shrink` | Shrink the APKs with R8: smaller, but needs much more memory |
| `-v`, `--verbose` | Show the full build output instead of writing it to `build.log` |
| `GRADLE_HEAP`, `GRADLE_WORKERS` | Build memory and parallelism (default `5g`, `2`) |

APKs are signed with the Android debug key of the machine that builds them
(`~/.android/debug.keystore`). Updates must be signed with the same key, so keep it.

### Updating to a newer Firefox

Firefox ships security fixes about every four weeks, and FireSafari only gets them through a
rebuild. Set `FIREFOX_TAG` and `GECKOVIEW_VERSION` at the top of `build.sh` to the new release
(tags: `git ls-remote --tags https://github.com/mozilla-firefox/firefox 'FIREFOX_*_RELEASE'`,
engine builds: [geckoview-omni](https://maven.mozilla.org/maven2/org/mozilla/geckoview/geckoview-omni/)),
delete `firefox/`, and run `./build.sh`. If a patch no longer applies, fix it in the new checkout
and regenerate it with `git format-patch`.

## How it works

| Patch | What it does |
| --- | --- |
| `0001` | Builds on Mozilla's prebuilt release GeckoView instead of a locally compiled one |
| `0002` | The Safari identity: engine prefs for the user agent, platform and appVersion; a built-in extension for page-visible properties; Safari on Mac for desktop sites; web-compat fixes that leave the Safari user agent alone |
| `0003` | Telemetry and sponsored shortcuts off by default |
| `0004` | FireSafari name, package (`app.firesafari`), link scheme and flame icon |

The user agent, platform and appVersion are GeckoView default prefs, passed at startup. Because
tabs, custom tabs and installed web apps all share one engine, they all get them, and so do
workers and service workers. They are defaults, which is why a value set in `about:config` wins.

What prefs can't change is handled by a built-in extension (`ios-profile`) that runs in each
page's own JavaScript world, in every frame, before any page script. It adds Safari's
properties, removes Firefox's, and makes its replacements print as `[native code]`.

## Limits

- It is still Firefox's engine underneath. A site doing deep fingerprinting can notice
  engine-level differences (rendering, fonts, form controls, WebGL, the TLS fingerprint).
  Sites that only want to push you to their app don't look that deep.
- In desktop mode, Web Workers and service workers keep the iPhone user agent: the engine
  gives workers its global default, and changing that per tab needs a Gecko change.
- `navigator.standalone` is true in web apps installed with a web app manifest (Firefox's
  "Add app to Home screen"). Sites that only use Apple's `apple-mobile-web-app-capable` meta
  tag get a plain shortcut that opens as a normal tab.
- Web push notifications don't arrive: Firefox delivers them through Google's Firebase, which
  builds outside Mozilla's release pipeline don't include.

## License and trademarks

The patches modify Firefox source code, which is under the
[Mozilla Public License 2.0](https://www.mozilla.org/MPL/2.0/); the modified files stay under
it. Firefox is a trademark of the Mozilla Foundation, and Safari and iPhone are trademarks of
Apple Inc. FireSafari is not affiliated with either.

An earlier, much smaller version of this project (a standalone web-app launcher, not a full
browser) is kept at tag `webapp-launcher-0.1`.
