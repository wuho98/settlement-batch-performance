CREATE TABLE IF NOT EXISTS settlement (
    id BIGINT NOT NULL,
    merchant_id BIGINT NOT NULL,
    status VARCHAR(20) NOT NULL,
    amount DECIMAL(15, 2) NOT NULL,
    settled_at DATETIME(6) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    INDEX idx_settlement_status_settled_at_id (status, settled_at, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
