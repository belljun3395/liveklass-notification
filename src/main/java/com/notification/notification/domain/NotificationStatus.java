package com.notification.notification.domain;

import java.util.Set;

public enum NotificationStatus {
    PENDING,
    SENDING,
    SENT,
    FAILED,
    RETRY_WAITING,
    DEAD_LETTER,
    CANCELLED;

    static {
        PENDING.allowedTransitions = Set.of(SENDING, CANCELLED);
        SENDING.allowedTransitions = Set.of(SENT, FAILED, DEAD_LETTER, RETRY_WAITING);
        SENT.allowedTransitions = Set.of();
        FAILED.allowedTransitions = Set.of(RETRY_WAITING, DEAD_LETTER, CANCELLED);
        RETRY_WAITING.allowedTransitions = Set.of(SENDING, CANCELLED);
        DEAD_LETTER.allowedTransitions = Set.of(RETRY_WAITING, CANCELLED);
        CANCELLED.allowedTransitions = Set.of(RETRY_WAITING);
    }

    private Set<NotificationStatus> allowedTransitions;

    public boolean canTransitionTo(NotificationStatus target) {
        return allowedTransitions.contains(target);
    }

    public Set<NotificationStatus> getAllowedTransitions() {
        return allowedTransitions;
    }
}
