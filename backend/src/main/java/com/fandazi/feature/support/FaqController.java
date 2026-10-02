// 帮助中心 HTTP 入口：向客户端提供与 Agent 一致的规则文案。
package com.fandazi.feature.support;

import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** FAQ 是公开只读资源，不包含任何用户订单或私有状态。 */
@RestController
@RequestMapping("/api/faqs")
public class FaqController {
    private final FaqService faqs;

    /** 注入规则服务。 */
    public FaqController(FaqService faqs) { this.faqs = faqs; }

    /** 返回匹配的帮助条目。 */
    @GetMapping
    public List<Map<String, String>> list(@RequestParam(required = false) String keyword,
                                        @RequestParam(required = false) String category) { return faqs.query(keyword, category); }

    /** 分类是公开后端目录，不包含用户资源，也不由端侧编造问答分组。 */
    @GetMapping("/categories")
    public List<Map<String, String>> categories() { return faqs.categories(); }
}
