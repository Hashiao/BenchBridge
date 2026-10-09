import SwiftUI

@main struct BenchBridgeApp: App {
    @StateObject private var model = BenchModel()
    @Environment(\.scenePhase) private var phase
    var body: some Scene {
        WindowGroup {
            RootView(model: model)
                .environment(\.locale, L10n.locale)
                .environment(\.layoutDirection, .leftToRight)
                .onChange(of: phase) { value in if value == .background { model.backgrounded() } }
        }
    }
}
