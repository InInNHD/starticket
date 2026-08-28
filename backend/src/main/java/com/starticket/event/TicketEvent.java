package com.starticket.event;

import com.starticket.common.ApiException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.springframework.http.HttpStatus;

import java.time.Instant;

@Entity
@Table(name = "st_event")
class TicketEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "organizer_id", nullable = false)
    private Long organizerId;

    @Column(nullable = false, length = 120)
    private String title;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private EventCategory category;

    @Column(nullable = false, length = 4000)
    private String description;

    @Column(name = "poster_url", length = 500)
    private String posterUrl;

    @Column(name = "purchase_notice", nullable = false, length = 2000)
    private String purchaseNotice;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private EventStatus status;

    @Column(name = "review_note", length = 500)
    private String reviewNote;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected TicketEvent() {
    }

    static TicketEvent create(long organizerId, EventDraft draft) {
        TicketEvent event = new TicketEvent();
        event.organizerId = organizerId;
        event.status = EventStatus.DRAFT;
        event.createdAt = Instant.now();
        event.apply(draft);
        return event;
    }

    void update(EventDraft draft) {
        requireEditable();
        apply(draft);
    }

    void submit() {
        requireEditable();
        status = EventStatus.PENDING_REVIEW;
        reviewNote = null;
        updatedAt = Instant.now();
    }

    void approve() {
        requirePendingReview();
        status = EventStatus.APPROVED;
        reviewNote = null;
        updatedAt = Instant.now();
    }

    void reject(String note) {
        requirePendingReview();
        status = EventStatus.REJECTED;
        reviewNote = note;
        updatedAt = Instant.now();
    }

    void cancel() {
        if (status != EventStatus.DRAFT && status != EventStatus.REJECTED
                && status != EventStatus.PENDING_REVIEW && status != EventStatus.APPROVED) {
            throw new ApiException(HttpStatus.CONFLICT, "当前活动状态不允许取消");
        }
        status = EventStatus.CANCELLED;
        reviewNote = null;
        updatedAt = Instant.now();
    }

    void offShelf(String note) {
        if (status != EventStatus.APPROVED && status != EventStatus.ON_SALE) {
            throw new ApiException(HttpStatus.CONFLICT, "只有公开活动可以下架");
        }
        status = EventStatus.OFF_SHELF;
        reviewNote = note;
        updatedAt = Instant.now();
    }

    void end() {
        if (status != EventStatus.APPROVED && status != EventStatus.ON_SALE) return;
        status = EventStatus.ENDED;
        updatedAt = Instant.now();
    }

    void requireEditable() {
        if (status != EventStatus.DRAFT && status != EventStatus.REJECTED) {
            throw new ApiException(HttpStatus.CONFLICT, "当前活动状态不允许修改");
        }
    }

    private void requirePendingReview() {
        if (status != EventStatus.PENDING_REVIEW) {
            throw new ApiException(HttpStatus.CONFLICT, "活动不在待审核状态");
        }
    }

    private void apply(EventDraft draft) {
        title = draft.title();
        category = draft.category();
        description = draft.description();
        posterUrl = draft.posterUrl();
        purchaseNotice = draft.purchaseNotice();
        updatedAt = Instant.now();
    }

    Long getId() { return id; }
    Long getOrganizerId() { return organizerId; }
    String getTitle() { return title; }
    EventCategory getCategory() { return category; }
    String getDescription() { return description; }
    String getPosterUrl() { return posterUrl; }
    String getPurchaseNotice() { return purchaseNotice; }
    EventStatus getStatus() { return status; }
    String getReviewNote() { return reviewNote; }
    Instant getCreatedAt() { return createdAt; }
    Instant getUpdatedAt() { return updatedAt; }
}

record EventDraft(
        String title,
        EventCategory category,
        String description,
        String posterUrl,
        String purchaseNotice
) {
}
