# gRPC load balancing in Kubernetes

Why a gRPC client behind a normal Service sends every call to one Pod, and what changes that:
a headless Service with `round_robin`, `maxConnectionAge` on the server, and the client's DNS
cache. All of it was run on the k3s + Calico lab cluster (v1.34.1+k3s1) with grpc-java 1.86.0.

Labels: **(lab)** seen in the runs below; **(code)** read in grpc-java v1.86.0 (`fe53026`);
**(docs)** from the gRPC or Kubernetes documentation.

## 0. Short answers

- **gRPC balances calls, not connections, and a ClusterIP only balances connections.**
  kube-proxy picks a Pod when a TCP connection opens. A gRPC channel opens one connection and
  sends every call on it, so every call reaches that one Pod. That held for both load-balancing
  policies (§2).
- **To spread calls, the client must see every Pod.** A headless Service (`clusterIP: None`)
  makes DNS return every ready Pod IP. With `round_robin`, the client opens one connection per Pod
  and rotates calls over them. With the default, `pick_first`, it still uses one Pod.
- **grpc-java's default policy is `pick_first`** (`GrpcUtil.DEFAULT_LB_POLICY`) **(code)**.
  Set `defaultLoadBalancingPolicy("round_robin")` on the channel, or put it in the service config.
- **New Pods get no calls until the client resolves DNS again.** grpc-java resolves when a
  connection goes from READY to IDLE or every address fails, not on a timer **(code)**. In the lab,
  two Pods added mid-run got nothing for 35 s.
- **`maxConnectionAge` on the server makes clients re-resolve.** The server closes each connection
  after that age, with the two-GOAWAY sequence, and the client asks DNS again. New Pods then got
  calls, but only 25–30 s after the scale-up, because grpc-java won't resolve again within
  `networkaddress.cache.ttl` (30 s by default) of the last time **(lab + code)**.
- **The other way is an L7 proxy** (a service mesh sidecar, Envoy, an Ingress or Gateway with
  HTTP/2 backends) that balances each request. Then the client keeps one connection, to the proxy.

## 1. The lab

- **Server:** a Deployment `grpc-srv` of 3 Pods, spread over the three nodes, running a grpc-java
  server whose one method answers with the Pod's name (`$POD_NAME` from the downward API). The
  jars come from a hostPath; the image is `eclipse-temurin:21-jre`.
- **Two Services on the same Pods:** `grpc-srv` (ClusterIP) and `grpc-srv-headless`
  (`clusterIP: None`).
- **Client:** a Pod in the same namespace that makes one call every 20 ms and prints which Pod
  answered, per time window.

```java
ManagedChannel ch = ManagedChannelBuilder.forTarget(target)
    .defaultLoadBalancingPolicy(policy)          // "pick_first" or "round_robin"
    .usePlaintext().build();
```

What DNS returns for each Service, from inside the client Pod **(lab)**:

```text
$ getent hosts grpc-srv.lb.svc.cluster.local grpc-srv-headless.lb.svc.cluster.local
10.43.80.63     grpc-srv.lb.svc.cluster.local              one ClusterIP
10.244.127.76   grpc-srv-headless.lb.svc.cluster.local     the three Pod IPs
10.244.189.13   grpc-srv-headless.lb.svc.cluster.local
10.244.70.17    grpc-srv-headless.lb.svc.cluster.local
```

## 2. Four combinations, six seconds each (lab)

| Target | Policy | Pods that answered |
|---|---|---|
| `grpc-srv:50051` (ClusterIP) | `pick_first` | `xbw66`: 180 calls |
| `grpc-srv:50051` (ClusterIP) | `round_robin` | `r5xgl`: 188 calls |
| `dns:///grpc-srv-headless.lb.svc.cluster.local:50051` | `pick_first` | `r5xgl`: 233 calls |
| `dns:///grpc-srv-headless.lb.svc.cluster.local:50051` | `round_robin` | `kdp7b` 68, `r5xgl` 70, `xbw66` 69 |

```text
 ┌─ ClusterIP Service: DNS returns ONE address ─────────────────────────────┐
 │                                                                          │
 │ client ──── one TCP connection ────► 10.43.80.63:50051                   │
 │                                      kube-proxy DNAT picks a Pod once,   │
 │                                      when the connection opens           │
 │                                                                          │
 │ every call on that connection ──► the same Pod                           │
 │                                                                          │
 └──────────────────────────────────────────────────────────────────────────┘
 ┌─ headless Service: DNS returns every ready Pod IP ───────────────────────┐
 │                                                                          │
 │ client ─┬── connection ──► 10.244.70.17:50051                            │
 │         ├── connection ──► 10.244.189.13:50051                           │
 │         └── connection ──► 10.244.127.76:50051   (round_robin: one each) │
 │                                                                          │
 │ pick_first:  connects to the first address only (the next on failure)    │
 │ round_robin: calls 1, 2, 3, 4 … → Pods 1, 2, 3, 1 …                      │
 │                                                                          │
 └──────────────────────────────────────────────────────────────────────────┘
```

- **ClusterIP, either policy: one Pod.** DNS gave one address, so even `round_robin` had one
  connection to rotate over. kube-proxy chose the Pod when that connection opened. A new channel
  can land on another Pod (the two ClusterIP runs did), but each channel sticks to one.
- **Headless with `pick_first`: one Pod.** It connects to the first address and stays there.
- **Headless with `round_robin`: an even spread.** In grpc-java 1.86, `round_robin`
  (`io.grpc.util.RoundRobinLoadBalancer`) keeps one `pick_first` child per address and picks among
  the READY ones in turn **(code)**.

## 3. Scaling up while the client runs (lab + code)

The client ran headless + `round_robin` for 45 s, with counts per 5 s. At 10 s the Deployment was
scaled from 3 to 5 Pods.

**Without `maxConnectionAge`, the new Pods got nothing:**

```text
  0-  5s  {kdp7b=62, r5xgl=61, xbw66=60}
 10- 15s  {kdp7b=66, r5xgl=67, xbw66=67}        scaled to 5 at 10 s
 25- 30s  {kdp7b=71, r5xgl=72, xbw66=72}
 40- 45s  {kdp7b=72, r5xgl=72, xbw66=73}        526wp and mjlxr: Ready, no calls
```

The client resolved DNS once, at the start. Nothing made it resolve again: no connection broke and
no server said goodbye. grpc-java's `PickFirstLeafLoadBalancer` asks for re-resolution only when a
READY connection becomes IDLE, or after every address has failed **(code)**. DNS has no way to
push a change to the client.

**With `maxConnectionAge(10s)` on the server** (`NettyServerBuilder.maxConnectionAge`, plus
`maxConnectionAgeGrace(5s)`):

```text
 25- 30s  {j6fft=71, pd4rj=71, sx8r5=72}
 35- 40s  {bnr2v=4, j6fft=65, pd4rj=64, sx8r5=63, vx9sh=4}       the new Pods appear
 40- 45s  {bnr2v=40, j6fft=43, pd4rj=42, sx8r5=42, vx9sh=41}     even again
```

Each server closed its connection about every 10 s (±10% jitter, `NettyServer`) with the GOAWAY
sequence of [grpc-java-http2.md](grpc-java-http2.md) §12, debug data `max_age`. The connection went
IDLE, the child asked for re-resolution, and the client reconnected **(code)**. The calls in
between weren't lost: open calls finish during the grace period, and `round_robin` sends new ones
to the other connections.

**But the new Pods only appeared 25–30 s after the scale-up.** `DnsNameResolver` ignores a
refresh request until `networkaddress.cache.ttl` has passed since the last resolution, 30 s by
default (`DEFAULT_NETWORK_CACHE_TTL_SECONDS`) **(code)**. The refreshes triggered by the first
GOAWAYs were dropped. The same run with `-Dnetworkaddress.cache.ttl=0 -Dsun.net.inetaddr.ttl=0` on
the client:

```text
 10- 15s  {bnr2v=68, j6fft=68, sx8r5=69}                          scaled to 5 at 10 s
 15- 20s  {bnr2v=63, j6fft=62, sx8r5=63, tp644=7, zbphp=7}        the new Pods appear
 20- 25s  {bnr2v=43, j6fft=41, sx8r5=41, tp644=41, zbphp=42}
```

Two runs aren't a benchmark: Pod start-up time varies too. The ordering matches the code, though.

**What this means for settings:**

- **`maxConnectionAge` bounds how long a client can ignore new Pods.** A few minutes is typical:
  each reconnect costs a TCP (and TLS) handshake.
- **The client's DNS cache adds up to 30 s.** Lowering it means more DNS queries, from every
  client, each time a connection is recycled.
- **Removed Pods are a different case.** When a Pod goes away its connections break, the client
  re-resolves and stops using it. Before that, the Pod's graceful shutdown (GOAWAY) lets open
  calls finish.

## 4. The alternatives

| Approach | How calls are spread | What it costs |
|---|---|---|
| ClusterIP Service, default client | not at all: one Pod per channel | nothing to set up |
| Headless Service + `round_robin` | per call, by the client | client-side config; DNS caching delays new Pods; every client connects to every Pod |
| + `maxConnectionAge` on the server | as above, and new Pods are found within the age + 30 s | reconnects |
| L7 proxy (Envoy, Linkerd, Istio, an Ingress/Gateway with HTTP/2 to the backends) | per call, by the proxy | an extra hop; the proxy has to speak HTTP/2 both ways |
| xDS (proxyless gRPC, `xds:///` targets) | per call, by the client, from a control plane's endpoint list | a control plane such as Istio or Traffic Director **(docs)** |

The Gateway API's `GRPCRoute`, and an Ingress controller configured for gRPC backends, balance per
request because the proxy terminates the client's HTTP/2 connection
([ingress-and-gateway-api.md](ingress-and-gateway-api.md)).

## Related notes

- [grpc-java-http2.md](grpc-java-http2.md): how calls become HTTP/2 streams on one connection;
  §12 is the GOAWAY sequence that `maxConnectionAge` uses.
- [grpc-in-kubernetes.md](grpc-in-kubernetes.md): gRPC inside Kubernetes itself (etcd, CRI, CSI).
- [kubernetes-services.md](kubernetes-services.md): how kube-proxy picks a Pod per connection.
- [core-dns.md](core-dns.md): what CoreDNS returns for normal and headless Services.
