// 套餐选择规则回归：合成公开UUID固定需求边界，不通过测试数据推导被测算法。
package com.fandazi.app.feature.compare
import org.junit.Assert.*
import org.junit.Test
/** 数量和身份约束对真实状态对象执行，公开引用以用户加入顺序恢复。 */
class CompareSelectionTest {
    private val a="00000000-0000-0000-0000-000000000001"
    private val b="00000000-0000-0000-0000-000000000002"
    private val c="00000000-0000-0000-0000-000000000003"
    private val d="00000000-0000-0000-0000-000000000004"
    /** 同ID去重，不增加计数也不改变加入顺序。 */
    @Test fun duplicateNeverChangesSelection(){val x=CompareSelection().add(a).selection.add(b).selection;assertEquals(listOf(a,b),x.add(a).selection.ids)}
    /** 第四项必须被拒绝，已有三项与原顺序保持。 */
    @Test fun fourthIsRejected(){val x=CompareSelection().add(a).selection.add(b).selection.add(c).selection;val r=x.add(d);assertFalse("第四项必须拒绝",r.accepted);assertEquals(listOf(a,b,c),r.selection.ids)}
    /** 至少两项才能进入正式对比，单选不是合法对比。 */
    @Test fun entryRequiresTwoDifferentDeals(){assertFalse(CompareSelection().add(a).selection.canCompare);assertTrue(CompareSelection().add(a).selection.add(b).selection.canCompare)}
    /** 公开UUID薄恢复只保存ID，复原顺序并拒绝非法或超过三项的持久状态。 */
    @Test fun restoresPublicIdsWithoutDuplicates(){assertEquals(listOf(b,a),CompareSelection.restore(listOf(b,a,b)).ids);assertEquals(emptyList<String>(),CompareSelection.restore(listOf("123",a)).ids);assertEquals(emptyList<String>(),CompareSelection.restore(listOf(a,b,c,d)).ids)}
    /** 移除后再加入是新的显式操作，因此位于序列末尾。 */
    @Test fun removingAndReaddingPreservesUserOrder(){val x=CompareSelection.restore(listOf(a,b,c)).remove(b).add(b).selection;assertEquals(listOf(a,c,b),x.ids)}
}
