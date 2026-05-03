@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")

package com.shish.kaltswitch.viewcontroller

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asComposeCanvas
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.toComposeEvent
import androidx.compose.ui.input.pointer.MacosCursor
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.PointerKeyboardModifiers
import androidx.compose.ui.platform.PlatformContext
import androidx.compose.ui.platform.WindowInfo
import androidx.compose.ui.scene.CanvasLayersComposeScene
import androidx.compose.ui.text.input.EditCommand
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.ImeOptions
import androidx.compose.ui.text.input.PlatformTextInputService
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import kotlinx.coroutines.Dispatchers
import org.jetbrains.skia.Canvas
import org.jetbrains.skiko.SkiaLayer
import org.jetbrains.skiko.SkikoRenderDelegate
import platform.AppKit.NSCursor
import platform.AppKit.NSEvent
import platform.AppKit.NSTrackingActiveAlways
import platform.AppKit.NSTrackingActiveInKeyWindow
import platform.AppKit.NSTrackingArea
import platform.AppKit.NSTrackingAssumeInside
import platform.AppKit.NSTrackingInVisibleRect
import platform.AppKit.NSTrackingMouseEnteredAndExited
import platform.AppKit.NSTrackingMouseMoved
import platform.AppKit.NSView
import platform.AppKit.NSWindow

@OptIn(InternalComposeUiApi::class)
class ComposeNSViewDelegate(
    window: NSWindow,
    content: @Composable () -> Unit,
) {
    private var isDisposed = false
    private val macosTextInputService = MacosTextInputService()
    private val _windowInfo = MacosWindowInfoImpl().apply {
        isWindowFocused = true
    }

    private val platformContext: PlatformContext = object : PlatformContext {
        override val windowInfo get() = _windowInfo
        override val inputModeManager: InputModeManager = SimpleInputModeManager()
        override val textInputService get() = macosTextInputService
        override fun setPointerIcon(pointerIcon: PointerIcon) {
            val cursor = (pointerIcon as? MacosCursor)?.cursor ?: NSCursor.arrowCursor
            cursor.set()
        }
    }
    private val skiaLayer = SkiaLayer()
    private val scene = CanvasLayersComposeScene(
        coroutineContext = Dispatchers.Main,
        platformContext = platformContext,
        invalidate = skiaLayer::needRender,
    )

    private val renderDelegate = object : SkikoRenderDelegate {
        override fun onRender(canvas: Canvas, width: Int, height: Int, nanoTime: Long) {
            val sizeInPx = IntSize(width, height)
            _windowInfo.containerSize = sizeInPx
            scene.size = sizeInPx
            scene.render(canvas.asComposeCanvas(), nanoTime)
        }
    }

    @OptIn(ExperimentalForeignApi::class)
    internal val view = object : NSView(window.frame) {
        private var trackingArea: NSTrackingArea? = null
        override fun wantsUpdateLayer() = true
        override fun acceptsFirstResponder() = true
        override fun viewWillMoveToWindow(newWindow: NSWindow?) {
            updateTrackingAreas()
        }

        @OptIn(ExperimentalForeignApi::class)
        override fun updateTrackingAreas() {
            trackingArea?.let { removeTrackingArea(it) }
            val nextTrackingArea = NSTrackingArea(
                rect = bounds,
                options = NSTrackingActiveAlways or
                        NSTrackingMouseEnteredAndExited or
                        NSTrackingMouseMoved or
                        NSTrackingActiveInKeyWindow or
                        NSTrackingAssumeInside or
                        NSTrackingInVisibleRect,
                owner = this, userInfo = null
            )
            trackingArea = nextTrackingArea
            addTrackingArea(nextTrackingArea)
        }

        override fun mouseDown(event: NSEvent) =
            onMouseEvent(event, PointerEventType.Press, PointerButton.Primary)
        override fun mouseUp(event: NSEvent) =
            onMouseEvent(event, PointerEventType.Release, PointerButton.Primary)
        override fun rightMouseDown(event: NSEvent) =
            onMouseEvent(event, PointerEventType.Press, PointerButton.Secondary)
        override fun rightMouseUp(event: NSEvent) =
            onMouseEvent(event, PointerEventType.Release, PointerButton.Secondary)
        override fun otherMouseDown(event: NSEvent) =
            onMouseEvent(event, PointerEventType.Press, PointerButton(event.buttonNumber.toInt()))
        override fun otherMouseUp(event: NSEvent) =
            onMouseEvent(event, PointerEventType.Release, PointerButton(event.buttonNumber.toInt()))
        override fun mouseMoved(event: NSEvent) = onMouseEvent(event, PointerEventType.Move)
        override fun mouseDragged(event: NSEvent) = onMouseEvent(event, PointerEventType.Move)
        override fun scrollWheel(event: NSEvent) = onMouseEvent(event, PointerEventType.Scroll)

        override fun keyDown(event: NSEvent) {
            val consumed = onKeyboardEvent(event.toComposeEvent())
            if (!consumed) super.keyDown(event)
        }

        override fun keyUp(event: NSEvent) {
            onKeyboardEvent(event.toComposeEvent())
        }
    }

    init {
        window.contentView = view

        skiaLayer.renderDelegate = renderDelegate
        skiaLayer.attachTo(view)

        scene.density = Density(window.backingScaleFactor.toFloat())
        scene.setContent { content() }
    }

    @Suppress("Unused") fun destroy() {
        // Idempotent: AppKit / window-replacement / app-shutdown lifecycles
        // can race the Swift teardown order, and a second `destroy()` should
        // be a no-op rather than a crash.
        if (isDisposed) return
        skiaLayer.detach()
        scene.close()
        isDisposed = true
    }

    private fun onKeyboardEvent(event: KeyEvent): Boolean {
        if (isDisposed) return false
        return scene.sendKeyEvent(event)
    }

    private fun onMouseEvent(
        event: NSEvent,
        eventType: PointerEventType,
        button: PointerButton? = null,
    ) {
        if (isDisposed) return
        scene.sendPointerEvent(
            eventType = eventType,
            position = event.offset.toOffset(scene.density),
            scrollDelta = Offset(x = event.deltaX.toFloat(), y = event.deltaY.toFloat()),
            nativeEvent = event,
            button = button,
        )
    }

    @OptIn(ExperimentalForeignApi::class)
    private val NSEvent.offset: DpOffset
        get() {
            // `locationInWindow` is in window-content (= wrapper)
            // coords. Pre-iter48 the Compose host NSView WAS the
            // contentView, so its frame.origin was (0, 0) and
            // window-coords == view-local-coords; the original code
            // got away with using the raw locationInWindow.
            //
            // Since iter48 the Compose host is a subview of a wrapper
            // with a *non-zero* frame.origin — its frame spans the
            // whole captured screen and is repositioned within the
            // wrapper on every NSPanel `setFrame` so the scene
            // coordinate system stays pinned to the screen rect.
            // That means we have to convert window coords to view-local
            // Cocoa coords (subtract view.frame.origin) before flipping
            // Y for Compose's y-down. Without the subtraction Compose
            // sees pointer events at the wrong scene position — clicks
            // and hovers miss every cell.
            val winX = locationInWindow.useContents { x }
            val winY = locationInWindow.useContents { y }
            val viewOriginX = view.frame.useContents { origin.x }
            val viewOriginY = view.frame.useContents { origin.y }
            val viewHeight = view.frame.useContents { size.height }
            val xInView = winX - viewOriginX
            val yInViewCocoa = winY - viewOriginY
            return DpOffset(x = xInView.dp, y = (viewHeight - yInViewCocoa).dp)
        }
}

internal class MacosTextInputService : PlatformTextInputService {
    data class CurrentInput(
        var value: TextFieldValue,
        val onEditCommand: ((List<EditCommand>) -> Unit),
    )

    private var currentInput: CurrentInput? = null

    override fun startInput(
        value: TextFieldValue,
        imeOptions: ImeOptions,
        onEditCommand: (List<EditCommand>) -> Unit,
        onImeActionPerformed: (ImeAction) -> Unit
    ) {
        currentInput = CurrentInput(value, onEditCommand)
    }

    override fun stopInput() { currentInput = null }
    override fun showSoftwareKeyboard() {}
    override fun hideSoftwareKeyboard() {}
    override fun updateState(oldValue: TextFieldValue?, newValue: TextFieldValue) {
        currentInput?.let { it.value = newValue }
    }
}

@Stable
internal fun DpOffset.toOffset(density: Density): Offset = with(density) {
    if (isSpecified) Offset(x.toPx(), y.toPx()) else Offset.Unspecified
}

internal class MacosWindowInfoImpl : WindowInfo {
    private val _containerSize = mutableStateOf(IntSize.Zero)

    override var isWindowFocused: Boolean by mutableStateOf(false)

    override var keyboardModifiers: PointerKeyboardModifiers
        get() = GlobalKeyboardModifiers.value
        set(value) { GlobalKeyboardModifiers.value = value }

    override var containerSize: IntSize
        get() = _containerSize.value
        set(value) { _containerSize.value = value }

    companion object {
        internal val GlobalKeyboardModifiers = mutableStateOf(PointerKeyboardModifiers())
    }
}

private class SimpleInputModeManager(
    initialInputMode: InputMode = InputMode.Keyboard
) : InputModeManager {
    override var inputMode: InputMode by mutableStateOf(initialInputMode)

    @ExperimentalComposeUiApi
    override fun requestInputMode(inputMode: InputMode) =
        if (inputMode == InputMode.Touch || inputMode == InputMode.Keyboard) {
            this.inputMode = inputMode
            true
        } else false
}
