package com.example.ssafesta.feedback;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code mattermostWebhookUrl} is a plain {@link String}, not {@link java.net.URI} — the default is
 * an empty string ({@code app.feedback.mattermost-webhook-url: ${MATTERMOST_FEEDBACK_WEBHOOK_URL:}}),
 * and binding that to {@code URI} would fail before the "not configured" branch ever runs. Blank
 * means the Mattermost notification is skipped, not attempted with a broken destination.
 */
@ConfigurationProperties("app.feedback")
public record FeedbackProperties(String mattermostWebhookUrl) {

    public boolean mattermostConfigured() {
        return mattermostWebhookUrl != null && !mattermostWebhookUrl.isBlank();
    }
}
