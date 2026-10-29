CREATE TABLE member_history (
 sequence_id BIGINT AUTO_INCREMENT PRIMARY KEY, point_id VARCHAR(64) NOT NULL,
 valid_at TIMESTAMP(6) NOT NULL, snapshot_json LONGTEXT NOT NULL
);
CREATE INDEX idx_member_history_time ON member_history(point_id,valid_at,sequence_id);
