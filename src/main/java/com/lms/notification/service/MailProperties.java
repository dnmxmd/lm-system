package com.lms.notification.service;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "lms.mail")
public record MailProperties(String from) {

    public MailProperties {
        from = from == null ? "no-reply@lms.local" : from;
    }
}
