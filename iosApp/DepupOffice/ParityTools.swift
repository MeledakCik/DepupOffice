import CoreImage
import CoreImage.CIFilterBuiltins
import CoreText
import PDFKit
import SwiftUI
import UIKit
import Vision

// Alat-alat yang menyamakan iOS dengan menu Android. Semua diproses di perangkat (tanpa internet).

enum ParityEngine {
    static func run(tool: String, urls: [URL], options: [String: String]) throws -> [GeneratedDocument]? {
        switch tool {
        case "removeBlank": return [try removeBlankPages(urls[0])]
        case "compressTarget": return [try compress(urls[0], targetKB: Int(options["targetKB"] ?? "500") ?? 500)]
        case "extractImages": return try extractImages(urls[0])
        case "searchablePDF": return [try searchablePDF(urls[0])]
        case "exeToPdf": return [try exeToPdf(urls[0])]
        case "shadowRemove": return try urls.map { try removeShadow($0) }
        case "transparentSign": return try urls.map { try transparentSignature($0) }
        case "idPhoto": return try idPhoto(urls[0], size: options["size"] ?? "3x4", background: options["background"] ?? "red")
        case "businessCard": return [try businessCard(urls[0])]
        case "tableToExcel": return [try tableToExcel(urls[0])]
        case "receiptToExcel": return [try receiptToExcel(urls)]
        default: return nil
        }
    }

    static func base(_ url: URL) -> String { url.deletingPathExtension().lastPathComponent }

    // MARK: gambar & PDF bantu

    static func loadImage(_ url: URL) throws -> UIImage {
        guard let data = try? Data(contentsOf: url), let image = UIImage(data: data) else { throw OfficeError.invalidImage(url.lastPathComponent) }
        return normalized(image, maxSide: 3000)
    }

    /// Orientasi dibakukan ke .up dan sisi terpanjang dibatasi (hemat memori).
    static func normalized(_ image: UIImage, maxSide: CGFloat) -> UIImage {
        let scale = min(1, maxSide / max(image.size.width, image.size.height))
        let size = CGSize(width: (image.size.width * scale).rounded(), height: (image.size.height * scale).rounded())
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        return UIGraphicsImageRenderer(size: size, format: format).image { _ in image.draw(in: CGRect(origin: .zero, size: size)) }
    }

    static func rgba(_ cg: CGImage) -> (pixels: [UInt8], width: Int, height: Int)? {
        let w = cg.width, h = cg.height
        var pixels = [UInt8](repeating: 0, count: w * h * 4)
        let ok: Bool = pixels.withUnsafeMutableBytes { buf in
            guard let ctx = CGContext(data: buf.baseAddress, width: w, height: h, bitsPerComponent: 8, bytesPerRow: w * 4,
                                      space: CGColorSpaceCreateDeviceRGB(),
                                      bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue) else { return false }
            ctx.draw(cg, in: CGRect(x: 0, y: 0, width: w, height: h))
            return true
        }
        return ok ? (pixels, w, h) : nil
    }

    static func image(fromRGBA pixels: [UInt8], width: Int, height: Int) -> UIImage? {
        var copy = pixels
        return copy.withUnsafeMutableBytes { buf -> UIImage? in
            guard let ctx = CGContext(data: buf.baseAddress, width: width, height: height, bitsPerComponent: 8, bytesPerRow: width * 4,
                                      space: CGColorSpaceCreateDeviceRGB(),
                                      bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue),
                  let cg = ctx.makeImage() else { return nil }
            return UIImage(cgImage: cg)
        }
    }

    /// Halaman dirender sesuai tampilan (rotasi diterapkan PDFKit).
    static func render(_ page: PDFPage, scale: CGFloat) -> UIImage {
        let b = page.bounds(for: .cropBox)
        let rotated = page.rotation % 180 != 0
        let size = CGSize(width: (rotated ? b.height : b.width) * scale, height: (rotated ? b.width : b.height) * scale)
        return page.thumbnail(of: size, for: .cropBox)
    }

    static func displaySize(_ page: PDFPage) -> CGSize {
        let b = page.bounds(for: .cropBox)
        return page.rotation % 180 != 0 ? CGSize(width: b.height, height: b.width) : b.size
    }

    static func openPDF(_ url: URL) throws -> PDFDocument {
        guard let doc = PDFDocument(url: url) else { throw OfficeError.invalidPDF(url.lastPathComponent) }
        if doc.isLocked { throw ParityError.locked }
        return doc
    }

    // MARK: Hapus halaman kosong

    static func removeBlankPages(_ url: URL) throws -> GeneratedDocument {
        let doc = try openPDF(url)
        var removed = 0
        for index in stride(from: doc.pageCount - 1, through: 0, by: -1) {
            guard let page = doc.page(at: index), doc.pageCount > 1 else { continue }
            if isBlank(page) { doc.removePage(at: index); removed += 1 }
        }
        guard removed > 0 else { throw ParityError.message("Tidak ada halaman kosong yang ditemukan.") }
        guard let data = doc.dataRepresentation() else { throw OfficeError.cannotWrite }
        return GeneratedDocument(name: "\(base(url))_tanpa_kosong.pdf", data: data)
    }

    /// Kosong = hampir semua piksel putih (toleransi noda scan kecil), seperti deteksi Android.
    static func isBlank(_ page: PDFPage) -> Bool {
        if !(page.string ?? "").trimmingCharacters(in: .whitespacesAndNewlines).isEmpty { return false }
        guard let cg = render(page, scale: 0.35).cgImage, let px = rgba(cg) else { return false }
        var dark = 0
        let total = px.width * px.height
        var i = 0
        while i < px.pixels.count {
            let lum = (Int(px.pixels[i]) * 299 + Int(px.pixels[i + 1]) * 587 + Int(px.pixels[i + 2]) * 114) / 1000
            if lum < 200 { dark += 1 }
            i += 4
        }
        return total > 0 && Double(dark) / Double(total) < 0.004
    }

    // MARK: Kompres ke target ukuran

    static func compress(_ url: URL, targetKB: Int) throws -> GeneratedDocument {
        let doc = try openPDF(url)
        guard doc.pageCount > 0 else { throw OfficeError.invalidPDF(url.lastPathComponent) }
        let target = max(50, targetKB) * 1024
        if let original = try? Data(contentsOf: url), original.count <= target {
            throw ParityError.message("PDF sudah lebih kecil dari \(targetKB) KB.")
        }
        let ladder: [(scale: CGFloat, quality: CGFloat)] = [(2.0, 0.7), (1.6, 0.6), (1.3, 0.5), (1.1, 0.42), (0.9, 0.35), (0.75, 0.28), (0.6, 0.22), (0.5, 0.18)]
        var best: Data?
        for step in ladder {
            let data = rasterPDF(doc, scale: step.scale, quality: step.quality)
            best = data
            if data.count <= target { break }
        }
        guard let best else { throw OfficeError.cannotWrite }
        return GeneratedDocument(name: "\(base(url))_\(best.count / 1024)KB.pdf", data: best)
    }

    static func rasterPDF(_ doc: PDFDocument, scale: CGFloat, quality: CGFloat) -> Data {
        let first = doc.page(at: 0).map(displaySize) ?? CGSize(width: 595, height: 842)
        let renderer = UIGraphicsPDFRenderer(bounds: CGRect(origin: .zero, size: first))
        return renderer.pdfData { ctx in
            for index in 0..<doc.pageCount {
                autoreleasepool {
                    guard let page = doc.page(at: index) else { return }
                    let size = displaySize(page)
                    ctx.beginPage(withBounds: CGRect(origin: .zero, size: size), pageInfo: [:])
                    let image = render(page, scale: scale)
                    if let jpeg = image.jpegData(compressionQuality: quality), let small = UIImage(data: jpeg) {
                        small.draw(in: CGRect(origin: .zero, size: size))
                    }
                }
            }
        }
    }

    // MARK: Ambil gambar dari PDF

    static func extractImages(_ url: URL) throws -> [GeneratedDocument] {
        guard let provider = CGDataProvider(url: url as CFURL), let doc = CGPDFDocument(provider) else {
            throw OfficeError.invalidPDF(url.lastPathComponent)
        }
        if doc.isEncrypted && !doc.unlockWithPassword("") { throw ParityError.locked }
        var results: [GeneratedDocument] = []
        var seen = Set<Int>()
        let name = base(url)
        for pageIndex in 1...max(doc.numberOfPages, 1) {
            guard let page = doc.page(at: pageIndex), let dict = page.dictionary else { continue }
            var resources: CGPDFDictionaryRef?
            guard CGPDFDictionaryGetDictionary(dict, "Resources", &resources), let resources else { continue }
            collectImages(resources, depth: 0, page: pageIndex, prefix: name, seen: &seen, into: &results)
            if results.count >= 500 { break }
        }
        guard !results.isEmpty else { throw ParityError.message("Tidak ada gambar di dalam PDF ini.") }
        return results
    }

    private static func collectImages(_ resources: CGPDFDictionaryRef, depth: Int, page: Int, prefix: String,
                                      seen: inout Set<Int>, into results: inout [GeneratedDocument]) {
        guard depth < 4 else { return }
        var xobjects: CGPDFDictionaryRef?
        guard CGPDFDictionaryGetDictionary(resources, "XObject", &xobjects), let xobjects else { return }
        var keys: [String] = []
        CGPDFDictionaryApplyBlock(xobjects, { key, _, _ in
            keys.append(String(cString: key))
            return true
        }, nil)
        for key in keys {
            var stream: CGPDFStreamRef?
            guard CGPDFDictionaryGetStream(xobjects, key, &stream), let stream,
                  let sdict = CGPDFStreamGetDictionary(stream) else { continue }
            let identity = unsafeBitCast(stream, to: Int.self)   // alamat objek; berlaku untuk SDK lama (pointer) & baru (kelas)
            guard !seen.contains(identity) else { continue }
            seen.insert(identity)
            var subtype: UnsafePointer<CChar>?
            guard CGPDFDictionaryGetName(sdict, "Subtype", &subtype), let subtype else { continue }
            let kind = String(cString: subtype)
            if kind == "Form" {
                var inner: CGPDFDictionaryRef?
                if CGPDFDictionaryGetDictionary(sdict, "Resources", &inner), let inner {
                    collectImages(inner, depth: depth + 1, page: page, prefix: prefix, seen: &seen, into: &results)
                }
                continue
            }
            guard kind == "Image" else { continue }
            var format = CGPDFDataFormat.raw
            guard let cf = CGPDFStreamCopyData(stream, &format) else { continue }
            let data = cf as Data
            let number = results.count + 1
            switch format {
            case .jpegEncoded:
                results.append(GeneratedDocument(name: "\(prefix)_hal\(page)_gambar\(number).jpg", data: data))
            case .JPEG2000:
                results.append(GeneratedDocument(name: "\(prefix)_hal\(page)_gambar\(number).jp2", data: data))
            default:
                if let png = rawImagePNG(data, dict: sdict) {
                    results.append(GeneratedDocument(name: "\(prefix)_hal\(page)_gambar\(number).png", data: png))
                }
            }
        }
    }

    /// Gambar mentah 8-bit RGB / Gray / CMYK → PNG. Format lain (indexed, 1-bit) dilewati.
    private static func rawImagePNG(_ data: Data, dict: CGPDFDictionaryRef) -> Data? {
        var w: CGPDFInteger = 0, h: CGPDFInteger = 0, bpc: CGPDFInteger = 8
        guard CGPDFDictionaryGetInteger(dict, "Width", &w), CGPDFDictionaryGetInteger(dict, "Height", &h), w > 0, h > 0 else { return nil }
        _ = CGPDFDictionaryGetInteger(dict, "BitsPerComponent", &bpc)
        guard bpc == 8 else { return nil }
        var csName: UnsafePointer<CChar>?
        var components = 0
        var space: CGColorSpace?
        if CGPDFDictionaryGetName(dict, "ColorSpace", &csName), let csName {
            switch String(cString: csName) {
            case "DeviceRGB": components = 3; space = CGColorSpaceCreateDeviceRGB()
            case "DeviceGray": components = 1; space = CGColorSpaceCreateDeviceGray()
            case "DeviceCMYK": components = 4; space = CGColorSpaceCreateDeviceCMYK()
            default: return nil
            }
        } else {
            var arr: CGPDFArrayRef?
            guard CGPDFDictionaryGetArray(dict, "ColorSpace", &arr), let arr else { return nil }
            var first: UnsafePointer<CChar>?
            guard CGPDFArrayGetName(arr, 0, &first), let first, String(cString: first) == "ICCBased" else { return nil }
            var icc: CGPDFStreamRef?
            var n: CGPDFInteger = 3
            if CGPDFArrayGetStream(arr, 1, &icc), let icc, let d = CGPDFStreamGetDictionary(icc) { _ = CGPDFDictionaryGetInteger(d, "N", &n) }
            components = Int(n)
            space = components == 1 ? CGColorSpaceCreateDeviceGray() : components == 4 ? CGColorSpaceCreateDeviceCMYK() : CGColorSpaceCreateDeviceRGB()
        }
        let width = Int(w), height = Int(h)
        guard let space, data.count >= width * height * components,
              let provider = CGDataProvider(data: data as CFData),
              let cg = CGImage(width: width, height: height, bitsPerComponent: 8, bitsPerPixel: 8 * components,
                               bytesPerRow: width * components, space: space, bitmapInfo: CGBitmapInfo(rawValue: 0),
                               provider: provider, decode: nil, shouldInterpolate: false, intent: .defaultIntent) else { return nil }
        return UIImage(cgImage: cg).pngData()
    }

    // MARK: PDF bisa dicari (OCR tertanam)

    static func recognize(_ cg: CGImage, accurate: Bool = true) throws -> [VNRecognizedTextObservation] {
        let request = VNRecognizeTextRequest()
        request.recognitionLevel = accurate ? .accurate : .fast
        request.usesLanguageCorrection = true
        request.recognitionLanguages = ["id-ID", "en-US"]
        try VNImageRequestHandler(cgImage: cg, options: [:]).perform([request])
        return request.results ?? []
    }

    static func searchablePDF(_ url: URL) throws -> GeneratedDocument {
        let doc = try openPDF(url)
        guard doc.pageCount > 0 else { throw OfficeError.invalidPDF(url.lastPathComponent) }
        let first = doc.page(at: 0).map(displaySize) ?? CGSize(width: 595, height: 842)
        var recognizedAny = false
        var failure: Error?
        let data = UIGraphicsPDFRenderer(bounds: CGRect(origin: .zero, size: first)).pdfData { ctx in
            for index in 0..<doc.pageCount {
                autoreleasepool {
                    guard failure == nil, let page = doc.page(at: index) else { return }
                    let size = displaySize(page)
                    ctx.beginPage(withBounds: CGRect(origin: .zero, size: size), pageInfo: [:])
                    let image = render(page, scale: 2.5)
                    let jpeg = image.jpegData(compressionQuality: 0.8).flatMap(UIImage.init(data:)) ?? image
                    jpeg.draw(in: CGRect(origin: .zero, size: size))
                    guard let cg = image.cgImage else { return }
                    do {
                        let lines = try recognize(cg)
                        if !lines.isEmpty { recognizedAny = true }
                        for line in lines {
                            guard let text = line.topCandidates(1).first?.string, !text.isEmpty else { continue }
                            let bb = line.boundingBox
                            let rect = CGRect(x: bb.minX * size.width, y: (1 - bb.maxY) * size.height,
                                              width: bb.width * size.width, height: bb.height * size.height)
                            drawInvisible(AiText.clean(text), in: rect, context: ctx.cgContext)
                        }
                    } catch { failure = error }
                }
            }
        }
        if let failure { throw failure }
        guard recognizedAny else { throw ParityError.message("Tidak ada teks yang dikenali di PDF ini.") }
        return GeneratedDocument(name: "\(base(url))_bisa_dicari.pdf", data: data)
    }

    /// Teks tak terlihat (mode invisible) selebar kotak OCR, agar bisa dicari & disalin di atas gambar halaman.
    private static func drawInvisible(_ text: String, in rect: CGRect, context: CGContext) {
        let fontSize = max(4, rect.height * 0.85)
        let font = CTFontCreateWithName("Helvetica" as CFString, fontSize, nil)
        let attributed = NSAttributedString(string: text, attributes: [.font: font])
        let line = CTLineCreateWithAttributedString(attributed)
        let width = CGFloat(CTLineGetTypographicBounds(line, nil, nil, nil))
        guard width > 0 else { return }
        context.saveGState()
        context.translateBy(x: rect.minX, y: rect.maxY - rect.height * 0.18)
        context.scaleBy(x: rect.width / width, y: -1)
        context.textMatrix = .identity
        context.setTextDrawingMode(.invisible)
        context.textPosition = .zero
        CTLineDraw(line, context)
        context.restoreGState()
    }

    // MARK: EXE ke PDF (port ExeToPdf.kt)

    static func exeToPdf(_ url: URL) throws -> GeneratedDocument {
        let bytes = [UInt8](try Data(contentsOf: url))
        let pdf = try ExeExtractor.extract(bytes)
        return GeneratedDocument(name: "\(base(url)).pdf", data: Data(pdf))
    }

    // MARK: Pembersih bayangan

    static func removeShadow(_ url: URL) throws -> GeneratedDocument {
        let image = try loadImage(url)
        guard let cg = image.cgImage else { throw OfficeError.invalidImage(url.lastPathComponent) }
        let ci = CIImage(cgImage: cg)
        let radius = Double(max(cg.width, cg.height)) / 40
        let blur = CIFilter.gaussianBlur()
        blur.inputImage = ci.clampedToExtent()
        blur.radius = Float(radius)
        let context = CIContext()
        guard let blurred = blur.outputImage?.cropped(to: ci.extent), let bgCG = context.createCGImage(blurred, from: ci.extent),
              let src = rgba(cg), let bg = rgba(bgCG) else { throw OfficeError.cannotWrite }
        var out = src.pixels
        var i = 0
        while i < out.count {
            for c in 0..<3 {
                let s = Double(src.pixels[i + c]), b = max(1, Double(bg.pixels[i + c]))
                // bagi dengan latar (iluminasi) → kertas putih rata; sedikit kontras agar tinta tetap pekat
                var v = s / b * 255
                v = (v - 255) * 1.25 + 255
                out[i + c] = UInt8(max(0, min(255, v)))
            }
            out[i + 3] = 255
            i += 4
        }
        guard let result = Self.image(fromRGBA: out, width: src.width, height: src.height),
              let jpeg = result.jpegData(compressionQuality: 0.92) else { throw OfficeError.cannotWrite }
        return GeneratedDocument(name: "\(base(url))_bersih.jpg", data: jpeg)
    }

    // MARK: TTD transparan

    static func transparentSignature(_ url: URL) throws -> GeneratedDocument {
        let image = try loadImage(url)
        guard let cg = image.cgImage, let src = rgba(cg) else { throw OfficeError.invalidImage(url.lastPathComponent) }
        var out = [UInt8](repeating: 0, count: src.pixels.count)
        var minX = src.width, minY = src.height, maxX = -1, maxY = -1
        for y in 0..<src.height {
            for x in 0..<src.width {
                let i = (y * src.width + x) * 4
                let r = Int(src.pixels[i]), g = Int(src.pixels[i + 1]), b = Int(src.pixels[i + 2])
                let lum = (r * 299 + g * 587 + b * 114) / 1000
                // tinta gelap → buram, kertas terang → transparan (tepi lembut di antara keduanya)
                let alpha = max(0, min(255, (185 - lum) * 255 / 75))
                guard alpha > 8 else { continue }
                let darken = 0.55
                out[i] = UInt8(Double(r) * darken * Double(alpha) / 255)
                out[i + 1] = UInt8(Double(g) * darken * Double(alpha) / 255)
                out[i + 2] = UInt8(Double(b) * darken * Double(alpha) / 255)
                out[i + 3] = UInt8(alpha)
                minX = min(minX, x); maxX = max(maxX, x); minY = min(minY, y); maxY = max(maxY, y)
            }
        }
        guard maxX > minX, maxY > minY, let full = Self.image(fromRGBA: out, width: src.width, height: src.height)?.cgImage else {
            throw ParityError.message("Tanda tangan tidak terdeteksi. Gunakan foto tinta gelap di atas kertas putih.")
        }
        let pad = 12
        let crop = CGRect(x: max(0, minX - pad), y: max(0, minY - pad),
                          width: min(src.width, maxX + pad) - max(0, minX - pad), height: min(src.height, maxY + pad) - max(0, minY - pad))
        guard let cropped = full.cropping(to: crop), let png = UIImage(cgImage: cropped).pngData() else { throw OfficeError.cannotWrite }
        return GeneratedDocument(name: "\(base(url))_ttd.png", data: png)
    }

    // MARK: Pasfoto (2x3, 3x4, 4x6; latar merah/biru/putih; lembar A4)

    static func idPhoto(_ url: URL, size: String, background: String) throws -> [GeneratedDocument] {
        let image = try loadImage(url)
        guard let cg = image.cgImage else { throw OfficeError.invalidImage(url.lastPathComponent) }
        let (wcm, hcm): (CGFloat, CGFloat) = size == "2x3" ? (2, 3) : size == "4x6" ? (4, 6) : (3, 4)
        let color: UIColor = background == "blue" ? UIColor(red: 0.098, green: 0.463, blue: 0.824, alpha: 1)
            : background == "white" ? .white : UIColor(red: 0.827, green: 0.184, blue: 0.184, alpha: 1)

        // 1) pisahkan orang dari latar
        let seg = VNGeneratePersonSegmentationRequest()
        seg.qualityLevel = .accurate
        seg.outputPixelFormat = kCVPixelFormatType_OneComponent8
        let handler = VNImageRequestHandler(cgImage: cg, options: [:])
        let faces = VNDetectFaceRectanglesRequest()
        try handler.perform([seg, faces])
        guard let maskBuffer = seg.results?.first?.pixelBuffer else { throw ParityError.message("Orang tidak terdeteksi di foto.") }
        let ci = CIImage(cgImage: cg)
        var mask = CIImage(cvPixelBuffer: maskBuffer)
        mask = mask.transformed(by: CGAffineTransform(scaleX: ci.extent.width / mask.extent.width, y: ci.extent.height / mask.extent.height))
        let blend = CIFilter.blendWithMask()
        blend.inputImage = ci
        blend.backgroundImage = CIImage(color: CIColor(color: color)).cropped(to: ci.extent)
        blend.maskImage = mask
        let context = CIContext()
        guard let composed = blend.outputImage, let composedCG = context.createCGImage(composed, from: ci.extent) else { throw OfficeError.cannotWrite }

        // 2) potong mengikuti wajah: wajah ±45% tinggi foto, mata di sepertiga atas
        let W = CGFloat(cg.width), H = CGFloat(cg.height)
        let ratio = wcm / hcm
        var crop: CGRect
        if let face = (faces.results ?? []).max(by: { $0.boundingBox.height < $1.boundingBox.height })?.boundingBox {
            let f = CGRect(x: face.minX * W, y: (1 - face.maxY) * H, width: face.width * W, height: face.height * H)
            var ch = f.height / 0.45
            var cw = ch * ratio
            if cw > W { cw = W; ch = cw / ratio }
            if ch > H { ch = H; cw = ch * ratio }
            let cx = f.midX
            let top = f.minY - ch * 0.20
            crop = CGRect(x: cx - cw / 2, y: top, width: cw, height: ch)
        } else {
            let cw = min(W, H * ratio), ch = cw / ratio
            crop = CGRect(x: (W - cw) / 2, y: 0, width: cw, height: ch)
        }
        crop.origin.x = max(0, min(W - crop.width, crop.origin.x))
        crop.origin.y = max(0, min(H - crop.height, crop.origin.y))
        guard let cropped = composedCG.cropping(to: crop.integral) else { throw OfficeError.cannotWrite }

        // 3) 300 dpi
        let px = CGSize(width: (wcm / 2.54 * 300).rounded(), height: (hcm / 2.54 * 300).rounded())
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        let photo = UIGraphicsImageRenderer(size: px, format: format).image { _ in
            UIImage(cgImage: cropped).draw(in: CGRect(origin: .zero, size: px))
        }
        guard let jpeg = photo.jpegData(compressionQuality: 0.95) else { throw OfficeError.cannotWrite }

        // 4) lembar A4 (pt = cm / 2.54 * 72), jarak 0.3 cm, garis potong tipis
        let a4 = CGRect(x: 0, y: 0, width: 595.28, height: 841.89)
        let pw = wcm / 2.54 * 72, ph = hcm / 2.54 * 72, gap: CGFloat = 0.3 / 2.54 * 72, margin: CGFloat = 28
        let cols = Int((a4.width - 2 * margin + gap) / (pw + gap)), rows = Int((a4.height - 2 * margin + gap) / (ph + gap))
        let sheet = UIGraphicsPDFRenderer(bounds: a4).pdfData { ctx in
            ctx.beginPage()
            UIColor.lightGray.setStroke()
            for r in 0..<rows {
                for c in 0..<cols {
                    let rect = CGRect(x: margin + CGFloat(c) * (pw + gap), y: margin + CGFloat(r) * (ph + gap), width: pw, height: ph)
                    photo.draw(in: rect)
                    UIBezierPath(rect: rect).stroke()
                }
            }
        }
        let name = base(url)
        return [GeneratedDocument(name: "\(name)_pasfoto_\(size).jpg", data: jpeg),
                GeneratedDocument(name: "\(name)_pasfoto_\(size)_A4.pdf", data: sheet)]
    }

    // MARK: Kartu nama → VCF

    static func businessCard(_ url: URL) throws -> GeneratedDocument {
        let image = try loadImage(url)
        guard let cg = image.cgImage else { throw OfficeError.invalidImage(url.lastPathComponent) }
        let lines = try recognize(cg)
        let texts = lines.compactMap { obs -> (String, CGFloat)? in
            guard let t = obs.topCandidates(1).first?.string else { return nil }
            return (AiText.clean(t).trimmingCharacters(in: .whitespaces), obs.boundingBox.height)
        }.filter { !$0.0.isEmpty }
        guard !texts.isEmpty else { throw ParityError.message("Teks kartu nama tidak terbaca.") }
        let all = texts.map(\.0).joined(separator: "\n")
        var phones: [String] = [], emails: [String] = [], urls: [String] = [], addresses: [String] = []
        let types: NSTextCheckingResult.CheckingType = [.phoneNumber, .link, .address]
        if let detector = try? NSDataDetector(types: types.rawValue) {
            for m in detector.matches(in: all, range: NSRange(all.startIndex..., in: all)) {
                guard let r = Range(m.range, in: all) else { continue }
                let s = String(all[r])
                switch m.resultType {
                case .phoneNumber: phones.append(m.phoneNumber ?? s)
                case .link:
                    if m.url?.scheme == "mailto" || s.contains("@") { emails.append(s.replacingOccurrences(of: "mailto:", with: "")) } else { urls.append(s) }
                case .address: addresses.append(s.replacingOccurrences(of: "\n", with: ", "))
                default: break
                }
            }
        }
        let used = Set(phones + emails + urls)
        let candidates = texts.filter { t in !used.contains(where: { t.0.contains($0) }) && t.0.rangeOfCharacter(from: .letters) != nil }
        let name = candidates.max(by: { $0.1 < $1.1 })?.0 ?? texts[0].0
        let others = candidates.map(\.0).filter { $0 != name }
        let company = others.first(where: { $0.uppercased().contains("PT") || $0.uppercased().contains("CV") || $0.uppercased().contains("TOKO") }) ?? others.first
        func esc(_ s: String) -> String {
            s.replacingOccurrences(of: "\\", with: "\\\\").replacingOccurrences(of: ",", with: "\\,").replacingOccurrences(of: ";", with: "\\;")
        }
        var v = "BEGIN:VCARD\r\nVERSION:3.0\r\nFN:\(esc(name))\r\nN:\(esc(name));;;;\r\n"
        if let company { v += "ORG:\(esc(company))\r\n" }
        for p in phones { v += "TEL;TYPE=CELL:\(esc(p))\r\n" }
        for e in emails { v += "EMAIL;TYPE=INTERNET:\(esc(e))\r\n" }
        for u in urls { v += "URL:\(esc(u))\r\n" }
        for a in addresses { v += "ADR;TYPE=WORK:;;\(esc(a));;;;\r\n" }
        v += "NOTE:\(esc(all.replacingOccurrences(of: "\n", with: " | ")))\r\nEND:VCARD\r\n"
        let file = name.components(separatedBy: CharacterSet.alphanumerics.inverted).filter { !$0.isEmpty }.joined(separator: "_")
        return GeneratedDocument(name: "Kontak_\(file.isEmpty ? base(url) : String(file.prefix(40))).vcf", data: Data(v.utf8))
    }

    // MARK: Tabel / struk → Excel

    struct OCRWord { let text: String; let rect: CGRect }

    static func words(_ url: URL) throws -> [OCRWord] {
        let image = try loadImage(url)
        guard let cg = image.cgImage else { throw OfficeError.invalidImage(url.lastPathComponent) }
        let W = CGFloat(cg.width), H = CGFloat(cg.height)
        return try recognize(cg).compactMap { obs in
            guard let t = obs.topCandidates(1).first?.string else { return nil }
            let bb = obs.boundingBox
            return OCRWord(text: AiText.clean(t), rect: CGRect(x: bb.minX * W, y: (1 - bb.maxY) * H, width: bb.width * W, height: bb.height * H))
        }
    }

    /// Kelompokkan teks menjadi baris (pusat vertikal berdekatan), urut kiri → kanan.
    static func rows(_ words: [OCRWord]) -> [[OCRWord]] {
        let sorted = words.sorted { $0.rect.midY < $1.rect.midY }
        let heights = words.map(\.rect.height).sorted()
        let tolerance = (heights.isEmpty ? 10 : heights[heights.count / 2]) * 0.6
        var rows: [[OCRWord]] = []
        for w in sorted {
            if let last = rows.last, let anchor = last.first, abs(anchor.rect.midY - w.rect.midY) <= tolerance {
                rows[rows.count - 1].append(w)
            } else {
                rows.append([w])
            }
        }
        return rows.map { $0.sorted { $0.rect.minX < $1.rect.minX } }
    }

    static func number(_ s: String) -> Double? {
        let digits = s.replacingOccurrences(of: "Rp", with: "", options: .caseInsensitive)
            .replacingOccurrences(of: " ", with: "")
        guard digits.range(of: "^-?[0-9][0-9.,]*$", options: .regularExpression) != nil else { return nil }
        // Format Indonesia: titik = ribuan, koma = desimal
        var t = digits
        if t.contains(",") && t.contains(".") { t = t.replacingOccurrences(of: ".", with: "").replacingOccurrences(of: ",", with: ".") }
        else if t.contains(".") {
            let parts = t.split(separator: ".")
            if parts.count > 1 && parts.dropFirst().allSatisfy({ $0.count == 3 }) { t = t.replacingOccurrences(of: ".", with: "") }
        } else if t.contains(",") {
            let parts = t.split(separator: ",")
            t = parts.count == 2 && parts[1].count != 3 ? t.replacingOccurrences(of: ",", with: ".") : t.replacingOccurrences(of: ",", with: "")
        }
        return Double(t)
    }

    static func tableToExcel(_ url: URL) throws -> GeneratedDocument {
        let found = try words(url)
        guard !found.isEmpty else { throw ParityError.message("Tabel tidak terbaca di foto ini.") }
        let lines = rows(found)
        // batas kolom dari posisi kiri semua sel (dikelompokkan bila selisih < tinggi huruf)
        let heights = found.map(\.rect.height).sorted()
        let gap = heights[heights.count / 2] * 1.2
        var starts: [CGFloat] = []
        for x in found.map(\.rect.minX).sorted() {
            if let last = starts.last, x - last < gap { continue }
            starts.append(x)
        }
        let table: [[XlsxCell]] = lines.map { line in
            var cells = [XlsxCell](repeating: .empty, count: starts.count)
            for w in line {
                let col = (starts.lastIndex(where: { $0 <= w.rect.minX + gap / 2 }) ?? 0)
                if case .text(let prev) = cells[col] { cells[col] = .text(prev + " " + w.text); continue }
                if let n = number(w.text) { cells[col] = .number(n) } else { cells[col] = .text(w.text) }
            }
            return cells
        }
        let data = Xlsx.write(sheets: [(name: "Tabel", rows: table, boldRows: [0])])
        return GeneratedDocument(name: "\(base(url))_tabel.xlsx", data: data)
    }

    static func receiptToExcel(_ urls: [URL]) throws -> GeneratedDocument {
        var rowsOut: [[XlsxCell]] = [[.text("Struk"), .text("Barang"), .text("Harga")]]
        var detected = 0
        for url in urls {
            for line in rows(try words(url)) {
                guard let last = line.last, let amount = number(last.text), amount >= 100 else { continue }
                let label = line.dropLast().map(\.text).joined(separator: " ").trimmingCharacters(in: .whitespaces)
                let upper = label.uppercased()
                if upper.contains("TOTAL") || upper.contains("TUNAI") || upper.contains("KEMBALI") || upper.contains("CASH") || upper.contains("PPN") { continue }
                guard !label.isEmpty else { continue }
                rowsOut.append([.text(base(url)), .text(label), .number(amount)])
                detected += 1
            }
        }
        guard detected > 0 else { throw ParityError.message("Tidak ada baris harga yang terbaca di struk.") }
        rowsOut.append([.empty, .text("TOTAL"), .formula("SUM(C2:C\(rowsOut.count))")])
        let data = Xlsx.write(sheets: [(name: "Belanja", rows: rowsOut, boldRows: [0, rowsOut.count - 1])])
        return GeneratedDocument(name: "Struk_\(urls.count == 1 ? base(urls[0]) : "gabungan").xlsx", data: data)
    }
}

enum ParityError: LocalizedError {
    case locked, message(String)
    var errorDescription: String? {
        switch self {
        case .locked: "PDF terkunci kata sandi. Buka kuncinya dulu."
        case .message(let m): m
        }
    }
}

// MARK: - EXE → PDF (port 1:1 dari ExeToPdf.kt)

enum ExeExtractor {
    private static let K = 12
    private static let vaysoft: [UInt8] = [0x00, 0x0B, 0x0C, 0x04, 0x0D, 0x03, 0x04, 0x0B, 0x0C, 0x03, 0x06, 0x02]
    private static let magic = Array("%PDF-".utf8)
    private static let known = [10, 16, 18, 19]
    private static let plains: [[Int]] = [[0x20, 0x20, 0x0D, 0x0A], [0x20, 0x20, 0x20, 0x0A]]

    private static func mod(_ a: Int, _ n: Int) -> Int { ((a % n) + n) % n }
    private static func u16(_ d: [UInt8], _ o: Int) -> Int { Int(d[o]) | Int(d[o + 1]) << 8 }
    private static func u32(_ d: [UInt8], _ o: Int) -> Int { Int(d[o]) | Int(d[o + 1]) << 8 | Int(d[o + 2]) << 16 | Int(d[o + 3]) << 24 }

    static func overlayStart(_ d: [UInt8]) -> Int {
        guard d.count >= 0x40, d[0] == 0x4D, d[1] == 0x5A else { return 0 }
        let pe = u32(d, 0x3C)
        guard pe >= 0, pe + 24 <= d.count, d[pe] == 0x50, d[pe + 1] == 0x45, d[pe + 2] == 0, d[pe + 3] == 0 else { return 0 }
        let nSec = u16(d, pe + 6)
        let opt = u16(d, pe + 20)
        let tab = pe + 24 + opt
        var end = 0
        for i in 0..<nSec {
            let s = tab + i * 40
            if s + 40 > d.count { break }
            let size = u32(d, s + 16), raw = u32(d, s + 20)
            end = max(end, raw + size)
        }
        return min(max(end, 0), d.count)
    }

    static func extract(_ data: [UInt8]) throws -> [UInt8] {
        let start = overlayStart(data)
        let ov = (start > 0 && start < data.count) ? Array(data[start...]) : data
        if let at = indexOf(ov, magic, 0), let p = trim(Array(ov[at...])) { return p }
        let vs = (0..<ov.count).map { ov[$0] ^ vaysoft[mod($0, K)] }
        if startsWith(vs, magic), let p = trim(vs) { return p }
        if let p = recover(ov) { return p }
        throw ParityError.message("PDF tidak ditemukan di berkas ini. Mungkin bukan hasil \"PDF to EXE\", atau isinya dikunci kata sandi.")
    }

    private static func recover(_ d: [UInt8]) -> [UInt8]? {
        let n = d.count, stride = 60, minRows = 8
        var o = 0, tried = 0
        while o < n - minRows * stride - 20 && tried < 5000 {
            var ok = true
            outer: for p in known {
                let ref = d[o + p]
                for k in 1..<minRows where d[o + p + k * stride] != ref { ok = false; break outer }
            }
            if ok {
                tried += 1
                for pl in plains { if let r = tryTable(d, o, pl) { return r } }
            }
            o += 1
        }
        return nil
    }

    private static func tryTable(_ d: [UInt8], _ o: Int, _ plain: [Int]) -> [UInt8]? {
        var key = [Int?](repeating: nil, count: K)
        var rows = 0
        while o + rows * 20 + 20 <= d.count && rows < 60 {
            let base = o + rows * 20
            var good = true
            for i in known.indices {
                let ki = mod(base + known[i], K)
                let kb = Int(d[base + known[i]]) ^ plain[i]
                if let prev = key[ki], prev != kb { good = false; break }
                key[ki] = kb
            }
            if !good { if rows < 3 { return nil }; break }
            rows += 1
        }
        for r in 0..<min(rows, 6) {
            let base = o + r * 20
            for pos in 0..<18 {
                guard let k = key[mod(base + pos, K)] else { continue }
                let c = Int(d[base + pos]) ^ k
                let fine: Bool
                switch pos {
                case 10, 16: fine = c == 0x20
                case 17: fine = c == 0x6E || c == 0x66
                default: fine = (0x30...0x39).contains(c)
                }
                if !fine { return nil }
            }
        }
        fillFrom(d, o, &key, Array("xref".utf8))
        guard let pdfAt = findPdfStart(d, key) else { return nil }
        let head = Array("%PDF-1.".utf8)
        for j in head.indices {
            let ki = mod(pdfAt + j, K)
            if key[ki] == nil { key[ki] = Int(d[pdfAt + j]) ^ Int(head[j]) }
        }
        let missing = (0..<K).filter { key[$0] == nil }
        if missing.count > 2 { return nil }
        let total = 1 << (8 * missing.count)
        // Kandidat kunci wajib membuat baris xref valid penuh; dari yang lolos, pilih struktur PDF terbanyak.
        var best: [UInt8]?
        var bestScore = -1
        for combo in 0..<total {
            var kb = (0..<K).map { UInt8(truncatingIfNeeded: key[$0] ?? 0) }
            for (idx, ki) in missing.enumerated() { kb[ki] = UInt8(truncatingIfNeeded: combo >> (8 * idx)) }
            if !rowsValid(d, o, rows, kb) { continue }
            let headLen = min(65536, d.count - pdfAt)
            let h = (0..<headLen).map { d[pdfAt + $0] ^ kb[mod(pdfAt + $0, K)] }
            if !startsWith(h, magic) || indexOf(Array(h.prefix(2048)), Array("obj".utf8), 0) == nil { continue }
            let score = count(h, Array(" obj".utf8)) + 2 * count(h, Array("endobj".utf8))
            if score > bestScore { bestScore = score; best = kb }
        }
        guard let kb = best else { return nil }
        return trim((pdfAt..<d.count).map { d[$0] ^ kb[mod($0, K)] })
    }

    private static func rowsValid(_ d: [UInt8], _ o: Int, _ rows: Int, _ kb: [UInt8]) -> Bool {
        for r in 0..<min(rows, 6) {
            let base = o + r * 20
            for pos in 0..<20 {
                let c = Int(d[base + pos] ^ kb[mod(base + pos, K)])
                let fine: Bool
                switch pos {
                case 10, 16: fine = c == 0x20
                case 17: fine = c == 0x6E || c == 0x66
                case 18: fine = c == 0x0D || c == 0x20
                case 19: fine = c == 0x0A
                default: fine = (0x30...0x39).contains(c)
                }
                if !fine { return false }
            }
        }
        return true
    }

    private static func count(_ a: [UInt8], _ p: [UInt8]) -> Int {
        var n = 0
        var i = indexOf(a, p, 0)
        while let at = i { n += 1; i = indexOf(a, p, at + p.count) }
        return n
    }

    private static func fillFrom(_ d: [UInt8], _ o: Int, _ key: inout [Int?], _ word: [UInt8]) {
        for back in 5...40 {
            let s = o - back
            if s < 0 { break }
            var consistent = true
            var add: [Int: Int] = [:]
            for j in word.indices {
                let ki = mod(s + j, K)
                let kb = Int(d[s + j]) ^ Int(word[j])
                if let have = key[ki] ?? add[ki], have != kb { consistent = false; break }
                add[ki] = kb
            }
            if consistent && add.keys.contains(where: { key[$0] == nil }) && add.keys.filter({ key[$0] != nil }).count >= 2 {
                for (k, v) in add { key[k] = v }
                return
            }
        }
    }

    private static func findPdfStart(_ d: [UInt8], _ key: [Int?]) -> Int? {
        guard d.count > 8 else { return nil }
        for i in 0..<(d.count - 8) {
            var ok = true, knownCount = 0
            for j in magic.indices {
                guard let k = key[mod(i + j, K)] else { continue }
                if (Int(d[i + j]) ^ k) != Int(magic[j]) { ok = false; break }
                knownCount += 1
            }
            if ok && knownCount >= 3 { return i }
        }
        return nil
    }

    private static func trim(_ p: [UInt8]) -> [UInt8]? {
        guard let eof = lastIndexOf(p, Array("%%EOF".utf8)), eof >= 64 else { return nil }
        var end = eof + 5
        while end < p.count && (p[end] == 0x0A || p[end] == 0x0D) { end += 1 }
        return Array(p[0..<end])
    }

    private static func startsWith(_ a: [UInt8], _ p: [UInt8]) -> Bool { a.count >= p.count && Array(a[0..<p.count]) == p }

    private static func indexOf(_ a: [UInt8], _ p: [UInt8], _ from: Int) -> Int? {
        guard a.count >= p.count else { return nil }
        var i = from
        while i <= a.count - p.count {
            var j = 0
            while j < p.count && a[i + j] == p[j] { j += 1 }
            if j == p.count { return i }
            i += 1
        }
        return nil
    }

    private static func lastIndexOf(_ a: [UInt8], _ p: [UInt8]) -> Int? {
        guard a.count >= p.count else { return nil }
        var i = a.count - p.count
        while i >= 0 {
            var j = 0
            while j < p.count && a[i + j] == p[j] { j += 1 }
            if j == p.count { return i }
            i -= 1
        }
        return nil
    }
}

// MARK: - Label harga & barcode (Code 128, lembar A4)

struct PriceTagSheet: View {
    let onSave: ([GeneratedDocument]) -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var product = "Kopi Susu Gula Aren"
    @State private var code = String(format: "899%010d", Int.random(in: 0..<1_000_000_000))
    @State private var price = "Rp 18.000"
    @State private var failure: String?

    var body: some View {
        NavigationStack {
            Form {
                Section("Produk") {
                    TextField("Nama produk", text: $product)
                    TextField("Kode barcode / SKU", text: $code).keyboardType(.asciiCapable).autocorrectionDisabled()
                    TextField("Harga", text: $price)
                }
                if let tag = PriceTag.render(product: product, price: price, code: code) {
                    Section("Pratinjau") {
                        Image(uiImage: tag).resizable().scaledToFit().frame(maxHeight: 180)
                    }
                } else {
                    Section { Text("Kode barcode hanya boleh huruf/angka ASCII.").foregroundStyle(.red) }
                }
                if let failure { Section { Text(failure).foregroundStyle(.red) } }
                Section {
                    Button("Simpan 1 label (PNG)") { save(sheet: false) }
                    Button("Simpan lembar A4 (PDF, siap cetak)") { save(sheet: true) }
                }
            }
            .navigationTitle("Label Harga & Barcode")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Tutup") { dismiss() } } }
        }
    }

    private func save(sheet: Bool) {
        guard let tag = PriceTag.render(product: product, price: price, code: code) else { failure = "Barcode tidak bisa dibuat."; return }
        let safe = product.components(separatedBy: CharacterSet.alphanumerics.inverted).filter { !$0.isEmpty }.joined(separator: "_")
        if sheet {
            onSave([GeneratedDocument(name: "Label_A4_\(safe).pdf", data: PriceTag.a4Sheet(tag))])
        } else if let png = tag.pngData() {
            onSave([GeneratedDocument(name: "Label_\(safe).png", data: png)])
        }
        dismiss()
    }
}

enum PriceTag {
    static func barcode(_ text: String) -> UIImage? {
        guard !text.isEmpty, let ascii = text.data(using: .ascii) else { return nil }
        let f = CIFilter.code128BarcodeGenerator()
        f.message = ascii
        f.quietSpace = 4
        guard let out = f.outputImage?.transformed(by: CGAffineTransform(scaleX: 3, y: 3)),
              let cg = CIContext().createCGImage(out, from: out.extent) else { return nil }
        return UIImage(cgImage: cg)
    }

    static func render(product: String, price: String, code: String) -> UIImage? {
        guard let bar = barcode(code) else { return nil }
        let size = CGSize(width: 600, height: 340)
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        return UIGraphicsImageRenderer(size: size, format: format).image { ctx in
            UIColor.white.setFill(); ctx.fill(CGRect(origin: .zero, size: size))
            UIColor.black.setStroke()
            let border = UIBezierPath(roundedRect: CGRect(x: 4, y: 4, width: size.width - 8, height: size.height - 8), cornerRadius: 18)
            border.lineWidth = 4; border.stroke()
            let center = NSMutableParagraphStyle(); center.alignment = .center
            (product as NSString).draw(in: CGRect(x: 20, y: 22, width: size.width - 40, height: 70),
                                       withAttributes: [.font: UIFont.boldSystemFont(ofSize: 30), .paragraphStyle: center, .foregroundColor: UIColor.black])
            (price as NSString).draw(in: CGRect(x: 20, y: 86, width: size.width - 40, height: 70),
                                     withAttributes: [.font: UIFont.systemFont(ofSize: 50, weight: .heavy), .paragraphStyle: center, .foregroundColor: UIColor.black])
            let barRect = CGRect(x: 60, y: 170, width: size.width - 120, height: 110)
            ctx.cgContext.interpolationQuality = .none
            bar.draw(in: barRect)
            (code as NSString).draw(in: CGRect(x: 20, y: 284, width: size.width - 40, height: 40),
                                    withAttributes: [.font: UIFont.monospacedSystemFont(ofSize: 24, weight: .regular), .paragraphStyle: center, .foregroundColor: UIColor.black])
        }
    }

    static func a4Sheet(_ tag: UIImage) -> Data {
        let a4 = CGRect(x: 0, y: 0, width: 595.28, height: 841.89)
        let margin: CGFloat = 24, gap: CGFloat = 8
        let w = (a4.width - 2 * margin - 2 * gap) / 3
        let h = w * tag.size.height / tag.size.width
        let rows = Int((a4.height - 2 * margin + gap) / (h + gap))
        return UIGraphicsPDFRenderer(bounds: a4).pdfData { ctx in
            ctx.beginPage()
            for r in 0..<rows {
                for c in 0..<3 {
                    tag.draw(in: CGRect(x: margin + CGFloat(c) * (w + gap), y: margin + CGFloat(r) * (h + gap), width: w, height: h))
                }
            }
        }
    }
}

// MARK: - Cetak

enum PrintHelper {
    @MainActor static func print(_ url: URL) {
        let controller = UIPrintInteractionController.shared
        let info = UIPrintInfo(dictionary: nil)
        info.jobName = url.lastPathComponent
        info.outputType = url.pathExtension.lowercased() == "pdf" ? .general : .photo
        controller.printInfo = info
        if UIPrintInteractionController.canPrint(url) {
            controller.printingItem = url
        } else if let data = try? Data(contentsOf: url), let image = UIImage(data: data) {
            controller.printingItem = image
        } else {
            return
        }
        controller.present(animated: true)
    }
}
