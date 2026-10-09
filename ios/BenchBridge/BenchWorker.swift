import Foundation

final class CancellationToken: @unchecked Sendable {
    let handle: OpaquePointer
    private let lock = NSLock()
    private var message = "用户停止"
    init() throws { guard let handle = bb_session_create() else { throw BenchError.message("无法创建测量会话") }; self.handle = handle }
    deinit { bb_session_destroy(handle) }
    func cancel(_ reason: String) { lock.lock(); message = reason; lock.unlock(); bb_session_cancel(handle) }
    var cancelled: Bool { bb_session_cancelled(handle) != 0 }
    var reason: String { lock.lock(); defer { lock.unlock() }; return message }
    func check() throws { if cancelled { throw BenchError.message(reason) } }
}
enum BenchWorker {
    static let computeNames = ["内存读取", "内存写入", "内存拷贝", "FP32", "FP64", "INT24", "INT32", "INT64", "AES-256", "SHA-1", "Julia", "Mandel FP64"]
    static func computeUnit(_ kind: Int) -> String { kind <= 2 || kind == 8 || kind == 9 ? "GB/s" : kind <= 4 ? "GFLOPS" : kind <= 7 ? "GIOPS" : "MPix/s" }
    static func scorePlan(_ family: BenchFamily, config: BenchConfig = BenchConfig()) -> [ScoreItem] {
        switch family {
        case .memory: return [(0, "读取"), (1, "写入"), (5, "延迟"), (2, "拷贝")].map { ScoreItem(id: "ram-\($0.0)", title: $0.1, unit: $0.0 == 5 ? "ns" : "GB/s") }
        case .storage: return config.storageSettings.cases.flatMap { test in (0...1).map { operation in
            ScoreItem(id: "\(test.id)-\(operation)", title: "\(test.title) · \(operation == 0 ? "读取" : "写入")", unit: "MB/s", aggregation: "maximum_completed_round")
        } }
        case .compute: return (0...11).flatMap { kind in ["cpu", "gpu"].map { ScoreItem(id: "\($0)-\(kind)", title: "\(computeNames[kind]) · \($0.uppercased())", unit: computeUnit(kind)) } }
        }
    }
    static func budget(_ bytes: UInt64) throws {
        let headroom = bb_available_memory()
        let allowance = headroom > 0 ? headroom / 2 : ProcessInfo.processInfo.physicalMemory / 8
        guard bytes + 64 * 1048576 <= allowance else { throw BenchError.message("当前可用内存不足，请减小工作集后重试") }
    }
    static func initial(_ family: BenchFamily, config: BenchConfig) -> BenchReport {
        let device = DeviceInfo.collect(); let plan = scorePlan(family, config: config)
        var flags = ["system_scheduled_no_core_binding", "cpu_frequency_unavailable", "ui_active_during_test", "apple_protocol_not_android_equivalent"]
        if device.simulator || config.functionalTest { flags.append("functional_test_not_device_performance") }
        #if DEBUG
        flags.append("development_build")
        #endif
        return BenchReport(appVersion: Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "unknown",
                           id: UUID(), family: family, config: config, startedAt: Date(), device: device, scores: plan,
                           plannedRounds: plan.reduce(0) { $0 + config.rounds(family, kind: Int($1.id.split(separator: "-").last!)!) },
                           thermalAtStart: ProcessInfo.processInfo.thermalState.rawValue, qualityFlags: flags)
    }
    static func run(_ input: BenchReport, token: CancellationToken, store: ReportStore,
                    progress: @escaping @Sendable (BenchReport) async -> Void) async {
        var report = input; let config = report.config
        func publish() async throws { try store.save(report); await progress(report) }
        func rest(_ milliseconds: Int) async throws {
            for _ in 0..<(milliseconds + 49) / 50 { try token.check(); try await Task.sleep(nanoseconds: 50_000_000) }
        }
        var storageHandle: OpaquePointer?
        let storageFolder = FileManager.default.temporaryDirectory.appendingPathComponent("BenchBridgeIO-" + report.id.uuidString)
        defer { if let storageHandle { bb_storage_destroy(storageHandle) }; if report.family == .storage { try? FileManager.default.removeItem(at: storageFolder) } }
        do {
            try config.validate(); try await publish()
            if report.family == .storage {
                let parameters = config.storageSettings
                try budget(UInt64(64 * 1048576 + (parameters.cases.map { $0.blockKiB * 1024 * $0.queueDepth * $0.threads }.max() ?? 0)))
                try FileManager.default.createDirectory(at: storageFolder, withIntermediateDirectories: true)
                let capacity = try storageFolder.resourceValues(forKeys: [.volumeAvailableCapacityForImportantUsageKey]).volumeAvailableCapacityForImportantUsage ?? 0
                guard capacity > Int64(parameters.fileMiB) * 1048576 + 128 * 1048576 else { throw BenchError.message("可用存储空间不足") }
                report.progress = "初始化 \(parameters.fileMiB) MiB 测试文件"; await progress(report)
                var prepared = BBStorageResult()
                storageHandle = storageFolder.appendingPathComponent("data.bin").path.withCString {
                    bb_storage_create(token.handle, $0, UInt64(parameters.fileMiB) * 1048576, parameters.noCache ? 1 : 0, &prepared)
                }
                guard storageHandle != nil else { throw BenchError.message(NativeMeasurement(prepared.measurement).error ?? "文件初始化失败") }
            }
            for index in report.scores.indices {
                try token.check()
                let item = report.scores[index]
                let parts = item.id.split(separator: "-"); let kind = Int(parts.last!)!
                let rounds = config.rounds(report.family, kind: kind)
                let ram = config.ramSettings
                var ramThreads = kind == 5 ? 1 : ram.threads
                if report.family == .memory && kind != 5 && ram.automaticThreads {
                    report.progress = "校准 \(item.title) 的带宽线程"; await progress(report)
                    try budget(UInt64(ram.memoryMiB) * 1048576)
                    let maximum = min(16, report.device.logicalCpuCount)
                    let candidates = Set([1,2,3,4,6,8,maximum].filter { $0 <= maximum && $0 > 0 }).sorted()
                    var best = -Double.infinity
                    for count in candidates {
                        try token.check()
                        let measured = NativeMeasurement(bb_memory(token.handle, Int32(kind), UInt64(ram.memoryMiB) * 1048576, Int32(count), 50, 150, 0, 419))
                        report.scores[index].calibration = (report.scores[index].calibration ?? []) + [measured]
                        if measured.status == 0, measured.verified, let trial = measured.trials.first, trial.elapsedNs > 0 {
                            let value = Double(trial.logicalBytes) / Double(trial.elapsedNs)
                            if value > best { best = value; ramThreads = count }
                        }
                    }
                    try await publish()
                }
                if report.family == .compute && parts[0] == "gpu", let reason = MetalRunner.unavailable(kind) {
                    report.scores[index].state = "unavailable"; report.scores[index].reason = reason
                    report.processedRounds += rounds; try await publish(); continue
                }
                for round in 0..<rounds {
                    try token.check()
                    report.progress = item.title; report.scores[index].state = "running"; await progress(report)
                    if report.family != .storage { try budget(UInt64(report.family == .memory ? (kind == 5 ? ram.latencyMiB : ram.memoryMiB) : config.memoryMiB * 2) * 1048576) }
                    if report.family == .compute && parts[0] == "gpu" {
                        do {
                            let backend = try MetalRunner(); report.device.gpuName = backend.device.name
                            let sample = try backend.run(kind: kind, config: config, token: token)
                            report.scores[index].gpuSamples.append(sample)
                            let factor = kind >= 10 ? 1000.0 : 1.0
                            report.scores[index].values.append(Double(sample.workUnits) / Double(sample.gpuElapsedNs) * factor)
                            report.completedRounds += 1
                        } catch {
                            if token.cancelled { throw error }
                            report.scores[index].reason = description(error); report.scores[index].state = "failed"
                        }
                    } else {
                        let native: NativeMeasurement
                        switch report.family {
                        case .memory:
                            native = NativeMeasurement(bb_memory(token.handle, Int32(kind), UInt64(kind == 5 ? ram.latencyMiB : ram.memoryMiB) * 1048576,
                                                               Int32(ramThreads), Int32(ram.warmupMs), Int32(ram.durationMs), 0, 419))
                        case .compute:
                            native = NativeMeasurement(kind <= 2 ? bb_memory(token.handle, Int32(kind), UInt64(config.memoryMiB) * 1048576,
                                                                            Int32(config.threads), 60, Int32(config.durationMs), 0, 419)
                                : bb_cpu_compute(token.handle, Int32(kind), Int32(config.durationMs)))
                        case .storage:
                            let parameters = config.storageSettings; let test = parameters.cases[index / 2]
                            let raw = bb_storage_run(storageHandle, Int32(kind), test.random ? 1 : 0, Int32(test.blockKiB * 1024),
                                                     Int32(test.queueDepth), Int32(test.threads), Int32(parameters.warmupMs), Int32(parameters.durationMs))
                            native = NativeMeasurement(raw.measurement)
                            report.scores[index].storageSamples = (report.scores[index].storageSamples ?? []) + [StorageSample(raw)]
                        }
                        report.scores[index].measurements.append(native)
                        if native.status == 1 { try token.check(); throw BenchError.message("测量被中断") }
                        if native.verified, native.status == 0, let trial = native.trials.first, trial.elapsedNs > 0 {
                            let value: Double
                            if report.family == .memory && kind == 5 { value = Double(trial.elapsedNs) / Double(trial.operations) }
                            else if report.family == .storage { value = Double(trial.logicalBytes) / Double(trial.elapsedNs) * 1000 }
                            else if report.family == .compute && kind >= 3 { value = Double(trial.operations) / Double(trial.elapsedNs) * (kind >= 10 ? 1000 : 1) }
                            else { value = Double(trial.logicalBytes) / Double(trial.elapsedNs) }
                            guard value.isFinite && value > 0 else { throw BenchError.message("无效的测量计数") }
                            report.scores[index].values.append(value); report.completedRounds += 1
                        } else { report.scores[index].state = "failed"; report.scores[index].reason = native.error }
                    }
                    report.processedRounds += 1
                    if report.scores[index].values.count == rounds { report.scores[index].state = "completed" }
                    try await publish()
                    if round + 1 < rounds || index + 1 < report.scores.count {
                        let interval = report.family == .memory ? ram.intervalMs : report.family == .storage ? config.storageSettings.intervalMs : 0
                        if interval > 0 { report.progress = "轮间休息"; await progress(report); try await rest(interval) }
                    }
                }
            }
            if report.family == .memory && config.includeCurve {
                let anchors = (report.device.performanceLevels ?? []).flatMap { [$0.l1DataBytes, $0.l2Bytes].compactMap { $0 } } +
                    [report.device.reportedL1Bytes, report.device.reportedL2Bytes, report.device.reportedL3Bytes].compactMap { $0 }
                let sizes = Statistics.grid(maximum: UInt64(config.cacheMaxMiB) * 1048576, steps: config.stepsPerOctave, anchors: anchors)
                report.curves = (config.backgroundCurve ? [0, 1] : [0]).map { CurveGroup(qos: $0, plannedSizes: sizes) }
                try await publish()
                func measure(_ group: Int, _ bytes: UInt64, _ pass: Int) async throws {
                    try token.check(); try budget(bytes * 9 / 8)
                    let attempts = report.curves[group].batches.filter { $0.bytes == bytes && $0.pass == pass }.count
                    guard attempts < 3 else { return }
                    let qos = report.curves[group].qos
                    report.progress = "\(report.curves[group].title) · \(Statistics.size(bytes)) · 第 \(pass + 1) 遍"; await progress(report)
                    let seed = UInt64(0xB16B00B5 + pass * 1009 + (attempts + 1) * 7919)
                    let reportedStride = Int(report.device.reportedLineBytes ?? 0)
                    let stride = [32, 64, 128, 256].contains(reportedStride) ? reportedStride : 64
                    let measurement = NativeMeasurement(bb_cache_point(token.handle, bytes, Int32(stride), Int32(qos), seed))
                    if measurement.status == 1 { try token.check(); throw BenchError.message("采样被中断") }
                    report.curves[group].batches.append(CurveBatch(bytes: bytes, pass: pass, attempt: attempts + 1, seed: seed, measurement: measurement))
                    Statistics.refresh(&report.curves[group]); try await publish()
                }
                for pass in 0...1 {
                    for group in (pass == 0 ? Array(report.curves.indices) : Array(report.curves.indices.reversed())) {
                        for bytes in (pass == 0 ? sizes : Array(sizes.reversed())) { try await measure(group, bytes, pass) }
                    }
                }
                for group in report.curves.indices {
                    for _ in 0..<2 {
                        let valid = Set(report.curves[group].points.filter(\.stable).map(\.bytes))
                        for bytes in report.curves[group].plannedSizes where !valid.contains(bytes) { for pass in 0...1 { try await measure(group, bytes, pass) } }
                    }
                    Statistics.analyze(&report.curves[group])
                    let original = report.curves[group].plannedSizes
                    var refinement = Set<UInt64>()
                    for edge in report.curves[group].transitions {
                        let span = edge.upperBytes - edge.lowerBytes
                        for fraction: UInt64 in 1...3 {
                            let bytes = (edge.lowerBytes + span * fraction / 4) / 256 * 256
                            if !original.contains(bytes) { refinement.insert(bytes) }
                        }
                    }
                    let extra = refinement.sorted()
                    report.curves[group].plannedSizes = (original + extra).sorted()
                    for bytes in extra { for pass in 0...1 { try await measure(group, bytes, pass) } }
                    Statistics.analyze(&report.curves[group]); try await publish()
                }
            }
            let curvesComplete = report.curves.allSatisfy { $0.points.filter(\.stable).count == $0.plannedSizes.count }
            report.state = report.completedRounds == report.plannedRounds && curvesComplete ? "completed" : "partial"
        } catch {
            report.state = token.cancelled ? "cancelled" : "failed"; report.error = token.cancelled ? token.reason : description(error)
        }
        report.finishedAt = Date(); report.thermalAtEnd = ProcessInfo.processInfo.thermalState.rawValue; report.progress = report.statusTitle
        do { try store.save(report) } catch { report.state = "failed"; report.error = "结果保存失败：\(description(error))" }
        await progress(report)
    }
    static func description(_ error: Error) -> String { if case BenchError.message(let message) = error { return message }; return error.localizedDescription }
}
