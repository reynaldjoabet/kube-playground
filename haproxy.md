every load balancer consists of a frontend layer that accepts incoming requests and a backend layer that serves the requests

## Frontend
A frontend is a proxy listener that defines the ingress side of a proxy instance. Technically it specifies:
- Bind directives — one or more `bind` statements declaring the listening socket(s): IP address(es), port(s), and socket options (e.g. `ssl`, `alpn`, `tfo`, `accept-proxy`).
- Proxy mode — the L4/L7 processing mode (`mode tcp` or `mode http`), which determines how the byte stream is parsed and what features are available.
- Request inspection & classification — ACLs (named boolean expressions over request attributes: SNI, Host header, path, source IP, etc.) used as predicates.
- Routing / dispatch rules — `use_backend <name> if <acl>` and d`efault_backend`, which select the target backend based on those predicates. Plus content-switching and request-manipulation rules (`http-request`, `tcp-request content/connection`).
So a frontend terminates the client-side connection, parses the protocol, classifies the request, and dispatches it to a backend.

## Backend
A backend is a server pool with a scheduling policy that defines the egress side. Technically it specifies:
- Server directives — the set of upstream targets (`server <name> <addr:port> [params]`), forming the candidate set for connection assignment.
- Load-balancing algorithm — the balance directive selecting the scheduling discipline: `roundrobin`, `leastconn`, `static-rr`, `source`, `uri`, `hdr`(...), `random`, etc.
- Persistence / affinity — optional stickiness via `cookie` insertion or `stick-table` + `stick on` rules, overriding the scheduler to pin a client to a server.
- Health checking — active probes (`option httpchk/check`) and passive observation that mark servers UP/DOWN and gate them in/out of the rotation.
- Server-side connection management — connection reuse/pooling, `retries`, `timeout connect/server`, `queueing`, and `maxconn `per server.

So a backend applies a scheduling algorithm (subject to affinity and health state) to assign each dispatched request to a live upstream server, then manages the server-side connection.


DNS-based load balancing has no frontend/backend layers at all; it just hands out different IPs.

Hardware LBs and cloud LBs (AWS ALB/NLB) use different vocabulary (listeners + target groups),