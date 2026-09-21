package com.example.ssafesta.feedback;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Best-effort Mattermost incoming-webhook post on feedback submission (S15P21A604-953).
 *
 * <p>Optional by design — the webhook URL is deployment config that may not exist yet, and even
 * when it does, the team's Mattermost being unreachable must never fail a member's feedback
 * submission. {@link #notifySubmitted} never throws.
 */
@Component
class MattermostFeedbackNotifier {

    private static final Logger log = LoggerFactory.getLogger(MattermostFeedbackNotifier.class);

    private final FeedbackProperties properties;
    private final RestClient http = RestClient.create();

    MattermostFeedbackNotifier(FeedbackProperties properties) {
        this.properties = properties;
    }

    void notifySubmitted(Feedback feedback, String nickname) {
        if (!properties.mattermostConfigured()) {
            return;
        }
        String text = "새 피드백 (#%d, %s)\n%s".formatted(feedback.getId(), nickname, feedback.getContent());
        try {
            http.post()
                    .uri(properties.mattermostWebhookUrl())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new WebhookPayload(text))
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientException failure) {
            log.warn("Mattermost 피드백 알림이 실패했습니다 feedbackId={} 원인={}",
                    feedback.getId(), failure.getClass().getSimpleName());
        }
    }

    private record WebhookPayload(String text) {
    }
}
