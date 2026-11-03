CREATE TABLE IF NOT EXISTS accounts (
    id BIGINT PRIMARY KEY,
    balance DECIMAL(19, 2) NOT NULL CHECK (balance >= 0)
);

CREATE TABLE IF NOT EXISTS withdrawal_outbox (
    event_id VARCHAR(36) PRIMARY KEY,
    payload CLOB NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    attempt_count INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    next_attempt_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    locked_until TIMESTAMP,
    published_at TIMESTAMP,
    last_error VARCHAR(1000)
);
