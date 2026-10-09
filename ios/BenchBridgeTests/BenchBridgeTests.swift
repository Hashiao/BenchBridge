import XCTest
@testable import BenchBridge

final class BenchBridgeTests: XCTestCase {
    func testPrimaryLanguageAndReviewedTerminology() {
        for tag in ["zh", "zh-CN", "zh-SG", "zh-Hans-TW", "zh_Hans_HK"] { XCTAssertEqual(L10n.language(for: tag), .simplified) }
        for tag in ["zh-TW", "zh-HK", "zh-MO", "zh-Hant-CN", "zh_Hant_SG"] { XCTAssertEqual(L10n.language(for: tag), .traditional) }
        for tag in ["en", "ja-JP", "fr-FR", "ar-EG", "zh-Latn", ""] { XCTAssertEqual(L10n.language(for: tag), .english) }
        XCTAssertEqual(L10n.text("m_df9062b60024", language: .traditional), "快取與記憶體")
        XCTAssertEqual(L10n.text("m_290505a7ff35", language: .traditional), "CPU 執行緒")
        XCTAssertEqual(L10n.text("m_6f02986c45d1", language: .traditional), "循序讀取")
        XCTAssertEqual(L10n.display("CPU 6 · 5.01 GHz 上限", language: .english), "CPU 6 · Up to 5.01 GHz")
        XCTAssertEqual(L10n.display("Export JSON", language: .traditional), "匯出 JSON")
        XCTAssertEqual(L10n.display("AFFINITY_VERIFY_FAILED", language: .traditional), "AFFINITY_VERIFY_FAILED")
    }

    func testEveryLocalizationResourceHasCompleteArguments() throws {
        let url = try XCTUnwrap(Bundle.main.url(forResource: "localization", withExtension: "json"))
        let entries = try JSONDecoder().decode([String: [String: String]].self, from: Data(contentsOf: url))
        XCTAssertGreaterThan(entries.count, 500)
        for key in entries.keys { for language in L10n.Language.allCases {
            let value = L10n.text(key, language: language, values: (0..<12).map { "ARG\($0)" })
            XCTAssertNotEqual(value, key); XCTAssertFalse(value.isEmpty)
            XCTAssertNil(value.range(of: #"\{\d+\}"#, options: .regularExpression))
            if language == .english { XCTAssertNil(value.range(of: #"[\u3400-\u9fff]"#, options: .regularExpression)) }
        } }
    }
    func testAlignedDefaultsAndLegacyDecoding() throws {
        let config = BenchConfig()
        XCTAssertEqual(config.ramSettings.memoryMiB, 64); XCTAssertEqual(config.ramSettings.latencyMiB, 64)
        XCTAssertEqual(config.ramSettings.threads, 1); XCTAssertFalse(config.ramSettings.automaticThreads)
        XCTAssertEqual(config.ramSettings.repeats, 3); XCTAssertEqual(config.ramSettings.latencyRepeats, 3)
        XCTAssertTrue(config.singleCurveSample == true); XCTAssertEqual(config.ramSettings.aggregation, "arithmetic_mean")
        XCTAssertEqual(config.cacheMaxMiB, 64); XCTAssertTrue(config.backgroundCurve)
        XCTAssertEqual(config.storageSettings.fileMiB, 1024); XCTAssertEqual(config.storageSettings.repeats, 3)
        XCTAssertEqual(config.storageSettings.warmupMs, 1000); XCTAssertEqual(config.storageSettings.intervalMs, 1000)
        XCTAssertEqual(config.storageSettings.durationMs, 5000)
        XCTAssertEqual((0..<3).map { config.storageSettings.warmup(forRound: $0) }, [1000,0,0])
        XCTAssertEqual((0..<3).map { config.storageSettings.interval(afterRound: $0, lastItem: false) }, [0,0,1000])
        XCTAssertEqual(config.storageSettings.interval(afterRound: 2, lastItem: true), 0)
        let oldStorage = StorageParameters(protocolId: "diskmark-default-v2-apple-aio", warmupMs: 5000, intervalMs: 5000)
        XCTAssertEqual(oldStorage.warmup(forRound: 1), 5000); XCTAssertEqual(oldStorage.interval(afterRound: 0, lastItem: false), 5000)
        XCTAssertEqual(config.storageSettings.cases.map(\.queueDepth), [8,1,32,1])
        XCTAssertEqual(config.storageSettings.cases.map(\.threads), [1,1,1,1])
        XCTAssertEqual(BenchWorker.initial(.storage, config: config).plannedRounds, 24)
        XCTAssertEqual(BenchWorker.initial(.memory, config: config).plannedRounds, 12)
        let old = """
        {"memory_mi_b":64,"threads":1,"duration_ms":500,"repeats":3,"cache_max_mi_b":128,"steps_per_octave":8,
         "include_curve":true,"background_curve":false,"storage_mi_b":64,"functional_test":false}
        """.data(using: .utf8)!
        let legacy = try ReportStore.decoder().decode(BenchConfig.self, from: old)
        XCTAssertNil(legacy.ram); XCTAssertNil(legacy.storage); XCTAssertEqual(legacy.storageSettings.fileMiB, 64)
        XCTAssertNil(legacy.singleCurveSample); XCTAssertEqual(legacy.ramSettings.aggregation, "median")
        XCTAssertEqual(legacy.ramSettings.durationMs, 500); XCTAssertEqual(legacy.cacheMaxMiB, 128)
        let decoded = try ReportStore.decoder().decode(BenchConfig.self, from: ReportStore.encoder().encode(config))
        XCTAssertEqual(config, decoded)
        var edited = config; edited.ramSettings.memoryMiB = 128
        XCTAssertEqual(edited.ramSettings.latencyMiB, 64); XCTAssertEqual(edited.storageSettings, config.storageSettings)
        XCTAssertEqual(edited.durationMs, 500)
        edited.storageSettings.fileMiB = 0
        XCTAssertNoThrow(try edited.validate(.memory)); XCTAssertNoThrow(try edited.validate(.compute))
        XCTAssertThrowsError(try edited.validate(.storage))
        var custom = config.storageSettings; custom.cases[0].blockKiB = 128; custom.cases[0].queueDepth = 4
        XCTAssertEqual(custom.normalized.cases[0].id, "seq128k-q4t1")
        custom.cases[0] = custom.cases[1]; XCTAssertThrowsError(try custom.normalized.validate())
        var score = ScoreItem(id: "rom", title: "ROM", unit: "MB/s", values: [1,2,3])
        XCTAssertEqual(score.score, 2); score.aggregation = "maximum_completed_round"; XCTAssertEqual(score.score, 3)
        score.values = [1,2,9]; score.aggregation = "arithmetic_mean"; XCTAssertEqual(score.score, 4)
        score.aggregation = nil; XCTAssertEqual(score.score, 2)
    }
    func testAppleCatalogIsExactAndKeepsBinnedVariants() {
        XCTAssertEqual(AppleCatalog.lookup("iPhone19,2")?.soc, "A20 Pro")
        XCTAssertEqual(AppleCatalog.lookup("iPad16,3")?.performanceCores, [3,4])
        XCTAssertEqual(AppleCatalog.lookup("iPad16,8")?.efficiencyCores, [5])
        XCTAssertTrue(AppleCatalog.lookup("iPad15,7")?.performanceCores.isEmpty == true)
        XCTAssertNil(AppleCatalog.lookup("iPhone19,20")); XCTAssertNil(AppleCatalog.lookup("iPhone99,1"))
    }
    func testQueuedStorageAndCancellationDrain() throws {
        let token = try CancellationToken()
        let folder = FileManager.default.temporaryDirectory.appendingPathComponent("QueueTest-" + UUID().uuidString)
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: folder) }
        let path = folder.appendingPathComponent("data.bin")
        var prepared = BBStorageResult()
        let handle = try XCTUnwrap(path.path.withCString { bb_storage_create(token.handle, $0, 8 * 1048576, 1, &prepared) })
        XCTAssertEqual(prepared.preparation_no_cache, 1); XCTAssertEqual(prepared.measurement.no_cache, 1)
        XCTAssertEqual(prepared.buffer_alignment_bytes, 65536)
        for queue: Int32 in [1,8,32] {
            for write: Int32 in [0,1] {
                let raw = bb_storage_run(handle, write, 1, 4096, queue, 1, 5, 30)
                XCTAssertEqual(raw.measurement.status, 0, "errno \(raw.error_number)"); XCTAssertEqual(raw.measurement.verified, 1)
                XCTAssertGreaterThanOrEqual(raw.max_outstanding, queue == 1 ? 1 : 2); XCTAssertLessThanOrEqual(raw.max_outstanding, queue)
                XCTAssertTrue(raw.max_outstanding == queue || raw.resource_limited != 0); XCTAssertGreaterThan(raw.mean_outstanding, 0)
                XCTAssertEqual(NativeMeasurement(raw.measurement).trials.first!.logicalBytes, NativeMeasurement(raw.measurement).trials.first!.operations * 4096)
                XCTAssertEqual(raw.submitted_operations, raw.measurement.trials.0.operations)
                XCTAssertEqual(raw.completed_bytes, raw.measurement.trials.0.logical_bytes)
                XCTAssertGreaterThanOrEqual(raw.completion_wall_ns, raw.measurement.trials.0.elapsed_ns)
                XCTAssertEqual(raw.preparation_no_cache, 1); XCTAssertEqual(raw.measurement.no_cache, 1)
                let sample = StorageSample(raw)
                let decoded = try ReportStore.decoder().decode(StorageSample.self, from: ReportStore.encoder().encode(sample))
                XCTAssertEqual(decoded.queueDepth, Int(queue)); XCTAssertEqual(decoded.completedBytes, raw.completed_bytes)
                XCTAssertEqual(decoded.preparationNoCacheHint, true)
                var legacy = try XCTUnwrap(JSONSerialization.jsonObject(with: ReportStore.encoder().encode(sample)) as? [String: Any])
                for key in ["preparation_no_cache_hint","buffer_alignment_bytes","submitted_operations","completed_bytes","completion_wall_ns"] { legacy.removeValue(forKey: key) }
                legacy["backend"] = "posix-aio"
                let old = try ReportStore.decoder().decode(StorageSample.self, from: JSONSerialization.data(withJSONObject: legacy))
                XCTAssertNil(old.preparationNoCacheHint); XCTAssertNil(old.completedBytes); XCTAssertEqual(old.backend, "posix-aio")
            }
        }
        for queue: Int32 in [8,1] {
            let raw = bb_storage_run(handle, 0, 0, 1048576, queue, 1, 0, 100)
            XCTAssertEqual(raw.measurement.status, 0); XCTAssertEqual(raw.measurement.verified, 1)
            XCTAssertEqual(raw.completed_bytes, raw.measurement.trials.0.operations * 1048576)
            XCTAssertEqual(raw.submitted_operations, raw.measurement.trials.0.operations)
        }
        let shortRun = bb_storage_run(handle, 0, 1, 4096, 1, 16, 1, 5)
        XCTAssertEqual(shortRun.measurement.status, 0); XCTAssertGreaterThan(shortRun.minimum_worker_operations, 0)
        token.cancel("test"); XCTAssertEqual(bb_storage_run(handle, 1, 1, 4096, 32, 1, 0, 5000).measurement.status, 1)
        bb_storage_destroy(handle); XCTAssertFalse(FileManager.default.fileExists(atPath: path.path))
    }
    func testNativeMemoryCountsAndCancellation() throws {
        let token = try CancellationToken()
        for kind: Int32 in [0, 1, 2, 5] {
            let result = NativeMeasurement(bb_memory(token.handle, kind, 1048576, 1, 5, 10, 0, 419))
            XCTAssertTrue(result.verified, result.error ?? ""); XCTAssertEqual(result.status, 0)
            let trial = try XCTUnwrap(result.trials.first); XCTAssertGreaterThan(trial.operations, 0)
            if kind == 5 { XCTAssertGreaterThanOrEqual(result.warmupOperations, 1048576 / 64 * 2) }
            else { XCTAssertEqual(trial.logicalBytes, trial.operations * (kind == 2 ? 16 : 8)) }
        }
        let curve = NativeMeasurement(bb_cache_point(token.handle, 65536, 128, 0, 419))
        XCTAssertEqual(curve.status, 0); XCTAssertTrue(curve.verified)
        XCTAssertEqual(curve.nodeStrideBytes, 128)
        let once = NativeMeasurement(bb_cache_point_once(token.handle, 65536, 128, 0, 419))
        XCTAssertTrue(once.verified); XCTAssertEqual(once.trials.count, 1)
        var single = CurveGroup(qos: 0, plannedSizes: [65536], singleSample: true)
        single.batches = [CurveBatch(bytes: 65536, pass: 0, attempt: 1, seed: 419, measurement: once)]
        Statistics.refresh(&single); XCTAssertEqual(single.points.count, 1)
        XCTAssertEqual(single.points[0].latencyNs, once.trials[0].latency)
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
        XCTAssertEqual(decoded.exportLocale, L10n.current.rawValue); XCTAssertFalse(decoded.localizedSummary?.isEmpty ?? true)
        XCTAssertTrue(decoded.qualityFlags.contains("functional_test_not_device_performance"))
        let diagnostic = try XCTUnwrap(decoded.runtimeDiagnostics?.first)
        XCTAssertEqual(diagnostic.stage, "report_created"); XCTAssertGreaterThan(diagnostic.processId, 0)
        XCTAssertEqual(diagnostic.affinityCapability, "unavailable_system_scheduled_qos")
        var legacy = try XCTUnwrap(JSONSerialization.jsonObject(with: data) as? [String: Any])
        legacy.removeValue(forKey: "runtime_diagnostics")
        legacy.removeValue(forKey: "export_locale"); legacy.removeValue(forKey: "localized_summary")
        let old = try ReportStore.decoder().decode(BenchReport.self, from: JSONSerialization.data(withJSONObject: legacy))
        XCTAssertNil(old.runtimeDiagnostics); XCTAssertEqual(old.scores[0].values, decoded.scores[0].values)
        XCTAssertNil(old.exportLocale); XCTAssertNil(old.localizedSummary)
    }
    func testMetalValidationAndDeviceTimer() throws {
        guard let backend = try? MetalRunner() else { throw XCTSkip("No Metal device in this simulator environment") }
        let token = try CancellationToken()
        for kind in [0, 1, 2, 3, 5, 6, 7, 10] {
            do {
                let result = try backend.run(kind: kind, config: .test, token: token)
                XCTAssertTrue(result.verified); XCTAssertGreaterThan(result.gpuElapsedNs, 0); XCTAssertGreaterThan(result.workUnits, 0)
            } catch BenchError.message(let message) where message == L10n.t("m_18853a64daca") { throw XCTSkip(message) }
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
