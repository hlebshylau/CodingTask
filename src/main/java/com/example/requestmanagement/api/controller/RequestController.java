package com.example.requestmanagement.api.controller;

import com.example.requestmanagement.api.dto.AuditEntryResponse;
import com.example.requestmanagement.api.dto.CreateRequestPayload;
import com.example.requestmanagement.api.dto.EventPayload;
import com.example.requestmanagement.api.dto.PageResponse;
import com.example.requestmanagement.api.dto.RequestResponse;
import com.example.requestmanagement.api.dto.UpdateBodyPayload;
import com.example.requestmanagement.application.port.RequestSearchCriteria;
import com.example.requestmanagement.application.service.RequestService;
import com.example.requestmanagement.domain.model.Request;
import com.example.requestmanagement.domain.model.RequestStatus;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.util.List;

@RestController
@RequestMapping("/api/requests")
public class RequestController {

    private final RequestService requestService;

    public RequestController(RequestService requestService) {
        this.requestService = requestService;
    }

    @PostMapping
    public ResponseEntity<RequestResponse> create(@Valid @RequestBody CreateRequestPayload payload) {
        Request created = requestService.create(payload.name(), payload.body());
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}")
                .buildAndExpand(created.getId())
                .toUri();
        return ResponseEntity.created(location).body(RequestResponse.from(created));
    }

    @GetMapping("/{id}")
    public RequestResponse get(@PathVariable Long id) {
        return RequestResponse.from(requestService.getActive(id));
    }

    @GetMapping
    public PageResponse<RequestResponse> search(
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "" + RequestSearchCriteria.DEFAULT_PAGE_SIZE)
            @Min(1) @Max(RequestSearchCriteria.MAX_PAGE_SIZE) int size,
            @RequestParam(required = false) String name,
            @RequestParam(required = false) RequestStatus status) {
        RequestSearchCriteria criteria = new RequestSearchCriteria(name, status, page, size);
        return PageResponse.from(requestService.search(criteria).map(RequestResponse::from));
    }

    @PatchMapping("/{id}")
    public RequestResponse updateBody(@PathVariable Long id, @Valid @RequestBody UpdateBodyPayload payload) {
        return RequestResponse.from(requestService.updateBody(id, payload.body()));
    }

    @PostMapping("/{id}/events")
    public RequestResponse applyEvent(@PathVariable Long id, @Valid @RequestBody EventPayload payload) {
        return RequestResponse.from(requestService.applyEvent(id, payload.event(), payload.reason()));
    }

    @GetMapping("/{id}/audit")
    public List<AuditEntryResponse> audit(@PathVariable Long id) {
        return requestService.auditTrail(id).stream().map(AuditEntryResponse::from).toList();
    }
}
