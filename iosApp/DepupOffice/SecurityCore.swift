import CryptoKit
import Foundation
import MachO
import PDFKit
import SwiftUI
import UIKit

// MARK: - Rahasia ter-obfuskasi (XOR split) — identik dengan Sx.kt di Android

/// Rahasia asli tidak pernah tersimpan utuh di biner: byte = A[i] ^ B[i] ^ mask(salt, i).
enum SecretBox {
    private static let ka: [UInt8] = [0x23, 0xA4, 0x0E, 0x39, 0x64, 0xB2, 0x8E, 0x1B, 0xEC, 0x68, 0x31, 0x14, 0x3F, 0x7F, 0x9D, 0xB8, 0xB8, 0x8D, 0x78, 0x8A, 0x19, 0x8F, 0x50, 0x20, 0xB3, 0xD2, 0x82, 0x2C, 0xE2, 0x78, 0x30, 0x45]
    private static let kb: [UInt8] = [0x44, 0xE7, 0x09, 0x90, 0xAA, 0x06, 0x22, 0x2F, 0xDE, 0x4E, 0xA8, 0xD9, 0xC2, 0x93, 0xFE, 0xB5, 0xBE, 0xF0, 0xA7, 0x56, 0xF6, 0x68, 0x3B, 0x3F, 0xE1, 0xF4, 0x7A, 0xAA, 0x6E, 0x9A, 0x49, 0x2A]
    private static let pa: [UInt8] = [0xA8, 0x8A, 0x16, 0x13, 0xEF, 0x4F, 0x9F, 0x63, 0xB4, 0x59, 0xE0, 0xC1, 0xE1, 0xE0, 0xE0, 0xFF]
    private static let pb: [UInt8] = [0xBD, 0x9F, 0xE9, 0xE8, 0x4B, 0xEE, 0xE8, 0x33, 0x87, 0x64, 0x10, 0x0C, 0x01, 0x6F, 0xCA, 0xF2]

    private static func mask(_ salt: Int, _ i: Int) -> UInt8 { UInt8((salt + i * 31 + (i >> 2) * 7) & 0xFF) }
    private static func open(_ a: [UInt8], _ b: [UInt8], _ salt: Int) -> [UInt8] {
        (0..<a.count).map { a[$0] ^ b[$0] ^ mask(salt, $0) }
    }

    static func appSecret() -> [UInt8] { open(ka, kb, 0x3D) }
    static func readKeyPrefix() -> String { String(decoding: open(pa, pb, 0x51), as: UTF8.self) }
}

/// HONEYPOT: tampak seperti kunci produksi, sengaja mudah ditemukan. Tidak pernah dipakai mengenkripsi dokumen asli.
enum MasterKeys {
    static let aesMasterKey: [UInt8] = [0x89, 0xC2, 0x0F, 0x40, 0xB8, 0xC0, 0x63, 0x6A, 0xA2, 0x53, 0x99, 0x92, 0x96, 0xD4, 0xE4, 0x23,
                                        0xCA, 0x4D, 0xD4, 0x2E, 0x13, 0x5B, 0xF7, 0x82, 0x2F, 0x38, 0xF7, 0x6D, 0xD2, 0x6B, 0xED, 0xB4]
    static let readKeySalt = "DepUpMasterKey:v3:"
    static let licenseApiKey = "dpk_live_7Q2xN9fV4mK8sT1wR6yB3hJ5"
}

enum Honeypot {
    private(set) static var tripped = false

    static func decoyKey(docId: String) -> SymmetricKey {
        let mac = HMAC<SHA256>.authenticationCode(for: Data((MasterKeys.readKeySalt + docId).utf8),
                                                  using: SymmetricKey(data: MasterKeys.aesMasterKey))
        return SymmetricKey(data: Data(mac))
    }

    static func trip(_ reason: String) {
        tripped = true
        let d = UserDefaults.standard
        d.set(d.integer(forKey: "sec.hp.count") + 1, forKey: "sec.hp.count")
        d.set(Date().timeIntervalSince1970, forKey: "sec.hp.last")
        d.set(String(reason.prefix(64)), forKey: "sec.hp.reason")
    }
}

// MARK: - Integritas runtime (anti-inject)

enum RuntimeIntegrity {
    private static let suspiciousLibs = ["frida", "fridagadget", "cynject", "libcycript", "substrate", "substitute",
                                         "tweakinject", "libhooker", "sslkillswitch", "ellekit", "shadow.dylib"]
    private static let jailbreakPaths = ["/Applications/Cydia.app", "/Applications/Sileo.app", "/var/jb",
                                         "/usr/sbin/frida-server", "/Library/MobileSubstrate/MobileSubstrate.dylib",
                                         "/usr/lib/libsubstitute.dylib", "/private/var/lib/apt", "/usr/libexec/cydia"]

    /// Temuan; kosong = aman.
    static func findings() -> [String] {
        var f: [String] = []
        #if !DEBUG
        if debuggerAttached() { f.append("debugger") }
        #endif
        if injectedLibrary() { f.append("hook") }
        if getenv("DYLD_INSERT_LIBRARIES") != nil { f.append("dyld-insert") }
        #if !targetEnvironment(simulator)
        if jailbroken() { f.append("jailbreak") }
        #endif
        return f
    }

    static var compromised: Bool { !findings().isEmpty }

    static func describe(_ f: [String]) -> String {
        f.map {
            switch $0 {
            case "debugger": return "debugger terpasang"
            case "hook", "dyld-insert": return "alat injeksi/hook (Frida/Substrate) aktif"
            case "jailbreak": return "perangkat di-jailbreak"
            default: return $0
            }
        }.joined(separator: ", ")
    }

    private static func debuggerAttached() -> Bool {
        var info = kinfo_proc()
        var size = MemoryLayout<kinfo_proc>.stride
        var mib: [Int32] = [CTL_KERN, KERN_PROC, KERN_PROC_PID, getpid()]
        let r = sysctl(&mib, u_int(mib.count), &info, &size, nil, 0)
        return r == 0 && (info.kp_proc.p_flag & P_TRACED) != 0
    }

    private static func injectedLibrary() -> Bool {
        for i in 0..<_dyld_image_count() {
            guard let c = _dyld_get_image_name(i) else { continue }
            let name = String(cString: c).lowercased()
            if suspiciousLibs.contains(where: { name.contains($0) }) { return true }
        }
        return false
    }

    private static func jailbroken() -> Bool {
        if jailbreakPaths.contains(where: { FileManager.default.fileExists(atPath: $0) }) { return true }
        // Aplikasi yang tersandbox tidak boleh bisa menulis di luar kontainernya.
        let probe = "/private/depup_jb_probe.txt"
        if (try? "x".write(toFile: probe, atomically: true, encoding: .utf8)) != nil {
            try? FileManager.default.removeItem(atPath: probe)
            return true
        }
        return false
    }
}

// MARK: - Pembersih teks anti prompt-injection AI

enum AiText {
    private static func invisible(_ v: UInt32) -> Bool {
        (0x200B...0x200F).contains(v) || (0x202A...0x202E).contains(v) || (0x2060...0x2064).contains(v) ||
        (0x2066...0x2069).contains(v) || v == 0xFEFF || v == 0x00AD || (0xE0000...0xE007F).contains(v) ||
        (0xFE00...0xFE0F).contains(v) || (0xE0100...0xE01EF).contains(v)
    }

    /// Hapus karakter tersembunyi (zero-width, bidi, Unicode TAG / "ASCII smuggling").
    static func clean(_ s: String) -> String {
        var out = String.UnicodeScalarView()
        for u in s.unicodeScalars {
            let v = u.value
            if invisible(v) { continue }
            if u.properties.generalCategory == .control && u != "\n" && u != "\t" && u != "\r" { continue }
            out.append(u)
        }
        return String(out)
    }
}

// MARK: - Dokumen Terlindungi (format sama dengan ProtectedDoc.kt di Android)

enum ProtectedDocError: LocalizedError {
    case notProtected, corrupt, tampered, forged, unsafe(String)
    var errorDescription: String? {
        switch self {
        case .notProtected: "Bukan Dokumen Terlindungi DepUp Office."
        case .corrupt: "Data paket terlindungi rusak."
        case .tampered: "Dokumen telah dimodifikasi di luar DepUp Office atau integritas gagal diverifikasi."
        case .forged: "Dokumen palsu terdeteksi: dibuat dengan kunci yang dicuri dari aplikasi, bukan oleh DepUp Office."
        case .unsafe(let why): "Lingkungan tidak aman (\(why)). Dokumen terlindungi tidak dibuka."
        }
    }
}

enum ProtectedDocReader {
    private static let marker = "DEPUP_SECURE_DOC_v1"

    static func payload(_ data: Data) -> Data? {
        guard let provider = CGDataProvider(data: data as CFData),
              let doc = CGPDFDocument(provider), let catalog = doc.catalog else { return nil }
        var streamRef: CGPDFStreamRef?
        guard CGPDFDictionaryGetStream(catalog, "DepupSecureData", &streamRef), let stream = streamRef else { return nil }
        var format = CGPDFDataFormat.raw
        guard let cf = CGPDFStreamCopyData(stream, &format) else { return nil }
        return cf as Data
    }

    static func isProtected(_ url: URL) -> Bool {
        guard url.pathExtension.lowercased() == "pdf", let data = try? Data(contentsOf: url, options: .mappedIfSafe) else { return false }
        return payload(data) != nil
    }

    private struct Meta { let docId: String; let sha256: String; let salt: [UInt8]; let nonce: [UInt8]; let end: Int }

    private static func parseMeta(_ b: [UInt8]) throws -> Meta {
        guard b.count > marker.utf8.count, Array(b[0..<marker.utf8.count]) == Array(marker.utf8) else { throw ProtectedDocError.corrupt }
        var off = marker.utf8.count
        func field() throws -> [UInt8] {
            guard off + 4 <= b.count else { throw ProtectedDocError.corrupt }
            let n = Int(b[off]) << 24 | Int(b[off + 1]) << 16 | Int(b[off + 2]) << 8 | Int(b[off + 3])
            off += 4
            guard n >= 0, off + n <= b.count else { throw ProtectedDocError.corrupt }
            defer { off += n }
            return Array(b[off..<off + n])
        }
        let docId = String(decoding: try field(), as: UTF8.self)
        let sha = String(decoding: try field(), as: UTF8.self)
        let salt = try field()
        let nonce = try field()
        let pass = try field()
        off += 1 // editLocked
        guard UUID(uuidString: docId) != nil, sha.count == 64, salt.count == 16, nonce.count == 12, pass.count <= 128 else {
            throw ProtectedDocError.corrupt
        }
        return Meta(docId: docId, sha256: sha, salt: salt, nonce: nonce, end: off)
    }

    private static func realKey(docId: String) -> SymmetricKey {
        var secret = SecretBox.appSecret()
        defer { for i in secret.indices { secret[i] = 0 } }
        let mac = HMAC<SHA256>.authenticationCode(for: Data((SecretBox.readKeyPrefix() + docId).utf8),
                                                  using: SymmetricKey(data: secret))
        return SymmetricKey(data: Data(mac))
    }

    /// Verifikasi HMAC, dekripsi AES-256-GCM, cek SHA-256 → byte PDF asli.
    static func open(_ data: Data) throws -> Data {
        let f = RuntimeIntegrity.findings()
        guard f.isEmpty else { throw ProtectedDocError.unsafe(RuntimeIntegrity.describe(f)) }
        guard let pkg = payload(data) else { throw ProtectedDocError.notProtected }
        let all = [UInt8](pkg)
        guard all.count > 32 else { throw ProtectedDocError.corrupt }
        let body = Data(all[0..<(all.count - 32)])
        let tag = Data(all[(all.count - 32)...])
        let meta = try parseMeta([UInt8](body))

        let key = realKey(docId: meta.docId)
        guard HMAC<SHA256>.isValidAuthenticationCode(tag, authenticating: body, using: key) else {
            if HMAC<SHA256>.isValidAuthenticationCode(tag, authenticating: body, using: Honeypot.decoyKey(docId: meta.docId)) {
                Honeypot.trip("forged-doc")
                throw ProtectedDocError.forged
            }
            throw ProtectedDocError.tampered
        }
        let b = [UInt8](body)
        guard meta.end + 4 <= b.count else { throw ProtectedDocError.corrupt }
        let len = Int(b[meta.end]) << 24 | Int(b[meta.end + 1]) << 16 | Int(b[meta.end + 2]) << 8 | Int(b[meta.end + 3])
        let start = meta.end + 4
        guard len >= 16, start + len <= b.count else { throw ProtectedDocError.corrupt }
        let enc = Array(b[start..<start + len])
        let box = try AES.GCM.SealedBox(nonce: AES.GCM.Nonce(data: meta.nonce),
                                        ciphertext: enc[0..<(enc.count - 16)], tag: enc[(enc.count - 16)...])
        let plain = try AES.GCM.open(box, using: key)
        let digest = SHA256.hash(data: plain).map { String(format: "%02x", $0) }.joined()
        guard digest == meta.sha256 else { throw ProtectedDocError.tampered }
        return plain
    }
}

// MARK: - Penampil aman (anti tangkapan layar / rekam / salin / AI)

/// PDFView tanpa salin, bagikan, cari di web, atau "Look Up" (yang dapat meneruskan teks ke layanan AI).
final class LockedPDFView: PDFView {
    override func canPerformAction(_ action: Selector, withSender sender: Any?) -> Bool { false }
    override func buildMenu(with builder: UIMenuBuilder) {
        super.buildMenu(with: builder)
        builder.remove(menu: .lookup)
        builder.remove(menu: .share)
        builder.remove(menu: .standardEdit)
    }
}

/// Field aman yang tidak pernah memunculkan keyboard; kanvasnya disembunyikan iOS dari tangkapan layar & rekaman.
private final class ShieldField: UITextField {
    override var canBecomeFirstResponder: Bool { false }
}

final class ScreenShieldView: UIView {
    private let field = ShieldField()

    init(content: UIView) {
        super.init(frame: .zero)
        field.isSecureTextEntry = true
        field.translatesAutoresizingMaskIntoConstraints = false
        addSubview(field)
        pin(field, to: self)
        let canvas = field.subviews.first
        if let canvas {
            canvas.subviews.forEach { $0.removeFromSuperview() }
            canvas.isUserInteractionEnabled = true
            canvas.addSubview(content)
            content.translatesAutoresizingMaskIntoConstraints = false
            pin(content, to: canvas)
        } else {
            addSubview(content)
            content.translatesAutoresizingMaskIntoConstraints = false
            pin(content, to: self)
        }
        // Apple Intelligence & alat aksesibilitas tetap bekerja untuk VoiceOver, tetapi tanpa saran tulisan otomatis.
        if #available(iOS 18.0, *) { field.writingToolsBehavior = .none }
    }

    required init?(coder: NSCoder) { fatalError("init(coder:) has not been implemented") }

    private func pin(_ v: UIView, to p: UIView) {
        NSLayoutConstraint.activate([
            v.leadingAnchor.constraint(equalTo: p.leadingAnchor), v.trailingAnchor.constraint(equalTo: p.trailingAnchor),
            v.topAnchor.constraint(equalTo: p.topAnchor), v.bottomAnchor.constraint(equalTo: p.bottomAnchor)
        ])
    }
}

private struct ShieldedPDF: UIViewRepresentable {
    let document: PDFDocument

    func makeUIView(context: Context) -> ScreenShieldView {
        let pdf = LockedPDFView()
        pdf.autoScales = true
        pdf.displayMode = .singlePageContinuous
        pdf.displayDirection = .vertical
        pdf.document = document
        return ScreenShieldView(content: pdf)
    }

    func updateUIView(_ view: ScreenShieldView, context: Context) {}
}

struct ProtectedFile: Identifiable { let url: URL; var id: String { url.path } }

struct SecureDocumentView: View {
    let url: URL
    @Environment(\.dismiss) private var dismiss
    @Environment(\.scenePhase) private var scenePhase
    @State private var document: PDFDocument?
    @State private var error: String?
    @State private var captured = UIScreen.main.isCaptured
    @State private var screenshotWarning = false

    var body: some View {
        NavigationStack {
            ZStack {
                if let document {
                    ShieldedPDF(document: document).ignoresSafeArea(edges: .bottom)
                } else if let error {
                    ContentUnavailableView("Akses Ditolak", systemImage: "lock.trianglebadge.exclamationmark", description: Text(error))
                } else {
                    ProgressView("Memverifikasi dokumen…")
                }
                if document != nil && (captured || scenePhase != .active) {
                    Color.black.ignoresSafeArea().overlay {
                        VStack(spacing: 12) {
                            Image(systemName: "lock.fill").font(.largeTitle)
                            if captured { Text("Layar sedang direkam atau dibagikan. Dokumen terlindungi disembunyikan.").multilineTextAlignment(.center) }
                        }.foregroundStyle(.white).padding(32)
                    }
                }
            }
            .navigationTitle(url.lastPathComponent)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Tutup") { dismiss() } } }
        }
        .task { await load() }
        .onReceive(NotificationCenter.default.publisher(for: UIScreen.capturedDidChangeNotification)) { _ in
            captured = UIScreen.main.isCaptured
        }
        .onReceive(NotificationCenter.default.publisher(for: UIApplication.userDidTakeScreenshotNotification)) { _ in
            screenshotWarning = true
        }
        .alert("Dokumen Terlindungi", isPresented: $screenshotWarning) {
            Button("OK", role: .cancel) {}
        } message: { Text("Tangkapan layar dokumen terlindungi tidak diizinkan; isinya tidak ikut terekam.") }
    }

    private func load() async {
        let target = url
        let result: Result<Data, Error> = await Task.detached(priority: .userInitiated) {
            let access = target.startAccessingSecurityScopedResource()
            defer { if access { target.stopAccessingSecurityScopedResource() } }
            return Result { try ProtectedDocReader.open(try Data(contentsOf: target)) }
        }.value
        switch result {
        case .success(let data):
            if let doc = PDFDocument(data: data) { document = doc } else { error = ProtectedDocError.corrupt.localizedDescription }
        case .failure(let e):
            error = e.localizedDescription
        }
    }
}
