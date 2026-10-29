CREATE INDEX idx_orders_created ON shop_orders(created_at,id);
CREATE INDEX idx_ledger_created ON ledger_entries(created_at,id);
CREATE INDEX idx_awards_created ON reward_awards(created_at,id);
