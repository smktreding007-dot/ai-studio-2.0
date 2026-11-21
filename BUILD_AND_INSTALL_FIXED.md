# BESTWIN ESPORTS – Fixed APK Build Notes

## Modules

- `app/` = User/Player app
- `admin/` = Admin app
- `staff/` = Staff app

## APK built

The installable admin APK was built from the `admin` module:

```bash
./gradlew :admin:assembleDebug
```

Output:

```text
admin/build/outputs/apk/debug/admin-debug.apk
```

The APK uses the standard Android debug signing configuration from Gradle. This is suitable for direct installation/testing on a phone. It is not a Play Store production signing key.

## Release builds

```bash
./gradlew :app:assembleRelease
./gradlew :admin:assembleRelease
```

## SDK requirement

Use Android SDK Platform 34, Build Tools 34.0.0, and JDK 17. The original build failure was caused by the build environment not having `ANDROID_HOME`/`local.properties`; it was not a Kotlin source compilation error.

## Installation

If an older BESTWIN Admin APK is installed and Android reports a package/signature conflict, uninstall the old Admin app first, then install the new APK. Enable “Install unknown apps” for the file manager used to open the APK.
