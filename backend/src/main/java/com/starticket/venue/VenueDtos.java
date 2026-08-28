package com.starticket.venue;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

record CreateVenueRequest(
        @NotBlank @Size(max = 100) String name,
        @NotBlank @Size(max = 60) String city,
        @NotBlank @Size(max = 255) String address
) {
}

record CreateAreaRequest(
        @NotBlank @Size(max = 60) String name,
        @NotBlank @Pattern(regexp = "[a-zA-Z0-9_-]{1,20}") String code,
        @Min(0) @Max(10000) int sortOrder
) {
}

record GenerateSeatsRequest(
        @Min(1) @Max(100) int rowCount,
        @Min(1) @Max(200) int seatsPerRow
) {
}

record VenueSummary(Long id, String name, String city, String address, boolean enabled) {
    static VenueSummary from(Venue venue) {
        return new VenueSummary(
                venue.getId(), venue.getName(), venue.getCity(), venue.getAddress(), venue.isEnabled());
    }
}

record SeatView(Long id, String rowLabel, int seatNumber, String code, boolean enabled) {
    static SeatView from(Seat seat) {
        return new SeatView(
                seat.getId(), seat.getRowLabel(), seat.getSeatNumber(), seat.getCode(), seat.isEnabled());
    }
}

record AreaView(Long id, String name, String code, int sortOrder, List<SeatView> seats) {
}

record VenueLayout(VenueSummary venue, List<AreaView> areas) {
}

record SeatGenerationResult(Long areaId, int created) {
}
