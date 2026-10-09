package com.urke.saasbackendstarter.service;

import com.urke.saasbackendstarter.domain.*;
import com.urke.saasbackendstarter.dto.user.UserCreateRequest;
import com.urke.saasbackendstarter.exception.AccountApiException;
import com.urke.saasbackendstarter.repository.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest(properties = {"spring.jpa.hibernate.ddl-auto=create", "spring.jpa.show-sql=false", "auth.registration-organization-slug=account-test", "auth.demo-seed-enabled=false"})
@ActiveProfiles("test")
@Testcontainers
class AccountPersistenceIntegrationTest {
    @Container static final PostgreSQLContainer<?> DATABASE = new PostgreSQLContainer<>("postgres:16-alpine");
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", DATABASE::getJdbcUrl);
        registry.add("spring.datasource.username", DATABASE::getUsername);
        registry.add("spring.datasource.password", DATABASE::getPassword);
    }
    @Autowired AccountLifecycleService accounts;
    @Autowired AccountRateLimiter limits;
    @Autowired UserRepository users;
    @Autowired OrganizationRepository organizations;
    @Autowired RoleRepository roles;
    @Autowired JdbcTemplate jdbc;
    @MockBean EmailService mail;

    @Test void postgresPersistsHashedOneUseTokensAndSerializesVerificationAndRateLimits() throws Exception {
        Organization organization = organizations.saveAndFlush(Organization.builder().name("Account test").slug("account-test").build());
        roles.saveAndFlush(Role.builder().name("USER").organization(organization).permissions(Set.of()).build());
        var bodies = new CopyOnWriteArrayList<String>();
        doAnswer(invocation -> { bodies.add(invocation.getArgument(2)); return null; }).when(mail).sendEmail(anyString(), anyString(), anyString());
        accounts.register(UserCreateRequest.builder().email("database-reader@example.test").password("database-test-password").fullName("Reader").build());
        assertThat(bodies).hasSize(1);
        String raw = bodies.getFirst().split("token=")[1].split("\\s")[0];
        String hash = jdbc.queryForObject("select token_hash from account_action_tokens", String.class);
        assertThat(hash != null && hash.matches("[0-9a-f]{64}") && !hash.equals(raw)).isTrue();
        CountDownLatch start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            Callable<Boolean> verify = () -> { start.await(); try { accounts.verifyEmail(raw); return true; } catch (AccountApiException failure) { return false; } };
            var first = pool.submit(verify); var second = pool.submit(verify); start.countDown();
            int successes = (first.get(15, TimeUnit.SECONDS) ? 1 : 0) + (second.get(15, TimeUnit.SECONDS) ? 1 : 0);
            assertThat(successes).isEqualTo(1);
        }
        assertThat(users.findByEmailIgnoreCaseAndDeletedFalse("database-reader@example.test").orElseThrow().isEmailVerified()).isTrue();
        assertThat(jdbc.queryForObject("select count(*) from account_action_tokens where consumed_at is not null", Integer.class)).isEqualTo(1);
        accounts.forgotPassword("missing@example.test");
        assertThatThrownBy(() -> accounts.forgotPassword("missing@example.test")).isInstanceOf(AccountApiException.class);
        assertThat(jdbc.queryForObject("select count(*) from account_action_limits", Integer.class)).isGreaterThanOrEqualTo(2);
        String key = "concurrent-limit-test";
        CountDownLatch simultaneous = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            Callable<Boolean> acquire = () -> { simultaneous.await(); try { limits.check(key, 60, 1); return true; } catch (AccountApiException denied) { return false; } };
            var first = pool.submit(acquire); var second = pool.submit(acquire); simultaneous.countDown();
            assertThat((first.get(15, TimeUnit.SECONDS) ? 1 : 0) + (second.get(15, TimeUnit.SECONDS) ? 1 : 0)).isEqualTo(1);
        }
    }
}
