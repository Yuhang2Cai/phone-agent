// 公开目录来源模块：只对只读目录扩展元数据，原ApiResult及所有私人仓库保持原协议。
package com.fandazi.app.core.cache
import com.fandazi.app.core.network.ApiResult
/** 页面状态对应UI17；只有ONLINE代表本次成功联网事实，其余均不授予交易资格。 */
enum class CatalogReadStatus { ONLINE, REFRESHING, OFFLINE, STALE, MISSING, EXPIRED, INCOMPLETE }
/** 来源不包含账号、凭证或位置；时间为真实成功读取的毫秒墙钟。 */
data class CatalogReadMetadata(val status:CatalogReadStatus=CatalogReadStatus.ONLINE,val fetchedAt:Long?=null,
    /** 刷新是请求状态，过期是原快照年龄；两者独立，防止刷新把旧事实标成新鲜。 */
    val stale:Boolean=status==CatalogReadStatus.STALE || status==CatalogReadStatus.EXPIRED) {
    /** 任何缓存/失败/刷新中的事实都只读，调用者仍需新报价及用户确认。 */
    val canTransact:Boolean get()=status==CatalogReadStatus.ONLINE
    /** 列表在刷新期间仍显示已有同条件快照，不回退到全屏加载占位。 */
    val refreshing:Boolean get()=status==CatalogReadStatus.REFRESHING
    /** 来源值供审计及页面标签；不从网络连接图标推断HTTP成功。 */
    val source:String get()=if(status==CatalogReadStatus.ONLINE)"ONLINE"else"CACHE"
}
/** 数据与来源一起发布，禁止页面遗失元数据后把缓存包装成在线成功。 */
data class CatalogRead<T>(val result:ApiResult<T>,val metadata:CatalogReadMetadata=CatalogReadMetadata()) {
    /** DTO变换保留同一来源，不能将快照重新包装成在线成功。 */
    fun <R> map(transform:(T)->R):CatalogRead<R> = when(val r=result){is ApiResult.Success->CatalogRead(ApiResult.Success(transform(r.value)),metadata);is ApiResult.Failure->CatalogRead(r,metadata)}
    /** 非缓存的既有仓库默认适配，不改变测试替身、取消及HTTP错误类型。 */
    companion object {fun <T> online(result:ApiResult<T>)=CatalogRead(result,CatalogReadMetadata(fetchedAt=System.currentTimeMillis()))}
}
