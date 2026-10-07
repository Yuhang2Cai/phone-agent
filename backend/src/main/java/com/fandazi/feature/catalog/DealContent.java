// 套餐内容模块：运营提供的结构化公开内容，不由标题或说明自动推断。
package com.fandazi.feature.catalog;
/** 一项明确菜品名称及份数；非法内容在写入前拒绝，避免未知值成为虚假事实。 */
public record DealContent(String name,int quantity) {
    /** 校验名称和正份数；不转换业务描述或猜测套餐人数。 */
    public DealContent {
        if(name==null || name.isBlank() || name.length()>255 || quantity<1 || quantity>10000)
            throw new IllegalArgumentException("套餐结构化内容无效");
    }
}
