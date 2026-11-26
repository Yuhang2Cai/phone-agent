// 可选业务导航边界：只打开当前导航图已注册的页面，支持逐任务交付而不依赖未来模块的资源 ID。
package com.fandazi.app.core.navigation

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.navigation.NavGraph
import androidx.navigation.findNavController

/** 输入实际导航图和资源 ID；缺失或未注册时返回 null，不能把仅存在的资源声明当作可用页面。 */
internal fun registeredBusinessDestination(graph: NavGraph, resourceId: Int): Int? =
    resourceId.takeIf { it != 0 && graph.findNode(it) != null }

/** 根据固定 UI 路由名称打开已注册页面；参数沿用真实公开身份，未交付页面仅提示，不代替业务提交。 */
fun View.openRegisteredBusinessRoute(name: String, arguments: Bundle, unavailableMessage: String) {
    val controller = findNavController()
    // 页面可以在后续任务注册；当前任务不能为了静态 ID 编译而引入未验收的整套写业务。
    val resourceId = resources.getIdentifier(name, "id", context.packageName)
    val destination = registeredBusinessDestination(controller.graph, resourceId)
    if (destination == null) {
        // 未注册是明确的能力边界，不伪造操作成功，也不启动延期核销页面。
        Toast.makeText(context, unavailableMessage, Toast.LENGTH_SHORT).show()
        return
    }
    controller.navigate(destination, arguments)
}
