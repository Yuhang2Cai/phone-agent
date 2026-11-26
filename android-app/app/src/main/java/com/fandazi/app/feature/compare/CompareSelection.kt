// 套餐选择模块：全流程只保存公开ID与用户操作顺序，依赖目录UUID验证，不保存价格/交易凭证。
package com.fandazi.app.feature.compare
import com.fandazi.app.feature.store.CatalogRowIdentity
import java.util.Collections
/** 不可变有序选择；非法构造抛参数异常，恢复入口对损坏状态整体清空而不选默认套餐。 */
class CompareSelection(ids:List<String> = emptyList()) {
    /** 复制和规范化公开引用，外部集合变更不影响当前选择。 */
    val ids:List<String> = Collections.unmodifiableList(ids.map{CatalogRowIdentity.publicId(it).lowercase()})
    init {require(this.ids.size<=3 && this.ids.distinct().size==this.ids.size){"对比选择无效"}}
    /** 显式加入结果；被拒绝时原选择保持，错误文案由页面按数量事实展示。 */
    data class Added(val selection:CompareSelection,val accepted:Boolean)
    /** 正式对比必须有2–3个不同资源；单选仍可在列表继续浏览。 */
    val canCompare:Boolean get()=ids.size in 2..3
    /** 重复加入保持顺序，第四项拒绝；非法ID同样拒绝且不污染原状态。 */
    fun add(id:String):Added {
        val normal=runCatching{CatalogRowIdentity.publicId(id).lowercase()}.getOrNull() ?: return Added(this,false)
        if(normal in ids)return Added(this,true)
        if(ids.size==3)return Added(this,false)
        return Added(CompareSelection(ids+normal),true)
    }
    /** 移除只改变公开引用；再次加入由用户新操作置于末尾。 */
    fun remove(id:String)=CompareSelection(ids.filterNot{it.equals(id,true)})
    companion object {
        /** 恢复时先全量校验/去重，损坏或超过3项清空，禁止截断后默认购买。 */
        fun restore(ids:List<String>):CompareSelection=runCatching {
            val normal=ids.map{CatalogRowIdentity.publicId(it).lowercase()}.distinct()
            CompareSelection(normal)
        }.getOrElse{CompareSelection()}
    }
}
