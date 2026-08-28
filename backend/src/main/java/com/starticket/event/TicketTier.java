package com.starticket.event;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.math.BigDecimal;

@Entity
@Table(name = "st_ticket_tier")
class TicketTier {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "performance_id", nullable = false)
    private Performance performance;

    @Column(name = "area_id", nullable = false)
    private Long areaId;

    @Column(nullable = false, length = 60)
    private String name;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal price;

    @Column(nullable = false, length = 20)
    private String color;

    @Column(name = "purchase_limit", nullable = false)
    private int purchaseLimit;

    @Column(nullable = false)
    private boolean enabled = true;

    protected TicketTier() {
    }

    static TicketTier create(
            Performance performance, Long areaId, String name, BigDecimal price, String color, int purchaseLimit) {
        TicketTier tier = new TicketTier();
        tier.performance = performance;
        tier.areaId = areaId;
        tier.name = name;
        tier.price = price;
        tier.color = color;
        tier.purchaseLimit = purchaseLimit;
        return tier;
    }

    Long getId() { return id; }
    Long getPerformanceId() { return performance.getId(); }
    Long getAreaId() { return areaId; }
    String getName() { return name; }
    BigDecimal getPrice() { return price; }
    String getColor() { return color; }
    int getPurchaseLimit() { return purchaseLimit; }
    boolean isEnabled() { return enabled; }
}
