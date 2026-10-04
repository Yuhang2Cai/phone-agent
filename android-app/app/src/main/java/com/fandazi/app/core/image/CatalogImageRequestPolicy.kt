// 目录图片请求策略：区分公开餐图、受保护资源与显式重试；仅定义稳定身份，不处理业务刷新或授权。
package com.fandazi.app.core.image

/** 调用方依据后端字段所属领域声明访问范围，不能凭URL外形推断私人资源为公开。 */
sealed interface CatalogImageAccess {
    /** 已审核目录、套餐及订单快照中的公开餐图，可跨账户共享同一公开资源缓存。 */
    data object Public : CatalogImageAccess
    /**
     * 私人资源使用非空的账户归属键隔离加载中的请求，并关闭内存与磁盘缓存。
     * accountKey是调用方的账户身份，不传JWT；本策略不代替HTTP鉴权。
     */
    data class Private(val accountKey:String) : CatalogImageAccess {
        init { require(accountKey.isNotBlank()) { "受保护图片必须提供账户归属" } }
    }
}

/** NORMAL是普通绑定；RETRY仅供用户明确点击失败状态，禁止自动循环重试。 */
enum class CatalogImageRequestMode { NORMAL, RETRY }

/** 输入稳定服务器版本、访问归属和请求模式；缺版本沿用URL身份，不生成时间戳。 */
data class CatalogImageRequestOptions(
    val resourceVersion:String?=null,
    val access:CatalogImageAccess=CatalogImageAccess.Public,
    val mode:CatalogImageRequestMode=CatalogImageRequestMode.NORMAL
)

/** 输出Glide附加签名与缓存开关；URL仍由Glide作为资源键的一部分，不在这里替换。 */
data class CatalogImageRequestPlan(val signature:String,val cacheEnabled:Boolean)

/** 纯策略供大图和缩略图共享，实际缓存、取消和状态呈现仍由各自加载器负责。 */
object CatalogImageRequestPolicy {
    /**
     * 公开普通加载启用两级缓存；私人资源或显式重试不读写缓存。
     * 签名使用长度前缀避免账户/版本中的分隔符碰撞，账户还隔离同时加载的同URL任务。
     */
    fun resolve(options:CatalogImageRequestOptions):CatalogImageRequestPlan {
        // 规范化仅去版本外围空白，服务器版本内容不改写；无版本由URL自身承担版本身份。
        val version=options.resourceVersion?.trim().orEmpty()
        val owner=when(val access=options.access) {
            CatalogImageAccess.Public -> "public-catalog"
            is CatalogImageAccess.Private -> "private:${access.accountKey.length}:${access.accountKey}"
        }
        // 普通公开餐图共享稳定键；重试保留同资源身份，仅改变本次缓存读取和写入策略。
        return CatalogImageRequestPlan(
            signature="$owner|${version.length}:$version",
            cacheEnabled=options.access==CatalogImageAccess.Public && options.mode==CatalogImageRequestMode.NORMAL
        )
    }
}
