package com.urke.saasbackendstarter.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.UUID;

/** Development-only mailbox on disk. It is never served through a public HTTP route. */
@Service
@ConditionalOnProperty(name = "auth.mail.mode", havingValue = "local", matchIfMissing = true)
public class LocalOutboxEmailService implements EmailService {
    private final Path directory;

    public LocalOutboxEmailService(@Value("${auth.mail.local-directory:.local/workbench-dev/mail}") String directory) {
        this.directory = Path.of(directory).toAbsolutePath().normalize();
    }

    @Override
    public void sendEmail(String to, String subject, String body) {
        if (to.contains("\r") || to.contains("\n") || subject.contains("\r") || subject.contains("\n")) throw new IllegalArgumentException("Invalid mail headers");
        try {
            Files.createDirectories(directory);
            Path output = directory.resolve(UUID.randomUUID() + ".eml");
            Files.writeString(output, "To: " + to + "\nSubject: " + subject + "\nContent-Type: text/plain; charset=UTF-8\n\n" + body + "\n",
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        } catch (IOException failure) { throw new IllegalStateException("Local mail could not be written"); }
    }
}
