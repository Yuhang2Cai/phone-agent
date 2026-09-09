package com.fandazi.app.feature.auth

import com.fandazi.app.core.network.ApiError
import com.fandazi.app.core.network.ApiResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthFormProcessorTest {
    @Test
    fun invalidLoginShowsFieldErrorsWithoutSubmitting() = runBlocking {
        val processor = AuthFormProcessor(FakeSessionRepository())

        processor.submitLogin("", "short")

        assertEquals("请输入用户名", processor.state.value.usernameError)
        assertEquals("密码至少需要 8 位", processor.state.value.passwordError)
    }

    @Test
    fun duplicateLoginSubmissionIsIgnoredWhileFirstRequestRuns() = runBlocking {
        val firstRequest = CompletableDeferred<ApiResult<AuthResponse>>()
        val sessions = FakeSessionRepository(loginBlock = { firstRequest.await() })
        val processor = AuthFormProcessor(sessions)

        val first = async { processor.submitLogin("alice", "password123") }
        while (sessions.loginCalls == 0) {
            kotlinx.coroutines.yield()
        }
        processor.submitLogin("alice", "password123")
        firstRequest.complete(ApiResult.Success(AuthResponse("token-1", User("alice", "alice@example.com"))))
        first.await()

        assertEquals(1, sessions.loginCalls)
        assertFalse(processor.state.value.isSubmitting)
        assertEquals(null, processor.state.value.errorMessage)
    }

    @Test
    fun authenticationErrorIsPresentedToForm() = runBlocking {
        val processor = AuthFormProcessor(FakeSessionRepository(loginBlock = {
            ApiResult.Failure(ApiError.Http(400, "Invalid username or password"))
        }))

        processor.submitLogin("alice", "wrong-password")

        assertEquals("用户名或密码错误", processor.state.value.errorMessage)
        assertTrue(!processor.state.value.isSubmitting)
    }

    @Test
    fun duplicateRegistrationShowsConflictMessage() = runBlocking {
        val processor = AuthFormProcessor(FakeSessionRepository(registerBlock = {
            ApiResult.Failure(ApiError.Http(400, "Username is already in use"))
        }))
        processor.setRegisterMode(true)

        processor.submitRegister("alice", "alice@example.com", "password123")

        assertEquals("用户名或邮箱已被使用", processor.state.value.errorMessage)
    }

    @Test
    fun secondSubmissionCannotResetFirstSubmissionState() = runBlocking {
        val firstRequest = CompletableDeferred<ApiResult<AuthResponse>>()
        val sessions = FakeSessionRepository(loginBlock = { firstRequest.await() })
        val processor = AuthFormProcessor(sessions)

        val first = async { processor.submitLogin("alice", "password123") }
        while (sessions.loginCalls == 0) kotlinx.coroutines.yield()
        processor.submitLogin("", "short")

        assertTrue(processor.state.value.isSubmitting)
        firstRequest.complete(ApiResult.Success(AuthResponse("token-1", User("alice", "alice@example.com"))))
        first.await()
    }

    private class FakeSessionRepository(
        private val loginBlock: suspend () -> ApiResult<AuthResponse> = {
            ApiResult.Success(AuthResponse("token-1", User("alice", "alice@example.com")))
        },
        private val registerBlock: suspend () -> ApiResult<AuthResponse> = loginBlock
    ) : SessionActions {
        var loginCalls = 0

        override suspend fun login(username: String, password: String): ApiResult<AuthResponse> {
            loginCalls += 1
            return loginBlock()
        }

        override suspend fun register(username: String, email: String, password: String): ApiResult<AuthResponse> = registerBlock()

        override suspend fun logout() = Unit
    }
}
