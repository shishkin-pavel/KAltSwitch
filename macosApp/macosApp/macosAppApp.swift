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

        window.makeKeyAndOrderFront(nil)
        NSApp.activate(ignoringOtherApps: true)

        // Bring up the per-app AX watchers. Store is the singleton owned by the framework.
        appRegistry = AppRegistry(store: ComposeViewKt.store)
        appRegistry?.start()
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
        appRegistry?.stop()
        composeDelegate?.stop()
        composeDelegate?.destroy()
    }
}
