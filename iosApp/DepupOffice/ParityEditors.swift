import PDFKit
import PencilKit
import SwiftUI
import UIKit
import UniformTypeIdentifiers

// Editor Excel (rumus), editor Word (DOCX/RTF) + template, coret-coret PDF, pustaka tanda tangan — setara Android.

// MARK: - Rumus spreadsheet (SUM, AVERAGE/AVG, MIN, MAX, COUNT, ROUND, ABS, + - * / ^, referensi sel & rentang)

enum FormulaError: Error { case syntax, reference, cycle, divZero }

struct FormulaEngine {
    let raw: (Int, Int) -> String
    private var visiting = Set<Int>()

    init(raw: @escaping (Int, Int) -> String) { self.raw = raw }

    /// Nilai tampilan sebuah sel: rumus dihitung, teks dibiarkan.
    func display(_ r: Int, _ c: Int) -> String {
        let s = raw(r, c)
        guard s.hasPrefix("=") else { return s }
        var copy = self
        do {
            let v = try copy.value(r, c)
            return Self.format(v)
        } catch FormulaError.cycle { return "#SIKLUS" }
        catch FormulaError.divZero { return "#DIV/0" }
        catch FormulaError.reference { return "#REF" }
        catch { return "#ERROR" }
    }

    static func format(_ v: Double) -> String {
        if v == v.rounded() && abs(v) < 1e15 { return String(Int64(v)) }
        return String(format: "%.4g", v).replacingOccurrences(of: ".", with: ",")
    }

    mutating func value(_ r: Int, _ c: Int) throws -> Double {
        let key = r * 1000 + c
        if visiting.contains(key) { throw FormulaError.cycle }
        let s = raw(r, c).trimmingCharacters(in: .whitespaces)
        if s.hasPrefix("=") {
            visiting.insert(key)
            defer { visiting.remove(key) }
            var p = Parser(text: Array(String(s.dropFirst()).uppercased()), engine: self)
            let v = try p.expression()
            p.skip()
            guard p.atEnd else { throw FormulaError.syntax }
            return v
        }
        return ParityEngine.number(s) ?? 0
    }

    /// Nilai sel untuk fungsi agregat: nil bila sel kosong/teks (tidak ikut COUNT/AVERAGE).
    mutating func numeric(_ r: Int, _ c: Int) throws -> Double? {
        let s = raw(r, c).trimmingCharacters(in: .whitespaces)
        if s.isEmpty { return nil }
        if s.hasPrefix("=") { return try value(r, c) }
        return ParityEngine.number(s)
    }

    static func reference(_ s: String) -> (row: Int, col: Int)? {
        var col = 0, digits = ""
        for ch in s {
            if let a = ch.asciiValue, a >= 65, a <= 90, digits.isEmpty { col = col * 26 + Int(a - 64) }
            else if ch.isNumber { digits.append(ch) } else { return nil }
        }
        guard col > 0, let r = Int(digits), r > 0 else { return nil }
        return (r - 1, col - 1)
    }

    private struct Parser {
        let text: [Character]
        var engine: FormulaEngine
        var i = 0

        var atEnd: Bool { i >= text.count }
        mutating func skip() { while i < text.count && text[i] == " " { i += 1 } }
        mutating func peek() -> Character? { skip(); return i < text.count ? text[i] : nil }

        mutating func expression() throws -> Double {
            var v = try term()
            while let ch = peek(), ch == "+" || ch == "-" {
                i += 1
                let r = try term()
                v = ch == "+" ? v + r : v - r
            }
            return v
        }

        mutating func term() throws -> Double {
            var v = try power()
            while let ch = peek(), ch == "*" || ch == "/" {
                i += 1
                let r = try power()
                if ch == "/" { guard r != 0 else { throw FormulaError.divZero }; v /= r } else { v *= r }
            }
            return v
        }

        mutating func power() throws -> Double {
            let base = try unary()
            if peek() == "^" { i += 1; let e = try power(); return pow(base, e) }
            return base
        }

        mutating func unary() throws -> Double {
            if peek() == "-" { i += 1; let v = try unary(); return -v }
            if peek() == "+" { i += 1; return try unary() }
            return try primary()
        }

        mutating func word() -> String {
            skip()
            var s = ""
            while i < text.count, text[i].isLetter || text[i].isNumber || text[i] == "$" {
                if text[i] != "$" { s.append(text[i]) }
                i += 1
            }
            return s
        }

        mutating func primary() throws -> Double {
            guard let ch = peek() else { throw FormulaError.syntax }
            if ch == "(" {
                i += 1
                let v = try expression()
                guard peek() == ")" else { throw FormulaError.syntax }
                i += 1
                return v
            }
            if ch.isNumber || ch == "." || ch == "," {
                var s = ""
                while i < text.count, text[i].isNumber || text[i] == "." || text[i] == "," { s.append(text[i] == "," ? "." : text[i]); i += 1 }
                guard let v = Double(s) else { throw FormulaError.syntax }
                return v
            }
            let name = word()
            guard !name.isEmpty else { throw FormulaError.syntax }
            if peek() == "(" {
                i += 1
                return try function(name)
            }
            guard let ref = FormulaEngine.reference(name) else { throw FormulaError.reference }
            return try engine.value(ref.row, ref.col)
        }

        /// Argumen: rentang (A1:B5) atau ekspresi; dipisah ; atau ,
        mutating func arguments() throws -> [[Double]] {
            var args: [[Double]] = []
            if peek() == ")" { i += 1; return args }
            while true {
                let save = i
                let first = word()
                if let a = FormulaEngine.reference(first), peek() == ":" {
                    i += 1
                    guard let b = FormulaEngine.reference(word()) else { throw FormulaError.reference }
                    var values: [Double] = []
                    for r in min(a.row, b.row)...max(a.row, b.row) {
                        for c in min(a.col, b.col)...max(a.col, b.col) {
                            if let v = try engine.numeric(r, c) { values.append(v) }
                        }
                    }
                    args.append(values)
                } else {
                    i = save
                    args.append([try expression()])
                }
                guard let sep = peek() else { throw FormulaError.syntax }
                i += 1
                if sep == ")" { return args }
                guard sep == ";" || sep == "," else { throw FormulaError.syntax }
            }
        }

        mutating func function(_ name: String) throws -> Double {
            let args = try arguments()
            let all = args.flatMap { $0 }
            switch name {
            case "SUM": return all.reduce(0, +)
            case "AVERAGE", "AVG", "RATA":
                guard !all.isEmpty else { throw FormulaError.divZero }
                return all.reduce(0, +) / Double(all.count)
            case "MIN": return all.min() ?? 0
            case "MAX": return all.max() ?? 0
            case "COUNT": return Double(all.count)
            case "ABS": return abs(all.first ?? 0)
            case "ROUND":
                let digits = args.count > 1 ? (args[1].first ?? 0) : 0
                let m = pow(10, digits)
                return ((all.first ?? 0) * m).rounded() / m
            default: throw FormulaError.syntax
            }
        }
    }
}

// MARK: - Editor Excel

struct SheetCellData: Equatable { var text = ""; var bold = false }

struct SheetEditorView: View {
    let source: URL?
    let template: String?
    let onSave: ([GeneratedDocument]) -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var title = "Spreadsheet Baru"
    @State private var cells: [[SheetCellData]] = Array(repeating: Array(repeating: SheetCellData(), count: 5), count: 12)
    @State private var selected: (row: Int, col: Int) = (0, 0)
    @State private var editText = ""
    @State private var failure: String?
    @FocusState private var editing: Bool

    private let cellWidth: CGFloat = 110
    private let cellHeight: CGFloat = 36

    private var engine: FormulaEngine {
        let snapshot = cells
        return FormulaEngine { r, c in r < snapshot.count && c < snapshot[r].count ? snapshot[r][c].text : "" }
    }

    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                formulaBar
                Divider()
                ScrollView([.horizontal, .vertical]) {
                    grid.padding(.bottom, 40)
                }
                .background(Color(uiColor: .systemBackground))
                if let failure { Text(failure).font(.caption).foregroundStyle(.red).padding(6) }
            }
            .navigationTitle(title)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Tutup") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Menu("Simpan") {
                        Button("Excel (.xlsx)") { save(csv: false) }
                        Button("CSV") { save(csv: true) }
                    }
                }
                ToolbarItemGroup(placement: .bottomBar) {
                    Button { cells[selected.row][selected.col].bold.toggle() } label: { Image(systemName: "bold") }
                    Spacer()
                    Menu {
                        Button("Tambah baris") { addRow() }
                        Button("Hapus baris ini", role: .destructive) { deleteRow() }
                    } label: { Image(systemName: "rectangle.split.1x2") }
                    Menu {
                        Button("Tambah kolom") { addColumn() }
                        Button("Hapus kolom ini", role: .destructive) { deleteColumn() }
                    } label: { Image(systemName: "rectangle.split.2x1") }
                    Spacer()
                    Menu {
                        ForEach(["SUM", "AVERAGE", "MIN", "MAX", "COUNT"], id: \.self) { fn in Button(fn) { insertFunction(fn) } }
                    } label: { Image(systemName: "function") }
                }
            }
        }
        .task { load() }
    }

    private var formulaBar: some View {
        HStack(spacing: 8) {
            Text("\(Xlsx.columnName(selected.col))\(selected.row + 1)")
                .font(.caption.monospaced().weight(.semibold))
                .frame(width: 52)
            TextField("Isi atau =SUM(A1:A5)", text: $editText)
                .textFieldStyle(.roundedBorder)
                .autocorrectionDisabled()
                .textInputAutocapitalization(.never)
                .focused($editing)
                .onSubmit { commit(); move(down: true) }
            Button { commit() } label: { Image(systemName: "checkmark") }
        }
        .padding(8)
    }

    private var grid: some View {
        let eng = engine
        let columns = cells.first?.count ?? 0
        return VStack(alignment: .leading, spacing: 0) {
            HStack(spacing: 0) {
                Color.clear.frame(width: 40, height: 26)
                ForEach(0..<columns, id: \.self) { c in
                    Text(Xlsx.columnName(c)).font(.caption2.weight(.semibold)).frame(width: cellWidth, height: 26)
                        .background(Color(uiColor: .secondarySystemBackground)).border(Color.gray.opacity(0.25), width: 0.5)
                }
            }
            ForEach(0..<cells.count, id: \.self) { r in
                HStack(spacing: 0) {
                    Text("\(r + 1)").font(.caption2).frame(width: 40, height: cellHeight)
                        .background(Color(uiColor: .secondarySystemBackground)).border(Color.gray.opacity(0.25), width: 0.5)
                    ForEach(0..<columns, id: \.self) { c in
                        let isSel = selected.row == r && selected.col == c
                        let shown = eng.display(r, c)
                        Text(shown)
                            .font(.system(size: 13, weight: cells[r][c].bold ? .bold : .regular))
                            .lineLimit(1)
                            .frame(width: cellWidth - 8, height: cellHeight, alignment: ParityEngine.number(shown) != nil ? .trailing : .leading)
                            .padding(.horizontal, 4)
                            .background(isSel ? Color.depupBlue.opacity(0.12) : Color.clear)
                            .border(isSel ? Color.depupBlue : Color.gray.opacity(0.25), width: isSel ? 2 : 0.5)
                            .contentShape(Rectangle())
                            .onTapGesture { select(r, c) }
                    }
                }
            }
        }
    }

    private func select(_ r: Int, _ c: Int) {
        commit()
        selected = (r, c)
        editText = cells[r][c].text
    }

    private func commit() {
        guard selected.row < cells.count, selected.col < cells[selected.row].count else { return }
        if cells[selected.row][selected.col].text != editText { cells[selected.row][selected.col].text = AiText.clean(editText) }
    }

    private func move(down: Bool) {
        let r = min(selected.row + 1, cells.count - 1)
        selected = (r, selected.col)
        editText = cells[r][selected.col].text
        editing = true
    }

    private func insertFunction(_ fn: String) {
        let col = Xlsx.columnName(selected.col)
        let end = max(1, selected.row)
        editText = "=\(fn)(\(col)1:\(col)\(end))"
        editing = true
    }

    private func addRow() { cells.insert(Array(repeating: SheetCellData(), count: cells.first?.count ?? 5), at: min(selected.row + 1, cells.count)) }
    private func deleteRow() { guard cells.count > 1 else { return }; cells.remove(at: selected.row); selected = (min(selected.row, cells.count - 1), selected.col); editText = cells[selected.row][selected.col].text }
    private func addColumn() { guard (cells.first?.count ?? 0) < 26 else { return }; for r in cells.indices { cells[r].insert(SheetCellData(), at: min(selected.col + 1, cells[r].count)) } }
    private func deleteColumn() {
        guard (cells.first?.count ?? 0) > 1 else { return }
        for r in cells.indices { cells[r].remove(at: selected.col) }
        selected = (selected.row, min(selected.col, (cells.first?.count ?? 1) - 1))
        editText = cells[selected.row][selected.col].text
    }

    private func setMatrix(_ rows: [[String]], bold: Set<Int>) {
        let cols = min(26, max(4, rows.map(\.count).max() ?? 4))
        var m = rows.prefix(500).enumerated().map { r, row in
            (0..<cols).map { c in SheetCellData(text: c < row.count ? row[c] : "", bold: bold.contains(r)) }
        }
        while m.count < 12 { m.append(Array(repeating: SheetCellData(), count: cols)) }
        cells = m
        selected = (0, 0)
        editText = cells[0][0].text
    }

    private func load() {
        if let source {
            title = source.deletingPathExtension().lastPathComponent
            do {
                if source.pathExtension.lowercased() == "xlsx" {
                    setMatrix(try Xlsx.readFirstSheet(try Data(contentsOf: source)), bold: [0])
                } else {
                    let text = try String(contentsOf: source, encoding: .utf8)
                    let sep: Character = text.contains(";") && !text.contains(",") ? ";" : text.contains("\t") ? "\t" : ","
                    setMatrix(text.split(whereSeparator: \.isNewline).map { $0.split(separator: sep, omittingEmptySubsequences: false).map(String.init) }, bold: [0])
                }
            } catch { failure = error.localizedDescription }
            return
        }
        switch template {
        case "invoice":
            title = "Invoice / Kuitansi"
            setMatrix([["No Invoice", "Klien", "Jatuh Tempo", "Total Tagihan"], ["INV-001", "PT Depup Tech", "28 Okt", "15000000"],
                       ["INV-002", "CV Jaya Abadi", "30 Okt", "8500000"], ["TOTAL", "", "", "=SUM(D2:D3)"]], bold: [0, 3])
        case "presensi":
            title = "Presensi & Daftar Hadir"
            setMatrix([["Nama Karyawan", "Hadir", "Izin", "Status"], ["Budi Pratama", "22", "0", "Lengkap"], ["Siti Aminah", "21", "1", "Izin Sakit"],
                       ["RATA-RATA", "=AVERAGE(B2:B3)", "=SUM(C2:C3)", ""]], bold: [0, 3])
        case "keuangan":
            title = "Laporan Keuangan"
            setMatrix([["Produk/Item", "Qty", "Harga Satuan", "Total (Rp)"], ["Produk A", "12", "120000", "=B2*C2"], ["Produk B", "8", "250000", "=B3*C3"],
                       ["Produk C", "15", "12500", "=B4*C4"], ["TOTAL", "=SUM(B2:B4)", "", "=SUM(D2:D4)"]], bold: [0, 4])
        default:
            break
        }
    }

    private func save(csv: Bool) {
        commit()
        let used = cells.lastIndex(where: { $0.contains { !$0.text.isEmpty } }).map { $0 + 1 } ?? 0
        let usedCols = (cells.map { $0.lastIndex(where: { !$0.text.isEmpty }) ?? -1 }.max() ?? -1) + 1
        guard used > 0, usedCols > 0 else { failure = "Spreadsheet masih kosong."; return }
        let rows = Array(cells.prefix(used)).map { Array($0.prefix(usedCols)) }
        let name = SavedName.safe(title, fallback: "Spreadsheet")
        if csv {
            let eng = engine
            let text = rows.indices.map { r in
                rows[r].indices.map { c -> String in
                    let v = eng.display(r, c)
                    return v.contains(",") || v.contains("\"") || v.contains("\n") ? "\"" + v.replacingOccurrences(of: "\"", with: "\"\"") + "\"" : v
                }.joined(separator: ",")
            }.joined(separator: "\n")
            onSave([GeneratedDocument(name: name + ".csv", data: Data(text.utf8))])
        } else {
            let xl: [[XlsxCell]] = rows.map { row in
                row.map { cell in
                    let t = cell.text.trimmingCharacters(in: .whitespaces)
                    if t.isEmpty { return .empty }
                    if t.hasPrefix("=") { return .formula(String(t.dropFirst())) }
                    if let n = ParityEngine.number(t) { return .number(n) }
                    return .text(cell.text)
                }
            }
            let bold = Set(rows.indices.filter { r in rows[r].contains { $0.bold } })
            onSave([GeneratedDocument(name: name + ".xlsx", data: Xlsx.write(sheets: [(name: String(title.prefix(31)), rows: xl, boldRows: bold)]))])
        }
        dismiss()
    }
}

// MARK: - DOCX: baca ke teks berformat & tulis dari teks berformat

enum Docx {
    /// document.xml → NSAttributedString (tebal, miring, garis bawah, ukuran, perataan). Tabel → baris bertab.
    static func read(_ data: Data) throws -> NSAttributedString {
        let files = try Zip.read(data)
        guard let doc = files["word/document.xml"] else { throw ZipError.invalid }
        return DocxReader.parse(doc)
    }

    private static func esc(_ s: String) -> String { Xlsx.escape(s) }

    /// Paragraf & run dari teks berformat → .docx sah (Word, Google Docs, WPS, Android DepUp).
    static func write(_ text: NSAttributedString, extraBody: String = "") -> Data {
        var body = ""
        let full = text.string as NSString
        var lastWasTable = false
        full.enumerateSubstrings(in: NSRange(location: 0, length: full.length), options: [.byParagraphs, .substringNotRequired]) { _, range, _, _ in
            if range.length > 0, let table = text.attribute(.attachment, at: range.location, effectiveRange: nil) as? TableAttachment {
                body += Docx.table(table.rows, widths: table.widths, borders: table.borders, trailingParagraph: false)
                lastWasTable = true
                return
            }
            lastWasTable = false
            var pPr = ""
            if range.length > 0, let style = text.attribute(.paragraphStyle, at: range.location, effectiveRange: nil) as? NSParagraphStyle {
                switch style.alignment {
                case .center: pPr = "<w:jc w:val=\"center\"/>"
                case .right: pPr = "<w:jc w:val=\"right\"/>"
                case .justified: pPr = "<w:jc w:val=\"both\"/>"
                default: break
                }
            }
            body += "<w:p>" + (pPr.isEmpty ? "" : "<w:pPr>\(pPr)</w:pPr>")
            text.enumerateAttributes(in: range, options: []) { attrs, runRange, _ in
                if attrs[.attachment] != nil { return }
                let runText = full.substring(with: runRange)
                var rPr = ""
                if let font = attrs[.font] as? UIFont {
                    let traits = font.fontDescriptor.symbolicTraits
                    if traits.contains(.traitBold) { rPr += "<w:b/>" }
                    if traits.contains(.traitItalic) { rPr += "<w:i/>" }
                    rPr += "<w:sz w:val=\"\(Int((font.pointSize * 2).rounded()))\"/>"
                }
                if let u = attrs[.underlineStyle] as? Int, u != 0 { rPr += "<w:u w:val=\"single\"/>" }
                let pieces = runText.components(separatedBy: "\t")
                var xml = "<w:r>" + (rPr.isEmpty ? "" : "<w:rPr>\(rPr)</w:rPr>")
                for (i, piece) in pieces.enumerated() {
                    if i > 0 { xml += "<w:tab/>" }
                    let lines = piece.components(separatedBy: "\u{2028}")
                    for (j, line) in lines.enumerated() {
                        if j > 0 { xml += "<w:br/>" }
                        if !line.isEmpty { xml += "<w:t xml:space=\"preserve\">\(esc(line))</w:t>" }
                    }
                }
                body += xml + "</w:r>"
            }
            body += "</w:p>"
        }
        // Word mewajibkan paragraf setelah tabel terakhir.
        if lastWasTable { body += "<w:p/>" }
        return package(body + extraBody)
    }

    /// Membungkus isi body menjadi paket .docx (A4, margin 2,54 cm).
    static func package(_ body: String) -> Data {
        let document = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
            "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body>\(body)" +
            "<w:sectPr><w:pgSz w:w=\"11906\" w:h=\"16838\"/><w:pgMar w:top=\"1440\" w:right=\"1440\" w:bottom=\"1440\" w:left=\"1440\" w:header=\"708\" w:footer=\"708\" w:gutter=\"0\"/></w:sectPr>" +
            "</w:body></w:document>"
        let styles = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
            "<w:styles xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:docDefaults><w:rPrDefault><w:rPr>" +
            "<w:rFonts w:ascii=\"Calibri\" w:hAnsi=\"Calibri\" w:cs=\"Calibri\"/><w:sz w:val=\"24\"/></w:rPr></w:rPrDefault>" +
            "<w:pPrDefault><w:pPr><w:spacing w:after=\"120\" w:line=\"276\" w:lineRule=\"auto\"/></w:pPr></w:pPrDefault></w:docDefaults></w:styles>"
        return Zip.write([
            (name: "[Content_Types].xml", data: Data(("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
                "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">" +
                "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>" +
                "<Default Extension=\"xml\" ContentType=\"application/xml\"/>" +
                "<Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/>" +
                "<Override PartName=\"/word/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml\"/>" +
                "</Types>").utf8)),
            (name: "_rels/.rels", data: Data(("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
                "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
                "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"word/document.xml\"/>" +
                "</Relationships>").utf8)),
            (name: "word/_rels/document.xml.rels", data: Data(("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
                "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
                "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/>" +
                "</Relationships>").utf8)),
            (name: "word/document.xml", data: Data(document.utf8)),
            (name: "word/styles.xml", data: Data(styles.utf8))
        ])
    }

    /// Tabel bergaris (atau tanpa garis untuk kolom tanda tangan) dalam XML WordprocessingML.
    static func table(_ rows: [[String]], widths: [Int], borders: Bool = true, boldHeader: Bool = true, trailingParagraph: Bool = true) -> String {
        let total = max(1, widths.reduce(0, +))
        let grid = widths.map { "<w:gridCol w:w=\"\(9026 * $0 / total)\"/>" }.joined()
        let border = borders ? "<w:tblBorders><w:top w:val=\"single\" w:sz=\"4\"/><w:left w:val=\"single\" w:sz=\"4\"/><w:bottom w:val=\"single\" w:sz=\"4\"/><w:right w:val=\"single\" w:sz=\"4\"/><w:insideH w:val=\"single\" w:sz=\"4\"/><w:insideV w:val=\"single\" w:sz=\"4\"/></w:tblBorders>" : ""
        var xml = "<w:tbl><w:tblPr><w:tblW w:w=\"5000\" w:type=\"pct\"/>\(border)</w:tblPr><w:tblGrid>\(grid)</w:tblGrid>"
        for (r, row) in rows.enumerated() {
            xml += "<w:tr>"
            for (c, cell) in row.enumerated() {
                let w = 9026 * (c < widths.count ? widths[c] : 1) / total
                let bold = boldHeader && borders && r == 0 ? "<w:rPr><w:b/></w:rPr>" : ""
                let paras = cell.components(separatedBy: "\n").map { line in
                    "<w:p><w:pPr>\(borders ? "" : "<w:jc w:val=\"center\"/>")</w:pPr><w:r>\(bold)<w:t xml:space=\"preserve\">\(esc(line))</w:t></w:r></w:p>"
                }.joined()
                xml += "<w:tc><w:tcPr><w:tcW w:w=\"\(w)\" w:type=\"dxa\"/></w:tcPr>\(paras)</w:tc>"
            }
            xml += "</w:tr>"
        }
        return xml + "</w:tbl>" + (trailingParagraph ? "<w:p/>" : "")
    }

    static func paragraph(_ text: String, bold: Bool = false, align: String? = nil, size: Int? = nil) -> String {
        let pPr = align.map { "<w:pPr><w:jc w:val=\"\($0)\"/></w:pPr>" } ?? ""
        var rPr = bold ? "<w:b/>" : ""
        if let size { rPr += "<w:sz w:val=\"\(size * 2)\"/>" }
        let runs = text.components(separatedBy: "\n").enumerated().map { i, line in
            (i > 0 ? "<w:br/>" : "") + line.components(separatedBy: "\t").enumerated().map { j, part in
                (j > 0 ? "<w:tab/>" : "") + "<w:t xml:space=\"preserve\">\(esc(part))</w:t>"
            }.joined()
        }.joined()
        return "<w:p>\(pPr)<w:r>\(rPr.isEmpty ? "" : "<w:rPr>\(rPr)</w:rPr>")\(runs)</w:r></w:p>"
    }
}

private final class DocxReader: NSObject, XMLParserDelegate {
    private let out = NSMutableAttributedString()
    private var paragraph = NSMutableAttributedString()
    private var alignment: NSTextAlignment = .natural
    private var bold = false, italic = false, underline = false
    private var size: CGFloat = 12
    private var inRunProps = false
    private var capture = false
    private var tableDepth = 0
    // tabel tingkat atas yang sedang dibaca
    private var tableRows: [[String]] = []
    private var tableWidths: [Int] = []
    private var tableBorders = false
    private var inTablePr = false
    private var cellText = ""
    private var cellParagraphs: [String] = []

    static func parse(_ data: Data) -> NSAttributedString {
        let r = DocxReader()
        let x = XMLParser(data: data)
        x.delegate = r
        x.parse()
        if r.out.length > 0, r.out.string.hasSuffix("\n") { r.out.deleteCharacters(in: NSRange(location: r.out.length - 1, length: 1)) }
        return r.out
    }

    private func local(_ n: String) -> String { n.split(separator: ":").last.map(String.init) ?? n }

    private var font: UIFont {
        var traits: UIFontDescriptor.SymbolicTraits = []
        if bold { traits.insert(.traitBold) }
        if italic { traits.insert(.traitItalic) }
        let base = UIFont.systemFont(ofSize: size)
        return base.fontDescriptor.withSymbolicTraits(traits).map { UIFont(descriptor: $0, size: size) } ?? base
    }

    private func append(_ s: String) {
        if tableDepth > 0 { cellText += s == "\u{2028}" ? "\n" : s; return }
        var attrs: [NSAttributedString.Key: Any] = [.font: font, .foregroundColor: UIColor.label]
        if underline { attrs[.underlineStyle] = NSUnderlineStyle.single.rawValue }
        paragraph.append(NSAttributedString(string: s, attributes: attrs))
    }

    func parser(_ parser: XMLParser, didStartElement name: String, namespaceURI: String?, qualifiedName: String?, attributes: [String: String] = [:]) {
        let n = local(name)
        let val = attributes["w:val"] ?? attributes["val"]
        switch n {
        case "p": if tableDepth == 0 { paragraph = NSMutableAttributedString(); alignment = .natural }
        case "jc":
            if tableDepth == 0 {
                switch val { case "center": alignment = .center; case "right", "end": alignment = .right; case "both", "distribute": alignment = .justified; default: alignment = .natural }
            }
        case "r": bold = false; italic = false; underline = false; size = 12
        case "rPr": inRunProps = true
        case "b": if inRunProps { bold = val != "0" && val != "false" }
        case "i": if inRunProps { italic = val != "0" && val != "false" }
        case "u": if inRunProps { underline = val != "none" }
        case "sz": if inRunProps, let v = val, let half = Double(v) { size = CGFloat(half / 2) }
        case "t": capture = true
        case "tab": append("\t")
        case "br", "cr": append("\u{2028}")
        case "tbl":
            tableDepth += 1
            if tableDepth == 1 { tableRows = []; tableWidths = []; tableBorders = false }
        case "tblPr": if tableDepth == 1 { inTablePr = true }
        case "top", "left", "bottom", "right", "insideH", "insideV", "start", "end":
            if inTablePr, let v = val, v != "nil", v != "none" { tableBorders = true }
        case "tblStyle": if inTablePr { tableBorders = true }   // gaya tabel bawaan (mis. TableGrid) bergaris
        case "gridCol": if tableDepth == 1, let w = attributes["w:w"].flatMap({ Int($0) }) { tableWidths.append(max(1, w)) }
        case "tr": if tableDepth == 1 { tableRows.append([]) }
        case "tc": if tableDepth == 1 { cellText = ""; cellParagraphs = [] }
        default: break
        }
    }

    func parser(_ parser: XMLParser, foundCharacters string: String) { if capture { append(string) } }

    func parser(_ parser: XMLParser, didEndElement name: String, namespaceURI: String?, qualifiedName: String?) {
        switch local(name) {
        case "t": capture = false
        case "rPr": inRunProps = false
        case "tblPr": inTablePr = false
        case "p":
            if tableDepth == 0 {
                let style = NSMutableParagraphStyle()
                style.alignment = alignment
                paragraph.append(NSAttributedString(string: "\n", attributes: [.font: font]))
                paragraph.addAttribute(.paragraphStyle, value: style, range: NSRange(location: 0, length: paragraph.length))
                out.append(paragraph)
            } else {
                cellParagraphs.append(cellText)
                cellText = ""
            }
        case "tc":
            if tableDepth == 1, !tableRows.isEmpty {
                if !cellText.isEmpty { cellParagraphs.append(cellText) }
                tableRows[tableRows.count - 1].append(cellParagraphs.joined(separator: "\n"))
            }
        case "tbl":
            if tableDepth == 1 {
                let cols = tableRows.map(\.count).max() ?? 0
                let rows = tableRows.map { $0 + Array(repeating: "", count: max(0, cols - $0.count)) }
                let widths = tableWidths.count == cols ? tableWidths : Array(repeating: 1, count: cols)
                if cols > 0 {
                    let attachment = TableAttachment(rows: rows, widths: widths, borders: tableBorders)
                    let block = NSMutableAttributedString(attachment: attachment)
                    block.append(NSAttributedString(string: "\n", attributes: [.font: UIFont.systemFont(ofSize: 12)]))
                    out.append(block)
                }
            }
            tableDepth -= 1
        default: break
        }
    }
}

// MARK: - Tabel di editor Word (blok tabel yang bisa diketuk & diedit; disimpan sebagai tabel Word asli)

final class TableAttachment: NSTextAttachment {
    var rows: [[String]]
    var widths: [Int]
    var borders: Bool
    static let renderWidth: CGFloat = 500

    init(rows: [[String]], widths: [Int], borders: Bool) {
        self.rows = rows
        self.widths = widths
        self.borders = borders
        super.init(data: nil, ofType: nil)
        refresh()
    }

    required init?(coder: NSCoder) {
        rows = [[""]]
        widths = [1]
        borders = true
        super.init(coder: coder)
    }

    func refresh() {
        let img = TableAttachment.render(rows: rows, widths: widths, borders: borders, width: Self.renderWidth)
        image = img
        bounds = CGRect(origin: .zero, size: img.size)
    }

    static func render(rows: [[String]], widths: [Int], borders: Bool, width: CGFloat) -> UIImage {
        let cols = max(1, rows.map(\.count).max() ?? 1)
        let ws = widths.count == cols ? widths.map(CGFloat.init) : Array(repeating: 1, count: cols)
        let total = max(1, ws.reduce(0, +))
        let colW = ws.map { $0 / total * width }
        let font = UIFont.systemFont(ofSize: 11)
        let bold = UIFont.boldSystemFont(ofSize: 11)
        let heights = rows.enumerated().map { r, row -> CGFloat in
            let h = row.enumerated().map { c, cell -> CGFloat in
                let f = borders && r == 0 ? bold : font
                return ceil((cell as NSString).boundingRect(with: CGSize(width: max(10, colW[min(c, cols - 1)] - 8), height: .greatestFiniteMagnitude),
                                                            options: [.usesLineFragmentOrigin], attributes: [.font: f], context: nil).height)
            }.max() ?? 14
            return max(22, h + 8)
        }
        let size = CGSize(width: width, height: max(22, heights.reduce(0, +)))
        let format = UIGraphicsImageRendererFormat()
        format.scale = 2
        return UIGraphicsImageRenderer(size: size, format: format).image { _ in
            UIColor.white.setFill()
            UIRectFill(CGRect(origin: .zero, size: size))
            var y: CGFloat = 0
            for (r, row) in rows.enumerated() {
                var x: CGFloat = 0
                if borders && r == 0 { UIColor(white: 0.93, alpha: 1).setFill(); UIRectFill(CGRect(x: 0, y: y, width: width, height: heights[r])) }
                for c in 0..<cols {
                    let w = colW[c]
                    let text = c < row.count ? row[c] : ""
                    let style = NSMutableParagraphStyle()
                    style.alignment = borders ? .left : .center
                    (text as NSString).draw(with: CGRect(x: x + 4, y: y + 4, width: w - 8, height: heights[r] - 8), options: [.usesLineFragmentOrigin],
                                            attributes: [.font: borders && r == 0 ? bold : font, .foregroundColor: UIColor.black, .paragraphStyle: style], context: nil)
                    if borders {
                        UIColor(white: 0.55, alpha: 1).setStroke()
                        UIBezierPath(rect: CGRect(x: x + 0.25, y: y + 0.25, width: w - 0.5, height: heights[r] - 0.5)).stroke()
                    }
                    x += w
                }
                y += heights[r]
            }
            if !borders {
                UIColor(white: 0.8, alpha: 1).setStroke()
                let p = UIBezierPath(rect: CGRect(origin: .zero, size: size).insetBy(dx: 0.5, dy: 0.5))
                p.setLineDash([3, 3], count: 2, phase: 0)
                p.stroke()
            }
        }
    }
}

extension TableAttachment: Identifiable {}

struct TableEditorSheet: View {
    @State var rows: [[String]]
    @State var borders: Bool
    let onDone: ([[String]], Bool) -> Void
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            ScrollView([.horizontal, .vertical]) {
                VStack(alignment: .leading, spacing: 0) {
                    ForEach(rows.indices, id: \.self) { r in
                        HStack(spacing: 0) {
                            ForEach(rows[r].indices, id: \.self) { c in
                                TextField("", text: Binding(get: { rows[r][c] }, set: { rows[r][c] = $0 }), axis: .vertical)
                                    .font(.system(size: 14, weight: r == 0 && borders ? .semibold : .regular))
                                    .padding(6)
                                    .frame(width: 140, alignment: .leading)
                                    .frame(minHeight: 40)
                                    .border(Color.gray.opacity(0.4), width: 0.5)
                            }
                        }
                    }
                }
                .padding()
            }
            .navigationTitle("Edit Tabel")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Batal") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) { Button("Selesai") { onDone(rows, borders); dismiss() } }
                ToolbarItemGroup(placement: .bottomBar) {
                    Menu {
                        Button("Tambah baris") { rows.append(Array(repeating: "", count: rows.first?.count ?? 1)) }
                        Button("Hapus baris terakhir", role: .destructive) { if rows.count > 1 { rows.removeLast() } }
                    } label: { Label("Baris", systemImage: "rectangle.split.1x2") }
                    Menu {
                        Button("Tambah kolom") { for r in rows.indices { rows[r].append("") } }
                        Button("Hapus kolom terakhir", role: .destructive) { if (rows.first?.count ?? 0) > 1 { for r in rows.indices { rows[r].removeLast() } } }
                    } label: { Label("Kolom", systemImage: "rectangle.split.2x1") }
                    Toggle("Garis", isOn: $borders).toggleStyle(.button)
                }
            }
        }
    }
}

/// PDF berhalaman dari teks berformat (termasuk gambar & tabel), memakai tata letak TextKit.
enum AttributedPDF {
    static func render(_ text: NSAttributedString, paper: CGRect = CGRect(x: 0, y: 0, width: 595.28, height: 841.89), inset: CGFloat = 56) -> Data {
        let storage = NSTextStorage(attributedString: text)
        let layout = NSLayoutManager()
        storage.addLayoutManager(layout)
        let size = CGSize(width: paper.width - 2 * inset, height: paper.height - 2 * inset)
        var containers: [NSTextContainer] = []
        while containers.count < 2000 {
            let c = NSTextContainer(size: size)
            c.lineFragmentPadding = 0
            layout.addTextContainer(c)
            containers.append(c)
            layout.ensureLayout(for: c)
            let range = layout.glyphRange(for: c)
            if NSMaxRange(range) >= layout.numberOfGlyphs || range.length == 0 { break }
        }
        return UIGraphicsPDFRenderer(bounds: paper).pdfData { ctx in
            for c in containers {
                ctx.beginPage()
                let range = layout.glyphRange(for: c)
                let origin = CGPoint(x: inset, y: inset)
                layout.drawBackground(forGlyphRange: range, at: origin)
                layout.drawGlyphs(forGlyphRange: range, at: origin)
            }
        }
    }

    /// Salinan dengan teks hitam (warna sistem/dinamis bisa putih saat mode gelap).
    static func black(_ text: NSAttributedString) -> NSAttributedString {
        let m = NSMutableAttributedString(attributedString: text)
        m.addAttribute(.foregroundColor, value: UIColor.black, range: NSRange(location: 0, length: m.length))
        return m
    }
}

// MARK: - Template dokumen (sama dengan DocTemplates.kt Android)

enum DocTemplate: String, CaseIterable, Identifiable {
    case izin, ketRtRw, undangan, notulen, hadir, proposal
    var id: String { rawValue }

    var title: String {
        switch self {
        case .izin: "Surat Izin"
        case .ketRtRw: "Surat Keterangan RT/RW"
        case .undangan: "Undangan Rapat"
        case .notulen: "Notulen Rapat"
        case .hadir: "Daftar Hadir"
        case .proposal: "Proposal Kegiatan"
        }
    }

    var subtitle: String {
        switch self {
        case .izin: "Tidak masuk sekolah/kerja"
        case .ketRtRw: "Domisili / pengantar"
        case .undangan: "Rapat pengurus / warga"
        case .notulen: "Catatan hasil rapat"
        case .hadir: "Tabel nama & tanda tangan"
        case .proposal: "Latar belakang s.d. anggaran"
        }
    }

    private static var today: String { Date().formatted(.dateTime.day().month(.wide).year().locale(Locale(identifier: "id_ID"))) }
    private static var year: Int { Calendar.current.component(.year, from: Date()) }
    private static var romanMonth: String { ["I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X", "XI", "XII"][Calendar.current.component(.month, from: Date()) - 1] }

    private static func kop(_ org: String, _ addr: String) -> String {
        Docx.paragraph(org, bold: true, align: "center") + Docx.paragraph(addr, align: "center") +
            Docx.paragraph("______________________________________________________________", align: "center")
    }

    private static func sign(_ left: String, _ right: String) -> String {
        Docx.table([["\(left)\n\n\n\n(Nama Lengkap)", "\(right)\n\n\n\n(Nama Lengkap)"]], widths: [1, 1], borders: false)
    }

    private static func simple(_ header: [String], rows: Int, widths: [Int], numbered: Bool = false, fill: [String] = [], footer: [String]? = nil) -> String {
        var t = [header]
        for r in 1...rows {
            t.append(header.indices.map { c in c == 0 && numbered ? "\(r)" : c == 1 && r - 1 < fill.count ? fill[r - 1] : "" })
        }
        if let footer { t.append(footer) }
        return Docx.table(t, widths: widths)
    }

    private static func heading(_ s: String) -> String { Docx.paragraph(s, bold: true, size: 13) }
    private static func title(_ s: String) -> String { Docx.paragraph(s.uppercased(), bold: true, align: "center", size: 16) }

    /// Body WordprocessingML siap dibungkus menjadi .docx (tabel asli, bukan teks bertab).
    var body: String {
        let p = Docx.paragraph
        switch self {
        case .izin:
            return [p("(Kota), \(Self.today)", false, "right", nil),
                p("Kepada Yth.\n(Bapak/Ibu Wali Kelas / Atasan)\n(Nama Sekolah / Instansi)\ndi Tempat", false, nil, nil),
                p("Perihal: Permohonan Izin", true, nil, nil) + p("Dengan hormat,", false, nil, nil),
                p("Yang bertanda tangan di bawah ini:", false, nil, nil),
                p("Nama\t: (Nama Lengkap)\nKelas / Jabatan\t: (Kelas / Jabatan)\nAlamat\t: (Alamat)", false, nil, nil),
                p("Dengan ini memohon izin tidak dapat mengikuti kegiatan belajar / bekerja pada hari (Hari), tanggal (Tanggal), dikarenakan (alasan, misalnya: sakit / keperluan keluarga yang tidak dapat ditinggalkan).", false, "both", nil),
                p("Demikian surat izin ini saya sampaikan. Atas perhatian dan izin yang diberikan, saya ucapkan terima kasih.", false, "both", nil),
                p("Hormat saya,\n\n\n\n(Nama Lengkap)", false, "right", nil)].joined()
        case .ketRtRw:
            return [Self.kop("RUKUN TETANGGA (RT) 00X / RUKUN WARGA (RW) 00X", "Kelurahan (Nama) · Kecamatan (Nama) · Kota/Kabupaten (Nama)"),
                p("SURAT KETERANGAN", true, "center", nil) + p("Nomor: (00X)/RT.00X/RW.00X/\(Self.romanMonth)/\(Self.year)", false, "center", nil),
                p("Yang bertanda tangan di bawah ini Ketua RT 00X / RW 00X Kelurahan (Nama), Kecamatan (Nama), menerangkan bahwa:", false, "both", nil),
                p("Nama\t: (Nama Lengkap)\nNIK\t: (16 digit NIK)\nTempat/Tgl Lahir\t: (Kota, Tanggal)\nJenis Kelamin\t: (Laki-laki / Perempuan)\nAgama\t: (Agama)\nPekerjaan\t: (Pekerjaan)\nAlamat\t: (Alamat Lengkap)", false, nil, nil),
                p("Adalah benar warga kami yang berdomisili di alamat tersebut di atas. Surat keterangan ini dibuat untuk keperluan (keperluan, misalnya: pengurusan KTP / KK / SKCK / domisili usaha).", false, "both", nil),
                p("Demikian surat keterangan ini dibuat dengan sebenarnya untuk dapat dipergunakan sebagaimana mestinya.", false, "both", nil),
                p("(Kota), \(Self.today)", false, "right", nil) + Self.sign("Ketua RW 00X", "Ketua RT 00X")].joined()
        case .undangan:
            return [Self.kop("(NAMA ORGANISASI, misalnya KARANG TARUNA ...)", "Sekretariat: (Alamat) · Telp: (Nomor)"),
                p("Nomor\t: (00X)/UND/(Singkatan)/\(Self.romanMonth)/\(Self.year)\nLampiran\t: -\nPerihal\t: Undangan Rapat", false, nil, nil),
                p("(Kota), \(Self.today)", false, "right", nil),
                p("Kepada Yth.\nSeluruh Pengurus dan Anggota\n(Nama Organisasi)\ndi Tempat", false, nil, nil) + p("Dengan hormat,", false, nil, nil),
                p("Sehubungan dengan (maksud rapat, misalnya: persiapan kegiatan peringatan HUT RI), kami mengundang Saudara/i untuk hadir dalam rapat yang akan dilaksanakan pada:", false, "both", nil),
                p("Hari / Tanggal\t: (Hari, Tanggal)\nWaktu\t: (Jam) WIB s.d. selesai\nTempat\t: (Lokasi)\nAcara\t: 1. (Agenda pertama)\n\t  2. (Agenda kedua)\n\t  3. Lain-lain", false, nil, nil),
                p("Mengingat pentingnya acara tersebut, kami mengharapkan kehadiran Saudara/i tepat waktu. Atas perhatian dan kehadirannya, kami ucapkan terima kasih.", false, "both", nil),
                Self.sign("Ketua", "Sekretaris")].joined()
        case .notulen:
            return [Self.title("Notulen Rapat") + p("(Nama Organisasi)", false, "center", nil),
                p("Hari / Tanggal\t: (Hari, Tanggal)\nWaktu\t: (Jam mulai) – (Jam selesai) WIB\nTempat\t: (Lokasi)\nPimpinan Rapat\t: (Nama)\nNotulis\t: (Nama)\nPeserta\t: (Jumlah) orang (daftar hadir terlampir)", false, nil, nil),
                Self.heading("Agenda") + p("1. (Agenda pertama)\n2. (Agenda kedua)\n3. Lain-lain", false, nil, nil),
                Self.heading("Jalannya Rapat"),
                p("1. Rapat dibuka oleh (Nama) pada pukul (Jam) WIB.\n2. (Ringkasan pembahasan agenda pertama).\n3. (Ringkasan pembahasan agenda kedua).", false, "both", nil),
                Self.heading("Keputusan / Kesimpulan") + p("1. (Keputusan pertama)\n2. (Keputusan kedua)", false, nil, nil),
                Self.heading("Tindak Lanjut") + Self.simple(["No", "Kegiatan", "Penanggung Jawab", "Batas Waktu"], rows: 4, widths: [1, 5, 3, 3]),
                p("Rapat ditutup pada pukul (Jam) WIB.", false, nil, nil) + p("(Kota), \(Self.today)", false, "right", nil),
                Self.sign("Pimpinan Rapat", "Notulis")].joined()
        case .hadir:
            return [Self.title("Daftar Hadir"),
                p("Kegiatan\t: (Nama Kegiatan / Rapat)\nHari / Tanggal\t: (Hari, Tanggal)\nTempat\t: (Lokasi)", false, nil, nil),
                Self.simple(["No", "Nama", "Jabatan / Alamat", "No. HP", "Tanda Tangan"], rows: 20, widths: [1, 5, 4, 3, 3], numbered: true),
                p("(Kota), \(Self.today)", false, "right", nil) + p("Mengetahui,\nKetua\n\n\n\n(Nama Lengkap)", false, "right", nil)].joined()
        case .proposal:
            return [Self.title("Proposal Kegiatan") + p("(Nama Kegiatan) · (Nama Organisasi) · \(Self.year)", false, "center", nil),
                Self.heading("I. Latar Belakang") + p("(Jelaskan alasan kegiatan perlu diadakan, kondisi saat ini, dan manfaatnya bagi warga / anggota.)", false, "both", nil),
                Self.heading("II. Nama Kegiatan") + p("(Nama lengkap kegiatan)", false, nil, nil),
                Self.heading("III. Tujuan") + p("1. (Tujuan pertama)\n2. (Tujuan kedua)\n3. (Tujuan ketiga)", false, nil, nil),
                Self.heading("IV. Sasaran / Peserta") + p("(Siapa peserta dan perkiraan jumlahnya.)", false, nil, nil),
                Self.heading("V. Waktu dan Tempat") + p("Hari / Tanggal\t: (Hari, Tanggal)\nWaktu\t: (Jam) WIB s.d. selesai\nTempat\t: (Lokasi)", false, nil, nil),
                Self.heading("VI. Susunan Panitia"),
                Self.simple(["No", "Jabatan", "Nama"], rows: 6, widths: [1, 4, 6], numbered: true,
                            fill: ["Penanggung Jawab", "Ketua Pelaksana", "Sekretaris", "Bendahara", "Sie Acara", "Sie Perlengkapan"]),
                Self.heading("VII. Rencana Anggaran Biaya"),
                Self.simple(["No", "Uraian", "Jumlah", "Harga Satuan (Rp)", "Total (Rp)"], rows: 5, widths: [1, 5, 2, 3, 3], numbered: true,
                            footer: ["", "TOTAL", "", "", "(Jumlah)"]),
                Self.heading("VIII. Penutup"),
                p("Demikian proposal ini kami susun. Besar harapan kami atas dukungan dan partisipasi Bapak/Ibu demi terlaksananya kegiatan ini. Atas perhatian dan bantuannya, kami ucapkan terima kasih.", false, "both", nil),
                p("(Kota), \(Self.today)", false, "right", nil) + Self.sign("Ketua Pelaksana", "Sekretaris"),
                p("Mengetahui,\n(Ketua Organisasi / Lurah / Kepala Desa)\n\n\n\n(Nama Lengkap)", false, "center", nil)].joined()
        }
    }

    var docx: Data { Docx.package(body) }
}

struct DocTemplatePicker: View {
    let onSave: ([GeneratedDocument]) -> Void
    let onEdit: (URL) -> Void
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            List {
                Section {
                    Text("Template disimpan sebagai .docx dengan tabel asli. Isian bertanda (kurung) tinggal diganti.")
                        .font(.footnote).foregroundStyle(.secondary)
                }
                ForEach(DocTemplate.allCases) { t in
                    Button {
                        onSave([GeneratedDocument(name: SavedName.safe(t.title, fallback: "Template") + ".docx", data: t.docx)])
                        dismiss()
                    } label: {
                        VStack(alignment: .leading) {
                            Text(t.title).font(.body.weight(.medium)).foregroundStyle(.primary)
                            Text(t.subtitle).font(.caption).foregroundStyle(.secondary)
                        }
                    }
                }
            }
            .navigationTitle("Template Dokumen")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Tutup") { dismiss() } } }
        }
    }
}

// MARK: - Editor Word (rich text: tebal, miring, garis bawah, ukuran, perataan; simpan .docx / .rtf / .pdf)

@MainActor final class RichTextController: NSObject, ObservableObject, UITextViewDelegate {
    let textView = UITextView()
    @Published var editingTable: TableAttachment?

    override init() {
        super.init()
        textView.delegate = self
        textView.font = UIFont.systemFont(ofSize: 12)
        textView.allowsEditingTextAttributes = true
        textView.backgroundColor = .systemBackground
        textView.textContainerInset = UIEdgeInsets(top: 16, left: 12, bottom: 40, right: 12)
        textView.alwaysBounceVertical = true
        textView.typingAttributes = [.font: UIFont.systemFont(ofSize: 12), .foregroundColor: UIColor.label]
    }

    private var range: NSRange { textView.selectedRange }

    /// Ketuk tabel → buka editor tabel.
    func textView(_ textView: UITextView, primaryActionFor textItem: UITextItem, defaultAction: UIAction) -> UIAction? {
        if case .textAttachment(let attachment) = textItem.content, let table = attachment as? TableAttachment {
            return UIAction { [weak self] _ in self?.editingTable = table }
        }
        return defaultAction
    }

    private func applyFont(_ transform: (UIFont) -> UIFont) {
        let r = range
        if r.length == 0 {
            let f = (textView.typingAttributes[.font] as? UIFont) ?? UIFont.systemFont(ofSize: 12)
            textView.typingAttributes[.font] = transform(f)
            return
        }
        let storage = textView.textStorage
        storage.beginEditing()
        storage.enumerateAttribute(.font, in: r, options: []) { value, sub, _ in
            let f = (value as? UIFont) ?? UIFont.systemFont(ofSize: 12)
            storage.addAttribute(.font, value: transform(f), range: sub)
        }
        storage.endEditing()
    }

    func toggle(_ trait: UIFontDescriptor.SymbolicTraits) {
        applyFont { f in
            var t = f.fontDescriptor.symbolicTraits
            if t.contains(trait) { t.remove(trait) } else { t.insert(trait) }
            return f.fontDescriptor.withSymbolicTraits(t).map { UIFont(descriptor: $0, size: f.pointSize) } ?? f
        }
    }

    func resize(_ delta: CGFloat) {
        applyFont { f in UIFont(descriptor: f.fontDescriptor, size: max(6, min(72, f.pointSize + delta))) }
    }

    func toggleUnderline() {
        let r = range
        if r.length == 0 {
            let on = (textView.typingAttributes[.underlineStyle] as? Int ?? 0) != 0
            textView.typingAttributes[.underlineStyle] = on ? 0 : NSUnderlineStyle.single.rawValue
            return
        }
        let on = (textView.textStorage.attribute(.underlineStyle, at: r.location, effectiveRange: nil) as? Int ?? 0) != 0
        textView.textStorage.addAttribute(.underlineStyle, value: on ? 0 : NSUnderlineStyle.single.rawValue, range: r)
    }

    func align(_ a: NSTextAlignment) {
        let text = textView.textStorage.string as NSString
        let para = text.paragraphRange(for: range)
        let style = NSMutableParagraphStyle()
        style.alignment = a
        if para.length > 0 { textView.textStorage.addAttribute(.paragraphStyle, value: style, range: para) }
        textView.typingAttributes[.paragraphStyle] = style
    }

    /// Sisipkan tabel baru (3 kolom × 3 baris) sebagai paragraf sendiri di posisi kursor.
    func insertTable() {
        let table = TableAttachment(rows: [["Kolom 1", "Kolom 2", "Kolom 3"], ["", "", ""], ["", "", ""]], widths: [1, 1, 1], borders: true)
        let block = NSMutableAttributedString(string: "\n", attributes: [.font: UIFont.systemFont(ofSize: 12)])
        block.append(NSAttributedString(attachment: table))
        block.append(NSAttributedString(string: "\n", attributes: [.font: UIFont.systemFont(ofSize: 12), .foregroundColor: UIColor.label]))
        let r = textView.selectedRange
        textView.textStorage.replaceCharacters(in: r, with: block)
        textView.selectedRange = NSRange(location: r.location + block.length, length: 0)
    }

    /// Edit tabel terdekat dari kursor (cadangan bila ketukan langsung pada tabel tidak tersedia).
    @discardableResult
    func editTableAtCursor() -> Bool {
        let storage = textView.textStorage
        guard storage.length > 0 else { return false }
        let para = (storage.string as NSString).paragraphRange(for: textView.selectedRange)
        let search = NSRange(location: max(0, para.location - 1), length: min(storage.length - max(0, para.location - 1), para.length + 2))
        var found: TableAttachment?
        storage.enumerateAttribute(.attachment, in: search, options: []) { value, _, stop in
            if let t = value as? TableAttachment { found = t; stop.pointee = true }
        }
        editingTable = found
        return found != nil
    }

    /// Terapkan hasil edit tabel dan minta tata letak ulang.
    func update(_ table: TableAttachment, rows: [[String]], borders: Bool) {
        table.rows = rows
        table.borders = borders
        let cols = rows.map(\.count).max() ?? 1
        if table.widths.count != cols { table.widths = Array(repeating: 1, count: cols) }
        table.refresh()
        let storage = textView.textStorage
        storage.enumerateAttribute(.attachment, in: NSRange(location: 0, length: storage.length), options: []) { value, range, stop in
            if (value as? TableAttachment) === table {
                storage.edited(.editedAttributes, range: range, changeInLength: 0)
                stop.pointee = true
            }
        }
        textView.setNeedsLayout()
    }

    /// Salinan untuk disimpan: warna label sistem diganti hitam agar tetap terbaca di Word.
    var exportText: NSAttributedString {
        AttributedPDF.black(textView.attributedText ?? NSAttributedString())
    }
}

private struct RichTextView: UIViewRepresentable {
    let controller: RichTextController
    func makeUIView(context: Context) -> UITextView { controller.textView }
    func updateUIView(_ view: UITextView, context: Context) {}
}

struct DocEditorView: View {
    let source: URL?
    let onSave: ([GeneratedDocument]) -> Void
    @Environment(\.dismiss) private var dismiss
    @StateObject private var editor = RichTextController()
    @State private var title = "Dokumen Baru"
    @State private var notice: String?
    @State private var failure: String?

    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                if let notice { Text(notice).font(.caption).foregroundStyle(.secondary).padding(6) }
                if let failure { Text(failure).font(.caption).foregroundStyle(.red).padding(6) }
                RichTextView(controller: editor)
            }
            .navigationTitle(title)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Tutup") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Menu("Simpan") {
                        Button("Word (.docx)") { save("docx") }
                        Button("RTF") { save("rtf") }
                        Button("PDF") { save("pdf") }
                    }
                }
                ToolbarItemGroup(placement: .keyboard) { formatButtons }
                ToolbarItemGroup(placement: .bottomBar) { formatButtons }
            }
        }
        .task { load() }
        .sheet(item: $editor.editingTable) { table in
            TableEditorSheet(rows: table.rows, borders: table.borders) { rows, borders in editor.update(table, rows: rows, borders: borders) }
        }
    }

    @ViewBuilder private var formatButtons: some View {
        Button { editor.toggle(.traitBold) } label: { Image(systemName: "bold") }
        Button { editor.toggle(.traitItalic) } label: { Image(systemName: "italic") }
        Button { editor.toggleUnderline() } label: { Image(systemName: "underline") }
        Menu {
            Button { editor.insertTable() } label: { Label("Sisipkan tabel", systemImage: "plus") }
            Button { if !editor.editTableAtCursor() { failure = "Letakkan kursor tepat di bawah atau di atas tabel." } } label: { Label("Edit tabel di kursor", systemImage: "pencil") }
        } label: { Image(systemName: "tablecells") }
            .accessibilityLabel("Tabel")
        Button { editor.resize(-1) } label: { Image(systemName: "textformat.size.smaller") }
        Button { editor.resize(1) } label: { Image(systemName: "textformat.size.larger") }
        Menu {
            Button { editor.align(.left) } label: { Label("Rata kiri", systemImage: "text.alignleft") }
            Button { editor.align(.center) } label: { Label("Tengah", systemImage: "text.aligncenter") }
            Button { editor.align(.right) } label: { Label("Rata kanan", systemImage: "text.alignright") }
            Button { editor.align(.justified) } label: { Label("Rata kiri-kanan", systemImage: "text.justify") }
        } label: { Image(systemName: "text.alignleft") }
    }

    private func load() {
        guard let source, editor.textView.text.isEmpty else { return }
        title = source.deletingPathExtension().lastPathComponent
        do {
            switch source.pathExtension.lowercased() {
            case "docx":
                let data = try Data(contentsOf: source)
                let text = try Docx.read(data)
                let m = NSMutableAttributedString(attributedString: text)
                m.addAttribute(.foregroundColor, value: UIColor.label, range: NSRange(location: 0, length: m.length))
                editor.textView.attributedText = m
                if (try? Zip.read(data)["word/document.xml"].map { String(decoding: $0, as: UTF8.self).contains("<w:tbl") }) == true {
                    notice = "Ketuk tabel untuk mengedit isinya."
                }
            case "rtf":
                editor.textView.attributedText = try NSAttributedString(url: source, options: [.documentType: NSAttributedString.DocumentType.rtf], documentAttributes: nil)
            default:
                editor.textView.text = try DocText.read(source)
            }
        } catch { failure = error.localizedDescription }
    }

    private func save(_ format: String) {
        let text = editor.exportText
        guard text.length > 0 else { failure = "Dokumen masih kosong."; return }
        let name = SavedName.safe(title, fallback: "Dokumen")
        switch format {
        case "rtf":
            guard let data = try? text.data(from: NSRange(location: 0, length: text.length), documentAttributes: [.documentType: NSAttributedString.DocumentType.rtf]) else { failure = OfficeError.cannotWrite.localizedDescription; return }
            onSave([GeneratedDocument(name: name + ".rtf", data: data)])
        case "pdf":
            onSave([GeneratedDocument(name: name + ".pdf", data: AttributedPDF.render(text))])
        default:
            onSave([GeneratedDocument(name: name + ".docx", data: Docx.write(text))])
        }
        dismiss()
    }
}

// MARK: - Pustaka tanda tangan (coretan disimpan sebagai PKDrawing di perangkat)

struct SavedSignature: Identifiable {
    let url: URL
    let drawing: PKDrawing
    var id: URL { url }
}

enum SignatureLibrary {
    private static var folder: URL {
        let dir = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0].appendingPathComponent("signatures", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir
    }

    static func all() -> [SavedSignature] {
        let urls = (try? FileManager.default.contentsOfDirectory(at: folder, includingPropertiesForKeys: [.contentModificationDateKey])) ?? []
        return urls.filter { $0.pathExtension == "drawing" }
            .sorted { $0.lastPathComponent > $1.lastPathComponent }
            .compactMap { url -> SavedSignature? in
                guard let data = try? Data(contentsOf: url), let drawing = try? PKDrawing(data: data) else { return nil }
                return SavedSignature(url: url, drawing: drawing)
            }
    }

    static func save(_ drawing: PKDrawing) {
        let url = folder.appendingPathComponent("ttd_\(Int(Date().timeIntervalSince1970 * 1000)).drawing")
        try? drawing.dataRepresentation().write(to: url, options: [.atomic, .completeFileProtection])
    }

    static func delete(_ url: URL) { try? FileManager.default.removeItem(at: url) }

    static func thumbnail(_ drawing: PKDrawing) -> UIImage {
        let b = drawing.bounds.insetBy(dx: -8, dy: -8)
        return drawing.image(from: b, scale: 2)
    }
}

struct SignatureLibraryView: View {
    let onPick: ((PKDrawing) -> Void)?
    @Environment(\.dismiss) private var dismiss
    @State private var items: [SavedSignature] = []
    @State private var drawing = PKDrawing()
    @State private var adding = false

    var body: some View {
        NavigationStack {
            List {
                if items.isEmpty { Text("Belum ada tanda tangan tersimpan.").foregroundStyle(.secondary) }
                ForEach(items) { item in
                    Button {
                        if let onPick { onPick(item.drawing); dismiss() }
                    } label: {
                        Image(uiImage: SignatureLibrary.thumbnail(item.drawing)).resizable().scaledToFit().frame(height: 70)
                            .frame(maxWidth: .infinity).background(Color.white).clipShape(RoundedRectangle(cornerRadius: 8))
                    }
                    .buttonStyle(.plain)
                }
                .onDelete { idx in idx.map { items[$0].url }.forEach(SignatureLibrary.delete); items = SignatureLibrary.all() }
            }
            .navigationTitle("Pustaka Tanda Tangan")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Tutup") { dismiss() } }
                ToolbarItem(placement: .primaryAction) { Button { adding = true } label: { Image(systemName: "plus") } }
            }
            .sheet(isPresented: $adding) {
                NavigationStack {
                    VStack {
                        SignaturePad(drawing: $drawing).frame(height: 220).padding()
                        Spacer()
                    }
                    .navigationTitle("Tanda tangan baru")
                    .navigationBarTitleDisplayMode(.inline)
                    .toolbar {
                        ToolbarItem(placement: .cancellationAction) { Button("Batal") { adding = false } }
                        ToolbarItem(placement: .confirmationAction) {
                            Button("Simpan") { SignatureLibrary.save(drawing); drawing = PKDrawing(); items = SignatureLibrary.all(); adding = false }
                                .disabled(drawing.strokes.isEmpty)
                        }
                    }
                }
                .presentationDetents([.medium])
            }
        }
        .onAppear { items = SignatureLibrary.all() }
    }
}

struct SignaturePad: UIViewRepresentable {
    @Binding var drawing: PKDrawing

    func makeUIView(context: Context) -> PKCanvasView {
        let c = PKCanvasView()
        c.drawingPolicy = .anyInput
        c.tool = PKInkingTool(.pen, color: .black, width: 3)
        c.backgroundColor = .white
        c.overrideUserInterfaceStyle = .light
        c.layer.cornerRadius = 10
        c.delegate = context.coordinator
        return c
    }

    func updateUIView(_ c: PKCanvasView, context: Context) {
        if c.drawing.strokes.count != drawing.strokes.count { c.drawing = drawing }
    }

    func makeCoordinator() -> Coordinator { Coordinator(self) }

    @MainActor final class Coordinator: NSObject, PKCanvasViewDelegate {
        let parent: SignaturePad
        init(_ parent: SignaturePad) { self.parent = parent }
        func canvasViewDrawingDidChange(_ canvasView: PKCanvasView) { parent.drawing = canvasView.drawing }
    }
}

// MARK: - Coret-coret PDF (pena, stabilo, penghapus, catatan teks, sorot teks pilihan)

enum AnnotateTool: String, CaseIterable { case browse, pen, marker, eraser }

@MainActor final class AnnotateController: NSObject, ObservableObject, PDFPageOverlayViewProvider {
    let pdfView = PDFView()
    @Published var tool: AnnotateTool = .browse { didSet { applyTool() } }
    @Published var color: UIColor = .systemRed { didSet { applyTool() } }
    private var canvases: [PDFPage: PKCanvasView] = [:]

    override init() {
        super.init()
        pdfView.autoScales = true
        pdfView.displayMode = .singlePageContinuous
        pdfView.displayDirection = .vertical
        pdfView.pageOverlayViewProvider = self
    }

    func load(_ url: URL) -> Bool {
        guard let doc = PDFDocument(url: url), !doc.isLocked else { return false }
        pdfView.document = doc
        return true
    }

    nonisolated func pdfView(_ view: PDFView, overlayViewFor page: PDFPage) -> UIView? {
        MainActor.assumeIsolated {
            if let existing = canvases[page] { return existing }
            let canvas = PKCanvasView()
            canvas.backgroundColor = .clear
            canvas.isOpaque = false
            canvas.drawingPolicy = .anyInput
            canvases[page] = canvas
            configure(canvas)
            return canvas
        }
    }

    private func configure(_ canvas: PKCanvasView) {
        switch tool {
        case .browse: canvas.isUserInteractionEnabled = false
        case .pen: canvas.isUserInteractionEnabled = true; canvas.tool = PKInkingTool(.pen, color: color, width: 3)
        case .marker: canvas.isUserInteractionEnabled = true; canvas.tool = PKInkingTool(.marker, color: UIColor.systemYellow, width: 18)
        case .eraser: canvas.isUserInteractionEnabled = true; canvas.tool = PKEraserTool(.vector)
        }
    }

    private func applyTool() {
        pdfView.isInMarkupMode = tool != .browse
        canvases.values.forEach(configure)
    }

    /// Sorot teks yang sedang dipilih (mode Lihat) dengan stabilo kuning.
    func highlightSelection() -> Bool {
        guard let selection = pdfView.currentSelection else { return false }
        var added = false
        for line in selection.selectionsByLine() {
            for page in line.pages {
                let a = PDFAnnotation(bounds: line.bounds(for: page), forType: .highlight, withProperties: nil)
                a.color = UIColor.systemYellow.withAlphaComponent(0.5)
                page.addAnnotation(a)
                added = true
            }
        }
        pdfView.clearSelection()
        return added
    }

    func addNote(_ text: String) {
        guard let page = pdfView.currentPage, !text.isEmpty else { return }
        let b = page.bounds(for: .cropBox)
        let rect = CGRect(x: b.midX - 110, y: b.midY - 30, width: 220, height: 60)
        let a = PDFAnnotation(bounds: rect, forType: .freeText, withProperties: nil)
        a.contents = AiText.clean(text)
        a.font = UIFont.systemFont(ofSize: 14, weight: .medium)
        a.fontColor = color
        a.color = UIColor.systemYellow.withAlphaComponent(0.25)
        page.addAnnotation(a)
    }

    /// Coretan PencilKit → anotasi tinta PDF (vektor) per halaman; stabilo jadi tinta tebal transparan.
    func export() -> Data? {
        guard let doc = pdfView.document else { return nil }
        for (page, canvas) in canvases {
            for stroke in canvas.drawing.strokes {
                var pagePoints: [CGPoint] = []
                for point in stroke.path.interpolatedPoints(in: nil, by: .distance(1.5)) {
                    let inCanvas = point.location.applying(stroke.transform)
                    let inView = canvas.convert(inCanvas, to: pdfView)
                    pagePoints.append(pdfView.convert(inView, to: page))
                }
                guard let first = pagePoints.first else { continue }
                let isMarker = stroke.ink.inkType == .marker
                let width: CGFloat = isMarker ? 12 : 2
                var minX = first.x, minY = first.y, maxX = first.x, maxY = first.y
                for p in pagePoints { minX = min(minX, p.x); minY = min(minY, p.y); maxX = max(maxX, p.x); maxY = max(maxY, p.y) }
                let bounds = CGRect(x: minX - width, y: minY - width, width: maxX - minX + 2 * width, height: maxY - minY + 2 * width)
                let path = UIBezierPath()
                for (i, p) in pagePoints.enumerated() {
                    let q = CGPoint(x: p.x - bounds.minX, y: p.y - bounds.minY)
                    if i == 0 { path.move(to: q) } else { path.addLine(to: q) }
                }
                let a = PDFAnnotation(bounds: bounds, forType: .ink, withProperties: nil)
                a.add(path)
                let border = PDFBorder()
                border.lineWidth = width
                a.border = border
                a.color = isMarker ? stroke.ink.color.withAlphaComponent(0.35) : stroke.ink.color
                page.addAnnotation(a)
            }
            canvas.drawing = PKDrawing()
        }
        return doc.dataRepresentation()
    }
}

private struct AnnotatePDFRepresentable: UIViewRepresentable {
    let controller: AnnotateController
    func makeUIView(context: Context) -> PDFView { controller.pdfView }
    func updateUIView(_ view: PDFView, context: Context) {}
}

struct AnnotatePDFView: View {
    let url: URL
    let onSave: (Data) -> Void
    @Environment(\.dismiss) private var dismiss
    @StateObject private var controller = AnnotateController()
    @State private var failure: String?
    @State private var notePrompt = false
    @State private var noteText = ""

    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                if let failure { Text(failure).foregroundStyle(.red).padding() }
                AnnotatePDFRepresentable(controller: controller)
                HStack(spacing: 4) {
                    toolButton(.browse, "hand.point.up.left", "Lihat")
                    toolButton(.pen, "pencil.tip", "Pena")
                    toolButton(.marker, "highlighter", "Stabilo")
                    toolButton(.eraser, "eraser", "Hapus")
                    Menu {
                        Button("Merah") { controller.color = .systemRed }
                        Button("Biru") { controller.color = .systemBlue }
                        Button("Hitam") { controller.color = .black }
                        Button("Hijau") { controller.color = .systemGreen }
                    } label: { Circle().fill(Color(uiColor: controller.color)).frame(width: 22, height: 22) }
                    .frame(maxWidth: .infinity)
                }
                .padding(.vertical, 8)
                .background(.bar)
            }
            .navigationTitle("Coret-coret PDF")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Batal") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) { Button("Simpan") { save() } }
                ToolbarItem(placement: .topBarTrailing) {
                    Menu {
                        Button { if !controller.highlightSelection() { failure = "Pilih teks dulu (mode Lihat, tekan lama pada teks)." } } label: {
                            Label("Sorot teks pilihan", systemImage: "highlighter")
                        }
                        Button { notePrompt = true } label: { Label("Tambah catatan teks", systemImage: "text.bubble") }
                    } label: { Image(systemName: "plus.circle") }
                }
            }
            .alert("Catatan teks", isPresented: $notePrompt) {
                TextField("Tulis catatan", text: $noteText)
                Button("Tambah") { controller.addNote(noteText); noteText = "" }
                Button("Batal", role: .cancel) { noteText = "" }
            }
        }
        .onAppear { if !controller.load(url) { failure = "PDF tidak bisa dibuka atau terkunci kata sandi." } }
    }

    private func toolButton(_ t: AnnotateTool, _ symbol: String, _ label: String) -> some View {
        Button { controller.tool = t } label: {
            VStack(spacing: 2) { Image(systemName: symbol); Text(label).font(.caption2) }
                .frame(maxWidth: .infinity)
                .foregroundStyle(controller.tool == t ? Color.depupBlue : Color.primary)
        }
    }

    private func save() {
        guard let data = controller.export() else { failure = OfficeError.cannotWrite.localizedDescription; return }
        onSave(data)
        dismiss()
    }
}
