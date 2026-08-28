CREATE TABLE st_payment (
    id BIGINT NOT NULL AUTO_INCREMENT,
    payment_no VARCHAR(40) NOT NULL,
    order_id BIGINT NOT NULL,
    amount DECIMAL(10, 2) NOT NULL,
    status VARCHAR(20) NOT NULL,
    channel_txn_no VARCHAR(80),
    created_at TIMESTAMP(6) NOT NULL,
    paid_at TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT uk_st_payment_no UNIQUE (payment_no),
    CONSTRAINT uk_st_payment_order UNIQUE (order_id),
    CONSTRAINT uk_st_payment_channel UNIQUE (channel_txn_no),
    CONSTRAINT fk_st_payment_order FOREIGN KEY (order_id) REFERENCES st_order (id)
);

CREATE TABLE st_ticket (
    id BIGINT NOT NULL AUTO_INCREMENT,
    ticket_no VARCHAR(40) NOT NULL,
    order_item_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    performance_id BIGINT NOT NULL,
    code_hash VARCHAR(64) NOT NULL,
    status VARCHAR(20) NOT NULL,
    used_at TIMESTAMP(6),
    created_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_st_ticket_no UNIQUE (ticket_no),
    CONSTRAINT uk_st_ticket_item UNIQUE (order_item_id),
    CONSTRAINT uk_st_ticket_code UNIQUE (code_hash),
    CONSTRAINT fk_st_ticket_item FOREIGN KEY (order_item_id) REFERENCES st_order_item (id),
    CONSTRAINT fk_st_ticket_user FOREIGN KEY (user_id) REFERENCES st_user (id),
    CONSTRAINT fk_st_ticket_performance FOREIGN KEY (performance_id) REFERENCES st_performance (id),
    INDEX idx_st_ticket_user (user_id, created_at)
);

CREATE TABLE st_check_in_record (
    id BIGINT NOT NULL AUTO_INCREMENT,
    ticket_id BIGINT NOT NULL,
    operator_name VARCHAR(100) NOT NULL,
    result VARCHAR(30) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_st_check_in_ticket FOREIGN KEY (ticket_id) REFERENCES st_ticket (id),
    INDEX idx_st_check_in_ticket (ticket_id, created_at)
);

CREATE TABLE st_refund (
    id BIGINT NOT NULL AUTO_INCREMENT,
    refund_no VARCHAR(40) NOT NULL,
    order_id BIGINT NOT NULL,
    amount DECIMAL(10, 2) NOT NULL,
    status VARCHAR(20) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    completed_at TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT uk_st_refund_no UNIQUE (refund_no),
    CONSTRAINT uk_st_refund_order UNIQUE (order_id),
    CONSTRAINT fk_st_refund_order FOREIGN KEY (order_id) REFERENCES st_order (id)
);
