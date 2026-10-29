// 顶层导航模块：菜单显式映射、保存/恢复业务栈，认证变化销毁所有已保存栈；依赖既有NavController。
package com.fandazi.app.core.navigation

import android.os.Bundle
import androidx.navigation.NavController
import androidx.navigation.NavGraph
import androidx.navigation.NavDestination
import androidx.navigation.navOptions
import com.fandazi.app.R
import com.fandazi.app.feature.auth.SessionState

/** 只协调页面状态；公开资源仍由页面读取，保存栈不能成为交易资格或第二个会话源。 */
object TopLevelNavigator {
    /** 返回正式目的地，未知菜单返回null；AI菜单资源不是Fragment，必须显式映射。 */
    fun destinationForMenu(menu:Int):Int?=when(menu) {
        R.id.homeFragment->R.id.homeFragment
        R.id.agentAssistantTab->R.id.agentChatFragment
        R.id.ordersFragment->R.id.ordersFragment
        R.id.activityFragment->R.id.activityFragment
        R.id.profileFragment->R.id.profileFragment
        else->null
    }
    /** 公开浏览子页归首页；该映射只用于重选判定，不能作为访问私人资源的授权判断。 */
    fun rootForDestination(destination:Int?):Int?=when(destination) {
        R.id.homeFragment,R.id.searchFragment,R.id.storeFragment->R.id.homeFragment
        R.id.agentChatFragment->R.id.agentChatFragment
        R.id.ordersFragment->R.id.ordersFragment
        R.id.activityFragment->R.id.activityFragment
        R.id.profileFragment->R.id.profileFragment
        else->null
    }
    /** 正式根页面反查菜单ID；受保护入口登录回来时复用同一路径，非根页面返回null。 */
    fun menuForRoot(destination:Int):Int?=when(destination) {
        R.id.homeFragment,R.id.ordersFragment,R.id.activityFragment,R.id.profileFragment->destination
        R.id.agentChatFragment->R.id.agentAssistantTab
        else->null
    }
    /**
     * 处理真实菜单选择并返回是否已处理；当前所属Tab重选保持查询/位置，不追加条目。
     * 输入当前控制器及真实菜单；返回是否已处理，非法导航图仍由NavController拒绝。
     * 使用图根作为弹栈锚点，五条业务根都完整保存；避免以认证起点或未弹出的首页映射另一Tab快照。
     */
    fun select(controller:NavController,menu:Int,discardBusinessLeaf:Boolean=false):Boolean {
        val target=destinationForMenu(menu)?:return false
        if(discardBusinessLeaf) {
            // 明确离开支付结果等交易流程时先删除交易叶子，不把旧报价/确认页保存为首页浏览位置。
            val origin=listOf(R.id.homeFragment,R.id.agentChatFragment,R.id.ordersFragment,R.id.activityFragment,R.id.profileFragment)
                .firstOrNull{runCatching{controller.getBackStackEntry(it)}.isSuccess}
            if(origin!=null)controller.popBackStack(origin,false,false)else reset(controller,R.id.homeFragment)
        }
        if(rootForDestination(controller.currentDestination?.id)==target)return true
        // 登录页没有可点击底栏，拒绝错误代码把认证页保存成业务Tab；空栈仅建立公开首页。
        if(controller.currentDestination?.id==R.id.authFragment)return false
        if(controller.currentDestination==null)reset(controller,R.id.homeFragment)
        controller.navigate(target,null,navOptions {
            launchSingleTop=true
            restoreState=true
            popUpTo(controller.graph.id){inclusive=false;saveState=true}
        })
        // 首页Tab仍恢复搜索/门店完整公开分支；其业务输入可能直接进入聊天，保存栈会连同聊天叶子恢复。
        // 恢复后核对叶子归属：首页只有公开分支可保留，跨入口聊天必须弹回原首页，不能只把菜单染成选中。
        // AI/订单/我的显式入口仍回正式根；正常详情返回不变，清理仅发生在用户明确重选顶层入口时。
        val restored = controller.currentDestination?.id
        val needsRoot = if (target == R.id.homeFragment) rootForDestination(restored) != target else restored != target
        if (needsRoot) controller.popBackStack(target, false, false)
        return true
    }
    /** 薄恢复仅保存身份种类和公开用户名，不保存令牌/邮件/订单事实；令牌版本只在Activity内存比较。 */
    fun ownerLabel(state:SessionState):String=when(state) {
        SessionState.Guest->"guest"
        SessionState.Unauthenticated->"unauthenticated"
        is SessionState.Authenticated->"account:${state.user.username}"
    }
    /**
     * 退出、401或身份变化先销毁保存栈，再替换活动业务栈；返回和restoreState不能复活旧用户ViewModel。
     * arguments只能是当前明确入口的薄引用；此方法不保存、复制或重放旧资格与写请求。
     * 没有返回值；清理ViewModel与任务，非法target或参数由NavController抛异常，不静默跳默认业务页。
     */
    fun reset(controller:NavController,target:Int,arguments:Bundle?=null) {
        val ids=mutableListOf<Int>()
        fun collect(node:NavDestination) {
            ids+=node.id
            // 递归只遍历已装配导航图，未来新增节点同样受账号隔离，不额外启用任何页面。
            if(node is NavGraph)node.forEach(::collect)
        }
        collect(controller.graph)
        ids.forEach {controller.clearBackStack(it)}
        controller.navigate(target,arguments,navOptions {
            launchSingleTop=true
            restoreState=false
            popUpTo(controller.graph.id){inclusive=false;saveState=false}
        })
    }
}
