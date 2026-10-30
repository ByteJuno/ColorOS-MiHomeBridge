import java.util.Properties

plugins { id("com.android.application") }
val moduleSigning = Properties().apply {
    val config = rootProject.file(".local/signing/signing.properties")
    if (config.isFile) config.inputStream().use { load(it) }
}
android {
    namespace = "io.github.coloros.mihomebridge"
    compileSdk = 37
    buildToolsVersion = "37.0.0"
    defaultConfig {
        applicationId = "io.github.coloros.mihomebridge"
        minSdk = 37
        targetSdk = 37
        versionCode = 7
        versionName = "0.3.0-preview"
    }
    signingConfigs {
        if (moduleSigning.isNotEmpty()) create("module") {
            storeFile = rootProject.file(moduleSigning.getProperty("storeFile"))
            storePassword = moduleSigning.getProperty("storePassword")
            keyAlias = moduleSigning.getProperty("keyAlias")
            keyPassword = moduleSigning.getProperty("keyPassword")
            storeType = "PKCS12"
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("module")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    buildFeatures { buildConfig = true }
}
dependencies {
    compileOnly("io.github.libxposed:api:101.0.1")
    implementation("io.github.libxposed:service:101.0.0")
    testImplementation("junit:junit:4.13.2")
}
