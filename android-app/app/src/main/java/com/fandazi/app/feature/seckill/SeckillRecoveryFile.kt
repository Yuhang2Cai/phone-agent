// 秒杀恢复存储：在应用私有且不参与备份的目录原子保存原请求，不保存授权、凭据或金额。
package com.fandazi.app.feature.seckill

import android.content.Context
import android.util.AtomicFile
import com.google.gson.Gson
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 账号键由调用方摘要化，文件名再次校验；损坏记录不能被静默当作没有写入。 */
class SeckillRecoveryFile(context:Context):SeckillRecoveryStore {
    private companion object {
        // 多个页面可各有存储实例；同进程共用短临界区，避免AtomicFile读/改/删互相穿插。
        // 临界区仅操作很小的恢复JSON，且全部位于IO调度器，不在主线程等待磁盘。
        val FILE_GATE=Any()
    }
    private val directory=File(context.noBackupFilesDir,"seckill-recovery")
    private val gson=Gson()
    /** 只接受SHA256账号键，拒绝路径穿越或以真实账号作为文件名。 */
    private fun file(account:String):AtomicFile {
        require(account.matches(Regex("[0-9a-f]{64}")))
        check(directory.isDirectory || directory.mkdirs())
        return AtomicFile(File(directory,"$account.json"))
    }
    /** 读取失败向流程传播，页面以存储失败关闭下单能力；无文件才返回null。 */
    override suspend fun load(account:String):SeckillRecovery?=withContext(Dispatchers.IO) {
        synchronized(FILE_GATE){read(file(account))}
    }
    /** 在文件临界区中读取完整原子版本；损坏JSON向外抛出，不能当作无记录后允许新交易。 */
    private fun read(target:AtomicFile):SeckillRecovery? {
        if(!target.baseFile.exists() && !File(target.baseFile.path+".bak").exists())return null
        val value=gson.fromJson(target.openRead().bufferedReader().use {it.readText()},SeckillRecovery::class.java)
        require(!value.activityId.isNullOrBlank() && !value.requestId.isNullOrBlank())
        return value
    }
    /** 新原键同步完成才允许POST；已知订单回填先比对原键，磁盘等待期间的新原请求拥有优先权。 */
    override suspend fun save(account:String,value:SeckillRecovery,expectedRequestId:String?):Boolean=withContext(Dispatchers.IO) {
        synchronized(FILE_GATE) {
            val target=file(account);var output:java.io.FileOutputStream?=null
            try {
                // 核查与写入必须处于同一临界区；不匹配表示旧回填已失效，保留当前原键且不作任何写入。
                if(expectedRequestId!=null && read(target)?.requestId!=expectedRequestId)return@synchronized true
                output=target.startWrite();output.write(gson.toJson(value).toByteArray(Charsets.UTF_8));target.finishWrite(output);true
            }
            catch(failure:Exception){target.failWrite(output);false}
        }
    }
    /** 在同一临界区核对原键并删除；旧页面回调不得删掉该账号后来保存的另一笔原请求。 */
    override suspend fun clear(account:String,requestId:String):Boolean=withContext(Dispatchers.IO) {
        synchronized(FILE_GATE) {
            try {
                val target=file(account);val current=read(target)
                if(current==null || current.requestId!=requestId)return@synchronized true
                target.delete();!target.baseFile.exists() && !File(target.baseFile.path+".bak").exists()
            }catch(failure:Exception){false}
        }
    }
}
