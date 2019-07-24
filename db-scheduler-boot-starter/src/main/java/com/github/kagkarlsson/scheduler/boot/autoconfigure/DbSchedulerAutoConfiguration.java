package com.github.kagkarlsson.scheduler.boot.autoconfigure;

import com.github.kagkarlsson.scheduler.Scheduler;
import com.github.kagkarlsson.scheduler.SchedulerBuilder;
import com.github.kagkarlsson.scheduler.SchedulerName;
import com.github.kagkarlsson.scheduler.boot.config.DbSchedulerCustomizer;
import com.github.kagkarlsson.scheduler.boot.config.DbSchedulerProperties;
import com.github.kagkarlsson.scheduler.task.OnStartup;
import com.github.kagkarlsson.scheduler.task.Task;
import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfigurationPackage;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;

@Configuration
@EnableConfigurationProperties(DbSchedulerProperties.class)
@AutoConfigurationPackage
@AutoConfigureAfter(DataSourceAutoConfiguration.class)
@ConditionalOnBean(DataSource.class)
public class DbSchedulerAutoConfiguration {
    private static final Logger log = LoggerFactory.getLogger(DbSchedulerAutoConfiguration.class);
    private static Predicate<Task<?>> shouldBeStarted = task -> task instanceof OnStartup;

    private final DbSchedulerProperties config;
    private final DataSource existingDataSource;
    private final List<Task<?>> configuredTasks;

    public DbSchedulerAutoConfiguration(DbSchedulerProperties dbSchedulerProperties,
        DataSource dataSource, List<Task<?>> configuredTasks) {
        this.config = Objects.requireNonNull(dbSchedulerProperties, "Can't configure DB Scheduler without required configuration");
        this.existingDataSource = Objects.requireNonNull(dataSource, "An existing javax.sql.DataSource is required");
        this.configuredTasks = Objects.requireNonNull(configuredTasks, "At least one Task must be configured");
    }

    /**
     * Provide an empty customizer if not present in the context.
     */
    @ConditionalOnMissingBean
    @Bean
    public DbSchedulerCustomizer noopCustomizer() {
        return new DbSchedulerCustomizer() {
        };
    }

    @ConditionalOnBean(DataSource.class)
    @ConditionalOnMissingBean
    @Bean(initMethod = "start", destroyMethod = "stop")
    public Scheduler scheduler(DbSchedulerCustomizer customizer) {
        log.info("Creating DB Scheduler using tasks from Spring context: {}", configuredTasks);

        if (existingDataSource instanceof TransactionAwareDataSourceProxy) {
            log.info("Using a transaction aware DataSource");
        } else {
            log.info("The configured DataSource is not transaction aware: {}", existingDataSource);
        }

        // Instantiate a new builder
        final SchedulerBuilder builder = Scheduler.create(existingDataSource, nonStartupTasks(configuredTasks));

        builder.threads(config.getThreads());

        // Polling
        builder.pollingInterval(config.getPollingInterval());
        config.getPollingLimit().ifPresent(builder::pollingLimit);

        builder.heartbeatInterval(config.getHeartbeatInterval());

        // Use scheduler name implementation from customizer if available, otherwise use
        // configured scheduler name (String). If both is absent, use the library default
        if (customizer.schedulerName().isPresent()) {
            builder.schedulerName(customizer.schedulerName().get());
        } else if (config.getSchedulerName() != null) {
            builder.schedulerName(new SchedulerName.Fixed(config.getSchedulerName()));
        }

        builder.tableName(config.getTableName());

        // Use custom serializer if provided
        customizer.serializer().ifPresent(builder::serializer);

        if (config.isImmediateExecutionEnabled()) {
            builder.enableImmediateExecution();
        }

        // Use custom executor service if provided
        customizer.executorService().ifPresent(builder::executorService);

        // Add recurring jobs and jobs that implements OnStartup
        builder.startTasks(startupTasks(configuredTasks));

        return builder.build();
    }

    @SuppressWarnings("unchecked")
    private static <T extends Task<?> & OnStartup> List<T> startupTasks(List<Task<?>> tasks) {
        return tasks.stream()
            .filter(shouldBeStarted)
            .map(task -> (T) task)
            .collect(Collectors.toList());
    }

    private static List<Task<?>> nonStartupTasks(List<Task<?>> tasks) {
        return tasks.stream()
            .filter(shouldBeStarted.negate())
            .collect(Collectors.toList());
    }
}
