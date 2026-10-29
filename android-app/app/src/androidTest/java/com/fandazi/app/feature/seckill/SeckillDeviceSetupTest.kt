// 秒杀设备验收前置：从临时私有文件读取合成账号，经真实认证服务登录；凭据不进入测试源码或Maestro日志。
package com.fandazi.app.feature.seckill

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.fandazi.app.BuildConfig
import com.fandazi.app.FandaziApplication
import com.fandazi.app.core.network.ApiResult
import com.fandazi.app.feature.auth.SessionState
import com.google.gson.JsonParser
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** 仅本机13382隔离开发服务可运行；不提供生产登录后门，不保存访问令牌到外部存储。 */
@RunWith(AndroidJUnit4::class)
class SeckillDeviceSetupTest {
    /** 登录使用现有会话仓库和加密存储，完成或失败均删除一次性输入。 */
    @Test fun loginSyntheticAccountWithoutLoggingCredentials()=runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("seckillSetup")=="true")
        // 模拟器通过宿主机别名访问隔离服务，真机则通过仅限本轮的 adb reverse 访问同一端口；
        // 两种地址都必须指向 13382 隔离代理，防止测试误连生产环境或其他任务的服务。
        assertTrue(
            "秒杀设备登录只能连接本任务的 13382 隔离代理",
            BuildConfig.API_BASE_URL in setOf(
                "http://10.0.2.2:13382/api/",
                "http://127.0.0.1:13382/api/",
            ),
        )
        val app=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as FandaziApplication
        val input=File(app.cacheDir,"seckill-ui-login.json")
        assertTrue("必须先提供本任务私有合成登录输入",input.isFile)
        try {
            val value=JsonParser.parseString(input.readText()).asJsonObject
            val result=app.sessionRepository.login(value["username"].asString,value["password"].asString)
            // 断言仅输出成功/失败，禁止打印包含token的响应对象或输入内容。
            assertTrue("合成账号真实登录须成功",result is ApiResult.Success)
            assertTrue(app.sessionRepository.state.value is SessionState.Authenticated)
        } finally {assertTrue("一次性输入必须删除",input.delete())}
    }
}
