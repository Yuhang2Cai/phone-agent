// 种子资源身份服务：随机生成并持久化别名，迁移后保持 JSON 夹具引用一致；依赖别名仓储。
package com.fandazi.core.identity;

import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 没有 Controller 暴露此能力；公开业务服务仍只按 UUID 查实体，不能枚举 legacy 别名。 */
@Service
public class PublicIdAliases {
    private final PublicIdAliasRepository aliases;
    /** 注入内部映射仓储，不读客户端输入。 */
    public PublicIdAliases(PublicIdAliasRepository aliases) { this.aliases = aliases; }

    /** 为内部夹具别名返回稳定随机 UUID；第一次创建会写映射，不从别名推导 nameUUID。 */
    @Transactional
    public String getOrCreate(String kind, String alias) {
        if (kind == null || kind.isBlank() || alias == null || alias.isBlank())
            throw new IllegalArgumentException("内部资源别名不能为空");
        return aliases.findByResourceKindAndLegacyId(kind, alias).map(PublicIdAlias::getPublicId)
                .orElseGet(() -> aliases.save(new PublicIdAlias(kind, alias, UUID.randomUUID().toString())).getPublicId());
    }

    /** 读取已有内部别名；缺失时抛出异常，防止误创建第二个资源替代历史引用。 */
    @Transactional(readOnly = true)
    public String require(String kind, String alias) {
        return aliases.findByResourceKindAndLegacyId(kind, alias).map(PublicIdAlias::getPublicId)
                .orElseThrow(() -> new IllegalArgumentException("内部资源别名不存在：" + kind + "/" + alias));
    }

    /** 返回不可修改的该类型别名表，仅供装载器把旧 JSON 引用关联到现存实体。 */
    @Transactional(readOnly = true)
    public Map<String, String> mappings(String kind) {
        return aliases.findByResourceKind(kind).stream().collect(Collectors.toUnmodifiableMap(
                PublicIdAlias::getLegacyId, PublicIdAlias::getPublicId));
    }
}
