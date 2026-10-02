// 运行导航边界测试：保留历史类和资源ID不能让旧外卖交易重新出现在活动导航图。
package com.fandazi.app.core.navigation

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 读取实际交付资源及入口源码，补充设备验收，不能替代Maestro或Figma。 */
class NoTakeawayNavigationTest {
    /** 全部旧交易目标退出活动图，团购购买、售后和记忆目标仍存在。 */
    @Test fun oldTransactionDestinationsAreNotRegistered() {
        val factory = DocumentBuilderFactory.newInstance().apply {
            // 资源为本地XML，不允许外部实体或网络DTD参与测试解析。
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        }
        val doc = factory.newDocumentBuilder().parse(File("src/main/res/navigation/nav_graph.xml"))
        val nodes = doc.getElementsByTagName("fragment")
        val names = (0 until nodes.length).map { nodes.item(it).attributes.getNamedItem("android:name").nodeValue }
        for (retired in listOf("MerchantFragment", "AddressFragment", "AddressEditFragment", "AutomationSettingsFragment", "CartFragment", "PaymentFragment")) {
            assertFalse("旧目标仍在活动导航：$retired", names.any { it.substringAfterLast('.') == retired })
        }
        assertTrue(names.any { it.endsWith("MemoryFragment") })
        assertTrue(names.any { it.endsWith("GroupBuyPaymentFragment") })
    }

    /** 默认应用与身份切换不构建旧仓库或购物车，防止隐藏入口仍读取端侧旧事实。 */
    @Test fun defaultStartupDoesNotInitializeTakeawayState() {
        val app = File("src/main/java/com/fandazi/app/FandaziApplication.kt").readText()
        val main = File("src/main/java/com/fandazi/app/MainActivity.kt").readText()
        assertFalse(app.contains("HttpOrderingRepository(apiClient)"))
        assertFalse(app.contains("PreferencesCartStateStore(this)"))
        assertFalse(app.contains("HttpAutomationSettingsRepository(apiClient)"))
        assertFalse(main.contains("OrderingViewModelFactory("))
    }
}
