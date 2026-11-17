# NetworkPolicy: Kubernetes' firewall for Pods

NetworkPolicy is Kubernetes' firewall API for Pods. It declares which Pods may talk to which, on
which ports. By default every Pod can reach every other Pod in the cluster, whatever namespace
either is in; NetworkPolicy is how you restrict that.

**Namespaces are not a network boundary.** The Kubernetes network model requires every Pod to
reach every other Pod, on any node, without NAT. Every CNI must provide this, and namespaces play
no part in it. They only separate names, RBAC, quotas and which objects a selector looks at.
(docs)

- **Inside the cluster:** a Pod in `shop` reaches a Pod in `payments` by IP, or a Service via
  `backend.payments.svc.cluster.local`, with no extra setup.
- **From outside:** Pod IPs (here flannel's `10.42.0.0/16`) usually exist only inside the cluster,
  so nothing gets in unless it is exposed through a NodePort, LoadBalancer or Ingress. Azure CNI and
  the AWS VPC CNI are exceptions: they give Pods real VNet/VPC addresses.
- **Outbound:** Pods can reach the internet by default, through SNAT on the node.

### Why the open default is a security risk

Because every Pod can talk to every other Pod, any weakness in one workload exposes the rest of
the cluster:

- **Vulnerabilities are reachable from everywhere.** An internal service that was never meant to be
  public, such as an admin endpoint, a database, a cache or a metrics port, can be reached by every
  Pod in every namespace. A bug in it can be exploited from any of them, not only from the Pods that
  are supposed to use it.
- **Data can leak.** Any Pod can read from services that hold sensitive data if they rely on "only
  our app knows the address" instead of authentication. With open egress, a Pod can also send that
  data anywhere on the internet.
- **A compromised Pod is a foothold for lateral movement.** If an attacker gets code running in one
  Pod (a vulnerable dependency, an exposed debug endpoint, a malicious image), the network lets them
  scan the Pod CIDR, reach other teams' databases and internal APIs, and go after cluster
  components. One breached frontend becomes access to the whole cluster's network.

The lab shows the starting point: before any policy, `shop/other` and `payments/api`, Pods with no
business talking to `backend`, reached it just like `frontend` did (§3, "No policy" column). After
the four §3 policies, only `frontend` could.

NetworkPolicy limits the **blast radius**: a compromised Pod can only reach what its policies
allow. This is defence in depth, alongside authentication between services (e.g. mTLS from a
service mesh), RBAC and Pod security settings. It is not a replacement for them. Policies work on
IPs and ports, so a Pod that is allowed to reach a service can still abuse it.

A common first step is a default-deny policy in each namespace plus an "allow from my own
namespace" rule, which gives namespaces the isolation people often assume they already have:

```yaml
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata: { name: allow-same-namespace, namespace: shop }
spec:
  podSelector: {}                      # every Pod in shop…
  policyTypes: [Ingress]
  ingress:
  - from: [{ podSelector: {} }]        # …accepts traffic from any Pod in shop (podSelector alone = same namespace)
```

(With default-deny on egress too, add the matching egress rule, plus DNS as in §3.) **(docs; this
policy wasn't run in the lab)**

Sections are labelled **(docs)** when they come from the Kubernetes documentation, **(lab)** when
they were checked on a cluster, and **(not tested)** otherwise. The lab is a throwaway k3s cluster
(two nodes, later three; flannel VXLAN + kube-router), set up as shown in §6.

## 0. "Without NAT": what the network model means

It means **a packet arrives with the same source and destination IP it was sent with**. If Pod A
(`10.42.0.5`) connects to Pod B (`10.42.1.7`), B sees the connection coming from `10.42.0.5`, the
address A has on its own `eth0`. Nothing along the way rewrites it to a node IP, even when A and B
are on different nodes. (docs; the diagram's addresses are illustrative, the lab output below is real)

### What NAT would look like (and what's forbidden between Pods)

Compare plain Docker on two hosts:

```text
container 172.17.0.2 on host1 ──► host1 MASQUERADE: src becomes 192.168.1.10 (host1) ──► host2 ──► container
the receiver sees 192.168.1.10 and has no idea which container sent it
```

Containers on the default bridge sit behind their host's IP, like phones behind a home router. You
can't reach a container by its own IP from another host, only through published ports on the host
IP. Kubernetes rules this out between Pods.

### How a CNI crosses nodes without NAT

```text
Pod A 10.42.0.5 (node1)                                         Pod B 10.42.1.7 (node2)
   │  src 10.42.0.5  dst 10.42.1.7                                   ▲  src 10.42.0.5  dst 10.42.1.7  ← unchanged
   ▼                                                                 │
 node1: route 10.42.1.0/24 via flannel.1 ──VXLAN──► node2: decapsulate, cni0 → Pod B
        outer header: 172.17.0.3 → 172.17.0.4 (node IPs)
        inner header: untouched
```

- **Overlays (flannel VXLAN, Calico IP-in-IP):** wrap the packet in an outer header addressed to
  the nodes, and remove it on arrival. That's encapsulation, not NAT, because the inner packet is
  never rewritten.
- **Routing (Calico BGP, flannel host-gw, Azure CNI, AWS VPC CNI):** the network knows a route to
  every Pod subnet, so the packet travels as it is.

#### Seen on the two-node lab (lab)

Nodes `np-server` (`172.19.0.2`, Pods `10.42.0.0/24`) and `np-agent` (`172.19.0.3`, Pods
`10.42.1.0/24`). `frontend` is `10.42.0.4` on np-server, `backend` (traefik/whoami, which echoes
the address it sees) is `10.42.1.3` on np-agent.

np-server's routes. The other node's Pod subnet goes through `flannel.1`:

```text
$ ip route                                   # on np-server
default via 172.19.0.1 dev eth0
10.42.0.0/24 dev cni0 proto kernel scope link src 10.42.0.1      ← my Pods
10.42.1.0/24 via 10.42.1.0 dev flannel.1 onlink                  ← np-agent's Pods, through VXLAN
172.19.0.0/16 dev eth0 proto kernel scope link src 172.19.0.2
```

frontend → backend across nodes. backend sees frontend's own IP:

```text
$ kubectl exec -n shop frontend -- wget -qO- http://10.42.1.3:8080/
Hostname: backend
IP: 10.42.1.3
RemoteAddr: 10.42.0.4:35086                  ← frontend's eth0 address, not a node IP
```

The same connection captured on np-agent's `eth0` (still wrapped) and on its `cni0` (unwrapped):

```text
# eth0, tcpdump -T vxlan udp port 8472
IP 172.19.0.2.49112 > 172.19.0.3.8472: VXLAN, flags [I] (0x08), vni 1      ← outer: node → node
IP 10.42.0.4.60438 > 10.42.1.3.8080: Flags [S], ...                        ← inner: Pod → Pod, untouched

# cni0, after decapsulation
IP 10.42.0.4.51284 > 10.42.1.3.8080: Flags [S], ...                        ← same addresses, no NAT
```

flannel makes sure of this in its `FLANNEL-POSTRTG` chain (nat table, POSTROUTING). The real rules
on np-server:

```text
-A POSTROUTING -m comment --comment "flanneld masq" -j FLANNEL-POSTRTG
-A FLANNEL-POSTRTG -m mark --mark 0x4000/0x4000 -j RETURN                       ① kube-proxy already marked it for MASQUERADE
-A FLANNEL-POSTRTG -s 10.42.0.0/24 -d 10.42.0.0/16 -j RETURN                    ② my Pods → any Pod: no NAT
-A FLANNEL-POSTRTG -s 10.42.0.0/16 -d 10.42.0.0/24 -j RETURN                    ③ any Pod → my Pods: no NAT
-A FLANNEL-POSTRTG ! -s 10.42.0.0/16 -d 10.42.0.0/24 -j RETURN                  ④ outside → my Pods: no NAT
-A FLANNEL-POSTRTG -s 10.42.0.0/16 ! -d 224.0.0.0/4 -j MASQUERADE --random-fully    ⑤ Pod → outside the cluster: NAT
-A FLANNEL-POSTRTG ! -s 10.42.0.0/16 -d 10.42.0.0/16 -j MASQUERADE --random-fully   ⑥ outside → another node's Pods: NAT
```

(`-m comment --comment "flanneld masq"` removed from the chain rules.) ② and ③ are the "without
NAT" rule. ① leaves kube-proxy's `0x4000` traffic to `KUBE-POSTROUTING`
([kubernetes-services.md](kubernetes-services.md)), so it isn't NATed twice. ⑤ is why Pods can reach
the internet. ⑥ catches traffic from a node address (e.g. a NodePort) heading for a Pod on another
node, so the reply comes back through the same node.

#### Three nodes: the tables behind cross-node routing (lab)

A third node, `np-agent2` (`172.19.0.4`), joined the same lab. Each node gets a `/24` from
`10.42.0.0/16`, and flannel records its VXLAN details on the Node object:

```text
$ kubectl get nodes -o custom-columns=NAME:.metadata.name,PODCIDR:.spec.podCIDR,\
    VTEP:.metadata.annotations.flannel\.alpha\.coreos\.com/backend-data,\
    PUBLIC-IP:.metadata.annotations.flannel\.alpha\.coreos\.com/public-ip
NAME        PODCIDR        VTEP                                      PUBLIC-IP
np-server   10.42.0.0/24   {"VNI":1,"VtepMAC":"86:23:34:44:77:e6"}   172.19.0.2
np-agent    10.42.1.0/24   {"VNI":1,"VtepMAC":"aa:14:56:99:38:06"}   172.19.0.3
np-agent2   10.42.2.0/24   {"VNI":1,"VtepMAC":"5a:f9:4e:d2:7c:55"}   172.19.0.4
```

| Node | `eth0` | `flannel.1` (VTEP) | `flannel.1` MAC | `cni0` (Pods' gateway) |
|---|---|---|---|---|
| np-server | 172.19.0.2 | 10.42.0.0/32 | 86:23:34:44:77:e6 | 10.42.0.1/24 |
| np-agent | 172.19.0.3 | 10.42.1.0/32 | aa:14:56:99:38:06 | 10.42.1.1/24 |
| np-agent2 | 172.19.0.4 | 10.42.2.0/32 | 5a:f9:4e:d2:7c:55 | 10.42.2.1/24 (created with its first Pod) |

`flannel.1` is a VXLAN device: `vxlan id 1 local <eth0 IP> dev eth0 dstport 8472` on every node.

**Cross-node routing isn't done by iptables.** It is three kernel tables on each node, all written
by flannel (embedded in k3s) from the other nodes' annotations above. np-server's:

```text
$ ip route                                      ① which Pod subnet is where
10.42.0.0/24 dev cni0 proto kernel scope link src 10.42.0.1      ← my Pods: straight onto the bridge
10.42.1.0/24 via 10.42.1.0 dev flannel.1 onlink                  ← np-agent's Pods: "gateway" = np-agent's VTEP IP
10.42.2.0/24 via 10.42.2.0 dev flannel.1 onlink                  ← np-agent2's Pods

$ ip neigh show dev flannel.1                   ② that gateway IP → the remote VTEP's MAC (no ARP needed)
10.42.1.0 lladdr aa:14:56:99:38:06 PERMANENT
10.42.2.0 lladdr 5a:f9:4e:d2:7c:55 PERMANENT

$ bridge fdb show dev flannel.1                 ③ that MAC → the node IP to send the UDP packet to
aa:14:56:99:38:06 dst 172.19.0.3 self permanent
5a:f9:4e:d2:7c:55 dst 172.19.0.4 self permanent
```

(`bridge` here is iproute2's tool, run from a `nicolaka/netshoot` container sharing the node's
network namespace: `docker run --rm --network container:k3s-np-server nicolaka/netshoot:v0.13
bridge fdb show dev flannel.1`. Inside the k3s image, `bridge` is the CNI bridge plugin.)

All three nodes, side by side:

| On node | route to 10.42.0.0/24 | route to 10.42.1.0/24 | route to 10.42.2.0/24 | fdb entries |
|---|---|---|---|---|
| np-server | `cni0` (local) | via 10.42.1.0 `flannel.1` | via 10.42.2.0 `flannel.1` | aa:14… → .3, 5a:f9… → .4 |
| np-agent | via 10.42.0.0 `flannel.1` | `cni0` (local) | via 10.42.2.0 `flannel.1` | 86:23… → .2, 5a:f9… → .4 |
| np-agent2 | via 10.42.0.0 `flannel.1` | via 10.42.1.0 `flannel.1` | `cni0` (local) | 86:23… → .2, aa:14… → .3 |

Every node has a direct entry for every other node, a full mesh. Adding a node adds one route, one
neighbour and one fdb entry on each existing node.

```text
                         np-server 172.19.0.2
                         Pods 10.42.0.0/24
                        ╱                    ╲
         VXLAN UDP 8472                        VXLAN UDP 8472
                      ╱                          ╲
   np-agent 172.19.0.3 ─────── VXLAN UDP 8472 ─────── np-agent2 172.19.0.4
   Pods 10.42.1.0/24                                  Pods 10.42.2.0/24
```

**One packet, Pod `a` (10.42.0.8, np-server) → Pod `c` (10.42.2.2, np-agent2):**

```text
np-server
  a's eth0 ──► cni0                      src 10.42.0.8  dst 10.42.2.2
  ① ip route: 10.42.2.0/24 via 10.42.2.0 dev flannel.1
  ② ip neigh: 10.42.2.0 → 5a:f9:4e:d2:7c:55       (inner Ethernet dst MAC)
  ③ fdb:      5a:f9:4e:d2:7c:55 → 172.19.0.4       (outer IP dst)
  flannel.1 wraps it: outer 172.19.0.2:57865 → 172.19.0.4:8472  VXLAN vni 1
  ──► eth0 ──► Docker network ──►
np-agent2
  eth0 ─► UDP 8472 ─► flannel.1 unwraps ─► inner src 10.42.0.8 dst 10.42.2.2
  ip route: 10.42.2.0/24 dev cni0 ──► c's eth0
  c's whoami: RemoteAddr: 10.42.0.8:46424          ← unchanged
```

Captured on all three nodes at once (`tcpdump -T vxlan udp port 8472` on `eth0`, plain on `cni0`):

| Where | Saw |
|---|---|
| np-server `cni0` | `10.42.0.8.46424 > 10.42.2.2.8080: Flags [S]` and the `[S.]` reply |
| np-server `eth0` | `172.19.0.2.57865 > 172.19.0.4.8472: VXLAN vni 1` wrapping `10.42.0.8 > 10.42.2.2.8080` |
| **np-agent `eth0`** | **0 VXLAN packets**: the third node isn't involved at all |
| np-agent2 `eth0` | the same VXLAN packets, and the reply `172.19.0.4 > 172.19.0.2.8472` |
| np-agent2 `cni0` | `10.42.0.8.46424 > 10.42.2.2.8080`, same addresses as on np-server |

**What iptables does on the way.** The routing above needs no iptables rule. iptables only decides
whether the forwarded packet is allowed, and whether it is NATed. Rule counters on the sender
and the receiver, before and after 5 requests from `a` to `c` (namespace `lab3`, no policies):

```text
filter FORWARD (same on both nodes)                                    np-server   np-agent2
-A FORWARD … "kube-router netpol" -j KUBE-ROUTER-FORWARD                 +52         +52     ← every packet, both ways; all but the SYNs are ACCEPTed in the Pod's chain (ESTABLISHED)
-A FORWARD --ctstate NEW … -j KUBE-PROXY-FIREWALL                        +5          +5      ← only the 5 SYNs get this far
-A FORWARD … -j KUBE-FORWARD                                              +5          +5
-A FORWARD --ctstate NEW … -j KUBE-SERVICES                              +5          +5
-A FORWARD --ctstate NEW … -j KUBE-EXTERNAL-SERVICES                     +5          +5
-A FORWARD -m mark --mark 0x20000/0x20000 -j ACCEPT                      +5          +5      ← kube-router: "allowed by policy" (none apply)
-A FORWARD … "flanneld forward" -j FLANNEL-FWD                            0           0      ← never reached
-A FLANNEL-FWD -s 10.42.0.0/16 -j ACCEPT
-A FLANNEL-FWD -d 10.42.0.0/16 -j ACCEPT

nat FLANNEL-POSTRTG (first packet of each connection only)
np-server:  -s 10.42.0.0/24 -d 10.42.0.0/16 -j RETURN      ②  +6   ← my Pod → any Pod: no NAT (5 + one other local connection)
np-agent2:  -s 10.42.0.0/16 -d 10.42.2.0/24 -j RETURN      ③  +5   ← any Pod → my Pod: no NAT
MASQUERADE rules                                                0 on both
```

- `FLANNEL-FWD` is flannel's fallback for hosts whose FORWARD policy is DROP, as Docker sets it on
  hosts that run Docker. **(flannel docs)** On these nodes the policy is `-P FORWARD ACCEPT`
  anyway, and kube-router's `0x20000` ACCEPT comes first, so flannel's rules stay at 0. **(lab)**
- The iptables rules are **the same on all three nodes** except for the node's own `/24` in
  `FLANNEL-POSTRTG`, and they use `10.42.0.0/16` for "any Pod". np-server's rules were identical
  before and after np-agent2 joined. Adding a node changes routes, neighbours and fdb entries, not
  iptables.

For the single-node floci-az cluster's routes, see
[azure-local-lab.md](azure-local-lab.md#where-the-clusters-routes-come-from).

### What the rule doesn't cover

- **Services do NAT, on top of the model.** A ClusterIP is DNATed to a Pod IP
  ([kubernetes-services.md](kubernetes-services.md)). The model is about Pod IP → Pod IP; Services
  are an extra layer. With ClusterIP → Pod, the source normally stays the client Pod's IP. NodePort
  with `externalTrafficPolicy: Cluster` and hairpin traffic also SNAT, which the NodePort lab
  showed (`10.42.0.1`).
- **Traffic leaving the cluster is NATed.** Pod → internet is masqueraded to the node IP; the
  model doesn't forbid that.
- **The node can also reach its own Pods.** The model's other rule is that agents on a node, like
  the kubelet, can reach all Pods on that node. That's how probes work.

On the lab (conntrack on np-server, one entry per line, original tuple then reply tuple):

```text
# frontend → CoreDNS via the kube-dns ClusterIP: destination DNATed, source kept
udp  src=10.42.0.4 dst=10.43.0.10 sport=57113 dport=53   src=10.42.0.3 dst=10.42.0.4 sport=53 dport=57113
                                                         └ reply comes from the CoreDNS Pod, to frontend's own IP

# frontend → example.com: source masqueraded to the node (FLANNEL-POSTRTG ⑤)
tcp  src=10.42.0.4 dst=104.20.23.154 sport=44594 dport=80   src=104.20.23.154 dst=172.19.0.2 sport=80 dport=48544
                                                            └ the server replies to np-server's IP, and to a new port (--random-fully)
```

And payments/api → `backend.shop.svc.cluster.local` (ClusterIP, other namespace, other node) shows
`RemoteAddr: 10.42.0.6`, api's own IP.

### Why it matters

- **A Pod's IP is its identity on the network.** NetworkPolicy's `podSelector` works by matching
  the source IP to a Pod. If the source were rewritten to a node IP, the policy couldn't tell Pods
  apart. That's also why SNATed NodePort traffic can stop matching `ipBlock` rules (§4).
- **Apps can announce their own address.** Kafka, Cassandra and etcd peers tell others "reach me
  at my IP". That only works if the IP a Pod sees on its own interface is the one others can use.
- **Logs and traces show real senders,** not a node IP shared by dozens of Pods.

## 1. How it works (docs)

1. **A policy selects Pods** with `podSelector`, in its own namespace. `podSelector: {}` selects
   every Pod in the namespace.
2. **Selected Pods become deny-by-default, but only in the directions the policy lists**
   (`policyTypes: Ingress`, `Egress` or both). Pods that no policy selects stay fully open.
3. **Rules only allow.** Standard NetworkPolicy has no deny rule. Isolating a Pod denies
   everything, and each rule then adds something back.
4. **Policies add up.** If several policies select a Pod, traffic is allowed when any one of them
   allows it. Order doesn't matter, and no policy can override another.
5. **Both ends must allow it.** For A → B, A's egress (if A is isolated for egress) and B's ingress
   (if B is isolated for ingress) must each allow the connection. Allowing A → B does **not** let B start a
   connection to A (§3.1).
6. **Replies are always allowed.** Enforcement uses connection tracking
   ([conntrack.md](conntrack.md)), so only the side that starts the connection needs a rule.

```text
                      A (frontend)                              B (backend)
                ┌───────────────────────┐                 ┌───────────────────────┐
  connection →  │ egress check          │ ──────────────► │ ingress check         │
                │ (only if A is         │                 │ (only if B is         │
                │  isolated for egress) │                 │  isolated for ingress)│
                └───────────────────────┘                 └───────────────────────┘
  reply ←       conntrack: ESTABLISHED, no rule needed in either direction
```

## 2. What a rule can match (docs)

| Field | Matches |
|---|---|
| `podSelector` | Pods with these labels, in the policy's own namespace |
| `namespaceSelector` | every Pod in namespaces with these labels |
| `namespaceSelector` + `podSelector` **in the same entry** | Pods with these labels in those namespaces (both must match) |
| `ipBlock` | CIDRs, with `except`. Meant for traffic from outside the cluster |
| `ports` | protocol and port (number or the container port's name), or a range with `endPort` |

The "same entry" rule is the most common mistake. One `-` too many changes AND into OR:

```yaml
ingress:
- from:
  - namespaceSelector: { matchLabels: { team: shop } }   # one entry with two selectors:
    podSelector:       { matchLabels: { app: api } }     #   app=api Pods IN team=shop namespaces
---
ingress:
- from:
  - namespaceSelector: { matchLabels: { team: shop } }   # two entries:
  - podSelector:       { matchLabels: { app: api } }     #   ANY Pod in team=shop namespaces,
                                                         #   OR app=api Pods in this namespace
```

**Tested (lab).** Namespace `payments` has the label `team: pay` and two Pods, `api`
(`app: api`) and `worker` (`app: worker`). An ingress policy on `shop/backend`, port 8080:

```yaml
# AND: one entry with two selectors
  - from:
    - namespaceSelector: { matchLabels: { team: pay } }
      podSelector:       { matchLabels: { app: api } }
# OR: the same two selectors as separate entries
  - from:
    - namespaceSelector: { matchLabels: { team: pay } }
    - podSelector:       { matchLabels: { app: api } }
```

| Client | AND | OR |
|---|---|---|
| `payments/api` | OK (`RemoteAddr: 10.42.0.6`) | OK |
| `payments/worker` | **Connection refused** | OK (`RemoteAddr: 10.42.0.7`): any Pod in `team: pay` namespaces |

Every namespace has the label `kubernetes.io/metadata.name: <name>` set automatically, so you can
select a namespace by name without labelling it yourself.

## 3. A typical setup (lab)

The usual pattern: deny everything in the namespace, let DNS through, then allow each flow
explicitly.

```yaml
# 1. Deny all ingress and egress for every Pod in the namespace
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata: { name: default-deny, namespace: shop }
spec:
  podSelector: {}
  policyTypes: [Ingress, Egress]
---
# 2. Let every Pod resolve DNS (forgetting this breaks everything after step 1)
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata: { name: allow-dns, namespace: shop }
spec:
  podSelector: {}
  policyTypes: [Egress]
  egress:
  - to:
    - namespaceSelector: { matchLabels: { kubernetes.io/metadata.name: kube-system } }
      podSelector: { matchLabels: { k8s-app: kube-dns } }
    ports: [{ protocol: UDP, port: 53 }, { protocol: TCP, port: 53 }]
---
# 3. frontend → backend on 8080 (needs ingress on backend and egress on frontend)
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata: { name: backend-from-frontend, namespace: shop }
spec:
  podSelector: { matchLabels: { app: backend } }
  policyTypes: [Ingress]
  ingress:
  - from: [{ podSelector: { matchLabels: { app: frontend } } }]
    ports: [{ protocol: TCP, port: 8080 }]
---
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata: { name: frontend-to-backend, namespace: shop }
spec:
  podSelector: { matchLabels: { app: frontend } }
  policyTypes: [Egress]
  egress:
  - to: [{ podSelector: { matchLabels: { app: backend } } }]
    ports: [{ protocol: TCP, port: 8080 }]
```

Applied one at a time on the lab, testing after each step (`wget -T 3`, `nslookup`). frontend
(`10.42.0.4`) and other (`10.42.0.5`) are in `shop` on np-server, backend (`10.42.1.3`, port 8080,
Service `backend` port 80 → 8080) on np-agent, api (`10.42.0.6`) in `payments`:

| Test | No policy | + default-deny | + allow-dns | + backend-from-frontend | + frontend-to-backend |
|---|---|---|---|---|---|
| frontend DNS lookup | OK | refused | **OK** | OK | OK |
| frontend → backend Pod IP :8080 | OK | refused | refused | refused (frontend's egress still denied) | **OK**, `RemoteAddr: 10.42.0.4` |
| frontend → Service `backend` :80 | OK | refused | refused | refused | **OK** (DNAT to :8080 first) |
| other (shop, no label) → backend | OK | refused | refused | refused | refused |
| payments/api → backend | OK | refused | refused | refused | refused |
| frontend → example.com | OK | DNS fails | refused | refused | refused |

With all four in place (lab):

| From → to | Result | Because |
|---|---|---|
| frontend → backend:8080 | OK | frontend's egress and backend's ingress both allow it |
| backend → frontend:8080 (busybox `httpd` listening) | refused | backend has no egress rule to frontend, and frontend has no ingress rule |
| backend → 1.1.1.1:80 | refused | default-deny egress, nothing adds it back |
| any `shop` Pod → CoreDNS:53 | OK | allow-dns; `kube-system` has no policy, so CoreDNS's ingress is open |
| another namespace → backend | refused | backend's ingress only allows frontend Pods in `shop` |

The "+ backend-from-frontend" column shows **both ends must allow it**: backend's ingress already
allows frontend, but frontend is still isolated for egress, so the connection fails until
frontend-to-backend is added. Each node checks only its own Pods: frontend's egress is checked on
np-server, backend's ingress on np-agent.

**Blocked looks like "Connection refused" with kube-router, not a timeout.** It ends each Pod's
chain with `REJECT --reject-with icmp-port-unreachable`, so the client gets the same error as for a
closed port. To tell them apart, check the REJECT rule's counters
(`iptables -L -v -n | grep "REJECT.*<pod>"`) or the target Pod's own listener. Calico and Cilium
drop by default, so there you'd see a timeout instead. **(Calico/Cilium: docs)**

The DNS rule names the CoreDNS **Pods**, not the `kube-dns` Service IP. That is the second catch
in §4.

### 3.1 Allowing A → B doesn't let B start a connection to A (lab)

A rule covers connections in **one direction**: the side that starts the connection, plus the
replies on that same connection. If B starts its own connection to A, that's a new connection,
checked against **B's egress** (if B is isolated for egress) and **A's ingress** (if A is isolated
for ingress). If neither is isolated, B → A works anyway, because the default is open.

Lab, namespace `lab3`, each Pod running a web server on 8080 (busybox `httpd`, or whoami for c):

| Pod | IP | Node |
|---|---|---|
| a | 10.42.0.8 | np-server |
| d | 10.42.0.9 | np-server |
| b | 10.42.1.4 | np-agent |
| c | 10.42.2.2 | np-agent2 |

```yaml
# c-from-a: c accepts a on 8080 (c becomes isolated for ingress)
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata: { name: c-from-a, namespace: lab3 }
spec:
  podSelector: { matchLabels: { app: c } }
  policyTypes: [Ingress]
  ingress:
  - from: [{ podSelector: { matchLabels: { app: a } } }]
    ports: [{ protocol: TCP, port: 8080 }]
---
# default-deny-ingress: every Pod in lab3 (a included) becomes isolated for ingress
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata: { name: default-deny-ingress, namespace: lab3 }
spec:
  podSelector: {}
  policyTypes: [Ingress]
---
# a-to-c: a may only start connections to c on 8080 (a becomes isolated for egress)
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata: { name: a-to-c, namespace: lab3 }
spec:
  podSelector: { matchLabels: { app: a } }
  policyTypes: [Egress]
  egress:
  - to: [{ podSelector: { matchLabels: { app: c } } }]
    ports: [{ protocol: TCP, port: 8080 }]
```

| Policies applied | a → c | c → a (c starts it) | Why c → a gets that result |
|---|---|---|---|
| `c-from-a` | OK, `RemoteAddr: 10.42.0.8` | **OK** (`hello-from-a`) | a isn't isolated for ingress and c isn't isolated for egress, so the open default applies |
| `c-from-a` + `default-deny-ingress` | OK | **Connection refused** | a is now isolated for ingress, and no rule lets c in |
| `a-to-c` only | OK | **OK** | a's egress rule doesn't affect connections coming *into* a |

The replies to a → c need no rule because they belong to a's connection. conntrack holds one entry
with both directions, and kube-router accepts the replies through `--ctstate RELATED,ESTABLISHED`:

```text
tcp 6 119 TIME_WAIT src=10.42.0.8 dst=10.42.2.2 sport=36592 dport=8080   src=10.42.2.2 dst=10.42.0.8 sport=8080 dport=36592 [ASSURED]
                    └ a → c (original)                                   └ c → a (reply on the same connection)
```

To allow both directions, write both: for example `c-from-a` plus `a-from-c` (ingress on a,
from c), plus the matching egress rules if either Pod is isolated for egress.

### 3.2 ipBlock: one IP, a subnet, a range with exceptions (lab)

`ipBlock` matches addresses instead of labels. `cidr` is the range, and `except` removes smaller
ranges from it. `/32` is a single IPv4 address.

The docs say `ipBlock` is meant for addresses **outside** the cluster, because Pod IPs change when
Pods are recreated, and whether it applies to Pod IPs at all depends on the implementation.
kube-router applies it to Pod IPs, which makes the ingress cases below easy to show. In real
policies, select Pods with `podSelector`/`namespaceSelector` instead. **(docs; kube-router
behaviour: lab)**

**Ingress (who may connect to c):** c-from-ipblock replaced each time; clients a, d (np-server) and
b (np-agent):

```yaml
# i1: one address
  ingress:
  - from: [{ ipBlock: { cidr: 10.42.0.8/32 } }]        # exactly one address: Pod a
    ports: [{ protocol: TCP, port: 8080 }]
# i2: a subnet
  - from: [{ ipBlock: { cidr: 10.42.0.0/24 } }]        # np-server's whole Pod subnet
# i3: a range minus one address
  - from:
    - ipBlock:
        cidr: 10.42.0.0/16                             # every Pod in the cluster…
        except: [10.42.0.9/32]                         # …except Pod d
```

(Each is a full `NetworkPolicy` with `podSelector: { matchLabels: { app: c } }` and
`policyTypes: [Ingress]`, like `c-from-a` above.)

| Policy | a (10.42.0.8) → c | d (10.42.0.9) → c | b (10.42.1.4) → c |
|---|---|---|---|
| i1 `10.42.0.8/32` | **OK** | refused | refused |
| i2 `10.42.0.0/24` | **OK** | **OK** | refused (np-agent's subnet) |
| i3 `10.42.0.0/16` except `10.42.0.9/32` | **OK** | refused | **OK** |

**Egress (where a may connect):** a-egress-ipblock replaced each time; `1.1.1.1` and `1.0.0.1` are
public addresses that answer on port 80 (checked with no policy first):

```yaml
# e1: one external address
  egress:
  - to: [{ ipBlock: { cidr: 1.1.1.1/32 } }]
    ports: [{ protocol: TCP, port: 80 }]
# e2: a range, 1.0.0.0 – 1.1.255.255
  - to: [{ ipBlock: { cidr: 1.0.0.0/15 } }]
    ports: [{ protocol: TCP, port: 80 }]
# e3: the range minus one address
  - to:
    - ipBlock:
        cidr: 1.0.0.0/15
        except: [1.1.1.1/32]
    ports: [{ protocol: TCP, port: 80 }]
# e4: "the internet, but nothing inside the cluster" (any port)
  - to:
    - ipBlock:
        cidr: 0.0.0.0/0
        except: [10.42.0.0/16, 10.43.0.0/16, 172.19.0.0/16]   # Pods, Services, node network
```

(Each is a full `NetworkPolicy` with `podSelector: { matchLabels: { app: a } }` and
`policyTypes: [Egress]`.)

| Policy | a → 1.1.1.1:80 | a → 1.0.0.1:80 | a → c (Pod, 10.42.2.2:8080) | a → b (Pod, 10.42.1.4:8080) |
|---|---|---|---|---|
| e1 `1.1.1.1/32` | **OK** | refused | refused | — |
| e2 `1.0.0.0/15` | **OK** | **OK** | refused | — |
| e3 `1.0.0.0/15` except `1.1.1.1/32` | refused | **OK** | — | — |
| e4 `0.0.0.0/0` except cluster ranges | **OK** | **OK** | refused | refused |

Notes from the run:

- A blocked external address fails in about a second with `Connection refused`, not a timeout,
  because kube-router REJECTs (§3).
- Egress `ipBlock` sees the real destination and the Pod's own source. The check runs in FORWARD,
  before flannel's MASQUERADE in POSTROUTING (§0) changes the source to the node IP.
- **None of these allow DNS.** The tests used IP addresses. Even e4, "the whole internet", breaks
  name lookups: under e4, `nslookup` from a failed with `write to '10.43.0.10': Connection refused`
  (with no policy it resolved). The lookup goes to the kube-dns ClusterIP, gets DNATed to CoreDNS's
  **Pod** IP, and that is inside the excluded `10.42.0.0/16`. Add the `allow-dns` rule from §3 to
  any egress allow-list.
- `ipBlock` can't target a Service's ClusterIP (§4), because the check sees the Pod IP after DNAT.
  e4's `10.43.0.0/16` exception therefore probably changes nothing. **(not tested on its own)**

## 4. Things that catch people out

- **The API server stores policies but doesn't enforce them.** The CNI or a policy agent does that
  (Calico, Cilium, or kube-router in k3s). With plain flannel, policies are accepted but nothing is
  blocked, and nothing warns you. **(docs)**
- **Rules see Pod IPs and Pod ports, not Service IPs.** Policy is checked after kube-proxy's DNAT,
  so allow the `targetPort` (8080), not the Service port (80). You can't target a ClusterIP.
  **(lab)**: changing both §3 policies to `port: 80` made frontend → `backend.shop.svc` (port 80)
  fail with "Connection refused"; back to `8080`, it worked again.
- **Source NAT hides the client, but only sometimes from the policy.** With a NodePort and
  `externalTrafficPolicy: Cluster`, the Pod sees the source as a node address, not the client
  (NodePort lab, [kubernetes-services.md](kubernetes-services.md#a-nodeport-packet-rule-by-rule-and-hook-by-hook-lab-k3s),
  part 10). Whether an `ipBlock` for the client still matches depends on **where** the SNAT happens
  relative to the policy check. **(lab)**, client `172.19.0.4`, policy
  `ipBlock: 172.19.0.0/16` on backend (np-agent), Service NodePort 30080:

  | Request to | ETP | Pod sees | Policy result | Why |
  |---|---|---|---|---|
  | np-server:30080 | Cluster | — | **refused** | np-server SNATs to its `flannel.1` address `10.42.0.0` before the packet leaves (kube-proxy's `KUBE-POSTROUTING` MASQUERADE; conntrack on np-server: reply tuple `dst=10.42.0.0`); np-agent checks a source of `10.42.0.0`, which isn't in the block |
  | np-agent:30080 | Cluster | `10.42.1.1` | **allowed** | the check runs in FORWARD, *before* POSTROUTING's SNAT on the same node, so kube-router still sees `172.19.0.4`. The app sees `10.42.1.1` anyway. (Without the policy: refused, so the ipBlock is what allowed it) |
  | np-agent:30080 | Local | `172.19.0.4` | **allowed** | no SNAT at all |
  | np-server:30080 | Local | — | timed out | np-server has no backend Pod, so kube-proxy drops it; not the policy |

  Use `externalTrafficPolicy: Local` (and send traffic only to nodes with endpoints) if a policy
  or the app needs the client IP.
- **Pods with `hostNetwork: true` and traffic from the node itself** are handled inconsistently
  from one implementation to another. Most let traffic from the Pod's own node through, so kubelet
  probes keep working. **(lab, kube-router)** under default-deny:

  | From | To backend (`10.42.1.3`, np-agent) | Why |
  |---|---|---|
  | np-agent itself (`wget` on the node) | OK, `RemoteAddr: 10.42.1.1` | backend's chain has `-m addrtype --src-type LOCAL -j ACCEPT`: anything from the Pod's own node |
  | np-server (the other node) | refused | arrives as `10.42.0.0` (np-server's `flannel.1`), not a local address on np-agent |
- **It only covers layers 3 and 4,** meaning IPs and ports. Rules on HTTP paths, DNS names or deny
  ordering need Cilium's or Calico's own CRDs. AdminNetworkPolicy, a newer API for cluster-wide,
  ordered rules with deny, comes from the sig-network policy-api project as CRDs. It is still
  alpha, and few CNIs implement it yet. **(docs)**
- **There is no logging** in the standard API. You can't see what was dropped without the CNI's
  tools (Hubble, Calico flow logs). kube-router sends rejected packets to
  `NFLOG --nflog-group 100` (rate-limited to 10/min), with a prefix like
  `"DROP by policy shop/default-deny"`, which you can read with a tool that listens on that group
  (e.g. `tcpdump -i nflog:100`). **(docs; NFLOG rules seen in the lab, reading them not tested)**

## 5. Who enforces it

| Implementation | NetworkPolicy? | How it enforces |
|---|---|---|
| **flannel alone** | no | stored, never applied |
| **k3s** (flannel + embedded kube-router controller) | yes | iptables chains `KUBE-ROUTER-*`, hooked from the filter table |
| **Calico** | yes, plus its own `GlobalNetworkPolicy`, explicit Deny, `HostEndpoint` | iptables, nftables or eBPF on each node; can also run policy-only on top of flannel (Canal), Azure CNI or AWS VPC CNI |
| **Cilium** | yes, plus L7 (HTTP, DNS names) in `CiliumNetworkPolicy` | eBPF programs on each Pod's interface; `iptables -L` shows almost nothing |
| **Cloud CNIs** | depends | AKS: `--network-policy azure`, `calico` or `cilium`; EKS: VPC CNI's network policy agent, or Calico |

Enforcing a policy means turning it into rules on each node. iptables and nftables implementations
add chains to the **filter** table, matching Pod IPs, often grouped in ipsets. Cilium does the check
in eBPF instead.

### How kube-router does it (lab, k3s v1.34.1+k3s1, np-agent)

Even with **no** policies, every Pod gets a chain, hooked from the filter table's INPUT, FORWARD
and OUTPUT:

```text
-A FORWARD -m comment --comment "kube-router netpol - …" -j KUBE-ROUTER-FORWARD
-A FORWARD -m mark --mark 0x20000/0x20000 -j ACCEPT                     ← "explicitly ACCEPT traffic that comply to network policies"
-A KUBE-ROUTER-FORWARD -d 10.42.1.3/32 … "to POD name:backend namespace: shop" -j KUBE-POD-FW-…
-A KUBE-ROUTER-FORWARD -s 10.42.1.3/32 … "from POD name:backend namespace: shop" -j KUBE-POD-FW-…
   (plus the same with -m physdev --physdev-is-bridged, for Pod → Pod on the same bridge)
```

Only Pods on **this** node get these jumps: np-agent has chains for backend and
local-path-provisioner, not for frontend.

Before any policy, backend's chain runs everything through `KUBE-NWPLCY-DEFAULT`, which just sets
mark `0x10000` ("matches a policy"), so nothing is rejected. After the four §3 policies (comments
shortened):

```text
-A KUBE-POD-FW-… -m conntrack --ctstate RELATED,ESTABLISHED -j ACCEPT          ← replies (§1 point 6)
-A KUBE-POD-FW-… -m conntrack --ctstate INVALID -j DROP
-A KUBE-POD-FW-… -d 10.42.1.3/32 -m addrtype --src-type LOCAL -j ACCEPT        ← from this node (§4)
-A KUBE-POD-FW-… -d 10.42.1.3/32 "run through nw policy backend-from-frontend" -j KUBE-NWPLCY-NOH7…   ← ingress
-A KUBE-POD-FW-… -s 10.42.1.3/32 "run through nw policy allow-dns"             -j KUBE-NWPLCY-FUBF…   ← egress
-A KUBE-POD-FW-…                 "run through nw policy default-deny"          -j KUBE-NWPLCY-TPIM…   ← no rules: only NFLOG
-A KUBE-POD-FW-… -m mark ! --mark 0x10000/0x10000 -m limit --limit 10/min -j NFLOG --nflog-group 100
-A KUBE-POD-FW-… -m mark ! --mark 0x10000/0x10000 -j REJECT --reject-with icmp-port-unreachable
-A KUBE-POD-FW-… -j MARK --set-xmark 0x0/0x10000                               ← clear "matched"
-A KUBE-POD-FW-… -j MARK --set-xmark 0x20000/0x20000                           ← "accept" → FORWARD's ACCEPT rule
```

Each policy chain sets `0x10000` when a rule matches. Pod sets are ipsets, filled with the IPs of
the Pods the selectors pick:

```text
-A KUBE-NWPLCY-NOH7… -p tcp "policy name backend-from-frontend namespace shop"
     -m set --match-set KUBE-SRC-NVKP… src -m set --match-set KUBE-DST-FSBT… dst --dport 8080
     -j MARK --set-xmark 0x10000/0x10000

ipset KUBE-SRC-NVKP…   10.42.0.4          ← podSelector app=frontend
ipset KUBE-DST-FSBT…   10.42.1.3          ← the Pods the policy selects (app=backend)
ipset KUBE-DST-2U5Z…   10.42.0.3          ← allow-dns's destination: CoreDNS
```

That is the "policies add up" rule in iptables form: every policy that selects the Pod gets a jump,
any of them can set `0x10000`, and only if none did does the REJECT fire. Order doesn't matter.

Counting filter rules matching `KUBE-ROUTER|KUBE-POD-FW|KUBE-NWPLCY`, deleting and re-applying the
four policies: np-agent 51 → 68, np-server 119 → 136, so +17 on each node, plus 8 ipsets. The
policy chains exist on every node; the per-Pod chains only where the Pods run. Pods that match a
selector are listed in the ipsets, so a new matching Pod should only change an ipset, not the
rules. **(that last part: not tested)**

## 6. The lab setup

A throwaway k3s cluster (two nodes, later three) on its own Docker network, Traefik and ServiceLB off:

```bash
docker network create k3s-np
docker run -d --name k3s-np-server --hostname np-server --privileged --network k3s-np \
  --tmpfs /run --tmpfs /var/run rancher/k3s:latest \
  server --disable=traefik --disable=servicelb --token np-lab-token
docker run -d --name k3s-np-agent --hostname np-agent --privileged --network k3s-np \
  --tmpfs /run --tmpfs /var/run -e K3S_URL=https://k3s-np-server:6443 -e K3S_TOKEN=np-lab-token \
  rancher/k3s:latest agent
# third node, for the cross-node routing tables in §0
docker run -d --name k3s-np-agent2 --hostname np-agent2 --privileged --network k3s-np \
  --tmpfs /run --tmpfs /var/run -e K3S_URL=https://k3s-np-server:6443 -e K3S_TOKEN=np-lab-token \
  rancher/k3s:latest agent
docker exec k3s-np-server kubectl get nodes -o wide
# np-agent    Ready  <none>         172.19.0.3  v1.34.1+k3s1
# np-agent2   Ready  <none>         172.19.0.4  v1.34.1+k3s1
# np-server   Ready  control-plane  172.19.0.2  v1.34.1+k3s1
```

Workloads (Pods pinned with `nodeName` so frontend and backend are on different nodes):

```yaml
apiVersion: v1
kind: Namespace
metadata: { name: shop }
---
apiVersion: v1
kind: Namespace
metadata: { name: payments, labels: { team: pay } }
---
apiVersion: v1
kind: Pod
metadata: { name: backend, namespace: shop, labels: { app: backend } }
spec:
  nodeName: np-agent
  containers:
  - name: whoami
    image: traefik/whoami:v1.10          # replies with the RemoteAddr it sees
    args: ["--port", "8080"]
---
apiVersion: v1
kind: Service
metadata: { name: backend, namespace: shop }
spec:
  selector: { app: backend }
  ports: [{ port: 80, targetPort: 8080 }]
---
apiVersion: v1
kind: Pod
metadata: { name: frontend, namespace: shop, labels: { app: frontend } }
spec:
  nodeName: np-server
  containers: [{ name: c, image: busybox:1.36, command: ["sleep", "1d"] }]
# + shop/other (app: other), payments/api (app: api), payments/worker (app: worker): same busybox Pod
```

Tools used: `kubectl exec … wget -qO- -T 3`, `nslookup`; a `kubectl debug` ephemeral busybox
container in backend for its egress; busybox `httpd -p 8080` in frontend as a listener; a
hostNetwork `nicolaka/netshoot` Pod on np-agent for `tcpdump`; `conntrack -L` on the nodes; an
`alpine` container on the `k3s-np` network as the NodePort client.

Clean up:

```bash
docker rm -f k3s-np-server k3s-np-agent k3s-np-agent2 && docker network rm k3s-np
```

## Related notes

- [kubernetes-services.md](kubernetes-services.md): kube-proxy's DNAT and SNAT, which decide what
  addresses a policy sees
- [conntrack.md](conntrack.md): why replies need no rule
- [iptables.md](iptables.md) and [nftables.md](nftables.md): the filter-table hooks policies are enforced in
- [azure-local-lab.md](azure-local-lab.md): k3s vs upstream Kubernetes, and k3s's bundled policy controller
- [keycloak-on-kubernetes.md](keycloak-on-kubernetes.md): a real operator-generated NetworkPolicy (§7)

## Sources

- Kubernetes docs, Network Policies: https://kubernetes.io/docs/concepts/services-networking/network-policies/
- Kubernetes docs, Declare Network Policy: https://kubernetes.io/docs/tasks/administer-cluster/declare-network-policy/
- Network Policy API (AdminNetworkPolicy), sig-network: https://network-policy-api.sigs.k8s.io/
- k3s, packaged components (embedded network policy controller): https://docs.k3s.io/installation/packaged-components
- Calico docs, network policy: https://docs.tigera.io/calico/latest/network-policy/
- Cilium docs, network policy: https://docs.cilium.io/en/stable/security/policy/
- Kubernetes docs, the Kubernetes network model: https://kubernetes.io/docs/concepts/services-networking/#the-kubernetes-network-model
- flannel backends (VXLAN, host-gw) and ip-masq: https://github.com/flannel-io/flannel/blob/master/Documentation/backends.md
