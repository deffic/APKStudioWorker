# APK Studio Worker — Android

Personal, source-visible Android client for Игорь's private APK Studio.

**Current scope:** secure pairing; encrypted credentials in Android Keystore; connection to a single, fixed HTTPS origin; visible foreground polling with a stop button; guarded ZIP import into app-private storage; transactional source-file backups and automatic rollback on a compilation failure; build job protocol and log/APK upload support.

**Important limitation:** the installed v0.1 APK is a *connection and diagnostic build*, **not a complete on-device APK compiler**. The unmerged v0.2 source branch adds a project profiler, a testable resource compile/link stage, native AAPT2 smoke-test harness, and a SHA-256 pinned compiler-pack installer. The branch now also contains a **reproducible CI bootstrap recipe** for native ARM64 AAPT2; it has **not been executed in GitHub Actions, installed, or tested on a phone**. No usable Kotlin/Compose compiler pack is published. The app deliberately refuses build jobs without a verified, working local toolchain. Android 10 and newer prohibit executing downloaded binaries from writable app-home storage (Android's W^X restriction). Merely downloading a conventional Linux JDK/SDK into `files/toolchain` is not viable on modern Android.

The private `TradingHelperAI` Android app uses **Kotlin + Jetpack Compose**, so ECJ (Java-only) is not a sufficient solution. A full solution needs Kotlin's compiler plus Compose compiler plugin, dependency resolution, Android resource packaging, D8, signing, and hardware testing. An Android-native `aapt2` should be embedded in `nativeLibraryDir` as a packaged `.so`, where Android permits execution; the app must not download and execute untrusted scripts/binaries from writable directories.

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

The first debug APK uses an ephemeral GitHub runner's debug signing key and cannot be assumed to update a separately signed APK in place. A stable release signing key should be established before public distribution or subsequent updates. **The v0.2 branch is an unmerged draft; a new GitHub Actions run must be separately authorized by the owner.**

## Next milestone

- **In progress, source only:** `BuildProfile.inspect` safely scans imported Gradle project modules, detects Kotlin/Compose and SDK versions without running Gradle scripts. `LocalToolchain` checks phone ABI/API and includes a bounded AAPT2 resource smoke test that will run once the native executable is packaged. The user can open this report with **Проверить сборочный движок**; jobs still refuse to build until a compiler actually works.
- **Implemented and host-tested, source only:** `VerifiedToolPack` copies an approved toolpack ZIP into app-private storage, checks a hash **pinned into the APK itself**, then safely extracts only non-native compiler data files with count/size/path limits and an atomic installation directory. It never accepts a hash supplied by a remote job. `ToolPackCatalog.installBundled` will read a first-party APK asset once a real compiler pack's provenance, license, size and SHA-256 have been verified. Both the asset and its hash are currently intentionally absent; tapping **Проверить пакет компиляторов** explains this rather than installing arbitrary code.
- **Implemented and host-tested, source only:** `SourcePatchTransaction` validates all remote edits before writing anything, rejects duplicate/protected paths and symlinks, journals originals, applies replacements safely and restores them if a future compilation fails. The worker continues refusing edits and builds until a real toolchain is ready; backup/rollback logic is preparatory, not permission to run untrusted jobs.
- **Implemented and host-tested, source only:** `ResourceBuildStage` performs the AAPT2 `compile` step for each Android resource, then `link`s the compiled `.flat` files with the app manifest and compatible Android resource platform. It validates the output resource-only APK and generated `R.java`. `BoundedProcessRunner` permits just the supplied embedded native executable, with timeouts and capped logs. On Android, the linker prefers a pinned platform `android.jar`, but can fall back to the device's read-only `/system/framework/framework-res.apk` **only when the device API is at least the project's compileSdk** and the ZIP contains `resources.arsc`. This fallback helps resource diagnostics and **does not replace the SDK stubs required by Kotlin/Java compilation**. The Android UI has **Проверить ресурсы проекта (AAPT2)**; native ARM64 AAPT2 is still not packaged. These resource artifacts are not installable APKs — Kotlin/Java compilation, D8, dependency merging and signing still remain.
- **Prepared in source, not yet deployed:** `scripts/prepare_aapt2.sh` fetches [ReVanced/aapt2 v1.1.0](https://github.com/ReVanced/aapt2/releases/tag/v1.1.0) ARM64 release, checks its *hardcoded SHA-256* `7e5ae2e1f62fc24cab14072555ffd0a1a7e1ce27e82cc1008967b835b6d8df5b`, validates its ELF64 AArch64 static format and 16K page-aligned segments, and embeds it as `jniLibs/arm64-v8a/libaapt2.so`. The app uses `useLegacyPackaging = true` so the binary is extracted into `nativeLibraryDir` at installation. The recipe also embeds the upstream [GPL-3.0 license](https://github.com/ReVanced/aapt2/blob/v1.1.0/LICENSE) plus its `NOTICE.txt`, and refuses to build if license retrieval fails. The binary is a **non-PIE static ELF executable**, not an ordinary shared object; its compatibility with the owner's actual Android device must be proven by the in-app AAPT2 compile smoke test. This dependency is used by a separate subprocess and is never loaded through JNI. The download happens **only during an explicitly approved bootstrap build**, never when the worker app runs. The APK is not yet rebuilt.
- Run the new AAPT2 resource compile/link smoke test on an actual Android 11+ ARM64 phone, then check whether `resourcePlatform` can use its system framework for the project or requires a pinned `android.jar`.
- Integrate an on-device Java compiler, D8 and signing; prove an APK produced and installed on the phone without GitHub Actions/Bitrise.
- Integrate Kotlin + Compose and resolve AndroidX dependencies for `TradingHelperAI`.
- Populate the first-party compiler data pack and pin its SHA-256. Android-native binaries must instead be packaged as executable libraries in the APK. Test every component on real ARM64 hardware.
- Test an actual `TradingHelperAI` build on the user's Android hardware.
- Add build failure reporting, upgrade signing, resumable artifact transfer and storage cleanup.

Host-only checks with a Java runtime containing `jdk.compiler`:

```sh
java scripts/ValidateJava.java app/src/main/java/online/apkstudio/worker/*.java
java scripts/RunBuildProfileTests.java app/src/main/java/online/apkstudio/worker/BuildProfile.java scripts/TestBuildProfile.java
java scripts/RunToolPackTests.java app/src/main/java/online/apkstudio/worker/VerifiedToolPack.java scripts/TestVerifiedToolPack.java
java scripts/RunSourcePatchTests.java app/src/main/java/online/apkstudio/worker/SourcePatchTransaction.java scripts/TestSourcePatchTransaction.java
java scripts/RunResourceBuildTests.java app/src/main/java/online/apkstudio/worker/ResourceBuildStage.java scripts/TestResourceBuildStage.java
java scripts/RunAapt2VerificationTests.java scripts/VerifyAapt2.java scripts/TestVerifyAapt2.java /path/to/pinned/aapt2-arm64-v8a
```

Architecture researched against the [Android 10 executable-file restrictions](https://developer.android.com/about/versions/10/behavior-changes-10) and independent [AIDE-OS toolchain research](https://github.com/sanjarAmin/AIDE-OS/tree/main/tools). No third-party IDE code has been copied into APK Studio Worker, and any future dependency must comply with its license.

The source protocol intentionally allows **only** `assembleDebug`, not arbitrary shell commands from the cloud. Android projects can contain Gradle scripts that execute commands as part of their build; the user should import only their own trusted source.
