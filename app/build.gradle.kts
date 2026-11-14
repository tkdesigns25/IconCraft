import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties()
if (keystorePropertiesFile.exists()) {
    keystorePropertiesFile.inputStream().use { keystoreProperties.load(it) }
}

android {
    namespace = "com.iconcraft.studio"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.iconcraft.studio"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        vectorDrawables {
            useSupportLibrary = true
        }
    }

    signingConfigs {
        if (keystorePropertiesFile.exists()) {
            val storeFileProp = keystoreProperties.getProperty("storeFile")
            val storePasswordProp = keystoreProperties.getProperty("storePassword")
            val keyAliasProp = keystoreProperties.getProperty("keyAlias")
            val keyPasswordProp = keystoreProperties.getProperty("keyPassword")
            if (!storeFileProp.isNullOrBlank() &&
                !storePasswordProp.isNullOrBlank() &&
                !keyAliasProp.isNullOrBlank() &&
                !keyPasswordProp.isNullOrBlank()
            ) {
                create("release") {
                    storeFile = rootProject.file(storeFileProp)
                    storePassword = storePasswordProp
                    keyAlias = keyAliasProp
                    keyPassword = keyPasswordProp
                }
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            val releaseSigning = signingConfigs.findByName("release")
            if (releaseSigning != null) {
                signingConfig = releaseSigning
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

val syncTemplateApk by tasks.registering {
    dependsOn(":iconpack_template:assembleRelease")
    val releaseApkDir = project(":iconpack_template").layout.buildDirectory.dir("outputs/apk/release")
    val targetAssetFile = layout.projectDirectory.file("src/main/assets/template_iconpack.apk")

    inputs.dir(releaseApkDir)
    outputs.file(targetAssetFile)

    doLast {
        val dir = releaseApkDir.get().asFile
        val apkFile = dir.walkTopDown().firstOrNull { it.isFile && it.extension == "apk" }
            ?: throw GradleException("Could not find generated APK in ${dir.absolutePath}")
        val outFile = targetAssetFile.asFile
        outFile.parentFile.mkdirs()
        apkFile.copyTo(outFile, overwrite = true)
        println("Synced template APK (${apkFile.length()} bytes) -> ${outFile.absolutePath}")
    }
}

tasks.named("preBuild") {
    dependsOn(syncTemplateApk)
}

dependencies {
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.activity:activity-compose:1.10.1")

    val composeBom = platform("androidx.compose:compose-bom:2025.09.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    // Spec-required libraries
    implementation("androidx.palette:palette-ktx:1.0.0")
    implementation("com.android.tools.build:apksig:8.13.0")

    testImplementation("junit:junit:4.13.2")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
