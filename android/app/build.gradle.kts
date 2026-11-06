plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val repoRoot = rootProject.projectDir.parentFile

// Environment variables take precedence; -P properties are convenient for local builds.
fun signingValue(vararg names: String): String? =
    names.firstNotNullOfOrNull { System.getenv(it)?.takeIf(String::isNotBlank) }
        ?: names.firstNotNullOfOrNull { (project.findProperty(it) as? String)?.takeIf(String::isNotBlank) }
val releaseStorePath = signingValue("RYANMUSIC_STORE_FILE", "ANDROID_KEYSTORE_PATH")
val releaseStorePassword = signingValue("RYANMUSIC_STORE_PASSWORD")
val releaseKeyAlias = signingValue("RYANMUSIC_KEY_ALIAS")
val releaseKeyPassword = signingValue("RYANMUSIC_KEY_PASSWORD")

val validateReleaseSigningInputs = tasks.register("validateReleaseSigningInputs") {
    doLast {
        val missing = listOf(
            "RYANMUSIC_STORE_FILE or ANDROID_KEYSTORE_PATH" to releaseStorePath,
            "RYANMUSIC_STORE_PASSWORD" to releaseStorePassword,
            "RYANMUSIC_KEY_ALIAS" to releaseKeyAlias,
            "RYANMUSIC_KEY_PASSWORD" to releaseKeyPassword,
        ).filter { it.second == null }.map { it.first }
        if (missing.isNotEmpty()) {
            throw GradleException("Release signing is not configured. Missing: ${missing.joinToString()}. See android/README.md.")
        }
        if (!rootProject.file(releaseStorePath!!).isFile) {
            throw GradleException("Release keystore file does not exist. Check RYANMUSIC_STORE_FILE or ANDROID_KEYSTORE_PATH.")
        }
    }
}
tasks.matching { it.name == "preReleaseBuild" }.configureEach {
    dependsOn(validateReleaseSigningInputs)
}

android {
    namespace = "cn.ryanmusic.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "cn.ryanmusic.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 2017
        versionName = "2.0.17"
        val cloudOriginFile = File(repoRoot, "shared/cloud-origin.txt")
        val cloudOrigin = cloudOriginFile
            .takeIf { it.exists() }
            ?.readText()
            ?.trim()
            ?.trimEnd('/')
            ?.plus("/")
            ?: "https://ryanmusic.vercel.app/"
        buildConfigField("String", "CLOUD_ORIGIN", "\"$cloudOrigin\"")
    }

    buildFeatures {
        buildConfig = true
    }

    signingConfigs {
        create("release") {
            if (releaseStorePath != null) storeFile = rootProject.file(releaseStorePath)
            storePassword = releaseStorePassword
            keyAlias = releaseKeyAlias
            keyPassword = releaseKeyPassword
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.webkit:webkit:1.11.0")
}
