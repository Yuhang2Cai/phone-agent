// 公开缓存编码器：只接收五种目录DTO，逐字段白名单裁剪/校验；从不保存原HTTP正文或私人对象。
package com.fandazi.app.core.cache
import com.fandazi.app.feature.store.*
import com.fandazi.app.feature.deal.*
import com.google.gson.*
/** 唯一生产编码集合；未来DTO即使增加私人字段也不会自动落盘。 */
object PublicCatalogCacheCodecs {
    private val gson=Gson()
    private val dealFields=setOf("publicId","storePublicId","title","description","priceCents","originalPriceCents","inventory","validFrom","validTo","active","purchaseLimit","imageUrl","imageAlt","peopleCount","contents","usageRules","storeName")
    private val storeFields=setOf("publicId","name","cityCode","category","averageSpendCents","address","openingHours","rating","reviewCount","reviewDistribution","imageUrl","imageAlt","featuredDeal")
    private val dishFields=setOf("publicId","name","description","priceCents","imageUrl")
    private val reviewFields=setOf("publicId","storePublicId","reviewerName","rating","content","createdAt")
    private val cityFields=setOf("cityCode","name")
    /** 编码仅保留白名单；解码拒绝多余字段，不让损坏/外来私人JSON被静默忽略。 */
    private fun project(value:JsonObject,fields:Set<String>,decoding:Boolean):JsonObject {
        if(decoding)require(value.keySet().all{it in fields}){"公开缓存包含禁止字段"}
        return JsonObject().apply{fields.forEach{key->value.get(key)?.let{add(key,it.deepCopy())}}}
    }
    /** 嵌套套餐和内容也逐层限制，不能只裁剪外层而把内部账户字段保存。 */
    private fun dealObject(value:JsonObject,decoding:Boolean):JsonObject=project(value,dealFields,decoding).apply {
        get("contents")?.takeUnless{it.isJsonNull}?.let{array->add("contents",JsonArray().apply{array.asJsonArray.forEach{add(project(it.asJsonObject,setOf("name","quantity"),decoding))}})}
    }
    /** 明确类型构造有序编解码；公开ID与集合ID不一致直接拒绝。 */
    private fun <T:Any> list(type:Class<T>,fields:Set<String>,identity:(T)->String,validate:(T)->Unit={},transform:(JsonObject,Boolean)->JsonObject={v,d->project(v,fields,d)}):CatalogCacheCodec<List<T>> = object:CatalogCacheCodec<List<T>> {
        override fun encode(value:List<T>):List<Pair<String,String>> = value.map{validate(it);identity(it) to transform(gson.toJsonTree(it).asJsonObject,false).toString()}
        override fun decode(rows:List<Pair<String,String>>):List<T> = rows.map{(id,payload)->
            val json=transform(JsonParser.parseString(payload).asJsonObject,true)
            // 必需公开ID不能靠Gson缺省值猜出；成员身份与原集合顺序一起校验。
            val item=gson.fromJson(json,type);require(identity(item)==id){"公开缓存身份不一致"};validate(item);item
        }
    }
    /** 门店排除distanceMeters；嵌套套餐白名单保留已确认未知/null语义。 */
    val stores:CatalogCacheCodec<List<CatalogStore>> = list(
        CatalogStore::class.java,storeFields,{it.publicId},
        {store->
            CatalogRowIdentity.publicId(store.publicId)
            require(store.name.isNotBlank() && store.averageSpendCents>=0 && store.reviewCount>=0){"公开门店字段无效"}
            store.featuredDeal?.let{DealDetailValidation.fields(it);require(it.storePublicId==store.publicId){"公开门店套餐归属无效"}}
        },
        {value,decoding->
            project(value,storeFields,decoding).apply {
                get("featuredDeal")?.takeUnless{it.isJsonNull}?.let{add("featuredDeal",dealObject(it.asJsonObject,decoding))}
            }
        }
    )
    /** 套餐含价格/库存快照但不含购买资格、订单ID或确认凭证。 */
    val deals:CatalogCacheCodec<List<DealPackage>> = list(DealPackage::class.java,dealFields,{it.publicId},{DealDetailValidation.fields(it)},{v,d->dealObject(v,d)})
    /** 菜品只有公开展示字段，无加购、外卖或私人购买信息。 */
    val dishes:CatalogCacheCodec<List<CatalogDish>> = list(CatalogDish::class.java,dishFields,{it.publicId},{CatalogRowIdentity.publicId(it.publicId);require(it.name.isNotBlank() && it.priceCents>=0){"公开菜品字段无效"}})
    /** 评价仅存公开文字摘要及原门店归属，不接受用户ID或私人反馈对象。 */
    val reviews:CatalogCacheCodec<List<CatalogReview>> = list(CatalogReview::class.java,reviewFields,{it.publicId},{CatalogRowIdentity.reviews(listOf(it),it.storePublicId)})
    /** 城市代码为目录公开身份，不能从定位或设备默认值生成。 */
    val cities:CatalogCacheCodec<List<CatalogCity>> = list(CatalogCity::class.java,cityFields,{it.cityCode},{require(it.cityCode.isNotBlank() && it.name.isNotBlank()){"公开城市字段无效"}})
    /** 单资源必须恰好一个原ID，空集合/多成员不能借首项冒充详情。 */
    fun <T> single(list:CatalogCacheCodec<List<T>>):CatalogCacheCodec<T> = object:CatalogCacheCodec<T> {
        override fun encode(value:T)=list.encode(listOf(value))
        override fun decode(rows:List<Pair<String,String>>):T=list.decode(rows).single()
    }
}
