# EzFiles

Offline-first Android document reader and toolkit with **Fily**, an on-device voice assistant. Nothing leaves the phone: no cloud, no network permission, speech recognised on the device.

- **Android:** minimum Android 11 (API 30), target API 37, arm64-v8a and x86_64.
- **iOS:** parked. Shared code compiles, but the iOS platform parts are stubs.

All features, setup and limits: [docs/FEATURES.md](docs/FEATURES.md). Decisions and work checkpoints: [NOTES.md](NOTES.md).

## Fily, the on-device assistant

- **Model:** a fine-tuned LFM2.5-350M (Liquid AI) in GGUF, run by [llama.cpp](https://github.com/ggml-org/llama.cpp) through JNI. It runs on the CPU (Q8_0, 4 threads). The Adreno GPU (OpenCL) works, but measured slower to start on the phones tested ([docs/adreno-gpu-research.md](docs/adreno-gpu-research.md)).
- **Speech:** Android's on-device recognizer, biased towards file names and commands. Fily keeps listening between turns and pauses after about a minute of silence.
- **How a request is handled:**
  - Exact commands skip the model: next/previous page, scroll, zoom, cells, play/pause, close, "open the second file", "go to the slides section".
  - Everything else goes to the model, which can only output a valid tool call, a reply or a question (grammar-constrained).
  - Action results get fixed, accurate replies. Questions about a file are answered from the relevant passages, with page numbers.
- **Code:** `shared/src/commonMain/kotlin/com/ezfiles/reader/application/assistant/`. Start with `FilySession.kt`.
- **Model file:** not inside the APK yet. Push it to the phone:
  ```
  adb push Fily-EzFiles-350M-Q8_0.gguf /data/local/tmp/
  ```
- **What to train next:** [docs/fily-training-needs.md](docs/fily-training-needs.md). The prompt format the app sends must match the training data exactly; `FilyPromptContractTest` checks it.

## Repository layout

| Path | What it is |
|---|---|
| `androidApp/` | The Android app module (entry point, manifest, signing, ABI splits). |
| `shared/` | Kotlin Multiplatform code: UI (Compose), viewers, library, PDF tools, Fily. Native code in `shared/src/androidMain/cpp/`. |
| `engine/llama.cpp` | **Git submodule.** The LLM engine Fily runs on. |
| `engine/OpenCL-Headers`, `engine/OpenCL-ICD-Loader` | **Git submodules** (Khronos). Needed only to build llama.cpp's Adreno GPU backend. The phone's own OpenCL driver is used at run time. |
| `scripts/phone-bench.sh` | Voice-free Fily speed benchmark on a connected phone (debug build). |
| `training/` | Local model training workspace. **Git-ignored** (Python venv, datasets, checkpoints); see `training/README.md`. |
| `docs/` | Feature list, research and plans. |

**Why VS Code shows four repositories:** the app's own repository, plus the three submodules above. They are separate Git repositories by design. Commit only in the app repository. A submodule changes only when its recorded version is deliberately updated.

## Build

```bash
git clone --recurse-submodules <repo-url>          # or, in an existing clone:
git submodule update --init --recursive

./gradlew :androidApp:installDebug                  # debug build on a connected phone
./gradlew :shared:testDebugUnitTest                 # unit tests
./gradlew :androidApp:assembleShare                 # optimized APK for testers (local key if no upload key)
```

Requirements: JDK 17, Android SDK with NDK and CMake. On Windows, the Visual Studio Build Tools are needed to build the OpenCL loader.

Release signing values live outside the repo, in `~/.gradle/gradle.properties` (`ezfiles.signing.storeFile`, `storePassword`, `keyAlias`, `keyPassword`) or `EZFILES_SIGNING_*` environment variables. Without them, `assembleRelease` stays unsigned.

Crash reports are ask-then-share by default. Firebase is opt-in at build time with the Gradle property `ezfiles.firebaseCrashReports`.
