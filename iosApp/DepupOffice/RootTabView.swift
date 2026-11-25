import QuickLook
import SwiftUI
import UIKit
import UniformTypeIdentifiers
import VisionKit

private enum AppTab: Hashable {
    case home, documents, scan, tools, profile
}

extension Color {
    static let depupBlue = Color(red: 0.09, green: 0.32, blue: 0.96)
    static let depupBackground = Color(uiColor: .systemGroupedBackground)
}

private struct NoteLink: Identifiable { let id: UUID }

struct RootTabView: View {
    @EnvironmentObject private var documents: DocumentStore
    @State private var selectedTab: AppTab = .home
    @State private var noteLink: NoteLink?
    #if DEBUG
    @State private var uiTestRoute: UITestRoute?
    #endif

    var body: some View {
        TabView(selection: $selectedTab) {
            NavigationStack {
                HomeScreen(onScan: { selectedTab = .scan }, onDocuments: { selectedTab = .documents })
                    .navigationTitle("DepUp Office")
                    .navigationBarTitleDisplayMode(.inline)
                    .toolbar {
                        ToolbarItem(placement: .topBarTrailing) {
                            Button { selectedTab = .profile } label: {
                                Image(systemName: "person.crop.circle")
                            }
                            .accessibilityLabel("Profil")
                        }
                    }
            }
            .tabItem { Label("Beranda", systemImage: "house") }
            .tag(AppTab.home)

            NavigationStack {
                DocumentLibraryScreen()
                    .navigationTitle("Dokumen")
                    .navigationBarTitleDisplayMode(.inline)
            }
            .tabItem { Label("Berkas", systemImage: "folder") }
            .tag(AppTab.documents)

            NavigationStack {
                ScanScreen()
                    .navigationTitle("Scan")
                    .navigationBarTitleDisplayMode(.inline)
            }
            .tabItem { Label("Scan", systemImage: "viewfinder") }
            .tag(AppTab.scan)

            NavigationStack {
                NativeToolsScreen()
                    .navigationTitle("Alat")
                    .navigationBarTitleDisplayMode(.inline)
            }
            .tabItem { Label("Alat", systemImage: "wrench.and.screwdriver") }
            .tag(AppTab.tools)

            NavigationStack {
                ProfileScreen()
                    .navigationTitle("Saya")
                    .navigationBarTitleDisplayMode(.inline)
            }
            .tabItem { Label("Saya", systemImage: "person.crop.circle") }
            .tag(AppTab.profile)
        }
        .quickLookPreview($documents.previewURL)
        #if DEBUG
        .sheet(item: $uiTestRoute) { route in UITestScreenView(route: route).environmentObject(documents) }
        .task {
            guard let screen = UITestSupport.screen else { return }
            switch screen {
            case "tab:home": selectedTab = .home
            case "tab:documents": selectedTab = .documents
            case "tab:scan": selectedTab = .scan
            case "tab:tools": selectedTab = .tools
            case "tab:profile": selectedTab = .profile
            case "protected": documents.protectedFile = ProtectedFile(url: UITestSupport.url("Rahasia_terlindungi.pdf"))
            default: uiTestRoute = UITestRoute(id: screen)
            }
        }
        #endif
        .onOpenURL { url in
            if let id = NotesFile.noteId(from: url) { noteLink = NoteLink(id: id) }
            else if url.isFileURL { documents.importAndOpen(url) }
        }
        .sheet(item: $noteLink) { link in NotesScreen(openNoteId: link.id) }
        .fullScreenCover(item: $documents.protectedFile) { file in
            SecureDocumentView(url: file.url)
                .environmentObject(documents)
        }
        .alert("DepUp Office", isPresented: statusAlertIsPresented) {
            Button("OK") { documents.statusMessage = nil }
        } message: {
            Text(documents.statusMessage ?? "")
        }
    }

    private var statusAlertIsPresented: Binding<Bool> {
        Binding(
            get: { documents.statusMessage != nil },
            set: { if !$0 { documents.statusMessage = nil } }
        )
    }
}

private struct HomeScreen: View {
    @EnvironmentObject private var documents: DocumentStore
    @State private var query = ""
    let onScan: () -> Void
    let onDocuments: () -> Void

    private var visibleDocuments: [LocalDocument] {
        let filtered = documents.documents.filter { query.isEmpty || $0.name.localizedCaseInsensitiveContains(query) }
        return Array(filtered.prefix(3))
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 24) {
                VStack(alignment: .leading, spacing: 5) {
                    Text(Date.now.formatted(.dateTime.weekday(.wide).day().month(.wide).locale(Locale(identifier: "id_ID"))))
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                    Text("\(greeting()),\nmau kerjakan apa?")
                        .font(.system(size: 31, weight: .semibold, design: .rounded))
                        .tracking(-0.8)
                        .fixedSize(horizontal: false, vertical: true)
                }
                .padding(.top, 8)

                HStack(spacing: 10) {
                    Image(systemName: "magnifyingglass").foregroundStyle(.secondary)
                    TextField("Cari dokumen", text: $query)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                }
                .padding(.horizontal, 15)
                .frame(height: 50)
                .background(Color(uiColor: .secondarySystemGroupedBackground), in: RoundedRectangle(cornerRadius: 16))

                scanCard

                VStack(alignment: .leading, spacing: 14) {
                    HStack {
                        Text("Akses cepat").font(.title3.weight(.semibold))
                        Spacer()
                        Button("Semua berkas", action: onDocuments)
                            .font(.subheadline.weight(.medium))
                            .foregroundStyle(Color.depupBlue)
                    }
                    HStack(spacing: 12) {
                        QuickAction(title: "PDF", detail: "Buka dokumen", symbol: "doc.richtext", color: DocumentKind.pdf.color, action: onDocuments)
                        QuickAction(title: "Word", detail: "Berkas teks", symbol: "doc.text", color: DocumentKind.word.color, action: onDocuments)
                        QuickAction(title: "Excel", detail: "Spreadsheet", symbol: "tablecells", color: DocumentKind.spreadsheet.color, action: onDocuments)
                    }
                }

                VStack(alignment: .leading, spacing: 12) {
                    HStack {
                        Text("Terakhir dibuka").font(.title3.weight(.semibold))
                        Spacer()
                        if !documents.documents.isEmpty {
                            Text("\(documents.documents.count) berkas")
                                .font(.caption)
                                .foregroundStyle(.secondary)
                        }
                    }
                    if visibleDocuments.isEmpty {
                        EmptyDocumentsCard()
                    } else {
                        VStack(spacing: 0) {
                            ForEach(Array(visibleDocuments.enumerated()), id: \.element.id) { index, document in
                                DocumentRow(document: document) { documents.open(document) }
                                if index < visibleDocuments.count - 1 { Divider().padding(.leading, 62) }
                            }
                        }
                        .padding(.horizontal, 14)
                        .background(Color(uiColor: .secondarySystemGroupedBackground), in: RoundedRectangle(cornerRadius: 18))
                    }
                }
            }
            .padding(.horizontal, 20)
            .padding(.bottom, 28)
        }
        .background(Color.depupBackground)
    }

    private var scanCard: some View {
        Button(action: onScan) {
            HStack(alignment: .center, spacing: 18) {
                VStack(alignment: .leading, spacing: 10) {
                    Text("Scan dokumen")
                        .font(.title2.weight(.semibold))
                    Text("Ubah kertas menjadi PDF dalam beberapa langkah.")
                        .font(.subheadline)
                        .foregroundStyle(.white.opacity(0.84))
                        .multilineTextAlignment(.leading)
                    Label("Mulai scan", systemImage: "arrow.right")
                        .font(.subheadline.weight(.semibold))
                        .padding(.horizontal, 13)
                        .padding(.vertical, 9)
                        .foregroundStyle(Color.depupBlue)
                        .background(.white, in: Capsule())
                        .padding(.top, 3)
                }
                Spacer(minLength: 0)
                Image(systemName: "doc.viewfinder")
                    .font(.system(size: 54, weight: .light))
                    .symbolRenderingMode(.hierarchical)
                    .accessibilityHidden(true)
            }
            .foregroundStyle(.white)
            .padding(20)
            .background(Color.depupBlue, in: RoundedRectangle(cornerRadius: 24, style: .continuous))
        }
        .buttonStyle(.plain)
    }

    private func greeting() -> String {
        let hour = Calendar.current.component(.hour, from: .now)
        switch hour {
        case 4..<11: return "Selamat pagi"
        case 11..<15: return "Selamat siang"
        case 15..<18: return "Selamat sore"
        default: return "Selamat malam"
        }
    }
}

private struct QuickAction: View {
    let title: String
    let detail: String
    let symbol: String
    let color: Color
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            VStack(alignment: .leading, spacing: 9) {
                Image(systemName: symbol)
                    .font(.system(size: 19, weight: .medium))
                    .foregroundStyle(color)
                    .frame(width: 38, height: 38)
                    .background(color.opacity(0.12), in: RoundedRectangle(cornerRadius: 12))
                Text(title).font(.subheadline.weight(.semibold)).foregroundStyle(.primary)
                Text(detail).font(.caption).foregroundStyle(.secondary).lineLimit(1)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(13)
            .background(Color(uiColor: .secondarySystemGroupedBackground), in: RoundedRectangle(cornerRadius: 18))
        }
        .buttonStyle(.plain)
    }
}

private struct EmptyDocumentsCard: View {
    var body: some View {
        VStack(spacing: 9) {
            Image(systemName: "doc.on.doc")
                .font(.system(size: 25, weight: .regular))
                .foregroundStyle(.secondary)
            Text("Belum ada dokumen")
                .font(.subheadline.weight(.semibold))
            Text("Berkas yang Anda impor atau scan akan muncul di sini.")
                .font(.caption)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 28)
        .padding(.horizontal, 18)
        .background(Color(uiColor: .secondarySystemGroupedBackground), in: RoundedRectangle(cornerRadius: 18))
    }
}

private struct DocumentLibraryScreen: View {
    @EnvironmentObject private var documents: DocumentStore
    @State private var isImporterPresented = false
    @State private var isLinkPresented = false
    @State private var editTarget: EditorTarget?
    @State private var pendingDelete: LocalDocument?

    /// Jenis editor untuk berkas ini (nil = hanya pratinjau).
    private func editorKind(_ d: LocalDocument) -> String? {
        switch d.fileExtension {
        case "docx", "rtf", "txt": return "docEditor"
        case "xlsx", "csv": return "sheetEditor"
        case "pdf": return ProtectedDocReader.isProtected(d.url) ? nil : "annotate"
        default: return nil
        }
    }

    var body: some View {
        Group {
            if documents.documents.isEmpty {
                ContentUnavailableView {
                    Label("Belum ada dokumen", systemImage: "folder")
                } description: {
                    Text("Impor PDF, Word, Excel, atau berkas lain untuk mulai bekerja.")
                } actions: {
                    Button("Impor berkas") { isImporterPresented = true }
                        .buttonStyle(.borderedProminent)
                    Button("Buka dari link") { isLinkPresented = true }
                }
            } else {
                List(documents.documents) { document in
                    DocumentRow(document: document) { documents.open(document) }
                        .listRowInsets(EdgeInsets(top: 8, leading: 16, bottom: 8, trailing: 16))
                        .contextMenu {
                            if let kind = editorKind(document) {
                                Button { editTarget = EditorTarget(url: document.url, kind: kind) } label: {
                                    Label(kind == "annotate" ? "Coret-coret" : "Edit", systemImage: "pencil")
                                }
                            }
                            ShareLink(item: document.url) { Label("Bagikan", systemImage: "square.and.arrow.up") }
                            Button(role: .destructive) { pendingDelete = document } label: { Label("Hapus", systemImage: "trash") }
                        }
                        .swipeActions {
                            Button(role: .destructive) { pendingDelete = document } label: { Label("Hapus", systemImage: "trash") }
                        }
                }
                .listStyle(.insetGrouped)
                .refreshable { documents.refresh() }
            }
        }
        .background(Color.depupBackground)
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Menu {
                    Button { isImporterPresented = true } label: { Label("Impor berkas", systemImage: "folder") }
                    Button { isLinkPresented = true } label: { Label("Buka dari link", systemImage: "link") }
                } label: { Image(systemName: "plus") }
                    .accessibilityLabel("Tambah berkas")
            }
        }
        .sheet(isPresented: $isLinkPresented) { LinkImportSheet().environmentObject(documents) }
        .sheet(item: $editTarget) { target in
            switch target.kind {
            case "docEditor": DocEditorView(source: target.url) { documents.saveGenerated($0) }
            case "sheetEditor": SheetEditorView(source: target.url, template: nil) { documents.saveGenerated($0) }
            default:
                AnnotatePDFView(url: target.url) { data in
                    documents.savePDF(data, name: target.url.deletingPathExtension().lastPathComponent + "_coret")
                }
            }
        }
        .confirmationDialog("Hapus berkas ini secara permanen?", isPresented: Binding(get: { pendingDelete != nil }, set: { if !$0 { pendingDelete = nil } }),
                            titleVisibility: .visible, presenting: pendingDelete) { doc in
            Button("Hapus \(doc.name)", role: .destructive) { documents.delete(doc) }
        } message: { _ in Text("Berkas yang dihapus tidak bisa dikembalikan.") }
        .fileImporter(
            isPresented: $isImporterPresented,
            allowedContentTypes: [.item],
            allowsMultipleSelection: true
        ) { result in
            if case let .success(urls) = result { documents.importFiles(from: urls) }
            if case let .failure(error) = result { documents.statusMessage = error.localizedDescription }
        }
    }
}

private struct DocumentRow: View {
    let document: LocalDocument
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 12) {
                Image(systemName: document.kind.symbol)
                    .font(.system(size: 18, weight: .medium))
                    .foregroundStyle(document.kind.color)
                    .frame(width: 42, height: 46)
                    .background(document.kind.softColor, in: RoundedRectangle(cornerRadius: 12))
                VStack(alignment: .leading, spacing: 4) {
                    Text(document.name)
                        .font(.subheadline.weight(.medium))
                        .foregroundStyle(.primary)
                        .lineLimit(1)
                    Text("\(document.kind.title) · \(document.modifiedAt.formatted(Date.FormatStyle(date: .abbreviated, time: .shortened).locale(Locale(identifier: "id_ID"))))")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                }
                Spacer(minLength: 4)
                Image(systemName: "chevron.right")
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(.tertiary)
            }
            .contentShape(Rectangle())
            .padding(.vertical, 3)
        }
        .buttonStyle(.plain)
    }
}

private struct ScanScreen: View {
    @EnvironmentObject private var documents: DocumentStore
    @State private var isScannerPresented = false

    var body: some View {
        VStack(spacing: 20) {
            Image(systemName: "doc.viewfinder")
                .font(.system(size: 62, weight: .light))
                .foregroundStyle(Color.depupBlue)
                .frame(width: 118, height: 118)
                .background(Color.depupBlue.opacity(0.10), in: RoundedRectangle(cornerRadius: 32))
            VStack(spacing: 8) {
                Text("Scan dokumen")
                    .font(.title2.weight(.semibold))
                Text("Deteksi tepi dan hasilkan PDF menggunakan pemindai dokumen bawaan iPhone.")
                    .font(.body)
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
            }
            .padding(.horizontal, 22)
            Button {
                isScannerPresented = true
            } label: {
                Label("Mulai scan", systemImage: "camera")
                    .font(.headline)
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 15)
            }
            .buttonStyle(.borderedProminent)
            .padding(.horizontal, 28)
            .disabled(!VNDocumentCameraViewController.isSupported)
            if !VNDocumentCameraViewController.isSupported {
                Text("Pemindai tidak tersedia pada perangkat ini.")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }
            Spacer()
        }
        .padding(.top, 54)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(Color.depupBackground)
        .sheet(isPresented: $isScannerPresented) {
            NativeDocumentScanner { pdfData in
                documents.saveScannedPDF(pdfData)
                isScannerPresented = false
            } onCancel: {
                isScannerPresented = false
            }
            .ignoresSafeArea()
        }
    }
}

private struct NativeDocumentScanner: UIViewControllerRepresentable {
    let onScan: (Data) -> Void
    let onCancel: () -> Void

    func makeCoordinator() -> Coordinator { Coordinator(onScan: onScan, onCancel: onCancel) }

    func makeUIViewController(context: Context) -> VNDocumentCameraViewController {
        let controller = VNDocumentCameraViewController()
        controller.delegate = context.coordinator
        return controller
    }

    func updateUIViewController(_ controller: VNDocumentCameraViewController, context: Context) {}

    final class Coordinator: NSObject, VNDocumentCameraViewControllerDelegate {
        let onScan: (Data) -> Void
        let onCancel: () -> Void

        init(onScan: @escaping (Data) -> Void, onCancel: @escaping () -> Void) {
            self.onScan = onScan
            self.onCancel = onCancel
        }

        func documentCameraViewControllerDidCancel(_ controller: VNDocumentCameraViewController) {
            onCancel()
        }

        func documentCameraViewController(
            _ controller: VNDocumentCameraViewController,
            didFailWithError error: Error
        ) {
            onCancel()
        }

        func documentCameraViewController(
            _ controller: VNDocumentCameraViewController,
            didFinishWith scan: VNDocumentCameraScan
        ) {
            let pageBounds = CGRect(x: 0, y: 0, width: 612, height: 792)
            let renderer = UIGraphicsPDFRenderer(bounds: pageBounds)
            let data = renderer.pdfData { context in
                for pageIndex in 0..<scan.pageCount {
                    context.beginPage()
                    let image = scan.imageOfPage(at: pageIndex)
                    let scale = min(pageBounds.width / image.size.width, pageBounds.height / image.size.height)
                    let size = CGSize(width: image.size.width * scale, height: image.size.height * scale)
                    let rect = CGRect(
                        x: (pageBounds.width - size.width) / 2,
                        y: (pageBounds.height - size.height) / 2,
                        width: size.width,
                        height: size.height
                    )
                    image.draw(in: rect)
                }
            }
            onScan(data)
        }
    }
}

private struct ToolsScreen: View {
    private let tools: [(String, String, String)] = [
        ("PDF", "Buka dan bagikan dokumen PDF", "doc.richtext"),
        ("Word", "Impor dan pratinjau dokumen Word", "doc.text"),
        ("Excel", "Impor dan pratinjau spreadsheet", "tablecells"),
        ("Pemindai", "Buat PDF dari dokumen kertas", "viewfinder")
    ]

    var body: some View {
        List(tools, id: \.0) { title, detail, symbol in
            Label {
                VStack(alignment: .leading, spacing: 4) {
                    Text(title).font(.body.weight(.medium))
                    Text(detail).font(.caption).foregroundStyle(.secondary)
                }
            } icon: {
                Image(systemName: symbol).foregroundStyle(Color.depupBlue)
            }
            .padding(.vertical, 5)
        }
        .listStyle(.insetGrouped)
        .background(Color.depupBackground)
    }
}

private struct ProfileScreen: View {
    var body: some View {
        List {
            Section {
                Label("DepUp Office", systemImage: "app.fill")
                LabeledContent("Versi", value: Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "1.0")
            }
            Section("Preferensi") {
                Label("Tampilan mengikuti pengaturan iPhone", systemImage: "circle.lefthalf.filled")
            }
            Section {
                Link(destination: URL(string: "https://depupstudio.com/office/privacy-policy")!) {
                    Label("Kebijakan Privasi", systemImage: "hand.raised")
                }
                Link(destination: URL(string: "https://depupstudio.com/office")!) {
                    Label("Depup Studio", systemImage: "globe")
                }
            } header: {
                Text("Tentang")
            } footer: {
                Text("Offline: berkas tetap di iPhone Anda. Tanpa akun, iklan, atau analitik.")
            }
        }
        .listStyle(.insetGrouped)
        .background(Color.depupBackground)
    }
}
