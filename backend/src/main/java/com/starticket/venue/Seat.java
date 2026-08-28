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
@Table(name = "st_seat")
class Seat {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "area_id", nullable = false)
    private VenueArea area;

    @Column(name = "row_label", nullable = false, length = 20)
    private String rowLabel;

    @Column(name = "seat_number", nullable = false)
    private int seatNumber;

    @Column(nullable = false, length = 64)
    private String code;

    @Column(nullable = false)
    private boolean enabled = true;

    protected Seat() {
    }

    static Seat create(VenueArea area, String rowLabel, int seatNumber) {
        Seat seat = new Seat();
        seat.area = area;
        seat.rowLabel = rowLabel;
        seat.seatNumber = seatNumber;
        seat.code = area.getCode() + "-" + rowLabel + "-" + seatNumber;
        return seat;
    }

    Long getId() {
        return id;
    }

    Long getAreaId() {
        return area.getId();
    }

    String getRowLabel() {
        return rowLabel;
    }

    int getSeatNumber() {
        return seatNumber;
    }

    String getCode() {
        return code;
    }

    boolean isEnabled() {
        return enabled;
    }
}
