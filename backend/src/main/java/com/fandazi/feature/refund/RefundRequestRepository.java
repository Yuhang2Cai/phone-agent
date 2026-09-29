// 售后模块：封装退款申请持久化。
package com.fandazi.feature.refund;

import org.springframework.data.jpa.repository.JpaRepository;

/** 退款申请仓储。 */
public interface RefundRequestRepository extends JpaRepository<RefundRequestEntity, Long> {}
