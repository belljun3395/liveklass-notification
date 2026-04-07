package com.notification.notification.application.notification.dto;

import java.util.List;

public record CursorPage<T>(List<T> items, Long nextCursor, boolean hasNext) {}
