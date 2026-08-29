package dev.rodolphe.accesscontrol.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * The scheduler the signaling hub uses for its one timer: giving up on a call nobody answers.
 *
 * <p>Declared explicitly rather than relying on Spring Boot's auto-configuration, which only produces
 * a scheduler under conditions this application does not meet. A bean the code depends on should not
 * appear or vanish depending on whether something unrelated is enabled.
 *
 * <p>{@link ThreadPoolTaskScheduler} implements Spring's lifecycle interfaces, so the pool is started
 * with the context and shut down with it — no {@code @PreDestroy} to remember. It replaces the
 * {@code CoroutineScope} the Kotlin hub received, which was tied to the Ktor application's lifetime
 * for the same reason.
 */
@Configuration
public class SchedulingConfig {

    @Bean
    public TaskScheduler taskScheduler() {
        var scheduler = new ThreadPoolTaskScheduler();
        // Two threads: these tasks only fire once per unanswered call and do nothing but send two
        // messages. A larger pool would idle.
        scheduler.setPoolSize(2);
        scheduler.setThreadNamePrefix("signaling-timeout-");
        // A pending "nobody answered" timer has no value once the server is stopping.
        scheduler.setWaitForTasksToCompleteOnShutdown(false);
        return scheduler;
    }
}
