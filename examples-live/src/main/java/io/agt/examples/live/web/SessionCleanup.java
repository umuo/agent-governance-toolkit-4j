package io.agt.examples.live.web;

import jakarta.servlet.http.HttpSessionEvent;
import jakarta.servlet.http.HttpSessionListener;
import org.springframework.boot.web.servlet.ServletListenerRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** HTTP 会话过期时释放内存上下文及运行配额，保留工作区文件供审计。 */
@Configuration
public class SessionCleanup {
    @Bean
    ServletListenerRegistrationBean<HttpSessionListener> conversationCleanup(
            WebAgentService service) {
        return new ServletListenerRegistrationBean<>(
                new HttpSessionListener() {
                    @Override
                    public void sessionDestroyed(HttpSessionEvent event) {
                        service.release(event.getSession().getId());
                    }
                });
    }
}
