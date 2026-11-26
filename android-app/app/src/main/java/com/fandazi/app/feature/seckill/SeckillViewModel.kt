// 秒杀页面状态宿主：旋转时保留流程，账号身份变化时撤销旧确认，生命周期恢复只读。
package com.fandazi.app.feature.seckill

import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import com.fandazi.app.FandaziApplication
import com.fandazi.app.feature.auth.SessionState
import com.fandazi.app.feature.auth.authenticationIdentity
import java.security.MessageDigest
import java.util.UUID

/** 依赖应用唯一认证客户端，避免创建第二套登录或后台轮询服务。 */
class SeckillViewModel(application:Application):AndroidViewModel(application) {
    private val app=application as FandaziApplication
    /** 页面与底部确认共享同一流程，配置变化不会产生新的请求键。 */
    val flow=SeckillFlow(HttpSeckillRepository(app.apiClient),SeckillRecoveryFile(app),SystemClock::elapsedRealtime){UUID.randomUUID().toString()}
    private var identity:String?=null
    private var resource:String?=null
    /** 切换登录会话需重新读取；同一sid的JWT轮换不清掉已在途原请求。 */
    suspend fun enter(activityId:String,state:SessionState,orderId:String?=null) {
        val key=authenticationIdentity(state)
        val target="$activityId:${orderId.orEmpty()}"
        if(resource==target && identity==key){flow.refresh();return}
        resource=target;identity=key
        val user=state as? SessionState.Authenticated
        // 只把摘要用于本机隔离，不记录邮箱、JWT或明文用户名；服务端仍逐次执行真正鉴权。
        val account=user?.let {MessageDigest.getInstance("SHA-256").digest(it.user.username.toByteArray(Charsets.UTF_8)).joinToString(""){b->"%02x".format(b)}}
        flow.open(activityId,account,key,orderId)
    }
}
