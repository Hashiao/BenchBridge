import SwiftUI

@main struct BenchBridgeApp: App {
    @StateObject private var model = BenchModel()
    @Environment(\.scenePhase) private var phase
    var body: some Scene {
        WindowGroup {
            RootView(model: model)
                .onChange(of: phase) { value in if value == .background { model.backgrounded() } }
        }
    }
}
