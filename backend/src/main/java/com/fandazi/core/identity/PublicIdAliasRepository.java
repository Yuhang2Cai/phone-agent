// 别名仓储：种子根据资源种类和旧引用查找固定 UUID，不参与公开 API 查询。
package com.fandazi.core.identity;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** 数据库唯一键防止一个旧引用在重复装载时生成不同公开编号。 */
public interface PublicIdAliasRepository extends JpaRepository<PublicIdAlias, Long> {
    /** 按内部资源种类与别名查询映射，不进行前缀或模糊匹配。 */
    Optional<PublicIdAlias> findByResourceKindAndLegacyId(String kind, String alias);
    /** 返回指定类型的内部映射，供种子恢复关联使用。 */
    List<PublicIdAlias> findByResourceKind(String kind);
}
