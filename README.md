# license-key-server

Minds Ignited's license key server: sells, issues and tracks Kinotic Enterprise license keys. Customers create an
account on `licenses.kinotic.ai` by confirming their email with a code, then buy the plan their annual revenue sets
(Small, Medium or Large), paying monthly or annually through Stripe, and get a signed key the moment payment
succeeds; small organizations request a free license that staff approve. A license keeps the price list and terms it
started on for as long as it stays active; see [Price lists](docs/deployment.md#price-lists).
Staff see every customer and the keys it holds on a dashboard served on a network-restricted host.

The server runs on Kinotic RPC alone (`kinotic-core` and `kinotic-api-gateway`) with its own single-node Ignite and
a Postgres database. It never needs a Kinotic OS server: it is what licenses them. Keys are ES256 JWTs signed in Azure
Key Vault, where the private key stays, in the format Kinotic's license verifier reads: `sub` is the customer's legal
name, `exp` is always set, and `features` is `["*"]`, every Enterprise Feature.

Everything a signed-in page does goes through the published services over Kinotic RPC. HTTP carries only sign-in and
sign-up (as the Kinotic consoles do), the Stripe webhook, the staff OIDC redirect, and `GET /api/site`, which a page
reads before signing in to learn which site it is on. The plans and their prices are shown on the kinotic.ai website,
which links to the purchase and free license pages here.

- `src/main/java` — the server: sign-in, key issuing, Stripe billing, free licenses, the staff and customer services
- `src/main/resources/db/migration` — the Postgres schema
- `frontend/` — the Vue app: the public pages, the customer account and the staff dashboard
- `docs/deployment.md` — hosts, configuration, Stripe, Key Vault, email and the webhook
- `CLAUDE.md` — the conventions, copied from kinotic-ai/kinotic

## Running locally

Postgres on a port of your choosing, then:

```bash
./gradlew bootRun --args='--spring.profiles.active=development'   # the API on 58503
cd frontend && pnpm install && pnpm dev                            # the public site on 5173
cd frontend && pnpm dev:staff                                      # the staff dashboard on 5174
```

The development profile (`application-development.yml`) points at Postgres on 5433 as `postgres`/`postgres`, logs
emails instead of sending them, and signs keys with an ephemeral key (`kinotic.licenses.ephemeralSigningKey`). Stripe and the staff OIDC provider need real
test-mode settings in an `application-local.yml` (gitignored) or the environment; see `docs/deployment.md`.

```bash
LICENSES_TEST_DATABASE_URL=postgresql://postgres:postgres@127.0.0.1:5433/licenses_test ./gradlew test
```

runs the suite against that database; without the variable the suite starts a Testcontainers Postgres, and skips
when Docker is unavailable too.
