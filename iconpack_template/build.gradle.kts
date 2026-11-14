import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.awt.image.BufferedImage
import javax.imageio.ImageIO

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.iconcraft.pack"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.iconcraft.pack"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            isCrunchPngs = false
            signingConfig = signingConfigs.getByName("debug")
        }
        debug {
            isMinifyEnabled = false
            isShrinkResources = false
            isCrunchPngs = false
        }
    }

    androidResources {
        noCompress += listOf("png", "xml")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

val ensure500TemplateSlots by tasks.registering {
    val drawableDir = layout.projectDirectory.dir("src/main/res/drawable-nodpi")
    outputs.dir(drawableDir)
    doLast {
        val dir = drawableDir.asFile
        dir.mkdirs()
        val seedFile = File(dir, "custom_icon_001.png")
        if (!seedFile.exists()) {
            val img = BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB)
            ImageIO.write(img, "png", seedFile)
        }
        val seedBytes = seedFile.readBytes()
        var created = 0
        for (i in 1..500) {
            val slotFile = File(dir, "custom_icon_%03d.png".format(i))
            if (!slotFile.exists()) {
                slotFile.writeBytes(seedBytes)
                created++
            }
        }
        if (created > 0) {
            println("Generated $created additional 1x1 template PNG slots (total capacity: 500 slots)")
        }
    }
}

tasks.named("preBuild") {
    dependsOn(ensure500TemplateSlots)
}
