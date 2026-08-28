CREATE INDEX idx_st_event_status_updated ON st_event (status, updated_at);
CREATE INDEX idx_st_event_organizer_updated ON st_event (organizer_id, updated_at);
CREATE INDEX idx_st_order_performance_status ON st_order (performance_id, status, created_at);
CREATE INDEX idx_st_order_status_created ON st_order (status, created_at);
