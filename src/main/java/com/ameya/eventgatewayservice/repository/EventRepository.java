package com.ameya.eventgatewayservice.repository;

import com.ameya.eventgatewayservice.model.EventEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface EventRepository extends JpaRepository<EventEntity, String> {

    List<EventEntity> findByAccountIdOrderByEventTimestampAsc(String accountId);

    Page<EventEntity> findByAccountId(String accountId, Pageable pageable);
}