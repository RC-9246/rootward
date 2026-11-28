#!/usr/bin/env bash
# Requirements: JDK 17, Android SDK platform 37.2 and build-tools 37.0.0, zip, openssl.
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
        if [[ -r "$candidate/platforms/android-37.2/android.jar" ]]; then sdk_dir="$candidate"; break; fi
    done
fi
android_jar="$sdk_dir/platforms/android-37.2/android.jar"
tools_dir="$sdk_dir/build-tools/${ANDROID_BUILD_TOOLS_VERSION:-37.0.0}"
if [[ ! -r "$android_jar" || ! -x "$tools_dir/d8" ]]; then
    echo "Set ANDROID_HOME to an SDK containing platform 37.2 and build-tools 37.0.0." >&2
    exit 1
fi
if [[ ! -r "$source_dir/java/io/local/rootward/shizuku/MainActivity.java" ]]; then
    echo "The launcher source MainActivity.java is missing." >&2
    exit 1
fi

mkdir -p "$build_dir"
stage="$(mktemp -d "$build_dir/.compile.XXXXXX")"
source_root="$project_dir"
if [[ -f "$project_dir/../app/src/main/java/io/local/accpanel/AccBackend.java" ]]; then
    source_root="$(cd "$project_dir/.." && pwd)"
fi
signing_dir="${ACC_SIGNING_DIR:-$source_root/../signing}"
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

dependencies="$build_dir/deps"
mkdir -p "$dependencies"
jars=()
while read -r name digest; do
    package="$dependencies/$name.aar"
    url="https://repo.maven.apache.org/maven2/dev/rikka/shizuku/$name/13.1.5/$name-13.1.5.aar"
    if [[ ! -f "$package" ]]; then
        curl -fsSL --retry 3 "$url" -o "$stage/$name.aar"
        mv "$stage/$name.aar" "$package"
    fi
    actual=$(openssl dgst -sha256 "$package" | awk '{print $NF}')
    [[ "$actual" == "$digest" ]] || { echo "Digest mismatch: $name" >&2; exit 1; }
    unzip -p "$package" classes.jar > "$stage/$name.jar"
    jars+=("$stage/$name.jar")
done < "$project_dir/shizuku-dependencies.txt"
annotation="$dependencies/annotation.jar"
if [[ ! -f "$annotation" ]]; then
    curl -fsSL --retry 3 https://dl.google.com/dl/android/maven2/androidx/annotation/annotation/1.3.0/annotation-1.3.0.jar -o "$stage/annotation.jar"
    mv "$stage/annotation.jar" "$annotation"
fi
actual=$(openssl dgst -sha256 "$annotation" | awk '{print $NF}')
[[ "$actual" == 97dc45afefe3a1e421da42b8b6e9f90491477c45fc6178203e3a5e8a05ee8553 ]] || { echo "Digest mismatch: AndroidX annotations" >&2; exit 1; }
jars+=("$annotation")
# D8 rewrites newer Java APIs; L8 builds the matching java.time and NIO implementations for Nougat.
desugar_version=2.1.5
while read -r name digest; do
    package="$dependencies/$name-$desugar_version.jar"
    if [[ ! -f "$package" ]]; then
        url="https://dl.google.com/dl/android/maven2/com/android/tools/$name/$desugar_version/$name-$desugar_version.jar"
        curl -fsSL --retry 3 "$url" -o "$stage/$name.jar"
        actual=$(openssl dgst -sha256 "$stage/$name.jar" | awk '{print $NF}')
        [[ "$actual" == "$digest" ]] || { echo "Digest mismatch: $name" >&2; exit 1; }
        mv "$stage/$name.jar" "$package"
    fi
    actual=$(openssl dgst -sha256 "$package" | awk '{print $NF}')
    [[ "$actual" == "$digest" ]] || { echo "Digest mismatch: $name" >&2; exit 1; }
done < "$project_dir/desugar-dependencies.txt"
desugar_lib="$dependencies/desugar_jdk_libs_nio-$desugar_version.jar"
desugar_adapter="$dependencies/desugar_jdk_libs_configuration_nio-$desugar_version.jar"
unzip -p "$desugar_adapter" META-INF/desugar/d8/desugar.json > "$stage/desugar.json"

"$tools_dir/aidl" "-p$sdk_dir/platforms/android-37.2/framework.aidl" "-I$source_dir/aidl" "-o$stage/gen" "$source_dir/aidl/io/local/rootward/shizuku/IBatteryReader.aidl"
classpath=$(IFS=:; echo "${jars[*]}")
sources=()
while IFS= read -r -d '' source_file; do sources+=("$source_file"); done \
    < <(find "$source_dir/java" "$stage/gen" -name '*.java' -print0)
javac -encoding UTF-8 --release 8 -g:source,lines -cp "$android_jar:$classpath" -d "$stage/classes" "${sources[@]}"
jar cf "$stage/classes.jar" -C "$stage/classes" .
"$tools_dir/d8" --min-api 24 --release --lib "$android_jar" --desugared-lib "$stage/desugar.json" \
    --output "$stage/dex" "$stage/classes.jar" "${jars[@]}"
mkdir "$stage/runtime-dex"
java -cp "$tools_dir/lib/d8.jar" com.android.tools.r8.L8 --min-api 24 --release \
    --lib "$android_jar" --desugared-lib "$stage/desugar.json" --output "$stage/runtime-dex" "$desugar_lib" "$desugar_adapter"
# Append each runtime dex without replacing an application dex.
app_dex=("$stage/dex"/classes*.dex)
index=${#app_dex[@]}
for runtime in "$stage/runtime-dex"/classes*.dex; do
    index=$((index + 1))
    mv "$runtime" "$stage/dex/classes$index.dex"
done
mkdir -p "$stage/dex/assets/desugar-licenses"
cp "$project_dir/desugar-licenses/"*.txt "$stage/dex/assets/desugar-licenses/"
(
    cd "$stage/dex"
    touch -t 200901010000.00 classes*.dex assets/desugar-licenses/*.txt
    zip -Xq "$stage/resources.apk" classes*.dex assets/desugar-licenses/*.txt
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
mv -f "$stage/signed.apk" "$build_dir/Rootward-Shizuku-1.4.apk"
echo "$build_dir/Rootward-Shizuku-1.4.apk"
