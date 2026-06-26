package com.ameya.eventgatewayservice.controller;

import com.ameya.eventgatewayservice.dto.EventRequest;
import com.ameya.eventgatewayservice.dto.EventResponse;
import com.ameya.eventgatewayservice.model.EventEntity;
import com.ameya.eventgatewayservice.model.EventSubmissionResult;
import com.ameya.eventgatewayservice.service.event.EventService;
import io.micrometer.tracing.Tracer;
import jakarta.validation.Valid;
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

    @GetMapping("/{id}")
    public ResponseEntity<EventResponse> getEvent(@PathVariable String id) {
        EventEntity entity = eventService.getEvent(id);
        return ResponseEntity.ok(toResponse(entity));
    }

    @GetMapping
    public ResponseEntity<List<EventResponse>> getEventsForAccount(
            @RequestParam("account") String accountId) {
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