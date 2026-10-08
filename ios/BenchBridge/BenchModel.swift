import Foundation
import UIKit
import Combine

@MainActor final class BenchModel: ObservableObject {
    @Published var config: BenchConfig
    @Published var reports: [BenchFamily: BenchReport] = [:]
    @Published var history: [BenchReport] = []
    @Published var running = false
    @Published var error: String?
    @Published var activeFamily: BenchFamily = .memory
    let store: ReportStore
    private var token: CancellationToken?
    private var task: Task<Void, Never>?
    private var thermalObserver: NSObjectProtocol?
    init() {
        let arguments = ProcessInfo.processInfo.arguments
        config = arguments.contains("--uitest") ? .test : BenchConfig()
        if let argument = arguments.first(where: { $0.hasPrefix("--uitest-store=") }), let id = UUID(uuidString: String(argument.dropFirst(15))) {
            store = ReportStore(root: FileManager.default.temporaryDirectory.appendingPathComponent("BenchBridgeUITest-\(id.uuidString)"))
        } else { store = ReportStore() }
        do { try store.recover() } catch { self.error = BenchWorker.description(error) }
        history = store.all()
        thermalObserver = NotificationCenter.default.addObserver(forName: ProcessInfo.thermalStateDidChangeNotification, object: nil, queue: .main) { [weak self] _ in
            Task { @MainActor in if ProcessInfo.processInfo.thermalState.rawValue >= ProcessInfo.ThermalState.serious.rawValue { self?.stop("设备需要降温") } }
        }
    }
    func start(_ family: BenchFamily) {
        guard !running else { return }
        do {
            try config.validate()
            guard ProcessInfo.processInfo.thermalState.rawValue < ProcessInfo.ThermalState.serious.rawValue else { throw BenchError.message("设备需要降温") }
            let session = try CancellationToken(); token = session
            let report = BenchWorker.initial(family, config: config); reports[family] = report
            running = true; activeFamily = family; error = nil; UIApplication.shared.isIdleTimerDisabled = true
            let destination = store
            task = Task.detached(priority: .userInitiated) { [weak self] in
                await BenchWorker.run(report, token: session, store: destination) { updated in await self?.accept(updated) }
            }
        } catch { self.error = BenchWorker.description(error) }
    }
    private func accept(_ report: BenchReport) {
        reports[report.family] = report
        if report.state != "running" { running = false; token = nil; UIApplication.shared.isIdleTimerDisabled = false; history = store.all() }
    }
    func stop(_ reason: String = "用户停止") { token?.cancel(reason) }
    func backgrounded() { if running { stop("应用进入后台，已保存完成的采样") } }
    func refresh() { history = store.all() }
    func export(_ report: BenchReport) -> URL? { do { return try store.export(report) } catch { self.error = BenchWorker.description(error); return nil } }
}
