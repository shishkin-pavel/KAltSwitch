package com.shish.kaltswitch.model

typealias WindowId = Long

data class App(
    val pid: Int,
    val bundleId: String?,
    val name: String,
)

data class Window(
    val id: WindowId,
    val pid: Int,
    val title: String,
)

sealed interface Group {
    val displayName: String
}

data class AppGroup(val app: App) : Group {
    override val displayName: String = app.name
}
