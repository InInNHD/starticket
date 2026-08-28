package com.starticket.event;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

record EventDraftRequest(
        @NotBlank @Size(max = 120) String title,
        @NotNull EventCategory category,
        @NotBlank @Size(max = 4000) String description,
        @Size(max = 500) String posterUrl,
        @NotBlank @Size(max = 2000) String purchaseNotice
) {
}

record CreatePerformanceRequest(
        @NotNull Long venueId,
        @NotBlank @Size(max = 100) String name,
        @NotNull Instant startsAt,
        @NotNull Instant salesStartAt,
        @NotNull Instant salesEndAt
) {
}

record CreateTicketTierRequest(
        @NotNull Long areaId,
        @NotBlank @Size(max = 60) String name,
        @NotNull @DecimalMin("0.01") @Digits(integer = 8, fraction = 2) BigDecimal price,
        @NotBlank @Pattern(regexp = "#[0-9a-fA-F]{6}") String color,
        @Min(1) @Max(6) int purchaseLimit
) {
}

record UpdateTicketTierRequest(
        @NotBlank @Size(max = 60) String name,
        @NotNull @DecimalMin("0.01") @Digits(integer = 8, fraction = 2) BigDecimal price,
        @NotBlank @Pattern(regexp = "#[0-9a-fA-F]{6}") String color,
        @Min(1) @Max(6) int purchaseLimit,
        boolean enabled
) {
}

record RejectEventRequest(@NotBlank @Size(max = 500) String note) {
}

record EventSummary(
        Long id,
        Long organizerId,
        String title,
        EventCategory category,
        EventStatus status,
        String reviewNote,
        Instant createdAt,
        Instant updatedAt
) {
    static EventSummary from(TicketEvent event) {
        return new EventSummary(event.getId(), event.getOrganizerId(), event.getTitle(), event.getCategory(),
                event.getStatus(), event.getReviewNote(), event.getCreatedAt(), event.getUpdatedAt());
    }
}

record TicketTierView(
        Long id,
        Long areaId,
        String name,
        BigDecimal price,
        String color,
        int purchaseLimit,
        boolean enabled
) {
    static TicketTierView from(TicketTier tier) {
        return new TicketTierView(tier.getId(), tier.getAreaId(), tier.getName(), tier.getPrice(),
                tier.getColor(), tier.getPurchaseLimit(), tier.isEnabled());
    }
}

record PerformanceView(
        Long id,
        Long venueId,
        String name,
        Instant startsAt,
        Instant salesStartAt,
        Instant salesEndAt,
        PerformanceStatus status,
        List<TicketTierView> ticketTiers
) {
}

record EventView(
        Long id,
        Long organizerId,
        String title,
        EventCategory category,
        String description,
        String posterUrl,
        String purchaseNotice,
        EventStatus status,
        String reviewNote,
        Instant createdAt,
        Instant updatedAt,
        List<PerformanceView> performances
) {
}
