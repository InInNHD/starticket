package com.starticket.venue;

import com.starticket.common.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class VenueService {

    private static final int MAX_SEATS_PER_AREA = 10_000;

    private final VenueRepository venues;
    private final VenueAreaRepository areas;
    private final SeatRepository seats;

    VenueService(VenueRepository venues, VenueAreaRepository areas, SeatRepository seats) {
        this.venues = venues;
        this.areas = areas;
        this.seats = seats;
    }

    @Transactional
    VenueSummary createVenue(CreateVenueRequest request) {
        Venue venue = Venue.create(clean(request.name()), clean(request.city()), clean(request.address()));
        return VenueSummary.from(venues.save(venue));
    }

    @Transactional(readOnly = true)
    List<VenueSummary> listVenues() {
        return venues.findAll().stream().map(VenueSummary::from).toList();
    }

    @Transactional
    AreaView createArea(Long venueId, CreateAreaRequest request) {
        Venue venue = requireVenue(venueId);
        String code = request.code().toUpperCase(Locale.ROOT);
        if (areas.existsByVenue_IdAndCode(venueId, code)) {
            throw new ApiException(HttpStatus.CONFLICT, "区域编码已存在");
        }
        VenueArea area = areas.save(VenueArea.create(venue, clean(request.name()), code, request.sortOrder()));
        return new AreaView(area.getId(), area.getName(), area.getCode(), area.getSortOrder(), List.of());
    }

    @Transactional
    SeatGenerationResult generateSeats(Long areaId, GenerateSeatsRequest request) {
        VenueArea area = areas.findById(areaId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "区域不存在"));
        if (seats.existsByArea_Id(areaId)) {
            throw new ApiException(HttpStatus.CONFLICT, "区域已有座位，不能重复生成");
        }
        int total = Math.multiplyExact(request.rowCount(), request.seatsPerRow());
        if (total > MAX_SEATS_PER_AREA) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "单个区域最多生成10000个座位");
        }
        List<Seat> generated = new ArrayList<>(total);
        for (int row = 1; row <= request.rowCount(); row++) {
            for (int number = 1; number <= request.seatsPerRow(); number++) {
                generated.add(Seat.create(area, String.valueOf(row), number));
            }
        }
        // ponytail: 规则生成上限为1万座，若真实压测证明过慢再改为 JDBC 批量插入。
        seats.saveAll(generated);
        return new SeatGenerationResult(areaId, total);
    }

    @Transactional(readOnly = true)
    VenueLayout getLayout(Long venueId) {
        Venue venue = requireVenue(venueId);
        List<VenueArea> venueAreas = areas.findByVenue_IdOrderBySortOrderAscIdAsc(venueId);
        List<Long> areaIds = venueAreas.stream().map(VenueArea::getId).toList();
        Map<Long, List<SeatView>> seatsByArea = areaIds.isEmpty()
                ? Map.of()
                : seats.findByArea_IdIn(areaIds).stream()
                        .sorted(Comparator.comparing(Seat::getAreaId)
                                .thenComparingInt(seat -> Integer.parseInt(seat.getRowLabel()))
                                .thenComparingInt(Seat::getSeatNumber))
                        .collect(Collectors.groupingBy(
                                Seat::getAreaId,
                                Collectors.mapping(SeatView::from, Collectors.toList())));
        List<AreaView> areaViews = venueAreas.stream()
                .map(area -> new AreaView(
                        area.getId(), area.getName(), area.getCode(), area.getSortOrder(),
                        seatsByArea.getOrDefault(area.getId(), List.of())))
                .toList();
        return new VenueLayout(VenueSummary.from(venue), areaViews);
    }

    private Venue requireVenue(Long venueId) {
        return venues.findById(venueId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "场馆不存在"));
    }

    public void requireEnabledVenue(Long venueId) {
        Venue venue = requireVenue(venueId);
        if (!venue.isEnabled()) {
            throw new ApiException(HttpStatus.CONFLICT, "场馆已停用");
        }
    }

    public void requireAreaWithSeats(Long venueId, Long areaId) {
        if (!areas.existsByIdAndVenue_Id(areaId, venueId)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "区域不属于当前场馆");
        }
        if (!seats.existsByArea_Id(areaId)) {
            throw new ApiException(HttpStatus.CONFLICT, "区域尚未生成座位");
        }
    }

    private static String clean(String value) {
        return value.trim();
    }

}
