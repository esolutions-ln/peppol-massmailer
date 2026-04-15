package com.esolutions.massmailer.config;

import com.esolutions.massmailer.infrastructure.adapters.common.ErpAdapterProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.web.client.RestTemplate;
import java.util.concurrent.Executor;
import java.util.concurrent.Semaphore;

/**
 * Configures the async executor backed by virtual threads (Project Loom)
 * and a rate-limiting semaphore for SMTP throttling.
 */
@Configuration
@EnableConfigurationProperties({MailerProperties.class, ErpAdapterProperties.class, PdfWatcherProperties.class})
public class AsyncMailConfig implements AsyncConfigurer {

    private final MailerProperties props;

    public AsyncMailConfig(MailerProperties props) {
        this.props = props;
    }

    /**
     * Virtual-thread executor — each mail dispatch runs on its own
     * lightweight virtual thread, ideal for I/O-bound SMTP calls.
     */
    @Override
    @Bean(name = "mailExecutor")
    public Executor getAsyncExecutor() {
        var executor = new SimpleAsyncTaskExecutor("mail-vt-");
        executor.setVirtualThreads(true);
        // Cap concurrency to rate limit — prevents SMTP flooding
        executor.setConcurrencyLimit(props.rateLimit());
        return executor;
    }

    /**
     * Semaphore-based rate limiter for fine-grained SMTP throttling.
     * Permits = configured rate-limit (emails/second window).
     */
    @Bean
    public Semaphore smtpRateLimiter() {
        return new Semaphore(props.rateLimit(), true);
    }

    /**
     * RestTemplate for PEPPOL simplified HTTP delivery to AP endpoints.
     * 10s connect / 30s read — AP endpoints may be slow on first contact.
     */
    @Bean
    public RestTemplate restTemplate() {
        var rt = new RestTemplate();
        // 10s connect / 30s read for PEPPOL AP endpoints
        var factory = new org.springframework.http.client.SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(10_000);
        factory.setReadTimeout(30_000);
        rt.setRequestFactory(factory);
        return rt;
    }
}
