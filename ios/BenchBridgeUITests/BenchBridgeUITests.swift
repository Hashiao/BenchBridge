import XCTest

final class BenchBridgeUITests: XCTestCase {
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
        if !app.buttons["export-json"].isHittable { app.swipeUp() }
        XCTAssertTrue(app.buttons["export-json"].exists)
        tab("历史", app: app); XCTAssertTrue(app.collectionViews.firstMatch.exists || app.descendants(matching: .any)["history-list"].exists)
        screenshot("History", app: app)
    }
    func testStorageAndCancellation() {
        let app = app(); tab("ROM", app: app); app.buttons["start-test"].tap()
        XCTAssertTrue(app.buttons["export-json"].waitForExistence(timeout: 60))
        for id in ["seq-1048576-0", "seq-1048576-1", "random-4096-0", "random-4096-1"] {
            XCTAssertNotEqual(app.staticTexts["value-\(id)"].label, "—")
        }
        screenshot("Storage", app: app)
        tab("RAM", app: app); app.buttons["start-test"].tap()
        XCTAssertTrue(app.buttons["stop-test"].waitForExistence(timeout: 10)); app.buttons["stop-test"].tap()
        XCTAssertTrue(app.buttons["start-test"].waitForExistence(timeout: 20))
        screenshot("Cancellation", app: app)
    }
}
