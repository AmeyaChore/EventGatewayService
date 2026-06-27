package com.ameya.eventgatewayservice.controller;

import com.ameya.eventgatewayservice.dto.EventRequest;
import com.ameya.eventgatewayservice.dto.EventResponse;
import com.ameya.eventgatewayservice.model.EventEntity;
import com.ameya.eventgatewayservice.model.EventSubmissionResult;
import com.ameya.eventgatewayservice.service.event.EventService;
import io.github.resilience4j.ratelimiter.annotation.RateLimiter;
import io.micrometer.tracing.Tracer;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.List;

@RestController
@RequestMapping("/events")
public class EventController {

    private static final Logger log = LoggerFactory.getLogger(EventController.class);

    private final EventService eventService;
    private final Tracer tracer;

    public EventController(EventService eventService, Tracer tracer) {
        this.eventService = eventService;
        this.tracer = tracer;
    }

    @PostMapping
    @RateLimiter(name = "eventSubmission")
    @Operation(
            summary = "Submit a transaction event",
            description = """
                    Idempotent on eventId: a repeat submission with the SAME payload returns \
                    the original event (200). A repeat submission with a DIFFERENT payload for \
                    the same eventId is rejected (409). Rate limited to 10 requests/second \
                    shared across all callers."""
    )
    @ApiResponse(responseCode = "201", description = "New event accepted and forwarded to the Account Service")
    @ApiResponse(responseCode = "200", description = "Idempotent replay - same eventId and payload as a prior submission")
    @ApiResponse(responseCode = "400", description = "Validation failed (missing/invalid field)")
    @ApiResponse(responseCode = "404", description = "Account Service reports the account does not exist")
    @ApiResponse(responseCode = "409", description = "Same eventId submitted before with a DIFFERENT payload")
    @ApiResponse(responseCode = "429", description = "Rate limit exceeded")
    @ApiResponse(responseCode = "503", description = "Account Service is unreachable; event was stored locally for later retry")
    public ResponseEntity<EventResponse> submitEvent(@Valid @RequestBody EventRequest request) {
        log.info("Received event submission eventId={} accountId={} traceId={}",
                request.eventId(), request.accountId(), currentTraceId());

        EventSubmissionResult result = eventService.submitEvent(request);
        EventResponse response = toResponse(result.event());

        if (result.wasNewlyCreated()) {
            return ResponseEntity
                    .created(URI.create("/events/" + result.event().getEventId()))
                    .body(response);
        }

        // Idempotent replay: same eventId, same payload -> 200, not a fresh 201
        return ResponseEntity.ok(response);
    }

    @GetMapping("/{eventId}")
    @Operation(summary = "Get a single event by its ID")
    @ApiResponse(responseCode = "200", description = "Event found")
    @ApiResponse(responseCode = "404", description = "No event exists with this ID")
    public ResponseEntity<EventResponse> getEvent(@PathVariable(required = true) @NotBlank String eventId) {
        EventEntity entity = eventService.getEvent(eventId);
        return ResponseEntity.ok(toResponse(entity));
    }

    @GetMapping
    @Operation(
            summary = "List events for an account",
            description = "Returns events for the given account, ordered chronologically by eventTimestamp."
    )
    @ApiResponse(responseCode = "200", description = "List of events for the account (may be empty)")
    public ResponseEntity<List<EventResponse>> getEventsForAccount(
            @RequestParam(value = "account", required = true) @NotBlank String accountId) {
        List<EventResponse> events = eventService.getEventsForAccount(accountId)
                .stream()
                .map(this::toResponse)
                .toList();
        return ResponseEntity.ok(events);
    }

    @GetMapping("/health")
    public ResponseEntity<String> health() {
        return ResponseEntity.ok("Application is UP");
    }

    private EventResponse toResponse(EventEntity entity) {
        return EventResponse.from(entity, eventService.parseMetadata(entity.getMetadataJson()));
    }

    private String currentTraceId() {
        return tracer.currentSpan() != null
                ? tracer.currentSpan().context().traceId()
                : "n/a";
    }
}