// 身份响应契约：昵称必须跟随真实后端响应和加密会话序列化保留，不由页面凭本地输入假造。
package com.fandazi.app.feature.auth

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Test

/** 非UI契约测试，不测试布局，也不替代最新APK/Figma与真实注册验收。 */
class AuthNicknameContractTest {
    /** 模拟协议输入后再序列化会话，防止DTO字段遗漏导致注册后昵称丢失。 */
    @Test fun backendNicknameSurvivesSessionSerialization() {
        val gson = Gson()
        val response = gson.fromJson("""{"token":"synthetic-token","user":{"username":"synthetic","email":"synthetic@local.fandazi","nickname":"测试食客"}}""", AuthResponse::class.java)
        val persisted = PersistedSession(response.token, response.user)
        val saved = gson.toJsonTree(persisted).asJsonObject.getAsJsonObject("user")
        assertEquals("测试食客", saved.get("nickname")?.asString)
    }
}
