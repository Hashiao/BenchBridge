import XCTest

final class BenchBridgeUITests: XCTestCase {
    func testAutomaticLanguagesAndEnglishFallback() {
        // 非中文首选语言即使带中文第二语言，也必须使用英文。
        // Non-Chinese primary languages must use English even with Chinese as a secondary preference.
        let cases = [
            ("(zh-Hans)", "zh_CN", "准备就绪", "开始测试", "RAM 设置", "zh-Hans"),
            ("(zh-Hant)", "zh_TW", "準備就緒", "開始測試", "RAM 設定", "zh-Hant"),
            ("(en)", "en_US", "Ready", "Start test", "RAM settings", "en"),
            ("(ja, zh-Hant)", "ja_JP", "Ready", "Start test", "RAM settings", "ja-fallback"),
            ("(ar, zh-Hans)", "ar_EG", "Ready", "Start test", "RAM settings", "ar-fallback")
        ]
        for (languages, locale, ready, start, settings, name) in cases {
            let app = XCUIApplication()
            app.launchArguments = ["--uitest", "--uitest-store=" + UUID().uuidString, "-AppleLanguages", languages, "-AppleLocale", locale]
            app.launch()
            // iPad 顶部标签可能隐藏导航标题；检查两端共同可见的状态与控件。
            // iPad top tabs can hide navigation titles; assert shared visible status and controls.
            XCTAssertTrue(app.staticTexts["run-progress"].waitForExistence(timeout: 15))
            XCTAssertEqual(app.staticTexts["run-progress"].label, ready)
            XCTAssertEqual(app.buttons["start-test"].label, start); XCTAssertTrue(app.buttons["start-test"].isHittable)
            screenshot("Locale " + name, app: app)
            app.buttons["settings"].tap()
            XCTAssertTrue(app.navigationBars[settings].waitForExistence(timeout: 10))
            XCTAssertTrue(app.buttons["settings-done"].isHittable)
            screenshot("Settings " + name, app: app)
            app.buttons["settings-done"].tap()
            app.terminate()
        }
    }
    private func app() -> XCUIApplication {
        let app = XCUIApplication(); app.launchArguments = ["--uitest", "--uitest-store=" + UUID().uuidString, "-AppleLanguages", "(zh-Hans)", "-AppleLocale", "zh_CN"]
        app.launch(); return app
    }
    private func screenshot(_ name: String, app: XCUIApplication) {
        let attachment = XCTAttachment(screenshot: app.screenshot()); attachment.name = name; attachment.lifetime = .keepAlways; add(attachment)
    }
    private func tab(_ title: String, app: XCUIApplication) {
        // iPadOS 18 的顶部标签不属于 TabBar；按可见标签定位。 / iPadOS 18 top tabs are not TabBar descendants; locate by visible label.
        let button = app.buttons.matching(NSPredicate(format: "label == %@", title)).firstMatch
        XCTAssertTrue(button.waitForExistence(timeout: 10)); button.tap()
    }
    func testRAMCurveHistoryAndExportEntry() {
        let app = app(); XCTAssertTrue(app.buttons["start-test"].waitForExistence(timeout: 15))
        app.buttons["start-test"].tap()
        XCTAssertTrue(app.buttons["stop-test"].waitForExistence(timeout: 10))
        XCTAssertTrue(app.buttons["start-test"].waitForExistence(timeout: 120))
        for kind in [0, 1, 5, 2] {
            let value = app.staticTexts["value-ram-\(kind)"]
            XCTAssertTrue(value.exists); XCTAssertNotEqual(value.label, "—")
        }
        screenshot("RAM and cache", app: app)
        app.swipeUp()
        let chart = app.descendants(matching: .any)["cache-chart"].firstMatch
        if !chart.isHittable { app.swipeUp() }
        XCTAssertTrue(chart.waitForExistence(timeout: 10))
        chart.coordinate(withNormalizedOffset: CGVector(dx: 0.4, dy: 0.5)).press(forDuration: 0.2,
            thenDragTo: chart.coordinate(withNormalizedOffset: CGVector(dx: 0.6, dy: 0.5)))
        XCTAssertTrue(app.staticTexts["curve-selected-0"].waitForExistence(timeout: 10))
        XCTAssertTrue(app.staticTexts["curve-selected-0"].isHittable)
        if !app.buttons["export-json"].isHittable { app.swipeUp() }
        XCTAssertTrue(app.buttons["export-json"].exists)
        tab("历史", app: app); XCTAssertTrue(app.collectionViews.firstMatch.exists || app.descendants(matching: .any)["history-list"].exists)
        screenshot("History", app: app)
    }
    func testStorageAndCancellation() {
        let app = app(); tab("ROM", app: app); app.buttons["start-test"].tap()
        XCTAssertTrue(app.buttons["export-json"].waitForExistence(timeout: 60))
        for test in ["seq1m-q8t1", "seq1m-q1t1", "rnd4k-q32t1", "rnd4k-q1t1"] {
            for direction in 0...1 {
                let value = app.staticTexts["value-\(test)-\(direction)"]
                XCTAssertTrue(value.exists); XCTAssertNotEqual(value.label, "—")
            }
        }
        screenshot("Storage", app: app)
        tab("RAM", app: app)
        // 使用标准时长验证取消，避免单次快测在点击前完成。 / Use standard timing so the single-sample quick run cannot finish before cancellation.
        app.buttons["settings"].tap()
        XCTAssertTrue(app.buttons["preset-standard"].waitForExistence(timeout: 10)); app.buttons["preset-standard"].tap()
        app.buttons["settings-done"].tap()
        app.buttons["start-test"].tap()
        XCTAssertTrue(app.buttons["stop-test"].waitForExistence(timeout: 10)); app.buttons["stop-test"].tap()
        XCTAssertTrue(app.buttons["start-test"].waitForExistence(timeout: 20))
        screenshot("Cancellation", app: app)
    }
}
