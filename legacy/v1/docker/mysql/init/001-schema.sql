CREATE TABLE IF NOT EXISTS user_profile_source (
    id BIGINT PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    user_name VARCHAR(64) NOT NULL,
    email VARCHAR(128),
    status VARCHAR(20) NOT NULL,
    balance DECIMAL(12, 2) NOT NULL DEFAULT 0,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS user_profile_target (
    id BIGINT PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    user_name VARCHAR(64) NOT NULL,
    email VARCHAR(128),
    status VARCHAR(20) NOT NULL,
    balance DECIMAL(12, 2) NOT NULL DEFAULT 0,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS order_snapshot (
    order_id BIGINT PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    amount DECIMAL(12, 2) NOT NULL,
    order_status VARCHAR(20) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT INTO user_profile_source (id, tenant_id, user_name, email, status, balance) VALUES
    (1, 1001, 'alice', 'alice@example.test', 'ACTIVE', 120.50),
    (2, 1001, 'bob', 'bob@example.test', 'ACTIVE', 88.00),
    (3, 1002, 'carol', 'carol@example.test', 'LOCKED', 0.00)
ON DUPLICATE KEY UPDATE
    tenant_id = VALUES(tenant_id),
    user_name = VALUES(user_name),
    email = VALUES(email),
    status = VALUES(status),
    balance = VALUES(balance);

INSERT INTO user_profile_target (id, tenant_id, user_name, email, status, balance) VALUES
    (1, 1001, 'alice-old', 'alice-old@example.test', 'ACTIVE', 10.00)
ON DUPLICATE KEY UPDATE
    tenant_id = VALUES(tenant_id),
    user_name = VALUES(user_name),
    email = VALUES(email),
    status = VALUES(status),
    balance = VALUES(balance);

INSERT INTO order_snapshot (order_id, tenant_id, user_id, amount, order_status) VALUES
    (9001, 1001, 1, 42.30, 'PAID'),
    (9002, 1001, 2, 19.90, 'CREATED'),
    (9003, 1002, 3, 108.66, 'PAID')
ON DUPLICATE KEY UPDATE
    tenant_id = VALUES(tenant_id),
    user_id = VALUES(user_id),
    amount = VALUES(amount),
    order_status = VALUES(order_status);
