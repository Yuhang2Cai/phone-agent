// 业务列表身份模块：公开UUID决定行身份，快照隔离外部可变集合，保持后端原始顺序。
package com.fandazi.app.feature.store

import com.fandazi.app.feature.deal.DealPackage
import com.fandazi.app.feature.voucher.GroupOrderSummary
import java.util.Collections
import java.util.UUID

/** 六类公开数据共享验证；重复投影是协议错误，不能按位置或名称补造身份及静默丢行。 */
object CatalogRowIdentity {
    /** 校验标准UUID文本；非法或非标准短写拒绝，以免同一资源存在不同字符串身份。 */
    fun publicId(value:String):String {
        require(UUID.fromString(value).toString().equals(value,ignoreCase=true)) { "公开身份格式无效" }
        return value
    }
    /** 输入后端顺序，输出只读容器；遇重复公开身份直接失败，不进行客户端去重或排序。 */
    fun <T> ordered(rows:List<T>,identity:(T)->String):List<T> {
        val seen=HashSet<String>()
        rows.forEach { require(seen.add(publicId(identity(it)).lowercase())) { "重复公开身份" } }
        return immutable(rows)
    }
    /** 公共列表包含嵌套评分数组，复制嵌套数组才不会在后台Diff读取时被调用方改写。 */
    fun stores(rows:List<CatalogStore>):List<CatalogStore> = ordered(rows.map { it.copy(reviewDistribution=immutable(it.reviewDistribution)) }) {it.publicId}
    /** 当前门店套餐须有独立UUID且归属一致；价格、库存、顺序沿用服务器值。 */
    fun deals(rows:List<DealPackage>,storeId:String):List<DealPackage> = ordered(rows) {
        require(it.storePublicId==storeId) { "套餐门店归属无效" };it.publicId
    }
    /** 菜单只读行由后端持久化UUID区分，同名同价的两道菜也不能合成一行。 */
    fun dishes(rows:List<CatalogDish>):List<CatalogDish> = ordered(rows) {it.publicId}
    /** 评价同时核对所属门店，避免错误路径数据混入另一门店页面。 */
    fun reviews(rows:List<CatalogReview>,storeId:String):List<CatalogReview> = ordered(rows) {
        // 评价Holder依赖完整文字、合法评分和UTC时间；协议坏值应由Repository转成局部失败。
        require(it.storePublicId==storeId) { "评价门店归属无效" }
        require(it.reviewerName.isNotBlank() && it.content.isNotBlank()) { "评价文字无效" }
        require(it.rating.isFinite() && it.rating in 0.0..5.0) { "评价评分无效" }
        java.time.Instant.parse(it.createdAt)
        it.publicId
    }
    /** 订单快照复制嵌套券集合；不持久化资格，也不修改接口枚举和金额。 */
    fun orders(rows:List<GroupOrderSummary>):List<GroupOrderSummary> = ordered(rows.map { it.copy(vouchers=immutable(it.vouchers)) }) {it.orderId}
    /** 内部复制保证外部列表之后插入、排序或删除不会影响已提交值。 */
    private fun <T> immutable(rows:List<T>):List<T> = Collections.unmodifiableList(ArrayList(rows))
}
