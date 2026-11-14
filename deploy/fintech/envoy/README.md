# fintech/envoy: the edge for domain.com (Gateway API + Envoy Gateway)

Everything between the internet and the services for the domains and APIs below: Gateways,
certificates, routes, OAuth2 scope checks, MFA and IP rules for the console, rate limits, webhooks,
the sandbox, NetworkPolicies and RBAC. The services themselves (Deployments) aren't here. Each file
lists the Services it expects.

Concepts are explained in [ingress-and-gateway-api.md](../../../ingress-and-gateway-api.md): Gateway
vs GatewayClass (§3.3), `allowedRoutes` (§3.3), policies and `mergeType` (§3.9), and NetworkPolicy
with client IPs (§1.1).

## Domains

| Host | What | In this folder | Who may call |
|---|---|---|---|
| `domain.com` | marketing / docs landing, no PII | `30-web.yaml` → Service `web/site` | anyone |
| `docs.domain.com` | API docs & changelog | `30-web.yaml` → `web/docs` | anyone |
| `status.domain.com` | uptime & incidents | **not here**: host it outside this cluster (a status-page service), DNS only | anyone |
| `console.domain.com` | partner/admin console | `34-console.yaml` → `console/console-ui`, `console/admin-api` | office/VPN IPs; admin API also needs a staff token with MFA |
| `auth.domain.com` | OAuth2/OIDC authorization server | `31-auth.yaml` → `auth/auth-server` | anyone (rate-limited) |
| `api.domain.com` | `/v1/...` public partner API; unversioned paths: first-party app API | `33-partner-api.yaml`, `32-app-api.yaml` | partners: token + scope; app users: user token |
| `events.domain.com` | outbound webhook delivery IPs | **not here**: these are the cluster's *egress* IPs (a NAT gateway with fixed addresses), not a listener | — |
| `sandbox.api.domain.com` | public sandbox API | `35-sandbox.yaml` on its **own Gateway** | sandbox tokens |
| `sandbox.auth.domain.com` | sandbox IdP | `35-sandbox.yaml` | anyone |

```text
                    prod Gateway (own Envoy fleet, own NLB, own IP)              sandbox Gateway (own IP)
                    ───────────────────────────────────────────────              ────────────────────────
 :80   every host  → 301 to https (and cert-manager's HTTP-01 challenges)        same
 :443  domain.com          → web/site                      (listener https-apex)
       docs.domain.com     → web/docs                      (https-docs)
       auth.domain.com     → auth/auth-server              (https-auth)
       api.domain.com      → /v1/...  → partner-api/customers, partner-api/accounts, ledger/ledger
                            → /cards, /wallets, /trades, … → app-api/*   (https-api)
       console.domain.com  → /admin/auth → console/admin-api (IP only)
                            → /admin     → console/admin-api (IP + MFA token)
                            → /          → console/console-ui (IP only)       (https-console)
                                                                               sandbox.api.domain.com  → sandbox/*
                                                                               sandbox.auth.domain.com → sandbox/auth-server
```

Each listener only admits routes from namespaces carrying its surface label
(`gateway.domain.com/surface: web|auth|api|console|sandbox`). So the console team can't publish
on `api.domain.com`, for example. The label is the permission, which is why `50-rbac.yaml` gives
app teams no rights on namespaces.

## Every endpoint, where it goes, and what the edge checks

**Partner API** (`api.domain.com/v1`, `33-partner-api.yaml`). Token audience `api.domain.com`,
issuer `https://auth.domain.com`. The edge checks the **scope per method and path**; anything not
listed is denied.

| Method | Path | Scope | Service |
|---|---|---|---|
| POST | `/v1/customers` | `customers.write` | partner-api/customers |
| GET | `/v1/customers/{customerId}` | `customers.read` | customers |
| PATCH | `/v1/customers/{customerId}` | `customers.write` | customers |
| POST | `/v1/customers/{customerId}:close` | `customers.write` | customers |
| POST | `/v1/customers/{customerId}/kyc-intents` | `kyc.write` | customers |
| GET | `/v1/customers/{customerId}/kyc-intents/{intentId}` | `kyc.read` | customers |
| POST | `/v1/customers/{customerId}/kyc-intents/{intentId}/documents` | `kyc.write` | customers (60 s timeout) |
| GET | `/v1/customers/{customerId}/screenings` | `kyc.read` | customers |
| GET | `/v1/products`, `/v1/products/{productId}` | `products.read` | partner-api/accounts |
| POST | `/v1/accounts` | `accounts.write` | accounts |
| GET | `/v1/accounts?customer_id=…`, `/v1/accounts/{accountId}` | `accounts.read` | accounts |
| PATCH | `/v1/accounts/{accountId}` | `accounts.write` | accounts |
| POST | `/v1/accounts/{accountId}:close` | `accounts.write` | accounts |
| GET | `/v1/accounts/{accountId}/balance` | `accounts.read` | accounts |
| GET | `/v1/accounts/{accountId}/statements?from=&to=&format=` | `accounts.read` | accounts (60 s timeout) |
| POST | `/v1/beneficiaries` | `beneficiaries.write` | accounts |
| GET | `/v1/beneficiaries` | `beneficiaries.read` | accounts |
| GET | `/v1/ledger/accounts/{accountId}/transactions?cursor=&limit=` | `ledger.read` | ledger/ledger |
| GET | `/v1/ledger/journals/{journalId}` | `ledger.read` | ledger |
| POST | `/v1/ledger/journals` | `ledger.post` | ledger |
| POST | `/v1/ledger/journals/{journalId}:reverse` | `ledger.post` | ledger |

**First-party app API** (`api.domain.com`, unversioned, `32-app-api.yaml`). Token audience
`app.domain.com`; the user's `sub` reaches the services as `X-User-Id`. The edge checks *who* the
user is. *What* they may do (own wallet only, KYC level for trades and withdrawals, card limits) is
up to each service.

| Paths | Service | Edge check |
|---|---|---|
| `/cards`, `/cards/{card_id}`, `…/issue`, `…/activate`, `…/block`, `…/unblock`, `…/limits`, `…/controls`, `…/transactions` | app-api/cards | user token |
| `/wallets…` (CRUD, balances, transactions), `/transactions/{transactionId}` | app-api/wallets | user token |
| `/trades/quote`, `/trades/buy`, `/trades/sell`, `/trades`, `/trades/{tradeId}` | app-api/trading | user token; buy/sell 10/min per user |
| `/crypto/deposit`, `/crypto/withdraw`, `/crypto/addresses/{walletId}` | app-api/crypto | user token; withdraw 10/min per user |
| `/fiat/deposit`, `/fiat/withdraw`, `/fiat/accounts` | app-api/fiat | user token; withdraw 10/min per user |
| `/plaid/link-token`, `/plaid/exchange-token`, `/plaid/accounts…` | app-api/bank-link | user token |
| `/kyc/start`, `/kyc/documents`, `/kyc/selfie`, `/kyc/status`, `/kyc/result`, `/kyc/resubmit` | app-api/kyc (ZeroHash) | user token; 60 s timeout |
| `POST /kiosks/{kioskId}/deposit`, `…/withdraw`, `GET …/transactions` | app-api/kiosks | user token; withdraw 10/min per user |
| `GET /kiosks`, `/kiosks/nearby?lat=&lng=`, `/kiosks/{kioskId}` | app-api/kiosks | **public**, 120/min per IP |
| `POST /webhooks/plaid` | app-api/bank-link | **public**: bank-link **must verify Plaid's `Plaid-Verification` JWT** |
| all users: 300 requests/min per user | | |

**Console** (`console.domain.com`, `34-console.yaml`)

| Paths | Service | Edge check |
|---|---|---|
| `POST /admin/auth/login`, `POST /admin/auth/logout`, `GET /admin/auth/session` | console/admin-api | office/VPN IP; 20/min per IP |
| every other `/admin/...` (users, lock/suspend/unlock, search, KYC queue and decisions, wallets, transactions, compliance, roles, permissions) | console/admin-api | office/VPN IP **and** staff token (audience `console.domain.com`) whose `amr` contains `mfa` |
| `/` (the console web app) | console/console-ui | office/VPN IP |

Which admin may approve KYC or edit roles stays in admin-api (`/admin/roles`, `/admin/permissions`).
The edge guarantees only that every admin API call comes from an allowed network with MFA.

## Files

| File | Owner | Contents |
|---|---|---|
| `00-namespaces.yaml` | platform | 7 namespaces with surface labels |
| `10-cert-manager-issuer.yaml` | platform | Let's Encrypt ClusterIssuers (HTTP-01 through the Gateways) |
| `20-gateways.yaml` | platform | `EnvoyProxy` sizing, Gateways `prod` and `sandbox`, HTTP→HTTPS |
| `25-gateway-policies.yaml` | platform | TLS ≥ 1.2, timeouts, client-IP handling; default timeout, retry, circuit breaker, outlier ejection |
| `30-web.yaml` … `35-sandbox.yaml` | each team | routes, `SecurityPolicy`, `BackendTrafficPolicy` per surface; ReferenceGrant for the ledger |
| `40-network-policies.yaml` | platform | only the right Gateway's Envoy Pods reach each surface |
| `50-rbac.yaml` | platform | teams may edit routes and route policies in their own namespace only |

## What you have to provide

- **The backend Services** named in each file, listening on **8080** (Services map 80 → 8080; the
  NetworkPolicies allow 8080).
- **Tokens from auth.domain.com** with:
  - `iss` `https://auth.domain.com`;
  - `aud` `api.domain.com`, `app.domain.com` or `console.domain.com`;
  - `scope` as a space-separated string;
  - `client_id` on partner tokens, `sub` on user tokens;
  - `amr` containing `mfa` on console tokens.

  Keys at `https://auth.domain.com/.well-known/jwks.json`; change the `remoteJWKS` URIs if yours
  differ.
- **Office/VPN ranges** in place of the placeholders `203.0.113.0/24`, `198.51.100.0/24` in
  `34-console.yaml`. Client IPs are real only because of `externalTrafficPolicy: Local` plus an
  NLB that preserves them (`20-gateways.yaml`). Behind a CDN or another proxy, raise
  `numTrustedHops` in `25-gateway-policies.yaml` by the number of proxies that add
  `X-Forwarded-For`.
- **Cloud LB annotations** in `20-gateways.yaml` (AWS shown).
- **cert-manager** with Gateway API support (`enableGatewayAPI: true`), and DNS for every host
  pointing at the right Gateway's address **before** issuing.
- **Fixed egress IPs** for `events.domain.com` (a NAT gateway), and an external status page.

## Applying and checking

```bash
kubectl apply -k deploy/fintech/envoy
kubectl -n infra get gateway prod sandbox                     # PROGRAMMED True, ADDRESS = the NLB
kubectl -n infra get gateway prod -o jsonpath='{range .status.listeners[*]}{.name} attached={.attachedRoutes} {range .conditions[*]}{.type}={.status} {end}{"\n"}{end}'
kubectl get httproute -A                                      # then -o yaml: Accepted, ResolvedRefs
kubectl get securitypolicy,backendtrafficpolicy -A -o wide    # Accepted; Merged=True on the rate limits
```

## What was tested (minikube, Envoy Gateway v1.9.3, Gateway API CRDs v1.6.1)

- **Schema:** every object except the cert-manager issuers passed `kubectl apply
  --dry-run=server --validate=strict`, and `kubectl kustomize` builds 55 objects.
- **Behaviour:** run with the real route, policy and NetworkPolicy files, three changes only:
  - a 1-replica copy of Gateway `prod` (the real one asks for 3 × 512 MiB);
  - an inline test key (`localJWKS`) instead of `auth.domain.com`'s keys;
  - `traefik/whoami` stand-ins for every Service.

  All 9 routes and every policy were `Accepted`; the rate-limit policies were `Merged`.

| Request | Token | Result |
|---|---|---|
| `GET /v1/customers/c_123` | `customers.read` | 200 |
| `PATCH /v1/customers/c_123` | `customers.read` / `customers.write` | 403 / 200 |
| `POST /v1/customers/c_123:close` | `customers.write` / `customers.read` | 200 / 403 |
| `GET /v1/customers/c_123/kyc-intents/i_9` | `kyc.read` / `customers.read` | 200 / 403 |
| `POST /v1/ledger/journals` | `ledger.read` / `ledger.post` | 403 / 200 |
| `POST /v1/ledger/journals/j_1:reverse` | `ledger.post` | 200 |
| `GET /v1/customers/c_123/unlisted-endpoint` | `customers.read` | **403** (default deny) |
| `GET /v1/customers/c_123` | none / wrong issuer / a user token | 401 / 401 / 403 |
| `GET /wallets` | user / none / partner token | 200 / 401 / 403 |
| `GET /kiosks/nearby`, `GET /kiosks/k1`, `POST /webhooks/plaid` | none | 200 (public) |
| `GET /kiosks/k1/transactions`, `POST /kiosks/k1/withdraw` | none | 401 |
| `GET /webhooks/plaid`, `GET /admin/users` on api.domain.com | | 404 |
| console `/`, `/admin/auth/login`, `/admin/users` from outside the allowlist | even with an MFA token | 403 |
| console `/admin/users` from inside the allowlist | MFA / no MFA / none / user token | 200 / 403 / 401 / 403 |
| `domain.com`, `docs.domain.com`, `auth.domain.com` | | 200; port 80 → 301 to https |
| 14 × `POST /crypto/withdraw` as one user | user | 10 × 200, then 429 (keyed on the JWT's `sub`) |
| 24 × `POST /admin/auth/login` from one IP | | 20 × 200, then 429 |
| direct calls to `cards`, `customers`, `ledger`, `admin-api` from another namespace | | blocked (NetworkPolicy) |
| an app-api Pod → `ledger` / → `admin-api` | | allowed / blocked |

**Findings that changed the files:**

- **One certificate per listener.** The first version shared one certificate across `docs`,
  `auth`, `api` and `console`. Envoy Gateway flagged every HTTPS listener with
  `OverlappingTLSConfig: … ALPN will default to HTTP/1.1 to prevent HTTP/2 connection coalescing,
  unless explicitly configured via ClientTrafficPolicy`, and this ClientTrafficPolicy does set ALPN.
  With separate certificates the condition cleared, and each host presented its own certificate
  over HTTP/2.
- **Client-sent identity headers are stripped gateway-wide.** In a first version, a client could
  send its own `X-User-Id`, `X-Client-Id` or `X-Staff-Id`. `claimToHeaders` overwrites only the
  header it sets itself, so with a real partner token a forged `X-User-Id: admin` still reached
  the partner services, and on the public routes all three passed through. `25-gateway-policies.yaml`
  now removes all three in `earlyRequestHeaders`, before any filter runs.

  Tested: with `X-User-Id: admin`, `X-Client-Id: evil` and `X-Staff-Id: root` sent by the client,
  `/wallets` received only `X-User-Id: user-42`, `/v1/customers` only `X-Client-Id: p1`, and the
  public routes none. (Found while building ../haproxy, which strips them with `del-header`.)
- **The backends receive the raw `Authorization` header** as well as `X-User-Id`/`X-Client-Id`.
  Strip it with a `RequestHeaderModifier` if services shouldn't see tokens.

**Not tested:**

- cert-manager and Let's Encrypt (not installed; needs a public domain);
- the real LB and its annotations (minikube has no LB, so `prod` reports `AddressNotAssigned`);
- the sandbox Gateway (same mechanisms as prod);
- `remoteJWKS` fetching from a real `auth.domain.com`;
- uploads near the timeouts.

## Recommended next steps

- **Idempotency keys** on every money-moving `POST` (trades, withdrawals, `ledger/journals`,
  `:reverse`). The edge retries only failed connects, but clients retry too.
- **Global rate limits** (`type: Global`, Redis) for exact per-partner quotas. `Local` counts per
  Envoy replica, so with 3–12 replicas the real limit is up to 12 × higher.
- **A WAF in front of the NLB** for `api` and `console` (see [aws-waf-placement.md](../../../aws-waf-placement.md)).
- **Re-encrypt to the ledger** with `BackendTLSPolicy` (§3.9, object 11) if traffic inside the
  cluster must stay encrypted.
- **Sandbox in a separate cluster or account.** Here it's a separate Gateway and namespace, which
  isolates traffic but not the cluster.
- **Logging:** keep PII and tokens out of Envoy access logs (KYC documents, `Authorization`).
