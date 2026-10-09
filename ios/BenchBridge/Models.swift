import Foundation

enum BenchFamily: String, Codable, CaseIterable, Sendable {
    case memory, storage, compute
    var title: String { switch self { case .memory: "RAM"; case .storage: "ROM"; case .compute: "GPGPU" } }
}
struct BenchConfig: Codable, Equatable, Sendable {
    var memoryMiB = 64
    var threads = 1
    var durationMs = 500
    var repeats = 3
    var cacheMaxMiB = 64
    var stepsPerOctave = 8
    var includeCurve = true
    var backgroundCurve = true
    var storageMiB = 1024
    var functionalTest = false
    // 可选版本化配置兼容旧记录；缺失时恢复旧协议，不套用新默认。 / Missing versioned settings retain the legacy protocol.
    var ram: RAMParameters? = RAMParameters()
    var storage: StorageParameters? = StorageParameters()
    var ramSettings: RAMParameters {
        get { ram ?? RAMParameters(memoryMiB: memoryMiB, latencyMiB: memoryMiB, threads: threads, automaticThreads: false,
                                   warmupMs: 60, durationMs: durationMs, repeats: repeats, latencyRepeats: repeats, intervalMs: 0) }
        set { ram = newValue }
    }
    var storageSettings: StorageParameters {
        get { storage ?? StorageParameters(protocolId: "apple-storage-q1-legacy-v1", fileMiB: storageMiB, repeats: repeats,
                                           warmupMs: 0, durationMs: durationMs, intervalMs: 0, cases: [StorageCase.standard[1], StorageCase.standard[3]]) }
        set { storage = newValue }
    }
    static var quick: Self {
        var value = Self(memoryMiB: 16, durationMs: 100, repeats: 1, cacheMaxMiB: 16, stepsPerOctave: 4, storageMiB: 64)
        value.ram = RAMParameters(memoryMiB: 16, latencyMiB: 16, warmupMs: 25, durationMs: 150, repeats: 1, latencyRepeats: 1, intervalMs: 0)
        value.storage = StorageParameters(fileMiB: 64, repeats: 1, warmupMs: 100, durationMs: 600, intervalMs: 100)
        return value
    }
    static var test: Self {
        var value = Self(memoryMiB: 1, durationMs: 10, repeats: 1, cacheMaxMiB: 1, stepsPerOctave: 1, backgroundCurve: false, storageMiB: 8, functionalTest: true)
        value.ram = RAMParameters(memoryMiB: 1, latencyMiB: 1, automaticThreads: false, warmupMs: 5, durationMs: 10, repeats: 1, latencyRepeats: 1, intervalMs: 0)
        value.storage = StorageParameters(fileMiB: 8, repeats: 1, warmupMs: 5, durationMs: 15, intervalMs: 0)
        return value
    }
    func rounds(_ family: BenchFamily, kind: Int) -> Int {
        switch family { case .memory: kind == 5 ? ramSettings.latencyRepeats : ramSettings.repeats
        case .storage: storageSettings.repeats; case .compute: repeats }
    }
    func summary(_ family: BenchFamily) -> String {
        switch family {
        case .memory:
            let p = ramSettings
            return "RAM 带宽 \(p.memoryMiB) MiB · 延迟 \(p.latencyMiB) MiB / T1 · \(p.automaticThreads ? "带宽线程自动校准" : "带宽 T\(p.threads)")\n带宽 \(p.repeats) 次 · 延迟 \(p.latencyRepeats) 次 · \(p.durationMs) ms / 轮" +
                (includeCurve ? "\n曲线：4 KiB–\(cacheMaxMiB) MiB · T1 · 正反扫描" : "")
        case .storage:
            let p = storageSettings
            return "\(Statistics.size(UInt64(p.fileMiB) * 1048576)) · \(p.repeats) 次 · \(p.durationMs) ms / 轮\n预热 \(p.warmupMs) ms · 间隔 \(p.intervalMs) ms · \(storage == nil ? "旧版中位数" : "最佳完整轮次")"
        case .compute: return "\(memoryMiB) MiB · \(repeats) 次 · \(durationMs) ms / 轮"
        }
    }
    func validate() throws {
        guard (1...256).contains(memoryMiB), (1...8).contains(threads), (5...5000).contains(durationMs),
              (1...5).contains(repeats), (1...256).contains(cacheMaxMiB), (1...8).contains(stepsPerOctave), (1...65536).contains(storageMiB)
        else { throw BenchError.message("测试参数超出范围") }
        try ramSettings.validate(); try storageSettings.validate()
    }
}

struct RAMParameters: Codable, Equatable, Sendable {
    var memoryMiB = 64
    var latencyMiB = 64
    var threads = 1
    var automaticThreads = true
    var warmupMs = 1000
    var durationMs = 3000
    var repeats = 3
    var latencyRepeats = 5
    var intervalMs = 2000
    func validate() throws {
        guard (1...256).contains(memoryMiB), (1...256).contains(latencyMiB), (1...16).contains(threads),
              (0...5000).contains(warmupMs), (5...5000).contains(durationMs), (1...10).contains(repeats),
              (1...10).contains(latencyRepeats), (0...30000).contains(intervalMs) else { throw BenchError.message("RAM 参数超出范围") }
    }
}
struct StorageCase: Codable, Equatable, Identifiable, Sendable {
    var id: String
    var random: Bool
    var blockKiB: Int
    var queueDepth: Int
    var threads: Int
    var title: String { "\(random ? "RND" : "SEQ") \(blockKiB >= 1024 ? "\(blockKiB / 1024) MiB" : "\(blockKiB) KiB") Q\(queueDepth)T\(threads)" }
    static let standard: [Self] = [
        Self(id: "seq1m-q8t1", random: false, blockKiB: 1024, queueDepth: 8, threads: 1),
        Self(id: "seq1m-q1t1", random: false, blockKiB: 1024, queueDepth: 1, threads: 1),
        Self(id: "rnd4k-q32t1", random: true, blockKiB: 4, queueDepth: 32, threads: 1),
        Self(id: "rnd4k-q1t1", random: true, blockKiB: 4, queueDepth: 1, threads: 1)]
}
struct StorageParameters: Codable, Equatable, Sendable {
    var protocolId = "diskmark-default-v2-apple-aio"
    var fileMiB = 1024
    var repeats = 3
    var warmupMs = 5000
    var durationMs = 5000
    var intervalMs = 5000
    var noCache = true
    var cases = StorageCase.standard
    func validate() throws {
        guard (1...65536).contains(fileMiB), (1...9).contains(repeats), (0...10000).contains(warmupMs),
              (5...30000).contains(durationMs), (0...30000).contains(intervalMs), !cases.isEmpty, cases.count <= 8,
              Set(cases.map(\.id)).count == cases.count, cases.allSatisfy({
                  !$0.id.isEmpty && $0.id.count <= 64 && [4,8,16,32,64,128,256,512,1024,2048,4096].contains($0.blockKiB) &&
                  (1...64).contains($0.queueDepth) && (1...16).contains($0.threads) && $0.queueDepth * $0.threads <= 512 &&
                  $0.blockKiB * $0.queueDepth * $0.threads <= 256 * 1024 && fileMiB * 1024 / ($0.blockKiB * $0.threads) >= $0.queueDepth
              }) else { throw BenchError.message("ROM 参数超出范围或文件不足以容纳队列；请增大文件或减小块/队列/线程") }
    }
}
enum BenchError: Error { case message(String) }
struct RawTrial: Codable, Sendable {
    var elapsedNs: UInt64
    var cpuNs: UInt64
    var operations: UInt64
    var logicalBytes: UInt64
    var accepted: Bool
    var latency: Double? { operations > 0 && elapsedNs > 0 ? Double(elapsedNs) / Double(operations) : nil }
}
struct NativeMeasurement: Codable, Sendable {
    var status: Int
    var verified: Bool
    var kind: Int
    var threads: Int
    var qos: Int
    var workingSetBytes: UInt64
    var warmupOperations: UInt64
    var wallNs: UInt64
    var checksum: UInt64
    var noCacheHint: Bool
    var nodeStrideBytes: Int
    var trials: [RawTrial]
    init(_ result: BBResult) {
        var raw = result.trials
        trials = withUnsafePointer(to: &raw) { pointer in
            pointer.withMemoryRebound(to: BBTrial.self, capacity: 9) {
                UnsafeBufferPointer(start: $0, count: max(0, min(9, Int(result.trial_count)))).map {
                    RawTrial(elapsedNs: $0.elapsed_ns, cpuNs: $0.cpu_ns, operations: $0.operations,
                             logicalBytes: $0.logical_bytes, accepted: $0.accepted != 0)
                }
            }
        }
        status = Int(result.status); verified = result.verified != 0; kind = Int(result.kind)
        threads = Int(result.threads); qos = Int(result.qos); workingSetBytes = result.working_set_bytes
        warmupOperations = result.warmup_operations; wallNs = result.wall_ns; checksum = result.checksum; noCacheHint = result.no_cache != 0
        nodeStrideBytes = Int(result.node_stride_bytes)
    }
    var error: String? { switch status {
    case 0: nil; case 1: "测试已停止"; case 2: "参数不受支持"; case 3: "内存或线程资源不足"
    case 4: "计算、数据或调度设置校验失败"; case 5: "文件读写失败"; default: "此后端暂未实现该项目"
    } }
}
struct ScoreItem: Codable, Identifiable, Sendable {
    var id: String
    var title: String
    var unit: String
    var values: [Double] = []
    var measurements: [NativeMeasurement] = []
    var gpuSamples: [GPUSample] = []
    var state = "pending"
    var reason: String?
    var aggregation: String?
    var storageSamples: [StorageSample]?
    var calibration: [NativeMeasurement]?
    var median: Double? { Statistics.median(values) }
    var score: Double? { aggregation == "maximum_completed_round" ? values.filter { $0.isFinite && $0 > 0 }.max() : median }
    var aggregationTitle: String { aggregation == "maximum_completed_round" ? "最佳完整轮次" : "中位数" }
}
struct StorageSample: Codable, Sendable {
    var queueDepth: Int
    var blockBytes: Int
    var random: Bool
    var meanOutstandingPerThread: Double
    var maxOutstandingPerThread: Int
    var flushNsSeparate: UInt64
    var prepareBytes: UInt64
    var writtenBytesTotal: UInt64
    var errnoCode: Int
    var resourceLimited: Bool
    var backend = "posix-aio"
    var dataPattern = "splitmix64-64mib-pool-v1"
    var timer = "submission-through-last-completion;flush-separate"
    init(_ result: BBStorageResult) {
        queueDepth = Int(result.queue_depth); blockBytes = Int(result.block_bytes); random = result.random_access != 0
        meanOutstandingPerThread = result.mean_outstanding; maxOutstandingPerThread = Int(result.max_outstanding)
        flushNsSeparate = result.flush_ns; prepareBytes = result.prepare_bytes; writtenBytesTotal = result.written_bytes_total
        errnoCode = Int(result.error_number)
        resourceLimited = result.resource_limited != 0
    }
}
struct GPUSample: Codable, Sendable {
    var workUnits: UInt64
    var gpuElapsedNs: UInt64
    var wallElapsedNs: UInt64
    var verified: Bool
    var iterations: Int
    var itemCount: Int
    var timer = "MTLCommandBuffer.gpuEndTime-gpuStartTime"
}
struct CurveBatch: Codable, Sendable {
    var bytes: UInt64
    var pass: Int
    var attempt: Int
    var seed: UInt64
    var measurement: NativeMeasurement
}
struct CurvePoint: Codable, Identifiable, Sendable {
    var bytes: UInt64
    var latencyNs: Double
    var lowNs: Double
    var highNs: Double
    var stable: Bool
    var passMedians: [Double]
    var id: UInt64 { bytes }
}
struct CurveRegion: Codable, Sendable, Identifiable {
    var lowerBytes: UInt64
    var upperBytes: UInt64
    var medianNs: Double
    var id: UInt64 { lowerBytes }
}
struct CurveTransition: Codable, Sendable, Identifiable {
    var lowerBytes: UInt64
    var upperBytes: UInt64
    var beforeNs: Double
    var afterNs: Double
    var id: UInt64 { lowerBytes }
}
struct CurveGroup: Codable, Identifiable, Sendable {
    var qos: Int
    var plannedSizes: [UInt64]
    var batches: [CurveBatch] = []
    var points: [CurvePoint] = []
    var regions: [CurveRegion] = []
    var transitions: [CurveTransition] = []
    var summary = "等待扫描"
    var id: Int { qos }
    var title: String { qos == 0 ? "高优先级" : "后台优先级" }
}
struct BenchReport: Codable, Identifiable, Sendable {
    var schemaVersion = 2
    var platform = "ios"
    var appVersion: String
    var id: UUID
    var family: BenchFamily
    var config: BenchConfig
    var startedAt: Date
    var finishedAt: Date?
    var state = "running"
    var progress = "准备测试"
    var device: DeviceInfo
    var scores: [ScoreItem]
    var curves: [CurveGroup] = []
    var plannedRounds: Int
    var processedRounds = 0
    var completedRounds = 0
    var error: String?
    var coreProtocol = "apple-unpinned-index-v2"
    var coreBinding = false
    var frequencyHz: UInt64? = nil
    var thermalAtStart: Int
    var thermalAtEnd: Int?
    var qualityFlags: [String]
    var curvePairs: Int { curves.reduce(0) { $0 + Set($1.batches.map { "\($0.bytes)-\($0.pass)" }).count } }
    var plannedCurvePairs: Int { curves.reduce(0) { $0 + $1.plannedSizes.count * 2 } }
    var statusTitle: String { switch state {
    case "running": "测试中"; case "completed": "已完成"; case "partial": "部分测量未通过验证"
    case "cancelled": "已停止"; case "interrupted": "运行中断，已保存采样"; default: "未完成"
    } }
}
enum Statistics {
    static func median(_ input: [Double]) -> Double? {
        let values = input.filter { $0.isFinite && $0 > 0 }.sorted()
        guard !values.isEmpty else { return nil }
        let middle = values.count / 2
        return values.count % 2 == 0 ? (values[middle - 1] + values[middle]) / 2 : values[middle]
    }
    static func consistent(_ values: [Double]) -> Bool {
        guard values.count >= 5, let center = median(values) else { return false }
        let deviations = values.map { abs($0 - center) }.sorted()
        return deviations[deviations.count / 2] / center <= 0.06 && values.filter { abs($0 - center) / center <= 0.12 }.count * 5 >= values.count * 4
    }
    static func grid(maximum: UInt64, steps: Int, anchors: [UInt64] = []) -> [UInt64] {
        guard maximum >= 4096, steps > 0 else { return [] }
        let count = Int(ceil(log2(Double(maximum) / 4096) * Double(steps)))
        let regular = (0...count).map { UInt64(4096 * pow(2, Double($0) / Double(steps))) / 256 * 256 }
        let reference = anchors.filter { $0 <= 256 * 1048576 }.flatMap { [$0 * 15 / 16, $0, $0 * 17 / 16].map { $0 / 256 * 256 } }
        return Array(Set((regular + reference + [maximum / 256 * 256]).filter { $0 >= 4096 && $0 <= maximum })).sorted()
    }
    static func refresh(_ group: inout CurveGroup) {
        var latest: [String: NativeMeasurement] = [:]
        for batch in group.batches { latest["\(batch.bytes)-\(batch.pass)"] = batch.measurement }
        group.points = group.plannedSizes.compactMap { bytes in
            let passes: [[Double]] = (0...1).map { pass in
                guard let raw = latest["\(bytes)-\(pass)"], raw.status == 0, raw.verified else { return [] }
                return raw.trials.filter(\.accepted).compactMap(\.latency)
            }
            let values = passes.flatMap { $0 }.sorted(); guard let center = median(values) else { return nil }
            let medians = passes.compactMap(median)
            let stable = passes.allSatisfy(consistent) && medians.count == 2 && (medians.max()! - medians.min()!) / center <= 0.12
            return CurvePoint(bytes: bytes, latencyNs: center, lowNs: values[(values.count - 1) / 10],
                              highNs: values[(values.count - 1) * 9 / 10], stable: stable, passMedians: medians)
        }
    }
    // 只在连续有效点中寻找持续转换；不跨缺口推断缓存级别。 / Find sustained transitions only in contiguous valid spans, never infer cache levels across gaps.
    static func analyze(_ group: inout CurveGroup) {
        group.regions = []; group.transitions = []
        let lookup = Dictionary(uniqueKeysWithValues: group.points.map { ($0.bytes, $0) })
        var spans: [[CurvePoint]] = []; var span: [CurvePoint] = []
        for bytes in group.plannedSizes {
            if let point = lookup[bytes], point.stable { span.append(point) }
            else if !span.isEmpty { spans.append(span); span = [] }
        }
        if !span.isEmpty { spans.append(span) }
        for points in spans where points.count >= 6 {
            var start = 0; var lastBoundary = -4
            for i in 3...(points.count - 3) {
                let before = median(points[(i - 3)..<i].map(\.latencyNs))!
                let after = median(points[i..<(i + 3)].map(\.latencyNs))!
                let direct = points[i].latencyNs / points[i - 1].latencyNs
                if i - lastBoundary >= 4 && after / before >= 1.4 && direct >= 1.2 && points[i].bytes <= points[i - 1].bytes * 2 {
                    group.transitions.append(CurveTransition(lowerBytes: points[i - 1].bytes, upperBytes: points[i].bytes, beforeNs: before, afterNs: after))
                    group.regions.append(CurveRegion(lowerBytes: points[start].bytes, upperBytes: points[i - 1].bytes, medianNs: median(points[start..<i].map(\.latencyNs))!))
                    start = i; lastBoundary = i
                }
            }
            group.regions.append(CurveRegion(lowerBytes: points[start].bytes, upperBytes: points.last!.bytes, medianNs: median(points[start...].map(\.latencyNs))!))
        }
        let valid = group.points.filter(\.stable).count
        group.summary = "\(valid)/\(group.plannedSizes.count) 点通过验证；\(group.regions.count) 个连续区间，\(group.transitions.count) 处持续转换。" + (valid == group.plannedSizes.count ? "" : "未通过范围不推断边界。")
    }
    static func size(_ bytes: UInt64) -> String {
        if bytes >= 1073741824 { return String(format: "%.3g GiB", Double(bytes) / 1073741824) }
        if bytes >= 1048576 { return String(format: "%.3g MiB", Double(bytes) / 1048576) }
        return String(format: "%.3g KiB", Double(bytes) / 1024)
    }
}
