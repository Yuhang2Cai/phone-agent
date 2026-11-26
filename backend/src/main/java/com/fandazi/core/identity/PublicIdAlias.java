// 资源别名持久化：仅迁移与种子内部使用，将旧确定性引用连接到随机公开 UUID；依赖 JPA。
package com.fandazi.core.identity;

import jakarta.persistence.*;

/** 别名不是公开访问入口，内部主键和用户归属不因公开编号升级而变化。 */
@Entity
@Table(name = "public_id_aliases", uniqueConstraints = {
        @UniqueConstraint(columnNames = {"resource_kind", "legacy_id"}),
        @UniqueConstraint(columnNames = {"resource_kind", "public_id"})})
public class PublicIdAlias {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "resource_kind", nullable = false, length = 40) private String resourceKind;
    @Column(name = "legacy_id", nullable = false, length = 80) private String legacyId;
    @Column(name = "public_id", nullable = false, length = 80) private String publicId;

    protected PublicIdAlias() {}

    /** 保存种子或历史别名与公开 UUID 的固定关联，不包含用户数据或凭据。 */
    public PublicIdAlias(String kind, String alias, String publicId) {
        this.resourceKind = kind; this.legacyId = alias; this.publicId = publicId;
    }
    /** 返回资源类型以隔离门店、套餐和订单的同名别名。 */
    public String getResourceKind() { return resourceKind; }
    /** 返回仅供内部迁移/夹具引用的旧编号，不下发客户端。 */
    public String getLegacyId() { return legacyId; }
    /** 返回持久化随机 UUID，重启后不重新生成。 */
    public String getPublicId() { return publicId; }
}
