import AVFoundation
import Darwin
import Network
import PhotosUI
import Shared
import SwiftUI
import UIKit

final class NativeBridgeCoordinator: NSObject, ObservableObject, IosNativeDelegate {
    @Published var isShowingScanner: Bool = false
    @Published var isShowingPhotoPicker: Bool = false
    @Published var isShowingCameraCapture: Bool = false
    @Published var photoSelectionLimit: Int = 4
    private var activeQrReqId: Int64?
    private var activeImageReqId: Int64?
    private let tsnetQueue = DispatchQueue(label: "dev.lework.tsnet", qos: .userInitiated)
    private let pathMonitor = NWPathMonitor()
    private let pathQueue = DispatchQueue(label: "dev.lework.pathmonitor", qos: .utility)
    private var lastPathSignature: String?

    override init() {
        super.init()
        IosNativeBridge.shared.registerDelegate(delegate: self)
        startPathMonitor()
    }

    private func startPathMonitor() {
        pathMonitor.pathUpdateHandler = { [weak self] path in
            guard let self else { return }
            let isWifi = path.usesInterfaceType(.wifi) || path.usesInterfaceType(.wiredEthernet)
            let isCell = path.usesInterfaceType(.cellular)
            let ifaceNames = path.availableInterfaces.map(\.name).joined(separator: ",")
            let sig = "\(path.status)-\(isWifi)-\(isCell)-\(ifaceNames)"
            if self.lastPathSignature == nil {
                self.lastPathSignature = sig
                return
            }
            guard self.lastPathSignature != sig else { return }
            self.lastPathSignature = sig
            self.tsnetQueue.async { [weak self] in
                guard let self else { return }
                let json = self.consumeGoCString(LeworkTsnetNotifyLinkChange())
                DispatchQueue.main.async {
                    IosNativeBridge.shared.notifyNetworkChanged(tsnetJson: json, isWifi: isWifi)
                }
            }
        }
        pathMonitor.start(queue: pathQueue)
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

    func presentImagePicker(reqId: Int64, useCamera: Bool, maxCount: Int32) {
        DispatchQueue.main.async { [weak self] in
            guard let self else { return }
            if let prev = self.activeImageReqId {
                IosNativeBridge.shared.completeMultiImagePick(reqId: prev, payloadsJson: nil)
            }
            self.activeImageReqId = reqId
            let clamped = max(1, min(4, Int(maxCount)))
            self.photoSelectionLimit = clamped

            if useCamera && UIImagePickerController.isSourceTypeAvailable(.camera) {
                let auth = AVCaptureDevice.authorizationStatus(for: .video)
                switch auth {
                case .authorized:
                    self.isShowingCameraCapture = true
                case .notDetermined:
                    AVCaptureDevice.requestAccess(for: .video) { [weak self] granted in
                        DispatchQueue.main.async {
                            guard let self else { return }
                            if granted {
                                self.isShowingCameraCapture = true
                            } else {
                                self.finishMultiImagePick(nil)
                            }
                        }
                    }
                default:
                    self.finishMultiImagePick(nil)
                }
            } else {
                self.isShowingPhotoPicker = true
            }
        }
    }

    func finishMultiImagePick(_ payloadsJson: String?) {
        DispatchQueue.main.async { [weak self] in
            guard let self else { return }
            self.isShowingPhotoPicker = false
            self.isShowingCameraCapture = false
            if let reqId = self.activeImageReqId {
                self.activeImageReqId = nil
                IosNativeBridge.shared.completeMultiImagePick(reqId: reqId, payloadsJson: payloadsJson)
            }
        }
    }
}

private enum IosImageEncoder {
    struct EncodedItem: Codable {
        let fileName: String
        let mimeType: String
        let dataBase64: String
        let thumbnailBase64: String?
    }

    static func encodeBatchToJson(_ items: [(UIImage, String)]) -> String? {
        var encoded: [EncodedItem] = []
        for (idx, pair) in items.enumerated() {
            if let item = encodeSingle(image: pair.0, rawName: pair.1, index: idx + 1) {
                encoded.append(item)
            }
        }
        guard !encoded.isEmpty,
              let data = try? JSONEncoder().encode(encoded),
              let json = String(data: data, encoding: .utf8)
        else {
            return nil
        }
        return json
    }

    static func encodeSingle(image: UIImage, rawName: String, index: Int) -> EncodedItem? {
        let normalized = normalizeAndScale(image: image, maxDimension: 1280)
        var mainB64 = ""
        let attempts: [(CGFloat, CGFloat)] = [
            (1280, 0.78),
            (1280, 0.62),
            (1024, 0.52),
            (800, 0.44),
        ]
        for (dim, quality) in attempts {
            let candidate = dim < 1280 ? normalizeAndScale(image: normalized, maxDimension: dim) : normalized
            if let jpeg = candidate.jpegData(compressionQuality: quality) {
                let b64 = jpeg.base64EncodedString()
                mainB64 = b64
                if b64.count <= 900_000 {
                    break
                }
            }
        }
        guard !mainB64.isEmpty, mainB64.count <= 980_000 else { return nil }

        let thumbImg = normalizeAndScale(image: normalized, maxDimension: 520)
        let thumbB64 = thumbImg.jpegData(compressionQuality: 0.68)?.base64EncodedString()
        let cleanThumb = (thumbB64 != nil && thumbB64!.count <= 240_000) ? thumbB64 : nil

        let trimmed = rawName.trimmingCharacters(in: .whitespacesAndNewlines)
        let base = trimmed.isEmpty ? "photo_\(index)" : trimmed
        let finalName = base.lowercased().hasSuffix(".jpg") || base.lowercased().hasSuffix(".jpeg")
            ? base
            : "\(base).jpg"

        return EncodedItem(
            fileName: String(finalName.prefix(100)),
            mimeType: "image/jpeg",
            dataBase64: mainB64,
            thumbnailBase64: cleanThumb
        )
    }

    private static func normalizeAndScale(image: UIImage, maxDimension: CGFloat) -> UIImage {
        let size = image.size
        let longest = max(size.width, size.height)
        guard longest > 0 else { return image }
        let ratio = longest > maxDimension ? (maxDimension / longest) : 1.0
        if ratio == 1.0 && image.imageOrientation == .up {
            return image
        }
        let newSize = CGSize(
            width: max(1, floor(size.width * ratio)),
            height: max(1, floor(size.height * ratio))
        )
        let format = UIGraphicsImageRendererFormat.default()
        format.scale = 1.0
        format.opaque = true
        return UIGraphicsImageRenderer(size: newSize, format: format).image { _ in
            image.draw(in: CGRect(origin: .zero, size: newSize))
        }
    }
}

struct PhotoPickerSheet: UIViewControllerRepresentable {
    let selectionLimit: Int
    let onComplete: (String?) -> Void

    func makeCoordinator() -> Coordinator {
        Coordinator(onComplete: onComplete)
    }

    func makeUIViewController(context: Context) -> PHPickerViewController {
        var config = PHPickerConfiguration()
        config.filter = .images
        config.selectionLimit = max(1, min(4, selectionLimit))
        if #available(iOS 15.0, *) {
            config.selection = .ordered
        }
        let picker = PHPickerViewController(configuration: config)
        picker.delegate = context.coordinator
        return picker
    }

    func updateUIViewController(_ uiViewController: PHPickerViewController, context: Context) {}

    final class Coordinator: NSObject, PHPickerViewControllerDelegate {
        let onComplete: (String?) -> Void
        private var didFinish = false

        init(onComplete: @escaping (String?) -> Void) {
            self.onComplete = onComplete
        }

        func picker(_ picker: PHPickerViewController, didFinishPicking results: [PHPickerResult]) {
            guard !didFinish else { return }
            didFinish = true
            let valid = results.filter { $0.itemProvider.canLoadObject(ofClass: UIImage.self) }
            guard !valid.isEmpty else {
                onComplete(nil)
                return
            }

            let group = DispatchGroup()
            let lock = NSLock()
            var loadedByIdx: [Int: (UIImage, String)] = [:]

            for (idx, item) in valid.prefix(4).enumerated() {
                let suggested = item.itemProvider.suggestedName ?? "photo_\(idx + 1)"
                group.enter()
                item.itemProvider.loadObject(ofClass: UIImage.self) { obj, _ in
                    if let img = obj as? UIImage {
                        lock.lock()
                        loadedByIdx[idx] = (img, suggested)
                        lock.unlock()
                    }
                    group.leave()
                }
            }

            group.notify(queue: DispatchQueue.global(qos: .userInitiated)) { [onComplete] in
                let ordered = (0..<valid.count).compactMap { loadedByIdx[$0] }
                let json = IosImageEncoder.encodeBatchToJson(ordered)
                DispatchQueue.main.async {
                    onComplete(json)
                }
            }
        }
    }
}

struct CameraPhotoCaptureSheet: UIViewControllerRepresentable {
    let onComplete: (String?) -> Void

    func makeCoordinator() -> Coordinator {
        Coordinator(onComplete: onComplete)
    }

    func makeUIViewController(context: Context) -> UIImagePickerController {
        let picker = UIImagePickerController()
        picker.sourceType = .camera
        picker.cameraCaptureMode = .photo
        picker.delegate = context.coordinator
        return picker
    }

    func updateUIViewController(_ uiViewController: UIImagePickerController, context: Context) {}

    final class Coordinator: NSObject, UINavigationControllerDelegate, UIImagePickerControllerDelegate {
        let onComplete: (String?) -> Void
        private var didFinish = false

        init(onComplete: @escaping (String?) -> Void) {
            self.onComplete = onComplete
        }

        func imagePickerControllerDidCancel(_ picker: UIImagePickerController) {
            guard !didFinish else { return }
            didFinish = true
            onComplete(nil)
        }

        func imagePickerController(
            _ picker: UIImagePickerController,
            didFinishPickingMediaWithInfo info: [UIImagePickerController.InfoKey: Any]
        ) {
            guard !didFinish else { return }
            didFinish = true
            guard let uiImage = (info[.editedImage] ?? info[.originalImage]) as? UIImage else {
                onComplete(nil)
                return
            }
            let ts = Int(Date().timeIntervalSince1970)
            DispatchQueue.global(qos: .userInitiated).async { [onComplete] in
                let json = IosImageEncoder.encodeBatchToJson([(uiImage, "camera_\(ts)")])
                DispatchQueue.main.async {
                    onComplete(json)
                }
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
