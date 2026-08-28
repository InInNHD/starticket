package com.starticket.event;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/organizer")
class OrganizerEventController {

    private final EventService eventService;

    OrganizerEventController(EventService eventService) {
        this.eventService = eventService;
    }

    @PostMapping("/events")
    @ResponseStatus(HttpStatus.CREATED)
    EventView create(Authentication authentication, @Valid @RequestBody EventDraftRequest request) {
        return eventService.create(authentication.getName(), request);
    }

    @GetMapping("/events")
    List<EventSummary> list(Authentication authentication) {
        return eventService.listOwned(authentication.getName());
    }

    @GetMapping("/events/{eventId}")
    EventView get(@PathVariable Long eventId, Authentication authentication) {
        return eventService.getOwned(eventId, authentication.getName());
    }

    @PutMapping("/events/{eventId}")
    EventView update(@PathVariable Long eventId, Authentication authentication,
                     @Valid @RequestBody EventDraftRequest request) {
        return eventService.update(eventId, authentication.getName(), request);
    }

    @PostMapping("/events/{eventId}/performances")
    @ResponseStatus(HttpStatus.CREATED)
    PerformanceView addPerformance(@PathVariable Long eventId, Authentication authentication,
                                   @Valid @RequestBody CreatePerformanceRequest request) {
        return eventService.addPerformance(eventId, authentication.getName(), request);
    }

    @PostMapping("/performances/{performanceId}/tiers")
    @ResponseStatus(HttpStatus.CREATED)
    TicketTierView addTicketTier(@PathVariable Long performanceId, Authentication authentication,
                                 @Valid @RequestBody CreateTicketTierRequest request) {
        return eventService.addTicketTier(performanceId, authentication.getName(), request);
    }

    @PostMapping("/events/{eventId}/submit")
    EventView submit(@PathVariable Long eventId, Authentication authentication) {
        return eventService.submit(eventId, authentication.getName());
    }
}

@RestController
@RequestMapping("/api/admin/events")
class AdminEventController {

    private final EventService eventService;

    AdminEventController(EventService eventService) {
        this.eventService = eventService;
    }

    @GetMapping("/pending")
    List<EventSummary> pending() {
        return eventService.listPending();
    }

    @GetMapping("/{eventId}")
    EventView get(@PathVariable Long eventId) {
        return eventService.getForReview(eventId);
    }

    @PostMapping("/{eventId}/approve")
    EventView approve(@PathVariable Long eventId) {
        return eventService.approve(eventId);
    }

    @PostMapping("/{eventId}/reject")
    EventView reject(@PathVariable Long eventId, @Valid @RequestBody RejectEventRequest request) {
        return eventService.reject(eventId, request.note());
    }
}

@RestController
@RequestMapping("/api/events")
class PublicEventController {

    private final EventService eventService;

    PublicEventController(EventService eventService) {
        this.eventService = eventService;
    }

    @GetMapping
    List<EventSummary> list() {
        return eventService.listPublic();
    }

    @GetMapping("/{eventId}")
    EventView get(@PathVariable Long eventId) {
        return eventService.getPublic(eventId);
    }
}
