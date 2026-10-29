// 帮助中心服务：集中维护团购售后与评价规则，供页面和 Agent 共用。
package com.fandazi.feature.support;

import java.util.List;
import java.util.Map;
import java.util.Locale;
import org.springframework.stereotype.Service;

/** 只回答已实现的本地平台规则，不推断具体订单的退款结果。 */
@Service
public class FaqService {
    /** 规则文案与服务资格判断一致；具体状态仍须查询用户自己的券或订单。 */
    private static final List<Map<String, String>> ITEMS = List.of(
            // 购买和用券帮助只描述当前已实现的模拟支付与券信息查询；核销暂缓，不承诺可到店核销。
            Map.of("id", "purchase-flow", "question", "如何购买团购？",
                    "answer", "选择套餐后确认订单并完成模拟支付；支付成功后可在订单中查看团购券。"),
            Map.of("id", "voucher-use", "question", "如何使用团购券？",
                    "answer", "可在我的订单中查看团购券的有效期和使用规则；到店核销功能目前暂缓开放。"),
            Map.of("id", "refund-eligibility", "question", "什么情况下可以申请退款？",
                    "answer", "已支付且订单未取消、团购券在有效期内、未核销且未申请退款时，可以提交退款申请；是否符合条件以服务端校验为准，提交后请查看实际处理状态。"),
            // 游客不能使用 AI 助手；帮助内容不要求客户端猜测账号能力或维护本地副本。
            Map.of("id", "account-login", "question", "账号与登录如何使用？",
                    "answer", "使用账号和密码登录后，可以查看订单和使用 AI 助手；游客点击 AI 助手需先登录。"),
            // 平台规则明确区分已上线的演示支付和暂缓的核销，防止帮助页给出超出服务能力的承诺。
            Map.of("id", "platform-rules", "question", "平台规则有哪些？",
                    "answer", "平台当前提供团购套餐浏览、模拟支付、订单与团购券查询；到店核销功能暂缓开放，退款申请结果以进度页实际状态为准。"),
            Map.of("id", "review-eligibility", "question", "什么时候可以评价门店？",
                    "answer", "订单中至少一张团购券核销后可以评价，每笔订单只能评价一次。"),
            Map.of("id", "coupon-use", "question", "如何查看优惠券？",
                    "answer", "登录后可通过 AI 助手查询本人优惠券的状态、门槛、有效期和适用范围；本期仅支持只读查询，不支持领取或支付抵扣。"),
            Map.of("id", "complaint", "question", "如何反馈门店问题？",
                    "answer", "可在帮助与客服中提交投诉；关联订单时请选择您自己的团购订单。"));

    /** 经人工维护的帮助主题词，只用于已进入 FAQ 工具后的知识召回，不参与三层意图路由。 */
    private static final Map<String, List<String>> TOPIC_TERMS = Map.of(
            "purchase-flow", List.of("购买", "下单", "支付", "purchase", "payment"),
            "voucher-use", List.of("团购券", "用券", "核销", "voucher"),
            "refund-eligibility", List.of("退款", "退钱", "退费", "refund"),
            "account-login", List.of("账号", "账户", "登录", "注册", "密码", "account", "login"),
            "platform-rules", List.of("平台规则", "平台功能", "平台能力", "platform"),
            "review-eligibility", List.of("评价", "评论", "评分", "review"),
            "coupon-use", List.of("优惠券", "抵扣", "coupon"),
            "complaint", List.of("投诉", "反馈", "客服", "complaint"));

    /** 本实例固定的知识快照；默认行为沿用原目录，公平对照可注入已审校的同结构语料。 */
    private final List<Map<String, String>> items;
    /** 生产目录由Spring注入；原构造器只用于历史离线组件对照。 */
    private KnowledgeCatalog knowledge;

    /** 接入权威目录，发布命令与请求处理使用同一数据库。 */
    @org.springframework.beans.factory.annotation.Autowired
    void attachKnowledge(KnowledgeCatalog catalog) { this.knowledge = catalog; }

    /** 工具桥传入已验证身份，新知识必须与当前动作绑定。 */
    public List<Map<String,String>> queryForAgent(String question, String topic, KnowledgeCatalog.Scope scope) {
        return knowledge == null ? queryForAgent(question, topic) : knowledge.lookup(scope, question, topic);
    }
    /** 原工具结果只在绑定仍有效且全文一致时重放，不能自动替换成新发布而冒充原动作。 */
    public Map<String,Object> replayForAgent(KnowledgeCatalog.Scope scope, Map<String,Object> cached) {
        if (knowledge == null) return cached;
        var current = knowledge.replay(scope);
        if (!java.util.Objects.equals(current, cached.get("faqs"))) throw new IllegalStateException("缓存知识与原绑定正文不一致");
        return Map.of("faqs", current);
    }

    /** Spring默认构造使用原有目录，不因新增测量入口而提前启用未验收内容。 */
    public FaqService() {
        this(ITEMS);
    }

    /** 固定调用方提供的语料副本，避免测量期间修改共享集合造成不同条件拼接；不改变匹配算法。 */
    FaqService(List<Map<String, String>> catalog) {
        this.items = catalog.stream().map(Map::copyOf).toList();
    }

    /** 知识召回专用同义词，覆盖审核主题的不同表达；只在FAQ内部使用，不能参与本地意图分类。 */
    private static final Map<String, List<String>> REVIEWED_TERMS = Map.of(
            "purchase-flow", List.of("购买", "下单", "支付", "付款", "买团购", "选择套餐", "买完套餐", "真实交易", "演示", "purchase", "payment"),
            "voucher-use", List.of("团购券", "核销", "验券", "用券", "使用规则", "使用说明", "使用细则", "券的有效", "券还能", "voucher"),
            "refund-eligibility", List.of("退款", "退钱", "退费", "退回", "refund"),
            "account-login", List.of("账号", "账户", "登录", "登陆", "游客", "密码", "account", "login"),
            "platform-rules", List.of("平台规则", "平台功能", "平台能力", "什么功能", "哪些功能", "推送", "通知", "人工", "接管", "真人", "platform"),
            "review-eligibility", List.of("评价", "评论", "点评", "打分", "评分", "review"),
            "coupon-use", List.of("优惠券", "折扣券", "抵扣", "领取", "coupon"),
            "complaint", List.of("投诉", "反馈", "服务问题", "记录我的问题", "记录问题", "complaint"));

    /** 显式外部领域词表用于拒绝拿平台知识解释其它领域；不是开放领域分类器，也不代表穷尽未知问题。 */
    private static final List<String> EXTERNAL_DOMAINS = List.of(
            "税", "量子", "火星", "航天", "机票", "公司", "国际清算", "积分", "保险", "证券", "股票", "医保");

    /**
     * 在相同语料上测量词项改进：完整聚合所有匹配主题，保留可选主题严格约束，不执行生成或任何业务写入。
     * 输入为空返回空集，不能把目录展示当成已回答；显式外部领域拒绝，银行问题仅在明确平台模拟交易语境下解释。
     */
    List<Map<String, String>> queryReviewed(String question, String topic) {
        String text = question == null ? "" : question.strip().toLowerCase(Locale.ROOT);
        // 先处理不能由当前审核知识回答的领域；额外系统指令不构成任何授权，也不启动工具。
        if (text.isBlank() || EXTERNAL_DOMAINS.stream().anyMatch(text::contains)) return List.of();
        boolean platformPayment = List.of("团购", "套餐", "本平台", "本应用", "模拟支付").stream().anyMatch(text::contains);
        if ((text.contains("银行") || text.contains("信用卡")) && !platformPayment) return List.of();
        // 不因首个原文命中提前退出，避免组合问题丢掉其它必要规则；返回仍仅为三个原协议字段。
        var candidates = items.stream().filter(item -> REVIEWED_TERMS.getOrDefault(item.get("id"), List.of())
                .stream().anyMatch(text::contains)).toList();
        if (topic == null || topic.isBlank()) return candidates;
        String normalized = topic.strip().toLowerCase(Locale.ROOT);
        return candidates.stream().filter(item -> item.get("id").equals(normalized)
                || REVIEWED_TERMS.getOrDefault(item.get("id"), List.of()).contains(normalized)).toList();
    }

    /** Agent 的完整问题先检索原文，未匹配再按明确主题召回原条目；空问题兼容公开目录查询。 */
    public List<Map<String, String>> query(String question) {
        if (knowledge != null) return knowledge.publicRows(question, null);
        var literal = query(question, null);
        if (!literal.isEmpty()) return literal;
        String text = question == null ? "" : question.strip().toLowerCase(Locale.ROOT);
        // 只返回知识库中已存在的答案；未知领域保持空集，不让兜底变成编造平台规则。
        return items.stream().filter(item -> TOPIC_TERMS.get(item.get("id")).stream().anyMatch(text::contains)).toList();
    }

    /** 工具可选主题作为严格约束：接受标准 ID 或已知主题词，不能对 ID 做任意子串过滤。 */
    public List<Map<String, String>> queryForAgent(String question, String topic) {
        // 生产工具必须显式传可信scope；不能让旧调用绕过来源绑定仍宣称具备撤回保证。
        if (knowledge != null) throw new IllegalStateException("知识工具需要可信动作身份");
        var candidates = query(question);
        if (topic == null || topic.isBlank()) return candidates;
        String normalized = topic.strip().toLowerCase(Locale.ROOT);
        // 问题与主题冲突或主题未知时返回空；不绕过约束返回全部帮助。
        return candidates.stream().filter(item -> item.get("id").equals(normalized)
                || TOPIC_TERMS.get(item.get("id")).contains(normalized)).toList();
    }

    /** 页面分类目录由后端统一给出；编号沿用已有 FAQ 标识，安卓只维护选择状态。 */
    public List<Map<String, String>> categories() {
        if (knowledge != null) return knowledge.publicRows(null, null).stream()
                .map(row -> Map.of("id", row.get("id"), "label", row.get("question"))).toList();
        return items.stream().map(item -> Map.of("id", item.get("id"), "label", item.get("question"))).toList();
    }

    /** 分类和关键词同时出现时取交集；未知分类返回空集，不回退到全部内容。 */
    public List<Map<String, String>> query(String keyword, String category) {
        if (knowledge != null) return knowledge.publicRows(keyword, category);
        String text = keyword == null ? "" : keyword.trim();
        // 返回仍只有原协议中的三个字段，避免页面分类改动破坏 query_faq 严格工具契约。
        return items.stream().filter(item -> category == null || category.isBlank() || category.equals(item.get("id")))
                .filter(item -> text.isEmpty() || item.get("question").contains(text)
                || item.get("answer").contains(text)).toList();
    }
}
