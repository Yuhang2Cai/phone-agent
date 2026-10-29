// 恢复文件真实实现回归：使用Android测试文件目录，核对原键条件清理与损坏数据的封闭行为。
package com.fandazi.app.feature.seckill

import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** 两个存储实例共用同一账号目录；验证实际AtomicFile，而非只依赖内存替身。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class SeckillRecoveryFileTest {
    /** 旧实例迟到的清理必须保留新原键；匹配键只清理本账号，不能删除另一账号。 */
    @Test fun staleRequestClearPreservesNewRecordAndOtherAccount()=runBlocking {
        val context=RuntimeEnvironment.getApplication()
        val first=SeckillRecoveryFile(context);val second=SeckillRecoveryFile(context)
        val account="a".repeat(64);val other="b".repeat(64)
        val old=SeckillRecovery("activity-a","old-key","old-order")
        val fresh=SeckillRecovery("activity-b","new-key")
        assertTrue(first.save(account,old));assertTrue(second.save(account,fresh));assertTrue(second.save(other,old))
        // 旧实例回填已知订单与旧清理一样，必须在真实文件上保留后来产生的未知原键。
        assertTrue(first.save(account,old,old.requestId));assertEquals(fresh,second.load(account))
        assertTrue(first.clear(account,old.requestId))
        assertEquals(fresh,second.load(account));assertEquals(old,second.load(other))
        assertTrue(second.clear(account,fresh.requestId));assertNull(first.load(account))
        assertEquals(old,first.load(other));assertTrue(second.clear(other,old.requestId))
    }
    /** 无法判断原键的损坏文件不能被清理调用顺手删除，否则会丢失待核查写入的线索。 */
    @Test fun corruptRecordClearFailsWithoutDeletingEvidence()=runBlocking {
        val context=RuntimeEnvironment.getApplication();val account="c".repeat(64)
        val directory=File(context.noBackupFilesDir,"seckill-recovery");directory.mkdirs()
        val file=File(directory,"$account.json");file.writeText("{invalid")
        try {
            assertFalse(SeckillRecoveryFile(context).clear(account,"unknown-key"))
            assertEquals("{invalid",file.readText())
        }finally {assertTrue(file.delete())}
    }
}
