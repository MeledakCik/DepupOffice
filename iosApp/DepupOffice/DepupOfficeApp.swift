import SwiftUI

@main
struct DepupOfficeApp: App {
    @StateObject private var documents = DocumentStore()

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
        }
    }
}
