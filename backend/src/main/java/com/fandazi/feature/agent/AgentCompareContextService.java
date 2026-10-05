// 对比可信上下文模块：只接受用户显式选择的公开ID，由Spring重查事实；不接受客户端价格或授权。
package com.fandazi.feature.agent;
import com.fandazi.feature.catalog.DealPackageService;
import com.fandazi.feature.DomainException;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
/** 只读上下文不签发确认凭证，不创建订单、报价或支付，跨门店公开套餐可比较。 */
@Service
public class AgentCompareContextService {
    private final DealPackageService deals;
    /** 注入唯一权威目录服务；返回手工白名单投影，禁止端上缓存或任意DTO字段成为模型事实。 */
    public AgentCompareContextService(DealPackageService deals){this.deals=deals;}
    /** 返回空上下文或2–3项最新公开事实；非法数量/重复/资源缺失抛领域异常。 */
    @Transactional(readOnly=true)
    public Map<String,Object> build(List<String> selected){
        if(selected==null)return Map.of();
        // 输入先全部规范校验，防止查询一半才发现第四项或重复；UUID规范化只影响身份文本。
        if(selected.size()<2 || selected.size()>3)throw new DomainException("对比需要2至3个不同套餐");
        var ids=new ArrayList<String>();
        for(String id:selected){
            try {String normal=UUID.fromString(id).toString();if(!normal.equalsIgnoreCase(id) || ids.contains(normal))throw new IllegalArgumentException();ids.add(normal);}
            catch(Exception invalid){throw new DomainException("对比套餐标识无效或重复");}
        }
        // 只读重查每个原资源，删除资源明确失败，失效/售罄状态仍可告诉用户但不授权购买。
        var facts=new ArrayList<Map<String,Object>>();
        for(String id:ids){var deal=deals.detail(id);if(!id.equalsIgnoreCase(deal.publicId()))throw new DomainException("对比套餐身份不匹配");
            var row=new LinkedHashMap<String,Object>();row.put("publicId",deal.publicId());row.put("storePublicId",deal.storePublicId());row.put("storeName",deal.storeName());row.put("title",deal.title());row.put("description",deal.description());
            row.put("priceCents",deal.priceCents());row.put("originalPriceCents",deal.originalPriceCents());row.put("inventory",deal.inventory());row.put("active",deal.active());row.put("purchaseLimit",deal.purchaseLimit());
            row.put("validFrom",deal.validFrom().toString());row.put("validTo",deal.validTo().toString());row.put("peopleCount",deal.peopleCount());row.put("usageRules",deal.usageRules());
            row.put("contents",deal.contents()==null?null:deal.contents().stream().map(x->Map.of("name",x.name(),"quantity",x.quantity())).toList());facts.add(Collections.unmodifiableMap(row));}
        // 输出只含白名单公开资料，不含数据库主键、用户字段、图片凭据或确认状态。
        return Map.of("comparisonDeals",List.copyOf(facts));
    }
}
