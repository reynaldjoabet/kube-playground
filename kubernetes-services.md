# Kubernetes Services: why they exist, how they work, and what they cost at scale

For the general iptables/CNI background, see [kube-networking.md](kube-networking.md).

## 0. Before you start: terms and the setup used

### 0.1 Terms

| Term | Meaning here |
|---|---|
| **Pod** | One or more containers sharing a network namespace, so one IP address. The unit Kubernetes schedules. |
| **Deployment / ReplicaSet** | A Deployment keeps N copies of a pod running. It does it through a ReplicaSet, which creates a new pod whenever one disappears. |
| **Label / selector** | A label is a `key=value` tag on an object (`app=web`). A selector is a query over labels (`app=web` matches every pod with that label). |
| **Node** | A machine (here, one Docker container run by minikube, IP `192.168.49.2`) that runs pods. |
| **Pod CIDR** | The address range pods get IPs from: `10.244.0.0/16` here. |
| **Service CIDR** | The range ClusterIPs come from: `10.96.0.0/12` here. These addresses exist on no interface (§3.2). |
| **CNI / Calico** | The plugin that gives pods their IPs and routes between them. Calico's tunnel interface on the node, `tunl0`, has address `10.244.120.64`. |
| **kube-proxy** | A program on every node that turns Services into kernel NAT rules (iptables here). It doesn't carry traffic itself. |
| **EndpointSlice** | An API object listing the pod IPs behind a Service, with their readiness (§3.6). |
| **NAT, DNAT, SNAT** | Network address translation: rewriting addresses in a packet. DNAT rewrites the *destination* (Service IP → pod IP). SNAT rewrites the *source*. MASQUERADE is SNAT to the outgoing interface's address. |
| **conntrack** | The kernel's connection table. It remembers each connection's NAT so later packets and replies are rewritten the same way ([conntrack.md](conntrack.md)). |
| **iptables chain, rule, jump** | A chain is a list of rules checked in order. `-j X` means "jump to chain X" or "apply target X" ([iptables.md](iptables.md)). |
| **Packet mark** | A number the kernel attaches to a packet while it's on this host. Rules can set it early and test it later. It's never sent on the wire. |
| **Readiness probe** | A check the kubelet runs in a container. While it fails, the pod is "not ready" and gets no Service traffic (§3.6). |

### 0.2 The setup

Everything runs in the namespace `svc-demo`. The backend is a three-replica
Deployment of `agnhost`, the Kubernetes project's own test image. Its
`netexec` mode is a small HTTP server with handy endpoints:

| Endpoint | Returns |
|---|---|
| `/hostname` | The pod's name, so you can see which pod answered |
| `/clientip` | The source `IP:port` the pod saw, so you can see whether SNAT happened |

```yaml
apiVersion: apps/v1
kind: Deployment
metadata: {name: web, namespace: svc-demo}
spec:
  replicas: 3
  selector: {matchLabels: {app: web}}
  template:
    metadata: {labels: {app: web}}
    spec:
      containers:
      - name: web
        image: registry.k8s.io/e2e-test-images/agnhost:2.53
        # /tmp/ready is what the readiness probe checks; deleting it makes the pod unready (§3.6)
        command: ["sh", "-c", "touch /tmp/ready && exec /agnhost netexec --http-port=8080"]
        readinessProbe:
          exec: {command: ["cat", "/tmp/ready"]}
          periodSeconds: 2
          failureThreshold: 1
        lifecycle:
          preStop: {sleep: {seconds: 5}}   # §5; added partway through the experiments
```

Requests are sent from a separate pod, `client`, which uses the same image
because it has `curl`, `dig` and `nslookup`:

```bash
kubectl -n svc-demo run client --image=registry.k8s.io/e2e-test-images/agnhost:2.53 \
  --restart=Never --command -- sleep infinity
kubectl -n svc-demo exec client -- curl -s web/hostname
# web-7b6bc8bc5-p8nbc
```

Node-side commands (`iptables`, `conntrack`) run on the node with
`minikube ssh -- sudo <command>`, or inside the kube-proxy pod with
`kubectl -n kube-system exec ds/kube-proxy -- <command>`.

**Pod IPs differ between sections.** The experiments ran over several
sessions, and pods were recreated in between, so a pod IP in one section
(`10.244.120.71`) may not appear in another (`10.244.120.116`). The
Service IPs stay the same throughout, which is exactly the point of §1.

---

## 1. The problem: pod IPs don't last

Pods are disposable. A rollout, crash, rescheduling or scale-down replaces them,
and the replacement gets a new IP. Restarting a three-replica Deployment:

```
before restart            after restart
web-...-f5mdz 10.244.120.66   web-...-rr8m9 10.244.120.71
web-...-h7tlz 10.244.120.65   web-...-rn28l 10.244.120.73
web-...-vf2zl 10.244.120.70   web-...-n8q2l 10.244.120.72
```

A client that stored a pod IP is now broken. It also had no way to choose
between replicas or to know which ones were healthy.

## 2. What a Service gives you

A Service is a stable name and virtual IP in front of whatever pods its label
selector currently matches:

```bash
kubectl -n svc-demo expose deploy/web --port=80 --target-port=8080
```

```
NAME   TYPE        CLUSTER-IP      PORT(S)   SELECTOR
web    ClusterIP   10.107.59.191   80/TCP    app=web
```

While a pod was deleted under continuous load, the Service IP never changed:

```
Service IP before: 10.107.59.191
deleting pod/web-7b4b8c7c84-lbrpc
Service IP after:  10.107.59.191        # new pod came up on 10.244.120.88
```

The `kubectl expose` command above is shorthand for this manifest:

```yaml
apiVersion: v1
kind: Service
metadata: {name: web, namespace: svc-demo}
spec:
  type: ClusterIP          # the default; can be left out
  selector: {app: web}     # which pods are behind it
  ports:
  - port: 80               # the port clients connect to on the ClusterIP
    targetPort: 8080       # the port the pods actually listen on
    protocol: TCP
```

### 2.1 The selector decides who is behind the Service, continuously

A Service doesn't list pods. It holds a label selector, and the set of pods
behind it is re-evaluated whenever pods or labels change:

```
$ kubectl -n svc-demo get pods -l app=web --show-labels
NAME                  READY   STATUS    LABELS
web-7b6bc8bc5-p8nbc   1/1     Running   app=web,pod-template-hash=7b6bc8bc5
web-7b6bc8bc5-wflg7   1/1     Running   app=web,pod-template-hash=7b6bc8bc5
web-7b6bc8bc5-x2pfs   1/1     Running   app=web,pod-template-hash=7b6bc8bc5

$ kubectl -n svc-demo get endpointslice -l kubernetes.io/service-name=web
NAME        ADDRESSTYPE   PORTS   ENDPOINTS
web-g2xh7   IPv4          8080    10.244.120.114,10.244.120.116,10.244.120.121
```

Removing the `app` label from one pod (`kubectl label pod web-7b6bc8bc5-wflg7
app-`, where the trailing `-` means "remove") has two effects, because two
different controllers use the same label:

```
$ kubectl -n svc-demo get pods -L app
NAME                  READY   STATUS    IP               APP
web-7b6bc8bc5-9xfnz   1/1     Running   10.244.120.126   web     # new: the ReplicaSet's replacement
web-7b6bc8bc5-p8nbc   1/1     Running   10.244.120.121   web
web-7b6bc8bc5-wflg7   1/1     Running   10.244.120.114           # still running, label gone
web-7b6bc8bc5-x2pfs   1/1     Running   10.244.120.116   web

$ kubectl -n svc-demo get endpointslice -l kubernetes.io/service-name=web
NAME        ADDRESSTYPE   PORTS   ENDPOINTS
web-g2xh7   IPv4          8080    10.244.120.116,10.244.120.121,10.244.120.126
```

- **The Service dropped `wflg7`** (`10.244.120.114` left the EndpointSlice).
- **The ReplicaSet** no longer counts `wflg7` as one of its 3 pods, so it
  created `9xfnz`, and the Service picked that up because it has the label.

The unlabelled pod still works. It just isn't reachable through the Service:

```
$ curl -s 10.244.120.114:8080/hostname          # direct to the pod
web-7b6bc8bc5-wflg7
$ for i in $(seq 30); do curl -s web/hostname; echo; done | sort | uniq -c
     10 web-7b6bc8bc5-9xfnz
     14 web-7b6bc8bc5-p8nbc
      6 web-7b6bc8bc5-x2pfs                     # wflg7 never answers
```

This is a common debugging trick: unlabel a misbehaving pod to take it out of
traffic while keeping it alive to inspect. Any pod with the right labels
joins the Service, even one you created by hand, so labels are effectively
access control for who receives the traffic.

### 2.2 Three different ports: `port`, `targetPort`, `nodePort`

```yaml
spec:
  type: NodePort
  ports:
  - port: 80           # on the ClusterIP:          10.106.133.73:80
    targetPort: 8080   # on each pod:                10.244.120.x:8080
    nodePort: 30081    # on every node's own IP:    192.168.49.2:30081
```

All three reach the same pods. A port that isn't declared reaches nothing:

```
$ curl -s t-nodeport:80/hostname           # Service name, port
web-7b6bc8bc5-p8nbc
$ curl -s 10.106.133.73:80/hostname        # ClusterIP, port
web-7b6bc8bc5-p8nbc
$ curl -s 192.168.49.2:30081/hostname      # node IP, nodePort
web-7b6bc8bc5-p8nbc
$ curl -s 10.244.120.126:8080/hostname     # pod IP, targetPort (no Service involved)
web-7b6bc8bc5-9xfnz
$ curl -s -m 2 t-nodeport:8080/hostname    # ClusterIP with the *pod's* port
curl exit 28                               # timeout: no rule matches :8080 on the ClusterIP
```

The last one is a classic mistake: the Service only listens on `port`, and
the translation to `targetPort` happens in the DNAT. If `targetPort` is left
out it defaults to the same number as `port`. If `nodePort` is left out on a
NodePort Service, one is picked from 30000–32767.

**These commands ran from the `client` Pod (§0.2), inside the cluster.**
That's why the name, the ClusterIP and the Pod IP all worked. The same four
requests from three places:

```
                                   from a Pod      from the node        from the Mac
                                   (inside)        (minikube ssh)       (outside)
 t-nodeport:80        name          ✓              ✗ exit 6 (no DNS)    ✗ exit 6 (no DNS)
 10.106.133.73:80     ClusterIP     ✓              ✓                    ✗ exit 28 (timeout)
 192.168.49.2:30081   node:nodePort ✓              ✓                    ✗ exit 28 *
 10.244.120.115:8080  Pod IP        ✓              ✓                    ✗ exit 28 (timeout)
```

(`curl` exit 6 means the name couldn't be resolved; exit 28 means the request timed out.)

| Address | What makes it work | So it works from |
|---|---|---|
| **name** `t-nodeport` | CoreDNS answers it, and only Pods use CoreDNS (`nameserver 10.96.0.10` in their `/etc/resolv.conf`, [core-dns.md](core-dns.md) §3) | Pods only. Even the node fails, because it uses its own resolver. |
| **ClusterIP** `10.106.133.73` | No machine has this IP. kube-proxy's iptables rules **on each node** rewrite it to a Pod IP, and both Pod traffic and the node's own traffic pass through them. | Pods and nodes |
| **Pod IP** `10.244.120.115` | The Pod network (Calico here) gives every node routes to every Pod IP. Outside machines have no route to `10.244.0.0/16`. | Pods and nodes |
| **node IP : nodePort** | a real IP on a real machine, plus kube-proxy's rule for the port | anything that can reach the node: this is the address meant for outside |

\* That last failure is specific to minikube's Docker driver on macOS. The
"node" is a container, and its `192.168.49.2` network isn't routable from
macOS. On a real cluster (Hetzner, bare metal, or minikube on Linux),
`nodeIP:nodePort` works from outside, and it's what a cloud LB connects to
(§2.3).

```
 outside ──────────────► node IP:nodePort ──┐
                                            │ kube-proxy rules (on every node)
 Pod / node ─► ClusterIP:port ──────────────┤
 Pod ─► name ─► (CoreDNS) ─► ClusterIP ─────┘
                                            ▼
 Pod / node ─────────────────────────────► Pod IP:targetPort   (the Pod network)
```

#### What is the node IP?

The **node IP** is the address of the machine itself: the VM or server that
runs the kubelet. Kubernetes records it on the Node object (lab):

```
$ kubectl get node -o wide
NAME       STATUS   ROLES           VERSION   INTERNAL-IP    EXTERNAL-IP
minikube   Ready    control-plane   v1.34.0   192.168.49.2   <none>

$ kubectl get node minikube -o jsonpath='{.status.addresses}'
InternalIP=192.168.49.2
Hostname=minikube
```

On the machine, `ip addr` shows it on the main interface, next to other
addresses that are **not** the node IP:

```
eth0     192.168.49.2/24     ← THE node IP (InternalIP): the machine's own network interface
tunl0    10.244.120.64/32    ← Calico's tunnel address: a Pod-network address the node uses (the masquerade source seen earlier)
docker0  172.17.0.1/16       ← Docker's bridge, unused by Kubernetes here
cali…    (no IPv4)           ← one veth per Pod; Pod IPs are reached through routes on these, not addresses
```

**Node IP types.** A node can have several addresses, each labelled with a type:

- **InternalIP:** the address on the cluster's own network. Nodes talk to each
  other on it, and it's the default for node ports and kubelet traffic.
- **ExternalIP:** a public address, if the machine has one.
- **Hostname:** the machine's name.

Who fills them in:

- **Without a cloud provider** (minikube, bare metal), the kubelet does, from
  `--node-ip` or the interface of its default route. That's `192.168.49.2` here.
- **With a CCM**, the CCM's node controller asks the cloud and sets them
  ([cloud-control-manager.md](cloud-control-manager.md)). On Hetzner that's
  typically the server's public IPv4 as ExternalIP, and its private Network IP
  as InternalIP when the server is in a Network. That's from the CCM's design;
  it isn't on this lab.

Every kind of address in these notes, side by side:

| Address | Example | Belongs to | Reachable from |
|---|---|---|---|
| **node IP** | `192.168.49.2` | the machine (a real interface) | the node's network: other nodes, the LB, the internet if it's public |
| Pod IP | `10.244.120.71` | one Pod (its veth) | Pods and nodes (the Pod network) |
| ClusterIP | `10.106.133.73` | nothing: only NAT rules (§3.2) | Pods and nodes, via kube-proxy's rules |
| LB IP / `EXTERNAL-IP` of a Service | `203.0.113.10` | the cloud LB (§2.3) | anyone who can reach the LB |
| node's Pod-network address | `10.244.120.64` (`tunl0`) | the node, on the Pod network | Pods and nodes |

The node IP doesn't have to be public. Only what clients connect to does:
the LB, or the node itself for a plain NodePort. A Hetzner LB with
`use-private-ip` reaches nodes over the private Network (§2.3).

The four shapes on Hetzner (one Service, `nodePort: 31845`):

```
 A. NodePort, no LB:  client (internet) ──────────────────► node PUBLIC IP:31845   → node IP must be public
 B. Hetzner LB, default:
      client ─► LB 203.0.113.10:80 ─(internet side)──────► node PUBLIC IP:31845   → public, but firewall it to the LB
 C. Hetzner LB + use-private-ip:
      client ─► LB 203.0.113.10:80 ─(private Network)────► node 10.0.0.21:31845    → node needs NO public IP for traffic
 D. internal-only: LB with disable-public-network:
      client inside the private Network ─► LB 10.0.0.5 ──► node 10.0.0.21:31845    → nothing public at all
```

**The same on Scaleway**, checked against the CCM source
(`scaleway-cloud-controller-manager`). Two settings decide everything:

- **The `PN_ID` env var on the CCM:** the Private Network the cluster's nodes are on.
- **The `service.beta.kubernetes.io/scw-loadbalancer-private` annotation:** whether the LB gets a public IP.

```
 A. NodePort, no LB:  client (internet) ──────────────────► node EXTERNAL IP:31845    → node IP must be public
 B. Scaleway LB, no PN_ID (no Private Network):
      client ─► LB 203.0.113.10:80 ─(public side)────────► node EXTERNAL IP:31845    → public, but restrict it
 C. Scaleway LB, PN_ID set (LB public, also attached to the PN):
      client ─► LB 203.0.113.10:80 ─(Private Network)────► node 172.16.0.21:31845     → node needs NO public IP for traffic
 D. internal-only: scw-loadbalancer-private: "true"  (needs PN_ID):
      client inside the Private Network ─► LB 172.16.0.5 ─► node 172.16.0.21:31845  → nothing public at all
```

What the code does in each case (`scaleway/loadbalancers.go` unless noted):

| | Code | Effect |
|---|---|---|
| A | none (no CCM involved) | kube-proxy opens 31845 on every node address. A node's `ExternalIP` is its first DHCP-provisioned public IPv4 (`instances.go`, `instanceAddresses`) |
| B | `useInternalIPs := getForceInternalIP(service) \|\| l.pnID != "" \|\| len(getPrivateNetworkIDs(service)) > 0` is false, so `extractNodesExternalIps(nodes)` fills the backend pool | the LB connects to `<node public IP>:31845`, as on Hetzner's default |
| C | `l.pnID != ""` makes `useInternalIPs` true, so the pool comes from `extractNodesInternalIps`. `attachPrivateNetworks` attaches the LB to `PN_ID`, or to `scw-loadbalancer-pn-ids` if set | **automatic for every LoadBalancer Service** once `PN_ID` is set. Hetzner needs `use-private-ip` per Service or by env var. The LB keeps its public flexible IP |
| D | `svcPrivate` true → `createLoadBalancer` skips the IP IDs and sets `AssignFlexibleIP: false`; `createPrivateServiceStatus` reports the LB's IPAM address on the PN | `EXTERNAL-IP` is a private address. With `scw-loadbalancer-use-hostname` it's `<lb-id>.<pn-name>` instead |

The node's `InternalIP` in C and D comes from IPAM: the address of the node's
private NIC on `PN_ID` (`instances.go`, `getPrivateNetworkAddresses`). Without
a Private Network, the CCM falls back to the instance's legacy `PrivateIP`.

Guards and side effects the code enforces:

- **A private LB without `PN_ID` is refused:**
  `cannot create private load balancers without a private network`.
- **Static IPs only work on public LBs:** `scw-loadbalancer-private` together with
  `scw-loadbalancer-ip-ids` or `loadBalancerIP` is an error.
- **Changing the private flag deletes and recreates the LB.** `privateModeMismatch := lbPrivate != (len(lb.IP) == 0)`,
  so the address changes.
- **A new node without the expected IP holds the update up.** `nodesInitialized`
  fails with `missing an external IP address`, or `an internal IP address`.
  It only checks nodes less than 3 minutes old. Older ones without that address
  are left out of the pool silently.
- **`scw-loadbalancer-pn-ids` only overrides an existing `PN_ID`.**
  `attachPrivateNetworks` returns at once when `PN_ID` is empty, while the
  annotation still switches the targets to internal IPs.
- **`scw-loadbalancer-force-internal-ip` is deprecated** (`docs/loadbalancer-annotations.md`).
  It was for clusters with no Private Network.

Not from the code: whether Kapsule sets `PN_ID` for you. Newer Kapsule
clusters are believed to always be on a Private Network, which would make C the
default there. This wasn't checked against Scaleway's docs.

**The same on OVHcloud.** OVHcloud Managed Kubernetes runs the upstream OpenStack
CCM with Octavia LBs ([cloud-control-manager.md](cloud-control-manager.md) §14).
Checked against `cloud-provider-openstack` and OVH's docs; nothing
was run. An Octavia LB's own address (the VIP) is always private. "Public" means
a Floating IP is attached to it.

```
 A. NodePort, no LB (public cluster): client ────────────► node PUBLIC IP:31845      → node IP must be public
 B. "Public-to-Public" (no private network):
      client ─► FIP 203.0.113.10:80 ─► VIP ─(OVH Gateway)─► node public fixed IP:31845 → nodes are public anyway
 C. "Public-to-Private" (cluster on your private subnet):
      client ─► FIP 203.0.113.10:80 ─► VIP 10.0.0.5 ─────► node 10.0.0.21:31845     → nodes need NO public IP
 D. "Private-to-Private": openstack-internal-load-balancer: "true":
      client inside the private network ─► VIP 10.0.0.5 ─► node 10.0.0.21:31845    → nothing public at all
```

| | Code (`pkg/openstack/`) | Effect |
|---|---|---|
| members | `nodeAddressForLB` tries `NodeInternalIP`, then `NodeExternalIP`; `ProtocolPort: int(port.NodePort)` | the LB connects to `<node InternalIP>:31845` |
| InternalIP | `nodeAddresses`: every **fixed** IP on the server's ports, unless its network is listed in `public-network-name` | on a private-network cluster, the private IP (C, D). In B the only fixed IP is the public one (inferred; OVH's CCM config isn't visible) |
| public or not | `ensureFloatingIP`: the annotation or the config's `internal-lb` → no FIP; else it reuses or creates one | C gets a FIP; D doesn't, so `EXTERNAL-IP` is the VIP |
| B's Gateway | OVH's docs only: "A single OpenStack Gateway is spawned for all your LoadBalancers ... may throttle" | B's LB → node traffic goes through that router |

OVH's docs add two limits:
- **A public LB needs an OVHcloud Gateway** (a Neutron router) on the LB's subnet.
- **No public LBs at all on a cluster created with `disablePublicNetworking`.**

Unlike Hetzner, no switch moves the LB → node traffic to the private network.
It follows from the cluster's network: if the nodes have private fixed IPs, the
LB uses them.

**The same on DigitalOcean**, checked against `digitalocean-cloud-controller-manager`. Every droplet has a VPC (private)
IPv4, because the CCM refuses a node without one. Two annotations decide the
shape:
- `service.beta.kubernetes.io/do-loadbalancer-type`: `REGIONAL_NETWORK` (the default, a pass-through LB) or `REGIONAL` (proxying)
- `service.beta.kubernetes.io/do-loadbalancer-network`: `EXTERNAL` (the default) or `INTERNAL`

```
 A. NodePort, no LB:  client (internet) ─────────────────► droplet PUBLIC IPv4:31845  → node IP must be public
 B. REGIONAL_NETWORK + EXTERNAL (default):
      client ─► LB 203.0.113.10:80 ─(passed through)─────► droplet PUBLIC IPv4:80     → nodes need a public IPv4
 C. REGIONAL + EXTERNAL:
      client ─► LB 203.0.113.10:80 ─(VPC)─────────────────► droplet 10.110.0.21:31845  → node needs NO public IP
 D. internal-only: do-loadbalancer-network: INTERNAL:
      client inside the VPC ─► LB 10.110.0.5:80 ─(VPC)─────► droplet 10.110.0.21:31845 → nothing public at all
        (with REGIONAL_NETWORK + INTERNAL the target is :80, the Service port, as in B)
```

| | Code (`cloud-controller-manager/do/`) | Effect |
|---|---|---|
| A | firewall controller: for a NodePort Service, `allow <nodePort> from 0.0.0.0/0, ::/0` (only if `PUBLIC_ACCESS_FIREWALL_NAME` is set) | a plain NodePort is meant to be public |
| B | `buildRegionalNetworkForwardingRule`: `TargetPort: int(port.Port)`. `classifyNode` drops nodes without an ExternalIP | the port the node receives is the Service `port`, not the node port. kube-proxy's LB-IP rule must catch it (inferred, §4.1). The firewall controller opens that port to everyone, and the health-check port only from the LB |
| C | `buildForwardingRule`: `TargetPort = int(port.NodePort)`. `allowsPrivateOnlyBackends(REGIONAL, EXTERNAL)` is true | private-only droplets work. "Over the VPC interface" is from the repo's docs |
| D | `getNetwork` → `INTERNAL`. Every node passes, and `INTERNAL` can't be `DUALSTACK` | `EXTERNAL-IP` is a VPC address |
| all | `req.DropletIDs = dropletIDs`: **droplet IDs, not IPs** | which droplet address the LB connects to is DO's decision, not the CCM's ([cloud-control-manager.md](cloud-control-manager.md) §9.5) |

So DigitalOcean flips the usual pattern. The *default* (B) needs public nodes,
and the proxying type (C) is the one that works with private-only nodes.
`10.110.0.x` is just an example VPC range.

#### With an LB in front, aren't public node IPs wasted?

For **inbound** traffic, mostly yes. The LB only has to reach the nodes, and a
private network does that. Public node IPs then add cost and attack surface:

```
 public nodes behind an LB                     private nodes behind an LB
 ─────────────────────────                     ──────────────────────────
 client ─► LB (1 public IP)                    client ─► LB (1 public IP)
             └─► node-1 public IP ◄─ internet       └─(private net)─► node-1 10.0.0.21
             └─► node-2 public IP ◄─ internet       └─(private net)─► node-2 10.0.0.22
             └─► node-3 public IP ◄─ internet
 N+1 public IPs; every node port reachable     1 public IP; nothing else exposed
 from the internet unless firewalled
```

**What keeps them around is outbound traffic.** The LB carries replies to its
clients, but not the node's own connections. A node still has to:
- pull images (Docker Hub, ghcr.io, …)
- call external APIs, package mirrors and time servers
- on some setups, reach the cloud API or a managed control plane

Without a public IP that needs a NAT gateway on the private network:

```
 private node ─► NAT gateway (1 public IP) ─► internet      ← outbound only, shared by all nodes
```

| | Public IP per node | Private nodes + NAT |
|---|---|---|
| Public IPv4s | one per node, plus the LB | the LB, plus one for the NAT |
| Outbound traffic | direct, nothing extra to run | through the NAT. That's a managed gateway, or a VM you run, and it can become a bottleneck or single point of failure |
| Exposure | every node port is public unless firewalled | nothing inbound except via the LB |
| Setup | simplest, and the default on several clouds | more pieces: private network, routes, NAT |

Per provider (from the CCM code above; the NAT products aren't checked here):

| Provider | Private-only nodes behind an LB? |
|---|---|
| Hetzner | yes, with `use-private-ip` plus a Network. There's no managed NAT; outbound needs a NAT server you run, or IPv6 (from memory) |
| Scaleway | yes. With `PN_ID` set, the LB uses private IPs automatically. NAT: a Public Gateway (Scaleway's docs) |
| OVHcloud | yes, "Public-to-Private". The OVHcloud Gateway on the subnet is a router, presumably also the nodes' NAT (inferred) |
| DigitalOcean | only with `REGIONAL` or `INTERNAL`. The default `REGIONAL_NETWORK` + `EXTERNAL` **requires** public IPv4 on the nodes, so it isn't wasted there: the LB delivers to it |

**When a public node IP is not wasted:**
- No LB at all: a plain NodePort or `hostNetwork` setup.
- A pass-through LB that delivers to it: DigitalOcean's default.
- Per-node outbound addresses are needed, for example for allow-lists. With NAT, all nodes share one address.
- A small cluster where a NAT gateway costs more than a few IPs.

For a production cluster behind an LB, the usual shape is private nodes, a
public LB, and NAT for outbound: case C in the blocks above. Check whether your
cloud bills public IPv4 addresses per address; prices change and weren't checked
here.

#### Which of these goes through the Service IP?

None of the A–D paths above does. A packet to `node:31845` is DNATed straight
to a Pod; the ClusterIP is never its destination. The ClusterIP is a *sibling*
entry point into the same rules, not a step on the way.

From the lab, whose Envoy Gateway Service happens to use
`80:31845`. It's ClusterIP `10.99.63.149`, LB IP `127.0.0.1` from `minikube tunnel`:

```
$ kubectl -n kube-system exec <kube-proxy pod> -- iptables-save -t nat | grep envoy-infra-public-8d55c10f:http-80
-A KUBE-NODEPORTS -p tcp ... "…:http-80" -m tcp --dport 31845              -j KUBE-EXT-Z2K6S4QBRLXAOINF
-A KUBE-SERVICES -d 127.0.0.1/32    -p tcp ... "…:http-80 loadbalancer IP" --dport 80 -j KUBE-EXT-Z2K6S4QBRLXAOINF
-A KUBE-SERVICES -d 10.99.63.149/32 -p tcp ... "…:http-80 cluster IP"      --dport 80 -j KUBE-SVC-Z2K6S4QBRLXAOINF
-A KUBE-EXT-Z2K6S4QBRLXAOINF ...                                       -j KUBE-SVC-Z2K6S4QBRLXAOINF
-A KUBE-SVC-Z2K6S4QBRLXAOINF ... "… -> 10.244.120.112:10080"            -j KUBE-SEP-GKGOSQ6OXEZ5L3OO
-A KUBE-SEP-GKGOSQ6OXEZ5L3OO -p tcp ... -j DNAT --to-destination 10.244.120.112:10080
```

```
ENTRY POINTS (all written by kube-proxy)                                       SHARED PART
──────────────────────────────────────────                                     ───────────
KUBE-NODEPORTS   dst = any node IP, :31845   ◄── A–D       ─► KUBE-EXT ─┐
KUBE-SERVICES    dst = LB IP, :80            (shortcut /                 ├─► KUBE-SVC ─► KUBE-SEP
                                              pass-through) ─► KUBE-EXT ─┘      ▲        DNAT → 10.244.120.112:10080
KUBE-SERVICES    dst = ClusterIP, :80        (in-cluster clients) ──────────────┘        (Pod IP:targetPort)
```

One request to the node port, and its conntrack entry. There was **no** entry
for `10.99.63.149`:

```
$ curl http://192.168.49.2:31845/   (from the node)  → 301
tcp 6 119 TIME_WAIT src=192.168.49.2 dst=192.168.49.2 sport=34082 dport=31845 ...
                    src=10.244.120.112 dst=10.244.120.64 sport=10080 dport=54633 ... [ASSURED]
                    └─ the reply comes from the Pod; the ClusterIP appears nowhere
```

| Packet arrives with destination | Rule that matches | ClusterIP involved? |
|---|---|---|
| node IP:nodePort (A–D, every CCM in these notes) | `KUBE-NODEPORTS` → `KUBE-EXT` → `KUBE-SVC` → DNAT | no |
| ClusterIP:port (a Pod calling `my-svc.ns.svc`) | `KUBE-SERVICES "cluster IP"` → `KUBE-SVC` → DNAT | **yes**, the only common case |
| LB IP:port (kube-proxy's shortcut, or DigitalOcean `REGIONAL_NETWORK`; the latter inferred) | `KUBE-SERVICES "loadbalancer IP"` → `KUBE-EXT` → DNAT | no |

Paths that skip the Service's NAT entirely:
- **Ingress controllers and gateways** (ingress-nginx, Envoy) connect to Pod IPs
  from the EndpointSlices ([ingress-and-gateway-api.md](ingress-and-gateway-api.md) §2.1, §3.4).
- **LBs that target Pod IPs** instead of nodes. None of the CCMs read here do
  that ([cloud-control-manager.md](cloud-control-manager.md)).
- **Headless Services** (`clusterIP: None`): DNS returns the Pod IPs, and there
  are no NAT rules.
- **`hostNetwork` / `hostPort` Pods:** traffic goes to the node IP and the
  Pod's port, with no Service involved.

#### So a node port doesn't use the Service? Where do the Pod IPs come from?

It does use the Service: the *object*, not its ClusterIP *address*. The
ClusterIP is one of the Service's three entry points, and all three share one
backend list:

```
 Service object (type LoadBalancer ⊇ NodePort ⊇ ClusterIP)
 ├── selector  app.kubernetes.io/component=proxy, …     → which Pods
 ├── ports     80 → targetPort 10080, nodePort 31845
 ├── clusterIP 10.99.63.149     ← entry point  (dst = ClusterIP:80)
 ├── nodePort  31845            ← entry point  (dst = any node IP:31845)
 └── LB IP     127.0.0.1        ← entry point  (dst = LB IP:80)
                    all three use the SAME backend list ↓
 EndpointSlice  (written by the EndpointSlice controller from the selector)
 └── 10.244.120.112 : 10080, ready=true, pod=envoy-infra-public-…-555jv
```

A NodePort Service still gets a ClusterIP. Node-port traffic just never uses
that address.

How the Pod IP gets into the DNAT rule:

```
① EndpointSlice controller (in kube-controller-manager)
     watches Services + Pods
     Pods matching the selector → EndpointSlice  envoy-infra-public-8d55c10f-tx46b
                                    endpoints: 10.244.120.112, ports 10080/10443, ready=true

② kube-proxy (on every node)
     watches Services + EndpointSlices
     writes the rules:
       KUBE-NODEPORTS  :31845           ─┐
       KUBE-SERVICES   10.99.63.149:80  ─┼─► KUBE-SVC-Z2K6… ─► KUBE-SEP-GKGO… DNAT → 10.244.120.112:10080
       KUBE-SERVICES   127.0.0.1:80     ─┘   (one KUBE-SEP per endpoint)

③ kernel, per packet
     dst node:31845 → match → DNAT to the Pod IP that ② wrote
```

From the lab:

```
$ kubectl -n envoy-gateway-system get svc envoy-infra-public-8d55c10f
LoadBalancer  clusterIP 10.99.63.149  http-80=80:31845->10080  https-443=443:32053->10443

$ kubectl -n envoy-gateway-system get endpointslice -l kubernetes.io/service-name=envoy-infra-public-8d55c10f
envoy-infra-public-8d55c10f-tx46b   IPv4   10443,10080   10.244.120.112
  owner: Service/envoy-infra-public-8d55c10f   managed-by=endpointslice-controller.k8s.io
  10.244.120.112 ready=true pod=envoy-infra-public-8d55c10f-76bf47b465-555jv

$ kubectl -n envoy-gateway-system get pod -l app.kubernetes.io/component=proxy -o wide
envoy-infra-public-8d55c10f-76bf47b465-555jv   2/2   Running   10.244.120.112   minikube
```

The `DNAT --to-destination 10.244.120.112:10080` in the rules above is exactly
this EndpointSlice entry.

**With more than one Pod: one `KUBE-SEP` per endpoint, picked at random.** The
Envoy Service has a single endpoint, so the choice doesn't show. The lab's
`svc-demo/t-nodeport` (`80:30081`, ClusterIP `10.106.133.73`, selector `app=web`)
has three. Comments are trimmed:

```
-A KUBE-NODEPORTS -p tcp --dport 30081             -j KUBE-EXT-6PIIFHSYVUQU3J3L   ← node-port entry
-A KUBE-SERVICES  -d 10.106.133.73/32 -p tcp --dport 80 -j KUBE-SVC-6PIIFHSYVUQU3J3L ← ClusterIP entry
-A KUBE-SVC-6PII… -m statistic --mode random --probability 0.33333333349 -j KUBE-SEP-NQRD…   1st: 1/3
-A KUBE-SVC-6PII… -m statistic --mode random --probability 0.50000000000 -j KUBE-SEP-452C…   2nd: ½ of the rest
-A KUBE-SVC-6PII…                                                         -j KUBE-SEP-YEGJ…   3rd: whatever is left
-A KUBE-SEP-NQRD… -p tcp -j DNAT --to-destination 10.244.120.115:8080
-A KUBE-SEP-452C… -p tcp -j DNAT --to-destination 10.244.120.71:8080
-A KUBE-SEP-YEGJ… -p tcp -j DNAT --to-destination 10.244.120.74:8080
```

The probabilities 1/3, then 1/2 of the remaining 2/3, then the rest give each
Pod 1/3. The choice happens once per connection; conntrack keeps the rest of
the connection on that Pod (§3.5).

**Live: scaling changes the EndpointSlice and the rules, not the Service.**
After `kubectl -n svc-demo scale deploy web --replicas=4`:

```
EndpointSlice t-nodeport-zcxk2:
  10.244.120.71  web-7b6bc8bc5-bcjj6
  10.244.120.74  web-7b6bc8bc5-5nkcv
  10.244.120.115 web-7b6bc8bc5-4shjp
  10.244.120.125 web-7b6bc8bc5-gbklr        ← new Pod, added by the EndpointSlice controller

KUBE-SVC-6PII…  --probability 0.25000000000 -j KUBE-SEP-NQRD…   (.115)
KUBE-SVC-6PII…  --probability 0.33333333349 -j KUBE-SEP-KYHX…   (.125, new)
KUBE-SVC-6PII…  --probability 0.50000000000 -j KUBE-SEP-452C…   (.71)
KUBE-SVC-6PII…                              -j KUBE-SEP-YEGJ…   (.74)
-A KUBE-SEP-KYHX… -p tcp -j DNAT --to-destination 10.244.120.125:8080   ← new rule, written by kube-proxy
```

The ClusterIP (`10.106.133.73`), the node port (`30081`) and the entry rules
stayed the same. Only the endpoint list and the `KUBE-SVC` chain under it
changed, now in quarters. Scaling back to 3 removed the fourth `KUBE-SEP`
(3 DNAT rules again).

| | Node port traffic | ClusterIP traffic |
|---|---|---|
| Uses the Service object (selector, ports)? | yes | yes |
| Uses the EndpointSlice (Pod IPs)? | yes, through kube-proxy's rules | yes, the same rules |
| Packet ever addressed to the ClusterIP? | **no** | yes |

Ingress controllers and gateways read the same EndpointSlice, but themselves,
and connect to the Pod IPs without kube-proxy's rules. The backend list is the
same; only the entry point differs.

#### When *is* the ClusterIP involved?

Whenever the client sends to the Service's DNS name or ClusterIP. In practice
that's most in-cluster traffic. These are the ClusterIP destinations in the
lab's conntrack table, with each source IP mapped to its Pod:

```
 who (source)                          → ClusterIP:port          Service                       DNAT'd to (reply src)
 ───────────────────────────────────── ─────────────────────── ────────────────────────────── ──────────────────────
 coredns (×2), envoy-gateway,           10.96.0.1:443           default/kubernetes             192.168.49.2  (the API server)
 ingress-nginx-controller,
 calico-kube-controllers
 a hostNetwork process on the node (×3) 10.96.0.1:443           default/kubernetes             (the API server)
 Envoy proxy Pod                        10.96.0.10:53  (udp)    kube-system/kube-dns           10.244.120.87 / .88  (CoreDNS Pods)
 Envoy proxy Pod                        10.109.166.162:18000    envoy-gateway-system/envoy-gateway  10.244.120.79 (xDS from the controller)
```

| Case | Example on the lab | Why it's a ClusterIP |
|---|---|---|
| **Pods talking to the API server** | every controller in a Pod: CoreDNS, Envoy Gateway, the ingress-nginx controller, Calico | they find it through `KUBERNETES_SERVICE_HOST=10.96.0.1`, the ClusterIP of `default/kubernetes` ([core-dns.md](core-dns.md) §9) |
| **Every DNS lookup from a Pod** | the Envoy proxy resolving names | `/etc/resolv.conf` says `nameserver 10.96.0.10`, the ClusterIP of `kube-dns` |
| **Pod → Service by name** | the Envoy proxy → `envoy-gateway:18000` for xDS; moneystack → `lattice.lattice.svc` ([deploy/README.md](deploy/README.md)) | the DNS name resolves to the ClusterIP |
| **A process on a node** using a ClusterIP | the hostNetwork connections above; the `curl` tests from the node earlier in §2.2 | the node has the same kube-proxy rules |

The rule of thumb: the ClusterIP is involved when the client is **inside the
cluster and asks for the Service**. It isn't involved when the client comes
from **outside, through a node port or an LB**.

Even when the ClusterIP is used, it never receives the packet: the destination
is rewritten to a Pod before the packet leaves the node. `default/kubernetes`
translates to `192.168.49.2`, the node IP, rather than a Pod IP. The API server
runs with `hostNetwork`, so its EndpointSlice holds the node address.

One detail visible here: the Node says `podCIDR=10.244.0.0/24`, but the Pods
got `10.244.120.x`. Calico assigns Pod IPs from its own address blocks and
ignores the Node's `podCIDR`. That's from Calico's docs; the lab only shows
that the two differ.

```
                client outside the cluster
                    │  192.168.1.2:30008            (any node's IP works; same rule on every node)
                    ▼
 ┌─ Node 1  192.168.1.2 ─────────────────────────┐    ┌─ Node 2  192.168.1.3 ─────────────────┐
 │  :30008  ◄─ nodePort                          │    │  :30008  ◄─ same nodePort rule        │
 │     │                                         │    │                                       │
 │     │  kube-proxy rule (DNAT, in the kernel): │    │                                       │
 │     │  pick ONE Pod from the EndpointSlice    │    │                                       │
 │     ├────────────────────┐                    │    │                                       │
 │     ▼                    └────────────────────┼────┼──────────┐                            │
 │  ┌────────────────────────┐                   │    │  ┌───────▼───────────────┐            │
 │  │ Pod 10.244.0.2   :80   │ ◄─ targetPort     │    │  │ Pod 10.244.1.5   :80  │            │
 │  └────────────────────────┘                   │    │  └───────────────────────┘            │
 │     ▲                                         │    │          ▲                            │
 └─────┼─────────────────────────────────────────┘    └──────────┼────────────────────────────┘
       │                                                         │
       └──── in-cluster caller ─► ClusterIP 10.96.0.50:80 (port) ─DNAT─┘   (the same Pods, a second way in)

 There's no "Service" process anywhere in this picture. Only rules, plus the EndpointSlice they're built from.
```

**The slide, run on this cluster** (minikube, kube-proxy iptables mode). This is the slide's Service with two agnhost Pods listening on 80:

```yaml
apiVersion: v1
kind: Service
metadata: {name: myapp-service, namespace: slide}
spec:
  type: NodePort
  selector: {app: myapp}
  ports: [{port: 80, targetPort: 80, nodePort: 30008}]
```

```
$ kubectl -n slide get svc,endpointslice
NAME            TYPE       CLUSTER-IP     EXTERNAL-IP   PORT(S)        AGE
myapp-service   NodePort   10.96.99.212   <none>        80:30008/TCP   3s
NAME                  ADDRESSTYPE   PORTS   ENDPOINTS
myapp-service-7bvn6   IPv4          80      10.244.120.67,10.244.120.94

# from the node: node port ×6, then ClusterIP ×4. Both reach both Pods
$ curl 192.168.49.2:30008/hostname    → cfvkh, cfvkh, zngpk, cfvkh, zngpk, zngpk
$ curl 10.96.99.212:80/hostname       → zngpk, zngpk, zngpk, cfvkh
```

The rules show the two ways in meeting at one chain (`iptables -t nat -S`, trimmed):

```
 node port:  KUBE-NODEPORTS  -p tcp "slide/myapp-service"                  -j KUBE-EXT-DCOT4UTR3VANDJ4U
             KUBE-EXT-DCOT…  -j KUBE-MARK-MASQ        (source rewritten: externalTrafficPolicy Cluster, §4.3)
             KUBE-EXT-DCOT…  -j KUBE-SVC-DCOT4UTR3VANDJ4U ─┐
                                                           │
 ClusterIP:  KUBE-SERVICES   -d 10.96.99.212/32 -p tcp     │
                             -j KUBE-SVC-DCOT4UTR3VANDJ4U ─┤   the same chain: this IS "the Service" in the kernel
                                                           ▼
             KUBE-SVC-DCOT…  statistic random 0.5  -j KUBE-SEP-225H…  → DNAT --to-destination 10.244.120.67:80
             KUBE-SVC-DCOT…                         -j KUBE-SEP-2LFR…  → DNAT --to-destination 10.244.120.94:80
```

So the slide's "Service" box does exist, as the `KUBE-SVC` chain that both 30008 and the ClusterIP jump to. But it's a rule list, not a hop. A packet to `192.168.49.2:30008` is rewritten once, straight to `10.244.120.x:80`. Its destination is never `10.96.99.212`. This minikube has one node, so the second-node part of the drawing wasn't run.

How a cloud LB, Ingress and Gateway stack on top of this is at the end of §2.3 ("Where Ingress and Gateway fit on top of a NodePort").

### 2.3 The types

The Service types build on each other:

| Type | What it adds | Verified behaviour (§4) |
|---|---|---|
| `ClusterIP` | Virtual IP reachable inside the cluster | Rewritten to a pod IP by iptables |
| `NodePort` | Also a port (30000–32767) on every node | `192.168.49.2:30080` reached the pods |
| `LoadBalancer` | Also an external load balancer pointing at the NodePort | `<pending>` until something provisions it |
| `ExternalName` | Only a DNS CNAME, no proxying | `ext` → `kubernetes.io` |
| Headless (`clusterIP: None`) | No virtual IP; DNS returns the pod IPs | 3 A records, 0 iptables rules |

Each of the first three contains the one before it. A NodePort Service *is*
a ClusterIP Service, plus the same Service on a fixed port on every node. A
LoadBalancer Service *is* a NodePort Service, plus a cloud load balancer in
front of those node ports:

```
 ClusterIP     virtual IP inside the cluster         10.106.133.73:80 ------------+
                                                                                  |
 NodePort      = ClusterIP                                                        +--> pod IPs : targetPort 8080
               + port 30000-32767 on EVERY node      <any node IP>:30081 ---------+    (from the EndpointSlices, §3.6)
                                                                                  |
 LoadBalancer  = NodePort                                                         |
               + a cloud LB in front of the nodes    203.0.113.10:8090 -> nodes:30082
                 (created by the cloud controller manager, or `minikube tunnel` here)
```

So "NodePort = ClusterIP plus port mapping" is close, but the port mapping
(`port` → `targetPort`) is already in ClusterIP. What NodePort adds is
*another way in*. `kubectl get svc` shows the nesting: a LoadBalancer Service
has a `CLUSTER-IP`, and `PORT(S)` reads `<port>:<nodePort>`, e.g. `8090:30082/TCP`
for `t-lb`. All three entry points are kube-proxy rules leading to the same
EndpointSlice list of pods (§4.1 shows where each one enters the NAT chains).

What the node port brings with it:

- **It's open on every node**, including nodes running none of the pods. Those
  forward the traffic to a pod on another node.
- **`externalTrafficPolicy` decides how** (§4.3).
  - `Cluster` (the default) forwards to any pod but rewrites the source, so the
    pod sees a node IP instead of the client's.
  - `Local` keeps the client IP but only uses pods on the receiving node. A
    node with none drops the traffic, and a cloud LB's health check takes that
    node out of rotation.
- **The port range is fixed** (30000–32767 by default), and clients must know a
  node IP. That's why plain NodePort is mostly for labs, bare metal, or behind
  a load balancer you run yourself.

Two variations on LoadBalancer:

- **Some cloud LBs can target pod IPs directly** (see
  [cloud-control-manager.md](cloud-control-manager.md)). Then
  `spec.allocateLoadBalancerNodePorts: false` skips the node ports.
- **With a Gateway or ingress controller, the pods behind the LoadBalancer are
  the proxy's pods**, and the proxy picks the backend pods itself, bypassing
  their ClusterIP (§1.1 and §4 of
  [ingress-and-gateway-api.md](ingress-and-gateway-api.md)).

#### Where Ingress and Gateway fit on top of a NodePort

This builds on the NodePort picture in §2.2 ("Reading a typical NodePort diagram"). A LoadBalancer Service only adds a cloud LB in front of the node ports. Ingress and Gateway add a proxy, which runs as Pods behind its *own* LoadBalancer Service:

```
 (A) NodePort       client ───────────────────────────────► node:30008 ─DNAT─► app Pod:80

 (B) LoadBalancer   client ─► cloud LB 203.0.113.10:80 ───► node:30008 ─DNAT─► app Pod:80
                              (made by the CCM, one per Service; it knows ports, not paths)

 (C) Ingress        client ─► cloud LB :80/:443 ─► node:3xxxx ─DNAT─► ingress-nginx Pod
                                                                          │ reads Host + path,
                                                                          │ Ingress rule → Service "app"
                                                                          │ → its EndpointSlice → Pod IPs
                                                                          ▼
                                                                      app Pod:80   (ClusterIP skipped)

 (D) Gateway        client ─► cloud LB :80/:443 ─► node:3xxxx ─DNAT─► Envoy Pod
                                                                          │ HTTPRoute rule → Service "app"
                                                                          │ → its EndpointSlice → Pod IPs
                                                                          ▼
                                                                      app Pod:80   (ClusterIP skipped)
```

In (C) and (D) there are two kinds of Service:

```
 the proxy's Service   type LoadBalancer  (= NodePort = ClusterIP)  ─► carries ALL outside traffic, to the proxy Pods
 each app's Service    type ClusterIP     ─► the proxy only reads it to find Pod IPs;
                                             in-cluster callers still use its ClusterIP:80
```

So one cloud LB and one node port serve every app behind the proxy. In (B) each app needs its own LB.

#### Does the cloud LB really target the node port? Is that port public?

In all four CCMs' source ([cloud-control-manager.md](cloud-control-manager.md)), the cloud
LB is pointed at the Service's `nodePort`, with one exception: DigitalOcean's default
`REGIONAL_NETWORK` type targets the Service `port` instead (below).

Hetzner, `hcloud-cloud-controller-manager`, `internal/lbspec/opts.go`:

```go
func (s ServiceSpec) AddServiceOpts(port corev1.ServicePort, ...) hcloud.LoadBalancerAddServiceOpts {
	destinationPort := int(port.NodePort)
	opts := hcloud.LoadBalancerAddServiceOpts{
		ListenPort:      new(int(port.Port)),          // 8090 on the LB
		DestinationPort: new(destinationPort),         // 30082 on each node
		...
		HealthCheck:     s.addHealthCheckOpts(destinationPort),
```

DigitalOcean, `digitalocean-cloud-controller-manager`,
`cloud-controller-manager/do/loadbalancers.go`. DigitalOcean has two LB
types, and they target different ports. `REGIONAL` (the proxying, layer-7
LB) uses `buildForwardingRule`:

```go
forwardingRule.EntryPort = int(port.Port)        // 8090 on the LB
forwardingRule.TargetPort = int(port.NodePort)   // 30082 on each droplet
```

`REGIONAL_NETWORK` (the pass-through, layer-4 LB) uses
`buildRegionalNetworkForwardingRule`:

```go
forwardingRules = append(forwardingRules, godo.ForwardingRule{
	EntryProtocol:  protocol,
	EntryPort:      int(port.Port),            // 8090 on the LB
	TargetProtocol: protocol,
	TargetPort:     int(port.Port),            // 8090 on each droplet: the Service port, not the node port
})
```

**`REGIONAL_NETWORK` is the CCM's default**, not `REGIONAL` (`do/cloud.go`:
`defaultLBType := godo.LoadBalancerTypeRegionalNetwork`, overridable with
`DEFAULT_LB_TYPE`, or per Service with
`service.beta.kubernetes.io/do-loadbalancer-type`). So on DigitalOcean, the
plain diagram above (cloud LB → node port) only describes `REGIONAL`.

Linode, `linode-cloud-controller-manager`,
`cloud/linode/loadbalancers.go`. Each NodeBalancer config, one per Service
port, gets one backend node per Kubernetes node, at the node's address plus
the node port:

```go
// called with port.NodePort for every Service port
nodeIP, err := getNodeBackendIP(service, node, subnetID, useIPv6Backends)
nodeOptions := &linodego.NodeBalancerConfigRebuildNodeOptions{
	NodeBalancerNodeCreateOptions: linodego.NodeBalancerNodeCreateOptions{
		Address: formatNodeBalancerBackendAddress(nodeIP, nodePort),   // <node IP>:30082
		...
```

Scaleway, `scaleway-cloud-controller-manager`, `scaleway/loadbalancers.go`
(`servicePortToFrontend`, `servicePortToBackend`):

```go
return &scwlb.Frontend{
	InboundPort: port.Port,              // 8090 on the LB
	...
backend := &scwlb.Backend{
	Pool:        nodeIPs,                // every matching node
	ForwardPort: port.NodePort,          // 30082 on each node
	...
healthCheck = &scwlb.HealthCheck{
	Port: port.NodePort,
```

**Whether the node port is reachable from the internet** is a separate
question. kube-proxy opens a node port on *every* node address by default
(`nodePortAddresses` unset), public and private. What decides exposure is the
network the LB uses to reach the nodes, and the cloud firewall:

- **Hetzner, by default, uses the node's public IP.** `internal/config/config.go`
  reads `HCLOUD_LOAD_BALANCERS_USE_PRIVATE_IP` with a default of `false`
  (per Service: the annotation `load-balancer.hetzner.cloud/use-private-ip`).
  So the LB connects to `<node public IP>:30082`, and that port must be open on
  the public interface. That makes it reachable by anyone unless a Hetzner Cloud
  Firewall restricts it. With `use-private-ip: "true"` (node and LB in the same
  Hetzner Network), the LB connects over the private network instead, and the
  node-port range can be closed publicly. (That Cloud Firewalls don't filter
  private-network traffic comes from third-party guides, not from Hetzner's
  docs or this code.)
- **DigitalOcean depends on the LB type.**
  - A `REGIONAL` LB reaches the droplets over the VPC. From the repo's
    `docs/getting-started.md`: "DigitalOcean Load-Balancers access the cluster
    over the VPC interface and as such are not managed by this particular
    firewall."
  - The default, `REGIONAL_NETWORK` with external networking, passes client
    traffic through to the droplets' **public** IPv4 on the *Service* port.
    The getting-started table says this type can't use private-only droplets
    as backends.

  Its optional firewall controller (`PUBLIC_ACCESS_FIREWALL_NAME`, in
  `firewall_controller.go`) handles three cases:
  - for `type: NodePort` Services, it opens each node port to
    `0.0.0.0/0` and `::/0`, so a plain NodePort is *meant* to be public;
  - for `REGIONAL` LoadBalancers, it adds nothing;
  - for `REGIONAL_NETWORK` with external networking, it opens the Service
    ports to everyone, and the health-check port only from the LB
    (`Sources: LoadBalancerUIDs`).
- **Linode uses the node's private address.** `getNodeBackendIP` →
  `getNodePrivateIP` returns the node's `InternalIP`. That's its VPC address
  when the cluster runs in a VPC, otherwise Linode's private `192.168.x.x`
  address. The `node.k8s.linode.com/private-ip` annotation can override it
  outside a VPC. Only if the Service asks for IPv6 backends does it use the
  node's **public** IPv6 (from an annotation on the node).
- **Scaleway uses the nodes' external IPs unless a private network is
  configured.** In `updateLoadBalancer`:

  ```go
  useInternalIPs := getForceInternalIP(service) || l.pnID != "" || len(getPrivateNetworkIDs(service)) > 0
  ```

  With none of those (no `PN_ID`, no private-network annotation, no
  `force-internal-ip` annotation), `extractNodesExternalIps` fills the backend
  pool. The node port then has to be reachable on the public interface, as on
  Hetzner's default.

| | LB targets | Reaches nodes over | Node port must be public? |
|---|---|---|---|
| Hetzner, default | `nodePort` | node's **public** IP | yes, so restrict it with a Cloud Firewall |
| Hetzner, `use-private-ip: "true"` | `nodePort` | private Network | no, so the public side can be closed |
| DigitalOcean `REGIONAL` | `nodePort` | VPC | no |
| DigitalOcean `REGIONAL_NETWORK`, external (**default**) | Service `port` | client traffic passed through to the droplet's public IPv4 | the **Service** port is public (the firewall controller opens it); the node port isn't used |
| DigitalOcean `INTERNAL` (either type) | `nodePort` (`REGIONAL`) or Service `port` (`REGIONAL_NETWORK`) | VPC | no; the LB itself has only a VPC address |
| Linode (IPv4 backends, default) | `nodePort` | node's private / VPC IP | no |
| Linode, IPv6 backends | `nodePort` | node's public IPv6 | yes |
| Scaleway, no private network | `nodePort` | node's **external** IP | yes |
| Scaleway, `PN_ID` / private network / `force-internal-ip` | `nodePort` | node's internal IP | no |
| OVHcloud (Octavia), cluster on a private network | `nodePort` | node's private (fixed) IP | no |
| OVHcloud (Octavia), public-only cluster | `nodePort` | node's public fixed IP, via OVH's Gateway (inferred) | yes |
| `type: NodePort`, no LB | n/a | clients connect directly | yes, by design |

**The same paths as diagrams.** One example Service is used throughout:
`port: 8090`, `targetPort: 8080`, `nodePort: 30082`, on two nodes. Each node
has a public IP (`203.0.113.21`, `.22`) and a private IP (`10.0.0.21`, `.22`).

```
HETZNER, default (use-private-ip: false)          LB service: listen 8090 -> destination 30082
                                                   targets: nodes' PUBLIC IPs
  client --> LB 198.51.100.5:8090 --(internet side)--> 203.0.113.21:30082 --+
                                                       203.0.113.22:30082 --+--> kube-proxy NodePort rule
                                                                                 --> pod :8080
  exposure: 30082 must be open on the public interface (anyone can try it
            unless a Cloud Firewall allows only the LB)

HETZNER, use-private-ip: "true"  (LB and nodes in one Hetzner Network)
  client --> LB 198.51.100.5:8090 --(private Network)--> 10.0.0.21:30082 --+
                                                         10.0.0.22:30082 --+--> kube-proxy --> pod :8080
  exposure: 30082 can be closed on the public side
```

**After the LB: one Hetzner request, hop by hop.** The LB and the Service don't need to share a network. A Service isn't on a network at all; it's rules on every node. The LB only has to *reach a node IP*: public by default, private with `use-private-ip`.

```
 client
   │ TCP to 203.0.113.10:80                        (Hetzner LB public IP)
   ▼
 Hetzner LB  "service": listen 80 → destination 31845
   │ opens its OWN TCP connection to a target:      (it's a proxy: a second connection, not a forwarded packet)
   │   default:              <node PUBLIC IP>:31845
   │   use-private-ip=true:  <node PRIVATE IP>:31845   (LB attached to the same Hetzner Network)
   ▼
 node, in the kernel (kube-proxy's rules):
   KUBE-NODEPORTS  --dport 31845        → KUBE-EXT-…   (+ masquerade if externalTrafficPolicy: Cluster)
   KUBE-EXT-…                          → KUBE-SVC-…    (the SAME chain the ClusterIP uses)
   KUBE-SVC-…   pick one endpoint       → KUBE-SEP-…
   KUBE-SEP-…   DNAT → 10.244.1.23:8080                ← Pod IP : targetPort
   ▼
 Pod 10.244.1.23:8080  (maybe on another node: then routed over the Pod network)

 The ClusterIP 10.96.x.x:80 appears NOWHERE on this path.
```

The node half of this chain (`KUBE-NODEPORTS → KUBE-EXT → KUBE-SVC → DNAT podIP:targetPort`) was shown on minikube for a NodePort Service in §2.2 and for the Gateway's LoadBalancer Service in [ingress-and-gateway-api.md](ingress-and-gateway-api.md) §3.4. The LB half is from the Hetzner CCM source above. This lab has no Hetzner account.

Two consequences:

- **The client's IP is lost twice by default.** The Hetzner LB opens its own connection, so the node sees the LB's IP. Then `externalTrafficPolicy: Cluster` masquerades again. `load-balancer.hetzner.cloud/uses-proxyprotocol: "true"` carries the client IP through, but the receiving app or proxy must speak PROXY protocol ([cloud-control-manager.md](cloud-control-manager.md) §13.5, `ipMode: Proxy`).
- **Every node is a target**, including nodes without a matching Pod. Those forward to a Pod elsewhere, unless `externalTrafficPolicy: Local` lets the LB's health check drop them.

**`port` is not the node port.** A common mistake is setting `port` to a high number, thinking a LoadBalancer Service "uses node ports". The node port is allocated **separately**, whatever `port` is. Two LoadBalancer Services, applied on the lab, differing only in `port`:

```
ports: [{port: 30080, targetPort: 8080}]   →   high-port   LoadBalancer   …   30080:31781/TCP
ports: [{port: 80,    targetPort: 8080}]   →   normal      LoadBalancer   …   80:31209/TCP
```

```
 port 30080:  client ─► LB-IP:30080 ─► node:31781 ─► rules ─► Pod:8080    (an odd public port, no gain)
 port 80:     client ─► LB-IP:80    ─► node:31209 ─► rules ─► Pod:8080
                         ▲ Service port      ▲ nodePort (auto)     ▲ targetPort
```

**For a LoadBalancer Service, `port` is the port the Hetzner LB listens on.** The Hetzner CCM builds each LB "service" from one Service port (`internal/lbspec/opts.go`):

```go
destinationPort := int(port.NodePort)       // where the LB sends traffic: the auto-picked node port
ListenPort:      new(int(port.Port)),       // where the LB listens: your `port`
DestinationPort: new(destinationPort),
```

So with `port: 30080`, the Hetzner LB **listens on 30080**, and clients must call `http://LB-IP:30080`. The LB still forwards to a different, auto-allocated node port. So:

| Field | Set it to | Becomes on Hetzner |
|---|---|---|
| `port` | what clients should type: 80 / 443 for web, or the protocol's usual port | the LB "service" `listen_port` |
| `targetPort` | the app's port, preferably by name (`http`) | where the DNAT ends |
| `nodePort` | **leave it out**; set it only if something outside Kubernetes must know the number in advance (a firewall rule, a hand-managed LB). It must be in 30000–32767 and free. | the LB "service" `destination_port` |

**Is the LB connecting to a "public Service IP"? No: that IP is the LB.** A
Service never gets a public IP of its own. The `EXTERNAL-IP` column shows the
**LB's** IP, which the CCM copies into the Service's
`status.loadBalancer.ingress` after creating the LB:

```
$ kubectl get svc moneystack-public
TYPE           CLUSTER-IP     EXTERNAL-IP                                PORT(S)
LoadBalancer   10.96.12.34    203.0.113.10,2001:db8:1::1,10.0.0.5       80:31845/TCP
                ▲ inside only   ▲ ALL of these are the Hetzner LB's own addresses (status.loadBalancer.ingress):
                                  public IPv4, public IPv6, and its private-Network IP. None is a "Service IP".

 client ─► 203.0.113.10:80  (the LB itself)
        ─► node IP:31845                          (public or private node IP, see use-private-ip)
        ─► node kernel: KUBE-NODEPORTS → … → DNAT  (rules written earlier by kube-proxy; it isn't in the path)
        ─► Pod IP:8080
```

On Hetzner, `EXTERNAL-IP` usually lists several addresses. The CCM writes
every LB address into the status (`hcloud/load_balancers.go`):

```go
if spec.Hostname != "" {                                         // annotation load-balancer.hetzner.cloud/hostname
	return []corev1.LoadBalancerIngress{{Hostname: spec.Hostname}}   // → a name instead of IPs
}
if lb.PublicNet.Enabled {
	ingress = append(ingress, …{IP: lb.PublicNet.IPv4.IP.String(), IPMode: &ipMode})
	if spec.IPv6 {                                               // on unless ipv6-disabled / HCLOUD_LOAD_BALANCERS_DISABLE_IPV6
		ingress = append(ingress, …{IP: lb.PublicNet.IPv6.IP.String(), IPMode: &ipMode})
	}
}
if spec.PrivateIngress {                                         // on unless disabled; adds the LB's private IP(s)
	for _, privateNet := range lb.PrivateNet { ingress = append(ingress, …{IP: privateNet.IP.String(), …}) }
}
```

`kubectl` joins them with commas. `ipMode` comes along with each one: `VIP`
normally, `Proxy` with PROXY protocol. So there's no second public hop. The
LB connects to node IPs on the node port (above).

The addresses in the example are illustrative, because this lab has no
Hetzner LB. The defaults (IPv6 on, private ingress on) come from the CCM's
`internal/config/config.go`, where each is turned off with a `DISABLE_`
variable.

**There is real redundancy, though: the traffic is load-balanced twice.**

```
 LB picks a NODE ──────► node A:31845 ──► kube-proxy picks a POD ──► Pod on node B
   (balancing #1)                           (balancing #2: may cross to another node)
```

The LB spreads traffic over nodes. Then kube-proxy on that node spreads it
again over **all** Pods, often sending it to another node. That costs an extra
network hop, and with `externalTrafficPolicy: Cluster` the client IP is lost to
masquerade. There are three ways to remove or use a layer:

| Option | What it removes | Trade-off |
|---|---|---|
| `externalTrafficPolicy: Local` | balancing #2: a node only sends to its own Pods, and the LB's health check drops nodes with none (§4.3) | uneven load if Pods aren't spread evenly; keeps the client IP |
| LB targets **Pod IPs** directly, with `allocateLoadBalancerNodePorts: false` (AWS LB Controller IP mode, GKE NEGs) | the node port and kube-proxy entirely | needs a cloud LB that can reach Pod IPs. The Hetzner CCM targets nodes, not Pods ([cloud-control-manager.md](cloud-control-manager.md) §13.2). |
| an Ingress or Gateway proxy | not removed, but balancing #2 is put to use: the proxy picks the Pod by host and path, and kube-proxy only carries traffic to the proxy | one more process in the path, but one LB for many apps |

**Inside the cluster, kube-proxy skips the LB entirely.** kube-proxy also
writes a rule for the **LB's IP**. A Pod that calls `203.0.113.10` is rewritten
straight to a Pod and never goes out to the LB and back. The lab has this rule
for the Gateway's Service, with `127.0.0.1` as the LB IP from `minikube tunnel`
([ingress-and-gateway-api.md](ingress-and-gateway-api.md) §3.4):

```
KUBE-SERVICES -d 127.0.0.1/32 … "envoy-infra-public-8d55c10f:https-443 loadbalancer IP" -j KUBE-EXT-…
```

That shortcut is exactly what Hetzner and Scaleway turn off with
`ipMode: Proxy` when the LB uses PROXY protocol. Then the traffic **must** go
through the LB, because the backend expects the PROXY header
([cloud-control-manager.md](cloud-control-manager.md) §13.5).

The layers aren't redundant by design. Each solves a different problem:

- the LB gives one stable public IP
- the node port gets traffic into any node
- kube-proxy finds a live Pod

The double balancing is real, though, and `externalTrafficPolicy: Local` or
Pod-IP targeting are the usual ways to remove it.

```
DIGITALOCEAN, REGIONAL  (proxying LB; set do-loadbalancer-type: REGIONAL)
                                                   forwarding rule: entry 8090 -> target 30082
  client --> LB :8090 --(VPC)--> 10.0.0.21:30082 --+
                                 10.0.0.22:30082 --+--> kube-proxy NodePort rule --> pod :8080
  exposure: nothing public on the nodes; the LB uses the VPC

DIGITALOCEAN, REGIONAL_NETWORK + EXTERNAL  (the CCM's DEFAULT; pass-through LB)
                                                   forwarding rule: entry 8090 -> target 8090
  client --> LB 198.51.100.5:8090 --(passed through)--> 203.0.113.21:8090 --+
                                                        203.0.113.22:8090 --+--> kube-proxy rule for the
                                                                                 Service's LB IP (inferred,
                                                                                 cloud-control-manager §9.5)
                                                                                 --> pod :8080
  exposure: the SERVICE port 8090 is opened on the droplets' public side by the
            firewall controller (health-check port only from the LB itself);
            nodePort 30082 isn't used by the LB
```

```
LINODE, IPv4 backends (default)                    NodeBalancer config :8090 -> nodes <InternalIP>:30082
  client --> NodeBalancer :8090 --(private / VPC)--> 192.168.x.21:30082 --+   (or VPC 10.0.0.21)
                                                     192.168.x.22:30082 --+--> kube-proxy --> pod :8080
  exposure: none needed on the public side

LINODE, IPv6 backends (requested on the Service)
  client --> NodeBalancer :8090 --(public IPv6)--> [2001:db8::21]:30082 --+--> kube-proxy --> pod :8080
  exposure: 30082 open on the nodes' public IPv6
```

```
SCALEWAY, no private network                       frontend :8090 -> backend ForwardPort 30082
  client --> LB :8090 --(public)--> 203.0.113.21:30082 --+
                                    203.0.113.22:30082 --+--> kube-proxy --> pod :8080
  exposure: 30082 open on the nodes' public side

SCALEWAY, PN_ID set / private-network annotation / force-internal-ip
  client --> LB :8090 --(Private Network)--> 10.0.0.21:30082 --+
                                             10.0.0.22:30082 --+--> kube-proxy --> pod :8080
  exposure: none needed on the public side
```

The pattern across providers:

- **Classic design: the LB targets the node port, so the question is which
  address it uses.** Hetzner (default) and Scaleway (without a private
  network) use public addresses. DigitalOcean `REGIONAL` and Linode use
  private ones.
- **Only DigitalOcean's pass-through `REGIONAL_NETWORK` LB skips the node
  port.** It sends traffic to the Service port on the droplet's public IP, so
  the Service port is what has to be open.
- **Whichever path is used, the last hop is the same:** kube-proxy's rules
  (§4.1) on whichever node received the packet, then a pod. Where the
  addresses are public, a provider firewall (or `nodePortAddresses`) decides
  who else can reach the same port.

kube-proxy's `nodePortAddresses` (e.g. only the private subnet's CIDR) also
stops node ports from listening on the public interface at all, whatever the
firewall allows.

The specs of the test Services used in §4, one per type (from `kubectl get
svc <name> -o yaml`, trimmed to `spec`):

```yaml
# t-cluster: reachable only inside the cluster
type: ClusterIP
clusterIP: 10.108.167.147                  # assigned by the API server
selector: {app: web}
ports: [{port: 80, targetPort: 8080}]

# t-nodeport: ClusterIP + a port on every node
type: NodePort
clusterIP: 10.106.133.73
selector: {app: web}
ports: [{port: 80, targetPort: 8080, nodePort: 30081}]
externalTrafficPolicy: Cluster             # §4.3

# t-lb: NodePort + an external load balancer address
type: LoadBalancer
clusterIP: 10.111.233.178
selector: {app: web}
ports: [{port: 8090, targetPort: 8080, nodePort: 30082}]
# status.loadBalancer.ingress[0].ip: 127.0.0.1   ← filled in by `minikube tunnel` (§4.4)

# t-extip: any type, plus addresses you route to the node yourself
type: ClusterIP
clusterIP: 10.111.153.85
externalIPs: [192.168.49.200]
selector: {app: web}
ports: [{port: 80, targetPort: 8080}]

# t-headless: no ClusterIP; DNS returns the pod IPs (§4.2)
type: ClusterIP
clusterIP: None
selector: {app: web}
ports: [{port: 8080, targetPort: 8080}]

# t-extname: no selector, no ports, no IP; only a DNS alias (§4.5)
type: ExternalName
externalName: kubernetes.io
```

## 3. How a request reaches a pod

The components involved:

```
 client pod ──DNS──► CoreDNS (itself a Service, 10.96.0.10)
     │                    returns ClusterIP 10.107.59.191
     ▼
 node kernel: iptables NAT (written by kube-proxy)
     rewrites 10.107.59.191:80 ──► one pod IP:8080, chosen at random
     ▼
 conntrack remembers the rewrite for the rest of the connection
     ▼
 pod

 kube-proxy (one per node) watches: Services + EndpointSlices
 EndpointSlice controller (kube-controller-manager) watches: Services + Pods
```

Before any request, this is how the ClusterIP gets from etcd to the places that use it:

```
kubectl expose → API server
   ├─ allocates 10.106.133.73 from the ServiceCIDR
   ├─ writes /registry/ipaddresses/10.106.133.73 and /registry/services/specs/default/web   (etcd)
   └─ EndpointSlice controller (kube-controller-manager) writes the ready Pod IPs: 10.244.120.71:8080, ...

        API server ── watch events ──┬──► CoreDNS     (kubernetes plugin, in memory)
                                     │      web.default.svc.cluster.local → 10.106.133.73
                                     │
                                     └──► kube-proxy on every node
                                            writes iptables/nftables:
                                            dst 10.106.133.73:80 → DNAT to one of the Pod IPs
```

Only the API server reads etcd. CoreDNS and kube-proxy keep their own copies,
updated through watches ([watching-the-api-server.md](watching-the-api-server.md)).
The etcd keys are from [etcd-storage.md](etcd-storage.md).

Then, at call time, `curl http://web.default/` from a Pod:

```
1. Name → IP (DNS)
   the app resolves "web.default"
     /etc/resolv.conf: nameserver 10.96.0.10 (the kube-dns Service), search default.svc.cluster.local ...
   → query web.default.svc.cluster.local → CoreDNS answers 10.106.133.73
     (the DNS query is itself a call to a ClusterIP, and is DNATed to a CoreDNS Pod the same way)

2. IP → Pod (kube-proxy's rule, in the node's kernel)
   the app connects to 10.106.133.73:80
   the packet leaves the Pod's veth and enters the node: PREROUTING
     -200 conntrack: no entry → create one
     -100 dstnat: KUBE-SERVICES → KUBE-SVC-… → pick → KUBE-SEP-… → DNAT to 10.244.120.71:8080

3. Routing
   dst is now 10.244.120.71 → route: dev cali5e245bbbec2 (or across nodes via the CNI) → Pod
   POSTROUTING: confirm, the conntrack entry is saved

4. Reply
   the Pod answers from 10.244.120.71:8080 → conntrack reverses the NAT: src becomes 10.106.133.73:80
   → the app sees a reply from the address it called
```

In step 1, `web.default` has fewer than five dots (`ndots:5`), so the resolver
tries the search list first. `web.default.default.svc.cluster.local` gets
NXDOMAIN, and the next name, `web.default.svc.cluster.local`, is answered (§3.1).
Steps 2 to 4 are covered in detail in §3.2 to §3.5.

### 3.1 DNS turns the name into the ClusterIP

The kubelet writes this into every pod:

```
$ cat /etc/resolv.conf
nameserver 10.96.0.10
search svc-demo.svc.cluster.local svc.cluster.local cluster.local
options ndots:5
```

```
$ nslookup web
Name:    web.svc-demo.svc.cluster.local
Address: 10.107.59.191
```

The three lines mean:

- **`nameserver 10.96.0.10`**: send every DNS query to this address. It is the
  ClusterIP of the `kube-dns` Service, which fronts the CoreDNS pods
  ([core-dns.md](core-dns.md)).
- **`search ...`**: if a name is "short", try appending each of these
  suffixes in turn.
- **`ndots:5`**: a name counts as short if it has fewer than 5 dots.

So `web` (0 dots) is tried as:

```
web.svc-demo.svc.cluster.local     ← found: the Service in my own namespace
web.svc.cluster.local              (not tried, the first one answered)
web.cluster.local
web
```

Every Service gets the name `<service>.<namespace>.svc.cluster.local`. From
another namespace you'd write `web.svc-demo` (which expands via the second
suffix to `web.svc-demo.svc.cluster.local`). `ndots:5` is
`defaultDNSOptions` in `pkg/kubelet/network/dns/dns.go`. It has a cost; see §6.5.

### 3.2 The ClusterIP is only a NAT rule

No interface has the ClusterIP and nothing listens on it:

```
$ ip -4 addr | grep -c 10.107.59.191
0
```

So something has to catch packets sent to it and rewrite them. kube-proxy
writes three levels of iptables chains in the `nat` table for that. How to
read one rule:

```
-A KUBE-SERVICES -d 10.107.59.191/32 -p tcp -m tcp --dport 80 -j KUBE-SVC-IGTRU775QHYZG4VA
│  │             │                   │      │                 └─ if all of it matches: jump to this chain
│  │             │                   │      └─ and destination port 80
│  │             │                   └─ and the protocol is TCP
│  │             └─ match: destination address is exactly 10.107.59.191
│  └─ in chain KUBE-SERVICES
└─ append this rule
```

`-m comment --comment "..."` is a label kube-proxy adds so you can tell which
Service a rule belongs to; it matches everything. The hash suffix
(`IGTRU775QHYZG4VA`) is the first 16 base32 characters of a SHA-256 over the
Service's namespace, name, port name and protocol (`portProtoHash` in
`pkg/proxy/iptables/proxier.go`), so the chain name stays stable across
syncs.

The three levels for the `web` Service:

```
-A KUBE-SERVICES -d 10.107.59.191/32 -p tcp -m comment --comment "svc-demo/web cluster IP" -j KUBE-SVC-IGTRU775QHYZG4VA

-A KUBE-SVC-IGTRU775QHYZG4VA ! -s 10.244.0.0/16 -d 10.107.59.191/32 ... -j KUBE-MARK-MASQ
-A KUBE-SVC-IGTRU775QHYZG4VA ... -> 10.244.120.71:8080 -m statistic --mode random --probability 0.33333333349 -j KUBE-SEP-NG6A...
-A KUBE-SVC-IGTRU775QHYZG4VA ... -> 10.244.120.72:8080 -m statistic --mode random --probability 0.50000000000 -j KUBE-SEP-NG2W...
-A KUBE-SVC-IGTRU775QHYZG4VA ... -> 10.244.120.73:8080 -j KUBE-SEP-2C32...

-A KUBE-SEP-NG6AIAJCPIV5FQ6B -s 10.244.120.71/32 ... -j KUBE-MARK-MASQ
-A KUBE-SEP-NG6AIAJCPIV5FQ6B -p tcp ... -j DNAT --to-destination 10.244.120.71:8080
```

- **`KUBE-SERVICES`** has one entry per Service IP and port. The first packet of
  every new connection walks it from the top (demonstrated in §3.3). Which
  addresses get an entry depends on the Service type (§4.1).

> **Missing ports in these listings.** The rules above, and the listings in
> §3.3, come from the node's `iptables v1.8.7`, which doesn't print the port
> match kube-proxy (`iptables v1.8.9`) writes. Every real entry includes it,
> e.g. `-d 10.107.59.191/32 -p tcp -m tcp --dport 80 -j KUBE-SVC-...`. To see
> the true rules, run `iptables-save` inside the kube-proxy pod:
> `kubectl -n kube-system exec ds/kube-proxy -- iptables-save -t nat`.
- **`KUBE-SVC-*`** picks an endpoint. The probabilities are `1/(endpoints remaining)`:
  1/3, then 1/2 of the rest, then everything left over, which is an even
  three-way split. This is `writeServiceToEndpointRules` and `computeProbability`
  in `pkg/proxy/iptables/proxier.go`. Worked through for one new connection:

  | Rule | Chance this rule is tried | Chance it fires when tried | Overall share |
  |---|---|---|---|
  | → `.71` | 1 | 1/3 | 1 × 1/3 = **1/3** |
  | → `.72` | 2/3 (the first didn't fire) | 1/2 | 2/3 × 1/2 = **1/3** |
  | → `.73` | 2/3 × 1/2 = 1/3 | 1 (no probability: always) | 1/3 × 1 = **1/3** |

  With 4 endpoints the probabilities would be 1/4, 1/3, 1/2 and then always,
  which again gives each a quarter. The `! -s 10.244.0.0/16 ... KUBE-MARK-MASQ`
  rule at the top of the chain only marks; it's explained at the end of §3.4.
- **`KUBE-SEP-*`** does the destination rewrite (DNAT) to one pod. Its first rule
  handles a pod that reaches itself through its own Service; both rules are
  explained in §3.4.

300 requests over 300 separate connections:

```
 108 web-6b4ff589f4-n8q2l
  93 web-6b4ff589f4-rn28l
  99 web-6b4ff589f4-rr8m9
```

#### Is NAT all a Service does?

**Why a ClusterIP needs NAT and a Pod IP doesn't.** A Pod IP is a real address
with a real route. A ClusterIP is neither. The node's own view:

```
$ ip route get 10.244.120.71           # a Pod IP
10.244.120.71 dev cali5e245bbbec2      ← a real interface: the veth into that Pod (made by Calico)

$ ip route get 10.106.133.73           # a ClusterIP
10.106.133.73 via 192.168.49.1 dev eth0   ← no route of its own: just the default gateway (nowhere useful)

KUBE-SERVICES rules with a Pod IP as destination:      0
KUBE-SERVICES rules with a Service IP as destination: 31
```

```
 dst = Pod IP     ─► nat rules: no match ─► routing: dev cali… ─► Pod            (no NAT)
 dst = ClusterIP  ─► nat rules: MATCH → DNAT to a Pod IP ─► routing: dev cali… ─► Pod
```

A packet to a Pod IP matches no NAT rule, so the kernel just routes it to the
Pod's interface. A packet to a ClusterIP matches a kube-proxy rule and is
rewritten to a Pod IP **before routing**. Without that rewrite it would leave
by the default gateway and never arrive, because no machine owns
`10.106.133.73`.

The rule runs in PREROUTING for packets from Pods (they arrive on a veth) and in
OUTPUT for packets the node sends itself. Only after the DNAT does the kernel look
up a route, and by then the destination is a Pod IP, so the lookup finds the Pod's
veth. The ClusterIP itself is never routed.

**In the packet path, with kube-proxy in iptables mode, yes:** DNAT to pick a
Pod, sometimes SNAT (masquerade) for node-port traffic, and conntrack to keep
each connection on its Pod (§3.5). But that's only the last step. A Service is
several pieces made by different components, and the NAT is how kube-proxy
carries out a decision made elsewhere:

| Piece | Made by | Without it |
|---|---|---|
| a stable virtual IP (the ClusterIP) | the API server, at creation | clients would chase changing Pod IPs |
| a DNS name (`web.svc-demo.svc.cluster.local`) | CoreDNS, watching Services ([core-dns.md](core-dns.md)) | clients would need the IP |
| **the member list**: which Pods, which are Ready, their actual ports | the EndpointSlice controller, from the selector and readiness (§3.6) | the NAT would have nowhere correct to send |
| the rewrite itself: DNAT, plus SNAT for node ports | **kube-proxy** (iptables here) | the ClusterIP wouldn't route anywhere (above) |
| a node port, and for `LoadBalancer` a cloud LB | the API server (port), the CCM (LB) | no way in from outside |

Several Services do **no NAT at all**, which shows the NAT isn't the essential
part:

- **Headless** (`clusterIP: None`): DNS returns the Pod IPs, and the client
  connects to them directly. There are no rules (§4.2).
- **ExternalName**: a DNS CNAME only (§4.5).
- **Behind Ingress or Gateway**: the proxy reads the member list and connects to
  Pod IPs itself ([ingress-and-gateway-api.md](ingress-and-gateway-api.md)).

Other implementations of the same Service API do the rewrite step differently
(from their docs; not tested here):

- **IPVS mode**: still NAT, through the kernel's IPVS load balancer instead of
  iptables chains.
- **nftables mode**: NAT through nftables maps ([nftables.md](nftables.md)).
- **Cilium (eBPF)**: rewrites the destination when the app calls `connect()`,
  so packets carry the Pod IP from the start. There's no per-packet NAT and no
  conntrack entry for the ClusterIP.

### 3.3 `KUBE-SERVICES` is a list, walked from the top

Each `KUBE-SERVICES` rule matches the ClusterIP as the packet's destination,
plus the protocol and port:

```
-A KUBE-SERVICES -d 10.106.133.73/32 -p tcp --dport 80 -j KUBE-SVC-6PIIFHSYVUQU3J3L
                 └─ dst = ClusterIP ┘ └─ proto + port ┘   └─ jump to this Service's chain
```

Only this first chain looks at the ClusterIP:

```
KUBE-SERVICES     match: dst = ClusterIP, proto, port      → jump to KUBE-SVC-…
KUBE-SVC-…        endpoint rules: nothing about the dst; random probability → jump to one KUBE-SEP-…
                  (only its masquerade rule repeats the ClusterIP, see below)
KUBE-SEP-…        DNAT to that Pod IP:targetPort
```

#### Five Services, and the ClusterIPs in their rules (lab)

Five Services in namespace `rules-demo`, in front of two nginx Pods
(`10.244.120.110`, `10.244.120.66`):

```
NAME             TYPE        CLUSTER-IP       PORT(S)
s1-plain         ClusterIP   10.108.226.157   80/TCP
s2-twoports      ClusterIP   10.110.239.254   80/TCP,8080/TCP
s3-nodeport      NodePort    10.103.35.246    80:32665/TCP
s4-noendpoints   ClusterIP   10.100.164.76    80/TCP          (selector matches no Pod)
s5-headless      ClusterIP   None             80/TCP
```

The ClusterIPs in `KUBE-SERVICES`, nat table:

```
-A KUBE-SERVICES -d 10.108.226.157/32 -p tcp -m comment --comment "rules-demo/s1-plain cluster IP"         -m tcp --dport 80   -j KUBE-SVC-J3F5JJQ4XO2YHCVL
-A KUBE-SERVICES -d 10.110.239.254/32 -p tcp -m comment --comment "rules-demo/s2-twoports:http cluster IP" -m tcp --dport 80   -j KUBE-SVC-DXAAFJWTOZOMTL6P
-A KUBE-SERVICES -d 10.110.239.254/32 -p tcp -m comment --comment "rules-demo/s2-twoports:alt cluster IP"  -m tcp --dport 8080 -j KUBE-SVC-IF6HV33NO22G2TSB
-A KUBE-SERVICES -d 10.103.35.246/32  -p tcp -m comment --comment "rules-demo/s3-nodeport cluster IP"      -m tcp --dport 80   -j KUBE-SVC-X66ZZSVUNJVBYS2I
...
-A KUBE-SERVICES ... -m addrtype --dst-type LOCAL -j KUBE-NODEPORTS        ← always the last rule
```

filter table:

```
-A KUBE-SERVICES -d 10.100.164.76/32 -p tcp -m comment --comment "rules-demo/s4-noendpoints has no endpoints" -m tcp --dport 80 -j REJECT --reject-with icmp-port-unreachable
```

What each Service got:

- **s1-plain**: one nat rule, ClusterIP + port 80.
- **s2-twoports**: two nat rules, same ClusterIP, one per port, each with its
  own `KUBE-SVC` chain.
- **s3-nodeport**: the same ClusterIP rule as s1. The node port `32665` is
  matched separately in `KUBE-NODEPORTS` and jumps to `KUBE-EXT-X66ZZSVUNJVBYS2I`,
  which marks for masquerade and then jumps to the same `KUBE-SVC` chain.
- **s4-noendpoints**: no nat rule at all. Its ClusterIP is only in the
  **filter** table, as a `REJECT`, so a caller gets "connection refused" at once
  instead of a timeout.
- **s5-headless**: no ClusterIP, so no rules anywhere (0 matches in nat and
  filter). DNS returns the Pod IPs directly.

s3-nodeport's two ways in:

```
Pod  → 10.103.35.246:80  → KUBE-SERVICES (ClusterIP rule)                          → KUBE-SVC → KUBE-SEP → DNAT
User → <nodeIP>:32665    → KUBE-SERVICES (last rule: --dst-type LOCAL) → KUBE-NODEPORTS → KUBE-EXT → KUBE-SVC → KUBE-SEP → DNAT
```

#### s3-nodeport's rules in full (lab)

Entry points (shared by every Service):

```
-A PREROUTING -m comment --comment "kubernetes service portals" -j KUBE-SERVICES
-A OUTPUT -m comment --comment "kubernetes service portals" -j KUBE-SERVICES
```

ClusterIP rule (the same shape as s1-plain):

```
-A KUBE-SERVICES -d 10.103.35.246/32 -p tcp -m comment --comment "rules-demo/s3-nodeport cluster IP" -m tcp --dport 80 -j KUBE-SVC-X66ZZSVUNJVBYS2I
```

Last rule in `KUBE-SERVICES` (shared, leads to the node ports):

```
-A KUBE-SERVICES -m comment --comment "kubernetes service nodeports; NOTE: this must be the last rule in this chain" -m addrtype --dst-type LOCAL -j KUBE-NODEPORTS
```

Extra: 2 rules in `KUBE-NODEPORTS`:

```
-A KUBE-NODEPORTS -d 127.0.0.0/8 -p tcp -m comment --comment "rules-demo/s3-nodeport" -m tcp --dport 32665 -m nfacct --nfacct-name  localhost_nps_accepted_pkts -j KUBE-EXT-X66ZZSVUNJVBYS2I
-A KUBE-NODEPORTS -p tcp -m comment --comment "rules-demo/s3-nodeport" -m tcp --dport 32665 -j KUBE-EXT-X66ZZSVUNJVBYS2I
```

Extra: the `KUBE-EXT` chain:

```
-A KUBE-EXT-X66ZZSVUNJVBYS2I -m comment --comment "masquerade traffic for rules-demo/s3-nodeport external destinations" -j KUBE-MARK-MASQ
-A KUBE-EXT-X66ZZSVUNJVBYS2I -j KUBE-SVC-X66ZZSVUNJVBYS2I
```

Service chain (both paths reach it):

```
-A KUBE-SVC-X66ZZSVUNJVBYS2I ! -s 10.244.0.0/16 -d 10.103.35.246/32 -p tcp -m comment --comment "rules-demo/s3-nodeport cluster IP" -m tcp --dport 80 -j KUBE-MARK-MASQ
-A KUBE-SVC-X66ZZSVUNJVBYS2I -m comment --comment "rules-demo/s3-nodeport -> 10.244.120.110:80" -m statistic --mode random --probability 0.50000000000 -j KUBE-SEP-4AN3OGJ23OWHC73U
-A KUBE-SVC-X66ZZSVUNJVBYS2I -m comment --comment "rules-demo/s3-nodeport -> 10.244.120.66:80" -j KUBE-SEP-ASTJPQBZXDN4ONBR
```

Endpoint chains:

```
-A KUBE-SEP-4AN3OGJ23OWHC73U -s 10.244.120.110/32 -m comment --comment "rules-demo/s3-nodeport" -j KUBE-MARK-MASQ
-A KUBE-SEP-4AN3OGJ23OWHC73U -p tcp -m comment --comment "rules-demo/s3-nodeport" -m tcp -j DNAT --to-destination 10.244.120.110:80
-A KUBE-SEP-ASTJPQBZXDN4ONBR -s 10.244.120.66/32 -m comment --comment "rules-demo/s3-nodeport" -j KUBE-MARK-MASQ
-A KUBE-SEP-ASTJPQBZXDN4ONBR -p tcp -m comment --comment "rules-demo/s3-nodeport" -m tcp -j DNAT --to-destination 10.244.120.66:80
```

Mark, then masquerade on the way out (shared):

```
-A KUBE-MARK-MASQ -j MARK --set-xmark 0x4000/0x4000
-A POSTROUTING -m comment --comment "kubernetes postrouting rules" -j KUBE-POSTROUTING
-A KUBE-POSTROUTING -m mark ! --mark 0x4000/0x4000 -j RETURN
-A KUBE-POSTROUTING -j MARK --set-xmark 0x4000/0x0
-A KUBE-POSTROUTING -m comment --comment "kubernetes service traffic requiring SNAT" -j MASQUERADE --random-fully
```

- **Each path marks the packet in a different place.** The masquerade rule in
  `KUBE-SVC` only matches `-d 10.103.35.246` (the ClusterIP). A packet sent to
  `nodeIP:32665` never matches it, so `KUBE-EXT` marks node-port traffic itself
  before jumping into `KUBE-SVC`.
- **The two `KUBE-SEP` chains are s3's own.** Their names differ from s1's
  (`CVGCZW3JY7AJO3NW` / `I76BNFAXYQQIF6HS`), even though both Services point at
  the same two Pods. Endpoint chains are per Service and port. The ClusterIP
  path and the node-port path of s3 share them.

What the extras are:

- **The localhost rule (`127.0.0.0/8`).** This handles `curl localhost:32665`
  from the node itself. Its `nfacct` match counts those packets.
- **The `KUBE-EXT` chain.** It marks every node-port packet for masquerade, so
  the source becomes the node IP and the reply comes back through this node.
  Then it jumps into the same `KUBE-SVC` chain as the ClusterIP path. That's why
  there are no extra `KUBE-SEP` chains.
- **`externalTrafficPolicy: Local` would change this.** `KUBE-EXT` would then
  skip the masquerade for external callers and send their traffic only to local
  endpoints (§4, "Step 4: what `KUBE-EXT` does…").

#### Every chain and table, per Service (lab)

Only `nat` and `filter` have per-Service rules. kube-proxy writes nothing per
Service in `raw` or `mangle`. s1–s3 have no rules in `filter` either: in
`filter` only the shared chains (`KUBE-FORWARD`, `KUBE-FIREWALL`…) apply to them.

##### s1-plain: ClusterIP, 1 port, 2 endpoints

nat: 1 + 3 + 2×2 = 8 rules. filter: none.

```
# nat KUBE-SERVICES
-A KUBE-SERVICES -d 10.108.226.157/32 -p tcp -m comment --comment "rules-demo/s1-plain cluster IP" -m tcp --dport 80 -j KUBE-SVC-J3F5JJQ4XO2YHCVL
# nat KUBE-SVC-J3F5JJQ4XO2YHCVL
-A KUBE-SVC-J3F5JJQ4XO2YHCVL ! -s 10.244.0.0/16 -d 10.108.226.157/32 -p tcp -m comment --comment "rules-demo/s1-plain cluster IP" -m tcp --dport 80 -j KUBE-MARK-MASQ
-A KUBE-SVC-J3F5JJQ4XO2YHCVL -m comment --comment "rules-demo/s1-plain -> 10.244.120.110:80" -m statistic --mode random --probability 0.50000000000 -j KUBE-SEP-CVGCZW3JY7AJO3NW
-A KUBE-SVC-J3F5JJQ4XO2YHCVL -m comment --comment "rules-demo/s1-plain -> 10.244.120.66:80" -j KUBE-SEP-I76BNFAXYQQIF6HS
# nat KUBE-SEP-CVGCZW3JY7AJO3NW
-A KUBE-SEP-CVGCZW3JY7AJO3NW -s 10.244.120.110/32 -m comment --comment "rules-demo/s1-plain" -j KUBE-MARK-MASQ
-A KUBE-SEP-CVGCZW3JY7AJO3NW -p tcp -m comment --comment "rules-demo/s1-plain" -m tcp -j DNAT --to-destination 10.244.120.110:80
# nat KUBE-SEP-I76BNFAXYQQIF6HS
-A KUBE-SEP-I76BNFAXYQQIF6HS -s 10.244.120.66/32 -m comment --comment "rules-demo/s1-plain" -j KUBE-MARK-MASQ
-A KUBE-SEP-I76BNFAXYQQIF6HS -p tcp -m comment --comment "rules-demo/s1-plain" -m tcp -j DNAT --to-destination 10.244.120.66:80
```

##### s2-twoports: ClusterIP, 2 ports (http 80, alt 8080 → both targetPort 80)

nat: everything doubled, one set per port, 16 rules. filter: none.

```
# nat KUBE-SERVICES
-A KUBE-SERVICES -d 10.110.239.254/32 -p tcp -m comment --comment "rules-demo/s2-twoports:http cluster IP" -m tcp --dport 80 -j KUBE-SVC-DXAAFJWTOZOMTL6P
-A KUBE-SERVICES -d 10.110.239.254/32 -p tcp -m comment --comment "rules-demo/s2-twoports:alt cluster IP" -m tcp --dport 8080 -j KUBE-SVC-IF6HV33NO22G2TSB
# nat KUBE-SVC-DXAAFJWTOZOMTL6P (http)
-A KUBE-SVC-DXAAFJWTOZOMTL6P ! -s 10.244.0.0/16 -d 10.110.239.254/32 -p tcp -m comment --comment "rules-demo/s2-twoports:http cluster IP" -m tcp --dport 80 -j KUBE-MARK-MASQ
-A KUBE-SVC-DXAAFJWTOZOMTL6P -m comment --comment "rules-demo/s2-twoports:http -> 10.244.120.110:80" -m statistic --mode random --probability 0.50000000000 -j KUBE-SEP-QNLKFVKZV5FSVJ27
-A KUBE-SVC-DXAAFJWTOZOMTL6P -m comment --comment "rules-demo/s2-twoports:http -> 10.244.120.66:80" -j KUBE-SEP-6ELI3QRSIGBIO53Q
# nat KUBE-SVC-IF6HV33NO22G2TSB (alt)
-A KUBE-SVC-IF6HV33NO22G2TSB ! -s 10.244.0.0/16 -d 10.110.239.254/32 -p tcp -m comment --comment "rules-demo/s2-twoports:alt cluster IP" -m tcp --dport 8080 -j KUBE-MARK-MASQ
-A KUBE-SVC-IF6HV33NO22G2TSB -m comment --comment "rules-demo/s2-twoports:alt -> 10.244.120.110:80" -m statistic --mode random --probability 0.50000000000 -j KUBE-SEP-OMBVVW22UAQJNODC
-A KUBE-SVC-IF6HV33NO22G2TSB -m comment --comment "rules-demo/s2-twoports:alt -> 10.244.120.66:80" -j KUBE-SEP-Z5PSQ6IU5IPEN6H2
# nat KUBE-SEP-* (http)
-A KUBE-SEP-QNLKFVKZV5FSVJ27 -s 10.244.120.110/32 -m comment --comment "rules-demo/s2-twoports:http" -j KUBE-MARK-MASQ
-A KUBE-SEP-QNLKFVKZV5FSVJ27 -p tcp -m comment --comment "rules-demo/s2-twoports:http" -m tcp -j DNAT --to-destination 10.244.120.110:80
-A KUBE-SEP-6ELI3QRSIGBIO53Q -s 10.244.120.66/32 -m comment --comment "rules-demo/s2-twoports:http" -j KUBE-MARK-MASQ
-A KUBE-SEP-6ELI3QRSIGBIO53Q -p tcp -m comment --comment "rules-demo/s2-twoports:http" -m tcp -j DNAT --to-destination 10.244.120.66:80
# nat KUBE-SEP-* (alt)
-A KUBE-SEP-OMBVVW22UAQJNODC -s 10.244.120.110/32 -m comment --comment "rules-demo/s2-twoports:alt" -j KUBE-MARK-MASQ
-A KUBE-SEP-OMBVVW22UAQJNODC -p tcp -m comment --comment "rules-demo/s2-twoports:alt" -m tcp -j DNAT --to-destination 10.244.120.110:80
-A KUBE-SEP-Z5PSQ6IU5IPEN6H2 -s 10.244.120.66/32 -m comment --comment "rules-demo/s2-twoports:alt" -j KUBE-MARK-MASQ
-A KUBE-SEP-Z5PSQ6IU5IPEN6H2 -p tcp -m comment --comment "rules-demo/s2-twoports:alt" -m tcp -j DNAT --to-destination 10.244.120.66:80
```

Both ports DNAT to the same Pod port `:80`, but each port gets its own
`KUBE-SEP` chains.

##### s3-nodeport: NodePort 80:32665

nat: s1's shape plus `KUBE-NODEPORTS` (2 rules) and `KUBE-EXT` (2 rules), 12
rules. filter: none.

```
# nat KUBE-SERVICES
-A KUBE-SERVICES -d 10.103.35.246/32 -p tcp -m comment --comment "rules-demo/s3-nodeport cluster IP" -m tcp --dport 80 -j KUBE-SVC-X66ZZSVUNJVBYS2I
# nat KUBE-NODEPORTS
-A KUBE-NODEPORTS -d 127.0.0.0/8 -p tcp -m comment --comment "rules-demo/s3-nodeport" -m tcp --dport 32665 -m nfacct --nfacct-name  localhost_nps_accepted_pkts -j KUBE-EXT-X66ZZSVUNJVBYS2I
-A KUBE-NODEPORTS -p tcp -m comment --comment "rules-demo/s3-nodeport" -m tcp --dport 32665 -j KUBE-EXT-X66ZZSVUNJVBYS2I
# nat KUBE-EXT-X66ZZSVUNJVBYS2I
-A KUBE-EXT-X66ZZSVUNJVBYS2I -m comment --comment "masquerade traffic for rules-demo/s3-nodeport external destinations" -j KUBE-MARK-MASQ
-A KUBE-EXT-X66ZZSVUNJVBYS2I -j KUBE-SVC-X66ZZSVUNJVBYS2I
# nat KUBE-SVC-X66ZZSVUNJVBYS2I
-A KUBE-SVC-X66ZZSVUNJVBYS2I ! -s 10.244.0.0/16 -d 10.103.35.246/32 -p tcp -m comment --comment "rules-demo/s3-nodeport cluster IP" -m tcp --dport 80 -j KUBE-MARK-MASQ
-A KUBE-SVC-X66ZZSVUNJVBYS2I -m comment --comment "rules-demo/s3-nodeport -> 10.244.120.110:80" -m statistic --mode random --probability 0.50000000000 -j KUBE-SEP-4AN3OGJ23OWHC73U
-A KUBE-SVC-X66ZZSVUNJVBYS2I -m comment --comment "rules-demo/s3-nodeport -> 10.244.120.66:80" -j KUBE-SEP-ASTJPQBZXDN4ONBR
# nat KUBE-SEP-*
-A KUBE-SEP-4AN3OGJ23OWHC73U -s 10.244.120.110/32 -m comment --comment "rules-demo/s3-nodeport" -j KUBE-MARK-MASQ
-A KUBE-SEP-4AN3OGJ23OWHC73U -p tcp -m comment --comment "rules-demo/s3-nodeport" -m tcp -j DNAT --to-destination 10.244.120.110:80
-A KUBE-SEP-ASTJPQBZXDN4ONBR -s 10.244.120.66/32 -m comment --comment "rules-demo/s3-nodeport" -j KUBE-MARK-MASQ
-A KUBE-SEP-ASTJPQBZXDN4ONBR -p tcp -m comment --comment "rules-demo/s3-nodeport" -m tcp -j DNAT --to-destination 10.244.120.66:80
```

##### s4-noendpoints: ClusterIP, selector matches no Pod

nat: none (no `KUBE-SVC`, no `KUBE-SEP`). filter: 1 rule.

```
# filter KUBE-SERVICES
-A KUBE-SERVICES -d 10.100.164.76/32 -p tcp -m comment --comment "rules-demo/s4-noendpoints has no endpoints" -m tcp --dport 80 -j REJECT --reject-with icmp-port-unreachable
```

##### s5-headless: `clusterIP: None`

nat: none. filter: none. Nothing to match, because there's no virtual IP. DNS
returns the Pod IPs and traffic goes straight to them.

##### Shared by all of them (written once, not per Service)

```
nat     PREROUTING / OUTPUT  -j KUBE-SERVICES
nat     KUBE-SERVICES        last rule: --dst-type LOCAL -j KUBE-NODEPORTS
nat     KUBE-MARK-MASQ       -j MARK --set-xmark 0x4000/0x4000
nat     POSTROUTING          -j KUBE-POSTROUTING → MASQUERADE if marked 0x4000
filter  FORWARD              -j KUBE-FORWARD (drop INVALID, accept marked / ESTABLISHED,RELATED)
filter  FORWARD / OUTPUT     -m conntrack --ctstate NEW -j KUBE-SERVICES  (where s4's REJECT lives)
```

Rule count per Service:

- Per port: 1 `KUBE-SERVICES` rule.
- Per port: 1 + N `KUBE-SVC` rules, where N is the number of endpoints.
- Per port and endpoint: 2 `KUBE-SEP` rules.
- NodePort: adds 4 rules.
- No endpoints: everything above is replaced by 1 filter `REJECT`.

##### Why `KUBE-SVC` has 1 + N rules

```
-A KUBE-SVC-J3F5… ! -s 10.244.0.0/16 -d 10.108.226.157/32 --dport 80 -j KUBE-MARK-MASQ    ← the "1"
-A KUBE-SVC-J3F5… --probability 0.5 -j KUBE-SEP-CVGC…   (10.244.120.110)                  ← endpoint 1
-A KUBE-SVC-J3F5…                   -j KUBE-SEP-I76B…   (10.244.120.66)                   ← endpoint 2
```

The 1: a masquerade check for callers outside the Pod network.

- **What it matches:** traffic to the ClusterIP whose source is not in
  `10.244.0.0/16`. That's the node itself, or a hostNetwork Pod.
- **Why the mark:** these callers use the node's IP, and the Pod may be on
  another node. Without SNAT, the Pod would reply straight to that address and
  skip the node that did the DNAT. The reply would then never be un-NATed.
- **What it doesn't do:** it only marks the packet and falls through. The real
  SNAT happens later, in `KUBE-POSTROUTING`.
- **When it changes:** it exists because kube-proxy knows the cluster CIDR. With
  `masqueradeAll: true`, the rule loses the `! -s` and marks every caller. With
  neither set, the rule isn't written, and the chain has only N rules. (I tested
  only the first case.)

The N: one jump per endpoint, so kube-proxy can pick one at random.

iptables has no "pick 1 of N" target, so kube-proxy writes a ladder. Each rule
takes its share of what's left:

```
N = 3:  rule 1  --probability 0.33333  (1/3 of all)
        rule 2  --probability 0.50000  (1/2 of the remaining 2/3 = 1/3)
        rule 3  no probability         (everything left = 1/3)
```

##### Why each `KUBE-SEP` has 2 rules

```
-A KUBE-SEP-CVGC… -s 10.244.120.110/32 -j KUBE-MARK-MASQ                   ← hairpin check
-A KUBE-SEP-CVGC… -p tcp -j DNAT --to-destination 10.244.120.110:80        ← the actual rewrite
```

**Rule 1: hairpin.** Pod `.110` calls its own Service, and the dice pick `.110`
itself. After the DNAT, the packet would arrive with source = dest =
`10.244.120.110`. The Pod would answer itself directly over loopback, which
skips the conntrack un-NAT. Its client would then expect a reply from the
ClusterIP and get one from the Pod IP, and the connection would break.
Masquerading makes the source the node or bridge IP, so the reply goes back
through the node. This rule can only be written per endpoint, because it has to
match "source = this endpoint's IP".

**Rule 2: DNAT.** This rule rewrites the destination to Pod IP:targetPort. It's
per endpoint because each one has a different IP and port.

##### One `KUBE-SVC` per Service port, one `KUBE-SEP` per endpoint

`KUBE-SVC` is one chain per Service port. Inside that chain there is one jump
rule per endpoint, and each jump goes to that endpoint's own `KUBE-SEP` chain:

```
Service port (s1-plain:80)   → 1 chain   KUBE-SVC-J3F5JJQ4XO2YHCVL
   ready Pod 10.244.120.110  → 1 rule in it  → 1 chain  KUBE-SEP-CVGCZW3JY7AJO3NW
   ready Pod 10.244.120.66   → 1 rule in it  → 1 chain  KUBE-SEP-I76BNFAXYQQIF6HS
```

Per Service port:

- One chain. s2-twoports has 2 ports, so it gets 2 `KUBE-SVC` chains
  (`DXAAFJ…` for http, `IF6HV3…` for alt).
- Plus one masquerade rule at the top of that chain.

Per ready endpoint (Pod):

- One jump rule inside the `KUBE-SVC` chain.
- One `KUBE-SEP` chain of its own, holding the hairpin check and the DNAT.

`KUBE-SEP` chains belong to one Service port. The same Pod gets a separate
`KUBE-SEP` chain for every Service port that selects it. Pod `10.244.120.110`
has 4 of them in the lab:

```
s1-plain           → KUBE-SEP-CVGCZW3JY7AJO3NW
s2-twoports:http   → KUBE-SEP-QNLKFVKZV5FSVJ27
s2-twoports:alt    → KUBE-SEP-OMBVVW22UAQJNODC
s3-nodeport        → KUBE-SEP-4AN3OGJ23OWHC73U
```

Only ready Pods are counted. A Pod that isn't ready gets no jump rule and no
`KUBE-SEP` chain (§3.6).

##### Two ports: every layer doubles

s2-twoports, 2 ports, 2 ready Pods:

```
KUBE-SERVICES (nat)
├─ -d 10.110.239.254 --dport 80   → KUBE-SVC-DXAAFJWTOZOMTL6P   (http)
│    ├─ ! -s 10.244.0.0/16 … → KUBE-MARK-MASQ
│    ├─ prob 0.5              → KUBE-SEP-QNLKFVKZV5FSVJ27  → DNAT 10.244.120.110:80
│    └─ (rest)                → KUBE-SEP-6ELI3QRSIGBIO53Q  → DNAT 10.244.120.66:80
│
└─ -d 10.110.239.254 --dport 8080 → KUBE-SVC-IF6HV33NO22G2TSB   (alt)
     ├─ ! -s 10.244.0.0/16 … → KUBE-MARK-MASQ
     ├─ prob 0.5              → KUBE-SEP-OMBVVW22UAQJNODC  → DNAT 10.244.120.110:80
     └─ (rest)                → KUBE-SEP-Z5PSQ6IU5IPEN6H2  → DNAT 10.244.120.66:80
```

| Layer | Per port | × 2 ports |
|---|---|---|
| `KUBE-SERVICES` rules | 1 | 2 (same ClusterIP, different `--dport`) |
| `KUBE-SVC` chains | 1, with 1 + N = 3 rules | 2 chains, 6 rules |
| `KUBE-SEP` chains | N = 2, with 2 rules each | 4 chains, 8 rules |
| Total | 8 | 16 |

The two ports never share a chain below `KUBE-SERVICES`. In this lab, both
ports DNAT to the same Pod `:80`, yet they still get separate `KUBE-SEP` chains,
`QNLK…` and `OMBV…` for Pod `.110`. A chain name is a hash of the Service port
name, the protocol and the endpoint, so a different port name always means a
different chain.

The general formula for a Service with P ports and N ready endpoints:

- P `KUBE-SERVICES` rules;
- P `KUBE-SVC` chains;
- P × N `KUBE-SEP` chains;
- P × (1 + 3N) rules in total.

##### Three ports, worked out (not tested)

The `KUBE-SEP` count is 3 × N, not N. Each port gets its own set of `KUBE-SEP`
chains, even when the ports point at the same Pods.

Three ports with N = 2 Pods:

```
KUBE-SERVICES        3 rules      one per port (same ClusterIP, different --dport)
   ├─ KUBE-SVC-A     1 + 2 rules  masquerade check + 2 jumps (prob 0.5, rest)
   │    ├─ KUBE-SEP-A1  2 rules   → Pod1
   │    └─ KUBE-SEP-A2  2 rules   → Pod2
   ├─ KUBE-SVC-B     1 + 2 rules
   │    ├─ KUBE-SEP-B1  2 rules   → Pod1
   │    └─ KUBE-SEP-B2  2 rules   → Pod2
   └─ KUBE-SVC-C     1 + 2 rules
        ├─ KUBE-SEP-C1  2 rules   → Pod1
        └─ KUBE-SEP-C2  2 rules   → Pod2
```

Counts:

- `KUBE-SERVICES` rules: 3.
- `KUBE-SVC` chains: 3. Each holds 1 + N rules, with its own probabilities. The
  chains don't share them.
- `KUBE-SEP` chains: 3 × N = 6, with 2 rules each.
- Rules in total: 3 × (1 + 3N) = 3 × 7 = 21.

Why the `KUBE-SEP` chains aren't shared: each one DNATs to Pod IP:targetPort for
its own port. The chain name is a hash of the Service port name, the protocol
and the endpoint, so `KUBE-SEP-A1` and `KUBE-SEP-B1` are different chains even
though both go to Pod1.

##### Two `KUBE-SERVICES` chains: nat and filter (lab)

```
nat    KUBE-SERVICES   Services WITH endpoints:  -d ClusterIP --dport P → KUBE-SVC-…   (then DNAT)
                       last rule: --dst-type LOCAL → KUBE-NODEPORTS
filter KUBE-SERVICES   Services WITHOUT endpoints: -d ClusterIP --dport P → REJECT
```

Which table each chain lives in:

- **nat:** `KUBE-SERVICES`, `KUBE-SVC-*`, `KUBE-SEP-*`, `KUBE-EXT-*`,
  `KUBE-NODEPORTS`, `KUBE-MARK-MASQ` and `KUBE-POSTROUTING` (the address
  rewriting).
- **filter:** `KUBE-SERVICES` (the REJECTs), `KUBE-FORWARD`,
  `KUBE-EXTERNAL-SERVICES`, `KUBE-PROXY-FIREWALL`, `KUBE-FIREWALL`, and a filter
  `KUBE-NODEPORTS` that accepts traffic to health-check node ports.

Where each chain is entered from (from the lab dumps):

```
nat    PREROUTING, OUTPUT                        -j KUBE-SERVICES
filter FORWARD, OUTPUT  (--ctstate NEW only)     -j KUBE-SERVICES
```

Hook order on the node: a packet from a Pod hits nat PREROUTING first, and the
DNAT happens there. filter FORWARD comes later. So for a Service with endpoints,
the filter chain sees the packet after the ClusterIP has already been rewritten
to a Pod IP. Nothing in filter `KUBE-SERVICES` matches it then. Only a Service
with no endpoints still has its ClusterIP at that point, and that's what the
REJECT catches.

#### Scaling 0 → 1 → 2 → 3 → 5 Pods: the rules after each step (lab)

One Deployment `web` (nginx, readiness probe), scaled 0 → 1 → 2 → 3 → 5, behind
two Services in namespace `scale-demo`: `one` (1 port) and `two` (2 ports, `http`
80 and `alt` 8080). Rules printed with kube-proxy's own `iptables-save`; the
`-m comment` parts are removed below except in the REJECT rules.

##### When the rules are written

Creating the Service is not what writes the rules. kube-proxy watches Services
and EndpointSlices, and it rewrites the rules on every change to either one.

At `replicas=0`, right after `kubectl apply`, the Service already existed but no
Pod was ready. The only rules were the filter REJECTs:

```
-A KUBE-SERVICES -d 10.99.135.185/32 -p tcp -m comment --comment "scale-demo/one:http has no endpoints" -m tcp --dport 80 -j REJECT --reject-with icmp-port-unreachable
-A KUBE-SERVICES -d 10.108.143.22/32 -p tcp -m comment --comment "scale-demo/two:alt has no endpoints" -m tcp --dport 8080 -j REJECT --reject-with icmp-port-unreachable
-A KUBE-SERVICES -d 10.108.143.22/32 -p tcp -m comment --comment "scale-demo/two:http has no endpoints" -m tcp --dport 80 -j REJECT --reject-with icmp-port-unreachable
```

There were no nat rules yet. The nat chains appeared only once a Pod became
ready and the EndpointSlice listed it. Each scale step below changed the
EndpointSlice, and kube-proxy wrote new rules each time.

##### `one` (1 port, ClusterIP 10.99.135.185)

1 Pod: 1 + 2 + 2 = 5 rules. The single jump has no probability.

```
-A KUBE-SERVICES -d 10.99.135.185/32 -p tcp -m tcp --dport 80 -j KUBE-SVC-564F3BSLYQJ35EE3
-A KUBE-SVC-564F3BSLYQJ35EE3 ! -s 10.244.0.0/16 -d 10.99.135.185/32 -p tcp -m tcp --dport 80 -j KUBE-MARK-MASQ
-A KUBE-SVC-564F3BSLYQJ35EE3 -j KUBE-SEP-3FLTUC756IU4E4TG
-A KUBE-SEP-3FLTUC756IU4E4TG -s 10.244.120.100/32 -j KUBE-MARK-MASQ
-A KUBE-SEP-3FLTUC756IU4E4TG -p tcp -m tcp -j DNAT --to-destination 10.244.120.100:80
```

2 Pods: 1 + 3 + 4 = 8 rules.

```
-A KUBE-SERVICES -d 10.99.135.185/32 -p tcp -m tcp --dport 80 -j KUBE-SVC-564F3BSLYQJ35EE3
-A KUBE-SVC-564F3BSLYQJ35EE3 ! -s 10.244.0.0/16 -d 10.99.135.185/32 -p tcp -m tcp --dport 80 -j KUBE-MARK-MASQ
-A KUBE-SVC-564F3BSLYQJ35EE3 -m statistic --mode random --probability 0.50000000000 -j KUBE-SEP-3FLTUC756IU4E4TG
-A KUBE-SVC-564F3BSLYQJ35EE3 -j KUBE-SEP-AUXFIQTBYHB4F3ID
-A KUBE-SEP-3FLTUC756IU4E4TG -s 10.244.120.100/32 -j KUBE-MARK-MASQ
-A KUBE-SEP-3FLTUC756IU4E4TG -p tcp -m tcp -j DNAT --to-destination 10.244.120.100:80
-A KUBE-SEP-AUXFIQTBYHB4F3ID -s 10.244.120.90/32 -j KUBE-MARK-MASQ
-A KUBE-SEP-AUXFIQTBYHB4F3ID -p tcp -m tcp -j DNAT --to-destination 10.244.120.90:80
```

3 Pods: 1 + 4 + 6 = 11 rules.

```
-A KUBE-SERVICES -d 10.99.135.185/32 -p tcp -m tcp --dport 80 -j KUBE-SVC-564F3BSLYQJ35EE3
-A KUBE-SVC-564F3BSLYQJ35EE3 ! -s 10.244.0.0/16 -d 10.99.135.185/32 -p tcp -m tcp --dport 80 -j KUBE-MARK-MASQ
-A KUBE-SVC-564F3BSLYQJ35EE3 -m statistic --mode random --probability 0.33333333349 -j KUBE-SEP-3FLTUC756IU4E4TG
-A KUBE-SVC-564F3BSLYQJ35EE3 -m statistic --mode random --probability 0.50000000000 -j KUBE-SEP-IJKNT5I37IJHBQOF
-A KUBE-SVC-564F3BSLYQJ35EE3 -j KUBE-SEP-AUXFIQTBYHB4F3ID
-A KUBE-SEP-3FLTUC756IU4E4TG -s 10.244.120.100/32 -j KUBE-MARK-MASQ
-A KUBE-SEP-3FLTUC756IU4E4TG -p tcp -m tcp -j DNAT --to-destination 10.244.120.100:80
-A KUBE-SEP-IJKNT5I37IJHBQOF -s 10.244.120.125/32 -j KUBE-MARK-MASQ
-A KUBE-SEP-IJKNT5I37IJHBQOF -p tcp -m tcp -j DNAT --to-destination 10.244.120.125:80
-A KUBE-SEP-AUXFIQTBYHB4F3ID -s 10.244.120.90/32 -j KUBE-MARK-MASQ
-A KUBE-SEP-AUXFIQTBYHB4F3ID -p tcp -m tcp -j DNAT --to-destination 10.244.120.90:80
```

5 Pods: 1 + 6 + 10 = 17 rules.

```
-A KUBE-SERVICES -d 10.99.135.185/32 -p tcp -m tcp --dport 80 -j KUBE-SVC-564F3BSLYQJ35EE3
-A KUBE-SVC-564F3BSLYQJ35EE3 ! -s 10.244.0.0/16 -d 10.99.135.185/32 -p tcp -m tcp --dport 80 -j KUBE-MARK-MASQ
-A KUBE-SVC-564F3BSLYQJ35EE3 -m statistic --mode random --probability 0.20000000019 -j KUBE-SEP-3FLTUC756IU4E4TG
-A KUBE-SVC-564F3BSLYQJ35EE3 -m statistic --mode random --probability 0.25000000000 -j KUBE-SEP-J6TDOIEIIE63MRAU
-A KUBE-SVC-564F3BSLYQJ35EE3 -m statistic --mode random --probability 0.33333333349 -j KUBE-SEP-G3K77TWAVWQUTLBC
-A KUBE-SVC-564F3BSLYQJ35EE3 -m statistic --mode random --probability 0.50000000000 -j KUBE-SEP-IJKNT5I37IJHBQOF
-A KUBE-SVC-564F3BSLYQJ35EE3 -j KUBE-SEP-AUXFIQTBYHB4F3ID
-A KUBE-SEP-3FLTUC756IU4E4TG -s 10.244.120.100/32 -j KUBE-MARK-MASQ
-A KUBE-SEP-3FLTUC756IU4E4TG -p tcp -m tcp -j DNAT --to-destination 10.244.120.100:80
-A KUBE-SEP-J6TDOIEIIE63MRAU -s 10.244.120.113/32 -j KUBE-MARK-MASQ
-A KUBE-SEP-J6TDOIEIIE63MRAU -p tcp -m tcp -j DNAT --to-destination 10.244.120.113:80
-A KUBE-SEP-G3K77TWAVWQUTLBC -s 10.244.120.124/32 -j KUBE-MARK-MASQ
-A KUBE-SEP-G3K77TWAVWQUTLBC -p tcp -m tcp -j DNAT --to-destination 10.244.120.124:80
-A KUBE-SEP-IJKNT5I37IJHBQOF -s 10.244.120.125/32 -j KUBE-MARK-MASQ
-A KUBE-SEP-IJKNT5I37IJHBQOF -p tcp -m tcp -j DNAT --to-destination 10.244.120.125:80
-A KUBE-SEP-AUXFIQTBYHB4F3ID -s 10.244.120.90/32 -j KUBE-MARK-MASQ
-A KUBE-SEP-AUXFIQTBYHB4F3ID -p tcp -m tcp -j DNAT --to-destination 10.244.120.90:80
```

The probability ladder is 1/5, 1/4, 1/3, 1/2, then the rest, so every Pod gets
20%.

##### `two`, 2 Pods with 2 ports (ClusterIP 10.108.143.22)

2 × (1 + 3 × 2) = 14 rules.

```
-A KUBE-SERVICES -d 10.108.143.22/32 -p tcp -m tcp --dport 80 -j KUBE-SVC-KBK3S454ZKMKCNCK       # two:http
-A KUBE-SERVICES -d 10.108.143.22/32 -p tcp -m tcp --dport 8080 -j KUBE-SVC-CAI52UTZW7PK2YWI     # two:alt

-A KUBE-SVC-KBK3S454ZKMKCNCK ! -s 10.244.0.0/16 -d 10.108.143.22/32 -p tcp -m tcp --dport 80 -j KUBE-MARK-MASQ
-A KUBE-SVC-KBK3S454ZKMKCNCK -m statistic --mode random --probability 0.50000000000 -j KUBE-SEP-LRKRNHR3RCOF6KT6
-A KUBE-SVC-KBK3S454ZKMKCNCK -j KUBE-SEP-UJCWXTIB3CMNYILJ

-A KUBE-SVC-CAI52UTZW7PK2YWI ! -s 10.244.0.0/16 -d 10.108.143.22/32 -p tcp -m tcp --dport 8080 -j KUBE-MARK-MASQ
-A KUBE-SVC-CAI52UTZW7PK2YWI -m statistic --mode random --probability 0.50000000000 -j KUBE-SEP-2TGTJHXKHMNKLCRE
-A KUBE-SVC-CAI52UTZW7PK2YWI -j KUBE-SEP-T2WJ4TN54P4REABD

-A KUBE-SEP-LRKRNHR3RCOF6KT6 -s 10.244.120.100/32 -j KUBE-MARK-MASQ                               # http → .100
-A KUBE-SEP-LRKRNHR3RCOF6KT6 -p tcp -m tcp -j DNAT --to-destination 10.244.120.100:80
-A KUBE-SEP-UJCWXTIB3CMNYILJ -s 10.244.120.90/32 -j KUBE-MARK-MASQ                                # http → .90
-A KUBE-SEP-UJCWXTIB3CMNYILJ -p tcp -m tcp -j DNAT --to-destination 10.244.120.90:80
-A KUBE-SEP-2TGTJHXKHMNKLCRE -s 10.244.120.100/32 -j KUBE-MARK-MASQ                               # alt → .100
-A KUBE-SEP-2TGTJHXKHMNKLCRE -p tcp -m tcp -j DNAT --to-destination 10.244.120.100:80
-A KUBE-SEP-T2WJ4TN54P4REABD -s 10.244.120.90/32 -j KUBE-MARK-MASQ                                # alt → .90
-A KUBE-SEP-T2WJ4TN54P4REABD -p tcp -m tcp -j DNAT --to-destination 10.244.120.90:80
```

The counts match P × (1 + 3N).

Chain names stay the same while the Service scales.
`KUBE-SEP-3FLTUC756IU4E4TG` (Pod `.100`) kept its name from 1 Pod to 5. The name
is a hash of the Service port name, the protocol and the endpoint, so adding
Pods doesn't rename existing chains. kube-proxy only adds new `KUBE-SEP` chains
and rewrites the `KUBE-SVC` chain with new probabilities.

#### A NodePort Service, one port, every rule (lab)

One Service `np` in `np-demo`, one port (`80:30080`), and 2 nginx Pods
(`10.244.120.65`, `10.244.120.95`).

```
NAME  TYPE      CLUSTER-IP   PORT(S)
np    NodePort  10.97.69.78  80:30080/TCP
```

1. Shared entry points (exist for every Service):

```
-A PREROUTING -m comment --comment "kubernetes service portals" -j KUBE-SERVICES
-A OUTPUT -m comment --comment "kubernetes service portals" -j KUBE-SERVICES
```

2. `KUBE-SERVICES`: the ClusterIP rule, plus the shared last rule:

```
-A KUBE-SERVICES -d 10.97.69.78/32 -p tcp -m comment --comment "np-demo/np:http cluster IP" -m tcp --dport 80 -j KUBE-SVC-DXBLXFCWJ7EEPMJQ
-A KUBE-SERVICES -m comment --comment "kubernetes service nodeports; NOTE: this must be the last rule in this chain" -m addrtype --dst-type LOCAL -j KUBE-NODEPORTS
```

3. `KUBE-NODEPORTS`: 2 rules, both for the node port:

```
-A KUBE-NODEPORTS -d 127.0.0.0/8 -p tcp -m comment --comment "np-demo/np:http" -m tcp --dport 30080 -m nfacct --nfacct-name  localhost_nps_accepted_pkts -j KUBE-EXT-DXBLXFCWJ7EEPMJQ
-A KUBE-NODEPORTS -p tcp -m comment --comment "np-demo/np:http" -m tcp --dport 30080 -j KUBE-EXT-DXBLXFCWJ7EEPMJQ
```

4. `KUBE-EXT`: 2 rules, mark for SNAT, then into the Service chain:

```
-A KUBE-EXT-DXBLXFCWJ7EEPMJQ -m comment --comment "masquerade traffic for np-demo/np:http external destinations" -j KUBE-MARK-MASQ
-A KUBE-EXT-DXBLXFCWJ7EEPMJQ -j KUBE-SVC-DXBLXFCWJ7EEPMJQ
```

5. `KUBE-SVC`: 1 + N = 3 rules (same shape as a ClusterIP Service):

```
-A KUBE-SVC-DXBLXFCWJ7EEPMJQ ! -s 10.244.0.0/16 -d 10.97.69.78/32 -p tcp -m comment --comment "np-demo/np:http cluster IP" -m tcp --dport 80 -j KUBE-MARK-MASQ
-A KUBE-SVC-DXBLXFCWJ7EEPMJQ -m comment --comment "np-demo/np:http -> 10.244.120.65:80" -m statistic --mode random --probability 0.50000000000 -j KUBE-SEP-Q2DQUFAJY7UBHOLW
-A KUBE-SVC-DXBLXFCWJ7EEPMJQ -m comment --comment "np-demo/np:http -> 10.244.120.95:80" -j KUBE-SEP-CTS7ZA7P2QU73KPO
```

6. `KUBE-SEP`: 2 × N = 4 rules:

```
-A KUBE-SEP-Q2DQUFAJY7UBHOLW -s 10.244.120.65/32 -m comment --comment "np-demo/np:http" -j KUBE-MARK-MASQ
-A KUBE-SEP-Q2DQUFAJY7UBHOLW -p tcp -m comment --comment "np-demo/np:http" -m tcp -j DNAT --to-destination 10.244.120.65:80
-A KUBE-SEP-CTS7ZA7P2QU73KPO -s 10.244.120.95/32 -m comment --comment "np-demo/np:http" -j KUBE-MARK-MASQ
-A KUBE-SEP-CTS7ZA7P2QU73KPO -p tcp -m comment --comment "np-demo/np:http" -m tcp -j DNAT --to-destination 10.244.120.95:80
```

7. Shared SNAT on the way out:

```
-A KUBE-MARK-MASQ -j MARK --set-xmark 0x4000/0x4000
-A POSTROUTING -m comment --comment "kubernetes postrouting rules" -j KUBE-POSTROUTING
-A KUBE-POSTROUTING -m mark ! --mark 0x4000/0x4000 -j RETURN
-A KUBE-POSTROUTING -j MARK --set-xmark 0x4000/0x0
-A KUBE-POSTROUTING -m comment --comment "kubernetes service traffic requiring SNAT" -j MASQUERADE --random-fully
```

filter table: nothing for this Service. The Service has endpoints, so there's no
REJECT. With the default `externalTrafficPolicy: Cluster` there's no
health-check port either.

Count:

```
ClusterIP part   1 (KUBE-SERVICES) + 3 (KUBE-SVC) + 4 (KUBE-SEP) = 8   ← same as a ClusterIP Service
NodePort extra   2 (KUBE-NODEPORTS) + 2 (KUBE-EXT)                = 4
total                                                               12   (steps 2–6 show 13 lines: these 12 + the shared last rule of KUBE-SERVICES)
```

In general a NodePort Service has P × (1 + 3N) + 4P rules. Each port adds 2
`KUBE-NODEPORTS` rules and 2 `KUBE-EXT` rules.

The `np` chains as a tree (nat table):

```
PREROUTING / OUTPUT
└─ KUBE-SERVICES
   ├─ -d 10.97.69.78 --dport 80 ─────────────────────────────┐   ClusterIP path
   │                                                         │
   └─ (last) --dst-type LOCAL                                │   node-port path
      └─ KUBE-NODEPORTS            (shared by all NodePorts) │
         ├─ -d 127.0.0.0/8 --dport 30080 ─┐                  │
         └─ --dport 30080 ────────────────┤                  │
                                          ▼                  │
            KUBE-EXT-DXBLXFCWJ7EEPMJQ                        │
            ├─ → KUBE-MARK-MASQ  (always)                    │
            └─ → KUBE-SVC ───────────────────────────────────┤
                                                             ▼
                              KUBE-SVC-DXBLXFCWJ7EEPMJQ
                              ├─ ! -s 10.244.0.0/16 -d 10.97.69.78 → KUBE-MARK-MASQ
                              ├─ prob 0.5 → KUBE-SEP-Q2DQUFAJY7UBHOLW
                              │             ├─ -s 10.244.120.65 → KUBE-MARK-MASQ   (hairpin)
                              │             └─ DNAT → 10.244.120.65:80
                              └─ (rest)   → KUBE-SEP-CTS7ZA7P2QU73KPO
                                            ├─ -s 10.244.120.95 → KUBE-MARK-MASQ   (hairpin)
                                            └─ DNAT → 10.244.120.95:80

POSTROUTING
└─ KUBE-POSTROUTING   marked 0x4000? → MASQUERADE (source = node IP)  else RETURN
```

The two paths side by side, with what happens to the packet:

```
                  Pod → np:80                          client → 192.168.49.2:30080
                  src 10.244.x.y                       src <client>
                  dst 10.97.69.78:80                   dst 192.168.49.2:30080
                        │                                      │
KUBE-SERVICES     matches ClusterIP rule              no ClusterIP rule matches,
                        │                             last rule (dst is LOCAL)
                        │                                      │
KUBE-NODEPORTS          │                             --dport 30080
                        │                                      │
KUBE-EXT                │                             mark 0x4000
                        ▼                                      ▼
KUBE-SVC          source in Pod CIDR: no mark  ──── same chain ────  (masq rule doesn't
                  pick a Pod (50 / 50)                               match: dst is node IP)
                        │                                      │
KUBE-SEP          DNAT → 10.244.120.65:80 or .95:80   DNAT → 10.244.120.65:80 or .95:80
                        │                                      │
KUBE-POSTROUTING  not marked → no SNAT                marked → SNAT src to node IP
                        ▼                                      ▼
                  Pod sees src = calling Pod          Pod sees src = node IP
```

Which rule each curl hit. I ran two curls from the node, and both returned 200:

```
curl http://192.168.49.2:30080/   → 200
curl http://127.0.0.1:30080/      → 200
```

```
KUBE-NODEPORTS  pkts
   1   -d 127.0.0.0/8 --dport 30080 → KUBE-EXT    ← the 127.0.0.1 curl
   1             --dport 30080      → KUBE-EXT    ← the 192.168.49.2 curl
KUBE-EXT-DXBLXFCWJ7EEPMJQ
   2   → KUBE-MARK-MASQ                           ← both marked
   2   → KUBE-SVC-DXBLXFCWJ7EEPMJQ                ← both into the same Service chain
```

#### One Service's full path (s1-plain) (lab)

```
KUBE-SERVICES  -d 10.108.226.157/32 --dport 80 → KUBE-SVC-J3F5JJQ4XO2YHCVL

KUBE-SVC-J3F5JJQ4XO2YHCVL
  ! -s 10.244.0.0/16 -d 10.108.226.157/32 --dport 80 → KUBE-MARK-MASQ    (callers from outside the Pod network)
  -> 10.244.120.110:80  --probability 0.50000000000   → KUBE-SEP-CVGCZW3JY7AJO3NW
  -> 10.244.120.66:80   (no probability: the rest)    → KUBE-SEP-I76BNFAXYQQIF6HS

KUBE-SEP-CVGCZW3JY7AJO3NW
  -s 10.244.120.110/32 → KUBE-MARK-MASQ                                  (hairpin: the Pod calling itself)
  -p tcp -j DNAT --to-destination 10.244.120.110:80
```

The ClusterIP appears in two places:

- the `KUBE-SERVICES` match;
- the masquerade rule in `KUBE-SVC`.

The `KUBE-SEP` chains never mention it. They only know the Pod IP.

#### Why some printed rules have no `--dport` (lab)

The same rules printed two ways:

- `minikube ssh -- sudo iptables-save -t nat`: the node's `iptables v1.8.7 (nf_tables)`.
  The `-m tcp --dport 80` part is missing from every Service rule.
- `kubectl -n kube-system exec <kube-proxy pod> -- iptables-save -t nat`:
  kube-proxy's own `iptables v1.8.9 (nf_tables)`. `--dport` is there.

The rules in the kernel are the same. The older binary can't fully decode the
port match that the newer one wrote, so it leaves it out of the printout. Some
lab output elsewhere in this note was taken from the node and shows rules
without `--dport` for this reason. Read rules with the iptables binary inside
the kube-proxy Pod.

Three Services, one of them with two ports:

```yaml
alpha:   ports: [{name: http,  port: 80}]
bravo:   ports: [{name: http,  port: 80}, {name: https, port: 443}]
charlie: ports: [{name: http,  port: 80}]
```

```
NAME      CLUSTER-IP      PORT(S)
alpha     10.98.84.12     80/TCP
bravo     10.111.109.97   80/TCP,443/TCP
charlie   10.98.136.130   80/TCP
```

The whole chain on the node, alongside the `web` Service from §2 and the
built-in `kubernetes` and `kube-dns` Services:

```
$ iptables -t nat -L KUBE-SERVICES -n --line-numbers
num  target                     prot  destination     comment
1    KUBE-SVC-IGTRU775QHYZG4VA  tcp   10.107.59.191   svc-demo/web cluster IP
2    KUBE-SVC-JD5MR3NA4I4DYORP  tcp   10.96.0.10      kube-system/kube-dns:metrics cluster IP
3    KUBE-SVC-72C52PYTL7I523BG  tcp   10.98.136.130   svc-demo/charlie:http cluster IP
4    KUBE-SVC-NPX46M4PTMTKRN6Y  tcp   10.96.0.1       default/kubernetes:https cluster IP
5    KUBE-SVC-ERIFXISQEP7F7OF4  tcp   10.96.0.10      kube-system/kube-dns:dns-tcp cluster IP
6    KUBE-SVC-4EE5ZY6T4VGEIYEW  tcp   10.98.84.12     svc-demo/alpha:http cluster IP
7    KUBE-SVC-VZ5KWM7DHWWRYRAQ  tcp   10.111.109.97   svc-demo/bravo:http cluster IP
8    KUBE-SVC-CWRDXECOLI2BS3IP  tcp   10.111.109.97   svc-demo/bravo:https cluster IP
9    KUBE-SVC-TCOU7JCQXEZGVUNU  udp   10.96.0.10      kube-system/kube-dns:dns cluster IP
10   KUBE-NODEPORTS             all   (local addresses)  must be the last rule in this chain
```

What it shows:

- **One entry per IP and port, not per Service.** `bravo` appears twice (7 and 8),
  with the same IP and different ports. `kube-dns` appears three times (DNS over
  UDP, DNS over TCP, metrics).
- **The order isn't meaningful.** It isn't creation order (alpha, bravo,
  charlie) or alphabetical. kube-proxy writes the entries in map iteration order,
  so a Service's position is arbitrary and can change between syncs.
- **`KUBE-NODEPORTS` is always last.** Traffic to a node's own address only
  reaches NodePort handling after every ClusterIP entry above it has failed to
  match.

To show the walk itself, I inserted four probe rules into the chain. Each one
matches only `10.111.109.97:443` and has no target, so it just counts the
packet and lets it carry on. Then I opened one new connection to `bravo:443`:

```
num  pkts  target                     destination       comment
1    1                                10.111.109.97:443 PROBE-1 top of chain
2    0     KUBE-SVC-IGTRU775QHYZG4VA  10.107.59.191     svc-demo/web
3    0     KUBE-SVC-JD5MR3NA4I4DYORP  10.96.0.10        kube-dns:metrics
4    0     KUBE-SVC-72C52PYTL7I523BG  10.98.136.130     svc-demo/charlie:http
5    1                                10.111.109.97:443 PROBE-2 after charlie
6    2     KUBE-SVC-NPX46M4PTMTKRN6Y  10.96.0.1         default/kubernetes:https
7    0     KUBE-SVC-ERIFXISQEP7F7OF4  10.96.0.10        kube-dns:dns-tcp
8    0     KUBE-SVC-4EE5ZY6T4VGEIYEW  10.98.84.12       svc-demo/alpha:http
9    0     KUBE-SVC-VZ5KWM7DHWWRYRAQ  10.111.109.97     svc-demo/bravo:http
10   1                                10.111.109.97:443 PROBE-3 just before bravo:https
11   4     KUBE-SVC-CWRDXECOLI2BS3IP  10.111.109.97     svc-demo/bravo:https   <- match
12   4     KUBE-SVC-TCOU7JCQXEZGVUNU  10.96.0.10        kube-dns:dns (udp)
13   0                                10.111.109.97:443 PROBE-4 after bravo:https
14   475   KUBE-NODEPORTS                               (local addresses)
```

The packet incremented PROBE-1, PROBE-2 and PROBE-3, then matched
`bravo:https` and jumped to its `KUBE-SVC` chain. PROBE-4, below the match,
stayed at **0**. So the packet was compared against every Service entry above
its own, and against none below it.

Some counters include other traffic:

- `bravo:https` shows 4 because of three earlier test requests.
- `kube-dns:dns` counts the curl's own DNS lookups for `bravo`, which take the
  same walk.
- `KUBE-NODEPORTS` counts connections to the node itself.

Two notes on the method:

- **Why probes instead of a packet trace.** The `TRACE` target matched the packet
  (counter 1), but this node's kernel delivered no trace events to
  `xtables-monitor`. The probes need nothing beyond counters.
- **Probes are temporary.** kube-proxy rewrites `KUBE-SERVICES` on its next sync,
  which would wipe them anyway. I deleted them straight after.

The walk costs a few rule comparisons here. In a cluster with 10,000 Services
it's up to 10,000 comparisons on the first packet of every new connection. That
is the linear cost in §6.1, and the reason the nftables proxier replaces the
list with a single map lookup.

#### A Service with several ports is handled one port at a time

`KUBE-SERVICES` gets one rule **per Service port, per address the Service can
be reached on**: its ClusterIP, each external IP, each LB IP. A multi-port
Service is handled almost as if each port were a Service of its own. Here's
`bravo`, rechecked:

```
$ kubectl -n svc-demo get svc bravo        (ports)
http   port=80   targetPort=8080  TCP
https  port=443  targetPort=8080  TCP

$ kubectl -n svc-demo get endpointslice -l kubernetes.io/service-name=bravo
bravo-rcn94   ports: http=8080 https=8080   addresses: 10.244.120.71 10.244.120.74 10.244.120.115
```

Its full rule set (from the kube-proxy Pod's `iptables-save -t nat`, trimmed):

```
# KUBE-SERVICES: one rule per port. Same ClusterIP, different --dport, different KUBE-SVC chain
-A KUBE-SERVICES -d 10.111.109.97/32 -p tcp --comment "svc-demo/bravo:http cluster IP"  --dport 80  -j KUBE-SVC-VZ5KWM7DHWWRYRAQ
-A KUBE-SERVICES -d 10.111.109.97/32 -p tcp --comment "svc-demo/bravo:https cluster IP" --dport 443 -j KUBE-SVC-CWRDXECOLI2BS3IP

# KUBE-SVC for http (port 80): its own pick and its own KUBE-SEP chains
-A KUBE-SVC-VZ5KWM7DHWWRYRAQ ! -s 10.244.0.0/16 -d 10.111.109.97/32 -p tcp --dport 80 -j KUBE-MARK-MASQ
-A KUBE-SVC-VZ5KWM7DHWWRYRAQ --comment "svc-demo/bravo:http -> 10.244.120.115:8080" --probability 0.33333333349 -j KUBE-SEP-72LUBGJ32DNUJ2QW
-A KUBE-SVC-VZ5KWM7DHWWRYRAQ --comment "svc-demo/bravo:http -> 10.244.120.71:8080"  --probability 0.50000000000 -j KUBE-SEP-C67JJALQJ2RLBSHX
-A KUBE-SVC-VZ5KWM7DHWWRYRAQ --comment "svc-demo/bravo:http -> 10.244.120.74:8080"                              -j KUBE-SEP-LKCCMXE3PHT2RNF6

# KUBE-SVC for https (port 443): a separate pick and separate KUBE-SEP chains, to the same Pods
-A KUBE-SVC-CWRDXECOLI2BS3IP ! -s 10.244.0.0/16 -d 10.111.109.97/32 -p tcp --dport 443 -j KUBE-MARK-MASQ
-A KUBE-SVC-CWRDXECOLI2BS3IP --comment "svc-demo/bravo:https -> 10.244.120.115:8080" --probability 0.33333333349 -j KUBE-SEP-RWW5GILLLLGWNIPK
-A KUBE-SVC-CWRDXECOLI2BS3IP --comment "svc-demo/bravo:https -> 10.244.120.71:8080"  --probability 0.50000000000 -j KUBE-SEP-NVMGK5PS2HS6HMF3
-A KUBE-SVC-CWRDXECOLI2BS3IP --comment "svc-demo/bravo:https -> 10.244.120.74:8080"                              -j KUBE-SEP-GN5YKV2OTIJ6QMOZ

# KUBE-SEP: one chain per (port, Pod), so 2 × 3 = 6. One pair shown per port:
-A KUBE-SEP-72LUBGJ32DNUJ2QW -s 10.244.120.115/32 --comment "svc-demo/bravo:http"  -j KUBE-MARK-MASQ
-A KUBE-SEP-72LUBGJ32DNUJ2QW -p tcp               --comment "svc-demo/bravo:http"  -j DNAT --to-destination 10.244.120.115:8080
-A KUBE-SEP-RWW5GILLLLGWNIPK -s 10.244.120.115/32 --comment "svc-demo/bravo:https" -j KUBE-MARK-MASQ
-A KUBE-SEP-RWW5GILLLLGWNIPK -p tcp               --comment "svc-demo/bravo:https" -j DNAT --to-destination 10.244.120.115:8080
```

| Per port | `bravo` (2 ports, 3 Pods) |
|---|---|
| a `KUBE-SERVICES` rule for each address (ClusterIP here) | 2 |
| a `KUBE-SVC-…` chain with its own random pick | 2 |
| a `KUBE-SEP-…` chain for each Pod | 2 × 3 = 6 |

What follows:

- **Chain names are per port.** They're hashes of
  `namespace/name:portname/protocol`, so `bravo:http` and `bravo:https` get
  unrelated names. The comments show the port name.
- **Each port makes its own pick.** A connection to `:80` and a connection to
  `:443` from the same client are separate random picks, and can land on
  different Pods. Nothing ties them together. `sessionAffinity: ClientIP` is
  also per port.
- **Ports must have names when there's more than one.** The API requires it.
  The names (`http`, `https`) are how the EndpointSlice's ports (`http=8080
  https=8080`) are matched to the Service's ports.
- **Two ports can go to the same `targetPort`.** That's the case here: 80 and 443
  both go to 8080, which is how a plain-HTTP request to `bravo:443` worked in
  the DNAT/SNAT examples in §3.5. Or each port can go to a different container port.

**What the chain holds on the lab** (all namespaces; more entries
than in the 10-rule listing above, which was taken earlier):

| `KUBE-SERVICES` rule kind | Count | One per |
|---|---|---|
| `cluster IP` | 31 | Service port with a ClusterIP **and at least one endpoint** |
| `loadbalancer IP` | 3 | Service port × LB IP: `t-lb:8090`, plus Envoy's `http-80` and `https-443` (all on `127.0.0.1`, from `minikube tunnel`) |
| `external IP` | 1 | Service port × external IP: `t-extip` → `192.168.49.200:80` |
| jump to `KUBE-NODEPORTS` | 1 | always the last rule, for every node port |
| **total** | **36** | from 23 Services with a ClusterIP, which have 32 ports between them |

**A port with no endpoints gets no nat rule at all.** The 32nd port,
`dns-demo/empty:80`, has no endpoints. kube-proxy doesn't add it to the nat
`KUBE-SERVICES`. It puts it in the **filter** table's `KUBE-SERVICES`, which
rejects the connection at once instead of letting it hang:

```
$ iptables-save -t filter | grep 'has no endpoints'
-A KUBE-SERVICES -d 10.109.169.18/32 -p tcp -m comment --comment "dns-demo/empty has no endpoints" -m tcp --dport 80 -j REJECT --reject-with icmp-port-unreachable
```

An ICMP port-unreachable makes a TCP client fail at once with "connection
refused", instead of timing out. That's from how TCP handles the ICMP error;
it wasn't tried against `empty` here.

### 3.4 Inside a `KUBE-SEP` chain: two rules

Each endpoint chain has exactly two rules. This one is for pod
`web-7b6bc8bc5-p8nbc` (`10.244.120.90`):

```
-A KUBE-SEP-FFXFUKGXSWYVFB47 -s 10.244.120.90/32 -m comment --comment "svc-demo/web" -j KUBE-MARK-MASQ
-A KUBE-SEP-FFXFUKGXSWYVFB47 -p tcp -m comment --comment "svc-demo/web" -m tcp -j DNAT --to-destination 10.244.120.90:8080
```

**Rule 2 is the actual rewrite.** It changes the destination from the
ClusterIP to `10.244.120.90:8080`, and conntrack records the change (§3.5).
Everything else in the Service machinery exists to pick which `KUBE-SEP` chain
the packet reaches.

**Rule 1 handles hairpin traffic.** It fires only when the source is the same
pod as this endpoint, meaning the pod called its own Service and was load
balanced back to itself. It doesn't rewrite anything; it marks the packet for
a source rewrite (SNAT) later on:

```
-A KUBE-MARK-MASQ -j MARK --set-xmark 0x4000/0x4000
-A KUBE-POSTROUTING -m mark ! --mark 0x4000/0x4000 -j RETURN
-A KUBE-POSTROUTING -j MARK --set-xmark 0x4000/0x0
-A KUBE-POSTROUTING -m comment --comment "kubernetes service traffic requiring SNAT" -j MASQUERADE --random-fully
```

- `KUBE-MARK-MASQ` only sets mark bit `0x4000`.
- At the end of routing, `KUBE-POSTROUTING` lets unmarked packets go
  (`RETURN`).
- Marked packets have the bit cleared, so they aren't rewritten twice, and then
  their source is rewritten to the outgoing interface's address (`MASQUERADE`).

On this node that address is `10.244.120.64`, Calico's `tunl0`. These rules
are written in `syncProxyRules` in `pkg/proxy/iptables/proxier.go`.

> **Listing gotcha:** the node's `iptables v1.8.7` printed the first
> `KUBE-POSTROUTING` rule as a bare `-j RETURN`, as if nothing could ever reach
> `MASQUERADE`. kube-proxy writes the rules with its own `iptables v1.8.9`,
> which uses a match encoding the older binary can't display. The counters
> showed the truth: 1,311 packets returned early and 2 were masqueraded.
> `kubectl -n kube-system exec ds/kube-proxy -- iptables-save -t nat` prints
> the real rule.

**What rule 1 does to the source IP.** I called the Service 30 times from
`p8nbc` itself. The endpoint's `/clientip` reports the source address it
received:

```
  18 10.244.120.90    # served by the other two pods: real source, no SNAT
  12 10.244.120.64    # balanced back to p8nbc itself: SNAT to the node's tunl0 address
```

**What breaks without it.** I deleted rule 1 from this chain only, ran the same
30 requests, then put the rule back:

```
rule 1 removed:   20 10.244.120.90    10 TIMEOUT
rule 1 restored:  15 10.244.120.90    15 10.244.120.64
```

Every request that landed back on the calling pod hung. Here is why, reasoned
from the outputs rather than captured with a packet trace:

1. After the DNAT, the packet reads `src=10.244.120.90 dst=10.244.120.90:8080`,
   so the pod is talking to its own address.
2. The pod sends its reply to itself inside its own network namespace. The
   reply never passes back through the node, so conntrack never gets the chance
   to reverse the DNAT.
3. The client socket is connected to `10.107.59.191:80` but gets a reply from
   `10.244.120.90:8080`. It doesn't recognise the reply and waits until it
   times out.

Rewriting the source to the node's address forces the reply back through the
node, where conntrack reverses both rewrites.

`KUBE-SVC` chains carry a similar mark rule one level up
(`! -s 10.244.0.0/16 -d 10.107.59.191 -j KUBE-MARK-MASQ`, §3.2). It covers
callers outside the pod CIDR, such as processes on the node itself or
host-network pods, whose replies would otherwise bypass the NAT too.

### 3.5 conntrack pins the connection

The NAT decision is made once, on the first packet. The kernel's connection
table records it. With one connection held open, the node showed:

```
$ conntrack -L -d 10.107.59.191
tcp ESTABLISHED src=10.244.120.74 dst=10.107.59.191 sport=50846 dport=80 ...
                src=10.244.120.87 dst=10.244.120.74 sport=8080 dport=50846 [ASSURED]
```

The client sent its packets to `10.107.59.191:80`, and the replies come back from
the pod `10.244.120.87:8080`.

An entry holds two directions, and the NAT is visible in the difference
between them. A full entry, taken after one `curl` (with `minikube ssh --
sudo conntrack -L -d 10.107.59.191`):

```
tcp 6 111 TIME_WAIT src=10.244.120.125 dst=10.107.59.191 sport=45060 dport=80    packets=6 bytes=394
                    src=10.244.120.116 dst=10.244.120.125 sport=8080 dport=45060 packets=4 bytes=352 [ASSURED] mark=0 use=1
```

| Field | Meaning |
|---|---|
| `tcp 6` | Protocol name and number |
| `111` | Seconds until this entry expires |
| `TIME_WAIT` | TCP state; the connection is closed and the entry is waiting out its timer |
| 1st `src= dst= sport= dport=` | The **original** direction, as the client sent it: to the ClusterIP, port 80 |
| 2nd `src= dst= sport= dport=` | The **reply** direction the kernel expects: from pod `.116` port 8080 |
| `packets= bytes=` | Counted per direction |
| `[ASSURED]` | Traffic was seen both ways, so the entry won't be evicted early |

The original direction has `dst=10.107.59.191`, but the expected reply has
`src=10.244.120.116`. That mismatch *is* the DNAT. Every later packet of the
connection is rewritten from this entry, without walking `KUBE-SERVICES`
again, and replies from `.116` are rewritten back to look as if they came
from `10.107.59.191:80`.

**Do we track the Pod IP or the ClusterIP? Both, in one entry.** Here's one
connection from the `t-nodeport` Service (ClusterIP `10.106.133.73`), taken on
From a test Pod `10.244.120.66`:

```
tcp ESTABLISHED→TIME_WAIT
  ORIGINAL (what the client sent):   src=10.244.120.66  dst=10.106.133.73  sport=56004 dport=80     ← ClusterIP:port
  REPLY    (what really answers):    src=10.244.120.71  dst=10.244.120.66  sport=8080  dport=56004  ← Pod IP:targetPort
```

One entry, two sides. The first side is the connection as the client sees
it: to the ClusterIP. The second is the same connection as it really is: with
the Pod `10.244.120.71:8080`, which is `web-…-bcjj6`.

```
 client 10.244.120.66:56004                                     Pod 10.244.120.71:8080 (bcjj6)
   │ SYN  dst=10.106.133.73:80                                     ▲
   ▼                                                               │
 node kernel: first packet → walk kube-proxy rules → random pick → DNAT dst to 10.244.120.71:8080
              and WRITE the pair into conntrack ─────────────────────┘
   │
   │ every later packet of THIS connection (same 4 numbers: client IP, client port, ClusterIP, 80)
   │   → found in conntrack → same rewrite, no rule walk, no new pick
   │
   │ replies from the Pod (src 10.244.120.71:8080)
   │   → matched by the REPLY side → source rewritten back to 10.106.133.73:80
   ▼
 client sees answers "from 10.106.133.73:80". It never learns the Pod IP.
```

**New connections walk the rules. Later packets skip the rules but are still
rewritten** (proved packet by packet in "Every packet is rewritten" below).
conntrack only matches packets of a connection it has already recorded. A new connection has a new client port, so it misses conntrack and
walks the rules again, with a new random pick. You can see this in the rule
counters (`iptables -t nat -L <chain> -v -n -x`, run in the kube-proxy Pod).
They count **packets that matched each rule**. Here are 5 `wget`s from
`web-…-bcjj6` (`10.244.120.71`) to the `web` ClusterIP:

```
                                                 before → after
KUBE-SERVICES  web ClusterIP rule                     0 → 5
KUBE-SVC       → .115  (probability 0.333)            0 → 1
               → .71   (probability 0.5)              0 → 3     ← bcjj6 picked itself 3 times
               → .74   (no condition)                 0 → 1
                                                          ─
                                                          5     one per connection
```

The connections themselves, from `conntrack -L -s 10.244.120.71 -d 10.107.59.191`:

```
sport=57382 packets=6   REPLY src=10.244.120.71  dst=10.244.120.64   ← itself (hairpin SNAT)
sport=57394 packets=5   REPLY src=10.244.120.71  dst=10.244.120.64   ← itself
sport=57392 packets=6   REPLY src=10.244.120.71  dst=10.244.120.64   ← itself
sport=57402 packets=6   REPLY src=10.244.120.115 dst=10.244.120.71
sport=57386 packets=6   REPLY src=10.244.120.74  dst=10.244.120.71
                  ──
                  29 packets from the client; the rules counted 5
```

| | Walks the nat rules? | Rewritten by |
|---|---|---|
| First packet of a connection (the SYN) | yes | the rules: a new random pick, then DNAT |
| Every later packet, both directions | no | the conntrack entry: still rewritten, the same way as the first |
| A new connection, even from the same client | yes | the rules again: possibly a different Pod |

So 29 packets in 5 connections went to 3 different Pods, and the rules saw only 5
of them. Two things follow:

- **Rule changes only affect new connections.** After scaling or a Pod
  removal, kube-proxy rewrites the rules, but open connections keep the DNAT in
  their entry until they close.
- **The SNAT works the same way.** `KUBE-POSTROUTING` also runs once per
  connection; the hairpin test rule in `POSTROUTING` (test 4 below) didn't
  stop DNAT, which runs earlier, in `KUBE-SERVICES` from `PREROUTING`/`OUTPUT`.
  The capture there still showed the SYN arriving DNATed to `10.244.120.115:8080`.

**One connection means one Pod, however many requests it carries.** From the
same test Pod (`curl -w '%{local_port}'` shows which connection was used):

```
A. one curl, two URLs (curl reuses the connection: keep-alive)
   web-7b6bc8bc5-bcjj6  [local port 56004]
   web-7b6bc8bc5-bcjj6  [local port 56004]     ← same connection → same Pod

B. four separate curls (four connections)
   web-7b6bc8bc5-5nkcv  [local port 56010]
   web-7b6bc8bc5-bcjj6  [local port 56026]
   web-7b6bc8bc5-bcjj6  [local port 56034]
   web-7b6bc8bc5-4shjp  [local port 56042]     ← a new random pick per connection
```

The five conntrack entries on the node match these picks: `.74` is `5nkcv`,
`.71` is `bcjj6`, and `.115` is `4shjp`. That's why HTTP keep-alive, HTTP/2 and
gRPC clients stick to one Pod behind a ClusterIP (§5).

**Without NAT, the reply side is just the original side swapped.** The same
client Pod (`10.244.120.76`) connecting to the same Pod, once by Pod IP and once
by ClusterIP:

```
curl 10.244.120.71:8080   ORIGINAL src=10.244.120.76:53442 dst=10.244.120.71:8080
                          REPLY    src=10.244.120.71:8080  dst=10.244.120.76:53442   ← exact swap: no NAT
curl 10.106.133.73:80     ORIGINAL src=10.244.120.76:60388 dst=10.106.133.73:80
                          REPLY    src=10.244.120.71:8080  dst=10.244.120.76:60388   ← dst ≠ reply src: DNAT
```

| Compare | If they differ | Here |
|---|---|---|
| ORIGINAL `dst` vs REPLY `src` | **DNAT**: the destination was rewritten | `10.106.133.73:80` ≠ `10.244.120.71:8080`: the ClusterIP became a Pod |
| ORIGINAL `src` vs REPLY `dst` | **SNAT / masquerade**: the source was rewritten | equal here (`10.244.120.76:60388`): Pod-to-ClusterIP traffic keeps its source |

A case where **both sides differ**, from the node-port test in
[ingress-and-gateway-api.md](ingress-and-gateway-api.md) §3.4:

```
curl 192.168.49.2:32053   (node port, externalTrafficPolicy: Cluster)
   ORIGINAL  src=192.168.49.2:55454    dst=192.168.49.2:32053
   REPLY     src=10.244.120.112:10443  dst=10.244.120.64:14751
             └ DNAT: node:32053 → Pod ┘ └ SNAT: client → node's tunl0 address ┘
```

That's why the backend saw `10.244.120.64` instead of the real client, as the
Envoy access log showed.

How the kernel uses the pair:

```
 packet going OUT from client:  matches ORIGINAL → rewrite dst  10.106.133.73:80   → 10.244.120.71:8080
 packet coming BACK from Pod:   matches REPLY    → rewrite src  10.244.120.71:8080 → 10.106.133.73:80
                                                   (undo, so the client's socket accepts it)
```

The Pod IP needs no NAT because it's routable: on the node, `ip route get
10.244.120.71` gives `dev cali5e245bbbec2`, the veth into that Pod. Nothing in
`KUBE-SERVICES` matches a Pod IP (0 rules, against 31 for Service IPs). The
ClusterIP has no route of its own (`ip route get 10.106.133.73` only finds the
default gateway), so it works only because the DNAT rewrites it first (§3.2).

#### The whole request, step by step

Once the client has the ClusterIP from DNS and calls `10.106.133.73:80`:

```
client Pod 10.244.120.115                                   target Pod 10.244.120.71:8080
   │
1. connect(10.106.133.73:80)
   Pod's own routing table: default via 169.254.1.1 dev eth0  (any non-local dst goes out eth0)
   SYN: src 10.244.120.115:38770 → dst 10.106.133.73:80
   │  eth0 inside the Pod ═══ veth ═══ cali… on the node
   ▼
2. node, PREROUTING
   -200 conntrack   no entry → create one (ORIGINAL = .115:38770 → 10.106.133.73:80)
   -100 nat         KUBE-SERVICES: "dst 10.106.133.73/32 tcp dpt:80" → KUBE-SVC-XXXX
                    KUBE-SVC-XXXX: random pick among the endpoints (probability rules)
                    KUBE-SEP-YYYY: DNAT → 10.244.120.71:8080
                    packet is now:  .115:38770 → .71:8080
   │
3. routing decision on the new dst
   same node:  10.244.120.71 dev cali5e24…   (the target Pod's veth)
   other node: via the CNI (Calico: BGP route / IPIP / VXLAN to that node)
   │
4. FORWARD   filter: KUBE-FORWARD drops INVALID, accepts marked and ESTABLISHED/RELATED.
             This first SYN is NEW and unmarked, so it falls through KUBE-FORWARD and
             passes on the CNI's rules (Calico cali-FORWARD) or the chain's ACCEPT policy
   │
5. POSTROUTING
   +100 nat    KUBE-POSTROUTING: masquerade only if marked (hairpin, NodePort, external client)
   last        conntrack confirm → entry saved, REPLY = .71:8080 → .115:38770
   ▼
6. target Pod receives  src .115:38770 → dst .71:8080
   It never sees the ClusterIP. It sees the real client IP (no masquerade here).

7. Reply  .71:8080 → .115:38770
   enters the node from the target's veth → conntrack finds the entry (reply direction)
   → un-DNAT: src becomes 10.106.133.73:80 → routed to the client's veth
   client socket sees  10.106.133.73:80 → .115:38770: matches what it connected to ✓

8. Every later packet (ACK, GET, data, FIN)
   conntrack hit → same rewrite from the entry, no walk of KUBE-SERVICES, same Pod every time
```

The Pod's `169.254.1.1` gateway and proxy ARP are explained in "Where tcpdump
looks" below. The `KUBE-FORWARD` rules are in §4.1 ("Shared rules, written once
and used by every Service"). The sections that follow
prove each step with captures.

#### One connection, packet by packet

The same thing as a tcpdump capture on the node. The client is
Pod `web-…-4shjp` (`10.244.120.115`), the Service is `web` (ClusterIP
`10.107.59.191:80`), and the pick was `web-…-bcjj6` (`10.244.120.71:8080`).
The capture used a temporary hostNetwork netshoot Pod, deleted afterwards.
Only the SYN and SYN-ACK are shown; every later packet is rewritten the same way.

```
   CLIENT Pod                 NODE KERNEL                                SERVER Pod
   10.244.120.115             (kube-proxy rules + conntrack)             10.244.120.71
   ──────────────             ──────────────────────────────             ─────────────
 ① sends
   from 10.244.120.115:36720 ──► "10.107.59.191:80 is a Service. Pick .71.
   to   10.107.59.191:80          Rewrite the TO address, store the entry."
                                              │
                               ② forwards     ▼
                                  from 10.244.120.115:36720 ───────────►  gets it; has never
                                  to   10.244.120.71:8080                 heard of the ClusterIP
                                                                       ③ replies to the sender
                               ◄───────────────────────────────────────    from 10.244.120.71:8080
                                 "Matches the entry's REPLY side.          to   10.244.120.115:36720
                                  Rewrite the FROM address back."
 ④ receives                                   │
   from 10.107.59.191:80   ◄──────────────────┘
   to   10.244.120.115:36720
```

The conntrack entry for that connection holds packets ① and ③, each before its
rewrite:

```
src=10.244.120.115 dst=10.107.59.191 sport=36720 dport=80     ← ①  as the client sent it   (ORIGINAL)
src=10.244.120.71  dst=10.244.120.115 sport=8080 dport=36720  ← ③  as the server sends it  (REPLY)
```

conntrack doesn't print ④. It's the ORIGINAL line read backwards: from
`10.107.59.191:80` to `10.244.120.115:36720`. So "the REPLY side shows the Pod
IP" and "the client gets answers from the ClusterIP" are both true. They
describe the same reply before and after the node rewrites it.

| Packet | Seen by | Source | Destination |
|---|---|---|---|
| ① | client | 10.244.120.115:36720 | **10.107.59.191:80** |
| ② | server | 10.244.120.115:36720 | 10.244.120.71:8080 |
| ③ | server | 10.244.120.71:8080 | 10.244.120.115:36720 |
| ④ | client | **10.107.59.191:80** | 10.244.120.115:36720 |

Only the client's side (① ④) ever shows the ClusterIP.

#### Where tcpdump looks: one virtual cable per Pod

To read these captures, you need to know where they were taken. Each Pod is
joined to the node by a **veth pair**, a virtual network cable with two ends.
One end is inside the Pod's network namespace and is called `eth0`. The other
end is on the node and is called `cali<hash>`, a name Calico creates. Whatever
goes into one end comes out of the other. The node sits between all the cables
and routes between them, and that's where kube-proxy's NAT runs.

```
     CLIENT Pod (.115)                    NODE                              SERVER Pod (.71)
  ┌──────────────────┐                                                   ┌──────────────────┐
  │                  │   cable 1                            cable 2      │                  │
  │   eth0 ══════════╪══════════ calia6c53c03131   cali5e245bbbec2 ══════╪══════════ eth0   │
  │                  │                 │                 ▲               │                  │
  └──────────────────┘                 │                 │               └──────────────────┘
                                       ▼                 │
                                 node kernel: routing + kube-proxy NAT
                                       (the rewrite happens here)
```

- **A Pod has only one way out: its `eth0`.** Even when the client and server
  are on the same node, a packet between them leaves the client through cable 1,
  goes through the node's kernel, and enters the server through cable 2. It never
  goes from one Pod to the other directly. That's why the node can rewrite every
  packet.
- **Which cable is which Pod?** The node's routing table says which cable leads
  to each Pod IP:

  ```
  $ ip route get 10.244.120.115
  10.244.120.115 dev calia6c53c03131 src 10.244.120.64      ← the client's cable
  $ ip route get 10.244.120.71
  10.244.120.71 dev cali5e245bbbec2 src 10.244.120.64        ← the server's cable
  ```

- **The capture runs on the node** (from a hostNetwork Pod, so it sees the
  node's interfaces). It watches the node's end of both cables, so each packet
  is seen **twice**: once as it enters the node from one Pod, and once as it
  leaves toward the other.
- **`In` and `Out` are from the node's point of view.** `calia6c… In` means the
  packet arrived at the node from the client's cable, so it's exactly what the
  client sent. `cali5e2… Out` means the node sent it into the server's cable, so
  it's exactly what the server will receive. **The difference between those two
  lines is the NAT.**

**Checking the cable from both ends** (client `4shjp`). Each end of
a veth knows the interface number of its partner, so the two ends can be
matched without the routing table:

```
inside the Pod:  $ ip link show eth0
                 11: eth0@if210: … link/ether 62:b0:60:bc:33:71
                     └ I'm interface 11; my other end is interface 210 (in another namespace)
on the node:     $ ip link show calia6c53c03131
                 210: calia6c53c03131@if11: … link/ether ee:ee:ee:ee:ee:ee
                     └ I'm interface 210; my other end is interface 11
                 $ ip -d link show calia6c53c03131   → type veth
```

`11 ↔ 210` is one cable. Its Pod end is `eth0` with the Pod's own MAC, and its
node end is `calia6c53c03131` with MAC `ee:ee:ee:ee:ee:ee`. Calico gives that
same MAC to the node end of every Pod's cable.

**Why everything goes out that cable, even to a Pod next door.** The Pod's
network setup leaves it no other choice:

```
inside the Pod:  $ ip -4 addr show eth0
                 inet 10.244.120.115/32            ← /32: no subnet, so no other Pod counts as "local"
                 $ ip route
                 default via 169.254.1.1 dev eth0  ← everything goes to one gateway, through eth0
                 169.254.1.1 dev eth0 scope link
                 $ ip neigh
                 169.254.1.1   dev eth0 lladdr ee:ee:ee:ee:ee:ee   ← the "gateway" is the node's end of the cable
                 10.244.120.64 dev eth0 lladdr ee:ee:ee:ee:ee:ee
```

| What you see | What it means |
|---|---|
| Address `/32` | The Pod has no neighbours on a shared subnet. To the Pod, every other IP, including `.71` on the same node, is "somewhere else" |
| `default via 169.254.1.1` | So every packet is sent to the gateway. `169.254.1.1` is a made-up link-local address. No interface on the node actually has it |
| `169.254.1.1 → ee:ee:ee:ee:ee:ee` | When the Pod asks (ARP) who has `169.254.1.1`, the node's end of the cable answers with its own MAC. This is **proxy ARP**: `/proc/sys/net/ipv4/conf/calia6c53c03131/proxy_arp` is `1` |
| Node: `10.244.120.115 dev calia6c53c03131 scope link` | The way back: the node has one route per local Pod, each pointing down that Pod's cable |
| Node: `blackhole 10.244.120.64/26 proto bird` | The node's block of Pod IPs. Any address in it without its own `/32` Pod route is dropped, not sent elsewhere |

So every packet a Pod sends is delivered to the node's kernel. The node routes
it, and NATs it if it's going to a Service, before sending it down the next
cable. There's no path between two Pods that skips the node.

The same MACs appear in the martian log from hairpin test 4: `ll header:
62 b0 60 bc 33 71 ee ee ee ee ee ee 08 00`. That's destination MAC `62:b0:…`
(the Pod), source MAC `ee:ee:…` (the node's end of the cable), and type `0800`
(IPv4). The dropped SYN came up the cable from the node.

One request packet, then one reply packet, on that route:

```
REQUEST
 client sends ─► calia6c… In    .115:38770 → 10.107.59.191:80     what the client sent (before the rewrite)
                 node kernel:   DNAT, dst → .71:8080
                 cali5e2… Out   .115:38770 → .71:8080             what the server receives (after the rewrite)
              ─► server

REPLY
 server sends ─► cali5e2… In    .71:8080 → .115:38770             what the server sent (before)
                 node kernel:   reverse rewrite, src → 10.107.59.191:80
                 calia6c… Out   10.107.59.191:80 → .115:38770     what the client receives (after)
              ─► client
```

| tcpdump line | Where | Shows |
|---|---|---|
| `calia6c… In` | client's cable, into the node | the request as the client sent it |
| `cali5e2… Out` | server's cable, out of the node | the request as the server gets it |
| `cali5e2… In` | server's cable, into the node | the reply as the server sent it |
| `calia6c… Out` | client's cable, out of the node | the reply as the client gets it |

The hairpin test 4 below is the exception that proves the rule. Client and
server are the same Pod, so both directions use the **same** cable, and the
capture shows `Out` then `In` on one `eth0`. That capture was taken inside the
Pod, so there `Out` and `In` are from the Pod's point of view.

#### Every packet is rewritten, not only the first

The rules run once per connection, but the rewrite happens on **every packet**,
using the conntrack entry. Here's one full connection (`wget
http://10.107.59.191/clientip` from `4shjp`, picked `bcjj6`),
captured with `tcpdump -i any -nn 'host 10.244.120.115 and (tcp port 80 or tcp
port 8080)'`. Each packet appears on both cables, and the `KUBE-SERVICES` `web`
rule counter was read before and after.

**How to read it: follow each packet along the cables.** Every packet makes
the same three-step trip. It comes up one Pod's cable into the node, the node's
kernel rewrites one address, and it goes down the other Pod's cable. tcpdump
on the node sees it at both cables, so each packet is two lines in the capture:
**one before the rewrite, one after**.

```
     CLIENT Pod (.115)                    NODE                              SERVER Pod (.71)
   eth0 ════ cable 1 ════ calia6c53c03131        cali5e245bbbec2 ════ cable 2 ════ eth0
                                │      node kernel       ▲
   client → server packets:     └──► DNAT: dst → .71 ────┘        (rewrite dst)
   server → client packets:     ┌─── un-DNAT: src → ClusterIP ◄──┐ (rewrite src)
                                ▼                                 │
```

Two of the ten packets, step by step:

```
PACKET 1, SYN (client → server): the first packet, so the rules run
  ① client's eth0 sends it         .115:38770 → 10.107.59.191:80
  ② node sees it arrive on cable 1 "calia6c… In"   .115:38770 → 10.107.59.191:80    BEFORE
  ③ node kernel: no entry for this connection yet → walk KUBE-SERVICES (counter 5 → 6)
                 → random pick .71 → DNAT dst → create the conntrack entry
  ④ node sends it down cable 2     "cali5e2… Out"  .115:38770 → .71:8080            AFTER
  ⑤ server's eth0 receives it      .115:38770 → .71:8080

PACKET 6, 200 OK (server → client): a reply, so the entry is used and the rules aren't
  ① server's eth0 sends it         .71:8080 → .115:38770           (it answers whoever wrote to it)
  ② node sees it arrive on cable 2 "cali5e2… In"   .71:8080 → .115:38770            BEFORE
  ③ node kernel: matches the entry's REPLY side → rewrite src back (counter stays 6)
  ④ node sends it down cable 1     "calia6c… Out"  10.107.59.191:80 → .115:38770    AFTER
  ⑤ client's eth0 receives it      10.107.59.191:80 → .115:38770   (the address it called)
```

Here are all ten, laid out the same way, with the cable each packet came in on
and the cable it left on. "Before" is what the sender sent, and "after" is
what the receiver got:

| # | TCP | Direction | Enters the node on | Before the rewrite | Node kernel does | Leaves the node on | After the rewrite | Rule counter |
|---|---|---|---|---|---|---|---|---|
| 1 | SYN | client → server | cable 1 (`calia6c… In`) | `.115:38770 → 10.107.59.191:80` | **rules walked**, pick `.71`, DNAT, entry created | cable 2 (`cali5e2… Out`) | `.115:38770 → .71:8080` | **5 → 6** |
| 2 | SYN-ACK | server → client | cable 2 (`cali5e2… In`) | `.71:8080 → .115:38770` | entry: src → ClusterIP | cable 1 (`calia6c… Out`) | `10.107.59.191:80 → .115:38770` | 6 |
| 3 | ACK | client → server | cable 1 | `.115:38770 → 10.107.59.191:80` | entry: dst → `.71:8080` | cable 2 | `.115:38770 → .71:8080` | 6 |
| 4 | `GET /clientip` | client → server | cable 1 | `.115:38770 → 10.107.59.191:80` | entry: dst → `.71:8080` | cable 2 | `.115:38770 → .71:8080` | 6 |
| 5 | ACK | server → client | cable 2 | `.71:8080 → .115:38770` | entry: src → ClusterIP | cable 1 | `10.107.59.191:80 → .115:38770` | 6 |
| 6 | `200 OK` | server → client | cable 2 | `.71:8080 → .115:38770` | entry: src → ClusterIP | cable 1 | `10.107.59.191:80 → .115:38770` | 6 |
| 7 | ACK | client → server | cable 1 | `.115:38770 → 10.107.59.191:80` | entry: dst → `.71:8080` | cable 2 | `.115:38770 → .71:8080` | 6 |
| 8 | FIN | server → client | cable 2 | `.71:8080 → .115:38770` | entry: src → ClusterIP | cable 1 | `10.107.59.191:80 → .115:38770` | 6 |
| 9 | FIN | client → server | cable 1 | `.115:38770 → 10.107.59.191:80` | entry: dst → `.71:8080` | cable 2 | `.115:38770 → .71:8080` | 6 |
| 10 | ACK | server → client | cable 2 | `.71:8080 → .115:38770` | entry: src → ClusterIP | cable 1 | `10.107.59.191:80 → .115:38770` | 6 |

What the TCP column means: 1–3 is the handshake, 4–6 is the request and
response, and 7–10 is the close. agnhost closed first (FIN at 8), then wget
(FIN at 9).

Read down the "Before" and "After" columns:

- **The ClusterIP only ever appears on cable 1**, the client's: as the
  destination before the rewrite for client → server packets, and as the source
  after the rewrite for server → client packets. Only the client ever sees it.
- **`.71:8080` only ever appears on cable 2**, the server's. Only the server
  ever sees it, and it never sees the ClusterIP.
- **`.115:38770` is never changed.** That's no SNAT: this wasn't a hairpin, so
  the server saw the real client (its `/clientip` answer was `10.244.120.115:38770`).
- **The counter moved only on packet 1.** Packets 2–10 were each rewritten
  just the same, but by the conntrack entry, not the rules.

Two lines of the raw capture, the `GET`, on both cables:

```
13:26:32.525341 calia6c53c03131 In  IP 10.244.120.115.38770 > 10.107.59.191.80:   Flags [P.], seq 1:85, … HTTP: GET /clientip HTTP/1.1
13:26:32.525350 cali5e245bbbec2 Out IP 10.244.120.115.38770 > 10.244.120.71.8080: Flags [P.], seq 1:85, … HTTP: GET /clientip HTTP/1.1
```

The two lines are 9 µs apart, with the same `seq` and the same payload. Only the
destination differs.

So later packets skip the **rules**, not the **rewrite**. The kernel finds the
packet's connection in conntrack and applies the address change stored there,
without walking `KUBE-SERVICES` and without a new pick.

| | Rules walked? | Rewritten? |
|---|---|---|
| 1st packet (SYN) | yes, and the pick is made | yes: dst → Pod |
| Later client → server packets | no | yes: dst → Pod, from the entry |
| All server → client packets | no | yes: src → ClusterIP, from the entry |

#### Inside the node's kernel: conntrack, then NAT, then save

The conntrack entry is **created before DNAT**, **filled in by DNAT and
SNAT**, and **saved into the table only after both**. Each hook point on a
packet's path (PREROUTING, OUTPUT, POSTROUTING, INPUT) runs its handlers in
priority order, lowest number first.

**The first packet, the SYN, going out.** Same connection as above: client
`.115:38770` → `web` ClusterIP, picked `.71`.

```
 first packet (SYN) of .115:38770 → 10.107.59.191:80, arriving from the client's cable

 PREROUTING
   -300  raw          (iptables raw table; unused here)
   -200  CONNTRACK    ① look up .115:38770 → 10.107.59.191:80 → not found
                         → CREATE a new entry, not yet in the table
                           ORIGINAL = .115:38770 → 10.107.59.191:80    (the packet as it arrived)
                           REPLY    = 10.107.59.191:80 → .115:38770    (just the swap, for now)
   -150  mangle
   -100  DNAT (nat)   ② kube-proxy rules: KUBE-SERVICES → KUBE-SVC → pick → KUBE-SEP → DNAT .71:8080
                         → rewrite the packet's dst
                         → UPDATE the entry's REPLY = .71:8080 → .115:38770
 routing decision: .71 → cable 2
 POSTROUTING
   +100  SNAT (nat)   ③ KUBE-POSTROUTING: not marked → no MASQUERADE
                         (if it were marked: rewrite src, and change REPLY's dst too)
   last  CONFIRM      ④ SAVE the entry into the conntrack table → the [NEW] event fires
 → out to cable 2
```

| Step | What happens to the entry |
|---|---|
| ① conntrack, before DNAT | **Created.** ORIGINAL is recorded *as the packet arrived*, so it holds the ClusterIP. That's why ORIGINAL always shows what the client sent |
| ② DNAT | **REPLY changed** to expect the answer from `.71:8080`. That change is the ORIGINAL dst ≠ REPLY src mismatch |
| ③ SNAT, if marked | **REPLY changed again** (hairpin, node or outside caller): the answer is expected at `10.244.120.64:<port>` |
| ④ confirm, at the very end | **Saved.** From now on, later packets of this connection find it |

Why this order:

- **conntrack before NAT.** The NAT decision is stored *in* the entry. With no
  entry, there's nowhere to record the pick, and later packets couldn't get the
  same rewrite. That's also why a packet marked `NOTRACK` in the raw table (−300,
  before conntrack) can't be NATed at all.
- **ORIGINAL recorded before DNAT.** Later packets still arrive addressed to the
  ClusterIP, so they have to be found by the address the client used.
- **Saved after SNAT.** The REPLY side isn't final until both NAT steps have run.

What was seen on the lab, and what wasn't:

- **Seen: the `[NEW]` event already has the NATed REPLY side**, `[NEW] … src=10.244.120.71
  dst=10.244.120.115 …` in the `conntrack -E` run. The event fires at ④, after NAT.
- **Seen: the nat chain priorities.** `nft list table ip nat`, from a temporary
  hostNetwork netshoot Pod, gave `prerouting priority dstnat` (−100), `output
  priority dstnat`, `postrouting priority srcnat` (+100) and `input priority srcnat`.
  Raw is at `priority raw` (−300) and mangle at `priority mangle` (−150).
- **Not seen: conntrack's own positions.** −200 and "last" are the kernel's
  constants `NF_IP_PRI_CONNTRACK` and `NF_IP_PRI_CONNTRACK_CONFIRM` (`INT_MAX`).
  `nft list hooks` printed nothing on this node (kernel 7.0.14-linuxkit), so
  they weren't confirmed here.

When the client is the node itself (examples E and F above), the first packet
starts at **OUTPUT** instead of PREROUTING. It's the same sequence: conntrack,
then DNAT at `priority dstnat`, then routing, then POSTROUTING.

**Later packets in the same direction** (ACK, `GET`, FIN): ① finds the saved
entry, so ② doesn't walk `KUBE-SERVICES`. The nat hook applies the stored DNAT
instead, and ③ applies the stored SNAT, if any. Nothing new is saved.

#### The return path, through the same hooks

A reply goes through the same hook points. conntrack recognises it as the
**REPLY direction** of the saved entry, and the NAT is **undone in reverse
order**: SNAT is undone first, DNAT last.

**The SYN-ACK of the normal connection** (no SNAT), arriving from the server's
cable:

```
 reply (SYN-ACK) .71:8080 → .115:38770, arriving from the server's cable (cable 2)

 PREROUTING
   -300  raw
   -200  CONNTRACK    ① look up .71:8080 → .115:38770 → FOUND, as the REPLY side of the saved entry
                         → this packet is direction=REPLY; the entry is now SEEN_REPLY (later ASSURED)
   -150  mangle
   -100  nat          ② no rules walked (not a new connection).
                         Undo SNAT = put the original client back as dst. There was no SNAT → nothing to do
 routing decision: dst .115 → cable 1
 POSTROUTING
   +100  nat          ③ no rules walked. Undo DNAT = put the ClusterIP back as src:
                         src .71:8080 → 10.107.59.191:80
   last  CONFIRM      ④ already saved → nothing
 → out to cable 1:  10.107.59.191:80 → .115:38770   (what the client receives)
```

**The SYN-ACK of the hairpin** (example G: `.115:49382` → `web`, picked
itself, SNATed to `10.244.120.64:36689`), arriving back on the **same** cable:

```
 reply (SYN-ACK) .115:8080 → 10.244.120.64:36689, arriving from cable 1 (the Pod is both ends)

 PREROUTING
   -200  CONNTRACK    ① FOUND: matches REPLY  src=10.244.120.115 dst=10.244.120.64 sport=8080 dport=36689
   -100  nat          ② undo SNAT: dst 10.244.120.64:36689 → .115:49382
 routing decision: dst .115 → cable 1                  ← must come AFTER ②
 POSTROUTING
   +100  nat          ③ undo DNAT: src .115:8080 → 10.107.59.191:80
 → out to cable 1:  10.107.59.191:80 → .115:49382   (what the client socket expects)
```

| | First packet (going out) | Reply (coming back) |
|---|---|---|
| conntrack lookup | not found → create | found, as REPLY → mark seen |
| PREROUTING / OUTPUT nat (`dstnat`) | **DNAT**: rules walked, pick made | **undo SNAT** (dst back to the real client), from the entry |
| routing | to the picked Pod's cable | to the client's cable |
| POSTROUTING nat (`srcnat`) | **SNAT**, if marked | **undo DNAT** (src back to ClusterIP), from the entry |
| confirm | entry saved | nothing |

Why reverse order: each hook only ever changes one side of the packet.

- **`dstnat` hooks (PREROUTING/OUTPUT) change the destination, before routing.**
  Routing needs the real destination to choose a cable. Going out, that's the
  DNAT. Coming back, undoing SNAT is also a destination change: the reply is
  addressed to `10.244.120.64`.
- **`srcnat` hooks (POSTROUTING/INPUT) change the source, after routing.**
  Going out, that's the SNAT. Coming back, undoing DNAT is a source change.

The hairpin shows why the undo-SNAT has to come before routing. The reply is
addressed to `10.244.120.64`, which is the node's own `tunl0` address. If
routing ran first, the node would deliver the packet to **itself** (INPUT),
not down cable 1. Changing the destination back to `.115` first sends it to
the Pod. It worked every time in tests 1 and 2, with 15 hairpin
connections answered.

What was seen on the lab: the reply rewrites (the 10-packet capture, packets
2, 5, 6, 8 and 10), and the counter staying at 6, so no rules were walked for
replies. The exact hook each undo runs at comes from the kernel's NAT design,
`dstnat`/`srcnat` as above. It wasn't traced packet by packet here.

#### Packets 1, 2 and 3 through the hooks, one after another

The TCP handshake is the first three packets of the 10-packet capture. Each
uses the entry differently:

- **Packet 1** creates the entry.
- **Packet 2** is the first packet seen in the REPLY direction.
- **Packet 3** is the first *later* packet in the ORIGINAL direction.

Every later packet of the connection behaves like 2 (server → client) or 3
(client → server).

The tcpdump lines are from the capture (client `.115:38770`, `web` ClusterIP,
picked `.71`). The entry states are the ones `conntrack -E` showed for every
connection in the 8-request run: `[NEW] SYN_SENT` → `[UPDATE] SYN_RECV` →
`[UPDATE] ESTABLISHED [ASSURED]`.

**Packet 1: SYN, client → server.** The entry doesn't exist yet.

```
 tcpdump  13:26:32.525100 calia6c53c03131 In   .115:38770 > 10.107.59.191:80   [S]

 PREROUTING
   -200  CONNTRACK    look up .115:38770 → 10.107.59.191:80 → NOT FOUND
                      → create entry (unsaved), direction = ORIGINAL, state SYN_SENT
                        ORIGINAL .115:38770 → 10.107.59.191:80
                        REPLY    10.107.59.191:80 → .115:38770       (plain swap for now)
   -100  nat          NEW connection → WALK the rules: KUBE-SERVICES → KUBE-SVC (random) → KUBE-SEP
                      → DNAT dst to .71:8080                         KUBE-SERVICES counter 5 → 6
                      → entry REPLY becomes .71:8080 → .115:38770
 routing: .71 → cable 2
 POSTROUTING
   +100  nat          KUBE-POSTROUTING: no 0x4000 mark → RETURN, no SNAT
   last  CONFIRM      SAVE the entry                                  → event [NEW] SYN_SENT [UNREPLIED]

 tcpdump  13:26:32.525191 cali5e245bbbec2 Out  .115:38770 > .71:8080           [S]
```

Entry after packet 1:

```
SYN_SENT  src=.115 dst=10.107.59.191 sport=38770 dport=80 [UNREPLIED] src=.71 dst=.115 sport=8080 dport=38770
```

**Packet 2: SYN-ACK, server → client.** The first packet in the REPLY direction.

```
 tcpdump  13:26:32.525222 cali5e245bbbec2 In   .71:8080 > .115:38770           [S.]

 PREROUTING
   -200  CONNTRACK    look up .71:8080 → .115:38770 → FOUND, matches the entry's REPLY side
                      → direction = REPLY. First reply → [UNREPLIED] is cleared (SEEN_REPLY)
                      → TCP tracking: SYN then SYN-ACK → state SYN_RECV
   -100  nat          not NEW → no rules walked (counter stays 6)
                      undo SNAT (put the real client back as dst): there was none → nothing
 routing: .115 → cable 1
 POSTROUTING
   +100  nat          undo DNAT, from the entry: src .71:8080 → 10.107.59.191:80
   last  CONFIRM      already saved → nothing                         → event [UPDATE] SYN_RECV

 tcpdump  13:26:32.525231 calia6c53c03131 Out  10.107.59.191:80 > .115:38770   [S.]
```

Entry after packet 2:

```
SYN_RECV  src=.115 dst=10.107.59.191 sport=38770 dport=80 src=.71 dst=.115 sport=8080 dport=38770
```

**Packet 3: ACK, client → server.** A later packet in the ORIGINAL direction.

```
 tcpdump  13:26:32.525245 calia6c53c03131 In   .115:38770 > 10.107.59.191:80   [.]

 PREROUTING
   -200  CONNTRACK    look up .115:38770 → 10.107.59.191:80 → FOUND, matches ORIGINAL
                      → direction = ORIGINAL. Handshake complete → state ESTABLISHED,
                        traffic seen both ways → ASSURED
   -100  nat          not NEW → no rules walked (counter stays 6), no new pick
                      apply the stored DNAT, from the entry: dst → .71:8080
 routing: .71 → cable 2
 POSTROUTING
   +100  nat          apply the stored SNAT: none → nothing
   last  CONFIRM      already saved → nothing                         → event [UPDATE] ESTABLISHED [ASSURED]

 tcpdump  13:26:32.525248 cali5e245bbbec2 Out  .115:38770 > .71:8080           [.]
```

Entry after packet 3:

```
ESTABLISHED  src=.115 dst=10.107.59.191 sport=38770 dport=80 src=.71 dst=.115 sport=8080 dport=38770 [ASSURED]
```

The three side by side:

| | Packet 1: SYN | Packet 2: SYN-ACK | Packet 3: ACK |
|---|---|---|---|
| Comes in on | cable 1 (client) | cable 2 (server) | cable 1 (client) |
| conntrack lookup | not found → **create** | found as **REPLY** | found as **ORIGINAL** |
| Rules walked? | **yes** (counter 5 → 6) | no | no |
| `dstnat` hook (PREROUTING) | DNAT dst → `.71:8080`, **pick made** | undo SNAT: none | stored DNAT: dst → `.71:8080` |
| Routing | → cable 2 | → cable 1 | → cable 2 |
| `srcnat` hook (POSTROUTING) | SNAT check: not marked | undo DNAT: src → `10.107.59.191:80` | stored SNAT: none |
| Confirm | **entry saved** | nothing | nothing |
| Entry state after | `SYN_SENT [UNREPLIED]` | `SYN_RECV` | `ESTABLISHED [ASSURED]` |
| Event | `[NEW]` | `[UPDATE]` | `[UPDATE]` |
| Time on the node (In → Out) | 91 µs | 9 µs | 3 µs |

Packet 1 is the slowest because it's the only one that walks the rule chains
and allocates an entry. The others are just a table lookup and a rewrite.

The remaining seven packets repeat these two patterns:

- **Like packet 3** (client → server, stored DNAT): 4 `GET`, 7 ACK, 9 FIN.
- **Like packet 2** (server → client, undo DNAT): 5 ACK, 6 `200 OK`, 8 FIN, 10 ACK.

The FINs move the entry on to `FIN_WAIT`/`LAST_ACK` and then `TIME_WAIT`, which it
keeps for 120 s before it's deleted.

**What was seen and what was inferred.** Seen: the tcpdump lines, the rule
counter (5 → 6, then unchanged), and the entry states and event types. They
come from `conntrack -E` on the connections of the 8-request run; this
particular connection (port 38770) wasn't streamed with `-E`, but every one
of those connections showed the same `NEW SYN_SENT` → `UPDATE SYN_RECV` →
`UPDATE ESTABLISHED [ASSURED]` sequence. Inferred from the kernel's design, not
traced per hook: which hook does each step. The In → Out times are only the gap
between the two tcpdump lines.

#### Later packets: the same four steps, much less work

Every packet passes all four points (conntrack, the `dstnat` hook, routing,
the `srcnat` hook), because they're fixed places on the path through the
kernel. For a packet that isn't the first, they shrink:

```
 packet 3 (ACK) arrives:  .115:38770 → 10.107.59.191:80

 ① conntrack (-200)   hash lookup on the 4 numbers → FOUND, direction ORIGINAL → attach the entry
 ② nat, dstnat (-100) not NEW → SKIP the iptables rules entirely
                      entry has DST_NAT → do the stored swap: dst → .71:8080
 routing              dst is now .71 → cable 2
 ③ nat, srcnat (+100) entry has no SRC_NAT → nothing
 ④ confirm            entry already saved → nothing
 leaves:                 .115:38770 → .71:8080
```

| Step | Packet 1 | Every later packet |
|---|---|---|
| ① conntrack | not found → create | found → attach (one hash lookup) |
| ② `dstnat` hook | **walk the iptables rules**, pick, set up the NAT | no rules: if `DST_NAT`, rewrite dst from the entry |
| routing | yes | yes |
| ③ `srcnat` hook | SNAT check (`KUBE-POSTROUTING`) | no rules: if `SRC_NAT`, rewrite src from the entry |
| ④ confirm | save the entry | nothing |

The swap still has to happen at ②, before routing: the kernel can only choose
cable 2 once the destination is the Pod IP. That's why packet 1 took 91 µs on
the node and packet 3 took 3 µs. The steps are the same; the work is much smaller.

#### There's no "undo" rule. The entry remembers both addresses

kube-proxy only writes rules for the forward direction (`KUBE-SERVICES →
KUBE-SVC → KUBE-SEP → DNAT`). Nothing in `iptables-save` reverses it. The
reverse rewrite is done by the kernel's NAT code, from the entry.

The entry holds two complete tuples. Between them, they record both the
address the client used and the Pod it really went to:

```
ORIGINAL  src=10.244.120.115 dst=10.107.59.191 sport=38770 dport=80      ← the ClusterIP:port is here
REPLY     src=10.244.120.71  dst=10.244.120.115 sport=8080 dport=38770   ← the Pod IP:port is here
```

There's no third field saying "DNAT target = `.71:8080`". The kernel works the
rewrite out from the two tuples. To fix a packet going one way, it takes the
**other** direction's tuple, flips it ("what this packet should look like"),
and copies the side the current hook is allowed to change:

| Packet | Hook | Other direction's tuple | Flipped | Copies | Result |
|---|---|---|---|---|---|
| client → server (ORIGINAL) | `dstnat` | REPLY `.71:8080 → .115:38770` | `.115:38770 → .71:8080` | destination | dst `10.107.59.191:80` → `.71:8080` |
| server → client (REPLY), the "undo" | `srcnat` | ORIGINAL `.115:38770 → 10.107.59.191:80` | `10.107.59.191:80 → .115:38770` | source | src `.71:8080` → `10.107.59.191:80` |

That's why `conntrack -L` always shows the ClusterIP in the first half. That
half is the record the kernel uses to put it back.

#### Where the rewrite happens, in the kernel source

These excerpts are from Linux **v6.12** (`torvalds/linux`, tag `v6.12`). The
lab runs `7.0.14-linuxkit`, which wasn't traced function by function. The lab
does show the effect: the rewrite between the `In` and `Out` capture lines, and
the `srcnat`/`dstnat` priorities on the nat table.

**1. The NAT module hooks four functions into the packet path**
(`net/netfilter/nf_nat_proto.c`):

```c
static const struct nf_hook_ops nf_nat_ipv4_ops[] = {
	/* Before packet filtering, change destination */
	{
		.hook		= nf_nat_ipv4_pre_routing,
		.pf		= NFPROTO_IPV4,
		.hooknum	= NF_INET_PRE_ROUTING,
		.priority	= NF_IP_PRI_NAT_DST,
	},
	/* After packet filtering, change source */
	{
		.hook		= nf_nat_ipv4_out,
		.pf		= NFPROTO_IPV4,
		.hooknum	= NF_INET_POST_ROUTING,
		.priority	= NF_IP_PRI_NAT_SRC,
	},
	/* Before packet filtering, change destination */
	{
		.hook		= nf_nat_ipv4_local_fn,
		.pf		= NFPROTO_IPV4,
		.hooknum	= NF_INET_LOCAL_OUT,
		.priority	= NF_IP_PRI_NAT_DST,
	},
	/* After packet filtering, change source */
	{
		.hook		= nf_nat_ipv4_local_in,
		.pf		= NFPROTO_IPV4,
		.hooknum	= NF_INET_LOCAL_IN,
		.priority	= NF_IP_PRI_NAT_SRC,
	},
};
```

`NF_IP_PRI_NAT_DST` (−100) and `NF_IP_PRI_NAT_SRC` (+100) are what `nft list
table ip nat` printed on the node as `priority dstnat` and `priority srcnat`.
The iptables `nat` table's chains (`PREROUTING` → `KUBE-SERVICES`,
`POSTROUTING` → `KUBE-POSTROUTING`) run *inside* these hooks, but only for
a connection's first packet (step 3).

**2. Which side each hook may change** (`include/net/netfilter/nf_nat.h`):

```c
/* SRC manip occurs POST_ROUTING or LOCAL_IN */
#define HOOK2MANIP(hooknum) ((hooknum) != NF_INET_POST_ROUTING && \
			     (hooknum) != NF_INET_LOCAL_IN)
```

POSTROUTING and INPUT change the **source**. PREROUTING and OUTPUT change the
**destination**.

**3. Rules only for the first packet; the entry for the rest**
(`net/netfilter/nf_nat_core.c`, `nf_nat_inet_fn()`, called by every nat hook):

```c
	switch (ctinfo) {
	case IP_CT_RELATED:
	case IP_CT_RELATED_REPLY:
		/* Only ICMPs can be IP_CT_IS_REPLY.  Fallthrough */
	case IP_CT_NEW:
		/* Seen it before?  This can happen for loopback, retrans,
		 * or local packets.
		 */
		if (!nf_nat_initialized(ct, maniptype)) {
			...
			for (i = 0; i < e->num_hook_entries; i++) {
				ret = e->hooks[i].hook(e->hooks[i].priv, skb,     ← the iptables nat chains:
						       state);                    ← KUBE-SERVICES, KUBE-POSTROUTING …
				...
			}
null_bind:
			ret = nf_nat_alloc_null_binding(ct, state->hook);     ← no rule matched: record "no NAT here"
			...
		}
		break;
	default:
		/* ESTABLISHED */                                          ← packets 2, 3, 4 … land here: no rules
		WARN_ON(ctinfo != IP_CT_ESTABLISHED &&
			ctinfo != IP_CT_ESTABLISHED_REPLY);
		...
	}
do_nat:
	return nf_nat_packet(ct, ctinfo, state->hook, skb);            ← every packet
```

**4. Apply only if the entry has that kind of NAT** (`nf_nat_core.c`,
`nf_nat_packet()`):

```c
	enum nf_nat_manip_type mtype = HOOK2MANIP(hooknum);
	enum ip_conntrack_dir dir = CTINFO2DIR(ctinfo);
	...
	if (mtype == NF_NAT_MANIP_SRC)
		statusbit = IPS_SRC_NAT;
	else
		statusbit = IPS_DST_NAT;

	/* Invert if this is reply dir. */
	if (dir == IP_CT_DIR_REPLY)
		statusbit ^= IPS_NAT_MASK;

	/* Non-atomic: these bits don't change. */
	if (ct->status & statusbit)
		verdict = nf_nat_manip_pkt(skb, ct, mtype, dir);
```

`IPS_DST_NAT` and `IPS_SRC_NAT` are the flags that `conntrack -L --dst-nat` /
`--src-nat` filtered on above. The "invert" line is what makes the undo work.
For a **reply**, the source-changing hook checks for **DST_NAT**. A DNAT-ed
connection therefore gets its *source* changed on the way back.

| Hook | Changes | Forward packet: applies if | Reply packet: applies if |
|---|---|---|---|
| PREROUTING / OUTPUT | destination | `DST_NAT` (DNAT) | `SRC_NAT` (undo SNAT) |
| POSTROUTING / INPUT | source | `SRC_NAT` (SNAT) | `DST_NAT` (undo DNAT) |

**5. Compute the new address from the other direction's tuple**
(`net/netfilter/nf_nat_proto.c`, `nf_nat_manip_pkt()`):

```c
	struct nf_conntrack_tuple target;

	/* We are aiming to look like inverse of other direction. */
	nf_ct_invert_tuple(&target, &ct->tuplehash[!dir].tuple);
```

`!dir` means the other direction. For a reply, that's the ORIGINAL tuple, the
one holding `10.107.59.191:80`.

**6. Write it into the IP header** (`nf_nat_proto.c`, `nf_nat_ipv4_manip_pkt()`):

```c
	if (maniptype == NF_NAT_MANIP_SRC) {
		csum_replace4(&iph->check, iph->saddr, target->src.u3.ip);
		iph->saddr = target->src.u3.ip;
	} else {
		csum_replace4(&iph->check, iph->daddr, target->dst.u3.ip);
		iph->daddr = target->dst.u3.ip;
	}
```

**7. And the port into the TCP header** (`nf_nat_proto.c`, `tcp_manip_pkt()`):

```c
	if (maniptype == NF_NAT_MANIP_SRC) {
		/* Get rid of src port */
		newport = tuple->src.u.tcp.port;
		portptr = &hdr->source;
	} else {
		/* Get rid of dst port */
		newport = tuple->dst.u.tcp.port;
		portptr = &hdr->dest;
	}

	oldport = *portptr;
	*portptr = newport;
	...
	nf_csum_update(skb, iphdroff, &hdr->check, tuple, maniptype);
```

The IP checksum and the TCP checksum are both fixed, because changing an
address or a port invalidates them.

**Packet 2 (SYN-ACK) through all of it, with the real values:**

```
 arrives on cable 2:   src .71:8080 → dst .115:38770        tcpdump 13:26:32.525222 cali5e245bbbec2 In

 PREROUTING
   conntrack           found: matches REPLY → ctinfo = IP_CT_ESTABLISHED_REPLY
   nf_nat_ipv4_pre_routing → nf_nat_inet_fn
                       HOOK2MANIP(PRE_ROUTING) = DST; ESTABLISHED → no rules
                       nf_nat_packet: statusbit = DST_NAT, reply → flipped to SRC_NAT
                       entry has no SRC_NAT → nothing changed
 routing               dst .115 → cable 1
 POSTROUTING
   nf_nat_ipv4_out → nf_nat_inet_fn
                       HOOK2MANIP(POST_ROUTING) = SRC; ESTABLISHED → no rules
                       nf_nat_packet: statusbit = SRC_NAT, reply → flipped to DST_NAT
                       entry has DST_NAT → nf_nat_manip_pkt(…, SRC, REPLY)
   nf_nat_manip_pkt    other direction = ORIGINAL  .115:38770 → 10.107.59.191:80
                       inverted → target           10.107.59.191:80 → .115:38770
   nf_nat_ipv4_manip_pkt  iph->saddr  = 10.107.59.191     (IP checksum fixed)
   tcp_manip_pkt          hdr->source = 80                 (TCP checksum fixed)

 leaves on cable 1:    src 10.107.59.191:80 → dst .115:38770   tcpdump 13:26:32.525231 calia6c53c03131 Out
```

For the **hairpin** reply, the PREROUTING step does change something. That entry
has `SRC_NAT` too, so the destination is set back from `10.244.120.64:36689` to
the client's `.115:49382` before routing. Then POSTROUTING changes the source,
exactly as above.

#### The entry also flags which NAT it did

The difference between the two sides shows *what* was rewritten. The entry
also carries flags saying *that* it was. `conntrack -L` doesn't print them, but
it can filter on them: `--dst-nat` (`-g`), `--src-nat` (`-n`), `--any-nat` (`-j`).

Six connections from `web-…-4shjp` to the `web` ClusterIP (with
`kubectl exec … /agnhost connect 10.107.59.191:80`, run on the node):

```
$ sudo conntrack -L -d 10.107.59.191
tcp 6 119 TIME_WAIT src=10.244.120.115 dst=10.107.59.191 sport=57688 dport=80 packets=4 bytes=216 src=10.244.120.115 dst=10.244.120.64 sport=8080 dport=29993 packets=3 bytes=164 [ASSURED] mark=0 use=1
tcp 6 119 TIME_WAIT src=10.244.120.115 dst=10.107.59.191 sport=57666 dport=80 packets=4 bytes=216 src=10.244.120.115 dst=10.244.120.64 sport=8080 dport=14108 packets=3 bytes=164 [ASSURED] mark=0 use=1
tcp 6 119 TIME_WAIT src=10.244.120.115 dst=10.107.59.191 sport=57700 dport=80 packets=4 bytes=216 src=10.244.120.115 dst=10.244.120.64 sport=8080 dport=58157 packets=3 bytes=164 [ASSURED] mark=0 use=1
tcp 6 119 TIME_WAIT src=10.244.120.115 dst=10.107.59.191 sport=57704 dport=80 packets=4 bytes=216 src=10.244.120.115 dst=10.244.120.64 sport=8080 dport=49533 packets=2 bytes=112 [ASSURED] mark=0 use=1
tcp 6 119 TIME_WAIT src=10.244.120.115 dst=10.107.59.191 sport=57652 dport=80 packets=4 bytes=216 src=10.244.120.74 dst=10.244.120.115 sport=8080 dport=57652 packets=3 bytes=164 [ASSURED] mark=0 use=1
tcp 6 119 TIME_WAIT src=10.244.120.115 dst=10.107.59.191 sport=57678 dport=80 packets=4 bytes=216 src=10.244.120.71 dst=10.244.120.115 sport=8080 dport=57678 packets=2 bytes=112 [ASSURED] mark=0 use=1

$ sudo conntrack -L -d 10.107.59.191 --dst-nat
… the same 6 entries …
conntrack v1.4.6 (conntrack-tools): 6 flow entries have been shown.

$ sudo conntrack -L -d 10.107.59.191 --src-nat
… only the first 4 (sport 57688, 57666, 57700, 57704) …
conntrack v1.4.6 (conntrack-tools): 4 flow entries have been shown.
```

Read with the two comparisons from the table above:

| Entries | ORIGINAL dst → REPLY src | ORIGINAL src → REPLY dst | Flags | What happened |
|---|---|---|---|---|
| sport 57652, 57678 | ClusterIP:80 → `.74:8080` / `.71:8080` | `.115:port` → `.115:port` (same) | DNAT | Another Pod was picked. Only the destination changed |
| sport 57688, 57666, 57700, 57704 | ClusterIP:80 → `.115:8080` | `.115:57688` → `10.244.120.64:29993` | DNAT + SNAT | **Hairpin**: the client picked *itself*, so the source was changed to the node's `tunl0` address and a random port |

Four hairpins out of six is chance. Each connection has a 1-in-3 chance of
going back to the client. The `web` Service's full rule set (from the
kube-proxy Pod's `iptables-save -t nat`) shows where the pick and
the SNAT come from:

```
# KUBE-SVC: the pick. Each rule is tried in turn, and the first match wins.
-A KUBE-SVC-IGTRU775QHYZG4VA -m comment --comment "svc-demo/web -> 10.244.120.115:8080" -m statistic --mode random --probability 0.33333333349 -j KUBE-SEP-FPB55NDEKFM2GH7L
-A KUBE-SVC-IGTRU775QHYZG4VA -m comment --comment "svc-demo/web -> 10.244.120.71:8080" -m statistic --mode random --probability 0.50000000000 -j KUBE-SEP-NG6AIAJCPIV5FQ6B
-A KUBE-SVC-IGTRU775QHYZG4VA -m comment --comment "svc-demo/web -> 10.244.120.74:8080" -j KUBE-SEP-UCUFHSMNCIZQIFW7

# KUBE-SEP: one chain per Pod. Rule 1 = hairpin check, rule 2 = DNAT.
-A KUBE-SEP-FPB55NDEKFM2GH7L -s 10.244.120.115/32 -m comment --comment "svc-demo/web" -j KUBE-MARK-MASQ
-A KUBE-SEP-FPB55NDEKFM2GH7L -p tcp -m comment --comment "svc-demo/web" -m tcp -j DNAT --to-destination 10.244.120.115:8080
-A KUBE-SEP-NG6AIAJCPIV5FQ6B -s 10.244.120.71/32 -m comment --comment "svc-demo/web" -j KUBE-MARK-MASQ
-A KUBE-SEP-NG6AIAJCPIV5FQ6B -p tcp -m comment --comment "svc-demo/web" -m tcp -j DNAT --to-destination 10.244.120.71:8080
-A KUBE-SEP-UCUFHSMNCIZQIFW7 -s 10.244.120.74/32 -m comment --comment "svc-demo/web" -j KUBE-MARK-MASQ
-A KUBE-SEP-UCUFHSMNCIZQIFW7 -p tcp -m comment --comment "svc-demo/web" -m tcp -j DNAT --to-destination 10.244.120.74:8080
```

| Rule | Chance it matches | Chance overall |
|---|---|---|
| 1st `KUBE-SVC` → `.115` | 0.333 | 1/3 |
| 2nd → `.71` (only reached if the 1st didn't match) | 0.5 of the remaining 2/3 | 1/3 |
| 3rd → `.74` (no condition: everything left) | 1 | 1/3 |

Each `KUBE-SEP` chain's first rule matches only when **the source is that same
Pod**, i.e. the Pod reached itself through the Service. It doesn't NAT
anything itself. It sets mark `0x4000`, and `KUBE-POSTROUTING` later
masquerades marked packets after routing has chosen the way out.

`10.244.120.64` is what `MASQUERADE` chose. It's the address the node uses to
reach the Pod, which is the node's `tunl0` address:

```
$ ip -4 addr show tunl0
    inet 10.244.120.64/32 scope global tunl0
$ ip route get 10.244.120.115
10.244.120.115 dev calia6c53c03131 src 10.244.120.64 uid 1000
    cache
```

So the NAT is recorded twice in the entry. The flags say which kinds were
applied, and the REPLY tuple says what the addresses became.

#### Watching an entry being created: `conntrack -E`

`conntrack -L` lists the table as it is now. `conntrack -E` prints each change
as it happens. It was streamed from the node while `web-…-4shjp` ran
`wget -qO- http://10.107.59.191/clientip` 8 times. Here's one
connection of each kind, with timestamps shortened to milliseconds from that
connection's first packet:

```
DNAT only: picked .71
  +0.0 ms    [NEW] SYN_SENT    src=10.244.120.115 dst=10.107.59.191 sport=34658 dport=80 [UNREPLIED] src=10.244.120.71 dst=10.244.120.115 sport=8080 dport=34658
  +0.7 ms [UPDATE] SYN_RECV    … same tuples …
  +0.8 ms [UPDATE] ESTABLISHED … same tuples … [ASSURED]
  +3.1 ms [UPDATE] LAST_ACK    …
  +3.2 ms [UPDATE] TIME_WAIT   …

Hairpin: picked itself
  +0.0 ms    [NEW] SYN_SENT    src=10.244.120.115 dst=10.107.59.191 sport=34668 dport=80 [UNREPLIED] src=10.244.120.115 dst=10.244.120.64 sport=8080 dport=2803
  +0.0 ms [UPDATE] SYN_RECV    … same tuples …
  +0.0 ms [UPDATE] ESTABLISHED … same tuples … [ASSURED]
  +2.1 ms [UPDATE] FIN_WAIT    …
  +2.1 ms [UPDATE] LAST_ACK    …
  +2.1 ms [UPDATE] TIME_WAIT   …
```

(The command was `minikube ssh -- 'sudo timeout 20 conntrack -E -d 10.107.59.191 -o timestamp'`.
It printed 47 events for the 8 connections.)

| What you see | What it means |
|---|---|
| `[NEW]` already has the full REPLY side | The NAT is decided on the **first packet**, the SYN, before the server has answered. The REPLY side is what the kernel *expects* back |
| `[UNREPLIED]` on `[NEW]` only | Nothing has come back yet. It goes away when the SYN-ACK matches the REPLY side |
| The tuples never change after `[NEW]` | Later packets don't re-pick or re-NAT; they're looked up and rewritten the same way (§3.5 above) |
| `[ASSURED]` from `ESTABLISHED` | Traffic was seen both ways, so the entry won't be dropped first when the table fills |
| Timeouts `120 → 60 → 432000 → 30 → 120` | SYN_SENT 2 min, SYN_RECV 1 min, ESTABLISHED 5 days, LAST_ACK 30 s, TIME_WAIT 2 min: the kernel's default `nf_conntrack_tcp_timeout_*` values |
| There's no `[DESTROY]` | The stream stopped after 20 s, but TIME_WAIT lasts 120 s |

#### What the server sees as the client

agnhost's `/clientip` replies with the source address the server saw. These are
the same 8 requests, matched to the events by port:

```
$ for i in $(seq 8); do kubectl -n svc-demo exec web-7b6bc8bc5-4shjp -- wget -qO- http://10.107.59.191/clientip; echo; done
10.244.120.115:34658     ← .71 answered: real client address
10.244.120.115:34662     ← .74
10.244.120.115:34666     ← .74
10.244.120.64:2803       ← hairpin: the node's tunl0 address, the port from the REPLY side
10.244.120.115:34682     ← .74
10.244.120.64:30693      ← hairpin
10.244.120.115:34708     ← .74
10.244.120.64:21651      ← hairpin
```

The three hairpin answers have ports `2803`, `30693` and `21651`. Those are
exactly the `dport=` values on the REPLY side of the three hairpin events. The
server's view and conntrack's REPLY tuple are the same thing.

#### Does the Pod reply to the client directly?

**Its addresses do. Its path doesn't.** Without SNAT, the server sees the real
client IP, so it addresses its reply straight to `10.244.120.115:34658`. It has
never heard of the ClusterIP.

The reply still goes through the node's kernel before reaching the client.
There it matches the entry's REPLY side and its source is changed back to
`10.107.59.191:80` (③ → ④ above). It can't skip that step: a Pod's veth leads
into the host, so every packet a Pod sends is handled by the node first.

This only works because the reply reaches **the node that did the DNAT**, the
one holding the entry. SNAT is how kube-proxy forces that when it isn't
guaranteed:

```
 Pod client, other Pod as backend (DNAT only)        verified above
   client ─► node kernel: DNAT, entry ─► server
   client ◄─ node kernel: un-DNAT     ◄─ server     reply goes to the client's IP, through the node

 Pod client, itself as backend (hairpin: DNAT + SNAT)  both halves tested: see the hairpin tests below
   without SNAT: the SYN comes back into the Pod from its own address, 10.244.120.115.
                 The Pod's kernel drops it as a "martian source". There's no SYN-ACK, and the client times out.
   with SNAT:    the server answers 10.244.120.64:2803 → that goes to the node → matches REPLY →
                 rewritten to 10.107.59.191:80 → 10.244.120.115:34668

 Outside client → node port on node A, backend on node B (externalTrafficPolicy: Cluster)
   without SNAT: B's Pod answers the outside IP directly, through node B. Node B has no entry,
                 so the source isn't changed back. The client gets a reply from a Pod IP it
                 never called and drops it.
   with SNAT:    B's Pod answers node A → A un-NATs both sides → the client gets
                 node A:port, which it did call.
                 The SNAT itself is shown in §4.1.1 (E3). The lab has one node, so the
                 two-node path is reasoning, not tested.
```

`externalTrafficPolicy: Local` avoids the SNAT a different way. It only sends to
Pods on the node that received the packet, so the reply already goes back
through the right node (§4.1.1, E7).

#### When does hairpin happen?

You never set it up on purpose. A hairpin is what happens when a Pod calls a
Service that it is itself one of the backends for. The SNAT exists so that
call doesn't fail. The client only knows the Service name, and kube-proxy
picks a backend at random without checking whether it's the caller. With N
replicas, about 1 in N of a backend's own calls to its Service comes back to
itself (10 of 36 in test 1 below).

Where Pods call their own Service:

| Situation | Example |
|---|---|
| An app calling its own API by URL | A web frontend that server-side renders by fetching `http://shop-api/...`, where `shop-api` also serves the API. Or an app that calls one of its own endpoints, such as a webhook, callback or internal job trigger, through its configured base URL |
| Clustered software finding its peers through a Service | Elasticsearch, Cassandra seeds, Kafka bootstrap, RabbitMQ, Consul, etcd. A member connects to `my-cluster:9300` to join and may land on itself |
| Config that's the same for every replica | `API_URL=http://orders.orders.svc` set on all `orders` Pods, so every replica's outbound call can hairpin |
| Proxies and gateways | An ingress controller or Envoy Pod whose route points at a Service that includes the proxy itself, such as a default backend, an auth subrequest or an admin route |
| Monitoring and sidecars | A sidecar calling the app through its Service instead of `localhost`, or a Pod's metrics exporter scraping through the Service |

Without the SNAT, those self-picks are dropped as martians and time out
(test 4). With 3 replicas, about a third of such calls would hang at random.
That's hard to diagnose, because it depends on the pick and looks like flaky
networking.

To avoid hairpin where it matters:
- If a Pod means to call *itself*, use `localhost:<port>`. That's the test 3
  path: no NAT and no node involved.
- If it means a *peer*, use a headless Service or StatefulSet DNS names, and
  skip your own name.

You can't switch hairpin SNAT off in kube-proxy; it's always there for this
case. Its one cost is that the server sees the node's address
(`10.244.120.64`) instead of its own IP. That only matters if the app logs or
authorises callers by source IP.

#### Hairpin tests

Four tests on the lab (one node, Calico, kube-proxy in iptables
mode). The client asks agnhost's `/clientip` what source address the server
saw.

**Test 1: every backend calls the Service it belongs to.** Each of the three
`web` Pods made 12 connections to `10.107.59.191:80`.

```
$ for p in 4shjp bcjj6 5nkcv; do for i in $(seq 12); do
    kubectl -n svc-demo exec web-7b6bc8bc5-$p -- wget -qO- http://10.107.59.191/clientip; echo
  done | sed 's/:.*//' | sort | uniq -c; done

client 4shjp (10.244.120.115)    8 × 10.244.120.115    4 × 10.244.120.64
client bcjj6 (10.244.120.71)     9 × 10.244.120.71     3 × 10.244.120.64
client 5nkcv (10.244.120.74)     9 × 10.244.120.74     3 × 10.244.120.64
```

The 36 conntrack entries, sorted by whether the REPLY `src` equals the
ORIGINAL `src` (the Pod picked itself) and whether the REPLY `dst` changed (SNAT):

```
   8 client 10.244.120.115  picked another Pod        →  no SNAT
   4 client 10.244.120.115  picked itself (hairpin)   →  SNAT to 10.244.120.64
   9 client 10.244.120.71   picked another Pod        →  no SNAT
   3 client 10.244.120.71   picked itself (hairpin)   →  SNAT to 10.244.120.64
   9 client 10.244.120.74   picked another Pod        →  no SNAT
   3 client 10.244.120.74   picked itself (hairpin)   →  SNAT to 10.244.120.64
```

SNAT happened **exactly** when a Pod picked itself: 10 of 36, against an
expected 12. It never happened otherwise. Every backend has its own
`-s <itself> -j KUBE-MARK-MASQ` rule, so this applies to all of them.

**Test 2: a Service whose only backend is the client.** It's a temporary
Service `hairpin` with no selector and a hand-written EndpointSlice holding only
`10.244.120.115:8080`. Its ClusterIP is `10.108.17.148`. kube-proxy wrote the
same rule pair as for `web`:

```
-A KUBE-SVC-KMDTDUTDXZ3JCUYS -m comment --comment "svc-demo/hairpin:http -> 10.244.120.115:8080" -j KUBE-SEP-4EMOS4GOJXI2TPED
-A KUBE-SEP-4EMOS4GOJXI2TPED -s 10.244.120.115/32 -m comment --comment "svc-demo/hairpin:http" -j KUBE-MARK-MASQ
-A KUBE-SEP-4EMOS4GOJXI2TPED -p tcp -m comment --comment "svc-demo/hairpin:http" -m tcp -j DNAT --to-destination 10.244.120.115:8080
```

```
4shjp → hairpin Service (backend = itself)      bcjj6 → hairpin Service (backend = another Pod)
  10.244.120.64:9787                              10.244.120.71:57328
  10.244.120.64:6079                              10.244.120.71:57344
  10.244.120.64:8567                              10.244.120.71:57346
  10.244.120.64:7247
  10.244.120.64:33634
```

Every self-call is SNATed. The same Service called from another Pod isn't.
The SNAT depends on who calls, not on the Service.

**Test 3: a Pod calling its own Pod IP, with no Service.**

```
4shjp → 10.244.120.115:8080/clientip
  10.244.120.115:40536
  10.244.120.115:40538
  10.244.120.115:40540

node: conntrack -L -s 10.244.120.115 -d 10.244.120.115  →  0 entries
```

This is a normal local connection. The Pod's kernel delivers it to itself,
the packets never leave the Pod's network namespace, and the node sees
nothing. Hairpin only happens when a *Service address* is involved, because
only the node can resolve that address.

**Test 4: switching the hairpin SNAT off.** A temporary rule at the top of
the node's `nat POSTROUTING` made packets from `.115` to `.115:8080` skip
`KUBE-POSTROUTING`, which is where `MASQUERADE` happens. It was inserted
from the kube-proxy Pod, so it was written with the same iptables 1.8.9:

```
$ iptables -t nat -I POSTROUTING 1 -s 10.244.120.115/32 -d 10.244.120.115/32 -p tcp --dport 8080 \
    -m comment --comment "hairpin-test: skip SNAT" -j RETURN
$ iptables -t nat -S POSTROUTING
-P POSTROUTING ACCEPT
-A POSTROUTING -s 10.244.120.115/32 -d 10.244.120.115/32 -p tcp -m tcp --dport 8080 -m comment --comment "hairpin-test: skip SNAT" -j RETURN
-A POSTROUTING -m comment --comment "CNI portfwd requiring masquerade" -j CNI-HOSTPORT-MASQ
-A POSTROUTING -m comment --comment "kubernetes postrouting rules" -j KUBE-POSTROUTING
-A POSTROUTING -s 172.17.0.0/16 ! -o docker0 -j MASQUERADE
-A POSTROUTING -d 192.168.65.254/32 -j DOCKER_POSTROUTING
-A POSTROUTING -m comment --comment "cali:0i8pjzKKPyA34aQD" -j cali-POSTROUTING

$ kubectl -n svc-demo exec web-7b6bc8bc5-4shjp -- wget -qO- -T 4 http://10.108.17.148/clientip
wget: download timed out
```

The node's entry stayed half-open. The REPLY side expects an answer from
`.115` to `.115`, and none came:

```
tcp 6 115 SYN_SENT src=10.244.120.115 dst=10.108.17.148 sport=42560 dport=80 packets=1 bytes=60 [UNREPLIED]
                   src=10.244.120.115 dst=10.244.120.115 sport=8080 dport=42560 packets=0 bytes=0
```

tcpdump ran on every interface inside the Pod's network namespace (`nsenter -t
<agnhost PID> -n tcpdump -i any`, from a temporary privileged `hostPID` Pod)
and on the node's side of its veth. Both show the same thing: the SYN goes out
to the ClusterIP and comes back DNATed with the Pod's own address as source. It
was retried at 1, 2 and 3 s, and there's never a SYN-ACK:

```
13:15:07.795573 eth0  Out IP 10.244.120.115.42560 > 10.108.17.148.80:    Flags [S]   ← the client's SYN
13:15:07.795687 eth0  In  IP 10.244.120.115.42560 > 10.244.120.115.8080:  Flags [S]   ← back, DNATed, source not changed
13:15:08.825903 eth0  Out IP 10.244.120.115.42560 > 10.108.17.148.80:    Flags [S]   ← retransmit
13:15:08.826192 eth0  In  IP 10.244.120.115.42560 > 10.244.120.115.8080:  Flags [S]
… same pair at 13:15:09.85 and 13:15:10.87; 8 packets in all, no [S.] …
```

The server's socket never saw the SYN, because the Pod's kernel dropped it
first. Run again with `log_martians=1` set in the Pod's netns only (and reset
afterwards):

```
$ dmesg
IPv4: martian source (src=10.244.120.115, dst=10.244.120.115, dev=eth0)
IPv4: martian source (src=10.244.120.115, dst=10.244.120.115, dev=eth0)
IPv4: martian source (src=10.244.120.115, dst=10.244.120.115, dev=eth0)

$ nsenter -t <PID> -n sysctl net.ipv4.conf.eth0.accept_local
net.ipv4.conf.eth0.accept_local = 0
```

A packet arriving on a real interface (`eth0`) with one of the host's *own*
addresses as its source is treated as spoofed. With `accept_local = 0`, the
default, the kernel drops it during routing, before TCP sees it. With SNAT
restored (rule deleted), the same `wget` returned `10.244.120.64:31746` at once.

So without the hairpin SNAT, the self-call fails one step **earlier** than
"the reply skips the un-DNAT". The request itself is dropped. The SNAT does
two jobs here:

| Without SNAT | With SNAT to 10.244.120.64 |
|---|---|
| The SYN arrives with the Pod's own address as source → dropped as martian | The source is the node's address → accepted |
| Even if it were accepted, the reply to `.115` would stay inside the Pod and never be un-DNATed | The reply goes to `10.244.120.64` → to the node → matches REPLY → rewritten to `10.108.17.148:80 → .115` |

Cleanup: the test rule was deleted (`iptables -t nat -S POSTROUTING | grep -c
hairpin-test` → `0`), `log_martians` was set back to `0`, and the `hairpin`
Service, its EndpointSlice and the capture Pod were deleted.

Not tested: whether `accept_local=1` would turn this into the
"reply skips the un-DNAT" failure; other CNIs (some handle hairpin in the
bridge instead, with `hairpinMode`); kube-proxy's nftables and IPVS modes.

### 3.6 EndpointSlices and readiness decide who gets traffic

The EndpointSlice controller (part of kube-controller-manager) watches the
Service's selector and the matching pods, and writes the result into
EndpointSlice objects. kube-proxy on each node reads those, never the pods
directly. The `web` Service's slice (`kubectl -n svc-demo get endpointslice
-l kubernetes.io/service-name=web -o yaml`, trimmed):

```yaml
apiVersion: discovery.k8s.io/v1
kind: EndpointSlice
metadata:
  name: web-g2xh7                                   # generated: <service>-<random>
  labels:
    kubernetes.io/service-name: web                 # how a slice is tied to its Service
    endpointslice.kubernetes.io/managed-by: endpointslice-controller.k8s.io
addressType: IPv4
ports:
- port: 8080                                        # the targetPort
  protocol: TCP
endpoints:
- addresses: [10.244.120.114]
  conditions: {ready: true, serving: true, terminating: false}
  nodeName: minikube
  targetRef: {kind: Pod, name: web-7b6bc8bc5-wflg7, namespace: svc-demo}
- addresses: [10.244.120.116]
  conditions: {ready: true, serving: true, terminating: false}
  nodeName: minikube
  targetRef: {kind: Pod, name: web-7b6bc8bc5-x2pfs, namespace: svc-demo}
- addresses: [10.244.120.121]
  conditions: {ready: true, serving: true, terminating: false}
  nodeName: minikube
  targetRef: {kind: Pod, name: web-7b6bc8bc5-p8nbc, namespace: svc-demo}
```

The three conditions:

| Condition | Meaning | Gets new connections? |
|---|---|---|
| `ready` | Passing its readiness probe and not terminating | Yes |
| `serving` | Passing its readiness probe, even if terminating | Only as a fallback when no endpoint is `ready` |
| `terminating` | The pod is being deleted | No (unless it's the fallback above) |

`nodeName` is what `externalTrafficPolicy: Local` uses to pick node-local
endpoints (§4.3).

The listings below print one line per endpoint with:

```bash
kubectl -n svc-demo get endpointslice -l kubernetes.io/service-name=web -o json | jq -r \
  '.items[].endpoints[] | "\(.addresses[0])  ready=\(.conditions.ready) serving=\(.conditions.serving) terminating=\(.conditions.terminating)  pod=\(.targetRef.name)"'
```

During a rollout the old pods are kept but flagged:

```
10.244.120.65  ready=false serving=true terminating=true   pod=web-7dc54bcb87-h7tlz
10.244.120.71  ready=true  serving=true terminating=false  pod=web-6b4ff589f4-rr8m9
...
```

kube-proxy only writes rules for ready endpoints. Making one pod unready (its
readiness probe checks for `/tmp/ready`, §0.2, so deleting the file fails the
probe within 2 seconds):

```bash
kubectl -n svc-demo exec web-7b4b8c7c84-h9km8 -- rm /tmp/ready      # take it out
kubectl -n svc-demo exec web-7b4b8c7c84-h9km8 -- touch /tmp/ready   # put it back
```

```
web-7b4b8c7c84-h9km8  ready=false

300 requests:   164 web-...-scntw    136 web-...-tlztj         # h9km8 gets nothing
iptables:       svc-demo/web -> 10.244.120.85:8080
                svc-demo/web -> 10.244.120.87:8080            # only 2 endpoints

after restoring readiness:   97 h9km8   98 scntw   105 tlztj
```

The pod was never restarted. It was only taken out of rotation, then put back.

## 4. Each Service type, tried

### 4.1 Where each type lands in the NAT chains

`KUBE-SERVICES` doesn't get one entry per Service. It gets one entry per
**address and port that traffic can arrive on**, and the Service type decides
how many such addresses there are. Six Services, one per variant:

```
NAME         TYPE           CLUSTER-IP       EXTERNAL-IP      PORT(S)
t-cluster    ClusterIP      10.108.167.147   <none>           80/TCP
t-nodeport   NodePort       10.106.133.73    <none>           80:30081/TCP
t-lb         LoadBalancer   10.111.233.178   127.0.0.1        8090:30082/TCP    # IP from minikube tunnel
t-extip      ClusterIP      10.111.153.85    192.168.49.200   80/TCP            # spec.externalIPs
t-headless   ClusterIP      None             <none>           8080/TCP
t-extname    ExternalName   <none>           kubernetes.io    <none>
```

The NAT rules they produced (from `iptables-save` inside the kube-proxy pod):

```
# KUBE-SERVICES: one entry per ClusterIP, external IP and load balancer IP, each with its port
-A KUBE-SERVICES -d 10.108.167.147/32 -p tcp "svc-demo/t-cluster cluster IP"       --dport 80   -j KUBE-SVC-EPYL...
-A KUBE-SERVICES -d 10.106.133.73/32  -p tcp "svc-demo/t-nodeport cluster IP"      --dport 80   -j KUBE-SVC-6PII...
-A KUBE-SERVICES -d 10.111.233.178/32 -p tcp "svc-demo/t-lb cluster IP"            --dport 8090 -j KUBE-SVC-2VPX...
-A KUBE-SERVICES -d 127.0.0.1/32      -p tcp "svc-demo/t-lb loadbalancer IP"       --dport 8090 -j KUBE-EXT-2VPX...
-A KUBE-SERVICES -d 10.111.153.85/32  -p tcp "svc-demo/t-extip cluster IP"         --dport 80   -j KUBE-SVC-E2CZ...
-A KUBE-SERVICES -d 192.168.49.200/32 -p tcp "svc-demo/t-extip external IP"        --dport 80   -j KUBE-EXT-E2CZ...

# KUBE-NODEPORTS: one entry per node port (the second rule per Service handles localhost)
-A KUBE-NODEPORTS             -p tcp "svc-demo/t-nodeport" --dport 30081 -j KUBE-EXT-6PII...
-A KUBE-NODEPORTS -d 127.0.0.0/8 -p tcp "svc-demo/t-nodeport" --dport 30081 -m nfacct ... -j KUBE-EXT-6PII...
-A KUBE-NODEPORTS             -p tcp "svc-demo/t-lb"       --dport 30082 -j KUBE-EXT-2VPX...
-A KUBE-NODEPORTS -d 127.0.0.0/8 -p tcp "svc-demo/t-lb"       --dport 30082 -m nfacct ... -j KUBE-EXT-2VPX...

# t-headless, t-extname: no rules in any table
```

Per Service, the number of entries is:

| Service | `KUBE-SERVICES` entries | `KUBE-NODEPORTS` entries | Chains |
|---|---|---|---|
| ClusterIP | 1 per port (ClusterIP) | – | `KUBE-SVC` |
| NodePort | 1 per port (ClusterIP) | 1 per port | `KUBE-SVC`, `KUBE-EXT` |
| LoadBalancer | 1 per port for the ClusterIP + 1 per port per load balancer IP | 1 per port | `KUBE-SVC`, `KUBE-EXT` |
| `externalIPs` set (any type) | +1 per port per external IP | – | `KUBE-EXT` |
| Headless | 0 | 0 | none; DNS returns pod IPs |
| ExternalName | 0 | 0 | none; DNS CNAME |
| Selector matches no pods | 0 in `nat`; a `REJECT` in the `filter` table's own `KUBE-SERVICES` chain (§4.6) | | |

**Every entry point ends at the same `KUBE-SVC` chain.** The external entries
(load balancer IP, external IP, node port) jump to a `KUBE-EXT` chain first. It
marks the packet for a source rewrite, then hands off to the Service's ordinary
`KUBE-SVC` chain:

```
-A KUBE-EXT-2VPXTAGMVMM46THU "masquerade traffic for svc-demo/t-lb external destinations" -j KUBE-MARK-MASQ
-A KUBE-EXT-2VPXTAGMVMM46THU -j KUBE-SVC-2VPXTAGMVMM46THU
```

The mark is the `externalTrafficPolicy: Cluster` source rewrite measured in
§4.3. Switching `t-nodeport` to `Local` changed its `KUBE-EXT` chain to sort
traffic three ways:

```
-A KUBE-EXT-6PII... -s 10.244.0.0/16 "pod traffic for svc-demo/t-nodeport external destinations" -j KUBE-SVC-6PII...
-A KUBE-EXT-6PII... "masquerade LOCAL traffic ..." -m addrtype --src-type LOCAL -j KUBE-MARK-MASQ
-A KUBE-EXT-6PII... "route LOCAL traffic ..."      -m addrtype --src-type LOCAL -j KUBE-SVC-6PII...
-A KUBE-EXT-6PII... -j KUBE-SVL-6PII...
-A KUBE-SVL-6PII... "svc-demo/t-nodeport -> 10.244.120.90:8080" ... -j KUBE-SEP-...   # node-local endpoints only
```

- **Pods** calling the NodePort or load balancer address still get the
  cluster-wide `KUBE-SVC` chain.
- **Processes on the node itself** get the source rewrite plus `KUBE-SVC`.
- **Real external clients** fall through to `KUBE-SVL`, which lists only this
  node's endpoints and has no source rewrite. That's why the client IP
  survived in §4.3.

On this single-node cluster every endpoint is local, so `KUBE-SVL` happens to
list all three.

Whichever address a request arrives on, it reaches the same three endpoints:

```
ClusterIP from a pod:          curl http://t-lb:8090                -> web-7b6bc8bc5-x2pfs
Load balancer IP from the Mac: curl http://127.0.0.1:8090           -> web-7b6bc8bc5-p8nbc
NodePort from outside:         curl http://192.168.49.2:30082       -> web-7b6bc8bc5-4gdww
```

**`KUBE-NODEPORTS` is reached last.** It is a single jump at the end of
`KUBE-SERVICES`, taken only for packets addressed to the node itself (§3.3).
A NodePort connection therefore first fails to match every ClusterIP entry,
then walks `KUBE-NODEPORTS`.

These rules are built in `syncProxyRules` (`pkg/proxy/iptables/proxier.go`). The
entries are labelled with the comments `"%s cluster IP"`, `"%s external IP"` and
`"%s loadbalancer IP"`, and the node port rules go to `kubeNodePortsChain`.

#### Tables and chains: what `KUBE-SERVICES`, `KUBE-SVC` and `KUBE-EXT` are

They're all **chains**. The **tables** are `raw`, `mangle`, `nat` and `filter`.

- **Table:** a fixed group defined by the kernel, grouped by purpose. `nat`
  rewrites addresses, `filter` accepts or drops packets, `mangle` changes packet
  fields or marks, and `raw` runs before conntrack. iptables can't create new
  tables.
- **Chain:** an ordered list of rules **inside** a table. There are two kinds:
  - **Built-in chains**, one per hook point the table uses: `PREROUTING`,
    `INPUT`, `FORWARD`, `OUTPUT`, `POSTROUTING`. Packets enter these automatically.
  - **User-defined chains**, which anyone can create. kube-proxy creates every
    `KUBE-…` chain. A packet only enters one when a rule jumps to it with `-j`.

`iptables-save` declares each chain at the top of its table, on a line starting
with `:`. Here's `t-lb`'s set from the nat dump:

```
:PREROUTING ACCEPT [3373:242386]     ← built-in: has a policy (ACCEPT) and counters
:KUBE-SERVICES - [0:0]               ← user-defined: "-" means no policy
:KUBE-SVC-2VPXTAGMVMM46THU - [0:0]
:KUBE-EXT-2VPXTAGMVMM46THU - [0:0]
:KUBE-SEP-N2ZSYKUSTVSOTSP5 - [0:0]
```

A user-defined chain has no policy. If a packet gets to the end without a
final verdict (such as `DNAT`), it **returns** to the rule after the jump that
brought it there.

`KUBE-SERVICES` only runs because kube-proxy added jumps to it from the
built-in chains:

```
nat table
  PREROUTING  (built-in, runs for arriving packets)
    -A PREROUTING -j KUBE-SERVICES        ← jump
  OUTPUT      (built-in, runs for packets from the node)
    -A OUTPUT -j KUBE-SERVICES            ← jump
  KUBE-SERVICES (user-defined)
    -A KUBE-SERVICES -d 10.107.59.191/32 --dport 80 -j KUBE-SVC-IGTRU775QHYZG4VA
    ...
```

**A chain's name is only unique within its table.** There are two
`KUBE-SERVICES` chains and two `KUBE-NODEPORTS` chains on the node:

| Chain | Table | Jumped to from | Holds |
|---|---|---|---|
| `KUBE-SERVICES` | **nat** | `PREROUTING`, `OUTPUT` | the ClusterIP, external IP and LB IP entries that lead to DNAT |
| `KUBE-SERVICES` | **filter** | `FORWARD`, `OUTPUT` (new connections) | `REJECT` for Services with no endpoints (`dns-demo/empty`) |
| `KUBE-NODEPORTS` | **nat** | the last rule of nat `KUBE-SERVICES` | node port jumps to `KUBE-EXT` |
| `KUBE-NODEPORTS` | **filter** | `INPUT` | `ACCEPT` for health check node ports |

That's why `iptables-save` takes a table (`-t nat`, `-t filter`), and why rules
are written `-A <chain>`: first the table is chosen, then the chain inside it.

**kube-proxy's per-Service chains.** The hash in each name comes from the
Service port, plus the endpoint for `KUBE-SEP`. The counts are chain
declarations in the nat dump:

| Prefix | One per | Job | In nat on the lab |
|---|---|---|---|
| `KUBE-SVC-<hash>` | Service port | "Cluster" policy: pick any endpoint at random | 31 |
| `KUBE-SVL-<hash>` | Service port with a `Local` traffic policy | "Local" policy: pick only from this node's endpoints | 2 (Envoy's two ports) |
| `KUBE-EXT-<hash>` | Service port reachable from outside (node port, external IP or LB IP) | external traffic: mark for SNAT or sort callers by ETP, then `KUBE-SVC`/`KUBE-SVL` | 7 |
| `KUBE-SEP-<hash>` | Service port × endpoint | hairpin mark, then DNAT to one Pod | 56 |
| `KUBE-FW-<hash>` | Service port with `loadBalancerSourceRanges` | allow LB IP traffic by source | 0 (seen only while testing in §4.4.1) |

The 31 `KUBE-SVC` chains match the 31 `cluster IP` rules in `KUBE-SERVICES`
(§3.3): one per Service port with endpoints.

**The shared chains**, one of each:

| Table | Chains |
|---|---|
| nat | `KUBE-SERVICES`, `KUBE-NODEPORTS`, `KUBE-POSTROUTING`, `KUBE-MARK-MASQ`, plus the canaries `KUBE-PROXY-CANARY` and `KUBE-KUBELET-CANARY` |
| filter | `KUBE-SERVICES`, `KUBE-NODEPORTS`, `KUBE-EXTERNAL-SERVICES`, `KUBE-PROXY-FIREWALL`, `KUBE-FORWARD`, `KUBE-FIREWALL`, and the canaries |

The per-Service chains exist **only in nat**. So the whole path, from the
built-in chain to the rule that does the DNAT, is a series of jumps between
chains in one table. For `t-lb` reached on its LB IP:

```
nat: PREROUTING → KUBE-SERVICES → KUBE-EXT-2VPX… → KUBE-SVC-2VPX… → KUBE-SEP-N2ZS… → DNAT
     (built-in)   (shared)        (per port)       (per port)       (per port × Pod)
```

The SNAT, if a rule on that path set the `0x4000` mark, happens later, in
another jump in the same table:
`POSTROUTING → KUBE-POSTROUTING → MASQUERADE`.

**How many `KUBE-SERVICES` chains?** One per table, per IP family, **per node**.

On the lab's node, from the kube-proxy Pod:

```
                     IPv4 (iptables-save)          IPv6 (ip6tables-save)
nat    KUBE-SERVICES  36 rules: all the Services   1 rule: only the KUBE-NODEPORTS jump
filter KUBE-SERVICES   1 rule: dns-demo/empty      0 rules
```

kube-proxy logs `"kube-proxy running in dual-stack mode" primary
ipFamily="IPv4"`, so it maintains an IPv4 set and an IPv6 set. The Services
here are IPv4 only, so the IPv6 nat chain only has the shared tail rule:

```
-A KUBE-SERVICES ! -d ::1/128 -m comment --comment "kubernetes service nodeports; NOTE: this must be the last rule in this chain" -m addrtype --dst-type LOCAL -j KUBE-NODEPORTS
```

**One per node, not one per cluster.** iptables rules live in one node's
kernel, so every node's kube-proxy (a DaemonSet: one Pod per node) writes its
**own** chains. Each copy lists **every Service in the cluster**, not only the
ones with Pods on that node, because any Pod on any node may call any Service:

```
node A kernel: nat KUBE-SERVICES → all Services → KUBE-SVC → KUBE-SEP → DNAT to Pods on A, B, C…
node B kernel: nat KUBE-SERVICES → all Services → KUBE-SVC → KUBE-SEP → DNAT to Pods on A, B, C…
node C kernel: same again
```

So a connection is always NATed by the kernel of the node where it **starts**
(or enters, for node ports and LB IPs). No other node is involved in the
decision. The packet then leaves already addressed to a Pod IP and is routed
there like any other Pod traffic. That's also why the conntrack entry, and the
undo on the reply, are on that first node (§3.5).

The copies are almost identical. They differ only where a rule depends on the
node:

| Part | Same on every node? |
|---|---|
| `KUBE-SERVICES` entries, `KUBE-SVC` picks, `KUBE-SEP` DNAT | yes: every endpoint, cluster-wide |
| `KUBE-SVL` (Local policy) | **no**: only that node's endpoints |
| ETP or ITP `Local` with no local endpoint | **no**: a `DROP` rule only on nodes without one |
| node port jumps | the same rules; "the node's addresses" (`--dst-type LOCAL`) differ per node |

The lab has a single node (`kube-proxy` DaemonSet: 1 desired, 1 ready), so the
per-node differences come from kube-proxy's code, where local endpoints are
worked out using its own node name (`CategorizeEndpoints(…, proxier.nodeName,
…)` in `syncProxyRules`). They weren't seen here.

It's also why the rule count grows as Services × endpoints **on every node**
(§6.1), and why an endpoint change anywhere makes every node's kube-proxy
rewrite its own copy (§6.3).

**How many entries does one Service get?** Not one per ClusterIP: one per
**address + port + protocol**. In the nat `KUBE-SERVICES` on the lab there are
**22 different ClusterIPs but 31 `cluster IP` entries**. Every
entry matches on all three of `-d <IP>`, `-p <protocol>` and `--dport <port>`,
so a packet to the right IP but the wrong port matches none of them.

Same Service, different ports (`bravo`):

```
-A KUBE-SERVICES -d 10.111.109.97/32 -p tcp "svc-demo/bravo:http cluster IP"  --dport 80  -j KUBE-SVC-VZ5KWM7DHWWRYRAQ
-A KUBE-SERVICES -d 10.111.109.97/32 -p tcp "svc-demo/bravo:https cluster IP" --dport 443 -j KUBE-SVC-CWRDXECOLI2BS3IP
```

Same Service, same port number, different protocol (`kube-dns`). UDP 53 and TCP
53 are separate Service ports, so each gets its own entry and its own `KUBE-SVC`
chain:

```
-A KUBE-SERVICES -d 10.96.0.10/32 -p udp "kube-system/kube-dns:dns cluster IP"     --dport 53   -j KUBE-SVC-TCOU7JCQXEZGVUNU
-A KUBE-SERVICES -d 10.96.0.10/32 -p tcp "kube-system/kube-dns:dns-tcp cluster IP" --dport 53   -j KUBE-SVC-ERIFXISQEP7F7OF4
-A KUBE-SERVICES -d 10.96.0.10/32 -p tcp "kube-system/kube-dns:metrics cluster IP" --dport 9153 -j KUBE-SVC-JD5MR3NA4I4DYORP
```

Same Service, same port, other addresses (`t-extip`, `t-lb`):

```
-A KUBE-SERVICES -d 10.111.153.85/32  -p tcp "svc-demo/t-extip cluster IP"   --dport 80   -j KUBE-SVC-E2CZQENNJUIIXJBF
-A KUBE-SERVICES -d 192.168.49.200/32 -p tcp "svc-demo/t-extip external IP"  --dport 80   -j KUBE-EXT-E2CZQENNJUIIXJBF
-A KUBE-SERVICES -d 10.111.233.178/32 -p tcp "svc-demo/t-lb cluster IP"      --dport 8090 -j KUBE-SVC-2VPXTAGMVMM46THU
-A KUBE-SERVICES -d 127.0.0.1/32      -p tcp "svc-demo/t-lb loadbalancer IP" --dport 8090 -j KUBE-EXT-2VPXTAGMVMM46THU
```

So, for a Service port with endpoints:

```
entries = Service ports × (1 ClusterIP + number of external IPs + number of LB IPs)
```

| Service | Ports | Addresses | Entries |
|---|---|---|---|
| `web` | 1 | ClusterIP | 1 |
| `bravo` | 2 (80, 443) | ClusterIP | 2 |
| `kube-dns` | 3 (53/udp, 53/tcp, 9153) | ClusterIP | 3 |
| `t-extip` | 1 | ClusterIP + 1 external IP | 2 |
| `t-lb` | 1 | ClusterIP + 1 LB IP | 2 |
| `t-nodeport` | 1 | ClusterIP | 1 (its node port is in `KUBE-NODEPORTS`, not here) |

#### Every rule, per Service, per table

All four tables were dumped from the kube-proxy Pod (`iptables-save
-t <table>` for raw, mangle, nat and filter). Every rule was then grouped by
the `svc-demo` Service it belongs to. A plain `grep <service>` isn't enough,
because some rules have no comment, for example `-A KUBE-EXT-… -j KUBE-SVC-…`.
The grouping follows each Service's own chains (`KUBE-SVC-`, `KUBE-SVL-`,
`KUBE-EXT-`, `KUBE-SEP-`, `KUBE-FW-` + hash) and assigns every rule in them to
that Service.

**Where the rules live:**

| Table | Rules in the table | kube-proxy rules | Rules for `svc-demo` Services |
|---|---|---|---|
| raw | 11 | 0 | 0 |
| mangle | 11 | 0 (only the empty `KUBE-IPTABLES-HINT`, `KUBE-PROXY-CANARY` and `KUBE-KUBELET-CANARY` chains) | 0 |
| **nat** | 299 | 274 | **111** |
| filter | 557 | 18 | 0 |

The other rules in each table are Calico's (`cali-…`) and Docker's.

A Service's own rules are in **nat**, with one exception: kube-proxy writes
**filter** rules for a Service only when traffic must be refused or let in
past the firewall:

| Case | Filter rule | Example on the lab |
|---|---|---|
| no endpoints | `KUBE-SERVICES … -j REJECT` (and `KUBE-EXTERNAL-SERVICES` for node port / external / LB IPs) | `dns-demo/empty` |
| ETP or ITP `Local`, no local endpoints | `… -j DROP`, comment "has no local endpoints" | – |
| `loadBalancerSourceRanges` | `KUBE-PROXY-FIREWALL -d <LB IP> --dport <port> -j DROP` | `t-lb`, temporarily (§4.4.1) |
| ETP `Local` | `KUBE-NODEPORTS --dport <healthCheckNodePort> -j ACCEPT` | Envoy's `32335` |

None of the `svc-demo` Services is in any of those cases, so all their rules are in nat.

##### nat, per Service

Comments are trimmed, along with `-m tcp` and `-m statistic --mode random`.
Every `KUBE-SEP` chain is the same two rules: the hairpin mark, then DNAT.

**ClusterIP, 1 port, 3 Pods: `alpha`, `charlie`, `t-cluster`, `web`. 11 rules each, the same shape.**
`web`:

```
-A KUBE-SERVICES -d 10.107.59.191/32 -p tcp --dport 80 -j KUBE-SVC-IGTRU775QHYZG4VA
-A KUBE-SVC-IGTRU775QHYZG4VA ! -s 10.244.0.0/16 -d 10.107.59.191/32 -p tcp --dport 80 -j KUBE-MARK-MASQ
-A KUBE-SVC-IGTRU775QHYZG4VA --probability 0.33333333349 -j KUBE-SEP-FPB55NDEKFM2GH7L
-A KUBE-SVC-IGTRU775QHYZG4VA --probability 0.50000000000 -j KUBE-SEP-NG6AIAJCPIV5FQ6B
-A KUBE-SVC-IGTRU775QHYZG4VA                             -j KUBE-SEP-UCUFHSMNCIZQIFW7
-A KUBE-SEP-FPB55NDEKFM2GH7L -s 10.244.120.115/32 -j KUBE-MARK-MASQ
-A KUBE-SEP-FPB55NDEKFM2GH7L -p tcp -j DNAT --to-destination 10.244.120.115:8080
-A KUBE-SEP-NG6AIAJCPIV5FQ6B -s 10.244.120.71/32  -j KUBE-MARK-MASQ
-A KUBE-SEP-NG6AIAJCPIV5FQ6B -p tcp -j DNAT --to-destination 10.244.120.71:8080
-A KUBE-SEP-UCUFHSMNCIZQIFW7 -s 10.244.120.74/32  -j KUBE-MARK-MASQ
-A KUBE-SEP-UCUFHSMNCIZQIFW7 -p tcp -j DNAT --to-destination 10.244.120.74:8080
```

The others differ only in IP and hashes:

| Service | Entry | `KUBE-SVC` | `KUBE-SEP` chains |
|---|---|---|---|
| `alpha` | `-d 10.98.84.12/32 --dport 80` | `KUBE-SVC-4EE5ZY6T4VGEIYEW` | `JUNCJCXP6HUXLDQE` (.115), `XJR2UF73NMTPR6PS` (.71), `HHJCRQSPDSN4FJ5P` (.74) |
| `charlie` | `-d 10.98.136.130/32 --dport 80` | `KUBE-SVC-72C52PYTL7I523BG` | `6VB2ZEZKHZY3WUTI` (.115), `2VMWOLI2UKULBQ62` (.71), `3NFVGRJ2BVM5IJ5G` (.74) |
| `t-cluster` | `-d 10.108.167.147/32 --dport 80` | `KUBE-SVC-EPYLASWNPVCYWJLJ` | `CFFHVJ54GCLK5TON` (.115), `BSIT6VWXSH236BPN` (.71), `FUM7T4D3NVQFBGSV` (.74) |
| `web` | `-d 10.107.59.191/32 --dport 80` | `KUBE-SVC-IGTRU775QHYZG4VA` | `FPB55NDEKFM2GH7L` (.115), `NG6AIAJCPIV5FQ6B` (.71), `UCUFHSMNCIZQIFW7` (.74) |

The same three Pods get a **different** `KUBE-SEP` chain in every Service.
The hash covers the Service port and the endpoint, so nothing is shared
between Services.

**ClusterIP with 2 ports: `bravo`, 22 rules.** That's the whole set once per port:

```
-A KUBE-SERVICES -d 10.111.109.97/32 -p tcp --dport 80  -j KUBE-SVC-VZ5KWM7DHWWRYRAQ
-A KUBE-SERVICES -d 10.111.109.97/32 -p tcp --dport 443 -j KUBE-SVC-CWRDXECOLI2BS3IP
-A KUBE-SVC-VZ5KWM7DHWWRYRAQ ! -s 10.244.0.0/16 -d 10.111.109.97/32 -p tcp --dport 80 -j KUBE-MARK-MASQ
-A KUBE-SVC-VZ5KWM7DHWWRYRAQ --probability 0.33333333349 -j KUBE-SEP-72LUBGJ32DNUJ2QW    (.115)
-A KUBE-SVC-VZ5KWM7DHWWRYRAQ --probability 0.50000000000 -j KUBE-SEP-C67JJALQJ2RLBSHX    (.71)
-A KUBE-SVC-VZ5KWM7DHWWRYRAQ                             -j KUBE-SEP-LKCCMXE3PHT2RNF6    (.74)
-A KUBE-SVC-CWRDXECOLI2BS3IP ! -s 10.244.0.0/16 -d 10.111.109.97/32 -p tcp --dport 443 -j KUBE-MARK-MASQ
-A KUBE-SVC-CWRDXECOLI2BS3IP --probability 0.33333333349 -j KUBE-SEP-RWW5GILLLLGWNIPK    (.115)
-A KUBE-SVC-CWRDXECOLI2BS3IP --probability 0.50000000000 -j KUBE-SEP-NVMGK5PS2HS6HMF3    (.71)
-A KUBE-SVC-CWRDXECOLI2BS3IP                             -j KUBE-SEP-GN5YKV2OTIJ6QMOZ    (.74)
(+ 6 KUBE-SEP chains × 2 rules, all DNAT to <Pod>:8080)
```

**ClusterIP + external IP: `t-extip`, 14 rules.** The 11, plus an external IP
entry and a `KUBE-EXT` chain:

```
-A KUBE-SERVICES -d 10.111.153.85/32  -p tcp --dport 80 -j KUBE-SVC-E2CZQENNJUIIXJBF
-A KUBE-SERVICES -d 192.168.49.200/32 -p tcp --dport 80 -j KUBE-EXT-E2CZQENNJUIIXJBF    ← external IP
-A KUBE-EXT-E2CZQENNJUIIXJBF -j KUBE-MARK-MASQ
-A KUBE-EXT-E2CZQENNJUIIXJBF -j KUBE-SVC-E2CZQENNJUIIXJBF
-A KUBE-SVC-E2CZQENNJUIIXJBF ! -s 10.244.0.0/16 -d 10.111.153.85/32 -p tcp --dport 80 -j KUBE-MARK-MASQ
-A KUBE-SVC-E2CZQENNJUIIXJBF --probability 0.33333333349 -j KUBE-SEP-3JN65FCMBXXTOSDR    (.115)
-A KUBE-SVC-E2CZQENNJUIIXJBF --probability 0.50000000000 -j KUBE-SEP-2OTXORUU2KPGQVD4    (.71)
-A KUBE-SVC-E2CZQENNJUIIXJBF                             -j KUBE-SEP-JEO6UZXH7PZSOSIQ    (.74)
(+ 3 KUBE-SEP chains × 2 rules)
```

**NodePort: `t-nodeport`, 15 rules.** The 11, plus two node port entries and `KUBE-EXT`:

```
-A KUBE-SERVICES -d 10.106.133.73/32 -p tcp --dport 80 -j KUBE-SVC-6PIIFHSYVUQU3J3L
-A KUBE-NODEPORTS -d 127.0.0.0/8 -p tcp --dport 30081 -m nfacct --nfacct-name localhost_nps_accepted_pkts -j KUBE-EXT-6PIIFHSYVUQU3J3L
-A KUBE-NODEPORTS                -p tcp --dport 30081 -j KUBE-EXT-6PIIFHSYVUQU3J3L
-A KUBE-EXT-6PIIFHSYVUQU3J3L -j KUBE-MARK-MASQ
-A KUBE-EXT-6PIIFHSYVUQU3J3L -j KUBE-SVC-6PIIFHSYVUQU3J3L
-A KUBE-SVC-6PIIFHSYVUQU3J3L ! -s 10.244.0.0/16 -d 10.106.133.73/32 -p tcp --dport 80 -j KUBE-MARK-MASQ
-A KUBE-SVC-6PIIFHSYVUQU3J3L --probability 0.33333333349 -j KUBE-SEP-NQRDS4GWIQMFWW3M    (.115)
-A KUBE-SVC-6PIIFHSYVUQU3J3L --probability 0.50000000000 -j KUBE-SEP-452CWNTY6HII7QQF    (.71)
-A KUBE-SVC-6PIIFHSYVUQU3J3L                             -j KUBE-SEP-YEGJKGXYO7NSCXWW    (.74)
(+ 3 KUBE-SEP chains × 2 rules)
```

The `127.0.0.0/8` rule is the localhost node port. The same jump is counted
by the `localhost_nps_accepted_pkts` nfacct counter, so kube-proxy can report
how often node ports are reached through `127.0.0.1`.

**LoadBalancer: `t-lb`, 16 rules.** The 15, plus the LB IP entry (§4.4.1):

```
-A KUBE-SERVICES -d 10.111.233.178/32 -p tcp --dport 8090 -j KUBE-SVC-2VPXTAGMVMM46THU
-A KUBE-SERVICES -d 127.0.0.1/32      -p tcp --dport 8090 -j KUBE-EXT-2VPXTAGMVMM46THU    ← LB IP
-A KUBE-NODEPORTS -d 127.0.0.0/8 -p tcp --dport 30082 -m nfacct --nfacct-name localhost_nps_accepted_pkts -j KUBE-EXT-2VPXTAGMVMM46THU
-A KUBE-NODEPORTS                -p tcp --dport 30082 -j KUBE-EXT-2VPXTAGMVMM46THU
-A KUBE-EXT-2VPXTAGMVMM46THU -j KUBE-MARK-MASQ
-A KUBE-EXT-2VPXTAGMVMM46THU -j KUBE-SVC-2VPXTAGMVMM46THU
-A KUBE-SVC-2VPXTAGMVMM46THU ! -s 10.244.0.0/16 -d 10.111.233.178/32 -p tcp --dport 8090 -j KUBE-MARK-MASQ
-A KUBE-SVC-2VPXTAGMVMM46THU --probability 0.33333333349 -j KUBE-SEP-N2ZSYKUSTVSOTSP5    (.115)
-A KUBE-SVC-2VPXTAGMVMM46THU --probability 0.50000000000 -j KUBE-SEP-HDHRF623EEAFUNHU    (.71)
-A KUBE-SVC-2VPXTAGMVMM46THU                             -j KUBE-SEP-IIZAP5J6TEBVAKXL    (.74)
(+ 3 KUBE-SEP chains × 2 rules)
```

**`t-headless` and `t-extname`: 0 rules in any table.** One has no ClusterIP
and the other is only a DNS alias.

##### Shared rules, written once and used by every Service

**nat:**

```
-A PREROUTING  -m comment --comment "kubernetes service portals"  -j KUBE-SERVICES      (packets arriving)
-A OUTPUT      -m comment --comment "kubernetes service portals"  -j KUBE-SERVICES      (packets from the node itself)
-A POSTROUTING -m comment --comment "kubernetes postrouting rules" -j KUBE-POSTROUTING
-A KUBE-SERVICES -m comment --comment "kubernetes service nodeports; NOTE: this must be the last rule in this chain" -m addrtype --dst-type LOCAL -j KUBE-NODEPORTS
-A KUBE-MARK-MASQ -j MARK --set-xmark 0x4000/0x4000
-A KUBE-POSTROUTING -m mark ! --mark 0x4000/0x4000 -j RETURN
-A KUBE-POSTROUTING -j MARK --set-xmark 0x4000/0x0
-A KUBE-POSTROUTING -m comment --comment "kubernetes service traffic requiring SNAT" -j MASQUERADE --random-fully
```

**filter** (all 18 kube-proxy rules on the node):

```
-A INPUT   -m conntrack --ctstate NEW -m comment --comment "kubernetes load balancer firewall" -j KUBE-PROXY-FIREWALL
-A INPUT   -m comment --comment "kubernetes health check service ports" -j KUBE-NODEPORTS
-A INPUT   -m conntrack --ctstate NEW -m comment --comment "kubernetes externally-visible service portals" -j KUBE-EXTERNAL-SERVICES
-A INPUT   -j KUBE-FIREWALL
-A FORWARD -m conntrack --ctstate NEW -m comment --comment "kubernetes load balancer firewall" -j KUBE-PROXY-FIREWALL
-A FORWARD -m comment --comment "kubernetes forwarding rules" -j KUBE-FORWARD
-A FORWARD -m conntrack --ctstate NEW -m comment --comment "kubernetes service portals" -j KUBE-SERVICES
-A FORWARD -m conntrack --ctstate NEW -m comment --comment "kubernetes externally-visible service portals" -j KUBE-EXTERNAL-SERVICES
-A OUTPUT  -m conntrack --ctstate NEW -m comment --comment "kubernetes load balancer firewall" -j KUBE-PROXY-FIREWALL
-A OUTPUT  -m conntrack --ctstate NEW -m comment --comment "kubernetes service portals" -j KUBE-SERVICES
-A OUTPUT  -j KUBE-FIREWALL
-A KUBE-FIREWALL ! -s 127.0.0.0/8 -d 127.0.0.0/8 -m comment --comment "block incoming localnet connections" -m conntrack ! --ctstate RELATED,ESTABLISHED,DNAT -j DROP
-A KUBE-FORWARD -m conntrack --ctstate INVALID -m nfacct --nfacct-name ct_state_invalid_dropped_pkts -j DROP
-A KUBE-FORWARD -m comment --comment "kubernetes forwarding rules" -m mark --mark 0x4000/0x4000 -j ACCEPT
-A KUBE-FORWARD -m comment --comment "kubernetes forwarding conntrack rule" -m conntrack --ctstate RELATED,ESTABLISHED -j ACCEPT
-A KUBE-NODEPORTS -p tcp -m comment --comment "envoy-gateway-system/envoy-infra-public-8d55c10f:https-443 health check node port" -m tcp --dport 32335 -j ACCEPT
-A KUBE-NODEPORTS -p tcp -m comment --comment "envoy-gateway-system/envoy-infra-public-8d55c10f:http-80 health check node port" -m tcp --dport 32335 -j ACCEPT
-A KUBE-SERVICES -d 10.109.169.18/32 -p tcp -m comment --comment "dns-demo/empty has no endpoints" -m tcp --dport 80 -j REJECT --reject-with icmp-port-unreachable
```

The **filter** table's `KUBE-SERVICES` and `KUBE-NODEPORTS` are different
chains from the **nat** ones with the same names. Each table has its own set
of chains.

| filter chain | Hooked from | Holds |
|---|---|---|
| `KUBE-SERVICES` | FORWARD, OUTPUT (new connections) | `REJECT` for ClusterIPs with no endpoints |
| `KUBE-EXTERNAL-SERVICES` | INPUT, FORWARD (new connections) | `REJECT`/`DROP` for node ports, external IPs and LB IPs with no (local) endpoints. Empty here |
| `KUBE-PROXY-FIREWALL` | INPUT, FORWARD, OUTPUT (new connections) | `DROP` for LB IPs from sources outside `loadBalancerSourceRanges`. Empty here |
| `KUBE-NODEPORTS` | INPUT | `ACCEPT` for health check node ports |
| `KUBE-FORWARD` | FORWARD | drop INVALID; accept marked (`0x4000`) and established traffic |
| `KUBE-FIREWALL` | INPUT, OUTPUT | drop new connections to `127.0.0.0/8` that come from elsewhere and weren't DNATed (guards `route_localnet=1`) |

##### The pattern

| What the Service has | nat rules | Made of |
|---|---|---|
| ClusterIP, 1 port, 3 Pods | 11 | 1 entry + 4 `KUBE-SVC` (1 masq + 3 picks) + 3 × 2 `KUBE-SEP` |
| each extra port | +11 | the whole set again (`bravo`: 22) |
| each extra Pod | +3 per port | 1 more pick in `KUBE-SVC` + a 2-rule `KUBE-SEP` chain |
| an external IP | +3 | 1 entry + 2 `KUBE-EXT` |
| NodePort | +4 | 2 `KUBE-NODEPORTS` + 2 `KUBE-EXT` |
| LoadBalancer (VIP mode, 1 LB IP) | +5 over ClusterIP | NodePort's 4 + the LB IP entry |
| ETP `Local` | `KUBE-EXT` grows to 4 rules, + a `KUBE-SVL` chain | §4.4.1 |
| headless, ExternalName | 0 | – |

Totals for `svc-demo`: 11 + 22 + 11 + 11 + 14 + 16 + 15 + 11 = **111** nat
rules for 10 Services, of which 2 have none.

§4.1 lists the rules. This section follows single requests through them,
each checked with its conntrack entry (lab). The three Services:

```
t-cluster    ClusterIP      10.108.167.147   80               → targetPort 8080, selector app=web (3 Pods)
t-nodeport   NodePort       10.106.133.73    80:30081         → 8080, externalTrafficPolicy: Cluster
t-lb         LoadBalancer   10.111.233.178   8090:30082       → 8080, LB IP 127.0.0.1 (minikube tunnel), ETP Cluster
```

**Read the rules with kube-proxy's own `iptables`.** The node's `iptables`
v1.8.7 misprints rules written by kube-proxy's v1.8.9: it drops the
`--dport` and `-m mark` matches. `minikube ssh -- sudo iptables-save` therefore
shows `KUBE-POSTROUTING -j RETURN` with no condition, and node-port rules with
no port. Use `kubectl -n kube-system exec <kube-proxy pod> -- iptables-save -t nat`.

**The rules every Service shares**, run in this order for every packet:

```
 nat PREROUTING (packets arriving: from outside or from a Pod's veth)  ─┐
 nat OUTPUT     (packets created on the node itself)                   ─┴─► KUBE-SERVICES
                                                                                 │
   1. -d <ClusterIP>/32  --dport <port>  "cluster IP"       → KUBE-SVC-…    (one per Service port)
   2. -d <LB IP>/32      --dport <port>  "loadbalancer IP"  → KUBE-EXT-…    (LoadBalancer only)
   3. LAST: -m addrtype --dst-type LOCAL                    → KUBE-NODEPORTS
              (dst is one of the node's own addresses)         └─ --dport <nodePort> → KUBE-EXT-…
                                                                                 │
   KUBE-EXT-… (ETP Cluster):  -j KUBE-MARK-MASQ   (always),  then  -j KUBE-SVC-…
   KUBE-SVC-… :  ! -s 10.244.0.0/16 -d <ClusterIP> → KUBE-MARK-MASQ   (callers outside the Pod CIDR)
                 random 1/3 → KUBE-SEP-A,  random 1/2 → KUBE-SEP-B,  else → KUBE-SEP-C
   KUBE-SEP-… :  -s <its own Pod IP> → KUBE-MARK-MASQ   (hairpin: a Pod reaching itself)
                 -j DNAT --to-destination <PodIP>:8080
                                                                                 │
 nat POSTROUTING ─► KUBE-POSTROUTING
   -m mark ! --mark 0x4000/0x4000 -j RETURN          unmarked: leave the source alone
   -j MARK --set-xmark 0x4000/0x0                    clear the mark
   -j MASQUERADE --random-fully                      marked: SNAT to the outgoing interface's address
```

`KUBE-MARK-MASQ` is just `-j MARK --set-xmark 0x4000/0x4000`. It only flags the
packet; the SNAT itself happens at the end, in `KUBE-POSTROUTING`. On this node
the address used for SNAT toward Pods is `10.244.120.64`, the node's `tunl0`
address.

How to read the conntrack lines below: the first tuple is what the client
sent, the second is what the reply looks like (§3.5). A reply source that
differs from the original destination means **DNAT**. A reply destination that
differs from the original source means **SNAT**.

#### ClusterIP Service: `t-cluster`

Its only rules: one entry in `KUBE-SERVICES`, the `KUBE-SVC` chain, three `KUBE-SEP` chains.

```
-A KUBE-SERVICES -d 10.108.167.147/32 -p tcp "svc-demo/t-cluster cluster IP" --dport 80 -j KUBE-SVC-EPYLASWNPVCYWJLJ
-A KUBE-SVC-EPYL… ! -s 10.244.0.0/16 -d 10.108.167.147/32 -p tcp -j KUBE-MARK-MASQ
-A KUBE-SVC-EPYL… "-> 10.244.120.115:8080" --probability 0.33333333349 -j KUBE-SEP-CFFH…
-A KUBE-SVC-EPYL… "-> 10.244.120.71:8080"  --probability 0.50000000000 -j KUBE-SEP-BSIT…
-A KUBE-SVC-EPYL… "-> 10.244.120.74:8080"                              -j KUBE-SEP-FUM7…
-A KUBE-SEP-BSIT… -s 10.244.120.71/32 -j KUBE-MARK-MASQ
-A KUBE-SEP-BSIT… -p tcp -j DNAT --to-destination 10.244.120.71:8080
```

**Example 1. Pod → ClusterIP:** `web-…-4shjp` (`10.244.120.115`) runs `agnhost connect 10.108.167.147:80`.

```
PREROUTING → KUBE-SERVICES: -d 10.108.167.147 --dport 80 ✓ → KUBE-SVC-EPYL
  ! -s 10.244.0.0/16?  src is 10.244.120.115, in the Pod CIDR → no mark
  random → KUBE-SEP-FUM7 (.74): not its own IP → no mark; DNAT → 10.244.120.74:8080
POSTROUTING: unmarked → RETURN (source kept)

conntrack:  src=10.244.120.115 dst=10.108.167.147 dport=80
            src=10.244.120.74  dst=10.244.120.115 sport=8080
            DNAT yes (reply from .74); SNAT no (reply goes to the real caller)
```

**Example 2. Node → ClusterIP:** `curl http://10.108.167.147/hostname` from the node → `web-…-bcjj6`.

```
OUTPUT → KUBE-SERVICES: ClusterIP entry ✓ → KUBE-SVC-EPYL
  ! -s 10.244.0.0/16?  src is 192.168.49.2 → MARK 0x4000
  random → KUBE-SEP-BSIT → DNAT → 10.244.120.71:8080
POSTROUTING: marked → MASQUERADE to 10.244.120.64 (tunl0)

conntrack:  src=192.168.49.2 dst=10.108.167.147 dport=80
            src=10.244.120.71 dst=10.244.120.64 sport=8080
            DNAT yes; SNAT yes (reply goes to tunl0's address, not 192.168.49.2)
```

The source is rewritten because the Pod has to send its reply back through
this node to be un-NATed, and an address outside the Pod network might be
routed elsewhere.

#### NodePort Service: `t-nodeport`

It gets everything a ClusterIP Service has, plus a node-port entry and a `KUBE-EXT` chain:

```
-A KUBE-NODEPORTS -p tcp "svc-demo/t-nodeport" -m tcp --dport 30081 -j KUBE-EXT-6PIIFHSYVUQU3J3L
-A KUBE-NODEPORTS -d 127.0.0.0/8 -p tcp "svc-demo/t-nodeport" --dport 30081 -m nfacct ... -j KUBE-EXT-6PII…
-A KUBE-EXT-6PII… "masquerade traffic for svc-demo/t-nodeport external destinations" -j KUBE-MARK-MASQ
-A KUBE-EXT-6PII… -j KUBE-SVC-6PIIFHSYVUQU3J3L
-A KUBE-SERVICES -d 10.106.133.73/32 -p tcp "svc-demo/t-nodeport cluster IP" --dport 80 -j KUBE-SVC-6PII…
(KUBE-SVC-6PII… and its three KUBE-SEPs: same shape as t-cluster)
```

**Example 3. Outside → node port:** a container on the Docker network, `192.168.49.50`, runs `curl http://192.168.49.2:30081/hostname` → `web-…-bcjj6`.

```
PREROUTING → KUBE-SERVICES: no ClusterIP or LB IP matches 192.168.49.2
  last rule: dst-type LOCAL ✓ (192.168.49.2 is the node) → KUBE-NODEPORTS
  --dport 30081 ✓ → KUBE-EXT-6PII: MARK 0x4000 (always, with ETP Cluster) → KUBE-SVC-6PII
  random → KUBE-SEP-452C → DNAT → 10.244.120.71:8080
POSTROUTING: marked → MASQUERADE to 10.244.120.64

conntrack:  src=192.168.49.50 dst=192.168.49.2 dport=30081
            src=10.244.120.71 dst=10.244.120.64 sport=8080
            DNAT yes; SNAT yes, so the Pod sees the node, not 192.168.49.50 (§4.3)
```

**Example 4. Pod → node port, landing on itself:** `web-…-4shjp` (`10.244.120.115`) runs `agnhost connect 192.168.49.2:30081`.

```
KUBE-SERVICES → dst-type LOCAL → KUBE-NODEPORTS --dport 30081 → KUBE-EXT: MARK
  random → KUBE-SEP-NQRD (.115, the caller itself) → also -s 10.244.120.115 → MARK (hairpin)
  DNAT → 10.244.120.115:8080
POSTROUTING: marked → MASQUERADE

conntrack:  src=10.244.120.115 dst=192.168.49.2   dport=30081
            src=10.244.120.115 dst=10.244.120.64  sport=8080
            the Pod connected to itself; without SNAT it would see a packet from its own IP and the reply would never leave it
```

Unlike example 1, a Pod calling a **node port** is SNATed even though it's
inside the Pod CIDR, because `KUBE-EXT` marks unconditionally under ETP `Cluster`.

#### LoadBalancer Service: `t-lb`

It gets everything a NodePort Service has, plus one `KUBE-SERVICES` entry per LB IP:

```
-A KUBE-SERVICES -d 10.111.233.178/32 -p tcp "svc-demo/t-lb cluster IP"      --dport 8090 -j KUBE-SVC-2VPXTAGMVMM46THU
-A KUBE-SERVICES -d 127.0.0.1/32      -p tcp "svc-demo/t-lb loadbalancer IP" --dport 8090 -j KUBE-EXT-2VPXTAGMVMM46THU
-A KUBE-NODEPORTS -p tcp "svc-demo/t-lb" --dport 30082 -j KUBE-EXT-2VPX…
-A KUBE-EXT-2VPX… "masquerade traffic for svc-demo/t-lb external destinations" -j KUBE-MARK-MASQ
-A KUBE-EXT-2VPX… -j KUBE-SVC-2VPX…
```

A cloud LB **doesn't add rules for its own forwarding**. It sends to
`node:30082`, which is the node-port entry. The "loadbalancer IP" entry is for
packets that reach the node still addressed to the LB IP: the in-cluster
shortcut, or pass-through LBs (§2.3).

**Example 5. Cloud-LB path, simulated:** the outside container runs `curl http://192.168.49.2:30082/hostname`, as a Hetzner or Scaleway LB would → `web-…-4shjp`.

```
KUBE-SERVICES → dst-type LOCAL → KUBE-NODEPORTS --dport 30082 → KUBE-EXT-2VPX: MARK → KUBE-SVC-2VPX
  → KUBE-SEP (.115) → DNAT → 10.244.120.115:8080;  POSTROUTING: MASQUERADE

conntrack:  src=192.168.49.50  dst=192.168.49.2  dport=30082
            src=10.244.120.115 dst=10.244.120.64 sport=8080
            exactly like the NodePort example: the LB IP plays no part
```

**Example 6. To the LB IP itself:** `curl http://127.0.0.1:8090/hostname` from the node → `web-…-4shjp`.

```
OUTPUT → KUBE-SERVICES: -d 127.0.0.1 --dport 8090 "loadbalancer IP" ✓ → KUBE-EXT-2VPX: MARK
  → KUBE-SVC-2VPX → KUBE-SEP (.115) → DNAT → 10.244.120.115:8080;  POSTROUTING: MASQUERADE

conntrack:  src=127.0.0.1 dst=127.0.0.1 dport=8090
            src=10.244.120.115 dst=10.244.120.64 sport=8080
            the packet never went to an LB: the node rewrote it on the spot (the LB-IP shortcut, §2.3)
```

On a cloud the LB IP is a real public address, so the same rule catches a
Pod or node calling `http://<LB IP>`: the request never leaves the cluster.
This lab's `127.0.0.1` comes from `minikube tunnel`. From a Pod, `127.0.0.1`
is the Pod's own loopback and never reaches these rules.

#### The same LoadBalancer with `externalTrafficPolicy: Local`

The Envoy Gateway Service `envoy-infra-public-8d55c10f` (`443:32053` → `10443`,
`healthCheckNodePort: 32335`) has ETP `Local`. Its `KUBE-EXT` sorts callers
instead of marking all of them, and adds a `KUBE-SVL` chain:

```
-A KUBE-EXT-HJ7C… -s 10.244.0.0/16 "pod traffic for … external destinations"  -j KUBE-SVC-HJ7C…   Pods: whole cluster, no mark
-A KUBE-EXT-HJ7C… -m addrtype --src-type LOCAL "masquerade LOCAL traffic …"   -j KUBE-MARK-MASQ  the node itself: mark
-A KUBE-EXT-HJ7C… -m addrtype --src-type LOCAL "route LOCAL traffic …"        -j KUBE-SVC-HJ7C…
-A KUBE-EXT-HJ7C…                                                              -j KUBE-SVL-HJ7C…   everyone else: local Pods only
-A KUBE-SVL-HJ7C… "-> 10.244.120.112:10443" -j KUBE-SEP-JUW6…
```

**Example 7. Outside → node port with ETP `Local`:** the container runs `curl -k https://money.example.com:32053/` (resolved to `192.168.49.2`) → HTTP 200.

```
KUBE-NODEPORTS --dport 32053 → KUBE-EXT-HJ7C:
  -s 10.244.0.0/16? no.  src-type LOCAL? no (192.168.49.50 is another host)
  → KUBE-SVL-HJ7C → KUBE-SEP-JUW6 → DNAT → 10.244.120.112:10443
POSTROUTING: unmarked → RETURN (source kept)

conntrack:  src=192.168.49.50  dst=192.168.49.2  dport=32053
            src=10.244.120.112 dst=192.168.49.50 sport=10443
            DNAT yes; SNAT no: Envoy sees the real client 192.168.49.50
```

#### Summary of the seven

| # | Client → destination | Entry rule | Marked by | SNAT? | Pod sees caller as |
|---|---|---|---|---|---|
| 1 | Pod → ClusterIP | `KUBE-SERVICES "cluster IP"` | – | no | the Pod's IP |
| 2 | node → ClusterIP | `KUBE-SERVICES "cluster IP"` | `KUBE-SVC` (`! -s 10.244.0.0/16`) | yes | `10.244.120.64` |
| 3 | outside → node:30081 | `KUBE-NODEPORTS --dport 30081` | `KUBE-EXT` (always) | yes | `10.244.120.64` |
| 4 | Pod → node:30081, lands on itself | `KUBE-NODEPORTS` | `KUBE-EXT` and `KUBE-SEP` (hairpin) | yes | `10.244.120.64` |
| 5 | outside (as a cloud LB) → node:30082 | `KUBE-NODEPORTS --dport 30082` | `KUBE-EXT` | yes | `10.244.120.64` |
| 6 | node → LB IP:8090 | `KUBE-SERVICES "loadbalancer IP"` | `KUBE-EXT` | yes | `10.244.120.64` |
| 7 | outside → node:32053, ETP `Local` | `KUBE-NODEPORTS --dport 32053` | – (falls to `KUBE-SVL`) | **no** | `192.168.49.50` (the real client) |

Every row ends in the same kind of DNAT to `PodIP:targetPort`. The Service
type only adds **entry points**: `cluster IP` for all types, `KUBE-NODEPORTS`
from NodePort up, `loadbalancer IP` for LoadBalancer. The traffic policy
decides whether the caller's address is rewritten.

### 4.2 Headless: DNS hands back the pods

```yaml
spec: {clusterIP: None, selector: {app: web}, ports: [{port: 8080}]}
```

```
$ dig +short web.svc-demo.svc.cluster.local           $ dig +short web-headless.svc-demo.svc.cluster.local
10.107.59.191                                          10.244.120.87
                                                       10.244.120.85
                                                       10.244.120.88
iptables rules mentioning web-headless: 0
```

Clients connect straight to pods. Use this when the client does its own load
balancing (gRPC) or needs to address specific pods (StatefulSets).

### 4.3 NodePort and `externalTrafficPolicy`: who the pod thinks called

I called `192.168.49.2:30080` from a container outside the cluster, on the same
Docker network as the node. The caller's real IP is `192.168.49.5`.
The `/clientip` endpoint shows what the pod sees:

```
externalTrafficPolicy: Cluster (default)  ->  10.244.120.64:45893   # the node's own address
externalTrafficPolicy: Local              ->  192.168.49.5:57346    # the real caller
```

- **Cluster** may forward to a pod on another node, so it rewrites the source
  address (SNAT) to make replies come back through the same node. The caller's
  IP is lost.
- **Local** only sends to pods on the node that received the traffic, so no SNAT
  is needed and the client IP survives. The cost is that a node with no local
  pod drops the traffic, and load follows how pods are spread across nodes.

### 4.4 LoadBalancer: needs something to provision it

Without a cloud controller, the Service waits. It still gets its ClusterIP and
NodePort straight away:

```
web-lb   LoadBalancer   10.100.138.59   <pending>     80:30650/TCP
```

With `minikube tunnel` running to stand in for a cloud provider:

```
web-lb   LoadBalancer   10.100.138.59   127.0.0.1     8080:30650/TCP

30 requests from the host:   10 web-...-4gdww   12 web-...-p8nbc   8 web-...-x2pfs
```

The port was moved from 80 to 8080 because ports below 1024 make the tunnel ask
for sudo. With the Docker driver on macOS, the external IP is `127.0.0.1`. In a
real cloud, each LoadBalancer Service is usually its own billed load balancer.

### 4.4.1 What kube-proxy writes for a LoadBalancer, from its code

This section follows a LoadBalancer Service through kube-proxy's source and
checks each step on the lab. The source is **kubernetes `v1.34.0`**, the lab's
kube-proxy version (`registry.k8s.io/kube-proxy:v1.34.0`). The files are
`pkg/proxy/serviceport.go`, `pkg/proxy/util/utils.go` and
`pkg/proxy/iptables/proxier.go`.

For context, the entry rules of all the `svc-demo` test Services side by side:

```
# KUBE-SERVICES: one entry per ClusterIP, external IP and load balancer IP, each with its port
-A KUBE-SERVICES -d 10.108.167.147/32 -p tcp "svc-demo/t-cluster cluster IP"       --dport 80   -j KUBE-SVC-EPYL...
-A KUBE-SERVICES -d 10.106.133.73/32  -p tcp "svc-demo/t-nodeport cluster IP"      --dport 80   -j KUBE-SVC-6PII...
-A KUBE-SERVICES -d 10.111.233.178/32 -p tcp "svc-demo/t-lb cluster IP"            --dport 8090 -j KUBE-SVC-2VPX...
-A KUBE-SERVICES -d 127.0.0.1/32      -p tcp "svc-demo/t-lb loadbalancer IP"       --dport 8090 -j KUBE-EXT-2VPX...
-A KUBE-SERVICES -d 10.111.153.85/32  -p tcp "svc-demo/t-extip cluster IP"         --dport 80   -j KUBE-SVC-E2CZ...
-A KUBE-SERVICES -d 192.168.49.200/32 -p tcp "svc-demo/t-extip external IP"        --dport 80   -j KUBE-EXT-E2CZ...

# KUBE-NODEPORTS: one entry per node port (the second rule per Service handles localhost)
-A KUBE-NODEPORTS             -p tcp "svc-demo/t-nodeport" --dport 30081 -j KUBE-EXT-6PII...
-A KUBE-NODEPORTS -d 127.0.0.0/8 -p tcp "svc-demo/t-nodeport" --dport 30081 -m nfacct ... -j KUBE-EXT-6PII...
-A KUBE-NODEPORTS             -p tcp "svc-demo/t-lb"       --dport 30082 -j KUBE-EXT-2VPX...
-A KUBE-NODEPORTS -d 127.0.0.0/8 -p tcp "svc-demo/t-lb"       --dport 30082 -m nfacct ... -j KUBE-EXT-2VPX...

# t-headless, t-extname: no rules in any table
```

The LoadBalancer adds exactly one kind of rule on top of a NodePort Service:
the `"loadbalancer IP"` entry. Everything below explains where it comes from,
where it goes, and what changes it.

#### Step 1: which LB IPs kube-proxy takes from the Service

kube-proxy doesn't create load balancers. It reads the addresses that a cloud
controller (or `minikube tunnel`) wrote into the Service's **status**, and keeps only some of them.
From `pkg/proxy/serviceport.go`, `newBaseServiceInfo()`:

```go
	// Filter Load Balancer Ingress IPs to correct IP family. While proxying load
	// balancers might choose to proxy connections from an LB IP of one family to a
	// service IP of another family, that's irrelevant to kube-proxy, which only
	// creates rules for VIP-style load balancers.
	for _, ing := range service.Status.LoadBalancer.Ingress {
		if ing.IP == "" || !proxyutil.IsVIPMode(ing) {
			continue
		}

		ip := netutils.ParseIPSloppy(ing.IP) // (already verified as an IP-address)
		if ingFamily := proxyutil.GetIPFamilyFromIP(ip); ingFamily == ipFamily {
			info.loadBalancerVIPs = append(info.loadBalancerVIPs, ip)
		}
	}
```

And from `pkg/proxy/util/utils.go`:

```go
func IsVIPMode(ing v1.LoadBalancerIngress) bool {
	if !utilfeature.DefaultFeatureGate.Enabled(features.LoadBalancerIPMode) {
		return true // backwards compat
	}
	if ing.IPMode == nil {
		return true
	}
	return *ing.IPMode == v1.LoadBalancerIPModeVIP
}
```

An ingress entry is skipped when:

| `status.loadBalancer.ingress[]` | Used? | Why |
|---|---|---|
| `ip` set, `ipMode: VIP` or unset | **yes** | the LB forwards packets still addressed to the LB IP, so the node must recognise that address |
| `ip` set, `ipMode: Proxy` | no | the LB terminates the connection and opens a new one to a node port, so the LB IP never reaches the node |
| only `hostname` (e.g. AWS ELB) | no | `ing.IP == ""`: there's no address to match on |
| an IP of the other family | no | each kube-proxy instance handles one IP family |

The lab's `t-lb` (`kubectl get svc t-lb -o jsonpath='{.status.loadBalancer}'`):

```
{"ingress":[{"ip":"127.0.0.1","ipMode":"VIP"}]}
```

`minikube tunnel` set that status earlier and isn't running now. kube-proxy
only reads the status, so the rule stays.

The same file also cleans the source ranges: a zero-mask CIDR (`0.0.0.0/0`) is
dropped, because it means "allow everyone", the same as no list at all:

```go
	// zero-masked cidr means "allow any", which same as the empty loadBalancerSourceRanges.
	if slices.ContainsFunc(cidrs, proxyutil.IsZeroCIDR) {
		cidrs = []*net.IPNet{}
	}
	info.loadBalancerSourceRanges = cidrs
```

#### Step 2: which chain the LB IP entry jumps to

From `pkg/proxy/iptables/proxier.go`, `syncProxyRules()`, per Service port:

```go
		externalTrafficChain := svcInfo.externalChainName // eventually jumps to externalPolicyChain

		// usesExternalTrafficChain is based on hasEndpoints, not hasExternalEndpoints,
		// because we need the local-traffic-short-circuiting rules even when there
		// are no externally-usable endpoints.
		usesExternalTrafficChain := hasEndpoints && svcInfo.ExternallyAccessible()

		// Traffic to LoadBalancer IPs can go directly to externalTrafficChain
		// unless LoadBalancerSourceRanges is in use in which case we will
		// create a firewall chain.
		loadBalancerTrafficChain := externalTrafficChain
		fwChain := svcInfo.firewallChainName
		usesFWChain := hasEndpoints && len(svcInfo.LoadBalancerVIPs()) > 0 && len(svcInfo.LoadBalancerSourceRanges()) > 0
		if usesFWChain {
			loadBalancerTrafficChain = fwChain
		}
```

| Service has | LB IP entry jumps to |
|---|---|
| no `loadBalancerSourceRanges` | `KUBE-EXT-<hash>`, the same chain as the node port and external IPs |
| `loadBalancerSourceRanges` | `KUBE-FW-<hash>`, a firewall chain that lets allowed sources on to `KUBE-EXT` |

#### Step 3: the entry rule itself

```go
		// Capture load-balancer ingress.
		for _, lbip := range svcInfo.LoadBalancerVIPs() {
			if hasEndpoints {
				natRules.Write(
					"-A", string(kubeServicesChain),
					"-m", "comment", "--comment", fmt.Sprintf(`"%s loadbalancer IP"`, svcPortNameString),
					"-m", protocol, "-p", protocol,
					"-d", lbip.String(),
					"--dport", strconv.Itoa(svcInfo.Port()),
					"-j", string(loadBalancerTrafficChain))

			}
			if usesFWChain {
				filterRules.Write(
					"-A", string(kubeProxyFirewallChain),
					"-m", "comment", "--comment", fmt.Sprintf(`"%s traffic not accepted by %s"`, svcPortNameString, svcInfo.firewallChainName),
					"-m", protocol, "-p", protocol,
					"-d", lbip.String(),
					"--dport", strconv.Itoa(svcInfo.Port()),
					"-j", "DROP")
			}
		}
```

- **One rule per LB IP, per Service port.** The match is `-d <LB IP> --dport <port>`,
  using the Service `port` (`8090`), not the node port.
- **Only if the Service has endpoints.** Otherwise there's no nat rule, and a
  filter-table rule rejects or drops the traffic instead. That's the
  `!hasExternalEndpoints` block right after: `REJECT` with no endpoints at all,
  `DROP` with ETP `Local` and no local endpoints.
- **With source ranges, a second rule in the filter table** drops whatever the
  nat-table firewall chain didn't let through (step 5).

The lab's `t-lb` (`port 8090`, `targetPort 8080`, `nodePort 30082`, ETP and
ITP `Cluster`, selector `app: web`), every rule that mentions it:

```
-A KUBE-SERVICES -d 10.111.233.178/32 -p tcp -m comment --comment "svc-demo/t-lb cluster IP" -m tcp --dport 8090 -j KUBE-SVC-2VPXTAGMVMM46THU
-A KUBE-SERVICES -d 127.0.0.1/32 -p tcp -m comment --comment "svc-demo/t-lb loadbalancer IP" -m tcp --dport 8090 -j KUBE-EXT-2VPXTAGMVMM46THU
-A KUBE-NODEPORTS -d 127.0.0.0/8 -p tcp -m comment --comment "svc-demo/t-lb" -m tcp --dport 30082 -m nfacct --nfacct-name  localhost_nps_accepted_pkts -j KUBE-EXT-2VPXTAGMVMM46THU
-A KUBE-NODEPORTS -p tcp -m comment --comment "svc-demo/t-lb" -m tcp --dport 30082 -j KUBE-EXT-2VPXTAGMVMM46THU
-A KUBE-EXT-2VPXTAGMVMM46THU -m comment --comment "masquerade traffic for svc-demo/t-lb external destinations" -j KUBE-MARK-MASQ
-A KUBE-EXT-2VPXTAGMVMM46THU -j KUBE-SVC-2VPXTAGMVMM46THU
-A KUBE-SVC-2VPXTAGMVMM46THU ! -s 10.244.0.0/16 -d 10.111.233.178/32 -p tcp -m comment --comment "svc-demo/t-lb cluster IP" -m tcp --dport 8090 -j KUBE-MARK-MASQ
-A KUBE-SVC-2VPXTAGMVMM46THU -m comment --comment "svc-demo/t-lb -> 10.244.120.115:8080" -m statistic --mode random --probability 0.33333333349 -j KUBE-SEP-N2ZSYKUSTVSOTSP5
-A KUBE-SVC-2VPXTAGMVMM46THU -m comment --comment "svc-demo/t-lb -> 10.244.120.71:8080" -m statistic --mode random --probability 0.50000000000 -j KUBE-SEP-HDHRF623EEAFUNHU
-A KUBE-SVC-2VPXTAGMVMM46THU -m comment --comment "svc-demo/t-lb -> 10.244.120.74:8080" -j KUBE-SEP-IIZAP5J6TEBVAKXL
(+ 3 KUBE-SEP chains, one per Pod: hairpin MARK, then DNAT to <Pod>:8080)
```

Note that `-A KUBE-EXT-2VPX… -j KUBE-SVC-2VPX…` has **no comment**, so a
`grep t-lb` misses it. Grep the chain name instead.

Three entry points converge on two chains:

```
 127.0.0.1:8090          (LB IP)      ─┐
 <any node addr>:30082   (node port)  ─┼─► KUBE-EXT-2VPX ─► mark for SNAT ─► KUBE-SVC-2VPX ─► pick ─► KUBE-SEP ─► DNAT
 127.x.x.x:30082         (localhost)  ─┘                                         ▲
 10.111.233.178:8090     (ClusterIP)  ───────────────────────────────────────────┘ (marked only if the source isn't a Pod)
```

The LB IP and node port go through `KUBE-EXT` ("external" traffic, subject to
`externalTrafficPolicy`). The ClusterIP goes straight to `KUBE-SVC`
("internal" traffic, subject to `internalTrafficPolicy`).

#### Step 4: what `KUBE-EXT` does depends on `externalTrafficPolicy`

```go
		if usesExternalTrafficChain {
			natChains.Write(utiliptables.MakeChainLine(externalTrafficChain))
			activeNATChains.Insert(externalTrafficChain)

			if !svcInfo.ExternalPolicyLocal() {
				// If we are using non-local endpoints we need to masquerade,
				// in case we cross nodes.
				natRules.Write(
					"-A", string(externalTrafficChain),
					"-m", "comment", "--comment", fmt.Sprintf(`"masquerade traffic for %s external destinations"`, svcPortNameString),
					"-j", string(kubeMarkMasqChain))
			} else {
				// If we are only using same-node endpoints, we can retain the
				// source IP in most cases.

				if proxier.localDetector.IsImplemented() {
					// Treat all locally-originated pod -> external destination
					// traffic as a special-case.  It is subject to neither
					// form of traffic policy, which simulates going up-and-out
					// to an external load-balancer and coming back in.
					natRules.Write(
						"-A", string(externalTrafficChain),
						"-m", "comment", "--comment", fmt.Sprintf(`"pod traffic for %s external destinations"`, svcPortNameString),
						proxier.localDetector.IfLocal(),
						"-j", string(clusterPolicyChain))
				}

				// Locally originated traffic (not a pod, but the host node)
				// still needs masquerade because the LBIP itself is a local
				// address, so that will be the chosen source IP.
				natRules.Write(
					"-A", string(externalTrafficChain),
					"-m", "comment", "--comment", fmt.Sprintf(`"masquerade LOCAL traffic for %s external destinations"`, svcPortNameString),
					"-m", "addrtype", "--src-type", "LOCAL",
					"-j", string(kubeMarkMasqChain))

				// Redirect all src-type=LOCAL -> external destination to the
				// policy=cluster chain. This allows traffic originating
				// from the host to be redirected to the service correctly.
				natRules.Write(
					"-A", string(externalTrafficChain),
					"-m", "comment", "--comment", fmt.Sprintf(`"route LOCAL traffic for %s external destinations"`, svcPortNameString),
					"-m", "addrtype", "--src-type", "LOCAL",
					"-j", string(clusterPolicyChain))
			}

			// Anything else falls thru to the appropriate policy chain.
			if hasExternalEndpoints {
				natRules.Write(
					"-A", string(externalTrafficChain),
					"-j", string(externalPolicyChain))
			}
		}
```

**ETP `Cluster` (`t-lb`):** one rule marks everything for SNAT, then everything goes to
`KUBE-SVC` (all endpoints, cluster-wide). That's the two `KUBE-EXT-2VPX` lines above.

**ETP `Local`:** the Envoy Gateway's LoadBalancer Service
`envoy-infra-public-8d55c10f` (`http-80: 80 → 31845`, `https-443: 443 →
32053`, `healthCheckNodePort: 32335`, status `{"ip":"127.0.0.1","ipMode":"VIP"}`).
Its `http-80` port:

```
-A KUBE-SERVICES -d 127.0.0.1/32 -p tcp --comment "…:http-80 loadbalancer IP" --dport 80 -j KUBE-EXT-Z2K6S4QBRLXAOINF
-A KUBE-EXT-Z2K6S4QBRLXAOINF -s 10.244.0.0/16 --comment "pod traffic for … external destinations"          -j KUBE-SVC-Z2K6S4QBRLXAOINF
-A KUBE-EXT-Z2K6S4QBRLXAOINF -m addrtype --src-type LOCAL --comment "masquerade LOCAL traffic for …"     -j KUBE-MARK-MASQ
-A KUBE-EXT-Z2K6S4QBRLXAOINF -m addrtype --src-type LOCAL --comment "route LOCAL traffic for …"          -j KUBE-SVC-Z2K6S4QBRLXAOINF
-A KUBE-EXT-Z2K6S4QBRLXAOINF                                                                            -j KUBE-SVL-Z2K6S4QBRLXAOINF
-A KUBE-SVL-Z2K6S4QBRLXAOINF --comment "…:http-80 -> 10.244.120.112:10080" -j KUBE-SEP-GKGOSQ6OXEZ5L3OO

# and in the FILTER table's KUBE-NODEPORTS (hooked from INPUT), the health check port (ETP Local only),
# accepted so the LB's probes reach kube-proxy's health server
-A KUBE-NODEPORTS -p tcp --comment "…:http-80 health check node port" --dport 32335 -j ACCEPT
```

| Caller reaching the LB IP or node port | ETP `Cluster` | ETP `Local` |
|---|---|---|
| a Pod (`-s 10.244.0.0/16`) | SNAT, any endpoint | **no SNAT**, any endpoint (`KUBE-SVC`): treated as if it had gone out to the LB and back |
| the node itself (`src-type LOCAL`) | SNAT, any endpoint | SNAT, any endpoint: the LB IP is a local address, so the source would otherwise be the LB IP |
| anyone else (the LB, a client) | SNAT, any endpoint | **no SNAT**, **this node's endpoints only** (`KUBE-SVL`). The client IP is kept |

`KUBE-SVC` is the "Cluster" policy chain (all endpoints). `KUBE-SVL` is the
"Local" one (endpoints on this node). That's the "SVL" = **S**er**V**ice **L**ocal.

#### Step 5: `loadBalancerSourceRanges` adds a firewall chain

```go
		// Set up firewall chain, if needed
		if usesFWChain {
			natChains.Write(utiliptables.MakeChainLine(fwChain))
			activeNATChains.Insert(fwChain)

			// The service firewall rules are created based on the
			// loadBalancerSourceRanges field. This only works for VIP-like
			// loadbalancers that preserve source IPs. For loadbalancers which
			// direct traffic to service NodePort, the firewall rules will not
			// apply.
			args = append(args[:0],
				"-A", string(fwChain),
				"-m", "comment", "--comment", fmt.Sprintf(`"%s loadbalancer IP"`, svcPortNameString),
			)

			// firewall filter based on each source range
			allowFromNode := false
			for _, cidr := range svcInfo.LoadBalancerSourceRanges() {
				natRules.Write(args, "-s", cidr.String(), "-j", string(externalTrafficChain))
				if cidr.Contains(proxier.nodeIP) {
					allowFromNode = true
				}
			}
			// For VIP-like LBs, the VIP is often added as a local
			// address (via an IP route rule).  In that case, a request
			// from a node to the VIP will not hit the loadbalancer but
			// will loop back with the source IP set to the VIP.  We
			// need the following rules to allow requests from this node.
			if allowFromNode {
				for _, lbip := range svcInfo.LoadBalancerVIPs() {
					natRules.Write(
						args,
						"-s", lbip.String(),
						"-j", string(externalTrafficChain))
				}
			}
			// If the packet was able to reach the end of firewall chain,
			// then it did not get DNATed, so it will match the
			// corresponding KUBE-PROXY-FIREWALL rule.
			natRules.Write(
				"-A", string(fwChain),
				"-m", "comment", "--comment", fmt.Sprintf(`"other traffic to %s will be dropped by KUBE-PROXY-FIREWALL"`, svcPortNameString),
			)
		}
```

How it works:
- The **nat** chain `KUBE-FW` sends allowed sources on to `KUBE-EXT`, where they
  get DNATed.
- A packet from anywhere else falls off the end of `KUBE-FW` **without DNAT**,
  so its destination is still the LB IP.
- The **filter** table's `KUBE-PROXY-FIREWALL` (hooked into INPUT, FORWARD and
  OUTPUT for `--ctstate NEW`) then matches `-d <LB IP> --dport <port>` and
  drops it.

The nat table can't drop packets. It can only decide whether to DNAT, so the
drop happens in the filter table, after the fact.

**Tested on `t-lb`**, with temporary patches that were reverted afterwards.
The test was `curl` from the node to the LB IP `127.0.0.1:8090` and to the node
port `192.168.49.2:30082`:

```
0. no source ranges (as normal)
   KUBE-SERVICES -d 127.0.0.1/32 --dport 8090 "loadbalancer IP" -j KUBE-EXT-2VPX…
   LB IP 127.0.0.1:8090 -> 200          node port 192.168.49.2:30082 -> 200

1. loadBalancerSourceRanges: [10.0.0.0/8]        (the node, 192.168.49.2, is not in it)
   KUBE-SERVICES -d 127.0.0.1/32 --dport 8090 "loadbalancer IP" -j KUBE-FW-2VPX…      ← now the FW chain
   KUBE-FW-2VPX… -s 10.0.0.0/8 "svc-demo/t-lb loadbalancer IP" -j KUBE-EXT-2VPX…
   KUBE-FW-2VPX… "other traffic to svc-demo/t-lb will be dropped by KUBE-PROXY-FIREWALL"
   (filter) KUBE-PROXY-FIREWALL -d 127.0.0.1/32 --dport 8090
            "svc-demo/t-lb traffic not accepted by KUBE-FW-2VPX…" -j DROP
   LB IP 127.0.0.1:8090 -> 000 (dropped)   node port 192.168.49.2:30082 -> 200   ← node port NOT filtered

2. loadBalancerSourceRanges: [10.0.0.0/8, 192.168.49.0/24]   (the node IS in it)
   KUBE-FW-2VPX… -s 10.0.0.0/8       -j KUBE-EXT-2VPX…
   KUBE-FW-2VPX… -s 192.168.49.0/24  -j KUBE-EXT-2VPX…
   KUBE-FW-2VPX… -s 127.0.0.1/32     -j KUBE-EXT-2VPX…      ← allowFromNode: "-s <LB IP>" added
   KUBE-FW-2VPX… "other traffic … dropped by KUBE-PROXY-FIREWALL"
   LB IP 127.0.0.1:8090 -> 200          node port 192.168.49.2:30082 -> 200
```

What the tests show:

- **Case 1:** `curl 127.0.0.1:8090` from the node has source `127.0.0.1`, which isn't in
  `10.0.0.0/8`. It fell off `KUBE-FW` undNATed and was dropped by
  `KUBE-PROXY-FIREWALL`. The drop is silent (`000` = no response), not a reject.
- **Case 2:** the node's IP is in a range, so kube-proxy also allows `-s <LB IP>`.
  That's the `allowFromNode` branch, for nodes that hold the LB IP as a local
  address and send from it. Here the LB IP happens to be `127.0.0.1`, which is
  also the curl's source, so the request was let through.
- **The node port was never filtered** (`200` in every case). The code says so:
  "nodeports are not subect to loadBalancerSourceRanges, and we can't change
  that". The same comment explains why source ranges only work with **VIP-style
  LBs that keep the client IP**. An LB that connects to `node:30082` from its own
  address never reaches `KUBE-FW`, so on those clouds the filtering has to be done
  by the LB itself.

#### Step 6: `ipMode: Proxy` removes the LB IP rule

**Tested on `t-lb`**, by patching the status subresource, then reverting:

```
$ kubectl -n svc-demo patch svc t-lb --subresource=status --type=merge \
    -p '{"status":{"loadBalancer":{"ingress":[{"ip":"127.0.0.1","ipMode":"Proxy"}]}}}'

   KUBE-SERVICES "t-lb loadbalancer IP"  → gone
   KUBE-NODEPORTS --dport 30082          → still there
   KUBE-EXT-2VPX…                        → still there (the node port uses it)
   LB IP 127.0.0.1:8090 -> 000           node port 192.168.49.2:30082 -> 200
```

With `ipMode: Proxy`, kube-proxy assumes the LB proxies the connection to a node
port, so it doesn't capture the LB IP. On a real cloud, a Pod calling the LB IP
then really goes out to the LB and back, instead of being short-circuited on
the node. That's the point of the field, used for LBs that do TLS or PROXY protocol. Here
nothing else answers `127.0.0.1:8090`, so the connection failed. Restored to
`ipMode: VIP`, the rule came back and the request returned `200`.

#### Summary: what a LoadBalancer Service adds

| Field | Effect on kube-proxy's rules (iptables mode) | Checked |
|---|---|---|
| `type: LoadBalancer` | everything a NodePort Service gets: `cluster IP` entry, `KUBE-NODEPORTS` entries, `KUBE-EXT` | lab |
| `status.loadBalancer.ingress[].ip` (VIP mode) | one `"loadbalancer IP"` entry per IP per port, `-d <LB IP> --dport <port>` → `KUBE-EXT` | lab |
| `ingress[].ipMode: Proxy`, or only `hostname` | no LB IP entry; traffic must come via the node port | lab (Proxy); code (hostname) |
| `externalTrafficPolicy: Cluster` | `KUBE-EXT` marks everything for SNAT → `KUBE-SVC` (all endpoints) | lab |
| `externalTrafficPolicy: Local` | `KUBE-EXT` sorts callers (Pods / node / others) → `KUBE-SVL` (local endpoints, no SNAT) + health check node port | lab (Envoy) |
| `loadBalancerSourceRanges` | LB IP entry → `KUBE-FW` (allow by source) + filter `KUBE-PROXY-FIREWALL` DROP; node ports unaffected | lab |
| `loadBalancerSourceRanges: [0.0.0.0/0]` | treated as no ranges (no `KUBE-FW`) | code |
| no endpoints | no nat entries; filter `REJECT` (or `DROP` for ETP `Local` without local endpoints) | code; `REJECT` seen for `dns-demo/empty` (§3.3) |

Cleanup: `t-lb` is back to no `loadBalancerSourceRanges` and status
`{"ip":"127.0.0.1","ipMode":"VIP"}`. Its LB IP rule jumps to `KUBE-EXT-2VPX…`
again, and both test URLs return `200`.

### 4.5 ExternalName: only DNS

```
ext.svc-demo.svc.cluster.local. 30 IN CNAME kubernetes.io.
kubernetes.io.                  30 IN A     3.33.186.135
```

There is no ClusterIP and no proxying; it's a CNAME record and nothing else.
The client resolves the alias and connects to the external address directly.
Re-run later with `dig +noall +answer t-extname.svc-demo.svc.cluster.local`
from the `client` pod (the target's A records had changed in between):

```
t-extname.svc-demo.svc.cluster.local. 30 IN CNAME kubernetes.io.
kubernetes.io.                        30 IN A     15.197.167.90
kubernetes.io.                        30 IN A     3.33.186.135
```

One consequence: the client still sends its own idea of the host name. An
HTTP request to `http://t-extname/` carries `Host: t-extname`, which the
remote server doesn't know about, and TLS certificates won't match it either.
Clients usually have to set the real name themselves:

```
$ curl -s -o /dev/null -w "http=%{http_code}\n" -H "Host: kubernetes.io" http://t-extname/
http=301
```

### 4.6 No matching pods: rejected immediately

A Service whose selector matches no pods still gets a ClusterIP, but its
EndpointSlice is empty:

```yaml
apiVersion: v1
kind: Service
metadata: {name: nobody, namespace: svc-demo}
spec:
  selector: {app: does-not-exist}
  ports: [{port: 80, targetPort: 8080}]
```

```
$ kubectl -n svc-demo get svc,endpointslice -l kubernetes.io/service-name=nobody
NAME     TYPE        CLUSTER-IP      PORT(S)
nobody   ClusterIP   10.99.113.248   80/TCP
NAME           ADDRESSTYPE   PORTS     ENDPOINTS
nobody-pql58   IPv4          <unset>   <unset>
```

kube-proxy then writes a `REJECT` rule in the **filter** table (not `nat`),
which answers with an ICMP "port unreachable" message. The client sees
"connection refused" at once instead of waiting for a timeout:

```
-A KUBE-SERVICES -d 10.99.113.248/32 -p tcp "svc-demo/nobody has no endpoints" -m tcp --dport 80 -j REJECT --reject-with icmp-port-unreachable

$ time curl -s -m 5 nobody/hostname; echo "curl exit=$?"
curl exit=7          # 7 = couldn't connect
real    0m 0.00s     # immediate, not the 5 s timeout
```

The same happens when every pod behind a Service is unready (§3.6). It's a
useful signal: "connection refused" on a ClusterIP usually means "no ready
endpoints", not "the app is down on one pod".

### 4.7 `sessionAffinity: ClientIP`

Normally every new connection is balanced independently. With
`sessionAffinity: ClientIP`, all connections from one client IP go to the
same pod until the client has been quiet for `timeoutSeconds`:

```yaml
apiVersion: v1
kind: Service
metadata: {name: sticky, namespace: svc-demo}
spec:
  selector: {app: web}
  ports: [{port: 80, targetPort: 8080}]
  sessionAffinity: ClientIP
  sessionAffinityConfig: {clientIP: {timeoutSeconds: 600}}   # default 10800 (3 hours)
```

Twenty separate connections from each of two client pods:

```
client  (10.244.120.125):   20 web-7b6bc8bc5-9xfnz
client2 (10.244.120.127):   20 web-7b6bc8bc5-x2pfs
```

Each client sticks to one pod, and different clients can land on different
pods. Earlier, 30 connections from one client all went to `x2pfs` the same way.

**How it's implemented.** kube-proxy uses the iptables `recent` module, which
keeps a small in-kernel list of source IPs with timestamps. The `KUBE-SVC`
chain gets one extra rule per endpoint, placed *before* the random ones
(from `iptables-save -t nat` inside the kube-proxy pod):

```
# 1. "Has this client been sent to endpoint X in the last 600 s?" If yes, go there again.
-A KUBE-SVC-DLIP... "svc-demo/sticky -> 10.244.120.116:8080" -m recent --rcheck --seconds 600 --reap --name KUBE-SEP-5GG2... --rsource -j KUBE-SEP-5GG2...
-A KUBE-SVC-DLIP... "svc-demo/sticky -> 10.244.120.121:8080" -m recent --rcheck --seconds 600 --reap --name KUBE-SEP-IODI... --rsource -j KUBE-SEP-IODI...
-A KUBE-SVC-DLIP... "svc-demo/sticky -> 10.244.120.126:8080" -m recent --rcheck --seconds 600 --reap --name KUBE-SEP-54GW... --rsource -j KUBE-SEP-54GW...
# 2. New client: the usual random choice
-A KUBE-SVC-DLIP... "svc-demo/sticky -> 10.244.120.116:8080" -m statistic --mode random --probability 0.33333333349 -j KUBE-SEP-5GG2...
-A KUBE-SVC-DLIP... "svc-demo/sticky -> 10.244.120.121:8080" -m statistic --mode random --probability 0.50000000000 -j KUBE-SEP-IODI...
-A KUBE-SVC-DLIP... "svc-demo/sticky -> 10.244.120.126:8080" -j KUBE-SEP-54GW...

# 3. In each endpoint chain, the DNAT rule also records the client: --set adds its IP to the list
-A KUBE-SEP-54GW... -p tcp -m recent --set --name KUBE-SEP-54GW... --rsource -m tcp -j DNAT --to-destination 10.244.120.126:8080
```

- `--rsource`: key the list by the packet's source address.
- `--rcheck --seconds 600`: match if this source is in the list and was seen
  in the last 600 seconds.
- `--reap`: drop entries older than that while checking.
- `--set`: add or refresh this source in the list.

The kernel's lists after the test show exactly the two clients and where
they were pinned (`minikube ssh -- sudo cat /proc/net/xt_recent/<name>`):

```
KUBE-SEP-54GWOK2IG34HGMJJ (→ 10.244.120.126, 9xfnz): src=10.244.120.125    ← client
KUBE-SEP-5GG2FHRNNLUHRPUT (→ 10.244.120.116, x2pfs): src=10.244.120.127    ← client2
```

Limits that follow from this design (reasoned from the rules above, not
tested):

- **It's per source IP, not per user.** Everyone behind one NAT gateway, or
  every pod calling through a node's SNAT, shares one pin.
- **It's per node.** Each node keeps its own lists, so the same client can be
  pinned to different pods via different nodes.
- **A pinned pod going unready breaks the pin.** Its rule disappears, and the
  client is balanced again.

## 5. Behaviours that surprise people

### Load balancing is per connection, not per request

```
30 requests on ONE keep-alive connection:   30 web-...-h9km8
30 requests on 30 connections:              10 h9km8   8 scntw   12 tlztj
```

Reproduced later with the commands shown. Giving `curl` several URLs in one
call makes it reuse one connection. A loop of separate `curl` calls opens a
new connection each time:

```
$ curl -s $(for i in $(seq 30); do printf "http://web/hostname "; done) -w "\n" | sort | uniq -c
     30 web-7b6bc8bc5-x2pfs

$ for i in $(seq 30); do curl -s http://web/hostname; echo; done | sort | uniq -c
      7 web-7b6bc8bc5-9xfnz
     13 web-7b6bc8bc5-p8nbc
     10 web-7b6bc8bc5-x2pfs
```

`curl -v` confirms the first form used one connection:

```
* Connected to web (10.107.59.191) port 80
* Connection #0 to host web left intact
* Re-using existing connection with host web
* Re-using existing connection with host web
```

The reason is §3.5: the pod is chosen once, by the first packet of the
connection, and conntrack applies that choice to everything after it. Requests
are invisible to kube-proxy; it only ever sees connections.

HTTP/2 and gRPC reuse one connection for every request, so a ClusterIP gives
them no balancing at all. Fixes: a headless Service plus client-side balancing,
a service mesh, or recycling connections periodically.

### Deleting a pod drops some requests unless it drains

When a pod is deleted, two things start at the same moment: the kubelet sends
SIGTERM, and the endpoint removal propagates through the API server and the
EndpointSlice controller to kube-proxy on every node. If the app exits
immediately, it's gone before the rules are updated.

Continuous requests while deleting one pod:

```
no preStop hook:             ok=6139 failed=2
preStop: sleep 5 (run 1):    ok=4160 failed=0
preStop: sleep 5 (run 2):    ok=2534 failed=0
```

```yaml
lifecycle:
  preStop:
    sleep: {seconds: 5}   # keep serving until the endpoint has been removed everywhere
```

## 6. What Services cost at scale

To measure it, I added 1,500 Services that each select the same 3 pods, giving
4,500 service-to-endpoint pairs.

| Measurement | 7 Services | 1,507 Services |
|---|---|---|
| NAT rules on the node (`iptables-save -t nat \| wc -l`) | 106 | **22,606** |
| `KUBE-SERVICES` chain length | 7 | **1,507** |
| `KUBE-SEP` chains | – | 4,510 |
| Full kube-proxy rule sync (one sample each) | 0.069 s | **1.40 s** |
| Partial sync after scaling an unrelated Deployment | – | 0.129 s |

### 6.1 Rules grow with Services × endpoints

Each Service adds one `KUBE-SERVICES` entry plus a `KUBE-SVC` chain, and each
endpoint adds a `KUBE-SEP` chain. The first packet of every new connection
walks `KUBE-SERVICES` one rule at a time (shown with probe counters in §3.3),
so lookup cost grows with the number of Services. I didn't measure
per-connection latency; at 1,500 Services it's microseconds.

Above 1,000 endpoints (`largeClusterEndpointsThreshold` in
`pkg/proxy/iptables/proxier.go`), kube-proxy switches to "large cluster mode",
which gives up some debuggability to write fewer rules. This test passed that
threshold.

The **nftables** proxier avoids the linear walk: it looks up a Service in a
single map (`service-ips` in `pkg/proxy/nftables/proxier.go`). **IPVS** used to
be the scalable choice, but the source now warns that it is deprecated,
disabled by default in 1.40 and removed in 1.43
(`cmd/kube-proxy/app/server_linux.go`). nftables is the migration path.

### 6.2 Full syncs get slower

A full rewrite went from 0.069 s to 1.40 s. kube-proxy does one at startup and
once an hour (`FullSyncPeriod` in `pkg/proxy/util/utils.go`). Partial syncs
rewrite only the Services that changed, which is why an unrelated scale-up
stayed at 0.129 s.

### 6.3 One pod change fans out across the cluster

I deleted one pod that all 1,501 `web`-style Services select, and watched
EndpointSlices for 90 s:

```
EndpointSlice update events:  1,829
kube-proxy work on this node: 83 partial syncs, 8.49 s total
```

Each Service's slice updated when the pod started terminating, again when it
was removed, and again when the replacement became ready. Every kube-proxy
watches every EndpointSlice in the cluster.

*Reasoned, not measured:* on a 1,000-node cluster, that one deletion would be
about 1.8 million watch events from the API server, and every node would do
those 8.5 s of syncing. Rolling a large Deployment multiplies it.

### 6.4 The old Endpoints API hit a hard limit

*From source, not measured.* The legacy Endpoints controller truncates at
1,000 addresses (`maxCapacity` in `pkg/controller/endpoint/endpoints_controller.go`,
marked with the `endpoints.kubernetes.io/over-capacity: truncated` annotation).
It also rewrote the whole object on every change. EndpointSlices replace it with
shards of at most 100 endpoints by default (`MaxEndpointsPerSlice` in
`pkg/controller/endpointslice/config/v1alpha1/defaults.go`; the flag goes up to
1,000), so a change only rewrites one shard.

### 6.5 `ndots:5` multiplies DNS queries

With CoreDNS query logging on, one `getent hosts github.com` from a pod sent
eight queries:

```
A    github.com.svc-demo.svc.cluster.local.   NXDOMAIN
AAAA github.com.svc-demo.svc.cluster.local.   NXDOMAIN
A    github.com.svc.cluster.local.            NXDOMAIN
AAAA github.com.svc.cluster.local.            NXDOMAIN
A    github.com.cluster.local.                NXDOMAIN
AAAA github.com.cluster.local.                NXDOMAIN
A    github.com.
AAAA github.com.
```

Any name with fewer than five dots tries every search domain first. Six of the
eight queries are wasted, and they all land on CoreDNS, which sits behind a
Service itself.

### 6.6 Every pod gets environment variables for every Service

By default the kubelet injects `<NAME>_SERVICE_HOST`, `_PORT` and Docker-links
style variables for every Service in the pod's namespace (`getServiceEnvVarMap`
in `pkg/kubelet/kubelet_pods.go`). A new pod in the namespace with 1,501
Services:

```
enableServiceLinks: true (default)   ->  10,520 env vars, 323,789 bytes
enableServiceLinks: false            ->  13 env vars
```

The seven variables one Service (`web`) produces, from `env | grep ^WEB_` in
the `client` pod:

```
WEB_SERVICE_HOST=10.107.59.191
WEB_SERVICE_PORT=80
WEB_PORT=tcp://10.107.59.191:80
WEB_PORT_80_TCP=tcp://10.107.59.191:80
WEB_PORT_80_TCP_ADDR=10.107.59.191
WEB_PORT_80_TCP_PORT=80
WEB_PORT_80_TCP_PROTO=tcp
```

Each Service gives 2 variables plus 5 per port, which is how 1,501 Services
reached 10,520. They're written once, when the pod starts. A Service created
later doesn't appear in an already-running pod, which is one more reason to
use DNS names instead. To turn them off:

```yaml
spec:
  enableServiceLinks: false
```

### 6.7 Finite ranges and per-Service cost

*From source and docs, not measured:*

- NodePorts come from a 2,768-port range for the whole cluster
  (`DefaultServiceNodePortRange` = 30000 + 2768 in
  `pkg/kubeapiserver/options/options.go`).
- ClusterIPs come from the service CIDR. minikube uses `10.96.0.0/12`. If none
  is configured, the API server's built-in default is `10.0.0.0/24`, only 254
  Services.
- Each `LoadBalancer` Service is typically its own cloud load balancer.

## 7. What large clusters do about it

| Problem | Mitigation |
|---|---|
| Linear iptables lookup, slow full syncs | `kube-proxy --proxy-mode=nftables`, or an eBPF dataplane (e.g. Cilium) that replaces kube-proxy |
| EndpointSlice fan-out | Fewer, larger Services; avoid many Services over the same pods; `trafficDistribution: PreferSameZone` |
| gRPC/HTTP2 imbalance | Headless Service plus client-side load balancing, or a mesh |
| Dropped requests on rollout | `preStop` sleep longer than endpoint propagation; handle SIGTERM gracefully |
| DNS amplification | Fully qualified names with a trailing dot (`api.example.com.`); lower `ndots` via `dnsConfig`; NodeLocal DNSCache |
| Env var bloat | `enableServiceLinks: false` (use DNS instead) |
| One cloud LB per Service | One Ingress or Gateway API entry point that routes to ClusterIP Services |
| Lost client IP | `externalTrafficPolicy: Local` on NodePort/LoadBalancer Services |
