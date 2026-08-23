CREATE TABLE app_metadata
(
    id         BIGSERIAL PRIMARY KEY,
    name       VARCHAR(100)             NOT NULL UNIQUE,
    value      VARCHAR(500)             NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

INSERT INTO app_metadata (name, value)
VALUES ('schema_version', '1');
