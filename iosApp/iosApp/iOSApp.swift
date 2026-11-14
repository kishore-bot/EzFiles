import AppIntents
import Foundation
import Shared
import SwiftUI

@main
struct iOSApp: App {
    init() {
        EzFilesShortcuts.updateAppShortcutParameters()
    }

    var body: some Scene {
        WindowGroup {
            ContentView()
                .ignoresSafeArea(.keyboard)
                .onOpenURL { url in
                    IosAssistantBridge.shared.openUserDocument(url: url)
                }
                .onReceive(NotificationCenter.default.publisher(for: UserDefaults.didChangeNotification)) { _ in
                    EzFilesShortcuts.updateAppShortcutParameters()
                }
        }
    }
}
