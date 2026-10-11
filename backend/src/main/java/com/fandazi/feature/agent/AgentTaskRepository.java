// 会话任务仓储：归属先于投影，写入在会话锁后按任务ID获取行锁。
package com.fandazi.feature.agent;
import jakarta.persistence.LockModeType;
import java.util.*;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

/** 目录不查询其他会话或epoch；跨任务控制由调用方按ID排序锁定。 */
public interface AgentTaskRepository extends JpaRepository<AgentTaskEntity,String> {
    /** 只查同scope的活跃/暂停目录；终态历史另按ID读取，避免长会话每次GET扫描全部历史。 */
    @Query("select t from AgentTaskEntity t where t.userId=:user and t.sessionId=:session and t.epoch=:epoch and t.lifecycle in ('active','paused')")
    List<AgentTaskEntity> findLiveScope(@Param("user") Long userId,@Param("session") String sessionId,@Param("epoch") String epoch);
    /** 会话锁后获取单任务锁；查不到与不属于本scope都统一404。 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from AgentTaskEntity t where t.id=:id")
    Optional<AgentTaskEntity> lockById(@Param("id") String id);
    /** 写查询同时限定scope，避免已知外部taskId让本会话持锁另一个账号的任务。 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from AgentTaskEntity t where t.id=:id and t.userId=:user and t.sessionId=:session and t.epoch=:epoch")
    Optional<AgentTaskEntity> lockOwned(@Param("id") String id,@Param("user") Long user,
            @Param("session") String session,@Param("epoch") String epoch);
}
