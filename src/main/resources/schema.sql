-- ============================================================
-- Notification Service Schema (PostgreSQL)
-- ============================================================
-- spring.jpa.hibernate.ddl-auto=validate 환경에서 사용합니다.
-- Spring Modulith의 event_publication 테이블은 자동 생성되므로 여기에 포함하지 않습니다.

-- ── notification_jobs ────────────────────────────────────────

CREATE TABLE IF NOT EXISTS notification_jobs (
    id                BIGINT       PRIMARY KEY,
    status            VARCHAR(30)  NOT NULL,
    title_template    VARCHAR(500) NOT NULL,
    content_template  TEXT         NOT NULL,
    channel           VARCHAR(20)  NOT NULL,
    notification_type VARCHAR(50),
    metadata          JSONB        NOT NULL DEFAULT '{}',
    idempotency_key   VARCHAR(255) NOT NULL UNIQUE,
    created_at        TIMESTAMPTZ  NOT NULL,
    updated_at        TIMESTAMPTZ  NOT NULL,
    deleted           BOOLEAN      NOT NULL DEFAULT FALSE
);

CREATE INDEX IF NOT EXISTS idx_notification_jobs_status ON notification_jobs (status);
CREATE INDEX IF NOT EXISTS idx_notification_jobs_idempotency_key ON notification_jobs (idempotency_key);

-- ── notifications ────────────────────────────────────────────

CREATE TABLE IF NOT EXISTS notifications (
    id                           BIGINT       PRIMARY KEY,
    job_id                       BIGINT       NOT NULL,
    recipient_id                 BIGINT       NOT NULL,
    recipient_contact            VARCHAR(255) NOT NULL,
    variables                    JSONB        NOT NULL DEFAULT '{}',
    notification_type            VARCHAR(50),
    metadata                     JSONB        NOT NULL DEFAULT '{}',
    status                       VARCHAR(30)  NOT NULL,
    send_try_count               INT          NOT NULL DEFAULT 0,
    last_failure_classification  VARCHAR(20),
    last_failure_reason          TEXT,
    first_read_at                TIMESTAMPTZ,
    created_at                   TIMESTAMPTZ  NOT NULL,
    updated_at                   TIMESTAMPTZ  NOT NULL,
    deleted                      BOOLEAN      NOT NULL DEFAULT FALSE
);

CREATE INDEX IF NOT EXISTS idx_notifications_job_id ON notifications (job_id);
CREATE INDEX IF NOT EXISTS idx_notifications_recipient_id ON notifications (recipient_id);
CREATE INDEX IF NOT EXISTS idx_notifications_status ON notifications (status);
CREATE INDEX IF NOT EXISTS idx_notifications_job_id_status ON notifications (job_id, status);

-- ── notification_contents ────────────────────────────────────

CREATE TABLE IF NOT EXISTS notification_contents (
    id               BIGINT       PRIMARY KEY,
    notification_id  BIGINT       NOT NULL UNIQUE,
    rendered_title   VARCHAR(500),
    rendered_body    TEXT         NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_notification_contents_notification_id ON notification_contents (notification_id);

-- ── scheduled_notification_jobs ──────────────────────────────

CREATE TABLE IF NOT EXISTS scheduled_notification_jobs (
    id            BIGINT       PRIMARY KEY,
    job_id        BIGINT       NOT NULL,
    schedule_type VARCHAR(20)  NOT NULL,
    scheduled_at  TIMESTAMPTZ  NOT NULL,
    executed      BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at    TIMESTAMPTZ  NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_scheduled_jobs_schedule_type_executed_scheduled
    ON scheduled_notification_jobs (schedule_type, executed, scheduled_at);
CREATE INDEX IF NOT EXISTS idx_scheduled_jobs_job_id ON scheduled_notification_jobs (job_id);

-- ── notification_send_histories ───────────────────────────────

CREATE TABLE IF NOT EXISTS notification_send_histories (
    id                   BIGINT       PRIMARY KEY,
    notification_id      BIGINT       NOT NULL,
    status               VARCHAR(30)  NOT NULL,
    send_failure_reason  TEXT,
    sent_at              TIMESTAMPTZ  NOT NULL,
    attempt_no           INT,
    from_status          VARCHAR(30),
    to_status            VARCHAR(30),
    failure_code         VARCHAR(50)
);

CREATE INDEX IF NOT EXISTS idx_notification_send_histories_notification_id ON notification_send_histories (notification_id);

-- ── notification_read_events ──────────────────────────────────

CREATE TABLE IF NOT EXISTS notification_read_events (
    id               BIGINT       PRIMARY KEY,
    notification_id  BIGINT       NOT NULL,
    user_id          BIGINT       NOT NULL,
    device_id        VARCHAR(255),
    device_type      VARCHAR(50),
    read_at          TIMESTAMPTZ  NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_notification_read_events_notification_id ON notification_read_events (notification_id);
CREATE INDEX IF NOT EXISTS idx_notification_read_events_user_id ON notification_read_events (user_id);

-- ── notification_job_status_change_histories ─────────────────

CREATE TABLE IF NOT EXISTS notification_job_status_change_histories (
    id                    BIGINT       PRIMARY KEY,
    job_id                BIGINT       NOT NULL,
    pre_status            VARCHAR(30)  NOT NULL,
    status                VARCHAR(30)  NOT NULL,
    status_change_reason  TEXT,
    triggered_by          VARCHAR(255),
    created_at            TIMESTAMPTZ  NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_job_status_histories_job_id ON notification_job_status_change_histories (job_id);

-- ── notification_templates ───────────────────────────────────

CREATE TABLE IF NOT EXISTS notification_templates (
    id              BIGINT       PRIMARY KEY,
    code            VARCHAR(100) NOT NULL,
    channel         VARCHAR(20)  NOT NULL,
    locale          VARCHAR(10)  NOT NULL,
    version         INT          NOT NULL,
    title_template  VARCHAR(500) NOT NULL,
    body_template   TEXT         NOT NULL,
    description     VARCHAR(500),
    variables       TEXT         NOT NULL DEFAULT '[]',
    deleted         BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at      TIMESTAMPTZ  NOT NULL,
    updated_at      TIMESTAMPTZ  NOT NULL,

    CONSTRAINT uk_template_code_channel_locale_version
        UNIQUE (code, channel, locale, version)
);

CREATE INDEX IF NOT EXISTS idx_notification_templates_code ON notification_templates (code);
