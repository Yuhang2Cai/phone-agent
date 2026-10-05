// 真设备Room测试：验证有序成员事务、实际表白名单、失败回滚及只清纯公开库的降级迁移。
package com.fandazi.app.core.cache
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import android.content.Context
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
@RunWith(AndroidJUnit4::class) class CatalogCacheDatabaseTest {
    private val context=ApplicationProvider.getApplicationContext<Context>()
    private val name="task17-public-test.db";private lateinit var db:CatalogDatabase
    /** 每例只清合成测试库，不能修改正式public-catalog.db或私人存储。 */
    @Before fun setUp(){context.deleteDatabase(name);db=CatalogDatabase.create(context,name)}
    /** 关闭句柄再删除本例测试库，避免影响真实UI缓存验收。 */
    @After fun tearDown(){db.close();context.deleteDatabase(name);context.deleteDatabase("task17-neighbor-test.db")}
    /** 构建两条不同ID原顺序快照，均为合成公开测试字段。 */
    private fun fixture():Pair<CatalogCachedQuery,List<CatalogCachedRecord>> {val ids=listOf("b","a");return CatalogCachedQuery("A","DEAL",ids,fetchedAt=100,expiresAt=86_400_100) to ids.map{CatalogCachedRecord("DEAL",it,it,fetchedAt=100,expiresAt=86_400_100)}}
    /** 两表同时保存且重建按原顺序读取，不允许只有集合的假完整结果。 */
    @Test fun atomicMembersAndOrder()=runBlocking{val(q,rows)=fixture();db.catalog().saveSnapshot(q,rows);val snapshot=db.catalog().snapshot("A")!!;assertEquals(2,snapshot.records.size);assertEquals(listOf("b","a"),snapshot.query.orderedIds);assertNull(db.catalog().snapshot("B"))}
    /** 真实SQLite只有公开实体/集合，无账号、凭证、经纬度、聊天或业务表。 */
    @Test fun schemaHasNoPrivateColumns()=runBlocking{db.catalog().snapshot("A");val sql=db.openHelper.readableDatabase;val tables=mutableSetOf<String>();sql.query("SELECT name FROM sqlite_master WHERE type='table'").use{while(it.moveToNext())tables+=it.getString(0)};assertTrue(tables.containsAll(listOf("catalog_records","catalog_queries")));assertFalse(tables.any{it.contains("order") || it.contains("token") || it.contains("chat")});for(table in listOf("catalog_records","catalog_queries")){sql.query("PRAGMA table_info($table)").use{while(it.moveToNext()){assertFalse(it.getString(1) in listOf("latitude","longitude","userId","accountId","token","credential","audio"))}}}}
    /** 注入实际SQL中止触发器，原集合和成员在保存失败后应一起回滚。 */
    @Test fun sqlFailureRollsBackWholeSnapshot()=runBlocking{val(q,rows)=fixture();db.openHelper.writableDatabase.execSQL("CREATE TRIGGER fail_query BEFORE INSERT ON catalog_queries BEGIN SELECT RAISE(ABORT,'task17 rollback'); END");try{db.catalog().saveSnapshot(q,rows);fail("SQL写入应被触发器拒绝")}catch(expected:android.database.sqlite.SQLiteException){assertNull(db.catalog().query("A"));assertTrue(db.catalog().records("DEAL",listOf("a","b")).isEmpty())}}
    /** 旧纯公开库版本降级清缓存，邻近合成私人库的数据保持，不能借迁移清业务库。 */
    @Test fun incompatibleVersionClearsOnlyPublicDatabase()=runBlocking{db.close();SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(name),null).use{it.execSQL("CREATE TABLE old_public (id TEXT)");it.execSQL("INSERT INTO old_public VALUES ('old')");it.version=2};val neighbor=context.getDatabasePath("task17-neighbor-test.db");SQLiteDatabase.openOrCreateDatabase(neighbor,null).use{it.execSQL("CREATE TABLE private_fixture (id TEXT)");it.execSQL("INSERT INTO private_fixture VALUES ('keep')");it.version=1};db=CatalogDatabase.create(context,name);assertNull(db.catalog().snapshot("A"));db.openHelper.readableDatabase.query("SELECT name FROM sqlite_master WHERE name='old_public'").use{assertFalse(it.moveToFirst())};SQLiteDatabase.openDatabase(neighbor.path,null,SQLiteDatabase.OPEN_READONLY).use{it.rawQuery("SELECT id FROM private_fixture",null).use{row->assertTrue(row.moveToFirst());assertEquals("keep",row.getString(0))}}}
}
