package com.ameya.eventgatewayservice.repository;

import com.ameya.eventgatewayservice.model.EventEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface EventRepository extends JpaRepository<EventEntity, String> {

    List<EventEntity> findByAccountIdOrderByEventTimestampAsc(String accountId);
}