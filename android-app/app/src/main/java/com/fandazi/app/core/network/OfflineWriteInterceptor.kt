// 离线写门禁：在真正发包前拒绝非只读业务请求，无持久队列或恢复重放。
package com.fandazi.app.core.network
import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException
/** 实时判断由正式Application装配；默认connected沿canWrite便于独立网络测试，不持有凭证。 */
class OfflineWriteInterceptor(private val canWrite:()->Boolean,private val connected:()->Boolean=canWrite):Interceptor {
    /** GET/HEAD允许探测恢复；显式登录只要求实际连接，其它写操作还要求当前目录服务可达。 */
    override fun intercept(chain:Interceptor.Chain):Response {
        val request=chain.request()
        if(request.method!="GET" && request.method!="HEAD") {
            // 登录是用户明确发起且不能产生交易授权；允许其恢复身份，不能据此重放旧订单/报价。
            val permitted=if(request.url.encodedPath.startsWith("/api/auth/"))connected() else canWrite()
            if(!permitted)throw IOException("当前离线，请联网后重新操作")
        }
        return chain.proceed(request)
    }
}
