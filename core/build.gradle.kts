import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
    alias(libs.plugins.kotlinSerialization)
}

kotlin {
    android {
        namespace = "org.app.kdownloader"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
        compilerOptions {
            jvmTarget = JvmTarget.fromTarget(libs.versions.javaVersion.get())
        }
    }

    listOf(
        iosArm64(),
        iosSimulatorArm64()
    ).forEach {
        it.binaries.framework {
            baseName = "kdownloader"
            isStatic = true
        }
    }

    jvm("desktop")

    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        browser()
    }

    applyDefaultHierarchyTemplate()

    sourceSets {
        // Platforms with a real file system (Android, desktop, iOS) share the file-backed part store.
        val fileMain by creating { dependsOn(commonMain.get()) }
        androidMain.get().dependsOn(fileMain)
        val desktopMain by getting { dependsOn(fileMain) }
        iosMain.get().dependsOn(fileMain)

        commonMain.dependencies {
            api(libs.kotlinx.coroutines.core)
            implementation(libs.ktor.client.core)
            implementation(libs.kotlin.serialization)
        }
        fileMain.dependencies {
            implementation(libs.kotlinx.io.core)
        }
        androidMain.dependencies {
            implementation(libs.ktor.client.cio)
            implementation(libs.androidx.core.ktx)
        }
        desktopMain.dependencies {
            implementation(libs.ktor.client.cio)
        }
        iosMain.dependencies {
            implementation(libs.ktor.client.darwin)
        }
        wasmJsMain.dependencies {
            implementation(libs.ktor.client.js)
            implementation(libs.kotlinx.browser)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
