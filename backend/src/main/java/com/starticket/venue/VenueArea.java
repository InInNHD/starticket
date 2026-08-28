package com.starticket.venue;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "st_venue_area")
class VenueArea {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "venue_id", nullable = false)
    private Venue venue;

    @Column(nullable = false, length = 60)
    private String name;

    @Column(nullable = false, length = 20)
    private String code;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    protected VenueArea() {
    }

    static VenueArea create(Venue venue, String name, String code, int sortOrder) {
        VenueArea area = new VenueArea();
        area.venue = venue;
        area.name = name;
        area.code = code;
        area.sortOrder = sortOrder;
        return area;
    }

    Long getId() {
        return id;
    }

    Long getVenueId() {
        return venue.getId();
    }

    String getName() {
        return name;
    }

    String getCode() {
        return code;
    }

    int getSortOrder() {
        return sortOrder;
    }
}
