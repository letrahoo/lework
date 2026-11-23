#!/usr/bin/env swift
// Generates all Lework cross-platform app icons from a single mathematical Everforest Dark vector definition:
//   1. macOS `.icns` (`app/desktopApp/src/jvmMain/resources/Lework.icns`, Apple HIG 824/1024 squircle + soft shadow)
//   2. iOS `AppIcon.appiconset` (`1024x1024`, `180x180`, `120x120`, opaque full-bleed PNGs)
//   3. Android legacy `mipmap-{mdpi,hdpi,xhdpi,xxhdpi,xxxhdpi}/ic_launcher{,_round}.png`
//   4. Preview sheet (optional `--preview <path>`)
import CoreGraphics
import Foundation
import ImageIO
import UniformTypeIdentifiers

let sRGB = CGColorSpace(name: CGColorSpace.sRGB)!

func cgColor(_ hex: UInt32, alpha: CGFloat = 1.0) -> CGColor {
    let r = CGFloat((hex >> 16) & 0xFF) / 255.0
    let g = CGFloat((hex >> 8) & 0xFF) / 255.0
    let b = CGFloat(hex & 0xFF) / 255.0
    return CGColor(colorSpace: sRGB, components: [r, g, b, alpha])!
}

func squirclePath(rect: CGRect, radius: CGFloat) -> CGPath {
    let path = CGMutablePath()
    let x = rect.minX, y = rect.minY, w = rect.width, h = rect.height
    let r = min(radius, min(w, h) / 2)
    let k: CGFloat = 0.55228475 * 1.14
    let c = r * (1.0 - k)

    path.move(to: CGPoint(x: x + r, y: y))
    path.addLine(to: CGPoint(x: x + w - r, y: y))
    path.addCurve(to: CGPoint(x: x + w, y: y + r),
                  control1: CGPoint(x: x + w - c, y: y),
                  control2: CGPoint(x: x + w, y: y + c))
    path.addLine(to: CGPoint(x: x + w, y: y + h - r))
    path.addCurve(to: CGPoint(x: x + w - r, y: y + h),
                  control1: CGPoint(x: x + w, y: y + h - c),
                  control2: CGPoint(x: x + w - c, y: y + h))
    path.addLine(to: CGPoint(x: x + r, y: y + h))
    path.addCurve(to: CGPoint(x: x, y: y + h - r),
                  control1: CGPoint(x: x + c, y: y + h),
                  control2: CGPoint(x: x, y: y + h - c))
    path.addLine(to: CGPoint(x: x, y: y + r))
    path.addCurve(to: CGPoint(x: x + r, y: y),
                  control1: CGPoint(x: x, y: y + c),
                  control2: CGPoint(x: x + c, y: y))
    path.closeSubpath()
    return path
}

func roundedPolygonPath(_ pts: [CGPoint], radii: [CGFloat]) -> CGPath {
    let path = CGMutablePath()
    let n = pts.count
    let firstMid = CGPoint(x: (pts[n - 1].x + pts[0].x) * 0.5, y: (pts[n - 1].y + pts[0].y) * 0.5)
    path.move(to: firstMid)
    for i in 0..<n {
        let p = pts[i]
        let next = pts[(i + 1) % n]
        path.addArc(tangent1End: p, tangent2End: next, radius: radii[i])
    }
    path.closeSubpath()
    return path
}

func drawObsidianBackground(ctx: CGContext, rect: CGRect) {
    let cs = CGColorSpaceCreateDeviceRGB()
    let bgGrad = CGGradient(
        colorsSpace: cs,
        colors: [cgColor(0x252E34), cgColor(0x1C2327), cgColor(0x13191C)] as CFArray,
        locations: [0.0, 0.52, 1.0]
    )!
    ctx.drawLinearGradient(
        bgGrad,
        start: CGPoint(x: rect.minX + rect.width * 0.15, y: rect.maxY),
        end: CGPoint(x: rect.maxX - rect.width * 0.10, y: rect.minY),
        options: []
    )
    let topGlow = CGGradient(
        colorsSpace: cs,
        colors: [cgColor(0x38464E, alpha: 0.35), cgColor(0x1C2327, alpha: 0.0)] as CFArray,
        locations: [0.0, 1.0]
    )!
    ctx.drawRadialGradient(
        topGlow,
        startCenter: CGPoint(x: rect.midX, y: rect.maxY - rect.height * 0.18),
        startRadius: 0,
        endCenter: CGPoint(x: rect.midX, y: rect.maxY - rect.height * 0.18),
        endRadius: rect.width * 0.68,
        options: []
    )
}

func drawLeworkEmblem(ctx: CGContext, frame: CGRect, emblemScale: CGFloat = 1.05) {
    let cs = CGColorSpaceCreateDeviceRGB()
    let s = frame.width * emblemScale
    let ox = frame.midX - s * 0.5
    let oy = frame.midY - s * 0.5

    func pt(_ nx: CGFloat, _ ny: CGFloat) -> CGPoint {
        CGPoint(x: ox + nx * s, y: oy + (1.0 - ny) * s)
    }

    // Subtle ambient emerald & warm gold glows
    ctx.saveGState()
    let greenHalo = CGGradient(
        colorsSpace: cs,
        colors: [cgColor(0x83C995, alpha: 0.24), cgColor(0x83C995, alpha: 0.07), cgColor(0x83C995, alpha: 0.0)] as CFArray,
        locations: [0.0, 0.45, 1.0]
    )!
    ctx.drawRadialGradient(
        greenHalo,
        startCenter: pt(0.59, 0.41),
        startRadius: 0,
        endCenter: pt(0.59, 0.41),
        endRadius: s * 0.36,
        options: []
    )
    let goldHalo = CGGradient(
        colorsSpace: cs,
        colors: [cgColor(0xE6C384, alpha: 0.11), cgColor(0xE6C384, alpha: 0.0)] as CFArray,
        locations: [0.0, 1.0]
    )!
    ctx.drawRadialGradient(
        goldHalo,
        startCenter: pt(0.36, 0.66),
        startRadius: 0,
        endCenter: pt(0.36, 0.66),
        endRadius: s * 0.42,
        options: []
    )
    ctx.restoreGState()

    let seam: CGFloat = 0.017
    let barW: CGFloat = 0.140
    let lx: CGFloat = 0.220
    let ty: CGFloat = 0.215
    let by: CGFloat = 0.780
    let rx: CGFloat = 0.785
    let cornerR: CGFloat = 0.016 * s

    // 1. Vertical Mitered Brushed-Gold Bar
    let vPts = [
        pt(lx, ty + barW * 0.92),
        pt(lx + barW, ty),
        pt(lx + barW, by - barW - seam * 0.75),
        pt(lx, by - seam * 0.75),
    ]
    let vPath = roundedPolygonPath(vPts, radii: [cornerR * 1.15, cornerR * 0.85, cornerR * 0.65, cornerR * 0.75])

    ctx.saveGState()
    ctx.setShadow(offset: CGSize(width: 0, height: -s * 0.022), blur: s * 0.045, color: cgColor(0x000000, alpha: 0.55))
    ctx.addPath(vPath)
    ctx.setFillColor(cgColor(0xE6C384))
    ctx.fillPath()
    ctx.restoreGState()

    ctx.saveGState()
    ctx.addPath(vPath)
    ctx.clip()
    let vGoldGrad = CGGradient(
        colorsSpace: cs,
        colors: [cgColor(0xF8E3B5), cgColor(0xE8C688), cgColor(0xCFA45D)] as CFArray,
        locations: [0.0, 0.52, 1.0]
    )!
    ctx.drawLinearGradient(vGoldGrad, start: pt(lx, ty), end: pt(lx + barW * 1.2, by), options: [.drawsBeforeStartLocation, .drawsAfterEndLocation])
    ctx.addPath(vPath)
    ctx.setStrokeColor(cgColor(0xFFF3D4, alpha: 0.45))
    ctx.setLineWidth(s * 0.011)
    ctx.strokePath()
    ctx.restoreGState()

    // 2. Horizontal Mitered Brushed-Gold Bar (smooth full-span gradient, zero hard clamp seam!)
    let hPts = [
        pt(lx + barW + seam * 0.75, by - barW),
        pt(rx, by - barW),
        pt(rx - barW * 0.92, by),
        pt(lx + seam * 0.75, by),
    ]
    let hPath = roundedPolygonPath(hPts, radii: [cornerR * 0.65, cornerR * 0.85, cornerR * 1.15, cornerR * 0.75])

    ctx.saveGState()
    ctx.setShadow(offset: CGSize(width: 0, height: -s * 0.022), blur: s * 0.045, color: cgColor(0x000000, alpha: 0.55))
    ctx.addPath(hPath)
    ctx.setFillColor(cgColor(0xDFB874))
    ctx.fillPath()
    ctx.restoreGState()

    ctx.saveGState()
    ctx.addPath(hPath)
    ctx.clip()
    let hGoldGrad = CGGradient(
        colorsSpace: cs,
        colors: [cgColor(0xF6DFB0), cgColor(0xE4BE7C), cgColor(0xC69850)] as CFArray,
        locations: [0.0, 0.55, 1.0]
    )!
    ctx.drawLinearGradient(hGoldGrad, start: pt(lx, by - barW), end: pt(rx, by), options: [.drawsBeforeStartLocation, .drawsAfterEndLocation])
    ctx.addPath(hPath)
    ctx.setStrokeColor(cgColor(0xFFF2D0, alpha: 0.40))
    ctx.setLineWidth(s * 0.011)
    ctx.strokePath()
    ctx.restoreGState()

    // 3. Sculptural Faceted Forest-Green Forward Chevron `>`
    let cLeft: CGFloat = 0.482
    let cThick: CGFloat = 0.096
    let cTop: CGFloat = 0.270
    let cMid: CGFloat = 0.416
    let cBot: CGFloat = 0.562
    let armDx: CGFloat = cMid - cTop
    let cInnerTipX: CGFloat = cLeft + armDx
    let cOuterTipX: CGFloat = cInnerTipX + cThick

    let cUpperPts = [
        pt(cLeft, cTop),
        pt(cLeft + cThick, cTop),
        pt(cOuterTipX, cMid),
        pt(cInnerTipX, cMid),
    ]
    let cLowerPts = [
        pt(cInnerTipX, cMid),
        pt(cOuterTipX, cMid),
        pt(cLeft + cThick, cBot),
        pt(cLeft, cBot),
    ]
    let cFullPts = [
        pt(cLeft, cTop),
        pt(cLeft + cThick, cTop),
        pt(cOuterTipX, cMid),
        pt(cLeft + cThick, cBot),
        pt(cLeft, cBot),
        pt(cInnerTipX, cMid),
    ]
    let cR = 0.012 * s
    let cFullPath = roundedPolygonPath(cFullPts, radii: [cR, cR, cR * 1.1, cR, cR, cR * 0.8])

    ctx.saveGState()
    ctx.setShadow(offset: CGSize(width: 0, height: -s * 0.012), blur: s * 0.055, color: cgColor(0x83C995, alpha: 0.48))
    ctx.addPath(cFullPath)
    ctx.setFillColor(cgColor(0x83C995))
    ctx.fillPath()
    ctx.restoreGState()

    ctx.saveGState()
    ctx.addPath(cFullPath)
    ctx.clip()

    ctx.saveGState()
    let upPoly = CGMutablePath()
    upPoly.addLines(between: cUpperPts)
    upPoly.closeSubpath()
    ctx.addPath(upPoly)
    ctx.clip()
    let upGreen = CGGradient(
        colorsSpace: cs,
        colors: [cgColor(0xB9F0C6), cgColor(0x8BD29D), cgColor(0x6EB883)] as CFArray,
        locations: [0.0, 0.55, 1.0]
    )!
    ctx.drawLinearGradient(upGreen, start: pt(cLeft, cTop), end: pt(cOuterTipX, cMid), options: [.drawsBeforeStartLocation, .drawsAfterEndLocation])
    ctx.restoreGState()

    ctx.saveGState()
    let loPoly = CGMutablePath()
    loPoly.addLines(between: cLowerPts)
    loPoly.closeSubpath()
    ctx.addPath(loPoly)
    ctx.clip()
    let loGreen = CGGradient(
        colorsSpace: cs,
        colors: [cgColor(0x74BD88), cgColor(0x5CA472), cgColor(0x47895C)] as CFArray,
        locations: [0.0, 0.55, 1.0]
    )!
    ctx.drawLinearGradient(loGreen, start: pt(cOuterTipX, cMid), end: pt(cLeft, cBot), options: [.drawsBeforeStartLocation, .drawsAfterEndLocation])
    ctx.restoreGState()

    ctx.beginPath()
    ctx.move(to: pt(cInnerTipX, cMid))
    ctx.addLine(to: pt(cOuterTipX, cMid))
    ctx.setStrokeColor(cgColor(0xD8FAE0, alpha: 0.45))
    ctx.setLineWidth(s * 0.005)
    ctx.strokePath()

    ctx.addPath(cFullPath)
    ctx.setStrokeColor(cgColor(0xD7FBE0, alpha: 0.52))
    ctx.setLineWidth(s * 0.009)
    ctx.strokePath()

    ctx.restoreGState()
}

func renderFullBleed(size: Int, emblemScale: CGFloat = 1.06) -> CGImage {
    let cs = CGColorSpaceCreateDeviceRGB()
    let ctx = CGContext(data: nil, width: size, height: size, bitsPerComponent: 8, bytesPerRow: 0, space: cs, bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue)!
    ctx.interpolationQuality = .high
    ctx.setShouldAntialias(true)
    let rect = CGRect(x: 0, y: 0, width: size, height: size)
    drawObsidianBackground(ctx: ctx, rect: rect)
    drawLeworkEmblem(ctx: ctx, frame: rect, emblemScale: emblemScale)
    return ctx.makeImage()!
}

func renderMacOsSquircle(size: Int) -> CGImage {
    let cs = CGColorSpaceCreateDeviceRGB()
    let ctx = CGContext(data: nil, width: size, height: size, bitsPerComponent: 8, bytesPerRow: 0, space: cs, bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue)!
    ctx.interpolationQuality = .high
    ctx.setShouldAntialias(true)
    let s = CGFloat(size)
    // Apple HIG macOS icon template: 824/1024 squircle + soft Dock shadow + subtle glass rim
    let tileW = s * (824.0 / 1024.0)
    let inset = (s - tileW) * 0.5
    let tile = CGRect(x: inset, y: inset, width: tileW, height: tileW)
    let sq = squirclePath(rect: tile, radius: tileW * 0.225)

    ctx.saveGState()
    ctx.setShadow(offset: CGSize(width: 0, height: -s * 0.020), blur: s * 0.042, color: cgColor(0x000000, alpha: 0.42))
    ctx.addPath(sq)
    ctx.setFillColor(cgColor(0x161C1F))
    ctx.fillPath()
    ctx.restoreGState()

    ctx.saveGState()
    ctx.addPath(sq)
    ctx.clip()
    drawObsidianBackground(ctx: ctx, rect: tile)
    drawLeworkEmblem(ctx: ctx, frame: tile, emblemScale: 1.06)

    let rimRect = tile.insetBy(dx: tileW * 0.006, dy: tileW * 0.006)
    ctx.addPath(squirclePath(rect: rimRect, radius: tileW * 0.220))
    ctx.setStrokeColor(cgColor(0xE8DFCC, alpha: 0.14))
    ctx.setLineWidth(max(1.0, tileW * 0.010))
    ctx.strokePath()
    ctx.restoreGState()

    return ctx.makeImage()!
}

func renderAndroidLegacy(size: Int, circular: Bool) -> CGImage {
    let cs = CGColorSpaceCreateDeviceRGB()
    let ctx = CGContext(data: nil, width: size, height: size, bitsPerComponent: 8, bytesPerRow: 0, space: cs, bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue)!
    ctx.interpolationQuality = .high
    ctx.setShouldAntialias(true)
    let s = CGFloat(size)
    let pad = s * 0.04
    let tile = CGRect(x: pad, y: pad, width: s - pad * 2, height: s - pad * 2)

    ctx.saveGState()
    if circular {
        ctx.addEllipse(in: tile)
    } else {
        ctx.addPath(squirclePath(rect: tile, radius: tile.width * 0.22))
    }
    ctx.clip()
    drawObsidianBackground(ctx: ctx, rect: tile)
    drawLeworkEmblem(ctx: ctx, frame: tile, emblemScale: circular ? 0.96 : 1.04)

    if circular {
        ctx.addEllipse(in: tile.insetBy(dx: max(0.5, s * 0.008), dy: max(0.5, s * 0.008)))
    } else {
        ctx.addPath(squirclePath(rect: tile.insetBy(dx: max(0.5, s * 0.008), dy: max(0.5, s * 0.008)), radius: tile.width * 0.215))
    }
    ctx.setStrokeColor(cgColor(0xE8DFCC, alpha: 0.14))
    ctx.setLineWidth(max(1.0, s * 0.012))
    ctx.strokePath()
    ctx.restoreGState()
    return ctx.makeImage()!
}

func savePng(_ image: CGImage, to path: String) {
    let url = URL(fileURLWithPath: path)
    try? FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
    guard let dest = CGImageDestinationCreateWithURL(url as CFURL, UTType.png.identifier as CFString, 1, nil) else {
        fatalError("Failed to create PNG destination at \(path)")
    }
    CGImageDestinationAddImage(dest, image, nil)
    guard CGImageDestinationFinalize(dest) else {
        fatalError("Failed to write PNG at \(path)")
    }
}

let scriptUrl = URL(fileURLWithPath: CommandLine.arguments[0]).standardizedFileURL
let repoRoot = scriptUrl.deletingLastPathComponent().deletingLastPathComponent().path

// 1. iOS AppIcon.appiconset (opaque full-bleed PNGs, true 1024x1024 / 180x180 / 120x120)
let iosIconDir = "\(repoRoot)/app/iosApp/iosApp/Assets.xcassets/AppIcon.appiconset"
savePng(renderFullBleed(size: 1024), to: "\(iosIconDir)/AppIcon-1024.png")
savePng(renderFullBleed(size: 180), to: "\(iosIconDir)/AppIcon-60@3x.png")
savePng(renderFullBleed(size: 120), to: "\(iosIconDir)/AppIcon-60@2x.png")
print("Generated iOS AppIcon assets in \(iosIconDir)")

// 2. Android legacy mipmap PNGs (all 5 densities, both square-squircle & circular)
let androidResDir = "\(repoRoot)/app/androidApp/src/main/res"
let androidDensities: [(String, Int)] = [
    ("mdpi", 48),
    ("hdpi", 72),
    ("xhdpi", 96),
    ("xxhdpi", 144),
    ("xxxhdpi", 192),
]
for (density, px) in androidDensities {
    savePng(renderAndroidLegacy(size: px, circular: false), to: "\(androidResDir)/mipmap-\(density)/ic_launcher.png")
    savePng(renderAndroidLegacy(size: px, circular: true), to: "\(androidResDir)/mipmap-\(density)/ic_launcher_round.png")
}
print("Generated Android mipmap PNGs across 5 densities in \(androidResDir)")

// 3. macOS .icns (Apple HIG squircle, standard PNG-embedded ICNS chunks for all Retina sizes)
let desktopResDir = "\(repoRoot)/app/desktopApp/src/jvmMain/resources"
try FileManager.default.createDirectory(atPath: desktopResDir, withIntermediateDirectories: true)
let icnsPath = "\(desktopResDir)/Lework.icns"

func encodePngData(_ image: CGImage) -> Data {
    let data = NSMutableData()
    guard let dest = CGImageDestinationCreateWithData(data as CFMutableData, UTType.png.identifier as CFString, 1, nil) else {
        fatalError("Failed to create in-memory PNG encoder")
    }
    CGImageDestinationAddImage(dest, image, nil)
    guard CGImageDestinationFinalize(dest) else {
        fatalError("Failed to encode PNG bytes")
    }
    return data as Data
}

// Standard Apple ICNS type codes embedding lossless PNGs for 16..1024px (1x & 2x Retina)
let icnsChunks: [(String, Int)] = [
    ("icp4", 16),   // 16x16 @1x
    ("ic11", 32),   // 16x16 @2x
    ("icp5", 32),   // 32x32 @1x
    ("ic12", 64),   // 32x32 @2x
    ("ic07", 128),  // 128x128 @1x
    ("ic13", 256),  // 128x128 @2x
    ("ic08", 256),  // 256x256 @1x
    ("ic14", 512),  // 256x256 @2x
    ("ic09", 512),  // 512x512 @1x
    ("ic10", 1024), // 512x512 @2x
]
var pngBySize: [Int: Data] = [:]
var body = Data()
for (fourCC, px) in icnsChunks {
    let png = pngBySize[px] ?? {
        let d = encodePngData(renderMacOsSquircle(size: px))
        pngBySize[px] = d
        return d
    }()
    let tag = fourCC.data(using: .ascii)!
    var chunkLen = UInt32(8 + png.count).bigEndian
    body.append(tag)
    withUnsafeBytes(of: &chunkLen) { body.append(contentsOf: $0) }
    body.append(png)
}
var icnsData = Data("icns".utf8)
var totalLen = UInt32(8 + body.count).bigEndian
withUnsafeBytes(of: &totalLen) { icnsData.append(contentsOf: $0) }
icnsData.append(body)
try icnsData.write(to: URL(fileURLWithPath: icnsPath))
print("Generated macOS icon bundle: \(icnsPath) (\(icnsData.count) bytes)")

// 4. Optional preview sheet for visual inspection / PR review
if let idx = CommandLine.arguments.firstIndex(of: "--preview"), idx + 1 < CommandLine.arguments.count {
    let previewPath = CommandLine.arguments[idx + 1]
    let W = 1600, H = 760
    let cs = CGColorSpaceCreateDeviceRGB()
    let ctx = CGContext(data: nil, width: W, height: H, bitsPerComponent: 8, bytesPerRow: 0, space: cs, bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue)!
    ctx.setFillColor(cgColor(0x101417))
    ctx.fill(CGRect(x: 0, y: 0, width: W, height: H))

    let macImg = renderMacOsSquircle(size: 1024)
    let iosImg = renderFullBleed(size: 1024)

    // Dark macOS Dock tile
    ctx.draw(macImg, in: CGRect(x: 60, y: 150, width: 460, height: 460))
    // Light macOS wallpaper card
    let lightCard = CGRect(x: 560, y: 130, width: 480, height: 500)
    ctx.saveGState()
    ctx.addPath(squirclePath(rect: lightCard, radius: 36))
    ctx.setFillColor(cgColor(0xEAE7E1))
    ctx.fillPath()
    ctx.draw(macImg, in: CGRect(x: 580, y: 150, width: 440, height: 440))
    ctx.restoreGState()
    // Android circle adaptive preview
    let circleRect = CGRect(x: 1120, y: 360, width: 320, height: 320)
    ctx.saveGState()
    ctx.addEllipse(in: circleRect)
    ctx.clip()
    ctx.draw(iosImg, in: circleRect)
    ctx.restoreGState()
    // Multi-size Dock scale checks
    ctx.draw(macImg, in: CGRect(x: 1120, y: 160, width: 128, height: 128))
    ctx.draw(macImg, in: CGRect(x: 1280, y: 192, width: 64, height: 64))
    ctx.draw(macImg, in: CGRect(x: 1376, y: 208, width: 32, height: 32))

    savePng(ctx.makeImage()!, to: previewPath)
    print("Generated brand preview sheet: \(previewPath)")
}
