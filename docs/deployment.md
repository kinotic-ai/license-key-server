# Deploying the license key server

One container serves two hostnames on one port (58503): the public site, `licenses.kinotic.ai`, and the staff
dashboard on a host restricted by network to Minds Ignited, as the Kinotic system console is. The reverse proxy in
front of the container terminates TLS for both names and forwards every path, WebSockets included (the STOMP API is
`/api/v1`), to port 58503 with the `Host` header unchanged: the server decides which site a request belongs to from
that header. Exactly one proxy stands in front of the server, and it appends the client's address to
`X-Forwarded-For`: the sign-in code rate limits, which also gate sign-up, key on that last entry, since the client writes
whatever it likes to the left of it. A second proxy in the chain would make every client look like the first proxy.

## Infrastructure

- **Postgres** 16 or later, one database. Flyway migrates it at startup from `src/main/resources/db/migration`.
- **Ignite** runs inside the container as a single node bound to the loopback interface; nothing to deploy, and no
  port to expose. The work directory is `/var/lib/license-key-server/ignite`.
- **Azure Key Vault** holds the ES256 signing key. Create it once, and never export it:
  ```bash
  az keyvault key create --vault-name <vault> --name kinotic-license-signing --kty EC --curve P-256 --ops sign verify
  ```
  Grant the container's identity (a managed identity, or the workload identity `DefaultAzureCredential` resolves)
  the `Key Vault Crypto User` role, which allows `sign` and `get` and nothing else. Key Vault offers P-256, P-384,
  P-521 and P-256K; it has no Ed25519, which is why the format is ES256. In a Premium vault or a Managed HSM, an
  HSM-protected key (`--kty EC-HSM`) works the same way and keeps the private key in the HSM.
- **Azure Communication Services** email with a verified sender on the kinotic.ai domain. The same identity needs the
  `Contributor` role on the Communication Services resource to send.
- **Stripe**: a Price per paid plan with the lookup keys `kinotic-startup`, `kinotic-business` and
  `kinotic-enterprise` (monthly recurring, one currency); Link enabled under payment methods; the Kinotic logo and
  brand colour under branding, which Checkout and the Customer Portal use; the Customer Portal enabled with payment
  method updates and invoice history. Prices live only in Stripe.
- **Stripe webhook**: an endpoint at `https://licenses.kinotic.ai/api/stripe/webhook` delivering
  `checkout.session.completed`, `invoice.paid`, `invoice.payment_failed`, `customer.subscription.updated` and
  `customer.subscription.deleted`, pinned to the API version of the `stripe-java` release in `gradle.properties`.
  Its signing secret is `KINOTIC_LICENSES_STRIPE_WEBHOOKSECRET`. Stripe delivers events in no set order, so an
  event only tells the server which subscription changed: the server reads that subscription, with its price and
  latest invoice, from Stripe and brings the license to it. A restricted secret key therefore needs read access to
  prices, products, subscriptions and invoices, and write access to customers and to Checkout and Customer Portal
  sessions. A card
  payment gets its key from `checkout.session.completed`; a bank debit through Link completes Checkout unpaid, and
  its key is issued and emailed once Stripe reports the first invoice paid. A subscription the customer cancels in
  the Customer Portal keeps its key until it expires and is not renewed.
- **Staff OIDC**: a web application in Minds Ignited's Google Workspace (or Entra tenant) with the redirect URI
  `https://<staff host>/api/auth/staff/callback`, scopes `openid email profile`. With Entra, add `email` as an
  optional ID token claim: Entra sends no `email_verified`, so the server takes the tenant's own address for
  verified, and accepts only accounts on `KINOTIC_LICENSES_STAFFOIDC_ALLOWEDEMAILDOMAIN`.

## Configuration

Every setting is a Spring property, set through the environment:

| Variable | Value |
|---|---|
| `SPRING_PROFILES_ACTIVE` | leave unset in production; `development` on a developer's machine |
| `KINOTIC_LICENSES_PUBLICBASEURL` | `https://licenses.kinotic.ai` |
| `KINOTIC_LICENSES_STAFFBASEURL` | `https://<staff host>` |
| `KINOTIC_LICENSES_SIGNINGKEYID` | the key identifier, `https://<vault>.vault.azure.net/keys/kinotic-license-signing/<version>` |
| `KINOTIC_LICENSES_DATABASE_HOST`, `_PORT`, `_NAME`, `_USER`, `_PASSWORD` | the Postgres database |
| `KINOTIC_LICENSES_STRIPE_SECRETKEY` | `sk_live_…` (or `sk_test_…` for test mode) |
| `KINOTIC_LICENSES_STRIPE_WEBHOOKSECRET` | `whsec_…` |
| `KINOTIC_LICENSES_EMAIL_ENDPOINT` | `https://<acs>.communication.azure.com` |
| `KINOTIC_LICENSES_EMAIL_SENDERADDRESS` | `licenses@kinotic.ai` |
| `KINOTIC_LICENSES_STAFFOIDC_ISSUER` | `https://accounts.google.com`, or the Entra tenant's `v2.0` issuer |
| `KINOTIC_LICENSES_STAFFOIDC_CLIENTID`, `_CLIENTSECRET` | the OIDC application |
| `KINOTIC_LICENSES_STAFFOIDC_ALLOWEDEMAILDOMAIN` | `mindsignited.com` |
| `KINOTIC_CORS_ALLOWEDORIGINPATTERN` | see below |
| `AZURE_CLIENT_ID`, `AZURE_TENANT_ID`, `AZURE_FEDERATED_TOKEN_FILE` | set by the platform for the workload identity `DefaultAzureCredential` uses |

`KINOTIC_LICENSES_SIGNINGKEYID` is required: without it the server refuses to start. The one exception is
`kinotic.licenses.ephemeralSigningKey=true`, which the development and test profiles set so a developer's machine
signs with a key generated at startup that no verifier trusts. Never set it in production.

### CORS

The gateway's CORS handler refuses a request whose `Origin` is not admitted, and browsers send `Origin` on every
POST, so the pattern must admit the kinotic.ai pricing page, which reads `/api/plans`, and both of this server's own
sites:

```
KINOTIC_CORS_ALLOWEDORIGINPATTERN='https://(kinotic\.ai|licenses\.kinotic\.ai|<staff host>)'
```

`kinotic.cors.allowCredentials` is `true` in `application.yml`, so the session cookie flows.

## Running the image

```bash
docker build -t kinoticai/license-key-server .
docker run --rm -p 58503:58503 --env-file license-key-server.env kinoticai/license-key-server
```

`GET /health` answers `204` once the gateway is up. The frontend is built into the image; `/` serves it on both
hostnames.

## The trial key

Staff issue the trial key from the dashboard (Customers, "Issue trial key"). It licenses `kinotic-trial` for
development and testing with every feature and never expires; commit it to kinotic-ai/kinotic for CI and tests
together with the public key the dashboard shows beside it, which the future `kinotic-license` verifier needs.

## Legal

The click-through with each purchase and free license request (on `/buy`, `/free` and the account page's renewal)
records who accepted which version of the terms, when and for which customer (`agreement_acceptance`). Whether that
click-through is the written agreement the Kinotic Enterprise License requires is for a lawyer to confirm; the server
records what it can and decides nothing.
