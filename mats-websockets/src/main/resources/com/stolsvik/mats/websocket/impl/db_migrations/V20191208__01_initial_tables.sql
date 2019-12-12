CREATE TABLE mats_socket_session (
    mats_session_id VARCHAR(255) NOT NULL,
    nodename VARCHAR(255),  -- NULL if no node has this session anymore. Row is deleted if terminated.
    liveliness_timestamp BIGINT NOT NULL,  -- millis since epoch.

    CONSTRAINT PK_mats_socket_session PRIMARY KEY (mats_session_id)
);

CREATE TABLE mats_socket_message (
    mats_session_id VARCHAR(255) NOT NULL,
    message_id BIGINT NOT NULL,
    trace_id ${texttype} NOT NULL,
    stored_timestamp BIGINT NOT NULL,  -- millis since epoch.
    delivery_count INT NOT NULL, -- Starts at zero.
    type VARCHAR(255) NOT NULL,
    message_text ${texttype},
    message_binary ${binarytype},

    CONSTRAINT PK_mats_socket_message PRIMARY KEY (mats_session_id, message_id)
);