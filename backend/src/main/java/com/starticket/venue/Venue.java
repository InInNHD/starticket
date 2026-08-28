package com.starticket.venue;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "st_venue")
class Venue {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(nullable = false, length = 60)
    private String city;

    @Column(nullable = false, length = 255)
    private String address;

    @Column(nullable = false)
    private boolean enabled = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected Venue() {
    }

    static Venue create(String name, String city, String address) {
        Venue venue = new Venue();
        venue.name = name;
        venue.city = city;
        venue.address = address;
        venue.createdAt = Instant.now();
        return venue;
    }

    Long getId() {
        return id;
    }

    String getName() {
        return name;
    }

    String getCity() {
        return city;
    }

    String getAddress() {
        return address;
    }

    boolean isEnabled() {
        return enabled;
    }
}
