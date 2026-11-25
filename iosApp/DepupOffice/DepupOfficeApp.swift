import SwiftUI

@main
struct DepupOfficeApp: App {
    @StateObject private var documents = DocumentStore()
    @StateObject private var updater = AppStoreUpdate()
    @Environment(\.scenePhase) private var scenePhase

    init() {
        #if DEBUG
        UITestSupport.seed()
        #endif
    }

    private var colorScheme: ColorScheme? {
        #if DEBUG
        if UITestSupport.dark { return .dark }
        #endif
        return nil
    }

    var body: some Scene {
        WindowGroup {
            RootTabView()
                .environmentObject(documents)
                .tint(Color.depupBlue)
                .preferredColorScheme(colorScheme)
                .environment(\.locale, Locale(identifier: "id_ID"))
                // Pembaruan wajib: diperiksa saat dibuka dan setiap kali kembali ke depan.
                .fullScreenCover(item: $updater.required) { RequiredUpdateView(info: $0) }
                .onChange(of: scenePhase) { _, phase in
                    if phase == .active { Task { await updater.check() } }
                }
        }
    }
}
