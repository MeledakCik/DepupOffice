import XCTest

/// Uji UI di Simulator: tur semua layar (screenshot dikirim CI ke branch ci-screenshots) + alur penting.
final class DepupOfficeUITests: XCTestCase {
    override func setUp() { continueAfterFailure = true }

    @discardableResult
    func launch(_ screen: String?, dark: Bool = false, fresh: Bool = false) -> XCUIApplication {
        let app = XCUIApplication()
        app.launchArguments = ["-UITestSeed"] + (screen.map { ["-UITestScreen", $0] } ?? []) + (dark ? ["-UITestDark"] : [])
            + (fresh ? ["-UITestFresh"] : [])
        app.launch()
        XCTAssertTrue(app.wait(for: .runningForeground, timeout: 20), "aplikasi gagal terbuka: \(screen ?? "-")")
        return app
    }

    func shot(_ app: XCUIApplication, _ name: String) {
        let a = XCTAttachment(screenshot: app.screenshot())
        a.name = name
        a.lifetime = .keepAlways
        add(a)
    }

    func settle(_ seconds: Double = 2.5) { Thread.sleep(forTimeInterval: seconds) }

    func alive(_ app: XCUIApplication, _ what: String) {
        XCTAssertEqual(app.state, .runningForeground, "aplikasi tertutup (crash?) di \(what)")
    }

    // MARK: tur

    func testTourAllScreens() {
        let screens = ["tab:home", "tab:documents", "tab:scan", "tab:profile",
                       "invoice", "catalog", "schedule", "notes", "searchAll", "templates", "signLibrary", "priceTag", "liveQR",
                       "sign", "organize", "annotate", "chart", "summary", "voice", "slideshow", "officeToPdf",
                       "docEditor", "docNew", "sheetEditor", "sheetTemplate", "protected"]
        for (i, s) in screens.enumerated() {
            let app = launch(s)
            settle(s == "officeToPdf" || s == "summary" ? 5 : 3)
            alive(app, s)
            shot(app, String(format: "%02d_%@", i + 1, s.replacingOccurrences(of: ":", with: "_")))
            app.terminate()
        }
    }

    func testToolsListAllPages() {
        let app = launch("tab:tools")
        settle()
        for page in 1...6 {
            shot(app, "30_alat_hal\(page)")
            app.swipeUp(velocity: .slow)
            settle(1)
        }
        alive(app, "daftar alat")
    }

    func testDarkMode() {
        for (i, s) in ["tab:home", "tab:tools", "tab:documents", "invoice", "docEditor", "sheetEditor", "notes", "protected"].enumerated() {
            let app = launch(s, dark: true)
            settle(3)
            alive(app, "gelap " + s)
            shot(app, String(format: "40_gelap_%02d_%@", i + 1, s.replacingOccurrences(of: ":", with: "_")))
            app.terminate()
        }
    }

    // MARK: alur

    func testProtectedDocumentWrongThenRightCode() {
        var app = launch("protected", fresh: true)
        let field = app.textFields.firstMatch
        XCTAssertTrue(field.waitForExistence(timeout: 15), "kolom kode akses tidak muncul")
        field.tap()
        field.typeText("XXXX-XXXX-XXXX")
        app.buttons["Buka"].tap()
        XCTAssertTrue(app.staticTexts["Kode akses salah. Periksa kembali kode dari pembuat dokumen."].waitForExistence(timeout: 15), "pesan kode salah tidak muncul")
        shot(app, "50_terlindungi_kode_salah")
        app.terminate()

        app = launch("protected")
        let f2 = app.textFields.firstMatch
        XCTAssertTrue(f2.waitForExistence(timeout: 15))
        f2.tap()
        f2.typeText("abcd efgh jkmn")
        app.buttons["Buka"].tap()
        XCTAssertTrue(app.buttons["Opsi dokumen"].waitForExistence(timeout: 20), "dokumen tidak terbuka dengan kode benar")
        XCTAssertFalse(app.staticTexts["Akses Ditolak"].exists)
        settle(1.5)
        shot(app, "51_terlindungi_terbuka")
        app.buttons["Opsi dokumen"].tap()
        settle(1)
        shot(app, "52_terlindungi_menu")
        app.terminate()

        // kode diingat (Keychain) → langsung terbuka tanpa mengetik
        app = launch("protected")
        XCTAssertTrue(app.buttons["Opsi dokumen"].waitForExistence(timeout: 20), "kode yang diingat tidak dipakai")
        shot(app, "53_terlindungi_kode_diingat")
    }

    func testSheetFormula() {
        let app = launch("sheetEditor")
        let april = app.staticTexts["April"]
        XCTAssertTrue(april.waitForExistence(timeout: 10), "isi Excel contoh tidak tampil")
        app.staticTexts["1400000"].tap()
        let bar = app.textFields.firstMatch
        bar.tap()
        let current = (bar.value as? String) ?? ""
        bar.typeText(String(repeating: XCUIKeyboardKey.delete.rawValue, count: current.count + 2))
        bar.typeText("=SUM(B2:B4)\n")
        XCTAssertTrue(app.staticTexts["4450000"].waitForExistence(timeout: 5), "rumus SUM tidak dihitung")
        shot(app, "60_excel_rumus")
    }

    func testDocEditorTyping() {
        let app = launch("docNew")
        let tv = app.textViews.firstMatch
        XCTAssertTrue(tv.waitForExistence(timeout: 10))
        tv.tap()
        tv.typeText("Surat Pemberitahuan\nRapat warga hari Minggu pukul 08.00.")
        settle(1)
        shot(app, "61_word_mengetik")
    }

    func testInvoiceAndTemplateSave() {
        var app = launch("invoice")
        XCTAssertTrue(app.buttons["Simpan PDF"].waitForExistence(timeout: 10))
        app.buttons["Simpan PDF"].tap()
        XCTAssertTrue(app.alerts.firstMatch.waitForExistence(timeout: 10), "tidak ada konfirmasi simpan invoice")
        shot(app, "62_invoice_tersimpan")
        app.terminate()

        app = launch("templates")
        XCTAssertTrue(app.buttons["Surat Izin"].waitForExistence(timeout: 10) || app.staticTexts["Surat Izin"].waitForExistence(timeout: 2))
        app.staticTexts["Surat Izin"].tap()
        XCTAssertTrue(app.alerts.firstMatch.waitForExistence(timeout: 10), "template tidak tersimpan")
        shot(app, "63_template_tersimpan")
    }

    func testDocumentsLibraryContextMenu() {
        let app = launch("tab:documents")
        let cell = app.staticTexts["Penjualan.xlsx"]
        XCTAssertTrue(cell.waitForExistence(timeout: 10), "berkas contoh tidak muncul di daftar")
        cell.press(forDuration: 1.2)
        settle(1)
        shot(app, "64_berkas_menu")
        if app.buttons["Edit"].exists {
            app.buttons["Edit"].tap()
            XCTAssertTrue(app.staticTexts["April"].waitForExistence(timeout: 10), "editor Excel tidak terbuka dari daftar berkas")
            shot(app, "65_berkas_edit_excel")
        }
    }
}

extension DepupOfficeUITests {
    /// Isi PDF terlindungi benar-benar terdekripsi & tampil (pelindung dimatikan khusus untuk screenshot ini;
    /// dengan pelindung aktif, tangkapan layar sengaja kosong).
    func testProtectedDocumentContentRenders() {
        let app = XCUIApplication()
        app.launchArguments = ["-UITestSeed", "-UITestScreen", "protected", "-UITestNoShield"]
        app.launch()
        let field = app.textFields.firstMatch
        if field.waitForExistence(timeout: 15) {
            field.tap()
            field.typeText("ABCD-EFGH-JKMN")
            app.buttons["Buka"].tap()
        }
        XCTAssertTrue(app.buttons["Opsi dokumen"].waitForExistence(timeout: 20), "dokumen tidak terbuka")
        Thread.sleep(forTimeInterval: 2)
        shot(app, "54_terlindungi_isi_tanpa_pelindung")
    }
}
