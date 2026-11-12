# DNS in Kubernetes: CoreDNS, and how it connects to everything else

The outputs below are real, and anything described but not measured is
marked. The examples run in a namespace called `dns-demo`, with CoreDNS
behind the `kube-system/kube-dns` Service at `10.96.0.10`. Kubernetes source
references are to `kubernetes/kubernetes` master.

Related: [conntrack.md](conntrack.md) (DNS creates a conntrack entry per query)
and [iptables.md](iptables.md).

## 0. DNS basics used here

| Term | Meaning |
|---|---|
| **Resolver** | The code that looks names up. In a pod it's the C library (musl or glibc), configured by `/etc/resolv.conf` |
| **Nameserver / DNS server** | A server that answers queries. CoreDNS is the cluster's |
| **Upstream** | The server a DNS server forwards to for names it doesn't own |
| **Zone** | The part of the name tree a server is in charge of. CoreDNS owns `cluster.local` |
| **FQDN, trailing dot** | A fully qualified name, written with a final dot (`api.dns-demo.svc.cluster.local.`). The dot means "this is complete, don't append anything" (§6) |
| **Search list** | Suffixes the resolver appends to short names (§3, §6) |
| **TTL** | How many seconds an answer may be cached |
| **NOERROR / NXDOMAIN / SERVFAIL** | Response codes: success / the name doesn't exist / the server failed to answer |

The record types:

| Type | Maps | Example here |
|---|---|---|
| `A` | name → IPv4 address | `api.dns-demo.svc.cluster.local` → `10.106.46.221` |
| `AAAA` | name → IPv6 address | none: this cluster is IPv4 only, so these come back empty |
| `CNAME` | name → another name | an ExternalName Service ([kubernetes-services.md](kubernetes-services.md) §4.5) |
| `SRV` | service name → host name and port | `_http._tcp.api...` → `api...:80` (§5) |
| `PTR` | IP address → name (reverse lookup) | `10.106.46.221` → `api.dns-demo.svc.cluster.local` (§5) |

**Reading `dig` output.** Every lookup below used `dig` from a pod. The full
answer for one Service, with what each part means:

```
$ dig api.dns-demo.svc.cluster.local
;; ->>HEADER<<- opcode: QUERY, status: NOERROR, id: 39987      ← response code
;; flags: qr aa rd; QUERY: 1, ANSWER: 1, AUTHORITY: 0, ADDITIONAL: 1
;;   qr = this is a response; aa = authoritative (CoreDNS owns cluster.local);
;;   rd = the client asked for recursion
;; QUESTION SECTION:
;api.dns-demo.svc.cluster.local.	IN	A                       ← what was asked: an A record
;; ANSWER SECTION:
api.dns-demo.svc.cluster.local.	30 IN	A	10.106.46.221           ← name, TTL 30 s, class IN, type, value
;; Query time: 4 msec
;; SERVER: 10.96.0.10#53(10.96.0.10) (UDP)                    ← who answered: the kube-dns ClusterIP
```

`dig` also prints a warning that `.local` is reserved for multicast DNS
(mDNS). That's harmless inside a cluster. `dig +short` prints only the
values, and `dig +search` applies the search list the way an application
would.

## 0.1 The test objects

All in namespace `dns-demo`, using the same `agnhost` test image as
[kubernetes-services.md](kubernetes-services.md) §0.2. The image is Alpine
based, so its resolver is musl libc (this matters in §6).

```yaml
# Deployment api: 2 pods with a *named* container port
kind: Deployment
metadata: {name: api}
spec:
  replicas: 2
  selector: {matchLabels: {app: api}}
  template:
    metadata: {labels: {app: api}}
    spec:
      containers:
      - name: c
        image: registry.k8s.io/e2e-test-images/agnhost:2.53
        args: [netexec, --http-port=8080]
        ports: [{name: http, containerPort: 8080}]
---
# Service api: normal ClusterIP; targetPort refers to the port by name
kind: Service
metadata: {name: api}
spec:
  selector: {app: api}
  ports: [{name: http, port: 80, targetPort: http}]
---
# Service api-headless: same pods, no ClusterIP
kind: Service
metadata: {name: api-headless}
spec:
  clusterIP: None
  selector: {app: api}
  ports: [{name: http, port: 8080}]
---
# StatefulSet db and its governing headless Service db
kind: StatefulSet
metadata: {name: db}
spec:
  replicas: 2
  serviceName: db            # the headless Service that gives each pod a DNS name
  selector: {matchLabels: {app: db}}
  template:
    metadata: {labels: {app: db}}
    spec:
      containers:
      - name: c
        image: registry.k8s.io/e2e-test-images/agnhost:2.53
        args: [netexec, --http-port=5432]
        ports: [{name: pg, containerPort: 5432}]
---
kind: Service
metadata: {name: db}
spec:
  clusterIP: None
  selector: {app: db}
  ports: [{name: pg, port: 5432}]
---
# empty and empty-headless: selectors that match no pod
kind: Service
metadata: {name: empty}
spec:
  selector: {app: nothing}
  ports: [{port: 80}]
---
kind: Service
metadata: {name: empty-headless}
spec:
  clusterIP: None
  selector: {app: nothing}
  ports: [{port: 80}]
```

Pod IPs in the outputs differ between sections, because the pods were
restarted between runs. Service ClusterIPs don't change.

---

## 1. Why a cluster needs its own DNS

A Service gives you a stable IP, but nobody wants `10.106.46.221` in their
config. And since the IP is assigned at creation time, it's different in every
cluster and every time the Service is recreated. DNS turns that into a name that
is stable across clusters and environments:

```
api                                  same namespace
api.dns-demo                         another namespace
api.dns-demo.svc.cluster.local.      fully qualified, works from anywhere in the cluster
```

Kubernetes doesn't contain a DNS server. It defines what the names must look
like (the cluster DNS specification). An add-on implements it, which is CoreDNS
almost everywhere. CoreDNS has to be able to answer:

- **Which IP is Service X?** A Service's ClusterIP, or each pod's IP for a
  headless Service.
- **Which port, on which host?** SRV records for named ports.
- **Which pod is `db-0`?** Stable per-pod names for StatefulSets.
- **Which name is this IP?** Reverse (PTR) lookups.
- **Everything else**, such as `example.org`, by passing it to a resolver
  outside the cluster.

## 2. How the pieces connect

```
 ┌──── kube-apiserver (listens :8443) ────┐  ① CoreDNS DIALS IN (10.96.0.1:443)
 │ Services, EndpointSlices, Pods, NS     │◄──── GET ?watch=true ─────────────── CoreDNS (2 pods, kube-system)
 │                                        │───── ② events stream BACK ─────────►   │  in-memory index of names
 └────────────────────────────────────────┘      on that same connection          │  forward . /etc/resolv.conf
                                                                                   ▼  (node's resolver, for the rest)
 kubelet ── writes /etc/resolv.conf into every pod ──┐
   clusterDNS: 10.96.0.10                            │
   clusterDomain: cluster.local                      ▼
                                       pod: nameserver 10.96.0.10
                                            search <ns>.svc.cluster.local svc.cluster.local cluster.local
                                            options ndots:5
                                                     │ UDP/TCP 53 to 10.96.0.10
                                                     ▼
                         kube-proxy NAT: KUBE-SVC for kube-dns:dns  ──►  one CoreDNS pod
                         (a conntrack entry per query)
```

The top arrows show two directions:

- **① Who connects.** The API server listens. CoreDNS is the client: it dials the API server, lists the objects once, then sends a watch request that never ends.
- **② Which way data flows.** The API server writes each change (ADDED, MODIFIED, DELETED) back down that open connection.

The API server never opens a connection to CoreDNS, and doesn't know it exists beyond that connection ([watching-the-api-server.md](watching-the-api-server.md) §2.1, §8.1). On the lab, CoreDNS's two Pods hold one connection each to `:8443`.

Five components are involved:

| Component | Role in DNS | How it's configured |
|---|---|---|
| kubelet | Writes each pod's `/etc/resolv.conf` | `clusterDNS`, `clusterDomain` in `/var/lib/kubelet/config.yaml`; pod `dnsPolicy`/`dnsConfig` |
| kube-apiserver | Source of truth for names | Services, EndpointSlices, Pods, Namespaces |
| CoreDNS | Answers queries from a watched copy of the API | Corefile in ConfigMap `kube-system/coredns` |
| `kube-dns` Service | Stable IP for CoreDNS itself | ClusterIP `10.96.0.10`, UDP and TCP 53, metrics on 9153 |
| kube-proxy | Load-balances queries across CoreDNS pods | Ordinary Service rules |

One lookup through all five:

```
Pod /etc/resolv.conf   (written by the kubelet)
  nameserver 10.96.0.10
  search default.svc.cluster.local svc.cluster.local cluster.local
  options ndots:5
        │
        │ app calls getaddrinfo("web.default") → UDP 53 to 10.96.0.10
        ▼
node kernel: kube-proxy DNAT 10.96.0.10:53 → a CoreDNS Pod (e.g. 10.244.x.y:53)
        ▼
CoreDNS, Corefile:
  .:53 {
      kubernetes cluster.local ...   ← *.cluster.local: answered from its in-memory copy of Services
      forward . /etc/resolv.conf     ← anything else: sent to the node's upstream resolver
      cache 30                       ← upstream answers cached up to 30 s (not cluster.local, §4)
  }
        ▼
reply: web.default.svc.cluster.local A 10.106.133.73   (src rewritten back to 10.96.0.10 by conntrack)
```

The kubelet config on this node:

```
clusterDNS:
- 10.96.0.10
clusterDomain: cluster.local
```

`clusterDNS` must equal the `kube-dns` Service's ClusterIP. Installers
(kubeadm, minikube) pick a fixed address from the service range for it, so pods
get a working resolver before DNS has even started.

## 3. What a pod's `/etc/resolv.conf` contains

kubelet builds it in `GetPodDNS` (`pkg/kubelet/network/dns/dns.go`), based on
the pod's `dnsPolicy`. Five test pods, all running `agnhost` with
`sleep infinity`, differing only in these fields:

```yaml
p-clusterfirst:  {dnsPolicy: ClusterFirst}                             # what you get if you set nothing
p-default:       {dnsPolicy: Default}
p-none:
  dnsPolicy: None                                                      # ignore everything; use only dnsConfig
  dnsConfig:
    nameservers: [10.96.0.10]
    searches: [dns-demo.svc.cluster.local]
    options: [{name: ndots, value: "1"}, {name: timeout, value: "2"}]
p-hostnet:       {hostNetwork: true, dnsPolicy: ClusterFirst}          # shares the node's network
p-hostnet-cf:    {hostNetwork: true, dnsPolicy: ClusterFirstWithHostNet}
```

What each one got in `/etc/resolv.conf` (`kubectl -n dns-demo exec <pod> --
cat /etc/resolv.conf`), with the node's own file for comparison:

```
node:                                   nameserver 192.168.65.254             options ndots:0

ClusterFirst (default):                 nameserver 10.96.0.10
                                        search dns-demo.svc.cluster.local svc.cluster.local cluster.local
                                        options ndots:5

Default:                                nameserver 192.168.65.254             options ndots:0

None + dnsConfig:                       nameserver 10.96.0.10
                                        search dns-demo.svc.cluster.local
                                        options ndots:1 timeout:2

hostNetwork + ClusterFirst:             nameserver 192.168.65.254             options ndots:0

hostNetwork + ClusterFirstWithHostNet:  nameserver 10.96.0.10
                                        search dns-demo.svc.cluster.local svc.cluster.local cluster.local
                                        options ndots:5
```

`192.168.65.254` is the resolver Docker Desktop gives the minikube node.
Whether each pod can resolve a cluster name (`getent hosts
kubernetes.default`, i.e. the API server's Service in namespace `default`):

```
p-clusterfirst   10.96.0.1  kubernetes.default.svc.cluster.local
p-default        NOT RESOLVED (exit 2)
p-none           10.96.0.1  kubernetes.default.svc.cluster.local
p-hostnet        NOT RESOLVED (exit 2)
p-hostnet-cf     10.96.0.1  kubernetes.default.svc.cluster.local
```

How kubelet builds it, from the source:

- **The search list** is always `<namespace>.svc.<domain>`, `svc.<domain>`,
  `<domain>`, followed by the node's own search domains
  (`generateSearchesForDNSClusterFirst`).
- **The search list has limits:** at most 32 entries and 2,048 characters
  (`MaxDNSSearchPaths`, `MaxDNSSearchListChars` in
  `pkg/apis/core/validation/validation.go`).
- **`ndots:5`** is hard-coded (`defaultDNSOptions`).
- **`ClusterFirst` on a host-network pod quietly becomes `Default`**
  (`getPodDNSType`). A host-network agent such as a DaemonSet that needs
  Service names must set `ClusterFirstWithHostNet`.
- **Without `clusterDNS`, kubelet falls back.** If kubelet has no `clusterDNS`
  configured, `ClusterFirst` pods fall back to `Default` and get a
  `MissingClusterDNS` warning event.
- **`Default` doesn't mean the default.** The default policy is
  `ClusterFirst`. `Default` means "use the node's resolver".

## 4. The Corefile

```
.:53 {
    errors
    health { lameduck 5s }
    ready
    kubernetes cluster.local in-addr.arpa ip6.arpa {
       pods insecure
       fallthrough in-addr.arpa ip6.arpa
       ttl 30
    }
    prometheus :9153
    forward . /etc/resolv.conf { max_concurrent 1000 }
    cache 30 {
       disable success cluster.local
       disable denial cluster.local
    }
    loop
    reload
    loadbalance
}
```

| Plugin | What it does here | Seen in |
|---|---|---|
| `errors` | Logs errors | – |
| `health { lameduck 5s }` | `/health` for the liveness probe; on shutdown keeps answering for 5 s | §9: no failed queries while a pod was deleted |
| `ready` | `/ready` returns OK once all plugins are ready (readiness probe) | – |
| `kubernetes` | Answers `cluster.local` and reverse zones from watched API objects; `pods insecure` enables pod A records; `ttl 30` | §5 |
| `fallthrough in-addr.arpa ip6.arpa` | Reverse lookups for IPs it doesn't know are passed to the next plugin, ending at `forward` | – |
| `prometheus :9153` | Metrics, exposed through the `kube-dns` Service | §8 |
| `forward . /etc/resolv.conf` | Everything else goes to the resolver in CoreDNS's *own* `/etc/resolv.conf`; at most 1,000 concurrent upstream queries | §8 |
| `cache 30` | Caches upstream answers for up to 30 s, but not `cluster.local` answers | §8 |
| `loop` | Detects forwarding loops and exits | §10 |
| `reload` | Applies Corefile changes without a restart (about every 30 s) | – |
| `loadbalance` | Shuffles the order of A records in each answer | – |

Plugins run in a fixed order compiled into CoreDNS, not the order written in
the Corefile.

## 5. What CoreDNS answers

The test objects are in §0.1. Each line below is the answer to `dig +search
<name>` from `p-clusterfirst`, shown with its full name:

```
api.dns-demo.svc.cluster.local              A 10.106.46.221                      NOERROR   # ClusterIP
api-headless.dns-demo.svc.cluster.local     A 10.244.120.99  A 10.244.120.98     NOERROR   # every ready pod
empty.dns-demo.svc.cluster.local            A 10.109.169.18                      NOERROR   # resolves even with no pods
empty-headless.dns-demo.svc.cluster.local   (no answer)                          NXDOMAIN
nope.dns-demo.svc.cluster.local             (no answer)                          NXDOMAIN
```

**A normal Service always resolves; a headless one only resolves while it has
ready pods.** A client of `empty` gets an IP and then a connection refused
(kube-proxy's REJECT rule). A client of `empty-headless` gets a DNS failure.
Those look completely different in application logs.

The same, run again later (pod IPs had changed after a restart):

```
$ for n in api api-headless empty empty-headless nope; do dig +short +search $n; dig +search $n | grep -o "status: [A-Z]*"; done
api              10.106.46.221                  status: NOERROR
api-headless     10.244.120.120 10.244.120.115  status: NOERROR
empty            10.109.169.18                  status: NOERROR
empty-headless                                  status: NXDOMAIN
nope                                            status: NXDOMAIN
```

**SRV records, one per named port** (`dig -t SRV <name>`). An SRV answer has
four fields: priority, weight, port, target host:

```
_http._tcp.api.dns-demo.svc.cluster.local. 30 IN SRV 0 100 80 api.dns-demo.svc.cluster.local.
api.dns-demo.svc.cluster.local.            30 IN A   10.106.46.221

_pg._tcp.db.dns-demo.svc.cluster.local.    30 IN SRV 0 50 5432 db-0.db.dns-demo.svc.cluster.local.
_pg._tcp.db.dns-demo.svc.cluster.local.    30 IN SRV 0 50 5432 db-1.db.dns-demo.svc.cluster.local.
db-0.db.dns-demo.svc.cluster.local.        30 IN A   10.244.120.100
db-1.db.dns-demo.svc.cluster.local.        30 IN A   10.244.120.101
```

The name format is `_<port-name>._<protocol>.<service>`. A normal Service
returns one record pointing at the Service. A headless one returns one per pod,
with equal weights, so clients can discover every member and its port.

**StatefulSet pods get stable names** in the form
`<pod>.<governing-service>.<namespace>.svc.<domain>`:

```
db-0.db.dns-demo.svc.cluster.local   A 10.244.120.100
db-1.db.dns-demo.svc.cluster.local   A 10.244.120.101
```

`db-0` keeps its name when it's rescheduled and gets a new IP. This is how
databases and consensus systems find their peers.

**Pod records**, available because of `pods insecure`: the pod's IP with dashes.

```
10-244-120-98.dns-demo.pod.cluster.local   A 10.244.120.98
```

"Insecure" because CoreDNS returns whatever IP is in the name, without checking
a pod with that IP exists in that namespace. `pods verified` checks it, but
costs memory, because CoreDNS then has to watch every pod.

**Reverse lookups** (`dig -x <ip>`, which asks for the PTR record of
`<ip reversed>.in-addr.arpa`, e.g. `221.46.106.10.in-addr.arpa`):

```
10.106.46.221   PTR api.dns-demo.svc.cluster.local.                                       # ClusterIP -> Service
10.244.120.100  PTR db-0.db.dns-demo.svc.cluster.local.                                   # StatefulSet pod
10.244.120.98   PTR 10-244-120-98.api.dns-demo.svc.cluster.local.
                PTR 10-244-120-98.api-headless.dns-demo.svc.cluster.local.                # one per headless/backing Service
```

## 6. The search list and `ndots:5`

A name with fewer dots than `ndots` (5) is tried against every search domain
before being tried as written. For `example.com` (1 dot) from a pod in
`dns-demo`, that means, in order:

```
example.com.dns-demo.svc.cluster.local.   → NXDOMAIN
example.com.svc.cluster.local.            → NXDOMAIN
example.com.cluster.local.                → NXDOMAIN
example.com.                              → the real answer
```

With A and AAAA asked for each, that's 8 queries for one lookup.

To see what each style of name costs, I turned on CoreDNS query logging by
adding the `log` plugin to the Corefile (§4). The `reload` plugin picks it up
within about 30 s, without a restart:

```
.:53 {
    errors
    log            ← added: one log line per query
    ...
```

Each query then appears in `kubectl -n kube-system logs -l k8s-app=kube-dns`.
The lines for one `getent hosts api.dns-demo`:

```
[INFO] 10.244.120.112:53422 - 57035 "AAAA IN api.dns-demo.dns-demo.svc.cluster.local. udp 57 false 512" NXDOMAIN qr,aa,rd 150 0.000635125s
[INFO] 10.244.120.112:40384 - 60835 "AAAA IN api.dns-demo.svc.cluster.local. udp 48 false 512" NOERROR qr,aa,rd 141 0.000132958s
[INFO] 10.244.120.112:50980 - 26712 "A IN api.dns-demo.svc.cluster.local. udp 48 false 512" NOERROR qr,aa,rd 94 0.000070417s
       └ client IP:port   └ query id  └ type, name, transport, size  └ rcode   └ flags  └ size, time taken
```

Logging every query is expensive on a busy cluster, so remove `log` again
afterwards. Then I counted the queries per lookup with `getent hosts`, which
resolves a name the same way an application does:

```
pod              name                                     result    queries
p-clusterfirst   api                                      resolved   2 (0 NXDOMAIN)
p-clusterfirst   api.dns-demo                             resolved   6 (3 NXDOMAIN)
p-clusterfirst   api.dns-demo.svc.cluster.local           resolved  12 (9 NXDOMAIN)
p-clusterfirst   api.dns-demo.svc.cluster.local.          resolved   3 (0 NXDOMAIN)
p-clusterfirst   example.com                              resolved   8 (6 NXDOMAIN)
p-clusterfirst   example.com.                             resolved   2 (0 NXDOMAIN)
p-none (ndots:1) example.com                              resolved   2 (0 NXDOMAIN)
p-none (ndots:1) api                                      resolved   2 (0 NXDOMAIN)
```

- **A short name in the same namespace is cheap.** The first search domain
  completes it on the first try.
- **The fully qualified name without a trailing dot is the most expensive**
  (12 queries). It has only 4 dots, so it's first tried as
  `api.dns-demo.svc.cluster.local.dns-demo.svc.cluster.local`, then with each
  of the other search domains, before being looked up as written. Writing the
  "safe", fully qualified form actually costs the most.
- **A trailing dot skips the search list.** `example.com.` and
  `api.dns-demo.svc.cluster.local.` go straight out.
- **Lowering `ndots` fixes external names**, and short in-cluster names still
  work because the search list is still there.

These counts were measured with musl libc (Alpine-based images), which sends A
and AAAA in parallel and here repeated the A query. glibc images send a
different number, but the multiplication through the search list is the same.
The trace for `api.dns-demo`:

```
AAAA  api.dns-demo.dns-demo.svc.cluster.local.   NXDOMAIN
A     api.dns-demo.dns-demo.svc.cluster.local.   NXDOMAIN
A     api.dns-demo.dns-demo.svc.cluster.local.   NXDOMAIN
A     api.dns-demo.svc.cluster.local.
AAAA  api.dns-demo.svc.cluster.local.
A     api.dns-demo.svc.cluster.local.
```

The same cost is visible in production metrics. On one CoreDNS pod after these
tests:

```
coredns_dns_responses_total{rcode="NOERROR"}   32
coredns_dns_responses_total{rcode="NXDOMAIN"}  29
```

Almost half of all answers were "no such name", almost all of them from search
list expansion.

To reduce it per workload:

```yaml
spec:
  dnsConfig:
    options:
    - {name: ndots, value: "2"}      # names with 2+ dots go out as written first
```

Alternatively, use fully qualified names with a trailing dot in configuration,
for example `api.dns-demo.svc.cluster.local.` or `db.example.com.`.

## 7. How CoreDNS learns about Services

CoreDNS doesn't query the API per DNS request. It holds a watched in-memory
copy. Its RBAC shows exactly what it watches:

```
$ kubectl get clusterrole system:coredns
[""]                 [endpoints services pods namespaces]   [list watch]
["discovery.k8s.io"] [endpointslices]                       [list watch]
```

It has no write access to anything. How quickly changes reached DNS, polling
every 100 ms from a pod:

```
create Service -> resolvable:  within 0.22s, 0.17s, 0.17s  (3 runs; includes the kubectl round trip)
delete Service -> NXDOMAIN:    immediate, within the polling interval
```

There's no CoreDNS cache in the way for in-cluster names (§8). Delay comes
from:

- the watch event reaching CoreDNS (well under a second here)
- **client-side caching**: some runtimes keep answers far longer than the
  30 s TTL, and JVMs in particular can cache successful lookups indefinitely
  (`networkaddress.cache.ttl`)

*Reasoned, not measured:* every CoreDNS replica holds every Service,
EndpointSlice and Namespace in memory, and receives every change. With
thousands of Services, or large headless Services whose pods change often,
CoreDNS memory and watch traffic grow just like kube-proxy's
([kubernetes-services.md](kubernetes-services.md) §6.3).

## 8. Forwarding and caching

CoreDNS's own pod uses `dnsPolicy: Default`. Its `/etc/resolv.conf` is the
node's, so `forward . /etc/resolv.conf` sends external names to the node's
resolver (`192.168.65.254` here). If CoreDNS used cluster
DNS, it would be forwarding to itself.

Asking one CoreDNS pod directly, every 2 s, shows the cache:

```
example.org.                      TTL 30    # forwarded, then cached
example.org.                      TTL 28    # from cache: TTL counting down
example.org.                      TTL 26
api.dns-demo.svc.cluster.local.   TTL 30    # built fresh from the watch data every time
api.dns-demo.svc.cluster.local.   TTL 30
api.dns-demo.svc.cluster.local.   TTL 30
```

`disable success cluster.local` and `disable denial cluster.local` keep
in-cluster answers, positive and negative, out of the cache. CoreDNS's watch
data is already current, and caching it would only add staleness. A cache
shared by all pods would make that staleness worse. Older default Corefiles
cache everything, including NXDOMAIN for a Service that's about to be created,
for up to 30 s.

## 9. CoreDNS is itself behind a Service

Queries to `10.96.0.10` go through the same kube-proxy rules as any other
Service, separately for UDP and TCP:

```
-A KUBE-SERVICES -d 10.96.0.10/32 -p udp "kube-system/kube-dns:dns cluster IP"     --dport 53 -j KUBE-SVC-TCOU...
-A KUBE-SERVICES -d 10.96.0.10/32 -p tcp "kube-system/kube-dns:dns-tcp cluster IP" --dport 53 -j KUBE-SVC-ERIF...
-A KUBE-SVC-TCOU... "kube-system/kube-dns:dns -> 10.244.120.102:53" --probability 0.5 -j KUBE-SEP-QWX4...
-A KUBE-SVC-TCOU... "kube-system/kube-dns:dns -> 10.244.120.103:53"                   -j KUBE-SEP-R6TO...
```

- **Load spreads per query.** Each `dig` uses a new source port, so each query
  is a new flow and is balanced independently. The queries that showed up in
  the logs split 10 and 10 between the two CoreDNS pods.
- **Every query is a conntrack entry on the client's node.** There were 84
  entries for `10.96.0.10` at that moment:

  ```
  udp 17 25 src=10.244.120.96 dst=10.96.0.10 sport=42307 dport=53 packets=1 ... src=10.244.120.102 dst=10.244.120.96 sport=53 dport=42307 packets=1 ...
  ```

  One request and one reply never earns `[ASSURED]`. The flow never becomes a
  UDP "stream", so each entry waits out the 30 s non-stream UDP timeout
  ([conntrack.md](conntrack.md) §3.1). On a busy node, DNS is a steady source of
  conntrack entries ([conntrack.md](conntrack.md) §10).

**Losing a CoreDNS pod.** I queried continuously for 20 s and deleted one of
the two pods partway through:

```
deleting pod/coredns-7fb8d4ff4c-lqsvs
queries ok=787 failed=0
```

This held up for three reasons:

- `lameduck 5s` keeps the deleted pod answering while it's removed from the
  Service endpoints.
- There was a second replica.
- kube-proxy removes conntrack entries pointing at the old UDP endpoint
  ([conntrack.md](conntrack.md) §7).

**If DNS is a Service, and Services need DNS, isn't that a loop?** No.
Nothing ever looks up DNS by its name. Clients reach the `kube-dns` Service
by its IP, which they already have, so the name `kube-dns` is only a label for
people:

```
                          ┌───────────── no DNS lookup anywhere on this side ─────────────┐
 kubelet config:          │                                                               │
   clusterDNS: 10.96.0.10 │  kubelet writes it into every Pod (dnsPolicy ClusterFirst):   │
                          │     /etc/resolv.conf                                          │
                          │       nameserver 10.96.0.10                                   │
                          │       search moneystack.svc.cluster.local svc.cluster.local … │
                          └───────────────────────────────────────────────────────────────┘
 app Pod: "lattice.lattice.svc?"
     │  UDP to 10.96.0.10:53            ← an IP, already known
     ▼
 kube-proxy rule for ClusterIP 10.96.0.10 ─DNAT─► CoreDNS Pod 10.244.120.87 / .88 :53
     │                                            (from EndpointSlice kube-dns-gzfxq, no DNS needed)
     ▼
 CoreDNS answers from its watch of Services/EndpointSlices:  lattice.lattice.svc → 10.x.x.x
```

There are three places a loop could happen, and each one is broken by a fixed
IP (checked on this minikube):

| Who needs to find something | How it finds it without DNS | Seen here |
|---|---|---|
| Every Pod, to find DNS | The kubelet's config has the DNS Service's ClusterIP, and the kubelet writes it into each Pod's `/etc/resolv.conf` (§2, §3) | `clusterDNS: 10.96.0.10`, `nameserver 10.96.0.10` |
| CoreDNS, to learn about Services (§7) | It finds the API server through environment variables holding the `kubernetes` Service's IP (`KUBERNETES_SERVICE_HOST`), not a name | `KUBERNETES_SERVICE_HOST=10.96.0.1` in the CoreDNS container (`docker inspect` on the node) |
| CoreDNS, for outside names (§8) | Its Pod uses `dnsPolicy: Default`, so it gets the **node's** resolver instead of itself. The `forward . /etc/resolv.conf` line in its config sends outside names there | `dnsPolicy: Default`, node `nameserver 192.168.65.254` |

Two more details:

- **The DNS Service's IP is fixed in advance, not assigned at random.** The
  installer creates `kube-dns` with `clusterIP: 10.96.0.10`, a set address
  inside the Service IP range, and puts the same value in the kubelet config
  (§2). If the two ever disagreed, every Pod's lookups would fail.
- **The cluster's own parts don't use cluster DNS.** The kubelet and
  kube-proxy use node addresses and IPs. The DNS Service depends only on
  kube-proxy rules and an EndpointSlice, and neither of those needs DNS.

The one real loop is upstream. If the node's resolver points back at CoreDNS
(a local stub such as `127.0.0.53`), outside queries go round in circles.
The `loop` plugin detects that and stops CoreDNS on purpose (§10). That part
comes from CoreDNS's docs and wasn't reproduced here.

## 10. Production

**What this cluster ships with** (minikube, comparable to a kubeadm default):

```
replicas: 2
resources: requests cpu 100m, memory 70Mi; limits memory 170Mi
priorityClassName: system-cluster-critical
readiness: /ready:8181   liveness: /health:8080
podAntiAffinity: preferred (not required) across nodes
PodDisruptionBudget: none
```

**Gaps worth closing:**

| Risk | Why | Fix |
|---|---|---|
| Both replicas down at once | No PDB; anti-affinity is only *preferred*, so both can land on one node, and a drain can evict both | Add a PDB (`minAvailable: 1`); make anti-affinity required or use topology spread across zones |
| Too few replicas as the cluster grows | 2 is fixed | `cluster-proportional-autoscaler` (replicas from node and core count) or an HPA on CPU |
| OOM kills with many Services | Memory grows with watched objects (§7) | Size the memory limit from `container_memory_working_set_bytes` at your object count |
| DNS latency or timeouts under load | Every query crosses kube-proxy and conntrack to a remote pod (§9) | **NodeLocal DNSCache**: a per-node caching agent on a link-local IP; pods query it locally, and it talks to CoreDNS over TCP |
| Occasional 5 s DNS delays | musl or glibc send A and AAAA in parallel from one socket; the two UDP conntrack inserts race and one is dropped (`insert_failed`), so the client waits out its 5 s timeout | NodeLocal DNSCache, `single-request-reopen` (glibc), or `dnsConfig` `use-vc` (TCP); watch `insert_failed` ([conntrack.md](conntrack.md) §13). Not reproduced here: `insert_failed` was 0 |
| Search-list amplification (§6) | `ndots:5` | `dnsConfig` `ndots: 2`, trailing-dot names, or the CoreDNS `autopath` plugin (answers the whole search list in one go, but needs `pods verified`) |
| CoreDNS crash-looping with `Loop ... detected` | The node's `/etc/resolv.conf` points at a local stub such as systemd-resolved's `127.0.0.53`, so CoreDNS forwards to itself | Point kubelet's `resolvConf` at the real upstream (`/run/systemd/resolve/resolv.conf`), or name explicit upstreams in `forward` |
| Upstream outage breaks every external lookup | Everything goes through one forwarder | Several upstreams in `forward`; `health_check`; `max_concurrent` to cap load |
| Host-network agents can't resolve Services | `ClusterFirst` silently becomes `Default` (§3) | `dnsPolicy: ClusterFirstWithHostNet` |
| Apps keep using old IPs | Client-side DNS caching longer than the TTL | Configure the runtime's DNS TTL (JVM `networkaddress.cache.ttl=30`) |

**What to monitor** (CoreDNS `:9153/metrics`):

- `coredns_dns_requests_total` and `coredns_dns_responses_total{rcode}`.
  - A **high NXDOMAIN share** means search-list waste (§6).
  - **`SERVFAIL`** means upstream or plugin errors.
- `coredns_dns_request_duration_seconds`: latency, with the p99 compared per
  pod.
- `coredns_forward_requests_total`, `coredns_forward_responses_total`,
  `coredns_forward_healthcheck_failures_total`: upstream health.
- `coredns_cache_hits_total`, `coredns_cache_misses_total`: the hit rate for
  external names.
- `coredns_kubernetes_dns_programming_duration_seconds`: time from an API change
  to DNS being updated.
- Node conntrack `insert_failed` and table usage
  ([conntrack.md](conntrack.md) §13).

**Debugging checklist:**

```bash
kubectl exec <pod> -- cat /etc/resolv.conf                       # right nameserver and search list? (§3)
kubectl exec <pod> -- dig +search <name>                         # resolve as the app would
kubectl exec <pod> -- dig @10.96.0.10 <fqdn>.                    # bypass the search list
kubectl exec <pod> -- dig @<coredns-pod-ip> <fqdn>.              # bypass the Service; isolates kube-proxy
kubectl -n kube-system get endpointslices -l kubernetes.io/service-name=kube-dns   # are CoreDNS pods ready?
kubectl -n kube-system logs -l k8s-app=kube-dns                  # errors, loop detection
kubectl -n kube-system get cm coredns -o yaml                    # Corefile; add `log` temporarily to see queries
```
