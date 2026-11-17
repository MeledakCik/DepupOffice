import PDFKit
import CoreImage.CIFilterBuiltins
import CryptoKit
import LocalAuthentication
import Security
import SwiftUI
import UIKit
import UniformTypeIdentifiers
import Vision

struct GeneratedDocument: Identifiable {
    let id = UUID()
    let name: String
    let data: Data
}

enum NativeOfficeEngine {
    static func run(tool: String, urls: [URL], options: [String: String]) throws -> [GeneratedDocument] {
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
        let request = VNDetectBarcodesRequest()
        request.symbologies = [.qr, .ean13, .ean8, .code128, .pdf417, .aztec]
        try VNImageRequestHandler(url: url).perform([request])
        let results = (request.results ?? []).compactMap { item -> String? in
            guard let value = item.payloadStringValue else { return nil }
            return "\(item.symbology.rawValue): \(value)"
        }
        let text = results.isEmpty ? "Kode tidak ditemukan di \(url.lastPathComponent)." : results.joined(separator: "\n")
        return GeneratedDocument(name: "Hasil_kode_\(url.deletingPathExtension().lastPathComponent).txt", data: Data(text.utf8))
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
        .init(id: "imagePDF", title: "Foto ke PDF", detail: "Gabungkan satu atau beberapa foto ke PDF", symbol: "photo.badge.plus", group: "Konversi", multiple: true, imagesOnly: true),
        .init(id: "ocr", title: "OCR dokumen", detail: "Kenali teks pada hasil scan dan ekspor ke TXT", symbol: "text.viewfinder", group: "Konversi"),
        .init(id: "extractText", title: "Ekstrak teks PDF", detail: "Simpan teks PDF ke berkas TXT", symbol: "doc.text.magnifyingglass", group: "Konversi"),
        .init(id: "pdfToWord", title: "PDF ke Word", detail: "Ekspor teks PDF ke format RTF yang bisa dibuka Word", symbol: "doc.text", group: "Konversi"),
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

    var body: some View {
        List {
            Section {
                Label("Pemrosesan berkas dilakukan di perangkat ini.", systemImage: "iphone.and.arrow.forward")
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
            }
            ForEach(["Kelola PDF", "Konversi", "Gambar", "Dokumen", "Keamanan", "Bagikan & ekspor"], id: \.self) { group in
                Section(group) {
                    ForEach(OfficeTool.all.filter { $0.group == group }) { tool in
                        Button { selectedTool = tool } label: {
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
                WordDraftEditor { name, text in documents.saveWordDraft(name: name, text: text) }
            } else {
                SpreadsheetDraftEditor { name, text in documents.saveSpreadsheetDraft(name: name, text: text) }
            }
        }
        .sheet(isPresented: $isQRGeneratorPresented) {
            QRCodeGeneratorSheet { name, data in documents.saveGeneratedData(data, name: name) }
                .presentationDetents([.medium, .large])
        }
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Menu {
                    Button { draftKind = .word } label: { Label("Dokumen Word (RTF)", systemImage: "doc.text") }
                    Button { draftKind = .spreadsheet } label: { Label("Spreadsheet Excel (CSV)", systemImage: "tablecells") }
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
        if tool.id == "share" { return [.item] }
        return [.pdf]
    }

    private func handlePicked(_ urls: [URL]) {
        guard let tool = importTool else { return }
        defer { importTool = nil }
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
