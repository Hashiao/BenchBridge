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
    var cacheMaxMiB = 128
    var stepsPerOctave = 8
    var includeCurve = true
    var backgroundCurve = false
    var storageMiB = 64
    var functionalTest = false
    static var quick: Self { Self(memoryMiB: 16, durationMs: 100, repeats: 1, cacheMaxMiB: 16, stepsPerOctave: 4, storageMiB: 16) }
    static var test: Self { Self(memoryMiB: 1, durationMs: 10, repeats: 1, cacheMaxMiB: 1, stepsPerOctave: 1, storageMiB: 1, functionalTest: true) }
    func validate() throws {
        guard (1...256).contains(memoryMiB), (1...8).contains(threads), (5...5000).contains(durationMs),
              (1...5).contains(repeats), (1...256).contains(cacheMaxMiB), (1...8).contains(stepsPerOctave), (1...256).contains(storageMiB)
        else { throw BenchError.message("测试参数超出范围") }
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
    var median: Double? { Statistics.median(values) }
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
    var schemaVersion = 1
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
    var coreProtocol = "apple-unpinned-index-v1"
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
        return deviations[deviations.count / 2] / center <= 0.08 && values.filter { abs($0 - center) / center <= 0.15 }.count * 5 >= values.count * 4
    }
    static func grid(maximum: UInt64, steps: Int) -> [UInt64] {
        guard maximum >= 4096, steps > 0 else { return [] }
        let count = Int(ceil(log2(Double(maximum) / 4096) * Double(steps)))
        return Array(Set((0...count).map { UInt64(4096 * pow(2, Double($0) / Double(steps))) / 256 * 256 }.filter { $0 <= maximum } + [maximum / 256 * 256])).sorted()
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
            let stable = passes.allSatisfy(consistent) && medians.count == 2 && (medians.max()! - medians.min()!) / center <= 0.15
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
    static func size(_ bytes: UInt64) -> String { bytes >= 1048576 ? String(format: "%.3g MiB", Double(bytes) / 1048576) : String(format: "%.3g KiB", Double(bytes) / 1024) }
}
