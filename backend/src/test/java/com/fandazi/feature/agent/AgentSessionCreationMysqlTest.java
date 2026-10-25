// AG06-B01真实MySQL有界验证：固定两方事务，不计时、不循环重试、不访问模型或业务工具。
package com.fandazi.feature.agent;
import com.fandazi.feature.auth.UserEntity;
import com.fandazi.feature.auth.UserRepository;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.IntFunction;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;

/** 必须显式指定自有测试库；未启用时的跳过不能计入真实MySQL通过，正式收尾须有单独实测。 */
@EnabledIfEnvironmentVariable(named="AG06_B01_MYSQL_ENABLED",matches="true")
@SpringBootTest(properties={
 "spring.datasource.url=${AG06_B01_DB_URL}","spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver",
 "spring.datasource.username=${AG06_B01_DB_USER}","spring.datasource.password=${AG06_B01_DB_PASSWORD}",
 "spring.jpa.hibernate.ddl-auto=validate","spring.flyway.enabled=true","seed.enabled=false",
 "spring.datasource.hikari.transaction-isolation=TRANSACTION_REPEATABLE_READ"})
class AgentSessionCreationMysqlTest {
 @Autowired AgentSessionRepository repository;@Autowired UserRepository users;
 @Autowired AgentConversationSourceService sources;@Autowired JdbcTemplate jdbc;
 @Autowired PlatformTransactionManager transactions;
 /** 仅核库种类与当前连接隔离，禁止把H2或其他库的结果冒认为本组实测。 */
 @BeforeEach void mysqlOnly(){assertTrue(jdbc.queryForObject("select version()",String.class).startsWith("8."));assertEquals("REPEATABLE-READ",jdbc.queryForObject("select @@transaction_isolation",String.class));}
 /** 同用户同会话两个独立服务对象/事务竞争，返回同一行及同epoch。 */
 @Test void sameOwnerSameKeyCreatesExactlyOneRow() throws Exception {
  long owner=user();String key=key();var result=pair(i->service().startTaskSession(owner,key,"same-"+i));
  assertEquals(result.get(0).getId(),result.get(1).getId());assertEquals(result.get(0).conversationEpoch(),result.get(1).conversationEpoch());assertCount(key,1);
 }
 /** 同一用户的两个新会话不因索引间隙锁互等；这是原历史失败的关键形态。 */
 @Test void sameOwnerDifferentKeysBothCommit() throws Exception {
  long owner=user();String[] keys={key(),key()};var result=pair(i->service().startTaskSession(owner,keys[i],"different-"+i));
  assertNotEquals(result.get(0).getId(),result.get(1).getId());for(String key:keys)assertCount(key,1);
 }
 /** 竞争同键的不同用户只能一方成功，另一方404且不能改写胜方归属。 */
 @Test void competingOwnersCannotTakeOverSession() throws Exception {
  long[] owners={user(),user()};String key=key();var result=pair(i->{
   try {service().startTaskSession(owners[i],key,"owner-"+i);return i;}
   catch(ResponseStatusException e){assertEquals(404,e.getStatusCode().value());return -1;}
  });
  assertEquals(1,result.stream().filter(i->i>=0).count());int winner=result.stream().filter(i->i>=0).findFirst().orElseThrow();
  assertEquals(owners[winner],repository.findBySessionId(key).orElseThrow().ownerId());assertCount(key,1);
 }
 /** 旧2.0和2.1分配也经过原子建行，仍各自保留请求版本。 */
 @Test void legacyProtocolsCreateWithoutGapLockCycle() throws Exception {
  long owner=user();String[] keys={key(),key()};var result=pair(i->service().start(owner,keys[i],AgentMode.CONFIRM,"legacy-"+i,i==0?"2.0":"2.1"));
  assertEquals("2.0",result.get(0).getContextProtocolVersion());assertEquals("2.1",result.get(1).getContextProtocolVersion());
 }
 /** 真正来源外层事务并发，不用串行seed包装掩盖调用链上游的缺失行锁。 */
 @Test void sourceTransactionsCanCreateTwoMissingSessions() throws Exception {
  long owner=user();String[] keys={key(),key()};var result=pair(i->sources.beginInput(owner,keys[i],"查看订单","input-"+i,AgentMode.CONFIRM,"source-"+i));
  for(var row:result)assertEquals("2.1",row.protocolVersion());for(String key:keys)assertCount(key,1);
 }
 /** 在真正数据库事务中模拟后续失败，关闭事务后从新连接读取，不得留下新会话。 */
 @Test void failingOuterTransactionLeavesNoSession(){
  long owner=user();String key=key();assertThrows(IllegalStateException.class,()->new TransactionTemplate(transactions).execute(status->{
   service().startTaskSession(owner,key,"rollback");throw new IllegalStateException("指定后续失败");}));assertCount(key,0);
 }
 /** 创建新的服务对象，不共享应用层锁；事务由调用方模板显式管理，连接按线程独立借用。 */
 private AgentSessionService service(){return new AgentSessionService(repository,users);}
 /** 所有数据使用本次唯一合成前缀；不清理既有证据数据或其他用户。 */
 private long user(){String key=UUID.randomUUID().toString();return users.saveAndFlush(new UserEntity("uc-mysql-"+key,key+"@example.invalid","synthetic-hash")).getId();}
 /** 生成全新测试键，不重放此前业务scope。 */
 private String key(){return "uc-mysql-"+UUID.randomUUID();}
 /** 固定两参与者、一轮、30秒Future上限；失败直接保留，不重试提高通过率。 */
 private <T> List<T> pair(IntFunction<T> operation) throws Exception {
  var gate=new CyclicBarrier(2);var pool=Executors.newFixedThreadPool(2);
  try {var futures=new ArrayList<Future<T>>();for(int i=0;i<2;i++){final int index=i;
   futures.add(pool.submit(()->new TransactionTemplate(transactions).execute(status->{
    try {gate.await(10,TimeUnit.SECONDS);}catch(Exception e){throw new IllegalStateException("双事务前置未就绪",e);}return operation.apply(index);
   })));}
   var results=new ArrayList<T>();for(var future:futures)results.add(future.get(30,TimeUnit.SECONDS));return results;
  } finally {pool.shutdownNow();assertTrue(pool.awaitTermination(10,TimeUnit.SECONDS),"测试自有线程必须回收");}
 }
 /** 从事务外核验真实唯一行数，不仅凭接口成功返回判断。 */
 private void assertCount(String key,int expected){assertEquals(expected,jdbc.queryForObject("select count(*) from agent_sessions where session_id=?",Integer.class,key));}
}
