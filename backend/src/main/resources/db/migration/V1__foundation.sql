CREATE TABLE app_users (
 id VARCHAR(64) PRIMARY KEY, username VARCHAR(64) NOT NULL UNIQUE,
 display_name VARCHAR(100) NOT NULL, phone VARCHAR(32) NOT NULL,
 password_hash VARCHAR(100) NOT NULL, payment_hash VARCHAR(100) NOT NULL,
 role VARCHAR(32) NOT NULL, owner_id VARCHAR(64), department VARCHAR(64) NOT NULL DEFAULT '初语山言',
 active BOOLEAN NOT NULL DEFAULT TRUE, must_change_password BOOLEAN NOT NULL DEFAULT FALSE,
 created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE auth_sessions (
 token_hash VARCHAR(64) PRIMARY KEY, user_id VARCHAR(64) NOT NULL,
 expires_at TIMESTAMP NOT NULL, FOREIGN KEY(user_id) REFERENCES app_users(id)
);
CREATE TABLE rule_versions (
 id VARCHAR(64) PRIMARY KEY, name VARCHAR(128) NOT NULL, status VARCHAR(16) NOT NULL,
 source VARCHAR(128) NOT NULL, config_json LONGTEXT NOT NULL, created_by VARCHAR(64) NOT NULL,
 tested_hash VARCHAR(64), effective_at TIMESTAMP, created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE app_settings (
 id INT PRIMARY KEY, transfer_enabled BOOLEAN NOT NULL DEFAULT TRUE,
 current_rule VARCHAR(64) NOT NULL, revision BIGINT NOT NULL DEFAULT 0,
 FOREIGN KEY(current_rule) REFERENCES rule_versions(id)
);
CREATE TABLE member_points (
 id VARCHAR(64) PRIMARY KEY, owner_id VARCHAR(64) NOT NULL, phone VARCHAR(32) NOT NULL,
 nickname VARCHAR(100) NOT NULL, parent_id VARCHAR(64), is_main BOOLEAN NOT NULL,
 sequence_no INT NOT NULL, grade INT NOT NULL DEFAULT 0, director_grade INT NOT NULL DEFAULT 0,
 grade_version VARCHAR(8) NOT NULL DEFAULT 'v2', shop_type VARCHAR(16) NOT NULL DEFAULT 'NONE',
 identities VARCHAR(512) NOT NULL DEFAULT '', active BOOLEAN NOT NULL DEFAULT TRUE,
 activated BOOLEAN NOT NULL DEFAULT FALSE, empty_point BOOLEAN NOT NULL DEFAULT FALSE,
 manual_own DECIMAL(20,2) NOT NULL DEFAULT 0, manual_team DECIMAL(20,2) NOT NULL DEFAULT 0,
 manual_small DECIMAL(20,2) NOT NULL DEFAULT 0, plan_override VARCHAR(8) NOT NULL DEFAULT 'AUTO',
 fixed_plan1 DECIMAL(20,2), fixed_plan2 DECIMAL(20,2),
 created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
 UNIQUE(owner_id, sequence_no), FOREIGN KEY(parent_id) REFERENCES member_points(id)
);
CREATE INDEX idx_members_parent ON member_points(parent_id, active);
CREATE INDEX idx_members_phone ON member_points(phone, sequence_no);
CREATE TABLE wallets (
 point_id VARCHAR(64) NOT NULL, kind VARCHAR(16) NOT NULL,
 balance DECIMAL(20,2) NOT NULL DEFAULT 0, frozen DECIMAL(20,2) NOT NULL DEFAULT 0,
 restricted DECIMAL(20,2) NOT NULL DEFAULT 0, revision BIGINT NOT NULL DEFAULT 0,
 PRIMARY KEY(point_id,kind), FOREIGN KEY(point_id) REFERENCES member_points(id)
);
CREATE TABLE ledger_transactions (
 id VARCHAR(64) PRIMARY KEY, business_key VARCHAR(180) NOT NULL UNIQUE,
 kind VARCHAR(32) NOT NULL, actor_id VARCHAR(64) NOT NULL, origin_id VARCHAR(64),
 description VARCHAR(512) NOT NULL, created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE ledger_entries (
 id VARCHAR(64) PRIMARY KEY, transaction_id VARCHAR(64) NOT NULL, point_id VARCHAR(64) NOT NULL,
 wallet_kind VARCHAR(16) NOT NULL, delta DECIMAL(20,2) NOT NULL,
 balance_after DECIMAL(20,2) NOT NULL, frozen_after DECIMAL(20,2) NOT NULL,
 description VARCHAR(512) NOT NULL, created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
 FOREIGN KEY(transaction_id) REFERENCES ledger_transactions(id),
 FOREIGN KEY(point_id,wallet_kind) REFERENCES wallets(point_id,kind)
);
CREATE INDEX idx_ledger_point_time ON ledger_entries(point_id,created_at,id);
CREATE TABLE products (
 id VARCHAR(64) PRIMARY KEY, name VARCHAR(128) NOT NULL,
 first_price DECIMAL(20,2) NOT NULL, repeat_one DECIMAL(20,2) NOT NULL,
 repeat_other DECIMAL(20,2) NOT NULL, limit_one INT NOT NULL, limit_other INT NOT NULL,
 subsidy_one DECIMAL(20,2) NOT NULL, subsidy_rate DECIMAL(12,8) NOT NULL,
 differential_json VARCHAR(256) NOT NULL, weight_kg DECIMAL(10,3) NOT NULL,
 active BOOLEAN NOT NULL DEFAULT TRUE
);
CREATE TABLE shop_orders (
 id VARCHAR(64) PRIMARY KEY, point_id VARCHAR(64) NOT NULL, shop_id VARCHAR(64) NOT NULL,
 payer_id VARCHAR(64) NOT NULL, product_id VARCHAR(64) NOT NULL, order_type VARCHAR(16) NOT NULL,
 quantity INT NOT NULL, unit_price DECIMAL(20,2) NOT NULL,
 total DECIMAL(20,2) NOT NULL, reward_paid DECIMAL(20,2) NOT NULL, cash_paid DECIMAL(20,2) NOT NULL,
 payment_status VARCHAR(16) NOT NULL, activation_status VARCHAR(16) NOT NULL,
 fulfilment_status VARCHAR(16) NOT NULL, cancelled BOOLEAN NOT NULL DEFAULT FALSE,
 pickup BOOLEAN NOT NULL DEFAULT FALSE, rule_id VARCHAR(64) NOT NULL,
 rule_snapshot LONGTEXT NOT NULL, member_snapshot LONGTEXT,
 created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, paid_at TIMESTAMP,
 activated_at TIMESTAMP, cancelled_at TIMESTAMP,
 FOREIGN KEY(point_id) REFERENCES member_points(id), FOREIGN KEY(shop_id) REFERENCES member_points(id),
 FOREIGN KEY(product_id) REFERENCES products(id), FOREIGN KEY(rule_id) REFERENCES rule_versions(id)
);
CREATE INDEX idx_orders_point_date ON shop_orders(point_id,order_type,created_at);
CREATE INDEX idx_orders_shop_state ON shop_orders(shop_id,activation_status,cancelled,created_at);
CREATE INDEX idx_orders_period ON shop_orders(activated_at,order_type,cancelled);
CREATE TABLE settlement_batches (
 id VARCHAR(64) PRIMARY KEY, business_key VARCHAR(180) NOT NULL UNIQUE,
 period_type VARCHAR(16) NOT NULL, period VARCHAR(16) NOT NULL, rule_id VARCHAR(64) NOT NULL,
 status VARCHAR(16) NOT NULL, snapshot_json LONGTEXT NOT NULL,
 awards_json LONGTEXT NOT NULL, context_hash VARCHAR(64) NOT NULL,
 gross DECIMAL(20,2) NOT NULL, fee DECIMAL(20,2) NOT NULL, net DECIMAL(20,2) NOT NULL,
 created_by VARCHAR(64) NOT NULL, posted_at TIMESTAMP, created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
 FOREIGN KEY(rule_id) REFERENCES rule_versions(id)
);
CREATE TABLE reward_awards (
 id VARCHAR(64) PRIMARY KEY, batch_id VARCHAR(64), order_id VARCHAR(64), recipient_id VARCHAR(64) NOT NULL,
 award_type VARCHAR(40) NOT NULL, base_amount DECIMAL(20,2) NOT NULL, rate DECIMAL(12,8) NOT NULL,
 gross DECIMAL(20,2) NOT NULL, fee DECIMAL(20,2) NOT NULL, net DECIMAL(20,2) NOT NULL,
 explanation VARCHAR(1000) NOT NULL, rule_id VARCHAR(64) NOT NULL,
 reversed BOOLEAN NOT NULL DEFAULT FALSE, created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
 FOREIGN KEY(recipient_id) REFERENCES member_points(id)
);
CREATE INDEX idx_awards_order ON reward_awards(order_id,reversed);
CREATE INDEX idx_awards_point_time ON reward_awards(recipient_id,created_at);
CREATE TABLE performance_snapshots (
 point_id VARCHAR(64) NOT NULL, snapshot_date DATE NOT NULL, team DECIMAL(20,2) NOT NULL,
 small DECIMAL(20,2) NOT NULL, own DECIMAL(20,2) NOT NULL,
 PRIMARY KEY(point_id,snapshot_date)
);
CREATE TABLE performance_offsets (
 id VARCHAR(64) PRIMARY KEY, point_id VARCHAR(64) NOT NULL, order_id VARCHAR(64) NOT NULL,
 remaining DECIMAL(20,2) NOT NULL, created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
 UNIQUE(point_id,order_id)
);
CREATE TABLE withdrawals (
 id VARCHAR(64) PRIMARY KEY, point_id VARCHAR(64) NOT NULL, gross DECIMAL(20,2) NOT NULL,
 fee DECIMAL(20,2) NOT NULL, tax DECIMAL(20,2) NOT NULL, net DECIMAL(20,2) NOT NULL,
 bank_mask VARCHAR(64) NOT NULL, status VARCHAR(16) NOT NULL, reason VARCHAR(512),
 request_by VARCHAR(64) NOT NULL, review_by VARCHAR(64), rule_id VARCHAR(64) NOT NULL,
 created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, reviewed_at TIMESTAMP
);
CREATE INDEX idx_withdrawals_status ON withdrawals(status,created_at);
CREATE TABLE topups (
 id VARCHAR(64) PRIMARY KEY, shop_id VARCHAR(64) NOT NULL, amount DECIMAL(20,2) NOT NULL,
 status VARCHAR(16) NOT NULL, request_by VARCHAR(64) NOT NULL, review_by VARCHAR(64),
 created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE fulfilment_applications (
 id VARCHAR(64) PRIMARY KEY, shop_id VARCHAR(64) NOT NULL, status VARCHAR(16) NOT NULL,
 pickup BOOLEAN NOT NULL, recipient VARCHAR(64) NOT NULL, phone VARCHAR(32) NOT NULL,
 address VARCHAR(512) NOT NULL, carrier VARCHAR(64), tracking VARCHAR(128), reason VARCHAR(512),
 request_by VARCHAR(64) NOT NULL, reviewed_by VARCHAR(64), shipped_by VARCHAR(64),
 created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, shipped_at TIMESTAMP
);
CREATE TABLE fulfilment_lines (
 id VARCHAR(64) PRIMARY KEY, application_id VARCHAR(64) NOT NULL, order_id VARCHAR(64) NOT NULL,
 quantity INT NOT NULL, FOREIGN KEY(application_id) REFERENCES fulfilment_applications(id),
 FOREIGN KEY(order_id) REFERENCES shop_orders(id), UNIQUE(application_id,order_id)
);
CREATE INDEX idx_fulfilment_order ON fulfilment_lines(order_id);
CREATE TABLE audit_logs (
 id VARCHAR(64) PRIMARY KEY, actor_id VARCHAR(64) NOT NULL, action VARCHAR(80) NOT NULL,
 target_id VARCHAR(64), before_json LONGTEXT, after_json LONGTEXT, reason VARCHAR(512),
 created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_audit_created ON audit_logs(created_at,id);
CREATE TABLE job_runs (
 id VARCHAR(64) PRIMARY KEY, job_name VARCHAR(64) NOT NULL, period VARCHAR(16) NOT NULL,
 status VARCHAR(16) NOT NULL, message VARCHAR(1000), started_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
 finished_at TIMESTAMP
);
