// 个人资料模块：定义该模块的数据与行为。
package com.fandazi.app.feature.profile

import android.content.Context
import java.math.BigDecimal

class TaskAutomationPreference(context: Context, username: String) {
    private val preferences = context.getSharedPreferences("automation_ui_$username", 0)

    fun save(enabled: Boolean, maxAmount: BigDecimal) {
        preferences.edit()
            .putBoolean(KEY_ENABLED, enabled)
            .putString(KEY_LIMIT, maxAmount.stripTrailingZeros().toPlainString())
            .apply()
    }

    fun isEnabled(): Boolean = preferences.getBoolean(KEY_ENABLED, false)

    fun consumeLimit(): BigDecimal? {
        if (!isEnabled()) return null
        val limit = preferences.getString(KEY_LIMIT, null)?.toBigDecimalOrNull()?.takeIf { it > BigDecimal.ZERO }
        preferences.edit().putBoolean(KEY_ENABLED, false).apply()
        return limit
    }

    private companion object {
        const val KEY_ENABLED = "this_task_enabled"
        const val KEY_LIMIT = "this_task_limit"
    }
}
