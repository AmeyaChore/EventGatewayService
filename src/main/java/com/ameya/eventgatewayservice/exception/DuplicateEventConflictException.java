package com.ameya.eventgatewayservice.exception;

/**
 * Thrown when an eventId has already been submitted but with a different
 * payload than the original. This is NOT plain idempotency (same id + same
 * payload) — that case is handled silently by returning the original event.
 */
public class DuplicateEventConflictException extends RuntimeException {
    public DuplicateEventConflictException(String eventId) {
        super("Event '" + eventId + "' already exists with a different payload");
    }
}