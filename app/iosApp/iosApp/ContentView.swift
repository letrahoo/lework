import Shared
import SwiftUI

struct ComposeView: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        MainViewControllerKt.MainViewController()
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}

struct ContentView: View {
    @StateObject private var bridge = NativeBridgeCoordinator()

    var body: some View {
        ComposeView()
            .ignoresSafeArea(.all)
            .sheet(
                isPresented: $bridge.isShowingScanner,
                onDismiss: {
                    bridge.finishQrScan(nil)
                },
                content: {
                    QrScannerSheet { scanned in
                        bridge.finishQrScan(scanned)
                    }
                }
            )
            .onOpenURL { url in
                IosNativeBridge.shared.handleIncomingUrl(url: url.absoluteString)
            }
    }
}
