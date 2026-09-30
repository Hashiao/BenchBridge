package io.benchbridge.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.benchbridge.app.hardware.CpuCache
import io.benchbridge.app.hardware.CpuCore
import io.benchbridge.app.hardware.CpuTopology
import io.benchbridge.app.hardware.SocCatalog
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SocCatalogTest {
    private val catalog = SocCatalog(ApplicationProvider.getApplicationContext<Context>())

    @Test fun mainstreamAndRequestedPlatformsHaveDistinctProfiles() {
        val expected = mapOf(
            "Qualcomm SM4250-AA" to "sm4250-aa", "SM6225-AD" to "sm6225-ad",
            "SM7635-AC" to "sm7635-ac", "MediaTek Helio G99" to "helio-g99",
            "天玑 6100+" to "dimensity-6100-plus", "Dimensity 1100" to "dimensity-1100",
            "Samsung Exynos 1280" to "exynos-1280", "UNISOC T612" to "unisoc-t612",
            "Kirin 820" to "kirin-820", "Google Tensor" to "google-tensor",
            "玄戒 O3" to "xring-o3", "8EE6" to "sm8975", "8E6" to "sm8950",
            "Qualcomm SM8975" to "sm8975", "Qualcomm SM8950" to "sm8950",
        )
        expected.forEach { (name, id) -> assertEquals(name, id, catalog.match(listOf(name))?.getString("id")) }
        val o3 = catalog.match(listOf("Xiaomi XRING O3"))!!
        assertEquals(10, o3.getInt("cpu_count"))
        assertEquals(16, o3.getJSONObject("gpu").getInt("shader_cores"))
        for (name in listOf("8EE6", "8E6")) {
            val soc = catalog.match(listOf(name))!!
            assertEquals(8, soc.getInt("cpu_count"))
            assertTrue(soc.isNull("cpu_l3"))
            assertTrue(soc.getJSONObject("gpu").isNull("alus"))
        }
    }

    @Test fun sharedCodesNeedConsistentNamesAndNeverMatchNumericPrefixes() {
        assertNull(catalog.match(listOf("SM6225")))
        assertEquals("sm6225", catalog.match(listOf("SM6225", "Snapdragon 680"))!!.getString("id"))
        assertEquals("sm6225-ad", catalog.match(listOf("SM6225", "Snapdragon 685"))!!.getString("id"))
        assertEquals("sm6225-ad", catalog.match(listOf("Qualcomm SM6225-AD", "SM6225"))!!.getString("id"))
        assertNull(catalog.match(listOf("SM6225-AD", "Snapdragon 680")))
        assertNull(catalog.match(listOf("SM8975", "8E6")))
        assertNull(catalog.match(listOf("SM89750", "Qualcomm")))
        assertNull(catalog.match(listOf("SM8975-UNKNOWN")))
        assertNull(catalog.match(listOf("Dimensity 7300 Turbo")))
        assertEquals(7, catalog.match(listOf("SM8750-3-AB"))!!.getInt("cpu_count"))
        assertNull(catalog.match(listOf("SM8750")))
    }

    @Test fun unconfirmedSpecificationsLeaveRuntimeCachesIntact() {
        val cores = (0..7).map { CpuCore(it, "cpu$it", 0, 0, "", "", true) }
        val runtime = CpuCache("L1:0", 1, 32768, 64, listOf(0), "runtime-sysfs")
        val unknown = catalog.match(listOf("Snapdragon 778G+"))!!
        // 用未确认的核心数验证入口，避免资料库补全时改变用例含义。
        // Keep the fixture's count unconfirmed even if later catalog revisions fill it in.
        unknown.put("cpu_count", JSONObject.NULL)
        val topology = CpuTopology.withCatalog(CpuTopology(cores, listOf(runtime), unknown, catalog.revision))
        assertEquals(listOf(runtime), topology.caches)
        assertTrue(topology.notes.contains("CATALOG_CORE_COUNT_UNCONFIRMED"))
        val ordinary = CpuTopology.withCatalog(topology.copy(soc = catalog.match(listOf("Helio G99")), notes = emptyList()))
        assertEquals(listOf(runtime), ordinary.caches)
    }

    @Test fun verifiedCacheGroupsPreserveRuntimeValuesAndSharedScope() {
        val soc = catalog.match(listOf("Dimensity 8500"))!!
        val cores = (0..7).map {
            CpuCore(it, "cpu$it", 0, if (it == 7) 3400000 else if (it >= 4) 3200000 else 2200000,
                "d87", "", true)
        }
        val actual = CpuCache("L2:7", 2, 2097152, 64, listOf(7), "runtime-sysfs")
        val topology = CpuTopology.withCatalog(CpuTopology(cores, listOf(actual), soc, catalog.revision))
        assertEquals(2097152L, topology.cache(7, 2)!!.bytes)
        assertEquals("runtime-sysfs", topology.cache(7, 2)!!.source)
        assertEquals(524288L, topology.cache(6, 2)!!.bytes)
        assertEquals(262144L, topology.cache(0, 2)!!.bytes)
        assertEquals(6291456L, topology.cache(0, 3)!!.bytes)
        assertEquals((0..7).toList(), topology.cache(0, 3)!!.cpus)
        assertNull(topology.cache(0, 1))
    }
}
