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
    private let everforestBg0 = Color(red: 45.0 / 255.0, green: 53.0 / 255.0, blue: 59.0 / 255.0)

    var body: some View {
        ZStack {
            everforestBg0.ignoresSafeArea(.all)
            ComposeView()
                .ignoresSafeArea(.all)
        }
        .preferredColorScheme(.dark)
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
            .sheet(
                isPresented: $bridge.isShowingPhotoPicker,
                onDismiss: {
                    bridge.finishImagePick(fileName: nil, mimeType: nil, dataBase64: nil)
                },
                content: {
                    PhotoPickerSheet { fileName, mimeType, b64 in
                        bridge.finishImagePick(fileName: fileName, mimeType: mimeType, dataBase64: b64)
                    }
                }
            )
            .onOpenURL { url in
                IosNativeBridge.shared.handleIncomingUrl(url: url.absoluteString)
            }
    }
}
