package com.ameya.eventgatewayservice.model;

public enum EventStatus {
    RECEIVED,           // stored locally, not yet forwarded
    FORWARDED,          // successfully applied to Account Service
    FAILED_DOWNSTREAM   // Account Service was unreachable/failing when this was processed
}