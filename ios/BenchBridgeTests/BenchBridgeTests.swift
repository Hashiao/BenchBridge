import XCTest
@testable import BenchBridge

final class BenchBridgeTests: XCTestCase {
    func testNativeMemoryCountsAndCancellation() throws {
        let token = try CancellationToken()
        for kind: Int32 in [0, 1, 2, 5] {
            let result = NativeMeasurement(bb_memory(token.handle, kind, 1048576, 1, 5, 10, 0, 419))
            XCTAssertTrue(result.verified, result.error ?? ""); XCTAssertEqual(result.status, 0)
            let trial = try XCTUnwrap(result.trials.first); XCTAssertGreaterThan(trial.operations, 0)
            if kind == 5 { XCTAssertGreaterThanOrEqual(result.warmupOperations, 1048576 / 64 * 2) }
            else { XCTAssertEqual(trial.logicalBytes, trial.operations * (kind == 2 ? 16 : 8)) }
        }
        token.cancel("test"); XCTAssertEqual(bb_memory(token.handle, 0, 1048576, 2, 5, 10, 0, 419).status, 1)
    }
    func testSharedCPUReferencesAndAppleCrypto() throws {
        let token = try CancellationToken()
        for kind: Int32 in 3...11 {
            let result = bb_cpu_compute(token.handle, kind, 5)
            XCTAssertEqual(result.status, 0, "kind \(kind)"); XCTAssertEqual(result.verified, 1)
        }
    }
    func testCurveRetainsMultipleTransitionsAndDoesNotBridgeGaps() {
        let sizes = Statistics.grid(maximum: 4 * 1048576, steps: 4)
        var group = CurveGroup(qos: 0, plannedSizes: sizes)
        group.points = sizes.map { bytes in
            let value: Double = bytes <= 65536 ? 1 : bytes <= 1048576 ? 4 : 100
            return CurvePoint(bytes: bytes, latencyNs: value, lowNs: value, highNs: value, stable: true, passMedians: [value, value])
        }
        let original = group.points.map(\.latencyNs)
        Statistics.analyze(&group); XCTAssertEqual(group.transitions.count, 2); XCTAssertEqual(original, group.points.map(\.latencyNs))
        let index = group.points.firstIndex { $0.bytes > 65536 }!
        group.points[index].stable = false; Statistics.analyze(&group)
        XCTAssertEqual(group.transitions.count, 1)
        XCTAssertTrue(group.transitions.allSatisfy { $0.lowerBytes > 65536 })
    }
    func testReportRoundTripRecoveryAndExport() throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent("StoreTest-" + UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: root) }
        let store = ReportStore(root: root)
        var report = BenchWorker.initial(.memory, config: .test)
        report.scores[0].values = [12.5, 13.5]; report.completedRounds = 2
        try store.save(report); try store.recover()
        let recovered = try XCTUnwrap(store.all().first)
        XCTAssertEqual(recovered.id, report.id); XCTAssertEqual(recovered.state, "interrupted")
        XCTAssertEqual(recovered.scores[0].median, 13); XCTAssertEqual(recovered.config, report.config)
        let exported = try store.export(recovered); defer { try? FileManager.default.removeItem(at: exported) }
        let data = try Data(contentsOf: exported)
        let decoded = try ReportStore.decoder().decode(BenchReport.self, from: data)
        XCTAssertEqual(decoded.scores[0].values, [12.5, 13.5]); XCTAssertFalse(decoded.coreBinding)
        XCTAssertTrue(decoded.qualityFlags.contains("functional_test_not_device_performance"))
    }
    func testMetalValidationAndDeviceTimer() throws {
        guard let backend = try? MetalRunner() else { throw XCTSkip("No Metal device in this simulator environment") }
        let token = try CancellationToken()
        for kind in [0, 1, 2, 3, 5, 6, 7, 10] {
            do {
                let result = try backend.run(kind: kind, config: .test, token: token)
                XCTAssertTrue(result.verified); XCTAssertGreaterThan(result.gpuElapsedNs, 0); XCTAssertGreaterThan(result.workUnits, 0)
            } catch BenchError.message(let message) where message.contains("时间戳不可用") { throw XCTSkip(message) }
        }
        XCTAssertNotNil(MetalRunner.unavailable(4)); XCTAssertNotNil(MetalRunner.unavailable(8))
    }
    func testStorageCountsAndOwnedFileCleanup() throws {
        let token = try CancellationToken(); let root = FileManager.default.temporaryDirectory.appendingPathComponent("IOTest-" + UUID().uuidString)
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: root) }
        for write: Int32 in 0...1 {
            let path = root.appendingPathComponent("test-\(write).bin")
            let native = path.path.withCString { NativeMeasurement(bb_storage(token.handle, $0, write, 1, 1048576, 4096, 10)) }
            XCTAssertTrue(native.verified, native.error ?? ""); let trial = try XCTUnwrap(native.trials.first)
            XCTAssertEqual(trial.logicalBytes, trial.operations * 4096); XCTAssertFalse(FileManager.default.fileExists(atPath: path.path))
        }
    }
}
