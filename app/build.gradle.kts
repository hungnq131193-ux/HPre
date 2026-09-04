import org.gradle.api.provider.Provider
import org.gradle.api.GradleException

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.ksp)
}

fun signingValue(propertyName: String, environmentName: String): Provider<String> =
    providers.gradleProperty(propertyName)
        .orElse(providers.environmentVariable(environmentName))

val releaseStoreFile = signingValue("hpreSigning.storeFile", "HPRE_SIGNING_STORE_FILE")
val releaseStorePassword = signingValue(
    "hpreSigning.storePassword",
    "HPRE_SIGNING_STORE_PASSWORD"
)
val releaseKeyAlias = signingValue("hpreSigning.keyAlias", "HPRE_SIGNING_KEY_ALIAS")
val releaseKeyPassword = signingValue(
    "hpreSigning.keyPassword",
    "HPRE_SIGNING_KEY_PASSWORD"
)

android {
    namespace = "com.hpre.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.hpre.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 32
        versionName = "1.0.31"

        testInstrumentationRunner = "com.hpre.app.testing.HPreTestRunner"
        vectorDrawables {
            useSupportLibrary = true
        }

        ksp {
            arg("room.schemaLocation", "$projectDir/schemas")
        }
    }

    sourceSets {
        getByName("androidTest") {
            assets.srcDirs("$projectDir/schemas")
        }
    }

    signingConfigs {
        create("release") {
            storeFile = releaseStoreFile.orNull?.let(::file)
            storePassword = releaseStorePassword.orNull
            keyAlias = releaseKeyAlias.orNull
            keyPassword = releaseKeyPassword.orNull
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
            applicationIdSuffix = ""
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

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

val validateHpreReleaseSigning by tasks.registering {
    group = "verification"
    description = "Validates that HPre release signing credentials are configured."

    doLast {
        val missing = buildList {
            if (releaseStoreFile.orNull.isNullOrBlank()) add("store file")
            if (releaseStorePassword.orNull.isNullOrBlank()) add("store password")
            if (releaseKeyAlias.orNull.isNullOrBlank()) add("key alias")
            if (releaseKeyPassword.orNull.isNullOrBlank()) add("key password")
        }

        if (missing.isNotEmpty()) {
            throw GradleException(
                "HPre release signing is incomplete: missing ${missing.joinToString()}. " +
                    "Configure hpreSigning.* Gradle properties or HPRE_SIGNING_* environment variables."
            )
        }

        val keystore = file(releaseStoreFile.get())
        if (!keystore.isFile) {
            throw GradleException("HPre release keystore does not exist at the configured path.")
        }
    }
}

tasks.configureEach {
    if (name == "preReleaseBuild") {
        dependsOn(validateHpreReleaseSigning)
    }
}

dependencies {
    // AndroidX Core & Lifecycle
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)

    // Compose Material 3 BOM
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)

    // Media3 Playback
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.exoplayer.hls)
    implementation(libs.media3.exoplayer.dash)
    implementation(libs.media3.session)
    implementation(libs.media3.ui)
    implementation(libs.media3.datasource.okhttp)

    // Persistence (Room)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    // Networking, Image Loading & Async
    implementation(libs.okhttp)
    implementation(libs.okhttp.brotli)
    implementation(libs.coil.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.moshi)

    // Extractor (Only accessed via extractor adapter in Task 3)
    implementation(libs.newpipe.extractor)

    // Unit Testing
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.room.testing)
    testImplementation(libs.room.runtime)

    // Android Instrumentation Testing
    androidTestImplementation(libs.androidx.test.ext)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation(libs.room.testing)

    // Debug tooling
    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)
}
