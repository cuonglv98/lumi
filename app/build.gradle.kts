import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

/**
 * Chìa khoá ký app. Lấy theo thứ tự:
 * 1. Biến môi trường (GitHub Actions đặt từ Secrets)
 * 2. File keystore.properties ở thư mục gốc (máy cá nhân, KHÔNG đưa lên git)
 * Dùng chung 1 chìa khoá cho mọi bản build → APK build ở máy và APK GitHub build cài đè lên nhau được.
 */
val signing: Map<String, String>? = run {
    System.getenv("LUMI_KEYSTORE_PATH")?.let { path ->
        return@run mapOf(
            "storeFile" to path,
            "storePassword" to System.getenv("LUMI_KEYSTORE_PASSWORD").orEmpty(),
            "keyAlias" to (System.getenv("LUMI_KEY_ALIAS") ?: "lumi"),
        )
    }
    val file = rootProject.file("keystore.properties")
    if (!file.exists()) return@run null
    Properties().apply { file.inputStream().use { load(it) } }
        .let { p -> p.stringPropertyNames().associateWith { p.getProperty(it) } }
}

/** Repo GitHub chứa bản phát hành (vd "ten-ban/lumi"); rỗng = tắt tự cập nhật app. */
val updateRepo = (findProperty("lumi.updateRepo") as String?).orEmpty()

android {
    namespace = "dev.lumi.player"
    compileSdk {
        version = release(37) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "dev.lumi.player"
        minSdk = 26
        targetSdk = 36
        // GitHub Actions đặt số build tăng dần; build trên máy mặc định là 1
        versionCode = System.getenv("LUMI_VERSION_CODE")?.toInt() ?: 1
        versionName = System.getenv("LUMI_VERSION_NAME") ?: "0.1.0-local"
        // Phiên bản NewPipeExtractor đang dùng — để so với bản mới nhất trên GitHub
        buildConfigField("String", "EXTRACTOR_VERSION", "\"${libs.versions.newpipeExtractor.get()}\"")
        buildConfigField("String", "UPDATE_REPO", "\"$updateRepo\"")
    }

    signingConfigs {
        if (signing != null) {
            create("lumi") {
                storeFile = file(signing.getValue("storeFile"))
                storePassword = signing.getValue("storePassword")
                keyAlias = signing.getValue("keyAlias")
                keyPassword = signing.getValue("storePassword")
            }
        }
    }

    buildTypes {
        val lumiSigning = if (signing != null) signingConfigs.getByName("lumi") else signingConfigs.getByName("debug")
        debug {
            signingConfig = lumiSigning
        }
        release {
            isMinifyEnabled = false
            signingConfig = lumiSigning
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // NewPipeExtractor dùng một số API Java mới hơn Android hỗ trợ sẵn
        isCoreLibraryDesugaringEnabled = true
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    coreLibraryDesugaring(libs.desugar.jdk.libs)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    debugImplementation(libs.compose.ui.tooling)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.runtime.compose)

    implementation(libs.media3.exoplayer)
    implementation(libs.media3.exoplayer.dash)
    implementation(libs.media3.exoplayer.hls)
    implementation(libs.media3.datasource.okhttp)
    implementation(libs.media3.session)
    implementation(libs.media3.ui)

    implementation(libs.newpipe.extractor)
    implementation(libs.okhttp)
    implementation(libs.coil.compose)
    implementation(libs.work.runtime)
}
