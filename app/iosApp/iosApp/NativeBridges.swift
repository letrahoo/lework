import AVFoundation
import Darwin
import PhotosUI
import Shared
import SwiftUI
import UIKit

final class NativeBridgeCoordinator: NSObject, ObservableObject, IosNativeDelegate {
    @Published var isShowingScanner: Bool = false
    @Published var isShowingPhotoPicker: Bool = false
    private var activeQrReqId: Int64?
    private var activeImageReqId: Int64?
    private let tsnetQueue = DispatchQueue(label: "dev.lework.tsnet", qos: .userInitiated)

    override init() {
        super.init()
        IosNativeBridge.shared.registerDelegate(delegate: self)
    }

    private func consumeGoCString(_ ptr: UnsafeMutablePointer<CChar>?) -> String {
        guard let ptr else { return "{}" }
        defer { LeworkTsnetFreeString(ptr) }
        return String(cString: ptr)
    }

    func tsnetStart(stateDir: String, hostname: String, controlUrl: String, authKey: String, reqId: Int64) {
        tsnetQueue.async { [weak self] in
            guard let self else { return }
            let cDir = strdup(stateDir)
            let cHost = strdup(hostname)
            let cCtrl = strdup(controlUrl)
            defer {
                free(cDir)
                free(cHost)
                free(cCtrl)
            }
            var json = self.consumeGoCString(LeworkTsnetStart(cDir, cHost, cCtrl))
            if !authKey.isEmpty {
                let cKey = strdup(authKey)
                defer { free(cKey) }
                json = self.consumeGoCString(LeworkTsnetConfigure(cCtrl, cKey))
            }
            DispatchQueue.main.async {
                IosNativeBridge.shared.completeTsnetRequest(reqId: reqId, json: json)
            }
        }
    }

    func tsnetStatus(reqId: Int64) {
        tsnetQueue.async { [weak self] in
            guard let self else { return }
            let json = self.consumeGoCString(LeworkTsnetStatus())
            DispatchQueue.main.async {
                IosNativeBridge.shared.completeTsnetRequest(reqId: reqId, json: json)
            }
        }
    }

    func tsnetLogin(controlUrl: String, authKey: String, reqId: Int64) {
        tsnetQueue.async { [weak self] in
            guard let self else { return }
            if !controlUrl.isEmpty || !authKey.isEmpty {
                let cCtrl = strdup(controlUrl)
                let cKey = strdup(authKey)
                defer {
                    free(cCtrl)
                    free(cKey)
                }
                _ = self.consumeGoCString(LeworkTsnetConfigure(cCtrl, cKey))
            }
            let json = self.consumeGoCString(LeworkTsnetLogin())
            DispatchQueue.main.async {
                IosNativeBridge.shared.completeTsnetRequest(reqId: reqId, json: json)
            }
        }
    }

    func tsnetLogout(reqId: Int64) {
        tsnetQueue.async { [weak self] in
            guard let self else { return }
            let json = self.consumeGoCString(LeworkTsnetLogout())
            DispatchQueue.main.async {
                IosNativeBridge.shared.completeTsnetRequest(reqId: reqId, json: json)
            }
        }
    }

    func presentQrScanner(reqId: Int64) {
        DispatchQueue.main.async { [weak self] in
            guard let self else { return }
            if let prev = self.activeQrReqId {
                IosNativeBridge.shared.completeQrScan(reqId: prev, scannedText: nil)
            }
            self.activeQrReqId = reqId
            self.isShowingScanner = true
        }
    }

    func finishQrScan(_ result: String?) {
        DispatchQueue.main.async { [weak self] in
            guard let self else { return }
            self.isShowingScanner = false
            if let reqId = self.activeQrReqId {
                self.activeQrReqId = nil
                IosNativeBridge.shared.completeQrScan(reqId: reqId, scannedText: result)
            }
        }
    }

    func presentImagePicker(reqId: Int64) {
        DispatchQueue.main.async { [weak self] in
            guard let self else { return }
            if let prev = self.activeImageReqId {
                IosNativeBridge.shared.completeImagePick(reqId: prev, fileName: nil, mimeType: nil, dataBase64: nil)
            }
            self.activeImageReqId = reqId
            self.isShowingPhotoPicker = true
        }
    }

    func finishImagePick(fileName: String?, mimeType: String?, dataBase64: String?) {
        DispatchQueue.main.async { [weak self] in
            guard let self else { return }
            self.isShowingPhotoPicker = false
            if let reqId = self.activeImageReqId {
                self.activeImageReqId = nil
                IosNativeBridge.shared.completeImagePick(
                    reqId: reqId,
                    fileName: fileName,
                    mimeType: mimeType,
                    dataBase64: dataBase64
                )
            }
        }
    }
}

struct PhotoPickerSheet: UIViewControllerRepresentable {
    let onComplete: (String?, String?, String?) -> Void

    func makeCoordinator() -> Coordinator {
        Coordinator(onComplete: onComplete)
    }

    func makeUIViewController(context: Context) -> PHPickerViewController {
        var config = PHPickerConfiguration()
        config.filter = .images
        config.selectionLimit = 1
        let picker = PHPickerViewController(configuration: config)
        picker.delegate = context.coordinator
        return picker
    }

    func updateUIViewController(_ uiViewController: PHPickerViewController, context: Context) {}

    final class Coordinator: NSObject, PHPickerViewControllerDelegate {
        let onComplete: (String?, String?, String?) -> Void

        init(onComplete: @escaping (String?, String?, String?) -> Void) {
            self.onComplete = onComplete
        }

        func picker(_ picker: PHPickerViewController, didFinishPicking results: [PHPickerResult]) {
            guard let first = results.first,
                  first.itemProvider.canLoadObject(ofClass: UIImage.self)
            else {
                onComplete(nil, nil, nil)
                return
            }
            let suggested = first.itemProvider.suggestedName ?? "photo"
            first.itemProvider.loadObject(ofClass: UIImage.self) { [onComplete] obj, _ in
                guard let uiImage = obj as? UIImage else {
                    DispatchQueue.main.async { onComplete(nil, nil, nil) }
                    return
                }
                let scaled = Self.downscale(image: uiImage, maxDimension: 1280)
                guard let jpegData = scaled.jpegData(compressionQuality: 0.78) else {
                    DispatchQueue.main.async { onComplete(nil, nil, nil) }
                    return
                }
                let b64 = jpegData.base64EncodedString()
                DispatchQueue.main.async {
                    onComplete("\(suggested).jpg", "image/jpeg", b64)
                }
            }
        }

        private static func downscale(image: UIImage, maxDimension: CGFloat) -> UIImage {
            let size = image.size
            let longest = max(size.width, size.height)
            guard longest > maxDimension, longest > 0 else { return image }
            let ratio = maxDimension / longest
            let newSize = CGSize(width: floor(size.width * ratio), height: floor(size.height * ratio))
            let format = UIGraphicsImageRendererFormat.default()
            format.scale = 1.0
            return UIGraphicsImageRenderer(size: newSize, format: format).image { _ in
                image.draw(in: CGRect(origin: .zero, size: newSize))
            }
        }
    }
}

struct QrScannerSheet: View {
    let onComplete: (String?) -> Void
    @State private var statusMessage: String = "对准电脑终端 lework pair 生成的二维码"

    var body: some View {
        ZStack {
            Color(red: 0.14, green: 0.16, blue: 0.18)
                .ignoresSafeArea()

            QrCameraPreview(
                onScanned: { code in
                    UINotificationFeedbackGenerator().notificationOccurred(.success)
                    onComplete(code)
                },
                onUnavailable: { reason in
                    statusMessage = reason
                }
            )
            .ignoresSafeArea()

            VStack {
                HStack {
                    Text("扫码配对主机")
                        .font(.system(size: 17, weight: .semibold))
                        .foregroundColor(Color(red: 0.83, green: 0.78, blue: 0.67))
                    Spacer()
                    Button(action: { onComplete(nil) }) {
                        Text("取消")
                            .font(.system(size: 15, weight: .medium))
                            .foregroundColor(Color(red: 0.90, green: 0.76, blue: 0.52))
                            .padding(.horizontal, 12)
                            .padding(.vertical, 6)
                            .background(Color.black.opacity(0.45))
                            .clipShape(Capsule())
                    }
                }
                .padding(.horizontal, 20)
                .padding(.top, 18)

                Spacer()

                RoundedRectangle(cornerRadius: 20)
                    .stroke(Color(red: 0.51, green: 0.79, blue: 0.58), lineWidth: 2.5)
                    .frame(width: 248, height: 248)
                    .background(Color.clear)

                Spacer()

                Text(statusMessage)
                    .font(.system(size: 13))
                    .foregroundColor(Color(red: 0.83, green: 0.78, blue: 0.67))
                    .multilineTextAlignment(.center)
                    .padding(.horizontal, 24)
                    .padding(.vertical, 12)
                    .background(Color.black.opacity(0.55))
                    .clipShape(RoundedRectangle(cornerRadius: 12))
                    .padding(.bottom, 36)
            }
        }
    }
}

private struct QrCameraPreview: UIViewControllerRepresentable {
    let onScanned: (String) -> Void
    let onUnavailable: (String) -> Void

    func makeUIViewController(context: Context) -> QrScannerViewController {
        let vc = QrScannerViewController()
        vc.onScanned = onScanned
        vc.onUnavailable = onUnavailable
        return vc
    }

    func updateUIViewController(_ uiViewController: QrScannerViewController, context: Context) {}
}

private final class QrScannerViewController: UIViewController, AVCaptureMetadataOutputObjectsDelegate {
    var onScanned: ((String) -> Void)?
    var onUnavailable: ((String) -> Void)?

    private let session = AVCaptureSession()
    private var previewLayer: AVCaptureVideoPreviewLayer?
    private var didFinish = false
    private let sessionQueue = DispatchQueue(label: "dev.lework.qr-camera")

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .clear
        checkPermissionAndConfigure()
    }

    override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        previewLayer?.frame = view.bounds
    }

    override func viewWillDisappear(_ animated: Bool) {
        super.viewWillDisappear(animated)
        sessionQueue.async { [weak self] in
            if self?.session.isRunning == true {
                self?.session.stopRunning()
            }
        }
    }

    private func checkPermissionAndConfigure() {
        switch AVCaptureDevice.authorizationStatus(for: .video) {
        case .authorized:
            setupCaptureSession()
        case .notDetermined:
            AVCaptureDevice.requestAccess(for: .video) { [weak self] granted in
                DispatchQueue.main.async {
                    if granted {
                        self?.setupCaptureSession()
                    } else {
                        self?.onUnavailable?("未授予相机权限，请在系统设置中开启相机访问，或粘贴配对链接")
                    }
                }
            }
        default:
            onUnavailable?("相机权限受限，请在系统设置中开启相机访问，或粘贴配对链接")
        }
    }

    private func setupCaptureSession() {
        guard let videoDevice = AVCaptureDevice.default(for: .video),
              let videoInput = try? AVCaptureDeviceInput(device: videoDevice),
              session.canAddInput(videoInput)
        else {
            onUnavailable?("当前设备无可用摄像头，请在登录页手动粘贴 lework://pair 配对链接")
            return
        }
        session.addInput(videoInput)

        let metadataOutput = AVCaptureMetadataOutput()
        guard session.canAddOutput(metadataOutput) else {
            onUnavailable?("无法初始化二维码识别器")
            return
        }
        session.addOutput(metadataOutput)
        metadataOutput.setMetadataObjectsDelegate(self, queue: DispatchQueue.main)
        if metadataOutput.availableMetadataObjectTypes.contains(.qr) {
            metadataOutput.metadataObjectTypes = [.qr]
        }

        let layer = AVCaptureVideoPreviewLayer(session: session)
        layer.videoGravity = .resizeAspectFill
        layer.frame = view.bounds
        view.layer.insertSublayer(layer, at: 0)
        previewLayer = layer

        sessionQueue.async { [weak self] in
            self?.session.startRunning()
        }
    }

    func metadataOutput(
        _ output: AVCaptureMetadataOutput,
        didOutput metadataObjects: [AVMetadataObject],
        from connection: AVCaptureConnection
    ) {
        guard !didFinish else { return }
        for obj in metadataObjects {
            if let readable = obj as? AVMetadataMachineReadableCodeObject,
               let text = readable.stringValue?.trimmingCharacters(in: .whitespacesAndNewlines),
               !text.isEmpty
            {
                didFinish = true
                sessionQueue.async { [weak self] in
                    self?.session.stopRunning()
                }
                onScanned?(text)
                break
            }
        }
    }
}
