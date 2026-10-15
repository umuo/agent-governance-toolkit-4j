package io.agt.examples.live.web;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** 本地 Agent 实验服务，默认仅监听回环地址。 */
@SpringBootApplication
public class AgentApplication {
    public static void main(String[] args) throws Exception {
        if (args.length > 0 && args[0].equals("--cli")) {
            io.agt.examples.live.GeneralAgentDemo.main(
                    java.util.Arrays.copyOfRange(args, 1, args.length));
            return;
        }
        var settings =
                io.agt.examples.live.config.LocalEnvironment.load(
                        java.nio.file.Path.of("."), System.getenv());
        var application = new SpringApplication(AgentApplication.class);
        application.setDefaultProperties(
                java.util.Map.of("server.port", settings.getOrDefault("AGT_SERVER_PORT", "8080")));
        application.run(args);
    }
}
