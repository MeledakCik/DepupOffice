import CoreImage
import CryptoKit
import PDFKit
import UIKit
import XCTest
@testable import DepupOffice

/// Uji logika iOS. Vektor Dokumen Terlindungi v3 IDENTIK dengan SecurePackageTest (Android) dan referensi Python,
/// sehingga kecocokan format Android ↔ iOS terbukti di CI, bukan sekadar terkompilasi.
final class SecurePackageTests: XCTestCase {
    let docId = "0f8fad5b-d9cb-469f-a165-70867728950e"
    let plain = Data("%PDF-1.4 contoh isi rahasia".utf8)

    func vector() throws -> Data {
        try SecurePackage.buildV3(plain: plain, accessCode: "7kq2 m9xd-4rtw", editPassword: "GuruRahasia!", docId: docId,
                                  iterations: 100_000, fixedSalt: Array(0..<16), fixedNonce: (0..<12).map { UInt8(100 + $0) })
    }

    func split(_ pkg: Data) throws -> ([UInt8], [UInt8], SecurePackage.Meta) {
        let all = [UInt8](pkg)
        let body = Array(all[0..<(all.count - 32)]), tag = Array(all[(all.count - 32)...])
        return (body, tag, try SecurePackage.parseMeta(body))
    }

    func testV3VectorIsByteIdenticalToAndroid() throws {
        let pkg = try vector()
        XCTAssertEqual(pkg.count, 315)
        XCTAssertEqual(SecurePackage.hex(SHA256.hash(data: pkg)), "d3fd862f041dfa5d7473a70de3c59e5c87c9783a424f6e4c103f22ca41fda843")
    }

    func testV3OpensWithNormalizedCode() throws {
        let (body, tag, meta) = try split(try vector())
        XCTAssertEqual(meta.version, 3)
        XCTAssertEqual(try SecurePackage.openV3(body: body, tag: tag, meta: meta, code: "7KQ2M9XD4RTW"), plain)
        XCTAssertEqual(try SecurePackage.openV3(body: body, tag: tag, meta: meta, code: "  7kq2-m9xd-4rtw "), plain)
    }

    func testWrongAndMissingCode() throws {
        let (body, tag, meta) = try split(try vector())
        XCTAssertThrowsError(try SecurePackage.openV3(body: body, tag: tag, meta: meta, code: "7KQ2M9XD4RTX")) { e in
            guard case ProtectedDocError.wrongCode = e else { return XCTFail("\(e)") }
        }
        XCTAssertThrowsError(try SecurePackage.openV3(body: body, tag: tag, meta: meta, code: nil)) { e in
            guard case ProtectedDocError.needsCode = e else { return XCTFail("\(e)") }
        }
    }

    func testEveryTamperedByteRejected() throws {
        let pkg = [UInt8](try vector())
        for at in stride(from: 0, to: pkg.count, by: 7) {
            var t = pkg
            t[at] ^= 0x41
            let r = Result { () -> Data in
                let (b, g, m) = try split(Data(t))
                return try SecurePackage.openV3(body: b, tag: g, meta: m, code: "7KQ2M9XD4RTW")
            }
            if case .success = r { XCTFail("byte \(at) diterima setelah diubah") }
        }
    }

    func testEditPassword() throws {
        let (_, _, meta) = try split(try vector())
        XCTAssertTrue(SecurePackage.editPasswordMatches(meta, "GuruRahasia!"))
        XCTAssertFalse(SecurePackage.editPasswordMatches(meta, "gururahasia!"))
        XCTAssertFalse(SecurePackage.editPasswordMatches(meta, ""))
    }

    func testGeneratedCodes() {
        for _ in 0..<50 {
            let c = SecurePackage.generateAccessCode()
            XCTAssertNotNil(c.range(of: "^[0-9A-HJKMNP-TV-Z]{4}-[0-9A-HJKMNP-TV-Z]{4}-[0-9A-HJKMNP-TV-Z]{4}$", options: .regularExpression), c)
            XCTAssertNil(SecurePackage.validateNewCode(c))
        }
        XCTAssertNotNil(SecurePackage.validateNewCode("abc-12"))
        XCTAssertEqual(SecurePackage.groupedCode("7kq2m9xd4rtw"), "7KQ2-M9XD-4RTW")
    }

    /// PDF buatan iPhone: paket keluar utuh dari katalog dan terbuka lewat jalur pembaca penuh.
    func testWriterRoundTripThroughReader() throws {
        let pkg = try vector()
        let pdf = ProtectedDocWriter.cover(docId: docId, package: pkg)
        XCTAssertNotNil(PDFDocument(data: pdf), "sampul harus PDF sah")
        XCTAssertEqual(ProtectedDocReader.payload(pdf), pkg)
        let info = try ProtectedDocReader.info(pdf)
        XCTAssertEqual(info.version, 3)
        XCTAssertEqual(info.docId, docId)
        XCTAssertEqual(try ProtectedDocReader.open(pdf, code: "7KQ2-M9XD-4RTW"), plain)
        XCTAssertTrue(ProtectedDocReader.verifyEditPassword(pdf, code: "7KQ2-M9XD-4RTW", password: "GuruRahasia!"))
        XCTAssertFalse(ProtectedDocReader.verifyEditPassword(pdf, code: "7KQ2-M9XD-4RTW", password: "salah"))
    }

    /// Keluaran penulis iOS identik dengan fixture yang dibuka Android di CrossPlatformTest.kt.
    func testWriterOutputMatchesAndroidFixture() throws {
        let pdf = ProtectedDocWriter.cover(docId: docId, package: try vector())
        XCTAssertEqual(pdf.count, 2688)
        XCTAssertEqual(SecurePackage.hex(SHA256.hash(data: pdf)), "a5727c85d3616f7c69ccd4f3e0e19f578fec1d7fb93af91f08fbf1b25ea98ecd")
    }

    func testMakeProducesOpenableDocument() throws {
        let original = UIGraphicsPDFRenderer(bounds: CGRect(x: 0, y: 0, width: 200, height: 200)).pdfData { $0.beginPage() }
        let out = try ProtectedDocWriter.make(original: original, accessCode: "ABCD-EFGH-JKMN", editPassword: "guru123!")
        XCTAssertEqual(try ProtectedDocReader.open(out, code: "abcdefghjkmn"), original)
    }

    /// Dokumen v1 (DepUp 4.1.0) tetap terbuka; dokumen yang ditandatangani kunci umpan dikenali sebagai palsu.
    func testV1AndHoneypot() throws {
        func v1(key: SymmetricKey) throws -> Data {
            let nonce = (0..<12).map { _ in UInt8(9) }
            let salt = (0..<16).map { _ in UInt8(7) }
            let sealed = try AES.GCM.seal(plain, using: key, nonce: AES.GCM.Nonce(data: nonce))
            let ct = Array(sealed.ciphertext) + Array(sealed.tag)
            func f(_ b: [UInt8]) -> [UInt8] { let n = UInt32(b.count); return [UInt8(n >> 24), UInt8(n >> 16 & 0xFF), UInt8(n >> 8 & 0xFF), UInt8(n & 0xFF)] + b }
            var body = Array("DEPUP_SECURE_DOC_v1".utf8)
            body += f(Array(docId.utf8)) + f(Array(SecurePackage.hex(SHA256.hash(data: plain)).utf8)) + f(salt) + f(nonce)
            body += f(Array(try SecurePackage.editHash("guru123", salt: salt).utf8))
            body.append(1)
            body += f(ct)
            let tag = HMAC<SHA256>.authenticationCode(for: body, using: key)
            return ProtectedDocWriter.cover(docId: docId, package: Data(body + Array(tag)))
        }
        var secret = SecretBox.appSecret()
        let real = SymmetricKey(data: Data(HMAC<SHA256>.authenticationCode(for: Data((SecretBox.readKeyPrefix() + docId).utf8), using: SymmetricKey(data: secret))))
        secret = []
        XCTAssertEqual(try ProtectedDocReader.open(try v1(key: real)), plain)
        XCTAssertThrowsError(try ProtectedDocReader.open(try v1(key: Honeypot.decoyKey(docId: docId)))) { e in
            guard case ProtectedDocError.forged = e else { return XCTFail("\(e)") }
        }
    }
}

final class OfficeFileTests: XCTestCase {
    func testZipRoundTripAndCRC() throws {
        XCTAssertEqual(Zip.crc32(Data("123456789".utf8)), 0xCBF4_3926)
        let entries: [(name: String, data: Data)] = [(name: "a.txt", data: Data("halo".utf8)), (name: "folder/b.bin", data: Data((0..<1000).map { UInt8($0 % 251) }))]
        let files = try Zip.read(Zip.write(entries))
        XCTAssertEqual(files["a.txt"], Data("halo".utf8))
        XCTAssertEqual(files["folder/b.bin"], entries[1].data)
    }

    func testXlsxWriteThenRead() throws {
        let data = Xlsx.write(sheets: [(name: "Belanja", rows: [[.text("Barang"), .text("Harga")], [.text("Kopi & <gula>"), .number(18000)], [.text("Roti"), .number(12500.5)], [.text("TOTAL"), .formula("SUM(B2:B3)")]], boldRows: [0])])
        let rows = try Xlsx.readFirstSheet(data)
        XCTAssertEqual(rows[0], ["Barang", "Harga"])
        XCTAssertEqual(rows[1][0], "Kopi & <gula>")
        XCTAssertEqual(rows[1][1], "18000")   // bilangan bulat tanpa ".0"
        XCTAssertEqual(Double(rows[2][1]), 12500.5)
        XCTAssertEqual(Xlsx.columnName(0), "A")
        XCTAssertEqual(Xlsx.columnName(25), "Z")
        XCTAssertEqual(Xlsx.columnName(26), "AA")
    }

    func testDocxRoundTripKeepsFormattingAndTables() throws {
        let m = NSMutableAttributedString(string: "Judul\n", attributes: [.font: UIFont.boldSystemFont(ofSize: 16)])
        let center = NSMutableParagraphStyle()
        center.alignment = .center
        m.append(NSAttributedString(string: "Tengah\n", attributes: [.font: UIFont.systemFont(ofSize: 12), .paragraphStyle: center]))
        m.append(NSAttributedString(attachment: TableAttachment(rows: [["No", "Nama"], ["1", "Budi"]], widths: [1, 4], borders: true)))
        m.append(NSAttributedString(string: "\nPenutup", attributes: [.font: UIFont.systemFont(ofSize: 12)]))
        let docx = Docx.write(m)
        let xml = String(decoding: try Zip.read(docx)["word/document.xml"]!, as: UTF8.self)
        XCTAssertTrue(xml.contains("<w:tbl>"))
        XCTAssertTrue(xml.contains("<w:b/>"))
        XCTAssertTrue(xml.contains("<w:jc w:val=\"center\"/>"))

        let back = try Docx.read(docx)
        var tables: [TableAttachment] = []
        back.enumerateAttribute(.attachment, in: NSRange(location: 0, length: back.length)) { v, _, _ in if let t = v as? TableAttachment { tables.append(t) } }
        XCTAssertEqual(tables.count, 1)
        XCTAssertEqual(tables.first?.rows, [["No", "Nama"], ["1", "Budi"]])
        XCTAssertEqual(tables.first?.widths.count, 2)
        XCTAssertTrue(back.string.contains("Judul"))
        let font = back.attribute(.font, at: 0, effectiveRange: nil) as? UIFont
        XCTAssertTrue(font?.fontDescriptor.symbolicTraits.contains(.traitBold) == true)

        // simpan ulang → tabel tetap tabel, tidak bertambah paragraf kosong tiap simpan
        let again = Docx.write(back)
        let xml2 = String(decoding: try Zip.read(again)["word/document.xml"]!, as: UTF8.self)
        XCTAssertEqual(xml2.components(separatedBy: "<w:tbl>").count, 2)
        let third = Docx.write(try Docx.read(again))
        XCTAssertEqual(try Zip.read(third)["word/document.xml"], try Zip.read(again)["word/document.xml"])
    }

    func testTemplatesAreWellFormedDocx() throws {
        for t in DocTemplate.allCases {
            let files = try Zip.read(t.docx)
            let xml = try XCTUnwrap(files["word/document.xml"], t.title)
            let parser = XMLParser(data: xml)
            XCTAssertTrue(parser.parse(), "\(t.title): \(parser.parserError?.localizedDescription ?? "")")
            XCTAssertNotNil(files["[Content_Types].xml"])
        }
        let hadir = String(decoding: try Zip.read(DocTemplate.hadir.docx)["word/document.xml"]!, as: UTF8.self)
        XCTAssertTrue(hadir.contains("<w:tbl>"))
    }

    func testOfficeRenderTableAndAttributedPDF() throws {
        let rows = [["Nama", "Nilai"], ["Ani", "90"], ["Budi", "85"]]
        XCTAssertGreaterThanOrEqual(PDFDocument(data: OfficeRender.table(rows, title: "Nilai"))?.pageCount ?? 0, 1)
        let long = NSAttributedString(string: String(repeating: "Baris panjang untuk uji pemenggalan halaman.\n", count: 300),
                                      attributes: [.font: UIFont.systemFont(ofSize: 12), .foregroundColor: UIColor.black])
        XCTAssertGreaterThan(PDFDocument(data: AttributedPDF.render(long))?.pageCount ?? 0, 3)
        XCTAssertEqual(PDFDocument(data: OfficeRender.slides(["Slide 1\nisi", "Slide 2"]))?.pageCount, 2)
    }
}

final class FormulaTests: XCTestCase {
    func engine(_ cells: [[String]]) -> FormulaEngine {
        FormulaEngine { r, c in r < cells.count && c < cells[r].count ? cells[r][c] : "" }
    }

    func testFunctionsAndArithmetic() {
        let e = engine([["10", "=A1*2", "=SUM(A1:B1)"], ["5", "=AVERAGE(A1:A2)", "=MAX(A1;A2;B2)"], ["teks", "=COUNT(A1:A3)", "=ROUND(10/3;2)"],
                        ["=2+3*4", "=(2+3)*4", "=2^3^2"], ["=A1/0", "=B5+1", "=-A1+ABS(-3)"]])
        XCTAssertEqual(e.display(0, 1), "20")
        XCTAssertEqual(e.display(0, 2), "30")
        XCTAssertEqual(e.display(1, 1), "7,5")
        XCTAssertEqual(e.display(1, 2), "10")
        XCTAssertEqual(e.display(2, 1), "2")      // teks tidak dihitung
        XCTAssertEqual(e.display(2, 2), "3,33")
        XCTAssertEqual(e.display(3, 0), "14")
        XCTAssertEqual(e.display(3, 1), "20")
        XCTAssertEqual(e.display(3, 2), "512")
        XCTAssertEqual(e.display(4, 0), "#DIV/0")
        XCTAssertEqual(e.display(4, 1), "#SIKLUS")
        XCTAssertEqual(e.display(4, 2), "-7")
    }

    func testIndonesianNumbers() {
        XCTAssertEqual(ParityEngine.number("Rp 18.000"), 18000)
        XCTAssertEqual(ParityEngine.number("1.234,56"), 1234.56)
        XCTAssertEqual(ParityEngine.number("12,5"), 12.5)
        XCTAssertEqual(ParityEngine.number("1.000.000"), 1_000_000)
        XCTAssertNil(ParityEngine.number("abc"))
    }
}

final class ToolTests: XCTestCase {
    private func samplePdf() -> [UInt8] {
        var s = "%PDF-1.4\n1 0 obj\n<< /Type /Catalog >>\nendobj\n%" + String(repeating: "x", count: 300) + "\n"
        s += "xref\n0 40\n0000000000 65535 f\r\n"
        for i in 1..<40 { s += String(format: "%010ld 00000 n\r\n", i * 37 + 15) }
        s += "trailer\n<< /Size 40 /Root 1 0 R >>\nstartxref\n9\n%%EOF\n"
        return Array(s.utf8)
    }

    func testExeToPdfPlainAndRecoveredKeys() throws {
        let pdf = samplePdf()
        XCTAssertEqual(try ExeExtractor.extract([0x4D, 0x5A] + [UInt8](repeating: 0, count: 126) + pdf), pdf)
        var rng = SystemRandomNumberGenerator()
        for _ in 0..<10 {
            let key = (0..<12).map { _ in UInt8.random(in: 0...255, using: &rng) }
            let prefix = Int.random(in: 100...300)
            var data = [UInt8](repeating: 0, count: prefix)
            data[0] = 0x4D; data[1] = 0x5A
            data += pdf.enumerated().map { $1 ^ key[(prefix + $0) % 12] }
            XCTAssertEqual(try ExeExtractor.extract(data), pdf, "kunci \(key)")
        }
        XCTAssertThrowsError(try ExeExtractor.extract((0..<4096).map { UInt8(truncatingIfNeeded: $0 * 7) }))
    }

    func testSignatureGeometry() {
        let crop = CGRect(x: 0, y: 0, width: 600, height: 800)
        // rotasi 0: kiri-atas tampilan = kiri-atas halaman (y PDF di atas)
        XCTAssertEqual(SignatureGeometry.displayToPage(CGPoint(x: 0, y: 0), crop: crop, rotation: 0), CGPoint(x: 0, y: 800))
        XCTAssertEqual(SignatureGeometry.displayToPage(CGPoint(x: 1, y: 1), crop: crop, rotation: 0), CGPoint(x: 600, y: 0))
        for r in [0, 90, 180, 270] {
            // pusat tampilan selalu pusat halaman
            XCTAssertEqual(SignatureGeometry.displayToPage(CGPoint(x: 0.5, y: 0.5), crop: crop, rotation: r), CGPoint(x: 300, y: 400), "rotasi \(r)")
            // titik yang ditaruh di tampilan kembali ke titik yang sama setelah halaman diputar untuk ditampilkan
            let display = SignatureGeometry.displaySize(crop: crop, rotation: r)
            let n = CGPoint(x: 0.2, y: 0.7)
            let p = SignatureGeometry.displayToPage(n, crop: crop, rotation: r)
            let back = pageToDisplay(p, crop: crop, rotation: r, display: display)
            XCTAssertEqual(back.x, n.x * display.width, accuracy: 0.001, "rotasi \(r)")
            XCTAssertEqual(back.y, n.y * display.height, accuracy: 0.001, "rotasi \(r)")
        }
        // garis mendatar di kanvas, diputar 90° → tegak; lebar 50% tampilan
        let placed = SignatureGeometry.place(strokes: [[CGPoint(x: 0, y: 50), CGPoint(x: 100, y: 50)]], drawingBounds: CGRect(x: 0, y: 0, width: 100, height: 100),
                                             crop: crop, pageRotation: 0, center: CGPoint(x: 0.5, y: 0.5), widthFraction: 0.5, degrees: 90)
        let a = placed[0][0], b = placed[0][1]
        XCTAssertEqual(a.x, b.x, accuracy: 0.001)
        XCTAssertEqual(abs(a.y - b.y), 300, accuracy: 0.001)
    }

    /// Kebalikan displayToPage (memakai PDFKit: halaman diputar searah jarum jam saat ditampilkan).
    private func pageToDisplay(_ p: CGPoint, crop: CGRect, rotation: Int, display: CGSize) -> CGPoint {
        let ux = p.x - crop.minX, uy = p.y - crop.minY   // dari kiri-bawah halaman
        switch rotation {
        case 90: return CGPoint(x: uy, y: ux)
        case 180: return CGPoint(x: crop.width - ux, y: uy)
        case 270: return CGPoint(x: crop.height - uy, y: crop.width - ux)
        default: return CGPoint(x: ux, y: crop.height - uy)
        }
    }

    func testSummarizerKeepsDocumentOrder() {
        let text = """
        Paragraf pembuka yang cukup panjang untuk dihitung sebagai poin.
        Kesimpulan rapat adalah anggaran disetujui dan kegiatan berjalan.
        Catatan penting: pembayaran paling lambat tanggal sepuluh bulan depan.
        """
        let points = Summarizer.keyPoints(text)
        XCTAssertEqual(points.count, 3)
        XCTAssertTrue(points[0].hasPrefix("Paragraf pembuka"))
    }

    func testSummarizerJoinsWrappedLinesAndDedupes() {
        let text = "Kegiatan bulan ini berjalan lancar. Kesimpulan penting: anggaran disetujui\ndan laporan dikirim tepat waktu.\n\n" +
            "Kegiatan bulan ini berjalan lancar. Kesimpulan penting: anggaran disetujui\ndan laporan dikirim tepat waktu."
        XCTAssertEqual(Summarizer.keyPoints(text), ["Kegiatan bulan ini berjalan lancar. Kesimpulan penting: anggaran disetujui dan laporan dikirim tepat waktu."])
    }

    func testAiTextStripsHiddenCharacters() {
        let smuggled = "Halo" + String(UnicodeScalar(0xE0049)!) + "\u{200B}dunia\u{202E}"
        XCTAssertEqual(AiText.clean(smuggled), "Halodunia")
        XCTAssertEqual(AiText.clean("baris 1\nbaris 2\tok"), "baris 1\nbaris 2\tok")
    }

    func testNotesFileToggle() throws {
        let file = FileManager.default.temporaryDirectory.appendingPathComponent("notes-test-\(UUID()).json")
        let item = NoteItem(text: "Kertas")
        let note = Note(title: "Belanja", checklist: [item], isChecklist: true)
        NotesFile.save([note], to: file)
        XCTAssertEqual(NotesFile.load(from: file).first?.title, "Belanja")
        XCTAssertTrue(NotesFile.toggle(noteId: note.id, itemId: item.id, in: file))
        XCTAssertEqual(NotesFile.load(from: file).first?.checklist.first?.done, true)
        XCTAssertEqual(NotesFile.noteId(from: NotesFile.deepLink(note.id)), note.id)
        try? FileManager.default.removeItem(at: file)
    }

    func testAccessCodeKeychainRoundTrip() {
        let id = UUID().uuidString.lowercased()
        AccessCodeKeychain.save(docId: id, code: "abcd-efgh-jkmn")
        XCTAssertEqual(AccessCodeKeychain.load(docId: id), "ABCDEFGHJKMN", "Keychain tidak menyimpan kode akses")
        AccessCodeKeychain.forget(docId: id)
        XCTAssertNil(AccessCodeKeychain.load(docId: id))
    }

    func testPriceTagBarcodeAndSheet() {
        XCTAssertNotNil(PriceTag.render(product: "Kopi", price: "Rp 18.000", code: "8991234567890"))
        XCTAssertNil(PriceTag.barcode(""))
        let tag = PriceTag.render(product: "Kopi", price: "Rp 18.000", code: "ABC123")!
        XCTAssertEqual(PDFDocument(data: PriceTag.a4Sheet(tag))?.pageCount, 1)
    }

    func testCompressAndBlankPageDetection() throws {
        let data = UIGraphicsPDFRenderer(bounds: CGRect(x: 0, y: 0, width: 595, height: 842)).pdfData { ctx in
            ctx.beginPage()
            ("Halaman berisi teks" as NSString).draw(at: CGPoint(x: 50, y: 50), withAttributes: [.font: UIFont.systemFont(ofSize: 30)])
            ctx.beginPage()   // kosong
        }
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("uji-\(UUID()).pdf")
        try data.write(to: url)
        defer { try? FileManager.default.removeItem(at: url) }
        let out = try ParityEngine.removeBlankPages(url)
        XCTAssertEqual(PDFDocument(data: out.data)?.pageCount, 1)
    }
}

/// Semua alat dijalankan pada berkas contoh realistis (sama dengan yang dipakai tur UI) dan hasilnya diperiksa isinya.
final class EngineTests: XCTestCase {
    var dir: URL!

    override func setUpWithError() throws {
        dir = FileManager.default.temporaryDirectory.appendingPathComponent("engine-\(UUID())", isDirectory: true)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
    }

    override func tearDownWithError() throws { try? FileManager.default.removeItem(at: dir) }

    func write(_ data: Data?, _ name: String) throws -> URL {
        let u = dir.appendingPathComponent(name)
        try XCTUnwrap(data, name).write(to: u)
        return u
    }

    func run(_ tool: String, _ urls: [URL], _ options: [String: String] = [:]) throws -> [GeneratedDocument] {
        let out = try NativeOfficeEngine.run(tool: tool, urls: urls, options: options)
        XCTAssertFalse(out.isEmpty, tool)
        for d in out { XCTAssertGreaterThan(d.data.count, 0, "\(tool): \(d.name)") }
        return out
    }

    func pdfPages(_ d: GeneratedDocument) -> Int { PDFDocument(data: d.data)?.pageCount ?? -1 }
    func text(_ d: GeneratedDocument) -> String { String(decoding: d.data, as: UTF8.self) }

    func testPdfTools() throws {
        let pdf = try write(UITestSupport.samplePDF(), "Contoh.pdf")
        XCTAssertEqual(pdfPages(try run("merge", [pdf, pdf])[0]), 6)
        XCTAssertEqual(pdfPages(try run("split", [pdf], ["range": "1,3"])[0]), 2)
        XCTAssertEqual(PDFDocument(data: try run("rotate", [pdf], ["degrees": "90"])[0].data)?.page(at: 0)?.rotation, 90)
        XCTAssertEqual(pdfPages(try run("watermark", [pdf], ["text": "RAHASIA"])[0]), 3)
        XCTAssertEqual(pdfPages(try run("number", [pdf])[0]), 3)
        XCTAssertEqual(pdfPages(try run("compress", [pdf])[0]), 3)
        XCTAssertEqual(try run("pdfImages", [pdf]).count, 3)
        XCTAssertTrue(text(try run("extractText", [pdf])[0]).contains("anggaran"))
        XCTAssertTrue(text(try run("search", [pdf], ["query": "anggaran"])[0]).lowercased().contains("anggaran"))
        XCTAssertTrue(text(try run("ocr", [pdf])[0]).lowercased().contains("laporan"))
        XCTAssertNotNil(try run("pdfToWord", [pdf])[0])
        XCTAssertEqual(pdfPages(try run("removeBlank", [pdf])[0]), 2)
        let locked = try run("protect", [pdf], ["password": "1234"])[0]
        XCTAssertTrue(PDFDocument(data: locked.data)?.isLocked == true)
        let secured = try run("secureDoc", [pdf], ["accessCode": "ABCD-EFGH-JKMN", "editPassword": "guru123!"])[0]
        XCTAssertEqual(try ProtectedDocReader.open(secured.data, code: "abcdefghjkmn"), try Data(contentsOf: pdf))
        // sudah terlindungi → ditolak, bukan dibungkus dua kali
        let securedURL = try write(secured.data, "x_terlindungi.pdf")
        XCTAssertThrowsError(try NativeOfficeEngine.run(tool: "secureDoc", urls: [securedURL], options: ["accessCode": "ABCD-EFGH-JKMN", "editPassword": "guru123!"]))
    }

    func testImageAndOcrTools() throws {
        let photo = try write(UITestSupport.textImage(["Laporan Kegiatan", "Anggaran disetujui"], shadow: true).jpegData(compressionQuality: 0.9), "Foto.jpg")
        let card = try write(UITestSupport.textImage(["Budi Santoso", "PT Maju Jaya", "0812-3456-7890", "budi@majujaya.co.id"], shadow: false).jpegData(compressionQuality: 0.9), "Kartu.jpg")
        let table = try write(UITestSupport.tableImage().jpegData(compressionQuality: 0.95), "Tabel.jpg")
        let receipt = try write(UITestSupport.textImage(["TOKO MAKMUR", "Kopi Susu        18.000", "Roti Bakar       12.500", "TOTAL            30.500"], shadow: false, mono: true).jpegData(compressionQuality: 0.95), "Struk.jpg")

        let imagePdf = try run("imagePDF", [photo, table])[0]
        XCTAssertEqual(pdfPages(imagePdf), 2)
        let scanned = try write(imagePdf.data, "Scan.pdf")
        XCTAssertGreaterThanOrEqual(try run("extractImages", [scanned]).count, 2)
        let searchable = try run("searchablePDF", [scanned])[0]
        XCTAssertTrue((PDFDocument(data: searchable.data)?.string ?? "").lowercased().contains("anggaran"), "teks OCR tertanam harus bisa dicari")
        XCTAssertNotNil(UIImage(data: try run("shadowRemove", [photo])[0].data))
        XCTAssertNotNil(UIImage(data: try run("transparentSign", [table])[0].data))
        XCTAssertEqual(try run("compressImages", [photo, card]).count, 2)
        XCTAssertEqual(try run("batchImagePDF", [photo, card]).count, 2)
        XCTAssertEqual(try run("photoWatermark", [photo], ["text": "DepUp"]).count, 1)

        let vcf = text(try run("businessCard", [card])[0])
        XCTAssertTrue(vcf.contains("BEGIN:VCARD") && vcf.contains("Budi"), vcf)
        XCTAssertTrue(vcf.contains("TEL") && vcf.contains("EMAIL"), vcf)

        let rows = try Xlsx.readFirstSheet(try run("tableToExcel", [table])[0].data)
        let flat = rows.flatMap { $0 }.joined(separator: "|")
        XCTAssertTrue(flat.contains("Ani") && flat.contains("90"), flat)

        let rec = try Xlsx.readFirstSheet(try run("receiptToExcel", [receipt])[0].data)
        let recFlat = rec.flatMap { $0 }.joined(separator: "|")
        XCTAssertTrue(recFlat.contains("|18000|") || recFlat.hasSuffix("|18000") || recFlat.contains("18000"), recFlat)
        XCTAssertTrue(recFlat.contains("TOTAL"), recFlat)
        XCTAssertFalse(recFlat.contains("30500"), "baris TOTAL di struk tidak boleh ikut dihitung sebagai barang")

        // pasfoto tanpa orang → pesan jelas, bukan crash
        XCTAssertThrowsError(try NativeOfficeEngine.run(tool: "idPhoto", urls: [table], options: ["size": "3x4", "background": "red"]))
    }

    func testQrScan() throws {
        let filter = CIFilter(name: "CIQRCodeGenerator", parameters: ["inputMessage": Data("https://depup.cloud".utf8)])!
        let ci = filter.outputImage!.transformed(by: CGAffineTransform(scaleX: 12, y: 12))
        let cg = CIContext().createCGImage(ci, from: ci.extent)!
        let qr = try write(UIImage(cgImage: cg).pngData(), "qr.png")
        XCTAssertTrue(text(try run("qrScan", [qr])[0]).contains("https://depup.cloud"))
    }

    func testCompressTargetAndOfficeToPdf() throws {
        let big = try write(UIGraphicsPDFRenderer(bounds: CGRect(x: 0, y: 0, width: 595, height: 842)).pdfData { ctx in
            for i in 0..<4 {
                ctx.beginPage()
                UITestSupport.textImage(["Halaman \(i + 1)"], shadow: true).draw(in: CGRect(x: 0, y: 0, width: 595, height: 842))
            }
        }, "Besar.pdf")
        let original = try Data(contentsOf: big).count
        if original > 200 * 1024 {
            let out = try run("compressTarget", [big], ["targetKB": "200"])[0]
            XCTAssertLessThan(out.data.count, original)
            XCTAssertEqual(pdfPages(out), 4)
        }
        let docx = try write(DocTemplate.hadir.docx, "Hadir.docx")
        XCTAssertGreaterThanOrEqual(PDFDocument(data: try OfficeRender.pdf(docx))?.pageCount ?? 0, 1)
        let xlsx = try write(Xlsx.write(sheets: [(name: "A", rows: [[.text("A"), .text("B")], [.number(1), .number(2)]], boldRows: [0])]), "A.xlsx")
        XCTAssertEqual(PDFDocument(data: try OfficeRender.pdf(xlsx))?.pageCount, 1)
    }
}
