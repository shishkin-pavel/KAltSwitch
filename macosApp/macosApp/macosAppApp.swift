import SwiftUI
import ComposeAppMac

@main
struct macosAppApp: SwiftUI.App {
    @NSApplicationDelegateAdaptor(AppDelegate.self) var appDelegate

    var body: some SwiftUI.Scene {}
}

class AppDelegate: NSObject, NSApplicationDelegate {
    var window: NSWindow!
    private var composeDelegate: ComposeNSViewDelegate? = nil
    private var appRegistry: AppRegistry? = nil
    private var frameObservers: [NSObjectProtocol] = []

    func applicationDidFinishLaunching(_ aNotification: Notification) {
        window = NSWindow(
            contentRect: NSRect(x: 0, y: 0, width: 800, height: 600),
            styleMask: [.titled, .closable, .miniaturizable, .resizable],
            backing: .buffered,
            defer: false
        )
        window.title = "KAltSwitch"
        window.center()

        composeDelegate = ComposeViewKt.AttachMainComposeView(window: window)

        // Restore the inspector window's last position/size, if any. K/N's StateFlow
        // exposes `value` as `Any?`, so cast to the concrete frame type.
        if let saved = ComposeViewKt.store.inspectorFrame.value as? WindowFrame {
            let restored = NSRect(x: saved.x, y: saved.y, width: saved.width, height: saved.height)
            if NSScreen.screens.contains(where: { $0.visibleFrame.intersects(restored) }) {
                window.setFrame(restored, display: false)
            }
        }

        window.makeKeyAndOrderFront(nil)
        NSApp.activate(ignoringOtherApps: true)

        // Persist user-driven moves and resizes back to config.json.
        let center = NotificationCenter.default
        let move = center.addObserver(forName: NSWindow.didMoveNotification, object: window, queue: .main) { [weak self] _ in
            self?.persistFrame()
        }
        let resize = center.addObserver(forName: NSWindow.didEndLiveResizeNotification, object: window, queue: .main) { [weak self] _ in
            self?.persistFrame()
        }
        frameObservers = [move, resize]

        // Bring up the per-app AX watchers. Store is the singleton owned by the framework.
        appRegistry = AppRegistry(store: ComposeViewKt.store)
        appRegistry?.start()
    }

    private func persistFrame() {
        let f = window.frame
        ComposeViewKt.store.saveInspectorFrame(
            x: Double(f.origin.x),
            y: Double(f.origin.y),
            width: Double(f.size.width),
            height: Double(f.size.height)
        )
    }

    func applicationShouldTerminateAfterLastWindowClosed(_ sender: NSApplication) -> Bool {
        return true
    }

    func applicationWillFinishLaunching(_ notification: Notification) {
        composeDelegate?.create()
        composeDelegate?.start()
    }

    func applicationDidBecomeActive(_ notification: Notification) {
        composeDelegate?.resume()
    }

    func applicationDidResignActive(_ notification: Notification) {
        composeDelegate?.pause()
    }

    func applicationWillTerminate(_ notification: Notification) {
        let center = NotificationCenter.default
        for obs in frameObservers { center.removeObserver(obs) }
        frameObservers.removeAll()
        appRegistry?.stop()
        composeDelegate?.stop()
        composeDelegate?.destroy()
    }
}
