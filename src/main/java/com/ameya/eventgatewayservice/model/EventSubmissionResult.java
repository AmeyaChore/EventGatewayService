package com.ameya.eventgatewayservice.model;


public record EventSubmissionResult(EventEntity event, boolean wasNewlyCreated) {
}