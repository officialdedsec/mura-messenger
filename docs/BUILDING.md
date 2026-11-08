# Building Mura from source

Building it yourself is the only way to know exactly what you are running. It is also the
recommended way to install Mura until a signed release exists.

## Requirements

| | |
|---|---|
| JDK | 17 |
| Android SDK | API 36 (compile/target), min API 28 |
| Disk | ~4 GB free |
| Android Studio | optional — the Gradle wrapper is included |

## Quick build

```bash
git clone https://github.com/officialdedsec/mura-messenger.git
cd mura
./gradlew assembleDebug
# → app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Run the test suite

```bash
./gradlew test
```

This covers the crypto known-answer vectors, the Double Ratchet, group sender keys, backup
encryption and decryption, and the store-and-forward router. Please run it before opening a
pull request.

## Release build

Release builds are shrunk by R8, and the ProGuard rules cover Tor, IPtProxy, Tink, ZXing and
WorkManager by hand. **Always test a release build on a real device** — a missing keep rule
only shows up there.

Create a signing key:

```bash
keytool -genkeypair -v -keystore mura-upload.jks -alias mura \
        -keyalg RSA -keysize 4096 -validity 10000
```

Create `keystore.properties` beside `settings.gradle.kts` (already gitignored):

```properties
storeFile=/absolute/path/mura-upload.jks
storePassword=...
keyAlias=mura
keyPassword=...
```

Then:

```bash
./gradlew bundleRelease     # → app/build/outputs/bundle/release/app-release.aab
./gradlew assembleRelease   # → APK, if you want to distribute directly
```

If `keystore.properties` is missing the build falls back to the debug key, so **verify the
signer** before you publish anything:

```bash
apksigner verify --print-certs app/build/outputs/apk/release/app-release.apk
```

## Verify 16 KB page support

Required for Android 15+ with targetSdk 36. Every 64-bit native library must be aligned to
16 KB pages:

```bash
mkdir -p /tmp/aabcheck && cd /tmp/aabcheck
unzip -oq /path/to/app-release.aab
for so in $(find . \( -path '*arm64-v8a*' -o -path '*x86_64*' \) -name '*.so'); do
  bad=$(readelf -lW "$so" | awk '$1=="LOAD" && strtonum($NF) < 16384 {print $NF}')
  [ -n "$bad" ] && echo "NOT 16KB: $so ($bad)" || echo "ok: $so"
done
```

32-bit libraries are exempt — the requirement applies to 64-bit ABIs only.

## Reproducible builds

Not yet achieved. This is a priority, because reproducibility is what lets a third party
confirm that a published APK really was built from this source. Known obstacles: build
timestamps in the AAB, and R8 output varying across versions. Help here is very welcome.

Until then, prefer building yourself over trusting any binary, including ours.

## Project layout

```
app/src/main/java/com/mura/mesh/
├── core/        crypto, protocol frames, bundles, MeshRouter, compression
├── mesh/        MeshEngine, BleTransport, OnlineTransport, permissions
├── data/        encrypted database and stores
├── identity/    key generation and management
├── backup/      encrypted backup and restore
├── service/     MeshService, KeepAlive, Notifier
└── ui/          Jetpack Compose screens, theme, icons
```

Start reading at `MeshEngine` — it is the brain — then `MeshRouter` for how messages travel.
