CREATE TABLE IF NOT EXISTS chat_message (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    bot_id      TEXT    NOT NULL DEFAULT 'legacy',
    user_id     TEXT    NOT NULL,
    role        TEXT    NOT NULL,
    content     TEXT,
    rich_content TEXT,
    metadata    TEXT,
    created_at  TEXT    NOT NULL DEFAULT (datetime('now', 'localtime'))
);

CREATE INDEX IF NOT EXISTS idx_chat_message_bot_user ON chat_message(bot_id, user_id);

CREATE TABLE IF NOT EXISTS bot_registry (
    bot_id          TEXT PRIMARY KEY,
    label           TEXT,
    wechat_user_id  TEXT,
    wechat_bot_id   TEXT,
    bot_token       TEXT,
    base_url        TEXT,
    created_at      TEXT NOT NULL DEFAULT (datetime('now', 'localtime')),
    last_active_at  TEXT,
    last_login_at   TEXT
);

CREATE INDEX IF NOT EXISTS idx_bot_registry_wechat_user ON bot_registry(wechat_user_id);
