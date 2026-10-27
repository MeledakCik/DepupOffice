plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "cloud.depup.office"
    compileSdk = 34

    defaultConfig {
        applicationId = "cloud.depup.office"
        minSdk = 29
        targetSdk = 34
        versionCode = 2
        versionName = "2.1.0"
    }

    base.archivesName.set("DepupOffice_v2.1.0")

    signingConfigs {
        create("release") {
            val ksFile = rootProject.file("depup-office.jks")
            if (ksFile.exists()) {
                storeFile = ksFile
                storePassword = "123456"
                keyAlias = "depup"
                keyPassword = "123456"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            val releaseConfig = signingConfigs.findByName("release")
            if (releaseConfig != null && releaseConfig.storeFile?.exists() == true) {
                signingConfig = releaseConfig
            } else {
                signingConfig = signingConfigs.getByName("debug")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

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

    signingConfigs.all {
        val currentStoreFile = storeFile
        if (currentStoreFile != null && !currentStoreFile.exists()) {
            val rootFile = rootProject.file(currentStoreFile.name)
            val projectFile = file(currentStoreFile.name)
            val rootPathFile = rootProject.file(currentStoreFile.path)
            val projectPathFile = file(currentStoreFile.path)

            when {
                rootFile.exists() -> storeFile = rootFile
                projectFile.exists() -> storeFile = projectFile
                rootPathFile.exists() -> storeFile = rootPathFile
                projectPathFile.exists() -> storeFile = projectPathFile
                else -> {
                    val debugStore = signingConfigs.findByName("debug")?.storeFile
                    if (debugStore != null && debugStore.exists()) {
                        storeFile = debugStore
                    }
                }
            }
        }
    }
}

// Sengaja minim: tanpa Compose, tanpa library PDF pihak ketiga -> APK kecil & ringan.
dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.exifinterface:exifinterface:1.3.7")
    // Viewfinder kamera sendiri
    val camerax = "1.3.4"
    implementation("androidx.camera:camera-core:$camerax")
    implementation("androidx.camera:camera-camera2:$camerax")
    implementation("androidx.camera:camera-lifecycle:$camerax")
    implementation("androidx.camera:camera-view:$camerax")
    // OCR offline & QR Code Scanner
    implementation("com.google.mlkit:text-recognition:16.0.1")
    implementation("com.google.mlkit:barcode-scanning:17.2.0")
    implementation("com.google.android.gms:play-services-tasks:18.2.0")
    // Encoder QR asli (ringan, murni Java) + fallback decoder foto.
    implementation("com.google.zxing:core:3.5.3")
}
