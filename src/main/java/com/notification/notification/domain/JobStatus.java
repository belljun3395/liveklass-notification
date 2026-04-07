package com.notification.notification.domain;

import java.util.Set;

public enum JobStatus {
    CREATED,
    SCHEDULED,
    PROCESSING,
    CANCELLED,
    COMPLETED,
    FAILED,
    RETRYING;

    static {
        CREATED.allowedTransitions = Set.of(SCHEDULED, CANCELLED);
        SCHEDULED.allowedTransitions = Set.of(PROCESSING, CANCELLED);
        PROCESSING.allowedTransitions = Set.of(COMPLETED, FAILED);
        CANCELLED.allowedTransitions = Set.of(RETRYING);
        COMPLETED.allowedTransitions = Set.of();
        FAILED.allowedTransitions = Set.of(RETRYING);
        RETRYING.allowedTransitions = Set.of(PROCESSING, CANCELLED);
    }

    private Set<JobStatus> allowedTransitions;

    public boolean canTransitionTo(JobStatus target) {
        return allowedTransitions.contains(target);
    }

    public Set<JobStatus> getAllowedTransitions() {
        return allowedTransitions;
    }
}
