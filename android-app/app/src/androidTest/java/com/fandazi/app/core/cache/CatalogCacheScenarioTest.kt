// Task17设备条件与连通验证：真实公开HTTP预热，时间/成员缺失受控操作只针对新增公开库。
package com.fandazi.app.core.cache
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.fandazi.app.FandaziApplication
import com.fandazi.app.core.network.*
import com.fandazi.app.feature.store.CatalogQuery
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.last
import com.google.gson.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.signature.ObjectKey
import com.fandazi.app.core.image.*
import java.util.concurrent.ExecutionException
@RunWith(AndroidJUnit4::class) class CatalogCacheScenarioTest {
    private val app=ApplicationProvider.getApplicationContext<FandaziApplication>()
    private lateinit var db:CatalogDatabase
    /** 独立句柄只打开public-catalog.db，不读取账户、订单、券、聊天或加密存储。 */
    @Before fun open(){db=CatalogDatabase.create(app)}
    /** 释放本测试句柄，不清除业务库或自动改网络，外部控制器负责finally恢复环境。 */
    @After fun close(){db.close()}
    /** 直接API客户端读城市，不经缓存：确实收到HTTP成功才能声明恢复连通。 */
    @Test fun actualDeviceHttpConnectivity()=runBlocking {
        assertTrue("平台网络应实际存在",CatalogNetworkAccess.connected(app))
        val response=app.apiClient.get("stores/cities")
        val failure=(response as? ApiResult.Failure)?.error
        val reason=if(failure is ApiError.Network)failure.message else failure?.javaClass?.simpleName.orEmpty()
        assertTrue("设备实际HTTP必须成功，缓存不算联网；安全网络诊断=$reason",response is ApiResult.Success)
        assertTrue(JsonParser.parseString((response as ApiResult.Success).value).isJsonArray)
    }
    /** 无视图的真实预热，不下载照片；实际返回的所有成员按原仓库保存，便于验证新图片版本未缓存。 */
    @Test fun warmPublicCatalogWithoutImages()=runBlocking {
        val read=app.catalogRepository.catalogStores(CatalogQuery()).last()
        assertTrue(read.result is ApiResult.Success);assertEquals(CatalogReadStatus.ONLINE,read.metadata.status)
        val rows=(read.result as ApiResult.Success).value;assertTrue(rows.isNotEmpty())
        for(store in rows.take(2)) {
            assertEquals(CatalogReadStatus.ONLINE,app.catalogRepository.catalogStore(store.publicId,CatalogQuery()).last().metadata.status)
            assertEquals(CatalogReadStatus.ONLINE,app.groupBuyingRepository.catalogStoreDeals(store.publicId).last().metadata.status)
        }
    }
    /** 只调图片缓存条件：清Glide测试状态后下载一个真实菜品，其余图片确实未下载，业务库不动。 */
    @Test fun partialRealPhotoCache()=runBlocking {
        val read=app.catalogRepository.catalogStores(CatalogQuery()).last()
        assertEquals(CatalogReadStatus.ONLINE,read.metadata.status)
        val rows=(read.result as ApiResult.Success).value
        val urls=rows.mapNotNull{CatalogImageUrls.resolve(it.imageUrl)}.distinct()
        assertTrue("真实目录应至少有两张不同图片",urls.size>=2)
        withContext(Dispatchers.Main){Glide.get(app).clearMemory()}
        withContext(Dispatchers.IO) {
            // 受保护图片原策略不落磁盘，本操作只重置Glide图片缓存，不删除Room或私人状态。
            Glide.get(app).clearDiskCache()
            val plan=CatalogImageRequestPolicy.resolve(CatalogImageRequestOptions())
            for(url in listOf(urls[1],CatalogImageUrls.thumbnail(urls[1])!!).distinct()) {
                val target=Glide.with(app).load(url).signature(ObjectKey(plan.signature)).diskCacheStrategy(DiskCacheStrategy.AUTOMATIC).submit(340,128)
                try {assertNotNull("照片必须真正HTTP下载及解码",target.get())}finally{Glide.with(app).clear(target)}
            }
            val cached=Glide.with(app).load(urls[1]).signature(ObjectKey(plan.signature)).onlyRetrieveFromCache(true).submit(340,128)
            try {assertNotNull(cached.get())}finally{Glide.with(app).clear(cached)}
            val missing=Glide.with(app).load(urls.first()).signature(ObjectKey(plan.signature)).onlyRetrieveFromCache(true).submit(340,128)
            try {missing.get();fail("未下载第一张图必须真实缓存未命中")}
            catch(expected:ExecutionException){assertNotNull(expected.cause)}finally{Glide.with(app).clear(missing)}
        }
    }
    /** 检查正式运行公开库的实际每个JSON键，不能仅凭Entity字段少就认为未存私人载荷。 */
    @Test fun actualPublicCacheWhitelist()=runBlocking {
        val forbidden=setOf("userId","accountId","phone","latitude","longitude","distanceMeters","token","jwt","confirmationToken","messages","memory","audio")
        fun inspect(value:JsonElement) {
            if(value.isJsonObject)for((key,child) in value.asJsonObject.entrySet()){assertFalse("公开缓存不得保存私人或定位字段$key",key in forbidden);inspect(child)}
            else if(value.isJsonArray)for(child in value.asJsonArray)inspect(child)
        }
        db.openHelper.readableDatabase.query("SELECT payload FROM catalog_records").use {cursor->
            assertTrue("必须检验真实预热而不是空库",cursor.count>0)
            while(cursor.moveToNext())inspect(JsonParser.parseString(cursor.getString(0)))
        }
    }
    /** 把已真实预热数据整体调到12小时前；只改变公开时间，不改任何金额、标题、库存或身份。 */
    @Test fun setRealWarmCacheAge12Hours()=runBlocking {backup();setAge(12*3_600_000L)}
    /** 25小时快照应隐藏，受控时间操作不伪装成未预热或重新查询成功。 */
    @Test fun setRealWarmCacheAge25Hours()=runBlocking {backup();setAge(25*3_600_000L)}
    /** 原查询的一个真实成员缺失时应整体不完整，不从别的集合借成员填充。 */
    @Test fun removeOneRealWarmQueryMember()=runBlocking {
        backup();val snapshot=db.catalog().snapshot(CatalogCachePolicy.queryKey("stores",CatalogQuery())!!)!!
        assertTrue(snapshot.query.orderedIds.isNotEmpty())
        db.openHelper.writableDatabase.execSQL("DELETE FROM catalog_records WHERE kind=? AND publicId=?",arrayOf(snapshot.query.kind,snapshot.query.orderedIds.first()))
        assertEquals(snapshot.query.orderedIds.size-1,db.catalog().snapshot(snapshot.query.queryKey)!!.records.size)
    }
    /** 冷缓存只清新增的纯公开两表，保留当前真实身份及私人草稿。 */
    @Test fun clearOnlyPublicCache()=runBlocking {
        backup();db.openHelper.writableDatabase.execSQL("DELETE FROM catalog_queries");db.openHelper.writableDatabase.execSQL("DELETE FROM catalog_records")
        assertNull(db.catalog().snapshot(CatalogCachePolicy.queryKey("stores",CatalogQuery())!!))
    }
    /** 恢复备份原公开快照，操作前后的来源/载荷一致，不把受控时间写进正式运行事实。 */
    @Test fun restoreOriginalPublicCache()=runBlocking {
        val backup=file();assertTrue(backup.isFile)
        val rows=JsonParser.parseString(backup.readText()).asJsonArray
        db.runInTransaction {db.openHelper.writableDatabase.execSQL("DELETE FROM catalog_queries");db.openHelper.writableDatabase.execSQL("DELETE FROM catalog_records")}
        for(row in rows) {
            val value=Gson().fromJson(row,CatalogCachedSnapshot::class.java)
            // SQLite的IN查询只保证成员集合，不保证返回顺序；恢复必须沿原orderedIds重建顺序。
            // single要求每个ID/类型恰有一个成员，缺失或重复仍抛错，不能放宽生产完整性校验。
            val ordered=value.query.orderedIds.map { id -> value.records.single { it.publicId==id && it.kind==value.query.kind } }
            db.catalog().saveSnapshot(value.query,ordered)
        }
        assertTrue(rows.size()>0);assertTrue("仅删除本测试公开缓存备份",backup.delete())
    }
    /** 时间字段一起更新保持约定expiresAt，不能用错误过期字段制造另一个失败原因。 */
    private fun setAge(age:Long) {
        val at=System.currentTimeMillis()-age;val expires=at+CatalogCachePolicy.MAX_MILLIS
        db.runInTransaction{for(table in listOf("catalog_queries","catalog_records"))db.openHelper.writableDatabase.execSQL("UPDATE $table SET fetchedAt=?,expiresAt=?",arrayOf(at,expires))}
        db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM catalog_queries").use{assertTrue(it.moveToFirst());assertTrue("必须先有真实预热",it.getInt(0)>0)}
    }
    /** 备份仅公开库快照，文件在应用私有测试目录；从不复制其它表或凭据。 */
    private suspend fun backup() {
        val file=file();if(file.exists())return
        val keys=mutableListOf<String>();db.openHelper.readableDatabase.query("SELECT queryKey FROM catalog_queries").use{while(it.moveToNext())keys+=it.getString(0)}
        assertTrue(keys.isNotEmpty());val snapshots=keys.map{db.catalog().snapshot(it)!!};file.writeText(Gson().toJson(snapshots))
    }
    /** 固定测试文件名仅用于受控公开快照恢复，不包含实际账号或用户输入。 */
    private fun file()=File(app.filesDir,"task17-public-cache-backup.json")
}
