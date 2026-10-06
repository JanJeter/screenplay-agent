package com.urke.saasbackendstarter.screenplay.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
@EnableScheduling
public class AgentExecutorConfig {
    /** Long-lived Gateway SSE consumers: one per active application run. */
    @Bean("agentGatewayStreamExecutor")
    public AsyncTaskExecutor agentGatewayStreamExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(8);
        executor.setMaxPoolSize(16);
        executor.setQueueCapacity(64);
        executor.setThreadNamePrefix("agent-gateway-stream-");
        executor.initialize();
        return executor;
    }

    /** Short REST controls such as dispatch, retry and cancellation must not wait behind streams. */
    @Bean("agentControlExecutor")
    public AsyncTaskExecutor agentControlExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("agent-control-");
        executor.initialize();
        return executor;
    }

    /** Replaying persisted browser events is short-lived; browser streams do not consume a worker thread. */
    @Bean("agentBrowserReplayExecutor")
    public AsyncTaskExecutor agentBrowserReplayExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("agent-browser-replay-");
        executor.initialize();
        return executor;
    }
}
