package com.notification.notification.application.notification.dto;

/**
 * @param readFilter null=전체, true=읽은것만, false=안읽은것만
 * @param cursorId null=첫 페이지, 이전 응답의 nextCursor=다음 페이지
 */
public record BrowseUserNotificationUseCaseIn(
        Long userId, Boolean readFilter, Long cursorId, int size) {}
