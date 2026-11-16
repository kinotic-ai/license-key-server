# license-key-server

Minds Ignited's license key server: sells, issues and tracks Kinotic Enterprise license keys. Customers create an
account on `licenses.kinotic.ai` with GitHub, Microsoft or Google, or with their email, whose emailed link opens the
form that names their organization and sets their password, then buy the plan their annual revenue sets
(Small, Medium or Large) for as many Kinotic clusters as they run, paying monthly or annually through Stripe; small
organizations request a free license that staff approve. A license has seats, one for each cluster it covers, and each
cluster the customer adds gets keys of its own that work on that cluster alone; see
[Clusters and seats](docs/deployment.md#clusters-and-seats). A customer that has never had a paid plan or a trial, and
holds no active free license, may first try a paid plan free for three months on one cluster, with no payment details;
see [Trials](docs/deployment.md#trials). A license keeps the price list and terms it
started on for as long as it stays active; see [Price lists](docs/deployment.md#price-lists). Once a year the customer
confirms its figures, and the license moves to the plan they set at its anniversary; see
[Yearly confirmation](docs/deployment.md#yearly-confirmation-and-plan-moves). Design partners apply for half off a paid
plan for three years in return for publicity they approve; see [Design partners](docs/deployment.md#design-partners).
Staff see every customer with its clusters and their keys on a dashboard served on a network-restricted host.

The server runs on Kinotic RPC alone (`kinotic-core` and `kinotic-api-gateway`, with `kinotic-common` for the
customers' provider sign-in) with its own single-node Ignite and a Postgres database. It never needs a Kinotic OS server: it is what licenses them. Keys are ES256 JWTs signed in Azure
Key Vault, where the private key stays, in the format Kinotic's license verifier reads: `sub` is the customer's legal
name, `clusterId` is the UID of the `kube-system` namespace of the one cluster the key works on, `exp` is always set,
and `features` is `["*"]`, every Enterprise Feature.

Everything a signed-in page does goes through the published services over Kinotic RPC. A Kinotic cluster renews its
key over Kinotic RPC too, signed in with that key (see below). HTTP carries only sign-in and sign-up (as the Kinotic consoles do), the Stripe webhook, the staff and customer OIDC redirects, and `GET /api/site`, which a page
reads before signing in to learn which site it is on and which providers customers sign in with; see
[Customer sign-in providers](docs/deployment.md#customer-sign-in-providers). The plans and their prices are shown on the kinotic.ai website,
which links to the purchase and free license pages here.

- `src/main/java` — the server: sign-in, key issuing and renewal, Stripe billing, free licenses, design partners, the
  staff and customer services
- `src/main/resources/db/migration` — the Postgres schema
- `frontend/` — the Vue app: the public pages, the customer account and the staff dashboard
- `docs/deployment.md` — hosts, configuration, Stripe, Key Vault, email and the webhook
- `CLAUDE.md` — the conventions, copied from kinotic-ai/kinotic

## Key renewal over Kinotic RPC

A Kinotic cluster renews its key from this server with `kinotic-client`
(`org.kinotic.client.api.KinoticConnectionFactory`). Kinotic's side is a separate change in kinotic-ai/kinotic; this is
the contract it builds to.

- **Endpoint:** the gateway on the public host. Connect with `ConnectOptions.serverUrl` `wss://licenses.kinotic.ai`;
  the client appends the STOMP path, so the WebSocket is `wss://licenses.kinotic.ai/api/v1`.
- **Credential:** the cluster's current license key, in the `license-key` header of the WebSocket upgrade
  (`ConnectOptions.authHeaders`). The gateway hands the upgrade's headers, not the STOMP CONNECT frame's, to its
  `SecurityService`. A key signs in when its signature verifies against this server's signing key, its `jti` is one this
  server issued, it expired no more than 30 days ago, its license is not cancelled, lapsed, expired or revoked, and its
  cluster has not moved to another license. Any other key is refused with a 401 on the upgrade. Any key of the cluster signs in,
  however old, so a restored backup is never locked out.
- **Service:** Kinotic declares this interface in its own code; there is no shared jar. A service's address is its
  zone, namespace, name and version together, so all four must match:

  ```java
  @Publish(namespace = "org.kinotic.license", name = "LicenseRenewalService")
  @Version("1.0.0")
  @Zone("licensee")
  public interface LicenseRenewalService {
      CompletableFuture<String> renew();
  }
  ```

  The signed-in cluster may call this service alone.
- **`renew()`** returns the cluster's newest key, read from the cluster the key it signed in with was issued for (it
  takes no argument). When the license is paid beyond that key's coverage it issues the key covering the paid-through
  date first, so repeated calls return the same key until the license is paid further. A trial's cluster gets its one
  key, which expires when the trial ends. A cluster beyond the license's seats, which are its oldest clusters still
  holding one, gets an error saying so. For a past-due license it issues nothing: a cluster that signed in with an older
  key gets its newest, and one already holding its newest gets an error naming the reason (past due since when, and when
  the newest key expires).

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

## License

Licensed under the [Elastic License 2.0](LICENSE.md).
