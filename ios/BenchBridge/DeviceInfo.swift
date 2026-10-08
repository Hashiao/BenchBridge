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
    var gpuName: String?
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
        return Self(machine: String(cString: buffer), osVersion: ProcessInfo.processInfo.operatingSystemVersionString,
                    logicalCpuCount: ProcessInfo.processInfo.activeProcessorCount, physicalMemoryBytes: ProcessInfo.processInfo.physicalMemory,
                    pageBytes: Int(getpagesize()), simulator: simulated, reportedL1Bytes: integer("hw.l1dcachesize"),
                    reportedL2Bytes: integer("hw.l2cachesize"), reportedL3Bytes: integer("hw.l3cachesize"))
    }
}
