// 工具执行边界：按用户身份校验权限后才触发业务操作。
package com.fandazi.feature.agent;

import com.fandazi.feature.DomainException;
import com.fandazi.feature.address.AddressEntity;
import com.fandazi.feature.address.AddressService;
import com.fandazi.feature.automation.OrderAuthorizationService;
import com.fandazi.feature.automation.AutomationPolicyService;
import com.fandazi.feature.cart.CartDraft;
import com.fandazi.feature.cart.CartItemCommand;
import com.fandazi.feature.cart.CartQuote;
import com.fandazi.feature.cart.CartService;
import com.fandazi.feature.catalog.DishEntity;
import com.fandazi.feature.catalog.DishOptionRepository;
import com.fandazi.feature.catalog.DishRepository;
import com.fandazi.feature.catalog.MerchantEntity;
import com.fandazi.feature.catalog.MerchantRepository;
import com.fandazi.feature.catalog.StoreEntity;
import com.fandazi.feature.catalog.StoreReviewEntity;
import com.fandazi.feature.catalog.StoreReviewRepository;
import com.fandazi.feature.catalog.StoreService;
import com.fandazi.feature.catalog.DealPackageEntity;
import com.fandazi.feature.catalog.DealPackageService;
import com.fandazi.feature.order.CreateOrderCommand;
import com.fandazi.feature.order.OrderEntity;
import com.fandazi.feature.order.OrderConflictException;
import com.fandazi.feature.order.OrderRepository;
import com.fandazi.feature.order.OrderResponse;
import com.fandazi.feature.order.OrderService;
import com.fandazi.feature.payment.PaymentService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service public class AgentToolService {
    private static final java.util.Set<String> ALLOWED=java.util.Set.of("search_stores","get_store_detail","list_deal_packages","get_deal_detail","search_reviews","search_merchants","list_dishes","get_last_order","create_cart_draft","validate_cart","get_addresses","create_order","pay_mock_order");
    private final AgentSessionService sessions; private final AgentEventService events; private final MerchantRepository merchants; private final DishRepository dishes; private final DishOptionRepository options; private final AddressService addresses; private final CartService carts; private final OrderAuthorizationService authorizations; private final AutomationPolicyService policies; private final OrderService orders; private final OrderRepository orderRepository; private final PaymentService payments; private final StoreService stores; private final DealPackageService deals; private final StoreReviewRepository storeReviews;
// 初始化对象依赖与默认状态。
    public AgentToolService(AgentSessionService sessions,AgentEventService events,MerchantRepository merchants,DishRepository dishes,DishOptionRepository options,AddressService addresses,CartService carts,OrderAuthorizationService authorizations,AutomationPolicyService policies,OrderService orders,OrderRepository orderRepository,PaymentService payments,StoreService stores,DealPackageService deals,StoreReviewRepository storeReviews){this.sessions=sessions;this.events=events;this.merchants=merchants;this.dishes=dishes;this.options=options;this.addresses=addresses;this.carts=carts;this.authorizations=authorizations;this.policies=policies;this.orders=orders;this.orderRepository=orderRepository;this.payments=payments;this.stores=stores;this.deals=deals;this.storeReviews=storeReviews;}
// 判断allowed。
    public boolean isAllowed(String name){return ALLOWED.contains(name);}
// 处理 `execute` 相关逻辑。
    @Transactional public ToolResult execute(String name,ToolRequest request){
        if(!isAllowed(name))throw new DomainException("工具不在白名单中");
        AgentSession session=sessions.trustedForUpdate(request.sessionId()); Long userId=session.getUser().getId();
        events.record(userId,session.getSessionId(),"tool","正在执行工具",name,"running",Map.of());
        ToolResult result=switch(name){
            case "search_stores" -> searchStores(request.payload()); case "get_store_detail" -> storeDetail(request.payload()); case "list_deal_packages" -> listDeals(request.payload()); case "get_deal_detail" -> dealDetail(request.payload()); case "search_reviews" -> searchReviews(request.payload());
            case "search_merchants" -> search(request.payload()); case "list_dishes" -> listDishes(request.payload()); case "get_last_order" -> lastOrder(userId);
            case "create_cart_draft" -> createDraft(userId,request.payload()); case "validate_cart" -> validate(userId,request.payload()); case "get_addresses" -> getAddresses(userId);
            case "create_order" -> createOrder(userId,session,request); case "pay_mock_order" -> pay(userId,session,request); default -> throw new DomainException("工具不在白名单中");};
        events.record(userId,session.getSessionId(),"tool","工具执行完成",name,"ok",result.data()); return result;
    }
    // 以下五个只读工具都基于 Spring 查询结果构造返回值，模型不能自行生成门店、价格或库存字段。
    private ToolResult searchStores(Map<String,Object> payload){String city=String.valueOf(payload.getOrDefault("cityCode",""));String sort=String.valueOf(payload.getOrDefault("sort","RECOMMENDED"));java.math.BigDecimal lat=decimal(payload.get("latitude"));java.math.BigDecimal lon=decimal(payload.get("longitude"));Integer min=integer(payload.get("minPriceCents"));Integer max=integer(payload.get("maxPriceCents"));return ToolResult.ok(Map.of("stores",stores.search(city,sort,lat,lon,String.valueOf(payload.getOrDefault("category","")),min,max).stream().map(this::storeData).toList()));}
    private ToolResult storeDetail(Map<String,Object> payload){return ToolResult.ok(Map.of("store",storeData(stores.require(String.valueOf(payload.getOrDefault("storeId",""))))));}
    private ToolResult listDeals(Map<String,Object> payload){return ToolResult.ok(Map.of("deals",deals.list(String.valueOf(payload.getOrDefault("storeId","")),String.valueOf(payload.getOrDefault("sort",""))).stream().map(this::dealData).toList()));}
    private ToolResult dealDetail(Map<String,Object> payload){return ToolResult.ok(Map.of("deal",dealData(deals.require(String.valueOf(payload.getOrDefault("dealId",""))))));}
    private ToolResult searchReviews(Map<String,Object> payload){StoreEntity store=stores.require(String.valueOf(payload.getOrDefault("storeId","")));String keyword=String.valueOf(payload.getOrDefault("keyword",""));return ToolResult.ok(Map.of("reviews",storeReviews.findAll().stream().filter(item->item.getStore().getId().equals(store.getId())).filter(item->keyword.isBlank()||item.getContent().contains(keyword)).sorted(java.util.Comparator.comparing(StoreReviewEntity::getCreatedAt).reversed()).limit(20).map(this::reviewData).toList()));}
// 搜索当前数据。
    private ToolResult search(Map<String,Object> payload){String query=String.valueOf(payload.getOrDefault("query","")).trim();List<Map<String,Object>> values=merchants.findAll().stream().filter(m->m.isOpen()&&(query.isBlank()||query.contains(m.getName())||query.contains(m.getCategory())||m.getName().contains(query)||m.getCategory().contains(query)||dishes.findByMerchantId(m.getId()).stream().anyMatch(d->query.contains(d.getName())||d.getName().contains(query)||d.getDescription().contains(query)))).map(this::merchant).toList();return ToolResult.ok(Map.of("merchants",values));}
// 列出dishes。
    private ToolResult listDishes(Map<String,Object> payload){Long merchantId=number(payload,"merchantId");if(merchantId==null)throw new DomainException("缺少商家");return ToolResult.ok(Map.of("dishes",dishes.findByMerchantId(merchantId).stream().filter(DishEntity::isListed).map(this::dish).toList()));}
// 获取addresses。
    private ToolResult getAddresses(Long userId){return ToolResult.ok(Map.of("addresses",addresses.list(userId).stream().map(this::address).toList()));}
// 处理 `lastOrder` 相关逻辑。
    private ToolResult lastOrder(Long userId){OrderEntity order=orderRepository.findFirstByUserIdOrderByIdDesc(userId).orElseThrow(()->new DomainException("没有历史订单"));for(var item:order.getItems()){DishEntity dish=dishes.findById(item.getDishId()).orElseThrow(()->new DomainException("复购菜品已下架"));if(!dish.isListed())throw new DomainException("复购菜品已下架");for(Long optionId:item.getOptionIds()){var option=options.findById(optionId).orElseThrow(()->new DomainException("复购规格已下架"));if(!option.isAvailable()||!option.getDish().getId().equals(dish.getId()))throw new DomainException("复购规格已下架");}}return ToolResult.ok(Map.of("order",orderData(OrderResponse.from(order))));}
// 创建draft。
    private ToolResult createDraft(Long userId,Map<String,Object> payload){MerchantEntity merchant=merchant(payload);CartDraft draft=carts.createDraft(userId,merchant.getId());List<CartItemCommand> items=items(payload);if(items.isEmpty())items=itemsFromText(payload,merchant);carts.replaceItems(userId,draft.getId(),items);Map<String,Object> data=new LinkedHashMap<>();data.put("cartDraftId",draft.getId());data.put("merchantId",merchant.getId());defaultAddress(userId).ifPresent(value->data.put("addressId",value.getId()));return ToolResult.ok(data);}
// 校验当前数据。
    private ToolResult validate(Long userId,Map<String,Object> payload){Long cartId=number(payload,"cartDraftId");if(cartId==null)throw new DomainException("缺少购物车");CartQuote quote=carts.validate(userId,cartId);Long addressId=number(payload,"addressId");if(addressId==null)addressId=defaultAddress(userId).map(AddressEntity::getId).orElseThrow(()->new DomainException("没有默认地址"));addresses.owned(userId,addressId);return ToolResult.ok(quoteData(quote,addressId,cartId));}
// 创建订单。
    private ToolResult createOrder(Long userId,AgentSession session,ToolRequest request){Long cartId=number(request.payload(),"cartDraftId");Long addressId=number(request.payload(),"addressId");if(cartId==null||addressId==null)throw new DomainException("订单参数不完整");String key="agent:"+session.getSessionId()+":"+requireKey(request.idempotencyKey());var existing=orders.findByIdempotencyKey(userId,key);if(existing.isPresent()){String fingerprint=cartId+":"+addressId;if(!fingerprint.equals(existing.get().getRequestFingerprint()))throw new OrderConflictException("幂等键对应不同请求");return ToolResult.ok(Map.of("order",orderData(OrderResponse.from(existing.get()))));}String workflowId=request.workflowId()==null?session.getTraceId():request.workflowId();sessions.bindWorkflow(session,workflowId);authorizations.authorize(userId,session,cartId,addressId,workflowId);OrderEntity order=orders.create(userId,new CreateOrderCommand(cartId,addressId,key));sessions.bindCreatedOrder(session,order.getId(),workflowId);policies.bindTaskGrant(userId,session.getSessionId(),workflowId,cartId,order.getId());if(session.getMode()==AgentMode.CONFIRM)sessions.consumeConfirmation(session);return ToolResult.ok(Map.of("order",orderData(OrderResponse.from(order))));}
// 支付当前数据。
    private ToolResult pay(Long userId,AgentSession session,ToolRequest request){if(session.getMode()!=AgentMode.THIS_TASK_AUTO)throw new DomainException("当前会话未获自动支付授权");Long orderId=number(request.payload(),"orderId");if(orderId==null)throw new DomainException("缺少订单");String key="agent:"+session.getSessionId()+":"+requireKey(request.idempotencyKey());OrderEntity already=payments.alreadyPaid(userId,orderId,key);if(already!=null)return ToolResult.ok(Map.of("order",orderData(OrderResponse.from(already))));String workflowId=request.workflowId()==null?session.getTraceId():request.workflowId();OrderEntity order=orders.get(userId,orderId);authorizations.authorizePayment(userId,session,orderId,workflowId,order.getTotal());OrderResponse paid=payments.payResponse(userId,orderId,key);policies.consumeTaskGrant(userId,session.getSessionId(),workflowId,orderId);sessions.settleCreatedOrder(session);return ToolResult.ok(Map.of("order",orderData(paid)));}
// 处理 `merchant` 相关逻辑。
    private MerchantEntity merchant(Map<String,Object> payload){Long id=number(payload,"merchantId");if(id!=null)return merchants.findById(id).filter(MerchantEntity::isOpen).orElseThrow(()->new DomainException("商家不存在或未营业"));String text=String.valueOf(payload.getOrDefault("text",payload.getOrDefault("query",""))).trim();if(text.isBlank())throw new DomainException("缺少明确商家");List<MerchantEntity> matches=merchants.findAll().stream().filter(MerchantEntity::isOpen).filter(m->text.contains(m.getName())).toList();if(matches.size()!=1)throw new DomainException(matches.isEmpty()?"缺少明确商家":"商家不明确");return matches.get(0);}
// 处理 `items` 相关逻辑。
    private List<CartItemCommand> items(Map<String,Object> payload){Object raw=payload.get("items");if(!(raw instanceof List<?> list))return List.of();List<CartItemCommand> result=new ArrayList<>();for(Object item:list){if(!(item instanceof Map<?,?> map))throw new DomainException("购物车项目无效");Long dishId=number(map,"dishId");Long quantity=number(map,"quantity");if(dishId==null||quantity==null)throw new DomainException("购物车项目无效");List<Long> options=((map.get("optionIds") instanceof List<?> values)?values:List.of()).stream().map(v->number(Map.of("x",v),"x")).toList();result.add(new CartItemCommand(dishId,Math.toIntExact(quantity),options,null));}return result;}
// 处理 `itemsFromText` 相关逻辑。
    private List<CartItemCommand> itemsFromText(Map<String,Object> payload,MerchantEntity merchant){String text=String.valueOf(payload.getOrDefault("text","")).trim();if(text.isBlank())throw new DomainException("缺少明确菜品");List<DishEntity> matches=dishes.findByMerchantId(merchant.getId()).stream().filter(DishEntity::isListed).filter(d->text.contains(d.getName())).toList();if(matches.size()!=1)throw new DomainException(matches.isEmpty()?"缺少明确菜品":"菜品不明确");return List.of(new CartItemCommand(matches.get(0).getId(),1,List.of(),null));}
// 处理 `defaultAddress` 相关逻辑。
    private java.util.Optional<AddressEntity> defaultAddress(Long userId){return addresses.list(userId).stream().filter(AddressEntity::isDefaultAddress).findFirst();}
// 处理 `number` 相关逻辑。
    private Long number(Map<?,?> data,String name){Object value=data.get(name);if(value instanceof Number number)return number.longValue();if(value instanceof String text&&!text.isBlank())try{return Long.valueOf(text);}catch(NumberFormatException ignored){}return null;}
// 处理 `requireKey` 相关逻辑。
    private String requireKey(String key){if(key==null||key.isBlank())throw new DomainException("缺少幂等键");return key;}
// 处理 `merchant` 相关逻辑。
    private Map<String,Object> merchant(MerchantEntity m){return Map.of("id",m.getId(),"name",m.getName(),"category",m.getCategory(),"open",m.isOpen(),"minimumOrder",m.getMinimumOrder(),"deliveryFee",m.getDeliveryFee());}
    private Map<String,Object> storeData(StoreEntity s){Map<String,Object> data=new LinkedHashMap<>();data.put("publicId",s.getPublicId());data.put("name",s.getName());data.put("brandName",s.getBrandName());data.put("cityCode",s.getCityCode());data.put("address",s.getAddress());data.put("category",s.getCategory());data.put("averageSpendCents",s.getAverageSpendCents());data.put("latitude",s.getLatitude());data.put("longitude",s.getLongitude());data.put("open",s.isOpen());data.put("openingHours",s.getOpeningHours());return data;}
    private Map<String,Object> dealData(DealPackageEntity d){return Map.of("publicId",d.getPublicId(),"storeId",d.getStore().getPublicId(),"title",d.getTitle(),"description",d.getDescription(),"priceCents",d.getPriceCents(),"originalPriceCents",d.getOriginalPriceCents(),"inventory",d.getInventory(),"validFrom",d.getValidFrom(),"validTo",d.getValidTo(),"purchaseLimit",d.getPurchaseLimit());}
    private Map<String,Object> reviewData(StoreReviewEntity r){return Map.of("storeId",r.getStore().getPublicId(),"reviewerName",r.getReviewerName(),"rating",r.getRating(),"content",r.getContent(),"createdAt",r.getCreatedAt());}
// 处理 `dish` 相关逻辑。
    private Map<String,Object> dish(DishEntity d){return Map.of("id",d.getId(),"merchantId",d.getMerchant().getId(),"name",d.getName(),"price",d.getPrice(),"stock",d.getStock());}
// 处理 `address` 相关逻辑。
    private Map<String,Object> address(AddressEntity a){return Map.of("id",a.getId(),"recipient",a.getRecipient(),"phone",a.getPhone(),"detail",a.getDetail(),"defaultAddress",a.isDefaultAddress());}
// 处理 `quoteData` 相关逻辑。
    private Map<String,Object> quoteData(CartQuote q,Long addressId,Long cartId){Map<String,Object> data=new LinkedHashMap<>();data.put("valid",true);data.put("cartDraftId",cartId);data.put("addressId",addressId);data.put("total",q.total());data.put("subtotal",q.subtotal());data.put("deliveryFee",q.deliveryFee());data.put("merchantId",q.merchantId());return data;}
// 处理 `orderData` 相关逻辑。
    private Map<String,Object> orderData(OrderResponse order){return Map.of("id",order.id(),"status",order.status().name(),"total",order.total(),"address",order.detail());}
    private java.math.BigDecimal decimal(Object value){try{return value==null?null:new java.math.BigDecimal(String.valueOf(value));}catch(NumberFormatException ignored){return null;}}
    private Integer integer(Object value){try{return value==null?null:Integer.valueOf(String.valueOf(value));}catch(NumberFormatException ignored){return null;}}
}
