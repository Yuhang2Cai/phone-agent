// 会话并发与存储失败回归：被拒绝的旧身份不能继续提供凭据，也不能覆盖新的登录。
package com.fandazi.app.feature.auth

import com.fandazi.app.core.network.ApiResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.Assert.*
import org.junit.Test

/** 测试真实身份仓库，仅替换存储延迟和网络认证，所有身份均为合成测试值。 */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionTokenInvalidationTest {
    @Test
    /** 磁盘清理失败仍要停止提供被拒绝的令牌，并显式保留可定位的存储错误。 */
    fun rejectedIdentityFailsClosedWhenStorageClearFails() = runTest {
        val storage = Storage(clearSucceeds = false)
        val repository = SessionRepository(Gateway(), storage)
        repository.invalidateToken("old-test-token")
        assertEquals(SessionState.Unauthenticated, repository.state.value)
        assertNull(repository.tokenOrNull())
        assertEquals(SessionError.StorageClearFailed, repository.error.value)
    }

    @Test
    /** 清理途中发生的新登录必须串行持久化；旧清理完成后不能抹去新令牌。 */
    fun loginWaitsForInFlightInvalidationAndRemainsPersisted() = runTest {
        val storage = Storage(blockClear = true)
        val repository = SessionRepository(Gateway(), storage)
        val invalidation = async { repository.invalidateToken("old-test-token") }
        storage.clearStarted.await()
        val login = async { repository.login("new-owner", "synthetic-password") }
        runCurrent()
        assertFalse(login.isCompleted)
        // 解除仅存储层的延迟，验证真实互斥逻辑，而不是用计时猜测执行先后。
        storage.releaseClear.complete(Unit)
        invalidation.await()
        assertTrue(login.await() is ApiResult.Success)
        assertEquals("new-test-token", repository.tokenOrNull())
        assertEquals("new-test-token", storage.session?.token)
    }

    /** 合成认证响应，不请求外部服务，也不在测试中保存实际密码。 */
    private class Gateway : AuthGateway {
        override suspend fun login(username: String, password: String) =
            ApiResult.Success(AuthResponse("new-test-token", User(username, "synthetic@example.invalid")))
        override suspend fun register(username: String, email: String, password: String) = login(username, password)
    }

    /** 记录实际持久化值并按测试要求阻塞清理，用于验证锁覆盖整个挂起写入过程。 */
    private class Storage(private val clearSucceeds: Boolean = true, private val blockClear: Boolean = false) : SessionStorage {
        var session: PersistedSession? = PersistedSession("old-test-token", User("old-owner", "synthetic@example.invalid"))
        val clearStarted = CompletableDeferred<Unit>()
        val releaseClear = CompletableDeferred<Unit>()
        override val initializationError: Throwable? = null
        override fun read() = Result.success(session)
        override suspend fun write(value: PersistedSession): Boolean { session = value; return true }
        override suspend fun clear(): Boolean {
            clearStarted.complete(Unit)
            if (blockClear) releaseClear.await()
            if (clearSucceeds) session = null
            return clearSucceeds
        }
    }
}
