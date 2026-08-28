CREATE TABLE st_user (
    id BIGINT NOT NULL AUTO_INCREMENT,
    username VARCHAR(32) NOT NULL,
    email VARCHAR(254) NOT NULL,
    password_hash VARCHAR(100) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_st_user_username UNIQUE (username),
    CONSTRAINT uk_st_user_email UNIQUE (email)
);

CREATE TABLE st_user_role (
    user_id BIGINT NOT NULL,
    role VARCHAR(20) NOT NULL,
    PRIMARY KEY (user_id, role),
    CONSTRAINT fk_st_user_role_user FOREIGN KEY (user_id) REFERENCES st_user (id)
);
