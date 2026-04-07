package com.notification.template.web;

import com.notification.notification.domain.NotificationChannel;
import com.notification.support.web.response.ApiResponse;
import com.notification.template.application.BrowseTemplatesUseCase;
import com.notification.template.application.CreateTemplateUseCase;
import com.notification.template.application.DeleteTemplateUseCase;
import com.notification.template.application.GetTemplateUseCase;
import com.notification.template.application.PreviewTemplateUseCase;
import com.notification.template.application.dto.BrowseTemplatesUseCaseIn;
import com.notification.template.application.dto.CreateTemplateUseCaseIn;
import com.notification.template.application.dto.PreviewTemplateUseCaseIn;
import com.notification.template.application.dto.PreviewTemplateUseCaseOut;
import com.notification.template.application.dto.TemplateUseCaseOut;
import com.notification.template.domain.VariableDataType;
import com.notification.template.web.dto.CreateTemplateRequest;
import com.notification.template.web.dto.PreviewTemplateRequest;
import jakarta.validation.Valid;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/templates")
public class TemplateController {

    private final CreateTemplateUseCase createUseCase;
    private final DeleteTemplateUseCase deleteUseCase;
    private final PreviewTemplateUseCase previewUseCase;
    private final GetTemplateUseCase getUseCase;
    private final BrowseTemplatesUseCase browseUseCase;

    public TemplateController(
            CreateTemplateUseCase createUseCase,
            DeleteTemplateUseCase deleteUseCase,
            PreviewTemplateUseCase previewUseCase,
            GetTemplateUseCase getUseCase,
            BrowseTemplatesUseCase browseUseCase) {
        this.createUseCase = createUseCase;
        this.deleteUseCase = deleteUseCase;
        this.previewUseCase = previewUseCase;
        this.getUseCase = getUseCase;
        this.browseUseCase = browseUseCase;
    }

    @PostMapping
    public ResponseEntity<ApiResponse<TemplateUseCaseOut>> create(
            @Valid @RequestBody CreateTemplateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.of(createUseCase.execute(toUseCaseIn(request))));
    }

    @PostMapping("/{templateId}/preview")
    public ResponseEntity<ApiResponse<PreviewTemplateUseCaseOut>> preview(
            @PathVariable Long templateId, @Valid @RequestBody PreviewTemplateRequest request) {
        PreviewTemplateUseCaseOut result =
                previewUseCase.execute(
                        new PreviewTemplateUseCaseIn(templateId, request.variables()));
        return ResponseEntity.ok(ApiResponse.of(result));
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<TemplateUseCaseOut>>> list(@RequestParam String code) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(5, TimeUnit.MINUTES).cachePublic())
                .body(ApiResponse.of(browseUseCase.execute(new BrowseTemplatesUseCaseIn(code))));
    }

    @GetMapping("/{templateId}")
    public ResponseEntity<ApiResponse<TemplateUseCaseOut>> get(@PathVariable Long templateId) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(5, TimeUnit.MINUTES).cachePublic())
                .body(ApiResponse.of(getUseCase.execute(templateId)));
    }

    @DeleteMapping("/{templateId}")
    public ResponseEntity<Void> delete(@PathVariable Long templateId) {
        deleteUseCase.execute(templateId);
        return ResponseEntity.noContent().build();
    }

    private CreateTemplateUseCaseIn toUseCaseIn(CreateTemplateRequest request) {
        List<CreateTemplateUseCaseIn.Variable> variables =
                request.variables() == null
                        ? List.of()
                        : request.variables().stream()
                                .map(
                                        v ->
                                                new CreateTemplateUseCaseIn.Variable(
                                                        v.name(),
                                                        VariableDataType.valueOf(v.dataType()),
                                                        v.required(),
                                                        v.exampleValue(),
                                                        v.description()))
                                .toList();
        return new CreateTemplateUseCaseIn(
                request.code(),
                NotificationChannel.valueOf(request.channel()),
                request.locale(),
                request.titleTemplate(),
                request.bodyTemplate(),
                request.description(),
                variables);
    }
}
