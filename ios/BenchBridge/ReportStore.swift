import Foundation

struct ReportStore: Sendable {
    let root: URL
    init(root: URL? = nil) {
        self.root = root ?? FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0].appendingPathComponent("BenchBridge", isDirectory: true)
    }
    static func encoder() -> JSONEncoder { let encoder = JSONEncoder(); encoder.outputFormatting = [.prettyPrinted, .sortedKeys]; encoder.keyEncodingStrategy = .convertToSnakeCase; encoder.dateEncodingStrategy = .iso8601; return encoder }
    static func decoder() -> JSONDecoder { let decoder = JSONDecoder(); decoder.keyDecodingStrategy = .convertFromSnakeCase; decoder.dateDecodingStrategy = .iso8601; return decoder }
    func save(_ report: BenchReport) throws {
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        try Self.encoder().encode(report).write(to: root.appendingPathComponent(report.id.uuidString + ".json"), options: .atomic)
    }
    func all() -> [BenchReport] {
        let files = (try? FileManager.default.contentsOfDirectory(at: root, includingPropertiesForKeys: [.contentModificationDateKey, .fileSizeKey])) ?? []
        return files.filter { $0.pathExtension == "json" && UUID(uuidString: $0.deletingPathExtension().lastPathComponent) != nil }
            .sorted { ((try? $0.resourceValues(forKeys: [.contentModificationDateKey]).contentModificationDate) ?? .distantPast) > ((try? $1.resourceValues(forKeys: [.contentModificationDateKey]).contentModificationDate) ?? .distantPast) }.prefix(40)
            .compactMap { url in guard ((try? url.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? Int.max) < 16 * 1048576,
                                    let data = try? Data(contentsOf: url) else { return nil }; return try? Self.decoder().decode(BenchReport.self, from: data) }
    }
    func recover() throws {
        for var report in all() where report.state == "running" {
            report.state = "interrupted"; report.finishedAt = Date(); report.error = L10n.t("m_7f984426d344")
            let temporary = FileManager.default.temporaryDirectory.appendingPathComponent("BenchBridgeIO-" + report.id.uuidString)
            if FileManager.default.fileExists(atPath: temporary.path) {
                do { try FileManager.default.removeItem(at: temporary) }
                catch { report.error = L10n.t("m_a97fa26c8c08", error.localizedDescription) }
            }
            try save(report)
        }
    }
    func export(_ report: BenchReport) throws -> URL {
        // 保留原始测量，另附当前语言摘要。 / Preserve raw measurements and add a summary in the current language.
        var exported = report
        exported.exportLocale = L10n.current.rawValue
        exported.localizedSummary = ([report.family.title, report.statusTitle, report.config.summary(report.family)] + report.scores.map { item in
            let value = item.score.map { String(format: "%.2f", $0) } ?? "—"
            return "\(L10n.display(item.title)): \(value) \(item.unit)"
        } + [L10n.display(report.error ?? "")]).joined(separator: "\n")
        let folder = FileManager.default.temporaryDirectory.appendingPathComponent("BenchBridgeExports", isDirectory: true)
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        let url = folder.appendingPathComponent("BenchBridge_\(report.family.title)_\(report.id.uuidString).json")
        try Self.encoder().encode(exported).write(to: url, options: .atomic); return url
    }
}
