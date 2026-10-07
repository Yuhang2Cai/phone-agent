// 导航依赖边界回归：验证未来模块缺失不会被当成可打开路由，不对页面视觉使用TDD。
package com.fandazi.app.core.navigation

import androidx.navigation.NavDestination
import androidx.navigation.NavGraph
import androidx.navigation.NavGraphNavigator
import androidx.navigation.NavigatorProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 构造真实导航图；只测试注册边界，不创建订单、申请或静态业务数据。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class RegisteredBusinessRouteTest {
    /** 单独资源ID和不存在的页面均不得导航；后续显式注册后同ID才能使用。 */
    @Test
    fun missingDestinationIsNotEnabledByAnIdAlone() {
        val graph = NavGraph(NavGraphNavigator(NavigatorProvider()))
        assertNull(registeredBusinessDestination(graph, 0))
        assertNull(registeredBusinessDestination(graph, 17))
        // 注册只改变UI路由可用性，不能推断任何后端业务资格。
        graph.addDestination(NavDestination("fragment").apply { id = 17 })
        assertEquals(17, registeredBusinessDestination(graph, 17))
    }
}
