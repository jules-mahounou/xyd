import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Config lue (par ordre de priorité) : variables d'environnement (CI) puis local.properties (Android Studio).
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun cfg(env: String, prop: String, def: String = ""): String =
    System.getenv(env)?.takeIf { it.isNotBlank() } ?: localProps.getProperty(prop) ?: def

val keystoreFile = cfg("XYD_KEYSTORE_FILE", "xyd.keystore.file")

android {
    namespace = "tech.xydhub.xyd"
    compileSdk = 35

    defaultConfig {
        applicationId = "tech.xydhub.xyd"
        minSdk = 26
        targetSdk = 35
        versionCode = cfg("XYD_VERSION_CODE", "xyd.versionCode", "1").toInt()
        versionName = cfg("XYD_VERSION_NAME", "xyd.versionName", "0.1.0")

        buildConfigField("String", "SUPABASE_URL", "\"${cfg("SUPABASE_URL", "supabase.url").trimEnd('/')}\"")
        buildConfigField("String", "SUPABASE_KEY", "\"${cfg("SUPABASE_ANON_KEY", "supabase.anonKey")}\"")
    }

    // Seul le français est embarqué : on retire les traductions de toutes les libs.
    androidResources {
        localeFilters += listOf("fr")
    }

    signingConfigs {
        if (keystoreFile.isNotBlank()) {
            create("release") {
                storeFile = file(keystoreFile)
                storePassword = cfg("XYD_KEYSTORE_PASSWORD", "xyd.keystore.password")
                keyAlias = cfg("XYD_KEY_ALIAS", "xyd.key.alias")
                keyPassword = cfg("XYD_KEY_PASSWORD", "xyd.key.password")
                enableV1Signing = false
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (keystoreFile.isNotBlank()) signingConfigs.getByName("release")
            else signingConfigs.getByName("debug")
        }
        debug {
            applicationIdSuffix = ".debug"
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources {
            // On garde META-INF/services (utilisé par les coroutines), on vire le reste inutile.
            excludes += listOf(
                "META-INF/*.version",
                "META-INF/*.kotlin_module",
                "META-INF/**/LICENSE*",
                "META-INF/**/NOTICE*",
                "META-INF/androidx*",
                "kotlin/**",
                "DebugProbesKt.bin",
                "**/*.kotlin_builtins",
            )
        }
    }

    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        freeCompilerArgs.addAll(
            "-Xno-param-assertions",
            "-Xno-call-assertions",
            "-Xno-receiver-assertions",
        )
    }
}

dependencies {
    // UI : Compose + Material3 uniquement (pas de navigation-compose, pas de Coil, pas de Hilt).
    implementation(platform("androidx.compose:compose-bom:2025.04.00"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")

    // Lecteur : uniquement le cœur ExoPlayer (pas de media3-ui, pas de HLS/DASH).
    implementation("androidx.media3:media3-exoplayer:1.6.1")

    // Réseau : HttpURLConnection + org.json du framework Android -> 0 octet de dépendance.
}
