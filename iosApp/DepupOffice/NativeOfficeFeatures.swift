import PDFKit
import CoreImage.CIFilterBuiltins
import CryptoKit
import LocalAuthentication
import PencilKit
import Security
import SwiftUI
import UIKit
import UniformTypeIdentifiers
import Vision
import VisionKit

struct GeneratedDocument: Identifiable {
    let id = UUID()
    let name: String
    let data: Data
}

enum NativeOfficeEngine {
    static func run(tool: String, urls: [URL], options: [String: String]) throws -> [GeneratedDocument] {
        if let parity = try ParityEngine.run(tool: tool, urls: urls, options: options) { return parity }
        switch tool {
        case "merge": return [try merge(urls)]
        case "split": return [try split(urls[0], range: options["range"] ?? "1")]
        case "rotate": return [try rotate(urls[0], degrees: Int(options["degrees"] ?? "90") ?? 90)]
        case "watermark": return [try watermark(urls[0], text: options["text"] ?? "DEPU P")]
        case "number": return [try numberPages(urls[0])]
        case "compress": return [try compress(urls[0])]
        case "imagePDF": return [try imagesToPDF(urls)]
        case "ocr": return [try ocr(urls[0])]
        case "extractText": return [try extractText(urls[0])]
        case "search": return [try search(urls[0], query: options["query"] ?? "")]
        case "qrScan": return try urls.map { try scanQR($0) }
        case "compressImages": return try urls.map { try compressImage($0) }
        case "batchImagePDF": return try urls.map { try imagesToPDF([$0], name: "\($0.deletingPathExtension().lastPathComponent).pdf") }
        case "photoWatermark": return try urls.map { try watermarkImage($0, text: options["text"] ?? "") }
        case "pdfImages": return try pdfToImages(urls[0])
        case "pdfToWord": return [try pdfToWord(urls[0])]
        case "protect": return [try protectPDF(urls[0], password: options["password"] ?? "")]
        case "secureDoc": return [try secureDocument(urls[0], accessCode: options["accessCode"] ?? "", editPassword: options["editPassword"] ?? "")]
        default: throw OfficeError.unsupported
        }
    }

    private static func merge(_ urls: [URL]) throws -> GeneratedDocument {
        guard urls.count >= 2 else { throw OfficeError.needTwoFiles }
        let output = PDFDocument()
        for url in urls {
            guard let source = PDFDocument(url: url) else { throw OfficeError.invalidPDF(url.lastPathComponent) }
            for index in 0..<source.pageCount {
                if let page = source.page(at: index) { output.insert(page, at: output.pageCount) }
            }
        }
        return try pdfOutput(output, name: "Gabungan_\(stamp()).pdf")
    }

    private static func split(_ url: URL, range: String) throws -> GeneratedDocument {
        guard let source = PDFDocument(url: url) else { throw OfficeError.invalidPDF(url.lastPathComponent) }
        let pages = try parsePageRange(range, count: source.pageCount)
        let output = PDFDocument()
        for pageIndex in pages {
            if let page = source.page(at: pageIndex) { output.insert(page, at: output.pageCount) }
        }
        return try pdfOutput(output, name: "\(url.deletingPathExtension().lastPathComponent)_halaman.pdf")
    }

    private static func rotate(_ url: URL, degrees: Int) throws -> GeneratedDocument {
        guard let document = PDFDocument(url: url) else { throw OfficeError.invalidPDF(url.lastPathComponent) }
        let amount = ((degrees % 360) + 360) % 360
        for index in 0..<document.pageCount {
            guard let page = document.page(at: index) else { continue }
            page.rotation = (page.rotation + amount) % 360
        }
        return try pdfOutput(document, name: "\(url.deletingPathExtension().lastPathComponent)_rotasi.pdf")
    }

    private static func watermark(_ url: URL, text: String) throws -> GeneratedDocument {
        guard let document = PDFDocument(url: url) else { throw OfficeError.invalidPDF(url.lastPathComponent) }
        guard !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { throw OfficeError.emptyText }
        for index in 0..<document.pageCount {
            guard let page = document.page(at: index) else { continue }
            let bounds = page.bounds(for: .cropBox)
            let annotation = PDFAnnotation(bounds: CGRect(x: bounds.minX + bounds.width * 0.08,
                                                          y: bounds.midY - 28,
                                                          width: bounds.width * 0.84,
                                                          height: 56),
                                           forType: .freeText,
                                           withProperties: nil)
            annotation.contents = text
            annotation.font = UIFont.boldSystemFont(ofSize: min(34, max(18, bounds.width / 18)))
            annotation.fontColor = UIColor.systemGray.withAlphaComponent(0.34)
            annotation.color = .clear
            annotation.alignment = .center
            page.addAnnotation(annotation)
        }
        return try pdfOutput(document, name: "\(url.deletingPathExtension().lastPathComponent)_watermark.pdf")
    }

    private static func numberPages(_ url: URL) throws -> GeneratedDocument {
        guard let document = PDFDocument(url: url) else { throw OfficeError.invalidPDF(url.lastPathComponent) }
        let count = document.pageCount
        for index in 0..<count {
            guard let page = document.page(at: index) else { continue }
            let bounds = page.bounds(for: .cropBox)
            let annotation = PDFAnnotation(bounds: CGRect(x: bounds.minX + bounds.width - 80,
                                                          y: bounds.minY + 14,
                                                          width: 64,
                                                          height: 26),
                                           forType: .freeText,
                                           withProperties: nil)
            annotation.contents = "\(index + 1) / \(count)"
            annotation.font = UIFont.systemFont(ofSize: 10, weight: .medium)
            annotation.fontColor = .darkGray
            annotation.color = .clear
            annotation.alignment = .right
            page.addAnnotation(annotation)
        }
        return try pdfOutput(document, name: "\(url.deletingPathExtension().lastPathComponent)_bernomor.pdf")
    }

    private static func compress(_ url: URL) throws -> GeneratedDocument {
        guard let source = PDFDocument(url: url), source.pageCount > 0 else { throw OfficeError.invalidPDF(url.lastPathComponent) }
        let bounds = CGRect(x: 0, y: 0, width: 612, height: 792)
        let renderer = UIGraphicsPDFRenderer(bounds: bounds)
        let data = renderer.pdfData { context in
            for index in 0..<source.pageCount {
                context.beginPage()
                guard let page = source.page(at: index) else { continue }
                let image = page.thumbnail(of: CGSize(width: 1224, height: 1584), for: .mediaBox)
                let ratio = min(bounds.width / image.size.width, bounds.height / image.size.height)
                let size = CGSize(width: image.size.width * ratio, height: image.size.height * ratio)
                image.draw(in: CGRect(x: (bounds.width - size.width) / 2,
                                      y: (bounds.height - size.height) / 2,
                                      width: size.width,
                                      height: size.height))
            }
        }
        return GeneratedDocument(name: "\(url.deletingPathExtension().lastPathComponent)_ringkas.pdf", data: data)
    }

    private static func imagesToPDF(_ urls: [URL], name: String? = nil) throws -> GeneratedDocument {
        guard !urls.isEmpty else { throw OfficeError.noFiles }
        let page = CGRect(x: 0, y: 0, width: 612, height: 792)
        let renderer = UIGraphicsPDFRenderer(bounds: page)
        let data = renderer.pdfData { context in
            for url in urls {
                guard let image = UIImage(contentsOfFile: url.path) else { continue }
                context.beginPage()
                let scale = min(page.width / image.size.width, page.height / image.size.height)
                let size = CGSize(width: image.size.width * scale, height: image.size.height * scale)
                image.draw(in: CGRect(x: (page.width - size.width) / 2,
                                      y: (page.height - size.height) / 2,
                                      width: size.width,
                                      height: size.height))
            }
        }
        return GeneratedDocument(name: name ?? "Foto_\(stamp()).pdf", data: data)
    }

    private static func compressImage(_ url: URL) throws -> GeneratedDocument {
        guard let image = UIImage(contentsOfFile: url.path), let data = image.jpegData(compressionQuality: 0.58) else {
            throw OfficeError.invalidImage(url.lastPathComponent)
        }
        return GeneratedDocument(name: "\(url.deletingPathExtension().lastPathComponent)_ringkas.jpg", data: data)
    }

    private static func watermarkImage(_ url: URL, text: String) throws -> GeneratedDocument {
        guard let image = UIImage(contentsOfFile: url.path) else { throw OfficeError.invalidImage(url.lastPathComponent) }
        guard !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { throw OfficeError.emptyText }
        let renderer = UIGraphicsImageRenderer(size: image.size)
        let output = renderer.image { context in
            image.draw(in: CGRect(origin: .zero, size: image.size))
            let attributes: [NSAttributedString.Key: Any] = [
                .font: UIFont.boldSystemFont(ofSize: max(18, image.size.width / 24)),
                .foregroundColor: UIColor.white.withAlphaComponent(0.78),
                .strokeColor: UIColor.black.withAlphaComponent(0.38),
                .strokeWidth: -2
            ]
            let textSize = (text as NSString).size(withAttributes: attributes)
            let rect = CGRect(x: image.size.width - textSize.width - 24,
                              y: image.size.height - textSize.height - 22,
                              width: textSize.width,
                              height: textSize.height)
            (text as NSString).draw(in: rect, withAttributes: attributes)
            context.cgContext.flush()
        }
        guard let data = output.jpegData(compressionQuality: 0.9) else { throw OfficeError.cannotWrite }
        return GeneratedDocument(name: "\(url.deletingPathExtension().lastPathComponent)_watermark.jpg", data: data)
    }

    private static func scanQR(_ url: URL) throws -> GeneratedDocument {
        let image = try ParityEngine.loadImage(url)
        let results = QRReader.read(image)
        guard !results.isEmpty else { throw ParityError.message("Kode QR/barcode tidak ditemukan di \(url.lastPathComponent).") }
        return GeneratedDocument(name: "Hasil_kode_\(url.deletingPathExtension().lastPathComponent).txt",
                                 data: Data(AiText.clean(results.joined(separator: "\n")).utf8))
    }

    private static func pdfToImages(_ url: URL) throws -> [GeneratedDocument] {
        guard let document = PDFDocument(url: url), document.pageCount > 0 else { throw OfficeError.invalidPDF(url.lastPathComponent) }
        let base = url.deletingPathExtension().lastPathComponent
        return try (0..<document.pageCount).map { index in
            guard let page = document.page(at: index) else { throw OfficeError.cannotWrite }
            let image = page.thumbnail(of: CGSize(width: 1800, height: 2400), for: .mediaBox)
            guard let data = image.jpegData(compressionQuality: 0.92) else { throw OfficeError.cannotWrite }
            return GeneratedDocument(name: "\(base)_halaman_\(index + 1).jpg", data: data)
        }
    }

    private static func pdfToWord(_ url: URL) throws -> GeneratedDocument {
        guard let document = PDFDocument(url: url) else { throw OfficeError.invalidPDF(url.lastPathComponent) }
        let escaped = (document.string ?? "").replacingOccurrences(of: "\\", with: "\\\\")
            .replacingOccurrences(of: "{", with: "\\{")
            .replacingOccurrences(of: "}", with: "\\}")
            .replacingOccurrences(of: "\n", with: "\\par\n")
        let rtf = "{\\rtf1\\ansi\\deff0 {\\fonttbl{\\f0 Helvetica;}}\\f0\\fs24 \(escaped)}"
        return GeneratedDocument(name: "\(url.deletingPathExtension().lastPathComponent)_teks.rtf", data: Data(rtf.utf8))
    }

    private static func protectPDF(_ url: URL, password: String) throws -> GeneratedDocument {
        guard password.count >= 4 else { throw OfficeError.passwordTooShort }
        guard let document = PDFDocument(url: url) else { throw OfficeError.invalidPDF(url.lastPathComponent) }
        let options: [PDFDocumentWriteOption: Any] = [
            .ownerPasswordOption: UUID().uuidString,
            .userPasswordOption: password
        ]
        guard let data = document.dataRepresentation(options: options) else { throw OfficeError.cannotWrite }
        return GeneratedDocument(name: "\(url.deletingPathExtension().lastPathComponent)_terlindungi.pdf", data: data)
    }

    /// Dokumen Terlindungi v3 (format sama dengan Android): bisa dibuka di Android & iPhone dengan kode akses.
    private static func secureDocument(_ url: URL, accessCode: String, editPassword: String) throws -> GeneratedDocument {
        if let problem = SecurePackage.validateNewCode(accessCode) { throw ProtectedDocError.invalidCode(problem) }
        let edit = editPassword.trimmingCharacters(in: .whitespacesAndNewlines)
        guard edit.count >= 6, Set(edit).count > 1, edit != "123456" else {
            throw ProtectedDocError.invalidCode("Password edit guru minimal 6 karakter dan tidak mudah ditebak.")
        }
        guard SecurePackage.normalizeCode(edit) != SecurePackage.normalizeCode(accessCode) else {
            throw ProtectedDocError.invalidCode("Password edit guru harus berbeda dari kode akses murid.")
        }
        let original = try Data(contentsOf: url)
        if ProtectedDocReader.payload(original) != nil {
            throw ProtectedDocError.invalidCode("Berkas ini sudah merupakan Dokumen Terlindungi.")
        }
        let data = try ProtectedDocWriter.make(original: original, accessCode: accessCode, editPassword: edit)
        return GeneratedDocument(name: "\(url.deletingPathExtension().lastPathComponent)_terlindungi.pdf", data: data)
    }

    private static func extractText(_ url: URL) throws -> GeneratedDocument {
        guard let document = PDFDocument(url: url) else { throw OfficeError.invalidPDF(url.lastPathComponent) }
        return GeneratedDocument(name: "\(url.deletingPathExtension().lastPathComponent).txt",
                                 data: Data(AiText.clean(document.string ?? "Tidak ada teks yang bisa diekstrak.").utf8))
    }

    private static func search(_ url: URL, query: String) throws -> GeneratedDocument {
        guard let document = PDFDocument(url: url) else { throw OfficeError.invalidPDF(url.lastPathComponent) }
        let needle = query.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !needle.isEmpty else { throw OfficeError.emptyText }
        var matches: [String] = []
        for index in 0..<document.pageCount {
            guard let text = document.page(at: index)?.string else { continue }
            let lines = text.components(separatedBy: .newlines)
            for line in lines where line.localizedCaseInsensitiveContains(needle) {
                matches.append("Halaman \(index + 1): \(line.trimmingCharacters(in: .whitespaces))")
            }
        }
        let body = matches.isEmpty ? "Tidak ditemukan teks ‘\(needle)’ di dalam PDF." : matches.joined(separator: "\n")
        return GeneratedDocument(name: "Hasil_cari_\(stamp()).txt", data: Data(AiText.clean(body).utf8))
    }

    private static func ocr(_ url: URL) throws -> GeneratedDocument {
        guard let document = PDFDocument(url: url), document.pageCount > 0 else { throw OfficeError.invalidPDF(url.lastPathComponent) }
        var allText: [String] = []
        for index in 0..<document.pageCount {
            guard let page = document.page(at: index) else { continue }
            let image = page.thumbnail(of: CGSize(width: 1800, height: 2400), for: .mediaBox)
            guard let cgImage = image.cgImage else { continue }
            let request = VNRecognizeTextRequest()
            request.recognitionLevel = .accurate
            request.recognitionLanguages = ["id-ID", "en-US"]
            request.usesLanguageCorrection = true
            try VNImageRequestHandler(cgImage: cgImage).perform([request])
            let lines = (request.results ?? []).sorted { $0.boundingBox.midY > $1.boundingBox.midY }
                .compactMap { $0.topCandidates(1).first?.string }
            allText.append("— Halaman \(index + 1) —\n" + lines.joined(separator: "\n"))
        }
        return GeneratedDocument(name: "\(url.deletingPathExtension().lastPathComponent)_OCR.txt",
                                 data: Data(AiText.clean(allText.joined(separator: "\n\n")).utf8))
    }

    private static func pdfOutput(_ document: PDFDocument, name: String) throws -> GeneratedDocument {
        guard let data = document.dataRepresentation() else { throw OfficeError.cannotWrite }
        return GeneratedDocument(name: name, data: data)
    }

    private static func parsePageRange(_ text: String, count: Int) throws -> [Int] {
        var indices = Set<Int>()
        for part in text.split(separator: ",") {
            let ends = part.split(separator: "-", omittingEmptySubsequences: false).compactMap { Int($0.trimmingCharacters(in: .whitespaces)) }
            guard !ends.isEmpty else { continue }
            let first = max(1, min(count, ends[0]))
            let last = max(1, min(count, ends.count > 1 ? ends[1] : first))
            for number in min(first, last)...max(first, last) { indices.insert(number - 1) }
        }
        guard !indices.isEmpty else { throw OfficeError.invalidRange }
        return indices.sorted()
    }

    private static func stamp() -> String {
        Date.now.formatted(.dateTime.year().month(.twoDigits).day(.twoDigits).hour(.twoDigits(amPM: .omitted)).minute(.twoDigits))
            .replacingOccurrences(of: "/", with: "")
            .replacingOccurrences(of: ":", with: "")
            .replacingOccurrences(of: " ", with: "_")
    }
}

enum OfficeError: LocalizedError {
    case unsupported, needTwoFiles, invalidPDF(String), invalidImage(String), emptyText, noFiles, cannotWrite, invalidRange, passwordTooShort
    var errorDescription: String? {
        switch self {
        case .unsupported: "Fitur ini belum tersedia untuk jenis berkas tersebut."
        case .needTwoFiles: "Pilih minimal dua berkas PDF untuk digabung."
        case let .invalidPDF(name): "Berkas \(name) tidak bisa dibuka sebagai PDF."
        case let .invalidImage(name): "Gambar \(name) tidak bisa dibuka."
        case .emptyText: "Isi teks terlebih dahulu."
        case .noFiles: "Pilih setidaknya satu berkas."
        case .cannotWrite: "Hasil tidak bisa dibuat."
        case .invalidRange: "Rentang halaman tidak valid. Contoh: 1-3,5."
        case .passwordTooShort: "Kata sandi minimal 4 karakter."
        }
    }
}

private struct OfficeTool: Identifiable {
    let id: String
    let title: String
    let detail: String
    let symbol: String
    let group: String
    var multiple = false
    var imagesOnly = false

    static let all: [OfficeTool] = [
        .init(id: "merge", title: "Gabung PDF", detail: "Satukan beberapa PDF sesuai urutan pilihan", symbol: "square.stack.3d.up", group: "Kelola PDF", multiple: true),
        .init(id: "split", title: "Pisah PDF", detail: "Ambil halaman tertentu dari dokumen", symbol: "scissors", group: "Kelola PDF"),
        .init(id: "rotate", title: "Putar halaman", detail: "Putar seluruh halaman 90°, 180°, atau 270°", symbol: "rotate.right", group: "Kelola PDF"),
        .init(id: "number", title: "Nomor halaman", detail: "Tambahkan nomor halaman ke setiap halaman", symbol: "number", group: "Kelola PDF"),
        .init(id: "watermark", title: "Watermark PDF", detail: "Tambahkan teks watermark pada PDF", symbol: "character.textbox", group: "Kelola PDF"),
        .init(id: "compress", title: "Kompres PDF", detail: "Buat salinan lebih kecil dengan meraster halaman", symbol: "arrow.down.right.and.arrow.up.left", group: "Kelola PDF"),
        .init(id: "pdfImages", title: "PDF ke JPG", detail: "Ekspor setiap halaman sebagai gambar", symbol: "doc.on.doc", group: "Kelola PDF"),
        .init(id: "protect", title: "Kunci PDF", detail: "Enkripsi PDF dengan kata sandi pembuka", symbol: "lock.doc", group: "Keamanan"),
        .init(id: "secureDoc", title: "Buat Dokumen Terlindungi", detail: "Sampul DepUp + AES-256-GCM, dibuka dengan kode akses di Android & iPhone", symbol: "lock.shield.fill", group: "Keamanan"),
        .init(id: "imagePDF", title: "Foto ke PDF", detail: "Gabungkan satu atau beberapa foto ke PDF", symbol: "photo.badge.plus", group: "Konversi", multiple: true, imagesOnly: true),
        .init(id: "ocr", title: "OCR dokumen", detail: "Kenali teks pada hasil scan dan ekspor ke TXT", symbol: "text.viewfinder", group: "Konversi"),
        .init(id: "extractText", title: "Ekstrak teks PDF", detail: "Simpan teks PDF ke berkas TXT", symbol: "doc.text.magnifyingglass", group: "Konversi"),
        .init(id: "pdfToWord", title: "PDF ke Word", detail: "Ekspor teks PDF ke format RTF yang bisa dibuka Word", symbol: "doc.text", group: "Konversi"),
        .init(id: "removeBlank", title: "Hapus halaman kosong", detail: "Deteksi & buang halaman putih polos", symbol: "doc.badge.ellipsis", group: "Kelola PDF"),
        .init(id: "compressTarget", title: "Kompres PDF presisi", detail: "Target < 200 KB / 500 KB / 1 MB", symbol: "gauge.with.dots.needle.33percent", group: "Kelola PDF"),
        .init(id: "extractImages", title: "Ambil gambar", detail: "Simpan foto yang ada di dalam PDF", symbol: "photo.on.rectangle.angled", group: "Kelola PDF"),
        .init(id: "searchablePDF", title: "PDF bisa dicari", detail: "Tanam teks OCR, bisa dicari & disalin", symbol: "text.magnifyingglass", group: "Konversi"),
        .init(id: "exeToPdf", title: "EXE ke PDF", detail: "Ambil PDF dari berkas .exe (PDF to EXE)", symbol: "shippingbox", group: "Konversi"),
        .init(id: "shadowRemove", title: "Pembersih bayangan foto", detail: "Hapus bayangan tangan/HP & putihkan kertas", symbol: "wand.and.rays", group: "Gambar", multiple: true, imagesOnly: true),
        .init(id: "transparentSign", title: "TTD transparan", detail: "Foto tanda tangan jadi PNG tanpa latar", symbol: "signature", group: "Gambar", imagesOnly: true),
        .init(id: "idPhoto", title: "Pembuat pasfoto", detail: "Potong 2x3 3x4 4x6, latar merah/biru/putih, cetak A4", symbol: "person.crop.square", group: "Gambar", imagesOnly: true),
        .init(id: "businessCard", title: "Pindai kartu nama", detail: "Nama, HP & email jadi kontak (.vcf)", symbol: "person.text.rectangle", group: "Gambar", imagesOnly: true),
        .init(id: "tableToExcel", title: "Pindai tabel ke Excel", detail: "Foto tabel/kuitansi jadi berkas .xlsx", symbol: "tablecells.badge.ellipsis", group: "Konversi", imagesOnly: true),
        .init(id: "receiptToExcel", title: "Scan struk", detail: "Daftar belanja & total ke Excel", symbol: "receipt", group: "Konversi", multiple: true, imagesOnly: true),
        .init(id: "invoice", title: "Nota / Invoice", detail: "Nomor otomatis, hitung total, stempel toko (PDF + Excel)", symbol: "doc.plaintext", group: "Buat"),
        .init(id: "catalog", title: "Katalog produk", detail: "Foto, nama, harga & deskripsi jadi katalog PDF", symbol: "square.grid.2x2", group: "Buat"),
        .init(id: "schedule", title: "Perencana jadwal", detail: "Agenda harian/mingguan ke Excel & PDF", symbol: "calendar", group: "Buat"),
        .init(id: "notes", title: "Catatan", detail: "Teks & daftar centang, tersimpan di perangkat", symbol: "note.text", group: "Buat"),
        .init(id: "chart", title: "Grafik & chart Excel", detail: "Batang, lingkaran & garis dari .xlsx / .csv", symbol: "chart.bar.xaxis", group: "Konversi"),
        .init(id: "officeToPdf", title: "Word/Excel ke PDF", detail: "Ubah .docx, .xlsx, .pptx menjadi PDF", symbol: "doc.richtext", group: "Konversi"),
        .init(id: "summary", title: "Ringkas dokumen", detail: "Poin penting dari PDF, Word atau teks", symbol: "text.append", group: "Dokumen"),
        .init(id: "voice", title: "Bacakan suara", detail: "Dokumen dibacakan dengan suara bahasa Indonesia", symbol: "speaker.wave.2", group: "Dokumen"),
        .init(id: "slideshow", title: "Presentasi PDF", detail: "Tampilkan PDF layar penuh seperti slide", symbol: "play.rectangle", group: "Dokumen"),
        .init(id: "searchAll", title: "Cari teks dalam file", detail: "Cari kata di semua PDF, Word, Excel & teks", symbol: "doc.text.magnifyingglass", group: "Dokumen"),
        .init(id: "priceTag", title: "Label harga & barcode", detail: "Buat label harga toko & lembar A4", symbol: "barcode", group: "Dokumen"),
        .init(id: "print", title: "Cetak", detail: "Printer AirPrint atau simpan PDF", symbol: "printer", group: "Bagikan & ekspor"),
        .init(id: "annotate", title: "Coret-coret PDF", detail: "Stabilo, pena, catatan & sorot teks", symbol: "pencil.and.scribble", group: "Kelola PDF"),
        .init(id: "docEditor", title: "Edit Word", detail: "Buka & ubah .docx / RTF, simpan ke Word atau PDF", symbol: "doc.text", group: "Buat"),
        .init(id: "sheetEditor", title: "Edit Excel", detail: "Buka & ubah .xlsx / CSV dengan rumus SUM, AVERAGE…", symbol: "tablecells", group: "Buat"),
        .init(id: "templates", title: "Template dokumen", detail: "Surat izin, keterangan RT/RW, undangan, notulen, proposal", symbol: "doc.on.doc", group: "Buat"),
        .init(id: "signLibrary", title: "Pustaka TTD", detail: "Simpan tanda tangan untuk dipakai ulang", symbol: "signature", group: "Dokumen"),
        .init(id: "organize", title: "Susun & putar halaman", detail: "Urutkan, hapus, putar halaman, dan sisipkan halaman kosong", symbol: "rectangle.stack", group: "Kelola PDF"),
        .init(id: "sign", title: "Tanda tangan PDF", detail: "Gambar tanda tangan lalu tempel di halaman pilihan", symbol: "signature", group: "Dokumen"),
        .init(id: "liveQR", title: "Pindai QR dari kamera", detail: "Arahkan kamera ke QR atau barcode", symbol: "camera.viewfinder", group: "Konversi"),
        .init(id: "search", title: "Cari isi PDF", detail: "Temukan kata dalam dokumen PDF", symbol: "magnifyingglass", group: "Dokumen"),
        .init(id: "qrScan", title: "Pindai QR / barcode", detail: "Kenali kode dari foto lalu simpan hasilnya", symbol: "qrcode.viewfinder", group: "Konversi", imagesOnly: true),
        .init(id: "batchImagePDF", title: "Konversi foto massal", detail: "Buat satu PDF untuk setiap gambar", symbol: "photo.stack", group: "Konversi", multiple: true, imagesOnly: true),
        .init(id: "compressImages", title: "Kompres foto", detail: "Buat salinan JPEG yang lebih kecil", symbol: "arrow.down.right.and.arrow.up.left", group: "Gambar", multiple: true, imagesOnly: true),
        .init(id: "photoWatermark", title: "Watermark foto", detail: "Tempel teks pada beberapa foto", symbol: "character.textbox", group: "Gambar", multiple: true, imagesOnly: true),
        .init(id: "vault", title: "Brankas dokumen", detail: "Enkripsi berkas dengan AES-GCM dan kunci iOS Keychain", symbol: "lock.shield", group: "Keamanan"),
        .init(id: "share", title: "Bagikan berkas", detail: "Kirim dokumen melalui aplikasi iOS", symbol: "square.and.arrow.up", group: "Bagikan & ekspor")
    ]
}

struct NativeToolsScreen: View {
    @EnvironmentObject private var documents: DocumentStore
    @State private var selectedTool: OfficeTool?
    @State private var importTool: OfficeTool?
    @State private var importParameters: [String: String] = [:]
    @State private var isImporterPresented = false
    @State private var shareFile: ShareFile?
    @State private var shareTemporaryURL: URL?
    @State private var draftKind: DraftKind?
    @State private var isQRGeneratorPresented = false
    @State private var editorTarget: EditorTarget?
    @State private var editorCopy: URL?
    @State private var isLiveQRPresented = false
    @State private var isPriceTagPresented = false
    @State private var directTool: DirectTool?

    var body: some View {
        List {
            Section {
                Label("Pemrosesan berkas dilakukan di perangkat ini.", systemImage: "iphone.and.arrow.forward")
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
            }
            ForEach(["Kelola PDF", "Konversi", "Buat", "Gambar", "Dokumen", "Keamanan", "Bagikan & ekspor"], id: \.self) { group in
                Section(group) {
                    ForEach(OfficeTool.all.filter { $0.group == group }) { tool in
                        Button {
                            if tool.id == "liveQR" { isLiveQRPresented = true }
                            else if tool.id == "priceTag" { isPriceTagPresented = true }
                            else if let direct = DirectTool(rawValue: tool.id) { directTool = direct }
                            else { selectedTool = tool }
                        } label: {
                            HStack(spacing: 13) {
                                Image(systemName: tool.symbol)
                                    .font(.system(size: 17, weight: .medium))
                                    .foregroundStyle(Color.depupBlue)
                                    .frame(width: 38, height: 38)
                                    .background(Color.depupBlue.opacity(0.10), in: RoundedRectangle(cornerRadius: 11))
                                VStack(alignment: .leading, spacing: 3) {
                                    Text(tool.title).font(.body.weight(.medium)).foregroundStyle(.primary)
                                    Text(tool.detail).font(.caption).foregroundStyle(.secondary).fixedSize(horizontal: false, vertical: true)
                                }
                                Spacer(minLength: 0)
                                Image(systemName: "chevron.right").font(.caption.weight(.semibold)).foregroundStyle(.tertiary)
                            }.padding(.vertical, 4)
                        }.buttonStyle(.plain)
                    }
                }
            }
        }
        .listStyle(.insetGrouped)
        .background(Color.depupBackground)
        .sheet(item: $selectedTool, onDismiss: {
            if importTool != nil { isImporterPresented = true }
        }) { tool in
            if tool.id == "vault" {
                NativeVaultScreen()
            } else {
                ToolSetupSheet(tool: tool) { parameters in
                    importTool = tool
                    importParameters = parameters
                    selectedTool = nil
                }
                .presentationDetents([.medium])
                .presentationDragIndicator(.visible)
            }
        }
        .sheet(item: $draftKind) { kind in
            if kind == .word {
                DocEditorView(source: nil) { documents.saveGenerated($0) }
            } else {
                SheetEditorView(source: nil, template: nil) { documents.saveGenerated($0) }
            }
        }
        .sheet(item: $editorTarget, onDismiss: { cleanupEditorCopies() }) { target in
            if target.kind == "sign" {
                SignPDFView(url: target.url) { data in
                    documents.savePDF(data, name: target.url.deletingPathExtension().lastPathComponent + "_ttd")
                }
            } else if target.kind == "chart" {
                ChartMakerView(url: target.url) { documents.saveGenerated($0) }
            } else if target.kind == "summary" {
                SummaryView(url: target.url) { documents.saveGenerated($0) }
            } else if target.kind == "voice" {
                VoiceReaderView(url: target.url)
            } else if target.kind == "slideshow" {
                SlideshowView(url: target.url)
            } else if target.kind == "annotate" {
                AnnotatePDFView(url: target.url) { data in
                    documents.savePDF(data, name: target.url.deletingPathExtension().lastPathComponent + "_coret")
                }
            } else if target.kind == "docEditor" {
                DocEditorView(source: target.url) { documents.saveGenerated($0) }
            } else if target.kind == "sheetEditor" {
                SheetEditorView(source: target.url, template: nil) { documents.saveGenerated($0) }
            } else if target.kind == "officeToPdf" {
                OfficeToPDFView(url: target.url) { documents.saveGenerated($0) }
            } else {
                PageOrganizerView(url: target.url) { data in
                    documents.savePDF(data, name: target.url.deletingPathExtension().lastPathComponent + "_susun")
                }
            }
        }
        .sheet(item: $directTool) { tool in
            switch tool {
            case .invoice: InvoiceMakerView { documents.saveGenerated($0) }
            case .catalog: CatalogMakerView { documents.saveGenerated($0) }
            case .schedule: SchedulePlannerView { documents.saveGenerated($0) }
            case .notes: NotesScreen()
            case .searchAll: SearchAllView().environmentObject(documents)
            case .templates: DocTemplatePicker(onSave: { documents.saveGenerated($0) }, onEdit: { _ in })
            case .sheetInvoice: SheetEditorView(source: nil, template: "invoice") { documents.saveGenerated($0) }
            case .sheetPresensi: SheetEditorView(source: nil, template: "presensi") { documents.saveGenerated($0) }
            case .sheetKeuangan: SheetEditorView(source: nil, template: "keuangan") { documents.saveGenerated($0) }
            case .signLibrary: SignatureLibraryView(onPick: nil)
            }
        }
        .sheet(isPresented: $isPriceTagPresented) {
            PriceTagSheet { docs in documents.saveGenerated(docs) }
        }
        .sheet(isPresented: $isLiveQRPresented) {
            LiveQRScannerSheet { text in documents.saveText(text, name: "QR_\(Int(Date().timeIntervalSince1970))") }
        }
        .sheet(isPresented: $isQRGeneratorPresented) {
            QRCodeGeneratorSheet { name, data in documents.saveGeneratedData(data, name: name) }
                .presentationDetents([.medium, .large])
        }
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Menu {
                    Button { draftKind = .word } label: { Label("Dokumen Word", systemImage: "doc.text") }
                    Button { directTool = .templates } label: { Label("Template surat & proposal", systemImage: "doc.on.doc") }
                    Button { draftKind = .spreadsheet } label: { Label("Spreadsheet Excel", systemImage: "tablecells") }
                    Menu {
                        Button("Invoice / Kuitansi") { directTool = .sheetInvoice }
                        Button("Presensi & Daftar Hadir") { directTool = .sheetPresensi }
                        Button("Laporan Keuangan") { directTool = .sheetKeuangan }
                    } label: { Label("Template Excel", systemImage: "tablecells.badge.ellipsis") }
                    Button { isQRGeneratorPresented = true } label: { Label("Buat QR Code", systemImage: "qrcode") }
                } label: {
                    Image(systemName: "plus")
                }
                .accessibilityLabel("Buat dokumen")
            }
        }
        .fileImporter(isPresented: $isImporterPresented,
                      allowedContentTypes: importerTypes,
                      allowsMultipleSelection: importTool?.multiple ?? false) { result in
            switch result {
            case let .success(urls): handlePicked(urls)
            case let .failure(error):
                documents.statusMessage = "Pemilihan berkas gagal: \(error.localizedDescription)"
                importTool = nil
            }
        }
        .sheet(item: $shareFile, onDismiss: {
            if let shareTemporaryURL { try? FileManager.default.removeItem(at: shareTemporaryURL) }
            shareTemporaryURL = nil
        }) { file in
            ActivityShareSheet(url: file.url)
                .presentationDetents([.medium, .large])
        }
    }

    private var importerTypes: [UTType] {
        guard let tool = importTool else { return [.item] }
        if tool.imagesOnly { return [.image] }
        if tool.id == "merge" { return [.pdf] }
        if tool.id == "share" || tool.id == "print" { return [.item] }
        if tool.id == "exeToPdf" { return [.data] }
        if tool.id == "chart" { return [.commaSeparatedText, .plainText] + [UTType(filenameExtension: "xlsx")].compactMap { $0 } }
        if tool.id == "summary" || tool.id == "voice" { return DocText.readableTypes }
        if tool.id == "docEditor" { return [.rtf, .plainText] + [UTType(filenameExtension: "docx")].compactMap { $0 } }
        if tool.id == "sheetEditor" { return [.commaSeparatedText] + [UTType(filenameExtension: "xlsx")].compactMap { $0 } }
        if tool.id == "officeToPdf" { return ["docx", "xlsx", "pptx", "doc", "xls", "ppt"].compactMap { UTType(filenameExtension: $0) } + [.rtf] }
        return [.pdf]
    }

    /// Salinan sementara untuk editor dihapus setelah lembar ditutup (hasil sudah disimpan ke folder DepUp Office).
    private func cleanupEditorCopies() {
        if let editorCopy { try? FileManager.default.removeItem(at: editorCopy) }
        editorCopy = nil
    }

    private func handlePicked(_ urls: [URL]) {
        guard let tool = importTool else { return }
        defer { importTool = nil }
        if ["organize", "sign", "chart", "summary", "voice", "slideshow", "officeToPdf", "annotate", "docEditor", "sheetEditor"].contains(tool.id) {
            guard let url = urls.first else { return }
            do {
                let copy = try documents.makeShareCopy(from: url)
                editorCopy = copy
                editorTarget = EditorTarget(url: copy, kind: tool.id)
            }
            catch { documents.statusMessage = error.localizedDescription }
            return
        }
        if tool.id == "print" {
            guard let url = urls.first, let copy = try? documents.makeShareCopy(from: url) else { return }
            PrintHelper.print(copy)
            return
        }
        if tool.id == "share" {
            guard let url = urls.first else { return }
            do {
                let copy = try documents.makeShareCopy(from: url)
                shareTemporaryURL = copy
                shareFile = ShareFile(url: copy)
            } catch { documents.statusMessage = error.localizedDescription }
            return
        }
        documents.runTool(tool.id, urls: urls, options: importParameters)
    }
}

private enum DraftKind: String, Identifiable {
    case word, spreadsheet
    var id: String { rawValue }
}

private struct WordDraftEditor: View {
    let onSave: (String, String) -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var name = "Dokumen Baru"
    @State private var text = ""

    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                TextField("Nama dokumen", text: $name)
                    .font(.headline)
                    .padding()
                Divider()
                TextEditor(text: $text)
                    .padding(.horizontal, 10)
                    .overlay(alignment: .topLeading) {
                        if text.isEmpty {
                            Text("Mulai menulis…")
                                .foregroundStyle(.tertiary)
                                .padding(.top, 9)
                                .padding(.leading, 15)
                                .allowsHitTesting(false)
                        }
                    }
            }
            .navigationTitle("Dokumen Word")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) { Button("Batal") { dismiss() } }
                ToolbarItem(placement: .topBarTrailing) {
                    Button("Simpan") { onSave(name, text); dismiss() }.fontWeight(.semibold)
                }
            }
        }
    }
}

private struct SpreadsheetDraftEditor: View {
    let onSave: (String, String) -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var name = "Spreadsheet Baru"
    @State private var csv = ""

    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                TextField("Nama spreadsheet", text: $name)
                    .font(.headline)
                    .padding()
                Divider()
                TextEditor(text: $csv)
                    .font(.system(.body, design: .monospaced))
                    .padding(.horizontal, 10)
                    .overlay(alignment: .topLeading) {
                        if csv.isEmpty {
                            Text("Masukkan nilai dipisahkan koma; setiap baris menjadi satu baris spreadsheet.")
                                .foregroundStyle(.tertiary)
                                .padding(.top, 9)
                                .padding(.leading, 15)
                                .allowsHitTesting(false)
                        }
                    }
            }
            .navigationTitle("Spreadsheet Excel")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) { Button("Batal") { dismiss() } }
                ToolbarItem(placement: .topBarTrailing) {
                    Button("Simpan") { onSave(name, csv); dismiss() }.fontWeight(.semibold)
                }
            }
        }
    }
}

private struct ToolSetupSheet: View {
    let tool: OfficeTool
    let onContinue: ([String: String]) -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var range = "1-3"
    @State private var degrees = "90"
    @State private var watermark = "DEPU P OFFICE"
    @State private var query = ""
    @State private var password = ""
    @State private var accessCode = SecurePackage.generateAccessCode()
    @State private var targetKB = "500"
    @State private var photoSize = "3x4"
    @State private var photoBackground = "red"
    @State private var editPassword = ""

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    Label(tool.title, systemImage: tool.symbol).font(.headline)
                    Text(tool.detail).font(.subheadline).foregroundStyle(.secondary)
                }
                if tool.id == "split" {
                    Section("Halaman") {
                        TextField("Contoh: 1-3,5", text: $range).keyboardType(.numbersAndPunctuation)
                    }
                } else if tool.id == "rotate" {
                    Section("Sudut") {
                        Picker("Putar", selection: $degrees) {
                            Text("90°").tag("90")
                            Text("180°").tag("180")
                            Text("270°").tag("270")
                        }.pickerStyle(.segmented)
                    }
                } else if tool.id == "watermark" {
                    Section("Teks watermark") { TextField("Teks", text: $watermark) }
                } else if tool.id == "photoWatermark" {
                    Section("Teks watermark") { TextField("Teks", text: $watermark) }
                } else if tool.id == "search" {
                    Section("Kata yang dicari") { TextField("Masukkan kata atau frasa", text: $query) }
                } else if tool.id == "protect" {
                    Section("Kata sandi PDF") {
                        SecureField("Minimal 4 karakter", text: $password)
                    }
                } else if tool.id == "compressTarget" {
                    Section("Target ukuran") {
                        Picker("Target", selection: $targetKB) {
                            Text("< 200 KB").tag("200")
                            Text("< 500 KB").tag("500")
                            Text("< 1 MB").tag("1000")
                        }.pickerStyle(.segmented)
                    }
                } else if tool.id == "idPhoto" {
                    Section("Ukuran") {
                        Picker("Ukuran", selection: $photoSize) {
                            Text("2x3").tag("2x3")
                            Text("3x4").tag("3x4")
                            Text("4x6").tag("4x6")
                        }.pickerStyle(.segmented)
                    }
                    Section("Warna latar") {
                        Picker("Latar", selection: $photoBackground) {
                            Text("Merah").tag("red")
                            Text("Biru").tag("blue")
                            Text("Putih").tag("white")
                        }.pickerStyle(.segmented)
                    }
                } else if tool.id == "secureDoc" {
                    Section {
                        HStack {
                            TextField("Kode akses", text: $accessCode)
                                .font(.body.monospaced())
                                .textInputAutocapitalization(.characters)
                                .autocorrectionDisabled()
                            Button { accessCode = SecurePackage.generateAccessCode() } label: { Image(systemName: "arrow.clockwise") }
                                .accessibilityLabel("Buat kode baru")
                            Button { copySensitive(SecurePackage.groupedCode(accessCode)) } label: { Image(systemName: "doc.on.doc") }
                                .accessibilityLabel("Salin kode")
                        }
                    } header: { Text("Kode akses murid") } footer: {
                        Text("Bagikan kode ini terpisah dari berkasnya. Kode tidak tersimpan di dokumen dan tidak bisa dipulihkan bila hilang.")
                    }
                    Section("Password edit guru") {
                        SecureField("Untuk mengekspor PDF asli (min. 6 karakter)", text: $editPassword)
                    }
                }
                Section {
                    Button {
                        var values: [String: String] = [:]
                        if tool.id == "split" { values["range"] = range }
                        if tool.id == "rotate" { values["degrees"] = degrees }
                        if tool.id == "watermark" { values["text"] = watermark }
                        if tool.id == "photoWatermark" { values["text"] = watermark }
                        if tool.id == "search" { values["query"] = query }
                        if tool.id == "protect" { values["password"] = password }
                        if tool.id == "compressTarget" { values["targetKB"] = targetKB }
                        if tool.id == "idPhoto" { values["size"] = photoSize; values["background"] = photoBackground }
                        if tool.id == "secureDoc" {
                            values["accessCode"] = accessCode
                            values["editPassword"] = editPassword
                            copySensitive(SecurePackage.groupedCode(accessCode))
                        }
                        onContinue(values)
                        dismiss()
                    } label: {
                        Label(tool.multiple ? "Pilih berkas" : "Pilih berkas", systemImage: "folder")
                            .frame(maxWidth: .infinity)
                    }
                    .buttonStyle(.borderedProminent)
                }
            }
            .navigationTitle("Alat dokumen")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .topBarTrailing) { Button("Tutup") { dismiss() } } }
        }
    }
}

/// Salin ke papan klip hanya di perangkat ini (tanpa Universal Clipboard) dan kedaluwarsa 2 menit.
private func copySensitive(_ value: String) {
    UIPasteboard.general.setItems([[UTType.plainText.identifier: value]],
                                  options: [.localOnly: true, .expirationDate: Date().addingTimeInterval(120)])
}

private struct ShareFile: Identifiable {
    let id = UUID()
    let url: URL
    init(url: URL) { self.url = url }
}

struct ActivityShareSheet: UIViewControllerRepresentable {
    let url: URL
    func makeUIViewController(context: Context) -> UIActivityViewController {
        UIActivityViewController(activityItems: [url], applicationActivities: nil)
    }
    func updateUIViewController(_ controller: UIActivityViewController, context: Context) {}
}

private struct QRCodeGeneratorSheet: View {
    let onSave: (String, Data) -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var payload = ""
    @State private var name = "QR_Code"
    @State private var error: String?

    private var qrImage: UIImage? {
        guard let data = payload.data(using: .utf8), !data.isEmpty else { return nil }
        let filter = CIFilter.qrCodeGenerator()
        filter.message = data
        filter.correctionLevel = "M"
        guard let output = filter.outputImage,
              let cgImage = CIContext().createCGImage(output.transformed(by: CGAffineTransform(scaleX: 10, y: 10)), from: output.extent.applying(CGAffineTransform(scaleX: 10, y: 10))) else { return nil }
        return UIImage(cgImage: cgImage)
    }

    var body: some View {
        NavigationStack {
            Form {
                Section("Isi QR Code") {
                    TextField("Teks, nomor, atau URL", text: $payload, axis: .vertical)
                        .lineLimit(2...5)
                    TextField("Nama gambar", text: $name)
                }
                if let qrImage {
                    Section("Pratinjau") {
                        Image(uiImage: qrImage)
                            .interpolation(.none)
                            .resizable()
                            .scaledToFit()
                            .frame(maxWidth: .infinity)
                            .frame(height: 190)
                    }
                }
                if let error { Text(error).foregroundStyle(.red) }
                Section {
                    Button("Simpan QR sebagai PNG") {
                        guard let data = qrImage?.pngData() else { error = "Isi QR belum valid."; return }
                        onSave(name, data)
                        dismiss()
                    }
                    .disabled(qrImage == nil)
                }
            }
            .navigationTitle("Buat QR Code")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .topBarTrailing) { Button("Tutup") { dismiss() } } }
        }
    }
}

private struct VaultItem: Identifiable {
    let url: URL
    let name: String
    var id: String { url.path }
}

private enum NativeVaultStore {
    private static let service = "cloud.depup.office.ios.vault"

    static var directory: URL {
        FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("DepUpOffice/Vault", isDirectory: true)
    }

    static func items() throws -> [VaultItem] {
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        return try FileManager.default.contentsOfDirectory(at: directory, includingPropertiesForKeys: nil)
            .filter { $0.pathExtension == "depupvault" }
            .compactMap { url in
                guard let clear = try? decrypt(url) else { return nil }
                return VaultItem(url: url, name: clear.name)
            }
            .sorted { $0.name.localizedCaseInsensitiveCompare($1.name) == .orderedAscending }
    }

    static func add(_ urls: [URL]) throws {
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        let key = try encryptionKey()
        for source in urls {
            let hasAccess = source.startAccessingSecurityScopedResource()
            defer { if hasAccess { source.stopAccessingSecurityScopedResource() } }
            let name = Data(source.lastPathComponent.utf8)
            guard name.count <= Int(UInt32.max) else { throw OfficeError.cannotWrite }
            var length = UInt32(name.count).bigEndian
            var payload = withUnsafeBytes(of: &length) { Data($0) }
            payload.append(name)
            payload.append(try Data(contentsOf: source))
            let sealed = try AES.GCM.seal(payload, using: key)
            guard let combined = sealed.combined else { throw OfficeError.cannotWrite }
            try combined.write(to: directory.appendingPathComponent("\(UUID().uuidString).depupvault"), options: .atomic)
        }
    }

    static func decrypt(_ url: URL) throws -> (name: String, data: Data) {
        let sealed = try AES.GCM.SealedBox(combined: Data(contentsOf: url))
        let payload = try AES.GCM.open(sealed, using: encryptionKey())
        guard payload.count >= 4 else { throw OfficeError.cannotWrite }
        let length = payload.prefix(4).reduce(UInt32(0)) { ($0 << 8) | UInt32($1) }
        guard Int(length) <= payload.count - 4,
              let name = String(data: payload[4..<(4 + Int(length))], encoding: .utf8) else { throw OfficeError.cannotWrite }
        return (name, payload.dropFirst(4 + Int(length)))
    }

    private static func encryptionKey() throws -> SymmetricKey {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: "vault-key",
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne
        ]
        var result: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &result)
        if status == errSecSuccess, let data = result as? Data { return SymmetricKey(data: data) }
        guard status == errSecItemNotFound else { throw OfficeError.cannotWrite }
        var data = Data(count: 32)
        let randomStatus = data.withUnsafeMutableBytes { SecRandomCopyBytes(kSecRandomDefault, 32, $0.baseAddress!) }
        guard randomStatus == errSecSuccess else { throw OfficeError.cannotWrite }
        let addQuery: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: "vault-key",
            kSecAttrAccessible as String: kSecAttrAccessibleWhenUnlockedThisDeviceOnly,
            kSecValueData as String: data
        ]
        guard SecItemAdd(addQuery as CFDictionary, nil) == errSecSuccess else { throw OfficeError.cannotWrite }
        return SymmetricKey(data: data)
    }
}

private struct NativeVaultScreen: View {
    @State private var items: [VaultItem] = []
    @State private var isImporterPresented = false
    @State private var isUnlocked = false
    @State private var isBusy = false
    @State private var message: String?
    @State private var shareFile: ShareFile?
    @State private var shareTemporaryURL: URL?
    @State private var pendingDelete: VaultItem?

    var body: some View {
        NavigationStack {
            Group {
                if !isUnlocked || isBusy {
                    ProgressView(isBusy ? "Memproses brankas…" : "Membuka brankas…")
                } else if items.isEmpty {
                    ContentUnavailableView {
                        Label("Brankas kosong", systemImage: "lock.shield")
                    } description: {
                        Text("Berkas dienkripsi di perangkat dengan AES-GCM. Kunci disimpan di iOS Keychain.")
                    } actions: {
                        Button("Tambahkan berkas") { isImporterPresented = true }.buttonStyle(.borderedProminent)
                    }
                } else {
                    List {
                        Section {
                            Label("Terkunci dengan autentikasi perangkat", systemImage: "faceid")
                                .font(.caption)
                                .foregroundStyle(.secondary)
                        }
                        Section("Berkas terlindungi") {
                            ForEach(items) { item in
                                HStack {
                                    Button { restore(item) } label: {
                                        Label(item.name, systemImage: "doc.zipper")
                                            .lineLimit(1)
                                    }
                                    .buttonStyle(.plain)
                                    Spacer(minLength: 4)
                                    Button(role: .destructive) { pendingDelete = item } label: {
                                        Image(systemName: "trash")
                                    }
                                    .buttonStyle(.borderless)
                                    .accessibilityLabel("Hapus \(item.name) dari brankas")
                                }
                            }
                        }
                    }
                    .listStyle(.insetGrouped)
                }
            }
            .navigationTitle("Brankas dokumen")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) { Button("Tutup") { dismiss() } }
                ToolbarItem(placement: .topBarTrailing) {
                    Button { isImporterPresented = true } label: { Image(systemName: "plus") }
                        .disabled(!isUnlocked || isBusy)
                }
            }
            .fileImporter(isPresented: $isImporterPresented, allowedContentTypes: [.item], allowsMultipleSelection: true) { result in
                guard case let .success(urls) = result else { return }
                Task { await add(urls) }
            }
            .alert("Brankas", isPresented: Binding(get: { message != nil }, set: { if !$0 { message = nil } })) {
                Button("OK") { message = nil }
            } message: { Text(message ?? "") }
            .confirmationDialog("Hapus berkas dari brankas?", isPresented: Binding(get: { pendingDelete != nil }, set: { if !$0 { pendingDelete = nil } }), titleVisibility: .visible) {
                Button("Hapus", role: .destructive) { if let pendingDelete { remove(pendingDelete) }; pendingDelete = nil }
                Button("Batal", role: .cancel) { pendingDelete = nil }
            }
            .sheet(item: $shareFile, onDismiss: {
                if let shareTemporaryURL { try? FileManager.default.removeItem(at: shareTemporaryURL) }
                shareTemporaryURL = nil
            }) { file in ActivityShareSheet(url: file.url).presentationDetents([.medium, .large]) }
            .task { await unlock() }
        }
    }

    @Environment(\.dismiss) private var dismiss

    private func unlock() async {
        isBusy = true
        let context = LAContext()
        var evaluationError: NSError?
        guard context.canEvaluatePolicy(.deviceOwnerAuthentication, error: &evaluationError) else {
            isBusy = false
            message = evaluationError?.localizedDescription ?? "Autentikasi perangkat tidak tersedia."
            return
        }
        let allowed = await withCheckedContinuation { continuation in
            context.evaluatePolicy(.deviceOwnerAuthentication, localizedReason: "Buka brankas DepUp Office") { success, _ in
                continuation.resume(returning: success)
            }
        }
        guard allowed else { isBusy = false; message = "Autentikasi dibatalkan atau gagal."; return }
        do { items = try NativeVaultStore.items(); isUnlocked = true }
        catch { message = error.localizedDescription }
        isBusy = false
    }

    private func add(_ urls: [URL]) async {
        isBusy = true
        do {
            try NativeVaultStore.add(urls)
            items = try NativeVaultStore.items()
            message = "\(urls.count) berkas berhasil dienkripsi dan disimpan."
        } catch { message = error.localizedDescription }
        isBusy = false
    }

    private func restore(_ item: VaultItem) {
        Task {
            guard await authenticate(reason: "Buka \(item.name)") else { return }
            do {
                let clear = try NativeVaultStore.decrypt(item.url)
                let temp = FileManager.default.temporaryDirectory.appendingPathComponent("\(UUID().uuidString)_\(clear.name)")
                try clear.data.write(to: temp, options: .atomic)
                shareTemporaryURL = temp
                shareFile = ShareFile(url: temp)
            } catch { message = error.localizedDescription }
        }
    }

    private func remove(_ item: VaultItem) {
        Task {
            guard await authenticate(reason: "Hapus \(item.name) dari brankas") else { return }
            do { try FileManager.default.removeItem(at: item.url); items = try NativeVaultStore.items() }
            catch { message = error.localizedDescription }
        }
    }

    private func authenticate(reason: String) async -> Bool {
        let context = LAContext()
        var evaluationError: NSError?
        guard context.canEvaluatePolicy(.deviceOwnerAuthentication, error: &evaluationError) else {
            message = evaluationError?.localizedDescription ?? "Autentikasi perangkat tidak tersedia."
            return false
        }
        return await withCheckedContinuation { continuation in
            context.evaluatePolicy(.deviceOwnerAuthentication, localizedReason: reason) { success, _ in continuation.resume(returning: success) }
        }
    }
}

// MARK: - Fitur setara Android: susun halaman, tanda tangan PDF, pindai QR dari kamera

enum DirectTool: String, Identifiable {
    case invoice, catalog, schedule, notes, searchAll, templates, sheetInvoice, sheetPresensi, sheetKeuangan, signLibrary
    var id: String { rawValue }
}

struct EditorTarget: Identifiable {
    let id = UUID()
    let url: URL
    let kind: String
}

private final class OrganizerPage: Identifiable {
    let id = UUID()
    let page: PDFPage
    let thumbnail: UIImage
    var extraRotation = 0

    init(page: PDFPage) {
        self.page = page
        self.thumbnail = page.thumbnail(of: CGSize(width: 120, height: 160), for: .cropBox)
    }
}

/// Urutkan (seret), hapus (geser), putar per halaman, dan sisipkan halaman kosong A4 — setara "Susun & Putar Halaman" Android.
struct PageOrganizerView: View {
    let url: URL
    let onSave: (Data) -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var pages: [OrganizerPage] = []
    @State private var editMode: EditMode = .active
    @State private var failure: String?
    @State private var refreshToken = 0

    var body: some View {
        NavigationStack {
            List {
                if let failure { Text(failure).foregroundStyle(.red) }
                ForEach(Array(pages.enumerated()), id: \.element.id) { index, item in
                    HStack(spacing: 14) {
                        Image(uiImage: item.thumbnail)
                            .resizable()
                            .scaledToFit()
                            .frame(width: 54, height: 72)
                            .rotationEffect(.degrees(Double(item.extraRotation)))
                            .background(Color(uiColor: .secondarySystemBackground))
                        Text("Halaman \(index + 1)")
                        Spacer()
                        Button {
                            item.extraRotation = (item.extraRotation + 90) % 360
                            refreshToken += 1
                        } label: { Image(systemName: "rotate.right") }
                        .buttonStyle(.borderless)
                        .accessibilityLabel("Putar halaman \(index + 1)")
                    }
                    .id("\(item.id)-\(refreshToken)")
                }
                .onMove { pages.move(fromOffsets: $0, toOffset: $1) }
                .onDelete { pages.remove(atOffsets: $0) }
            }
            .environment(\.editMode, $editMode)
            .navigationTitle("Susun Halaman")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Batal") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) { Button("Simpan") { save() }.disabled(pages.isEmpty) }
                ToolbarItem(placement: .bottomBar) {
                    Button { addBlankPage() } label: { Label("Halaman kosong", systemImage: "doc.badge.plus") }
                }
            }
        }
        .task { load() }
    }

    private func load() {
        guard pages.isEmpty else { return }
        guard let document = PDFDocument(url: url) else { failure = OfficeError.invalidPDF(url.lastPathComponent).localizedDescription; return }
        if document.isLocked { failure = "PDF terkunci kata sandi tidak bisa disusun."; return }
        pages = (0..<document.pageCount).compactMap { document.page(at: $0).map(OrganizerPage.init) }
    }

    private func addBlankPage() {
        let size = pages.first?.page.bounds(for: .mediaBox).size ?? CGSize(width: 595, height: 842)
        let data = UIGraphicsPDFRenderer(bounds: CGRect(origin: .zero, size: size)).pdfData { $0.beginPage() }
        if let page = PDFDocument(data: data)?.page(at: 0) { pages.append(OrganizerPage(page: page)) }
    }

    private func save() {
        let output = PDFDocument()
        for item in pages {
            guard let copy = item.page.copy() as? PDFPage else { continue }
            copy.rotation = (copy.rotation + item.extraRotation) % 360
            output.insert(copy, at: output.pageCount)
        }
        guard output.pageCount > 0, let data = output.dataRepresentation() else { failure = OfficeError.cannotWrite.localizedDescription; return }
        onSave(data)
        dismiss()
    }
}

private struct SignatureCanvas: UIViewRepresentable {
    @Binding var drawing: PKDrawing

    func makeUIView(context: Context) -> PKCanvasView {
        let canvas = PKCanvasView()
        canvas.drawingPolicy = .anyInput
        canvas.tool = PKInkingTool(.pen, color: .black, width: 3)
        canvas.backgroundColor = .white
        canvas.overrideUserInterfaceStyle = .light
        canvas.delegate = context.coordinator
        return canvas
    }

    func updateUIView(_ canvas: PKCanvasView, context: Context) {
        if canvas.drawing.strokes.count != drawing.strokes.count { canvas.drawing = drawing }
    }

    func makeCoordinator() -> Coordinator { Coordinator(self) }

    @MainActor final class Coordinator: NSObject, PKCanvasViewDelegate {
        let parent: SignatureCanvas
        init(_ parent: SignatureCanvas) { self.parent = parent }
        func canvasViewDrawingDidChange(_ canvasView: PKCanvasView) { parent.drawing = canvasView.drawing }
    }
}

/// Tanda tangan tangan di layar, ditempel sebagai anotasi tinta (vektor) pada halaman pilihan — setara SignatureManager Android.
/// Geometri penempatan tanda tangan (murni, diuji di DepupOfficeTests): titik coretan → koordinat halaman PDF,
/// dengan posisi bebas, lebar, putaran, dan memperhitungkan rotasi halaman (0/90/180/270).
enum SignatureGeometry {
    /// Ukuran halaman seperti tampil di layar (lebar/tinggi tertukar untuk rotasi 90/270).
    static func displaySize(crop: CGRect, rotation: Int) -> CGSize {
        let r = ((rotation % 360) + 360) % 360
        return r == 90 || r == 270 ? CGSize(width: crop.height, height: crop.width) : crop.size
    }

    /// Titik ternormalisasi di tampilan (0…1, kiri-atas) → titik halaman PDF (kiri-bawah).
    static func displayToPage(_ n: CGPoint, crop: CGRect, rotation: Int) -> CGPoint {
        let W = crop.width, H = crop.height
        switch ((rotation % 360) + 360) % 360 {
        case 90: return CGPoint(x: crop.minX + n.y * W, y: crop.minY + n.x * H)
        case 180: return CGPoint(x: crop.maxX - n.x * W, y: crop.minY + n.y * H)
        case 270: return CGPoint(x: crop.maxX - n.y * W, y: crop.maxY - n.x * H)
        default: return CGPoint(x: crop.minX + n.x * W, y: crop.maxY - n.y * H)
        }
    }

    /// Vektor di ruang tampilan (y ke bawah, dalam poin) → vektor di ruang halaman.
    static func displayVectorToPage(_ v: CGVector, rotation: Int) -> CGVector {
        switch ((rotation % 360) + 360) % 360 {
        case 90: return CGVector(dx: v.dy, dy: v.dx)
        case 180: return CGVector(dx: -v.dx, dy: v.dy)
        case 270: return CGVector(dx: -v.dy, dy: -v.dx)
        default: return CGVector(dx: v.dx, dy: -v.dy)
        }
    }

    /// Coretan (koordinat kanvas, y ke bawah) → garis-garis di koordinat halaman.
    /// [center] ternormalisasi di tampilan; [widthFraction] terhadap lebar tampilan; [degrees] searah jarum jam.
    static func place(strokes: [[CGPoint]], drawingBounds: CGRect, crop: CGRect, pageRotation: Int,
                      center: CGPoint, widthFraction: CGFloat, degrees: CGFloat) -> [[CGPoint]] {
        guard drawingBounds.width > 0 else { return [] }
        let display = displaySize(crop: crop, rotation: pageRotation)
        let scale = display.width * widthFraction / drawingBounds.width
        let pivot = displayToPage(center, crop: crop, rotation: pageRotation)
        let angle = degrees * .pi / 180
        let c = cos(angle), s = sin(angle)
        return strokes.map { line in
            line.map { p in
                // relatif terhadap pusat coretan, diskalakan, diputar (y ke bawah → searah jarum jam)
                let x = (p.x - drawingBounds.midX) * scale, y = (p.y - drawingBounds.midY) * scale
                let rotated = CGVector(dx: x * c - y * s, dy: x * s + y * c)
                let v = displayVectorToPage(rotated, rotation: pageRotation)
                return CGPoint(x: pivot.x + v.dx, y: pivot.y + v.dy)
            }
        }
    }
}

/// Tanda tangan tangan di layar, ditempel bebas (geser, ukuran, putar, warna) sebagai anotasi tinta vektor —
/// setara "Tanda tangan PDF: Tempel bebas" di Android.
struct SignPDFView: View {
    let url: URL
    let onSave: (Data) -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var drawing = PKDrawing()
    @State private var document: PDFDocument?
    @State private var pageNumber = 1
    @State private var preview: UIImage?
    @State private var center = CGPoint(x: 0.72, y: 0.86)
    @State private var widthFraction: CGFloat = 0.32
    @State private var degrees: Double = 0
    @State private var colorIndex = 0
    @State private var showLibrary = false
    @State private var saved = false
    @State private var failure: String?

    static let colors: [(name: String, color: UIColor)] = [
        ("Hitam", .black), ("Biru", UIColor(red: 0.05, green: 0.2, blue: 0.65, alpha: 1)), ("Merah", UIColor(red: 0.75, green: 0.1, blue: 0.1, alpha: 1))
    ]

    private var pageCount: Int { max(document?.pageCount ?? 1, 1) }

    var body: some View {
        NavigationStack {
            Form {
                Section("Tanda tangan") {
                    SignatureCanvas(drawing: $drawing)
                        .frame(height: 160)
                        .clipShape(RoundedRectangle(cornerRadius: 10))
                        .overlay(RoundedRectangle(cornerRadius: 10).stroke(Color.secondary.opacity(0.3)))
                    HStack {
                        Button("Hapus coretan", role: .destructive) { drawing = PKDrawing(); saved = false }
                            .disabled(drawing.strokes.isEmpty)
                        Spacer()
                        Button("Pustaka") { showLibrary = true }
                        Button("Simpan ke pustaka") { SignatureLibrary.save(drawing); saved = true }
                            .disabled(drawing.strokes.isEmpty || saved)
                    }
                    .buttonStyle(.borderless)
                }
                Section {
                    placement
                    Stepper("Halaman \(pageNumber) dari \(pageCount)", value: $pageNumber, in: 1...pageCount)
                    labeledSlider("Ukuran \(Int(widthFraction * 100))%", value: $widthFraction, range: 0.1...0.8)
                    VStack(alignment: .leading) {
                        Text("Putar \(Int(degrees))°").font(.caption).foregroundStyle(.secondary)
                        Slider(value: $degrees, in: -180...180, step: 1)
                    }
                    Picker("Warna", selection: $colorIndex) {
                        ForEach(Self.colors.indices, id: \.self) { i in Text(Self.colors[i].name).tag(i) }
                    }
                    .pickerStyle(.segmented)
                } header: { Text("Penempatan") } footer: { Text("Geser tanda tangan di pratinjau halaman untuk memindahkannya.") }
                if let failure { Section { Text(failure).foregroundStyle(.red) } }
            }
            .sheet(isPresented: $showLibrary) {
                SignatureLibraryView { picked in drawing = picked; saved = true }
            }
            .navigationTitle("Tanda Tangan PDF")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Batal") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) { Button("Tempel") { save() }.disabled(drawing.strokes.isEmpty) }
            }
        }
        .task {
            if let doc = PDFDocument(url: url), !doc.isLocked {
                document = doc
                pageNumber = max(doc.pageCount, 1)
                renderPreview()
            } else {
                failure = OfficeError.invalidPDF(url.lastPathComponent).localizedDescription
            }
        }
        .onChange(of: pageNumber) { _, _ in renderPreview() }
    }

    private func labeledSlider(_ title: String, value: Binding<CGFloat>, range: ClosedRange<CGFloat>) -> some View {
        VStack(alignment: .leading) {
            Text(title).font(.caption).foregroundStyle(.secondary)
            Slider(value: value, in: range)
        }
    }

    private func renderPreview() {
        guard let page = document?.page(at: pageNumber - 1) else { return }
        preview = ParityEngine.render(page, scale: 1.2)
    }

    @ViewBuilder private var placement: some View {
        if let preview {
            GeometryReader { geo in
                let fit = min(geo.size.width / preview.size.width, geo.size.height / preview.size.height)
                let size = CGSize(width: preview.size.width * fit, height: preview.size.height * fit)
                let origin = CGPoint(x: (geo.size.width - size.width) / 2, y: (geo.size.height - size.height) / 2)
                ZStack(alignment: .topLeading) {
                    Image(uiImage: preview).resizable().frame(width: size.width, height: size.height)
                        .border(Color.gray.opacity(0.4))
                    if !drawing.strokes.isEmpty {
                        let sig = SignatureLibrary.thumbnail(drawing)
                        let w = size.width * widthFraction
                        let h = w * sig.size.height / max(sig.size.width, 1)
                        Image(uiImage: sig).renderingMode(.template).resizable()
                            .foregroundStyle(Color(uiColor: Self.colors[colorIndex].color))
                            .frame(width: w, height: h)
                            .rotationEffect(.degrees(degrees))
                            .overlay(RoundedRectangle(cornerRadius: 4).stroke(Color.depupBlue.opacity(0.7), style: StrokeStyle(lineWidth: 1, dash: [4])))
                            .position(x: center.x * size.width, y: center.y * size.height)
                    }
                }
                .frame(width: size.width, height: size.height)
                .contentShape(Rectangle())
                .gesture(DragGesture(minimumDistance: 0).onChanged { g in
                    center = CGPoint(x: min(1, max(0, g.location.x / size.width)), y: min(1, max(0, g.location.y / size.height)))
                })
                .offset(x: origin.x, y: origin.y)
            }
            .frame(height: 320)
        } else {
            ProgressView().frame(maxWidth: .infinity, minHeight: 120)
        }
    }

    private func save() {
        guard let document, let page = document.page(at: pageNumber - 1) else { failure = OfficeError.invalidPDF(url.lastPathComponent).localizedDescription; return }
        // Thumbnail pratinjau memakai batas coretan + 8 pt; gunakan batas yang sama agar posisi cocok.
        let source = drawing.bounds.insetBy(dx: -8, dy: -8)
        guard drawing.bounds.width > 1 else { failure = "Buat tanda tangan terlebih dahulu."; return }
        let strokes = drawing.strokes.map { stroke in
            stroke.path.interpolatedPoints(in: nil, by: .distance(1)).map { $0.location.applying(stroke.transform) }
        }
        let crop = page.bounds(for: .cropBox)
        let placed = SignatureGeometry.place(strokes: strokes, drawingBounds: source, crop: crop, pageRotation: page.rotation,
                                             center: center, widthFraction: widthFraction, degrees: CGFloat(degrees))
        let all = placed.flatMap { $0 }
        guard let first = all.first else { return }
        let scale = SignatureGeometry.displaySize(crop: crop, rotation: page.rotation).width * widthFraction / source.width
        let lineWidth = max(1.0, 3 * scale)
        var minX = first.x, minY = first.y, maxX = first.x, maxY = first.y
        for p in all { minX = min(minX, p.x); minY = min(minY, p.y); maxX = max(maxX, p.x); maxY = max(maxY, p.y) }
        let bounds = CGRect(x: minX - lineWidth, y: minY - lineWidth, width: maxX - minX + 2 * lineWidth, height: maxY - minY + 2 * lineWidth)
        let annotation = PDFAnnotation(bounds: bounds, forType: .ink, withProperties: nil)
        for line in placed where !line.isEmpty {
            let path = UIBezierPath()
            for (i, p) in line.enumerated() {
                let q = CGPoint(x: p.x - bounds.minX, y: p.y - bounds.minY)
                if i == 0 { path.move(to: q) } else { path.addLine(to: q) }
            }
            annotation.add(path)
        }
        let border = PDFBorder()
        border.lineWidth = lineWidth
        annotation.border = border
        annotation.color = Self.colors[colorIndex].color
        page.addAnnotation(annotation)
        guard let data = document.dataRepresentation() else { failure = OfficeError.cannotWrite.localizedDescription; return }
        onSave(data)
        dismiss()
    }
}

/// Pindai QR/barcode langsung dari kamera (VisionKit) — setara pemindai kamera Android.
struct LiveQRScannerSheet: View {
    let onSave: (String) -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var result: String?

    private var supported: Bool { DataScannerViewController.isSupported && DataScannerViewController.isAvailable }

    var body: some View {
        NavigationStack {
            Group {
                if let result {
                    Form {
                        Section("Hasil") {
                            Text(result).textSelection(.enabled)
                        }
                        Section {
                            Button("Salin") { UIPasteboard.general.string = result }
                            if let link = URL(string: result), let scheme = link.scheme?.lowercased(), scheme == "https" || scheme == "http" {
                                Link("Buka tautan", destination: link)
                            }
                            Button("Simpan sebagai TXT") { onSave(result); dismiss() }
                            Button("Pindai lagi") { self.result = nil }
                        }
                    }
                } else if supported {
                    LiveScanner { self.result = AiText.clean($0) }
                        .ignoresSafeArea(edges: .bottom)
                } else {
                    ContentUnavailableView("Kamera tidak tersedia", systemImage: "camera.metering.unknown",
                                           description: Text("Pemindai langsung membutuhkan kamera dan izin kamera. Gunakan \"Pindai QR / barcode\" dari foto sebagai gantinya."))
                }
            }
            .navigationTitle("Pindai QR")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Tutup") { dismiss() } } }
        }
    }
}

private struct LiveScanner: UIViewControllerRepresentable {
    let onFound: (String) -> Void

    func makeUIViewController(context: Context) -> DataScannerViewController {
        let scanner = DataScannerViewController(recognizedDataTypes: [.barcode()], qualityLevel: .balanced,
                                                recognizesMultipleItems: false, isHighFrameRateTrackingEnabled: false,
                                                isHighlightingEnabled: true)
        scanner.delegate = context.coordinator
        try? scanner.startScanning()
        return scanner
    }

    func updateUIViewController(_ controller: DataScannerViewController, context: Context) {
        if !context.coordinator.done && !controller.isScanning { try? controller.startScanning() }
    }

    static func dismantleUIViewController(_ controller: DataScannerViewController, coordinator: Coordinator) {
        controller.stopScanning()
    }

    func makeCoordinator() -> Coordinator { Coordinator(onFound: onFound) }

    @MainActor final class Coordinator: NSObject, DataScannerViewControllerDelegate {
        let onFound: (String) -> Void
        var done = false
        init(onFound: @escaping (String) -> Void) { self.onFound = onFound }

        func dataScanner(_ dataScanner: DataScannerViewController, didAdd addedItems: [RecognizedItem], allItems: [RecognizedItem]) {
            guard !done else { return }
            for item in addedItems {
                if case let .barcode(code) = item, let value = code.payloadStringValue {
                    done = true
                    dataScanner.stopScanning()
                    onFound(value)
                    return
                }
            }
        }
    }
}
