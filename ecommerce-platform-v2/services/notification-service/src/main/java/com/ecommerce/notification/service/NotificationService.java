package com.ecommerce.notification.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

import jakarta.mail.internet.MimeMessage;
import java.util.Map;

/**
 * Multi-channel notification dispatcher.
 * SLF4J via @Slf4j – no logback class references in service code.
 *
 * Channels:
 *   • Email    – JavaMail + SendGrid SMTP relay + Thymeleaf HTML templates
 *   • WebSocket – STOMP via SimpMessagingTemplate (real-time push)
 *
 * Pattern: Strategy (each channel is a distinct send strategy).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationService {

    private final JavaMailSender        mailSender;
    private final SimpMessagingTemplate wsTemplate;
    private final TemplateEngine        templateEngine;

    // ── Email ────────────────────────────────────────────────────────────────

    public void sendEmail(String to, String subject,
                          String templateName, Map<String, Object> vars) {
        try {
            Context ctx = new Context();
            vars.forEach(ctx::setVariable);
            String html = templateEngine.process(templateName, ctx);

            MimeMessage msg = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(msg, true, "UTF-8");
            helper.setTo(to);
            helper.setSubject(subject);
            helper.setText(html, true);
            helper.setFrom("noreply@shopease.com");
            mailSender.send(msg);
            log.info("Email sent to={} subject={}", to, subject);
        } catch (Exception e) {
            log.error("Email send failed to={} : {}", to, e.getMessage(), e);
        }
    }

    // ── WebSocket ────────────────────────────────────────────────────────────

    public void pushWebSocket(String userId, NotificationPayload payload) {
        try {
            wsTemplate.convertAndSendToUser(userId, "/queue/notifications", payload);
            log.debug("WebSocket pushed userId={} type={}", userId, payload.type());
        } catch (Exception e) {
            log.error("WebSocket push failed userId={}: {}", userId, e.getMessage());
        }
    }

    // ── Convenience ──────────────────────────────────────────────────────────

    public void notifyOrderConfirmed(String email, String userId, String orderId, String total) {
        sendEmail(email, "✅ Order Confirmed!", "order-confirmed",
            Map.of("orderId", orderId, "total", total));
        pushWebSocket(userId, new NotificationPayload("ORDER_CONFIRMED",
            "Order #" + orderId.substring(0, 8) + " confirmed!",
            Map.of("orderId", orderId, "total", total)));
    }

    public void notifyOrderShipped(String email, String userId, String orderId, String trackingNo) {
        sendEmail(email, "📦 Order Shipped!", "order-shipped",
            Map.of("orderId", orderId, "trackingNo", trackingNo));
        pushWebSocket(userId, new NotificationPayload("ORDER_SHIPPED",
            "Tracking: " + trackingNo, Map.of("orderId", orderId)));
    }

    public record NotificationPayload(
        String type, String message, Map<String, Object> data) {}
}
