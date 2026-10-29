package com.fandazi.feature.support;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import javax.sql.DataSource;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.stereotype.Service;

/** 知识权威目录：发布、撤回、动作绑定及交付许可共用业务数据库，禁止把索引作为有效性依据。 */
@Service
public class KnowledgeCatalog {
    /** 不可变审核条目；时间采用UTC半开区间，词项只服务知识召回。 */
    public record Entry(String id, String version, String title, String question, String body,
                        Instant validFrom, Instant validUntil, List<String> terms) {
        /** 防御复制防止发布校验后被调用者修改词项；其它内容在发布入口统一验证。 */
        public Entry { terms = terms == null ? List.of() : List.copyOf(terms); }
    }
    /** 工具桥已验证的身份；模型正文不能修改该绑定。 */
    public record Scope(String executionKey, String userId, String sessionId, String runId, String taskId) {}
    /** 交付许可只代表许可时刻有效，不能撤回此前已发送的网络字节。 */
    public record Permit(String id, long generation, Instant permittedAt, List<Map<String,String>> answers) {}

    /** 注入真实数据源与同库事务管理器；构造不建立目录或发送网络请求。 */
    public KnowledgeCatalog(DataSource source, PlatformTransactionManager transactions) {
        jdbc = new JdbcTemplate(source);
        // 这是开发保护上限，不代替F3性能预算；SQL等待异常必须向外传播并回滚。
        jdbc.setQueryTimeout(3);
        transaction = new TransactionTemplate(transactions);
        transaction.setTimeout(3);
    }
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    /**
     * 这些短语表示用户正在要求忽略、删除或伪造已审核规则；其后的文本只能作为不可信指令，
     * 不能再参与词项召回并借次要词扩张来源。列表只收录明确的规则变更动作，不把普通否定句截断。
     */
    private static final List<String> UNTRUSTED_RULE_MUTATION_MARKERS = List.of(
            "忽略", "删掉", "改成", "篡改", "假装", "伪装", "不要附带");
    /** 只在持有head行锁的事务中使用，不缓存跨事务的活动版本或代次。 */
    private record Head(String release, long generation) {}
    /** 仅首次部署导入随包审核稿；已有活动发布即保留，不随二进制重启覆盖运维决定。 */
    public boolean initialize(String release, String approval, List<Entry> entries) {
        return inTransaction(() -> {
            // 首次部署和并行实例都在同一head锁内决定是否初始化，避免先读后写竞争。
            if (lockHead().release() != null) return false;
            publish(release, approval, entries);
            return true;
        });
    }
    /** 校验并原子发布审核内容；相同版本不得改正文。 */
    public void publish(String release, String approval, List<Entry> entries) {
        identifier(release); required(approval, 4000);
        if (entries == null || entries.isEmpty() || entries.size() > 100) throw new IllegalArgumentException("发布条目数量无效");
        var snapshot = List.copyOf(entries);
        var ids = new HashSet<String>();
        // 所有内容在事务之前规范化校验；锁中只做版本一致性和数据库写入，不调用模型。
        for (var entry : snapshot) {
            validate(entry);
            if (!ids.add(entry.id())) throw new IllegalArgumentException("发布包含重复条目");
        }
        String manifest = digest(json(snapshot));
        inTransaction(() -> {
            Head head = lockHead();
            if (!jdbc.queryForList("SELECT release_id FROM knowledge_releases WHERE release_id=? FOR UPDATE", release).isEmpty())
                throw new IllegalArgumentException("发布编号已存在，禁止覆盖审核历史");
            for (var entry : snapshot) {
                String hash = digest(json(entry));
                var oldHashes = jdbc.queryForList("SELECT entry_sha FROM knowledge_entries WHERE entry_id=? AND entry_version=? FOR UPDATE", String.class, entry.id(), entry.version());
                if (oldHashes.stream().anyMatch(value -> !value.equals(hash)))
                    throw new IllegalArgumentException("同条目版本内容变化，必须升版本");
            }
            jdbc.update("INSERT INTO knowledge_releases(release_id,manifest_sha,approval_record,created_at_ms) VALUES(?,?,?,?)", release, manifest, approval, nowMillis());
            for (var entry : snapshot) jdbc.update("INSERT INTO knowledge_entries(release_id,entry_id,entry_version,entry_sha,entry_json,valid_from_ms,valid_until_ms) VALUES(?,?,?,?,?,?,?)",
                    release, entry.id(), entry.version(), digest(json(entry)), json(entry), entry.validFrom().toEpochMilli(),
                    entry.validUntil() == null ? null : entry.validUntil().toEpochMilli());
            jdbc.update("UPDATE knowledge_head SET active_release=?,generation=? WHERE singleton_id=1", release, head.generation()+1);
            return null;
        });
    }
    /** 返回当前时刻有效条目，撤回和到期不得依赖定时清理。 */
    public List<Entry> current() {
        return inTransaction(() -> available(lockHead(), nowMillis()));
    }
    /** 对已进入FAQ的问题按审核词项召回并绑定；不参与三层意图分类。 */
    public List<Map<String,String>> lookup(Scope scope, String question, String topic) {
        validate(scope);
        String text = normalize(question), selectedTopic = normalize(topic);
        if (text.length() > 4000) throw new IllegalArgumentException("知识问题过长");
        // 主问题之前的已审核主题仍可回答；明确要求修改规则的后半句只影响安全拒绝语义，不能扩大检索集合。
        String retrievalText = trustedRetrievalText(text);
        return inTransaction(() -> {
            Head head = lockHead();
            // 空问题与明确外部领域保留空绑定，最终不能把目录或无来源当作已完成知识回答。
            var entries = outsideKnowledge(retrievalText) ? List.<Entry>of() : available(head, nowMillis()).stream()
                    .filter(entry -> entry.terms().stream().map(this::normalize).anyMatch(retrievalText::contains))
                    .filter(entry -> selectedTopic.isEmpty() || entry.id().equals(selectedTopic)
                            || entry.terms().stream().map(this::normalize).anyMatch(selectedTopic::equals)).toList();
            // 保持head锁直到来源绑定提交，避免选完条目后刚好切换发布再绑定同名新正文。
            return bind(scope, entries.stream().map(Entry::id).toList());
        });
    }
    /** 帮助页使用当前有效的审核来源，分类与关键词取交集。 */
    public List<Map<String,String>> publicRows(String keyword, String category) {
        String text = keyword == null ? "" : keyword.strip();
        return inTransaction(() -> {
            Head head = lockHead();
            if (head.release() == null) throw new IllegalStateException("知识尚未发布");
            // 搜索审核正文而不是自动添加的来源标记，保留旧帮助页分类与关键词的交集含义。
            return render(available(head, nowMillis()).stream()
                    .filter(entry -> category == null || category.isBlank() || entry.id().equals(category))
                    .filter(entry -> text.isEmpty() || entry.question().contains(text) || entry.body().contains(text)).toList());
        });
    }
    /** 已由D1对照的领域排除策略，只约束平台FAQ召回；不宣称能识别任意开放领域问题。 */
    private boolean outsideKnowledge(String text) {
        if (text.isBlank() || List.of("税", "量子", "火星", "航天", "机票", "公司", "国际清算", "积分", "保险", "证券", "股票", "医保")
                .stream().anyMatch(text::contains)) return true;
        boolean platform = List.of("团购", "套餐", "本平台", "本应用", "模拟支付").stream().anyMatch(text::contains);
        return (text.contains("银行") || text.contains("信用卡")) && !platform;
    }
    /**
     * 返回可参与召回的可信问题前缀。若规则变更指令出现在开头则返回空串，使本次查询安全拒答；
     * 正常多主题问题没有这些明确动作词，仍保留完整文本并召回全部必要审核来源。
     */
    private String trustedRetrievalText(String text) {
        int boundary = UNTRUSTED_RULE_MUTATION_MARKERS.stream()
                .mapToInt(text::indexOf)
                .filter(index -> index >= 0)
                .min()
                .orElse(text.length());
        return text.substring(0, boundary).strip();
    }
    /** 规范化只处理词项大小写与首尾空白，不改变审核原文或执行意图边界。 */
    private String normalize(String value) { return value == null ? "" : value.strip().toLowerCase(java.util.Locale.ROOT); }
    /** 对来源集合建立可信动作绑定；输出保留既有FAQ三个字段。 */
    public List<Map<String,String>> bind(Scope scope, List<String> sourceIds) {
        validate(scope);
        var ids = checkedIds(sourceIds);
        return inTransaction(() -> {
            Head head = lockHead();
            // 活动版本缺失属于不可用，不能以空目录伪装成功发布。
            if (head.release() == null) throw new IllegalStateException("知识尚未发布");
            var entries = selectSources(head, ids, nowMillis());
            var existing = jdbc.queryForList("SELECT * FROM knowledge_bindings WHERE execution_key=? FOR UPDATE", scope.executionKey());
            if (!existing.isEmpty()) {
                verifyBinding(scope, head, existing.get(0));
                if (!parseIds((String) existing.get(0).get("sources_json")).equals(ids))
                    throw new IllegalStateException("原动作来源集合不可更换");
            } else {
                jdbc.update("INSERT INTO knowledge_bindings(execution_key,user_id,session_id,run_id,task_id,release_id,sources_json) VALUES(?,?,?,?,?,?,?)",
                        scope.executionKey(), scope.userId(), scope.sessionId(), scope.runId(), scope.taskId(), head.release(), json(ids));
            }
            return render(entries);
        });
    }
    /** 缓存重放只重读原绑定，绝不为缺绑定的旧结果补造新来源；空结果仍保持未回答。 */
    public List<Map<String,String>> replay(Scope scope) {
        validate(scope);
        return inTransaction(() -> {
            Head head = lockHead();
            var records = jdbc.queryForList("SELECT * FROM knowledge_bindings WHERE execution_key=? FOR UPDATE", scope.executionKey());
            if (records.size() != 1) throw new IllegalStateException("旧知识结果缺少可信来源绑定");
            var binding = records.get(0);
            verifyBinding(scope, head, binding);
            return render(selectSources(head, parseIds((String) binding.get("sources_json")), nowMillis()));
        });
    }
    /** 查询本轮可信动作绑定；调用方须先取得领域会话锁，此处使用当前读而非旧事务快照。 */
    public List<Scope> scopes(String user, String session, String run) {
        required(user,100); required(session,100); required(run,100);
        return inTransaction(() -> {
            lockHead();
            return jdbc.query("SELECT execution_key,user_id,session_id,run_id,task_id FROM knowledge_bindings WHERE user_id=? AND session_id=? AND run_id=? ORDER BY execution_key FOR UPDATE",
                    (row,index)->new Scope(row.getString(1),row.getString(2),row.getString(3),row.getString(4),row.getString(5)),user,session,run);
        });
    }
    /**
     * 只从同库原交付许可恢复来源集合；许可本身不代表当前有效，调用方仍须逐绑定申请新许可。
     * 用户、会话、run全部匹配，空关联或旧记录无法映射时失败关闭，不用最新动作补造历史身份。
     */
    public List<Scope> deliveryScopes(String user,String session,String run,String delivery) {
        required(user,100);required(session,100);required(run,100);required(delivery,255);
        return inTransaction(()->{
            lockHead();
            return jdbc.query("SELECT b.execution_key,b.user_id,b.session_id,b.run_id,b.task_id FROM knowledge_bindings b JOIN knowledge_delivery_permits p ON p.execution_key=b.execution_key WHERE b.user_id=? AND b.session_id=? AND b.run_id=? AND p.delivery_ref=? ORDER BY b.execution_key FOR UPDATE",
                (row,index)->new Scope(row.getString(1),row.getString(2),row.getString(3),row.getString(4),row.getString(5)),user,session,run,delivery).stream().distinct().toList();
        });
    }
    /** 校验原绑定身份并取得最终交付许可；无绑定、过期、撤回、版本变化必须拒绝。 */
    public Permit permit(Scope scope, String delivery) {
        validate(scope); required(delivery, 255);
        return inTransaction(() -> {
            Head head = lockHead();
            var records = jdbc.queryForList("SELECT * FROM knowledge_bindings WHERE execution_key=? FOR UPDATE", scope.executionKey());
            if (records.size() != 1) throw new IllegalStateException("知识动作缺少可信来源绑定");
            var binding = records.get(0);
            verifyBinding(scope, head, binding);
            // 取得锁之后读取数据库时间，防止锁等待跨过有效期仍沿用等待前时刻。
            long now = nowMillis();
            var ids = parseIds((String) binding.get("sources_json"));
            if (ids.isEmpty()) throw new IllegalStateException("无知识来源，不能签发成功回答许可");
            var answers = render(selectSources(head, ids, now));
            String permit = UUID.randomUUID().toString();
            jdbc.update("INSERT INTO knowledge_delivery_permits(permit_id,execution_key,delivery_ref,generation,permitted_at_ms,sources_json) VALUES(?,?,?,?,?,?)",
                    permit, scope.executionKey(), delivery, head.generation(), now, json(ids));
            return new Permit(permit, head.generation(), Instant.ofEpochMilli(now), answers);
        });
    }
    /** 紧急撤回独立于发布与供应商，重复相同撤回不得恢复来源。 */
    public void revoke(String id, String version, String reason) {
        identifier(id); identifier(version); required(reason, 4000);
        inTransaction(() -> {
            Head head = lockHead();
            if (jdbc.queryForList("SELECT entry_id FROM knowledge_entries WHERE entry_id=? AND entry_version=? FOR UPDATE", id, version).isEmpty())
                throw new IllegalArgumentException("不能撤回不存在的条目版本");
            if (jdbc.queryForList("SELECT entry_id FROM knowledge_revocations WHERE entry_id=? AND entry_version=? FOR UPDATE", id, version).isEmpty()) {
                jdbc.update("INSERT INTO knowledge_revocations(entry_id,entry_version,reason,revoked_at_ms) VALUES(?,?,?,?)", id, version, reason, nowMillis());
                jdbc.update("UPDATE knowledge_head SET generation=? WHERE singleton_id=1", head.generation()+1);
            }
            return null;
        });
    }
    /** 切换到已有发布但不删除永久撤回事实。 */
    public void activate(String release) {
        identifier(release);
        inTransaction(() -> {
            Head head = lockHead();
            if (jdbc.queryForList("SELECT release_id FROM knowledge_releases WHERE release_id=? FOR UPDATE", release).isEmpty())
                throw new IllegalArgumentException("发布不存在");
            jdbc.update("UPDATE knowledge_head SET active_release=?,generation=? WHERE singleton_id=1", release, head.generation()+1);
            return null;
        });
    }

    /** 所有目录操作加入已有领域事务；从独立命令调用时由同一管理器创建短事务。 */
    private <T> T inTransaction(Supplier<T> action) {
        return transaction.execute(status -> action.get());
    }
    /** 锁序固定为领域session/task之后的单行head；此模块不获取反向领域锁。 */
    private Head lockHead() {
        var rows = jdbc.query("SELECT active_release,generation FROM knowledge_head WHERE singleton_id=1 FOR UPDATE",
                (row, index) -> new Head(row.getString(1), row.getLong(2)));
        if (rows.size() != 1) throw new IllegalStateException("知识权威目录未初始化");
        return rows.get(0);
    }
    /** 用数据库时钟判定半开区间，生产MySQL显式读UTC；H2只服务离线定向测试。 */
    private long nowMillis() {
        return jdbc.execute((java.sql.Connection connection) -> {
            boolean mysql = connection.getMetaData().getDatabaseProductName().equalsIgnoreCase("MySQL");
            try (var statement = connection.createStatement()) {
                statement.setQueryTimeout(3);
                try (var result = statement.executeQuery(mysql ? "SELECT UTC_TIMESTAMP(3)" : "SELECT CURRENT_TIMESTAMP")) {
                    result.next();
                    return result.getTimestamp(1, java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))).getTime();
                }
            }
        });
    }
    /** 发布和撤回都用当前读，避免外层REPEATABLE_READ旧快照复活来源；顺序按ID稳定。 */
    private List<Entry> available(Head head, long now) {
        if (head.release() == null) return List.of();
        // 外层领域事务可能早于撤回建立快照。只给外层查询加锁不会自动改变子查询隔离，
        // 因此显式拆成两次当前读；head锁保证两次读取之间没有新发布或撤回穿插。
        var revoked = new HashSet<>(jdbc.query("SELECT entry_id,entry_version FROM knowledge_revocations FOR UPDATE",
                (row, index) -> row.getString(1) + ":" + row.getString(2)));
        return jdbc.query("SELECT entry_json FROM knowledge_entries WHERE release_id=? AND valid_from_ms<=? AND (valid_until_ms IS NULL OR valid_until_ms>?) ORDER BY entry_id FOR UPDATE",
                (row, index) -> readEntry(row.getString(1)), head.release(), now, now).stream()
                .filter(entry -> !revoked.contains(entry.id() + ":" + entry.version())).toList();
    }
    /** 缺任一来源时整体拒绝，不能删去失效条件后继续拼出看似完整的回答。 */
    private List<Entry> selectSources(Head head, List<String> ids, long now) {
        var active = available(head, now);
        var selected = new ArrayList<Entry>();
        for (String id : ids) selected.add(active.stream().filter(entry -> entry.id().equals(id)).findFirst()
                .orElseThrow(() -> new IllegalStateException("来源已失效或不属于当前发布")));
        return List.copyOf(selected);
    }
    /** 依据数据库绑定校验全身份，绝不从用户正文或来源显示字符串恢复授权。 */
    private void verifyBinding(Scope scope, Head head, Map<String,Object> binding) {
        if (!Objects.equals(scope.userId(), binding.get("user_id")) || !Objects.equals(scope.sessionId(), binding.get("session_id"))
                || !Objects.equals(scope.runId(), binding.get("run_id")) || !Objects.equals(scope.taskId(), binding.get("task_id"))
                || !Objects.equals(head.release(), binding.get("release_id"))) throw new IllegalStateException("知识绑定身份或发布已失效");
    }
    /** 输出仍只有原协议字段；原文完整保留，来源来自审核条目而非模型提供的引用。 */
    private List<Map<String,String>> render(List<Entry> entries) {
        return entries.stream().map(entry -> Map.of("id", entry.id(), "question", entry.question(), "answer",
                entry.body() + "\n依据：" + entry.title() + "；版本：" + entry.version() + "；帮助中心：" + entry.question())).toList();
    }
    /** 元数据有界且有效期精确到毫秒；不截断正文、条件或无效日期。 */
    private void validate(Entry entry) {
        if (entry == null) throw new IllegalArgumentException("知识条目不能为空");
        identifier(entry.id()); identifier(entry.version()); required(entry.title(), 200); required(entry.question(), 500); required(entry.body(), 12000);
        if (entry.validFrom() == null || !entry.validFrom().equals(Instant.ofEpochMilli(entry.validFrom().toEpochMilli()))
                || (entry.validUntil() != null && (!entry.validUntil().isAfter(entry.validFrom())
                || !entry.validUntil().equals(Instant.ofEpochMilli(entry.validUntil().toEpochMilli()))))) throw new IllegalArgumentException("有效区间无效");
        if (entry.terms().isEmpty() || entry.terms().size() > 100) throw new IllegalArgumentException("知识词项数量无效");
        for (String term : entry.terms()) required(term, 100);
    }
    /** 长度保护与原工具身份约束一致，失败不截断为另一个用户或动作。 */
    private void validate(Scope scope) {
        if (scope == null) throw new IllegalArgumentException("缺少可信身份");
        required(scope.executionKey(), 255); required(scope.userId(), 100); required(scope.sessionId(), 100);
        required(scope.runId(), 100); required(scope.taskId(), 100);
    }
    private void identifier(String value) {
        if (value == null || !value.matches("[A-Za-z0-9_-]{1,64}")) throw new IllegalArgumentException("知识标识无效");
    }
    private void required(String value, int limit) {
        if (value == null || value.isBlank() || value.length() > limit) throw new IllegalArgumentException("知识字段为空或过长");
    }
    /** 来源顺序按首次查询固定，重复来源拒绝，防止重复段落掩盖缺失来源。 */
    private List<String> checkedIds(List<String> values) {
        if (values == null || values.size() > 100) throw new IllegalArgumentException("来源集合无效");
        var result = List.copyOf(values);
        result.forEach(this::identifier);
        if (new HashSet<>(result).size() != result.size()) throw new IllegalArgumentException("来源重复");
        return result;
    }
    /** 内部JSON只读取本模块写入的确定结构，损坏时阻止交付而不回退静态知识。 */
    private Entry readEntry(String value) {
        try { return mapper.readValue(value, Entry.class); }
        catch (Exception failure) { throw new IllegalStateException("知识快照损坏", failure); }
    }
    private List<String> parseIds(String value) {
        try { return checkedIds(mapper.readValue(value, new TypeReference<List<String>>() {})); }
        catch (Exception failure) { throw new IllegalStateException("来源绑定损坏", failure); }
    }
    private String json(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (Exception failure) { throw new IllegalArgumentException("知识内容不能序列化", failure); }
    }
    private String digest(String value) {
        try { return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException failure) { throw new IllegalStateException(failure); }
    }
}
