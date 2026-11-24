import AppIntents
import SwiftUI
import WidgetKit

// Widget "Catatan DepUp" — setara widget catatan Android: pilih catatan, tampilkan teks atau daftar centang,
// centang item langsung dari layar utama, ketuk untuk membuka catatan di aplikasi.

struct NoteEntity: AppEntity {
    static var typeDisplayRepresentation: TypeDisplayRepresentation = "Catatan"
    static var defaultQuery = NoteQuery()
    var id: UUID
    var title: String
    var displayRepresentation: DisplayRepresentation { DisplayRepresentation(title: "\(title)") }
}

struct NoteQuery: EntityQuery {
    func entities(for identifiers: [UUID]) async throws -> [NoteEntity] {
        NotesFile.load().filter { identifiers.contains($0.id) }.map { NoteEntity(id: $0.id, title: $0.displayTitle) }
    }

    func suggestedEntities() async throws -> [NoteEntity] {
        NotesFile.load().map { NoteEntity(id: $0.id, title: $0.displayTitle) }
    }

    func defaultResult() async -> NoteEntity? {
        NotesFile.load().first.map { NoteEntity(id: $0.id, title: $0.displayTitle) }
    }
}

struct SelectNoteIntent: WidgetConfigurationIntent {
    static var title: LocalizedStringResource = "Pilih catatan"
    static var description = IntentDescription("Catatan yang ditampilkan di widget.")
    @Parameter(title: "Catatan") var note: NoteEntity?
}

struct ToggleItemIntent: AppIntent {
    static var title: LocalizedStringResource = "Centang item catatan"
    static var isDiscoverable = false
    @Parameter(title: "Catatan") var noteId: String
    @Parameter(title: "Item") var itemId: String

    init() {}
    init(noteId: UUID, itemId: UUID) {
        self.noteId = noteId.uuidString
        self.itemId = itemId.uuidString
    }

    func perform() async throws -> some IntentResult {
        if let n = UUID(uuidString: noteId), let i = UUID(uuidString: itemId) { NotesFile.toggle(noteId: n, itemId: i) }
        return .result()
    }
}

struct NoteEntry: TimelineEntry {
    let date: Date
    let note: Note?
}

struct NoteProvider: AppIntentTimelineProvider {
    func placeholder(in context: Context) -> NoteEntry {
        NoteEntry(date: .now, note: Note(title: "Belanja", checklist: [NoteItem(text: "Kertas A4"), NoteItem(text: "Tinta printer", done: true)], isChecklist: true))
    }

    func snapshot(for configuration: SelectNoteIntent, in context: Context) async -> NoteEntry { entry(for: configuration) }

    func timeline(for configuration: SelectNoteIntent, in context: Context) async -> Timeline<NoteEntry> {
        Timeline(entries: [entry(for: configuration)], policy: .never)
    }

    private func entry(for configuration: SelectNoteIntent) -> NoteEntry {
        let notes = NotesFile.load()
        let note = configuration.note.flatMap { selected in notes.first { $0.id == selected.id } } ?? notes.first
        return NoteEntry(date: .now, note: note)
    }
}

struct NotesWidgetView: View {
    let entry: NoteEntry
    @Environment(\.widgetFamily) private var family

    private var maxItems: Int { family == .systemLarge ? 12 : family == .systemMedium ? 5 : 4 }

    var body: some View {
        Group {
            if let note = entry.note {
                VStack(alignment: .leading, spacing: 6) {
                    Text(note.displayTitle).font(.headline).lineLimit(1)
                    if note.isChecklist {
                        ForEach(note.checklist.prefix(maxItems)) { item in
                            Button(intent: ToggleItemIntent(noteId: note.id, itemId: item.id)) {
                                HStack(spacing: 6) {
                                    Image(systemName: item.done ? "checkmark.circle.fill" : "circle")
                                        .foregroundStyle(item.done ? Color.green : Color.secondary)
                                    Text(item.text).font(.caption).strikethrough(item.done).lineLimit(1)
                                        .foregroundStyle(item.done ? Color.secondary : Color.primary)
                                }
                            }
                            .buttonStyle(.plain)
                        }
                        if note.checklist.count > maxItems {
                            Text("+\(note.checklist.count - maxItems) lagi").font(.caption2).foregroundStyle(.secondary)
                        }
                    } else {
                        Text(note.body).font(.caption).foregroundStyle(.secondary)
                    }
                    Spacer(minLength: 0)
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
                .widgetURL(NotesFile.deepLink(note.id))
            } else {
                VStack(spacing: 6) {
                    Image(systemName: "note.text").font(.title2)
                    Text("Belum ada catatan").font(.caption)
                    Text("Buat di DepUp Office → Alat → Catatan").font(.caption2).foregroundStyle(.secondary).multilineTextAlignment(.center)
                }
            }
        }
        .containerBackground(for: .widget) { Color(red: 1.0, green: 0.97, blue: 0.86) }
    }
}

struct NotesWidget: Widget {
    var body: some WidgetConfiguration {
        AppIntentConfiguration(kind: "DepUpNotesWidget", intent: SelectNoteIntent.self, provider: NoteProvider()) { entry in
            NotesWidgetView(entry: entry)
        }
        .configurationDisplayName("Catatan DepUp")
        .description("Pasang catatan teks atau daftar centang di layar utama.")
        .supportedFamilies([.systemSmall, .systemMedium, .systemLarge])
    }
}

@main
struct NotesWidgetBundle: WidgetBundle {
    var body: some Widget { NotesWidget() }
}
