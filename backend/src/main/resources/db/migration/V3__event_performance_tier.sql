CREATE TABLE st_event (
    id BIGINT NOT NULL AUTO_INCREMENT,
    organizer_id BIGINT NOT NULL,
    title VARCHAR(120) NOT NULL,
    category VARCHAR(30) NOT NULL,
    description VARCHAR(4000) NOT NULL,
    poster_url VARCHAR(500),
    purchase_notice VARCHAR(2000) NOT NULL,
    status VARCHAR(30) NOT NULL,
    review_note VARCHAR(500),
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_st_event_organizer FOREIGN KEY (organizer_id) REFERENCES st_user (id),
    INDEX idx_st_event_organizer (organizer_id),
    INDEX idx_st_event_status (status)
);

CREATE TABLE st_performance (
    id BIGINT NOT NULL AUTO_INCREMENT,
    event_id BIGINT NOT NULL,
    venue_id BIGINT NOT NULL,
    name VARCHAR(100) NOT NULL,
    starts_at TIMESTAMP(6) NOT NULL,
    sales_start_at TIMESTAMP(6) NOT NULL,
    sales_end_at TIMESTAMP(6) NOT NULL,
    status VARCHAR(20) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_st_performance_slot UNIQUE (event_id, venue_id, starts_at),
    CONSTRAINT fk_st_performance_event FOREIGN KEY (event_id) REFERENCES st_event (id),
    CONSTRAINT fk_st_performance_venue FOREIGN KEY (venue_id) REFERENCES st_venue (id),
    INDEX idx_st_performance_event (event_id),
    INDEX idx_st_performance_start (starts_at)
);

CREATE TABLE st_ticket_tier (
    id BIGINT NOT NULL AUTO_INCREMENT,
    performance_id BIGINT NOT NULL,
    area_id BIGINT NOT NULL,
    name VARCHAR(60) NOT NULL,
    price DECIMAL(10, 2) NOT NULL,
    color VARCHAR(20) NOT NULL,
    purchase_limit INT NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    PRIMARY KEY (id),
    CONSTRAINT uk_st_ticket_tier_area UNIQUE (performance_id, area_id),
    CONSTRAINT fk_st_ticket_tier_performance FOREIGN KEY (performance_id) REFERENCES st_performance (id),
    CONSTRAINT fk_st_ticket_tier_area FOREIGN KEY (area_id) REFERENCES st_venue_area (id),
    INDEX idx_st_ticket_tier_performance (performance_id)
);
