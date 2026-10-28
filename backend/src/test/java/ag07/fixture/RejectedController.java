package ag07.fixture;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** 仅供手工MockMvc装配的错误出口；位于生产扫描包之外，不得被主应用发现。 */
@RestController
public class RejectedController {
    private final RuntimeException failure;
    /** 接收被测客户端实际抛出的异常，不重新构造业务响应。 */
    public RejectedController(RuntimeException failure){this.failure=failure;}
    /** 将既有失败交给正式Advice；无网络、重试或业务副作用。 */
    @GetMapping("/synthetic-upstream") public void request(){throw failure;}
}
