import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

dependencies {
    implementation(project(":app:shared"))

    implementation(compose.desktop.currentOs)
    implementation(libs.kotlinx.coroutinesSwing)

    implementation(libs.compose.uiToolingPreview)
}

compose.desktop {
    application {
        mainClass = "com.vesper.baubuild.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "BAU BUILD"
            packageVersion = "1.0.0"
            description = "BAU BUILD"
            vendor = "BAU BUILD"

            macOS {
                bundleID = "com.vesper.baubuild"
                dockName = "BAU BUILD"
                iconFile.set(project.file("icons/icon.icns"))
            }
            windows {
                menuGroup = "BAU BUILD"
                iconFile.set(project.file("icons/icon.ico"))
                shortcut = true
                // Stable upgrade code so MSI updates replace the previous install.
                upgradeUuid = "6f1c3c3e-2b1a-4c7e-9a43-6b0f1d8e5a21"
            }
            linux {
                packageName = "bau-build"
                menuGroup = "Development"
                iconFile.set(project.file("icons/icon.png"))
            }
        }
    }
}