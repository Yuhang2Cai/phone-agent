// 秒杀流程定向回归：验证归零只读、重复确认、落盘失败与身份/进程恢复边界。
package com.fandazi.app.feature.seckill

import com.fandazi.app.core.network.*
import com.fandazi.app.feature.deal.DealPackage
import com.fandazi.app.feature.purchase.*
import java.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/** 合成仓库只控制网络时序；断言针对流程实际发出的操作及可购事实。 */
class SeckillFlowTest {
    /** 已核实支付成功后重新进入同一活动，应重新读资格；不能永久被旧成功页面劫持。 */
    @Test fun paidOrderDoesNotBlockNextExplicitPurchase()=runTest {
        val server=Server();val memory=Memory();val flow=SeckillFlow(server,memory,{elapsed},{"key"})
        flow.open("activity","a","session");flow.prepare(1);flow.confirm();flow.pay()
        assertEquals("PAID",flow.state.value.order?.value?.status)
        val reopened=SeckillFlow(server,memory,{elapsed},{"next"});reopened.open("activity","a","session")
        assertNull(reopened.state.value.order);assertTrue(reopened.state.value.eligibility?.canPurchase==true)
        assertEquals(1,server.creates)
    }
    /** 已知订单可从订单列表找回，不能污染用户明确打开的另一张活动券；未知请求仍必须优先核查。 */
    @Test fun knownOrderDoesNotReplaceAnotherActivity()=runTest {
        val server=Server();val memory=Memory();memory.records["a"]=SeckillRecovery("old-activity","old-key","old-order")
        val flow=SeckillFlow(server,memory,{elapsed},{"new"});flow.open("activity","a","session")
        assertEquals("activity",flow.state.value.activity?.id);assertNull(flow.state.value.order)
        assertTrue(flow.state.value.eligibility?.canPurchase==true);assertEquals(0,server.creates)
    }
    /** 切券不能绕过未知写入核查；只有已确定订单身份才允许独立浏览其他活动。 */
    @Test fun unresolvedRequestStillBlocksOtherActivityWrites()=runTest {
        val server=Server();val memory=Memory();memory.records["a"]=SeckillRecovery("activity","original-key")
        val flow=SeckillFlow(server,memory,{elapsed},{"replacement"});flow.open("another-activity","a","session")
        flow.prepare(1);flow.confirm()
        assertTrue(flow.state.value.unknown);assertEquals(1,server.originals);assertEquals(0,server.creates)
        assertEquals("original-key",memory.records["a"]?.requestId)
    }
    private val now=Instant.parse("2026-10-29T04:00:00Z")
    private var elapsed=1000L
    private val deal=DealPackage("deal","store","测试券","测试描述",100,200,10,"2026-10-30","2026-11-01",true,1)
    private val activity=SeckillActivity("activity",1,"LIVE",now,now.minusSeconds(1),now.plusSeconds(60),10,10,1,deal)
    private val quote=SeckillQuote(PurchaseQuote("quote","deal",1,100,0,100,now.plusSeconds(60).toString(),"grant",1,"测试券","测试店","规则",1,1,null,null),"activity",1,now)
    private val order=SeckillOrder(GroupBuyOrder("order","deal","store",1,100,"PENDING_PAYMENT",0,"测试券","测试店"),"activity",now.plusSeconds(300),now,null,null)
    /** 恢复记录模拟原子落盘，分别记录账号，允许制造明确的存储失败。 */
    private class Memory:SeckillRecoveryStore {
        val records=mutableMapOf<String,SeckillRecovery>();var writable=true;var corrupt=false
        // 暂停清理回调，精确制造磁盘等待期间切换账号；成功和失败都不能回写新身份页面。
        var clearGate:CompletableDeferred<Unit>?=null;var clearStarted=false;var clearResult=true
        // 仅阻塞已知订单身份回填，模拟旧查询的磁盘写入晚于另一页面的新原请求落盘。
        var knownSaveGate:CompletableDeferred<Unit>?=null;var knownSaveStarted=false
        override suspend fun load(account:String):SeckillRecovery? {check(!corrupt);return records[account]}
        override suspend fun save(account:String,value:SeckillRecovery,expectedRequestId:String?):Boolean {
            if(!writable)return false
            if(value.orderId!=null){knownSaveStarted=true;knownSaveGate?.await()}
            if(expectedRequestId!=null && records[account]?.requestId!=expectedRequestId)return true
            records[account]=value;return true
        }
        override suspend fun clear(account:String,requestId:String):Boolean {
            clearStarted=true;clearGate?.await()
            if(!clearResult)return false
            if(records[account]?.requestId==requestId)records.remove(account)
            return true
        }
    }
    /** 合成服务不会自行重试，保存每一种调用次数以排除隐式写入。 */
    private inner class Server:SeckillRepository {
        var current=activity;var creates=0;var pays=0;var reads=0;var originals=0
        var createFailure=false;var originalFound=false;var gate:CompletableDeferred<Unit>?=null
        // 稳定业务拒绝会进入恢复记录清理，与传输未知分支分开验证。
        var createRejection:ApiError?=null
        // 订单列表可打开历史终态，与该账号另一笔尚未完成的恢复记录并存。
        var readOrder=order
        fun <T> ok(value:T)=ApiResult.Success(SeckillTimed(value,elapsed,elapsed))
        override suspend fun list(storeId:String)=ok(listOf(current))
        override suspend fun detail(activityId:String)=ok(current)
        override suspend fun eligibility(activityId:String):ApiResult<SeckillTimed<SeckillEligibility>> {reads++;return ok(SeckillEligibility(current.id,current.version,current.state,current.serverTime,current.startsAt,current.endsAt,10,1,current.state=="LIVE",if(current.state=="LIVE")"AVAILABLE" else "NOT_STARTED"))}
        override suspend fun quote(dealId:String,quantity:Int,sessionId:String)=ok(quote)
        override suspend fun create(quote:SeckillQuote,sessionId:String,requestId:String):ApiResult<SeckillTimed<SeckillOrder>> {creates++;gate?.await();return createRejection?.let {ApiResult.Failure(it)} ?: if(createFailure)ApiResult.Failure(ApiError.Timeout) else ok(order)}
        override suspend fun order(orderId:String)=ok(readOrder)
        override suspend fun pay(orderId:String,requestId:String):ApiResult<SeckillTimed<SeckillOrder>> {pays++;return ok(order.copy(value=order.value.copy(status="PAID")))}
        override suspend fun original(requestId:String):ApiResult<SeckillTimed<SeckillOrder?>> {originals++;return ok(if(originalFound)order else null)}
    }
    /** 原请求在超时后只能查询，明确UNKNOWN也不能生成第二笔写入。 */
    @Test fun lostCreateResponseNeverResubmits()=runTest {
        val server=Server();server.createFailure=true;val memory=Memory();val flow=SeckillFlow(server,memory,{elapsed},{"key"})
        flow.open("activity","a","session");flow.prepare(1);flow.confirm()
        assertTrue(flow.state.value.unknown);assertEquals("key",memory.records["a"]?.requestId)
        flow.confirm();flow.refresh();flow.confirm()
        assertEquals(1,server.creates);assertEquals(1,server.originals);assertTrue(flow.state.value.unknown)
    }
    /** 原键无法可靠保存时，确认必须停在本地，不发任何创建请求。 */
    @Test fun storageFailurePreventsWrite()=runTest {
        val server=Server();val memory=Memory();val flow=SeckillFlow(server,memory,{elapsed},{"key"})
        flow.open("activity","a","session");flow.prepare(1);memory.writable=false;flow.confirm()
        assertEquals(0,server.creates);assertEquals(ApiError.Storage,flow.state.value.error)
    }
    /** 归零立即撤销资格，直到下一次服务器同步；单纯tick不能调用网络。 */
    @Test fun zeroCountdownRequiresServerSyncWithoutAutomaticPurchase()=runTest {
        val server=Server();server.current=activity.copy(state="UPCOMING",startsAt=now.plusSeconds(1));val flow=SeckillFlow(server,Memory(),{elapsed},{"key"})
        flow.open("activity","a","session");val reads=server.reads
        elapsed+=1000;flow.tick();assertFalse(flow.state.value.synchronized);assertEquals(reads,server.reads)
        server.current=activity.copy(serverTime=now.plusSeconds(1));flow.refresh()
        assertTrue(flow.state.value.eligibility?.canPurchase==true);assertEquals(0,server.creates)
    }
    /** 进程重建只核查已持久化的原键，账号切换不能展示前一账号订单。 */
    @Test fun recoveryIsReadOnlyAndAccountScoped()=runTest {
        val server=Server();server.originalFound=true;val memory=Memory();memory.records["a"]=SeckillRecovery("activity","key")
        val flow=SeckillFlow(server,memory,{elapsed},{"new"});flow.open("activity","a","session")
        assertEquals("order",flow.state.value.order?.value?.orderId);assertEquals(1,server.originals);assertEquals(0,server.creates)
        flow.open("activity","b","other");assertNull(flow.state.value.order);assertEquals(0,server.creates)
    }
    /** 网络等待中再次确认不会创建第二笔订单，迟到结果也不能覆盖新账号状态。 */
    @Test fun duplicateClickAndLateIdentityResponseCannotCreateOrLeak()=runTest {
        val server=Server();server.gate=CompletableDeferred();val flow=SeckillFlow(server,Memory(),{elapsed},{"key"})
        flow.open("activity","a","session");flow.prepare(1)
        val first=launch {flow.confirm()};testScheduler.runCurrent();flow.confirm();assertEquals(1,server.creates)
        flow.open("activity","b","other");server.gate!!.complete(Unit);first.join();assertNull(flow.state.value.order)
    }
    /** 支付截止归零只显示待核对，不能自行宣布关闭或继续发送支付。 */
    @Test fun paymentDeadlineNeverLocallyClosesOrder()=runTest {
        val flow=SeckillFlow(Server(),Memory(),{elapsed},{"key"});flow.open("activity","a","session");flow.prepare(1);flow.confirm()
        elapsed+=300_000;flow.tick();flow.pay()
        assertEquals("PENDING_PAYMENT",flow.state.value.order?.value?.status);assertFalse(flow.state.value.synchronized)
    }
    /** 游客也应在开抢后重新读取活动，不能永远停留在首份预告快照。 */
    @Test fun guestRefreshObservesOpeningWithoutPrivateEligibility()=runTest {
        val server=Server();server.current=activity.copy(state="UPCOMING",startsAt=now.plusSeconds(1))
        val flow=SeckillFlow(server,Memory(),{elapsed},{"key"});flow.open("activity",null,null)
        server.current=activity.copy(serverTime=now.plusSeconds(2));elapsed+=2000;flow.tick();flow.refresh()
        assertEquals("LIVE",flow.state.value.activity?.state);assertEquals(0,server.reads);assertNull(flow.state.value.eligibility)
    }
    /** 原请求记录损坏时刷新不能绕开恢复检查；必须修复读取后重新打开身份。 */
    @Test fun corruptRecoveryCannotBeBypassedByRefresh()=runTest {
        val server=Server();val memory=Memory();memory.corrupt=true
        val flow=SeckillFlow(server,memory,{elapsed},{"key"});flow.open("activity","a","session");flow.refresh();flow.prepare(1);flow.confirm()
        assertEquals(ApiError.Storage,flow.state.value.error);assertEquals(0,server.creates);assertFalse(flow.state.value.synchronized)
    }
    /** 页面取消在途POST不能清除原键，下一次进入只读找回同一订单。 */
    @Test fun cancellationRetainsOriginalRequestForReadOnlyRecovery()=runTest {
        val server=Server();server.gate=CompletableDeferred();val memory=Memory();val flow=SeckillFlow(server,memory,{elapsed},{"key"})
        flow.open("activity","a","session");flow.prepare(1)
        val request=launch {flow.confirm()};testScheduler.runCurrent();request.cancel();request.join()
        assertEquals("key",memory.records["a"]?.requestId);assertTrue(flow.state.value.unknown);assertFalse(flow.state.value.busy)
        server.originalFound=true;flow.refresh();assertEquals("order",flow.state.value.order?.value?.orderId);assertEquals(1,server.creates)
    }
    /** 从本人订单列表进入时按明确订单读取，不能依赖本设备曾保存过创建回执。 */
    @Test fun explicitOrderCanResumeOnAnotherDeviceWithoutCreating()=runTest {
        val server=Server();val flow=SeckillFlow(server,Memory(),{elapsed},{"key"})
        flow.open("activity","a","session","order")
        assertEquals("order",flow.state.value.order?.value?.orderId);assertEquals(0,server.creates);assertEquals(0,server.originals)
    }
    /** 已支付页返回活动的磁盘清理迟到时，新账号/另一活动的完整状态必须保持原样，包括失败提示。 */
    @Test fun lateReturnCleanupCannotReplaceNewAccountState()=runTest {
        for(clearSucceeds in listOf(true,false)) {
            val server=Server();val memory=Memory();val flow=SeckillFlow(server,memory,{elapsed},{"key"})
            flow.open("activity","a","session-a");flow.prepare(1);flow.confirm();flow.pay()
            memory.clearStarted=false;memory.clearResult=clearSucceeds;memory.clearGate=CompletableDeferred()
            val returning=launch {flow.returnToActivity()};testScheduler.runCurrent()
            assertTrue("必须实际停在旧账号的清理等待",memory.clearStarted)
            server.current=activity.copy(id="other-activity")
            flow.open("other-activity","b","session-b");val newIdentityState=flow.state.value
            memory.clearGate!!.complete(Unit);returning.join()
            assertEquals("旧清理回调不得覆盖新账号页面，清理成功=$clearSucceeds",newIdentityState,flow.state.value)
            assertEquals(1,server.creates);assertEquals(1,server.pays)
        }
    }
    /** 旧账号明确被拒后的清理即使迟到成功或失败，也不能把售罄错误和资格撤销写给新账号。 */
    @Test fun lateRejectedCreateCleanupCannotPolluteNewAccountState()=runTest {
        for(clearSucceeds in listOf(true,false)) {
            val server=Server();val memory=Memory();val flow=SeckillFlow(server,memory,{elapsed},{"key"})
            flow.open("activity","a","session-a");flow.prepare(1)
            server.createRejection=ApiError.Http(409,"库存不足","SOLD_OUT")
            memory.clearResult=clearSucceeds;memory.clearGate=CompletableDeferred()
            val confirming=launch {flow.confirm()};testScheduler.runCurrent()
            assertTrue("必须实际停在拒绝后的旧账号清理等待",memory.clearStarted)
            server.current=activity.copy(id="other-activity")
            flow.open("other-activity","b","session-b");val newIdentityState=flow.state.value
            memory.clearGate!!.complete(Unit);confirming.join()
            assertEquals("旧拒绝不得污染新账号页面，清理成功=$clearSucceeds",newIdentityState,flow.state.value)
            assertEquals(1,server.creates);assertEquals(0,server.pays)
        }
    }
    /** 查看旧已支付订单只清理它自己的原键，不能删除同账号另一张券的待支付恢复记录。 */
    @Test fun readingPaidHistoryDoesNotDeleteAnotherPendingRecovery()=runTest {
        val server=Server();server.readOrder=order.copy(value=order.value.copy(status="PAID"))
        val memory=Memory();val other=SeckillRecovery("other-activity","other-request","other-order")
        memory.records["a"]=other
        val flow=SeckillFlow(server,memory,{elapsed},{"unused"})
        flow.open("activity","a","session-a","order")
        assertEquals("PAID",flow.state.value.order?.value?.status)
        assertEquals("历史订单回读不得删除另一笔待支付恢复记录",other,memory.records["a"])
        assertEquals(0,server.creates);assertEquals(0,server.pays)
    }
    /** 旧订单查询回填迟到时，不得覆盖同账号另一页面已持久保存的未知写入原键。 */
    @Test fun lateOrderIdentitySaveCannotReplaceNewUnknownRequest()=runTest {
        val server=Server();val memory=Memory()
        memory.records["a"]=SeckillRecovery("activity","old-key","order")
        memory.knownSaveGate=CompletableDeferred()
        val flow=SeckillFlow(server,memory,{elapsed},{"unused"})
        val oldRead=launch {flow.open("activity","a","session-a")};testScheduler.runCurrent()
        assertTrue("必须停在旧订单身份回填",memory.knownSaveStarted)
        server.current=activity.copy(id="other-activity")
        flow.open("other-activity","a","session-a")
        // 另一页面在此时已保存新POST原键；不能借测试替身直接清空它来规避竞争。
        val newer=SeckillRecovery("other-activity","new-request")
        memory.records["a"]=newer
        memory.knownSaveGate!!.complete(Unit);oldRead.join()
        assertEquals("旧订单身份回填不得覆盖新未知请求",newer,memory.records["a"])
        assertEquals("other-activity",flow.state.value.activity?.id);assertEquals(0,server.creates)
    }
}
