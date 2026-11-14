# fintech: the edge for domain.com, two ways

The same edge (hosts, APIs, scopes, MFA and IP rules, rate limits, sandbox, NetworkPolicies), built
twice:

| Folder | Built on | Rules live in | Apply |
|---|---|---|---|
| [envoy/](envoy/README.md) | Gateway API + Envoy Gateway | Gateways, HTTPRoutes and policies, each team in its own namespace | `kubectl apply -k deploy/fintech/envoy` |
| [haproxy/](haproxy/README.md) | plain HAProxy 3.0 (no controller) | one `haproxy.cfg`, owned by the platform team | `kubectl apply -k deploy/fintech/haproxy` |

Both were tested against the same signed tokens and requests, and gave the same answer to every one.
Each README has its results.

The endpoint and scope tables are in [envoy/README.md](envoy/README.md#every-endpoint-where-it-goes-and-what-the-edge-checks).

Choosing:

- **envoy/** if several teams publish routes and you want each to own its part. Kubernetes
  objects enforce who may attach where (`allowedRoutes`, RBAC). JWT keys rotate automatically from
  the JWKS URL.
- **haproxy/** if one platform team owns the edge and prefers one reviewed file and no extra
  controller. You manage JWT public keys as files, and roll HAProxy when certificates renew.

Security findings from building both, now fixed in both:

- **HAProxy:** `jwt_verify` must be given a fixed algorithm. Taking it from the token let an unsigned
  `alg: none` token through.
- **Envoy Gateway:** client-sent identity headers (`X-User-Id`, …) must be stripped before the JWT
  filter, because `claimToHeaders` only overwrites the header it sets.
- **Envoy Gateway:** one certificate shared across hosts triggers HTTP/2 connection coalescing
  (`OverlappingTLSConfig`). Both versions use one certificate per host, and HAProxy also returns
  421 when the Host header and the TLS name differ.
