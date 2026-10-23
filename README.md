# APK Studio Worker — Android v0.1

Personal, source-visible Android client for Игорь's private APK Studio.

**Current scope:** secure pairing; encrypted credentials in Android Keystore; connection to a single, fixed HTTPS origin; visible foreground polling with a stop button; guarded ZIP import into app-private storage; source-file backups; build job protocol and log/APK upload support.

**Important limitation:** v0.1 is a *connection and diagnostic build*, **not a complete on-device APK compiler**. `WorkerService` deliberately fails jobs with an explicit error until we port and package an appropriate Android-native JDK / Gradle / AAPT2 toolchain **inside the APK**. Android 10 and newer prohibit executing downloaded binaries from writable app-home storage (Android's W^X restriction). Merely downloading a conventional Linux JDK/SDK into `files/toolchain` is not a viable solution on modern Android. No third-party IDE is required for the app itself. We must design and validate native code packaging before claiming autonomous APK compilation.

## Permissions

| Android permission | Reason |
|---|---|
| `INTERNET` | Communicate only with `apk-studio-igor.sassyibis7.chatgpt.site` over HTTPS. |
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_DATA_SYNC` | Keep the visible worker alive while the user has explicitly started it. |
| `POST_NOTIFICATIONS` | Show worker status and Stop action. |

No contacts, SMS, camera, location, accessibility, package-listing, root, all-files access, or advertising SDKs. The project ZIP is selected through Android's system document picker and extracted into app-private storage. There is no user tracking.

## Pairing

1. Visit APK Studio → **AI Builder** in a signed-in browser.
2. Select **Подключить телефон** and copy the one-time code. Never share it in chat or screenshots.
3. Open APK Studio Worker → paste the code → **Сохранить сопряжение**.
4. Tap **Включить связь**. The Site should show the phone online within about 15 seconds.

The owner-only pairing code includes a device bearer token and the Site's service-access credential because a private Site does not expose its worker endpoints to anonymous HTTP clients. Both are encrypted locally using Android Keystore AES-GCM. The service credential only lets the device pass the private Site gateway; the worker API *also* validates the separate revocable device bearer. Pairing again in APK Studio invalidates the previous device token.

## Import

Use Android's system picker to choose a ZIP containing `TradingHelperAI/gradlew`, either at archive root or under one top-level directory. The importer limits file count, total decompressed bytes and path traversal. Importing another ZIP creates a *new directory*; it never silently replaces the old project.

## First Android build (one-time GitHub Actions bootstrap)

This standalone public repository `deffic/APKStudioWorker` contains only APK Studio Worker, not the private `TradingHelperAI` code or history. Its `.github/workflows/apk-studio-native.yml` compiles `main` using Java 17 and Android Gradle Plugin 8.7.3. A successful run produces `app-debug.apk` under the `APK-Studio-Worker-v0.1-debug` artifact. The private `TradingHelperAI` repository and its `main` branch are not changed by this build.

The first debug APK uses an ephemeral GitHub runner's debug signing key and cannot be assumed to update a separately signed APK in place. A stable release signing key should be established before public distribution or subsequent updates.

## Next milestone

- Evaluate Android-native OpenJDK/Gradle/AAPT2 distributions and license obligations.
- Pin downloadable toolchain archives with SHA-256 and present exact download sizes/permissions.
- Test an actual `TradingHelperAI` Gradle build on the user's Android hardware.
- Add build failure reporting, upgrade signing, resumable artifact transfer and storage cleanup.

The source protocol intentionally allows **only** `assembleDebug`, not arbitrary shell commands from the cloud. Android projects can contain Gradle scripts that execute commands as part of their build; the user should import only their own trusted source.
