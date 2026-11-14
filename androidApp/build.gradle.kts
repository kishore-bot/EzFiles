plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.androidx.baselineprofile)
}

// Signing values live outside the repo: in ~/.gradle/gradle.properties or the
// environment. Without all four, a clean checkout still builds and release
// stays unsigned.
fun signingValue(property: String, environment: String): String? =
    providers.gradleProperty("ezfiles.signing.$property")
        .orElse(providers.environmentVariable(environment))
        .orNull
        ?.takeIf { it.isNotBlank() }

val signingStoreFile = signingValue("storeFile", "EZFILES_SIGNING_STORE_FILE")
val signingStorePassword = signingValue("storePassword", "EZFILES_SIGNING_STORE_PASSWORD")
val signingKeyAlias = signingValue("keyAlias", "EZFILES_SIGNING_KEY_ALIAS")
val signingKeyPassword = signingValue("keyPassword", "EZFILES_SIGNING_KEY_PASSWORD")
val hasSigningValues = signingStoreFile != null && signingStorePassword != null &&
    signingKeyAlias != null && signingKeyPassword != null && file(signingStoreFile).isFile

// Builds one APK per ABI instead of a universal one, for direct sharing.
// AGP's ABI splits apply to every build type and refuse ndk.abiFilters, so they
// are opt-in. Bundles must not use this: see the check at the end of this file.
val abiSplit = providers.gradleProperty("ezfiles.abiSplit").map { it.toBoolean() }.getOrElse(false)
val appAbis = setOf("arm64-v8a", "x86_64")
val firebaseReports = providers.gradleProperty("ezfiles.firebaseCrashReports").map { it.toBoolean() }.getOrElse(false)
if (firebaseReports) {
    check(file("google-services.json").isFile) { "Firebase diagnostics requires androidApp/google-services.json" }
    apply(plugin = "com.google.gms.google-services")
    apply(plugin = "com.google.firebase.crashlytics")
}

android {
    sourceSets.getByName("main").java.srcDir(if (firebaseReports) "src/firebase/kotlin" else "src/offline/kotlin")
    if (firebaseReports) sourceSets.configureEach {
        if (name in setOf("debug", "release", "share", "benchmark")) {
            manifest.srcFile(if (name == "benchmark") "src/firebase/benchmark-manifest.xml" else "src/firebase/AndroidManifest.xml")
        }
    }
    namespace = "com.ezfiles.android"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.ezfiles"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = 1
        versionName = "0.1.0"
        // Also keeps out the 32-bit libraries ML Kit and JNA ship: the engine has none.
        if (!abiSplit) ndk.abiFilters += appAbis
    }
    splits {
        abi {
            isEnable = abiSplit
            reset()
            include(*appAbis.toTypedArray())
            isUniversalApk = false
        }
    }
    signingConfigs {
        if (hasSigningValues) {
            create("upload") {
                storeFile = file(signingStoreFile!!)
                storePassword = signingStorePassword
                keyAlias = signingKeyAlias
                keyPassword = signingKeyPassword
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("upload")
        }
        // Release code with the debug key, for measuring frame times locally.
        // Never distribute this variant.
        create("benchmark") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += "release"
        }
        // Release code for sharing as an APK outside Play, with compressed native
        // libraries (see androidComponents below). Falls back to the debug key so a
        // clean checkout can test it; only distribute a build signed with your own key.
        create("share") {
            initWith(getByName("release"))
            // Distinguish local test APKs in Info/crash reports from production Release.
            versionNameSuffix = "-share-${providers.gradleProperty("ezfiles.buildLabel").getOrElse("local")}"
            signingConfig = signingConfigs.findByName("upload") ?: signingConfigs.getByName("debug")
            matchingFallbacks += "release"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    // Custom release-code variants must explicitly consume the release profiles.
    sourceSets.getByName("benchmark").baselineProfiles.srcDir("src/release/generated/baselineProfiles")
    sourceSets.getByName("share").baselineProfiles.srcDir("src/release/generated/baselineProfiles")
}

// Scoped to the share type: set in the android block, this would also reach the
// App Bundle.
androidComponents {
    onVariants(selector().withBuildType("share")) { variant ->
        variant.packaging.jniLibs.useLegacyPackaging.set(true)
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    if (firebaseReports) {
        implementation(platform(libs.firebase.bom))
        implementation(libs.firebase.crashlytics)
        // These already ship with this Crashlytics version; expose their APIs for the consent filter.
        implementation("com.google.android.datatransport:transport-runtime:5.0.0")
        implementation("com.google.android.datatransport:transport-backend-cct:5.0.0")
    }
    implementation(project(":shared"))
    implementation(libs.androidx.activity.compose)
    // Installs the Baseline Profile on sideloaded APKs too; Play does it for bundles.
    implementation(libs.androidx.profileinstaller)
    baselineProfile(project(":baselineprofile"))
}

// Prepare local artifacts only; cloud mapping/symbol uploads require a separate release step.
tasks.configureEach {
    if (name.startsWith("uploadCrashlytics")) enabled = false
}

baselineProfile {
    // Generated on demand (see baselineprofile/build.gradle.kts) and committed,
    // so a normal release build never needs a device.
    automaticGenerationDuringBuild = false
    // Only library launch enters the Startup Profile used for DEX layout.
    dexLayoutOptimization = true
}

gradle.taskGraph.whenReady {
    val names = allTasks.filter { it.project == project }.map { it.name }
    // AGP rejects this late and only for shrunk variants (issuetracker 402800800).
    if (abiSplit && names.any { it.matches(Regex("package\\w+Bundle")) }) {
        throw GradleException("-Pezfiles.abiSplit=true is for APKs only. Build bundles without it.")
    }
    if (!hasSigningValues && "packageShare" in names) {
        logger.warn("Share APK is signed with the debug key: for testing only, do not distribute.")
    }
}
