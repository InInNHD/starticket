package com.starticket.event;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

interface TicketEventRepository extends JpaRepository<TicketEvent, Long> {
    List<TicketEvent> findByOrganizerIdOrderByUpdatedAtDesc(Long organizerId);
    List<TicketEvent> findByStatusInOrderByUpdatedAtDesc(Collection<EventStatus> statuses);
}
