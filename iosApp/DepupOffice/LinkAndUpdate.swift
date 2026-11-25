import SwiftUI
import UIKit

// MARK: - Buka dari link (padanan ShareReceiverActivity/LinkFetch Android)

/// Unduh berkas dari link (Google Drive/Docs, share.google, LMS). DepUp tetap offline: internet hanya dipakai saat
/// pengguna sendiri menempelkan link lalu menekan "Unduh & buka".
enum LinkFetch {
    static let maxBytes = 100 << 20
    static let needLogin = "Link ini tidak langsung ke berkas PDF/Office (mungkin perlu login, mis. LMS). " +
        "Buka link di Safari, unduh berkasnya, lalu bagikan berkas itu ke DepUp Office."

    enum Kind: String { case pdf, docx, xlsx, pptx }

    struct LinkError: LocalizedError { let message: String; var errorDescription: String? { message } }

    /// Link https pertama di teks ("Judul https://…").
    static func extractURL(_ text: String) -> String? {
        guard let r = text.range(of: #"https://[^\s<>"'`]+"#, options: .regularExpression) else { return nil }
        var s = String(text[r])
        while let last = s.last, ".,)]};!?".contains(last) { s.removeLast() }
        return s
    }

    static func title(of text: String, url: String) -> String {
        text.replacingOccurrences(of: url, with: " ")
            .components(separatedBy: .newlines)
            .map { $0.trimmingCharacters(in: .whitespaces) }
            .first { !$0.isEmpty && !$0.hasPrefix("http") } ?? ""
    }

    private static func firstGroup(_ pattern: String, _ s: String, group: Int = 1) -> String? {
        guard let re = try? NSRegularExpression(pattern: pattern),
              let m = re.firstMatch(in: s, range: NSRange(s.startIndex..., in: s)),
              let r = Range(m.range(at: group), in: s) else { return nil }
        return String(s[r])
    }

    /// Halaman pratinjau Google → alamat unduhan langsung.
    static func directURL(_ url: String) -> String {
        if let id = firstGroup(#"^https://drive\.google\.com/(?:file/d/|open\?id=|uc\?(?:.*&)?id=)([A-Za-z0-9_-]{10,})"#, url) {
            return "https://drive.usercontent.google.com/download?id=\(id)&export=download&confirm=t"
        }
        let docs = #"^https://docs\.google\.com/(document|spreadsheets|presentation)/d/([A-Za-z0-9_-]{10,})"#
        if let type = firstGroup(docs, url), let id = firstGroup(docs, url, group: 2) {
            return type == "presentation"
                ? "https://docs.google.com/presentation/d/\(id)/export/pdf"
                : "https://docs.google.com/\(type)/d/\(id)/export?format=pdf"
        }
        return url
    }

    /// Jenis dari isi berkas (bukan header server).
    static func sniff(_ data: Data, nameHint: String) -> Kind? {
        let head = String(decoding: data.prefix(1024), as: UTF8.self)
        if head.contains("%PDF-") { return .pdf }
        let bytes = [UInt8](data.prefix(4))
        if bytes == [0x50, 0x4B, 3, 4] {
            let zipHead = String(decoding: data.prefix(64 * 1024).map { $0 < 128 ? $0 : 0x20 }, as: UTF8.self)
            if zipHead.contains("word/") { return .docx }
            if zipHead.contains("xl/") { return .xlsx }
            if zipHead.contains("ppt/") { return .pptx }
            return Kind(rawValue: (nameHint as NSString).pathExtension.lowercased())
        }
        return nil
    }

    static func fileName(disposition: String?, url: String, title: String) -> String {
        var header: String?
        if let d = disposition {
            if let v = firstGroup(#"(?i)filename\*=(?:UTF-8'')?([^;]+)"#, d) {
                header = v.trimmingCharacters(in: CharacterSet(charactersIn: "\" ")).removingPercentEncoding
            } else {
                header = firstGroup(#"(?i)filename="?([^";]+)"?"#, d)
            }
        }
        let fromURL = URL(string: url)?.lastPathComponent.removingPercentEncoding
        let skip: Set<String> = ["download", "export", "pdf", "view", "/"]
        let raw = [header, title.contains(".") ? title : nil, fromURL, title]
            .compactMap { $0 }.first { !$0.isEmpty && !skip.contains($0) } ?? "dokumen"
        let cleaned = raw.replacingOccurrences(of: #"[\\/:*?"<>|\x00-\x1F]+"#, with: "_", options: .regularExpression)
            .trimmingCharacters(in: .whitespaces)
        return cleaned.isEmpty ? "dokumen" : String(cleaned.prefix(120))
    }

    private final class NoRedirect: NSObject, URLSessionTaskDelegate {
        func urlSession(_ session: URLSession, task: URLSessionTask, willPerformHTTPRedirection response: HTTPURLResponse,
                        newRequest request: URLRequest, completionHandler: @escaping (URLRequest?) -> Void) {
            completionHandler(nil)   // pengalihan ditangani manual (cek https + ubah alamat Google)
        }
    }

    /// Unduh, lalu kembalikan berkas sementara bernama rapi.
    static func download(_ link: String, title: String) async throws -> URL {
        var url = directURL(link)
        guard url.hasPrefix("https://") else { throw LinkError(message: "Hanya link https yang bisa dibuka.") }
        let config = URLSessionConfiguration.ephemeral   // tanpa cookie/cache tersimpan
        config.timeoutIntervalForRequest = 30
        let session = URLSession(configuration: config)
        defer { session.finishTasksAndInvalidate() }
        for _ in 0..<8 {
            guard let u = URL(string: url) else { throw LinkError(message: "Link tidak valid.") }
            var req = URLRequest(url: u)
            req.setValue("application/pdf,application/octet-stream,*/*;q=0.8", forHTTPHeaderField: "Accept")
            let (data, response) = try await session.data(for: req, delegate: NoRedirect())
            guard let http = response as? HTTPURLResponse else { throw LinkError(message: "Jawaban server tidak dikenal.") }
            switch http.statusCode {
            case 300..<400:
                guard let loc = http.value(forHTTPHeaderField: "Location"), let next = URL(string: loc, relativeTo: u)?.absoluteString
                else { throw LinkError(message: "Pengalihan tanpa alamat tujuan.") }
                guard next.hasPrefix("https://") else { throw LinkError(message: "Link mengarah ke alamat tidak aman (bukan https).") }
                url = directURL(next)
                continue
            case 401, 403: throw LinkError(message: needLogin)
            case 200..<300: break
            default: throw LinkError(message: "Server menjawab \(http.statusCode). Coba buka link di Safari.")
            }
            guard data.count <= maxBytes else { throw LinkError(message: "Berkas lebih dari \(maxBytes >> 20) MB.") }
            let name = fileName(disposition: http.value(forHTTPHeaderField: "Content-Disposition"), url: url, title: title)
            guard let kind = sniff(data, nameHint: name) else { throw LinkError(message: needLogin) }
            let base = (name as NSString).deletingPathExtension
            let dir = FileManager.default.temporaryDirectory.appendingPathComponent("link-\(UUID().uuidString)", isDirectory: true)
            try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
            let file = dir.appendingPathComponent(base.isEmpty ? "dokumen" : base).appendingPathExtension(kind.rawValue)
            try data.write(to: file, options: .atomic)
            return file
        }
        throw LinkError(message: "Terlalu banyak pengalihan.")
    }
}

/// Lembar "Buka dari link": tempel link (PasteButton tanpa dialog izin tempel), unduh sekali, simpan & buka.
struct LinkImportSheet: View {
    @EnvironmentObject private var documents: DocumentStore
    @Environment(\.dismiss) private var dismiss
    @State private var text = ""
    @State private var busy = false
    @State private var error: String?

    private var url: String? { LinkFetch.extractURL(text) }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    TextField("https://…", text: $text, axis: .vertical)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                        .keyboardType(.URL)
                        .lineLimit(1...4)
                        .accessibilityIdentifier("linkField")
                    PasteButton(payloadType: String.self) { strings in
                        if let s = strings.first { text = s }
                    }
                } header: { Text("Link berkas") } footer: {
                    Text("Mendukung link Google Drive, Google Docs/Sheets/Slides, share.google, dan link PDF langsung. DepUp Office bekerja offline; internet hanya dipakai sekarang untuk mengunduh berkas ini.")
                }
                if let error {
                    Section { Text(error).foregroundStyle(.red) }
                }
                Section {
                    Button {
                        Task { await fetch() }
                    } label: {
                        HStack {
                            Text(busy ? "Mengunduh…" : "Unduh & buka")
                            Spacer()
                            if busy { ProgressView() }
                        }
                    }
                    .disabled(url == nil || busy)
                }
            }
            .navigationTitle("Buka dari link")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Batal") { dismiss() } } }
        }
    }

    @MainActor private func fetch() async {
        guard let url else { return }
        busy = true; error = nil
        defer { busy = false }
        do {
            let file = try await LinkFetch.download(url, title: LinkFetch.title(of: text, url: url))
            documents.importAndOpen(file)
            dismiss()
        } catch let e as URLError where e.code == .notConnectedToInternet {
            error = "Tidak ada koneksi internet."
        } catch let e as URLError where e.code == .timedOut {
            error = "Koneksi terlalu lambat. Coba lagi."
        } catch {
            self.error = error.localizedDescription
        }
    }
}

// MARK: - Pembaruan wajib (padanan ForceUpdate Android)

/// Bandingkan versi aplikasi dengan App Store (iTunes Lookup). Hanya mengirim bundle id ke Apple; tanpa data pengguna.
@MainActor final class AppStoreUpdate: ObservableObject {
    struct Required: Identifiable { let id = UUID(); let version: String; let url: URL }
    @Published var required: Required?

    /// "4.10.0" > "4.9.2" (dibandingkan per angka).
    nonisolated static func isNewer(_ store: String, than current: String) -> Bool {
        let a = store.split(separator: ".").map { Int($0) ?? 0 }
        let b = current.split(separator: ".").map { Int($0) ?? 0 }
        for i in 0..<max(a.count, b.count) {
            let x = i < a.count ? a[i] : 0, y = i < b.count ? b[i] : 0
            if x != y { return x > y }
        }
        return false
    }

    func check() async {
        #if DEBUG
        return
        #else
        guard let bundle = Bundle.main.bundleIdentifier,
              let current = Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String,
              let lookup = URL(string: "https://itunes.apple.com/lookup?bundleId=\(bundle)&country=id") else { return }
        var req = URLRequest(url: lookup, cachePolicy: .reloadIgnoringLocalCacheData, timeoutInterval: 10)
        req.setValue("application/json", forHTTPHeaderField: "Accept")
        guard let (data, _) = try? await URLSession(configuration: .ephemeral).data(for: req),
              let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let first = (json["results"] as? [[String: Any]])?.first,
              let version = first["version"] as? String,
              let link = (first["trackViewUrl"] as? String).flatMap(URL.init(string:)) else { return }
        required = Self.isNewer(version, than: current) ? Required(version: version, url: link) : nil
        #endif
    }
}

/// Layar penuh yang tidak bisa ditutup sampai aplikasi diperbarui.
struct RequiredUpdateView: View {
    let info: AppStoreUpdate.Required
    @Environment(\.openURL) private var openURL

    var body: some View {
        VStack(spacing: 18) {
            Spacer()
            Image(systemName: "arrow.down.app.fill")
                .font(.system(size: 64))
                .foregroundStyle(Color.accentColor)
            Text("Pembaruan wajib").font(.title2.bold())
            Text("DepUp Office versi \(info.version) sudah tersedia. Perbarui dulu agar fitur dan keamanan terbaru dapat dipakai.")
                .multilineTextAlignment(.center)
                .foregroundStyle(.secondary)
                .padding(.horizontal, 32)
            Button { openURL(info.url) } label: {
                Text("Perbarui di App Store").frame(maxWidth: .infinity)
            }
            .buttonStyle(.borderedProminent)
            .controlSize(.large)
            .padding(.horizontal, 32)
            Spacer()
        }
        .interactiveDismissDisabled()
    }
}
