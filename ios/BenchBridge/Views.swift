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
                        Text("\(report.family.title) · \(report.statusTitle)")
                        Text(report.startedAt.formatted(date: .abbreviated, time: .shortened)).font(.caption).foregroundStyle(.secondary)
                    }
                } }.navigationTitle("历史").onAppear { model.refresh() }.accessibilityIdentifier("history-list")
            }.tabItem { Label("历史", systemImage: "clock") }
            NavigationStack { DevicePage(info: DeviceInfo.collect()) }.tabItem { Label("设备", systemImage: "iphone.and.ipad") }
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
                if DeviceInfo.collect().simulator { Label("模拟器仅验证功能，数值不代表手机性能", systemImage: "desktopcomputer").font(.caption).foregroundStyle(.orange).accessibilityIdentifier("simulator-warning") }
                let device = report?.device ?? DeviceInfo.collect()
                Text(device.displayName + (device.modelReference.map { " · \($0.soc)" } ?? "")).font(.subheadline)
                Text(parameters.summary(family)).font(.caption).foregroundStyle(.secondary).accessibilityIdentifier("config-summary")
                Text(report?.progress ?? "准备就绪").font(.subheadline).foregroundStyle(.indigo).accessibilityIdentifier("run-progress")
                if let report {
                    Text("正式轮次 \(report.completedRounds)/\(report.plannedRounds)" + (report.curves.isEmpty ? "" : " · 曲线 \(report.curvePairs)/\(report.plannedCurvePairs) 点次"))
                        .font(.caption).foregroundStyle(.secondary)
                }
                if family == .memory {
                    VStack(alignment: .leading, spacing: 12) {
                        Text("RAM · 读取 / 写入 / 延迟 / 拷贝").font(.caption)
                        RAMRow(scores: report?.scores ?? BenchWorker.scorePlan(.memory))
                        Divider()
                        CurveChart(groups: report?.curves ?? [], maximumMiB: report?.config.cacheMaxMiB ?? model.config.cacheMaxMiB,
                                   height: sizeClass == .regular ? 340 : 200)
                    }.card()
                    Text("按任务优先级扫描，未固定到某颗 CPU；优先级曲线不能直接当作大小核曲线。").font(.caption).foregroundStyle(.secondary)
                } else if family == .storage {
                    VStack(alignment: .leading, spacing: 16) {
                        StorageBoard(scores: report?.scores ?? BenchWorker.scorePlan(.storage, config: model.config))
                        Text(parameters.storage == nil ? "旧协议：Q1T1，写入包含最终同步，中位数。" : "显示最佳完整轮次；最终同步另计。Q 为应用提交的未完成请求数，系统缓存提示不代表裸闪存性能。")
                            .font(.caption).foregroundStyle(.secondary)
                    }.card()
                } else {
                    VStack(spacing: 12) {
                        HStack { Text("项目").frame(maxWidth: .infinity, alignment: .leading); Text("CPU").frame(width: 90); Text("GPU").frame(width: 90) }.font(.caption)
                        ForEach(0..<12, id: \.self) { kind in
                            let plan = report?.scores ?? BenchWorker.scorePlan(.compute)
                            HStack {
                                Text(BenchWorker.computeNames[kind]).font(.subheadline).frame(maxWidth: .infinity, alignment: .leading)
                                ScoreCell(item: plan.first { $0.id == "cpu-\(kind)" }!).frame(width: 90)
                                ScoreCell(item: plan.first { $0.id == "gpu-\(kind)" }!).frame(width: 90)
                            }; Divider()
                        }
                        Text("GPU 主值使用 Metal 设备时间；完整等待耗时另存。未实现与设备能力限制均明确说明。").font(.caption).foregroundStyle(.secondary)
                    }.card()
                }
                if let error = report?.error ?? model.error { Text(error).font(.caption).foregroundStyle(.red) }
                if let report, report.state != "running" {
                    NavigationLink("查看详情", destination: ReportDetails(report: report, store: model.store)).accessibilityIdentifier("show-details")
                    ExportButton(report: report, store: model.store)
                }
                Text("BenchBridge \(Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "") · iPhone / iPad")
                    .font(.caption2).foregroundStyle(.secondary)
            }.padding().frame(maxWidth: 1000).frame(maxWidth: .infinity)
        }
        .navigationTitle(family == .memory ? "缓存与内存" : family == .storage ? "存储测试" : "计算测试")
        .navigationBarTitleDisplayMode(.inline)
        .background(Color(uiColor: .systemGroupedBackground))
        .toolbar { Button { settings = true } label: { Image(systemName: "gearshape") }.disabled(model.running).accessibilityIdentifier("settings") }
        .safeAreaInset(edge: .bottom) {
            HStack {
                Spacer()
                Button(model.running ? "停止测试" : "开始测试") { if model.running { model.stop() } else { model.start(family) } }
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
            Text(item.score.map { String(format: "%.2f", $0) } ?? "—")
                .font(.title3.weight(.semibold)).monospacedDigit().lineLimit(1).minimumScaleFactor(0.55)
                .foregroundStyle(item.score == nil ? Color.secondary : .indigo).accessibilityIdentifier("value-" + item.id)
            Text(item.unit).font(.caption2).foregroundStyle(.secondary)
            if item.id.hasPrefix("ram-"), let sample = item.measurements.last(where: { $0.status == 0 && $0.verified }) {
                Text("T\(sample.threads) · \(Statistics.size(sample.workingSetBytes))").font(.caption2).foregroundStyle(.secondary)
            }
            if item.state == "unavailable" || item.state == "failed" { Text(item.reason ?? "未完成").font(.caption2).lineLimit(2).foregroundStyle(.secondary) }
        }.frame(maxWidth: .infinity, alignment: .leading)
    }
}
private struct StorageBoard: View {
    let scores: [ScoreItem]
    var body: some View {
        VStack(spacing: 12) {
            HStack { Text("项目").frame(maxWidth: .infinity, alignment: .leading); Text("读取").frame(width: 85); Text("写入").frame(width: 85) }.font(.caption)
            ForEach(Array(stride(from: 0, to: scores.count, by: 2)), id: \.self) { index in
                HStack(alignment: .top) {
                    Text(scores[index].title.components(separatedBy: " · ").first ?? scores[index].title)
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
            ForEach(scores) { item in VStack(alignment: .leading, spacing: 5) { Text(item.title).font(.caption); ScoreCell(item: item) }.frame(maxWidth: .infinity, alignment: .leading) }
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
                Text("工作集大小—访问延迟").font(.headline); Spacer()
                Button(logarithmic ? "对数 ns" : "线性 ns") { logarithmic.toggle() }.font(.caption).accessibilityIdentifier("curve-scale")
            }
            if groups.count > 1 {
                Picker("调度优先级", selection: $selected) { Text("全部").tag(-1); ForEach(groups) { Text($0.title).tag($0.qos) } }.pickerStyle(.segmented)
            }
        }
    }
    private var plot: some View {
        Chart {
            ForEach(visible) { group in groupMarks(group) }
            if let selectedBytes {
                RuleMark(x: .value("选中工作集", selectedBytes)).foregroundStyle(.secondary).lineStyle(StrokeStyle(dash: [3,3]))
            }
        }
        .chartXScale(domain: 4096.0...Double(maximumMiB * 1048576), type: .log)
        .chartYScale(type: logarithmic ? .log : .linear)
        .chartForegroundStyleScale(domain: colorNames, range: colors)
        .chartXAxis { AxisMarks(values: axisValues) { value in
            AxisGridLine(); AxisValueLabel { axisLabel(value.as(Double.self)) }
        } }
        .chartYAxis { AxisMarks(position: .leading) }.chartYAxisLabel("ns")
        .chartXSelection(value: $selectedBytes)
        .frame(height: height).accessibilityIdentifier("cache-chart")
    }
    private var colorNames: [String] { visible.isEmpty ? ["高优先级"] : visible.map(\.title) }
    private var colors: [Color] { visible.isEmpty ? [.indigo] : visible.map { $0.qos == 0 ? .indigo : .teal } }
    @ChartContentBuilder private func groupMarks(_ group: CurveGroup) -> some ChartContent {
        ForEach(Array(segments(group).enumerated()), id: \.offset) { segment in
            ForEach(segment.element) { point in
                LineMark(x: .value("工作集", Double(point.bytes)), y: .value("延迟", point.latencyNs), series: .value("连续区间", "\(group.qos)-\(segment.offset)"))
                    .foregroundStyle(by: .value("优先级", group.title))
            }
        }
        ForEach(group.points) { point in
            RuleMark(x: .value("工作集", Double(point.bytes)), yStart: .value("10%", point.lowNs), yEnd: .value("90%", point.highNs))
                .foregroundStyle(by: .value("优先级", group.title)).opacity(0.25)
            PointMark(x: .value("工作集", Double(point.bytes)), y: .value("延迟", point.latencyNs))
                .foregroundStyle(by: .value("优先级", group.title)).symbolSize(point.stable ? 12 : 28).opacity(point.stable ? 1 : 0.4)
        }
    }
    private var samples: some View {
        VStack(alignment: .leading, spacing: 4) {
            if groups.flatMap(\.points).isEmpty { Text("RAM 四项完成后开始扫描；图中仅显示实测值。").font(.caption).foregroundStyle(.secondary) }
            else {
                Text("按住曲线查看采样；浅色点未通过重复性验证，不跨缺口连线；误差线为 10–90% 分位。").font(.caption2).foregroundStyle(.secondary)
                ForEach(visible) { group in
                    if let label = sampleLabel(group) { Text(label).font(.caption).monospacedDigit() }
                }
            }
        }
    }
    private func sampleLabel(_ group: CurveGroup) -> String? {
        guard let target = selectedBytes, target > 0 else { return nil }
        let nearest = group.points.min { abs(log(Double($0.bytes) / target)) < abs(log(Double($1.bytes) / target)) }
        guard let point = nearest else { return nil }
        return group.title + " · " + Statistics.size(point.bytes) + String(format: " · %.2f ns · ", point.latencyNs) + (point.stable ? "通过验证" : "未通过验证")
    }
    private func axisLabel(_ bytes: Double?) -> some View {
        let label = bytes.map { Statistics.size(UInt64($0)) } ?? ""
        return Text(label).font(.caption2)
    }
}
struct ReportDetails: View {
    let report: BenchReport
    let store: ReportStore
    var body: some View {
        ScrollView { VStack(alignment: .leading, spacing: 18) {
            Text(report.statusTitle).font(.headline).accessibilityIdentifier("report-status")
            Text(report.config.summary(report.family)).font(.subheadline).card()
            if report.family == .memory { RAMRow(scores: report.scores).card(); CurveChart(groups: report.curves, maximumMiB: report.config.cacheMaxMiB).card() }
            if report.family == .storage { StorageBoard(scores: report.scores).card() }
            ExportButton(report: report, store: store)
            Text("正式轮次 \(report.completedRounds)/\(report.plannedRounds) · 曲线 \(report.curvePairs)/\(report.plannedCurvePairs) 点次").font(.subheadline)
            ForEach(report.curves) { group in
                VStack(alignment: .leading, spacing: 8) {
                    Text(group.title).font(.headline); Text(group.summary).font(.subheadline)
                    ForEach(group.regions) { region in Text("\(Statistics.size(region.lowerBytes))–\(Statistics.size(region.upperBytes)) · \(region.medianNs, specifier: "%.2f") ns").font(.caption) }
                    ForEach(group.transitions) { edge in Text("转换：\(Statistics.size(edge.lowerBytes))–\(Statistics.size(edge.upperBytes)) · \(edge.beforeNs, specifier: "%.2f") → \(edge.afterNs, specifier: "%.2f") ns").font(.caption) }
                }.card()
            }
            ForEach(report.scores) { item in
                VStack(alignment: .leading, spacing: 8) {
                    Text(item.title).font(.headline); ScoreCell(item: item)
                    Text("\(item.values.count) 个通过校验的正式轮次 · \(item.aggregationTitle)").font(.caption)
                    if let sample = item.measurements.last { Text("实际工作集 \(Statistics.size(sample.workingSetBytes)) · \(sample.threads) 线程").font(.caption) }
                    if let sample = item.storageSamples?.last {
                        Text("Q\(sample.queueDepth) · 实测每线程最大在途 \(sample.maxOutstandingPerThread)，平均 \(sample.meanOutstandingPerThread, specifier: "%.2f") · 同步 \(Double(sample.flushNsSeparate) / 1e6, specifier: "%.2f") ms（另计）").font(.caption)
                        Text("累计写入（含初始化与预热）\(Statistics.size(sample.writtenBytesTotal))").font(.caption)
                        if sample.resourceLimited { Text("系统限制了异步请求资源；以实测在途深度为准。").font(.caption).foregroundStyle(.orange) }
                    }
                    if let reason = item.reason { Text(reason).font(.caption).foregroundStyle(.secondary) }
                }.card()
            }
            Text("本版未固定核心、未锁定 CPU 频率。缓存曲线包含系统调度、地址转换和预取的影响，不把拐点直接命名为 L1/L2/L3 容量。Metal 主计时为设备执行时间，CPU 和完整等待时间不混入 GPU 主值。不同平台与协议的数值不可直接视为等价。").font(.caption).foregroundStyle(.secondary)
            if report.device.simulator || report.config.functionalTest { Text("此记录仅供功能验证，不能代表 iPhone / iPad 性能。").foregroundStyle(.orange) }
        }.padding().frame(maxWidth: 1000).frame(maxWidth: .infinity) }.navigationTitle("测试详情")
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
            Button("导出 JSON") { do { url = try store.export(report); show = true } catch { self.error = error.localizedDescription } }
                .buttonStyle(.bordered).accessibilityIdentifier("export-json")
            if let error { Text(error).foregroundStyle(.red).font(.caption) }
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
            Section("预设") {
                Button("标准测试") { reset(BenchConfig()) }.accessibilityIdentifier("preset-standard")
                Button("快速测试") { reset(.quick) }.accessibilityIdentifier("preset-quick")
            }
            if family == .memory {
            Section("RAM") {
                sizePicker("带宽 · 总工作集", value: $model.config.ramSettings.memoryMiB)
                sizePicker("延迟 · 总工作集", value: $model.config.ramSettings.latencyMiB)
                Toggle("带宽线程自动校准", isOn: $model.config.ramSettings.automaticThreads)
                if !model.config.ramSettings.automaticThreads { Picker("带宽线程", selection: $model.config.ramSettings.threads) { ForEach(1...16, id: \.self) { Text("\($0)").tag($0) } } }
                Stepper("带宽 · 每项 \(model.config.ramSettings.repeats) 次", value: $model.config.ramSettings.repeats, in: 1...10)
                Stepper("延迟 · \(model.config.ramSettings.latencyRepeats) 次", value: $model.config.ramSettings.latencyRepeats, in: 1...10)
                timePicker("每轮测量时间", value: $model.config.ramSettings.durationMs)
                timePicker("预热时间", value: $model.config.ramSettings.warmupMs, zero: true)
                timePicker("轮间休息", value: $model.config.ramSettings.intervalMs, zero: true)
                Text("延迟固定单线程；RAM 四项先测，曲线随后扫描。").font(.caption)
            }
            Section("块大小—延迟曲线") {
                Toggle("扫描缓存曲线", isOn: $model.config.includeCurve)
                sizePicker("曲线最大工作集", value: $model.config.cacheMaxMiB)
                Picker("每倍容量采样间隔", selection: $model.config.stepsPerOctave) { ForEach([1,2,4,8], id: \.self) { Text("\($0)").tag($0) } }
                Toggle("增加后台优先级曲线", isOn: $model.config.backgroundCurve)
                Text("从 4 KiB 起，单线程正反扫描、自动复核。两条优先级曲线用于观察调度差异，无法保证分别覆盖两个物理核心簇。").font(.caption)
            }
            } else if family == .storage {
                Section("ROM · DiskMark 默认配置") {
                    Picker("测试文件", selection: $model.config.storageSettings.fileMiB) {
                        ForEach(Array(Set([64,128,256,512,1024,2048,4096,model.config.storageSettings.fileMiB])).sorted(), id: \.self) { Text(Statistics.size(UInt64($0) * 1048576)).tag($0) }
                    }.accessibilityIdentifier("storage-file-size")
                    Stepper("每项 \(model.config.storageSettings.repeats) 次", value: $model.config.storageSettings.repeats, in: 1...9)
                    timePicker("每轮测量时间", value: $model.config.storageSettings.durationMs)
                    timePicker("预热时间", value: $model.config.storageSettings.warmupMs, zero: true)
                    timePicker("轮间休息", value: $model.config.storageSettings.intervalMs, zero: true)
                    Toggle("请求绕过系统数据缓存", isOn: $model.config.storageSettings.noCache)
                    Text("文件在本次测试开始时初始化一次；预热、校验和最终同步不计入吞吐主值。").font(.caption)
                }
                ForEach(model.config.storageSettings.cases.indices, id: \.self) { index in
                    Section(model.config.storageSettings.cases[index].title) {
                        Toggle("随机访问", isOn: $model.config.storageSettings.cases[index].random)
                        Picker("块大小", selection: $model.config.storageSettings.cases[index].blockKiB) { ForEach([4,8,16,32,64,128,256,512,1024,2048,4096], id: \.self) { Text("\($0) KiB").tag($0) } }
                        Picker("队列深度 Q", selection: $model.config.storageSettings.cases[index].queueDepth) { ForEach([1,2,4,8,16,32,64], id: \.self) { Text("\($0)").tag($0) } }
                        Picker("线程数 T", selection: $model.config.storageSettings.cases[index].threads) { ForEach(1...16, id: \.self) { Text("\($0)").tag($0) } }
                    }
                }
            } else {
            Section("GPGPU · 重复测量") {
                sizePicker("内存工作集", value: $model.config.memoryMiB)
                Picker("内存线程", selection: $model.config.threads) { ForEach([1,2,4,8], id: \.self) { Text("\($0)").tag($0) } }
                Stepper("每项 \(model.config.repeats) 次", value: $model.config.repeats, in: 1...5)
                timePicker("每轮测量时间", value: $model.config.durationMs)
            }
            }
        }.navigationTitle("\(family.title) 设置").toolbar { Button("完成") { dismiss() }.accessibilityIdentifier("settings-done") } }
    }
    private func sizePicker(_ title: String, value: Binding<Int>) -> some View {
        Picker(title, selection: value) { ForEach(Array(Set([16,32,64,128,256,value.wrappedValue])).sorted(), id: \.self) { Text("\($0) MiB").tag($0) } }
    }
    private func timePicker(_ title: String, value: Binding<Int>, zero: Bool = false) -> some View {
        Picker(title, selection: value) { ForEach(Array(Set([100,500,1000,2000,3000,5000,value.wrappedValue] + (zero ? [0] : []))).sorted(), id: \.self) { Text("\($0) ms").tag($0) } }
    }
    private func reset(_ value: BenchConfig) {
        switch family {
        case .memory: model.config.ram = value.ram; model.config.cacheMaxMiB = value.cacheMaxMiB; model.config.includeCurve = value.includeCurve
            model.config.backgroundCurve = value.backgroundCurve; model.config.stepsPerOctave = value.stepsPerOctave
        case .storage: model.config.storage = value.storage
        case .compute: model.config.memoryMiB = value.memoryMiB; model.config.threads = value.threads; model.config.durationMs = value.durationMs; model.config.repeats = value.repeats
        }
    }
}
private struct DevicePage: View {
    let info: DeviceInfo
    var body: some View {
        Form {
            Section("运行环境") {
                LabeledContent("型号", value: info.displayName)
                LabeledContent("设备标识", value: info.machine); LabeledContent("系统", value: info.osVersion)
                LabeledContent("逻辑核心", value: "\(info.logicalCpuCount)"); LabeledContent("物理内存", value: Statistics.size(info.physicalMemoryBytes))
                LabeledContent("系统页", value: "\(info.pageBytes) B"); LabeledContent("核心绑定", value: "不提供，使用系统调度")
            }
            if let reference = info.modelReference {
                Section("SoC 型号资料") {
                    LabeledContent("芯片", value: reference.soc)
                    Text(reference.coreSummary)
                    if reference.performanceCores.count > 1 { Text("此型号有不同核心数量配置，以本机运行时报告为准。").font(.caption) }
                    Link("Apple 官方规格", destination: URL(string: reference.source)!)
                    Text("型号资料用于说明硬件；不会填入实测成绩或推断当前线程所在核心。").font(.caption)
                }
            }
            Section("运行时核心组") {
                if let levels = info.performanceLevels, !levels.isEmpty {
                    ForEach(levels) { level in
                        VStack(alignment: .leading, spacing: 5) {
                            Text("\(level.name) · \(level.count) 核")
                            Text("L1D \(level.l1DataBytes.map(Statistics.size) ?? "未提供") · L2 \(level.l2Bytes.map(Statistics.size) ?? "未提供")").font(.caption)
                            if let count = level.coresPerL2 { Text("每个 L2 共享域 \(count) 核").font(.caption) }
                        }
                    }
                } else { Text("系统未提供核心组信息") }
                Text("核心组来自系统 sysctl；曲线中的高/后台优先级表示调度请求，不能等同于指定核心组。").font(.caption)
            }
            Section("系统报告的缓存信息") {
                LabeledContent("L1", value: info.reportedL1Bytes.map(Statistics.size) ?? "未提供")
                LabeledContent("L2", value: info.reportedL2Bytes.map(Statistics.size) ?? "未提供")
                LabeledContent("L3", value: info.reportedL3Bytes.map(Statistics.size) ?? "未提供")
                LabeledContent("缓存行粒度", value: info.reportedLineBytes.map { "\($0) B" } ?? "未提供")
                Text("系统未提供的规格保持未知；这些信息不等于曲线已经证明的容量。").font(.caption)
            }
        }.navigationTitle("设备")
    }
}
private extension View {
    func card() -> some View { padding(16).background(Color(uiColor: .secondarySystemGroupedBackground), in: RoundedRectangle(cornerRadius: 18)) }
}
