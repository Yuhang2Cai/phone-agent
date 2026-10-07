// 公开只读缓存引擎：先显精确查询快照再读取网络，取消及同键代次防止旧响应覆写新事实。
package com.fandazi.app.core.cache
import com.fandazi.app.core.network.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
/** 引擎只接受公开读取闭包；业务交易仓库不装配本类，磁盘异常不会改变在线成功。 */
class CatalogCacheEngine(private val store:CatalogCacheStore,private val onStorageFailure:(String)->Unit={},private val clock:()->Long=System::currentTimeMillis) {
    private class Slot {val generation=AtomicLong();val write=Mutex()}
    private val slots=ConcurrentHashMap<String,Slot>()
    /** 输入精确查询键及公开编码器；输出来源与结果一起发布，null键表示此条件不能缓存。 */
    fun <T> read(key:String?,kind:String,codec:CatalogCacheCodec<T>,network:suspend()->ApiResult<T>):Flow<CatalogRead<T>> = flow {
        val slot=key?.let{slots.computeIfAbsent(it){Slot()}};val ticket=slot?.generation?.incrementAndGet()
        fun current()=slot==null || slot.generation.get()==ticket
        // 输入校验/本地读取：只查原键，磁盘坏了继续请求网络；取消必须向宿主传播。
        val snapshot=if(key==null)null else try {store.read(key)}catch(c:CancellationException){throw c}catch(e:Exception){null}
        val now=clock();var status=if(key==null)CatalogReadStatus.INCOMPLETE else CatalogReadStatus.MISSING
        var cached:T?=null;var fetchedAt:Long?=snapshot?.query?.fetchedAt
        if(snapshot!=null) {
            val query=snapshot.query;val rows=snapshot.records.associateBy{it.publicId}
            val headers=query.queryKey==key && query.kind==kind && CatalogCachePolicy.valid(query.schemaVersion,query.source,query.fetchedAt,query.expiresAt,now)
            val complete=headers && query.complete && query.orderedIds.distinct().size==query.orderedIds.size && rows.size==snapshot.records.size && query.orderedIds.size==rows.size && query.orderedIds.all{id->rows[id]?.let{it.kind==kind && CatalogCachePolicy.valid(it.schemaVersion,it.source,it.fetchedAt,it.expiresAt,now)}==true}
            // 集合或成员失效整体隐藏；不能返回部分行，也不能从其它城市/分类查缺失成员。
            status=when { !complete->CatalogReadStatus.INCOMPLETE;CatalogCachePolicy.age(query.fetchedAt,now)==CatalogCacheAge.EXPIRED || rows.values.any{CatalogCachePolicy.age(it.fetchedAt,now)==CatalogCacheAge.EXPIRED}->CatalogReadStatus.EXPIRED;else->CatalogReadStatus.OFFLINE }
            if(status==CatalogReadStatus.OFFLINE) {
                fetchedAt=(rows.values.map{it.fetchedAt}+query.fetchedAt).minOrNull()
                try {cached=codec.decode(query.orderedIds.map{it to rows.getValue(it).payload})}
                catch(c:CancellationException){throw c}catch(e:Exception){status=CatalogReadStatus.INCOMPLETE}
                if(cached!=null && CatalogCachePolicy.age(fetchedAt!!,now)==CatalogCacheAge.STALE)status=CatalogReadStatus.STALE
            }
        }
        // 缓存先显仍标刷新且关闭交易；页面可保留原锚点，避免空列表替换快照。
        if(cached!=null && current())emit(CatalogRead(ApiResult.Success(cached),CatalogReadMetadata(CatalogReadStatus.REFRESHING,fetchedAt,stale=status==CatalogReadStatus.STALE)))
        val result=network();currentCoroutineContext().ensureActive();if(!current())return@flow
        if(result is ApiResult.Success) {
            val at=clock()
            // 规范化/原子写入：编码异常和磁盘异常只影响下次离线，当前在线响应保持成功。
            if(key!=null && slot!=null && at>=0 && at<=Long.MAX_VALUE-CatalogCachePolicy.MAX_MILLIS)try {
                val encoded=codec.encode(result.value);require(encoded.map{it.first}.distinct().size==encoded.size){"公开缓存ID重复"}
                val expires=at+CatalogCachePolicy.MAX_MILLIS
                slot.write.withLock {if(current())store.save(CatalogCachedQuery(key,kind,encoded.map{it.first},fetchedAt=at,expiresAt=expires),encoded.map{CatalogCachedRecord(kind,it.first,it.second,fetchedAt=at,expiresAt=expires)})}
            }catch(c:CancellationException){throw c}catch(e:Exception){onStorageFailure(e.javaClass.simpleName)}
            currentCoroutineContext().ensureActive();if(current())emit(CatalogRead(result,CatalogReadMetadata(CatalogReadStatus.ONLINE,at)))
        }else {
            // 结果构造：失败仍保留同条件有效快照；没有数据时给真实原因，无自动请求重放。
            val fallback=if(cached!=null)ApiResult.Success(cached) else result
            emit(CatalogRead(fallback,CatalogReadMetadata(status,fetchedAt)))
        }
    }
}
