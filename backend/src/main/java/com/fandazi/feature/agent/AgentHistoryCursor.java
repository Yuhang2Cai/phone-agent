// 历史快照游标：使用既有服务端JWT密钥作独立用途HMAC，绑定账户/会话和消息、事件两组读取上界。
package com.fandazi.feature.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Set;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/** 游标不是登录令牌或交易凭证；不可跨会话/账户重用，任何篡改均显式400拒绝。 */
@Component
public class AgentHistoryCursor {
    private final ObjectMapper json;
    private final SecretKeySpec key;
    private static final Base64.Encoder ENCODER=Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER=Base64.getUrlDecoder();
    /** 独立用途前缀避免与登录JWT或其他HMAC数据混用；不新增硬编码密钥。 */
    private static final String PURPOSE="fandazi-history-v1:";

    /** 注入项目既有私有密钥，按UTF-8固定字节；密钥和游标均不记录到日志。 */
    public AgentHistoryCursor(ObjectMapper json,@Value("${security.jwt.secret}") String secret) {
        this.json=json;this.key=new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8),"HmacSHA256");
    }

    /** 两组上界只在第一页固定；后续游标只移动已返回的位置，不追随新写入。 */
    public record Snapshot(Long ownerId,String sessionId,long messageUpper,long eventUpper,long messageAfter,long eventAfter) {}

    /** 输出签名的六字段快照；编码异常为服务端配置故障，不退回未签名或随机游标。 */
    public String encode(Snapshot snapshot) {
        try {
            String data=ENCODER.encodeToString(json.writeValueAsBytes(snapshot));
            return data+"."+ENCODER.encodeToString(signature(data));
        } catch(Exception error) { throw new IllegalStateException("历史游标编码失败",error); }
    }

    /** 先验证签名再解析固定字段，最后核对所有权和位置范围；非法游标不读取数据库内容。 */
    public Snapshot decode(String cursor,Long ownerId,String sessionId) {
        try {
            if(cursor==null || cursor.length()>4096)throw new IllegalArgumentException();
            String[] parts=cursor.split("\\.",-1);
            if(parts.length!=2 || !MessageDigest.isEqual(signature(parts[0]),DECODER.decode(parts[1])))throw new IllegalArgumentException();
            var tree=json.readTree(DECODER.decode(parts[0]));
            Set<String> fields=new java.util.HashSet<>();tree.fieldNames().forEachRemaining(fields::add);
            if(!fields.equals(Set.of("ownerId","sessionId","messageUpper","eventUpper","messageAfter","eventAfter")))throw new IllegalArgumentException();
            Snapshot value=json.treeToValue(tree,Snapshot.class);
            // 未经签名的客户端只能猜测位置；签名有效仍必须属于当前认证账户及当前公开会话。
            if(!ownerId.equals(value.ownerId()) || !sessionId.equals(value.sessionId()) || value.messageUpper()<0 || value.eventUpper()<0
                    || value.messageAfter()<0 || value.messageAfter()>value.messageUpper() || value.eventAfter()<0 || value.eventAfter()>value.eventUpper())throw new IllegalArgumentException();
            return value;
        } catch(Exception error) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"历史游标无效"); }
    }

    /** 每次建立独立Mac实例避免线程共享；用途前缀和固定编码共同约束签名原文。 */
    private byte[] signature(String data) throws Exception {
        Mac mac=Mac.getInstance("HmacSHA256");mac.init(key);return mac.doFinal((PURPOSE+data).getBytes(StandardCharsets.UTF_8));
    }
}
