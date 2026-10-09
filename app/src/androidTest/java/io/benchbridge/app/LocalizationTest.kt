package io.benchbridge.app

import android.content.Context
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import io.benchbridge.app.i18n.L10n
import io.benchbridge.app.i18n.StringResources
import io.benchbridge.app.ram.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class LocalizationTest {
    @get:Rule val scenario = ActivityScenarioRule(MainActivity::class.java)
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

    @Test fun primaryLanguageScriptAndRegionRulesAreExplicit() {
        for(tag in listOf("zh", "zh-CN", "zh-SG", "zh-Hans", "zh-Hans-TW", "zh_Hans_HK"))
            assertEquals(tag, L10n.Language.HANS, L10n.languageFor(tag))
        for(tag in listOf("zh-TW", "zh-HK", "zh-MO", "zh-Hant", "zh-Hant-CN", "zh_Hant_SG"))
            assertEquals(tag, L10n.Language.HANT, L10n.languageFor(tag))
        for(tag in listOf("en", "en-TW", "ja-JP", "fr-FR", "ar-EG", "ko-KR", "zh-Latn", ""))
            assertEquals(tag, L10n.Language.EN, L10n.languageFor(tag))
    }

    @Test fun everyResourceExistsAndLegacyDescriptionsKeepNumbers() {
        for(language in L10n.Language.entries) for(key in StringResources.ids.keys) {
            val value=L10n.text(key,language,*Array<Any>(12){"ARG$it"})
            assertTrue(key,value.isNotEmpty());assertFalse(key,Regex("\\{\\d+\\}").containsMatchIn(value))
            if(language==L10n.Language.EN)assertFalse(key,Regex("[\\u3400-\\u9fff]").containsMatchIn(value))
        }
        assertEquals("快取與記憶體",L10n.text("m_df9062b60024",L10n.Language.HANT))
        assertEquals("CPU 執行緒",L10n.text("m_290505a7ff35",L10n.Language.HANT))
        assertEquals("循序讀取",L10n.text("m_6f02986c45d1",L10n.Language.HANT))
        assertEquals("Export JSON",L10n.display("导出 JSON",L10n.Language.EN))
        assertEquals("匯出 JSON",L10n.display("Export JSON",L10n.Language.HANT))
        assertEquals("CPU 6 · Up to 5.01 GHz",L10n.display("CPU 6 · 5.01 GHz 上限",L10n.Language.EN))
        assertEquals("Completed 12 / 12 runs",L10n.display("已完成 12 / 12 轮",L10n.Language.EN))
        assertEquals("112/113 valid samples; 4 contiguous regions and 2 sustained transitions. No boundaries are inferred across missing ranges.",L10n.display("112/113 个有效采样点；4 个连续区间，2 处持续转换。缺测范围不推断边界。",L10n.Language.EN))
        assertEquals("AFFINITY_VERIFY_FAILED",L10n.display("AFFINITY_VERIFY_FAILED",L10n.Language.HANT))
    }

    @Test fun actualSystemLanguageCoversNavigationSettingsResultsAndExport() = runBlocking {
        val expected=InstrumentationRegistry.getArguments().getString("expected_locale") ?: L10n.tag
        assertEquals(expected,L10n.tag)
        val title=L10n.t("m_df9062b60024")
        assertTrue(device.wait(Until.hasObject(By.text(title)),10000))
        val start=device.wait(Until.findObject(By.res("ram_start")),5000)
        val startLabel=device.wait(Until.findObject(By.text(L10n.t("m_69ed375721d9"))),5000)
        assertNotNull(startLabel);assertTrue(start.visibleBounds.contains(startLabel.visibleBounds))
        assertTrue(start.visibleBounds.bottom<device.displayHeight)
        device.findObject(By.res("ram_settings")).click()
        assertTrue(device.wait(Until.hasObject(By.text(L10n.t("m_dfb4855c2bb0"))),5000))
        device.takeScreenshot(File(context.cacheDir,"locale-$expected-settings.png"))
        device.findObject(By.res("settings_done")).click()
        lateinit var model:RamViewModel
        scenario.scenario.onActivity { model=ViewModelProvider(it)[RamViewModel::class.java] }
        val client=RunnerClient(context);val store=RunStore(context);var owned:String?=null
        try {
            withTimeout(15000){while(model.state.value.capabilities==null)delay(30)}
            val config=RamConfig.aida64().copy(warmupMs=100,durationMs=300,cooldownMs=0,calibrationMs=250,curveMaxMiB=1,curveSteps=2)
            scenario.scenario.onActivity { model.configure(config);model.start() }
            withTimeout(15000){while(model.state.value.report==null){check(model.state.value.error==null){model.state.value.error!!};delay(30)}}
            val id=model.state.value.report!!.getString("run_id");owned=id
            val report=withTimeout(180000){
                while(JSONObject(client.service().snapshot(id)).optString("state") !in RamResults.terminalStates)delay(100)
                store.read(id)!!
            }
            File(context.cacheDir,"locale-$expected-measurement.json").writeText(report.toString())
            assertTrue(report.optString("state")+": "+report.optString("error")+"; "+report.optJSONArray("cells"),report.optString("state") in listOf("COMPLETED","PARTIAL"))
            assertEquals(12,report.getInt("completed_rounds"))
            for(kind in MemoryPlanner.columns){
                assertEquals(3,RamResults.validRounds(report,kind).size)
                assertTrue(RamResults.validRounds(report,kind).all { it.getLong("working_set_bytes")==64L*1048576 && it.getInt("threads")==1 })
            }
            withTimeout(10000){while(model.state.value.running)delay(30)}
            for(kind in MemoryPlanner.columns)assertNotEquals("—",device.wait(Until.findObject(By.res("curve_ram_$kind")),5000).text)
            device.takeScreenshot(File(context.cacheDir,"locale-$expected-results.png"))
            val token=model.prepareExport(report);val pending=File(context.cacheDir,"pending_exports/$token.json")
            val exported=JSONObject(pending.readText())
            assertEquals(expected,exported.getString("export_locale"))
            assertEquals(report.getJSONArray("rounds").toString(),exported.getJSONArray("rounds").toString())
            if(expected=="en")assertFalse(exported.getString("localized_summary"),Regex("[\\u3400-\\u9fff]").containsMatchIn(exported.getString("localized_summary")))
            File(context.cacheDir,"locale-$expected-report.json").writeText(exported.toString())
            model.exportPrepared(null,token)
            device.findObject(By.res("result_details")).click()
            assertTrue(device.wait(Until.hasObject(By.text(L10n.t("m_38ab936eb50d"))),5000))
            device.findObject(By.res("settings_done")).click()
            device.wait(Until.findObject(By.res("tab_1")),5000).click()
            assertTrue(device.wait(Until.hasObject(By.text(L10n.t("m_7e67761835cd"))),5000))
            device.wait(Until.findObject(By.res("tab_4")),5000).click()
            assertTrue(device.wait(Until.hasObject(By.res("compute_page")),5000))
            device.wait(Until.findObject(By.res("tab_2")),5000).click()
            assertTrue(device.wait(Until.hasObject(By.text(L10n.t("m_4d0d5d853d24"))),5000))
            device.wait(Until.findObject(By.res("tab_3")),5000).click()
            assertTrue(device.wait(Until.hasObject(By.text(L10n.t("m_6c07bb6fd4b6"))),5000))
        } finally {
            owned?.let { client.service().cancel(it,"LOCALIZATION_TEST_CLEANUP");store.delete(it) };client.close()
        }
    }
}
