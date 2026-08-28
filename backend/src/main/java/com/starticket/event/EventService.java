package com.starticket.event;

import com.starticket.account.AccountLookup;
import com.starticket.common.ApiException;
import com.starticket.inventory.InventoryInitializer;
import com.starticket.venue.VenueService;
import org.springframework.http.HttpStatus;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
class EventService {

    private static final Set<EventStatus> PUBLIC_STATUSES = Set.of(EventStatus.APPROVED, EventStatus.ON_SALE);

    private final TicketEventRepository events;
    private final PerformanceRepository performances;
    private final TicketTierRepository tiers;
    private final AccountLookup accounts;
    private final VenueService venues;
    private final InventoryInitializer inventory;
    private final ObjectProvider<RedisEventCache> eventCaches;

    EventService(TicketEventRepository events, PerformanceRepository performances, TicketTierRepository tiers,
                 AccountLookup accounts, VenueService venues, InventoryInitializer inventory,
                 ObjectProvider<RedisEventCache> eventCaches) {
        this.events = events;
        this.performances = performances;
        this.tiers = tiers;
        this.accounts = accounts;
        this.venues = venues;
        this.inventory = inventory;
        this.eventCaches = eventCaches;
    }

    @Transactional
    EventView create(String username, EventDraftRequest request) {
        TicketEvent event = events.save(TicketEvent.create(accounts.requireUserId(username), draft(request)));
        return view(event);
    }

    @Transactional
    EventView update(Long eventId, String username, EventDraftRequest request) {
        TicketEvent event = requireOwned(eventId, username);
        event.update(draft(request));
        evict(eventId);
        return view(event);
    }

    @Transactional(readOnly = true)
    List<EventSummary> listOwned(String username) {
        long organizerId = accounts.requireUserId(username);
        return events.findByOrganizerIdOrderByUpdatedAtDesc(organizerId).stream().map(EventSummary::from).toList();
    }

    @Transactional(readOnly = true)
    EventView getOwned(Long eventId, String username) {
        return view(requireOwned(eventId, username));
    }

    @Transactional
    PerformanceView addPerformance(Long eventId, String username, CreatePerformanceRequest request) {
        TicketEvent event = requireOwned(eventId, username);
        event.requireEditable();
        venues.requireEnabledVenue(request.venueId());
        validateSchedule(request);
        Performance performance = performances.save(Performance.create(event, request.venueId(), clean(request.name()),
                request.startsAt(), request.salesStartAt(), request.salesEndAt()));
        return performanceView(performance, List.of());
    }

    @Transactional
    TicketTierView addTicketTier(Long performanceId, String username, CreateTicketTierRequest request) {
        Performance performance = performances.findById(performanceId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "场次不存在"));
        requireOwner(performance.getEvent(), username);
        performance.getEvent().requireEditable();
        venues.requireAreaWithSeats(performance.getVenueId(), request.areaId());
        if (tiers.existsByPerformance_IdAndAreaId(performanceId, request.areaId())) {
            throw new ApiException(HttpStatus.CONFLICT, "该区域已经配置票档");
        }
        TicketTier tier = tiers.save(TicketTier.create(performance, request.areaId(), clean(request.name()),
                request.price(), request.color().toUpperCase(), request.purchaseLimit()));
        return TicketTierView.from(tier);
    }

    @Transactional
    EventView submit(Long eventId, String username) {
        TicketEvent event = requireOwned(eventId, username);
        event.requireEditable();
        List<Performance> eventPerformances = performances.findByEvent_IdOrderByStartsAtAsc(eventId);
        if (eventPerformances.isEmpty()) {
            throw new ApiException(HttpStatus.CONFLICT, "至少需要配置一个场次");
        }
        boolean missingTier = eventPerformances.stream().anyMatch(item -> !tiers.existsByPerformance_Id(item.getId()));
        if (missingTier) {
            throw new ApiException(HttpStatus.CONFLICT, "每个场次至少需要配置一个票档");
        }
        event.submit();
        return view(event, eventPerformances);
    }

    @Transactional(readOnly = true)
    List<EventSummary> listPending() {
        return events.findByStatusInOrderByUpdatedAtDesc(List.of(EventStatus.PENDING_REVIEW)).stream()
                .map(EventSummary::from).toList();
    }

    @Transactional(readOnly = true)
    EventView getForReview(Long eventId) {
        return view(requireEvent(eventId));
    }

    @Transactional
    EventView approve(Long eventId) {
        TicketEvent event = requireEvent(eventId);
        event.approve();
        inventory.initializeEvent(eventId);
        evict(eventId);
        return view(event);
    }

    @Transactional
    EventView reject(Long eventId, String note) {
        TicketEvent event = requireEvent(eventId);
        event.reject(clean(note));
        evict(eventId);
        return view(event);
    }

    @Transactional(readOnly = true)
    List<EventSummary> listPublic() {
        // ponytail: 作品集初期数据量可控；接入真实运营数据后再加分页和搜索。
        return events.findByStatusInOrderByUpdatedAtDesc(PUBLIC_STATUSES).stream().map(EventSummary::from).toList();
    }

    @Transactional(readOnly = true)
    EventView getPublic(Long eventId) {
        RedisEventCache cache = eventCaches.getIfAvailable();
        if (cache != null) {
            EventView cached = cache.get(eventId).orElse(null);
            if (cached != null) return cached;
        }
        TicketEvent event = requireEvent(eventId);
        if (!PUBLIC_STATUSES.contains(event.getStatus())) {
            throw new ApiException(HttpStatus.NOT_FOUND, "活动不存在");
        }
        EventView result = view(event);
        if (cache != null) cache.put(result);
        return result;
    }

    private TicketEvent requireOwned(Long eventId, String username) {
        TicketEvent event = events.findById(eventId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "活动不存在"));
        requireOwner(event, username);
        return event;
    }

    private void requireOwner(TicketEvent event, String username) {
        if (!event.getOrganizerId().equals(accounts.requireUserId(username))) {
            throw new ApiException(HttpStatus.NOT_FOUND, "活动不存在");
        }
    }

    private TicketEvent requireEvent(Long eventId) {
        return events.findById(eventId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "活动不存在"));
    }

    private void evict(Long eventId) {
        RedisEventCache cache = eventCaches.getIfAvailable();
        if (cache != null) cache.evict(eventId);
    }

    private EventView view(TicketEvent event) {
        return view(event, performances.findByEvent_IdOrderByStartsAtAsc(event.getId()));
    }

    private EventView view(TicketEvent event, List<Performance> eventPerformances) {
        List<Long> performanceIds = eventPerformances.stream().map(Performance::getId).toList();
        Map<Long, List<TicketTierView>> tiersByPerformance = performanceIds.isEmpty() ? Map.of()
                : tiers.findByPerformance_IdInOrderByPriceAsc(performanceIds).stream()
                        .collect(Collectors.groupingBy(TicketTier::getPerformanceId,
                                Collectors.mapping(TicketTierView::from, Collectors.toList())));
        List<PerformanceView> performanceViews = eventPerformances.stream()
                .map(item -> performanceView(item, tiersByPerformance.getOrDefault(item.getId(), List.of())))
                .toList();
        return new EventView(event.getId(), event.getOrganizerId(), event.getTitle(), event.getCategory(),
                event.getDescription(), event.getPosterUrl(), event.getPurchaseNotice(), event.getStatus(),
                event.getReviewNote(), event.getCreatedAt(), event.getUpdatedAt(), performanceViews);
    }

    private static PerformanceView performanceView(Performance performance, List<TicketTierView> ticketTiers) {
        return new PerformanceView(performance.getId(), performance.getVenueId(), performance.getName(),
                performance.getStartsAt(), performance.getSalesStartAt(), performance.getSalesEndAt(),
                performance.getStatus(), ticketTiers);
    }

    private static EventDraft draft(EventDraftRequest request) {
        return new EventDraft(clean(request.title()), request.category(), clean(request.description()),
                emptyToNull(request.posterUrl()), clean(request.purchaseNotice()));
    }

    private static void validateSchedule(CreatePerformanceRequest request) {
        if (!request.salesStartAt().isBefore(request.salesEndAt())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "开售时间必须早于停售时间");
        }
        if (!request.salesEndAt().isBefore(request.startsAt())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "停售时间必须早于演出时间");
        }
        if (!request.startsAt().isAfter(Instant.now())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "演出时间必须晚于当前时间");
        }
    }

    private static String clean(String value) {
        return value.trim();
    }

    private static String emptyToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
