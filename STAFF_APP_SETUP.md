# BESTWIN ESPORTS Staff app (separate APK)

Staff now have their **own app** (module `staff/`, package `com.bestwinesport.staff.app`). The Admin app (`admin/`) is admin-only.

## What is different
* **Staff app:** login screen only (email/username + password + 12-digit key). No create account, no signup, no admin login.
  The code for creating accounts, adding/removing staff, changing roles, keys, permissions and the signup key is **not inside the staff app at all**.
* **Admin app:** admin login only (with "Create account" for the very first admin). Staff Management / Team & Keys / Create user stay here.
* A staff account that tries the Admin app is refused ("Staff must use the separate BESTWIN ESPORTS Staff app"); an admin account in the Staff app is refused.
* Both apps use the same Firebase project and database, so permissions you give in Admin -> Staff Management work in the Staff app after re-login.
* The Staff app only shows tiles the staff member has permission for. Firebase rules still enforce everything on the server.

## Build
`./gradlew :staff:assembleDebug` (Android Studio: choose the `staff` run configuration). APK: `staff/build/outputs/apk/`.

## MUST DO before the Staff app works
1. Firebase Console -> Project settings -> Add app -> Android -> package name **`com.bestwinesport.staff.app`**.
2. Download the new `google-services.json` and **replace `staff/google-services.json`**. The file in the project is only a copy of the admin one with the package renamed (so it compiles); push notifications will not register until you replace it.
3. Publish `database.rules.json` (v1.34 update 2) if you have not yet.
4. Share the staff APK only with staff; share the admin APK only with admins.
