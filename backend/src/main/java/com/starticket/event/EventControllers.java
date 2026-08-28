package com.starticket.event;

import com.starticket.common.PageResult;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.validation.annotation.Validated;

import java.util.List;

@RestController
@RequestMapping("/api/organizer")
@Validated
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
    PageResult<EventSummary> list(Authentication authentication,
                                  @RequestParam(defaultValue = "") String keyword,
                                  @RequestParam(required = false) EventStatus status,
                                  @RequestParam(defaultValue = "0") @Min(0) int page,
                                  @RequestParam(defaultValue = "10") @Min(1) @Max(100) int size) {
        return eventService.listOwned(authentication.getName(), keyword, status, page, size);
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

    @PutMapping("/performances/{performanceId}")
    PerformanceView updatePerformance(@PathVariable Long performanceId, Authentication authentication,
                                      @Valid @RequestBody CreatePerformanceRequest request) {
        return eventService.updatePerformance(performanceId, authentication.getName(), request);
    }

    @PostMapping("/performances/{performanceId}/cancel")
    PerformanceView cancelPerformance(@PathVariable Long performanceId, Authentication authentication) {
        return eventService.cancelPerformance(performanceId, authentication.getName());
    }

    @PostMapping("/performances/{performanceId}/tiers")
    @ResponseStatus(HttpStatus.CREATED)
    TicketTierView addTicketTier(@PathVariable Long performanceId, Authentication authentication,
                                 @Valid @RequestBody CreateTicketTierRequest request) {
        return eventService.addTicketTier(performanceId, authentication.getName(), request);
    }

    @PutMapping("/tiers/{tierId}")
    TicketTierView updateTicketTier(@PathVariable Long tierId, Authentication authentication,
                                    @Valid @RequestBody UpdateTicketTierRequest request) {
        return eventService.updateTicketTier(tierId, authentication.getName(), request);
    }

    @PostMapping("/events/{eventId}/submit")
    EventView submit(@PathVariable Long eventId, Authentication authentication) {
        return eventService.submit(eventId, authentication.getName());
    }

    @PostMapping("/events/{eventId}/cancel")
    EventView cancel(@PathVariable Long eventId, Authentication authentication) {
        return eventService.cancel(eventId, authentication.getName());
    }
}

@RestController
@RequestMapping("/api/admin/events")
@Validated
class AdminEventController {

    private final EventService eventService;

    AdminEventController(EventService eventService) {
        this.eventService = eventService;
    }

    @GetMapping("/pending")
    List<EventSummary> pending() {
        return eventService.listPending();
    }

    @GetMapping
    PageResult<EventSummary> list(@RequestParam(defaultValue = "") String keyword,
                                  @RequestParam(required = false) EventStatus status,
                                  @RequestParam(defaultValue = "0") @Min(0) int page,
                                  @RequestParam(defaultValue = "10") @Min(1) @Max(100) int size) {
        return eventService.listAdmin(keyword, status, page, size);
    }

    @GetMapping("/{eventId}")
    EventView get(@PathVariable Long eventId) {
        return eventService.getForReview(eventId);
    }

    @PostMapping("/{eventId}/approve")
    EventView approve(@PathVariable Long eventId, Authentication authentication) {
        return eventService.approve(eventId, authentication.getName());
    }

    @PostMapping("/{eventId}/reject")
    EventView reject(@PathVariable Long eventId, @Valid @RequestBody RejectEventRequest request,
                     Authentication authentication) {
        return eventService.reject(eventId, request.note(), authentication.getName());
    }

    @PostMapping("/{eventId}/off-shelf")
    EventView offShelf(@PathVariable Long eventId, @Valid @RequestBody OffShelfEventRequest request,
                       Authentication authentication) {
        return eventService.offShelf(eventId, request.note(), authentication.getName());
    }
}

@RestController
@RequestMapping("/api/events")
@Validated
class PublicEventController {

    private final EventService eventService;

    PublicEventController(EventService eventService) {
        this.eventService = eventService;
    }

    @GetMapping
    PageResult<EventSummary> list(@RequestParam(defaultValue = "") String keyword,
                                  @RequestParam(required = false) EventCategory category,
                                  @RequestParam(defaultValue = "0") @Min(0) int page,
                                  @RequestParam(defaultValue = "12") @Min(1) @Max(100) int size) {
        return eventService.listPublic(keyword, category, page, size);
    }

    @GetMapping("/{eventId}")
    EventView get(@PathVariable Long eventId) {
        return eventService.getPublic(eventId);
    }
}
