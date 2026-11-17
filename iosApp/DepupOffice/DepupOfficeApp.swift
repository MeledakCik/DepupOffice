import SwiftUI

@main
struct DepupOfficeApp: App {
    @StateObject private var documents = DocumentStore()

    var body: some Scene {
        WindowGroup {
            RootTabView()
                .environmentObject(documents)
                .tint(Color.depupBlue)
                .preferredColorScheme(nil)
        }
    }
}
