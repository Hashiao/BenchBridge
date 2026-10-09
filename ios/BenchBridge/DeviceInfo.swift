import Foundation
import UIKit
import Darwin

struct DeviceInfo: Codable, Sendable {
    var machine: String
    var osVersion: String
    var logicalCpuCount: Int
    var physicalMemoryBytes: UInt64
    var pageBytes: Int
    var simulator: Bool
    var reportedL1Bytes: UInt64?
    var reportedL2Bytes: UInt64?
    var reportedL3Bytes: UInt64?
    var reportedLineBytes: UInt64?
    var gpuName: String?
    var modelReference: AppleModelReference?
    var performanceLevels: [ApplePerformanceLevel]?
    var topologyNotes: [String]?
    var displayName: String { modelReference?.model ?? machine }
    static func integer(_ name: String) -> UInt64? {
        var value: UInt64 = 0; var length = MemoryLayout<UInt64>.size
        return sysctlbyname(name, &value, &length, nil, 0) == 0 && value > 0 ? value : nil
    }
    static func collect() -> Self {
        var length = 0; sysctlbyname("hw.machine", nil, &length, nil, 0)
        var buffer = [CChar](repeating: 0, count: max(1, length)); sysctlbyname("hw.machine", &buffer, &length, nil, 0)
        #if targetEnvironment(simulator)
        let simulated = true
        #else
        let simulated = false
        #endif
        let machine = String(cString: buffer)
        let reference = simulated ? nil : AppleCatalog.lookup(machine)
        let levelCount = min(16, Int(integer("hw.nperflevels") ?? 0))
        let levels = (0..<levelCount).compactMap { index -> ApplePerformanceLevel? in
            let prefix = "hw.perflevel\(index)."
            guard let count = integer(prefix + "physicalcpu") ?? integer(prefix + "logicalcpu") else { return nil }
            return ApplePerformanceLevel(index: index, name: string(prefix + "name") ?? "核心组 \(index)", count: Int(count),
                                         l1DataBytes: integer(prefix + "l1dcachesize"), l2Bytes: integer(prefix + "l2cachesize"),
                                         coresPerL2: integer(prefix + "cpusperl2").map(Int.init))
        }
        var notes = ["Core-type metadata does not establish affinity or identify the executing core."]
        if levels.isEmpty { notes.append("Runtime core-group topology unavailable; catalog remains reference-only.") }
        if simulated { notes.append("Simulator topology describes the host, not the simulated device.") }
        return Self(machine: machine, osVersion: ProcessInfo.processInfo.operatingSystemVersionString,
                    logicalCpuCount: ProcessInfo.processInfo.activeProcessorCount, physicalMemoryBytes: ProcessInfo.processInfo.physicalMemory,
                    pageBytes: Int(getpagesize()), simulator: simulated, reportedL1Bytes: integer("hw.l1dcachesize"),
                    reportedL2Bytes: integer("hw.l2cachesize"), reportedL3Bytes: integer("hw.l3cachesize"), reportedLineBytes: integer("hw.cachelinesize"),
                    modelReference: reference, performanceLevels: levels, topologyNotes: notes)
    }
    static func string(_ key: String) -> String? {
        var length = 0
        guard sysctlbyname(key, nil, &length, nil, 0) == 0, (1...1024).contains(length) else { return nil }
        var bytes = [CChar](repeating: 0, count: length)
        guard sysctlbyname(key, &bytes, &length, nil, 0) == 0 else { return nil }
        return String(cString: bytes)
    }
}
struct ApplePerformanceLevel: Codable, Identifiable, Sendable {
    var index: Int
    var name: String
    var count: Int
    var l1DataBytes: UInt64?
    var l2Bytes: UInt64?
    var coresPerL2: Int?
    var source = "runtime-sysctl-hw.perflevel"
    var id: Int { index }
}
struct AppleModelReference: Codable, Sendable {
    var model: String
    var soc: String
    var performanceCores: [Int]
    var efficiencyCores: [Int]
    var source: String
    var identitySource = "https://github.com/devicekit/DeviceKit"
    var revision = "2026-10-09"
    var coreSummary: String {
        guard !performanceCores.isEmpty, !efficiencyCores.isEmpty else { return "核心分组待运行时确认" }
        return "\(performanceCores.map(String.init).joined(separator: "/")) 个高性能核 + \(efficiencyCores.map(String.init).joined(separator: "/")) 个能效核"
    }
}
