// 业务列表锚点模块：仅保存后端公开行键和像素，缺失行回到顶部，避免位置号对应不同资源。
package com.fandazi.app.feature.store

import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/** key可空表示顶部，offset是布局管理器相对起始内边距的实际像素；没有业务事实。 */
data class ListAnchor(val key:String?=null,val offset:Int=0)

/** 生命周期内保存及提交完成恢复，输入rowKey对应Adapter当前不可变集合。 */
object BusinessListAnchor {
    /** 首行身份而非数组位置作为恢复依据，返回时服务端可能改变顺序。 */
    fun capture(list:RecyclerView,rowKey:(Int)->String?):ListAnchor {
        val layout=list.layoutManager as? LinearLayoutManager ?: return ListAnchor()
        val position=layout.findFirstVisibleItemPosition()
        val child=layout.findViewByPosition(position) ?: return ListAnchor()
        return ListAnchor(rowKey(position),layout.getDecoratedTop(child)-list.paddingTop)
    }
    /** 当前列表提交完成才执行；资源被筛除或删除时回到顶部，不恢复成另一条数据。 */
    fun restore(list:RecyclerView,anchor:ListAnchor,keys:List<String>) {
        val position=anchor.key?.let(keys::indexOf)?.takeIf{it>=0} ?: 0
        (list.layoutManager as? LinearLayoutManager)?.scrollToPositionWithOffset(position,if(anchor.key in keys)anchor.offset else 0)
    }
}
