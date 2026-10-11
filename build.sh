#!/usr/bin/env bash
#
# build.sh: build FireSafari, Firefox for Android presenting itself as Safari on iPhone.
#
# Fetches the Firefox release, applies patches/, and builds the Android app with Gradle on
# Mozilla's prebuilt GeckoView (no Gecko C++ is compiled). Release APKs land in dist/.
#
set -euo pipefail

# Firefox release to build. To move to a newer one, change both (they must be the same
# release), delete firefox/, and run again.
FIREFOX_TAG=${FIREFOX_TAG:-FIREFOX_157_0_1_RELEASE}
GECKOVIEW_VERSION=${GECKOVIEW_VERSION:-157.0.20261005135250} # maven.mozilla.org: geckoview-omni

export JAVA_HOME=${JAVA_HOME:-$HOME/.jdks/jdk-21.0.12.1+1}
export ANDROID_HOME=${ANDROID_HOME:-$HOME/Android/Sdk}
GRADLE_HEAP=${GRADLE_HEAP:-5g} # Fenix's own default is 7g
GRADLE_WORKERS=${GRADLE_WORKERS:-2}

ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
SRC=$ROOT/firefox
OBJDIR=$SRC/objdir-firesafari
DIST=$ROOT/dist
LOG=$ROOT/build.log
export MOZCONFIG=$ROOT/mozconfig
export PATH=$JAVA_HOME/bin:$PATH

SHRINK=0
VERBOSE=0

# --- Output, in the style of pacman and makepkg ------------------------------------------------

if [[ -t 1 && -z ${NO_COLOR:-} ]]; then
    BOLD=$'\e[1m' RESET=$'\e[0m'
    RED=$'\e[1;31m' GREEN=$'\e[1;32m' YELLOW=$'\e[1;33m' BLUE=$'\e[1;34m'
else
    BOLD='' RESET='' RED='' GREEN='' YELLOW='' BLUE=''
fi

msg()     { printf '%s==>%s%s %s%s\n' "$GREEN" "$RESET" "$BOLD" "$*" "$RESET"; }
msg2()    { printf '%s  ->%s%s %s%s\n' "$BLUE" "$RESET" "$BOLD" "$*" "$RESET"; }
plain()   { printf '    %s\n' "$*"; }
section() { printf '%s::%s%s %s%s\n' "$BLUE" "$RESET" "$BOLD" "$*" "$RESET"; }
warning() { printf '%s==> WARNING:%s%s %s%s\n' "$YELLOW" "$RESET" "$BOLD" "$*" "$RESET" >&2; }
error()   { printf '%s==> ERROR:%s%s %s%s\n' "$RED" "$RESET" "$BOLD" "$*" "$RESET" >&2; }
die()     { error "$@"; exit 1; }

# Runs a command with its output in build.log (or on screen with --verbose).
run() {
    if ((VERBOSE)); then
        "$@"
    elif ! "$@" >>"$LOG" 2>&1; then
        error "$1 failed. Last lines of build.log:"
        tail -n 30 "$LOG" | sed 's/^/    /' >&2
        exit 1
    fi
}

usage() {
    cat <<EOF
Usage: ./build.sh [options]

Builds FireSafari and puts the release APKs in dist/.

Options:
  -s, --shrink    Shrink the APKs with R8 (smaller, needs much more memory)
  -v, --verbose   Show the full build output instead of writing it to build.log
  -h, --help      Show this help

Environment:
  FIREFOX_TAG, GECKOVIEW_VERSION   Firefox release to build (must be the same release)
  JAVA_HOME, ANDROID_HOME          JDK 17 or 21, and the Android SDK
  GRADLE_HEAP, GRADLE_WORKERS      Build memory and parallelism (default: 5g, 2)
  NO_COLOR                         Plain output without colors
EOF
}

# --- Build steps ------------------------------------------------------------------------------

check_requirements() {
    msg "Checking build requirements..."
    local tool
    for tool in git python3 make sha256sum; do
        command -v "$tool" >/dev/null || die "Missing $tool"
    done
    [[ -x $JAVA_HOME/bin/java ]] || die "No JDK at JAVA_HOME=$JAVA_HOME (use JDK 17 or 21)"
    [[ -x $ANDROID_HOME/cmdline-tools/21.0/bin/sdkmanager ]] ||
        die "Mozilla's configure needs $ANDROID_HOME/cmdline-tools/21.0 (a symlink to cmdline-tools/latest works)"
    [[ -d $ANDROID_HOME/platforms/android-37.1 ]] || die "Missing Android SDK platform android-37.1"
    [[ -f $HOME/.mozbuild/bundletool.jar ]] ||
        die "Missing ~/.mozbuild/bundletool.jar (bundletool 1.18.3, see README)"
    msg2 "JDK $("$JAVA_HOME/bin/java" -version 2>&1 | awk -F'"' 'NR==1 {print $2}'), Android SDK at $ANDROID_HOME"
}

fetch_source() {
    msg "Retrieving sources..."
    if [[ -d $SRC/.git ]]; then
        msg2 "Found firefox/"
    else
        msg2 "Cloning $FIREFOX_TAG (about 4 GB)..."
        run git clone --depth 1 --branch "$FIREFOX_TAG" --single-branch \
            https://github.com/mozilla-firefox/firefox.git "$SRC"
    fi
    VERSION=$(<"$SRC/browser/config/version_display.txt")
}

apply_patches() {
    msg "Applying patches..."
    local patches=("$ROOT"/patches/*.patch)
    local total=${#patches[@]} i=0 patch title
    for patch in "${patches[@]}"; do
        i=$((i + 1))
        # The patch's subject line, which git format-patch may have wrapped onto two lines.
        title=$(awk '/^Subject:/ { s = $0; while ((getline l) > 0 && l ~ /^ /) s = s l; print s; exit }' "$patch" |
            sed 's/^Subject: \[PATCH[^]]*\] FireSafari: //')
        title=${title^}
        if git -C "$SRC" apply --reverse --check "$patch" 2>/dev/null; then
            msg2 "($i/$total) $title (already applied)"
        else
            git -C "$SRC" apply --whitespace=nowarn "$patch" ||
                die "$(basename "$patch") does not apply to $FIREFOX_TAG"
            msg2 "($i/$total) $title"
        fi
    done
}

configure() {
    msg "Configuring..."
    if [[ -f $OBJDIR/config.status.json ]]; then
        msg2 "Already configured"
        return
    fi
    msg2 "Running mach configure"
    (cd "$SRC" && run ./mach --no-interactive configure && run ./mach build-backend)
}

# A few sources come from Mozilla's build system rather than Gradle: the crash report
# annotations and the web-compat add-on. Build just those (a full `mach build` would fetch
# prebuilt Gecko binaries this build doesn't use).
generate_sources() {
    msg "Generating sources..."
    local targets
    targets=$(grep -oE '^\$\(TOPOBJDIR\)/mobile/android/(webcompat_addon_generated_files|android-components/components/lib/crash)/[^ :]*\.stub:' \
        "$OBJDIR/faster/Makefile" |
        sed -e 's/:$//' -e "s|\$(TOPOBJDIR)|$OBJDIR|" -e 's|\$(MDDEPDIR)|.deps|')
    [[ -n $targets ]] || die "No generated-source targets in $OBJDIR/faster/Makefile"
    msg2 "Crash report annotations and the web-compat add-on"
    run xargs make -s -C "$OBJDIR/faster" <<<"$targets"
}

build_apks() {
    msg "Building FireSafari $VERSION (this takes a while)..."
    local args=(
        --max-workers="$GRADLE_WORKERS" --no-parallel
        "-Dorg.gradle.jvmargs=-Xmx$GRADLE_HEAP -Xms1g -XX:MaxMetaspaceSize=1536m -XX:+UseParallelGC"
        -Pkotlin.compiler.execution.strategy=in-process
        -PgeckoviewVersion="$GECKOVIEW_VERSION"
        -PversionName="$VERSION"
    )
    ((SHRINK)) || args+=(-PdisableOptimization)
    msg2 "GeckoView $GECKOVIEW_VERSION, Gradle heap $GRADLE_HEAP, $GRADLE_WORKERS workers$( ((SHRINK)) && echo ", R8 shrinking")"

    # The build date becomes the version code, so every build installs over the previous one.
    export MOZ_BUILD_DATE
    MOZ_BUILD_DATE=$(date -u +%Y%m%d%H%M%S)
    (
        cd "$SRC/mobile/android/fenix"
        run ./gradlew "${args[@]}" :app:assembleRelease
        # Don't leave a multi-gigabyte Gradle daemon running after the build.
        run ./gradlew --stop
    )
}

collect_apks() {
    msg "Copying release APKs to dist/..."
    local out=$OBJDIR/gradle/build/mobile/android/fenix/app/outputs/apk/release apk abi name
    mkdir -p "$DIST"
    rm -f "$DIST"/firesafari-*.apk "$DIST"/SHA256SUMS
    for apk in "$out"/app-*-release.apk; do
        abi=${apk##*/app-}
        abi=${abi%-release.apk}
        name=firesafari-$VERSION-$abi.apk
        cp "$apk" "$DIST/$name"
        msg2 "$name ($(du -m "$apk" | cut -f1) MB)"
    done
    (cd "$DIST" && sha256sum firesafari-*.apk >SHA256SUMS)
    msg2 "SHA256SUMS"
}

# --- Main -------------------------------------------------------------------------------------

while (($#)); do
    case $1 in
        -s | --shrink) SHRINK=1 ;;
        -v | --verbose) VERBOSE=1 ;;
        -h | --help) usage; exit 0 ;;
        *) usage >&2; die "Unknown option: $1" ;;
    esac
    shift
done

: >"$LOG"
section "FireSafari: Firefox for Android, presenting as Safari on iPhone"
check_requirements
fetch_source
msg "Making package: firesafari $VERSION ($(date +%c))"
apply_patches
configure
generate_sources
build_apks
collect_apks
msg "Finished making: firesafari $VERSION ($(date +%c))"
section "Install on a phone: adb install dist/firesafari-$VERSION-arm64-v8a.apk"
((VERBOSE)) || plain "Full build output: build.log"
