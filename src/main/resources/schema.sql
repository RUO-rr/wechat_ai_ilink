CREATE TABLE IF NOT EXISTS chat_message (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    bot_id       VARCHAR(64)  NOT NULL DEFAULT 'legacy',
    user_id      VARCHAR(128) NOT NULL,
    role         VARCHAR(32)  NOT NULL,
    content     TEXT,
    rich_content TEXT,
    metadata    TEXT,
    created_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_chat_message_bot_user (bot_id, user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS bot_registry (
    bot_id          VARCHAR(64)  PRIMARY KEY,
    label           VARCHAR(128),
    wechat_user_id  VARCHAR(128),
    wechat_bot_id   VARCHAR(128),
    bot_token       VARCHAR(512),
    base_url        VARCHAR(255),
    created_at      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_active_at  DATETIME NULL DEFAULT NULL,
    last_login_at   DATETIME NULL DEFAULT NULL,
    KEY idx_bot_registry_wechat_user (wechat_user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
