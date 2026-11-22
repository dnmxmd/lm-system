package com.lms.notification.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

/**
 * Sends mail when an SMTP host is configured, and logs it when one is not.
 *
 * <p>The decision is made on the configured host rather than on whether a {@code JavaMailSender}
 * bean exists. Spring Boot creates that bean whenever {@code spring.mail.host} is <em>present</em>,
 * and a blank value counts as present — so trusting the bean meant every notification tried to
 * reach a server at "" and blocked the caller until the connection gave up.
 */
@Service
public class EmailService {

    private static final Logger log = LoggerFactory.getLogger(EmailService.class);

    private final ObjectProvider<JavaMailSender> mailSender;
    private final MailProperties properties;
    private final boolean smtpConfigured;

    public EmailService(ObjectProvider<JavaMailSender> mailSender, MailProperties properties,
                        @Value("${spring.mail.host:}") String smtpHost) {
        this.mailSender = mailSender;
        this.properties = properties;
        this.smtpConfigured = smtpHost != null && !smtpHost.isBlank();
        if (!smtpConfigured) {
            log.info("No SMTP host is configured; notifications will be written to this log");
        }
    }

    public void send(String to, EmailTemplates.Message message) {
        JavaMailSender sender = smtpConfigured ? mailSender.getIfAvailable() : null;
        if (sender == null) {
            log.info("Mail to {}: \"{}\"", to, message.subject());
            return;
        }
        SimpleMailMessage mail = new SimpleMailMessage();
        mail.setFrom(properties.from());
        mail.setTo(to);
        mail.setSubject(message.subject());
        mail.setText(message.body());
        try {
            sender.send(mail);
        } catch (MailException ex) {
            // A failed notification must not undo the thing it was notifying about, so this is
            // logged rather than rethrown.
            log.warn("Could not send \"{}\" to {}: {}", message.subject(), to, ex.getMessage());
        }
    }
}
