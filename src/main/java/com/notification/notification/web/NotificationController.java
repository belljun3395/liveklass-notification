package com.notification.notification.web;

import com.notification.notification.application.notification.BrowseUserNotificationUseCase;
import com.notification.notification.application.notification.GetNotificationUseCase;
import com.notification.notification.application.notification.ReadNotificationUseCase;
import com.notification.notification.application.notification.dto.BrowseUserNotificationUseCaseIn;
import com.notification.notification.application.notification.dto.CursorPage;
import com.notification.notification.application.notification.dto.NotificationResponse;
import com.notification.notification.application.notification.dto.ReadNotificationUseCaseIn;
import com.notification.notification.web.dto.ReadNotificationRequest;
import com.notification.support.web.response.ApiResponse;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class NotificationController {

    private final GetNotificationUseCase getNotificationUseCase;
    private final BrowseUserNotificationUseCase browseUserNotificationUseCase;
    private final ReadNotificationUseCase readNotificationUseCase;

    public NotificationController(
            GetNotificationUseCase getNotificationUseCase,
            BrowseUserNotificationUseCase browseUserNotificationUseCase,
            ReadNotificationUseCase readNotificationUseCase) {
        this.getNotificationUseCase = getNotificationUseCase;
        this.browseUserNotificationUseCase = browseUserNotificationUseCase;
        this.readNotificationUseCase = readNotificationUseCase;
    }

    @GetMapping("/notifications/{id}")
    public ResponseEntity<ApiResponse<NotificationResponse>> getNotification(
            @PathVariable Long id) {
        NotificationResponse response = getNotificationUseCase.execute(id);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    @GetMapping("/users/{userId}/notifications")
    public ResponseEntity<ApiResponse<CursorPage<NotificationResponse>>> getUserNotifications(
            @PathVariable Long userId,
            @RequestParam(required = false) Boolean read,
            @RequestParam(required = false) Long cursor,
            @RequestParam(defaultValue = "20") int size) {
        CursorPage<NotificationResponse> result =
                browseUserNotificationUseCase.execute(
                        new BrowseUserNotificationUseCaseIn(userId, read, cursor, size));
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noCache().cachePrivate())
                .body(ApiResponse.of(result));
    }

    @PatchMapping("/notifications/{id}/read")
    public ResponseEntity<Void> markAsRead(
            @PathVariable Long id,
            @RequestParam Long userId,
            @RequestBody(required = false) ReadNotificationRequest request) {
        String deviceId = request != null ? request.deviceId() : null;
        String deviceType = request != null ? request.deviceType() : null;
        readNotificationUseCase.execute(
                new ReadNotificationUseCaseIn(id, userId, deviceId, deviceType));
        return ResponseEntity.accepted().build();
    }
}
