// 客服支持模块：封装投诉工单持久化。
package com.fandazi.feature.support;

import org.springframework.data.jpa.repository.JpaRepository;

/** 投诉工单仓储。 */
public interface ComplaintTicketRepository extends JpaRepository<ComplaintTicketEntity, Long> {}
