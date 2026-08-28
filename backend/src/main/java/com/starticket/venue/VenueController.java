package com.starticket.venue;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/admin")
class VenueController {

    private final VenueService venueService;

    VenueController(VenueService venueService) {
        this.venueService = venueService;
    }

    @PostMapping("/venues")
    @ResponseStatus(HttpStatus.CREATED)
    VenueSummary createVenue(@Valid @RequestBody CreateVenueRequest request) {
        return venueService.createVenue(request);
    }

    @GetMapping("/venues")
    List<VenueSummary> listVenues() {
        return venueService.listVenues();
    }

    @PostMapping("/venues/{venueId}/areas")
    @ResponseStatus(HttpStatus.CREATED)
    AreaView createArea(@PathVariable Long venueId, @Valid @RequestBody CreateAreaRequest request) {
        return venueService.createArea(venueId, request);
    }

    @PostMapping("/areas/{areaId}/seats/generate")
    @ResponseStatus(HttpStatus.CREATED)
    SeatGenerationResult generateSeats(
            @PathVariable Long areaId, @Valid @RequestBody GenerateSeatsRequest request) {
        return venueService.generateSeats(areaId, request);
    }

    @GetMapping("/venues/{venueId}/layout")
    VenueLayout getLayout(@PathVariable Long venueId) {
        return venueService.getLayout(venueId);
    }
}

@RestController
@RequestMapping("/api/organizer/venues")
class OrganizerVenueController {

    private final VenueService venueService;

    OrganizerVenueController(VenueService venueService) {
        this.venueService = venueService;
    }

    @GetMapping
    List<VenueSummary> listVenues() {
        return venueService.listVenues();
    }

    @GetMapping("/{venueId}/layout")
    VenueLayout getLayout(@PathVariable Long venueId) {
        return venueService.getLayout(venueId);
    }
}
