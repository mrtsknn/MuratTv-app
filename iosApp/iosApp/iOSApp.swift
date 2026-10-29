import SwiftUI
import UIKit
import ComposeApp

struct ComposeView: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        MainViewControllerKt.MainViewController()
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}

@main
struct iOSApp: App {
    init() {
        // .ts dahil tüm yayınlar VLC ile açılır
        NativePlayerKt.nativePlayerFactory = { VlcPlayer() }
    }

    var body: some Scene {
        WindowGroup {
            ComposeView()
                .ignoresSafeArea()
                .statusBarHidden()
        }
    }
}
