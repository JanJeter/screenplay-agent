package com.urke.saasbackendstarter.screenplay.integration;

import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/** Exercises the same PostgreSQL compatibility script used on an existing deployment. */
@Testcontainers
class ContentRevisionMigrationTest {
    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("migration")
            .withUsername("migration")
            .withPassword("migration");

    @Test
    void backfillsLegacyScriptVersionsBeforeEnforcingNotNull() throws Exception {
        try (var connection = postgres.createConnection("" ); var statement = connection.createStatement()) {
            statement.execute("create table script_versions (id bigint primary key, raw_text text not null)");
            statement.execute("insert into script_versions (id, raw_text) values (1, 'legacy screenplay')");
            String migration;
            try (var stream = getClass().getResourceAsStream("/schema.sql")) {
                assertThat(stream).isNotNull();
                migration = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            }
            statement.execute(migration.replace("@@", ";"));
            try (var result = statement.executeQuery("select content_revision, is_nullable from script_versions "
                    + "join information_schema.columns on table_schema = current_schema() "
                    + "and table_name = 'script_versions' and column_name = 'content_revision' where id = 1")) {
                assertThat(result.next()).isTrue();
                assertThat(result.getLong(1)).isEqualTo(1L);
                assertThat(result.getString(2)).isEqualTo("NO");
            }
        }
    }
}
