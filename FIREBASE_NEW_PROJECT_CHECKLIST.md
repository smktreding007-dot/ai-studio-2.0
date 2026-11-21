# Firebase project: bestwin-esports-709b4 - setup checklist

1. **Realtime Database**: Build -> Realtime Database -> Create database (a database must exist at
   `https://bestwin-esports-709b4-default-rtdb.firebaseio.com`). Start in locked mode, then publish the rules below.
2. **Rules**: paste `database.rules.json` into Realtime Database -> Rules -> Publish
   (or run `firebase deploy --only database` - `firebase.json` and `.firebaserc` are included).
3. **Authentication**: enable **Email/Password** (and Google if used). Add your SHA-1 / SHA-256 for every app.
4. **Android apps** (Project settings): `com.bestwinesport.app`, `com.bestwinesport.admin.app`, `com.bestwinesport.staff.app` (all three registered; the real `google-services.json` is already in `app/`, `admin/` and `staff/`).
5. **Cloud Messaging**: enabled by default. Backend (Railway) needs a service account of project `bestwin-esports-709b4` in `FIREBASE_CONFIG`.
6. **First admin**: the Admin app opens on the **Sign up** page by itself while the Firebase has no admin yet. Enter the admin details and create a new 12-digit login key. No fixed signup key is required. From then on the app opens on **Login** only and Sign up is hidden and closed.
   - Firebase that already has an admin/player account with old data: open the Admin app, on Sign up enter the SAME email + password -> it becomes admin,
     old users / wallets / matches stay untouched. (If the Admin app shows Login only and you lost access: Realtime Database -> delete `system/bootstrapped`,
     then sign up again with the same email + password.)
   - After the first admin is created, change your password from Admin -> Change password. Generate a signup key from Team & Keys only when inviting additional staff/admin accounts.
   - IMPORTANT: publish the latest `database.rules.json` (the Admin app reads `system/bootstrapped` to decide Sign up / Login).
