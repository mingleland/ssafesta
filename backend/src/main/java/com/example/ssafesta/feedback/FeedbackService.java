package com.example.ssafesta.feedback;

import com.example.ssafesta.common.ApiException;
import com.example.ssafesta.common.ErrorCode;
import com.example.ssafesta.user.User;
import com.example.ssafesta.user.UserRepository;
import java.time.Instant;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class FeedbackService {

    private static final int MAX_CONTENT_LENGTH = 2000;

    private final FeedbackRepository feedback;
    private final UserRepository users;
    private final MattermostFeedbackNotifier mattermost;

    public FeedbackService(FeedbackRepository feedback, UserRepository users, MattermostFeedbackNotifier mattermost) {
        this.feedback = feedback;
        this.users = users;
        this.mattermost = mattermost;
    }

    @Transactional
    public Feedback submit(Long userId, String content) {
        String trimmed = content == null ? "" : content.trim();
        if (trimmed.isEmpty()) {
            throw ApiException.fieldInvalid("content", "필수입니다.");
        }
        if (trimmed.length() > MAX_CONTENT_LENGTH) {
            throw ApiException.fieldInvalid("content", MAX_CONTENT_LENGTH + "자 이하여야 합니다.");
        }
        Feedback saved = feedback.save(new Feedback(userId, trimmed, Instant.now()));
        String nickname = users.findById(userId).map(User::getNickname).orElse(null);
        mattermost.notifySubmitted(saved, nickname);
        return saved;
    }

    @Transactional(readOnly = true)
    public Page<AdminFeedbackView> listForAdmin(Pageable pageable) {
        Page<Feedback> page = feedback.findAllByOrderByCreatedAtDesc(pageable);
        Map<Long, String> nicknames = users.findAllById(page.getContent().stream().map(Feedback::getUserId).distinct().toList())
                .stream().collect(Collectors.toMap(User::getId, User::getNickname));
        return page.map(f -> AdminFeedbackView.of(f, nicknames.get(f.getUserId())));
    }

    @Transactional
    public AdminFeedbackView setFirstFound(Long feedbackId, boolean firstFound) {
        Feedback found = feedback.findById(feedbackId)
                .orElseThrow(() -> new ApiException(ErrorCode.FEEDBACK_NOT_FOUND));
        found.setFirstFound(firstFound);
        String nickname = users.findById(found.getUserId()).map(User::getNickname).orElse(null);
        return AdminFeedbackView.of(found, nickname);
    }

    public record AdminFeedbackView(Long feedbackId, Long userId, String nickname, String content,
                                    boolean firstFound, Instant createdAt) {
        static AdminFeedbackView of(Feedback f, String nickname) {
            return new AdminFeedbackView(f.getId(), f.getUserId(), nickname, f.getContent(), f.isFirstFound(),
                    f.getCreatedAt());
        }
    }
}
