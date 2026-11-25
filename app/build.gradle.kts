import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "cloud.depup.office"
    compileSdk = 36

    // Needed for debug-only diagnostics in the editor. Release builds expose DEBUG=false
    // so the diagnostic HUD can be removed by R8 and never shipped to production.
    buildFeatures {
        buildConfig = true
    }

    defaultConfig {
        applicationId = "cloud.depup.office"
        minSdk = 29
        targetSdk = 36
        ndkVersion = "28.2.13676358"
        // versionCode = mayor*10000 + minor*100 + patch (4.4.0 -> 40400). Play sudah memakai hingga 40303 (4.3.1). 40000 sudah dipakai di alpha.
        versionCode = 40400
        versionName = "4.4.0"
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64") }
        externalNativeBuild { cmake { cppFlags += "-std=c++17" } }
        val localProps = Properties().apply {
            val f = rootProject.file("local.properties")
            if (f.exists()) f.inputStream().use { load(it) }
        }
        fun buildValue(name: String): String =
            (project.findProperty(name) as String?) ?: System.getenv(name) ?: localProps.getProperty(name) ?: ""
        fun javaString(value: String) = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        // Signing certificate hashes stay in local.properties / build environment, never in source.
        val releaseCerts = (buildValue("DEPUP_RELEASE_CERTS").split(',') + buildValue("DEPUP_EXTRA_CERTS").split(','))
            .map { it.trim() }.filter { it.isNotBlank() }.distinct().joinToString(",")
        buildConfigField("String", "RELEASE_CERTS", javaString(releaseCerts))
        // App hanya berbahasa Indonesia (values/ tunggal, tanpa values-xx). Tanpa baris ini, string
        // terjemahan dari AndroidX/Play Services/CameraX untuk puluhan bahasa lain tetap ikut terbundel.
        // (Sengaja TIDAK membatasi density: mipmap launcher sudah kecil, dan membatasi density
        // berisiko menghilangkan ikon yang pas untuk sebagian perangkat demi penghematan tak berarti.)
    }

    externalNativeBuild {
        cmake { path = file("src/main/cpp/CMakeLists.txt"); version = "3.22.1" }
    }

    androidResources {
        localeFilters += listOf("id", "en")
    }

    base.archivesName.set("DepupOffice_v${defaultConfig.versionName}")

    // Kredensial TIDAK boleh hardcode. Isi keystore.properties (di-gitignore) atau env var DEPUP_*.
    val keystoreProps = Properties().apply {
        val f = rootProject.file("keystore.properties")
        if (f.exists()) f.inputStream().use { load(it) }
    }
    fun cred(key: String, env: String): String? = keystoreProps.getProperty(key) ?: System.getenv(env)

    signingConfigs {
        create("release") {
            val path = cred("storeFile", "DEPUP_STORE_FILE")
            if (path != null) {
                storeFile = rootProject.file(path)
                storePassword = cred("storePassword", "DEPUP_STORE_PASSWORD")
                keyAlias = cred("keyAlias", "DEPUP_KEY_ALIAS")
                keyPassword = cred("keyPassword", "DEPUP_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            val releaseConfig = signingConfigs.getByName("release")
            if (releaseConfig.storeFile != null) {
                signingConfig = releaseConfig
            } else {
                logger.warn("WARNING: keystore.properties tidak ada -> AAB release TIDAK ter-sign.")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // Berkas metadata pustaka yang tidak dipakai saat aplikasi berjalan (versi AndroidX, modul/builtins Kotlin untuk
    // kotlin-reflect yang tidak dipakai) tidak ikut dikemas -> APK sedikit lebih kecil.
    packaging {
        resources {
            excludes += setOf(
                "META-INF/*.version",
                "META-INF/*.kotlin_module",
                "kotlin/**",
                "DebugProbesKt.bin",
                "META-INF/{AL2.0,LGPL2.1}",
            )
        }
    }

    // Blok metadata dependensi hanya berguna untuk Play Console (bundle); APK yang dibagikan langsung tidak memuatnya.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = true
    }

    // Fix relative keystore paths injected by Android Studio or Gradle CLI (-Pandroid.injected.signing.store.file)
    if (project.hasProperty("android.injected.signing.store.file")) {
        val storePath = project.property("android.injected.signing.store.file").toString()
        val targetFile = File(storePath)
        val rootFile = rootProject.file(storePath)
        val projectFile = file(storePath)

        val resolvedFile = when {
            targetFile.exists() -> targetFile
            rootFile.exists() -> rootFile
            projectFile.exists() -> projectFile
            rootProject.file(targetFile.name).exists() -> rootProject.file(targetFile.name)
            file(targetFile.name).exists() -> file(targetFile.name)
            else -> rootFile
        }

        project.extensions.extraProperties.set("android.injected.signing.store.file", resolvedFile.absolutePath)

        if (resolvedFile.exists()) {
            listOf(rootFile, projectFile, targetFile).forEach { fileLocation ->
                if (!fileLocation.exists()) {
                    try {
                        fileLocation.parentFile?.mkdirs()
                        resolvedFile.copyTo(fileLocation, overwrite = true)
                    } catch (_: Exception) {}
                }
            }
        }
    }
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

// Sengaja minim: tanpa Compose, tanpa library PDF pihak ketiga -> APK kecil & ringan.
dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.8.0")
    implementation("androidx.recyclerview:recyclerview:1.4.0")
    implementation("androidx.exifinterface:exifinterface:1.4.2")
    // Viewfinder kamera sendiri
    val camerax = "1.6.2"
    implementation("androidx.camera:camera-core:$camerax")
    implementation("androidx.camera:camera-camera2:$camerax")
    implementation("androidx.camera:camera-lifecycle:$camerax")
    implementation("androidx.camera:camera-view:$camerax")
    // OCR offline & QR Code Scanner
    implementation("com.google.mlkit:text-recognition:16.0.1")
    implementation("com.google.mlkit:barcode-scanning:17.3.0")
    implementation("com.google.android.gms:play-services-tasks:18.4.1")
    // Paksaan pembaruan (In-App Updates, mode IMMEDIATE) — berkomunikasi dengan aplikasi Play Store di HP.
    implementation("com.google.android.play:app-update:2.1.0")
    // Encoder QR asli (ringan, murni Java) + fallback decoder foto.
    implementation("com.google.zxing:core:3.5.4")
    // Memasang Baseline Profile (src/main/baseline-prof.txt) juga untuk APK yang dipasang di luar Play Store:
    // kode dikompilasi lebih dulu (AOT) -> buka aplikasi lebih cepat, gulir & animasi pertama tidak tersendat.
    implementation("androidx.profileinstaller:profileinstaller:1.4.1")

    testImplementation("junit:junit:4.13.2")
    // org.json di android.jar hanya kerangka kosong saat uji JVM: pakai implementasi aslinya (NotesTest)
    testImplementation("org.json:json:20240303")
}
