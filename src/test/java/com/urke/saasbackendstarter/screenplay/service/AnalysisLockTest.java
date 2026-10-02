package com.urke.saasbackendstarter.screenplay.service;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.web.server.ResponseStatusException;

import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AnalysisLockTest {
    @Mock
    private JdbcTemplate jdbc;
    @Mock
    private PlatformTransactionManager transactionManager;

    @Test
    void limitsAnalysisToHalfThePoolWithoutOpeningAnExtraTransaction() throws SQLException {
        stubTransactions();
        when(jdbc.queryForObject(anyString(), eq(Boolean.class), anyString())).thenReturn(true);
        try (HikariDataSource dataSource = pool(5)) {
            AnalysisLock lock = new AnalysisLock(jdbc, transactionManager, dataSource, 10);

            String result = lock.withLock(1L, () -> lock.withLock(2L, () -> {
                assertThatThrownBy(() -> lock.withLock(3L, () -> "must not execute"))
                        .isInstanceOfSatisfying(ResponseStatusException.class,
                                exception -> assertThat(exception.getStatusCode())
                                        .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
                verify(transactionManager, times(2)).getTransaction(any(TransactionDefinition.class));
                verify(jdbc, times(2)).queryForObject(anyString(), eq(Boolean.class), anyString());
                return "completed";
            }));

            assertThat(result).isEqualTo("completed");
            assertThat(lock.withLock(3L, () -> "available again")).isEqualTo("available again");
            verify(transactionManager, times(3)).commit(any(TransactionStatus.class));
        }
    }

    @Test
    void configuredMaximumCanFurtherRestrictAnalysisCapacity() throws SQLException {
        stubTransactions();
        when(jdbc.queryForObject(anyString(), eq(Boolean.class), anyString())).thenReturn(true);
        try (HikariDataSource dataSource = pool(10)) {
            AnalysisLock lock = new AnalysisLock(jdbc, transactionManager, dataSource, 1);

            lock.withLock(1L, () -> {
                assertThatThrownBy(() -> lock.withLock(2L, () -> "must not execute"))
                        .isInstanceOfSatisfying(ResponseStatusException.class,
                                exception -> assertThat(exception.getStatusCode())
                                        .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
                return null;
            });

            verify(transactionManager).getTransaction(any(TransactionDefinition.class));
            verify(jdbc).queryForObject(anyString(), eq(Boolean.class), anyString());
        }
    }

    @Test
    void releasesCapacityWhenAnalysisWorkFails() throws SQLException {
        stubTransactions();
        when(jdbc.queryForObject(anyString(), eq(Boolean.class), anyString())).thenReturn(true);
        try (HikariDataSource dataSource = pool(2)) {
            AnalysisLock lock = new AnalysisLock(jdbc, transactionManager, dataSource, 4);
            IllegalStateException failure = new IllegalStateException("Analysis failed");

            assertThatThrownBy(() -> lock.withLock(1L, () -> {
                throw failure;
            })).isSameAs(failure);

            assertThat(lock.withLock(1L, () -> "retry succeeded")).isEqualTo("retry succeeded");
            verify(transactionManager, times(2)).getTransaction(any(TransactionDefinition.class));
            verify(transactionManager).rollback(any(TransactionStatus.class));
            verify(transactionManager).commit(any(TransactionStatus.class));
        }
    }

    @Test
    void rejectsDatabaseLockContentionAndReleasesCapacity() throws SQLException {
        stubTransactions();
        when(jdbc.queryForObject(anyString(), eq(Boolean.class), anyString())).thenReturn(false, true);
        try (HikariDataSource dataSource = pool(2)) {
            AnalysisLock lock = new AnalysisLock(jdbc, transactionManager, dataSource, 4);
            AtomicInteger workInvocations = new AtomicInteger();

            assertThatThrownBy(() -> lock.withLock(42L, workInvocations::incrementAndGet))
                    .isInstanceOfSatisfying(ResponseStatusException.class,
                            exception -> assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
            assertThat(workInvocations).hasValue(0);

            assertThat(lock.withLock(42L, workInvocations::incrementAndGet)).isEqualTo(1);
            assertThat(workInvocations).hasValue(1);
            verify(transactionManager).rollback(any(TransactionStatus.class));
            verify(transactionManager).commit(any(TransactionStatus.class));
        }
    }

    @Test
    void rejectsPoolsWithoutEnoughConnectionsAtStartup() {
        try (HikariDataSource dataSource = pool(1)) {
            assertThatThrownBy(() -> new AnalysisLock(jdbc, transactionManager, dataSource, 4))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("at least two connections");
            verifyNoInteractions(jdbc, transactionManager);
        }
    }

    @Test
    void rejectsNonpositiveConfiguredCapacityAtStartup() {
        try (HikariDataSource dataSource = pool(10)) {
            assertThatThrownBy(() -> new AnalysisLock(jdbc, transactionManager, dataSource, 0))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("must be positive");
            verifyNoInteractions(jdbc, transactionManager);
        }
    }

    private void stubTransactions() {
        when(transactionManager.getTransaction(any(TransactionDefinition.class)))
                .thenAnswer(invocation -> new SimpleTransactionStatus());
    }

    private static HikariDataSource pool(int maximumPoolSize) {
        HikariDataSource dataSource = new HikariDataSource();
        dataSource.setMaximumPoolSize(maximumPoolSize);
        return dataSource;
    }
}
