package com.ameya.eventgatewayservice.service.event;

import com.ameya.eventgatewayservice.dto.EventRequest;
import com.ameya.eventgatewayservice.exception.AccountServiceUnavailableException;
import com.ameya.eventgatewayservice.exception.DuplicateEventConflictException;
import com.ameya.eventgatewayservice.exception.EventNotFoundException;
import com.ameya.eventgatewayservice.model.EventEntity;
import com.ameya.eventgatewayservice.model.EventStatus;
import com.ameya.eventgatewayservice.model.EventSubmissionResult;
import com.ameya.eventgatewayservice.repository.EventRepository;
import com.ameya.eventgatewayservice.service.accountapi.AccountServiceClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
public class EventService {

    private static final Logger log = LoggerFactory.getLogger(EventService.class);

    private final EventRepository eventRepository;
    private final AccountServiceClient accountServiceClient;
    private final ObjectMapper objectMapper;

    public EventService(EventRepository eventRepository,
                         AccountServiceClient accountServiceClient,
                         ObjectMapper objectMapper) {
        this.eventRepository = eventRepository;
        this.accountServiceClient = accountServiceClient;
        this.objectMapper = objectMapper;
    }

    /**
     * Handles a new event submission with idempotency semantics:
     *  - unseen eventId            -> persist, forward downstream, return wasNewlyCreated=true
     *  - seen eventId, same payload -> return the original silently, wasNewlyCreated=false
     *  - seen eventId, diff payload -> throw DuplicateEventConflictException (409)
     */
    public EventSubmissionResult submitEvent(EventRequest request) {
        int incomingHash = hashPayload(request);

        return eventRepository.findById(request.eventId())
                .map(existing -> handleExistingEvent(existing, incomingHash))
                .orElseGet(() -> handleNewEvent(request, incomingHash));
    }

    private EventSubmissionResult handleExistingEvent(EventEntity existing, int incomingHash) {
        if (existing.getPayloadHash() != incomingHash) {
            log.warn("Duplicate eventId {} submitted with a different payload", existing.getEventId());
            throw new DuplicateEventConflictException(existing.getEventId());
        }
        log.info("Idempotent replay of eventId {}, returning original", existing.getEventId());
        return new EventSubmissionResult(existing, false);
    }

    private EventSubmissionResult handleNewEvent(EventRequest request, int payloadHash) {
        String metadataJson = serializeMetadata(request.metadata());

        EventEntity entity = new EventEntity(
                request.eventId(),
                request.accountId(),
                request.type(),
                request.amount(),
                request.currency(),
                request.eventTimestamp(),
                Instant.now(),
                metadataJson,
                EventStatus.RECEIVED,
                payloadHash
        );

        // Persist locally first - the event must survive even if the
        // Account Service call below fails (graceful degradation).
        eventRepository.save(entity);

        try {
//            accountServiceClient.applyTransaction(request);
            entity.setStatus(EventStatus.FORWARDED);
        } catch (AccountServiceUnavailableException ex) {
            log.error("Account Service unavailable while forwarding eventId {}: {}",
                    request.eventId(), ex.getMessage());
            entity.setStatus(EventStatus.FAILED_DOWNSTREAM);
            eventRepository.save(entity);
            throw ex; // let the controller translate this into a 503
        } catch (Exception ex){
            log.error("UnExpected Exception occurred while forwarding eventId {}: {}",
                    request.eventId(), ex.getMessage());
            entity.setStatus(EventStatus.FAILED_DOWNSTREAM);
            eventRepository.save(entity);
            throw ex;
        }

        eventRepository.save(entity);
        return new EventSubmissionResult(entity, true);
    }

    public EventEntity getEvent(String eventId) {
        return eventRepository.findById(eventId)
                .orElseThrow(() -> new EventNotFoundException(eventId));
    }

    public List<EventEntity> getEventsForAccount(String accountId) {
        return eventRepository.findByAccountIdOrderByEventTimestampAsc(accountId);
    }

    public Map<String, Object> parseMetadata(String metadataJson) {
        if (metadataJson == null) {
            return null;
        }
        try {
            return objectMapper.readValue(metadataJson, Map.class);
        } catch (Exception e) {
            log.warn("Failed to parse stored metadata JSON, returning empty map", e);
            return Map.of();
        }
    }

    private String serializeMetadata(Map<String, Object> metadata) {
        if (metadata == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(metadata);
        } catch (Exception e) {
            log.warn("Failed to serialize metadata, storing as null", e);
            return null;
        }
    }

    private int hashPayload(EventRequest request) {
        return Objects.hash(
                request.eventId(),
                request.accountId(),
                request.type(),
                request.amount(),
                request.currency(),
                request.eventTimestamp()
        );
    }
}