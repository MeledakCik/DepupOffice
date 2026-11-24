import AVFoundation
import Charts
import PDFKit
import PhotosUI
import SwiftUI
import UIKit
import UniformTypeIdentifiers
import WebKit
import WidgetKit

// Pembuat dokumen & alat baca yang menyamai Android (Invoice, Katalog, Jadwal, Grafik, Ringkasan,
// Pembaca Suara, Presentasi, Cari di semua berkas, Word/Excel ke PDF, Catatan).

// MARK: - Bantuan umum

enum DocText {
    /// Teks dari PDF, Word, Excel, PowerPoint, RTF, TXT/CSV — dibersihkan dari karakter injeksi tersembunyi.
    static func read(_ url: URL) throws -> String {
        let ext = url.pathExtension.lowercased()
        let text: String
        switch ext {
        case "pdf":
            guard let doc = PDFDocument(url: url) else { throw OfficeError.invalidPDF(url.lastPathComponent) }
            if doc.isLocked { throw ParityError.locked }
            text = (0..<doc.pageCount).compactMap { doc.page(at: $0)?.string }.joined(separator: "\n")
        case "docx", "pptx", "xlsx":
            text = try OfficeText.extract(try Data(contentsOf: url), ext: ext)
        case "rtf":
            text = try NSAttributedString(url: url, options: [.documentType: NSAttributedString.DocumentType.rtf], documentAttributes: nil).string
        default:
            let data = try Data(contentsOf: url)
            text = String(data: data, encoding: .utf8) ?? String(decoding: data, as: UTF8.self)
        }
        return AiText.clean(text)
    }

    static let readableTypes: [UTType] = [.pdf, .plainText, .rtf, .commaSeparatedText] +
        ["docx", "pptx", "xlsx"].compactMap { UTType(filenameExtension: $0) }
}

enum Money {
    static func format(_ v: Double) -> String {
        let f = NumberFormatter()
        f.locale = Locale(identifier: "id_ID")
        f.numberStyle = .decimal
        f.maximumFractionDigits = 0
        return "Rp " + (f.string(from: NSNumber(value: v)) ?? String(Int(v)))
    }

    static func parse(_ s: String) -> Double { ParityEngine.number(s.trimmingCharacters(in: .whitespaces)) ?? 0 }
}

/// Penyusun PDF A4 sederhana: teks, tabel, gambar, halaman baru otomatis.
final class PDFComposer {
    let page: CGRect
    let margin: CGFloat = 40
    private(set) var y: CGFloat = 40
    private let ctx: UIGraphicsPDFRendererContext

    init(_ ctx: UIGraphicsPDFRendererContext, page: CGRect = CGRect(x: 0, y: 0, width: 595.28, height: 841.89)) {
        self.ctx = ctx
        self.page = page
        ctx.beginPage(withBounds: page, pageInfo: [:])
        y = margin
    }

    var width: CGFloat { page.width - 2 * margin }

    func ensure(_ height: CGFloat) {
        if y + height > page.height - margin { ctx.beginPage(withBounds: page, pageInfo: [:]); y = margin }
    }

    @discardableResult
    func text(_ s: String, size: CGFloat = 11, weight: UIFont.Weight = .regular, color: UIColor = .black,
              align: NSTextAlignment = .left, x: CGFloat? = nil, width w: CGFloat? = nil, advance: Bool = true) -> CGFloat {
        let style = NSMutableParagraphStyle()
        style.alignment = align
        style.lineBreakMode = .byWordWrapping
        let attrs: [NSAttributedString.Key: Any] = [.font: UIFont.systemFont(ofSize: size, weight: weight), .foregroundColor: color, .paragraphStyle: style]
        let boxWidth = w ?? width
        let height = ceil((s as NSString).boundingRect(with: CGSize(width: boxWidth, height: .greatestFiniteMagnitude),
                                                       options: [.usesLineFragmentOrigin, .usesFontLeading], attributes: attrs, context: nil).height)
        if advance { ensure(height) }
        (s as NSString).draw(with: CGRect(x: x ?? margin, y: y, width: boxWidth, height: height),
                             options: [.usesLineFragmentOrigin, .usesFontLeading], attributes: attrs, context: nil)
        if advance { y += height }
        return height
    }

    func space(_ h: CGFloat) { y += h }

    func rule(_ color: UIColor = .lightGray) {
        ensure(2)
        color.setStroke()
        let p = UIBezierPath()
        p.move(to: CGPoint(x: margin, y: y)); p.addLine(to: CGPoint(x: page.width - margin, y: y))
        p.lineWidth = 0.7; p.stroke()
        y += 4
    }

    /// Tabel dengan kolom proporsional; [bold] = baris judul; angka rata kanan bila [rightAligned] berisi kolomnya.
    func table(_ rows: [[String]], fractions: [CGFloat], header: Bool = true, rightAligned: Set<Int> = []) {
        for (r, row) in rows.enumerated() {
            let isHeader = header && r == 0
            let cellHeights = row.enumerated().map { c, cell -> CGFloat in
                let attrs: [NSAttributedString.Key: Any] = [.font: UIFont.systemFont(ofSize: 10, weight: isHeader ? .semibold : .regular)]
                return ceil((cell as NSString).boundingRect(with: CGSize(width: fractions[c] * width - 8, height: .greatestFiniteMagnitude),
                                                            options: [.usesLineFragmentOrigin], attributes: attrs, context: nil).height)
            }
            let h = (cellHeights.max() ?? 12) + 8
            ensure(h)
            if isHeader {
                UIColor(white: 0.92, alpha: 1).setFill()
                UIRectFill(CGRect(x: margin, y: y, width: width, height: h))
            }
            var x = margin
            for (c, cell) in row.enumerated() where c < fractions.count {
                let w = fractions[c] * width
                let style = NSMutableParagraphStyle()
                style.alignment = rightAligned.contains(c) && !isHeader ? .right : .left
                (cell as NSString).draw(with: CGRect(x: x + 4, y: y + 4, width: w - 8, height: h - 8), options: [.usesLineFragmentOrigin],
                                        attributes: [.font: UIFont.systemFont(ofSize: 10, weight: isHeader ? .semibold : .regular),
                                                     .paragraphStyle: style, .foregroundColor: UIColor.black], context: nil)
                x += w
            }
            UIColor(white: 0.8, alpha: 1).setStroke()
            UIBezierPath(rect: CGRect(x: margin, y: y, width: width, height: h)).stroke()
            y += h
        }
    }

    func image(_ img: UIImage, maxWidth: CGFloat, maxHeight: CGFloat, x: CGFloat? = nil, advance: Bool = true) -> CGRect {
        let ratio = min(maxWidth / img.size.width, maxHeight / img.size.height, 1)
        let size = CGSize(width: img.size.width * ratio, height: img.size.height * ratio)
        if advance { ensure(size.height) }
        let rect = CGRect(x: x ?? margin, y: y, width: size.width, height: size.height)
        img.draw(in: rect)
        if advance { y += size.height }
        return rect
    }

    func moveTo(_ newY: CGFloat) { y = newY }
}

struct SavedName {
    static func safe(_ s: String, fallback: String) -> String {
        let t = s.components(separatedBy: CharacterSet.alphanumerics.inverted).filter { !$0.isEmpty }.joined(separator: "_")
        return t.isEmpty ? fallback : String(t.prefix(48))
    }
}

/// Gambar dari Foto (PhotosPicker) → UIImage.
struct PhotoField: View {
    let title: String
    @Binding var image: UIImage?
    @State private var item: PhotosPickerItem?

    var body: some View {
        HStack {
            if let image {
                Image(uiImage: image).resizable().scaledToFit().frame(width: 52, height: 52).clipShape(RoundedRectangle(cornerRadius: 8))
            }
            PhotosPicker(selection: $item, matching: .images) { Label(image == nil ? title : "Ganti foto", systemImage: "photo") }
            if image != nil {
                Spacer()
                Button(role: .destructive) { image = nil; item = nil } label: { Image(systemName: "trash") }.buttonStyle(.borderless)
            }
        }
        .onChange(of: item) { _, newItem in
            guard let newItem else { return }
            Task {
                if let data = try? await newItem.loadTransferable(type: Data.self), let img = UIImage(data: data) {
                    image = ParityEngine.normalized(img, maxSide: 1600)
                }
            }
        }
    }
}

// MARK: - Invoice / Nota

private struct InvoiceItem: Identifiable { let id = UUID(); var name: String; var qty: String; var price: String }

struct InvoiceMakerView: View {
    let onSave: ([GeneratedDocument]) -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var shop = "Toko Usaha Mandiri"
    @State private var number: String = {
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        f.dateFormat = "yyyyMMdd"
        return "INV-\(f.string(from: Date()))-\(Int.random(in: 100...999))"
    }()
    @State private var date = Date()
    @State private var customer = "Bpk. Pelanggan"
    @State private var note = "Terima kasih atas kepercayaan Anda."
    @State private var items: [InvoiceItem] = [InvoiceItem(name: "Barang contoh", qty: "1", price: "25000")]
    @State private var stamp: UIImage?

    private var total: Double { items.reduce(0) { $0 + Money.parse($1.qty) * Money.parse($1.price) } }

    var body: some View {
        NavigationStack {
            Form {
                Section("Toko & pelanggan") {
                    TextField("Nama toko / usaha", text: $shop)
                    TextField("Nomor nota / invoice", text: $number)
                    DatePicker("Tanggal", selection: $date, displayedComponents: .date)
                    TextField("Nama pembeli", text: $customer)
                }
                Section("Barang") {
                    ForEach($items) { $item in
                        VStack(alignment: .leading) {
                            TextField("Nama barang", text: $item.name)
                            HStack {
                                TextField("Qty", text: $item.qty).keyboardType(.decimalPad).frame(width: 60)
                                TextField("Harga satuan", text: $item.price).keyboardType(.numberPad)
                                Text(Money.format(Money.parse(item.qty) * Money.parse(item.price))).font(.caption).foregroundStyle(.secondary)
                            }
                        }
                    }
                    .onDelete { items.remove(atOffsets: $0) }
                    Button { items.append(InvoiceItem(name: "", qty: "1", price: "")) } label: { Label("Tambah barang", systemImage: "plus") }
                    HStack { Text("Total").bold(); Spacer(); Text(Money.format(total)).bold() }
                }
                Section("Lainnya") {
                    TextField("Catatan", text: $note)
                    PhotoField(title: "Tempel stempel / TTD toko", image: $stamp)
                }
            }
            .navigationTitle("Nota / Invoice")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Batal") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) { Button("Simpan PDF") { save() }.disabled(items.allSatisfy { $0.name.isEmpty }) }
            }
        }
    }

    private func save() {
        let valid = items.filter { !$0.name.trimmingCharacters(in: .whitespaces).isEmpty }
        let dateText = date.formatted(.dateTime.day().month(.wide).year().locale(Locale(identifier: "id_ID")))
        let data = UIGraphicsPDFRenderer(bounds: CGRect(x: 0, y: 0, width: 595.28, height: 841.89)).pdfData { ctx in
            let pdf = PDFComposer(ctx)
            pdf.text(shop, size: 20, weight: .bold)
            pdf.text("NOTA / INVOICE", size: 12, weight: .semibold, color: .darkGray)
            pdf.space(10)
            pdf.text("No: \(number)\nTanggal: \(dateText)\nKepada: \(customer)", size: 11)
            pdf.space(12)
            var rows = [["Nama Barang", "Qty", "Harga Satuan", "Total"]]
            for i in valid {
                let q = Money.parse(i.qty), p = Money.parse(i.price)
                rows.append([i.name, q == q.rounded() ? String(Int(q)) : String(q), Money.format(p), Money.format(q * p)])
            }
            rows.append(["", "", "TOTAL", Money.format(total)])
            pdf.table(rows, fractions: [0.46, 0.1, 0.22, 0.22], rightAligned: [1, 2, 3])
            pdf.space(16)
            if !note.isEmpty { pdf.text(note, size: 10, color: .darkGray) }
            pdf.space(24)
            pdf.text("Hormat kami,", size: 11, align: .right)
            if let stamp {
                pdf.ensure(110)
                _ = pdf.image(stamp, maxWidth: 150, maxHeight: 100, x: pdf.page.width - pdf.margin - 150)
            } else {
                pdf.space(50)
            }
            pdf.text(shop, size: 11, weight: .semibold, align: .right)
        }
        // Lembar Excel ikut dibuat agar rekap mudah dijumlah
        var sheet: [[XlsxCell]] = [[.text("Nama Barang"), .text("Qty"), .text("Harga Satuan"), .text("Total")]]
        for (n, i) in valid.enumerated() {
            sheet.append([.text(i.name), .number(Money.parse(i.qty)), .number(Money.parse(i.price)), .formula("B\(n + 2)*C\(n + 2)")])
        }
        sheet.append([.empty, .empty, .text("TOTAL"), .formula("SUM(D2:D\(valid.count + 1))")])
        let xlsx = Xlsx.write(sheets: [(name: "Nota", rows: sheet, boldRows: [0, sheet.count - 1])])
        let base = "Nota_" + SavedName.safe(number, fallback: "invoice")
        onSave([GeneratedDocument(name: base + ".pdf", data: data), GeneratedDocument(name: base + ".xlsx", data: xlsx)])
        dismiss()
    }
}

// MARK: - Katalog produk

private struct CatalogItem: Identifiable { let id = UUID(); var title = ""; var price = ""; var desc = ""; var photo: UIImage? }

struct CatalogMakerView: View {
    let onSave: ([GeneratedDocument]) -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var title = "Katalog Produk"
    @State private var contact = "Toko Usaha • WA: 08123456789"
    @State private var items: [CatalogItem] = [CatalogItem()]

    var body: some View {
        NavigationStack {
            Form {
                Section("Katalog") {
                    TextField("Judul katalog", text: $title)
                    TextField("Nama toko & kontak WA", text: $contact)
                }
                ForEach($items) { $item in
                    Section {
                        PhotoField(title: "Foto produk", image: $item.photo)
                        TextField("Nama produk", text: $item.title)
                        TextField("Harga (mis. Rp 150.000)", text: $item.price)
                        TextField("Deskripsi singkat", text: $item.desc, axis: .vertical)
                        if items.count > 1 {
                            Button("Hapus produk", role: .destructive) { items.removeAll { $0.id == item.id } }
                        }
                    }
                }
                Section { Button { items.append(CatalogItem()) } label: { Label("Tambah produk", systemImage: "plus") } }
            }
            .navigationTitle("Katalog Produk")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Batal") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) { Button("Simpan PDF") { save() }.disabled(items.allSatisfy { $0.title.isEmpty }) }
            }
        }
    }

    private func save() {
        let valid = items.filter { !$0.title.isEmpty }
        let data = UIGraphicsPDFRenderer(bounds: CGRect(x: 0, y: 0, width: 595.28, height: 841.89)).pdfData { ctx in
            let pdf = PDFComposer(ctx)
            pdf.text(title, size: 22, weight: .bold, align: .center)
            pdf.text(contact, size: 11, color: .darkGray, align: .center)
            pdf.space(14)
            let colW = (pdf.width - 16) / 2
            let cardH: CGFloat = 330
            for pair in stride(from: 0, to: valid.count, by: 2) {
                pdf.ensure(cardH)
                let top = pdf.y
                for k in 0..<2 where pair + k < valid.count {
                    let item = valid[pair + k]
                    let x = pdf.margin + CGFloat(k) * (colW + 16)
                    UIColor(white: 0.85, alpha: 1).setStroke()
                    UIBezierPath(roundedRect: CGRect(x: x, y: top, width: colW, height: cardH - 10), cornerRadius: 10).stroke()
                    pdf.moveTo(top + 8)
                    if let photo = item.photo {
                        let r = pdf.image(photo, maxWidth: colW - 16, maxHeight: 190, x: x + 8, advance: false)
                        pdf.moveTo(top + 8 + max(r.height, 190) + 6)
                    } else {
                        pdf.moveTo(top + 20)
                    }
                    pdf.text(item.title, size: 13, weight: .semibold, x: x + 10, width: colW - 20)
                    pdf.text(item.price, size: 13, weight: .bold, color: UIColor(red: 0.09, green: 0.32, blue: 0.96, alpha: 1), x: x + 10, width: colW - 20)
                    if !item.desc.isEmpty { pdf.text(item.desc, size: 9.5, color: .darkGray, x: x + 10, width: colW - 20) }
                }
                pdf.moveTo(top + cardH)
            }
        }
        onSave([GeneratedDocument(name: "Katalog_" + SavedName.safe(title, fallback: "produk") + ".pdf", data: data)])
        dismiss()
    }
}

// MARK: - Perencana jadwal

private struct ScheduleRow: Identifiable { let id = UUID(); var day: String; var task: String; var note: String }

struct SchedulePlannerView: View {
    let onSave: ([GeneratedDocument]) -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var title = "Jadwal Kerja & Agenda"
    @State private var owner = "Tim Operasional"
    @State private var rows: [ScheduleRow] = [
        ScheduleRow(day: "Senin 08:00", task: "Rapat mingguan", note: "Ruang rapat"),
        ScheduleRow(day: "Selasa 10:00", task: "", note: "")
    ]

    var body: some View {
        NavigationStack {
            Form {
                Section("Perencana") {
                    TextField("Judul", text: $title)
                    TextField("Nama / tim", text: $owner)
                }
                Section("Agenda") {
                    ForEach($rows) { $row in
                        VStack(alignment: .leading) {
                            TextField("Hari / waktu", text: $row.day).font(.subheadline.weight(.semibold))
                            TextField("Tugas / kegiatan", text: $row.task)
                            TextField("Catatan", text: $row.note).font(.caption)
                        }
                    }
                    .onDelete { rows.remove(atOffsets: $0) }
                    .onMove { rows.move(fromOffsets: $0, toOffset: $1) }
                    Button { rows.append(ScheduleRow(day: "", task: "", note: "")) } label: { Label("Tambah agenda", systemImage: "plus") }
                }
            }
            .navigationTitle("Perencana Jadwal")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Batal") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) { Button("Simpan") { save() } }
            }
        }
    }

    private func save() {
        let valid = rows.filter { !$0.task.trimmingCharacters(in: .whitespaces).isEmpty }
        var sheet: [[XlsxCell]] = [[.text("PERENCANA JADWAL & AGENDA")], [.text("Judul:"), .text(title), .text("Tim / Nama:"), .text(owner)], [],
                                   [.text("No"), .text("Hari / Waktu"), .text("Tugas / Kegiatan"), .text("Catatan")]]
        for (i, r) in valid.enumerated() { sheet.append([.number(Double(i + 1)), .text(r.day), .text(r.task), .text(r.note)]) }
        let xlsx = Xlsx.write(sheets: [(name: "Jadwal", rows: sheet, boldRows: [0, 3])])
        let pdf = UIGraphicsPDFRenderer(bounds: CGRect(x: 0, y: 0, width: 595.28, height: 841.89)).pdfData { ctx in
            let p = PDFComposer(ctx)
            p.text(title, size: 20, weight: .bold)
            p.text(owner, size: 11, color: .darkGray)
            p.space(12)
            var t = [["No", "Hari / Waktu", "Tugas / Kegiatan", "Catatan"]]
            for (i, r) in valid.enumerated() { t.append([String(i + 1), r.day, r.task, r.note]) }
            p.table(t, fractions: [0.08, 0.24, 0.4, 0.28])
        }
        let base = "Jadwal_" + SavedName.safe(title, fallback: "agenda")
        onSave([GeneratedDocument(name: base + ".xlsx", data: xlsx), GeneratedDocument(name: base + ".pdf", data: pdf)])
        dismiss()
    }
}

// MARK: - Grafik dari Excel / CSV

private struct ChartPoint: Identifiable { let id = UUID(); let label: String; let value: Double }

struct ChartMakerView: View {
    let url: URL
    let onSave: ([GeneratedDocument]) -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var table: [[String]] = []
    @State private var numericColumns: [Int] = []
    @State private var column = 1
    @State private var kind = 0
    @State private var failure: String?

    private var points: [ChartPoint] {
        guard table.count > 1 else { return [] }
        let labelColumn = (0..<(table.first?.count ?? 0)).first { !numericColumns.contains($0) } ?? 0
        return table.dropFirst().prefix(60).compactMap { row in
            guard column < row.count, let v = ParityEngine.number(row[column]) else { return nil }
            let label = labelColumn < row.count ? row[labelColumn] : ""
            return ChartPoint(label: label.isEmpty ? "-" : String(label.prefix(18)), value: v)
        }
    }

    private var chartTitle: String {
        let header = table.first ?? []
        return column < header.count ? header[column] : "Grafik"
    }

    var body: some View {
        NavigationStack {
            Form {
                if let failure { Text(failure).foregroundStyle(.red) }
                if !numericColumns.isEmpty {
                    Section {
                        Picker("Jenis", selection: $kind) {
                            Text("Batang").tag(0)
                            Text("Lingkaran").tag(1)
                            Text("Garis").tag(2)
                        }.pickerStyle(.segmented)
                        Picker("Kolom nilai", selection: $column) {
                            ForEach(numericColumns, id: \.self) { c in Text((table.first ?? []).indices.contains(c) ? table[0][c] : "Kolom \(c + 1)").tag(c) }
                        }
                    }
                    Section(chartTitle) { chart.frame(height: 300) }
                }
            }
            .navigationTitle("Grafik & Chart")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Tutup") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) { Button("Simpan") { save() }.disabled(points.isEmpty) }
            }
        }
        .task { load() }
    }

    @ViewBuilder private var chart: some View {
        let data = points
        switch kind {
        case 1:
            Chart(data) { p in
                SectorMark(angle: .value("Nilai", p.value), innerRadius: .ratio(0.45), angularInset: 1)
                    .foregroundStyle(by: .value("Label", p.label))
            }
        case 2:
            Chart(data) { p in
                LineMark(x: .value("Label", p.label), y: .value("Nilai", p.value))
                PointMark(x: .value("Label", p.label), y: .value("Nilai", p.value))
            }
        default:
            Chart(data) { p in
                BarMark(x: .value("Label", p.label), y: .value("Nilai", p.value)).foregroundStyle(Color.depupBlue)
            }
        }
    }

    private func load() {
        guard table.isEmpty else { return }
        do {
            if url.pathExtension.lowercased() == "xlsx" {
                table = try Xlsx.readFirstSheet(try Data(contentsOf: url))
            } else {
                let text = try String(contentsOf: url, encoding: .utf8)
                let sep: Character = text.contains(";") && !text.contains(",") ? ";" : text.contains("\t") ? "\t" : ","
                table = text.split(whereSeparator: \.isNewline).map { $0.split(separator: sep, omittingEmptySubsequences: false).map { String($0).trimmingCharacters(in: CharacterSet(charactersIn: "\" ")) } }
            }
            let cols = table.map(\.count).max() ?? 0
            numericColumns = (0..<cols).filter { c in
                let values = table.dropFirst().compactMap { c < $0.count ? $0[c] : nil }.filter { !$0.isEmpty }
                return !values.isEmpty && values.filter { ParityEngine.number($0) != nil }.count * 2 >= values.count
            }
            guard let first = numericColumns.first else { failure = "Tidak ada kolom angka di tabel ini."; return }
            column = first
        } catch { failure = error.localizedDescription }
    }

    @MainActor private func save() {
        let view = VStack(alignment: .leading, spacing: 12) {
            Text(chartTitle).font(.title2.bold())
            chart.frame(width: 760, height: 460)
        }
        .padding(24)
        .background(Color.white)
        .environment(\.colorScheme, .light)
        let renderer = ImageRenderer(content: view)
        renderer.scale = 2
        guard let image = renderer.uiImage, let png = image.pngData() else { failure = OfficeError.cannotWrite.localizedDescription; return }
        let pdf = UIGraphicsPDFRenderer(bounds: CGRect(x: 0, y: 0, width: 841.89, height: 595.28)).pdfData { ctx in
            ctx.beginPage()
            let r = min(801 / image.size.width, 555 / image.size.height)
            image.draw(in: CGRect(x: 20, y: 20, width: image.size.width * r, height: image.size.height * r))
        }
        let base = "Grafik_" + SavedName.safe(chartTitle, fallback: "data")
        onSave([GeneratedDocument(name: base + ".png", data: png), GeneratedDocument(name: base + ".pdf", data: pdf)])
        dismiss()
    }
}

// MARK: - Ringkasan dokumen (offline, sama dengan algoritma Android)

enum Summarizer {
    static let keywords = ["tujuan", "kesimpulan", "penting", "total", "ringkasan", "rekomendasi", "hasil", "catatan", "pasal", "syarat"]

    /// Baris PDF yang terpotong (tanpa tanda akhir kalimat) disambung menjadi satu paragraf.
    static func paragraphs(_ text: String) -> [String] {
        var out: [String] = []
        var current = ""
        for raw in text.components(separatedBy: "\n") {
            let line = raw.trimmingCharacters(in: .whitespaces)
            if line.isEmpty { if !current.isEmpty { out.append(current); current = "" }; continue }
            current = current.isEmpty ? line : current + " " + line
            if let last = line.last, ".!?:;)\"”".contains(last) { out.append(current); current = "" }
        }
        if !current.isEmpty { out.append(current) }
        return out
    }

    static func keyPoints(_ text: String) -> [String] {
        var seen = Set<String>()
        let paragraphs = paragraphs(text).filter { $0.count > 25 && seen.insert($0.lowercased()).inserted }
        if paragraphs.isEmpty { return [String(text.prefix(200))] }
        let scored = paragraphs.enumerated().map { index, p -> (Int, String, Int) in
            var score = min(p.count, 300)
            let lower = p.lowercased()
            for k in keywords where lower.contains(k) { score += 150 }
            return (index, p, score)
        }
        return scored.sorted { $0.2 > $1.2 }.prefix(5).sorted { $0.0 < $1.0 }.map { $0.1 }
    }
}

struct SummaryView: View {
    let url: URL
    let onSave: ([GeneratedDocument]) -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var points: [String] = []
    @State private var words = 0
    @State private var failure: String?
    @State private var loading = true

    var body: some View {
        NavigationStack {
            List {
                if loading { ProgressView("Menganalisis & merangkum…") }
                if let failure { Text(failure).foregroundStyle(.red) }
                if !points.isEmpty {
                    Section("\(words) kata • \(points.count) poin penting") {
                        ForEach(Array(points.enumerated()), id: \.offset) { i, p in
                            HStack(alignment: .top) { Text("\(i + 1).").bold(); Text(p).textSelection(.enabled) }
                        }
                    }
                }
            }
            .navigationTitle("Ringkasan Dokumen")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Tutup") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) { Button("Simpan") { save() }.disabled(points.isEmpty) }
            }
        }
        .task {
            let target = url
            let result = await Task.detached(priority: .userInitiated) { Result { try DocText.read(target) } }.value
            loading = false
            switch result {
            case .success(let text):
                if text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty { failure = "Tidak ada teks yang dapat dibaca di berkas ini."; return }
                words = text.split(whereSeparator: { $0.isWhitespace }).count
                points = Summarizer.keyPoints(text)
            case .failure(let e): failure = e.localizedDescription
            }
        }
    }

    private func save() {
        let name = url.deletingPathExtension().lastPathComponent
        let pdf = UIGraphicsPDFRenderer(bounds: CGRect(x: 0, y: 0, width: 595.28, height: 841.89)).pdfData { ctx in
            let p = PDFComposer(ctx)
            p.text("Ringkasan: \(name)", size: 18, weight: .bold)
            p.text("\(words) kata • dibuat \(Date().formatted(date: .long, time: .shortened))", size: 10, color: .darkGray)
            p.space(12)
            for (i, point) in points.enumerated() { p.text("\(i + 1). \(point)", size: 11); p.space(8) }
        }
        onSave([GeneratedDocument(name: "Ringkasan_\(SavedName.safe(name, fallback: "dokumen")).pdf", data: pdf)])
        dismiss()
    }
}

// MARK: - Pembaca suara (Voice Reader)

@MainActor final class SpeechController: NSObject, ObservableObject, AVSpeechSynthesizerDelegate {
    @Published var chunks: [String] = []
    @Published var current = 0
    @Published var speaking = false
    @Published var paused = false
    var rate: Float = AVSpeechUtteranceDefaultSpeechRate
    private let synth = AVSpeechSynthesizer()

    override init() {
        super.init()
        synth.delegate = self
    }

    func load(_ text: String) {
        var parts: [String] = []
        text.enumerateSubstrings(in: text.startIndex..., options: [.bySentences, .localized]) { s, _, _, _ in
            if let s = s?.trimmingCharacters(in: .whitespacesAndNewlines), !s.isEmpty { parts.append(s) }
        }
        chunks = parts.isEmpty ? [text] : parts
        current = 0
    }

    func play(from index: Int? = nil) {
        if paused && index == nil { synth.continueSpeaking(); paused = false; speaking = true; return }
        synth.stopSpeaking(at: .immediate)
        current = max(0, min(index ?? current, chunks.count - 1))
        try? AVAudioSession.sharedInstance().setCategory(.playback, mode: .spokenAudio)
        try? AVAudioSession.sharedInstance().setActive(true)
        speaking = true
        paused = false
        speakCurrent()
    }

    private func speakCurrent() {
        guard current < chunks.count else { speaking = false; return }
        let u = AVSpeechUtterance(string: chunks[current])
        u.voice = AVSpeechSynthesisVoice(language: "id-ID") ?? AVSpeechSynthesisVoice(language: Locale.current.identifier)
        u.rate = rate
        synth.speak(u)
    }

    func pause() { synth.pauseSpeaking(at: .word); paused = true; speaking = false }

    func stop() { synth.stopSpeaking(at: .immediate); speaking = false; paused = false }

    nonisolated func speechSynthesizer(_ synthesizer: AVSpeechSynthesizer, didFinish utterance: AVSpeechUtterance) {
        Task { @MainActor in
            guard self.speaking else { return }
            self.current += 1
            self.speakCurrent()
        }
    }
}

struct VoiceReaderView: View {
    let url: URL
    @Environment(\.dismiss) private var dismiss
    @StateObject private var speech = SpeechController()
    @State private var failure: String?
    @State private var rate: Double = Double(AVSpeechUtteranceDefaultSpeechRate)

    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                if let failure { Text(failure).foregroundStyle(.red).padding() }
                ScrollViewReader { proxy in
                    List(Array(speech.chunks.enumerated()), id: \.offset) { i, s in
                        Text(s)
                            .foregroundStyle(i == speech.current ? Color.depupBlue : Color.primary)
                            .fontWeight(i == speech.current ? .semibold : .regular)
                            .id(i)
                            .onTapGesture { speech.play(from: i) }
                    }
                    .listStyle(.plain)
                    .onChange(of: speech.current) { _, new in withAnimation { proxy.scrollTo(new, anchor: .center) } }
                }
                VStack(spacing: 10) {
                    HStack {
                        Image(systemName: "tortoise")
                        Slider(value: $rate, in: Double(AVSpeechUtteranceMinimumSpeechRate)...Double(AVSpeechUtteranceMaximumSpeechRate) * 0.75)
                            .onChange(of: rate) { _, v in speech.rate = Float(v) }
                        Image(systemName: "hare")
                    }
                    HStack(spacing: 36) {
                        Button { speech.play(from: max(0, speech.current - 1)) } label: { Image(systemName: "backward.fill") }
                        Button { speech.speaking ? speech.pause() : speech.play() } label: {
                            Image(systemName: speech.speaking ? "pause.circle.fill" : "play.circle.fill").font(.system(size: 52))
                        }
                        Button { speech.play(from: speech.current + 1) } label: { Image(systemName: "forward.fill") }
                    }
                    .font(.title2)
                    .disabled(speech.chunks.isEmpty)
                }
                .padding()
                .background(.bar)
            }
            .navigationTitle("Bacakan Suara")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Tutup") { speech.stop(); dismiss() } } }
        }
        .task {
            let target = url
            let result = await Task.detached(priority: .userInitiated) { Result { try DocText.read(target) } }.value
            switch result {
            case .success(let text):
                if text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty { failure = "Tidak ada teks yang bisa dibacakan." } else { speech.load(text) }
            case .failure(let e): failure = e.localizedDescription
            }
        }
        .onDisappear { speech.stop() }
    }
}

// MARK: - Presentasi (slideshow PDF layar penuh)

private struct SlideshowPDF: UIViewRepresentable {
    let document: PDFDocument

    func makeUIView(context: Context) -> PDFView {
        let v = PDFView()
        v.document = document
        v.displayMode = .singlePage
        v.displayDirection = .horizontal
        v.usePageViewController(true, withViewOptions: nil)
        v.autoScales = true
        v.backgroundColor = .black
        return v
    }

    func updateUIView(_ view: PDFView, context: Context) {}
}

struct SlideshowView: View {
    let url: URL
    @Environment(\.dismiss) private var dismiss
    @State private var document: PDFDocument?

    var body: some View {
        ZStack(alignment: .topTrailing) {
            Color.black.ignoresSafeArea()
            if let document {
                SlideshowPDF(document: document).ignoresSafeArea()
            } else {
                Text("PDF tidak bisa dibuka").foregroundStyle(.white)
            }
            Button { dismiss() } label: {
                Image(systemName: "xmark.circle.fill").font(.title).foregroundStyle(.white.opacity(0.8)).padding()
            }
            .accessibilityLabel("Tutup presentasi")
        }
        .statusBarHidden()
        .onAppear {
            document = PDFDocument(url: url)
            UIApplication.shared.isIdleTimerDisabled = true
        }
        .onDisappear { UIApplication.shared.isIdleTimerDisabled = false }
    }
}

// MARK: - Cari teks di semua berkas

private struct SearchHit: Identifiable { let id = UUID(); let url: URL; let snippet: String; let count: Int }

struct SearchAllView: View {
    @EnvironmentObject private var documents: DocumentStore
    @Environment(\.dismiss) private var dismiss
    @State private var query = ""
    @State private var hits: [SearchHit] = []
    @State private var searching = false
    @State private var searched = false

    var body: some View {
        NavigationStack {
            List {
                if searching { ProgressView("Mencari di \(documents.documents.count) berkas…") }
                if searched && !searching && hits.isEmpty { Text("Tidak ditemukan.").foregroundStyle(.secondary) }
                ForEach(hits) { hit in
                    Button {
                        let target = hit.url
                        dismiss()
                        DispatchQueue.main.asyncAfter(deadline: .now() + 0.4) { documents.open(LocalDocument(url: target, modifiedAt: .now)) }
                    } label: {
                        VStack(alignment: .leading, spacing: 4) {
                            HStack { Text(hit.url.lastPathComponent).font(.subheadline.weight(.semibold)); Spacer(); Text("\(hit.count)×").font(.caption).foregroundStyle(.secondary) }
                            Text(hit.snippet).font(.caption).foregroundStyle(.secondary).lineLimit(3)
                        }
                    }
                    .buttonStyle(.plain)
                }
            }
            .searchable(text: $query, placement: .navigationBarDrawer(displayMode: .always), prompt: "Kata di dalam PDF, Word, Excel, teks")
            .onSubmit(of: .search) { run() }
            .navigationTitle("Cari Teks dalam File")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Tutup") { dismiss() } } }
        }
    }

    private func run() {
        let q = query.trimmingCharacters(in: .whitespaces)
        guard q.count >= 2 else { return }
        let urls = documents.documents.map(\.url)
        searching = true
        Task {
            let found = await Task.detached(priority: .userInitiated) { () -> [SearchHit] in
                var out: [SearchHit] = []
                for url in urls {
                    if url.pathExtension.lowercased() == "pdf" && ProtectedDocReader.isProtected(url) { continue }
                    guard let text = try? DocText.read(url) else { continue }
                    let ranges = text.ranges(of: q, options: [.caseInsensitive, .diacriticInsensitive])
                    guard let first = ranges.first else { continue }
                    let lower = text.index(first.lowerBound, offsetBy: -60, limitedBy: text.startIndex) ?? text.startIndex
                    let upper = text.index(first.upperBound, offsetBy: 80, limitedBy: text.endIndex) ?? text.endIndex
                    let snippet = text[lower..<upper].replacingOccurrences(of: "\n", with: " ")
                    out.append(SearchHit(url: url, snippet: "…" + snippet + "…", count: ranges.count))
                }
                return out.sorted { $0.count > $1.count }
            }.value
            hits = found
            searching = false
            searched = true
        }
    }
}

private extension String {
    func ranges(of needle: String, options: String.CompareOptions) -> [Range<String.Index>] {
        var result: [Range<String.Index>] = []
        var start = startIndex
        while start < endIndex, let r = range(of: needle, options: options, range: start..<endIndex), result.count < 999 {
            result.append(r)
            start = r.upperBound
        }
        return result
    }
}

// MARK: - Word / Excel / PowerPoint ke PDF (tampilan WebKit; teks polos bila gagal)

@MainActor final class OfficePDFConverter: NSObject, ObservableObject, WKNavigationDelegate {
    let webView: WKWebView
    private var completion: ((Result<Data, Error>) -> Void)?
    private var source: URL?

    override init() {
        webView = WKWebView(frame: CGRect(x: 0, y: 0, width: 595, height: 842))
        super.init()
        webView.navigationDelegate = self
    }

    func convert(_ url: URL, completion: @escaping (Result<Data, Error>) -> Void) {
        self.completion = completion
        source = url
        webView.loadFileURL(url, allowingReadAccessTo: url.deletingLastPathComponent())
    }

    nonisolated func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
        Task { @MainActor in
            try? await Task.sleep(for: .seconds(1.2))   // beri waktu rendering dokumen Office
            self.render()
        }
    }

    nonisolated func webView(_ webView: WKWebView, didFail navigation: WKNavigation!, withError error: Error) {
        Task { @MainActor in self.fallback() }
    }

    nonisolated func webView(_ webView: WKWebView, didFailProvisionalNavigation navigation: WKNavigation!, withError error: Error) {
        Task { @MainActor in self.fallback() }
    }

    private func render() {
        let renderer = UIPrintPageRenderer()
        renderer.addPrintFormatter(webView.viewPrintFormatter(), startingAtPageAt: 0)
        let paper = CGRect(x: 0, y: 0, width: 595.28, height: 841.89)
        renderer.setValue(NSValue(cgRect: paper), forKey: "paperRect")
        renderer.setValue(NSValue(cgRect: paper.insetBy(dx: 24, dy: 24)), forKey: "printableRect")
        let pages = renderer.numberOfPages
        guard pages > 0 else { fallback(); return }
        let data = NSMutableData()
        UIGraphicsBeginPDFContextToData(data, paper, nil)
        renderer.prepare(forDrawingPages: NSRange(location: 0, length: pages))
        for i in 0..<pages {
            UIGraphicsBeginPDFPage()
            renderer.drawPage(at: i, in: UIGraphicsGetPDFContextBounds())
        }
        UIGraphicsEndPDFContext()
        finish(.success(data as Data))
    }

    /// Bila WebKit tidak bisa merender, dokumen dirender sendiri dengan format tetap terjaga.
    private func fallback() {
        guard let source else { finish(.failure(ParityError.message("Dokumen tidak bisa dikonversi."))); return }
        do { finish(.success(try OfficeRender.pdf(source))) }
        catch { finish(.failure(error)) }
    }

    private func finish(_ result: Result<Data, Error>) {
        let c = completion
        completion = nil
        c?(result)
    }
}

private struct HiddenWebView: UIViewRepresentable {
    let webView: WKWebView
    func makeUIView(context: Context) -> WKWebView { webView }
    func updateUIView(_ view: WKWebView, context: Context) {}
}

struct OfficeToPDFView: View {
    let url: URL
    let onSave: ([GeneratedDocument]) -> Void
    @Environment(\.dismiss) private var dismiss
    @StateObject private var converter = OfficePDFConverter()
    @State private var failure: String?

    var body: some View {
        ZStack {
            HiddenWebView(webView: converter.webView).frame(width: 595, height: 842).opacity(0.01).allowsHitTesting(false)
            Color(uiColor: .systemBackground).ignoresSafeArea()
            VStack(spacing: 16) {
                if let failure {
                    Text(failure).foregroundStyle(.red)
                    Button("Tutup") { dismiss() }
                } else {
                    ProgressView("Mengonversi \(url.lastPathComponent) ke PDF…")
                }
            }
            .padding()
        }
        .onAppear {
            converter.convert(url) { result in
                switch result {
                case .success(let data):
                    onSave([GeneratedDocument(name: url.deletingPathExtension().lastPathComponent + ".pdf", data: data)])
                    dismiss()
                case .failure(let e): failure = e.localizedDescription
                }
            }
        }
    }
}

/// Perender Office → PDF tanpa WebKit (juga dipakai sebagai cadangan).
enum OfficeRender {
    static let a4 = CGRect(x: 0, y: 0, width: 595.28, height: 841.89)
    static let a4Landscape = CGRect(x: 0, y: 0, width: 841.89, height: 595.28)

    static func pdf(_ url: URL) throws -> Data {
        let ext = url.pathExtension.lowercased()
        switch ext {
        case "docx":
            return AttributedPDF.render(AttributedPDF.black(try Docx.read(try Data(contentsOf: url))))
        case "rtf":
            let text = try NSAttributedString(url: url, options: [.documentType: NSAttributedString.DocumentType.rtf], documentAttributes: nil)
            return AttributedPDF.render(AttributedPDF.black(text))
        case "xlsx":
            return table(try Xlsx.readFirstSheet(try Data(contentsOf: url)), title: url.deletingPathExtension().lastPathComponent)
        case "csv":
            let text = try String(contentsOf: url, encoding: .utf8)
            let sep: Character = text.contains(";") && !text.contains(",") ? ";" : text.contains("\t") ? "\t" : ","
            return table(text.split(whereSeparator: \.isNewline).map { $0.split(separator: sep, omittingEmptySubsequences: false).map(String.init) },
                         title: url.deletingPathExtension().lastPathComponent)
        case "pptx":
            return slides(try OfficeText.slides(try Data(contentsOf: url)))
        default:
            let text = try DocText.read(url)
            guard !text.isEmpty else { throw ParityError.message("Dokumen kosong atau tidak bisa dibaca.") }
            return AttributedPDF.render(NSAttributedString(string: text, attributes: [.font: UIFont.systemFont(ofSize: 11), .foregroundColor: UIColor.black]))
        }
    }

    /// Tabel bergaris; kolom banyak → A4 mendatar; lebar kolom mengikuti panjang isi.
    static func table(_ raw: [[String]], title: String) -> Data {
        let rows = raw.filter { $0.contains { !$0.trimmingCharacters(in: .whitespaces).isEmpty } }
        let cols = rows.map(\.count).max() ?? 0
        guard cols > 0 else {
            return UIGraphicsPDFRenderer(bounds: a4).pdfData { ctx in _ = PDFComposer(ctx).text("(Sheet kosong)") }
        }
        let padded = rows.map { $0 + Array(repeating: "", count: cols - $0.count) }
        let weights = (0..<cols).map { c in CGFloat(min(40, max(4, padded.map { $0[c].count }.max() ?? 4))) }
        let total = weights.reduce(0, +)
        let page = cols > 6 ? a4Landscape : a4
        return UIGraphicsPDFRenderer(bounds: page).pdfData { ctx in
            let p = PDFComposer(ctx, page: page)
            p.text(title, size: 14, weight: .semibold)
            p.space(8)
            p.table(padded, fractions: weights.map { $0 / total }, header: true,
                    rightAligned: Set((0..<cols).filter { c in padded.dropFirst().contains { ParityEngine.number($0[c]) != nil } }))
        }
    }

    /// Satu slide per halaman A4 mendatar.
    static func slides(_ texts: [String]) -> Data {
        UIGraphicsPDFRenderer(bounds: a4Landscape).pdfData { ctx in
            for (i, text) in texts.enumerated() {
                ctx.beginPage()
                let lines = text.components(separatedBy: "\n").filter { !$0.trimmingCharacters(in: .whitespaces).isEmpty }
                var y: CGFloat = 50
                for (j, line) in lines.enumerated() {
                    let font = j == 0 ? UIFont.boldSystemFont(ofSize: 28) : UIFont.systemFont(ofSize: 18)
                    let rect = CGRect(x: 60, y: y, width: a4Landscape.width - 120, height: 400)
                    let h = ceil((line as NSString).boundingRect(with: rect.size, options: [.usesLineFragmentOrigin], attributes: [.font: font], context: nil).height)
                    if y + h > a4Landscape.height - 50 { break }
                    (line as NSString).draw(with: CGRect(x: 60, y: y, width: rect.width, height: h), options: [.usesLineFragmentOrigin],
                                            attributes: [.font: font, .foregroundColor: UIColor.black], context: nil)
                    y += h + (j == 0 ? 24 : 10)
                }
                ("\(i + 1)" as NSString).draw(at: CGPoint(x: a4Landscape.width - 60, y: a4Landscape.height - 36),
                                              withAttributes: [.font: UIFont.systemFont(ofSize: 11), .foregroundColor: UIColor.gray])
            }
            if texts.isEmpty { ctx.beginPage() }
        }
    }
}

// MARK: - Catatan (teks & daftar centang, tersimpan di perangkat)

@MainActor final class NotesStore: ObservableObject {
    static let shared = NotesStore()
    @Published private(set) var notes: [Note] = []

    init() {
        NotesFile.migrateIfNeeded()
        notes = NotesFile.load()
    }

    func reload() { notes = NotesFile.load() }

    func upsert(_ note: Note) {
        var n = note
        n.updated = Date()
        if let i = notes.firstIndex(where: { $0.id == n.id }) { notes[i] = n } else { notes.insert(n, at: 0) }
        notes.sort { $0.updated > $1.updated }
        persist()
    }

    func delete(_ ids: [UUID]) { notes.removeAll { ids.contains($0.id) }; persist() }

    private func persist() {
        NotesFile.save(notes)
        WidgetCenter.shared.reloadAllTimelines()
    }
}

struct NotesScreen: View {
    var openNoteId: UUID? = nil
    @Environment(\.dismiss) private var dismiss
    @ObservedObject private var store = NotesStore.shared
    @State private var editing: Note?

    var body: some View {
        NavigationStack {
            List {
                if store.notes.isEmpty {
                    ContentUnavailableView("Belum ada catatan", systemImage: "note.text", description: Text("Ketuk + untuk membuat catatan teks atau daftar centang."))
                }
                ForEach(store.notes) { note in
                    Button { editing = note } label: {
                        VStack(alignment: .leading, spacing: 4) {
                            Text(note.title.isEmpty ? "Tanpa judul" : note.title).font(.headline).foregroundStyle(.primary)
                            Text(note.isChecklist ? "\(note.checklist.filter(\.done).count)/\(note.checklist.count) selesai" : note.body)
                                .font(.caption).foregroundStyle(.secondary).lineLimit(2)
                        }
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                }
                .onDelete { idx in store.delete(idx.map { store.notes[$0].id }) }
            }
            .navigationTitle("Catatan")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Tutup") { dismiss() } }
                ToolbarItem(placement: .primaryAction) {
                    Menu {
                        Button { editing = Note() } label: { Label("Catatan teks", systemImage: "note.text") }
                        Button { editing = Note(isChecklist: true) } label: { Label("Daftar centang", systemImage: "checklist") }
                    } label: { Image(systemName: "plus") }
                }
            }
            .sheet(item: $editing) { note in NoteEditor(note: note) { store.upsert($0) } }
        }
        .onAppear {
            store.reload()   // catatan bisa berubah dari widget (centang item)
            if let openNoteId, let note = store.notes.first(where: { $0.id == openNoteId }) { editing = note }
        }
    }
}

private struct NoteEditor: View {
    @State var note: Note
    let onSave: (Note) -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var newItem = ""

    var body: some View {
        NavigationStack {
            Form {
                TextField("Judul", text: $note.title).font(.headline)
                if note.isChecklist {
                    Section {
                        ForEach($note.checklist) { $item in
                            HStack {
                                Button { item.done.toggle() } label: { Image(systemName: item.done ? "checkmark.circle.fill" : "circle") }
                                    .buttonStyle(.borderless)
                                TextField("Item", text: $item.text).strikethrough(item.done)
                            }
                        }
                        .onDelete { note.checklist.remove(atOffsets: $0) }
                        HStack {
                            TextField("Tambah item", text: $newItem).onSubmit(add)
                            Button(action: add) { Image(systemName: "plus.circle.fill") }.buttonStyle(.borderless)
                        }
                    }
                } else {
                    TextEditor(text: $note.body).frame(minHeight: 260)
                }
            }
            .navigationTitle(note.isChecklist ? "Daftar Centang" : "Catatan")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Batal") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) { Button("Simpan") { onSave(note); dismiss() } }
            }
        }
    }

    private func add() {
        let t = newItem.trimmingCharacters(in: .whitespaces)
        guard !t.isEmpty else { return }
        note.checklist.append(NoteItem(text: t))
        newItem = ""
    }
}
