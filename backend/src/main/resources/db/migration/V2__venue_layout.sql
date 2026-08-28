CREATE TABLE st_venue (
    id BIGINT NOT NULL AUTO_INCREMENT,
    name VARCHAR(100) NOT NULL,
    city VARCHAR(60) NOT NULL,
    address VARCHAR(255) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (id),
    INDEX idx_st_venue_city (city)
);

CREATE TABLE st_venue_area (
    id BIGINT NOT NULL AUTO_INCREMENT,
    venue_id BIGINT NOT NULL,
    name VARCHAR(60) NOT NULL,
    code VARCHAR(20) NOT NULL,
    sort_order INT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    CONSTRAINT uk_st_venue_area_code UNIQUE (venue_id, code),
    CONSTRAINT fk_st_venue_area_venue FOREIGN KEY (venue_id) REFERENCES st_venue (id)
);

CREATE TABLE st_seat (
    id BIGINT NOT NULL AUTO_INCREMENT,
    area_id BIGINT NOT NULL,
    row_label VARCHAR(20) NOT NULL,
    seat_number INT NOT NULL,
    code VARCHAR(64) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    PRIMARY KEY (id),
    CONSTRAINT uk_st_seat_position UNIQUE (area_id, row_label, seat_number),
    CONSTRAINT uk_st_seat_code UNIQUE (area_id, code),
    CONSTRAINT fk_st_seat_area FOREIGN KEY (area_id) REFERENCES st_venue_area (id),
    INDEX idx_st_seat_area (area_id)
);
