package com.example.xiangsugame.data

import java.util.Calendar

/** 日期工具:统一 yyyy-MM-dd(Calendar 实现,minSdk 24 无需 desugaring)。 */
object Dates {
    fun todayIso(): String {
        val c = Calendar.getInstance()
        return "%04d-%02d-%02d".format(
            c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH),
        )
    }
}
