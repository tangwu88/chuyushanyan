ALTER TABLE ledger_entries ADD COLUMN entry_no BIGINT AUTO_INCREMENT UNIQUE;
CREATE INDEX idx_ledger_point_sequence ON ledger_entries(point_id,created_at,entry_no);
CREATE INDEX idx_ledger_date_sequence ON ledger_entries(created_at,entry_no);
