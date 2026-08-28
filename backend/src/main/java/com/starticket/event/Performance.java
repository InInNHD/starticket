package com.starticket.event;

import com.starticket.common.ApiException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.time.Instant;
import org.springframework.http.HttpStatus;

@Entity
@Table(name = "st_performance")
class Performance {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "event_id", nullable = false)
    private TicketEvent event;

    @Column(name = "venue_id", nullable = false)
    private Long venueId;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(name = "starts_at", nullable = false)
    private Instant startsAt;

    @Column(name = "sales_start_at", nullable = false)
    private Instant salesStartAt;

    @Column(name = "sales_end_at", nullable = false)
    private Instant salesEndAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PerformanceStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected Performance() {
    }

    static Performance create(
            TicketEvent event, Long venueId, String name, Instant startsAt, Instant salesStartAt, Instant salesEndAt) {
        Performance performance = new Performance();
        performance.event = event;
        performance.venueId = venueId;
        performance.name = name;
        performance.startsAt = startsAt;
        performance.salesStartAt = salesStartAt;
        performance.salesEndAt = salesEndAt;
        performance.status = PerformanceStatus.SCHEDULED;
        performance.createdAt = Instant.now();
        return performance;
    }

    void update(Long venueId, String name, Instant startsAt, Instant salesStartAt, Instant salesEndAt) {
        if (status != PerformanceStatus.SCHEDULED) {
            throw new ApiException(HttpStatus.CONFLICT, "已停用场次不能修改");
        }
        this.venueId = venueId;
        this.name = name;
        this.startsAt = startsAt;
        this.salesStartAt = salesStartAt;
        this.salesEndAt = salesEndAt;
    }

    void cancel() {
        if (status == PerformanceStatus.CANCELLED) return;
        status = PerformanceStatus.CANCELLED;
    }

    Long getId() { return id; }
    Long getEventId() { return event.getId(); }
    TicketEvent getEvent() { return event; }
    Long getVenueId() { return venueId; }
    String getName() { return name; }
    Instant getStartsAt() { return startsAt; }
    Instant getSalesStartAt() { return salesStartAt; }
    Instant getSalesEndAt() { return salesEndAt; }
    PerformanceStatus getStatus() { return status; }
}
