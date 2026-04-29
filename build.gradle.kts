plugins {
    alias(libs.plugins.kotlin.multiplatform).apply(false)
    alias(libs.plugins.compose.compiler).apply(false)
    alias(libs.plugins.compose.multiplatform).apply(false)
    alias(libs.plugins.kotlin.jvm).apply(false)
}

// --- macOS native app build & run ---
//
// Glues xcodegen + xcodebuild + open into Gradle so a single
// `./gradlew runMacosApp` builds the Compose framework, regenerates the
// Xcode project, builds the .app, and launches it.

val macosAppDir = layout.projectDirectory.dir("macosApp")
val derivedDataDir = macosAppDir.dir("build/derived-data")
// PRODUCT_NAME=KAltSwitch in macosApp/project.yml — the bundle is
// KAltSwitch.app even though the xcodeproj target is still `macosApp`.
val builtAppPath = derivedDataDir.dir("Build/Products/Debug/KAltSwitch.app")

// Wrap commands in a login shell so Homebrew (/opt/homebrew, /usr/local) is on PATH
// at execution time. Avoids touching processes at configuration time, which would
// invalidate the configuration cache.
fun Exec.loginShell(vararg argv: String) {
    commandLine("/bin/zsh", "-lc", argv.joinToString(" ") { "'${it.replace("'", "'\\''")}'" })
}

val xcodegenMacos by tasks.registering(Exec::class) {
    group = "macos"
    description = "Generate macosApp.xcodeproj from project.yml via xcodegen."
    workingDir = macosAppDir.asFile
    loginShell("xcodegen", "generate")
    inputs.file(macosAppDir.file("project.yml"))
    // xcodegen scans source dirs at generate-time; if a .swift file is added or removed
    // from macosApp/macosApp/, the xcodeproj must be regenerated.
    inputs.dir(macosAppDir.dir("macosApp"))
    outputs.file(macosAppDir.file("macosApp.xcodeproj/project.pbxproj"))
}

val buildMacosApp by tasks.registering(Exec::class) {
    group = "macos"
    description = "Build macosApp.app via xcodebuild. Triggers framework build via embedded run-script."
    dependsOn(xcodegenMacos)
    workingDir = macosAppDir.asFile
    loginShell(
        "xcodebuild",
        "-project", "macosApp.xcodeproj",
        "-scheme", "macosApp",
        "-configuration", "Debug",
        "-destination", "platform=macOS,arch=arm64",
        "-derivedDataPath", derivedDataDir.asFile.absolutePath,
        "build",
    )
}

val killMacosApp by tasks.registering(Exec::class) {
    group = "macos"
    description = "Kill any running macosApp instance."
    isIgnoreExitValue = true
    commandLine("pkill", "-f", builtAppPath.asFile.absolutePath)
}

val macosAppLogPath = derivedDataDir.file("macosApp.log").asFile.absolutePath

tasks.register<Exec>("runMacosApp") {
    group = "macos"
    description = "Build and launch macosApp.app, redirecting stderr/stdout to $macosAppLogPath."
    dependsOn(killMacosApp, buildMacosApp)
    commandLine(
        "/usr/bin/open",
        "--stdout", macosAppLogPath,
        "--stderr", macosAppLogPath,
        builtAppPath.asFile.absolutePath,
    )
}
