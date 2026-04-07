package com.notification.notification.web;

import com.notification.notification.application.job.CancelNotificationJobUseCase;
import com.notification.notification.application.job.CreateScheduledNotificationJobUseCase;
import com.notification.notification.application.job.GetNotificationJobUseCase;
import com.notification.notification.application.job.IssueCreationJobKeyUseCase;
import com.notification.notification.application.job.RecoverNotificationJobUseCase;
import com.notification.notification.application.job.dto.CreateNotificationJobUseCaseIn;
import com.notification.notification.application.job.dto.CreateNotificationJobUseCaseOut;
import com.notification.notification.application.job.dto.IssueCreationJobKeyUseCaseOut;
import com.notification.notification.application.job.dto.NotificationJobResponse;
import com.notification.notification.domain.NotificationChannel;
import com.notification.notification.web.dto.CreateNotificationJobRequest;
import com.notification.support.web.response.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/notification-jobs")
public class NotificationJobController {

    private final CreateScheduledNotificationJobUseCase createScheduledUseCase;
    private final GetNotificationJobUseCase getNotificationJobUseCase;
    private final RecoverNotificationJobUseCase recoverNotificationJobUseCase;
    private final CancelNotificationJobUseCase cancelNotificationJobUseCase;
    private final IssueCreationJobKeyUseCase issueCreationJobKeyUseCase;

    public NotificationJobController(
            CreateScheduledNotificationJobUseCase createScheduledUseCase,
            GetNotificationJobUseCase getNotificationJobUseCase,
            RecoverNotificationJobUseCase recoverNotificationJobUseCase,
            CancelNotificationJobUseCase cancelNotificationJobUseCase,
            IssueCreationJobKeyUseCase issueCreationJobKeyUseCase) {
        this.createScheduledUseCase = createScheduledUseCase;
        this.getNotificationJobUseCase = getNotificationJobUseCase;
        this.recoverNotificationJobUseCase = recoverNotificationJobUseCase;
        this.cancelNotificationJobUseCase = cancelNotificationJobUseCase;
        this.issueCreationJobKeyUseCase = issueCreationJobKeyUseCase;
    }

    @PostMapping("/key")
    public ResponseEntity<ApiResponse<IssueCreationJobKeyUseCaseOut>> issueKey() {
        IssueCreationJobKeyUseCaseOut result = issueCreationJobKeyUseCase.execute();
        return ResponseEntity.ok(ApiResponse.of(result));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<NotificationJobResponse>> createJob(
            @Valid @RequestBody CreateNotificationJobRequest request) {
        CreateNotificationJobUseCaseOut result =
                createScheduledUseCase.execute(toUseCaseIn(request));
        HttpStatus status = result.created() ? HttpStatus.CREATED : HttpStatus.OK;
        return ResponseEntity.status(status).body(ApiResponse.of(result.response()));
    }

    @GetMapping("/{jobId}")
    public ResponseEntity<ApiResponse<NotificationJobResponse>> getJob(@PathVariable Long jobId) {
        NotificationJobResponse response = getNotificationJobUseCase.execute(jobId);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    @DeleteMapping("/{jobId}")
    public ResponseEntity<Void> deleteJob(@PathVariable Long jobId) {
        cancelNotificationJobUseCase.execute(jobId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{jobId}/recover")
    public ResponseEntity<Void> recover(@PathVariable Long jobId) {
        recoverNotificationJobUseCase.execute(jobId);
        return ResponseEntity.ok().build();
    }

    private CreateNotificationJobUseCaseIn toUseCaseIn(CreateNotificationJobRequest request) {
        return new CreateNotificationJobUseCaseIn(
                request.idempotencyKey(),
                NotificationChannel.valueOf(request.channel().name()),
                request.templateCode(),
                request.locale(),
                request.type(),
                request.metadata(),
                request.scheduledAt(),
                request.recipients().stream()
                        .map(
                                r ->
                                        new CreateNotificationJobUseCaseIn.Recipient(
                                                r.recipientId(), r.contact(), r.variables()))
                        .toList());
    }
}
