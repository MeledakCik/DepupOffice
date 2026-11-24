import Compression
import Foundation

// MARK: - ZIP (dasar XLSX/DOCX/PPTX). Penulis: metode "stored" + CRC-32. Pembaca: stored & deflate (Compression).

enum ZipError: LocalizedError {
    case invalid, unsupported, tooLarge
    var errorDescription: String? {
        switch self {
        case .invalid: "Berkas Office rusak atau bukan format ZIP."
        case .unsupported: "Kompresi di dalam berkas tidak didukung."
        case .tooLarge: "Berkas terlalu besar untuk diproses di perangkat ini."
        }
    }
}

enum Zip {
    private static let crcTable: [UInt32] = (0..<256).map { n -> UInt32 in
        var c = UInt32(n)
        for _ in 0..<8 { c = (c & 1) != 0 ? 0xEDB8_8320 ^ (c >> 1) : c >> 1 }
        return c
    }

    static func crc32(_ data: Data) -> UInt32 {
        var c: UInt32 = 0xFFFF_FFFF
        for b in data { c = crcTable[Int((c ^ UInt32(b)) & 0xFF)] ^ (c >> 8) }
        return c ^ 0xFFFF_FFFF
    }

    private static func put16(_ d: inout Data, _ v: Int) { d.append(UInt8(v & 0xFF)); d.append(UInt8(v >> 8 & 0xFF)) }
    private static func put32(_ d: inout Data, _ v: UInt32) {
        d.append(UInt8(v & 0xFF)); d.append(UInt8(v >> 8 & 0xFF)); d.append(UInt8(v >> 16 & 0xFF)); d.append(UInt8(v >> 24 & 0xFF))
    }

    /// Arsip ZIP tanpa kompresi (sah untuk Office Open XML; Word/Excel membukanya).
    static func write(_ entries: [(name: String, data: Data)]) -> Data {
        var out = Data()
        var central = Data()
        for entry in entries {
            let name = Array(entry.name.utf8)
            let crc = crc32(entry.data)
            let size = UInt32(entry.data.count)
            let offset = UInt32(out.count)
            // header lokal; tanggal 0x0021 = 1 Jan 1980; flag 0x0800 = nama UTF-8
            put32(&out, 0x0403_4B50); put16(&out, 20); put16(&out, 0x0800); put16(&out, 0); put16(&out, 0); put16(&out, 0x21)
            put32(&out, crc); put32(&out, size); put32(&out, size); put16(&out, name.count); put16(&out, 0)
            out.append(contentsOf: name)
            out.append(entry.data)
            put32(&central, 0x0201_4B50); put16(&central, 20); put16(&central, 20); put16(&central, 0x0800); put16(&central, 0)
            put16(&central, 0); put16(&central, 0x21); put32(&central, crc); put32(&central, size); put32(&central, size)
            put16(&central, name.count); put16(&central, 0); put16(&central, 0); put16(&central, 0); put16(&central, 0)
            put32(&central, 0); put32(&central, offset)
            central.append(contentsOf: name)
        }
        let centralOffset = UInt32(out.count)
        out.append(central)
        put32(&out, 0x0605_4B50); put16(&out, 0); put16(&out, 0); put16(&out, entries.count); put16(&out, entries.count)
        put32(&out, UInt32(central.count)); put32(&out, centralOffset); put16(&out, 0)
        return out
    }

    /// Baca semua entri (nama → isi). Batas total hasil ekstrak menahan "zip bomb".
    static func read(_ data: Data, maxTotal: Int = 256 << 20) throws -> [String: Data] {
        let b = [UInt8](data)
        func u16(_ o: Int) throws -> Int { guard o >= 0, o + 2 <= b.count else { throw ZipError.invalid }; return Int(b[o]) | Int(b[o + 1]) << 8 }
        func u32(_ o: Int) throws -> Int {
            guard o >= 0, o + 4 <= b.count else { throw ZipError.invalid }
            return Int(b[o]) | Int(b[o + 1]) << 8 | Int(b[o + 2]) << 16 | Int(b[o + 3]) << 24
        }
        // End of central directory (cari mundur, komentar maks. 64 KB)
        var eocd = -1
        var i = b.count - 22
        let stop = max(0, b.count - 22 - 65_535)
        while i >= stop {
            if b[i] == 0x50, b[i + 1] == 0x4B, b[i + 2] == 0x05, b[i + 3] == 0x06 { eocd = i; break }
            i -= 1
        }
        guard eocd >= 0 else { throw ZipError.invalid }
        let count = try u16(eocd + 10)
        var p = try u32(eocd + 16)
        var result: [String: Data] = [:]
        var total = 0
        for _ in 0..<count {
            guard try u32(p) == 0x0201_4B50 else { throw ZipError.invalid }
            let method = try u16(p + 10)
            let csize = try u32(p + 20)
            let usize = try u32(p + 24)
            let nlen = try u16(p + 28), elen = try u16(p + 30), clen = try u16(p + 32)
            let local = try u32(p + 42)
            guard p + 46 + nlen <= b.count else { throw ZipError.invalid }
            let name = String(decoding: b[(p + 46)..<(p + 46 + nlen)], as: UTF8.self)
            p += 46 + nlen + elen + clen
            guard try u32(local) == 0x0403_4B50 else { throw ZipError.invalid }
            let localName = try u16(local + 26)
            let localExtra = try u16(local + 28)
            let start = local + 30 + localName + localExtra
            guard start >= 0, start + csize <= b.count else { throw ZipError.invalid }
            if name.hasSuffix("/") { continue }
            total += usize
            guard total <= maxTotal else { throw ZipError.tooLarge }
            let raw = Data(b[start..<(start + csize)])
            switch method {
            case 0: result[name] = raw
            case 8: result[name] = try inflate(raw, size: usize)
            default: throw ZipError.unsupported
            }
        }
        return result
    }

    private static func inflate(_ raw: Data, size: Int) throws -> Data {
        if size == 0 { return Data() }
        var out = Data(count: size)
        let written = out.withUnsafeMutableBytes { dst in
            raw.withUnsafeBytes { src in
                compression_decode_buffer(dst.bindMemory(to: UInt8.self).baseAddress!, size,
                                          src.bindMemory(to: UInt8.self).baseAddress!, raw.count, nil, COMPRESSION_ZLIB)
            }
        }
        guard written == size else { throw ZipError.invalid }
        return out
    }
}

// MARK: - XLSX

enum XlsxCell {
    case text(String)
    case number(Double)
    case formula(String)
    case empty
}

enum Xlsx {
    static func escape(_ s: String) -> String {
        var o = ""
        for u in s.unicodeScalars {
            switch u {
            case "&": o += "&amp;"
            case "<": o += "&lt;"
            case ">": o += "&gt;"
            case "\"": o += "&quot;"
            default:
                // Karakter kontrol tidak sah di XML 1.0 (kecuali tab/CR/LF) dibuang.
                if u.value < 0x20 && u != "\t" && u != "\n" && u != "\r" { continue }
                o.unicodeScalars.append(u)
            }
        }
        return o
    }

    /// Bilangan bulat tanpa ".0" (1400000, bukan 1400000.0), sama seperti Excel.
    static func numberText(_ v: Double) -> String {
        guard v.isFinite else { return "0" }
        if v == v.rounded() && abs(v) < 1e15 { return String(Int64(v)) }
        return String(v)
    }

    static func columnName(_ index: Int) -> String {
        var n = index + 1
        var s = ""
        while n > 0 { let r = (n - 1) % 26; s = String(UnicodeScalar(UInt8(65 + r))) + s; n = (n - 1) / 26 }
        return s
    }

    /// Satu atau beberapa sheet; baris [boldRows] (indeks 0) ditebalkan. Lebar kolom menyesuaikan isi.
    static func write(sheets: [(name: String, rows: [[XlsxCell]], boldRows: Set<Int>)]) -> Data {
        var files: [(String, Data)] = []
        let sheetOverrides = sheets.indices.map {
            "<Override PartName=\"/xl/worksheets/sheet\($0 + 1).xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>"
        }.joined()
        files.append(("[Content_Types].xml", Data(("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
            "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">" +
            "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>" +
            "<Default Extension=\"xml\" ContentType=\"application/xml\"/>" +
            "<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>" +
            "<Override PartName=\"/xl/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml\"/>" +
            sheetOverrides + "</Types>").utf8)))
        files.append(("_rels/.rels", Data(("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
            "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
            "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/>" +
            "</Relationships>").utf8)))
        let sheetTags = sheets.enumerated().map { i, s in
            "<sheet name=\"\(escape(String(s.name.prefix(31))))\" sheetId=\"\(i + 1)\" r:id=\"rId\(i + 1)\"/>"
        }.joined()
        files.append(("xl/workbook.xml", Data(("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
            "<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\">" +
            "<sheets>\(sheetTags)</sheets><calcPr fullCalcOnLoad=\"1\"/></workbook>").utf8)))
        let rels = sheets.indices.map {
            "<Relationship Id=\"rId\($0 + 1)\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet\($0 + 1).xml\"/>"
        }.joined() + "<Relationship Id=\"rId\(sheets.count + 1)\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/>"
        files.append(("xl/_rels/workbook.xml.rels", Data(("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
            "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">\(rels)</Relationships>").utf8)))
        files.append(("xl/styles.xml", Data(("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
            "<styleSheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">" +
            "<fonts count=\"2\"><font><sz val=\"11\"/><name val=\"Calibri\"/></font><font><b/><sz val=\"11\"/><name val=\"Calibri\"/></font></fonts>" +
            "<fills count=\"2\"><fill><patternFill patternType=\"none\"/></fill><fill><patternFill patternType=\"gray125\"/></fill></fills>" +
            "<borders count=\"1\"><border><left/><right/><top/><bottom/><diagonal/></border></borders>" +
            "<cellStyleXfs count=\"1\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\"/></cellStyleXfs>" +
            "<cellXfs count=\"2\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\"/>" +
            "<xf numFmtId=\"0\" fontId=\"1\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyFont=\"1\"/></cellXfs>" +
            "<cellStyles count=\"1\"><cellStyle name=\"Normal\" xfId=\"0\" builtinId=\"0\"/></cellStyles>" +
            "</styleSheet>").utf8)))
        for (index, sheet) in sheets.enumerated() {
            let columns = sheet.rows.map(\.count).max() ?? 0
            let widths = (0..<columns).map { c -> Int in
                let longest = sheet.rows.map { r -> Int in
                    guard c < r.count else { return 0 }
                    switch r[c] { case .text(let t): return t.count; case .number(let v): return numberText(v).count; default: return 6 }
                }.max() ?? 8
                return min(60, max(8, longest + 2))
            }
            let cols = columns == 0 ? "" : "<cols>" + widths.enumerated().map { "<col min=\"\($0.offset + 1)\" max=\"\($0.offset + 1)\" width=\"\($0.element)\" customWidth=\"1\"/>" }.joined() + "</cols>"
            var rowsXml = ""
            for (r, row) in sheet.rows.enumerated() {
                let style = sheet.boldRows.contains(r) ? " s=\"1\"" : ""
                rowsXml += "<row r=\"\(r + 1)\">"
                for (c, cell) in row.enumerated() {
                    let ref = "\(columnName(c))\(r + 1)"
                    switch cell {
                    case .text(let t): rowsXml += "<c r=\"\(ref)\" t=\"inlineStr\"\(style)><is><t xml:space=\"preserve\">\(escape(t))</t></is></c>"
                    case .number(let v): rowsXml += "<c r=\"\(ref)\"\(style)><v>\(numberText(v))</v></c>"
                    case .formula(let f): rowsXml += "<c r=\"\(ref)\"\(style)><f>\(escape(f))</f></c>"
                    case .empty: break
                    }
                }
                rowsXml += "</row>"
            }
            files.append(("xl/worksheets/sheet\(index + 1).xml", Data(("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
                "<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">\(cols)<sheetData>\(rowsXml)</sheetData></worksheet>").utf8)))
        }
        return Zip.write(files.map { (name: $0.0, data: $0.1) })
    }

    /// Isi sheet pertama sebagai tabel teks (nilai tersimpan; rumus memakai hasil cache-nya).
    static func readFirstSheet(_ data: Data) throws -> [[String]] {
        let files = try Zip.read(data)
        let shared = files["xl/sharedStrings.xml"].map(SharedStringsParser.parse) ?? []
        let sheetName = files.keys.filter { $0.hasPrefix("xl/worksheets/sheet") && $0.hasSuffix(".xml") }.sorted {
            $0.count == $1.count ? $0 < $1 : $0.count < $1.count
        }.first
        guard let sheetName, let sheet = files[sheetName] else { throw ZipError.invalid }
        return SheetParser.parse(sheet, shared: shared)
    }
}

private final class SharedStringsParser: NSObject, XMLParserDelegate {
    private var items: [String] = []
    private var current = ""
    private var inText = false
    private var inPhonetic = false

    static func parse(_ data: Data) -> [String] {
        let p = SharedStringsParser()
        let x = XMLParser(data: data)
        x.delegate = p
        x.parse()
        return p.items
    }

    func parser(_ parser: XMLParser, didStartElement name: String, namespaceURI: String?, qualifiedName: String?, attributes: [String: String] = [:]) {
        switch name {
        case "si": current = ""
        case "t": inText = true
        case "rPh": inPhonetic = true
        default: break
        }
    }

    func parser(_ parser: XMLParser, foundCharacters string: String) { if inText && !inPhonetic { current += string } }

    func parser(_ parser: XMLParser, didEndElement name: String, namespaceURI: String?, qualifiedName: String?) {
        switch name {
        case "si": items.append(current)
        case "t": inText = false
        case "rPh": inPhonetic = false
        default: break
        }
    }
}

private final class SheetParser: NSObject, XMLParserDelegate {
    private let shared: [String]
    private var rows: [Int: [Int: String]] = [:]
    private var cellRef = ""
    private var cellType = ""
    private var value = ""
    private var capture = false
    private var nextRow = 0

    init(shared: [String]) { self.shared = shared }

    static func parse(_ data: Data, shared: [String]) -> [[String]] {
        let p = SheetParser(shared: shared)
        let x = XMLParser(data: data)
        x.delegate = p
        x.parse()
        let maxRow = p.rows.keys.max() ?? -1
        guard maxRow >= 0 else { return [] }
        return (0...maxRow).map { r in
            let row = p.rows[r] ?? [:]
            let maxCol = row.keys.max() ?? -1
            return maxCol < 0 ? [] : (0...maxCol).map { row[$0] ?? "" }
        }
    }

    private static func position(_ ref: String) -> (row: Int, col: Int)? {
        var col = 0
        var digits = ""
        for ch in ref.uppercased() {
            if let a = ch.asciiValue, a >= 65, a <= 90 { col = col * 26 + Int(a - 64) } else if ch.isNumber { digits.append(ch) }
        }
        guard col > 0, let r = Int(digits), r > 0 else { return nil }
        return (r - 1, col - 1)
    }

    func parser(_ parser: XMLParser, didStartElement name: String, namespaceURI: String?, qualifiedName: String?, attributes: [String: String] = [:]) {
        switch name {
        case "row":
            if let r = attributes["r"], let n = Int(r) { nextRow = n - 1 }
        case "c":
            cellRef = attributes["r"] ?? ""
            cellType = attributes["t"] ?? ""
            value = ""
        case "v", "t":
            capture = true
        default: break
        }
    }

    func parser(_ parser: XMLParser, foundCharacters string: String) { if capture { value += string } }

    func parser(_ parser: XMLParser, didEndElement name: String, namespaceURI: String?, qualifiedName: String?) {
        switch name {
        case "v", "t": capture = false
        case "c":
            var text = value
            if cellType == "s", let i = Int(value), i >= 0, i < shared.count { text = shared[i] }
            if cellType == "b" { text = value == "1" ? "TRUE" : "FALSE" }
            let fallback: (row: Int, col: Int) = (row: nextRow, col: (rows[nextRow]?.keys.max() ?? -1) + 1)
            let pos = Self.position(cellRef) ?? fallback
            rows[pos.row, default: [:]][pos.col] = text
        case "row": nextRow += 1
        default: break
        }
    }
}

// MARK: - DOCX / PPTX: teks saja (untuk pencarian, ringkasan, pembaca suara)

enum OfficeText {
    /// Teks per slide .pptx, urut sesuai nomor slide.
    static func slides(_ data: Data) throws -> [String] {
        let files = try Zip.read(data)
        let names = files.keys.filter { $0.hasPrefix("ppt/slides/slide") && $0.hasSuffix(".xml") }
            .sorted { $0.count == $1.count ? $0 < $1 : $0.count < $1.count }
        return names.compactMap { files[$0] }.map { ParagraphText.parse($0, paragraph: "p", text: "t") }
    }

    /// Teks paragraf dari .docx (word/document.xml) atau .pptx (ppt/slides/*.xml).
    static func extract(_ data: Data, ext: String) throws -> String {
        let files = try Zip.read(data)
        switch ext {
        case "docx":
            guard let doc = files["word/document.xml"] else { throw ZipError.invalid }
            return ParagraphText.parse(doc, paragraph: "p", text: "t")
        case "pptx":
            let slides = files.keys.filter { $0.hasPrefix("ppt/slides/slide") && $0.hasSuffix(".xml") }
                .sorted { $0.count == $1.count ? $0 < $1 : $0.count < $1.count }
            return slides.compactMap { files[$0] }.map { ParagraphText.parse($0, paragraph: "p", text: "t") }.joined(separator: "\n\n")
        case "xlsx":
            return try Xlsx.readFirstSheet(data).map { $0.joined(separator: "\t") }.joined(separator: "\n")
        default:
            throw ZipError.unsupported
        }
    }
}

private final class ParagraphText: NSObject, XMLParserDelegate {
    private let paragraph: String
    private let text: String
    private var out = ""
    private var line = ""
    private var capture = false

    init(paragraph: String, text: String) { self.paragraph = paragraph; self.text = text }

    static func parse(_ data: Data, paragraph: String, text: String) -> String {
        let p = ParagraphText(paragraph: paragraph, text: text)
        let x = XMLParser(data: data)
        x.delegate = p
        x.parse()
        if !p.line.isEmpty { p.out += p.line }
        return p.out.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    private func local(_ name: String) -> String { name.split(separator: ":").last.map(String.init) ?? name }

    func parser(_ parser: XMLParser, didStartElement name: String, namespaceURI: String?, qualifiedName: String?, attributes: [String: String] = [:]) {
        let n = local(name)
        if n == text { capture = true }
        if n == "tab" { line += "\t" }
        if n == "br" { line += "\n" }
    }

    func parser(_ parser: XMLParser, foundCharacters string: String) { if capture { line += string } }

    func parser(_ parser: XMLParser, didEndElement name: String, namespaceURI: String?, qualifiedName: String?) {
        let n = local(name)
        if n == text { capture = false }
        if n == paragraph { out += line + "\n"; line = "" }
    }
}
