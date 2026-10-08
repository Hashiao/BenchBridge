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
    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                if DeviceInfo.collect().simulator { Label("模拟器仅验证功能，数值不代表手机性能", systemImage: "desktopcomputer").font(.caption).foregroundStyle(.orange).accessibilityIdentifier("simulator-warning") }
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
                        Text("应用沙盒文件 · Q1 T1").font(.headline)
                        ForEach(report?.scores ?? BenchWorker.scorePlan(.storage)) { item in
                            HStack { Text(item.title).frame(maxWidth: .infinity, alignment: .leading); ScoreCell(item: item) }
                            Divider()
                        }
                        Text("写入计时包含最终同步；系统缓存提示不等于直接测量裸闪存。").font(.caption).foregroundStyle(.secondary)
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
        .sheet(isPresented: $settings) { SettingsPage(model: model) }
    }
}
private struct ScoreCell: View {
    let item: ScoreItem
    var body: some View {
        VStack(alignment: .leading, spacing: 3) {
            Text(item.median.map { String(format: "%.2f", $0) } ?? "—")
                .font(.title3.weight(.semibold)).monospacedDigit().lineLimit(1).minimumScaleFactor(0.55)
                .foregroundStyle(item.median == nil ? Color.secondary : .indigo).accessibilityIdentifier("value-" + item.id)
            Text(item.unit).font(.caption2).foregroundStyle(.secondary)
            if item.state == "unavailable" || item.state == "failed" { Text(item.reason ?? "未完成").font(.caption2).lineLimit(2).foregroundStyle(.secondary) }
        }.frame(maxWidth: .infinity, alignment: .leading)
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
    private var visible: [CurveGroup] { groups.filter { selected < 0 || $0.qos == selected } }
    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack { Text("工作集大小—访问延迟").font(.headline); Spacer(); Button(logarithmic ? "对数 ns" : "线性 ns") { logarithmic.toggle() }.font(.caption).accessibilityIdentifier("curve-scale") }
            if groups.count > 1 { Picker("调度优先级", selection: $selected) { Text("全部").tag(-1); ForEach(groups) { Text($0.title).tag($0.qos) } }.pickerStyle(.segmented) }
            Chart {
                ForEach(visible) { group in ForEach(group.points) { point in
                    LineMark(x: .value("工作集", Double(point.bytes)), y: .value("延迟", point.latencyNs))
                        .foregroundStyle(by: .value("优先级", group.title))
                    PointMark(x: .value("工作集", Double(point.bytes)), y: .value("延迟", point.latencyNs))
                        .foregroundStyle(by: .value("优先级", group.title)).symbolSize(point.stable ? 12 : 28).opacity(point.stable ? 1 : 0.4)
                } }
            }
            .chartXScale(domain: 4096.0...Double(maximumMiB * 1048576), type: .log)
            .chartYScale(type: logarithmic ? .log : .linear)
            .chartForegroundStyleScale(domain: visible.isEmpty ? ["高优先级"] : visible.map(\.title),
                                       range: visible.isEmpty ? [Color.indigo] : visible.map { $0.qos == 0 ? Color.indigo : Color.teal })
            .chartXAxis { AxisMarks(values: [4096.0, 65536, 1048576, 16777216, 134217728].filter { $0 <= Double(maximumMiB * 1048576) }) { value in
                AxisGridLine(); AxisValueLabel { if let bytes = value.as(Double.self) { Text(Statistics.size(UInt64(bytes))).font(.caption2) } }
            } }.chartYAxis { AxisMarks(position: .leading) }.chartYAxisLabel("ns")
            .frame(height: height).accessibilityIdentifier("cache-chart")
            if groups.flatMap(\.points).isEmpty { Text("RAM 四项完成后开始扫描；图中仅显示实测值。").font(.caption).foregroundStyle(.secondary) }
            else { Text("浅色点未通过重复性验证；原始采样均保存在 JSON。").font(.caption2).foregroundStyle(.secondary) }
        }
    }
}
struct ReportDetails: View {
    let report: BenchReport
    let store: ReportStore
    var body: some View {
        ScrollView { VStack(alignment: .leading, spacing: 18) {
            Text(report.statusTitle).font(.headline).accessibilityIdentifier("report-status")
            if report.family == .memory { RAMRow(scores: report.scores).card(); CurveChart(groups: report.curves, maximumMiB: report.config.cacheMaxMiB).card() }
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
                    Text("\(item.values.count) 个通过校验的正式轮次 · 中位数").font(.caption)
                    if let sample = item.measurements.last { Text("实际工作集 \(Statistics.size(sample.workingSetBytes)) · \(sample.threads) 线程").font(.caption) }
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
    @Environment(\.dismiss) private var dismiss
    var body: some View {
        NavigationStack { Form {
            Section("预设") {
                Button("标准测试") { model.config = BenchConfig() }.accessibilityIdentifier("preset-standard")
                Button("快速测试") { model.config = .quick }.accessibilityIdentifier("preset-quick")
            }
            Section("RAM") {
                Picker("工作集", selection: $model.config.memoryMiB) { ForEach(Array(Set([16, 32, 64, 128, 256, model.config.memoryMiB])).sorted(), id: \.self) { Text("\($0) MiB").tag($0) } }
                Picker("带宽线程", selection: $model.config.threads) { ForEach([1, 2, 4, 8], id: \.self) { Text("\($0)").tag($0) } }
                Text("延迟固定单线程；RAM 四项先测，曲线随后扫描。").font(.caption)
                Toggle("扫描缓存曲线", isOn: $model.config.includeCurve)
                Picker("曲线最大工作集", selection: $model.config.cacheMaxMiB) { ForEach(Array(Set([16, 64, 128, 256, model.config.cacheMaxMiB])).sorted(), id: \.self) { Text("\($0) MiB").tag($0) } }
                Toggle("增加后台优先级曲线", isOn: $model.config.backgroundCurve)
                Text("这是调度优先级对照，不是固定大小核测试。").font(.caption)
            }
            Section("重复测量") {
                Stepper("每项 \(model.config.repeats) 次", value: $model.config.repeats, in: 1...5)
                Picker("每轮时长", selection: $model.config.durationMs) { ForEach(Array(Set([100, 500, 1000, 3000, model.config.durationMs])).sorted(), id: \.self) { Text("\($0) ms").tag($0) } }
                Picker("存储文件", selection: $model.config.storageMiB) { ForEach(Array(Set([16, 64, 128, 256, model.config.storageMiB])).sorted(), id: \.self) { Text("\($0) MiB").tag($0) } }
            }
        }.navigationTitle("测试设置").toolbar { Button("完成") { dismiss() }.accessibilityIdentifier("settings-done") } }
    }
}
private struct DevicePage: View {
    let info: DeviceInfo
    var body: some View {
        Form {
            Section("运行环境") {
                LabeledContent("设备标识", value: info.machine); LabeledContent("系统", value: info.osVersion)
                LabeledContent("逻辑核心", value: "\(info.logicalCpuCount)"); LabeledContent("物理内存", value: Statistics.size(info.physicalMemoryBytes))
                LabeledContent("系统页", value: "\(info.pageBytes) B"); LabeledContent("核心绑定", value: "不提供，使用系统调度")
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
