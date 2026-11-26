package ag07.fixture;

import com.fandazi.FandaziApplication;
import org.springframework.boot.SpringApplication;

/** AG07-04零模型真实服务启动器：只在测试classpath，使用产品入口，无新增HTTP夹具或种子业务。 */
public final class AdmissionFixtureApplication {
    /** 固定地址、端口、轮次模式和保护开关；stdin关闭仅结束本任务拥有的应用。 */
    public static void main(String[] args) throws Exception {
        if(!"127.0.0.1".equals(System.getenv("SERVER_ADDRESS")) || !"18073".equals(System.getenv("SERVER_PORT"))
                || !"ag07-04-zero-model".equals(System.getenv("AG07_FIXTURE_MODE"))
                || !"true".equals(System.getenv("AG07_ADMISSION_ENABLED")))
            throw new IllegalArgumentException("unregistered_admission_fixture");
        try(var context=new SpringApplication(FandaziApplication.class).run(args)) {
            new java.io.BufferedReader(new java.io.InputStreamReader(System.in,java.nio.charset.StandardCharsets.UTF_8)).readLine();
        }
    }
}
