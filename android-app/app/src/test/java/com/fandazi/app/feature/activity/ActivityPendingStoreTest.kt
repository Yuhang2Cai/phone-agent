// 薄引用存储回归：模拟磁盘但直接测试生产策略，防止进程重建、换号和未知写覆盖回归。
package com.fandazi.app.feature.activity

import org.junit.Assert.*
import org.junit.Test

/** 固定公开 UUID 仅用于合成测试，不包含账户或凭据。 */
class ActivityPendingStoreTest {
    private val key = "20000000-0000-0000-0000-000000000001"
    private val payload = """{"id":"10000000-0000-0000-0000-000000000001","requestId":"$key"}"""

    /** 新存储对象模拟进程重建，原请求键应仍存在且没有令牌字段。 */
    @Test fun rebuildRetainsOriginalReference() {
        var disk: String? = null
        ActivityPendingStore({disk}, {disk=it;true}).save("owner-a", "claim", payload)
        val rebuilt = ActivityPendingStore({disk}, {disk=it;true})
        assertTrue(rebuilt.load("owner-a")?.contains(key) == true)
        assertFalse(disk?.contains("owner-a") == true)
        assertFalse(rebuilt.clear("owner-a", "other-key"))
        assertTrue(rebuilt.clear("owner-a", key))
        assertNull(rebuilt.load("owner-a"))
    }
    /** 新身份必须清除旧薄引用，磁盘失败必须阻止随后网络写入。 */
    @Test fun identityAndDiskFailureAreFailClosed() {
        var disk: String? = null
        val store = ActivityPendingStore({disk}, {disk=it;true})
        store.save("owner-a", "claim", payload)
        assertNull(store.load("owner-b"));assertNull(disk)
        assertThrows(IllegalStateException::class.java) {ActivityPendingStore({null}, {false}).save("owner-a", "claim", payload)}
    }
    /** 同键重试允许，其他请求不得覆盖尚未确认的请求引用。 */
    @Test fun unrelatedWriteCannotReplacePending() {
        var disk: String? = null
        val store = ActivityPendingStore({disk}, {disk=it;true})
        store.save("owner-a", "claim", payload);store.save("owner-a", "claim", payload)
        assertThrows(IllegalStateException::class.java) {store.save("owner-a", "claim", payload.replace(key, "30000000-0000-0000-0000-000000000001"))}
    }
}
