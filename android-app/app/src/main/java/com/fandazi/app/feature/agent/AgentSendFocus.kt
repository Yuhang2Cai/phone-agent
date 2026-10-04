// 主动发送定位状态：把用户发送意图与异步差量提交分开，拖动和账号变化可立即取消。
package com.fandazi.app.feature.agent

/** 仅保存待定位的展示键，不涉及消息发送、业务身份或网络调用。 */
internal class AgentSendFocus {
    private var requested=false
    private var target:String?=null
    /** 用户显式发送时登记定位请求；下一条新增用户消息提供稳定展示键。 */
    fun request(){requested=true}
    /** 返回本次差量希望定位的用户键；后续提交必须沿用同一请求。 */
    fun targetFor(userKey:String?,userAdded:Boolean):String? {
        if(requested && userAdded){requested=false;target=userKey}
        // 最新帧可能取代前一个异步提交，持续返回原目标直到有效提交确认；不会因终态先到达而丢失。
        return target
    }
    /** 只有目标键一致的有效提交能消费定位请求；旧提交不能消费更新的目标。 */
    fun committed(userKey:String?){if(target==userKey)target=null}
    /** 用户拖动、视图销毁或账号变化放弃待定位，旧差量回调不能重新请求。 */
    fun cancel(){requested=false;target=null}
}
