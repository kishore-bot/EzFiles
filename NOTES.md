# Progress notes

This is an append-only history. Older decisions and validation results apply to their recorded checkpoints; later entries may supersede them. Start with the [app feature document](docs/FEATURES.md) and [current implementation status](docs/FEATURES.md#platform-and-status), then read the latest relevant entries below.

## 2026-11-10: Correct Git ignore rules

- Goal: Keep local tooling state and generated build artifacts out of Git.
- Decisions: Retain the Xcode project ignore because `iosApp/project.yml` is the XcodeGen source of truth. Keep Cargo.lock, Gradle wrapper files, source assets, and baseline profiles trackable. Remove the redundant iOS build rule covered by `build/`.
- Files touched: `.gitignore`, `NOTES.md`. Existing source edits were preserved.
- Validation: `git diff --check -- .gitignore` passed; `git check-ignore --no-index -- <paths>` verified 22 ignored paths and 7 trackable paths; `git ls-files -ci --exclude-standard` found no tracked files matching ignore rules. Used path arguments after the PowerShell stdin pipeline failed to pass the probes correctly.
- Next steps: None; no application code changed.

## 2026-11-11: Repository study

- Goal: Understand the project from product, architecture, UI, data, native interoperability, security, performance, testing, and release perspectives. Study only; application source was not changed.
- Scope: Mapped 424 unique tracked paths and examined the major implementation paths, platform adapters, parser families, test sources, build configuration, and architecture/security documentation. This was a static study, not an exhaustive line-by-line audit or runtime certification.
- Repository state: The index had five unmerged paths: `engine/Cargo.lock`, `engine/Cargo.toml`, `engine/crates/document-ffi/Cargo.toml`, `shared/src/androidMain/kotlin/com/ezfiles/app/AndroidAppDependencies.kt`, and `shared/src/commonMain/kotlin/com/ezfiles/app/App.kt`. Working files contained the combined search/tidy wiring. Existing edits and merge state were preserved; files were being edited during the study.

### Code map and final understanding

- Android-first offline document reader/toolkit. Kotlin Multiplatform and Compose own UI, state, platform access, and orchestration. Android is the maintained platform; the SwiftUI host and several iOS adapters exist, but folder access, scanning/OCR, conversion, and screenshot protection have absent or parked implementations.
- `app/App.kt` is the manual composition/navigation root. Reader state chooses the library or reader; tools and About cover the library. Composition locals carry platform capabilities. `MainActivity` handles file intents and memory pressure; `EzFilesApplication` installs local crash reporting.
- Opening flows through `ReaderViewModel`, `DocumentReadingService`, `RustDocumentReader`, owned platform descriptors, and UniFFI/Gobley into Rust. One service owns one reader session. Cancellation guards keep old results out of newer opens; source mode, password prompts, incremental batches, search, and pending opens during editing have explicit state.
- Rust now has five workspace crates: `document-core`, `document-parsers`, `document-search`, `document-tidy`, and `document-ffi`. Older diagrams showing three crates predate search/tidy. Core detects content and applies budgets; parsers extract typed blocks/resources; FFI maps types and owns mutex-protected sessions/descriptors.
- Parser families cover text/code/CSV/Markdown/JSON/HTML, ZIP-based Office/OpenDocument/EPUB, OLE compound/legacy/encrypted Office, images, and PDF validation/tools. Shared ZIP/XML/stream infrastructure enforces bounds. Word/ODT pages and EPUB use generated HTML resources; PDF rendering and media decoding use platform APIs. Spreadsheet formulas use cached values rather than recalculation.
- The library walks user-chosen folder trees with depth/entry/file caps, caches metadata, merges entries, and ranks recents by frecency. Search combines prepared typo-tolerant filenames with a bounded Rust BM25 index and snippets/page locations. Background indexing is lazy, incremental, and paused outside the library; extracted snippets/text live in app-private cache.
- Tidy Up orchestrates size/partial/full xxHash64 duplicate grouping, SimHash document similarity, dHash photo similarity, and transparent keyword-based collections. Results/fingerprints are cached by file metadata. Sensitive-number detection returns flags for checksummed card/Aadhaar/IBAN patterns; screenshot hiding is opt-in. Android deletion is disabled because folder grants are read-only.
- UI includes format-specific readers, bookmarks/EPUB annotations, PDF search and annotations/signatures, text editing, PDF operations, creation, scanning/OCR, theme/haptics, and About/crash sharing. The shared glass palette, controls, motion, and vector icons form the design system. Static semantics/labels were inspected; visual/accessibility behavior was not exercised.
- Text editing is bounded, preserves supported encoding/line endings, detects external changes, and reads writes back before confirming success. It retains text on partial/unconfirmed writes; editor text does not survive process death. PDF tools build and verify private outputs before delivery; annotation overwrites append incremental bytes rather than truncating the original.
- Security boundaries include content-URI handover, SAF grants, bounded copies of non-seekable input, owned descriptor cleanup, archive/XML limits, password/key lifetime controls, blocked WebView network/file access, per-page content policies, disabled backup/transfer, and opt-in scrubbed crash reports. Office Agile HMAC verification, parser fuzzing, process isolation, and external security/device validation remain limitations; panic containment does not cover OOM aborts.
- Performance work includes batched extraction, text read-ahead, persistent reader page lists, lazy views, shared PDF render requests, a 64 MiB PDF cache plus 16 MiB bitmap pool, bounded image caches, memory-pressure callbacks, and low-priority search/tidy work. Baseline profiles and startup macrobenchmarks exist; previous measurements in docs were not rerun.
- Build configuration pins the Gobley/UniFFI pair and an AGP-8-compatible Compose stack, uses JDK 17 and Rust edition 2024, and targets Android arm64-v8a/x86_64. Release uses R8/resource shrinking and external signing settings; benchmark/share variants and opt-in ABI split APKs are distinct from bundles. iOS uses XcodeGen on macOS.

### Observations to carry forward

- File identity is `name|size` when size is known (`data/DocumentTrees.kt` and the Android picker); `ChosenFolderDocuments` deduplicates on it. Different files sharing both values can collapse, affecting library visibility and downstream search/tidy inputs. This is a static finding, not a reproduced device test.
- Architectural rules have drifted: Tidy application services import data storage, and `TidyService` imports presentation `FileType`. The documented stdlib-only domain also now exposes coroutine flows. No refactor was made.
- Some documented capabilities/budgets lag source, including the crate graph, viewer behavior, and picture cache defaults. Prefer the inspected implementation when planning follow-ups.
- Release placeholders remain in crash feedback contact/team metadata. No tracked CI, fuzzing, dependency-audit, or general UI instrumentation configuration was found in the reviewed inventory; the separate baseline-profile module contains device instrumentation.
- Test inventory: 48 Kotlin test files with 658 `@Test` occurrences, plus 426 Rust `#[test]` occurrences. Tests cover reader races/cancellation, parser limits/malformed inputs, editing/save failures, PDF operations, search/tidy, and real host-JVM/native bindings. These are source counts, not passing-test results.
- Validation: Source/configuration review and Git inventory only; no app build, test suite, device session, network capture, advisory audit, or benchmark was run. Checked the notes update with `git diff --check -- NOTES.md`.
- Test commands for a buildable checkout: from `engine`, `cargo test --workspace` and `cargo clippy --workspace --all-targets`; from the repository root, `.\gradlew.bat :shared:testDebugUnitTest :androidApp:assembleDebug`. Device benchmarks require an explicitly selected `ANDROID_SERIAL`; iOS validation requires macOS.
- Files touched by this task: `NOTES.md` only. Next steps: Await the requested follow-up; preserve existing source edits and recheck the current merge/build state before implementation.
- Final Git check: No unmerged paths remained; only the pre-existing `.gitignore` edit and this `NOTES.md` checkpoint were modified. The merge/source state changed independently during the study, so the earlier five-path observation describes the initial snapshot.

## 2026-11-11: Assistant access, sharing, and printing research

- Goal: Research Android MCP/Gemini voice access, Siri, and document sharing/printing; deliver a Markdown proposal in `docs/` without implementing features.
- Final scope: The user clarified Google AppFunctions + Gemini. The proposal uses AppFunctions for local Android tools, App Intents/App Shortcuts for Siri, and native share/print UI. No Genkit backend is needed for this scope.
- Deliverable: `docs/research/2026-11-11-assistant-voice-sharing-printing.md`, with 21 primary-source web references, repository evidence, proposed action coverage, architecture, privacy boundaries, delivery stages, and acceptance checks.
- Findings: The current setup guide requires compileSdk 37 and AGP 9.1+, conflicting with this checkout's compileSdk 36/AGP 8.13.2/Gobley 0.3.7 pins. AppFunctions 1.0.0-rc01 was released on 2026-11-04; examples still show beta01. A compatibility spike is the first gate. The overview's older private-preview wording and newer Connected Console publication guide are not fully aligned, so account eligibility/discovery must be verified.
- Decisions: Test Gemini spoken requests and Gemini Live separately. Restore the parked iOS host before claiming Siri support. Reuse local document services through a narrow action layer, fix name/size identity collisions before exposing document IDs, and use visible share/print/output flows. Start printing with PDF/images/text; validate richer-format fidelity separately. Optional assistant disclosure requires qualified privacy wording.
- Files touched: The new research document and this appended `NOTES.md` checkpoint. Pre-existing edits were preserved; no application source, dependencies, accounts, or configuration were changed.
- Validation: An inline Python check verified all 11 local file links, balanced fenced blocks, and the final newline; `git -c core.autocrlf=false diff --check -- NOTES.md docs/research/2026-11-11-assistant-voice-sharing-printing.md` passed. No application builds, device tests, connector registration, or deployment were performed.
- Next steps: Review the proposal; implementation begins with the toolchain/identity spike and reusable share/print capabilities when requested. Future build, ADB, voice, Siri, and print checks are listed in the proposal.

## 2026-11-11: Photo gallery and video controls research

- Goal: Create a separate research proposal for photo next/previous swiping and zoom, video subtitles/audio languages, seeking/taps, device-lock pause, and volume/brightness gestures; read the other project docs.
- Confirmed interaction decisions: Video horizontal swipe seeks, single tap shows/hides controls, double tap seeks, and device lock pauses playback. Proposed defaults are 10-second left/right double-tap skips, left-side vertical brightness, and right-side vertical media volume. Unlocking stays paused.
- Deliverable: `docs/research/2026-11-11-photo-gallery-video-controls.md`, with 16 primary-source web references, a review map covering all 13 pre-existing Markdown docs under `docs/`, current-code findings, architecture/gesture rules, delivery stages, and validation cases. README/NOTES context was also considered.
- Findings: Photo zoom, double-tap zoom, rotation, animated images, single-tap video controls, and lifecycle pause already exist. Gallery context and player track contracts are missing. The current Android prepared callback calls play unconditionally; test a late-preparation/background race before claiming lock-safe playback. iOS playback remains a parked stub.
- Final design recommendations: Gallery membership must come from authorized references; a single-file grant does not reveal siblings. Use a reference-aware bounded image loader and explicit gesture ownership. Evaluate Media3 behind the existing shared player boundary; 1.11.1 is the researched stable candidate, with build compatibility unverified. Keep audio and subtitle selection separate, start external captions with local SRT/VTT, and restore player-window brightness on exit. No new background-playback behavior is proposed.
- Files touched by this task: The new media research document and this appended checkpoint only. Existing application edits were preserved; no source, dependency, or build configuration changes were made by this task.
- Validation: Inline Python verified all 22 local links, complete 13-document review-map coverage, balanced fenced blocks, final newline, trailing whitespace, and absence of conflict markers. `git -c core.autocrlf=false diff --check -- NOTES.md` passed. No app tests/builds, media playback, device gestures, keyguard tests, benchmarks, or iOS builds were performed.
- Next steps: Review the proposal; future implementation starts with playback-intent/lifecycle validation and gallery reference/loading design, followed by the media-engine compatibility spike. Actual subtitle/codec support, gesture tuning, memory behavior, and optimized builds require device validation.

## 2026-11-11: Performance, motion, and app-wide haptics research

- Goal: Research a lightweight, responsive EzFiles with smooth professional animation and consistent haptics across feature families, covering DSA, Kotlin, Android/iOS, OS scheduling, CPU/GPU, threads, memory, and binary size.
- Deliverable: `docs/research/2026-11-11-performance-motion-haptics-plan.md`, with 24 primary-source web references, inspected-code evidence, proposed device-tier/latency/frame/memory/size gates, prioritized work, motion recipes, haptic coverage, and benchmark/release criteria.
- Findings: Central EzMotion/EzHaptics, persisted Touch feedback, cached backgrounds, drawing-layer press transforms, bounded caches, low-priority workers, and Baseline Profiles already exist. ChosenFolderDocuments synchronously reads/merges the file cache during dependencies created from Compose; trace that startup path first. Existing profile journeys mostly cover startup/Create/PDF tools and can skip missing controls, so reader acceptance benchmarks need asserted journeys and real fixture access. The native workspace has grown, requiring fresh size/memory baselines.
- Final recommendations: Prioritize measured startup work and combined visual-memory ownership, then foreground work queues and consistent interaction coverage. Keep DSA changes tied to traces: top-K search selection, prepared matchers, bounded LRU/in-flight requests, streaming extraction, and existing linear Sauvola scratch. Retain the glass identity with measured CPU/GPU cost, finite interruptible motion, reduced-motion support, and explicit resource budgets.
- Haptic policy proposal: Extend coverage to intentional navigation/activation across the app while retaining selection/toggle/threshold/result semantics. Use one cue per semantic event, throttle continuous detents, respect app/system settings, and keep scrolling/background work quiet. Android system feedback and iOS generators are preferred; no extra haptic library or vibration permission was added.
- Files touched by this task: The new performance research document and this appended checkpoint only. Existing application edits and earlier research were preserved; no source, dependencies, OS settings, or build configuration changed.
- Validation: Inline Python verified all 20 local links, requested topic coverage, balanced fences, final newline, trailing whitespace, and absence of conflict markers. `git -c core.autocrlf=false diff --check -- NOTES.md` passed. No application build/tests, device benchmark, profiler capture, actuator test, or iOS build was performed; all numerical gates remain proposals.
- Next steps: Review the plan. A future implementation starts with a revision/device-specific baseline, traces the synchronous startup/cache path, and establishes combined memory/work budgets before expanding motion/haptic coverage and testing optimized builds on phones.

## 2026-11-11: Android 17 and latest AGP foundation

- Goal: Upgrade before implementing the offline AppFunctions setup, on `feature/android17-appfunctions`; the user authorized parallel agents and pushing a separate branch.
- Decisions: compile/target API 37 (Android 17), AGP 9.5.1, regenerated Gradle 9.7.1 wrapper; retain minSdk 26, Kotlin 2.4.20, NDK 29 and existing Compose/Gobley versions. Keep documented legacy Kotlin/DSL/source-set migration flags until Gobley migrates; remove before AGP 10. Kotlin's fully tested matrix ceiling is lower, so this combination is locally build-tested rather than fully vendor-certified.
- Runtime/lint changes: gate every Android media start on resumed lifecycle to prevent late preparation starting after lock/background. Preserve deliberate telemetry-removal nodes and IANA mixed-case MIME aliases with exact-node lint explanations. No permissions added. Local ignored `local.properties` SDK drive colon was escaped to fix a machine-only lint error; it is excluded from Git.
- Files touched: version catalog, gradle.properties, complete wrapper files/scripts, Android manifest, MediaPlayback.android.kt, README, ADR-019 and this checkpoint. Earlier research/ignore edits remain preserved.
- Validation: `:shared:testDebugUnitTest :androidApp:assembleDebug :androidApp:assembleBenchmark :androidApp:assembleShare :androidApp:bundleRelease :androidApp:lintDebug :shared:lintDebug` passed (658 tests, zero failures/skips). JNI copies precede shared merges. Wrapper JAR SHA-256 matches the published distribution. A controlled text fixture rendered through the debug native reader on emulator-5554 (API 37, x86_64). Logs/artifacts are ignored under .gradle and build directories.
- Next steps: Implement the confirmed assistant switch (off by default, runtime revocation), AppFunctions, reusable share/print and Siri adapters. Still require optimized/compressed native runtime checks, API 26/arm64 devices, lifecycle/large-screen/camera/memory coverage and macOS/iOS verification. Gemini publication requires an eligible account and exact-bundle voice testing; no publication has occurred.

## 2026-11-11: Document sharing and printing feature

- Goal: Implement the proposal's reusable local delivery paths while keeping the existing EzFiles UI and source files intact. The user now requires a commit after each feature, and has additionally authorized the full photo/video and performance/motion/haptics proposals.
- Decisions: Share original files or explicitly selected/draft text through system UI; no automatic recipient. Reader/library/export actions reuse the app's controls, dialogs, haptics and cancellation. Narrow non-exported FileProvider subclass; grant-bearing ClipData; bounded private copies retained for receivers. Print PDF/images/plain text with permission checks, password entry, correct selected ranges and cancellation; Android streams a bounded visual raster copy rather than retaining every page. iOS uses scoped UIKit delivery and system permission checks.
- Limits: 32 shared files, fallback 64 MiB/file, 256 MiB staging with 24-hour cleanup/active print leases, 100,000 shared text characters, 256 KiB print text, 500 selected print pages/128 MiB output/4 million pixels per page. Rich-format printing requires explicit export. iOS password-PDF printing remains unavailable rather than bypassing rights.
- Files touched: DocumentDelivery common/Android/iOS adapters, AndroidDocumentPrintAdapter, share_paths.xml and FileProvider manifest registration; reader/library/editor/export menus; PDF success/preselection helpers and two focused flow tests; iOS picker metadata helper; this checkpoint. Source reads followed the same paths, existing PDF tools/creation contracts and actual AndroidX FileProvider APIs.
- Validation: Coordinated `:shared:testDebugUnitTest :androidApp:assembleDebug :androidApp:lintDebug :shared:lintDebug` passed, 725 tests with zero failures/errors/skips in the working tree (including upcoming feature tests). Delivery-specific range/capability tests and ordered/single-source PDF preselection tests passed. On emulator-5554/API 37, a controlled text fixture reached a one-page system print preview after explicit app confirmation. Actual iOS delivery/picker/access/common contracts compiled to an unlinked ios_arm64 library on Windows; Swift/Xcode and device validation remain separate.
- Next steps: Commit this feature, then commit assistant access/actions; retain subsequent photo/video and haptic changes separately. Still validate Sharesheet grants/receivers, protected PDFs, physical print output, cancellation/OEM behavior, and iOS UIKit delivery on devices.

## 2026-11-12: Optional local assistant feature

- Goal: Implement the complete local action catalog and an Info switch that blocks Gemini/Siri access when off, while preserving ordinary offline use. The user approved AppFunctions runtime/compiler 1.0.0-rc01 and KSP 2.3.12. Latest steering: commit each feature and defer the remaining combined tests until coding is finished.
- Decisions: Both master and content disclosure default off. Runtime generation checks, cancellation, current-scope/provider/version checks and expiring single-use handoffs enforce access independently of catalog discovery. Android uses chosen library folders; Siri uses still-readable picked recents. No Internet/storage permissions, remote server or Gemini API key. Same-name/same-size identities are replaced by provider identities, with cache rebuild and recents/bookmark normalization.
- Implemented: 33 generated Android actions covering metadata, content, bookmarks, explicit delivery reviews, bounded prefilled drafts, ordered PDF merge and direct PDF operation screens, local capture/OCR, visible Tidy scan and consented aggregate summaries. Existing editor/tool work is retained; assistant navigation uses the PDF unsaved-annotation guard. Siri has four authenticated App Shortcuts with scoped queries and foreground review. The implementation record is `docs/assistant-integration.md`; iOS validation is in `docs/ios-assistant-validation.md`.
- Files touched: Android service/registration/handoff classes and metadata resources, application/activity graph wiring, shared assistant policy/actions/navigation/draft/Tidy classes, Info controls, creation prefill, identity stores/pickers/tree adapters and regression tests; Swift App Intents/iOS runtime bridge; Gradle catalog/KSP configuration; README/privacy qualification and implementation/research documents. Delivery UI prerequisites were committed separately as 37e843c.
- Validation already completed before the latest steering: coordinated debug assembly, shared unit tests and app/shared lint passed with 725 working-tree tests, zero failures/errors/skips; generated catalog has 33 disabled-by-default functions. Earlier optimized/share/bundle builds passed for the initial provider. API 37 controlled-fixture checks established default-off denial, runtime denial despite forced catalog enable, filename search/opaque ID, separate content denial and confirmed print-preview handoff. New extended draft/unsaved-tool device script exists only under ignored .gradle and has not run.
- External validation: actual iOS delivery code compiled to an unlinked native library, but Swift/Xcode/device Siri and protected-password PDF printing remain outstanding. Google Connected Console/Play linking, policy/terms URLs, signed bundle and account voice tests have not been published/configured here.
- Tool issue: automatic approval review could not execute the last emulator install/check because its usage quota was exhausted; this was not an unsafe-action determination. No bypass was attempted. Continue authorized workspace coding; retry commits/final checks through the normal approval path and report any remaining block.
- Next steps: Commit assistant feature, integrate authorized photo gallery context/selected-file header, finish performance startup/reduced-motion/benchmark work, commit each feature, then run combined validation and push the feature branch.

## 2026-11-12: Photo gallery feature

- Goal: Swipe through explicitly authorized library photos with zoom, pan and rotation while retaining the app's rose/glass UI. Standalone picker/external-file opens remain single-photo; no sibling discovery is inferred from a file grant.
- Decisions: Freeze the originating ordered image list, deduplicate by URI, retain the session in a ViewModel across Activity recreation, and update header/details/share/print to the selected reference without reopening the Rust reader on every swipe. Unknown selected-file size is shown as Unknown. Pinch/pan suppress paging; a new selection resets its transform. Existing rotation, animation and 2.5x double-tap/6x zoom behavior remain.
- Memory: One visible decode plus one static adjacent preview, serialized reads/decodes, cancellation and memory-pressure cleanup. Preserve the former Rust 64 MiB input limit and 4096/2048 still/animated caps; constrained Android devices use lower decoded dimensions. Fresh grants are checked for prefetched bytes. Android bounds intermediate bitmap allocation and applies EXIF; animations stop with lifecycle. Accurate input lengths no longer trigger a second full encoded-buffer allocation at EOF. iOS retains its existing decoder pending the performance pass/native validation.
- Files read/changed: App.kt; ReaderScreen.kt; ImageViewer.kt; PhotoGallerySession.kt; PhotoImageLoader.kt; PhotoBytes common/Android/iOS; focused gallery/loader/byte-collector tests. Existing engine image limits and Android/iOS decoding paths were checked before preserving their supported input limits.
- Validation: Earlier coordinated checks included the initial photo source/tests; integration and final memory-limit adjustments are intentionally not built/tested yet, following the user's request to test at the end. Final gesture, selected-file delivery, animation, grant-revocation and memory checks remain required.
- Next steps: Commit photo feature, commit video controls and haptic/benchmark work, finish startup/reduced-motion improvements, then perform combined verification and fix failures before delivery.

## 2026-11-12: Video controls feature

- Goal: Complete Android language/subtitle and gesture controls while preserving existing media/document behavior and the app's UI. Existing MediaPlayer is retained; no dependency or permission added.
- Decisions: Separate audio/subtitle choices; native timed text and supported WebVTT packets; bounded local SRT/VTT (including supported BOM decoding). Single tap controls, double-tap 10-second seek, horizontal seek and left/right vertical brightness/volume. Restore window brightness, use media volume buttons, pause on focus loss/keyguard/lifecycle, release focus on completion/error, and never auto-resume on unlock. Unsupported binary captions are visibly unavailable. Existing iOS playback remains parked with compatible defaults.
- Files read/changed: MediaPlayback common/Android/iOS; MediaPlayerView; Android MediaControls; common MediaGestures and Subtitles; MediaControlsTest; media implementation/research documents and this checkpoint. Actual native APIs and controlled host-generated multitrack fixtures were inspected; app glass/rose controls and accessibility targets are retained.
- Validation: Initial coordinated compilation/unit/lint checks passed; the final video audit fixes (audio-focus completion/error, safe player failures, caption clearing, compact controls and cancelled seeking) await the end-of-coding run. Nine caption/gesture tests and a controlled 96 KiB, 30-second H.264/two-AAC-language/tx3g fixture plus SRT/VTT sidecars are ready. No new device playback result is claimed.
- Next steps: Commit video feature; finish startup/motion/benchmark work and commit it separately; then run all builds/tests, native/media/assistant/device checks and push the branch. Platform codec/subtitle support and physical/iOS validation remain explicit gates.

## 2026-11-12: Consistent haptic activation feature

- Goal: Extend intentional activation feedback throughout the existing app without duplicate pulses or noisy scrolling/typing.
- Decisions: Existing buttons, menus, navigation and file activation use a Main-thread semantic-request coordinator. Explicit selection/toggle/result feedback suppresses generic fallback activation, including across nested controls/remembered instances. Continuous detents cap at one tick per 80 ms, while confirmation/rejection remain distinct. App and system settings are retained; long-press native feedback now follows Touch feedback too. No vibration permission, custom vibration pattern, dependency or visual restyling.
- Files read/changed: EzHaptics.kt; components/Components.kt; components/EzChrome.kt; EzHapticsTest.kt; this checkpoint.
- Validation: Nine focused tests cover disabled feedback, activation opt-in, throttling/boundary/clock rollback, nested/cross-instance deduplication and explicit semantic priority. Initial coordinated working-tree checks passed; the final full run and physical haptic feel/accessibility checks remain deferred until coding finishes.
- Next steps: Commit haptics; complete startup/cache readiness, reduced motion and asserted benchmark journeys, then run final combined validation.

## 2026-11-12: Startup and reduced-motion feature

- Goal: Remove synchronous library-cache decoding from startup and add honest readiness/tracing while retaining the existing visual system and supported features.
- Decisions: Restore cached library metadata on the existing background dispatcher, await it suspending before ordinary refresh, and prevent a fresh scan/removal from being overwritten by late cache data. Libraries without folders skip the cache. Report the first usable library/reader model through Android fully-drawn instrumentation; trace fixed dependency/cache section names without filenames or content.
- Accessibility/motion: Observe Android animator scale and iOS Reduce Motion changes. Disable press/selection spatial effects and photo rotation/page travel when reduced; use a short dissolve for major screens. Normal rose/glass colors, shapes, gradients and spring behavior remain. No new dependencies or permissions. Framework/internal/iOS rendering still requires final device checks.
- Files read/changed: ChosenFolderDocuments.kt and LibraryTest.kt; AndroidAppDependencies.kt; App.kt; StartupTiming and ReducedMotion common/Android/iOS adapters; EzMotion.kt; Surfaces.kt; ImageViewer.kt. Cache/data/readiness and OS accessibility interfaces were inspected before changes.
- Validation: Three cache scheduling/readiness/stale-data regressions were added. No new build/test run yet, per the user's end-of-coding instruction. The final combined run must check cached/empty/removed folders, reader readiness, Android and native iOS types, reduced-motion toggling, startup timings and existing app behavior.
- Next steps: Commit startup/motion feature; commit asserted benchmark journeys; finish implementation documentation, then run all configured checks and controlled device tests, fix/commit failures and push.

## 2026-11-12: Custom navigation haptic coverage

- Goal: Cover the remaining custom Tools, Info and Tidy navigation rows without changing their layout or behavior.
- Decisions/files: Reuse the tested duplicate-suppressing activation helper in CreateTools.kt, AboutScreens.kt and TidyUpScreen.kt. Scanner buttons already delegate to covered primary/secondary controls, so they were left intact; touch-swallowing overlays remain quiet.
- Validation: Source/handler review only; combined verification remains scheduled after coding. No dependencies, permissions or visual changes.
- Next steps: Commit this small coverage fix, complete benchmark/documentation commits, then run final checks.

## 2026-11-12: Asserted benchmark journeys and implementation record

- Goal: Make startup/reader/profile checks exercise real native content and fail when required UI is missing, while keeping test fixtures separate from production access.
- Decisions: Minimal launch alone enters Startup Profile; tools and text/PDF readers enter Baseline Profile. A non-exported benchmark-only FileProvider grants controlled fixture access from the test UID before shell handover. Reader assertions verify parsed text/rendered PDF, finite scrolling and changed viewport; frame/peak-memory metrics compare compilation modes. Fixture grants are revoked; failed setup does not mask its error during teardown.
- Files read/changed: baselineprofile generator, journeys and startup benchmark; new reader benchmark, fixture provider/helper, test manifest/path XML; performance implementation/research documents and this checkpoint. Existing pinned Macrobenchmark APIs and production intent/grant handling were inspected. No production permissions or dependencies were added.
- Validation: Source/interface/fixture ownership review completed; no benchmark build/device run yet, following the user's instruction to test after coding. All benchmark and final application checks now need execution. Earlier read-only emulator memory/frame snapshots are explicitly not benchmarks; implementation docs record their limits.
- Next steps: Commit this feature, begin the combined build/unit/lint/benchmark/native/device validation, fix and commit actual failures, then push the requested branch. Account publication, physical-device/OEM/printer and macOS/iOS gates remain explicit.

## 2026-11-12: Combined validation fixes

- Goal: Complete the end-of-coding checks and fix actual failures before pushing.
- Decisions: Skip platform Trace only on host JVMs without Android (SDK_INT below 18), so cache restoration still executes in tests. Replace the benchmark-only AndroidX FileProvider reference with a read-only platform ContentProvider restricted to two literal fixtures; no dependency is added. This replaces the prior FileProvider decision, while preserving explicit grants and cleanup.
- Files read/changed: StartupTiming.android.kt; baselineprofile AndroidManifest.xml and BenchmarkFixtures.kt; removed unused benchmark_fixture_paths.xml; docs/performance-implementation.md; this checkpoint. Benchmark journeys/build configuration and the controlled assistant helper were read for validation.
- Validation: Combined shared tests (730, zero failures/errors/skips), debug/optimized benchmark/share APKs, release bundle, app/shared lint and benchmark APK assembly passed. Rust workspace tests passed. Actual iOS platform adapters including reduced motion/tracing compiled to an unlinked ios_arm64 library; this is not a Swift/Xcode app build. API 37 emulator checks passed authorized search, draft prefill, active-tool preservation, explicit excerpt consent and master-off denial; both switches were restored off. The helper needed a visible Back action after dismissing the keyboard; no app change was needed.
- Next steps: Commit these fixes, run controlled photo/video and benchmark checks, record actual evidence/limits, and push the feature branch.

## 2026-11-12: Immediate search input fix

- Goal: Fix dropped search characters reproduced during the final emulator checks.
- Decision: Keep saveable text-entry state in the UI and forward edits immediately to the existing ViewModel. Background ranking/content results continue independently, with the same filtering and trimmed-query behavior. Do not echo delayed computed results into the IME.
- Files read: LibraryScreen.kt; LibraryViewModel.kt; LibrarySearchUi.kt query references; AndroidDocumentTrees.kt; ChosenFolderDocuments.kt; ImageViewer.kt; MediaPlayerView.kt; MediaControls.android.kt. Files changed: LibraryScreen.kt and this checkpoint.
- Validation: All 730 shared tests, debug assembly and app/shared lint passed. The rebuilt API 37 app retained every character of fast injected `ezfiles-photo` input; before the fix the same command dropped characters. Photo checks passed next button/swipe, selected title/details, zoomed-pan paging suppression and rotation activation. Video checks passed Japanese track selection, displayed embedded English captions, double-tap/horizontal seeking, brightness/volume settings and lock pause without resume. External caption selection, benchmarks and refreshed optimized packages remain in progress.
- Next steps: Commit this fix, finish the remaining controlled checks, document evidence and push.

## 2026-11-12: Runtime media checks and benchmark selector fix

- Goal: Finish actual Android integration checks and make asserted benchmark journeys match the existing UI.
- Decisions: Replace the stale benchmark `Create` description with the library's visible `Tools` action. Production UI remains unchanged. Regenerate separate narrow startup and tools/text/PDF profiles after the correction.
- Files read/changed: Journeys.kt; benchmark generator/startup/reader classes and manifests; CreateTools.kt; docs/assistant-integration.md, media-controls.md and performance-implementation.md; this checkpoint. The implementation records now contain actual test evidence and remaining platform gates.
- Validation: Refreshed optimized benchmark/share APKs and release bundle passed after the search fix. All four variants have 33 default-disabled functions; the optimized manifest has camera and app-local receiver permissions, no Internet or broad storage permission; both supported native ABIs and compiled profiles are packaged. Local SRT selection/display passed through the real system picker. Selected-photo sharing reached the Sharesheet without sending; title/details followed the selected photo. Four eight-iteration reader benchmarks passed; two startup cases exposed the stale selector. After correction, current native PDF and startup profile collection journeys passed; remaining collection and startup reruns are in progress. Reports/traces are retained only under ignored .gradle.
- Next steps: Commit this checkpoint, finish profile generation/repackaging and corrected benchmark runs, record final results, push and verify the remote branch.

## 2026-11-12: Regenerated app-specific performance profiles

- Goal: Ship profiles collected from the actual current library, Tools and native text/PDF journeys, and ensure the benchmark measures them.
- Decisions: All four collection journeys passed (zero failures/errors/skips). Baseline Profile: 25,497 rules; Startup Profile: 19,819. Startup rules are a subset after ART-flag normalization. Inspection found the custom benchmark source set had only dependency rules, so it now explicitly consumes the same release profile directory as Share. Benchmark merging now includes 3,705 EzFiles rules and 2,046 EzFiles startup rules; this replaces the prior assumption that its profile included app code. Historical measurements are qualified accordingly.
- Files read/changed: androidApp/build.gradle.kts; the two generated release profile text files (counts/diff statistics, not large dumps); docs/architecture/performance.md and docs/performance-implementation.md; this checkpoint.
- Validation: Profile generation completed successfully; rebuilt optimized benchmark/share APKs, release bundle and benchmark test APK passed. Sizes: benchmark 38,051,960 bytes, Share 20,353,812 bytes, bundle 25,469,517 bytes. The final six-case timing suite is running on the final optimized APK, isolated from compilation; previous reader runs passed but used dependency-only profiles and are not claimed as app-profile comparisons.
- Next steps: Commit regenerated profiles and wiring, finish the six-case suite, smoke-test optimized assistant gating, record final measured evidence, push and verify the remote branch.

## 2026-11-12: Search restoration regression fix

- Goal: Keep restored search text and results consistent after Android reclaims the app process.
- Decision: Synchronize the saveable input into the existing ViewModel in the existing resume callback before refreshing the library. This adds one line and preserves immediate typing, background ranking and the current UI.
- Files read/changed: LibraryScreen.kt; MainActivity.kt and AndroidManifest.xml lifecycle/launch references; docs/performance-implementation.md; this checkpoint.
- Validation: Before the fix, process recreation restored the controlled query while showing the empty-query library. After the fix, confirmed debug-process death under its own UID restored both the query and No matches state; the controlled query was cleared. The helper now checks actual input focus and process exit, avoiding ambiguous Back keys/background-kill races. All 730 shared tests, app/shared lint, debug/optimized benchmark/share APKs and release bundle passed again. All six optimized benchmark cases passed at f5f7ae3; both cold-start cases are being rechecked after this one-line lifecycle correction. Reader implementation/measurement blocks are unchanged.
- Next steps: Commit this regression fix, finish cold-start recheck and optimized assistant gating, record measurements and remaining device/account gates, then push the branch.

## 2026-11-12: Final implementation and validation checkpoint

- Goal: Deliver the requested Android 17/AppFunctions, sharing/printing, media controls and performance/motion/haptic work as feature commits on a separate branch, with testing after coding.
- Final decisions: Keep existing UI/components and offline/local processing, optional assistant access plus independent content consent, bounded media/printing/caption work and current native playback. All local code fixes are committed. Account publication, full iOS linking/Siri, physical device/printer/provider acceptance and lag-free certification remain external release gates; no main merge is requested.
- Files touched in this checkpoint: docs/performance-implementation.md; docs/assistant-integration.md; NOTES.md. Feature source/profile changes and earlier implementation records are preserved in their individual commits. Final code checkpoint is 6a81161; generated profiles are f5f7ae3.
- Validation: 730 shared tests passed (zero failures/errors/skips), 460 Rust tests passed (one existing manual tuning measurement intentionally ignored), app/shared lint passed, debug/optimized benchmark/share APKs, release bundle and benchmark APK assembly passed. All four profile-generation journeys passed. All six optimized benchmark cases passed with eight iterations each; both cold-start cases passed again on 6a81161. Final minified assistant checks passed off/on metadata dispatch, controlled draft handoff and off denial, with both switches restored off. Earlier actual-device-fixture checks cover content consent, unsaved-tool preservation, photo paging/zoom/details/sharing and video tracks/captions/seeking/lock pause. Process-recreation search consistency passed after its correction. Actual iOS platform adapters compiled to an unlinked native library; no Swift/Xcode/device claim is made.
- Commands/evidence: `:shared:testDebugUnitTest :androidApp:assembleDebug :androidApp:lintDebug :shared:lintDebug :androidApp:assembleBenchmark :androidApp:assembleShare :androidApp:bundleRelease`; `cargo test --workspace --manifest-path engine/Cargo.toml`; `:androidApp:generateReleaseBaselineProfile` scoped to emulator-5554/BaselineProfileGenerator; standalone StartupBenchmark/ReaderBenchmark instrumentation with explicit EMULATOR acknowledgement. Logs, JSON and traces are ignored under .gradle; reproduction and measured table are in docs/performance-implementation.md.
- Limits: Final startup medians on the emulator were 1,701/1,646 ms initial and 1,821/1,746 ms fully drawn (None/required profile). Reader CPU P90 was about 12.8/11.4 ms for text and 27.6/26.9 ms for PDF. Cross-run startup variance and PDF tail frames prevent a universal lag-free claim. Physical low-memory/high-refresh, haptic feel, OEM codecs, actual receiver/print completion, revocation/cancellation stress, macOS/Siri and Google account/voice publication remain necessary acceptance work.
- Delivery: Push the fully committed feature/android17-appfunctions branch to origin and verify its remote head; preserve main and existing branches. Subsequent release work should follow the implementation documents' device/account gates rather than treating these local checks as publication or platform certification.

## 2026-11-12: Android 17 personal-phone installation diagnosis

- Goal: Diagnose androidApp-release.apk reporting App not installed and install the user's chosen optimized build while preserving existing app data.
- Evidence: The local release APK passes signature verification specifically for API 37, 16 KiB ZIP alignment and 16 KiB ELF LOAD alignment for all 12 bundled native libraries. Its signing certificate differs from the installed Debug app and existing Share APK. On the connected A059P phone (Android 17/API 37, arm64-v8a, 4 KiB pages), a non-uninstalling release update returned INSTALL_FAILED_UPDATE_INCOMPATIBLE: Existing package com.ezfiles signatures do not match newer version. This confirms the current update rejection; the originally reported fresh-install attempt was not reproduced after Debug was installed.
- Decision: The user explicitly chose to retain data and install the matching debug-key-signed optimized Share APK for local testing. No uninstall, data clear, signing-key change or application code change was performed. An actual production-signed release must keep a compatible signing identity, or use a separately approved clean installation; Share does not always use the debug key if upload-signing credentials are configured.
- Validation: Share installed with adb install -r successfully; credential/device-encrypted app-data directory inodes remained unchanged. Package flags no longer include DEBUGGABLE or TEST_ONLY. Explicit Activity launch returned Status ok and the process remained running. This is physical-device installation/launch evidence, not full media/haptic/performance acceptance. No private app data or signing secrets were inspected.
- Files read: NOTES.md; androidApp/build.gradle.kts; AndroidManifest.xml references; README.md; release output-metadata.json; existing APK manifests/signing metadata. Files changed: NOTES.md only. Exact installer output and an installed-APK certificate comparison were obtained using the user's authorized Wi-Fi connection; temporary diagnostics are ignored under .gradle.
- Next steps: Use the installed Share build for local testing. Keep a consistent production signing key for distributed updates; obtain explicit approval before any data-erasing clean installation. The existing physical feature, macOS/Siri and Gemini-publication gates remain.

## 2026-11-12: Shared safe-area layout

- Goal: Keep app controls/content clear of cutouts, side system bars and the keyboard while preserving the existing visual identity.
- Decisions: Add a shared appSafeArea modifier after each full-screen background, applying horizontal safe-drawing insets and display-cutout padding. Existing screen-owned status/navigation padding consumes the remaining insets rather than adding the same padding twice. Apply it to Library, reader, Info, tool/editor frames and sheets. Keep video pixels edge to edge while separately protecting header, transport and subtitle overlays. Library/tool frames handle IME padding; the seven-row Tools list scrolls on short/landscape screens. No dependency, permission, signing or processing change.
- Files changed: theme/AppSafeArea.kt; LibraryScreen.kt; ReaderScreen.kt; AboutScreens.kt; CreateTools.kt; TextEditorScreen.kt; components/EzChrome.kt; viewers/MediaPlayerView.kt; NOTES.md. Additional reads: MainActivity.kt, App.kt, Surfaces.kt, TidyUpScreen.kt, ScanComponents.kt, ContentView.swift and iOSApp.swift. iOS continues using its existing host safe-area/keyboard ownership; no Xcode validation is claimed.
- Validation: `:shared:testDebugUnitTest :androidApp:assembleShare :androidApp:lintDebug :shared:lintDebug` passed after the final scroll adjustment; 730 shared tests, zero failures/errors/skips. git diff --check passed. The optimized Share APK signature still matches the installed local-test build. No new tests were added to mirror framework padding APIs.
- Device status: The connected Android 17 phone reports a real 126-pixel top cutout in portrait and 63-pixel bottom navigation inset. Visual automation stopped before installation because the foreground switched away from EzFiles; no unknown editor/tool was discarded and no data was cleared. The new APK is ready at androidApp/build/outputs/apk/share/androidApp-share.apk, but it has not yet been installed on the phone. An availability question for the brief foreground/rotation/keyboard check is pending.
- Next steps: Once the phone can remain in EzFiles, install the matching-key Share update without clearing data, verify portrait/landscape/keyboard and the last Tools action, and restore the original rotation setting. Full-screen media/transient-bar, three-button navigation and iOS device checks remain acceptance work.

## 2026-11-12: Presentation paging crash correction

- Goal: Fix the reported Android 17/PPTX crash at SlideshowViewer's cached pager key callback (index 25, list length 25) during native batch loading.
- Decision: Keep the existing 1-based slide identity using index + 1 without indexing a captured list. Pager count and cached page content read one rememberUpdatedState slide list; obsolete scheduled page requests after a replacement/shrink are guarded. Page position, slide rendering and presenter navigation remain intact. Full-screen presentation content/chrome also respects cutouts and the bottom gesture area.
- Files read/changed: SlideshowViewer.kt; baselineprofile BenchmarkFixtures.kt and new SlideshowPaginationRegression.kt; docs/performance-implementation.md; NOTES.md. The test-only provider still accepts only four literal fixture filenames with exact MIME types, adding generated PPTX and Markdown fixtures without dependencies or production permissions.
- Validation: Earlier combined working-tree shared tests/lint/build passed before the final glass/fullscreen revisions. The latest combined application compiled and packaged successfully with :androidApp:assembleShare. A native 60-slide fixture/regression was prepared to cross multiple 64 KiB loading batches. Its pre-fix run failed to reach slide 2; a matching AndroidRuntime exception was not captured, so that failure is not claimed as proof of reproducing the supplied stack. The corrected regression was not run: the user explicitly took over testing. The isolated read-only emulator was stopped; no personal files were used for the generated fixtures.
- Next steps: User tests the latest Share APK with the original presentation, including the 25-slide boundary, forward/back navigation and presenter mode. Execute the prepared native regression when automated testing is requested again. A complete iOS/Xcode and physical acceptance claim is not made.

## 2026-11-12: Glass reader controls and fullscreen space

- Goal: Match the supplied screenshots/request: transparent glass Preview/Source buttons, HTML using the former white footer, glass app/book bars and safe fullscreen content/controls.
- Final decisions: Keep both Preview and Source visible with 48 dp touch targets, translucent glass and a subtle selected tint; haptics and actual view switching remain. Remove the fixed 60 dp native HTML footer so the WebView fills that space. Hide only the passive offline-content notice in immersive mode, retaining actual access/error prompts and the existing offline/security policy. Reduce shared bar opacity and replace opaque book-menu backgrounds with existing glass rendering. In immersive books, ordinary chapter/progress rows no longer shrink the page; their menu equivalents remain available. Reader/presentation fullscreen and media/book overlays respect cutouts, bars and the bottom gesture area; the fullscreen exit control uses glass and a 48 dp target. No dependency, network permission, script policy or signing change.
- Files read/changed: ReaderScreen.kt; components/Components.kt; theme/Surfaces.kt; viewers/HtmlViewer.kt, BookViewer.kt and MediaPlayerView.kt; baselineprofile/PreviewSourceRegression.kt; docs/performance-implementation.md; NOTES.md. The presentation fullscreen container is included in the preceding paging-fix commit. Existing ReaderUiState/ViewModel source-switch contracts, HTML policy and app/safe-area frames were reviewed.
- Validation: Latest :androidApp:assembleShare compiled and packaged successfully after these revisions. An earlier combined working-tree run passed shared tests/lint before the final screenshot-driven revisions; those results are not claimed for the latest complete UI. The user explicitly requested to handle testing: automated/device testing was stopped and the read-only emulator shut down. A Markdown fixture/scenario is prepared to check both mode directions and WebView height behind the floating controls, but was not run on the final build. No further phone interactions, uninstall or data clear were performed after that instruction.
- Replaced plan: The preceding pending automated phone-rotation/keyboard check is replaced by user-led testing for now. The final matching-key local-test APK is androidApp/build/outputs/apk/share/androidApp-share.apk. It has not been installed on the personal phone as part of this final packaging step.
- Next steps: User installs the latest Share APK and checks the shown HTML/footer case, both mode buttons, normal/fullscreen book and media controls, rotation/keyboard and the original presentation. Revisit automated regression/device acceptance only when requested; physical/iOS certification remains pending.

## 2026-11-12: Unicode analysis crash correction

- Goal: Fix the reported PDF At a glance UTF-8 panic without changing extraction or document contents.
- Final decisions: Date ordinal checks use ASCII bytes instead of slicing across a UTF-8 character; sentence abbreviation splitting handles multibyte whitespace. The old date probe reproduces the reported byte-boundary geometry (1553 inside a character at 1552). No dependencies or FFI changes.
- Files read/changed: engine/crates/document-glance/src/items.rs; sentences.rs; lib.rs. Related search/token/snippet and Kotlin glance call paths were reviewed.
- Validation: Android native compilation completed for the changed engine during the combined packaging/static-lint run. Unicode/date/whitespace regression scenarios were added but not executed; the user's instruction to handle testing remains in force. Final Android packaging/static checks are recorded below when complete.
- Next steps: User checks At a glance with the original PDF and non-ASCII text. Original-file reproduction and runtime regression execution remain pending.

## 2026-11-12: Conservative black-and-white scan edge cleanup

- Goal: Remove the thin background strip left by a slightly loose scan crop.
- Final decisions: Before thresholding, whiten only narrow edge-touching dark bands with at least 80% continuous support and a bright paper margin. Keep image size/content coordinates unchanged; other filters are untouched. Broad, sparse or uncertain edges remain manual-crop cases. A genuine continuous printed border exactly at the image edge can resemble background.
- Files read/changed: shared/src/commonMain/kotlin/com/ezfiles/reader/presentation/scan/ScanFilters.kt; shared/src/commonTest/kotlin/com/ezfiles/reader/scan/ScanFiltersTest.kt.
- Validation: Shared Android compilation passed in the combined packaging run; git diff --check passed. Regression scenarios cover all edges, sloping bands, interior ink and ambiguous borders; they were prepared but not run because the user owns testing for now.
- Next steps: User checks black-and-white scans with loose corners and edge-adjacent print; retain manual cropping for uncertain edges.

## 2026-11-12: Offline Tidy purpose collections and cache recovery

- Goal: Group resumes/CVs and banking documents by purpose, recover failed analysis and remove older Android Tidy lint warnings without weakening duplicate/similarity safeguards.
- Final decisions: Add Resumes & CVs across Rust/UniFFI/Kotlin; rename the banking UI label Banking & statements and require bank-document or ledger evidence. Explicit resume names/career structure take precedence over banking job-history vocabulary. Keep visible reasons, existing cards/actions and local English rules; uncertain files stay unclassified. No new model, dependency, network access or file movement.
- Cache decisions: Revision 2 reclassifies existing documents once while retaining exact hashes. Blank/missing text and failed previews retry on explicit scans. Successful short/flat results remain cached. Apply filename rules beyond content-read budgets; preserve cancellation. Existing 42-word similarity and flat-photo safeguards remain.
- Files read/changed: engine/crates/document-tidy/src/collections.rs; engine/crates/document-ffi/src/tidy.rs; shared common TidyService.kt, RustTidyEngine.kt, domain/Tidy.kt, presentation/tidy/TidyUpScreen.kt and TidyServiceTest.kt; AndroidTidyFiles.kt. docs/format-support-matrix.md records category/limit details alongside the PDF compatibility update.
- Validation: Regenerated native bindings and shared/app Android compilation passed during packaging. Static lint is running; final result is recorded below. Added classification, retry, legacy-cache, successful-cache and native-bridge scenarios, but no tests/device actions were executed under the user's testing instruction.
- Next steps: User runs an explicit Tidy rescan and checks real resumes, banking statements and a banking-job CV. Scanned images currently receive filename classification, not automatic content OCR; broader topic/language understanding remains unsupported.

## 2026-11-12: Legacy Android PDF text compatibility and identifiable handoff

- Goal: Make PDF find, At a glance and text export usable below native PDF text API support, keep analysis failures inside the UI, and distinguish the delivered APK from earlier crash reports.
- Final decisions: Preserve Android 15+ native extraction/selection and Android 12–14 SDK extension 13. Other Android devices reuse the already-bundled offline ML Kit Latin recognizer without a new dependency. Recognition uses serialized page work, bounded 2-megapixel/2048-side rendering, 32K characters per page and a 512 KiB/16-page text cache, cleared on memory pressure/close. Bitmap ownership lasts until recognition completes even if the caller cancels. Renderer locks are released before recognition. Encrypted/uninspectable PDFs are conservatively refused on this fallback; recognition provides approximate line highlights, not native selection.
- Work limits: Explicit find/text export retain page progress and cancellation; background text consumers sample at most eight pages. Query whitespace is normalized and wrapped phrase highlights use binary lookup by line offsets. Empty-result checks reuse the just-recognized page rather than repeating OCR over the whole PDF. Notices explain Latin-script accuracy and sampling. Glance catches analysis exceptions with Try again while preserving cancellation.
- Files read/changed: shared android platform/AndroidPdfPages.kt and new LegacyPdfText.kt; shared common platform/Platform.kt and new PdfOcrSearch.kt; data/EngineDocumentText.kt; presentation/glance/GlanceSheet.kt; pdftools/PdfToTextScreen.kt; viewers/PdfReader.kt and PdfSearch.kt; docs/format-support-matrix.md; androidApp/build.gradle.kts. Existing TextRecognition, PdfEditing, PdfSearch and photo/slideshow session contracts were reviewed.
- Crash/build evidence: User identified the reported crashes as Debug. No Debug obfuscation mapping exists locally, so the supplied PPTX stack cannot conclusively identify that APK. Current SlideshowViewer already uses one live slide snapshot, index-independent keys and guarded stale-page access (b74c048). Frozen photo sessions were reviewed and remain unchanged. Share versions now include an explicit local-build suffix; the requested artifact uses 0.1.0-share-fixes-20261112-r1 with the existing application ID/signing selection.
- Validation policy: The user continues to own runtime/device testing. No unit tests, instrumentation, installation, phone manipulation or data clearing were performed in this fix batch. Gradle packaging and static lint are allowed; final command/result and delivery are recorded below. The earlier broad completion claim does not supersede outstanding iOS/physical acceptance gates.
- Next steps: Install the labelled Share APK without uninstalling the matching-key app; check the original PDF/PPTX, explicit Tidy rescan, scan edges and old-Android recognition. Wider/ambiguous scan edges, unsupported scripts and general semantic AI grouping remain limitations; original-file runtime acceptance is pending.

### Final packaging/static checkpoint for this fix batch

- Passed: `java.exe -jar gradle/wrapper/gradle-wrapper.jar :androidApp:assembleShare :shared:lintDebug :androidApp:lintDebug -Pezfiles.buildLabel=fixes-20261112-r1 --console=plain` with the existing Gradle cache. Final run: BUILD SUCCESSFUL; includes the final low-memory/cache-snapshot correction. Static lint reports zero errors (89 shared and 7 app existing warnings); AndroidTidyFiles warnings are removed. No runtime/unit/instrumentation tests were run.
- Artifact: androidApp/build/outputs/apk/share/androidApp-share.apk, version 0.1.0-share-fixes-20261112-r1 (1). Build source includes the existing slideshow pager correction. No personal-phone installation or data changes were performed. The user's original-file and physical-device checks remain pending.
- Delivery: Commit each fix feature separately and push feature/android17-appfunctions; preserve main and all branches. Use the uniquely labelled APK for subsequent crash reports rather than assuming an earlier 0.1.0 (1) APK contains these fixes.

## 2026-11-12: Whole-UI semantic haptic coverage

- Goal: Give navigation, clicks, page changes, choices, toggles, adjustments and results distinct feedback while retaining the existing glass UI and user/system off switches.
- Decisions: Reuse short system effects only (no new SDK, waveform or vibration permission). Navigation uses GestureEnd; page turns use TextHandleMove. Shared controllers deduplicate nested semantic actions, defer asynchronous result feedback and keep unchanged choices quiet. Intentional App callbacks carry navigation feedback; no blanket screen-state observer was added. Ordinary scrolling, typing, restoration and background work remain quiet. Hardware/OS implementations may combine or omit effects, so physical feel is not guaranteed by the enum alone.
- Files reviewed/changed (common Kotlin paths, one per file):
  - app/App.kt
  - reader/presentation/theme/EzHaptics.kt
  - reader/presentation/components/EzChrome.kt
  - reader/presentation/bookmarks/BookmarksScreen.kt
  - reader/presentation/bookmarks/ReaderBookmarks.kt
  - reader/presentation/create/ExportBar.kt
  - reader/presentation/glance/GlanceSheet.kt
  - reader/presentation/library/LibrarySearchUi.kt
  - reader/presentation/pdftools/PdfConvertUi.kt
  - reader/presentation/pdftools/PdfToTextScreen.kt
  - reader/presentation/pdftools/PdfToolsHub.kt
  - reader/presentation/scan/ScanComponents.kt
  - reader/presentation/viewers/EmbeddedMedia.kt
  - reader/presentation/viewers/HtmlMedia.kt
  - reader/presentation/viewers/ImageViewer.kt
  - reader/presentation/viewers/PdfAnnotationUi.kt
  - reader/presentation/viewers/PdfPageScrubber.kt
  - reader/presentation/viewers/PdfReader.kt
  - reader/presentation/viewers/SignatureUi.kt
  - reader/presentation/viewers/SlideshowViewer.kt
  - reader/presentation/viewers/SpreadsheetViewer.kt
- Validation: Shared/app Kotlin compilation passed in the combined packaging/static-lint run. No unit/runtime/device tests were run under the user's instruction to handle testing. Final artifact/static results are recorded with the EPUB follow-up below. Book-specific controls and bridge changes are committed with that follow-up.
- Next steps: User compares click/navigation/page/choice/toggle/result cues on the Nothing phone and verifies Info Touch feedback off plus system haptics off. Physical iOS feel and reduced-motion/device acceptance remain pending.

## 2026-11-12: Animated EPUB swipes and book-control feedback

- Goal: Make Android paged EPUB swipes feel like moving a book page while keeping the app's glass chrome, reading features and bounded local processing.
- Decisions: Reuse the current chapter DOM and scroll geometry for a finger-following page transition, subtle paper-edge shade and 220 ms ease-out settle. Short/cancelled/multiple-touch gestures return to rest; selection and vertical native scrolling remain available. Chapter boundaries use a restrained incoming slide. Keep existing RTL/vertical-layout navigation rules, anchors, bookmarks, text offsets, highlighting and saving. No extra chapter DOM, bitmap snapshots, GPU effects library or new dependency. This is a paper-like slide, not a simulated 3D page curl.
- Feedback/accessibility: Token-validated successful-turn messages request the shared page cue. Deferred page/bookmark/highlight controls avoid duplicate generic feedback; book contents/notebook/settings/scrubbing use the shared activation/choice/step rules. Native reduced motion is embedded initially and can update live; the web media query is also respected. Animation frames cancel on jumps, relayout and motion changes. A second finger during a drag restores the resting page.
- Files read/changed:
  - engine/crates/document-parsers/src/epub_book.rs
  - shared/src/commonMain/kotlin/com/ezfiles/reader/presentation/viewers/BookModel.kt
  - shared/src/commonMain/kotlin/com/ezfiles/reader/presentation/viewers/BookViewer.kt
  - docs/performance-implementation.md
- Additional platform files reviewed:
  - shared/src/androidMain/kotlin/com/ezfiles/reader/presentation/viewers/HtmlViewerPage.android.kt
  - shared/src/iosMain/kotlin/com/ezfiles/reader/presentation/viewers/BookPage.ios.kt
  - shared/src/commonMain/kotlin/com/ezfiles/reader/presentation/theme/EzMotion.kt
  - shared/src/commonMain/kotlin/com/ezfiles/reader/presentation/components/Components.kt
- Validation: Final `:androidApp:assembleShare :shared:lintDebug :androidApp:lintDebug -Pezfiles.buildLabel=ui-haptics-book-20261112-r1` passed, including final native-source packaging. Static lint: zero errors, 89 shared/7 app existing warnings. Extracted embedded JavaScript passed Node `--check` using the already-installed VS Code embedded Node 24 runtime; no app script/browser execution was used. `git diff --check` passed. No unit tests, instrumentation, device installation, phone actions or performance benchmark were run; the user retains testing.
- Artifact: androidApp/build/outputs/apk/share/androidApp-share.apk, version 0.1.0-share-ui-haptics-book-20261112-r1 (1), existing application ID/signing selection. Save this as a separate feature commit and push feature/android17-appfunctions, preserving main/branches.
- Next steps/open gates: User tests forward/back and cancelled/rapid/reversed swipes, chapter/book boundaries, selection/links/notes/bookmarks, font/theme changes, rotation/fullscreen, RTL/vertical books, reduced motion and Touch feedback off. Physical smoothness/haptic feel remain unverified. The existing iOS paged BookPage adapter is still unimplemented (Scroll fallback); these changes do not complete it or establish iOS acceptance. Earlier benchmark/test evidence does not measure this follow-up.

## 2026-11-12: Shared glass polish and floating controls

- Goal: Review the app's UI and make its glass/floating actions feel more refined while protecting reading, existing actions and the established visual identity.
- Decisions: Add a stationary cached diagonal reflection to shared glass and panels. Introduce floatingGlass with an outside-only soft shadow. Apply it to shared round actions, navigation/action panels, the floating Open file capsule, Preview/Source and book controls. Open file keeps its 56 dp footprint and uses a red icon medallion plus theme-aware text. Book bars use a stronger tint for labels over pages; the fullscreen exit uses the existing shared 48 dp haptic/press-aware control. PDF tool tiles gain shared glassCard and press feedback. No new overlay action, dependency, blur/capture, animation loop, document opacity, text-style, colour-swatch or content-rendering change.
- Reading/layout audit: Library already reserves end clearance for floating actions/navigation. About/Create/Tools use shared safe-area frames and glass surfaces. Document pages/editor backgrounds retain their existing opaque paper and ink. Existing control placement, content space, source switching, safe areas, fullscreen visibility, haptics and reduced-motion behavior are retained. Physical appearance/contrast and smoothness remain acceptance checks rather than build guarantees.
- Files read/changed:
  - shared/src/commonMain/kotlin/com/ezfiles/reader/presentation/theme/Surfaces.kt
  - shared/src/commonMain/kotlin/com/ezfiles/reader/presentation/components/Components.kt
  - shared/src/commonMain/kotlin/com/ezfiles/reader/presentation/components/EzChrome.kt
  - shared/src/commonMain/kotlin/com/ezfiles/reader/presentation/ReaderScreen.kt
  - shared/src/commonMain/kotlin/com/ezfiles/reader/presentation/viewers/BookViewer.kt
  - shared/src/commonMain/kotlin/com/ezfiles/reader/presentation/pdftools/PdfToolsHub.kt
- Additional files reviewed/searched:
  - shared/src/commonMain/kotlin/com/ezfiles/reader/presentation/theme/EzTheme.kt
  - shared/src/commonMain/kotlin/com/ezfiles/reader/presentation/library/LibraryScreen.kt
  - shared/src/commonMain/kotlin/com/ezfiles/reader/presentation/about/AboutScreens.kt
  - shared/src/commonMain/kotlin/com/ezfiles/reader/presentation/create/CreateTools.kt
  - shared/src/commonMain/kotlin/com/ezfiles/reader/presentation/create/ExportBar.kt
  - shared/src/commonMain/kotlin/com/ezfiles/reader/presentation/pdftools/PdfConvertUi.kt
  - shared/src/commonMain/kotlin/com/ezfiles/reader/presentation/tidy/TidyUpScreen.kt
  - shared/src/commonMain/kotlin/com/ezfiles/reader/presentation/viewers/PdfReader.kt
  - shared/src/commonMain/kotlin/com/ezfiles/reader/presentation/viewers/MediaPlayerView.kt
- Validation: `:androidApp:assembleShare :shared:lintDebug :androidApp:lintDebug -Pezfiles.buildLabel=glass-20261112-r1` passed (BUILD SUCCESSFUL). Static lint has zero errors, 89 shared/7 app existing warnings. `git diff --check` passed. No unit/runtime/device tests, installation or phone manipulation were performed; the user retains testing. Earlier benchmarks do not measure this follow-up.
- Artifact/delivery: androidApp/build/outputs/apk/share/androidApp-share.apk, version 0.1.0-share-glass-20261112-r1 (1), existing application ID/signing selection. Commit this visual feature and push feature/android17-appfunctions, preserving main/branches.
- Next steps: User checks light/dark Library, About, tools, sheets/dialogs and Preview/Source; PDF/EPUB/fullscreen reading with the new control surfaces; TalkBack labels, font scaling, haptics off and low-memory/high-refresh behavior. Device contrast/appearance and iOS acceptance remain unverified. No additional assistant live-scroll/button AppFunctions were implemented by this UI-only task.

## 2026-11-12: Research-only review of supplied performance audit proposal

- Goal: Research the user's pasted optimization proposal without implementing application changes. Detailed deliverable: docs/research/2026-11-12-performance-audit-research-review.md.
- Decisions: Retain the audit categories but reject blanket sequence thresholds, all-collections stability replacements, all-native-calls-to-IO, universal flow/debounce policies and immediate copy/dead-code removal. Current generated Android bindings do use JNA; the proposal's fixed invocation timings are not EzFiles measurements. Recents are capped at 50 entries, content queries already debounce at 150 ms, and indexing/Tidy already have separate low-priority workers. Prioritize ownership/correctness, reader batch conversion, rendering, contention and memory lifetime before micro-optimization.
- Concrete candidate, not a measured defect: RustDocumentSession.nextBatch performs native work inside withContext(io) but maps the returned batch outside it; the ordinary Android caller is Main-scoped ReaderViewModel. Measure conversion separately before proposing implementation. No claim of a completed full-repository audit or new lag-free acceptance.
- Files read/searched for evidence (in addition to the supplied Pasted text.txt):
  - shared/build.gradle.kts
  - build.gradle.kts
  - gradle/libs.versions.toml
  - engine/Cargo.toml
  - engine/crates/document-ffi/Cargo.toml
  - shared/src/commonMain/kotlin/com/ezfiles/reader/data/RustDocumentReader.kt
  - shared/src/commonMain/kotlin/com/ezfiles/reader/data/RustImaging.kt
  - shared/src/commonMain/kotlin/com/ezfiles/reader/data/RustTidyEngine.kt
  - shared/src/commonMain/kotlin/com/ezfiles/reader/data/FullTextIndex.kt
  - shared/src/commonMain/kotlin/com/ezfiles/reader/data/EngineDocumentText.kt
  - shared/src/commonMain/kotlin/com/ezfiles/reader/data/LibraryContentSearch.kt
  - shared/src/commonMain/kotlin/com/ezfiles/reader/data/RecentDocumentsStore.kt
  - shared/src/commonMain/kotlin/com/ezfiles/reader/domain/Document.kt
  - shared/src/commonMain/kotlin/com/ezfiles/reader/domain/Frecency.kt
  - shared/src/commonMain/kotlin/com/ezfiles/reader/application/DocumentReadingService.kt
  - shared/src/commonMain/kotlin/com/ezfiles/reader/presentation/ReaderViewModel.kt
  - shared/src/commonMain/kotlin/com/ezfiles/reader/presentation/library/LibraryViewModel.kt
  - shared/src/commonMain/kotlin/com/ezfiles/reader/presentation/scan/ScanFilters.kt
  - shared/src/androidMain/kotlin/com/ezfiles/app/AndroidAppDependencies.kt
  - shared/src/androidMain/kotlin/com/ezfiles/reader/platform/LegacyPdfText.kt
  - shared/build/generated/uniffi/androidMain/kotlin/com/ezfiles/engine/ffi/document_ffi.android.kt (targeted import/binding check only, no generated edits)
  - docs/performance-implementation.md
- Research: Primary Kotlin/coroutines, Android Compose/JNI/rendering/R8/benchmarking, Gobley and Kotlin/Native sources are linked beside the associated claims in the report. The plan distinguishes current source observations, historical emulator evidence and future measurement proposals.
- Files changed: the research report and NOTES.md only. Validation: Markdown local-link existence and git diff --check; no application build, unit/device tests, benchmarks, dependencies, installation or phone actions. Next steps: user reviews the proposed priority order; runtime measurement/implementation remain unperformed and testing remains user-led.

## 2026-11-12: Screenshot correction for ring shadows and covered reading text

- Goal: Fix the supplied screenshots showing large concentric backgrounds around floating controls, book menus over paragraphs and Preview/Source covering content.
- Replaced decisions: The expanded filled-outline shadow introduced/strengthened in 8a7277c is replaced by low platform elevation with restrained shadow colours. Four widening filled shapes were producing hard rings on white paper. Shared tint/reflection/edges remain; no backdrop blur dependency or capture is added. The former over-page book menus and Preview/Source placement are replaced by reserved control rows outside the text viewport.
- Layout decisions: Book menus live before/after BookPage in its Column; passive chapter/progress rows hide while menus are open, removing duplicate labels. Parent ReaderScreen already handles safe areas, so book menus no longer add system insets again. Preview/Source sits on the app background below the document, keeping both actions without a white WebView footer or buttons covering paragraphs. This spends a small amount of viewport height to guarantee separation. Fullscreen still hides that dock; reading backgrounds, switching actions, haptics and annotations remain.
- Files read/changed:
  - shared/src/commonMain/kotlin/com/ezfiles/reader/presentation/theme/Surfaces.kt
  - shared/src/commonMain/kotlin/com/ezfiles/reader/presentation/ReaderScreen.kt
  - shared/src/commonMain/kotlin/com/ezfiles/reader/presentation/viewers/BookViewer.kt
- Additional files read/searched:
  - shared/src/commonMain/kotlin/com/ezfiles/reader/presentation/viewers/HtmlViewer.kt
  - shared/src/commonMain/kotlin/com/ezfiles/reader/presentation/viewers/HtmlViewerPolicy.kt
- Validation: Native/shared/app Android compilation passed in the combined packaging run. Final packaging/static-lint outcome is recorded with the paper-curl checkpoint below. No unit/runtime/device tests or personal-phone actions were run; the user continues to own testing.
- Next steps: User checks the exact three screenshot cases, text at the end of documents, normal/fullscreen books, menu open/close, keyboard/rotation and light/dark controls. Physical appearance/contrast and acceptance remain unverified.

## 2026-11-12: User-selected curled-paper EPUB turns

- Goal: Replace the plain page slide with the curled paper-edge option explicitly selected by the user, keeping the screenshot-driven reading-space corrections above.
- Replaced decision: The single-iframe scrolling-slide effect from 7a6b533 is replaced by stationary outgoing text clipped along a moving paper edge, with a curved back-paper strip and localized fold shading. Forward/back turns follow the drag; short/cancelled/multi-touch gestures return to rest. Reduced motion still commits navigation without animation. This is a lightweight paper-fold/curl treatment, not a claim of Kindle's exact rendering engine or measured physical-device smoothness.
- Preview/performance decisions: One transient sandboxed same-chapter preview can reveal adjacent text. Before creating it, eligibility checks cap chapter source at 256 KiB, DOM elements at 2,000, loaded images at 16 and summed natural image pixels at two million; audio/video/iframe/object/embed/canvas disqualify it. These are eligibility limits, not a whole-process RAM guarantee. The existing locked URL/base/resource/CSP path remains, author scripts are sandbox-blocked/removed and the preview is inert/noninteractive. No per-frame capture or whole-book clone. Large/media/fixed chapters and chapter boundaries retain the fold over the book theme without an adjacent-text preview.
- Ownership/cancellation: Keep the original active iframe for selection, links and annotations. Stale asynchronous preview callbacks check chapter/page/instance before use. Preview removal is wired to completion/cancellation, selection/relayout and page/document hiding. Stable committed text offsets are used during viewport relayout so opening/closing the newly reserved menu rows preserves the passage. Existing token-validated successful-turn haptics and RTL/vertical navigation rules remain.
- Files read/changed:
  - engine/crates/document-parsers/src/epub_book.rs
  - docs/performance-implementation.md
- Additional files read/searched:
  - shared/src/commonMain/kotlin/com/ezfiles/reader/presentation/viewers/HtmlViewerPolicy.kt
  - shared/src/commonMain/kotlin/com/ezfiles/reader/presentation/viewers/BookViewer.kt
- Validation so far: Extracted embedded JavaScript passed Node --check using the existing VS Code embedded Node runtime. Native/Android compilation, Share packaging and shared/app static lint passed before the final native bookmark-badge visibility guard; the final packaging result is recorded below. No unit/instrumentation/runtime/device tests or phone actions were performed; the user retains testing.
- Next steps: User checks the Malayalam book from the screenshot, slow and quick forward/back turns, cancelled/reversed swipes, chapter boundaries, text selection/notes/links, menu open/close/rotation anchor retention, reduced motion and haptics off. Appearance, first-turn preview latency, memory plateau and physical-device feel remain unverified. iOS paged EPUB remains an existing unimplemented adapter using Scroll fallback.

### Final screenshot/curl packaging checkpoint

- Passed after the final bookmark-badge guard: `:androidApp:assembleShare :shared:lintDebug :androidApp:lintDebug -Pezfiles.buildLabel=paper-curl-ui-20261112-r1` (BUILD SUCCESSFUL). Static lint reports zero errors, 89 shared/7 app existing warnings. Embedded JS syntax and git diff --check also passed. No unit/runtime/device tests, installation or phone actions were performed.
- Artifact: androidApp/build/outputs/apk/share/androidApp-share.apk, version 0.1.0-share-paper-curl-ui-20261112-r1 (1), existing application ID/signing selection. UI correction is saved separately from the EPUB curl feature; push both to feature/android17-appfunctions and preserve main/branches. Physical screenshot/gesture acceptance remains user-led, not established by compilation.

## 2026-11-12: Reader conversion on the existing worker

- Goal: Implement the concrete Main-thread conversion candidate from the research review, after verifying all previous work was committed. Initial tree was clean; previous UI/curl commits d9d637e and a337cca were already pushed.
- Decisions: Keep production document opening, native metadata lookup/domain wrapping, batch reading and block/span/shape mapping inside the existing reader I/O context. Check cancellation after native work before converting. Preserve session cleanup/adoption, error translation, outputs and limits; no new dispatcher, thread, dependency or FFI API. This removes conversion/native metadata work from the ordinary Main-scoped reader path but does not establish a measured speedup.
- Files read:
  - shared/src/commonMain/kotlin/com/ezfiles/reader/data/RustDocumentReader.kt
  - shared/src/commonMain/kotlin/com/ezfiles/reader/presentation/ReaderViewModel.kt
  - shared/src/androidUnitTest/kotlin/com/ezfiles/reader/data/RustEngineIntegrationTest.kt (existing coverage locations/scenarios only)
- Files changed: RustDocumentReader.kt; NOTES.md. Validation: shared/app Android compilation passed in the combined packaging/static run. Final result is recorded below after all targeted changes. No unit/runtime/device tests or benchmarks were run; user-led testing remains in force.
- Next steps: User checks styled DOCX/PPTX/text opening, subsequent batches, cancellation/close and source switching; later authorized traces should separate native reading, conversion and first usable content. Native cleanup ownership must remain intact in any later work.

## 2026-11-12: Bounded content-search result lookup

- Goal: Avoid building an entire-library identity map on every content query while preserving current-reference resolution and ranking.
- Decisions: Keep native query execution and hit-to-reference resolution in the existing query worker. Resolve only requested hit keys against a freshly read library snapshot; scan the complete snapshot to preserve last-wins duplicate identities. Keep original hit ordering and unavailable-file filtering. Expected scan time remains O(N + H); auxiliary lookup storage scales with H returned hits rather than N library entries (library UI requests at most 20 hits). Check cancellation every 256 references. No cross-query cache, new worker, debounce or flow-lifetime change.
- Files read/searched:
  - shared/src/commonMain/kotlin/com/ezfiles/reader/data/LibraryContentSearch.kt
  - shared/src/commonMain/kotlin/com/ezfiles/reader/data/FullTextIndex.kt
  - shared/src/commonMain/kotlin/com/ezfiles/reader/presentation/library/LibraryViewModel.kt
- Files changed: LibraryContentSearch.kt; NOTES.md. Validation: shared/app Android compilation and git diff --check passed during final packaging; final static/package outcome is recorded below. No tests/device actions/benchmarks ran.
- Next steps: User checks query changes, result ordering, disappearing files/folders and growing indexes. Allocation/frame-time improvements remain unmeasured; do not claim an O(H)-time search or a universal responsiveness gain.

## 2026-11-12: Sensitive-cache serialization on its existing I/O worker

- Goal: Remove cache JSON encoding from the sensitive-file hint's UI caller.
- Decisions: Retain the snapshot captured under the existing mutex; encode that snapshot inside the existing I/O storage block. Preserve cache data, mutex scope and write ordering with no extra dispatcher hop or policy change. Snapshot copying remains deliberate ownership protection.
- Files read/searched:
  - shared/src/commonMain/kotlin/com/ezfiles/reader/application/SensitiveCheck.kt
  - shared/src/commonMain/kotlin/com/ezfiles/reader/application/DocumentGlancer.kt
  - shared/src/commonMain/kotlin/com/ezfiles/reader/presentation/tidy/SensitiveFileHint.kt
- Files changed: SensitiveCheck.kt; NOTES.md. Validation: shared/app Android compilation passed in the combined packaging/static run; final outcome follows below. Existing runtime behavior/latency was not tested or benchmarked.
- Next steps: User checks sensitive-file hint/cache behavior and cancellation. Preserve the independent screenshot/security policy; this change does not add assistant content access or change findings.

## 2026-11-12: Remove redundant EPUB curl browser mutations

- Goal: Reduce work in the existing paper-curl animation without changing its fold geometry, shading, reading features or haptic/motion behavior.
- Decisions: Track the rendered preview destination; scroll it only when ready or when the target changes, rather than on every frame. Track visibility and hide a stale preview when a reversed gesture has no valid destination. Assign fold paint/radius/origin only when theme or direction changes; put the constant edge-shadow gradient in CSS. Reset all new markers in the existing release path. Existing preview bounds, cleanup, active-frame ownership, dynamic geometry and animation timing remain.
- Files read/changed:
  - engine/crates/document-parsers/src/epub_book.rs
  - docs/performance-implementation.md
- Validation: Extracted embedded JavaScript passed Node --check using the already-installed VS Code embedded runtime; git diff --check passed. Final Android/static results are recorded below. No browser/app runtime, unit/device tests or performance benchmarks ran.
- Next steps: User compares normal/reversed/cancelled paper turns, chapter/book boundaries, theme changes, reduced motion and rotation/menu resizing. Fewer repeated DOM mutations are established by the code; actual FPS, memory and battery gains remain unmeasured.

### Final targeted-performance packaging checkpoint

- Passed: `:androidApp:assembleShare :shared:lintDebug :androidApp:lintDebug -Pezfiles.buildLabel=performance-20261112-r1` (BUILD SUCCESSFUL, includes all four targeted changes). Static lint reports zero errors, 89 shared/7 app existing warnings. No app tests, benchmarks, installation, phone actions or data clearing occurred under the standing user-led testing instruction.
- Artifact: androidApp/build/outputs/apk/share/androidApp-share.apk, version 0.1.0-share-performance-20261112-r1 (1), existing application ID/signing selection. Save each targeted change in its own commit and push feature/android17-appfunctions, preserving main/branches. Earlier device/emulator measurements do not measure these changes; physical responsiveness, latency and memory acceptance remain pending.

## 2026-11-12: Uncommitted dedicated landscape UI preview

- Goal: Design and implement a separate landscape arrangement after considering its appearance and reading space. The user explicitly requested NO COMMIT. This supersedes the earlier standing commit/push workflow for this task: do not stage, commit or push these changes. HEAD remains 695257f; initial worktree was clean.
- Design: Wide landscape uses horizontal space for library navigation and reader controls rather than stretching a portrait stack. The layout sketch/rules are in docs/research/2026-11-12-landscape-ui-layout.md. Keep normal portrait arrangements, current glass/typography/haptics, no extra dependency or orientation lock. Window size (600 dp minimum width and wider than tall) selects side layouts; height below 480 dp reduces shared header padding. Narrow windows keep stacked controls.
- Library: Same list/search composition positions with 96 dp start clearance for an 88 dp scrollable rail; Open file plus all navigation actions remain. End clearance becomes 24 dp instead of the portrait 172 dp floating dock. Compact header/search; portrait dock retained. Bookmarks receive the same rail clearance.
- Reader: New ReaderPaneLayout keeps one reading composition slot and measures controls below or beside it. Preview/Source uses a 136 dp vertical segment region in landscape. EPUB menu becomes a scrollable 232 dp side panel while open; closing it returns the width to the page. The existing committed-offset relayout/paper curl remains. PDF gets a scrollable 192 dp rail with thumbnails, pagination, zoom and view modes; annotation controls remain full-width below the page. Search/fullscreen and existing state/resource owners remain.
- Other UI: Two-column Tools picker in wide landscape; shared top/search bars use shorter padding on short windows without shrinking icon targets. Long EzDialog bodies scroll while actions remain visible; three already-scrolling bodies explicitly opt out of the additional wrapper. Camera/crop/editor/About/media were inspected: their existing weighted/scroll/aspect-fit layouts remain; no untested redesign of those native adapters was introduced.
- Files read/changed:
  - shared/src/commonMain/kotlin/com/ezfiles/reader/presentation/components/ReaderPaneLayout.kt (new)
  - shared/src/commonMain/kotlin/com/ezfiles/reader/presentation/components/Components.kt
  - shared/src/commonMain/kotlin/com/ezfiles/reader/presentation/components/EzChrome.kt
  - shared/src/commonMain/kotlin/com/ezfiles/reader/presentation/library/LibraryScreen.kt
  - shared/src/commonMain/kotlin/com/ezfiles/reader/presentation/library/LandscapeLibraryRail.kt (new)
  - shared/src/commonMain/kotlin/com/ezfiles/reader/presentation/ReaderScreen.kt
  - shared/src/commonMain/kotlin/com/ezfiles/reader/presentation/viewers/BookViewer.kt
  - shared/src/commonMain/kotlin/com/ezfiles/reader/presentation/viewers/PdfReader.kt
  - shared/src/commonMain/kotlin/com/ezfiles/reader/presentation/viewers/PdfPageScrubber.kt
  - shared/src/commonMain/kotlin/com/ezfiles/reader/presentation/create/CreateTools.kt
  - shared/src/commonMain/kotlin/com/ezfiles/reader/presentation/about/CrashReportPrompt.kt
  - shared/src/commonMain/kotlin/com/ezfiles/reader/presentation/viewers/PdfAnnotationUi.kt
  - shared/src/commonMain/kotlin/com/ezfiles/reader/presentation/viewers/SignatureUi.kt
- Additional files read/searched:
  - shared/src/commonMain/kotlin/com/ezfiles/app/App.kt
  - shared/src/commonMain/kotlin/com/ezfiles/reader/presentation/ReaderUiState.kt
  - shared/src/commonMain/kotlin/com/ezfiles/reader/presentation/viewers/MediaPlayerView.kt
  - shared/src/commonMain/kotlin/com/ezfiles/reader/presentation/editor/TextEditorScreen.kt
  - shared/src/commonMain/kotlin/com/ezfiles/reader/presentation/scan/ScanComponents.kt
  - shared/src/commonMain/kotlin/com/ezfiles/reader/presentation/create/ScanToPdfScreen.kt
  - shared/src/commonMain/kotlin/com/ezfiles/reader/presentation/create/TextToPdfScreen.kt
  - shared/src/commonMain/kotlin/com/ezfiles/reader/presentation/pdftools/PdfToolHost.kt
  - shared/src/commonMain/kotlin/com/ezfiles/reader/presentation/pdftools/SignPdfScreen.kt
  - shared/src/commonMain/kotlin/com/ezfiles/reader/presentation/about/AboutScreens.kt
  - Installed Compose UI 1.11.1 WindowInfo.kt source: verified containerDpSize API and intended window-breakpoint use.
- Validation: `:androidApp:assembleShare :shared:lintDebug :androidApp:lintDebug -Pezfiles.buildLabel=landscape-preview-20261112-r1` passed (BUILD SUCCESSFUL). Final static lint and metadata were inspected. No unit/runtime/device tests, rotation/keyboard checks, screenshots, benchmarks, phone actions or installation were performed; user-led testing remains. No staging, commit or push occurred; index check exit was zero.
- Artifact: androidApp/build/outputs/apk/share/androidApp-share.apk, version 0.1.0-share-landscape-preview-20261112-r1 (1), existing application ID/signing selection. Keep the source and documentation uncommitted for review.
- Next steps/open gates: User rotates portrait/landscape/portrait with active reading/search/editing, checks passage/scroll/selection retention, side-control reachability at large font scales, safe areas/keyboard/fullscreen/annotations/share/print, EPUB curl, PDF thumbnails and sensitive/crash/signature dialogs. Native host/activity recreation, physical appearance and iOS acceptance remain unverified; foldable hinge/posture-specific behavior is not implemented. Source-level slot stability is not a promise that Activity/process recreation preserves a native view instance.

## 2026-11-12: Floating TXT modes and landscape space refinement (uncommitted)

- Goal: Finish the floating Preview/Source request and refine landscape around usable file space, a centered compact menu, independent Info and safe areas. The explicit NO COMMIT instruction still applies: no staging, commit or push.
- Replaced the initial landscape library rail sizing/placement above with a centered 80 dp navigation capsule within 112 dp clearance. Independent 48 dp Open file and Info buttons sit below it with an 8 dp gap. The group can scroll in short windows. Library adds vertical safe-drawing insets to its existing horizontal safe-area/cutout protection; title/search share a row; folders retain their actions without an extra heading; landscape section headings become compact while retaining counts/progress/Clear. Portrait retains its dock.
- Replaced the initial Preview/Source footer/landscape side rail above with a centered floating glass pill: 40 dp visible height, 48 dp touch targets, centered labels and font-scale-aware width. It hides on Compose/native HTML scrolling or passive drag observation, then returns after 1.1 seconds of inactivity. Scroll frames update a plain monotonic timestamp rather than launching per-frame coroutines. Reduced motion removes transitions; fullscreen retains its hiding policy. Hiding does not resize the page. Native HTML scroll observation does not consume gestures.
- TXT/.text now offers normal reading Preview and numbered Source using the existing streamed decoder/code renderer, with an internal txt-source dialect. Original file identity, parser limits, indexing/default text reads and authored HTML styles remain. TXT Preview uses a centered maximum-680 dp reading column with left-aligned paragraphs and existing reading typography; Source retains Unicode, blank lines and indentation. Unrelated log/code formats remain unchanged.
- Files read/changed for this refinement:
  - shared/src/commonMain/kotlin/com/ezfiles/reader/presentation/library/LibraryScreen.kt
  - shared/src/commonMain/kotlin/com/ezfiles/reader/presentation/library/LandscapeLibraryRail.kt
  - shared/src/commonMain/kotlin/com/ezfiles/reader/presentation/components/FloatingReaderModeSwitch.kt (new)
  - shared/src/commonMain/kotlin/com/ezfiles/reader/presentation/components/ReaderScrollActivity.kt (new)
  - shared/src/commonMain/kotlin/com/ezfiles/reader/presentation/ReaderScreen.kt
  - shared/src/androidMain/kotlin/com/ezfiles/reader/presentation/viewers/HtmlViewerPage.android.kt
  - shared/src/commonMain/kotlin/com/ezfiles/reader/presentation/viewers/BlockListViewer.kt
  - shared/src/commonMain/kotlin/com/ezfiles/reader/presentation/ReaderUiState.kt
  - shared/src/commonMain/kotlin/com/ezfiles/reader/data/RustDocumentReader.kt
  - engine/crates/document-parsers/src/text.rs
  - shared/src/commonTest/kotlin/com/ezfiles/reader/presentation/ReaderViewModelTest.kt
  - shared/src/androidUnitTest/kotlin/com/ezfiles/reader/data/RustEngineIntegrationTest.kt
  - docs/research/2026-11-12-landscape-ui-layout.md
- Regression scenarios prepared for TXT roundtrip, unrelated-format exclusions and Unicode/blank-line/indentation source parsing. They have NOT run under the standing user-led testing instruction. No device/runtime/browser tests, installation, phone actions, data clearing or benchmarks performed. Physical appearance and gestures remain user acceptance gates.
- Validation command: `:androidApp:assembleShare :shared:lintDebug :androidApp:lintDebug -Pezfiles.buildLabel=floating-text-landscape-20261112-r1`; packaging/static results follow. `git diff --check` passed; index is empty and HEAD remains 695257f.
- Next steps: User checks landscape system-bar/cutout clearance, all four menu destinations, independent Info/Open actions, file space at large font sizes/keyboard, TXT Preview/Source, pill hiding/returning on scroll/fling, fullscreen and portrait restoration. Existing landscape PDF/book/tool changes remain part of this uncommitted preview; iOS/device acceptance remains pending.

### Floating TXT / landscape packaging result

- Fixed a Compose implicit ColumnScope AnimatedVisibility resolution error by explicitly calling the top-level overlay animation; the final rerun passed `:androidApp:assembleShare :shared:lintDebug :androidApp:lintDebug -Pezfiles.buildLabel=floating-text-landscape-20261112-r1` (BUILD SUCCESSFUL, 3m 5s). Static lint: zero errors, 89 shared/7 app existing warnings. `git diff --check` passed. No tests or device actions ran.
- Artifact: androidApp/build/outputs/apk/share/androidApp-share.apk, version 0.1.0-share-floating-text-landscape-20261112-r1 (1), com.ezfiles, existing Share signing selection. Verified index empty and HEAD 695257f unchanged; no staging, commit or push. User visual/interaction acceptance remains pending.

## 2026-11-12: Compact-height landscape PDF redesign (uncommitted)

- Goal: Correct the oversized, repetitive PDF side controls shown in the user's screenshot using researched Google adaptive-design principles. Continue preserving every previous uncommitted change; NO COMMIT/staging/push remains in force.
- Research: Google's window-size-class guidance explicitly identifies height below 480 dp as compact and warns that landscape phones can be unsuitable for two panes despite medium width. Adaptive layout guidance recommends width then height, bounded component widths and changing presentation/revealing secondary controls. Compose accessibility guidance recommends 48 dp minimum targets. Primary sources are linked in docs/research/2026-11-12-landscape-ui-layout.md; these support the principles, not a mandated PDF toolbar design.
- Replaced the earlier PDF 192 dp side-panel rule on short landscape windows with one full-width document pane and a centered single-row glass toolbar (bounded to 560 dp, 48 dp button allocations, 4 dp outer vertical spacing). Removed persistent duplicate page count/slider/thumbnail controls only in this mode. Previous/page jump/next and zoom stay direct; existing Go to page retains slider/typed seeking; existing Grid retains thumbnail browsing. Existing Single/Two-up/Grid choices open from the view-options menu. Large text can scroll the compact toolbar horizontally instead of clipping its controls.
- Taller landscape retains its existing supporting panel; portrait, annotation tools, search/fullscreen, PDF state/cache owners and gesture handling remain. The existing ReaderPaneLayout keeps the document composition slot stable when layout changes. Appearance and rotation behavior still need user device acceptance.
- Files read:
  - NOTES.md
  - shared/src/commonMain/kotlin/com/ezfiles/reader/presentation/viewers/PdfReader.kt
  - shared/src/commonMain/kotlin/com/ezfiles/reader/presentation/viewers/PdfPageScrubber.kt
  - shared/src/commonMain/kotlin/com/ezfiles/reader/presentation/components/ReaderPaneLayout.kt
  - shared/src/commonMain/kotlin/com/ezfiles/reader/presentation/components/EzChrome.kt
  - docs/research/2026-11-12-landscape-ui-layout.md
- Files changed this task: PdfReader.kt; docs/research/2026-11-12-landscape-ui-layout.md; NOTES.md. No dependencies added.
- Validation command: `:androidApp:assembleShare :shared:lintDebug :androidApp:lintDebug -Pezfiles.buildLabel=compact-landscape-pdf-20261112-r1`; final result follows. No unit/runtime/device tests, installation, phone actions or benchmarks requested/performed; testing remains with the user.
- Next steps: User checks actual short-landscape fit, page jump slider/typing, next/previous, zoom/pinch, Single/Two-up/Grid, large fonts, safe areas, keyboard/search, fullscreen and portrait restoration. Do not claim measured performance or visual acceptance from compilation.
- Final validation: packaging and both static lint tasks passed (BUILD SUCCESSFUL, 2m 13s); shared/app lint zero errors, 89/7 existing warnings. `git diff --check` passed. Artifact: androidApp/build/outputs/apk/share/androidApp-share.apk, version 0.1.0-share-compact-landscape-pdf-20261112-r1 (1). Index remains empty, HEAD 695257f; no staging/commit/push or device tests. Physical acceptance remains pending.

## 2026-11-12: Team contacts and Android confirmed duplicate deletion

- Goal: Apply the owner's supplied team details and permission to support Android duplicate deletion; research user-consented Firebase crash uploads. Preserve all preceding uncommitted changes and the NO COMMIT instruction.
- About: KISHORE K / kishoreabhi333@gmail.com; Robin Isac / robinisacsharon@gmail.com. Mailto contact links use the existing team cards; no roles or biographies were invented. Blank roles are hidden. The existing crash-sharing/feedback destination now uses Kishore's address instead of TODO@example.com.
- Android grants: library folder selection requests write as well as read through SAF. Persist read/write when granted, fall back to read-only; removing a folder releases whichever persisted flags it holds. Existing read-only folders must be selected again, without removing their stored library entry. ChosenFolderDocuments.addFolder was inspected and takes the grant before replacing an existing entry.
- Deletion: scans do not delete. Only extra copies in exact-duplicate groups have a Delete action; the first is labelled Kept copy. Similar documents/photos/collections have none. Confirmation explains original-folder permanent deletion and re-comparison. TidyService serializes deletion, refuses while a scan is running, rejects the kept copy/non-duplicate refs and calls a platform duplicate-specific port. Android requires a persisted write grant for that tree, provider FLAG_SUPPORTS_DELETE, a non-directory target and distinct underlying provider/document identities. Both files are freshly byte-compared using two 32 KiB buffers and handling short reads before invoking DocumentsContract deletion. Failure leaves results unchanged and explains re-grant/rescan. No device files have been deleted by this coding session. SAF does not offer an atomic compare-and-delete transaction; external concurrent edits remain a provider limitation.
- Regression scenarios prepared (not run): kept-copy rejection, stale changed-copy rejection; existing deletion test fake now implements byte/content revalidation. No runtime/unit/device tests, installation or benchmarks performed under the standing user-led testing instruction.
- Firebase: owner explicitly approved Crashlytics SDK/Gradle-plugin dependencies and said they will provide the project. This authorization persists; do not ask it again. Project configuration is missing, so no Firebase SDK/plugins, networking or upload UI were added/activated in this task. Cloud upload remains pending, not complete. The current manifest removes network/transport components. Research doc explains opt-in defaults/revocation, SDK pending-report scope, identifiers/payload disclosure, scrubbed local preview differences and offline-build preservation; project wiring and cloud acceptance remain required. Do not invent project IDs/credentials or claim the SDK's raw crash payload is scrubbed by our local formatter.
- Files read/changed:
  - shared/src/commonMain/kotlin/com/ezfiles/reader/presentation/about/AboutContent.kt
  - shared/src/commonMain/kotlin/com/ezfiles/reader/presentation/about/AboutScreens.kt
  - shared/src/commonMain/kotlin/com/ezfiles/reader/crash/CrashReporting.kt
  - shared/src/androidMain/kotlin/com/ezfiles/reader/data/AndroidDocumentTrees.kt
  - shared/src/androidMain/kotlin/com/ezfiles/reader/data/AndroidTidyFiles.kt
  - shared/src/androidMain/kotlin/com/ezfiles/reader/platform/FolderPicker.android.kt
  - shared/src/commonMain/kotlin/com/ezfiles/reader/application/TidyPorts.kt
  - shared/src/commonMain/kotlin/com/ezfiles/reader/application/TidyService.kt
  - shared/src/commonMain/kotlin/com/ezfiles/reader/presentation/tidy/TidyUpScreen.kt
  - shared/src/commonMain/kotlin/com/ezfiles/reader/presentation/library/LibraryScreen.kt
  - shared/src/commonTest/kotlin/com/ezfiles/reader/tidy/TidyServiceTest.kt
  - README.md
  - docs/research/2026-11-12-consented-firebase-crash-reporting.md (new)
- Additional reads: CrashReports.kt; CrashReports.android.kt; CrashReportPrompt.kt; AndroidDocumentIdentity.kt; Document.kt; ChosenFolderDocuments.kt; androidApp/build.gradle.kts; root build.gradle.kts; gradle/libs.versions.toml; androidApp/src/main/AndroidManifest.xml. Firebase/Android primary-source references were consulted; no secrets/configuration files were accessed.
- Validation command: `:androidApp:assembleShare :shared:lintDebug :androidApp:lintDebug -Pezfiles.buildLabel=team-tidy-20261112-r1`; packaging/static results follow. User checks About mail links/feedback/crash sharing; duplicate confirmation/cancel/kept-copy behavior; read-only, revoked, unsupported, cloud and re-granted folder providers; changed/deleted copies and large files. Firebase integration awaits the project configuration.
- Final result: the first build passed in 2m 59s; final packaging/static-lint rerun after confirmation/kept-label edits passed in 2m 12s. Both lint reports have zero errors, with 89 shared/7 app existing warnings. `git diff --check` passed. APK: androidApp/build/outputs/apk/share/androidApp-share.apk, version 0.1.0-share-team-tidy-20261112-r1 (1), existing Share signing. No unit/runtime/device tests, deletion, uploads or installations were performed.
- Checkout checkpoint: HEAD is now 4896e4b (the earlier landscape changes were committed outside this task); this replaces the earlier 695257f baseline reference for future work. This task executed no staging/commit/push commands, its new changes remain uncommitted and the index is empty. No existing commit was modified or reverted.

## 2026-11-12: Android photo/video row previews and Firebase registration guidance

- Goal: Implement the approved media thumbnails while keeping the library light and preserving existing row actions; provide Android-only Firebase registration guidance. Preserve previous edits and the NO COMMIT instruction. No new dependencies added.
- UI: Library/Recent FileCards and content-result tiles use a shared MediaPreviewTile. Photos/videos get a stable 56 dp clipped preview area with the existing file icon while loading/unavailable; videos add a small high-contrast play badge and duration when available. Other file types retain their existing icons. Filename, metadata, row click/long press, More/share/print/selection/history controls and glass cards remain. Previews do not autoplay or become separate click targets. No new library grid layout was introduced.
- Loader: AndroidMediaThumbnails is activity-owned and provided through a common composition local; unsupported platforms use the default no-op/icon fallback. Image work uses targeted ImageDecoder (API 28+) or sampled BitmapFactory with the existing declared-size/sample bounds and EXIF transforms (API 26/27). Video work tries the provider thumbnail, then a scaled keyframe on API 27+; API 26 video falls back to its icon when the provider has no thumbnail. Duration extraction uses a closed/released MediaMetadataRetriever. All work runs via runInterruptible on Dispatchers.IO, with a two-permit semaphore; visible/composed nearby lazy rows request previews, not the whole library.
- Cache: 8 MiB bitmap-weighted LRU, keyed by URI, reported size/modification time, type and requested pixels (64–256 px). Failure backoff is 30 seconds with at most 128 negative entries. No disk cache, full media copies, new storage permission, networking library or bitmap recycling of live cached/UI images. Existing MemoryPressure callback and activity disposal evict entries; a generation check prevents in-flight work from repopulating after eviction. Native/provider work may not immediately honor thread interruption; concurrency remains bounded. Providers without changed size/time metadata may retain an old cached preview until eviction/recreation; no device latency/FPS claim is made.
- Firebase guidance: verified `applicationId = com.ezfiles` in androidApp/build.gradle.kts, no variant suffix; com.ezfiles.android is only the namespace. Owner registers an Android app in Firebase, optional nickname EzFiles Android, and places the downloaded Android client file at androidApp/google-services.json. No iOS registration is needed for the current request. Guide now includes official registration/setup references. Project configuration remains pending; SDK/plugin dependency approval from the prior task persists. No cloud upload or Firebase dependency/plugin/config file was added or enabled in this task, and no secret/config contents were read.
- Files read:
  - NOTES.md
  - shared/src/commonMain/kotlin/com/ezfiles/reader/presentation/components/Components.kt
  - shared/src/commonMain/kotlin/com/ezfiles/reader/presentation/library/LibraryScreen.kt
  - shared/src/commonMain/kotlin/com/ezfiles/reader/presentation/library/LibrarySearchUi.kt
  - shared/src/commonMain/kotlin/com/ezfiles/reader/presentation/library/LibraryViewModel.kt
  - shared/src/commonMain/kotlin/com/ezfiles/reader/presentation/library/FileType.kt
  - shared/src/commonMain/kotlin/com/ezfiles/reader/platform/MemoryPressure.kt
  - shared/src/commonMain/kotlin/com/ezfiles/reader/platform/ImageSupport.kt
  - shared/src/commonMain/kotlin/com/ezfiles/reader/platform/PhotoBytes.kt
  - shared/src/androidMain/kotlin/com/ezfiles/reader/platform/PhotoBytes.android.kt
  - androidApp/src/main/kotlin/com/ezfiles/android/MainActivity.kt
  - androidApp/src/main/kotlin/com/ezfiles/android/EzFilesApplication.kt
  - shared/src/commonMain/kotlin/com/ezfiles/app/App.kt
  - shared/src/commonMain/kotlin/com/ezfiles/reader/presentation/theme/EzIcons.kt
  - androidApp/build.gradle.kts
  - README.md
  - docs/research/2026-11-12-consented-firebase-crash-reporting.md
- Files changed this task: MainActivity.kt; Components.kt; LibraryScreen.kt; LibrarySearchUi.kt; README.md; Firebase guidance doc; NOTES.md; new common platform/MediaThumbnails.kt, common components/MediaPreviewTile.kt and Android platform/AndroidMediaThumbnails.kt.
- Primary API references checked: developer.android.com/reference/android/content/ContentResolver (loadThumbnail), developer.android.com/reference/android/media/MediaMetadataRetriever (scaled frame API 27), developer.android.com/reference/android/graphics/ImageDecoder; firebase.google.com/docs/android/setup and /docs/android/google-services-plugin-and-file. No providers/devices/accounts were accessed.
- Validation command: `:androidApp:assembleShare :shared:lintDebug :androidApp:lintDebug -Pezfiles.buildLabel=media-thumbnails-20261112-r1`; final results follow. No unit/runtime/device tests, installation or benchmarks were performed under the standing user-led testing instruction.
- Next steps: User checks large/rotated/animated images, HEIC/AVIF support per device, short/long/rotated/corrupt videos, duration, fast scrolling, Recent/filter/search transitions, modified files, revoked/unavailable providers, landscape, large font scales, background/reopen and all original row actions. iOS thumbnails remain icon fallbacks. Supply the Android Firebase client configuration to proceed with the pending consent-based integration.
- Final validation: initial build passed in 2m 49s. Addressed the new KTX scaling warning with existing Bitmap.scale; documented a file-scoped ExifInterface lint suppression for the intentional platform-only API 26/27 fallback (modern ImageDecoder path handles orientation without that reader), and made malformed legacy EXIF fall back to normal orientation. Final packaging/both static lint tasks passed in 1m 33s, zero errors with 89 shared/7 app existing warnings. `git diff --check` passed, index empty, HEAD 4896e4b unchanged. No staging/commit/push, tests, device actions, installation, benchmarks or cloud uploads occurred.
- Artifact: androidApp/build/outputs/apk/share/androidApp-share.apk, version 0.1.0-share-media-thumbnails-20261112-r1 (1), existing Share signing/application ID. This build also includes the preceding uncommitted team/Tidy work. Physical scrolling/appearance/provider compatibility and Firebase integration remain unverified/pending as described above.

## 2026-11-12: Supplied Android Firebase configuration and optional consented diagnostics

- Goal: Use the owner's C:/Users/kisho/Downloads/google-services.json for Android integration. Replaces the earlier pending-project status above: the client was validated for exactly one com.ezfiles registration and copied (not moved) to androidApp/google-services.json. Values were not printed, stored in docs or exposed in tool output. The configuration is Git-ignored; no service-account/private key was requested or read. Existing dependency approval was used without asking again. No commit/staging/push authorized.
- Build isolation: `-Pezfiles.firebaseCrashReports=true` selects the Firebase provider/source overlay, Google services plugin 4.5.0, Crashlytics plugin 3.0.8, BoM 35.1.0 and SDK. The default build selects a no-op provider, no Crashlytics dependency and no network permissions. Pinned versions came from Firebase's official Android setup/release docs. Transport runtime/CCT 5.0.0 already bundled with this SDK are explicitly exposed as compile APIs for the privacy filter; no extra analytics/image/backend libraries were added. No iOS Firebase integration or NDK crash dependency was added.
- Consent: common CloudCrashReporting port/default-off no-op and an About glass card. Enabling presents diagnostic-data/installation-ID disclosure and saves consent off the UI thread with checked commit success; activation is on a later process launch. FirebaseInitProvider is removed for both profiles and default Firebase/Crashlytics collection is false. If consent is false on startup, Firebase is never initialized by the app. Initialization failure preserves the local recorder and app startup. On opt-out, future exception forwarding bypasses the SDK, SDK collection is disabled, pending SDK deletion is requested and report transport checks refusal before sending. Restart fully unloads initialized SDK components; already-started/sent network requests cannot be recalled. Existing local reports remain manual-share only, not retroactively uploaded.
- Local crash ownership: installCrashReporter now returns AndroidCrashRecorder. The local recorder is installed first, captures the SDK's later handler, and restores itself as the outer handler. It writes the original through the existing scrubbed local formatter, then forwards only a scrubbed bounded exception graph when consent is active. A ThreadLocal re-entry guard avoids duplicate writes/handler recursion when the SDK calls its prior handler. Original graphs are not mutated; source frames remain for mapping. Causes are depth/count bounded with cycle detection and limited suppressed nodes. SDK reports have additional SDK-managed diagnostic fields and differ from the local preview. No document attachment/personal custom ID/Analytics was added.
- Shared transport privacy: ML Kit and Crashlytics use the same Google CCT backend. Simply restoring it would enable OCR telemetry and initially omitted backend metadata/scheduler permissions due the prior main-manifest removal markers. Replaced the diagnostics overlay with explicit non-exported components, BIND_JOB_SERVICE and one reflected ConsentedCrashBackendFactory. The factory drops every event except FIREBASE_CRASHLYTICS_REPORT, checks saved consent again at send time and delegates allowed requests to CctBackendFactory. Reflected factory names/constructor are retained by R8. Event name verified in Firebase's primary sender source; backend signatures/manifests inspected from resolved 5.0.0 artifacts. Future SDK/transport upgrades must revalidate this adapter and allowlist.
- Local builds disable every uploadCrashlytics* task. Mapping IDs/resources are generated locally, but mapping/symbol uploads remain an explicit release step. The build log verified uploadCrashlyticsMappingFileShare SKIPPED. No Firebase console/REST writes, deployment, test crashes, report uploads, installation or device actions were performed. Actual cloud receipt remains pending; readable obfuscated cloud stacks also need the exact matching mapping uploaded later.
- Regression scenarios prepared (not run): scrubbed causes/suppressed messages with original graph/frame preservation, and cyclic graph bounding. Device/user acceptance must cover default-off networking, enable/restart, SDK/local handler chaining, original report sharing, opt-out/restart, queued-report refusal, OCR telemetry filtering, offline retries and actual project receipt. Native signal/NDK coverage is not implemented.
- Files read/changed:
  - .gitignore
  - gradle/libs.versions.toml
  - build.gradle.kts
  - androidApp/build.gradle.kts
  - androidApp/proguard-rules.pro
  - androidApp/src/main/AndroidManifest.xml
  - androidApp/src/main/kotlin/com/ezfiles/android/EzFilesApplication.kt
  - androidApp/src/main/kotlin/com/ezfiles/android/MainActivity.kt
  - shared/src/androidMain/kotlin/com/ezfiles/reader/platform/CrashReports.android.kt
  - shared/src/commonMain/kotlin/com/ezfiles/reader/platform/CrashReports.kt
  - shared/src/commonMain/kotlin/com/ezfiles/reader/presentation/about/AboutScreens.kt
  - shared/src/commonMain/kotlin/com/ezfiles/reader/presentation/about/AboutContent.kt
  - README.md
  - docs/research/2026-11-12-consented-firebase-crash-reporting.md
- New files: androidApp/src/firebase/AndroidManifest.xml; androidApp/src/firebase/kotlin/com/ezfiles/android/CrashReportingProvider.kt; androidApp/src/firebase/kotlin/com/ezfiles/android/ConsentedCrashBackendFactory.kt; androidApp/src/offline/kotlin/com/ezfiles/android/CrashReportingProvider.kt; androidApp/src/main/kotlin/com/ezfiles/android/CrashReportingSetup.kt; shared/src/commonMain/kotlin/com/ezfiles/reader/platform/CloudCrashReporting.kt; shared/src/commonMain/kotlin/com/ezfiles/reader/presentation/about/CloudCrashReportingCard.kt; shared/src/androidUnitTest/kotlin/com/ezfiles/reader/platform/CloudCrashSanitizationTest.kt. Additional reads: settings.gradle.kts; CrashReportPrompt.kt; CrashReportingTest.kt; provided Android client config (package validation only, no output); primary Firebase setup/custom-report/SDK-source documentation and resolved transport artifact metadata/signatures.
- Validation commands: `:androidApp:assembleShare :shared:lintDebug :androidApp:lintDebug -Pezfiles.firebaseCrashReports=true -Pezfiles.buildLabel=firebase-optin-20261112-r1`; default offline verification uses the same tasks without the Firebase property and a distinct build label. First Firebase run failed lint because transport service classes were not exposed on its compile classpath; direct declarations of the existing SDK transitives and corrected declarations fixed that. Corrected Firebase run passed in 2m 44s. Final results/artifact separation follow.
- Final builds: Firebase package/static checks passed again in 1m 25s after documenting the intentional checked SharedPreferences.commit API (KTX edit returns Unit, so its lint suggestion would lose consent-write failure detection). Default offline package/static checks passed in 1m 42s. Both lint reports have zero errors; shared has 91 warnings (prior 89 plus dependency/Gradle update notifications), app has its previous 7. No runtime/unit/device tests ran; prepared sanitizer regression scenarios are unexecuted.
- Static artifact audit: diagnostics merged manifest has collection=false and no FirebaseInitProvider, exactly the consent-filtered backend metadata, and the reflected factory descriptor survives R8 in DEX. Default offline merged manifest has no INTERNET/ACCESS_NETWORK_STATE and no FirebaseInitProvider; its APK has no FirebaseCrashlytics class. Google services processing matched the supplied client. uploadCrashlyticsMappingFileShare was SKIPPED, not executed.
- Benchmark preservation: read androidApp/src/benchmark/AndroidManifest.xml; added androidApp/src/firebase/benchmark-manifest.xml with the same diagnostics rules plus the existing profileable android:shell=true. Build configuration selects it only for the Firebase benchmark variant, preserving the default benchmark manifest. `:androidApp:processBenchmarkMainManifest :androidApp:processShareMainManifest -Pezfiles.firebaseCrashReports=true` passed in 7s. Inspected current-task merged_manifest outputs to verify profiling, network scoping and filtered backend; the later processBenchmarkManifest output was a stale prior-build artifact and was not used as final evidence. No actual benchmark or device command ran.
- Named artifacts (APK folders are ignored build output): androidApp/build/outputs/diagnostics/ezfiles-firebase-optin.apk, version 0.1.0-share-firebase-optin-20261112-r1 (1), 19.66 MiB; androidApp/build/outputs/diagnostics/ezfiles-offline.apk, version 0.1.0-share-offline-verified-20261112-r1 (1), 19.45 MiB. Matching Firebase APK R8 mapping preserved at androidApp/build/outputs/diagnostics/firebase-optin-mapping.txt. Runtime/startup/memory overhead remains unmeasured. The standard outputs/apk/share/androidApp-share.apk currently holds the offline package; use the named diagnostics APK for the consent switch.
- Git checks: git diff --check passed; index empty; HEAD 4896e4b unchanged; supplied client file ignored. No staging, commit or push. Remaining acceptance: device opt-in/restart/opt-out and handler-chain behavior, OCR transport filtering on a real run, exact mapping upload and actual Firebase receipt. Those gates are not established by compilation, manifest inspection or DEX inspection.

## 2026-11-12: Preserved architecture decision history before documentation consolidation

The owner explicitly clarified that old documentation pages should be deleted and replaced by one feature document. The decision text below is preserved verbatim as a historical record before deleting its original page. Its links/source claims belong to that original snapshot; current app behavior is in docs/FEATURES.md. This preserves the append-only decision requirement without retaining a second feature guide.

````text
# Architecture decision records

Records retain their original context; older capability restrictions may be superseded. Use the [format matrix](../format-support-matrix.md) and [current status](../implementation-status.md) for present behavior. ADR-020 refines the permission policy without deleting earlier decisions.

Each record states the context, the alternatives considered, the decision, why, its trade-offs and its consequences. Records are append-only: a reversed decision gets a new record that supersedes the old one.

| ADR | Decision | Status |
|---|---|---|
| [001](#adr-001-overall-architecture) | Modular Clean + Hexagonal ports + MVVM/UDF + static Rust parser registry, in few modules | Accepted |
| [002](#adr-002-kotlin--rust-responsibility-boundary) | Rust owns bytes→document model; Kotlin owns access, lifecycle, state, rendering | Accepted |
| [003](#adr-003-ffi-tooling-uniffi-through-gobley) | UniFFI 0.29 bindings generated for KMP by Gobley | Accepted |
| [004](#adr-004-session-objects-and-bounded-batch-pulls) | Native session objects + bounded batch pulls | Accepted |
| [005](#adr-005-toolchain-pins-forced-by-gobley) | AGP 8.13 / Gradle 9.4 / CMP 1.11 pins forced by Gobley | Superseded by ADR-019 |
| [006](#adr-006-normalize-every-input-to-an-owned-file-descriptor) | Normalize every input to an owned, seekable file descriptor | Accepted |
| [007](#adr-007-failure-containment-in-process) | In-process engine; panics caught at the FFI boundary; no crash isolation | Accepted |
| [008](#adr-008-one-shared-module-feature-first-packages) | One `shared` Gradle module, feature-first packages, no `rustBridge` module | Accepted |
| [009](#adr-009-rendering-per-document-kind-native-pdf-display) | Viewer per document kind; native PDF renderers for display | Accepted |
| [010](#adr-010-manual-dependency-injection) | Manual dependency injection | Accepted |
| [011](#adr-011-container-formats-in-house-zip-reader-and-streaming-xml) | Container formats: in-house ZIP reader + streaming XML (quick-xml) | Accepted |
| [012](#adr-012-name-hints-for-text-presentation-and-inline-spans) | File-name hint picks how text is presented; inline styling as UTF-16 spans | Accepted |
| [013](#adr-013-design-system-recents-and-shared-pdf-controls) | Glass design system, persistent recents, shared PDF controls over platform renderers | Accepted |
| [014](#adr-014-designed-slides-presenter-device-library-and-frame-time-work) | Designed slides, presenter and device library | Accepted; later measurements remain separately dated |
| [015](#adr-015-spec-alignment-images-source-view-receiving-files-no-library-menu) | Images, source view and incoming files | Accepted |
| [016](#adr-016-find-picture-reads-between-batches-and-number-formats) | Find, image reads and number formats | Accepted |
| [017](#adr-017-no-storage-permission-offline-ocr-in-house-pdf-updates-legacy-and-encrypted-office) | Scoped storage, OCR, PDF tools and Office support | Accepted; folder/network policy refined by ADR-020 |
| [018](#adr-018-measured-performance-work-baseline-profile-memory-pressure) | Measured performance, profiles and memory pressure | Accepted; evidence applies to recorded checkpoints |
| [019](#adr-019-android-17-and-agp-9-compatibility-migration) | Android 17 / AGP 9 with temporary Gobley compatibility settings | Accepted; optional diagnostics exception in ADR-020 |
| [020](#adr-020-optional-diagnostics-and-confirmed-folder-writes) | Optional consented Firebase diagnostics and confirmed duplicate deletion | Implemented; runtime/device acceptance pending |

---

## ADR-001: Overall architecture

**Context.** A Kotlin Multiplatform reader for Android and iOS, offline, backed by a Rust engine that parses untrusted files. The team is small, so build complexity and module count weigh heavily. The main future work is adding parsers: DOCX, XLSX, PPTX, PDF, ODF, EPUB, CSV and more.

**Alternatives.** Each was scored against the ten required criteria (◎ strong, ○ adequate, △ weak):

| Criterion | Conventional layered | Feature-first modules | Minimal shared KMP module | Fully separated Clean modules | **Selected: Clean + Hexagonal ports, few modules** |
|---|---|---|---|---|---|
| 1 Maintainability | △ layers mix features | ○ | △ no boundaries | ○ heavy indirection | ◎ boundaries only where needed |
| 2 Performance | ○ | ○ | ○ | ○ | ○ (dominated by FFI and parsing design, not layering) |
| 3 Binary size | ○ | △ per-module overhead | ◎ | △ | ◎ one framework, one engine library |
| 4 Testability | △ | ○ | △ engine calls leak into UI | ◎ | ◎ domain ports faked; engine tested natively |
| 5 Cross-platform | ○ | ○ | ○ | ○ | ◎ platform code confined to adapters |
| 6 Build complexity | ◎ | △ many Gradle modules + iOS framework export | ◎ | △ 6–10 modules × 3 targets | ○ 2 Gradle modules + 3 crates |
| 7 Parser extensibility | △ | ○ | △ | ○ | ◎ new parser = new Rust module + registry line |
| 8 Memory efficiency | ○ | ○ | ○ | ○ | ◎ bounded batches built into the port |
| 9 Error isolation | △ | ○ | △ | ◎ | ◎ typed errors per stage; session poisoning (ADR-007) |
| 10 Long-term cost | △ rewrite risk | ○ | △ | △ high ceremony | ◎ |

**Decision.** Use Clean Architecture's dependency rule (presentation → application → domain; data implements domain ports) and a hexagonal *port* only where a real boundary exists: the engine (`DocumentReader`) and platform file access (`DocumentAccess`). The UI uses MVVM with unidirectional data flow. The Rust engine uses a trait-based parser registry built from a static slice. It all lives in as few build modules as the tooling permits (ADR-008).

**Justification.** The two boundaries that matter, platform I/O and the native engine, get interfaces. Nothing else does. Use cases that only forward a call are deliberately absent. The single application service exists because it owns a real responsibility: session lifetime.

**Trade-offs.** Package-level boundaries inside one Gradle module are enforced by review, not by the compiler. That is acceptable at this size.

**Consequences.** If compile times or team ownership demand it, `shared` can be split along its existing package boundaries without redesign (ADR-008 lists the triggers).

## ADR-002: Kotlin ↔ Rust responsibility boundary

**Context.** Logic duplicated across Kotlin and Rust drifts apart, so each responsibility needs exactly one owner.

**Decision.**

| Owned by Rust (`engine/`) | Owned by Kotlin (`shared/`) |
|---|---|
| Format detection from content | Picking files, OS permissions, URIs, security scopes |
| Parser selection and parsing | Turning a picked file into a file descriptor (ADR-006) |
| Resource budgets: input size, batch size, total output; later decompression and nesting | Session lifetime: one open document; close on replace or clear |
| Document model and its invariants | UI state machine, paging policy, rendering |
| Error classification | Mapping errors to user-facing copy |

**Alternatives.** Detecting the format in Kotlin from MIME type or extension was rejected. Extensions lie, and detection must sit next to the parsers it gates. Paging state in Rust beyond "next batch" was also rejected, because scroll position is UI state.

**Consequences.** Kotlin never inspects file bytes. Rust never sees a URI, a UI concept or an OS API. `document-core` has zero dependencies.

## ADR-003: FFI tooling: UniFFI through Gobley

**Context.** Shared Kotlin, not Swift, must call Rust on both platforms. The options available in November 2026:

| Option | Kotlin on Android | Kotlin/Native on iOS | Maintenance | Unsafe code we own |
|---|---|---|---|---|
| Hand-written C ABI + JNI + cinterop | JNI | cinterop | ours | ~150 lines plus a header |
| Official UniFFI 0.32 | generated (JNA) | **none**: Swift only, bridged into Kotlin through a Swift adapter | Mozilla, active | none |
| **UniFFI 0.29 through Gobley 0.3.7** | generated (JNA) | generated (cinterop) | community; last release Oct 2025 | none |

A hand-written C ABI with a JSON protocol was prototyped first and passed its tests. It was replaced at the project owner's direction to use UniFFI.

**Decision.** Use Gobley. One generated binding (`com.ezfiles.engine.ffi`) serves `commonMain` on both platforms. Gobley's Gradle plugin also builds the Rust library for every Kotlin target, links it, and supplies a host build for JVM unit tests.

**Justification.** Typed records, enums and errors replace a hand-maintained protocol. Object lifetimes are handled by generated code that is safe against use after close. The Kotlin code is identical on both platforms.

**Trade-offs and risks.**
- Gobley pins UniFFI to 0.29 (current upstream is 0.32).
- It forces older Android tooling (ADR-005).
- It is a single-maintainer-scale project. Mitigation: the exported surface is five functions and four types, so falling back to official UniFFI plus a Swift adapter, or to the hand-written ABI, means changing one file per side.
- JNA adds about 150 KB per ABI on Android, and calls through JNA cost microseconds. Coarse calls (ADR-004) make that negligible.

**Consequences.** `engine/Cargo.toml` and `gradle/libs.versions.toml` must move UniFFI and Gobley together.

## ADR-004: Session objects and bounded batch pulls

**Context.** Documents can be hundreds of MB. Copying a whole document model across the FFI boundary, or making one FFI call per paragraph, are both unacceptable.

**Alternatives.** Return the whole model, which is unbounded in memory and has no early display. Push callbacks from Rust into Kotlin, which needs threading on both sides and complicates cancellation. Fine-grained getters, which mean thousands of FFI calls.

**Decision.** `open_fd` returns an `EngineSession` object (a UniFFI handle). Kotlin pulls `next_batch()`, and each batch holds at most `max_batch_bytes` (64 KiB by default) of text. The UI requests the next batch only when the user scrolls near the end of what is loaded.

**Consequences.** Memory per open document is bounded by roughly one batch plus a 64 KiB read buffer in Rust. Kotlin retains everything displayed, capped at `maxTotalOutputBytes` (16 MiB of UTF-8). Random access, such as jumping to page 500 of a PDF, will need a `batch_at(index)` operation added for formats that support it. That is additive and doesn't break the contract.

## ADR-005: Toolchain pins forced by Gobley

**Status (2026-11-11).** The toolchain pins and migration exit criterion below are superseded by [ADR-019](#adr-019-android-17-and-agp-9-compatibility-migration). The original findings and rationale are retained as history.

**Context.** Measured on this project on 2026-11-03:
- **Gobley 0.3.7 with AGP 9:** it rejects AGP 9 setups ("Android JVM targets are added, but Android Gradle Plugin is not found"; gobley#153, PR #282 still open).
- **Legacy KMP setup on AGP 9:** AGP 9 refuses `com.android.library` together with KMP.
- **AGP 8.13 with Gradle 9.6+:** AGP 8.13 fails on Gradle 9.6 and later, which removed an internal API.
- **CMP 1.12 and lifecycle 2.11 on AGP 8:** these releases pull AndroidX artifacts whose metadata requires AGP 9.1.

**Decision.** Pin AGP 8.13.2, Gradle 9.4.1, Compose Multiplatform 1.11.1, lifecycle 2.10.0, compileSdk 36 and NDK 29.0.14206865. Kotlin stays on 2.4.20.

**Exit criterion.** When a Gobley release supports `com.android.kotlin.multiplatform.library`, move to AGP 9, the KMP Android library plugin, current CMP and Gradle in one change, then rerun the vertical slice.

**Consequences.** Gobley needs the NDK even to *configure* the build. Contributors must install NDK 29.0.14206865.

## ADR-006: Normalize every input to an owned file descriptor

**Context.** Android hands out `content://` URIs, which may be backed by cloud providers, pipes or real files. iOS hands out security-scoped URLs that may point at iCloud placeholders. Neither may be treated as a filesystem path.

**Decision.** Each platform's `DocumentAccess` turns a `DocumentRef` into **one open, readable, seekable POSIX file descriptor** and hands ownership to Rust (`open_fd`). Rust closes it on every path, success or failure.

- **Android:** `ContentResolver.openFileDescriptor(uri, "r")`. If the descriptor is seekable (checked with `lseek`), `detachFd()` passes it on with zero copies. Otherwise the stream is copied, under the input size limit, into `cacheDir/document-import/`, opened, then **immediately unlinked**, so it can't outlive the session even if the process dies.
- **iOS:** `startAccessingSecurityScopedResource` → `NSFileCoordinator.coordinateReadingItemAtURL` (which also downloads iCloud placeholders) → `open(O_RDONLY)` inside the coordinated block → stop the scope. The descriptor stays valid, because the sandbox checks at open time.

**Alternatives.** Copy every file to app storage: simple, but it doubles I/O and storage for large files. Pass paths: wrong on Android and loses the scope on iOS. Stream bytes across the FFI boundary: many calls, and most parsers need to seek.

**Trade-offs.** After the descriptor is open, iOS reads are not coordinated, so a concurrent writer can change the file mid-session. Parsers treat inconsistent content as `Malformed`, never as undefined behaviour.

## ADR-007: Failure containment in process

**Context.** The engine parses untrusted input in the app's process. Rust prevents memory unsafety in safe code, but not panics, infinite loops or memory exhaustion, and future parser dependencies may contain `unsafe` or C code.

**Decision.**
1. `panic = "unwind"` in every profile. UniFFI catches panics at the boundary, and Kotlin receives `InternalException`, mapped to `DocumentError.Internal`.
2. A panic while a session's mutex is held *poisons that session only*. Later calls on it fail, and other sessions are unaffected.
3. Resource exhaustion is prevented by budgets (input size, batch size, total output, and decompression and nesting limits once container formats arrive), not recovered from.
4. Parsers with C dependencies (for example a PDF library) are reviewed specifically and fuzzed before inclusion.

**Platform PDF renderers.** Android's `PdfRenderer` runs pdfium inside the app process, and PDFKit runs inside the app on iOS. Their containment is the platform's responsibility; a crash in either still ends the app.

**Not promised.** Isolation from aborts, stack overflow, OOM kills or memory corruption from `unsafe` or C code. Any of these terminates the app.

**Stronger options evaluated.**
- **Android:** run the engine in an `android:isolatedProcess` service and pass the descriptor over Binder. This gives real crash and sandbox isolation, at the cost of IPC and roughly 10–20 MB of RAM per process.
- **iOS:** no equivalent for app code (no XPC services on iOS), so only in-process containment is possible.

The decision is to revisit this for Android when the first parser with a C dependency is added.

## ADR-008: One shared module, feature-first packages

**Context.** The brief suggested a possible `rustBridge` module.

**Decision.** Two Gradle modules (`shared` and `androidApp`), the Xcode project `iosApp`, and three Rust crates:
- `document-core`: contracts, detection, coordinator; no dependencies.
- `document-parsers`: parsers plus the static registry.
- `document-ffi`: the UniFFI facade.

Kotlin is packaged by feature (`com.ezfiles.reader.*`), then by layer.

**Why no `rustBridge` module.** Gobley generates the bindings into `shared`'s own source sets and links the library there. A separate module would add a second iOS framework export and Gradle overhead without isolating anything.

**Split triggers.** Split `shared` when a second feature's compile time measurably slows iteration, or when teams own features separately. Split a parser into its own crate when it brings heavy dependencies that need feature-gating, licence review or independent fuzzing.

## ADR-009: Rendering per document kind; native PDF display

**Context.** Formats don't share layout semantics. Text reflows, spreadsheets are grids, and PDF pages are fixed-layout graphics.

**Decision.** `DocumentInfo.kind` selects a viewer composable (`DocumentViewer`). Each viewer owns its layout. There is no lowest-common-denominator renderer.

| Kind | Viewer |
|---|---|
| Text (plain text, DOCX, ODT, EPUB) | `BlockListViewer`: virtualized blocks; monospace for plain text only |
| Presentation (PPTX, ODP) | `BlockListViewer` with slide sections |
| Archive | `BlockListViewer` showing entries and sizes |
| Spreadsheet (XLSX, ODS) | `SpreadsheetViewer`: rows virtualized vertically, fixed-width columns scrolled horizontally as one grid, row-number gutter |
| PDF | `PdfDocumentViewer` (`expect`/`actual`) |

**PDF (implemented).** Pages are displayed by the platform renderers. The Rust engine validates the header and gives typed errors, but extracts nothing.
- **Android:** `PdfRenderer` over a second descriptor from the same `DocumentAccess` (so non-seekable providers are copied first). Pages render on demand at screen width, at most 1440 px, one at a time under a lock, and are released when scrolled away. No pinch-zoom yet.
- **iOS:** PDFKit's `PDFView` embedded with `UIKitView`, which provides native zoom, selection and accessibility. The security scope stays open while the view is shown.
- **Why native:** hardware-accelerated renderers already in the OS, so no binary cost. Rasterizing in Rust (pdfium or MuPDF bindings) would add 5–15 MB per ABI and C attack surface (ADR-007). Text extraction for search remains future work.
- **Password-protected PDFs** show a clear "not supported yet" message on both platforms.

## ADR-010: Manual dependency injection

**Decision.** Platform entry points construct the object graph directly (`androidDocumentReader`, `MainViewController`), and `viewModel { }` builds the ViewModel. The whole graph is four objects. Revisit when a second feature with shared dependencies appears.

## ADR-011: Container formats: in-house ZIP reader and streaming XML

**Context.** DOCX, XLSX, PPTX, ODT, ODS, ODP and EPUB are ZIP containers of XML parts. ZIP is a well-known attack vector: decompression bombs, overlapping entries, huge directories, path tricks. XML adds entity-expansion and external-entity (XXE) attacks. Office files can be hundreds of MB once decompressed.

**Alternatives.**

| Option | Notes |
|---|---|
| `zip` crate | Full-featured; frequent major-version churn; many optional codecs (bzip2, zstd, AES) we must disable; limits still to be layered on top |
| **In-house reader (~300 lines) + `flate2` (pure-Rust `miniz_oxide` backend)** | Only stored and deflate (all these formats need); every limit enforced at one choke point; no C code in the decompression path |
| DOM XML parser (roxmltree, xml-rs DOM) | Loads whole parts into memory: unbounded for large sheets |
| **`quick-xml` pull parser** | Streaming, no DTD processing, no external entities |

**Decision.**
1. `zip.rs` parses the central directory itself. It refuses ZIP64, multi-disk and encrypted entries, entries overlapping the directory, and unknown methods. It streams one entry at a time. `EntryReader` checks the declared size, CRC-32, compression ratio (250:1 after 1 MiB) and the session's decompressed total (1 GiB).
2. `xml.rs` wraps quick-xml. It resolves only the predefined entities, numeric references and a fixed list of common HTML entities. DTD-declared entities are never expanded and nothing external is loaded. Nesting is capped at 256, and cancellation is checked every 4,096 events.
3. Each format is a `BlockSource` that walks its XML and pushes blocks. The shared `Streamed` driver batches them under the same budgets as plain text, so the FFI contract and Kotlin model are unchanged.
4. Classification happens inside the container (`mimetype` entry, package relationships, well-known parts), never by extension. Plain ZIPs are listed from the central directory without decompressing anything, and nested archives are never opened.

**Consequences.**
- The release arm64 engine grew from 384 KB to **660 KB**.
- Streaming isn't absolute. XLSX shared strings and OOXML relationship parts are read whole (capped at 32 MiB), and ODS rows repeated more than 1,000 times are capped.
- Not yet extracted: headers and footers, footnotes, comments, images, charts, speaker notes, number and date formats in XLSX.

## ADR-012: Name hints for text presentation, and inline spans

**Context.** Students and professionals open Markdown notes, CSV exports, JSON configs and source files. Byte-level detection correctly says "UTF-8 text" for all of them, but they deserve different presentations. Documents also need inline styling (bold, italic, links) to read well.

**Decision.**
1. `open_fd`/`open_path` take an optional **name hint**. The engine still decides *what the bytes are* by content (ADR-002); only when the content is plain text does the hint's extension choose a presentation:
   - Markdown: pulldown-cmark, CommonMark + GitHub tables, task lists and strikethrough;
   - CSV/TSV: streamed spreadsheet rows, delimiter sniffed, header row marked;
   - JSON: pretty-printed;
   - about 60 source-code extensions: line-numbered code.

   A wrong or hostile name can at worst choose another text view, never another parser.
2. Text blocks carry `Rich { text, spans }`. A span is `start..end` in **UTF-16 code units**, the unit of Kotlin and Swift strings, plus style bits (bold, italic, underline, strike, code) and an optional link. Spans survive batch splitting (`Rich::split`). Links are kept only for `http`, `https` and `mailto`; other schemes (`javascript:`, `file:`, app schemes) are dropped in the engine.
3. New blocks: `Quote`, `Code { language, first_line }`, `Divider`, `ListItem.marker` (real numbering from DOCX `numbering.xml`, ODF and Markdown lists, task boxes), and `TableRow.header` (DOCX `tblLook`/`tblHeader`, PPTX `firstRow`, ODF header rows, Markdown head rows, CSV first row).

**Consequences.**
- Markdown and pretty JSON are parsed whole, so they are capped at 8 MiB. Larger files fall back to the plain-text and code views, which stream.
- Raw HTML inside Markdown is never rendered.
- The release arm64 engine is now **1.06 MB**: pulldown-cmark plus the new views.

## ADR-013: Design system, recents and shared PDF controls

**Context.** The app follows the "EzFiles design system" (Material 3 Expressive + glassmorphism) and the Library/Reader mockups. Users expect recent files, search, type filters and a full PDF reader.

**Decision.**
1. **Design tokens and glass** in `presentation/theme`. Compose has no cross-platform backdrop blur, so glass is translucency, a light border and a top highlight over the gradient backgrounds; a blur would barely change a smooth gradient. Translucent surfaces get no elevation shadow, because Android shadows show through them. Press feedback uses springs.
2. **Recent documents** (`RecentDocuments` port, `RecentDocumentsStore`): JSON in SharedPreferences or NSUserDefaults, 50 entries, references and metadata only, never content.
   - **Android** takes a persistable read grant.
   - **iOS** stores a bookmark inside the opaque `DocumentRef.uri`, so files reopen after a restart.
   - **De-duplication** uses `identity` (name + size + modified time on Android, the file path on iOS), because one file can arrive under several URIs.
3. **PDF (refines ADR-009).** The reader's controls are shared Compose code:
   - single, two-page and grid views;
   - zoom from 50% to 300% (buttons and pinch);
   - a page slider, a thumbnail strip, and go-to-page.

   Each platform supplies only a `PdfPages` renderer (`PdfRenderer` on Android, PDFKit page rendering on iOS). iOS therefore no longer embeds `PDFView` and loses PDFKit's native text selection in exchange for an identical reader.
   - **Password PDFs:** prompted for. The password stays in memory only for that screen. Android needs version 15 or newer (`LoadParams`); iOS uses `unlockWithPassword`.
4. **Presentations** use a slideshow: pager of 16:9 slide cards, speaker notes (PPTX notes slides, ODP `presentation:notes`), an outline toggle and a full-screen Present mode.

**Consequences.** Light theme only for now. Pinch zoom re-renders pages at the committed zoom; bitmap width is capped at 2048 px, so 300% zoom on wide screens is slightly soft.

## ADR-014: Designed slides, presenter, device library and frame-time work

**Context.** Users expect a PowerPoint file to look and play like PowerPoint: the slide as designed, animated transitions and click-by-click builds. They also expect the first screen to list their documents without picking each one, and the app had visible jank on scroll.

**Decision.**
1. **PPTX visual model.** The PPTX parser builds a small DOM per slide (`dom.rs`) and resolves PowerPoint's inheritance chain: theme → master (colour map, text styles, placeholders, background) → layout → slide.
   - Each slide emits `Section`, then `Slide { width, height, background, background_image, transition }`, then one `Shape` per element, then speaker notes as `Quote`s.
   - Each `Shape` carries its position in points, rotation, geometry, fill, line, picture path, paragraphs (size, colour, weight, alignment, bullet, level), anchor, insets, table cells and `Build { step, effect }` taken from `p:timing` click effects. Group transforms are applied.
   - Decks without layouts fall back to the default Office master's placeholder frames and text sizes.
   - Pictures are not inlined. `Session::resource(name)` serves them on demand, and the UI decodes them off the main thread into a 32-entry LRU (`SlideImageCache`).
   - ODP keeps the text-card slides from ADR-013.
2. **Slideshow UI.**
   - `SlideCanvas` positions shapes on a canvas scaled to the available width.
   - The pager has a deck transform (scale, tilt, dim) that runs only in the graphics layer, plus a thumbnail strip.
   - The presenter is immersive and landscape. A tap or swipe plays the next build (fly-in, zoom, wipe or fade by preset id), then the next slide with its `p:transition` mapped to a Compose transition. Going back mirrors the transition.
   - `MainActivity` handles orientation changes itself, so presenting never recreates the activity.
3. **Device library** (replaced by ADR-017). On Android, MediaStore listed supported files after the user granted all-files access. App-private and hidden folders were skipped, with a cap of 5000 files. Category chips filter that list, and opened files are kept in Recents. iOS has no shared file system, so it keeps the picker and Recents.
4. **Frame time.**
   - Backgrounds are rasterised once.
   - Glass surfaces are flat fills with no shadows.
   - Press feedback runs in the graphics layer.
   - PDF pages use a bitmap LRU.
   - Spreadsheets window their columns.
   - A `benchmark` build type (release and profileable) is used for `gfxinfo` measurements.

**Consequences.**
- Charts and SmartArt render as labelled boxes.
- Gradients use their first stop.
- Custom geometry draws as a rectangle.
- Motion paths and emphasis or exit effects are ignored. Only entrance builds play.
- Text uses the platform font, not the deck's fonts, so line breaks can differ slightly from PowerPoint.

## ADR-015: Spec alignment: images, source view, receiving files, no Library menu

**Context.** The consolidated product spec (5 November 2026) removes the Library's top menu, makes images P0, requires a Markdown source mode, and lists opening files from other apps as the first primary task.

**Decision.**
1. **Library header** is the wordmark only. "Clear" sits beside the Recent title, and the About item is gone.
2. **Images** are a new `FormatId::Image` and `DocumentKind::Image`, detected by signature. A `BM` prefix alone is ordinary text, so BMP also needs zero reserved bytes and a known header size. The engine serves the encoded bytes through `resource()` with a 64 MiB cap, and the platform decodes them. A Rust decoder would add binary size and still lack HEIC.
3. **Source view** reopens the file with no name hint, which the engine already treats as plain lines (ADR-012). This needs no new engine API. It does not touch recents.
4. **Receiving files (Android).** `VIEW` and `SEND` filters accept `content` URIs only. The activity is `singleTask`, so a new file replaces the open one.
5. **Picture decoding** belongs to `SlideImageCache`, not to the caller. Previously a scrolled-away thumbnail could cancel a decode the slide was also waiting on, and leave a permanent blank entry.
6. **Device listing** caps images and documents separately, and All hides images, so a photo library can't crowd out documents.

**Consequences.** iOS does not receive files from other apps yet. iOS image decoding goes through Skia, which is not expected to read HEIC or AVIF; the viewer shows a clear message instead. Image bytes cross the FFI as one copy, which is acceptable up to the cap.

## ADR-016: Find, picture reads between batches, and number formats

**Decision.**
1. **Find in block documents** runs off the main thread over the batches already loaded, and keeps pulling batches until the end of the document, the 16 MiB output limit or 10,000 matches. Matches are held per block and passed to visible items only.
2. **Find in PDF** is a port on `PdfPages`, because text comes from the platform. Android uses `PdfRenderer.Page.searchText` on API 35+, and `PdfRendererPreV` on API 31-34 with SDK extension 13, under the renderer's existing lock; below that the control is hidden. iOS uses `PDFPage.string` with `selectionForRange`. Highlights are stored as normalised top-left boxes, so zoom doesn't affect them. Search starts at the current page and wraps.
3. **Pictures can be read between batches** through `ZipArchive::read_aside`, which restores the file position. The PPTX approach closed the open XML part, which would break a streaming format mid-document.
4. **Number formats** are interpreted in-house (`numfmt.rs`) with no date library, keeping the binary small. Anything unsupported or hostile falls back to the raw value, so data is never lost.

**Consequences.** Matches can't span blocks. "3 / 17" in PDF can renumber while earlier pages are still being searched. XLSX formatting is checked against expected values written by hand, not side by side with Excel.

## ADR-017: No storage permission, offline OCR, in-house PDF updates, legacy and encrypted Office

**Decision.**
1. **Folders the user chooses replace all-files access.** Google Play's policy excludes file picking and media from `MANAGE_EXTERNAL_STORAGE`, and requires document apps to justify why alternatives are insufficient. Trees from `ACTION_OPEN_DOCUMENT_TREE` are walked with `DocumentsContract`, with bounded depth and counts. Android refuses the storage root and Download, so those files use Open file.
2. **OCR uses the bundled ML Kit model**, because the unbundled one and the ML Kit Document Scanner depend on Play services and a download. Telemetry components and `INTERNET` are removed in the manifest. Offline recognition was checked in airplane mode, including an R8 build. Page outline detection is in-house Kotlin.
3. **PDF annotations are saved as an incremental update** by a bounded in-house parser in the engine (`pdf_syntax`, `pdf_file`, `pdf_annot`). `android-36` has no annotation-writing API, and `lopdf` added 462 KiB and 66 packages. The reader draws saved annotations itself, on the assumption that `PdfRenderer` doesn't.
4. **Images to PDF has its own JPEG writer**, so pages are streamed one at a time. Scan pages with text and Text to PDF use `PdfDocument`, for embedded fonts.
5. **OLE compound files are read in-house** (`cfb.rs`). Decryption uses RustCrypto's `aes`, `cbc`, `sha1` and `sha2`, and `zeroize`.

**Consequences.** The first screen no longer finds files by itself, and Downloads can't be listed. A universal APK grows from 4.6 MB to about 31 MB, so Play gets an App Bundle (8.87 MB estimated download on arm64) and direct sharing gets per-ABI APKs with compressed libraries (9.60 MB). Searchable scans hold pages in memory, hence the 50-page limit. Annotations on a PDF are lost if the process dies before saving.

## ADR-018: Measured performance work, Baseline Profile, memory pressure

**Decision.**
1. **Performance changes need a measurement.** `document-ffi/examples/profile.rs` times the engine through the app's FFI functions; `:baselineprofile` has startup benchmarks. Before-and-after numbers go in [performance.md](performance.md).
2. **The XML reader keeps names, short texts and attributes inline** (`SmallStr`), with no change to its API. On a 17 MB sheet part, its overhead over quick-xml dropped from 242 to 140 ms.
3. **`document-parsers` is built at `opt-level = 3`**, the rest at `"s"`: about 20% faster parsing for +176 KB of native code per ABI.
4. **A Baseline Profile ships with the app**, generated on demand and committed. `profileinstaller` covers the direct-share APKs.
5. **Caches give memory back** through `MemoryPressure` when the UI is hidden or memory is low.

**Consequences.** XLSX reads 44% faster and DOCX 33% faster in the engine. The Baseline Profile showed no startup gain on the emulator, so its benefit on phones is still unmeasured. The profile has to be regenerated after large UI changes, otherwise new screens run without ahead-of-time compilation (still correct, just slower to warm up). Returning to a PDF after the app was in the background re-renders the visible pages.

## ADR-019: Android 17 and AGP 9 compatibility migration

**Context (2026-11-11).** [Android 17 is released](https://developer.android.com/blog/posts/android-17-is-here). [AGP 9.5.1](https://developer.android.com/build/releases/agp-9-5-0-release-notes) supports API 37 and requires Gradle 9.6 or newer. Gobley 0.3.7 still uses the legacy Android plugin/source-set interfaces for generated JNI libraries, so this upgrade needs a temporary compatibility route through AGP 9.

**Decision.** Use compileSdk 37 and targetSdk 37, AGP 9.5.1 and Gradle 9.7.1. Keep minSdk 26, the JDK 17 Kotlin/Java toolchain and bytecode target, NDK 29.0.14206865, Kotlin 2.4.20, Gobley 0.3.7 and the existing Compose/UniFFI versions. Keep the offline permission policy: camera access where needed, with no Internet or broad storage permission.

Retain these temporary properties in `gradle.properties`:

| Property | Reason |
|---|---|
| `android.builtInKotlin=false` | Retain the existing Kotlin plugin integration. |
| `android.newDsl=false` | Retain the legacy Android DSL types that Gobley detects. |
| `android.sourceset.disallowProvider=false` | Allow Gobley's Provider-backed generated JNI source-set directories. |

The first two are [documented AGP migration opt-outs](https://developer.android.com/build/migrate-to-built-in-kotlin#opt-out-of-built-in-kotlin), unavailable in AGP 10. This change retains the legacy KMP Android target. Remove the compatibility settings only after Gobley and the generated JNI wiring support migration to `com.android.kotlin.multiplatform.library`, then repeat packaging and device verification.

**Validation (2026-11-11).** Passed 658 Kotlin/JVM tests, including real host-native integration, app/shared debug lint, debug/benchmark/share APK assembly and the release bundle. Generated JNI copy tasks ran before shared JNI merge tasks. The regenerated wrapper JAR matches Gradle's published SHA-256. A controlled text fixture rendered through the debug native reader on the API 37 x86_64 emulator. Existing lint warnings and migration deprecations remain visible. This is a build-tested bridge: Kotlin 2.4.20's [fully tested matrix](https://kotlinlang.org/docs/gradle-configure-project.html) ends at AGP 9.3.1 / Gradle 9.7.0, below this requested toolchain.

**Remaining device validation.** Complete the following on Android 17 and an API 26 device/emulator:

- **Native loading:** target 37 requires native files loaded through `System.load()` to be read-only. Verify Rust/UniFFI and JNA on the normal APK and compressed direct-share APK; exercise any extraction fallback before deciding whether loader changes are needed. See [Android 17 target behavior changes](https://developer.android.com/about/versions/17/behavior-changes-17).
- **Audio lifecycle:** every start now requires a resumed lifecycle, including the prepared callback, and resume does not restart playback. Verify foreground playback, device locking, backgrounding while media prepares and returning to the viewer. Android 17 [hardens background audio playback](https://developer.android.com/about/versions/17/changes/bg-audio); this app's foreground playback does not require a background audio service.
- **Large screens:** test tablet/foldable portrait, landscape, split-screen, presentation mode and camera preview. Target 37 removes orientation/resizability opt-outs on displays with smallest width at least 600 dp; presentation mode cannot assume its landscape request will control the window. See [adaptive layout behavior](https://developer.android.com/about/versions/17/behavior-changes-17).
- **Memory and viewers:** stress PDF bitmap caches, large documents, HTML/WebView, image and scan/OCR flows. Android 17 adds a device-RAM-based [app memory limit](https://developer.android.com/about/versions/17/behavior-changes-all); inspect process exits for `REASON_OTHER` / `MemoryLimiter:AnonSwap` when investigating termination. Host JVM tests do not verify Android native loading, rendering or memory behavior.

**Consequences.** ADR-005's toolchain ceiling is replaced while its historical rationale remains. The compatibility flags are migration debt, and future AGP upgrades require checking their availability and Gobley's current integration rather than changing version numbers alone.

## ADR-020: Optional diagnostics and confirmed folder writes

**Date/status.** 12 November 2026. Implemented in the working tree; device/cloud acceptance remains pending. This refines ADR-017/019's permission policy without removing their historical rationale.

**Context.** The owner authorized confirmed Android duplicate deletion and optional Firebase crash diagnostics. An unconditional read-only/no-network statement no longer describes both build profiles. Document processing must remain local, and the strictly offline build must remain available.

**Decision.**

- Keep the default Android build without network permissions or Crashlytics. Select the diagnostics implementation only with `-Pezfiles.firebaseCrashReports=true` and a local, Git-ignored Android client configuration.
- Keep reporting off by default. Save explicit consent, initialize Firebase only on a subsequent process launch with that consent, and retain local manual report sharing. Opt-out bypasses future crash forwarding and blocks report transport; restart fully unloads the SDK. Already-started requests cannot be recalled.
- Retain the local recorder as the outer crash handler. Give Crashlytics a bounded scrubbed exception copy, not the original graph. Disclose SDK-managed diagnostics/identifiers separately from the local report preview.
- Restrict the shared CCT transport to consented Crashlytics report events. Do not reopen OCR/other telemetry when enabling diagnostics. Keep Analytics absent and build-time mapping/symbol upload disabled pending a separate release step.
- Request read/write SAF folder access but preserve read-only fallback. Delete only confirmed extra exact copies, keep the first copy, verify write/provider capability and compare fresh bytes. Scans and similarity/collection groups do not delete files.

**Consequences.** Build profiles have different permission/runtime boundaries. Transport integration must be revalidated when its pinned SDK/APIs change. SAF has no atomic compare-and-delete operation against other apps' concurrent edits. Neither compilation nor a static manifest audit proves device behavior, Firebase receipt or security certification.

**Evidence and follow-up.** Both Android build profiles compiled and passed static lint; manifest/DEX audits are recorded in [NOTES.md](../../NOTES.md). User-led device checks, iOS acceptance, actual Firebase receipt and matching mapping upload remain separate gates. See [security](../security.md), [Firebase setup](../research/2026-11-12-consented-firebase-crash-reporting.md) and [current status](../implementation-status.md).
````

## 2026-11-12: One app-feature document; old documentation deleted

- Goal: The owner clarified that clean meant delete old documentation and create ONE document with all app features. This replaces the earlier in-progress index/status cleanup approach. The deletion instruction explicitly authorized removing old documentation pages; no application/source deletion was authorized or performed.
- Final decision: docs/FEATURES.md is the only Markdown feature guide under docs. Removed 24 old Markdown pages, including the temporary index/status pages from the earlier interpretation, and removed the now-empty architecture/research directories. Kept the branding SVG and all bundled license texts. The root README is a short entry linking to FEATURES.md; NOTES.md remains the required append-only journal.
- Preserved decisions: Before deletion, copied the complete architecture decision record into the fenced historical section above, including original/superseded decisions. It is not a second current feature guide. Existing earlier journal entries remain unchanged except the introductory navigation links, now pointing to FEATURES.md.
- Feature coverage: Android/iOS status; complete readable-format and AppFunctions function tables; library/search/thumbnails; reader modes; PDF tools; EPUB/Office; photos/video/subtitles/languages/gestures; creation/editor; Tidy grouping and confirmed duplicate deletion; sharing/printing; assistants; glass/landscape/safe areas/haptics; privacy/local crashes/Firebase consent; Android setup/builds; performance bounds; team contacts; known gaps and validation limits. No source-implemented feature is relabelled as physically accepted.
- Files changed: docs/FEATURES.md (new); README.md (shortened); NOTES.md (history/checkpoint); deleted old docs Markdown pages. No application code, Gradle configuration, credentials/configuration contents, licenses, media fixtures or APKs changed during this documentation task. Earlier unrelated uncommitted source changes were preserved.
- Validation: Inline Python audit checked the three remaining active Markdown files, 27 local file/anchor links, heading/fence structure, UTF-8 replacement characters, resolved format/function placeholders, and exactly one Markdown guide under docs; no issues. External URLs were preserved, not live revalidated. Generated result: .gradle/consolidated-doc-audit.json. Command: git -c core.safecrlf=false diff --check -- '*.md'. No app build or runtime tests were needed for documentation-only changes; user-led device testing remains unchanged.
- Next steps: Use docs/FEATURES.md as the single feature/setup/status source. Update that document when behavior changes and append future checkpoints here. App/device/iOS/Firebase acceptance gaps are listed there. No staging, commit or push was performed.

## 2026-11-12: Owner-signed distribution APK preparation (waiting for local signing setup)

- Goal: Build an installable signed APK with the existing app icons. The owner explicitly selected their own release key for distribution, replacing the interim existing-test-key choice for the final deliverable. Existing device/iOS/Firebase acceptance gaps remain; do not claim every app feature is certified complete.
- Current checkout: HEAD 6bfd5cd; the owner committed the preceding work outside this signing task. Initial worktree was clean. Do not stage, commit or push this task.
- Files read: NOTES.md; androidApp/build.gradle.kts; androidApp/src/main/AndroidManifest.xml; androidApp/src/main/res/mipmap-anydpi/ic_launcher.xml, ic_launcher_light.xml and ic_launcher_dark.xml. Manifest aliases include system/light/dark icons, with shared foreground and monochrome resources. Keystore metadata only was checked: C:/Users/kisho/Development/EzFiles/ezjks exists as a file. No key contents/passwords were read or printed.
- Owner signing check: ignored local init script .gradle/check-owner-signing.init.gradle registers checkOwnerSigningReady and emits only boolean readiness/missing-field flags. All four ezfiles.signing.* values were absent at the last Gradle check; C:/Users/kisho/.gradle/gradle.properties itself was absent. No key was generated, replaced or altered. The keystore path alone does not supply the alias/password configuration.
- Local helper prepared (not run by the agent): .gradle/configure-release-signing.ps1. Owner runs it in their own PowerShell; masked password prompts stay local. It uses keytool via an ephemeral environment value, detects signing aliases and writes the four escaped Gradle properties outside the repository while preserving other lines. No password is printed or passed as a command-line value. Syntax was validated without executing credential prompts. Awaiting the owner's helper-completed/error reply.
- Completed verification: optimized test-key Share APK with Firebase profile built and app/shared lint passed (2m 1s). apksigner verified its v2 signature; aapt2 confirmed com.ezfiles, version 0.1.0-share-signed-test-20261112-r1, Android 17/API 37 target, EzFiles label/icon resources and arm64-v8a/x86_64 ABIs. This is an interim test artifact, NOT the requested final owner-signed distribution APK.
- Release preparation: :androidApp:assembleRelease -Pezfiles.firebaseCrashReports=true passed (1m 18s) without owner signing; output androidApp/build/outputs/apk/release/androidApp-release-unsigned.apk is unsigned and not the installable final deliverable. zipalign -c -P 16 4 passed; aapt2 confirmed com.ezfiles / 0.1.0 (1), API 37 target and packaged icons. Firebase mapping upload was SKIPPED. No installation, runtime/device tests, report uploads or key changes occurred.
- Next step after local setup: rerun checkOwnerSigningReady; require RELEASE_USES_OWNER_KEY=true, then assembleRelease with the Firebase property. Verify the actual signed release with SDK build-tools 37.0.0 apksigner/zipalign and inspect package/icon metadata. Preserve named final APK and exact matching mapping; compare public certificate digest with the existing test APK to accurately explain update compatibility. Do not silently deliver a debug-signed or unsigned APK as the owner's release.

## 2026-11-13: Signing helper destination correction; owner signature still pending

- Owner reported the helper completed and requested the build. A fresh Gradle readiness/assembleRelease invocation still saw all four signing fields absent and stopped before packaging. The expected C:/Users/kisho/.gradle/gradle.properties file was absent; no credentials were printed or read directly. Existing prepared release remains unsigned and must not be presented as the final installable owner release.
- Diagnosed a helper robustness issue: Environment.GetFolderPath(UserProfile) can return an empty string in this hosted shell. The helper now writes to the build's exact configured C:/Users/kisho/.gradle directory, verifies file creation and prints its saved path (not its contents). USERPROFILE itself is C:/Users/kisho. No properties file was found in either workspace .gradle location either. This does not establish why the owner's prior local attempt failed; await actual success/error output from the corrected helper.
- Corrected ignored local helper: .gradle/configure-release-signing.ps1. PowerShell parser validation passed without executing credential prompts. Asked owner to rerun locally and paste only the saved-path line or error. Keep passwords local. Do not run credential entry through chat or use guessed/default passwords.
- Additional static icon audit: aapt2 resource dump confirms ic_launcher, ic_launcher_light, ic_launcher_dark, foreground and monochrome resources survive release resource shrinking. No app source, key, signing configuration, device state or Firebase project was changed by the agent. No commit/staging/push.
- Next action: once the exact settings file exists, rerun readiness and assembleRelease with the Firebase profile, then apksigner/zipalign/public-certificate checks and named final-artifact creation. The requested owner-signed APK has NOT yet been produced.

## 2026-11-13: Owner-provided signed release APK verified

- Owner supplied androidApp/build/outputs/apk/release/androidApp-release.apk after local signing. This replaces the prior pending-signature status for THAT artifact; it does not establish that Gradle's unattended signing properties were configured.
- SDK build-tools 37.0.0 apksigner verified one signer and APK Signature Scheme v2. Its public certificate digest differs from the earlier test-key APK; no key/password was read or printed. zipalign -c -P 16 4 passed. aapt2 confirmed com.ezfiles, version 0.1.0 (1), target/compile API 37, EzFiles label, packaged launcher resources and arm64-v8a/x86_64.
- Artifact profile: signed APK manifest has no INTERNET permission, Firebase consent metadata or consent-filtered backend. It is the default OFFLINE release, not the Firebase-enabled profile previously prepared. Do not claim this particular signed artifact includes cloud diagnostics. No new app build, upload or installation was performed during this verification.
- File size: 38,101,072 bytes. SHA-256: 084a1869e486e28aff38cfb011e4761a30cf22249af0e7316d3537ad007c0ea8. Static audit saved under ignored .gradle/provided-release-audit.json. Public signature logs are ignored local build evidence.
- Compatibility: the certificate mismatch prevents updating an installation signed with the earlier test key. No installed app was removed, data cleared or device inspected. Existing device acceptance remains with the owner.
- Next steps: owner tests this signed offline release. If a signed Firebase-enabled release is wanted, build with -Pezfiles.firebaseCrashReports=true and sign it using the same owner key; current artifact verification does not establish cloud receipt. No staging, commit or push.

## 2026-11-13: Owner-requested local APK security assessment

- Goal: Security-test the owner's supplied signed offline APK. Scope was local APK analysis, source-assisted review, source regression tests and a disposable synthetic emulator attempt. No personal-device actions, real-data attacks, Firebase requests/uploads, production key access, app source fixes, staging, commits or pushes.
- Files read: NOTES.md (session start and latest checkpoints).
- Files read: androidApp/src/main/AndroidManifest.xml; res/xml/share_paths.xml; data_extraction_rules.xml; backup_rules.xml.
- Files read: shared/src/commonMain/kotlin/com/ezfiles/reader/presentation/viewers/HtmlViewerPolicy.kt; shared/src/androidMain/kotlin/com/ezfiles/reader/presentation/viewers/HtmlViewerPage.android.kt (relevant ranges).
- Files read: Android DocumentDelivery, DocumentPicker, PageFolder and AssistantHandoffs implementations (relevant security ranges); engine/Cargo.toml; engine/crates/document-parsers/src/zip.rs and xml.rs (test/security ranges); docs/FEATURES.md (current documentation constraints).
- Exact artifact: androidApp/build/outputs/apk/release/androidApp-release.apk, SHA-256 084a1869e486e28aff38cfb011e4761a30cf22249af0e7316d3537ad007c0ea8. Hash rechecked unchanged after assessment. Prior v2 signature/16 KB alignment verification retained. Manifest has no INTERNET permission. Scanned archive has no duplicate/traversal entries, credential-pattern/private-key hits or suspicious packaged key files; heuristic coverage cannot prove absence of secrets. Scanned native libraries have NX stacks, RELRO, immediate binding and no writable/executable LOAD segments.
- Finding WEB-01: medium candidate, source-backed shared-origin HTML DOM storage. Arbitrary local HTML uses the same page.ezfiles.invalid origin with DOM storage enabled, so one document may read another's localStorage. This is NOT a runtime-reproduced exploit of the signed APK. Recommended follow-up: isolate document origins/storage while preserving local-resource controls and add a two-document runtime regression. No app behavior changed in this audit.
- Runtime limit: created isolated EzFilesSecurityAudit20261113 AVD and dedicated ADB server 5039 excluding USB devices; installed target and synthetic content-provider fixture. Emulator package-manager launch failures affected both apps, with a storage-service internal exception on an update. No successful exploit test. Identity-checked audit emulator and dedicated ADB server were stopped; ignored fixture/AVD evidence retained. No existing AVD or phone modified.
- Validation: java -jar gradle/wrapper/gradle-wrapper.jar :shared:testDebugUnitTest --tests '*HtmlViewerPolicyTest' --tests '*DocumentDeliveryTest' --tests '*AssistantAccessTest' --tests '*AssistantActionsTest' --tests '*AssistantDraftTest' --tests '*CrashReportingTest' passed: 76 tests, zero failures/errors. Initial sandbox cache-lock access failure resolved by approved execution using existing Gradle cache. cargo test -p document-parsers --lib zip::tests passed 6; cargo test -p document-parsers --lib xml::tests passed 8 (6 XML plus 2 matching OOXML containment tests). Total 90 source tests; these are not proof of signed-APK runtime behavior. Gradle deprecation warnings remain.
- Files changed: NOTES.md only among tracked files. Ignored audit scripts/fixtures under .gradle/security; HTML/JSON reports and two network-free synthetic storage-check HTML files under androidApp/build/outputs/security. Existing single docs/FEATURES.md policy preserved.
- Next steps: review androidApp/build/outputs/security/apk-security-audit.html and JSON. Reproduce WEB-01 on a working isolated emulator/device with the synthetic seed/probe files, then scope any fix separately. Full DEX review, dependency advisory audit, native fuzzing, production devices and backend assessment were not covered; no security certification claimed.

## 2026-11-14: English-only offline Fily research

- Goal: Study the existing app and research optional human-like voice control and smart file management, including a small trainable local model. Research only; no application code, dependencies, model downloads/training, builds or device state changed.
- Owner constraints: English only; 6 GB RAM and above; "7s series" provisionally interpreted as Snapdragon 7s. Exact handset/chip generation remains unconfirmed. No implementation was requested.
- Files read/searched: relevant ranges/matches in 25 repository files, listed one per line in docs/VOICE_AND_FEATURES.md. NOTES.md was read first. No relevant EzFiles/Fily entry was found in the memory registry. Existing and concurrently changing application edits were preserved.
- Deliverable: appended dated section 9 to existing docs/VOICE_AND_FEATURES.md with 26 distinct primary-source URLs, code findings, language/wake/speech/embedding alternatives, RAG/KG/CAG/MCP roles, training/data/evaluation plan, offline model-pack import, master-off behavior and device acceptance gates. FEATURES.md remains the feature catalog; earlier voice proposal is retained with its generic recommendations explicitly superseded by the dated assessment.
- Final research recommendation: compare LFM2.5-350M Q4 (published file 229 MB) first for English tool use and short replies, FunctionGemma 270M for command specialization and Qwen3.5-0.8B for dialogue. Test sherpa keyword spotting, Whisper tiny.en/base.en, existing BM25 plus MiniLM retrieval and verified offline Android TTS. These are unbenchmarked candidates, not a proven production stack. Keep tool permissions and deterministic execution outside models.
- Findings: current keyword priority maps "find my latest resume" to ListRecent; the default contextual route ignores conversation context; library sheet passes null assistantContext; speech requests offline preference without guaranteeing it; default TTS does not enforce an offline voice; searched source/config has no implemented wake detector/inference runtime. Static findings only. FunctionGemma is explicitly not a direct dialogue model. Exact model/license/runtime and background microphone gates remain.
- Validation: inline Python checked UTF-8/newline/fences, all 25 evidence paths and 26 distinct source URLs; git -c core.safecrlf=false diff --check -- NOTES.md docs/VOICE_AND_FEATURES.md passed. No application tests/builds were run for this research-only documentation change; source references are not runtime certification.
- Files changed by this task: docs/VOICE_AND_FEATURES.md and NOTES.md only. No staging, commit or push.
- Next steps: owner reviews research; confirm exact phone, Fily pronunciation, background wake scope and package budget before a separately requested implementation/benchmark phase. English-only is confirmed. Personal file contents should be indexed locally rather than used as model training data.

## 2026-11-14: Local Fily training hardware feasibility

- Goal: Assess the owner's RTX 2050 4 GB for the proposed English Fily fine-tune; research only, training not started.
- Evidence: nvidia-smi --query-gpu=name,memory.total,memory.free,driver_version --format=csv,noheader returned NVIDIA GeForce RTX 2050, 4096 MiB total, 3965 MiB free, driver 621.24. Free memory is only a momentary snapshot. No GPU stress/load was run.
- Decision: LFM2.5-350M LoRA is a reasonable first local training trial; test batch size 1, 256-512 token sequences, rank 8 and gradient accumulation/checkpointing, using supported FP16 or quantized training paths. These are starting settings, not a verified fit or measured throughput. Do a short memory/compatibility trial before a full dataset run. Train tool behavior and clarification, keep user file facts in local retrieval. Quantize the evaluated export for phone inference; the existing GGUF inference artifact is not the initial training checkpoint.
- Sources checked: https://huggingface.co/LiquidAI/LFM2.5-350M and https://huggingface.co/docs/peft/en/developer_guides/quantization ; publisher documents LoRA SFT, and PEFT documents quantized LoRA. Neither source establishes this exact GPU/runtime's measured training memory.
- Files touched: NOTES.md only. Existing source edits preserved. Validation: documentation whitespace check; no environment installation, model download, app build, training or device benchmark. Next steps: a separately requested setup and short training-fit trial, followed by dataset preparation and held-out evaluation.

## 2026-11-14: Isolated Fily training workspace and active first GPU run

- Goal: Owner authorized an ignored training folder, Python venv, challenging English app-feature dataset and GPU training, with no application code edits. Created training/ and added /training/ to .gitignore. Existing/concurrent application edits were preserved. All scripts/data/model outputs/environment are deliberately untracked and need an independent backup.
- Files inspected before changes: .gitignore; latest NOTES.md; Downloads/LFM2 config.json, tokenizer_config.json, chat_template.jinja and README.md relevant ranges; assistant Actions/Context/Navigation/Access sources and Draft/Tidy signatures; docs/FEATURES.md feature/action ranges. The training README records one file per line. No relevant memory-registry match was found.
- Local model: the owner added model.safetensors during setup. Read-only inspection found 148 tensors; successfully loaded as LFM2.5-350M. Base-file SHA-256 1c9c77a4471a7f590f85240f74ed1fc26df7fbde88c3006724e2f93ca993ea4e. No replacement model download or Downloads writes. Supplied PyTorch 2.6.0+cu124 Python-3.13 Windows wheel installed into training/.venv; network dependency installation required approved execution after sandbox socket denial. Exact dependencies are in ignored training/requirements-lock.txt; pip check passed.
- Dataset v1: 2,494 train / 311 validation / 311 test conversations, 104 families and 37 training action contracts; 3,190 / 398 / 398 assistant targets. Exact conversation duplicates removed across splits. Added 24 separately authored challenge conversations excluded from training. Only fictional documents/IDs/text. Outputs use validated JSON tool/reply/clarify envelopes. 33 contracts represent existing workflows; four are explicitly future local adapters, not an implemented app API. Synthetic templates/entities are held out but task families are shared; this is not independent human/audio validation.
- Validation: full schema/authorized-ID/citation/access checks passed; all 37 tools covered in each generated split; 10 regression tests passed, including masking, invalid IDs, disabled access, evidence and ordering. All training/validation targets fit the 1,536-token budget; maximum training sequence 1,043 tokens, no truncation. No generated action executes. Git check-ignore confirmed both scripts and venv ignored.
- GPU smoke completed: RTX 2050, BF16 rank-8 LoRA, 2,998,272 trainable parameters; two longest-sequence optimizer steps succeeded with 1,552.7 MiB peak allocated tensor memory. Allocated tensors do not equal total driver/process GPU memory. Smoke adapter is training/runs/gpu-smoke-v1, not the final model.
- Base generation evaluation: fixed 30 held-out assistant turns, 8 valid JSON outputs, 1 exact match overall and 1/21 exact tool matches. Strict reply wording requires manual review. Baseline report is training/baseline-evaluation.json. No app/device accuracy claim.
- First full training run IN PROGRESS: training/.venv/Scripts/python.exe training/train.py --output runs/fily-lora-v1 (exec session 82670). One epoch = 798 optimizer steps, accumulation 4, max length 1,536, learning rate 1e-4, checkpointing; saves adapter/optimizer snapshots every 100 steps. Initial sampled validation loss 0.929235. At step 80 no failures; native PyTorch convolution fallback is slower but working. Do not claim completion from this checkpoint.
- Files changed so far: .gitignore, NOTES.md and ignored training/ only. No application tests/builds required because application code was not changed. Next steps: wait for full run, inspect saved report/weights, run fixed post-training and authored-challenge generation evaluations, record evidence and limitations. Do not stop at a successful smoke run or imply phone integration/export is complete.

### First pass completed; second pass in progress

- This replaces the first-run-in-progress status above: v1 completed all 798 optimizer steps on the RTX 2050 in 1,329.91 seconds. Sampled validation loss 0.929235 -> 0.042181; peak allocated tensor memory 1,566.8 MiB. Saved adapter training/runs/fily-lora-v1/adapter_model.safetensors is 12,017,104 bytes; all 184 saved tensors are finite. Dataset hashes and unchanged original base-model hash were verified.
- V1 matched all 30 sampled synthetic test turns, including all 21 tool outputs, versus base 1/21 tool matches. Separately authored challenge: 32/33 JSON, 29/33 schema/authorization-valid, 12/16 exact tool matches, 12/33 overall strict matches. Manual inspection found real errors beyond wording: false physical-print completion, fabricated macro capability/count, invalid page-range JSON, invented tool/argument/citation, and incorrect cancellation/date reasoning. Do not present the easy synthetic result as production readiness.
- Created a second-pass contrastive curriculum without reading/copying challenge rows: 540 new hard training conversations plus 384 old replay = 924 conversations/1,337 assistant targets; 81 hard validation plus 60 old replay = 141 conversations/205 targets. Categories were informed by challenge failures, so that challenge is now a development diagnostic. Added 16 fresh acceptance conversations authored after the second-pass dataset was frozen, excluded from both passes. Both dataset builders validated all rows; original 10 regressions passed again after adding continuation support.
- V2 training IN PROGRESS (exec session 34195): continue v1 adapter into a fresh runs/fily-lora-v2 output, one epoch/335 optimizer steps, learning rate 5e-5. New sample baseline validation loss 1.289887; no token truncation, max training length 1,042. V1 output remains intact. No application code or original model changes.
- Next: finish v2, evaluate fixed sample plus development challenge and fresh acceptance, inspect actual failures, preserve provenance/limitations and record final commands/results. A saved adapter is not a runnable app integration or a standalone phone model.

### Final training and evaluation checkpoint

- This replaces both run-in-progress statuses above: v1 and v2 finished successfully; no training process remains active. V2 completed 335 additional CUDA optimizer steps in 614.05 seconds; sampled hard-validation loss 1.289887 -> 0.005774; peak allocated tensor memory 1,562.8 MiB. Both adapters are retained with their original training logs/checkpoints. V2's 184 saved adapter tensors were verified finite. Base weights remain unchanged.
- Final outputs: training/runs/fily-lora-v2/adapter_model.safetensors (approximately 12 MB, requires original base model), FILY_MODEL_CARD.md, training/EVALUATION.md, training/training-summary.json, locked venv dependencies, reproducible dataset builders/validators/trainer/evaluator, raw generation reports and readable samples. Entire training/ is Git-ignored as requested; it is not backed up by Git. No application code, dependencies, credentials or original Downloads files were changed by this task.
- Dataset totals: 3,034 unique authored-synthetic training conversations across passes (2,494 core + 540 contrastive), with 384 old examples replayed in the second pass; separate generated validation/test sets, 24 diagnostic challenge conversations and 16 fresh acceptance conversations. Some template/utterance overlap remains across generated splits. Four of 37 action contracts are explicitly future local adapters; others represent existing workflows but still require ABI/schema reconciliation before integration.
- V2 fixed synthetic generation sample: 30/30 JSON, 30/30 contract-valid, 30/30 strict matches, 21/21 exact tool outputs. V2 diagnostic challenge: 31/33 JSON, 29/33 contract-valid, 15/16 exact tool outputs (v1 was 12/16), 15/33 overall strict matches. Because challenge failure categories informed v2, its post-tuning result is diagnostic, not an untouched benchmark.
- Fresh acceptance (excluded from both passes): 20/20 JSON, 19/20 contract-valid, 8/11 exact tool outputs, 8/20 overall strict matches. Strict reply comparison rejects valid paraphrases; manual review also found real failures: casual date sort defaults to relevance, verbatim dictation altered, omitted summary decision, reversed price-change direction, unavailable-track suggestion and invented draft content on an unsupported overwrite request. Diagnostic review additionally found document-instruction contamination, incorrect missing-date reasoning and malformed JSON. Prototype is explicitly NOT accepted for unattended file control or production. No generated tool was executed.
- Validation commands/evidence: training/.venv/Scripts/python.exe -m unittest discover -s training -p test_training.py -q passed all 10 checks after continuation support; pip check passed; script compile checks passed; builders validated every row, all 37 core contracts were covered per split, manifest hashes matched, and no train/validation targets were truncated. Evaluation commands are recorded exactly in training/README.md and runs/*/report.json. Training used the RTX 2050 and local pretrained weights; dependency installation used networking, training/inference did not use cloud services. No application build/device/audio/phone benchmark was run.
- Files changed by this task: .gitignore, NOTES.md and ignored training/ only. Earlier/concurrent app edits were preserved; repository state changed independently during the long run. No staging, commit or push was performed by this task. Final documentation whitespace/ignore checks passed.
- Next steps: broader independently reviewed English requests and faithful summaries; fresh semantic/adversarial evaluation; compare a larger model if 350M remains brittle. Phone quantization/export, wake/ASR/TTS, local retrieval adapters and app integration remain separate. Do not train directly on personal file contents or treat synthetic scores as a completed human-like assistant.

## 2026-11-14: Focused EzFiles-only training and quantized export (in progress)

- Owner clarified the scope: English conversation about supported files and app features, file questions/actions; train and quantize for small/fast offline inference. App source remains unchanged. Read current NOTES.md, training summary/README/catalog/trainer relevant ranges and current docs/FEATURES.md format/editing sections before changes; original Downloads model is read only. Found current documented Save As document replacement, PPTX slide tools and XLSX cell editing; represented these as help facts, not newly exposed voice actions.
- Created a domain curriculum: 744 training conversations/824 assistant targets (including 160 old replay), 142 validation conversations, 102 held-out domain conversations. Covers 14 documented format groups, 28 help topics, brief conversation, out-of-scope redirection, file finding/opening, clarification and grounded questions. V3 GPU pass completed 206 steps in 303.92 seconds, sampled validation loss 2.211430 -> 0.268411, 1,464.9 MiB peak allocated tensors.
- Downloaded official pinned llama.cpp b12377 Windows CPU tools and source to ignored training/tools. Binary archive SHA-256 d3b60c5abbe3a131c77e568310547492438bc7c785721eb765d2d817fe4424c5 verified against GitHub asset digest. Installed matching local gguf package plus tokenizer dependencies in the venv; original CUDA PyTorch installation retained. Converter and LFM2 conversion path inspected. No app dependencies changed.
- V3 merged/exported standalone GGUF artifacts: F16 711,487,520 bytes; Q8_0 379,220,000; Q4_K_M 229,314,592; Q4_0 219,312,160. Separate base/LoRA weights are not required for GGUF inference. Original base weights were not modified. Exports and checksums are under training/exports/fily-domain-v3. These are preliminary, not recommended final models: both unquantized and quantized feature answers invented unsupported extensions.
- Fixed a test-harness error: raw string completion added a second BOS to the already templated input. Quantized evaluator now sends explicit tokenizer IDs containing one BOS. Added regression coverage. First speed run overlapped a smoke test, so final benchmarks must run in isolation before making speed claims.
- Follow-up grounded domain curriculum supplies an 8,542-byte verified capability record (14 format groups/28 topics) via APP_STATE.capabilityFacts, sourced from a feature-document hash. This tests provided app facts rather than unsupported memorization claims. V4 GPU continuation is IN PROGRESS (exec session 64968), output training/runs/fily-domain-v4, 206 planned steps, baseline sampled grounded-validation loss 0.081215. No truncation, max target length 963; at step 110 stable.
- Validation so far: 14 unit/regression tests passed, including capability-source hash, grounded help contexts, schema rejection of invented actions and single-BOS inputs. Python syntax and pip checks passed. GGUF local CPU server smoke ran; no generated tool executes. Structured JSON constraints are explicitly part of the quantized test runtime, not evidence of unconstrained accuracy.
- Next: finish v4; export/quantize it; calibrate importance-aware Q4 using training data only; compare Q4/Q8/reference quality with correct tokenization; run isolated CPU speed benchmarks; record size, quality and latency without claiming Snapdragon/phone measurements. Preserve v1/v2/v3 histories and leave app source untouched.

### Focused domain model and quantization completed

- This replaces the in-progress statuses in this section. V4 grounded GPU continuation completed all 206 optimizer steps in 333.49 seconds; sampled validation loss 0.081215 -> 0.006025; 1,486.0 MiB peak allocated tensors. V3 and V4 adapters, prior v1/v2 adapters, datasets and reports are preserved. App scope is English file requests, EzFiles format/feature help, brief conversation, clarification and structured action requests. Training did not erase the pretrained model's general knowledge; runtime policy and domain data direct its behavior.
- Final standalone exports are under ignored training/exports/fily-domain-v4. Q8_0 quality default: 379,220,000 bytes, SHA-256 c146e969f0535da64586b7409ebb315435328a8268b9905e04bb6fc17d62221e. Small/fast calibrated Q4_K_M-I: 229,314,816 bytes, SHA-256 1ad51bee061afb0920296129888501b96ad1c9a0e0355f48e9598fe31c5ca794. F16 reference 711,487,520 bytes; ordinary Q4_K_M and Q4_0 retained as comparisons. GGUF embeds merged trained weights/tokenizer, so no separate base/LoRA is needed for inference. A compatible runtime and app context/action adapters remain necessary.
- The model pack includes app_capabilities.json (8,542 bytes; 14 documented format groups/28 topics), system_policy.txt, tool_catalog.json, inference-config.json, manifest/checksums, README and original LICENSE. Caller must supply relevant capability facts, authorized state and real retrieval/tool results. Current facts are based on the verified FEATURES.md snapshot; this is provided-context grounding, not a claim of reliable unsupported feature memorization.
- Importance calibration used only 32 training records, 16 chunks of 512 tokens, with 92 measured matrix entries. No evaluation text was used. Logs, data hashes and calibration provenance are saved. No original Downloads files or model weights were changed.
- Found and fixed another inference-harness issue: llama.cpp's full per-tool JSON schema reordered required/optional argument properties and suppressed resume category arguments. Final comparisons constrain envelope/tool name, preserve free argument order, then validate the complete strict argument/ID/citation contract after decoding. Pre-fix reports are preserved and excluded from final results. Added regression proving invalid decoded arguments remain rejected; 15 unit/regression tests passed.
- Corrected same-input quant comparison: 42 authored synthetic conversations/45 assistant turns, supplied capability facts, correct histories and permitted tool subsets. F16 and Q8 each had 45/45 JSON and contract-valid outputs, 7/7 exact action checks, 14/14 exact format extension lists and 43/45 strict overall matches. The two strict mismatches were harmless capitalization/clarification wording. Calibrated Q4 had 45/45 JSON/contract validity, 7/7 exact actions, 13/14 exact extension lists and 39/45 strict overall matches; it incorrectly refused a ZIP-extension question. Q4_0 had 40/45 contract-valid outputs and only 3/7 exact actions, so it is not recommended. This small synthetic/template-related comparison is not independent human or full app-action acceptance; the seven action turns cover search/open/excerpt selection.
- Final isolated Windows CPU benchmark: llama.cpp b12377, four threads, zero GPU layers, three repeats, 256 prompt tokens/64 generated tokens. F16 prefill 369.8 and generation 59.8 tokens/s; Q8 prefill 436.7/generation 96.8; ordinary Q4_K_M 967.5/159.8; calibrated Q4_K_M-I 1,015.9/159.5; Q4_0 805.1/173.3. Calibrated Q4 is 3.10x smaller and 2.67x faster generating tokens than F16 on this host. Q8 is 1.62x faster and retains stronger tested quality. These are not Snapdragon/Android or end-to-end voice measurements. Earlier overlapping speed runs were discarded.
- Final verification: all five final GGUF files passed header/size/SHA-256 checks; original base SHA-256 unchanged; Python syntax, pip dependency and Git whitespace/ignore checks passed; no owned training/evaluation/benchmark worker remains running. Local inference servers were restricted to 127.0.0.1 and terminated; no generated action executed. No app build or device/audio test was run because app source was not edited.
- Deliverables: training/QUANTIZATION_REPORT.md, updated training/README.md and training-summary.json, model-pack README/config/contracts, raw per-model evaluations and native benchmark reports, reproducible training/export/calibration/evaluation scripts. Entire training/ remains ignored and needs a separate backup. Files changed by this continuation: NOTES.md and ignored training/ only; existing app edits preserved. No staging, commit or push.
- Next steps: choose Q8 for quality or calibrated Q4 for size/speed, integrate the validated local model/action/retrieval/speech lifecycle only when requested, and run independent English/phone/permission tests. Optional wake/ASR/TTS and actual app execution are not implemented by a model file. Do not claim every supported workflow or arbitrary human phrasing is validated by seven action checks.

## 2026-11-14: Expanded app intelligence plan and five-hour GPU job started

- Goal: Owner requested more app edge cases and five hours of training, then additionally asked what more the model could do inside the app. Preserved both requests. No application code changes authorized or performed; existing reader/theme/viewer edits were preserved.
- Product deliverable: training/APP_INTELLIGENCE_PLAN.md maps conversational discovery, version comparison, document questions, related documents, review-only filing, duplicate explanations, guided PDF/capture/reading/editing, deadline awareness, local preferences, opt-in suggestions and recovery help to model responsibilities and required app services. These are proposals; no new app capabilities/APIs are claimed.
- Preparation plan: build an app-specific edge curriculum, audit splits/token lengths, test the GPU and recovery path, then start a bounded background supervisor with automatic evaluation/quantization after training. Files read: latest NOTES.md; training/train.py, catalog.py, validate_dataset.py, relevant builder helpers, and the v4 runtime configuration. Current app feature/capability snapshot from prior turn was retained and hash-tested.
- Dataset: training/data/edge_v5 has 46,987 train conversations/63,587 assistant targets; 1,887 validation/2,551 targets; 1,882 test/2,546 targets. 250 scenario families and all 37 training contracts are covered. Exact conversation leakage across splits was independently audited and absent. Templates/task primitives still overlap; this is authored synthetic data, not comprehensive edge coverage or an independent human corpus. No personal file was read.
- New cases cover same-name files, ownership/date ambiguity, copied/equal/missing dates, incomplete indexes, stale results, revoked access, offline placeholders, provider/time/resource failures, passwords and extraction failure, partial/contradictory/revised text, document instructions, invalid pages/ordering/selections, unsaved work, failed/cancelled/unconfirmed outputs, unavailable tracks/codecs, duplicate-review limits, proposed grouping, context reset, memory opt-out, exact dictation and comparison. Fixed ambiguous synthetic 'Prepare this for me' labels by making the requested action explicit.
- Training infrastructure: new disk-backed int32 token cache with bounds/count/fingerprint checks; assistant-only masking; CUDA-only rank-8 continuation from v4; shuffled sampler; fixed stratified validation; checkpoint/optimizer/scaler/RNG/sampler recovery; dataset/config signature checks; 18,000-second loop budget; best checkpoint retained. Cache setup is outside the training budget. Periodic validation/checkpointing is inside; final validation/export/evaluation are additional. No silent truncation or CPU fallback.
- Verification: 24 unit/regression tests passed; pip check passed; all generated rows/schema/source hashes/tool coverage checked; full split audit passed. Longest-example two-step CUDA smoke passed at 1,636.5 MiB peak allocated tensors. Separate resume trial restored step 2 and completed step 3, exercising optimizer/RNG/sampler restoration. Previous model weights/exports and smoke outputs preserved.
- Actual five-hour supervisor is RUNNING, not completed: pipeline PID 16700 (Start-Process launcher PID 23292), exec launch session 18468. Output training/runs/fily-edge-v5-5h. It was launched hidden with logs, prevents duplicate workers, and temporarily requests Windows stay awake; no persistent power settings changed. Verified live CUDA training at step 10, RTX 2050 utilization 98%, 3,517 MiB driver memory, 70.75 C. Live ETA at that check: 2026-11-14T17:47:07 UTC, approximately 23:17 IST, plus final evaluation/export time. Progress is not a completed-model claim.
- Supervisor will automatically merge/export the best checkpoint, calibrate Q4 using training data only, compare baseline/new Q8/Q4 predictions on held-out edge and capability cases, run isolated CPU benchmarks, save completed.json/RUN_REPORT.md and append an actual-completion entry here. No generated app action executes. It records failures/resume instructions rather than silently declaring success; older defaults remain until results are reviewed.
- Status: training/.venv/Scripts/python.exe training/status_five_hour.py. Instructions including stop/resume are in training/FIVE_HOUR_RUN.md. Current source of truth is progress.json and pipeline.json. New files/models remain under ignored training/; root NOTES.md is the only tracked file changed by this continuation. No staging, commit, push, app build, voice test or phone acceptance.
- Next: allow the background job to finish, inspect its completed/failure state and actual semantic results, then select/export for separately requested app integration. Keep the laptop powered; the job does not survive power-off. Do not call the five-hour run finished or promise exhaustive edge-case handling before its recorded results exist.


## 2026-11-14: Owner monitoring commands

- Goal: Provide step-by-step PowerShell commands for the already running job. Read current job/status instructions and checked the supervisor; PID 16700 was alive and training had reached step 200 with approximately 17,654 seconds remaining.
- Decision: Owner should monitor the existing run rather than launch another. Commands use the venv Python directly, so activation is unnecessary; live metrics, final reports/exports and failure-only resume instructions were provided.
- Files touched: NOTES.md only. Validation: live status/PID check; no app code, training parameters or worker state changed. Next: let the existing job and automatic post-processing finish.
