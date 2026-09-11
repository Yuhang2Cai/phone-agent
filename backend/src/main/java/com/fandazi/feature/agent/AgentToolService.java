package com.fandazi.feature.agent;

import com.fandazi.feature.DomainException;
import com.fandazi.feature.address.AddressEntity;
import com.fandazi.feature.address.AddressService;
import com.fandazi.feature.automation.OrderAuthorizationService;
import com.fandazi.feature.cart.CartDraft;
import com.fandazi.feature.cart.CartItemCommand;
import com.fandazi.feature.cart.CartQuote;
import com.fandazi.feature.cart.CartService;
import com.fandazi.feature.catalog.DishEntity;
import com.fandazi.feature.catalog.DishOptionRepository;
import com.fandazi.feature.catalog.DishRepository;
import com.fandazi.feature.catalog.MerchantEntity;
import com.fandazi.feature.catalog.MerchantRepository;
import com.fandazi.feature.order.CreateOrderCommand;
import com.fandazi.feature.order.OrderEntity;
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
    private static final java.util.Set<String> ALLOWED=java.util.Set.of("search_merchants","list_dishes","get_last_order","create_cart_draft","validate_cart","get_addresses","create_order","pay_mock_order");
    private final AgentSessionService sessions; private final AgentEventService events; private final MerchantRepository merchants; private final DishRepository dishes; private final DishOptionRepository options; private final AddressService addresses; private final CartService carts; private final OrderAuthorizationService authorizations; private final OrderService orders; private final OrderRepository orderRepository; private final PaymentService payments;
    public AgentToolService(AgentSessionService sessions,AgentEventService events,MerchantRepository merchants,DishRepository dishes,DishOptionRepository options,AddressService addresses,CartService carts,OrderAuthorizationService authorizations,OrderService orders,OrderRepository orderRepository,PaymentService payments){this.sessions=sessions;this.events=events;this.merchants=merchants;this.dishes=dishes;this.options=options;this.addresses=addresses;this.carts=carts;this.authorizations=authorizations;this.orders=orders;this.orderRepository=orderRepository;this.payments=payments;}
    public boolean isAllowed(String name){return ALLOWED.contains(name);}
    @Transactional public ToolResult execute(String name,ToolRequest request){
        if(!isAllowed(name))throw new DomainException("工具不在白名单中");
        AgentSession session=sessions.trusted(request.sessionId()); Long userId=session.getUser().getId();
        events.record(userId,session.getSessionId(),"tool","正在执行工具",name,"running",Map.of());
        ToolResult result=switch(name){
            case "search_merchants" -> search(request.payload()); case "list_dishes" -> listDishes(request.payload()); case "get_last_order" -> lastOrder(userId);
            case "create_cart_draft" -> createDraft(userId,request.payload()); case "validate_cart" -> validate(userId,request.payload()); case "get_addresses" -> getAddresses(userId);
            case "create_order" -> createOrder(userId,session,request); case "pay_mock_order" -> pay(userId,session,request); default -> throw new DomainException("工具不在白名单中");};
        events.record(userId,session.getSessionId(),"tool","工具执行完成",name,"ok",result.data()); return result;
    }
    private ToolResult search(Map<String,Object> payload){String query=String.valueOf(payload.getOrDefault("query","")).trim();List<Map<String,Object>> values=merchants.findAll().stream().filter(m->m.isOpen()&&(query.isBlank()||m.getName().contains(query)||m.getCategory().contains(query))).map(this::merchant).toList();return ToolResult.ok(Map.of("merchants",values));}
    private ToolResult listDishes(Map<String,Object> payload){Long merchantId=number(payload,"merchantId");if(merchantId==null)throw new DomainException("缺少商家");return ToolResult.ok(Map.of("dishes",dishes.findByMerchantId(merchantId).stream().filter(DishEntity::isListed).map(this::dish).toList()));}
    private ToolResult getAddresses(Long userId){return ToolResult.ok(Map.of("addresses",addresses.list(userId).stream().map(this::address).toList()));}
    private ToolResult lastOrder(Long userId){OrderEntity order=orderRepository.findFirstByUserIdOrderByIdDesc(userId).orElseThrow(()->new DomainException("没有历史订单"));for(var item:order.getItems()){DishEntity dish=dishes.findById(item.getDishId()).orElseThrow(()->new DomainException("复购菜品已下架"));if(!dish.isListed())throw new DomainException("复购菜品已下架");for(Long optionId:item.getOptionIds()){var option=options.findById(optionId).orElseThrow(()->new DomainException("复购规格已下架"));if(!option.isAvailable()||!option.getDish().getId().equals(dish.getId()))throw new DomainException("复购规格已下架");}}return ToolResult.ok(Map.of("order",orderData(OrderResponse.from(order))));}
    private ToolResult createDraft(Long userId,Map<String,Object> payload){MerchantEntity merchant=merchant(payload);CartDraft draft=carts.createDraft(userId,merchant.getId());List<CartItemCommand> items=items(payload);if(items.isEmpty()){DishEntity dish=dishes.findByMerchantId(merchant.getId()).stream().filter(DishEntity::isListed).findFirst().orElseThrow(()->new DomainException("商家没有可用菜品"));items=List.of(new CartItemCommand(dish.getId(),1,List.of(),null));}carts.replaceItems(userId,draft.getId(),items);Map<String,Object> data=new LinkedHashMap<>();data.put("cartDraftId",draft.getId());data.put("merchantId",merchant.getId());defaultAddress(userId).ifPresent(value->data.put("addressId",value.getId()));return ToolResult.ok(data);}
    private ToolResult validate(Long userId,Map<String,Object> payload){Long cartId=number(payload,"cartDraftId");if(cartId==null)throw new DomainException("缺少购物车");CartQuote quote=carts.validate(userId,cartId);Long addressId=number(payload,"addressId");if(addressId==null)addressId=defaultAddress(userId).map(AddressEntity::getId).orElseThrow(()->new DomainException("没有默认地址"));addresses.owned(userId,addressId);return ToolResult.ok(quoteData(quote,addressId,cartId));}
    private ToolResult createOrder(Long userId,AgentSession session,ToolRequest request){Long cartId=number(request.payload(),"cartDraftId");Long addressId=number(request.payload(),"addressId");if(cartId==null||addressId==null)throw new DomainException("订单参数不完整");authorizations.authorize(userId,session,cartId,addressId);String key="agent:"+session.getSessionId()+":"+requireKey(request.idempotencyKey());return ToolResult.ok(Map.of("order",orderData(OrderResponse.from(orders.create(userId,new CreateOrderCommand(cartId,addressId,key))))));}
    private ToolResult pay(Long userId,AgentSession session,ToolRequest request){if(session.getMode()!=AgentMode.THIS_TASK_AUTO)throw new DomainException("当前会话未获自动支付授权");Long orderId=number(request.payload(),"orderId");if(orderId==null)throw new DomainException("缺少订单");return ToolResult.ok(Map.of("order",orderData(payments.payResponse(userId,orderId,"agent:"+session.getSessionId()+":"+requireKey(request.idempotencyKey())))));}
    private MerchantEntity merchant(Map<String,Object> payload){Long id=number(payload,"merchantId");if(id!=null)return merchants.findById(id).orElseThrow(()->new DomainException("商家不存在"));String text=String.valueOf(payload.getOrDefault("text",payload.getOrDefault("query","")));return merchants.findAll().stream().filter(MerchantEntity::isOpen).filter(m->text.isBlank()||m.getName().contains(text)||m.getCategory().contains(text)).findFirst().orElseGet(()->merchants.findAll().stream().filter(MerchantEntity::isOpen).findFirst().orElseThrow(()->new DomainException("没有营业商家")));}
    private List<CartItemCommand> items(Map<String,Object> payload){Object raw=payload.get("items");if(!(raw instanceof List<?> list))return List.of();List<CartItemCommand> result=new ArrayList<>();for(Object item:list){if(!(item instanceof Map<?,?> map))throw new DomainException("购物车项目无效");Long dishId=number(map,"dishId");Long quantity=number(map,"quantity");if(dishId==null||quantity==null)throw new DomainException("购物车项目无效");List<Long> options=((map.get("optionIds") instanceof List<?> values)?values:List.of()).stream().map(v->number(Map.of("x",v),"x")).toList();result.add(new CartItemCommand(dishId,Math.toIntExact(quantity),options,null));}return result;}
    private java.util.Optional<AddressEntity> defaultAddress(Long userId){return addresses.list(userId).stream().filter(AddressEntity::isDefaultAddress).findFirst();}
    private Long number(Map<?,?> data,String name){Object value=data.get(name);if(value instanceof Number number)return number.longValue();if(value instanceof String text&&!text.isBlank())try{return Long.valueOf(text);}catch(NumberFormatException ignored){}return null;}
    private String requireKey(String key){if(key==null||key.isBlank())throw new DomainException("缺少幂等键");return key;}
    private Map<String,Object> merchant(MerchantEntity m){return Map.of("id",m.getId(),"name",m.getName(),"category",m.getCategory(),"open",m.isOpen(),"minimumOrder",m.getMinimumOrder(),"deliveryFee",m.getDeliveryFee());}
    private Map<String,Object> dish(DishEntity d){return Map.of("id",d.getId(),"merchantId",d.getMerchant().getId(),"name",d.getName(),"price",d.getPrice(),"stock",d.getStock());}
    private Map<String,Object> address(AddressEntity a){return Map.of("id",a.getId(),"recipient",a.getRecipient(),"phone",a.getPhone(),"detail",a.getDetail(),"defaultAddress",a.isDefaultAddress());}
    private Map<String,Object> quoteData(CartQuote q,Long addressId,Long cartId){Map<String,Object> data=new LinkedHashMap<>();data.put("valid",true);data.put("cartDraftId",cartId);data.put("addressId",addressId);data.put("total",q.total());data.put("subtotal",q.subtotal());data.put("deliveryFee",q.deliveryFee());data.put("merchantId",q.merchantId());return data;}
    private Map<String,Object> orderData(OrderResponse order){return Map.of("id",order.id(),"status",order.status().name(),"total",order.total(),"address",order.detail());}
}
