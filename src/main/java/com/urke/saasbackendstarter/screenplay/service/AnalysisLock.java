package com.urke.saasbackendstarter.screenplay.service;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import javax.sql.DataSource;
import java.sql.SQLException;
import java.util.concurrent.Semaphore;
import java.util.function.Supplier;

/** PostgreSQL transaction lock survives the inner replacement rollback and spans failure recording. */
@Component
public class AnalysisLock {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final Semaphore capacity;

    public AnalysisLock(JdbcTemplate jdbc, PlatformTransactionManager transactionManager,
                        DataSource dataSource,
                        @Value("${screenplay.analysis.max-concurrent:4}") int maxConcurrent) throws SQLException {
        this.jdbc = jdbc;
        if (maxConcurrent < 1) {
            throw new IllegalArgumentException("screenplay.analysis.max-concurrent must be positive");
        }
        int permits = maxConcurrent;
        if (dataSource.isWrapperFor(HikariDataSource.class)) {
            int poolSize = dataSource.unwrap(HikariDataSource.class).getMaximumPoolSize();
            // Hikari's default size is populated when the lazy pool starts.
            if (poolSize < 0) poolSize = 10;
            permits = Math.min(permits, poolSize / 2);
        }
        if (permits < 1) {
            throw new IllegalArgumentException("Script analysis requires a database pool of at least two connections");
        }
        capacity = new Semaphore(permits);
        transaction = new TransactionTemplate(transactionManager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public <T> T withLock(Long scriptId, Supplier<T> work) {
        // Reserve capacity before taking a connection: each analysis needs its lock plus one inner transaction.
        if (!capacity.tryAcquire()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Analysis capacity is busy; retry later");
        }
        try {
            return transaction.execute(status -> {
                Boolean acquired = jdbc.queryForObject(
                        "select pg_try_advisory_xact_lock(hashtextextended(?, 0))",
                        Boolean.class, "screenplay:analyze:" + scriptId);
                if (!Boolean.TRUE.equals(acquired)) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "Script analysis is already running");
                }
                return work.get();
            });
        } finally {
            capacity.release();
        }
    }
}

