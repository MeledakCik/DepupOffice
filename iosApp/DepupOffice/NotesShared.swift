import Foundation

// Dipakai bersama oleh aplikasi dan widget "Catatan DepUp" (target NotesWidgetExtension).
// Penyimpanan di App Group agar widget bisa membaca & mencentang item; tanpa App Group (build tanpa
// tanda tangan) jatuh ke folder aplikasi sendiri.

struct NoteItem: Codable, Identifiable, Equatable, Hashable { var id = UUID(); var text: String; var done = false }

struct Note: Codable, Identifiable, Equatable, Hashable {
    var id = UUID()
    var title = ""
    var body = ""
    var checklist: [NoteItem] = []
    var isChecklist = false
    var updated = Date()

    var displayTitle: String { title.isEmpty ? "Tanpa judul" : title }
}

enum NotesFile {
    static let appGroup = "group.cloud.depup.office.ios"

    private static var legacyURL: URL {
        let dir = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir.appendingPathComponent("notes.json")
    }

    static var url: URL {
        if let group = FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: appGroup) {
            return group.appendingPathComponent("notes.json")
        }
        return legacyURL
    }

    /// Pindahkan catatan versi sebelumnya (folder aplikasi) ke App Group sekali saja.
    static func migrateIfNeeded() {
        let target = url
        guard target != legacyURL, !FileManager.default.fileExists(atPath: target.path),
              FileManager.default.fileExists(atPath: legacyURL.path) else { return }
        try? FileManager.default.copyItem(at: legacyURL, to: target)
    }

    static func load(from file: URL? = nil) -> [Note] {
        guard let data = try? Data(contentsOf: file ?? url), let list = try? JSONDecoder().decode([Note].self, from: data) else { return [] }
        return list
    }

    /// Bisa dibaca widget setelah perangkat pertama kali dibuka kuncinya (catatan biasa, bukan dokumen rahasia).
    static func save(_ notes: [Note], to file: URL? = nil) {
        guard let data = try? JSONEncoder().encode(notes) else { return }
        try? data.write(to: file ?? url, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
    }

    /// Centang/lepas satu item (dipakai tombol interaktif widget).
    @discardableResult
    static func toggle(noteId: UUID, itemId: UUID, in file: URL? = nil) -> Bool {
        var notes = load(from: file)
        guard let n = notes.firstIndex(where: { $0.id == noteId }),
              let i = notes[n].checklist.firstIndex(where: { $0.id == itemId }) else { return false }
        notes[n].checklist[i].done.toggle()
        notes[n].updated = Date()
        save(notes, to: file)
        return true
    }

    static func deepLink(_ id: UUID) -> URL { URL(string: "depupoffice://note/\(id.uuidString)")! }

    static func noteId(from url: URL) -> UUID? {
        guard url.scheme == "depupoffice", url.host == "note" else { return nil }
        return UUID(uuidString: url.lastPathComponent)
    }
}
