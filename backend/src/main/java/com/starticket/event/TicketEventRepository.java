package com.starticket.event;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

interface TicketEventRepository extends JpaRepository<TicketEvent, Long> {
    List<TicketEvent> findByOrganizerIdOrderByUpdatedAtDesc(Long organizerId);
    List<TicketEvent> findByStatusInOrderByUpdatedAtDesc(Collection<EventStatus> statuses);

    @Query("""
            SELECT e FROM TicketEvent e
            WHERE e.status IN :statuses
              AND (:keyword = '' OR LOWER(e.title) LIKE LOWER(CONCAT('%', :keyword, '%')))
              AND (:category IS NULL OR e.category = :category)
            ORDER BY e.updatedAt DESC
            """)
    Page<TicketEvent> searchPublic(@Param("statuses") Collection<EventStatus> statuses,
                                   @Param("keyword") String keyword,
                                   @Param("category") EventCategory category,
                                   Pageable pageable);

    @Query("""
            SELECT e FROM TicketEvent e
            WHERE e.organizerId = :organizerId
              AND (:keyword = '' OR LOWER(e.title) LIKE LOWER(CONCAT('%', :keyword, '%')))
              AND (:status IS NULL OR e.status = :status)
            ORDER BY e.updatedAt DESC
            """)
    Page<TicketEvent> searchOwned(@Param("organizerId") Long organizerId,
                                  @Param("keyword") String keyword,
                                  @Param("status") EventStatus status,
                                  Pageable pageable);
}
