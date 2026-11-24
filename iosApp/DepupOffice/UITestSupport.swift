#if DEBUG
import PDFKit
import SwiftUI
import UIKit

// Dukungan uji UI otomatis (hanya build Debug; tidak ikut ke aplikasi rilis).
//   -UITestSeed            isi folder DepUp Office dengan berkas contoh (PDF, Word, Excel, foto, Dokumen Terlindungi)
//   -UITestScreen <nama>   langsung buka layar tertentu (lihat UITestRoute)
//   -UITestDark            paksa mode gelap

enum UITestSupport {
    static let accessCode = "ABCD-EFGH-JKMN"
    static var arguments: [String] { ProcessInfo.processInfo.arguments }
    static var isActive: Bool { arguments.contains("-UITestSeed") }
    static var dark: Bool { arguments.contains("-UITestDark") }
    /// Tanpa pelindung tangkapan layar — hanya agar uji UI bisa membuktikan isi PDF benar-benar tampil.
    static var noShield: Bool { arguments.contains("-UITestNoShield") }

    static var screen: String? {
        guard let i = arguments.firstIndex(of: "-UITestScreen"), i + 1 < arguments.count else { return nil }
        return arguments[i + 1]
    }

    static var folder: URL {
        FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0].appendingPathComponent("DepUp Office", isDirectory: true)
    }

    static func url(_ name: String) -> URL { folder.appendingPathComponent(name) }

    /// Berkas contoh yang realistis untuk setiap alat.
    static func seed() {
        guard isActive else { return }
        let fm = FileManager.default
        try? fm.createDirectory(at: folder, withIntermediateDirectories: true)
        // Mulai bersih setiap peluncuran, kecuali Dokumen Terlindungi (ID dokumennya harus tetap agar
        // "kode diingat" bisa diuji antar-peluncuran).
        for item in (try? fm.contentsOfDirectory(at: folder, includingPropertiesForKeys: nil)) ?? [] where item.lastPathComponent != "Rahasia_terlindungi.pdf" {
            try? fm.removeItem(at: item)
        }
        try? samplePDF().write(to: url("Contoh Laporan.pdf"))
        try? DocTemplate.hadir.docx.write(to: url("Daftar Hadir.docx"))
        try? DocTemplate.izin.docx.write(to: url("Surat Izin.docx"))
        try? Xlsx.write(sheets: [(name: "Penjualan", rows: [
            [.text("Bulan"), .text("Penjualan"), .text("Biaya")],
            [.text("Januari"), .number(1_200_000), .number(800_000)],
            [.text("Februari"), .number(1_500_000), .number(900_000)],
            [.text("Maret"), .number(1_750_000), .number(950_000)],
            [.text("April"), .number(1_400_000), .number(870_000)]
        ], boldRows: [0])]).write(to: url("Penjualan.xlsx"))
        try? Data("Nama,Nilai\nAni,90\nBudi,85\nCitra,78\n".utf8).write(to: url("Nilai.csv"))
        try? textImage(["DepUp Office", "Laporan Kegiatan Bulanan", "Kegiatan berjalan lancar dan tepat waktu.",
                        "Kesimpulan: anggaran disetujui."], shadow: true).jpegData(compressionQuality: 0.9)?.write(to: url("Foto Dokumen.jpg"))
        try? textImage(["Budi Santoso", "Manajer Pemasaran", "PT Maju Jaya", "0812-3456-7890", "budi@majujaya.co.id"], shadow: false)
            .jpegData(compressionQuality: 0.9)?.write(to: url("Kartu Nama.jpg"))
        try? tableImage().jpegData(compressionQuality: 0.95)?.write(to: url("Tabel.jpg"))
        try? textImage(["TOKO MAKMUR", "Kopi Susu        18.000", "Roti Bakar       12.500", "Air Mineral       5.000", "TOTAL            35.500"], shadow: false, mono: true)
            .jpegData(compressionQuality: 0.95)?.write(to: url("Struk.jpg"))
        if !fm.fileExists(atPath: url("Rahasia_terlindungi.pdf").path),
           let protected = try? ProtectedDocWriter.make(original: samplePDF(), accessCode: accessCode, editPassword: "guru123!") {
            try? protected.write(to: url("Rahasia_terlindungi.pdf"))
        }
        NotesFile.save([Note(title: "Belanja kantor", checklist: [NoteItem(text: "Kertas A4"), NoteItem(text: "Tinta printer", done: true), NoteItem(text: "Map plastik")], isChecklist: true),
                        Note(title: "Ide rapat", body: "Bahas jadwal piket dan anggaran kegiatan Agustus.")])
    }

    static func samplePDF() -> Data {
        UIGraphicsPDFRenderer(bounds: CGRect(x: 0, y: 0, width: 595, height: 842)).pdfData { ctx in
            for page in 1...3 {
                ctx.beginPage()
                if page == 2 { continue }   // halaman kosong (untuk "Hapus halaman kosong")
                let title = page == 1 ? "Laporan Kegiatan" : "Lampiran"
                (title as NSString).draw(at: CGPoint(x: 50, y: 60), withAttributes: [.font: UIFont.boldSystemFont(ofSize: 26)])
                let body = "Kegiatan bulan ini berjalan lancar. Kesimpulan penting: anggaran disetujui dan laporan dikirim tepat waktu. " +
                    "Catatan: rapat berikutnya hari Senin pukul 09.00."
                (body as NSString).draw(in: CGRect(x: 50, y: 110, width: 495, height: 300), withAttributes: [.font: UIFont.systemFont(ofSize: 14)])
                UIColor.systemBlue.setFill()
                UIRectFill(CGRect(x: 50, y: 420, width: 200, height: 120))   // gambar (untuk "Ambil gambar")
            }
        }
    }

    static func textImage(_ lines: [String], shadow: Bool, mono: Bool = false) -> UIImage {
        let size = CGSize(width: 1200, height: 800)
        let f = UIGraphicsImageRendererFormat(); f.scale = 1
        return UIGraphicsImageRenderer(size: size, format: f).image { ctx in
            UIColor.white.setFill(); ctx.fill(CGRect(origin: .zero, size: size))
            if shadow {
                let colors = [UIColor(white: 0.35, alpha: 1).cgColor, UIColor.white.cgColor] as CFArray
                if let g = CGGradient(colorsSpace: CGColorSpaceCreateDeviceRGB(), colors: colors, locations: [0, 1]) {
                    ctx.cgContext.drawLinearGradient(g, start: .zero, end: CGPoint(x: 700, y: 500), options: [])
                }
            }
            for (i, line) in lines.enumerated() {
                let font = mono ? UIFont.monospacedSystemFont(ofSize: 44, weight: .medium) : (i == 0 ? UIFont.boldSystemFont(ofSize: 60) : UIFont.systemFont(ofSize: 44))
                (line as NSString).draw(at: CGPoint(x: 80, y: 80 + CGFloat(i) * 110), withAttributes: [.font: font, .foregroundColor: UIColor.black])
            }
        }
    }

    static func tableImage() -> UIImage {
        let rows = [["Nama", "Kelas", "Nilai"], ["Ani", "7A", "90"], ["Budi", "7B", "85"], ["Citra", "7A", "78"]]
        let size = CGSize(width: 1200, height: 700)
        let f = UIGraphicsImageRendererFormat(); f.scale = 1
        return UIGraphicsImageRenderer(size: size, format: f).image { ctx in
            UIColor.white.setFill(); ctx.fill(CGRect(origin: .zero, size: size))
            UIColor.black.setStroke()
            for (r, row) in rows.enumerated() {
                for (c, cell) in row.enumerated() {
                    let rect = CGRect(x: 80 + c * 340, y: 80 + r * 130, width: 340, height: 130)
                    UIBezierPath(rect: rect).stroke()
                    (cell as NSString).draw(at: CGPoint(x: rect.minX + 30, y: rect.minY + 40), withAttributes: [.font: UIFont.systemFont(ofSize: 46)])
                }
            }
        }
    }
}

/// Layar yang bisa dibuka langsung oleh uji UI.
struct UITestRoute: Identifiable {
    let id: String
}

struct UITestScreenView: View {
    let route: UITestRoute
    @EnvironmentObject private var documents: DocumentStore

    private func u(_ name: String) -> URL { UITestSupport.url(name) }

    var body: some View {
        switch route.id {
        case "invoice": InvoiceMakerView { documents.saveGenerated($0) }
        case "catalog": CatalogMakerView { documents.saveGenerated($0) }
        case "schedule": SchedulePlannerView { documents.saveGenerated($0) }
        case "notes": NotesScreen()
        case "searchAll": SearchAllView().environmentObject(documents)
        case "templates": DocTemplatePicker(onSave: { documents.saveGenerated($0) }, onEdit: { _ in })
        case "signLibrary": SignatureLibraryView(onPick: nil)
        case "priceTag": PriceTagSheet { documents.saveGenerated($0) }
        case "liveQR": LiveQRScannerSheet { _ in }
        case "sign": SignPDFView(url: u("Contoh Laporan.pdf")) { documents.savePDF($0, name: "Contoh_ttd") }
        case "organize": PageOrganizerView(url: u("Contoh Laporan.pdf")) { documents.savePDF($0, name: "Contoh_susun") }
        case "annotate": AnnotatePDFView(url: u("Contoh Laporan.pdf")) { documents.savePDF($0, name: "Contoh_coret") }
        case "chart": ChartMakerView(url: u("Penjualan.xlsx")) { documents.saveGenerated($0) }
        case "summary": SummaryView(url: u("Contoh Laporan.pdf")) { documents.saveGenerated($0) }
        case "voice": VoiceReaderView(url: u("Surat Izin.docx"))
        case "slideshow": SlideshowView(url: u("Contoh Laporan.pdf"))
        case "officeToPdf": OfficeToPDFView(url: u("Daftar Hadir.docx")) { documents.saveGenerated($0) }
        case "docEditor": DocEditorView(source: u("Daftar Hadir.docx")) { documents.saveGenerated($0) }
        case "docNew": DocEditorView(source: nil) { documents.saveGenerated($0) }
        case "sheetEditor": SheetEditorView(source: u("Penjualan.xlsx"), template: nil) { documents.saveGenerated($0) }
        case "sheetTemplate": SheetEditorView(source: nil, template: "keuangan") { documents.saveGenerated($0) }
        default: Text("Layar uji tidak dikenal: \(route.id)")
        }
    }
}
#endif
