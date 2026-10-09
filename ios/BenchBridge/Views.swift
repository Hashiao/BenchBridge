import SwiftUI
import Charts
import UIKit

struct RootView: View {
    @ObservedObject var model: BenchModel
    var body: some View {
        TabView {
            ForEach(BenchFamily.allCases, id: \.self) { family in
                NavigationStack { Dashboard(model: model, family: family) }
                    .tabItem { Label(family.title, systemImage: family == .memory ? "memorychip" : family == .storage ? "internaldrive" : "cpu") }
            }
            NavigationStack {
                List(model.history) { report in NavigationLink {
                    ReportDetails(report: report, store: model.store)
                } label: {
                    VStack(alignment: .leading) {
                        Text(L10n.display("\(report.family.title) · \(report.statusTitle)"))
                        Text(L10n.display(report.startedAt.formatted(Date.FormatStyle(date: .abbreviated, time: .shortened).locale(L10n.locale)))).font(.caption).foregroundStyle(.secondary)
                    }
                } }.navigationTitle(L10n.t("m_b0385cfe4b42")).onAppear { model.refresh() }.accessibilityIdentifier("history-list")
            }.tabItem { Label(L10n.t("m_b0385cfe4b42"), systemImage: "clock") }
            NavigationStack { DevicePage(info: DeviceInfo.collect()) }.tabItem { Label(L10n.t("m_e1506406a5bd"), systemImage: "iphone.and.ipad") }
        }.tint(.indigo)
    }
}
private struct Dashboard: View {
    @ObservedObject var model: BenchModel
    let family: BenchFamily
    @State private var settings = false
    @Environment(\.horizontalSizeClass) private var sizeClass
    private var report: BenchReport? { model.reports[family] }
    private var parameters: BenchConfig { report?.config ?? model.config }
    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                if DeviceInfo.collect().simulator { Label(L10n.t("m_b118ad4032a0"), systemImage: "desktopcomputer").font(.caption).foregroundStyle(.orange).accessibilityIdentifier("simulator-warning") }
                let device = report?.device ?? DeviceInfo.collect()
                Text(L10n.display(device.displayName + (device.modelReference.map { " · \($0.soc)" } ?? ""))).font(.subheadline)
                Text(L10n.display(parameters.summary(family))).font(.caption).foregroundStyle(.secondary).accessibilityIdentifier("config-summary")
                Text(L10n.display(report?.progress ?? L10n.t("m_1bd1893c0900"))).font(.subheadline).foregroundStyle(.indigo).accessibilityIdentifier("run-progress")
                if let report {
                    Text(L10n.t("m_a0c512dee60f", report.completedRounds, report.plannedRounds) + (report.curves.isEmpty ? "" : L10n.t("m_48ecc65b106e", report.curvePairs, report.plannedCurvePairs)))
                        .font(.caption).foregroundStyle(.secondary)
                }
                if family == .memory {
                    VStack(alignment: .leading, spacing: 12) {
                        Text(L10n.t("m_f14d35b2a30e")).font(.caption)
                        RAMRow(scores: report?.scores ?? BenchWorker.scorePlan(.memory, config: model.config))
                        Divider()
                        CurveChart(groups: report?.curves ?? [], maximumMiB: report?.config.cacheMaxMiB ?? model.config.cacheMaxMiB,
                                   height: sizeClass == .regular ? 340 : 200)
                    }.card()
                    Text(L10n.t("m_32791febab2e")).font(.caption).foregroundStyle(.secondary)
                } else if family == .storage {
                    VStack(alignment: .leading, spacing: 16) {
                        StorageBoard(scores: report?.scores ?? BenchWorker.scorePlan(.storage, config: model.config))
                        Text(L10n.display(parameters.storage == nil ? L10n.t("m_7722c7c5789d") : L10n.t("m_04f95c015bbb")))
                            .font(.caption).foregroundStyle(.secondary)
                    }.card()
                } else {
                    VStack(spacing: 12) {
                        HStack { Text(L10n.t("m_79f326be4409")).frame(maxWidth: .infinity, alignment: .leading); Text(L10n.display("CPU")).frame(width: 90); Text(L10n.display("GPU")).frame(width: 90) }.font(.caption)
                        ForEach(0..<12, id: \.self) { kind in
                            let plan = report?.scores ?? BenchWorker.scorePlan(.compute)
                            HStack {
                                Text(L10n.display(BenchWorker.computeNames[kind])).font(.subheadline).frame(maxWidth: .infinity, alignment: .leading)
                                ScoreCell(item: plan.first { $0.id == "cpu-\(kind)" }!).frame(width: 90)
                                ScoreCell(item: plan.first { $0.id == "gpu-\(kind)" }!).frame(width: 90)
                            }; Divider()
                        }
                        Text(L10n.t("m_6aa2ece5a458")).font(.caption).foregroundStyle(.secondary)
                    }.card()
                }
                if let error = report?.error ?? model.error { Text(L10n.display(error)).font(.caption).foregroundStyle(.red) }
                if let report, report.state != "running" {
                    NavigationLink(L10n.t("m_a748cc074f78"), destination: ReportDetails(report: report, store: model.store)).accessibilityIdentifier("show-details")
                    ExportButton(report: report, store: model.store)
                }
                Text(L10n.display("BenchBridge \(Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "") · iPhone / iPad"))
                    .font(.caption2).foregroundStyle(.secondary)
            }.padding().frame(maxWidth: 1000).frame(maxWidth: .infinity)
        }
        .navigationTitle(family == .memory ? L10n.t("m_df9062b60024") : family == .storage ? L10n.t("m_53f5039a31a0") : L10n.t("m_4bdd5f5904f1"))
        .navigationBarTitleDisplayMode(.inline)
        .background(Color(uiColor: .systemGroupedBackground))
        .toolbar { Button { settings = true } label: { Image(systemName: "gearshape") }.disabled(model.running).accessibilityIdentifier("settings") }
        .safeAreaInset(edge: .bottom) {
            HStack {
                Spacer()
                Button(model.running ? L10n.t("m_d3a18faf1859") : L10n.t("m_69ed375721d9")) { if model.running { model.stop() } else { model.start(family) } }
                    .buttonStyle(.borderedProminent).controlSize(.large)
                    .accessibilityIdentifier(model.running ? "stop-test" : "start-test")
                Spacer()
            }.padding(10).background(.bar)
        }
        .sheet(isPresented: $settings) { SettingsPage(model: model, family: family) }
    }
}
private struct ScoreCell: View {
    let item: ScoreItem
    var body: some View {
        VStack(alignment: .leading, spacing: 3) {
            Text(L10n.display(item.score.map { String(format: "%.2f", $0) } ?? "—"))
                .font(.title3.weight(.semibold)).monospacedDigit().lineLimit(1).minimumScaleFactor(0.55)
                .foregroundStyle(item.score == nil ? Color.secondary : .indigo).accessibilityIdentifier("value-" + item.id)
            Text(L10n.display(item.unit)).font(.caption2).foregroundStyle(.secondary)
            if item.id.hasPrefix("ram-"), let sample = item.measurements.last(where: { $0.status == 0 && $0.verified }) {
                Text(L10n.display("T\(sample.threads) · \(Statistics.size(sample.workingSetBytes))")).font(.caption2).foregroundStyle(.secondary)
            }
            if item.state == "unavailable" || item.state == "failed" { Text(L10n.display(item.reason ?? L10n.t("m_6707de42c29d"))).font(.caption2).lineLimit(2).foregroundStyle(.secondary) }
        }.frame(maxWidth: .infinity, alignment: .leading)
    }
}
private struct StorageBoard: View {
    let scores: [ScoreItem]
    var body: some View {
        VStack(spacing: 12) {
            HStack { Text(L10n.t("m_79f326be4409")).frame(maxWidth: .infinity, alignment: .leading); Text(L10n.t("m_534cb3fa8fbf")).frame(width: 85); Text(L10n.t("m_5c783c467965")).frame(width: 85) }.font(.caption)
            ForEach(Array(stride(from: 0, to: scores.count, by: 2)), id: \.self) { index in
                HStack(alignment: .top) {
                    Text(L10n.display(scores[index].title.components(separatedBy: " · ").first ?? scores[index].title))
                        .font(.subheadline).frame(maxWidth: .infinity, alignment: .leading)
                    ScoreCell(item: scores[index]).frame(width: 85)
                    if index + 1 < scores.count { ScoreCell(item: scores[index + 1]).frame(width: 85) }
                }; Divider()
            }
        }
    }
}
private struct RAMRow: View {
    let scores: [ScoreItem]
    var body: some View {
        HStack(alignment: .top, spacing: 8) {
            ForEach(scores) { item in VStack(alignment: .leading, spacing: 5) { Text(L10n.display(item.title)).font(.caption); ScoreCell(item: item) }.frame(maxWidth: .infinity, alignment: .leading) }
        }
    }
}
private struct CurveChart: View {
    let groups: [CurveGroup]
    let maximumMiB: Int
    var height: CGFloat = 260
    @State private var logarithmic = false
    @State private var selected = -1
    @State private var selectedBytes: Double?
    private var visible: [CurveGroup] { groups.filter { selected < 0 || $0.qos == selected } }
    private var singleSample: Bool { !groups.isEmpty && groups.allSatisfy { $0.singleSample == true } }
    private var axisValues: [Double] {
        let maximum = Double(maximumMiB) * 1048576.0
        let candidates: [Double] = [4096, 32768, 262144, 2097152, 16777216, maximum]
        return Array(Set(candidates)).sorted().filter { $0 <= maximum }
    }
    private func segments(_ group: CurveGroup) -> [[CurvePoint]] {
        let lookup = Dictionary(uniqueKeysWithValues: group.points.map { ($0.bytes, $0) })
        var result: [[CurvePoint]] = []; var current: [CurvePoint] = []
        for bytes in group.plannedSizes {
            if let point = lookup[bytes], point.stable { current.append(point) }
            else if !current.isEmpty { result.append(current); current = [] }
        }
        if !current.isEmpty { result.append(current) }; return result
    }
    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            controls
            plot
            samples
        }
    }
    private var controls: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                Text(L10n.t("m_204f61585875")).font(.headline); Spacer()
                Button(logarithmic ? L10n.t("m_c4c97d2bb07c") : L10n.t("m_dd01dd267ba5")) { logarithmic.toggle() }.font(.caption).accessibilityIdentifier("curve-scale")
            }
            if groups.count > 1 {
                Picker(L10n.t("m_aaca27fe170e"), selection: $selected) { Text(L10n.t("m_5c55a67935af")).tag(-1); ForEach(groups) { Text(L10n.display($0.title)).tag($0.qos) } }.pickerStyle(.segmented)
            }
        }
    }
    private var plot: some View {
        Chart {
            ForEach(visible) { group in groupMarks(group) }
            if let selectedBytes {
                RuleMark(x: .value(L10n.t("m_e0549b01e813"), selectedBytes)).foregroundStyle(.secondary).lineStyle(StrokeStyle(dash: [3,3]))
            }
        }
        .chartXScale(domain: 4096.0...Double(maximumMiB * 1048576), type: .log)
        .chartYScale(type: logarithmic ? .log : .linear)
        .chartForegroundStyleScale(domain: colorNames, range: colors)
        .chartXAxis { AxisMarks(values: axisValues) { value in
            AxisGridLine(); AxisValueLabel { axisLabel(value.as(Double.self)) }
        } }
        .chartYAxis { AxisMarks(position: .leading) }.chartYAxisLabel("ns")
        // 用 iOS 16 图表覆盖层处理拖动，所有系统共享同一选点路径。
        // Use the iOS 16 chart overlay for the same point-selection path on every supported OS.
        .chartOverlay { proxy in
            GeometryReader { geometry in
                Rectangle().fill(.clear).contentShape(Rectangle())
                    .simultaneousGesture(DragGesture(minimumDistance: 0).onChanged { value in
                        guard abs(value.translation.width) >= abs(value.translation.height) else { return }
                        let frame = geometry[proxy.plotAreaFrame]
                        let x = value.location.x - frame.minX
                        guard frame.width > 0, x >= 0, x <= frame.width else { return }
                        selectedBytes = proxy.value(atX: x, as: Double.self)
                    })
            }
        }
        .frame(height: height).accessibilityIdentifier("cache-chart")
    }
    private var colorNames: [String] { visible.isEmpty ? [L10n.t("m_168b218cb403")] : visible.map(\.title) }
    private var colors: [Color] { visible.isEmpty ? [.indigo] : visible.map { $0.qos == 0 ? .indigo : .teal } }
    @ChartContentBuilder private func groupMarks(_ group: CurveGroup) -> some ChartContent {
        ForEach(Array(segments(group).enumerated()), id: \.offset) { segment in
            ForEach(segment.element) { point in
                LineMark(x: .value(L10n.t("m_a9d80f95e62f"), Double(point.bytes)), y: .value(L10n.t("m_18045b8c40f1"), point.latencyNs), series: .value(L10n.t("m_295937f60619"), "\(group.qos)-\(segment.offset)"))
                    .foregroundStyle(by: .value(L10n.t("m_565d64601d4d"), group.title))
            }
        }
        ForEach(group.points) { point in
            RuleMark(x: .value(L10n.t("m_a9d80f95e62f"), Double(point.bytes)), yStart: .value("10%", point.lowNs), yEnd: .value("90%", point.highNs))
                .foregroundStyle(by: .value(L10n.t("m_565d64601d4d"), group.title)).opacity(0.25)
            PointMark(x: .value(L10n.t("m_a9d80f95e62f"), Double(point.bytes)), y: .value(L10n.t("m_18045b8c40f1"), point.latencyNs))
                .foregroundStyle(by: .value(L10n.t("m_565d64601d4d"), group.title)).symbolSize(point.stable ? 12 : 28).opacity(point.stable ? 1 : 0.4)
        }
    }
    private var samples: some View {
        VStack(alignment: .leading, spacing: 4) {
            if groups.flatMap(\.points).isEmpty { Text(L10n.t("m_1116a491a0db")).font(.caption).foregroundStyle(.secondary) }
            else {
                Text(L10n.display(singleSample ? L10n.t("m_36ca663b02e0") : L10n.t("m_326ae3825cdc"))).font(.caption2).foregroundStyle(.secondary)
                ForEach(visible) { group in
                    if let label = sampleLabel(group) { Text(L10n.display(label)).font(.caption).monospacedDigit().accessibilityIdentifier("curve-selected-\(group.qos)") }
                }
            }
        }
    }
    private func sampleLabel(_ group: CurveGroup) -> String? {
        guard let target = selectedBytes, target > 0 else { return nil }
        let nearest = group.points.min { abs(log(Double($0.bytes) / target)) < abs(log(Double($1.bytes) / target)) }
        guard let point = nearest else { return nil }
        return group.title + " · " + Statistics.size(point.bytes) + String(format: " · %.2f ns · ", point.latencyNs) + (group.singleSample == true ? L10n.t("m_91020453439d") : point.stable ? L10n.t("m_505efd1fe138") : L10n.t("m_d466585af71f"))
    }
    private func axisLabel(_ bytes: Double?) -> some View {
        let label = bytes.map { Statistics.size(UInt64($0)) } ?? ""
        return Text(L10n.display(label)).font(.caption2)
    }
}
struct ReportDetails: View {
    let report: BenchReport
    let store: ReportStore
    var body: some View {
        ScrollView { VStack(alignment: .leading, spacing: 18) {
            Text(L10n.display(report.statusTitle)).font(.headline).accessibilityIdentifier("report-status")
            Text(L10n.display(report.config.summary(report.family))).font(.subheadline).card()
            if report.family == .memory { RAMRow(scores: report.scores).card(); CurveChart(groups: report.curves, maximumMiB: report.config.cacheMaxMiB).card() }
            if report.family == .storage { StorageBoard(scores: report.scores).card() }
            ExportButton(report: report, store: store)
            Text(L10n.t("m_cf2ea55d770b", report.completedRounds, report.plannedRounds, report.curvePairs, report.plannedCurvePairs)).font(.subheadline)
            ForEach(report.curves) { group in
                VStack(alignment: .leading, spacing: 8) {
                    Text(L10n.display(group.title)).font(.headline); Text(L10n.display(group.summary)).font(.subheadline)
                    ForEach(group.regions) { region in Text("\(Statistics.size(region.lowerBytes))–\(Statistics.size(region.upperBytes)) · \(region.medianNs, specifier: "%.2f") ns").font(.caption) }
                    ForEach(group.transitions) { edge in Text(L10n.t("m_cf326d2cc536", Statistics.size(edge.lowerBytes), Statistics.size(edge.upperBytes), String(format: "%.2f", edge.beforeNs), String(format: "%.2f", edge.afterNs))).font(.caption) }
                }.card()
            }
            ForEach(report.scores) { item in
                VStack(alignment: .leading, spacing: 8) {
                    Text(L10n.display(item.title)).font(.headline); ScoreCell(item: item)
                    Text(L10n.t("m_85f9fed9c801", item.values.count, item.aggregationTitle)).font(.caption)
                    if let sample = item.measurements.last { Text(L10n.t("m_f54ac3450821", Statistics.size(sample.workingSetBytes), sample.threads)).font(.caption) }
                    if let sample = item.storageSamples?.last {
                        Text(L10n.t("m_c71bf6710caa", sample.queueDepth, sample.maxOutstandingPerThread, String(format: "%.2f", sample.meanOutstandingPerThread), String(format: "%.2f", Double(sample.flushNsSeparate) / 1e6))).font(.caption)
                        Text(L10n.t("m_4c74eca2037f", Statistics.size(sample.writtenBytesTotal))).font(.caption)
                        if let uncached = sample.preparationNoCacheHint {
                            Text(L10n.t("m_68504e20fa2d", uncached ? L10n.t("m_dfb802238b38") : L10n.t("m_f95ea7f4c063"))).font(.caption)
                        }
                        if let bytes = sample.completedBytes, let submitted = sample.submittedOperations {
                            Text(L10n.t("m_ad12e355b8d5", Statistics.size(bytes), submitted)).font(.caption)
                        }
                        if sample.resourceLimited { Text(L10n.t("m_3ee4ee42167b")).font(.caption).foregroundStyle(.orange) }
                    }
                    if let reason = item.reason { Text(L10n.display(reason)).font(.caption).foregroundStyle(.secondary) }
                }.card()
            }
            Text(L10n.t("m_b27845f8b95e")).font(.caption).foregroundStyle(.secondary)
            if report.device.simulator || report.config.functionalTest { Text(L10n.t("m_a3572a4edd11")).foregroundStyle(.orange) }
        }.padding().frame(maxWidth: 1000).frame(maxWidth: .infinity) }.navigationTitle(L10n.t("m_38ab936eb50d"))
    }
}
private struct ExportButton: View {
    let report: BenchReport
    let store: ReportStore
    @State private var url: URL?
    @State private var show = false
    @State private var error: String?
    var body: some View {
        VStack(alignment: .leading) {
            Button(L10n.t("m_fb48367b485c")) { do { url = try store.export(report); show = true } catch { self.error = error.localizedDescription } }
                .buttonStyle(.bordered).accessibilityIdentifier("export-json")
            if let error { Text(L10n.display(error)).foregroundStyle(.red).font(.caption) }
        }.sheet(isPresented: $show) { if let url { ShareSheet(url: url) } }
    }
}
private struct ShareSheet: UIViewControllerRepresentable {
    let url: URL
    func makeUIViewController(context: Context) -> UIActivityViewController { UIActivityViewController(activityItems: [url], applicationActivities: nil) }
    func updateUIViewController(_ uiViewController: UIActivityViewController, context: Context) {}
}
private struct SettingsPage: View {
    @ObservedObject var model: BenchModel
    let family: BenchFamily
    @Environment(\.dismiss) private var dismiss
    var body: some View {
        NavigationStack { Form {
            Section(L10n.t("m_f1de1e2621de")) {
                Button(L10n.t("m_3459a2bac987")) { reset(BenchConfig()) }.accessibilityIdentifier("preset-standard")
                Button(L10n.t("m_fa394156117e")) { reset(.quick) }.accessibilityIdentifier("preset-quick")
            }
            if family == .memory {
            Section("RAM") {
                sizePicker(L10n.t("m_525dd7890344"), value: $model.config.ramSettings.memoryMiB)
                sizePicker(L10n.t("m_e3e42c6a76f1"), value: $model.config.ramSettings.latencyMiB)
                Toggle(L10n.t("m_7ae4a3f57717"), isOn: $model.config.ramSettings.automaticThreads)
                if !model.config.ramSettings.automaticThreads { Picker(L10n.t("m_19575851c0a0"), selection: $model.config.ramSettings.threads) { ForEach(1...16, id: \.self) { Text(L10n.display("\($0)")).tag($0) } } }
                Stepper(L10n.t("m_1ada9237519e", model.config.ramSettings.repeats), value: $model.config.ramSettings.repeats, in: 1...10)
                Stepper(L10n.t("m_dfc2e3b4df73", model.config.ramSettings.latencyRepeats), value: $model.config.ramSettings.latencyRepeats, in: 1...10)
                timePicker(L10n.t("m_339768bf98bf"), value: $model.config.ramSettings.durationMs)
                timePicker(L10n.t("m_30b8dcf0174f"), value: $model.config.ramSettings.warmupMs, zero: true)
                timePicker(L10n.t("m_69f82395883c"), value: $model.config.ramSettings.intervalMs, zero: true)
                Text(L10n.t("m_648c3ecf7173")).font(.caption)
            }
            Section(L10n.t("m_96c4fc9e110e")) {
                Toggle(L10n.t("m_28927ecc2c64"), isOn: $model.config.includeCurve)
                Toggle(L10n.t("m_e908350fc3c9"), isOn: Binding(get: { model.config.singleCurveSample == true }, set: { model.config.singleCurveSample = $0 }))
                sizePicker(L10n.t("m_4c1ba2afa2fb"), value: $model.config.cacheMaxMiB)
                Picker(L10n.t("m_3b3cfb43fce5"), selection: $model.config.stepsPerOctave) { ForEach([1,2,4,8], id: \.self) { Text(L10n.display("\($0)")).tag($0) } }
                Toggle(L10n.t("m_88011c572780"), isOn: $model.config.backgroundCurve)
                Text(L10n.display(model.config.singleCurveSample == true ? L10n.t("m_c22d6262da9f") : L10n.t("m_f48870fee1c2"))).font(.caption)
                Text(L10n.t("m_64747fd5eb0e")).font(.caption)
            }
            } else if family == .storage {
                Section(L10n.t("m_f40894c9060f")) {
                    Picker(L10n.t("m_6c04ba2bd676"), selection: $model.config.storageSettings.fileMiB) {
                        ForEach(Array(Set([64,128,256,512,1024,2048,4096,model.config.storageSettings.fileMiB])).sorted(), id: \.self) { Text(L10n.display(Statistics.size(UInt64($0) * 1048576))).tag($0) }
                    }.accessibilityIdentifier("storage-file-size")
                    Stepper(L10n.t("m_de2af697b5c5", model.config.storageSettings.repeats), value: $model.config.storageSettings.repeats, in: 1...9)
                    timePicker(L10n.t("m_339768bf98bf"), value: $model.config.storageSettings.durationMs)
                    timePicker(L10n.t("m_30b8dcf0174f"), value: $model.config.storageSettings.warmupMs, zero: true)
                    timePicker(L10n.t("m_69f82395883c"), value: $model.config.storageSettings.intervalMs, zero: true)
                    Toggle(L10n.t("m_557fa6af954e"), isOn: $model.config.storageSettings.noCache)
                    Text(L10n.t("m_9a7e8847ffef")).font(.caption)
                }
                ForEach(model.config.storageSettings.cases.indices, id: \.self) { index in
                    Section(model.config.storageSettings.cases[index].title) {
                        Toggle(L10n.t("m_7cbcc421dc4c"), isOn: $model.config.storageSettings.cases[index].random)
                        Picker(L10n.t("m_0f7d7860cbd3"), selection: $model.config.storageSettings.cases[index].blockKiB) { ForEach([4,8,16,32,64,128,256,512,1024,2048,4096], id: \.self) { Text(L10n.display("\($0) KiB")).tag($0) } }
                        Picker(L10n.t("m_7076dbb68661"), selection: $model.config.storageSettings.cases[index].queueDepth) { ForEach([1,2,4,8,16,32,64], id: \.self) { Text(L10n.display("\($0)")).tag($0) } }
                        Picker(L10n.t("m_56e542017a0f"), selection: $model.config.storageSettings.cases[index].threads) { ForEach(1...16, id: \.self) { Text(L10n.display("\($0)")).tag($0) } }
                    }
                }
            } else {
            Section(L10n.t("m_ccbf7250afd3")) {
                sizePicker(L10n.t("m_5ad3c0f3c7f4"), value: $model.config.memoryMiB)
                Picker(L10n.t("m_5eaab4942637"), selection: $model.config.threads) { ForEach([1,2,4,8], id: \.self) { Text(L10n.display("\($0)")).tag($0) } }
                Stepper(L10n.t("m_de2af697b5c5", model.config.repeats), value: $model.config.repeats, in: 1...5)
                timePicker(L10n.t("m_339768bf98bf"), value: $model.config.durationMs)
            }
            }
        }.navigationTitle(L10n.t("m_b1f1ab375a35", family.title)).toolbar { Button(L10n.t("m_c0b3fbff51cc")) { dismiss() }.accessibilityIdentifier("settings-done") } }
    }
    private func sizePicker(_ title: String, value: Binding<Int>) -> some View {
        Picker(title, selection: value) { ForEach(Array(Set([16,32,64,128,256,value.wrappedValue])).sorted(), id: \.self) { Text(L10n.display("\($0) MiB")).tag($0) } }
    }
    private func timePicker(_ title: String, value: Binding<Int>, zero: Bool = false) -> some View {
        Picker(title, selection: value) { ForEach(Array(Set([100,500,1000,2000,3000,5000,value.wrappedValue] + (zero ? [0] : []))).sorted(), id: \.self) { Text(L10n.display("\($0) ms")).tag($0) } }
    }
    private func reset(_ value: BenchConfig) {
        switch family {
        case .memory: model.config.ram = value.ram; model.config.cacheMaxMiB = value.cacheMaxMiB; model.config.includeCurve = value.includeCurve
            model.config.backgroundCurve = value.backgroundCurve; model.config.stepsPerOctave = value.stepsPerOctave
            model.config.singleCurveSample = value.singleCurveSample
        case .storage: model.config.storage = value.storage
        case .compute: model.config.memoryMiB = value.memoryMiB; model.config.threads = value.threads; model.config.durationMs = value.durationMs; model.config.repeats = value.repeats
        }
    }
}
private struct DevicePage: View {
    let info: DeviceInfo
    var body: some View {
        Form {
            Section(L10n.t("m_423f51a28678")) {
                LabeledContent(L10n.t("m_322408c53bed"), value: info.displayName)
                LabeledContent(L10n.t("m_db68f1777c59"), value: info.machine); LabeledContent(L10n.t("m_5b50d7c4b595"), value: info.osVersion)
                LabeledContent(L10n.t("m_e4b60a443ba3"), value: "\(info.logicalCpuCount)"); LabeledContent(L10n.t("m_ddc00e6620a4"), value: Statistics.size(info.physicalMemoryBytes))
                LabeledContent(L10n.t("m_73e2ec6ef1c7"), value: "\(info.pageBytes) B"); LabeledContent(L10n.t("m_cc1fac4524fd"), value: L10n.t("m_2f7693ddd129"))
            }
            if let reference = info.modelReference {
                Section(L10n.t("m_cb15f77ff481")) {
                    LabeledContent(L10n.t("m_984636a8319e"), value: reference.soc)
                    Text(L10n.display(reference.coreSummary))
                    if reference.performanceCores.count > 1 { Text(L10n.t("m_164906c818fe")).font(.caption) }
                    Link(L10n.t("m_dc0f66986fc4"), destination: URL(string: reference.source)!)
                    Text(L10n.t("m_3c5b1b8219fd")).font(.caption)
                }
            }
            Section(L10n.t("m_65026b27e78f")) {
                if let levels = info.performanceLevels, !levels.isEmpty {
                    ForEach(levels) { level in
                        VStack(alignment: .leading, spacing: 5) {
                            Text(L10n.t("m_1157929ae376", level.name, level.count))
                            Text(L10n.display("L1D \(level.l1DataBytes.map(Statistics.size) ?? L10n.t("m_756762e293f2")) · L2 \(level.l2Bytes.map(Statistics.size) ?? L10n.t("m_756762e293f2"))")).font(.caption)
                            if let count = level.coresPerL2 { Text(L10n.t("m_14beda673893", count)).font(.caption) }
                        }
                    }
                } else { Text(L10n.t("m_8c4a7419f98c")) }
                Text(L10n.t("m_5728e4f0b119")).font(.caption)
            }
            Section(L10n.t("m_32d6c632664d")) {
                LabeledContent("L1", value: info.reportedL1Bytes.map(Statistics.size) ?? L10n.t("m_756762e293f2"))
                LabeledContent("L2", value: info.reportedL2Bytes.map(Statistics.size) ?? L10n.t("m_756762e293f2"))
                LabeledContent("L3", value: info.reportedL3Bytes.map(Statistics.size) ?? L10n.t("m_756762e293f2"))
                LabeledContent(L10n.t("m_4be6a47f4140"), value: info.reportedLineBytes.map { "\($0) B" } ?? L10n.t("m_756762e293f2"))
                Text(L10n.t("m_9c6ddc50afa0")).font(.caption)
            }
        }.navigationTitle(L10n.t("m_e1506406a5bd"))
    }
}
private extension View {
    func card() -> some View { padding(16).background(Color(uiColor: .secondarySystemGroupedBackground), in: RoundedRectangle(cornerRadius: 18)) }
}
