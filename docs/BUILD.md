# Build DiPlay

Use this procedure to build the DiPlay app for an Android head unit.
Run the commands from the repository root, where `gradlew` and `settings.gradle.kts` are located.

## Select the application

This repository contains several Android applications.
Select `mobile` to build the main DiPlay app.

| Gradle module | Purpose | Debug APK |
| --- | --- | --- |
| `:mobile` | Main DiPlay app for Android head units | `mobile/build/outputs/apk/debug/mobile-debug.apk` |
| `:maphost` | Sample application that displays a map from DiPlay | `samples/maphost/build/outputs/apk/debug/maphost-debug.apk` |
| `:home` | Optional DiPlay Home launcher | `samples/home/build/outputs/apk/debug/home-debug.apk` |
| `:automotive` | Separate Android Automotive application inherited from xcertplay | `automotive/build/outputs/apk/debug/automotive-debug.apk` |

The `:common` and `:shared` modules are libraries.
The applications use these libraries.

If the installed app is **DiPlay map host**, you selected `:maphost` or its APK.
Build `:mobile` and select `mobile-debug.apk` to install the main app.
A command such as `./gradlew assembleDebug` can build more than one application.
Use the module name in each build command to select the required application.

## Prepare the build environment

Install these tools:

- JDK 25.
- Android SDK Platform 37 (`platforms;android-37.0`).
- Android SDK Build-Tools 36.0.0.
- Android SDK Platform-Tools.
- Android NDK 28.2.13676358.

Use the Gradle wrapper included in this repository.
The first build needs an internet connection to download dependencies.

1. Open the repository root in Android Studio.
2. Install the Android SDK packages with **Tools → SDK Manager**.
3. Set **Gradle JDK** to JDK 25 in the Android Studio Gradle settings.
4. Wait for Gradle sync to finish.

For a terminal build, set `JAVA_HOME` to your JDK 25 directory.
Set `ANDROID_HOME` to your Android SDK directory.
You can also specify the SDK directory in the root `local.properties` file:

```properties
sdk.dir=/absolute/path/to/Android/sdk
```

Keep `local.properties` outside Git.
Check the Java version before the build:

```sh
java -version
```

The output must identify Java 25.
On Windows, use `gradlew.bat` in place of `./gradlew` in the commands below.

## Build the main app

For a source build without runtime authentication assets:

```sh
./gradlew :mobile:assembleDebug
```

Check that this file exists:

```text
mobile/build/outputs/apk/debug/mobile-debug.apk
```

The debug application ID is `cn.manstep.phonemirrorBox.hudtest`.
The release application ID is `cn.manstep.phonemirrorBox`.

The source APK contains no accessory identity unless you supply runtime authentication assets.
Standalone CarPlay needs these assets to connect to an iPhone.
Use the car-test procedure below for that purpose.
Tests generate synthetic identities at runtime.
Keep test private keys outside Git.

### Use Android Studio

1. Open **Run → Edit Configurations**.
2. Select an **Android App** configuration, or create one.
3. Set **Module** to `mobile`.
4. Select this configuration in the toolbar.
5. Select the Android device.
6. Click **Run** to install the main debug app.

To build an APK without installation, run `:mobile:assembleDebug` in the Android Studio terminal.
Select the APK from the `mobile` output directory.

## Check the source build

Run the unit tests, lint checks, and main debug build:

```sh
./gradlew :shared:testDebugUnitTest :common:testDebugUnitTest :mobile:lintDebug :mobile:assembleDebug
```

Check that Gradle reports `BUILD SUCCESSFUL`.
For the complete CI procedure, see [the Android workflow](../.github/workflows/android.yml).
That workflow also checks and builds the `home` and `maphost` applications.

## Build a standalone car-test APK

Prepare an external directory with these runtime authentication files:

```text
runtime-assets/
└── offline-mfi/
    ├── identity.pk8
    └── certificate.p7b
```

Use only the intended runtime files in this directory.
Keep the directory and its private key outside Git.
The build rejects unexpected credential containers in APK assets.

1. Set `DIPLAY_AUTH_ASSETS_DIR` to the absolute directory path.
2. Run the standalone debug task.

```sh
DIPLAY_AUTH_ASSETS_DIR=/absolute/path/to/runtime-assets ./gradlew :mobile:assembleStandaloneDebug
```

The task stops if either required file is absent or empty.
Its APK output is `mobile/build/outputs/apk/debug/mobile-debug.apk`.
Check both files in the APK against your selected local inputs:

- `assets/offline-mfi/identity.pk8`.
- `assets/offline-mfi/certificate.p7b`.

Use this APK for a standalone connection test.
Update the existing test app with the same signing key to keep its settings.
Different signing keys cannot update the same installed application.

## Build a release APK

Prepare the runtime assets as described in the car-test procedure.
Set these environment variables locally:

| Variable | Value |
| --- | --- |
| `DIPLAY_AUTH_ASSETS_DIR` | Absolute path to the runtime assets directory |
| `ANDROID_KEYSTORE_PATH` | Path to your Android signing keystore |
| `ANDROID_KEYSTORE_PASSWORD` | Keystore password |
| `ANDROID_KEY_ALIAS` | Signing key alias |
| `ANDROID_KEY_PASSWORD` | Signing key password |

Keep the keystore and passwords outside Git.
The personal release switched Android signing keys at `precision.10`.
Users of `precision.9` and earlier must uninstall the old app before installing that release;
the uninstall clears app settings. Keep the new key for subsequent in-place updates.
See [release signing](RELEASE-SIGNING.md) for the public certificate fingerprints.
Run the release checks and build:

```sh
./gradlew :shared:testDebugUnitTest :common:testDebugUnitTest :mobile:lintRelease :mobile:assembleRelease
```

The output is `mobile/build/outputs/apk/release/mobile-release.apk`.
The APK contains the runtime identity you supplied.
Recipients can extract that identity from the APK.
The APK excludes the Android signing key.
See [the project notices](THIRD_PARTY_NOTICES.md) for the experimental identity used in public releases.

The public source archive matches the release tag.
It excludes runtime identities, signing keys, local configuration, and build output.
The retired `build-beta.py` helper is no longer part of the build procedure.

## Publish an over-the-air update

The app first checks `https://weclaw-agent.cloud/diplay-updates/releases.json`,
then falls back to this fork's GitHub API if the mirror is unavailable or invalid.
The mirror catalog uses the GitHub release format, but both APK and checksum URLs
point to the same HTTPS mirror. Head units do not need GitHub access when the mirror is healthy.
The existing checksum verification and Android package signature checks still apply.

`scripts/sync_diplay_updates.py` synchronizes up to 30 published releases. It verifies
each APK before publishing a new catalog, reuses verified local files, and leaves
the previous catalog available when GitHub is unavailable. Files removed from the
catalog remain for seven days for cached history lists and active downloads.
The service, timer and Nginx snippet are in `deploy/diplay-updates/`. The service
runs as `diplay-updates` and the timer refreshes every 15 minutes. To refresh manually:

```sh
sudo systemctl start diplay-updates.service
journalctl -u diplay-updates.service --no-pager -n 20
```

The mirror needs Python 3.11 or newer. For initial deployment, stage the sync script,
the `deploy/` files and the verified `public/` output in one directory, then run
`deploy/diplay-updates/install.sh STAGING NGINX_HTTPS_SITE` as root. The installer
backs up the existing HTTPS site, tests Nginx before reloading it, and enables the
sync timer. Slow upstream downloads run in the background while the previous
verified catalog remains available.

Mirror script regression tests: `python3 -m unittest discover -s scripts -p test_sync_diplay_updates.py`.
Existing app versions that contain only the GitHub URL need a one-time upgrade to
a build with the mirror URL. Keep the same release package and signing identity
when producing that build.

The Lincoln build checks its own fork for releases (see `UpdateClient.RELEASES_URL`). Run the
release script to build, verify, commit, push and publish in one step:

```sh
scripts/release-lincoln.sh --bump            # 0.2.20 -> 0.2.21, then publish
scripts/release-lincoln.sh --bump --dry-run  # build and verify only; nothing is published
```

Two properties of the in-app updater shape the release:

- `UpdateCatalog` ignores a release unless it carries **both** an `.apk` asset and a
  `SHA256SUMS.txt`. The checksum file lists `sha256  filename`, and the name must match the
  APK asset exactly or the install aborts on a checksum mismatch.
- `UpdateVersion.isNewer` compares the **tag** against the installed `versionName` by parsed
  `x.y.z` numbers. CorsairPlay product releases start at `1.0.0` / `v1.0.0`; increment
  `versionCode` for every published APK. The retired `precision.N` line remains readable,
  but must not be offered as newer than an installed 1.x product release.

Old precision clients cannot automatically compare the new product tag with their installed
version. For the first move to 1.0.0, download the APK from GitHub (or the app's release-history
picker) and install it over the existing app. Once upgraded, normal product-version checks apply.

An in-place install keeps settings when the package name and signing certificate match.
The first move from the old personal signing key to `precision.10` requires a manual
uninstall and reinstall, rather than the in-app update flow.
The driver updates from Settings -> About -> Check for updates. Daily background checks
cache release information; their offer is shown only while that update page is open.

## Home and projection lifecycle

`DiPlayActivity` is the single visible home, settings and projection Activity. Connection startup is lazy: opening home must not initialize the media runtime or request its permissions. Connecting, retrying and first-frame updates change the existing home widgets in place. Keep the video layer attached when replacing home/settings chrome, and preserve visible projection during window relayout.

Use `CarPlayEntry.intent(context)` for current projection entry points. The old `CarPlayHostActivity` component is an invisible compatibility forwarder for older callers; it must not initialize another controller or clear the live Activity’s callbacks when it finishes. USB attachment routes directly to `DiPlayActivity`.

Normal disconnects leave the same home and reusable runtime executors. A connection requested during teardown must wait for teardown completion; cancellation must cancel that pending request. Preserve answered permission decisions and pending permission state across recreation.

Home contains only the connection scene and its normal actions. Do not append update or readiness cards there. Update checks may cache metadata in the background, but update UI belongs only to Settings → About. Download, history lookup, history download, permission and installer callbacks must not rebuild home or show update dialogs after the driver leaves that page. `UpdateHomeAvailabilityTest`, `UpdatePromptFlowTest`, `UnifiedHomeActivityTest` and `UnifiedHomeScreenshotsTest` protect these rules.

Settings → Vehicle → Vehicle background is always available, including after selecting a custom CarPlay icon or migrating a custom OEM name. A saved model or custom wallpaper is independent of the CarPlay brand preset. Brands other than Lincoln keep a neutral default until a background is explicitly selected. `VehicleModelUiTest` covers entry visibility, persistence and large-font screenshots.

Settings → Vehicle → Home brand selects the Lincoln or Ford logo for the home header. Home shows only the logo, with the brand name retained for accessibility. The Ford mark reuses the packaged script with a transparent monochrome treatment matching Lincoln. This appearance choice applies immediately and is independent of the CarPlay vehicle-button identity, wallpaper and pairing; it does not require reconnecting. `HomeVehicleBrandUiTest` covers both brands, persistence, backup and compact/full/ultrawide/RTL screenshots.

The 1920×720 regression matrix covers 160, 240 and 320 dpi with normal and 1.3× fonts: home, connection setup, every setup-guide step, all Settings categories with automatic/full layouts, backup and update dialogs. Home state screenshots also assert unchanged widget positions at each density. Reports are retained in the CI screenshot artifacts.

## Correct build problems

| Symptom | Action |
| --- | --- |
| Installed app is DiPlay map host | Select `mobile` in Android Studio. Build and install the APK from the `mobile` output directory. |
| Gradle cannot find the Android SDK | Set `ANDROID_HOME` or the `sdk.dir` value in `local.properties`. |
| A required SDK or NDK package is absent | Install the listed package with SDK Manager. |
| Java or Gradle JDK version is incorrect | Set the terminal JDK and Android Studio Gradle JDK to Java 25. |
| Source APK cannot start standalone CarPlay | Supply runtime authentication assets. Use `:mobile:assembleStandaloneDebug`. |
| Standalone task reports missing authentication files | Check the external directory path and both required files. |
| Android rejects an app update | Use the same application ID and signing key as the installed app. |
