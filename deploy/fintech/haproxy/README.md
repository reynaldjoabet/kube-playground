# fintech/haproxy: the edge for domain.com (plain HAProxy)

The same edge as [../envoy](../envoy/README.md), with the same hosts, paths, scopes, MFA and IP
rules, rate limits and NetworkPolicies. It's built on HAProxy 3.0 with its own configuration
language, instead of Gateway API and Envoy Gateway. Behaviour was tested to be the same, request
for request (below).

It doesn't use HAProxy's Kubernetes controllers. HUG (HAProxy Unified Gateway) would let this
folder reuse Gateway API objects, but its install wasn't permitted in this session, so nothing
built on it could be tested. Everything here runs on the stock `haproxy:3.0.5` image, configured by
one file.

## What's here

| File | Contents |
|---|---|
| `haproxy.cfg` | **all of prod's rules**: TLS per host, 421 on host/SNI mismatch, JWT verification, scope per method + path, app-user and partner checks, console IP + MFA, rate limits (stick tables), security headers, backends |
| `haproxy-sandbox.cfg` | the sandbox edge: `sandbox.api` / `sandbox.auth.domain.com`, sandbox issuer and key |
| `00-namespaces.yaml` | `edge` (HAProxy) plus the same backend namespaces as ../envoy |
| `10-certificates.yaml` | cert-manager ClusterIssuer (**DNS-01**) and one Certificate per host |
| `20-haproxy.yaml` | prod HAProxy: Deployment (3 replicas, non-root, read-only root filesystem), LoadBalancer Service (`externalTrafficPolicy: Local`), PDB, HPA |
| `25-haproxy-sandbox.yaml` | sandbox HAProxy: its own Deployment and Service (own IP) |
| `40-network-policies.yaml` | only HAProxy's Pods reach each surface; the ledger also accepts internal posters |
| `50-rbac.yaml` | only the platform team may change `edge` |
| `jwt/auth.pem`, `jwt/sandbox-auth.pem` | the auth servers' **public keys** (placeholders; see below) |
| `kustomization.yaml` | builds the ConfigMaps from the `.cfg` files and keys, with content hashes, so any edit rolls the Pods |

```text
 client ─► NLB (keeps client IP) ─► Service edge/haproxy-edge :443 ─► HAProxy Pod :8443
                                     TLS by SNI (one cert per host) → 421 if Host ≠ SNI
                                     drop client-sent X-User-Id / X-Client-Id / X-Staff-Id
                                     JWT: RS256 pinned, signature, issuer, expiry
                                     host + method + path → scope / audience / MFA / IP checks
                                     rate limits (stick tables)
                                   ─► <service>.<namespace>.svc.cluster.local:80 (ClusterIP)
                                   ─► kube-proxy ─► Pod :8080
```

The hosts, endpoints, scopes and limits are the tables in [../envoy/README.md](../envoy/README.md#every-endpoint-where-it-goes-and-what-the-edge-checks).
`haproxy.cfg` implements them rule for rule.

## How it differs from the Envoy Gateway version

| | ../envoy (Gateway API) | here (plain HAProxy) |
|---|---|---|
| Where the rules live | many objects: Gateway, routes and policies per team namespace | **one file**, `haproxy.cfg`, owned by the platform team |
| Who can change routing | each team, for its own namespace, within `allowedRoutes` | the platform team; teams send changes for review |
| JWT keys | fetched from the JWKS URL (`remoteJWKS`), rotated automatically | **a PEM file** per issuer; key rotation means updating `jwt/*.pem` |
| Certificates | cert-manager watches the Gateway; HTTP-01 through it | cert-manager `Certificate`s with **DNS-01**; **HAProxy reloads only on restart**, so use Stakater Reloader (annotation in `20-haproxy.yaml`) |
| To backends | Envoy → Pod IPs (EndpointSlices) | HAProxy → Service ClusterIP → kube-proxy → Pod |
| Host/SNI mismatch | Envoy Gateway warns about overlapping certificates | explicit **421** for any request whose Host differs from the TLS name |
| Rate limits | `BackendTrafficPolicy` (Local, per replica) | stick tables (per replica; `peers` could share them) |
| Extra controller in the cluster | Envoy Gateway | none |

## What you have to provide

- **`jwt/auth.pem` and `jwt/sandbox-auth.pem`**: the auth servers' public keys as PEM.
  - The files shipped here are **placeholders**: real keys whose private half was discarded. HAProxy
    starts, but **no token verifies** until you replace them; it fails closed.
  - To convert a JWKS `n`/`e` pair to PEM, any JWK-to-PEM tool works.
  - **Rotation:** add the new key next to the old one and check both in `haproxy.cfg`
    (`jwt_verify` against two files), deploy, then remove the old one after it stops signing.
- **cert-manager** with a DNS-01 provider (Route 53 shown, credentials via IRSA or Pod Identity),
  plus **Stakater Reloader**, or another way to roll HAProxy when a certificate renews.
- **Office/VPN ranges** in place of `203.0.113.0/24 198.51.100.0/24` (`acl office_or_vpn`).
- **Cloud LB annotations** on the two Services (AWS shown).
- **The backend Services**, as in ../envoy (port 80 → Pod port 8080).

```bash
kubectl apply -k deploy/fintech/haproxy
kubectl -n edge get pods,svc
kubectl -n edge logs deploy/haproxy-edge | grep -i -E 'alert|could not resolve'   # should be empty
```

## What was tested

**The config, in Docker** (`haproxy:3.0.5`; `haproxy -c` passes for both files):
- 16 `traefik/whoami` backends answered under the **real cluster DNS names** (for example
  `customers.partner-api.svc.cluster.local`), so the config ran unchanged;
- one client was outside the office range, and one inside it (a Docker network on `203.0.113.0/24`);
- the tokens were the same signed test tokens as ../envoy's tests.

| Request | Token | Envoy result | HAProxy result |
|---|---|---|---|
| `GET /v1/customers/c_123` | `customers.read` | 200 | 200 |
| `PATCH /v1/customers/c_123` | `customers.read` / `customers.write` | 403 / 200 | 403 / 200 |
| `POST /v1/customers/c_123:close` | `customers.write` / `customers.read` | 200 / 403 | 200 / 403 |
| `GET …/kyc-intents/i_9` | `kyc.read` / `customers.read` | 200 / 403 | 200 / 403 |
| `POST /v1/ledger/journals` | `ledger.read` / `ledger.post` | 403 / 200 | 403 / 200 |
| `GET /v1/customers/c_123/unlisted-endpoint` | `customers.read` | 403 | 403 |
| `GET /v1/customers/c_123` | none / wrong issuer / user token | 401 / 401 / 403 | 401 / 401 / 403 |
| `GET /wallets` | user / none / partner | 200 / 401 / 403 | 200 / 401 / 403 |
| `GET /kiosks/nearby`, `/kiosks/k1`; `POST /webhooks/plaid` | none | 200 | 200 |
| `GET /kiosks/k1/transactions`; `POST /kiosks/k1/withdraw` | none | 401 | 401 |
| `GET /webhooks/plaid`, `/admin/users` on api | | 404 | 404 |
| console from outside the allowlist | even MFA | 403 | 403 |
| console `/admin/users` from the office | MFA / no MFA / none / user | 200 / 403 / 401 / 403 | 200 / 403 / 401 / 403 |
| money-out per user, console login per IP | | 10 / 20 per min | 10 / 20 per min |

HAProxy-specific checks:
- **Forged tokens:** `alg: none` with no signature, a changed signature byte, and an expired token
  (good signature, `exp` in 2023) were all **401**.
- **Forged identity headers:** a client sending `X-User-Id: admin` with a real user token reached
  the backend as `X-User-Id: user-42`.
- **Host/SNI mismatch:** HTTP/2 to `api.domain.com` with `Host: console.domain.com` got **421**.
- **Routing:** each path reached the right backend, by name.
- **The rest:** each host presented its own certificate over HTTP/2; the security headers were set;
  `:8080` redirected with 301; `:8404/healthz` returned 200.
- **Runtime scaling:** HAProxy's runtime API adds a server without a reload (Example 4 in
  ingress-and-gateway-api.md §2.1).

**On Kubernetes** (minikube): the real kustomization (36 objects) passed strict server-side
validation. It was deployed with two test changes: the test public key, and self-signed Secrets
instead of cert-manager.
- All 5 HAProxy Pods ran under UID 99 with a read-only root filesystem, and the projected
  `<host>.pem` + `<host>.pem.key` files loaded.
- Cluster DNS resolved every backend, with no errors in the logs.
- The backends saw HAProxy's Pod IPs as the source, so the NetworkPolicies match them, and every
  direct call that skipped HAProxy was blocked.
- A prod token was rejected by the sandbox (401).

**Finding that shaped the config:**
- **`jwt_verify` must get a fixed algorithm.** A first probe passed the token's own `alg` header
  to `jwt_verify`. A token with `"alg":"none"` and an empty signature then verified as **1**, so
  anyone could have forged any identity. `haproxy.cfg` pins `jwt_verify("RS256", …)` and also
  requires `alg` = `RS256`. The attack is now rejected (tested).

**Not tested:**
- cert-manager issuance and renewal, and the Reloader roll;
- a real cloud LB;
- key rotation with two keys;
- the HPA (minikube has no metrics-server).
