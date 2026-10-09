package com.urke.saasbackendstarter.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(name = "auth.mail.mode", havingValue = "smtp")
public class SmtpEmailService implements EmailService {
    private final JavaMailSender sender;
    private final String from;

    public SmtpEmailService(JavaMailSender sender, @Value("${auth.mail.from:}") String from) {
        if (from.isBlank() || from.contains("\r") || from.contains("\n")) throw new IllegalArgumentException("Configure AUTH_MAIL_FROM for SMTP mode");
        this.sender = sender;
        this.from = from;
    }

    @Override
    public void sendEmail(String to, String subject, String body) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(from); message.setTo(to); message.setSubject(subject); message.setText(body);
        sender.send(message);
    }
}
