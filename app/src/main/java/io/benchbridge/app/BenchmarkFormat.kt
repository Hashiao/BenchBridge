package io.benchbridge.app

import java.math.BigDecimal

/** 配置、运行计划、历史与结果共用显示规则。 / Shared formatting for settings, plans, history and results. */
object BenchmarkFormat {
    private fun decimal(value: Long, scale: Int): String = BigDecimal.valueOf(value, scale).stripTrailingZeros().toPlainString()
    fun duration(ms: Int): String = "${decimal(ms.toLong(), 3)} 秒"
    fun mib(value: Int): String = if (value >= 1024 && value % 1024 == 0) "${value / 1024} GiB" else "$value MiB"
    fun kib(value: Int): String = if (value >= 1024 && value % 1024 == 0) "${value / 1024} MiB" else "$value KiB"
    fun bytes(value: Long): String = when {
        value >= 1073741824 && value % 1073741824 == 0L -> "${value / 1073741824} GiB"
        value >= 1048576 && value % 1048576 == 0L -> "${value / 1048576} MiB"
        value >= 1024 && value % 1024 == 0L -> "${value / 1024} KiB"
        else -> "$value B"
    }
}
