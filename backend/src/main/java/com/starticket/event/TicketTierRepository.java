package com.starticket.event;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

interface TicketTierRepository extends JpaRepository<TicketTier, Long> {
    boolean existsByPerformance_Id(Long performanceId);
    boolean existsByPerformance_IdAndAreaId(Long performanceId, Long areaId);
    List<TicketTier> findByPerformance_IdInOrderByPriceAsc(Collection<Long> performanceIds);
}
