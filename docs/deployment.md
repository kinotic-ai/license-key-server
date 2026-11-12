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
- **Azure Key Vault** holds the ES256 signing key, `kinotic-license-signing`, which kinotic-ai/kinotic's
  `deployment/terraform/azure/global/license.tf` creates in the system Key Vault; its private key cannot be exported.
  Never create another: Kinotic trusts only that one (see [Checking the signing key](#checking-the-signing-key)).
  Grant the container's identity (a managed identity, or the workload identity `DefaultAzureCredential` resolves)
  the `Key Vault Crypto User` role, which allows `sign` and `get` and nothing else. Key Vault offers P-256, P-384,
  P-521 and P-256K; it has no Ed25519, which is why the format is ES256. In a Premium vault or a Managed HSM, an
  HSM-protected key (`--kty EC-HSM`) works the same way and keeps the private key in the HSM.
- **Azure Communication Services** email with a verified sender on the kinotic.ai domain. The same identity needs the
  `Contributor` role on the Communication Services resource to send.
- **Stripe**: the price lists described under [Price lists](#price-lists); Link enabled under payment methods; the
  Kinotic logo and brand colour under branding, which Checkout and the Customer Portal use; the Customer Portal
  enabled with payment method updates, invoice history and cancellation, with **subscription updates allowing quantity
  changes** (the quantity is the license's seats, one for each cluster) and **with no products to switch to**: a license
  changes plan only at its yearly confirmation. Prices live only in Stripe.
- **Stripe webhook**: an endpoint at `https://licenses.kinotic.ai/api/stripe/webhook` delivering
  `checkout.session.completed`, `invoice.paid`, `invoice.payment_failed`, `customer.subscription.updated` and
  `customer.subscription.deleted`, pinned to the API version of the `stripe-java` release in `gradle.properties`.
  Its signing secret is `KINOTIC_LICENSES_STRIPE_WEBHOOKSECRET`. Stripe delivers events in no set order, so an
  event only tells the server which subscription changed: the server reads that subscription, with its price and
  latest invoice, from Stripe and brings the license to it. A restricted secret key therefore needs read access to
  prices, products and invoices, and write access to customers, subscriptions (a lapsed license's subscription is
  cancelled, and one whose figures set Free ends at its anniversary), subscription schedules (plan moves), coupons (the
  design partner coupon is created on first use) and Checkout and Customer Portal sessions. A card
  payment makes the license ready for its clusters at `checkout.session.completed`; a bank debit through Link
  completes Checkout unpaid, and the license takes clusters once Stripe reports the first invoice paid. A subscription
  the customer cancels in the Customer Portal keeps its keys until they expire and is not renewed.

## Clusters and seats

A license has `seats`, how many Kinotic clusters it covers: the Stripe subscription item's quantity for a paid plan,
which Checkout sets from the cluster count the customer chose and the Customer Portal changes, each seat billed at
the plan's Price; one for Free and for a trial. The customer adds a cluster from the account with the id the cluster's
system console License page shows, the UID of its `kube-system` namespace, and a name (`licensed_cluster`). The
License page's "License this cluster" link opens `/account?clusterId=<uid>`, which opens the add-cluster form with the
id filled in (directly when exactly one license has a seat free); an air-gapped cluster's id is copied by hand. Adding a
cluster takes a seat and issues its first key at once; the license row is locked while seats are counted, so clusters
added together never take more seats than it has, and the partial unique index `licensed_cluster_active_idx` lets a
cluster hold one seat at a time across every license. A cluster keeps its seat for good: the customer cannot release
it, so a seat licenses one cluster. A cluster gives up its seat only when its license ends (expires, lapses, is canceled
or revoked) and the cluster is added to another license.

A license's seated clusters are its oldest unreleased ones, as many as it has seats. When the customer lowers the
quantity below the clusters it holds, the newest clusters beyond it get no new keys, and renewing from one says why,
until the customer adds seats again.

## Keys

Each key is bound to one cluster by its `clusterId` claim. A key expires seven days after the date its license is paid
through: the end of the Stripe billing period for a paid plan, the end of the license year for Free. A trial's key
expires when the trial ends. Every `invoice.paid` that extends the paid-through date, and every new year of a free
license, issues each seated cluster the key covering the new date, once however often Stripe delivers it, and emails
the contacts that the license is renewed; an email that fails is sent by the daily key email job. No email carries a
key: a cluster that reaches this server installs each new key by renewing, and the customer copies one for a cluster
that cannot from the account. A license that is past due, lapsed, cancelled, expired or revoked is issued no key, and
the keys it holds work until they expire (Kinotic keeps its features on for 14 days more). The same daily job warns,
once per key, the contacts of a cluster whose newest key expires within 14 days with no newer one coming by itself: one
of a license past due, set to end, a trial, or a free license, whose next year staff approve.

## Trials

A customer that has never held a paid license or a trial, and holds no active free license, may start a trial of the
paid plan its figures set from `/buy`, with no payment details and no Stripe call beyond reading the current price
list. The trial is a license in status `TRIAL` on that plan and the current price list, running three months
(`term_end`), for one cluster, whose key expires when the trial ends. A customer holds one trial: the start
checks the customer's licenses, and the partial unique index `license_one_trial_idx` refuses a second trial started at
the same moment. Buying a plan before the trial ends moves the trial onto the subscription, as it moves a free license:
it keeps its price list and terms, and its first license year starts at the purchase. An hourly job marks a trial
whose term has ended `EXPIRED` and emails its contacts; buying afterwards starts a new license on the current price
list.
- **Staff OIDC**: a web application in Minds Ignited's Google Workspace (or Entra tenant) with the redirect URI
  `https://<staff host>/api/auth/staff/callback`, scopes `openid email profile`. With Entra, add `email` as an
  optional ID token claim: Entra sends no `email_verified`, so the server takes the tenant's own address for
  verified, and accepts only accounts on `KINOTIC_LICENSES_STAFFOIDC_ALLOWEDEMAILDOMAIN`.

## Price lists

The plans and their eligibility are set by the kinotic.ai pricing page; what each costs lives only in Stripe, as
Prices named by lookup key:

```
kinotic-<priceList>-<plan>-<period>      e.g. kinotic-1-small-annual
```

`<plan>` is `small`, `medium` or `large` and `<period>` is `monthly` or `annual`, so a price list is six recurring
Prices in one currency: a monthly Price at the plan's monthly price, and an annual Price at twelve months less 10%.
Free has no Price. The **current price list** is the highest version that has an active Price for all six; a new
license starts on it, and the server reads it again every ten minutes. A license records its price list and the
terms version in effect when it starts, and keeps both for as long as it stays active (never cancelled, with every
failed payment settled within 30 days): its plan moves are priced on its own list. A customer holding an active free
license or a trial buys at that license's price list, and the license moves onto the subscription. A customer whose
license lapsed or was cancelled buys on the current list.

To change prices, create a **new** price list: six new Prices with the next version in their lookup keys. Never edit,
archive or reuse a Price a price list holds, since licenses on that list are billed with it. A version with fewer
than six active Prices is ignored, so a new list can be built up one Price at a time and goes live with the sixth.

A license past due for more than 30 days lapses: a daily job marks it `LAPSED` and cancels its Stripe subscription,
unless Stripe reports the payment settled by then.

## Yearly confirmation and plan moves

A license's anniversary falls every twelve months from its start, for both billing periods. Thirty days before it, a
daily job emails the contacts once to confirm the company's annual revenue in their account (a Free license confirms
its capital raised too); each confirmation is a row of `revenue_confirmation`. When the confirmed figures set another
plan:

- **Another paid plan:** the subscription moves to that plan's Price on the license's **own** price list, for the
  same billing period, at the anniversary. The server does this with a **Stripe Subscription Schedule**: it creates
  one from the subscription (or reads the one it has) and sets two phases, the current Price until the anniversary and
  the new Price from it, with `end_behavior: release` and no proration, copying the current phase's discounts to both
  phases so a design partner's coupon follows the move. Stripe's `customer.subscription.updated` then brings the
  license to the new plan.
- **Free** (under $1M revenue and under $5M raised): the subscription's `cancel_at` is set to the anniversary, after
  releasing any schedule. When Stripe ends it, the license continues as Free with the same price list and terms, and
  the job starts its next year as Free and issues its clusters' keys.
- **A Free license whose figures set a paid plan:** the customer must buy that plan by the anniversary; buying it
  moves the free license onto the subscription at the price list it keeps. Otherwise the license is not renewed.
- **A Free license that still qualifies:** a renewal request goes to the staff queue, and approving it starts the
  next year.

A license whose anniversary passes unconfirmed goes on exactly as it was, keys included, and is flagged
`REVENUE_UNCONFIRMED` in the staff dashboard's Unconfirmed revenue queue. The customer can still confirm the missed
anniversary, which clears the flag; a plan move it sets takes effect at the next anniversary
(`revenue_confirmation.applies_at`). A move to Free is matched to the ending subscription by its `cancel_at`, so it
holds whichever of Stripe's `customer.subscription.deleted` and the anniversary job comes first.

## Design partners

A company on a paid plan applies at `/design-partner` with its figures (which set the plan; a company that qualifies
for Free is offered Free), the billing period and what it uses Kinotic for, and accepts the design partner terms and
their publicity (`design_partner_application`). Staff approve or reject it under Design partner applications.
Approval makes it a design partner (`design_partner`) and emails the contacts a link to
`/design-partner/checkout?application=<id>`, which opens a fresh Stripe Checkout Session for the plan and period, so
the link never goes stale the way a Checkout Session does after 24 hours.

That Checkout Session carries the **Stripe coupon `kinotic-design-partner`**: 50% off, `duration: repeating`,
`duration_in_months: 36`. The server creates it the first time it is needed if it does not exist; do not change it,
since every design partner shares it. Stripe applies it to the subscription, so it follows the subscription through
plan moves (the Subscription Schedule phases copy its discount) and ends 36 months after the subscription starts,
which the design partner record keeps as `discount_ends_at`. After that the customer pays its locked price on the
same terms.

Under Design partners, staff record each publication the customer approves (`design_partner_publication`: `LOGO`,
`USE_DESCRIPTION`, `CASE_STUDY`, with who approved it and when), and the case study's status: `NOT_STARTED`,
`IN_PROGRESS` or `PUBLISHED`, which the server refuses until the `CASE_STUDY` publication is approved.

## Emails

Every email is a Handlebars template under `src/main/resources/templates/email/` (HTML and text), sent to each contact
of the customer through Azure Communication Services:

| Template | When |
|---|---|
| `sign-in-code` | a sign-in or sign-up code is requested |
| `license-ready`, `license-renewed` | a license is bought and ready for its clusters, and each payment or free year that issues its clusters new keys |
| `trial-started`, `trial-expired` | a trial starts, ready for its cluster, and once it ends without its plan bought |
| `past-due` | a payment fails and the license leaves good standing |
| `lapse-warning`, `lapsed` | a license past due for 20 days, and once it lapses at 30 |
| `key-expiring` | a cluster's newest key expires within 14 days with no newer one coming by itself, as a trial's does before it ends |
| `confirmation-due` | 30 days before an anniversary, to confirm the figures |
| `plan-changed` | the confirmed figures move the plan, end the subscription for Free, or require a free license to buy one |
| `free-request-approved`, `free-request-rejected` | staff decide a free license request |
| `design-partner-approved`, `design-partner-rejected` | staff decide a design partner application |

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
POST, to their own origin as well, so the pattern must admit both of this server's sites:

```
KINOTIC_CORS_ALLOWEDORIGINPATTERN='https://(licenses\.kinotic\.ai|<staff host>)'
```

`kinotic.cors.allowCredentials` is `true` in `application.yml`, so the session cookie flows.

A Kinotic cluster renewing its key connects with `kinotic-client`, whose WebSocket upgrade sends the gateway's
own origin, `https://licenses.kinotic.ai`, as its `Origin`; the pattern above already admits it.

## Running the image

```bash
docker build -t kinoticai/license-key-server .
docker run --rm -p 58503:58503 --env-file license-key-server.env kinoticai/license-key-server
```

`GET /health` answers `204` once the gateway is up. The frontend is built into the image; `/` serves it on both
hostnames.

## Checking the signing key

Kinotic accepts only keys signed by the key its verifier trusts: `TRUSTED_PUBLIC_KEY` in
`kinotic-domain/src/main/java/org/kinotic/domain/internal/api/ee/license/LicenseVerifier.java` in kinotic-ai/kinotic,
the X.509 SubjectPublicKeyInfo of `kinotic-license-signing` in Base64. That key is the one
`deployment/terraform/azure/global/license.tf` in kinotic-ai/kinotic creates (its `license_signing_public_key_pem`
output). Before the server issues its first key, and whenever `KINOTIC_LICENSES_SIGNINGKEYID` changes, check that the
key this server signs with is that key:

```bash
az keyvault key download --vault-name <vault> --name kinotic-license-signing --encoding DER --file signing.der
base64 -w0 signing.der   # must print TRUSTED_PUBLIC_KEY exactly
```

The staff dashboard shows the same value under Customers, "Signing key", read from the key the running server signs
with. If the two differ, Kinotic refuses every key this server issues.

## Legal

The click-through with each purchase, trial, free license request and design partner application (on `/buy`, `/free`
and `/design-partner`) records who accepted which version of the terms, when and for which customer, and what publicity
the customer consented to (`agreement_acceptance.publicity_consent`): none for a purchase, `CUSTOMER_NAME` for Free,
whose terms let Minds Ignited name the customer as a Kinotic customer, and `DESIGN_PARTNER` for a design partner. A
trial accepts the same terms as a purchase, with no publicity. A
license records the terms version it started on and keeps it while it stays active.

For a lawyer to confirm, not decided here: whether that click-through is the written agreement the Kinotic
Enterprise License requires; the wording of "your terms never change while your license stays active"; whether a
trial needs terms of its own, such as whether a trial key permits production use and what happens to a deployment
when the trial ends; and the Free and design partner publicity clauses, including the design partner promise that
partners approve everything published about them and that nothing confidential is ever shared. The server records what it can and decides
nothing.
