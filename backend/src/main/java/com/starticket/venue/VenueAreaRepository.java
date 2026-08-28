package com.starticket.venue;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface VenueAreaRepository extends JpaRepository<VenueArea, Long> {

    List<VenueArea> findByVenue_IdOrderBySortOrderAscIdAsc(Long venueId);

    boolean existsByVenue_IdAndCode(Long venueId, String code);

    boolean existsByIdAndVenue_Id(Long id, Long venueId);
}
