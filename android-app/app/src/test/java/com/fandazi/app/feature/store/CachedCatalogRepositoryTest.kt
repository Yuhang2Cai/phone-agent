// 公开目录仓库验收测试：使用纯内存公开存储和受控只读响应，固定断网与磁盘失败边界。
package com.fandazi.app.feature.store
import com.fandazi.app.core.cache.*
import com.fandazi.app.core.network.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
class CachedCatalogRepositoryTest {
    /** 合成公开字符串仅用于引擎单测，不进入正式目录装配或演示UI。 */
    private val codec=object:CatalogCacheCodec<List<String>> {override fun encode(value:List<String>)=value.map{it to it};override fun decode(rows:List<Pair<String,String>>)=rows.map{it.second}}
    private val now=100_000_000L
    /** 模拟完整原子快照及磁盘异常，业务写请求在接口上不存在。 */
    private class MemoryStore:CatalogCacheStore {
        val data=mutableMapOf<String,CatalogCachedSnapshot>();var failWrite=false;var failRead=false
        override suspend fun read(key:String):CatalogCachedSnapshot?{if(failRead)error("磁盘不可读");return data[key]}
        override suspend fun save(query:CatalogCachedQuery,records:List<CatalogCachedRecord>){if(failWrite)error("磁盘不可写");data[query.queryKey]=CatalogCachedSnapshot(query,records)}
    }
    /** 夹具只含公开ID和时间，确保顺序可独立断言。 */
    private fun seed(store:MemoryStore,key:String="A",age:Long=0,complete:Boolean=true,version:Int=1){val at=now-age;val ids=listOf("b","a");store.data[key]=CatalogCachedSnapshot(CatalogCachedQuery(key,"DEAL",ids,complete,version,fetchedAt=at,expiresAt=at+86_400_000),ids.map{CatalogCachedRecord("DEAL",it,it,version,fetchedAt=at,expiresAt=at+86_400_000)})}
    /** 离线保留同条件原顺序，先显刷新快照，后明确离线，不发写请求。 */
    @Test fun sameQueryOfflineKeepsOrder()=runTest{val store=MemoryStore();seed(store);val reads=CatalogCacheEngine(store){now}.read("A","DEAL",codec){ApiResult.Failure(ApiError.Network("断网"))}.toList();assertTrue("同条件离线必须返回缓存成功",reads.last().result is ApiResult.Success);assertEquals(listOf("b","a"),(reads.last().result as ApiResult.Success).value);assertEquals(CatalogReadStatus.REFRESHING,reads.first().metadata.status);assertEquals(CatalogReadStatus.OFFLINE,reads.last().metadata.status);assertFalse(reads.last().metadata.canTransact)}
    /** 12小时前的缓存先显虽处于刷新中，仍必须标记过期；网络成功后才清除此标记。 */
    @Test fun staleCacheFirstRemainsStale()=runTest {
        val store=MemoryStore();seed(store,age=12*3_600_000L)
        val reads=CatalogCacheEngine(store){now}.read("A","DEAL",codec){ApiResult.Failure(ApiError.Network("断网"))}.toList()
        assertEquals(CatalogReadStatus.REFRESHING,reads.first().metadata.status)
        assertTrue("过期快照先显也必须保留stale=true",reads.first().metadata.stale)
        assertTrue(reads.last().metadata.stale)
        val recovered=CatalogCacheEngine(store){now}.read("A","DEAL",codec){ApiResult.Success(listOf("new"))}.last()
        assertFalse(recovered.metadata.stale)
    }
    /** 条件B绝不借A，即使两次查询同城市也只能显示无缓存。 */
    @Test fun differentQueryNeverBorrows()=runTest{val store=MemoryStore();seed(store);val read=CatalogCacheEngine(store){now}.read("B","DEAL",codec){ApiResult.Failure(ApiError.Network("断网"))}.last();assertEquals(CatalogReadStatus.MISSING,read.metadata.status);assertTrue(read.result is ApiResult.Failure)}
    /** 超过24小时、结构版本错误、缺成员和不完整集合都隐藏业务数据。 */
    @Test fun oldAndIncompleteAreHidden()=runTest{for(pair in listOf(86_400_001L to CatalogReadStatus.EXPIRED,0L to CatalogReadStatus.INCOMPLETE)){val store=MemoryStore();seed(store,age=pair.first,complete=pair.second!=CatalogReadStatus.INCOMPLETE);val read=CatalogCacheEngine(store){now}.read("A","DEAL",codec){ApiResult.Failure(ApiError.Network("断网"))}.last();assertEquals(pair.second,read.metadata.status);assertTrue(read.result is ApiResult.Failure)}}
    /** schema不同或成员丢失均不能被成功解码成缩短后的列表。 */
    @Test fun invalidSchemaAndMissingMember()=runTest{for(invalidVersion in listOf(true,false)){val store=MemoryStore();seed(store,version=if(invalidVersion)2 else 1);if(!invalidVersion)store.data["A"]=store.data.getValue("A").copy(records=emptyList());val read=CatalogCacheEngine(store){now}.read("A","DEAL",codec){ApiResult.Failure(ApiError.Network("断网"))}.last();assertTrue(read.result is ApiResult.Failure);assertEquals(CatalogReadStatus.INCOMPLETE,read.metadata.status)}}
    /** 在线成功必须实际入库，之后换账号的公共读取仍只含原公开数据。 */
    @Test fun successfulReadWarmsPublicCache()=runTest{val store=MemoryStore();val engine=CatalogCacheEngine(store){now};engine.read("A","DEAL",codec){ApiResult.Success(listOf("c","a"))}.toList();assertTrue("成功响应必须实际保存公开缓存",store.data.containsKey("A"));assertEquals(listOf("c","a"),store.data.getValue("A").query.orderedIds);val read=engine.read("A","DEAL",codec){ApiResult.Failure(ApiError.Network("另一账号离线"))}.last();assertEquals(listOf("c","a"),(read.result as ApiResult.Success).value)}
    /** 存储故障不改在线成功，也不触发自动重试或业务写操作。 */
    @Test fun diskFailureDoesNotBreakOnline()=runTest{val store=MemoryStore();store.failRead=true;store.failWrite=true;var calls=0;val read=CatalogCacheEngine(store){now}.read("A","DEAL",codec){calls++;ApiResult.Success(listOf("a"))}.last();assertEquals(listOf("a"),(read.result as ApiResult.Success).value);assertEquals(CatalogReadStatus.ONLINE,read.metadata.status);assertEquals(1,calls)}
    /** 恢复使用一次真实新读取，并更新原查询缓存；实际无交易写入另由设备请求和后端计数验证。 */
    @Test fun recoveryOnlyReadsFreshFacts()=runTest{val store=MemoryStore();seed(store);var reads=0;val read=CatalogCacheEngine(store){now}.read("A","DEAL",codec){reads++;ApiResult.Success(listOf("new"))}.last();assertEquals(1,reads);assertTrue(read.metadata.canTransact);assertEquals(listOf("new"),store.data.getValue("A").query.orderedIds)}
    /** 不合作取消的旧请求迟到，也不能覆盖新请求写入的同键缓存。 */
    @Test fun lateRefreshCannotOverwrite()=runTest{val store=MemoryStore();val engine=CatalogCacheEngine(store){now};val waiting=CompletableDeferred<Unit>();val old=launch{engine.read("A","DEAL",codec){withContext(NonCancellable){waiting.await()};ApiResult.Success(listOf("old"))}.collect()};yield();engine.read("A","DEAL",codec){ApiResult.Success(listOf("new"))}.collect();waiting.complete(Unit);old.join();assertTrue("新响应必须保存同键快照",store.data.containsKey("A"));assertEquals(listOf("new"),store.data.getValue("A").query.orderedIds)}
    /** 取消不变成离线成功，当前任务结束后也不能保存旧响应。 */
    @Test fun cancellationPropagates()=runTest{val store=MemoryStore();try{CatalogCacheEngine(store){now}.read("A","DEAL",codec){throw CancellationException("页面退出")}.collect();fail("取消必须传播")}catch(expected:CancellationException){assertTrue(store.data.isEmpty())}}
}
