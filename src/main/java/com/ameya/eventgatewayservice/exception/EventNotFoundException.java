package com.ameya.eventgatewayservice.exception;

public class EventNotFoundException extends RuntimeException {
    public EventNotFoundException(String eventId) {
        super("Event '" + eventId + "' was not found");
    }
}