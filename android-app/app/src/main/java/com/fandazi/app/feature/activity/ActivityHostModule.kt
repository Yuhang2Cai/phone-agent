// RN 宿主桥：白名单请求、原生导航与薄状态事件；令牌、API 基址和账号标识不进入 JS。
package com.fandazi.app.feature.activity

import com.facebook.react.ReactPackage
import com.facebook.react.bridge.*
import com.facebook.react.uimanager.ViewManager
import com.fandazi.app.FandaziApplication
import com.fandazi.app.MainActivity
import com.fandazi.app.core.network.ApiError
import com.fandazi.app.core.network.ApiResult
import kotlinx.coroutines.*
import java.util.UUID

/** 注册唯一宿主模块，不注册额外界面或绕过原生网络的传输模块。 */
class ActivityHostPackage : ReactPackage {
    /** RN 创建上下文时装配桥；参数由 RN 提供，返回仅活动专用模块。 */
    override fun createNativeModules(context: ReactApplicationContext): List<NativeModule> = listOf(ActivityHostModule(context))
    /** 活动视图由 RN/Fabric 渲染，不提供第二套原生视图入口。 */
    override fun createViewManagers(context: ReactApplicationContext): List<ViewManager<*, *>> = emptyList()
}

/** 每个 RN 上下文持有可取消协程域；销毁上下文立即终止未完成回调。 */
class ActivityHostModule(context: ReactApplicationContext) : ReactContextBaseJavaModule(context) {
    private val app = context.applicationContext as FandaziApplication
    private val runtime get() = app.activityRuntime
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var observation: Job? = null
    private var listeners = 0

    /** JS 固定模块名，与 ActivityHost 类型契约对应。 */
    override fun getName() = "ActivityHost"

    /** 获取最新身份代次，不返回业务缓存或认证机密。 */
    @ReactMethod fun getSnapshot(promise: Promise) { promise.resolve(runtime.refreshIdentity().toMap()) }

    /** 生成一次写操作的随机键；重试必须复用原键，不在桥层偷偷替换。 */
    @ReactMethod fun newRequestId(promise: Promise) { promise.resolve(UUID.randomUUID().toString()) }

    /** 只接受当前可见代次的布局帧标识；不允许 JS 上报自选耗时或业务成功结果。 */
    @ReactMethod fun reportInteractive(generation: Double, entryId: Double) {
        if (generation == generation.toInt().toDouble() && entryId == entryId.toInt().toDouble())
            runtime.reportInteractive(generation.toInt(), entryId.toInt())
    }

    /** 读取原请求薄引用；不恢复业务写入，不向 JS 暴露存储身份摘要。 */
    @ReactMethod fun getPending(generation: Double, promise: Promise) {
        scope.launch(Dispatchers.IO) {
            try {
                require(generation == generation.toInt().toDouble())
                val reference = runtime.pendingReference(generation.toInt())
                promise.resolve(reference?.let {json -> Arguments.makeNativeMap(org.json.JSONObject(json).let {root ->
                    val payload = root.getJSONObject("payload")
                    mapOf("action" to root.getString("action"), "payload" to payload.keys().asSequence().associateWith {payload.get(it)})
                })})
            } catch (_: Exception) {promise.reject("LOCAL_STORAGE_ERROR", "活动原请求暂不可读取")}
        }
    }
    /** 仅清除与当前会话及原键一致的记录，不提供任意存储读写能力。 */
    @ReactMethod fun clearPending(requestId: String, generation: Double, promise: Promise) {
        scope.launch(Dispatchers.IO) {
            try {require(generation == generation.toInt().toDouble());promise.resolve(runtime.clearPending(generation.toInt(), requestId))}
            catch (_: Exception) {promise.reject("LOCAL_STORAGE_ERROR", "活动原请求暂不可清除")}
        }
    }

    /** 订阅聚合宿主事件；多个 JS 订阅者共用一个原生观察器。 */
    @ReactMethod fun addListener(event: String) {
        if (event != "ActivityHostChanged") return
        scope.launch {
            listeners++
            if (observation == null) observation = scope.launch {
                runtime.snapshot.collect { reactApplicationContext.emitDeviceEvent("ActivityHostChanged", it.toMap()) }
            }
        }
    }

    /** 最后一个订阅移除即取消观察，避免重挂载导致重复事件。 */
    @ReactMethod fun removeListeners(count: Double) {
        scope.launch { listeners = (listeners - count.toInt()).coerceAtLeast(0); if (listeners == 0) { observation?.cancel(); observation = null } }
    }

    /** 只接受业务动作与有限参数；任意 URL、用户 ID、奖励值或进度输入均被策略拒绝。 */
    @ReactMethod fun request(action: String, payload: String, generation: Double, promise: Promise) {
        val request = try { ActivityRequestPolicy.resolve(action, payload) }
        catch (_: IllegalArgumentException) { promise.reject("INVALID_REQUEST", "活动请求参数无效"); return }
        val expected = generation.toInt()
        if (generation != expected.toDouble() || runtime.refreshIdentity().generation != expected) {
            promise.reject("SESSION_CHANGED", "活动会话已变化"); return
        }
        scope.launch {
            // 先同步保存薄引用再派发POST；磁盘失败不得形成无法恢复的未知写。
            if (request.method != "GET") {
                try {withContext(Dispatchers.IO) {runtime.rememberPending(expected, action, payload)}}
                catch (_: Exception) {promise.reject("LOCAL_STORAGE_ERROR", "请先核查原活动请求");return@launch}
            }
            val credential = runtime.credentialFor(expected)
            val result = if (request.method == "GET") app.apiClient.getBound(request.path, credential)
                else app.apiClient.postJsonBound(request.path, request.body, request.headers, credential)
            // 回包再次核对代次：请求即使在换号前已发出，也不能污染新身份的页面。
            if (runtime.refreshIdentity().generation != expected) { promise.reject("SESSION_CHANGED", "活动会话已变化"); return@launch }
            val response = Arguments.createMap()
            when (result) {
                is ApiResult.Success -> { response.putBoolean("ok", true); response.putInt("status", 200); response.putString("body", result.value) }
                is ApiResult.Failure -> {
                    response.putBoolean("ok", false)
                    val error = result.error
                    response.putInt("status", (error as? ApiError.Http)?.code ?: 0)
                    // 统一执行器提供安全业务提示；网络错误不泄露原始地址或异常详情。
                    response.putString("body", (error as? ApiError.Http)?.message)
                    response.putString("error", if (error == ApiError.Timeout) "TIMEOUT" else "REQUEST_FAILED")
                }
            }
            promise.resolve(response)
        }
    }

    /** 用户显式点击才能调用原生入口；登录成功只恢复活动页，不自动重放领取或领奖。 */
    @ReactMethod fun openDestination(destination: String, generation: Double, promise: Promise) {
        scope.launch {
            if (runtime.refreshIdentity().generation.toDouble() != generation) { promise.reject("SESSION_CHANGED", "活动会话已变化"); return@launch }
            val activity = reactApplicationContext.currentActivity as? MainActivity
            if (activity == null || !runtime.snapshot.value.focused) { promise.reject("NOT_VISIBLE", "活动页当前不可见"); return@launch }
            if (destination !in setOf("login", "home", "orders")) { promise.reject("INVALID_DESTINATION", "不支持的活动入口"); return@launch }
            activity.openActivityDestination(destination)
            promise.resolve(true)
        }
    }

    /** RN 上下文销毁时取消所有请求与事件；协程取消不会被当作成功回执。 */
    override fun invalidate() { scope.cancel(); super.invalidate() }
}
