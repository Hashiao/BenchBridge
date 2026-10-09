import Foundation

/// 首选语言严格分流，保留地区回退与显式文字脚本。 / Resolve the primary language with explicit-script precedence.
enum L10n {
    enum Language: String, CaseIterable { case english = "en", simplified = "zh-Hans", traditional = "zh-Hant" }
    static func language(for tag: String) -> Language {
        let parts = tag.replacingOccurrences(of: "_", with: "-").split(separator: "-").map { $0.lowercased() }
        guard parts.first == "zh" else { return .english }
        if parts.contains("hans") { return .simplified }
        if parts.contains("hant") { return .traditional }
        if parts.dropFirst().contains(where: { $0.count == 4 }) { return .english }
        return parts.contains(where: { ["tw", "hk", "mo"].contains($0) }) ? .traditional : .simplified
    }
    static var current: Language { language(for: Locale.preferredLanguages.first ?? "en") }
    static var locale: Locale { Locale(identifier: current.rawValue) }
    private static let argument = try! NSRegularExpression(pattern: #"\{(\d+)\}"#)
    private static let bundles: [Language: Bundle] = Dictionary(uniqueKeysWithValues: Language.allCases.map { language in
        (language, Bundle.main.path(forResource: language.rawValue, ofType: "lproj").flatMap(Bundle.init(path:)) ?? Bundle.main)
    })
    private static func substitute(_ value: String, _ lookup: (Int) -> String) -> String {
        var result = value
        for match in argument.matches(in: value, range: NSRange(value.startIndex..., in: value)).reversed() {
            guard let number = Range(match.range(at: 1), in: value), let index = Int(value[number]), let range = Range(match.range, in: result) else { continue }
            result.replaceSubrange(range, with: lookup(index))
        }
        return result
    }
    static func t(_ key: String, _ values: Any...) -> String { text(key, language: current, values: values) }
    static func text(_ key: String, language: Language, values: [Any] = []) -> String {
        let value = bundles[language]!.localizedString(forKey: key, value: nil, table: "Localizable")
        return substitute(value) { $0 < values.count ? String(describing: values[$0]) : "null" }
    }

    private struct Matcher {
        let regex: NSRegularExpression
        let hint: String
        let order: [Int]
        let values: [String: String]
    }
    private static let entries: [[String: String]] = {
        guard let url = Bundle.main.url(forResource: "localization", withExtension: "json"),
              let data = try? Data(contentsOf: url), let catalog = try? JSONDecoder().decode([String: [String: String]].self, from: data) else { return [] }
        return catalog.keys.sorted().compactMap { catalog[$0] }
    }()
    private static let exact: [String: [String: String]] = {
        var result: [String: [String: String]] = [:]
        for entry in entries { for value in entry.values where value.range(of: #"\{\d+\}"#, options: .regularExpression) == nil {
            if result[value] == nil { result[value] = entry }
        } }
        return result
    }()
    private static let matchers: [Matcher] = entries.flatMap { entry in
        Set(entry.values).compactMap { value -> Matcher? in
            let matches = argument.matches(in: value, range: NSRange(value.startIndex..., in: value))
            guard !matches.isEmpty else { return nil }
            var pattern = "^"; var pieces: [String] = []; var order: [Int] = []; var end = value.startIndex
            for match in matches {
                guard let range = Range(match.range, in: value), let number = Range(match.range(at: 1), in: value) else { return nil }
                let fixed = String(value[end..<range.lowerBound]); pieces.append(fixed)
                pattern += NSRegularExpression.escapedPattern(for: fixed) + "(.*?)"; order.append(Int(value[number])!); end = range.upperBound
            }
            let fixed = String(value[end...]); pieces.append(fixed); pattern += NSRegularExpression.escapedPattern(for: fixed) + "$"
            guard let regex = try? NSRegularExpression(pattern: pattern, options: .dotMatchesLineSeparators) else { return nil }
            return Matcher(regex: regex, hint: pieces.max(by: { $0.count < $1.count }) ?? "", order: order, values: entry)
        }
    }.sorted { $0.hint.count > $1.hint.count }
    private static let lock = NSLock()
    private static var cache: [String: String] = [:]
    /// 只在显示时适配旧记录说明，原始测量与错误码不变。 / Localize legacy descriptions only for display; retain raw measurements and codes.
    static func display(_ value: String) -> String { display(value, language: current) }
    static func display(_ value: String, language: Language, depth: Int = 0) -> String {
        guard !value.isEmpty, depth <= 3 else { return value }
        if language == .english && value.range(of: #"[\u3400-\u9fff]"#, options: .regularExpression) == nil { return value }
        let key = language.rawValue + "\u{0}" + value
        lock.lock(); let cached = cache[key]; lock.unlock()
        if let cached { return cached }
        var result = exact[value]?[language.rawValue]
        if result == nil {
            for matcher in matchers where matcher.hint.count >= 2 && value.contains(matcher.hint) {
                guard let match = matcher.regex.firstMatch(in: value, range: NSRange(value.startIndex..., in: value)), match.range.length == value.utf16.count else { continue }
                var captured: [Int: String] = [:]
                for (index, position) in matcher.order.enumerated() {
                    if let range = Range(match.range(at: index + 1), in: value) { captured[position] = String(value[range]) }
                }
                result = substitute(matcher.values[language.rawValue] ?? value) { display(captured[$0] ?? "", language: language, depth: depth + 1) }
                break
            }
        }
        if result == nil && value.contains("\n") { result = value.components(separatedBy: "\n").map { display($0, language: language, depth: depth + 1) }.joined(separator: "\n") }
        let resolved = result ?? value
        lock.lock(); if cache.count >= 512 { cache.removeAll(keepingCapacity: true) }; cache[key] = resolved; lock.unlock()
        return resolved
    }
}
