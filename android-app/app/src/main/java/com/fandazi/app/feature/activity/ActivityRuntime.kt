// 活动运行时：原生持有唯一 RN 实例、身份代次和焦点；业务事实仍由 Spring 返回。
package com.fandazi.app.feature.activity

import com.facebook.react.PackageList
import com.facebook.react.ReactHost
import com.facebook.react.ReactNativeApplicationEntryPoint.loadReactNative
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.WritableMap
import com.facebook.react.defaults.DefaultReactHost
import com.fandazi.app.FandaziApplication
import com.fandazi.app.feature.auth.SessionState
import com.fandazi.app.feature.auth.authenticationIdentity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.IOException

/** 不含个人资料的宿主快照；generation 仅用于拒绝过期回调，不能充当服务端授权。 */
data class ActivitySnapshot(val generation: Int, val authenticated: Boolean, val focused: Boolean, val entryId: Int = 0) {
    /** 只构造身份代次、焦点和测量段引用，不泄露原生身份或令牌。 */
    fun toMap(): WritableMap = Arguments.createMap().apply {
        putInt("generation", generation); putBoolean("authenticated", authenticated); putBoolean("focused", focused);putInt("entryId", entryId)
    }
}

/** 首次打开活动才创建；会话观察与应用同寿命，普通续期保持代次，换号递增。 */
class ActivityRuntime(private val app: FandaziApplication) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var identity = identityOf(app.sessionRepository.state.value)
    private val mutableSnapshot = MutableStateFlow(ActivitySnapshot(1, app.sessionRepository.state.value is SessionState.Authenticated, false))
    val snapshot = mutableSnapshot.asStateFlow()
    private val mutableFailure = MutableStateFlow(false)
    val failure = mutableFailure.asStateFlow()
    private var entryStartedAt: Long? = null
    private var runtimeCount = 0
    // 单条引用使用不参与系统备份的原子文件；没有业务缓存，进程恢复必须再读后端。
    private val pendingFile = android.util.AtomicFile(java.io.File(app.noBackupFilesDir, "activity_pending_reference.json"))
    private val pending = ActivityPendingStore({
        if (pendingFile.baseFile.exists()) pendingFile.readFully().toString(Charsets.UTF_8) else null
    }, {value ->
        if (value == null) {pendingFile.delete();!pendingFile.baseFile.exists()}
        else {
            var stream: java.io.FileOutputStream? = null
            try {stream = pendingFile.startWrite();stream.write(value.toByteArray(Charsets.UTF_8));pendingFile.finishWrite(stream);true}
            catch (_: Exception) {pendingFile.failWrite(stream);false}
        }
    })

    init {
        scope.launch { app.sessionRepository.state.collect { refreshIdentity() } }
    }

    /** 首次访问加载原生库与内置 Bundle；主应用启动不支付 RN 初始化开销。 */
    val host: ReactHost by lazy {
        runtimeCount++
        loadReactNative(app)
        DefaultReactHost.getDefaultReactHost(
            app, PackageList(app).packages.apply { add(ActivityHostPackage()) },
            useDevSupport = false,
            exceptionHandler = { mutableFailure.value = true }
        )
    }

    /** 与凭据解析共用同步边界，避免异步观察滞后时把旧响应当作当前身份。 */
    @Synchronized fun refreshIdentity(): ActivitySnapshot {
        val state = app.sessionRepository.state.value
        val current = identityOf(state)
        if (current != identity) {
            identity = current
            mutableSnapshot.value = mutableSnapshot.value.copy(generation = mutableSnapshot.value.generation + 1, authenticated = state is SessionState.Authenticated)
        }
        return mutableSnapshot.value
    }

    /** 返回发包时执行的校验器；输入为 JS 发起请求时的代次，变化即拒绝发包。 */
    fun credentialFor(generation: Int): () -> String? = {
        synchronized(this) {
            if (refreshIdentity().generation != generation) throw IOException("活动会话已变化")
            // 读取同一会话快照再次核对身份，不能在两个 tokenOrNull 调用之间串账号。
            val state = app.sessionRepository.state.value
            if (identityOf(state) != identity) throw IOException("活动会话已变化")
            (state as? SessionState.Authenticated)?.token
        }
    }

    /** 发包前在同一身份同步边界持久化原请求；失败立即阻止网络写入。 */
    @Synchronized fun rememberPending(generation: Int, action: String, payload: String) {
        check(refreshIdentity().generation == generation && snapshot.value.authenticated) { "活动会话已变化" }
        pending.save(identity, action, payload)
    }
    /** 重建读取只返回本会话引用，身份不匹配时清掉旧引用。 */
    @Synchronized fun pendingReference(generation: Int): String? {
        check(refreshIdentity().generation == generation) { "活动会话已变化" }
        return pending.load(identity)
    }
    /** 回执验证完成后按原键清除，迟到回调不能清除新身份记录。 */
    @Synchronized fun clearPending(generation: Int, requestId: String): Boolean {
        check(refreshIdentity().generation == generation) { "活动会话已变化" }
        return pending.clear(identity, requestId)
    }

    /** 原生页面离开或进入前台时同步焦点，JS 仅在可见状态刷新。 */
    @Synchronized fun setFocused(focused: Boolean) {
        refreshIdentity()
        // 后台返回没有底栏点击时另开一段；采样脚本只选择明确点击产生的段。
        if (focused && !snapshot.value.focused && entryStartedAt == null) beginEntry(android.os.SystemClock.elapsedRealtime())
        mutableSnapshot.value = mutableSnapshot.value.copy(focused = focused)
    }

    /** 原生点击起点；entryId 拒绝上一次挂载的迟到帧回调，时钟不受设备时间调整影响。 */
    @Synchronized fun beginEntry(clickedAt: Long) {
        entryStartedAt = clickedAt
        mutableSnapshot.value = mutableSnapshot.value.copy(entryId = mutableSnapshot.value.entryId + 1)
    }
    /** RN本地结构完成布局并经过两帧后上报；benchmark日志只含耗时和运行时数量。 */
    @Synchronized fun reportInteractive(generation: Int, entryId: Int) {
        val current = refreshIdentity();val start = entryStartedAt ?: return
        if (!current.focused || current.generation != generation || current.entryId != entryId) return
        entryStartedAt = null
        if (com.fandazi.app.BuildConfig.BUILD_TYPE == "benchmark") android.util.Log.i("ActivityPerf",
            "entry=$entryId localInteractiveMs=${android.os.SystemClock.elapsedRealtime()-start} runtimeCount=$runtimeCount")
    }

    /** 仅由原生重试按钮触发重载；不重放任务写入，失败状态继续显式显示。 */
    fun retry() { mutableFailure.value = false; host.reload("活动用户重试") }

    /** 同账号重新登录也以新 sid 隔离；游客与未认证同样是不同身份边界。 */
    private fun identityOf(state: SessionState): String = when (state) {
        is SessionState.Authenticated -> "${state.user.username}:${authenticationIdentity(state)}"
        SessionState.Guest -> "guest"
        SessionState.Unauthenticated -> "unauthenticated"
    }
}
