// 自动续期状态机回归：真实身份仓库配可控网络和存储，覆盖并发、进程重建、迟到与落盘失败。
package com.fandazi.app.feature.auth
import com.fandazi.app.core.network.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

/** 固定合成期限和身份，无实际密码、录音、业务请求或设备依赖。 */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionRenewalTest {
    private val user=User("synthetic","synthetic@example.invalid")
    /** 所有回归使用明确即将到期的合成凭据，避免测试依赖壁钟等待。 */
    private fun old()=PersistedSession("old-access",user,"old-session","old-refresh",110_000L,9_000_000L)
    /** 成功响应保留会话身份并轮换秘密，到期时间超出一分钟预刷新窗口。 */
    private fun renewed()=AuthResponse("new-access",user,"old-session","new-refresh",500_000L,9_100_000L)

    /** 无需续期时不访问认证网关；重建仓库直接读取完整加密包对应的值。 */
    @Test fun validSessionRestoresWithoutRefresh()=runTest {
        val storage=Storage(old().copy(accessExpiresAtEpochMs=500_000L));val gateway=Gateway(renewed())
        val repo=SessionRepository(gateway,storage){100_000L}
        assertEquals(ApiResult.Success("old-access"),repo.renewIfNeeded());assertEquals(0,gateway.calls)
        assertEquals("old-session",(repo.state.value as SessionState.Authenticated).sessionId)
    }
    /** 并发调用只发一个真实续期，预先保存请求标识，JWT变化不改变导航身份。 */
    @Test fun concurrentRequestsShareOneRefreshAndKeepIdentity()=runTest {
        val storage=Storage(old());val gate=CompletableDeferred<Unit>();val gateway=Gateway(renewed(),gate)
        val repo=SessionRepository(gateway,storage){100_000L};val identity=authenticationIdentity(repo.state.value)
        val jobs=(1..20).map{async{repo.renewIfNeeded()}};runCurrent()
        assertEquals(1,gateway.calls);assertNotNull(storage.value?.pendingRefreshRequestId)
        gate.complete(Unit);jobs.forEach{assertEquals(ApiResult.Success("new-access"),it.await())}
        assertEquals(identity,authenticationIdentity(repo.state.value));assertNull(storage.value?.pendingRefreshRequestId)
        assertEquals("new-refresh",storage.value?.refreshToken)
    }
    /** 网络失败保留旧包；新进程重建后使用同一落盘标识恢复，不新建一轮请求。 */
    @Test fun networkFailureSurvivesProcessRecreationWithSameRequestId()=runTest {
        val storage=Storage(old());val fail=Gateway(renewed()).apply{failure=ApiError.Network("合成断网")}
        val repo=SessionRepository(fail,storage){100_000L};assertTrue(repo.renewIfNeeded() is ApiResult.Failure)
        val key=storage.value?.pendingRefreshRequestId;assertNotNull(key);assertEquals("old-access",repo.tokenOrNull())
        val success=Gateway(renewed());val restored=SessionRepository(success,storage){100_000L}
        assertEquals(ApiResult.Success("new-access"),restored.renewIfNeeded());assertEquals(key,success.keys.single())
    }
    /** 刷新响应写失败时不能只更新内存JWT，旧包与同一请求标识必须可以稍后恢复。 */
    @Test fun failedResponsePersistenceRetainsRecoverablePackage()=runTest {
        var time=100_000L;val storage=Storage(old()).apply{failWriteNumber=2};val gateway=Gateway(renewed())
        val repo=SessionRepository(gateway,storage){time}
        assertEquals(ApiResult.Failure(ApiError.Storage),repo.renewIfNeeded());assertEquals("old-access",repo.tokenOrNull())
        val key=storage.value?.pendingRefreshRequestId;time+=4_000L;storage.failWriteNumber=null
        assertEquals(ApiResult.Success("new-access"),repo.renewIfNeeded());assertEquals(listOf(key,key),gateway.keys)
    }
    /** 预先落盘失败时不发送刷新请求，更不能发生业务请求；抛出的存储异常也需可恢复。 */
    @Test fun pendingWriteExceptionNeverCallsServer()=runTest {
        val storage=Storage(old()).apply{throwWrite=true};val gateway=Gateway(renewed());val repo=SessionRepository(gateway,storage){100_000L}
        assertEquals(ApiResult.Failure(ApiError.Storage),repo.renewIfNeeded());assertEquals(0,gateway.calls);assertEquals("old-access",repo.tokenOrNull())
    }
    /** 续期等待期间退出先完成，迟到成功不能恢复已退出身份，在线撤销使用旧会话秘密。 */
    @Test fun logoutWinsOverLateRefresh()=runTest {
        val storage=Storage(old());val gate=CompletableDeferred<Unit>();val gateway=Gateway(renewed(),gate)
        val repo=SessionRepository(gateway,storage){100_000L};val job=async{repo.renewIfNeeded()};runCurrent()
        repo.logout();assertNull(repo.tokenOrNull());assertEquals(listOf("old-refresh"),gateway.revoked)
        gate.complete(Unit);assertEquals(ApiResult.Success<String?>(null),job.await());assertEquals(SessionState.Unauthenticated,repo.state.value)
    }
    /** 明确换账号/重登取得新sid，旧续期及旧401均不能覆盖或清掉新身份。 */
    @Test fun newLoginWinsOverLateRefreshAndUnauthorized()=runTest {
        val storage=Storage(old());val gate=CompletableDeferred<Unit>();val gateway=Gateway(renewed(),gate)
        gateway.loginResponse=renewed().copy(token="explicit-access",sessionId="explicit-session",refreshToken="explicit-refresh")
        val repo=SessionRepository(gateway,storage){100_000L};val job=async{repo.renewIfNeeded()};runCurrent()
        repo.login("synthetic","synthetic-password");gate.complete(Unit);job.await();repo.invalidateToken("old-access")
        assertEquals("explicit-access",repo.tokenOrNull());assertEquals("explicit-session",authenticationIdentity(repo.state.value))
    }
    /** 只有服务端明确401才注销；即使磁盘清除失败也禁止继续提供被拒绝身份。 */
    @Test fun refreshUnauthorizedClearsIdentityDespiteStorageFailure()=runTest {
        val storage=Storage(old()).apply{clearWorks=false};val gateway=Gateway(renewed()).apply{failure=ApiError.Http(401,"合成拒绝")}
        val repo=SessionRepository(gateway,storage){100_000L};repo.renewIfNeeded();assertNull(repo.tokenOrNull());assertEquals(SessionError.StorageClearFailed,repo.error.value)
    }
    /** 服务故障与错归属成功包不能伪装认证失效，也不能发布非法新身份。 */
    @Test fun serverFailureAndWrongIdentityPreserveOldSession()=runTest {
        for(gateway in listOf(Gateway(renewed()).apply{failure=ApiError.Http(503,"合成故障")},Gateway(renewed().copy(sessionId="wrong-session")))) {
            val repo=SessionRepository(gateway,Storage(old())){100_000L}
            assertTrue(repo.renewIfNeeded() is ApiResult.Failure);assertEquals("old-access",repo.tokenOrNull())
        }
    }
    /** 多个断网等待者共享短暂失败，不把GET网络重试转换为大量认证请求。 */
    @Test fun concurrentNetworkFailureIsCoalesced()=runTest {
        val gateway=Gateway(renewed()).apply{failure=ApiError.Network("合成断网")};val repo=SessionRepository(gateway,Storage(old())){100_000L}
        (1..20).map{async{repo.renewIfNeeded()}}.awaitAll();assertEquals(1,gateway.calls)
    }
    /** 记录实际写次数，用精确失败位置检查“预先标识”和“成功包”两次写入的边界。 */
    private class Storage(var value:PersistedSession?):SessionStorage {
        override val initializationError:Throwable?=null
        var writes=0;var failWriteNumber:Int?=null;var throwWrite=false;var clearWorks=true
        override fun read()=Result.success(value)
        override suspend fun write(value:PersistedSession):Boolean {writes++;if(throwWrite)throw IllegalStateException("合成存储故障");if(writes==failWriteNumber)return false;this.value=value;return true}
        override suspend fun clear():Boolean {if(clearWorks)value=null;return clearWorks}
    }
    /** 网络门可精确控制响应迟到；刷新键和撤销均仅使用合成凭据。 */
    private class Gateway(val response:AuthResponse,val gate:CompletableDeferred<Unit>?=null):AuthGateway {
        var calls=0;var failure:ApiError?=null;var loginResponse=response;val keys=mutableListOf<String>();val revoked=mutableListOf<String>()
        override suspend fun refresh(refreshToken:String,requestId:String):ApiResult<AuthResponse> {calls++;keys+=requestId;gate?.await();return failure?.let{ApiResult.Failure(it)} ?: ApiResult.Success(response)}
        override suspend fun revoke(refreshToken:String):ApiResult<Unit> {revoked+=refreshToken;return ApiResult.Success(Unit)}
        override suspend fun login(username:String,password:String)=ApiResult.Success(loginResponse)
        override suspend fun register(username:String,email:String,password:String)=login(username,password)
    }
}
