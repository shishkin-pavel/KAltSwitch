package com.shish.kaltswitch.model

typealias WindowId = Long

enum class AppActivationPolicy { Regular, Accessory, Prohibited }

/**
 * A running macOS application. Most fields come from `NSRunningApplication`; `isHidden`
 * tracks `cmd+H` state via AX `kAXApplicationHidden/Shown` notifications.
 */
data class App(
    val pid: Int,
    val bundleId: String?,
    val name: String,
    val activationPolicy: AppActivationPolicy = AppActivationPolicy.Regular,
    val isHidden: Boolean = false,
    val isFinishedLaunching: Boolean = true,
    val executablePath: String? = null,
    val launchDateMillis: Long? = null,
)

/**
 * One window of an application. All boolean state and the title come from AX attributes
 * read by the per-app watcher; we keep them on the model so the UI can render pictograms.
 */
data class Window(
    val id: WindowId,
    val pid: Int,
    val title: String,
    val role: String? = null,
    val subrole: String? = null,
    val isMinimized: Boolean = false,
    val isFullscreen: Boolean = false,
    val isFocused: Boolean = false,
    val isMain: Boolean = false,
    val x: Double? = null,
    val y: Double? = null,
    val width: Double? = null,
    val height: Double? = null,
)

sealed interface Group {
    val displayName: String
}

data class AppGroup(val app: App) : Group {
    override val displayName: String = app.name
}
