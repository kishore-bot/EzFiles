import Shared
import SwiftUI
import UIKit

/// Hosts the shared Compose UI. All reading logic lives in the shared module.
struct ContentView: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        MainViewControllerKt.MainViewController()
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}
