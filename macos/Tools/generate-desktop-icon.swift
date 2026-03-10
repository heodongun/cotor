import AppKit
import Foundation

func writePNG(_ image: NSImage, to outputPath: String) throws {
    guard
        let tiffData = image.tiffRepresentation,
        let bitmap = NSBitmapImageRep(data: tiffData),
        let pngData = bitmap.representation(using: .png, properties: [:])
    else {
        throw NSError(domain: "CotorIcon", code: 1, userInfo: [
            NSLocalizedDescriptionKey: "Failed to encode PNG data.",
        ])
    }

    try pngData.write(to: URL(fileURLWithPath: outputPath))
}

func aspectFitRect(sourceSize: CGSize, inside destination: CGRect) -> CGRect {
    let widthRatio = destination.width / sourceSize.width
    let heightRatio = destination.height / sourceSize.height
    let scale = min(widthRatio, heightRatio)
    let fittedSize = CGSize(width: sourceSize.width * scale, height: sourceSize.height * scale)

    return CGRect(
        x: destination.midX - (fittedSize.width / 2),
        y: destination.midY - (fittedSize.height / 2),
        width: fittedSize.width,
        height: fittedSize.height
    )
}

func preparedBrandMark(from sourcePath: String) throws -> CGImage {
    let sourceURL = URL(fileURLWithPath: sourcePath)
    let sourceData = try Data(contentsOf: sourceURL)

    guard
        let bitmap = NSBitmapImageRep(data: sourceData),
        let fullImage = bitmap.cgImage
    else {
        throw NSError(domain: "CotorIcon", code: 10, userInfo: [
            NSLocalizedDescriptionKey: "Failed to decode rasterized logo at \(sourcePath).",
        ])
    }

    let cropRect = CGRect(
        x: CGFloat(fullImage.width) * 0.05,
        y: CGFloat(fullImage.height) * 0.05,
        width: CGFloat(fullImage.width) * 0.90,
        height: CGFloat(fullImage.height) * 0.43
    ).integral

    guard let croppedImage = fullImage.cropping(to: cropRect) else {
        throw NSError(domain: "CotorIcon", code: 11, userInfo: [
            NSLocalizedDescriptionKey: "Failed to crop the Cotor symbol region.",
        ])
    }

    let markBitmap = NSBitmapImageRep(cgImage: croppedImage)

    // The source SVG includes a white artboard rectangle. That is fine for the
    // brand file itself, but it looks like a pasted sticker inside the Dock
    // icon. We strip only near-white pixels so the official mark keeps its
    // original colors and anti-aliased edges.
    for y in 0..<markBitmap.pixelsHigh {
        for x in 0..<markBitmap.pixelsWide {
            guard let color = markBitmap.colorAt(x: x, y: y) else {
                continue
            }

            if color.redComponent > 0.985 && color.greenComponent > 0.985 && color.blueComponent > 0.985 {
                markBitmap.setColor(
                    NSColor(
                        calibratedRed: color.redComponent,
                        green: color.greenComponent,
                        blue: color.blueComponent,
                        alpha: 0
                    ),
                    atX: x,
                    y: y
                )
            }
        }
    }

    guard let preparedImage = markBitmap.cgImage else {
        throw NSError(domain: "CotorIcon", code: 12, userInfo: [
            NSLocalizedDescriptionKey: "Failed to prepare the transparent Cotor symbol.",
        ])
    }

    return preparedImage
}

func makeFallbackIcon(size: CGFloat) -> NSImage {
    let image = NSImage(size: NSSize(width: size, height: size))

    image.lockFocus()

    guard let context = NSGraphicsContext.current?.cgContext else {
        fputs("Failed to create fallback graphics context.\n", stderr)
        exit(1)
    }

    context.setAllowsAntialiasing(true)
    context.setShouldAntialias(true)

    let canvas = CGRect(x: 0, y: 0, width: size, height: size)
    let appRect = canvas.insetBy(dx: 74, dy: 74)
    let framePath = NSBezierPath(roundedRect: appRect, xRadius: 210, yRadius: 210)

    NSColor(calibratedRed: 0.035, green: 0.053, blue: 0.084, alpha: 1).setFill()
    canvas.fill()

    context.saveGState()
    context.setShadow(offset: CGSize(width: 0, height: -26), blur: 84, color: NSColor.black.withAlphaComponent(0.45).cgColor)
    framePath.addClip()
    NSGradient(colors: [
        NSColor(calibratedRed: 0.055, green: 0.098, blue: 0.152, alpha: 1),
        NSColor(calibratedRed: 0.068, green: 0.188, blue: 0.285, alpha: 1),
        NSColor(calibratedRed: 0.035, green: 0.067, blue: 0.118, alpha: 1),
    ])?.draw(in: framePath, angle: 315)
    context.restoreGState()

    NSColor.white.withAlphaComponent(0.08).setStroke()
    framePath.lineWidth = 5
    framePath.stroke()

    let glowOval = NSBezierPath(ovalIn: CGRect(x: 212, y: 654, width: 420, height: 256))
    NSGradient(colors: [
        NSColor(calibratedRed: 0.31, green: 0.80, blue: 1.00, alpha: 0.42),
        NSColor(calibratedRed: 0.31, green: 0.80, blue: 1.00, alpha: 0.02),
    ])?.draw(in: glowOval, relativeCenterPosition: .zero)

    let cardRect = CGRect(x: 182, y: 166, width: 660, height: 692)
    let cardPath = NSBezierPath(roundedRect: cardRect, xRadius: 118, yRadius: 118)
    context.saveGState()
    context.setShadow(offset: CGSize(width: 0, height: -16), blur: 40, color: NSColor.black.withAlphaComponent(0.32).cgColor)
    cardPath.addClip()
    NSGradient(colors: [
        NSColor(calibratedRed: 0.088, green: 0.128, blue: 0.189, alpha: 0.98),
        NSColor(calibratedRed: 0.058, green: 0.083, blue: 0.132, alpha: 0.98),
    ])?.draw(in: cardPath, angle: 270)
    context.restoreGState()

    NSColor.white.withAlphaComponent(0.12).setStroke()
    cardPath.lineWidth = 3
    cardPath.stroke()

    let columnRects = [
        CGRect(x: 230, y: 232, width: 156, height: 560),
        CGRect(x: 414, y: 232, width: 176, height: 560),
        CGRect(x: 618, y: 232, width: 176, height: 560),
    ]

    for (index, rect) in columnRects.enumerated() {
        let columnPath = NSBezierPath(roundedRect: rect, xRadius: 56, yRadius: 56)
        let alpha = index == 1 ? 0.17 : 0.12
        NSColor.white.withAlphaComponent(alpha).setFill()
        columnPath.fill()
        NSColor.white.withAlphaComponent(index == 1 ? 0.18 : 0.11).setStroke()
        columnPath.lineWidth = 2.5
        columnPath.stroke()
    }

    let hubRect = CGRect(x: 468, y: 436, width: 72, height: 72)
    let hubPath = NSBezierPath(ovalIn: hubRect)
    context.saveGState()
    context.setShadow(offset: .zero, blur: 28, color: NSColor(calibratedRed: 0.28, green: 0.83, blue: 1.00, alpha: 0.55).cgColor)
    NSColor(calibratedRed: 0.28, green: 0.83, blue: 1.00, alpha: 1).setFill()
    hubPath.fill()
    context.restoreGState()

    let spokeCenters = [
        CGPoint(x: 308, y: 654),
        CGPoint(x: 308, y: 540),
        CGPoint(x: 308, y: 424),
        CGPoint(x: 506, y: 666),
        CGPoint(x: 706, y: 652),
        CGPoint(x: 706, y: 516),
        CGPoint(x: 706, y: 378),
    ]

    let hubCenter = CGPoint(x: hubRect.midX, y: hubRect.midY)

    for center in spokeCenters {
        let line = NSBezierPath()
        line.move(to: hubCenter)
        line.line(to: center)
        line.lineWidth = 9
        line.lineCapStyle = .round
        NSColor.white.withAlphaComponent(0.08).setStroke()
        line.stroke()

        let node = NSBezierPath(ovalIn: CGRect(x: center.x - 17, y: center.y - 17, width: 34, height: 34))
        let nodeColor: NSColor = center.x < hubCenter.x
            ? NSColor(calibratedRed: 0.32, green: 0.90, blue: 0.78, alpha: 1)
            : NSColor(calibratedRed: 1.00, green: 0.76, blue: 0.29, alpha: 1)

        context.saveGState()
        context.setShadow(offset: .zero, blur: 18, color: nodeColor.withAlphaComponent(0.45).cgColor)
        nodeColor.setFill()
        node.fill()
        context.restoreGState()
    }

    let footerBar = NSBezierPath(roundedRect: CGRect(x: 238, y: 268, width: 538, height: 82), xRadius: 34, yRadius: 34)
    NSColor.white.withAlphaComponent(0.08).setFill()
    footerBar.fill()

    let footerSegments = [
        CGRect(x: 274, y: 296, width: 118, height: 24),
        CGRect(x: 426, y: 296, width: 94, height: 24),
        CGRect(x: 552, y: 296, width: 170, height: 24),
    ]

    for rect in footerSegments {
        let segment = NSBezierPath(roundedRect: rect, xRadius: 12, yRadius: 12)
        NSColor.white.withAlphaComponent(0.18).setFill()
        segment.fill()
    }

    image.unlockFocus()
    return image
}

func makeBrandedIcon(sourcePath: String, outputPath: String, size: CGFloat) throws {
    let brandMark = try preparedBrandMark(from: sourcePath)

    let image = NSImage(size: NSSize(width: size, height: size))
    image.lockFocus()

    guard let context = NSGraphicsContext.current?.cgContext else {
        throw NSError(domain: "CotorIcon", code: 3, userInfo: [
            NSLocalizedDescriptionKey: "Failed to create icon graphics context.",
        ])
    }

    context.setAllowsAntialiasing(true)
    context.setShouldAntialias(true)
    context.interpolationQuality = .high

    let canvas = CGRect(x: 0, y: 0, width: size, height: size)
    let cardRect = canvas.insetBy(dx: 66, dy: 66)
    let cardPath = NSBezierPath(roundedRect: cardRect, xRadius: 214, yRadius: 214)

    // The icon uses the official Cotor SVG as the source of truth, but it
    // intentionally crops to the symbol lockup instead of the full wordmark.
    // macOS icons need a bold, compact silhouette at 16px-64px sizes, and the
    // full lockup becomes unreadable there.
    let contentBounds = cardRect.insetBy(dx: 92, dy: 132).offsetBy(dx: 0, dy: 24)
    let symbolRect = aspectFitRect(
        sourceSize: CGSize(width: brandMark.width, height: brandMark.height),
        inside: contentBounds
    )

    // The background stays neutral and warm so the purple/orange brand mark
    // remains dominant while still reading as a polished native macOS icon.
    NSColor(calibratedRed: 0.965, green: 0.972, blue: 0.988, alpha: 1).setFill()
    canvas.fill()

    context.saveGState()
    context.setShadow(offset: CGSize(width: 0, height: -22), blur: 78, color: NSColor.black.withAlphaComponent(0.18).cgColor)
    cardPath.addClip()
    NSGradient(colors: [
        NSColor(calibratedRed: 0.995, green: 0.996, blue: 1.000, alpha: 1),
        NSColor(calibratedRed: 0.954, green: 0.963, blue: 0.984, alpha: 1),
    ])?.draw(in: cardPath, angle: 270)
    context.restoreGState()

    NSColor.white.withAlphaComponent(0.92).setStroke()
    cardPath.lineWidth = 4
    cardPath.stroke()

    let purpleGlow = NSBezierPath(ovalIn: CGRect(x: 104, y: 546, width: 334, height: 280))
    NSGradient(colors: [
        NSColor(calibratedRed: 0.392, green: 0.224, blue: 0.651, alpha: 0.17),
        NSColor(calibratedRed: 0.392, green: 0.224, blue: 0.651, alpha: 0.01),
    ])?.draw(in: purpleGlow, relativeCenterPosition: .zero)

    let coralGlow = NSBezierPath(ovalIn: CGRect(x: 560, y: 300, width: 286, height: 248))
    NSGradient(colors: [
        NSColor(calibratedRed: 0.969, green: 0.451, blue: 0.224, alpha: 0.16),
        NSColor(calibratedRed: 0.969, green: 0.451, blue: 0.224, alpha: 0.01),
    ])?.draw(in: coralGlow, relativeCenterPosition: .zero)

    let insetCard = NSBezierPath(roundedRect: cardRect.insetBy(dx: 12, dy: 12), xRadius: 186, yRadius: 186)
    NSColor(calibratedWhite: 1, alpha: 0.42).setStroke()
    insetCard.lineWidth = 2
    insetCard.stroke()

    let shadowColor = NSColor(calibratedRed: 0.271, green: 0.141, blue: 0.416, alpha: 0.18)
    context.saveGState()
    context.setShadow(offset: CGSize(width: 0, height: -18), blur: 48, color: shadowColor.cgColor)
    context.draw(brandMark, in: symbolRect)
    context.restoreGState()

    let highlightArc = NSBezierPath()
    highlightArc.move(to: CGPoint(x: 240, y: 868))
    highlightArc.curve(to: CGPoint(x: 792, y: 872), controlPoint1: CGPoint(x: 392, y: 922), controlPoint2: CGPoint(x: 642, y: 924))
    highlightArc.lineWidth = 9
    highlightArc.lineCapStyle = .round
    NSColor.white.withAlphaComponent(0.30).setStroke()
    highlightArc.stroke()

    image.unlockFocus()
    try writePNG(image, to: outputPath)
}

let arguments = CommandLine.arguments
let size: CGFloat = 1024

do {
    switch arguments.count {
    case 2:
        try writePNG(makeFallbackIcon(size: size), to: arguments[1])
    case 3:
        try makeBrandedIcon(sourcePath: arguments[1], outputPath: arguments[2], size: size)
    default:
        fputs("Usage: generate-desktop-icon.swift [source-logo.png] <output.png>\n", stderr)
        exit(1)
    }
} catch {
    fputs("\(error.localizedDescription)\n", stderr)
    exit(1)
}
