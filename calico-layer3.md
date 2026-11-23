# Calico in Layer 3 mode: BGP routes, no bridge, no tunnel

With flannel (see [kube-networking.md](kube-networking.md), "The bridge model"), Pods on a node sit
on a shared Linux bridge, and traffic to other nodes is wrapped in VXLAN. Calico in its **Layer 3
mode** does neither:

- **No bridge.** Each Pod's veth is a separate interface on the node, and the node *routes* to
  each Pod with its own `/32` route.
- **No tunnel.** Nodes tell each other where their Pods are with **BGP**, and packets travel
  between nodes as plain IP, Pod address to Pod address.

Everything below was run on a three-node k3s cluster in Docker, with flannel switched off and
Calico v3.29.1 installed with encapsulation disabled. **(lab)** marks what was run and captured,
**(docs)** what comes from Calico's or the kernel's documentation.

---

## 0. The lab setup

Three k3s nodes on their own Docker network (`172.21.0.0/16`), flannel and its network-policy
controller off, Pod CIDR `10.244.0.0/16`:

```bash
docker network create k3s-cal
docker run -d --name cal-server --hostname cal-server --privileged --network k3s-cal \
  --tmpfs /run --tmpfs /var/run rancher/k3s:latest server \
  --flannel-backend=none --disable-network-policy --disable=traefik --disable=servicelb \
  --cluster-cidr=10.244.0.0/16 --token cal-token
for n in 1 2; do
  docker run -d --name cal-agent$n --hostname cal-agent$n --privileged --network k3s-cal \
    --tmpfs /run --tmpfs /var/run -e K3S_URL=https://cal-server:6443 -e K3S_TOKEN=cal-token \
    rancher/k3s:latest agent
done
```

The nodes stay `NotReady` until a CNI is installed. With flannel off, k3s leaves containerd's CNI
paths at the defaults (`/opt/cni/bin`, `/etc/cni/net.d`), which is where Calico's manifest
installs, so no path changes are needed.

Calico's manifest, changed in two places to get pure Layer 3 (`calico.yaml` from
`https://raw.githubusercontent.com/projectcalico/calico/v3.29.1/manifests/calico.yaml`):

```diff
             - name: CALICO_IPV4POOL_IPIP
-              value: "Always"
+              value: "Never"             # no IP-in-IP (VXLAN is already "Never")
-            # - name: CALICO_IPV4POOL_CIDR
-            #   value: "192.168.0.0/16"
+            - name: CALICO_IPV4POOL_CIDR
+              value: "10.244.0.0/16"     # match k3s's --cluster-cidr
```

One fix was needed because the nodes are containers. calico-node's init container `mount-bpffs`
mounts `/sys/fs` with `mountPropagation: Bidirectional`, and failed with:

```text
failed to generate spec: path "/sys/fs/" is mounted on "/sys" but it is not a shared mount
```

Making the node containers' mounts shared fixed it (k3d does the same):

```bash
for n in cal-server cal-agent1 cal-agent2; do docker exec $n mount --make-rshared /; done
kubectl -n kube-system delete pod -l k8s-app=calico-node     # let them retry
```

After that all three nodes went `Ready`. Test Pods:

| Pod | IP | Node |
|---|---|---|
| client (busybox) | 10.244.70.0 | cal-server (172.21.0.2) |
| web1 (whoami :8080) | 10.244.189.5 | cal-agent1 (172.21.0.3) |
| neighbour (busybox) | 10.244.189.4 | cal-agent1 |
| web2 (whoami :8080) | 10.244.127.64 | cal-agent2 (172.21.0.4) |

---

## 1. The picture

One node, cal-agent1, with two Pods:

```text
┌─ cal-agent1 (172.21.0.3): root network namespace, no bridge ─────────────────────┐
│                                                                                  │
│  ┌────────────────────────────┐   ┌────────────────────────────┐                 │
│  │ Pod web1 (own netns)       │   │ Pod neighbour (own netns)  │                 │
│  │ eth0 10.244.189.5/32       │   │ eth0 10.244.189.4/32       │                 │
│  │ default via 169.254.1.1    │   │ default via 169.254.1.1    │                 │
│  └───────────┬────────────────┘   └───────────┬────────────────┘                 │
│              │ veth pair                      │ veth pair                        │
│              │ cali9c5947ed78f                │ cali7ebe4d6074b                  │
│              │                                │ each cali veth: no IP,           │
│              │                                │ proxy_arp=1, ee:ee:ee:ee:ee:ee   │
│  ┌───────────┴────────────────────────────────┴───────────────────────────────┐  │
│  │ node routing table                                                         │  │
│  │ 10.244.189.5      dev cali9c5947ed78f         one /32 per local Pod        │  │
│  │ 10.244.189.4      dev cali7ebe4d6074b                                      │  │
│  │ blackhole 10.244.189.0/26                     this node's own block        │  │
│  │ 10.244.70.0/26    via 172.21.0.2  proto bird  learned over BGP             │  │
│  │ 10.244.127.64/26  via 172.21.0.4  proto bird                               │  │
│  │ 172.21.0.0/16     dev eth0                                                 │  │
│  └─────────────────────────────────────┬──────────────────────────────────────┘  │
│                                        │                                         │
│                                        │ eth0 172.21.0.3 (node NIC)              │
└────────────────────────────────────────┴─────────────────────────────────────────┘
```

The three nodes and how they learn each other's Pod addresses:

```text
cal-server 172.21.0.2          cal-agent1 172.21.0.3          cal-agent2 172.21.0.4
block 10.244.70.0/26           block 10.244.189.0/26          block 10.244.127.64/26

┌──────────────────────┐ BGP   ┌──────────────────────┐ BGP   ┌──────────────────────┐
│ calico-node:         ├───────┤ calico-node:         ├───────┤ calico-node:         │
│ BIRD (BGP, TCP 179)  │       │ BIRD (BGP, TCP 179)  │       │ BIRD (BGP, TCP 179)  │
└──────────┬───────────┘       └──────────────────────┘       └──────────┬───────────┘
           │                                                             │
           │                                                             │
           └────────────────────── BGP (full mesh) ──────────────────────┘

each node announces its own /26 block; the other nodes install it as
    <block> via <that node's IP> dev eth0 proto bird
```

---

## 2. Inside a Pod: a /32 address and a gateway that doesn't exist (lab)

```text
$ kubectl exec -n l3 client -- ip addr / ip route / ip neigh
eth0 10.244.70.0/32                       ← a /32: no subnet, so no other Pod is "on my link"
link/ether 96:f9:24:4c:7b:56

default via 169.254.1.1 dev eth0          ← every packet goes to the gateway…
169.254.1.1 dev eth0 scope link           ← …a link-local address nobody actually has

169.254.1.1 dev eth0 lladdr ee:ee:ee:ee:ee:ee REACHABLE    (after one ping)
```

- **The `/32` means the Pod thinks it's alone.** Even a Pod on the same node is "somewhere else",
  so every packet, to any destination, goes to the default gateway.
- **`169.254.1.1` isn't configured anywhere.** The Pod asks "who has 169.254.1.1?" (ARP), and the
  node side of the veth answers with its own MAC, `ee:ee:ee:ee:ee:ee`, because it has **proxy
  ARP** on (`proxy_arp=1`, §3). So the Pod hands every frame to the node.
- **All Pods use the same gateway address and MAC,** so nothing in the Pod changes if it moves
  between nodes. The `.0` address (`10.244.70.0`) is fine for a host here: with a `/32` there's
  no network or broadcast address to avoid.
- **MTU 1500:** without encapsulation the Pod gets the full Ethernet MTU (flannel VXLAN: 1450).

---

## 3. On the node: veths without a bridge, one route per Pod (lab)

```text
cal-agent1$ ip -o link show type bridge | wc -l
0                                                     ← no cni0, no bridge at all
cal-agent1$ ip -o link | grep cali
calid22b634f7e1  calic5ef41022dc  calif0365c1e56e  cali7ebe4d6074b  cali9c5947ed78f
cal-agent1$ (for one of them)
ipv4 addresses: 0     proxy_arp: 1     MAC: ee:ee:ee:ee:ee:ee

cal-agent1$ ip route
default via 172.21.0.1 dev eth0
10.244.70.0/26 via 172.21.0.2 dev eth0 proto bird     ← cal-server's Pods: learned over BGP
10.244.127.64/26 via 172.21.0.4 dev eth0 proto bird   ← cal-agent2's Pods: learned over BGP
10.244.189.0 dev calid22b634f7e1 scope link           ┐
10.244.189.1 dev calic5ef41022dc scope link           │ one /32 route per local Pod,
10.244.189.2 dev calif0365c1e56e scope link           │ straight to that Pod's veth
10.244.189.4 dev cali7ebe4d6074b scope link           │ (neighbour)
10.244.189.5 dev cali9c5947ed78f scope link           ┘ (web1)
blackhole 10.244.189.0/26 proto bird                  ← this node's whole block
172.21.0.0/16 dev eth0 proto kernel scope link src 172.21.0.3
```

- **Each `cali…` veth is a plain point-to-point link** into one Pod: no IP, proxy ARP on, and the
  fixed MAC `ee:ee:ee:ee:ee:ee` (the same on every veth, every node).
- **The `/32` routes** are written by the Calico CNI plugin when it sets up each Pod. The most
  specific route wins, so a packet for `10.244.189.5` goes to `cali9c5947ed78f` and nowhere else.
- **The `blackhole` route** covers the node's whole `/26` block. An address in the block that has
  no Pod (yet, or any more) matches only the blackhole and is dropped here, instead of being sent
  back out along the default route and looping. **(docs for the reason)**
- **Traffic between two Pods on the same node is routed, not switched.** `neighbour → web1` goes
  Pod → `cali7ebe4d6074b` → node routing → `cali9c5947ed78f` → Pod (§5).

### Where the /26 blocks come from: Calico IPAM (lab)

Calico doesn't use the node's `podCIDR`. Its IPAM hands each node a **block** from the pool and
records the assignment in its own resources:

```text
$ kubectl get ippools.crd.projectcalico.org
NAME                  CIDR            IPIP    VXLAN   NAT    BLOCK
default-ipv4-ippool   10.244.0.0/16   Never   Never   true   26

$ kubectl get blockaffinities.crd.projectcalico.org
NODE         CIDR               STATE
cal-server   10.244.70.0/26     confirmed
cal-agent1   10.244.189.0/26    confirmed
cal-agent2   10.244.127.64/26   confirmed
```

A `/26` is 64 addresses. A node that runs out gets another block, so a node can own several,
non-contiguous blocks. That's also why the blocks aren't in order: `.70`, `.189`, `.127`.
**(docs for the extra blocks)**

---

## 4. Between nodes: BGP (lab)

Each node's `calico-node` Pod runs **BIRD**, a BGP daemon, plus Calico's own agents:

```text
cal-agent1$ ps -o pid,args | grep -E 'calico-node|bird'
calico-node -felix                   ← programs routes, iptables rules (policy, NAT)
calico-node -confd                   ← writes BIRD's config from Calico's data
calico-node -allocate-tunnel-addrs   calico-node -monitor-addresses   calico-node -status-reporter
bird  -R -s /var/run/calico/bird.ctl  -c /etc/calico/confd/config/bird.cfg     ← BGP for IPv4
bird6 -R -s /var/run/calico/bird6.ctl -c /etc/calico/confd/config/bird6.cfg    ← BGP for IPv6
```

By default every node peers with every other node (the **node-to-node mesh**):

```text
$ kubectl -n kube-system exec <calico-node on cal-agent1> -c calico-node -- \
    birdcl -s /var/run/calico/bird.ctl show protocols
name             proto    state  info
Mesh_172_21_0_4  BGP      up     Established          ← session with cal-agent2
Mesh_172_21_0_2  BGP      up     Established          ← session with cal-server

cal-agent1$ netstat -tn | grep :179
172.21.0.3:39005  →  172.21.0.2:179   ESTABLISHED     ← BGP runs over TCP port 179
172.21.0.3:179    ←  172.21.0.4:50197 ESTABLISHED
```

What BIRD knows, and where each route came from:

```text
$ … birdcl … show route
10.244.189.0/26    blackhole [static1]                                 ← my block: announced to the peers
10.244.189.5/32    dev cali9c5947ed78f [kernel1]                       ← my Pods (from the kernel table)
…
10.244.70.0/26     via 172.21.0.2 on eth0 [Mesh_172_21_0_2] (100/0) [i]   ← received from cal-server
10.244.127.64/26   via 172.21.0.4 on eth0 [Mesh_172_21_0_4] (100/0) [i]   ← received from cal-agent2
```

1. Each node **announces its own block** (`10.244.189.0/26`), not each Pod. The `/32`s stay local.
2. Its peers receive the announcement with the **next hop = the announcing node's IP**.
3. BIRD writes the received routes into the kernel, marked **`proto bird`**: exactly the
   `10.244.70.0/26 via 172.21.0.2 … proto bird` lines in §3.

So the "network" between nodes needs no knowledge of Pods. It only has to deliver
`172.21.0.2 → 172.21.0.3`, and the nodes route the rest. That only works if the nodes can reach
each other directly at Layer 2 (as here, one Docker network) or the network in between also
learns the Pod routes (§8).

The list of peers comes from the Kubernetes API (Node objects and Calico's resources): BGP carries
the routes, but the nodes still learn *who* to peer with from the control plane.

---

## 5. A packet, end to end (lab)

**client (cal-server) → web1 (cal-agent1):**

```text
$ kubectl exec -n l3 client -- wget -qO- http://10.244.189.5:8080/
Hostname: web1
RemoteAddr: 10.244.70.0:51058          ← the client's own Pod IP: no NAT between Pods

$ kubectl exec -n l3 client -- traceroute -n 10.244.189.5
 1  172.21.0.2      ← cal-server: the "169.254.1.1" gateway is really this node
 2  172.21.0.3      ← cal-agent1
 3  10.244.189.5    ← web1
```

**The same connection on the wire between the nodes** (`tcpdump -nn -e -i eth0` on cal-agent1;
a filter for VXLAN, UDP 8472 and IP-in-IP matched nothing):

```text
52:a0:12:00:3e:c3 > 82:1f:bf:d9:14:7d, ethertype IPv4: 10.244.70.0.51862 > 10.244.189.5.8080: Flags [S], … mss 1460
└ cal-server's eth0   └ cal-agent1's eth0        └ Pod IP to Pod IP, no outer header
```

```text
client 10.244.70.0          cal-server                         cal-agent1                  web1 10.244.189.5
  eth0 ─ default via ──► cali3583acdbfb9                                                ┌─► eth0
  169.254.1.1 (proxy ARP)   route 10.244.189.0/26                 route 10.244.189.5    │
                            via 172.21.0.3 (proto bird) ─eth0──► dev cali9c5947ed78f ───┘
```

**neighbour → web1, both on cal-agent1:**

```text
$ kubectl exec -n l3 neighbour -- traceroute -n 10.244.189.5
 1  172.21.0.3      ← the node: same-node traffic is routed through it
 2  10.244.189.5
```

With flannel's bridge, the same-node case is one hop on the bridge and no traceroute hop appears.

---

## 6. Leaving the cluster: natOutgoing (lab)

The pool has `natOutgoing: true`, so traffic from a Pod to anything outside the Calico pools is
masqueraded to the node's address. Felix writes the rule:

```text
cal-agent1$ iptables-save -t nat | grep cali-nat-outgoing
-A cali-POSTROUTING … -j cali-nat-outgoing
-A cali-nat-outgoing … -m set --match-set cali40masq-ipam-pools src
                       -m set ! --match-set cali40all-ipam-pools dst -j MASQUERADE --random-fully

cal-server$ conntrack -L -d 1.1.1.1        (client → 1.1.1.1:80)
src=10.244.70.0 dst=1.1.1.1 sport=38187 dport=80   src=1.1.1.1 dst=172.21.0.2 sport=80 dport=29632
                                                                └ replies come back to the node's IP
```

Source "in a Calico pool", destination "not in any Calico pool": only Pod → outside is NATed;
Pod → Pod never is. Felix maintains about 150 iptables rules in 48 `cali-*` chains on each node
here (policy, NAT, anti-spoofing), next to kube-proxy's `KUBE-*` chains, which are unchanged.

---

## 7. What each Calico component does

| Component | Runs | Job (seen in this lab) |
|---|---|---|
| **calico CNI plugin** (`/opt/cni/bin/calico`, conflist `10-calico.conflist`) | called by containerd for each Pod | creates the veth, names it `cali…`, sets the Pod's `/32`, the `169.254.1.1` route and the node's `/32` route |
| **calico-ipam** (CNI IPAM plugin) | same | picks the Pod IP from the node's block; claims a new block when it's full |
| **felix** (in calico-node) | every node | iptables `cali-*` chains: NetworkPolicy, `natOutgoing`, anti-spoofing |
| **BIRD** (in calico-node) | every node | BGP sessions; announces the node's blocks; installs the other nodes' routes (`proto bird`) |
| **confd** (in calico-node) | every node | generates BIRD's config from the peers and blocks |
| **calico-kube-controllers** | one Deployment | garbage-collects IPAM and other Calico resources when Nodes and Pods go away **(docs)** |

---

## 8. Flannel bridge vs Calico Layer 3

| | flannel (VXLAN), [kube-networking.md](kube-networking.md) | Calico, Layer 3 mode (this note) |
|---|---|---|
| Pod address | `/24` on `eth0` (e.g. `10.42.0.46/24`) | `/32` on `eth0` |
| Pod's gateway | `10.42.0.1`, the bridge's real address | `169.254.1.1`, answered by proxy ARP |
| Node side | veths attached to the `cni0` bridge | separate `cali…` veths, no bridge |
| Node → local Pods | one route: `10.42.0.0/24 dev cni0` | one `/32` route per Pod |
| Same-node Pod → Pod | switched by the bridge (Layer 2) | routed by the node (Layer 3) |
| Node → other nodes' Pods | `via 10.42.x.0 dev flannel.1` + VXLAN (UDP 8472) | `via <node IP> dev eth0 proto bird`, plain IP |
| MTU | 1450 (VXLAN overhead) | 1500 |
| How nodes learn routes | flannel reads Node annotations from the API | BGP between nodes (peer list from the API) |
| Per-node address range | Node `podCIDR` (`/24`) | Calico IPAM blocks (`/26`, more on demand) |
| NetworkPolicy | not enforced by flannel (k3s adds kube-router) | felix, built in |

**When pure Layer 3 isn't enough (docs):**

- **Nodes in different subnets, or a network that drops unknown source IPs** (most cloud VPCs
  filter traffic whose source isn't the instance's own IP): set the pool's `ipipMode` or
  `vxlanMode` to `CrossSubnet`. Calico then encapsulates only between subnets and keeps plain
  routing inside one.
- **Many nodes:** a full mesh is n·(n−1)/2 sessions, so large clusters switch the mesh off and
  use **route reflectors**.
- **Physical networks:** nodes can peer with the top-of-rack routers (a `BGPPeer` resource), so
  the data-centre network itself learns the Pod routes, and Pod IPs become reachable from outside
  the cluster without NAT.

---

## 9. Cleaning up

```bash
docker rm -f cal-server cal-agent1 cal-agent2 && docker network rm k3s-cal
```

---

## Related notes

- [kube-networking.md](kube-networking.md): the flannel bridge model, for comparison
- [network-policy.md](network-policy.md): flannel VXLAN routing tables, and NetworkPolicy with kube-router
- [kubernetes-services.md](kubernetes-services.md): kube-proxy's chains, which work the same on top of Calico
- [networking.md](networking.md): routing tables and policy routing

## Sources

- Calico documentation, networking overview and BGP: https://docs.tigera.io/calico/latest/networking/
- Calico, IP pools and `ipipMode` / `vxlanMode` / `CrossSubnet`: https://docs.tigera.io/calico/latest/networking/configuring/vxlan-ipip
- Calico, BGP peering and route reflectors: https://docs.tigera.io/calico/latest/networking/configuring/bgp
- Calico IPAM blocks: https://docs.tigera.io/calico/latest/networking/ipam/
- Calico manifest v3.29.1: https://raw.githubusercontent.com/projectcalico/calico/v3.29.1/manifests/calico.yaml
- BIRD: https://bird.network.cz/
