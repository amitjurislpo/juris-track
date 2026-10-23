package com.jurislpo.juristrack.service;

import com.jurislpo.juristrack.config.JuristrackProperties;
import com.jurislpo.juristrack.service.MailTemplates.MailContent;
import jakarta.annotation.PreDestroy;
import jakarta.mail.internet.MimeMessage;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

/**
 * Sends email with JavaMailSender when {@code juristrack.mail.host} is set; without a host (dev) the message is written to the
 * server log, so the sign-in code can be copied from there. Sending happens on a background thread: the caller's answer does
 * not depend on whether or how fast a message went out (a sign-in answer must not reveal whether the account exists), and a
 * failed send is logged, never thrown.
 */
@Service
public class MailService {

    private static final Logger log = LoggerFactory.getLogger(MailService.class);
    private static final int SSL_PORT = 465;

    private final JuristrackProperties.Mail settings;
    private final JavaMailSenderImpl sender;
    private final ExecutorService sendingThread = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "juristrack-mail");
        thread.setDaemon(true);
        return thread;
    });

    public MailService(JuristrackProperties properties) {
        this.settings = properties.mail();
        this.sender = settings.host().isEmpty() ? null : createSender(settings);
    }

    private static JavaMailSenderImpl createSender(JuristrackProperties.Mail settings) {
        JavaMailSenderImpl mailSender = new JavaMailSenderImpl();
        mailSender.setHost(settings.host());
        mailSender.setPort(settings.port());
        boolean hasLogin = !settings.username().isEmpty();
        if (hasLogin) {
            mailSender.setUsername(settings.username());
            mailSender.setPassword(settings.password());
        }
        Properties mailProperties = mailSender.getJavaMailProperties();
        mailProperties.put("mail.smtp.auth", String.valueOf(hasLogin));
        if (settings.port() == SSL_PORT) mailProperties.put("mail.smtp.ssl.enable", "true");
        else mailProperties.put("mail.smtp.starttls.enable", "true");
        mailProperties.put("mail.smtp.connectiontimeout", "10000");
        mailProperties.put("mail.smtp.timeout", "10000");
        return mailSender;
    }

    /** Queues the message; returns at once. */
    public void send(String recipient, MailContent content) {
        sendingThread.execute(() -> deliver(recipient, content));
    }

    private void deliver(String recipient, MailContent content) {
        if (sender == null) {
            log.info("\n[email, not sent: juristrack.mail.host is empty] to={}\n   subject={}\n   {}\n", recipient, content.subject(),
                    content.text().replace("\n", "\n   "));
            return;
        }
        try {
            MimeMessage message = sender.createMimeMessage();
            MimeMessageHelper messageHelper = new MimeMessageHelper(message, true, "UTF-8");
            messageHelper.setFrom(settings.from());
            messageHelper.setTo(recipient);
            messageHelper.setSubject(content.subject());
            messageHelper.setText(content.text(), content.html());
            sender.send(message);
        } catch (Exception failure) {
            // never the message text: it may hold a sign-in code
            log.error("[email] sending \"{}\" failed: {}", content.subject().replaceAll("[0-9]{6}", "******"), failure.toString());
        }
    }

    @PreDestroy
    void stopSending() {
        sendingThread.shutdown();
    }
}
