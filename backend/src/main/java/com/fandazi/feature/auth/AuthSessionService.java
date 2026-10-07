/* 长期会话服务：签发、事务轮换、精确幂等恢复和撤销；依赖JPA、账号仓库及原JWT服务。 */
package com.fandazi.feature.auth;

import com.fandazi.core.security.JwtService;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 自有认证协议的服务端会话边界，刷新凭据只经认证响应返回，绝不作为普通接口JWT。 */
@Service
public class AuthSessionService {
    private final AuthSessionRepository sessions;
    private final UserRepository users;
    private final JwtService jwt;
    private final long idleDays,absoluteDays;
    private final byte[] rotationKey;
    private final SecureRandom random=new SecureRandom();

    /** 装配原JWT密钥的独立用途派生键；密钥不写数据库，配置缺失或无效期限立即阻止启动。 */
    public AuthSessionService(AuthSessionRepository sessions,UserRepository users,JwtService jwt,
            @Value("${security.jwt.secret}") String secret,
            @Value("${security.session.idle-days:30}") long idleDays,
            @Value("${security.session.absolute-days:180}") long absoluteDays) {
        if(idleDays<1 || absoluteDays<idleDays)throw new IllegalArgumentException("刷新会话期限配置无效");
        this.sessions=sessions;this.users=users;this.jwt=jwt;this.idleDays=idleDays;this.absoluteDays=absoluteDays;
        this.rotationKey=digestBytes("fandazi-refresh-rotation-v1|"+secret);
    }

    /** 密码认证成功后创建一个独立会话，返回带到期时间的完整凭据包；必须在账号事务中调用。 */
    @Transactional
    public AuthDtos.AuthResponse issue(UserEntity user) {
        // 输入规范化：初始秘密来自安全随机数，UUID只提供查询身份，不能替代秘密。
        Instant now=Instant.now();AuthSession session=new AuthSession();session.id=UUID.randomUUID().toString();session.userId=user.getId();
        byte[] secret=new byte[32];random.nextBytes(secret);
        String raw=session.id+"."+Base64.getUrlEncoder().withoutPadding().encodeToString(secret);
        session.tokenHash=hash(raw);session.absoluteExpiresAt=now.plus(absoluteDays,ChronoUnit.DAYS);
        session.idleExpiresAt=min(now.plus(idleDays,ChronoUnit.DAYS),session.absoluteExpiresAt);
        sessions.save(session);
        return response(session,user,raw);
    }

    /** 使用当前秘密轮换；相同上一轮请求恢复同一个后继，重用攻击撤销事务必须提交。 */
    @Transactional(noRollbackFor=RefreshRejectedException.class)
    public AuthDtos.AuthResponse refresh(AuthDtos.RefreshRequest request) {
        // 校验查询身份和请求标识，再锁住会话。非法秘密不得仅凭已知UUID修改其他人的状态。
        String id=sessionId(request.refreshToken());
        AuthSession session=sessions.findForUpdate(id).orElseThrow(RefreshRejectedException::new);
        Instant now=Instant.now();String presented=hash(request.refreshToken()),key=hash(request.requestId());
        if(!active(session,now))throw new RefreshRejectedException();
        UserEntity user=users.findById(session.userId).orElseThrow(RefreshRejectedException::new);
        String successor=successor(request.refreshToken(),request.requestId(),id);
        if(equal(presented,session.tokenHash)) {
            // 唯一真正轮换分支：只前移空闲期限，不改会话身份或绝对到期。
            session.previousTokenHash=session.tokenHash;session.previousRequestHash=key;session.tokenHash=hash(successor);
            session.idleExpiresAt=min(now.plus(idleDays,ChronoUnit.DAYS),session.absoluteExpiresAt);
            return response(session,user,successor);
        }
        if(equal(presented,session.previousTokenHash)) {
            // 丢响应恢复仅允许同一标识且推导结果仍是当前凭据，不延长空闲期限，不再次轮换。
            if(equal(key,session.previousRequestHash) && equal(hash(successor),session.tokenHash))return response(session,user,successor);
            // 确认使用的是上一轮真实秘密且换了请求标识，撤销该会话，立即使绑定JWT失效。
            session.revokedAt=now;
        }
        throw new RefreshRejectedException();
    }

    /** 幂等撤销当前或上一轮真实秘密所在的会话；任意伪造秘密和未知ID均不影响其他人。 */
    @Transactional
    public void revoke(String raw) {
        String id;
        try {id=sessionId(raw);}catch(RefreshRejectedException ignored){return;}
        sessions.findForUpdate(id).ifPresent(session->{
            String presented=hash(raw);
            if(equal(presented,session.tokenHash)||equal(presented,session.previousTokenHash))session.revokedAt=Instant.now();
        });
    }

    /** JWT签名和过期验证后仍需核对sid归属及撤销事实；返回false不会改变数据库。 */
    @Transactional(readOnly=true)
    public boolean accepts(String id,Long userId) {
        return sessions.findById(id).map(s->s.userId.equals(userId)&&active(s,Instant.now())&&users.existsById(userId)).orElse(false);
    }

    /** 构造兼容旧token/user字段的认证响应，访问JWT携带稳定sid用于在线撤销。 */
    private AuthDtos.AuthResponse response(AuthSession session,UserEntity user,String raw) {
        return new AuthDtos.AuthResponse(jwt.createSessionToken(user.getId(),session.id),AuthDtos.UserResponse.from(user),session.id,raw,
            jwt.accessExpiresAtEpochMs(),min(session.idleExpiresAt,session.absoluteExpiresAt).toEpochMilli());
    }
    /** 限制令牌输入结构与长度，不输出原输入，不允许恶意值成为SQL或日志内容。 */
    private String sessionId(String raw) {
        if(raw==null||!raw.matches("[0-9a-fA-F-]{36}\\.[A-Za-z0-9_-]{43}"))throw new RefreshRejectedException();
        try{return UUID.fromString(raw.substring(0,36)).toString();}catch(IllegalArgumentException e){throw new RefreshRejectedException();}
    }
    /** 后继用独立HMAC域推导，因此同一丢响应请求无需在数据库保存可还原秘密。 */
    private String successor(String raw,String requestId,String id) {
        try {
            Mac mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(rotationKey,"HmacSHA256"));
            return id+"."+Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal((raw+"|"+requestId).getBytes(StandardCharsets.UTF_8)));
        }catch(GeneralSecurityException e){throw new IllegalStateException("刷新凭据派生失败",e);}
    }
    /** 摘要比较不返回秘密正文；无上一轮摘要时直接不匹配。 */
    private static boolean equal(String a,String b) {return a!=null&&b!=null&&MessageDigest.isEqual(a.getBytes(StandardCharsets.US_ASCII),b.getBytes(StandardCharsets.US_ASCII));}
    /** 明确撤销、空闲到期或绝对到期任一成立均拒绝，边界时间不算仍有效。 */
    private static boolean active(AuthSession s,Instant now) {return s.revokedAt==null&&now.isBefore(s.idleExpiresAt)&&now.isBefore(s.absoluteExpiresAt);}
    /** 将32字节摘要编码为定长十六进制，仅用于持久化和比较。 */
    private static String hash(String value) {return HexFormat.of().formatHex(digestBytes(value));}
    /** 原始摘要只在内存，算法不可用视为服务配置错误，不伪造认证成功。 */
    private static byte[] digestBytes(String value) {
        try{return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));}catch(NoSuchAlgorithmException e){throw new IllegalStateException("摘要算法不可用",e);}
    }
    /** 空闲截止不能越过创建时固定的绝对截止。 */
    private static Instant min(Instant a,Instant b) {return a.isBefore(b)?a:b;}
}
