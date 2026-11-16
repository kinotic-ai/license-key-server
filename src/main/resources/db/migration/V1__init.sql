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
    email         TEXT NOT NULL,
    role          TEXT NOT NULL,
    -- a bcrypt hash; NULL until the contact sets a password through a password reset link
    password_hash TEXT
);
-- one address belongs to one customer, so an email and password always name one account
CREATE UNIQUE INDEX customer_contact_email_idx ON customer_contact (lower(email));
CREATE INDEX customer_contact_customer_idx ON customer_contact (customer_id);

CREATE TABLE agreement_acceptance (
    id                TEXT PRIMARY KEY,
    customer_id       TEXT        NOT NULL REFERENCES customer (id),
    terms_version     TEXT        NOT NULL,
    accepted_by_name  TEXT        NOT NULL,
    accepted_by_email TEXT        NOT NULL,
    -- what the customer let Minds Ignited publish about it, as a PublicityConsent name; NULL for none
    publicity_consent TEXT,
    accepted_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX agreement_acceptance_customer_idx ON agreement_acceptance (customer_id);

CREATE TABLE license (
    id                     TEXT PRIMARY KEY,
    customer_id            TEXT        NOT NULL REFERENCES customer (id),
    plan                   TEXT        NOT NULL,
    status                 TEXT        NOT NULL,
    -- MONTHLY or ANNUAL for a paid plan, NULL for a free license or a trial
    billing_period         TEXT,
    -- the price list and terms in effect when the license started, which it keeps while it stays active
    price_list             INTEGER     NOT NULL,
    terms_version          TEXT        NOT NULL,
    stripe_subscription_id TEXT UNIQUE,
    -- the Checkout Session that bought it, which the success page shows the first key for
    checkout_session_id    TEXT UNIQUE,
    current_period_end     TIMESTAMPTZ,
    -- how many clusters may hold the license's keys at once
    seats                  INTEGER     NOT NULL DEFAULT 1,
    -- when the subscription is set to end, as the customer scheduled it in the Customer Portal
    cancels_at             TIMESTAMPTZ,
    -- when the payment that left the license PAST_DUE failed; 30 days on, the license lapses
    past_due_since         TIMESTAMPTZ,
    -- when the contacts were warned the license lapses; once per time it falls past due
    lapse_warned_at        TIMESTAMPTZ,
    -- what staff should look at, as LicenseFlag names
    flags                  TEXT[]      NOT NULL DEFAULT '{}',
    -- when the contacts were last asked to confirm the figures; once per license year
    confirmation_requested_at TIMESTAMPTZ,
    term_start             TIMESTAMPTZ NOT NULL,
    term_end               TIMESTAMPTZ NOT NULL,
    last_payment_at        TIMESTAMPTZ,
    created_at             TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX license_customer_idx ON license (customer_id);
-- one trial at a time per customer, so two trials started together leave one
CREATE UNIQUE INDEX license_one_trial_idx ON license (customer_id) WHERE status = 'TRIAL';

-- The figures a customer confirms once a year for its license's anniversary; capital_raised only where it decides
-- between Free and Small. plan is the plan the figures set, license_plan the plan the license was on, and applies_at
-- the anniversary the plan takes effect at: the one confirmed, or the next for a confirmation made late.
CREATE TABLE revenue_confirmation (
    id                 TEXT PRIMARY KEY,
    license_id         TEXT           NOT NULL REFERENCES license (id),
    anniversary        TIMESTAMPTZ    NOT NULL,
    annual_revenue     NUMERIC(16, 2) NOT NULL,
    capital_raised     NUMERIC(16, 2),
    plan               TEXT           NOT NULL,
    license_plan       TEXT           NOT NULL,
    applies_at         TIMESTAMPTZ    NOT NULL,
    confirmed_by_name  TEXT           NOT NULL,
    confirmed_by_email TEXT           NOT NULL,
    confirmed_at       TIMESTAMPTZ    NOT NULL DEFAULT now(),
    UNIQUE (license_id, anniversary)
);

CREATE TABLE free_license_request (
    id              TEXT PRIMARY KEY,
    customer_id     TEXT        NOT NULL REFERENCES customer (id),
    annual_revenue  NUMERIC(16, 2) NOT NULL,
    capital_raised  NUMERIC(16, 2) NOT NULL,
    intended_use    TEXT        NOT NULL,
    status          TEXT        NOT NULL,
    reviewed_by     TEXT,
    reviewed_at     TIMESTAMPTZ,
    -- when an approval last started issuing; an approval that stopped before recording its key is resumed after a while
    claimed_at      TIMESTAMPTZ,
    -- the license the approval created or started the next year of, which completes it
    license_id      TEXT REFERENCES license (id),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX free_license_request_status_idx ON free_license_request (status, created_at);
-- a customer has at most one open request: pending, or approved with its key not yet issued
CREATE UNIQUE INDEX free_license_request_open_idx ON free_license_request (customer_id)
    WHERE status = 'PENDING' OR (status = 'APPROVED' AND license_id IS NULL);

-- Applications to be a design partner: the plan and billing period the figures set, and what the company uses Kinotic
-- for. Staff approve or reject them; a customer has at most one pending.
CREATE TABLE design_partner_application (
    id             TEXT PRIMARY KEY,
    customer_id    TEXT           NOT NULL REFERENCES customer (id),
    plan           TEXT           NOT NULL,
    billing_period TEXT           NOT NULL,
    annual_revenue NUMERIC(16, 2) NOT NULL,
    capital_raised NUMERIC(16, 2),
    intended_use   TEXT           NOT NULL,
    status         TEXT           NOT NULL,
    reviewed_by    TEXT,
    reviewed_at    TIMESTAMPTZ,
    created_at     TIMESTAMPTZ    NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX design_partner_application_pending_idx ON design_partner_application (customer_id) WHERE status = 'PENDING';

-- A design partner, from the approval of its application; its license and the end of its discount are set once its
-- discounted subscription is bought.
CREATE TABLE design_partner (
    id                TEXT PRIMARY KEY,
    customer_id       TEXT        NOT NULL REFERENCES customer (id),
    application_id    TEXT        NOT NULL UNIQUE REFERENCES design_partner_application (id),
    license_id        TEXT REFERENCES license (id),
    approved_at       TIMESTAMPTZ NOT NULL,
    discount_ends_at  TIMESTAMPTZ,
    case_study_status TEXT        NOT NULL,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- What may be published about a design partner, one row per PublicationType, approved by the customer before it goes
-- out.
CREATE TABLE design_partner_publication (
    id                TEXT PRIMARY KEY,
    design_partner_id TEXT NOT NULL REFERENCES design_partner (id),
    type              TEXT NOT NULL,
    approved_by       TEXT,
    approved_at       TIMESTAMPTZ,
    UNIQUE (design_partner_id, type)
);

-- A cluster a license's keys are bound to, by its id: the UID of the cluster's kube-system namespace, which Kubernetes
-- assigns when the cluster is created. A cluster holds a seat of its license for good, unless the license ends and the
-- cluster is added to another license.
CREATE TABLE licensed_cluster (
    id              TEXT PRIMARY KEY,
    license_id      TEXT        NOT NULL REFERENCES license (id),
    cluster_id      TEXT        NOT NULL,
    name            TEXT        NOT NULL,
    added_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    released_at     TIMESTAMPTZ,
    last_renewed_at TIMESTAMPTZ
);
CREATE INDEX licensed_cluster_license_idx ON licensed_cluster (license_id, added_at);
-- a cluster holds one license's seat at a time
CREATE UNIQUE INDEX licensed_cluster_active_idx ON licensed_cluster (cluster_id) WHERE released_at IS NULL;

-- Every key ever issued, each bound to one cluster, appended to and never updated.
CREATE TABLE issued_key (
    jti                 TEXT PRIMARY KEY,
    license_id          TEXT        NOT NULL REFERENCES license (id),
    licensed_cluster_id TEXT        NOT NULL REFERENCES licensed_cluster (id),
    key                 TEXT        NOT NULL,
    issued_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at          TIMESTAMPTZ NOT NULL
);
CREATE INDEX issued_key_license_idx ON issued_key (license_id, issued_at);
CREATE INDEX issued_key_cluster_idx ON issued_key (licensed_cluster_id, issued_at);

-- An event is claimed while a delivery handles it and processed once that succeeds. A claim left by a delivery that
-- died mid-way expires, so Stripe's redelivery handles the event again.
CREATE TABLE stripe_event (
    id           TEXT PRIMARY KEY,
    type         TEXT        NOT NULL,
    claimed_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    processed_at TIMESTAMPTZ
);

-- Licenses a payment extended whose renewal email has not gone out yet; the key email job sends them on its next run.
CREATE TABLE unsent_renewal_email (
    license_id TEXT PRIMARY KEY REFERENCES license (id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- The keys whose contacts were warned that the key expires with no newer one issued for its cluster, so each is warned once.
CREATE TABLE key_expiry_warning (
    jti     TEXT PRIMARY KEY REFERENCES issued_key (jti),
    sent_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE email_link (
    id         TEXT PRIMARY KEY,
    type       TEXT        NOT NULL,
    email      TEXT        NOT NULL,
    token_hash TEXT        NOT NULL UNIQUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at TIMESTAMPTZ NOT NULL,
    used_at    TIMESTAMPTZ
);
