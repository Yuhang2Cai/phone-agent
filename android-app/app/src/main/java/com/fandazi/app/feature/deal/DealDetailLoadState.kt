// 套餐分阶段状态模块：可信只读详情先呈现，同店选项独立加载；购买门槛不等价于后端报价或交易授权。
package com.fandazi.app.feature.deal

import com.fandazi.app.feature.store.CatalogRowIdentity
import java.time.LocalDate

/** 一次公开资源读取的不可变快照；失败只清受影响区，不保存DTO到进程恢复状态。 */
data class DealDetailLoadState(
    val requestedId:String="",
    val requestedStoreId:String="",
    val detail:DealPackage?=null,
    val detailLoading:Boolean=false,
    val detailError:String?=null,
    val options:List<DealPackage> = emptyList(),
    val optionsLoading:Boolean=false,
    val optionsError:String?=null,
    // 详情和选项各保留来源，任一缓存/在途/失败均不能开放购买。
    val metadata:com.fandazi.app.core.cache.CatalogReadMetadata=com.fandazi.app.core.cache.CatalogReadMetadata(),
    val optionsMetadata:com.fandazi.app.core.cache.CatalogReadMetadata=com.fandazi.app.core.cache.CatalogReadMetadata(),
) {
    /** 列表归属及成员验证完成后再开放入口；实际购买仍重新请求后端报价、确认和幂等写入。 */
    val purchaseReady:Boolean get() {
        val current=detail ?: return false
        if(!metadata.canTransact || !optionsMetadata.canTransact || detailLoading || optionsLoading || detailError!=null || optionsError!=null)return false
        val listed=options.singleOrNull{it.publicId==current.publicId && it.storePublicId==current.storePublicId} ?: return false
        // 两个只读响应都必须满足原可售条件；设备时间只作保守门槛，后端报价保留最终资格裁定。
        return DealDetailValidation.saleAvailable(current) && DealDetailValidation.saleAvailable(listed)
    }
}

/** 请求/响应身份与展示字段的统一边界；不猜标题人数、不改服务器金额，也不替换首个套餐。 */
internal object DealDetailValidation {
    /** UUID及非负数字、正限购量、ISO日期范围是可信展示前提；非法输入抛出参数或日期异常。 */
    fun fields(deal:DealPackage) {
        CatalogRowIdentity.publicId(deal.publicId);CatalogRowIdentity.publicId(deal.storePublicId)
        require(deal.title.isNotBlank()){"套餐标题缺失"}
        require(deal.priceCents>=0 && deal.originalPriceCents>=0 && deal.inventory>=0 && deal.purchaseLimit>0){"套餐数字字段无效"}
        val from=LocalDate.parse(deal.validFrom);val to=LocalDate.parse(deal.validTo)
        require(!from.isAfter(to)){"套餐日期范围无效"}
    }
    /** 依照原后端可售过滤检查日期/上架/库存；显式零价合法，不改变服务器价格或库存。 */
    fun saleAvailable(deal:DealPackage):Boolean {
        val today=LocalDate.now()
        return deal.active && deal.inventory>0 && !today.isBefore(LocalDate.parse(deal.validFrom)) && !today.isAfter(LocalDate.parse(deal.validTo))
    }
}
