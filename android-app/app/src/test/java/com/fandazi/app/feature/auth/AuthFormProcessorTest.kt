// 身份认证模块：验证关键行为与边界条件。
package com.fandazi.app.feature.auth

import com.fandazi.app.core.network.ApiError
import com.fandazi.app.core.network.ApiResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthFormProcessorTest {
    @Test
    fun draftFieldsAreRestoredByTheAuthViewModelSeam() {
        val processor = AuthFormProcessor(FakeSessionRepository())

        processor.updateDraft("alice", "alice@example.com", "password123")

        assertEquals("alice", processor.state.value.username)
        assertEquals("alice@example.com", processor.state.value.email)
        assertEquals("password123", processor.state.value.password)
    }

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
    fun parameterErrorIsNotPresentedAsCredentialError() = runBlocking {
        val processor = AuthFormProcessor(FakeSessionRepository(loginBlock = {
            ApiResult.Failure(ApiError.Http(400, "email must be valid"))
        }))

        processor.submitLogin("alice", "password123")

        assertEquals("请求参数有误，请检查后重试", processor.state.value.errorMessage)
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

    @Test
    fun changingModeWhileSubmittingPreservesSubmittingState() = runBlocking {
        val request = CompletableDeferred<ApiResult<AuthResponse>>()
        val processor = AuthFormProcessor(FakeSessionRepository(loginBlock = { request.await() }))

        val first = async { processor.submitLogin("alice", "password123") }
        while (!processor.state.value.isSubmitting) kotlinx.coroutines.yield()

        processor.setRegisterMode(true)

        assertTrue(processor.state.value.isSubmitting)
        assertTrue(processor.state.value.isRegisterMode)
        request.complete(ApiResult.Success(AuthResponse("token-1", User("alice", "alice@example.com"))))
        first.await()
    }

    @Test
    fun cancellationFromSessionRequestIsRethrown() = runBlocking {
        val cancellation = CancellationException("test cancellation")
        val processor = AuthFormProcessor(FakeSessionRepository(loginBlock = { throw cancellation }))

        try {
            processor.submitLogin("alice", "password123")
            throw AssertionError("Expected cancellation")
        } catch (actual: CancellationException) {
            assertEquals(cancellation, actual)
        }
    }

    @Test
    fun cancellationReleasesLockAndAllowsAnotherSubmission() = runBlocking {
        val cancellation = CancellationException("first request cancelled")
        lateinit var sessions: FakeSessionRepository
        sessions = FakeSessionRepository(loginBlock = {
            if (sessions.loginCalls == 1) throw cancellation
            ApiResult.Success(AuthResponse("token-2", User("alice", "alice@example.com")))
        })
        val processor = AuthFormProcessor(sessions)

        try {
            processor.submitLogin("alice", "password123")
            throw AssertionError("Expected cancellation")
        } catch (actual: CancellationException) {
            assertEquals(cancellation, actual)
        }
        assertFalse(processor.state.value.isSubmitting)

        processor.submitLogin("alice", "password123")

        assertEquals(2, sessions.loginCalls)
        assertFalse(processor.state.value.isSubmitting)
        assertEquals(null, processor.state.value.errorMessage)
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
