// 秒杀公开HTTP入口：仅提供读操作，动态时间和库存响应禁止缓存。
package com.fandazi.feature.seckill;
import com.fandazi.feature.agent.ToolBoundaryException;
import java.util.List;
import java.util.Map;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

/** 公开目录与本人资格分开注册，避免通配鉴权把个人数据开放给游客。 */
@RestController
public class SeckillCatalogController {
    private final SeckillCatalogService service;
    /** 注入只读目录服务，控制器不参与库存写入。 */
    public SeckillCatalogController(SeckillCatalogService service){this.service=service;}
    /** 读取指定门店秒杀预告/活动列表；无活动返回空数组，禁止客户端示例数据回填。 */
    @GetMapping("/api/stores/{storeId}/seckill-activities")
    public ResponseEntity<List<SeckillCatalogService.ActivityView>> list(@PathVariable String storeId){
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.list(storeId));
    }
    /** 读取活动真实详情；服务端时间、状态和库存使用同一响应锚点。 */
    @GetMapping("/api/seckill-activities/{activityId}")
    public ResponseEntity<SeckillCatalogService.ActivityView> detail(@PathVariable String activityId){
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.detail(activityId));
    }
    /** 仅映射明确的资源缺失，不将数据库异常或堆栈暴露给用户。 */
    @ExceptionHandler(ToolBoundaryException.class)
    public ResponseEntity<Map<String,String>> denied(ToolBoundaryException error){
        return ResponseEntity.status(HttpStatus.NOT_FOUND).cacheControl(CacheControl.noStore())
                .body(Map.of("code",error.getCode(),"error",error.getMessage()));
    }
}
