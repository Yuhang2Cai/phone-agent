// 购物车模块：封装持久化查询与写入。
package com.fandazi.feature.cart;
import java.util.*; import org.springframework.data.jpa.repository.JpaRepository;
public interface CartDraftRepository extends JpaRepository<CartDraft,Long>{ Optional<CartDraft> findByIdAndUserId(Long id,Long userId); }
