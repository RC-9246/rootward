#!/usr/bin/env bash
# Requirements: JDK 17, Android SDK platform 34 and build-tools 34.0.0, zip, openssl.
# Reuse ACC_SIGNING_DIR when rebuilding an update for an already-installed APK.
set -euo pipefail
export LC_ALL=C
export TZ=UTC
project_dir="$(cd "$(dirname "$0")" && pwd)"
source_dir="$project_dir/app/src/main"
build_dir="$project_dir/build"
sdk_dir="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
if [[ -z "$sdk_dir" ]]; then
    for candidate in /opt/homebrew/share/android-commandlinetools "$HOME/Library/Android/sdk" "$HOME/Android/Sdk"; do
        if [[ -d "$candidate" ]]; then sdk_dir="$candidate"; break; fi
    done
fi
android_jar="$sdk_dir/platforms/android-34/android.jar"
tools_dir="$sdk_dir/build-tools/${ANDROID_BUILD_TOOLS_VERSION:-34.0.0}"
if [[ ! -r "$android_jar" || ! -x "$tools_dir/d8" ]]; then
    echo "Set ANDROID_HOME to an SDK containing platform 34 and build-tools 34.0.0." >&2
    exit 1
fi
if [[ ! -r "$source_dir/java/io/local/accpanel/MainActivity.java" ]]; then
    echo "The launcher source MainActivity.java is missing." >&2
    exit 1
fi

mkdir -p "$build_dir"
stage="$(mktemp -d "$build_dir/.compile.XXXXXX")"
signing_dir="${ACC_SIGNING_DIR:-$project_dir/../signing}"
init_lock=""
cleanup() {
    rm -rf "$stage"
    if [[ -n "$init_lock" ]]; then rmdir "$init_lock"; fi
}
trap cleanup EXIT
mkdir -p "$stage/gen" "$stage/classes" "$stage/dex"
"$tools_dir/aapt2" compile --dir "$source_dir/res" -o "$stage/resources.zip"
"$tools_dir/aapt2" link --manifest "$source_dir/AndroidManifest.xml" \
    -I "$android_jar" --java "$stage/gen" -o "$stage/resources.apk" "$stage/resources.zip"

sources=()
while IFS= read -r -d '' source_file; do sources+=("$source_file"); done \
    < <(find "$source_dir/java" "$stage/gen" -name '*.java' -print0)
javac -encoding UTF-8 --release 8 -g:source,lines -cp "$android_jar" -d "$stage/classes" "${sources[@]}"
jar cf "$stage/classes.jar" -C "$stage/classes" .
"$tools_dir/d8" --min-api 26 --release --lib "$android_jar" --output "$stage/dex" "$stage/classes.jar"
(
    cd "$stage/dex"
    touch -t 200901010000.00 classes*.dex
    zip -Xq "$stage/resources.apk" classes*.dex
)
"$tools_dir/zipalign" -f 4 "$stage/resources.apk" "$stage/aligned.apk"

# The signing password and key stay outside the project, including on source-only exports.
key_path="$signing_dir/accpanel.p12"
pass_path="$signing_dir/passphrase"
if [[ ! -f "$key_path" || ! -f "$pass_path" ]]; then
    mkdir -p -m 700 "$signing_dir"
    if ! mkdir "$signing_dir/.initializing" 2>/dev/null; then
        echo "Another build is initializing the signing directory. Retry after it finishes." >&2
        exit 1
    fi
    init_lock="$signing_dir/.initializing"
    if [[ -e "$key_path" || -e "$pass_path" ]]; then
        echo "The signing directory is incomplete or was just initialized. Check its files before retrying." >&2
        exit 1
    fi
    (
        umask 077
        openssl rand -hex 32 > "$stage/new-password"
        keytool -genkeypair -alias accpanel -keyalg RSA -keysize 3072 -validity 10000 \
            -dname "CN=ACCPanel Local" -storetype PKCS12 -keystore "$stage/new-key.p12" \
            -storepass:file "$stage/new-password" -keypass:file "$stage/new-password"
        mv "$stage/new-key.p12" "$key_path"
        mv "$stage/new-password" "$pass_path"
    )
    rmdir "$init_lock"
    init_lock=""
fi
"$tools_dir/apksigner" sign --ks "$key_path" --ks-key-alias accpanel \
    --ks-pass "file:$pass_path" --v4-signing-enabled false \
    --out "$stage/signed.apk" "$stage/aligned.apk"
"$tools_dir/zipalign" -c 4 "$stage/signed.apk"
"$tools_dir/apksigner" verify --verbose "$stage/signed.apk"
mv -f "$stage/signed.apk" "$build_dir/Rootward.apk"
echo "$build_dir/Rootward.apk"
