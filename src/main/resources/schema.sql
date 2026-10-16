CREATE TABLE IF NOT EXISTS requests (
    id VARCHAR(36) PRIMARY KEY,
    file_name VARCHAR(255) NOT NULL UNIQUE
);
CREATE TABLE IF NOT EXISTS equipment (
    id VARCHAR(36) PRIMARY KEY,
    request_id VARCHAR(36) NOT NULL REFERENCES requests(id),
    uid VARCHAR(255) NOT NULL,
    name VARCHAR(1000) NOT NULL
);
CREATE INDEX IF NOT EXISTS equipment_request_idx ON equipment(request_id);
