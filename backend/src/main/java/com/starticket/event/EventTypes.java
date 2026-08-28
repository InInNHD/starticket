package com.starticket.event;

enum EventCategory {
    CONCERT,
    THEATRE,
    EXHIBITION,
    COMEDY,
    CAMPUS,
    OTHER
}

enum EventStatus {
    DRAFT,
    PENDING_REVIEW,
    APPROVED,
    ON_SALE,
    REJECTED,
    OFF_SHELF,
    ENDED
}

enum PerformanceStatus {
    SCHEDULED,
    CANCELLED
}
