plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.artem.blackoutwidget"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.artem.blackoutwidget"
        minSdk = 26
        targetSdk = 36
        // Google Play wants a higher versionCode for every upload; CI builds number themselves.
        versionCode = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
        versionName = "1.0"
        // The commit this build was made from (CI only); the sideload build compares it with the latest release.
        buildConfigField("String", "COMMIT", "\"${System.getenv("GITHUB_SHA")?.take(7) ?: "dev"}\"")
    }

    // A fixed, public "debug" keystore is committed on purpose: every CI build is signed with the
    // same key, so a new APK installs over the previous one without uninstalling. The app holds no secrets.
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    // "github" is installed by hand from GitHub and offers its own updates; "play" goes to Google Play,
    // which forbids an app updating itself, so it has no updater.
    flavorDimensions += "dist"
    productFlavors {
        create("github") { dimension = "dist" }
        create("play") { dimension = "dist" }
    }

    // Only the two languages the app has; libraries ship strings for dozens of others.
    androidResources {
        localeFilters += listOf("en", "uk", "ru")
    }

    // The key that signs what is uploaded to Google Play. Its file and passwords come from the build
    // environment (GitHub secrets) and are never committed; without them the release build is signed
    // with the public debug key above, as before.
    val uploadStore = System.getenv("UPLOAD_KEYSTORE_FILE")
    if (uploadStore != null) {
        signingConfigs {
            create("upload") {
                storeFile = file(uploadStore)
                storePassword = System.getenv("UPLOAD_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("UPLOAD_KEY_ALIAS")
                keyPassword = System.getenv("UPLOAD_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            // R8 and resource shrinking make the APK much smaller. The release build is signed with the
            // same fixed key as the debug one, so either installs over the other.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName(if (uploadStore != null) "upload" else "debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    // This is a sideloaded personal app; style/lint findings must not block building the APK.
    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.glance:glance-appwidget:1.1.1")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    testImplementation("junit:junit:4.13.2")
    // Real org.json for JVM unit tests (the android.jar one is a stub).
    testImplementation("org.json:json:20240303")
}
