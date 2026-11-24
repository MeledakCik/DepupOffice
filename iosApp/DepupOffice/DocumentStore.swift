import Foundation
import SwiftUI

struct LocalDocument: Identifiable, Equatable {
    let url: URL
    let modifiedAt: Date

    var id: String { url.path }
    var name: String { url.lastPathComponent }
    var fileExtension: String { url.pathExtension.lowercased() }

    var kind: DocumentKind {
        switch fileExtension {
        case "pdf": .pdf
        case "doc", "docx", "rtf": .word
        case "xls", "xlsx", "csv": .spreadsheet
        case "ppt", "pptx": .presentation
        case "jpg", "jpeg", "png", "heic", "webp": .image
        default: .other
        }
    }
}

enum DocumentKind {
    case pdf, word, spreadsheet, presentation, image, other

    var title: String {
        switch self {
        case .pdf: "PDF"
        case .word: "Word"
        case .spreadsheet: "Excel"
        case .presentation: "PowerPoint"
        case .image: "Gambar"
        case .other: "Berkas"
        }
    }

    var symbol: String {
        switch self {
        case .pdf: "doc.richtext"
        case .word: "doc.text"
        case .spreadsheet: "tablecells"
        case .presentation: "rectangle.on.rectangle"
        case .image: "photo"
        case .other: "doc"
        }
    }

    var color: Color {
        switch self {
        case .pdf: Color(red: 0.69, green: 0.28, blue: 0.18)
        case .word: Color(red: 0.20, green: 0.38, blue: 0.62)
        case .spreadsheet: Color(red: 0.21, green: 0.46, blue: 0.30)
        case .presentation: Color(red: 0.70, green: 0.39, blue: 0.20)
        case .image: Color(red: 0.45, green: 0.33, blue: 0.62)
        case .other: Color.secondary
        }
    }

    var softColor: Color { color.opacity(0.12) }
}

@MainActor
final class DocumentStore: ObservableObject {
    @Published private(set) var documents: [LocalDocument] = []
    @Published var previewURL: URL?
    /// Dokumen Terlindungi DepUp dibuka di penampil aman, bukan Quick Look (yang hanya menampilkan sampul).
    @Published var protectedFile: ProtectedFile?
    @Published var statusMessage: String?

    private var folderURL: URL {
        FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("DepUp Office", isDirectory: true)
    }

    init() {
        refresh()
    }

    func refresh() {
        do {
            try FileManager.default.createDirectory(at: folderURL, withIntermediateDirectories: true)
            let urls = try FileManager.default.contentsOfDirectory(
                at: folderURL,
                includingPropertiesForKeys: [.contentModificationDateKey, .isDirectoryKey],
                options: [.skipsHiddenFiles]
            )
            documents = urls.compactMap { url in
                guard (try? url.resourceValues(forKeys: [.isDirectoryKey]).isDirectory) != true else { return nil }
                let date = (try? url.resourceValues(forKeys: [.contentModificationDateKey]).contentModificationDate) ?? nil
                return LocalDocument(url: url, modifiedAt: date ?? .distantPast)
            }.sorted { $0.modifiedAt > $1.modifiedAt }
        } catch {
            statusMessage = "Folder dokumen belum bisa dibuka: \(error.localizedDescription)"
        }
    }

    func open(_ document: LocalDocument) {
        if document.kind == .pdf, ProtectedDocReader.isProtected(document.url) {
            protectedFile = ProtectedFile(url: document.url)
        } else {
            previewURL = document.url
        }
    }

    func makeShareCopy(from source: URL) throws -> URL {
        let hasAccess = source.startAccessingSecurityScopedResource()
        defer { if hasAccess { source.stopAccessingSecurityScopedResource() } }
        let temporaryFolder = FileManager.default.temporaryDirectory.appendingPathComponent("DepUpShare", isDirectory: true)
        try FileManager.default.createDirectory(at: temporaryFolder, withIntermediateDirectories: true)
        let destination = Self.uniqueDestination(for: source, in: temporaryFolder)
        try FileManager.default.copyItem(at: source, to: destination)
        return destination
    }

    func saveWordDraft(name: String, text: String) {
        let escaped = text
            .replacingOccurrences(of: "\\", with: "\\\\")
            .replacingOccurrences(of: "{", with: "\\{")
            .replacingOccurrences(of: "}", with: "\\}")
            .replacingOccurrences(of: "\n", with: "\\par\n")
        let rtf = "{\\rtf1\\ansi\\deff0 {\\fonttbl{\\f0 Helvetica;}}\\f0\\fs24 \(escaped)}"
        saveGeneratedFile(Data(rtf.utf8), name: name, extension: "rtf")
    }

    func saveSpreadsheetDraft(name: String, text: String) {
        saveGeneratedFile(Data(text.utf8), name: name, extension: "csv")
    }

    func savePDF(_ data: Data, name: String) {
        saveGeneratedFile(data, name: name, extension: "pdf")
    }

    func saveGeneratedData(_ data: Data, name: String) {
        saveGeneratedFile(data, name: name, extension: "png")
    }

    private func saveGeneratedFile(_ data: Data, name: String, extension fileExtension: String) {
        do {
            try FileManager.default.createDirectory(at: folderURL, withIntermediateDirectories: true)
            let safeName = name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? "Dokumen Baru" : name
            let file = folderURL.appendingPathComponent(safeName).appendingPathExtension(fileExtension)
            let destination = Self.uniqueDestination(for: file, in: folderURL)
            try data.write(to: destination, options: .atomic)
            refresh()
            statusMessage = "Tersimpan: \(destination.lastPathComponent)."
        } catch {
            statusMessage = "Dokumen tidak bisa disimpan: \(error.localizedDescription)"
        }
    }

    func importFiles(from sourceURLs: [URL]) {
        let destinationFolder = folderURL
        Task.detached(priority: .userInitiated) { [weak self] in
            do {
                try FileManager.default.createDirectory(at: destinationFolder, withIntermediateDirectories: true)
                for sourceURL in sourceURLs {
                    let hasAccess = sourceURL.startAccessingSecurityScopedResource()
                    defer {
                        if hasAccess { sourceURL.stopAccessingSecurityScopedResource() }
                    }
                    let destinationURL = Self.uniqueDestination(for: sourceURL, in: destinationFolder)
                    try FileManager.default.copyItem(at: sourceURL, to: destinationURL)
                }
                await self?.refresh()
                await self?.setStatus("Berkas berhasil disimpan di DepUp Office.")
            } catch {
                await self?.setStatus("Berkas tidak bisa diimpor: \(error.localizedDescription)")
            }
        }
    }

    func saveScannedPDF(_ data: Data) {
        let destinationFolder = folderURL
        Task.detached(priority: .userInitiated) { [weak self] in
            do {
                try FileManager.default.createDirectory(at: destinationFolder, withIntermediateDirectories: true)
                let formatter = DateFormatter()
                formatter.dateFormat = "yyyyMMdd_HHmmss"
                let fileURL = destinationFolder.appendingPathComponent("Scan_\(formatter.string(from: Date())).pdf")
                try data.write(to: fileURL, options: .atomic)
                await self?.refresh()
                await self?.setStatus("Hasil scan tersimpan sebagai \(fileURL.lastPathComponent).")
            } catch {
                await self?.setStatus("Hasil scan tidak bisa disimpan: \(error.localizedDescription)")
            }
        }
    }

    func runTool(_ tool: String, urls: [URL], options: [String: String] = [:]) {
        guard !urls.isEmpty else { return }
        statusMessage = "Memproses berkas…"
        Task { [weak self] in
            do {
                let results = try await Task.detached(priority: .userInitiated) {
                    var started: [URL] = []
                    for url in urls where url.startAccessingSecurityScopedResource() { started.append(url) }
                    defer { started.forEach { $0.stopAccessingSecurityScopedResource() } }
                    return try NativeOfficeEngine.run(tool: tool, urls: urls, options: options)
                }.value
                guard let self else { return }
                try FileManager.default.createDirectory(at: self.folderURL, withIntermediateDirectories: true)
                for result in results {
                    let output = Self.uniqueDestination(for: URL(fileURLWithPath: result.name), in: self.folderURL)
                    try result.data.write(to: output, options: .atomic)
                }
                self.refresh()
                self.statusMessage = results.count == 1
                    ? "Selesai: \(results[0].name) tersimpan di DepUp Office."
                    : "Selesai: \(results.count) berkas tersimpan di DepUp Office."
                if tool == "secureDoc", let code = options["accessCode"] {
                    self.statusMessage = (self.statusMessage ?? "") +
                        "\n\nKode akses: \(SecurePackage.groupedCode(code))\nKode sudah disalin. Bagikan ke pembaca lewat jalur terpisah; kode tidak bisa dipulihkan."
                }
            } catch {
                self?.statusMessage = error.localizedDescription
            }
        }
    }

    private func setStatus(_ message: String) {
        statusMessage = message
    }

    nonisolated private static func uniqueDestination(for source: URL, in folder: URL) -> URL {
        let baseName = source.deletingPathExtension().lastPathComponent
        let ext = source.pathExtension
        var candidate = folder.appendingPathComponent(source.lastPathComponent)
        var suffix = 2
        while FileManager.default.fileExists(atPath: candidate.path) {
            let name = ext.isEmpty ? "\(baseName) (\(suffix))" : "\(baseName) (\(suffix)).\(ext)"
            candidate = folder.appendingPathComponent(name)
            suffix += 1
        }
        return candidate
    }
}
