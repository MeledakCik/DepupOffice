import AppKit
import CoreGraphics
import CoreText
import ImageIO
import UniformTypeIdentifiers

private let W = 1080
private let H = 1920
private let space = CGColorSpace(name: CGColorSpace.sRGB)!

struct Slide {
    let source: String
    let output: String
    let headline: [String]
    let subtitle: String
    let accent: UInt32
}

func color(_ rgb: UInt32, _ alpha: CGFloat = 1) -> CGColor {
    CGColor(red: CGFloat((rgb >> 16) & 0xff) / 255,
            green: CGFloat((rgb >> 8) & 0xff) / 255,
            blue: CGFloat(rgb & 0xff) / 255,
            alpha: alpha)
}

func cgRect(_ x: CGFloat, _ top: CGFloat, _ width: CGFloat, _ height: CGFloat, canvasHeight: CGFloat = CGFloat(H)) -> CGRect {
    CGRect(x: x, y: canvasHeight - top - height, width: width, height: height)
}

func roundedPath(_ rect: CGRect, _ radius: CGFloat) -> CGPath {
    CGPath(roundedRect: rect, cornerWidth: radius, cornerHeight: radius, transform: nil)
}

func drawText(_ ctx: CGContext, _ value: String, x: CGFloat, top: CGFloat, size: CGFloat,
              fontName: String, color textColor: CGColor, tracking: CGFloat = 0) {
    let font = CTFontCreateWithName(fontName as CFString, size, nil)
    var attrs: [NSAttributedString.Key: Any] = [
        NSAttributedString.Key(rawValue: kCTFontAttributeName as String): font,
        NSAttributedString.Key(rawValue: kCTForegroundColorAttributeName as String): textColor
    ]
    if tracking != 0 {
        attrs[NSAttributedString.Key(rawValue: kCTKernAttributeName as String)] = tracking
    }
    let line = CTLineCreateWithAttributedString(NSAttributedString(string: value, attributes: attrs))
    var ascent: CGFloat = 0
    CTLineGetTypographicBounds(line, &ascent, nil, nil)
    ctx.textPosition = CGPoint(x: x, y: CGFloat(H) - top - ascent)
    CTLineDraw(line, ctx)
}

func drawGlow(_ ctx: CGContext, centerX: CGFloat, centerTop: CGFloat, radius: CGFloat, tint: UInt32, opacity: CGFloat) {
    let center = CGPoint(x: centerX, y: CGFloat(H) - centerTop)
    let colors = [color(tint, opacity), color(tint, 0)] as CFArray
    guard let gradient = CGGradient(colorsSpace: space, colors: colors, locations: [0, 1]) else { return }
    ctx.drawRadialGradient(gradient, startCenter: center, startRadius: 0,
                           endCenter: center, endRadius: radius, options: [])
}

func loadImage(_ path: String) -> CGImage {
    let url = URL(fileURLWithPath: path)
    guard let source = CGImageSourceCreateWithURL(url as CFURL, nil),
          let image = CGImageSourceCreateImageAtIndex(source, 0, nil) else {
        fatalError("Cannot load screenshot: \(path)")
    }
    return image
}

func savePNG(_ ctx: CGContext, to path: String) {
    guard let image = ctx.makeImage() else { fatalError("Cannot render image") }
    let url = URL(fileURLWithPath: path)
    guard let destination = CGImageDestinationCreateWithURL(url as CFURL, UTType.png.identifier as CFString, 1, nil) else {
        fatalError("Cannot write PNG: \(path)")
    }
    CGImageDestinationAddImage(destination, image, nil)
    guard CGImageDestinationFinalize(destination) else { fatalError("PNG export failed: \(path)") }
}

func makeSlide(_ slide: Slide, sourceDir: String, outputDir: String, index: Int) {
    guard let ctx = CGContext(data: nil, width: W, height: H, bitsPerComponent: 8, bytesPerRow: W * 4,
                              space: space, bitmapInfo: CGBitmapInfo.byteOrder32Big.rawValue | CGImageAlphaInfo.noneSkipLast.rawValue) else {
        fatalError("Cannot create drawing context")
    }
    ctx.setAllowsAntialiasing(true)
    ctx.setShouldAntialias(true)

    // Calm warm canvas, with a restrained DepUp blue glow behind the phone.
    let bg = CGGradient(colorsSpace: space,
                        colors: [color(0xF7F5F0), color(0xF0F3FA)] as CFArray,
                        locations: [0, 1])!
    ctx.drawLinearGradient(bg, start: CGPoint(x: 0, y: H), end: CGPoint(x: W, y: 0), options: [])
    drawGlow(ctx, centerX: 1030, centerTop: 120, radius: 360, tint: slide.accent, opacity: 0.14)
    drawGlow(ctx, centerX: 80, centerTop: 1650, radius: 420, tint: slide.accent, opacity: 0.10)
    drawGlow(ctx, centerX: 540, centerTop: 980, radius: 560, tint: slide.accent, opacity: 0.08)

    drawText(ctx, "DEPUPOFFICE  ·  MOBILE WORKSPACE", x: 70, top: 45, size: 15,
             fontName: "Funnel Display SemiBold", color: color(slide.accent), tracking: 1.4)

    let numberRect = cgRect(900, 37, 110, 40)
    ctx.addPath(roundedPath(numberRect, 20))
    ctx.setFillColor(color(slide.accent, 0.10)); ctx.fillPath()
    drawText(ctx, String(format: "%02d / 04", index), x: 922, top: 48, size: 13,
             fontName: "Funnel Display SemiBold", color: color(slide.accent), tracking: 0.5)

    for (line, text) in slide.headline.enumerated() {
        drawText(ctx, text, x: 70, top: CGFloat(91 + line * 68), size: 61,
                 fontName: "Funnel Display SemiBold", color: color(0x121722))
    }
    drawText(ctx, slide.subtitle, x: 72, top: 239, size: 22,
             fontName: "Funnel Display Regular", color: color(0x646A73))

    // Device body and soft shadow.
    let body = cgRect(170, 315, 740, 1590)
    ctx.saveGState()
    ctx.setShadow(offset: CGSize(width: 0, height: -18), blur: 34, color: color(0x101729, 0.28))
    ctx.addPath(roundedPath(body, 66)); ctx.setFillColor(color(0x141821)); ctx.fillPath()
    ctx.restoreGState()
    ctx.addPath(roundedPath(body, 66)); ctx.setStrokeColor(color(0xFFFFFF, 0.32)); ctx.setLineWidth(1.5); ctx.strokePath()

    // Small side keys make the phone frame read cleanly without covering the app UI.
    ctx.addPath(roundedPath(cgRect(162, 535, 8, 72), 4)); ctx.setFillColor(color(0x4A5260)); ctx.fillPath()
    ctx.addPath(roundedPath(cgRect(910, 585, 8, 104), 4)); ctx.setFillColor(color(0x4A5260)); ctx.fillPath()

    let screen = cgRect(194, 340, 692, 1538)
    ctx.saveGState()
    ctx.addPath(roundedPath(screen, 43)); ctx.clip()
    ctx.setFillColor(color(0xF2F0EB)); ctx.fill(screen)
    let app = loadImage((sourceDir as NSString).appendingPathComponent(slide.source))
    ctx.interpolationQuality = .high
    ctx.draw(app, in: screen)
    ctx.restoreGState()

    savePNG(ctx, to: (outputDir as NSString).appendingPathComponent(slide.output))
}

func makeContactSheet(_ inputs: [String], to output: String) {
    let width = 1120, height = 1920
    guard let ctx = CGContext(data: nil, width: width, height: height, bitsPerComponent: 8, bytesPerRow: width * 4,
                              space: space, bitmapInfo: CGBitmapInfo.byteOrder32Big.rawValue | CGImageAlphaInfo.noneSkipLast.rawValue) else { fatalError("Cannot create preview") }
    ctx.setFillColor(color(0xF3F2EE)); ctx.fill(CGRect(x: 0, y: 0, width: width, height: height))
    let positions: [(CGFloat, CGFloat)] = [(45, 35), (575, 35), (45, 980), (575, 980)]
    for (i, path) in inputs.enumerated() {
        let image = loadImage(path)
        let x = positions[i].0, top = positions[i].1
        let frame = cgRect(x, top, 500, 888, canvasHeight: CGFloat(height))
        ctx.saveGState()
        ctx.setShadow(offset: CGSize(width: 0, height: -8), blur: 20, color: color(0x101729, 0.20))
        ctx.addPath(roundedPath(frame, 34)); ctx.setFillColor(color(0x141821)); ctx.fillPath()
        ctx.restoreGState()
        let inner = cgRect(x + 17, top + 18, 466, 852, canvasHeight: CGFloat(height))
        ctx.saveGState(); ctx.addPath(roundedPath(inner, 24)); ctx.clip(); ctx.draw(image, in: inner); ctx.restoreGState()
    }
    savePNG(ctx, to: output)
}

let root = URL(fileURLWithPath: CommandLine.arguments.dropFirst().first ?? ".").standardizedFileURL.path
let sourceDir = (root as NSString).appendingPathComponent("play-store/listing/source")
let outputDir = (root as NSString).appendingPathComponent("play-store/listing/screenshots")
try! FileManager.default.createDirectory(atPath: outputDir, withIntermediateDirectories: true)

let fontURLs = ["funnel_display_semibold.ttf", "funnel_display_regular.ttf"].map {
    URL(fileURLWithPath: (root as NSString).appendingPathComponent("app/src/main/res/font/\($0)"))
}
for url in fontURLs { CTFontManagerRegisterFontsForURL(url as CFURL, .process, nil) }

let slides = [
    Slide(source: "workspace.png", output: "01-workspace.png",
          headline: ["Satu workspace.", "Semua dokumen."],
          subtitle: "PDF, Word, Excel, dan scan dalam satu aplikasi.", accent: 0x1955F5),
    Slide(source: "word.png", output: "02-word.png",
          headline: ["Tulis dokumen", "langsung di HP."],
          subtitle: "Buat dan format dokumen tanpa pindah aplikasi.", accent: 0x315F9C),
    Slide(source: "spreadsheet.png", output: "03-spreadsheet.png",
          headline: ["Rumus otomatis.", "Data lebih rapi."],
          subtitle: "Atur stok dan hitung total dengan rumus.", accent: 0x187444),
    Slide(source: "pdf-tools.png", output: "04-pdf-tools.png",
          headline: ["Atur PDF", "semudah itu."],
          subtitle: "Gabung, pisah, susun, dan kompres file PDF.", accent: 0xBD5137)
]
for (i, slide) in slides.enumerated() { makeSlide(slide, sourceDir: sourceDir, outputDir: outputDir, index: i + 1) }
let paths = slides.map { (outputDir as NSString).appendingPathComponent($0.output) }
makeContactSheet(paths, to: (root as NSString).appendingPathComponent("play-store/listing/preview.png"))
print("Created \(slides.count) Play listing mockups and preview in \(root)/play-store/listing")
