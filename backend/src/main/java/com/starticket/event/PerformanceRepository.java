package com.starticket.event;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.time.Instant;
import java.util.List;

interface PerformanceRepository extends JpaRepository<Performance, Long> {
    List<Performance> findByEvent_IdOrderByStartsAtAsc(Long eventId);
    List<Performance> findByEvent_IdAndStatusOrderByStartsAtAsc(Long eventId, PerformanceStatus status);
    List<Performance> findByEvent_IdInOrderByStartsAtAsc(Collection<Long> eventIds);
    boolean existsByEvent_IdAndStatusAndSalesStartAtLessThanEqual(Long eventId, PerformanceStatus status, Instant now);
}
