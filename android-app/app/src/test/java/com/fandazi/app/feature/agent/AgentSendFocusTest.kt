// 主动发送定位竞态回归：多个展示差量覆盖首提交时，定位意图保留至真正提交或用户取消。
package com.fandazi.app.feature.agent
import org.junit.Assert.*
import org.junit.Test

/** 纯状态测试模拟异步Diff提交序列，不依赖线程定时或业务发送替身。 */
class AgentSendFocusTest {
    /** 首差量被下一帧覆盖时仍定位同一用户消息，终态到达也不能提前丢掉请求。 */
    @Test fun supersededDiffRetainsFocusUntilActualCommit() {
        val focus=AgentSendFocus();focus.request()
        assertEquals("u1",focus.targetFor("u1",true))
        assertEquals("u1",focus.targetFor("u1",false))
        focus.committed("u1")
        assertNull(focus.targetFor("u1",false))
    }
    /** 旧轮提交不能消费下一轮目标；两次显式发送仍各自定位原展示键。 */
    @Test fun obsoleteCommitCannotConsumeNewTarget() {
        val focus=AgentSendFocus();focus.request();focus.targetFor("u1",true)
        focus.request();assertEquals("u2",focus.targetFor("u2",true))
        focus.committed("u1")
        assertEquals("u2",focus.targetFor("u2",false))
    }
    /** 用户拖动和账号切换取消后，不因迟到事件或旧提交重新定位。 */
    @Test fun draggingOrOwnerChangeCancelsPendingIntent() {
        val focus=AgentSendFocus();focus.request();focus.targetFor("u1",true)
        focus.cancel();focus.committed("u1")
        assertNull(focus.targetFor("u1",false))
        focus.request();focus.cancel()
        assertNull(focus.targetFor("u2",true))
    }
}
