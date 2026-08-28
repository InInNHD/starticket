ALTER TABLE st_outbox_event ADD COLUMN locked_by VARCHAR(80);
ALTER TABLE st_outbox_event ADD COLUMN locked_at TIMESTAMP(6);
CREATE INDEX idx_st_outbox_processing ON st_outbox_event (status, locked_at);

CREATE TABLE st_failed_message (
    id BIGINT NOT NULL AUTO_INCREMENT,
    message_id VARCHAR(100) NOT NULL,
    event_type VARCHAR(60) NOT NULL,
    aggregate_id VARCHAR(80) NOT NULL,
    payload VARCHAR(2000) NOT NULL,
    failure_reason VARCHAR(500) NOT NULL,
    status VARCHAR(20) NOT NULL,
    failed_at TIMESTAMP(6) NOT NULL,
    replayed_at TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT uk_st_failed_message UNIQUE (message_id),
    INDEX idx_st_failed_message_status (status, failed_at)
);

CREATE TABLE st_audit_log (
    id BIGINT NOT NULL AUTO_INCREMENT,
    actor VARCHAR(64) NOT NULL,
    action VARCHAR(60) NOT NULL,
    target_type VARCHAR(40) NOT NULL,
    target_id VARCHAR(100) NOT NULL,
    detail VARCHAR(500),
    created_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (id),
    INDEX idx_st_audit_created (created_at),
    INDEX idx_st_audit_target (target_type, target_id)
);
