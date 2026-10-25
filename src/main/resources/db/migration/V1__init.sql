-- The license key server's schema. While no release exists the cluster is rebuilt on a schema change, so this
-- file is edited in place (see CLAUDE.md, "Snapshot versions").

CREATE TABLE customer (
    id                 TEXT PRIMARY KEY,
    legal_name         TEXT        NOT NULL,
    stripe_customer_id TEXT UNIQUE,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE customer_contact (
    id          TEXT PRIMARY KEY,
    customer_id TEXT NOT NULL REFERENCES customer (id),
    name        TEXT NOT NULL,
    email       TEXT NOT NULL,
    role        TEXT NOT NULL
);
CREATE INDEX customer_contact_email_idx ON customer_contact (lower(email));
CREATE INDEX customer_contact_customer_idx ON customer_contact (customer_id);

CREATE TABLE agreement_acceptance (
    id                TEXT PRIMARY KEY,
    customer_id       TEXT        NOT NULL REFERENCES customer (id),
    terms_version     TEXT        NOT NULL,
    accepted_by_name  TEXT        NOT NULL,
    accepted_by_email TEXT        NOT NULL,
    accepted_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX agreement_acceptance_customer_idx ON agreement_acceptance (customer_id);

CREATE TABLE license (
    id                     TEXT PRIMARY KEY,
    customer_id            TEXT        NOT NULL REFERENCES customer (id),
    plan                   TEXT        NOT NULL,
    status                 TEXT        NOT NULL,
    features               TEXT[]      NOT NULL,
    stripe_subscription_id TEXT UNIQUE,
    -- the Checkout Session that bought it, which the success page shows the first key for
    checkout_session_id    TEXT UNIQUE,
    current_period_end     TIMESTAMPTZ,
    term_start             TIMESTAMPTZ NOT NULL,
    term_end               TIMESTAMPTZ NOT NULL,
    last_payment_at        TIMESTAMPTZ,
    created_at             TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX license_customer_idx ON license (customer_id);

CREATE TABLE free_license_request (
    id              TEXT PRIMARY KEY,
    customer_id     TEXT        NOT NULL REFERENCES customer (id),
    annual_revenue  NUMERIC(16, 2) NOT NULL,
    capital_raised  NUMERIC(16, 2) NOT NULL,
    intended_use    TEXT        NOT NULL,
    status          TEXT        NOT NULL,
    reviewed_by     TEXT,
    reviewed_at     TIMESTAMPTZ,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX free_license_request_status_idx ON free_license_request (status, created_at);

-- Every key ever issued, appended to and never updated. The trial key belongs to no license.
CREATE TABLE issued_key (
    jti        TEXT PRIMARY KEY,
    license_id TEXT REFERENCES license (id),
    purpose    TEXT        NOT NULL,
    key        TEXT        NOT NULL,
    issued_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at TIMESTAMPTZ
);
CREATE INDEX issued_key_license_idx ON issued_key (license_id, issued_at);

CREATE TABLE stripe_event (
    id           TEXT PRIMARY KEY,
    type         TEXT        NOT NULL,
    processed_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE sign_in_code (
    id         TEXT PRIMARY KEY,
    email      TEXT        NOT NULL,
    code_hash  TEXT        NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at TIMESTAMPTZ NOT NULL,
    used_at    TIMESTAMPTZ
);
CREATE INDEX sign_in_code_email_idx ON sign_in_code (lower(email), created_at);
