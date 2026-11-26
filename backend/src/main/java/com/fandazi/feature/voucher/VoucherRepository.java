// 团购券模块：封装用户和公开标识查询。
package com.fandazi.feature.voucher;

import java.util.Optional;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/** 团购券仓储。 */
public interface VoucherRepository extends JpaRepository<VoucherEntity, Long> {
    Optional<VoucherEntity> findByPublicId(String publicId);
    /** 按用户查询团购券，确保客户端不能读取其他用户的券。 */
    List<VoucherEntity> findByUserIdOrderByCreatedAtDesc(Long userId);
    /** 按用户和公开标识查询团购券，用于详情和展示令牌权限校验。 */
    Optional<VoucherEntity> findByUserIdAndPublicId(Long userId, String publicId);
    /** 退款提交时锁定券，防止并发申请绕过单券资格判断。 */
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select v from VoucherEntity v where v.user.id = :userId and v.publicId = :publicId")
    Optional<VoucherEntity> findOwnedForUpdate(@org.springframework.data.repository.query.Param("userId") Long userId,
                                                @org.springframework.data.repository.query.Param("publicId") String publicId);
    long countByStatus(VoucherStatus status);
}
