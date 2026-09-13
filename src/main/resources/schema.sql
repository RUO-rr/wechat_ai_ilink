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

CREATE TABLE IF NOT EXISTS agent_memory (
    id                BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id           VARCHAR(128) NOT NULL,
    memory_type       VARCHAR(16)  NOT NULL,   -- fact / preference / summary / note
    dimension         VARCHAR(64)  NOT NULL,   -- answer_style / timezone / company_focus / history / user_note ...
    content           TEXT         NOT NULL,
    source_message_id BIGINT       NULL,       -- 溯源：chat_message.id
    status            VARCHAR(16)  NOT NULL DEFAULT 'active',  -- active / superseded / deleted
    supersedes_id     BIGINT       NULL,       -- 取代了哪条旧记忆（冲突解决链）
    created_at        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at        DATETIME     NULL,
    KEY idx_memory_user (user_id, dimension, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ========== RAG 知识库（v2.6） ==========
-- 文档：一行一份被索引的文档；content_hash + embedding_model 用于幂等去重与模型版本隔离
CREATE TABLE IF NOT EXISTS knowledge_document (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    source_type     VARCHAR(32)  NOT NULL,              -- resource（内置资产）/ user_upload（用户上传）
    source_path     VARCHAR(512) NOT NULL,              -- 类路径或磁盘绝对路径
    title           VARCHAR(255) NOT NULL,
    content_hash    VARCHAR(64)  NOT NULL,              -- SHA-256，内容未变则跳过重建
    chunk_count     INT          NOT NULL DEFAULT 0,
    embedding_model VARCHAR(64)  NULL,                  -- 建索引时使用的向量模型标识
    dimension       INT          NULL,
    status          VARCHAR(16)  NOT NULL DEFAULT 'indexed',  -- indexed / failed
    created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME     NULL,
    UNIQUE KEY uk_knowledge_doc_path (source_path),
    KEY idx_knowledge_doc_type (source_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 片段：检索的最小单位。embedding 以 JSON 文本存储（可读、可审计、便于迁移换库）
CREATE TABLE IF NOT EXISTS knowledge_chunk (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    document_id     BIGINT       NOT NULL,
    chunk_index     INT          NOT NULL,
    heading         VARCHAR(255) NULL,                  -- Markdown 标题路径，用于引用定位
    content         TEXT         NOT NULL,
    embedding       MEDIUMTEXT   NULL,                  -- JSON float 数组；NULL 表示仅可关键词召回
    dimension       INT          NULL,
    embedding_model VARCHAR(64)  NULL,
    created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_knowledge_chunk (document_id, chunk_index),
    KEY idx_knowledge_chunk_doc (document_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;