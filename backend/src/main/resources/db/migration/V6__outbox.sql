CREATE TABLE st_outbox_event (
    id BIGINT NOT NULL AUTO_INCREMENT,
    event_type VARCHAR(60) NOT NULL,
    aggregate_id VARCHAR(80) NOT NULL,
    payload VARCHAR(2000) NOT NULL,
    status VARCHAR(20) NOT NULL,
    retry_count INT NOT NULL DEFAULT 0,
    next_retry_at TIMESTAMP(6) NOT NULL,
    last_error VARCHAR(500),
    created_at TIMESTAMP(6) NOT NULL,
    published_at TIMESTAMP(6),
    PRIMARY KEY (id),
    INDEX idx_st_outbox_publish (status, next_retry_at)
);
