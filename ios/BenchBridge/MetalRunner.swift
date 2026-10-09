import Foundation
import Metal

final class MetalRunner {
    private struct Params { var kind, count, iterations, width, height: UInt32 }
    let device: MTLDevice
    let queue: MTLCommandQueue
    private let library: MTLLibrary
    init() throws {
        guard let device = MTLCreateSystemDefaultDevice(), let queue = device.makeCommandQueue(), let library = device.makeDefaultLibrary()
        else { throw BenchError.message(L10n.t("m_2745849de623")) }
        self.device = device; self.queue = queue; self.library = library
    }
    static func unavailable(_ kind: Int) -> String? {
        switch kind {
        case 4, 11: L10n.t("m_9e8ac889e229")
        case 8, 9: L10n.t("m_d62f96646e4a")
        default: nil
        }
    }
    private func pattern(_ index: UInt32) -> UInt32 {
        var x = index &* 747796405 &+ 419 &* 2891336453 &+ 277803737
        x = ((x >> ((x >> 28) &+ 4)) ^ x) &* 277803737
        return (x >> 22) ^ x
    }
    func run(kind: Int, config: BenchConfig, token: CancellationToken) throws -> GPUSample {
        if let reason = Self.unavailable(kind) { throw BenchError.message(reason) }
        let name = kind == 0 ? "memoryRead" : kind == 1 ? "memoryWrite" : kind == 2 ? "memoryCopy" : kind == 10 ? "julia" : "arithmetic"
        guard let function = library.makeFunction(name: name) else { throw BenchError.message(L10n.t("m_30b09c2fc1d2")) }
        let pipeline = try device.makeComputePipelineState(function: function)
        let memoryWords = config.memoryMiB * 1048576 / 4
        let count = kind == 0 ? memoryWords / 64 : kind <= 2 ? memoryWords : kind == 10 ? 256 * 256 : 4096
        let outputWords = kind <= 2 ? (kind == 0 ? count : memoryWords) : kind == 10 ? count : count * 8
        guard let input = device.makeBuffer(length: max(4, kind <= 2 ? memoryWords * 4 : 4), options: .storageModeShared),
              let output = device.makeBuffer(length: outputWords * 4, options: .storageModeShared)
        else { throw BenchError.message(L10n.t("m_6586c0edc8b8")) }
        if kind <= 2 { let pointer = input.contents().bindMemory(to: UInt32.self, capacity: memoryWords); for i in 0..<memoryWords { pointer[i] = pattern(UInt32(i)) } }
        memset(output.contents(), 0, outputWords * 4)
        let iterations: UInt32 = 1024
        var parameters = Params(kind: UInt32(kind), count: UInt32(count), iterations: iterations, width: 256, height: 256)
        func submit() throws -> MTLCommandBuffer {
            try token.check()
            guard let command = queue.makeCommandBuffer(), let encoder = command.makeComputeCommandEncoder() else { throw BenchError.message(L10n.t("m_8ce9b91cc659")) }
            encoder.setComputePipelineState(pipeline); encoder.setBuffer(input, offset: 0, index: 0); encoder.setBuffer(output, offset: 0, index: 1)
            encoder.setBytes(&parameters, length: MemoryLayout<Params>.size, index: 2)
            encoder.dispatchThreads(MTLSize(width: count, height: 1, depth: 1), threadsPerThreadgroup: MTLSize(width: min(256, pipeline.maxTotalThreadsPerThreadgroup), height: 1, depth: 1))
            encoder.endEncoding(); command.commit(); command.waitUntilCompleted()
            guard command.status == .completed else { throw BenchError.message(command.error?.localizedDescription ?? L10n.t("m_9a1da4458b69")) }
            return command
        }
        func verify() throws {
            let words = output.contents().bindMemory(to: UInt32.self, capacity: outputWords)
            let ids = Array(Set([0, count - 1] + stride(from: 0, to: count, by: max(1, count / 127)).map { $0 }))
            for i in ids {
                if kind == 0 { var expected: UInt32 = 0; for j in 0..<64 { expected &+= pattern(UInt32(i * 64 + j)) }; guard words[i] == expected else { throw BenchError.message(L10n.t("m_b39172cac39a")) } }
                else if kind <= 2 { guard words[i] == pattern(UInt32(i)) else { throw BenchError.message(L10n.t("m_fd522dbc2d5d")) } }
                else if kind == 10 { guard words[i] == bb_fractal_reference(10, UInt32(i), 256, 256) else { throw BenchError.message(L10n.t("m_96c5161042c7")) } }
                else { guard bb_check_compute(Int32(kind), UInt32(i), iterations, words.advanced(by: i * 8)) != 0 else { throw BenchError.message(L10n.t("m_7668908d23d5")) } }
            }
        }
        _ = try submit(); try verify()
        let perDispatch: UInt64 = kind <= 2 ? UInt64(memoryWords * 4 * (kind == 2 ? 2 : 1)) : kind == 10 ? UInt64(count) : UInt64(count) * UInt64(iterations) * UInt64(kind == 7 ? 32 : 64)
        let start = DispatchTime.now().uptimeNanoseconds
        var work: UInt64 = 0; var gpu: UInt64 = 0
        repeat {
            let command = try submit()
            let elapsed = command.gpuEndTime - command.gpuStartTime
            guard elapsed.isFinite, elapsed > 0, command.gpuStartTime > 0 else { throw BenchError.message(L10n.t("m_18853a64daca")) }
            gpu += UInt64(elapsed * 1e9); work += perDispatch
        } while DispatchTime.now().uptimeNanoseconds - start < UInt64(config.durationMs) * 1_000_000
        let wall = DispatchTime.now().uptimeNanoseconds - start
        try verify(); try token.check()
        return GPUSample(workUnits: work, gpuElapsedNs: gpu, wallElapsedNs: wall, verified: true, iterations: Int(iterations), itemCount: count)
    }
}
