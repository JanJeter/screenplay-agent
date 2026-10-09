package com.urke.saasbackendstarter.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import static org.assertj.core.api.Assertions.*;

class LocalOutboxEmailServiceTest {
    @TempDir Path directory;

    @Test void developmentMailIsUtf8WithClickableLinkAndNoNetworkTransport() throws Exception {
        var mail = new LocalOutboxEmailService(directory.toString());
        mail.sendEmail("reader@example.test", "分镜工作台：验证邮箱", "请打开链接：http://127.0.0.1:5174/verify-email?token=test-only");
        try (var files = Files.list(directory)) {
            var messages = files.toList(); assertThat(messages).hasSize(1);
            assertThat(messages.getFirst().getFileName().toString()).endsWith(".eml");
            String body = Files.readString(messages.getFirst(), StandardCharsets.UTF_8);
            assertThat(body.contains("To: reader@example.test\n")).isTrue();
            assertThat(body.contains("分镜工作台：验证邮箱")).isTrue();
            assertThat(body.contains("http://127.0.0.1:5174/verify-email?token=")).isTrue();
        }
    }

    @Test void headerInjectionDoesNotCreateAnOutboxFile() throws Exception {
        var mail = new LocalOutboxEmailService(directory.toString());
        assertThatThrownBy(() -> mail.sendEmail("a@example.test\nBcc: other@example.test", "hello", "body")).isInstanceOf(IllegalArgumentException.class);
        try (var files = Files.list(directory)) { assertThat(files.count()).isZero(); }
    }
}
