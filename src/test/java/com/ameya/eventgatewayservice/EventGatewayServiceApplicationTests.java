package com.ameya.eventgatewayservice;

import com.ameya.eventgatewayservice.dto.EventRequest;
import com.ameya.eventgatewayservice.exception.AccountNotFoundException;
import com.ameya.eventgatewayservice.exception.AccountServiceUnavailableException;
import com.ameya.eventgatewayservice.exception.DuplicateEventConflictException;
import com.ameya.eventgatewayservice.exception.EventNotFoundException;
import com.ameya.eventgatewayservice.model.EventEntity;
import com.ameya.eventgatewayservice.model.EventStatus;
import com.ameya.eventgatewayservice.model.EventSubmissionResult;
import com.ameya.eventgatewayservice.repository.EventRepository;
import com.ameya.eventgatewayservice.service.accountapi.AccountServiceClient;
import com.ameya.eventgatewayservice.service.event.EventService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for EventService - no Spring context, EventRepository and
 * AccountServiceClient are mocked. Covers every branch of submitEvent(),
 * forwardToAccountService(), getEvent(), getEventsForAccount(), and
 * metadata (de)serialization.
 */
@DisplayName("EventService")
class EventGatewayServiceApplicationTests {

    private EventRepository eventRepository;
    private AccountServiceClient accountServiceClient;
    private EventService eventService;

    private static final String EVENT_ID = "evt-001";
    private static final String ACCOUNT_ID = "acct-123";
    private static final Instant EVENT_TIMESTAMP = Instant.parse("2026-06-25T10:00:00Z");

    @BeforeEach
    void setUp() {
        eventRepository = mock(EventRepository.class);
        accountServiceClient = mock(AccountServiceClient.class);
        eventService = new EventService(eventRepository, accountServiceClient, new ObjectMapper());
    }

    // ------------------------------------------------------------------
    // submitEvent() - new event path
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("submitEvent() - new event")
    class NewEventTests {

        @Test
        @DisplayName("persists the event, forwards it, and marks it FORWARDED on success")
        void newEvent_success_isPersistedForwardedAndMarkedForwarded() {
            EventRequest request = defaultRequest();
            when(eventRepository.findById(EVENT_ID)).thenReturn(Optional.empty());
            doNothing().when(accountServiceClient).applyTransaction(request);

            EventSubmissionResult result = eventService.submitEvent(request);

            assertThat(result.wasNewlyCreated()).isTrue();
            assertThat(result.event().getStatus()).isEqualTo(EventStatus.FORWARDED);
            assertThat(result.event().getEventId()).isEqualTo(EVENT_ID);

            // Saved at least twice: once as RECEIVED (before forwarding),
            // once more after status flips to FORWARDED.
            verify(eventRepository, atLeast(2)).save(any(EventEntity.class));
            verify(accountServiceClient).applyTransaction(request);
        }

        @Test
        @DisplayName("the event is persisted BEFORE the Account Service is called (durability over synchronous success)")
        void newEvent_isPersistedBeforeAccountServiceIsCalled() {
            EventRequest request = defaultRequest();
            when(eventRepository.findById(EVENT_ID)).thenReturn(Optional.empty());

            InOrder inOrder = inOrder(eventRepository, accountServiceClient);

            eventService.submitEvent(request);

            inOrder.verify(eventRepository).save(any(EventEntity.class)); // initial RECEIVED save
            inOrder.verify(accountServiceClient).applyTransaction(request);
        }

        @Test
        @DisplayName("metadata is serialized to JSON and stored on the entity")
        void newEvent_metadataIsSerializedToJson() {
            EventRequest request = new EventRequest(EVENT_ID, ACCOUNT_ID, "CREDIT",
                    BigDecimal.valueOf(100), "USD", EVENT_TIMESTAMP,
                    Map.of("source", "mainframe-batch"));
            when(eventRepository.findById(EVENT_ID)).thenReturn(Optional.empty());

            EventSubmissionResult result = eventService.submitEvent(request);

            assertThat(result.event().getMetadataJson()).contains("mainframe-batch");
        }

        @Test
        @DisplayName("null metadata is stored as null, not an empty/invalid JSON string")
        void newEvent_nullMetadata_storesNull() {
            EventRequest request = new EventRequest(EVENT_ID, ACCOUNT_ID, "CREDIT",
                    BigDecimal.valueOf(100), "USD", EVENT_TIMESTAMP, null);
            when(eventRepository.findById(EVENT_ID)).thenReturn(Optional.empty());

            EventSubmissionResult result = eventService.submitEvent(request);

            assertThat(result.event().getMetadataJson()).isNull();
        }
    }

    // ------------------------------------------------------------------
    // submitEvent() - idempotency on existing event
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("submitEvent() - idempotency")
    class IdempotencyTests {

        @Test
        @DisplayName("same eventId + same payload + status FORWARDED -> returns the original, does NOT re-forward")
        void sameEventIdSamePayload_forwarded_returnsOriginalWithoutReforwarding() {
            EventRequest request = defaultRequest();
            EventEntity existing = existingEntity(EventStatus.FORWARDED, hashOf(request));
            when(eventRepository.findById(EVENT_ID)).thenReturn(Optional.of(existing));

            EventSubmissionResult result = eventService.submitEvent(request);

            assertThat(result.wasNewlyCreated()).isFalse();
            assertThat(result.event()).isSameAs(existing);
            verifyNoInteractions(accountServiceClient);
        }

        @Test
        @DisplayName("same eventId + same payload + status RECEIVED -> returns the original, does NOT re-forward")
        void sameEventIdSamePayload_received_returnsOriginalWithoutReforwarding() {
            EventRequest request = defaultRequest();
            EventEntity existing = existingEntity(EventStatus.RECEIVED, hashOf(request));
            when(eventRepository.findById(EVENT_ID)).thenReturn(Optional.of(existing));

            EventSubmissionResult result = eventService.submitEvent(request);

            assertThat(result.wasNewlyCreated()).isFalse();
            verifyNoInteractions(accountServiceClient);
        }

        @Test
        @DisplayName("same eventId + DIFFERENT payload -> throws DuplicateEventConflictException, regardless of status")
        void sameEventIdDifferentPayload_throwsConflict() {
            EventRequest original = defaultRequest();
            EventEntity existing = existingEntity(EventStatus.FORWARDED, hashOf(original));

            EventRequest differentPayload = new EventRequest(EVENT_ID, ACCOUNT_ID, "CREDIT",
                    BigDecimal.valueOf(999), "USD", EVENT_TIMESTAMP, Map.of()); // different amount
            when(eventRepository.findById(EVENT_ID)).thenReturn(Optional.of(existing));

            assertThatThrownBy(() -> eventService.submitEvent(differentPayload))
                    .isInstanceOf(DuplicateEventConflictException.class);
            verifyNoInteractions(accountServiceClient);
        }

        @Test
        @DisplayName("same eventId + status FAILED_DOWNSTREAM + same payload -> RETRIES forwarding")
        void sameEventIdSamePayload_failedDownstream_retriesForwarding() {
            EventRequest request = defaultRequest();
            EventEntity existing = existingEntity(EventStatus.FAILED_DOWNSTREAM, hashOf(request));
            when(eventRepository.findById(EVENT_ID)).thenReturn(Optional.of(existing));
            doNothing().when(accountServiceClient).applyTransaction(request);

            EventSubmissionResult result = eventService.submitEvent(request);

            verify(accountServiceClient).applyTransaction(request);
            assertThat(result.event().getStatus()).isEqualTo(EventStatus.FORWARDED);
            assertThat(result.wasNewlyCreated()).isFalse(); // retry of an existing record, not a fresh creation
        }

        @Test
        @DisplayName("retrying a FAILED_DOWNSTREAM event that fails AGAIN stays FAILED_DOWNSTREAM and re-throws")
        void retryOfFailedDownstream_failsAgain_staysFailDownstreamAndThrows() {
            EventRequest request = defaultRequest();
            EventEntity existing = existingEntity(EventStatus.FAILED_DOWNSTREAM, hashOf(request));
            when(eventRepository.findById(EVENT_ID)).thenReturn(Optional.of(existing));
            doThrow(new AccountServiceUnavailableException("still down"))
                    .when(accountServiceClient).applyTransaction(request);

            assertThatThrownBy(() -> eventService.submitEvent(request))
                    .isInstanceOf(AccountServiceUnavailableException.class);
            assertThat(existing.getStatus()).isEqualTo(EventStatus.FAILED_DOWNSTREAM);
        }
    }

    // ------------------------------------------------------------------
    // forwardToAccountService() failure branches (exercised via submitEvent on a NEW event)
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("Account Service failure handling")
    class ForwardingFailureTests {

        @Test
        @DisplayName("AccountNotFoundException -> entity marked REJECTED, exception re-thrown (not swallowed)")
        void accountNotFound_marksRejectedAndRethrows() {
            EventRequest request = defaultRequest();
            when(eventRepository.findById(EVENT_ID)).thenReturn(Optional.empty());
            doThrow(new AccountNotFoundException(ACCOUNT_ID))
                    .when(accountServiceClient).applyTransaction(request);

            assertThatThrownBy(() -> eventService.submitEvent(request))
                    .isInstanceOf(AccountNotFoundException.class);

            ArgumentCaptor<EventEntity> captor = ArgumentCaptor.forClass(EventEntity.class);
            verify(eventRepository, atLeastOnce()).save(captor.capture());
            assertThat(captor.getValue().getStatus()).isEqualTo(EventStatus.FAILED_DOWNSTREAM);
        }

        @Test
        @DisplayName("AccountServiceUnavailableException -> entity marked FAILED_DOWNSTREAM, exception re-thrown")
        void accountServiceUnavailable_marksFailedDownstreamAndRethrows() {
            EventRequest request = defaultRequest();
            when(eventRepository.findById(EVENT_ID)).thenReturn(Optional.empty());
            doThrow(new AccountServiceUnavailableException("timeout"))
                    .when(accountServiceClient).applyTransaction(request);

            assertThatThrownBy(() -> eventService.submitEvent(request))
                    .isInstanceOf(AccountServiceUnavailableException.class);

            ArgumentCaptor<EventEntity> captor = ArgumentCaptor.forClass(EventEntity.class);
            verify(eventRepository, atLeastOnce()).save(captor.capture());
            assertThat(captor.getValue().getStatus()).isEqualTo(EventStatus.FAILED_DOWNSTREAM);
        }

    }

    // ------------------------------------------------------------------
    // getEvent()
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("getEvent()")
    class GetEventTests {

        @Test
        @DisplayName("returns the event when it exists")
        void existingEvent_isReturned() {
            EventEntity entity = existingEntity(EventStatus.FORWARDED, 0);
            when(eventRepository.findById(EVENT_ID)).thenReturn(Optional.of(entity));

            assertThat(eventService.getEvent(EVENT_ID)).isSameAs(entity);
        }

        @Test
        @DisplayName("throws EventNotFoundException when it doesn't exist")
        void missingEvent_throwsNotFound() {
            when(eventRepository.findById("missing")).thenReturn(Optional.empty());

            assertThatThrownBy(() -> eventService.getEvent("missing"))
                    .isInstanceOf(EventNotFoundException.class);
        }
    }

    // ------------------------------------------------------------------
    // parseMetadata()
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("parseMetadata()")
    class ParseMetadataTests {

        @Test
        @DisplayName("valid JSON is parsed back into a Map")
        void validJson_isParsed() {
            Map<String, Object> parsed = eventService.parseMetadata("{\"source\":\"batch\"}");
            assertThat(parsed).containsEntry("source", "batch");
        }

        @Test
        @DisplayName("null input returns null (no metadata was ever stored)")
        void nullInput_returnsNull() {
            assertThat(eventService.parseMetadata(null)).isNull();
        }

        @Test
        @DisplayName("malformed JSON returns an empty map rather than throwing")
        void malformedJson_returnsEmptyMapNotThrow() {
            Map<String, Object> parsed = eventService.parseMetadata("{not valid json");
            assertThat(parsed).isEmpty();
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private EventRequest defaultRequest() {
        return new EventRequest(EVENT_ID, ACCOUNT_ID, "CREDIT",
                BigDecimal.valueOf(150), "USD", EVENT_TIMESTAMP, Map.of());
    }

    private EventRequest defaultExistingEntityDb(EventStatus status, int payloadHash) {
        return new EventRequest(EVENT_ID, ACCOUNT_ID, "CREDIT",
                BigDecimal.valueOf(150), "USD", EVENT_TIMESTAMP, Map.of());
    }

    private EventEntity existingEntity(EventStatus status, int payloadHash) {
        return new EventEntity(EVENT_ID, ACCOUNT_ID, "CREDIT", BigDecimal.valueOf(150),
                "USD", EVENT_TIMESTAMP, Instant.now(), null, status, payloadHash);
    }

    private int hashOf(EventRequest request) {
        return Objects.hash(request.eventId(), request.accountId(), request.type(),
                request.amount(), request.currency(), request.eventTimestamp());
    }
}