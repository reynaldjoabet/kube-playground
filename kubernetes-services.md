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
1 EndpointSlice controller (in kube-controller-manager)
     watches Services + Pods
     Pods matching the selector → EndpointSlice  envoy-infra-public-8d55c10f-tx46b
                                    endpoints: 10.244.120.112, ports 10080/10443, ready=true

2 kube-proxy (on every node)
     watches Services + EndpointSlices
     writes the rules:
       KUBE-NODEPORTS  :31845           ─┐
       KUBE-SERVICES   10.99.63.149:80  ─┼─► KUBE-SVC-Z2K6… ─► KUBE-SEP-GKGO… DNAT → 10.244.120.112:10080
       KUBE-SERVICES   127.0.0.1:80     ─┘   (one KUBE-SEP per endpoint)

3 kernel, per packet
     dst node:31845 → match → DNAT to the Pod IP that 2 wrote
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

#### Every way in from outside the cluster

ClusterIP is internal only. From outside, traffic reaches a Pod in one of these
ways:

```text
client outside the cluster
   │
   ├─▶ NodePort                 nodeIP:30080 → kube-proxy → Pod
   │
   ├─▶ LoadBalancer             cloud LB → (NodePort on a node | Pod IP directly) → Pod
   │
   ├─▶ externalIPs              an IP you route to a node → kube-proxy → Pod
   │
   ├─▶ hostPort / hostNetwork   nodeIP:port is the Pod itself, no Service
   │
   └─▶ routable Pod IPs         the network routes Pod IPs (AWS VPC CNI, Azure CNI) → Pod
```

- **NodePort:** every node accepts the same port (30000–32767), and kube-proxy
  forwards it to the Service's endpoints. This is the basic way in.
- **LoadBalancer:** a NodePort plus a load balancer in front of it. The cloud
  controller manager creates it, or a controller such as the AWS Load Balancer
  Controller or MetalLB (`minikube tunnel` in this lab).
  - Classic mode: the LB sends traffic to the node port on the nodes.
  - IP-target mode: the LB sends straight to Pod IPs and skips the node port.
    Examples are the AWS Load Balancer Controller with `target-type: ip`, and
    GKE's container-native load balancing. It needs routable Pod IPs (below).
- **`externalIPs`:** kube-proxy accepts any IP you list in the Service, but you
  have to route that IP to a node yourself. It's rarely used, because whoever
  can edit a Service can claim any IP, including someone else's
  (CVE-2020-8554). The `DenyServiceExternalIPs` admission plugin turns the
  field off.
- **`hostPort` / `hostNetwork`:** the Pod listens on the node's own IP, with no
  Service involved. Ingress controllers and CNI agents sometimes run this way.
- **Routable Pod IPs:** on some CNIs a Pod IP is a real address in the network
  (VPC or VNet). Anything that can route to it can reach the Pod directly. In
  the cloud, the LB IP-target mode above uses this.
- **For development only:** `kubectl port-forward` and `kubectl proxy`. Both
  tunnel through the API server; neither is a production path.

##### Each way, with an example

NodePort, LoadBalancer and `externalIPs` are the `t-nodeport`, `t-lb` and
`t-extip` Services listed at the end of §2.3; §4.1 shows their rules, and §4.4
the LoadBalancer. The others
were run on minikube in a namespace `in-demo` (lab). The node IP is
192.168.49.2.

**`hostPort`:** a port on the node, DNATed to one Pod.

```yaml
apiVersion: v1
kind: Pod
metadata: {name: hp, namespace: in-demo}
spec:
  containers:
  - name: web
    image: registry.k8s.io/e2e-test-images/agnhost:2.53
    args: ["netexec", "--http-port=8080"]
    ports: [{containerPort: 8080, hostPort: 8088}]
```

```text
$ minikube ssh -- curl -s 192.168.49.2:8088/hostname
hp
```

Nothing listens on 8088 (`ss -ltn` shows no socket). The CNI's `portmap`
plugin wrote nat rules instead, the same idea as a node port but for one Pod:

```text
-A PREROUTING -m addrtype --dst-type LOCAL -j CNI-HOSTPORT-DNAT
-A OUTPUT -m addrtype --dst-type LOCAL -j CNI-HOSTPORT-DNAT
-A CNI-HOSTPORT-DNAT -p tcp -m comment --comment "dnat name: \"k8s-pod-network\" id: \"7fc0da3a…\"" -m multiport --dports 8088 -j CNI-DN-4f8d6341353c518a53b3b
-A CNI-DN-4f8d6341353c518a53b3b -s 10.244.120.68/32 -p tcp -m tcp --dport 8088 -j CNI-HOSTPORT-SETMARK
-A CNI-DN-4f8d6341353c518a53b3b -s 127.0.0.1/32 -p tcp -m tcp --dport 8088 -j CNI-HOSTPORT-SETMARK
-A CNI-DN-4f8d6341353c518a53b3b -p tcp -m tcp --dport 8088 -j DNAT --to-destination 10.244.120.68:8080
```

Only the node running the Pod has these rules. Unlike a node port, other nodes
don't forward, and two Pods with the same `hostPort` can't share a node (the
scheduler keeps them apart).

**`hostNetwork`:** the Pod uses the node's network namespace.

```yaml
spec:
  hostNetwork: true
  containers:
  - name: web
    image: registry.k8s.io/e2e-test-images/agnhost:2.53
    args: ["netexec", "--http-port=8089"]
```

```text
$ kubectl -n in-demo get pod hn -o wide
NAME   READY   STATUS    IP             NODE
hn     1/1     Running   192.168.49.2   minikube      # the Pod IP is the node IP

$ minikube ssh -- sudo ss -ltnp | grep 8089
LISTEN 0  4096  *:8089  *:*  users:(("agnhost",pid=8582,fd=3))   # a real socket on the node

$ minikube ssh -- curl -s 192.168.49.2:8089/hostname
minikube                                              # even its hostname is the node's
```

No NAT is involved. The process just listens on the node.

**A plain Pod is not reachable on the node IP:**

```text
$ minikube ssh -- curl -s --max-time 3 192.168.49.2:8080/hostname    # refused (exit 7)
$ minikube ssh -- curl -s 10.244.120.85:8080/hostname                # Pod IP, from the node
plain
```

The node can route to the Pod IP, but nothing outside the cluster can: here the
Pod network 10.244.0.0/16 exists only inside the minikube node. With a CNI that
gives Pods VPC or VNet addresses, the same Pod IP would be reachable from
anywhere in the network.

**`kubectl port-forward` and `kubectl proxy`:** both go laptop → API server →
kubelet → Pod.

```text
$ kubectl -n in-demo port-forward pod/plain 18080:8080 &
$ curl -s localhost:18080/hostname
plain
$ curl -s localhost:18080/clientip
127.0.0.1:49736            # the connection is opened inside the Pod's network namespace

$ kubectl proxy --port=18001 &
$ curl -s localhost:18001/api/v1/namespaces/in-demo/pods/plain:8080/proxy/hostname
plain
```

Both need Kubernetes credentials and send every byte through the API server.
That's fine for debugging and wrong for real traffic.

##### Where Ingress and Gateway API fit

An Ingress (or Gateway API `Gateway`) is a set of HTTP routing rules: by host
and path, with TLS termination. A controller Pod, such as ingress-nginx, Envoy
Gateway or Traefik, carries them out. That controller is an ordinary workload,
so it's exposed through one of the ways above:

```text
client → LoadBalancer (or NodePort/hostPort) → ingress controller Pod
            → reads Host/path → picks a backend Service → that Service's endpoints (Pod IPs)
```

There are two layers:

- **Getting in:** NodePort, LoadBalancer, externalIPs, hostPort/hostNetwork,
  routable Pod IPs. Each exposes one Service or one Pod.
- **L7 routing once in:** Ingress or Gateway. One entry point carries many
  hostnames and paths.

The next section draws both layers in full.

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
 1 sends
   from 10.244.120.115:36720 ──► "10.107.59.191:80 is a Service. Pick .71.
   to   10.107.59.191:80          Rewrite the TO address, store the entry."
                                              │
                               2 forwards     ▼
                                  from 10.244.120.115:36720 ───────────►  gets it; has never
                                  to   10.244.120.71:8080                 heard of the ClusterIP
                                                                       3 replies to the sender
                               ◄───────────────────────────────────────    from 10.244.120.71:8080
                                 "Matches the entry's REPLY side.          to   10.244.120.115:36720
                                  Rewrite the FROM address back."
 4 receives                                   │
   from 10.107.59.191:80   ◄──────────────────┘
   to   10.244.120.115:36720
```

The conntrack entry for that connection holds packets (1) and (3), each before its
rewrite:

```
src=10.244.120.115 dst=10.107.59.191 sport=36720 dport=80     ← 1  as the client sent it   (ORIGINAL)
src=10.244.120.71  dst=10.244.120.115 sport=8080 dport=36720  ← 3  as the server sends it  (REPLY)
```

conntrack doesn't print (4). It's the ORIGINAL line read backwards: from
`10.107.59.191:80` to `10.244.120.115:36720`. So "the REPLY side shows the Pod
IP" and "the client gets answers from the ClusterIP" are both true. They
describe the same reply before and after the node rewrites it.

| Packet | Seen by | Source | Destination |
|---|---|---|---|
| (1) | client | 10.244.120.115:36720 | **10.107.59.191:80** |
| (2) | server | 10.244.120.115:36720 | 10.244.120.71:8080 |
| (3) | server | 10.244.120.71:8080 | 10.244.120.115:36720 |
| (4) | client | **10.107.59.191:80** | 10.244.120.115:36720 |

Only the client's side ((1) (4)) ever shows the ClusterIP.

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
  1 client's eth0 sends it         .115:38770 → 10.107.59.191:80
  2 node sees it arrive on cable 1 "calia6c… In"   .115:38770 → 10.107.59.191:80    BEFORE
  3 node kernel: no entry for this connection yet → walk KUBE-SERVICES (counter 5 → 6)
                 → random pick .71 → DNAT dst → create the conntrack entry
  4 node sends it down cable 2     "cali5e2… Out"  .115:38770 → .71:8080            AFTER
  5 server's eth0 receives it      .115:38770 → .71:8080

PACKET 6, 200 OK (server → client): a reply, so the entry is used and the rules aren't
  1 server's eth0 sends it         .71:8080 → .115:38770           (it answers whoever wrote to it)
  2 node sees it arrive on cable 2 "cali5e2… In"   .71:8080 → .115:38770            BEFORE
  3 node kernel: matches the entry's REPLY side → rewrite src back (counter stays 6)
  4 node sends it down cable 1     "calia6c… Out"  10.107.59.191:80 → .115:38770    AFTER
  5 client's eth0 receives it      10.107.59.191:80 → .115:38770   (the address it called)
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
   -200  CONNTRACK    1 look up .115:38770 → 10.107.59.191:80 → not found
                         → CREATE a new entry, not yet in the table
                           ORIGINAL = .115:38770 → 10.107.59.191:80    (the packet as it arrived)
                           REPLY    = 10.107.59.191:80 → .115:38770    (just the swap, for now)
   -150  mangle
   -100  DNAT (nat)   2 kube-proxy rules: KUBE-SERVICES → KUBE-SVC → pick → KUBE-SEP → DNAT .71:8080
                         → rewrite the packet's dst
                         → UPDATE the entry's REPLY = .71:8080 → .115:38770
 routing decision: .71 → cable 2
 POSTROUTING
   +100  SNAT (nat)   3 KUBE-POSTROUTING: not marked → no MASQUERADE
                         (if it were marked: rewrite src, and change REPLY's dst too)
   last  CONFIRM      4 SAVE the entry into the conntrack table → the [NEW] event fires
 → out to cable 2
```

| Step | What happens to the entry |
|---|---|
| (1) conntrack, before DNAT | **Created.** ORIGINAL is recorded *as the packet arrived*, so it holds the ClusterIP. That's why ORIGINAL always shows what the client sent |
| (2) DNAT | **REPLY changed** to expect the answer from `.71:8080`. That change is the ORIGINAL dst ≠ REPLY src mismatch |
| (3) SNAT, if marked | **REPLY changed again** (hairpin, node or outside caller): the answer is expected at `10.244.120.64:<port>` |
| (4) confirm, at the very end | **Saved.** From now on, later packets of this connection find it |

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
  dst=10.244.120.115 …` in the `conntrack -E` run. The event fires at (4), after NAT.
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

**Later packets in the same direction** (ACK, `GET`, FIN): (1) finds the saved
entry, so (2) doesn't walk `KUBE-SERVICES`. The nat hook applies the stored DNAT
instead, and (3) applies the stored SNAT, if any. Nothing new is saved.

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
   -200  CONNTRACK    1 look up .71:8080 → .115:38770 → FOUND, as the REPLY side of the saved entry
                         → this packet is direction=REPLY; the entry is now SEEN_REPLY (later ASSURED)
   -150  mangle
   -100  nat          2 no rules walked (not a new connection).
                         Undo SNAT = put the original client back as dst. There was no SNAT → nothing to do
 routing decision: dst .115 → cable 1
 POSTROUTING
   +100  nat          3 no rules walked. Undo DNAT = put the ClusterIP back as src:
                         src .71:8080 → 10.107.59.191:80
   last  CONFIRM      4 already saved → nothing
 → out to cable 1:  10.107.59.191:80 → .115:38770   (what the client receives)
```

**The SYN-ACK of the hairpin** (example G: `.115:49382` → `web`, picked
itself, SNATed to `10.244.120.64:36689`), arriving back on the **same** cable:

```
 reply (SYN-ACK) .115:8080 → 10.244.120.64:36689, arriving from cable 1 (the Pod is both ends)

 PREROUTING
   -200  CONNTRACK    1 FOUND: matches REPLY  src=10.244.120.115 dst=10.244.120.64 sport=8080 dport=36689
   -100  nat          2 undo SNAT: dst 10.244.120.64:36689 → .115:49382
 routing decision: dst .115 → cable 1                  ← must come AFTER 2
 POSTROUTING
   +100  nat          3 undo DNAT: src .115:8080 → 10.107.59.191:80
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

 1 conntrack (-200)   hash lookup on the 4 numbers → FOUND, direction ORIGINAL → attach the entry
 2 nat, dstnat (-100) not NEW → SKIP the iptables rules entirely
                      entry has DST_NAT → do the stored swap: dst → .71:8080
 routing              dst is now .71 → cable 2
 3 nat, srcnat (+100) entry has no SRC_NAT → nothing
 4 confirm            entry already saved → nothing
 leaves:                 .115:38770 → .71:8080
```

| Step | Packet 1 | Every later packet |
|---|---|---|
| (1) conntrack | not found → create | found → attach (one hash lookup) |
| (2) `dstnat` hook | **walk the iptables rules**, pick, set up the NAT | no rules: if `DST_NAT`, rewrite dst from the entry |
| routing | yes | yes |
| (3) `srcnat` hook | SNAT check (`KUBE-POSTROUTING`) | no rules: if `SRC_NAT`, rewrite src from the entry |
| (4) confirm | save the entry | nothing |

The swap still has to happen at (2), before routing: the kernel can only choose
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
`10.107.59.191:80` ((3) → (4) above). It can't skip that step: a Pod's veth leads
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

#### Who watches what: from Service to rules

```text
Service (selector app=web, port 80 → targetPort 8080)
   │  watched by
   ▼
EndpointSlice controller (in kube-controller-manager)
   │  finds Pods matching the selector, records their IPs and readiness
   ▼
EndpointSlice  (label kubernetes.io/service-name=web)
   endpoints: 10.244.0.5 ready, 10.244.0.6 ready, 10.244.0.7 not ready
   │  watched by
   ├──▶ kube-proxy on every node → iptables/nftables rules (ClusterIP → Pod IP DNAT)
   └──▶ CoreDNS → web.ns.svc.cluster.local → ClusterIP (or Pod IPs if headless)
```

1. **The Service is only a spec.** It holds a ClusterIP, ports and a label
   selector.
2. **The EndpointSlice controller turns the selector into a list of Pod IPs.**
   It watches Services, Pods and Nodes. For each Service it:
   - finds the Pods whose labels match the selector;
   - writes their IPs into one or more EndpointSlice objects, by default up to
     100 endpoints per slice;
   - records three conditions for each endpoint:
     - `ready`: the readiness probe passes and the Pod isn't terminating;
     - `serving`: like `ready`, but stays true while the Pod is terminating;
     - `terminating`: the Pod is being deleted;
   - resolves a named `targetPort` (e.g. `http`) separately for each Pod, so
     different Pods can use different port numbers.
3. **kube-proxy turns the slices into rules.** kube-proxy runs on every node and
   watches Services and EndpointSlices. In iptables mode, `KUBE-SVC` has one
   probability jump per ready endpoint, and each endpoint has its own
   `KUBE-SEP` DNAT chain. In the lab (§3.3, "Scaling 0 → 1 → 2 → 3 → 5 Pods"):
   - the rules changed when the EndpointSlice changed, not when the Service was
     created;
   - with 0 replicas there was only the filter `REJECT` rule.
4. **CoreDNS answers name lookups from the same data.** A normal Service name
   resolves to its ClusterIP. A headless Service (`clusterIP: None`) resolves
   straight to the ready endpoint IPs (§4.2).

What follows from this:

- **Readiness decides who gets traffic.** A Pod failing its probe stays in the
  slice with `ready: false`, so kube-proxy drops its rule. The Pod keeps
  running; it just gets no new connections.
- **Scaling or Pod restarts change only the slices.** The Service object never
  changes.
- **A Service without a selector gets no endpoints automatically.** You write
  the EndpointSlice yourself, for example to point at a database outside the
  cluster. Set its `kubernetes.io/service-name` label so kube-proxy links it to
  the Service, and give its port the same name as the Service's port.
- **The old `Endpoints` object is deprecated** (since v1.33). It was a single
  object per Service, which had size problems at scale (§6.4). The control
  plane still writes it for Services with a selector, for older clients, but
  kube-proxy and CoreDNS read EndpointSlices.

The examples below show each point on a cluster. First, the `web` slice from
§0.2 and what its conditions mean.

#### The `web` slice and its conditions (lab)

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

#### Examples: named ports, scaling to 0, no selector, legacy Endpoints (lab)

Run on minikube (Kubernetes v1.34, kube-proxy in iptables mode) in a namespace
`ep-demo`. Rules were read with
`kubectl -n kube-system exec <kube-proxy pod> -- iptables-save`.

##### A named `targetPort` with a different port on each Pod

Two Deployments carry the same `app: web` label, but name a different container
port `http`. The Service refers to the name, not a number:

```yaml
# web-a: 2 replicas, labels {app: web, track: a}
        args: ["netexec", "--http-port=8080"]
        ports: [{name: http, containerPort: 8080}]
---
# web-b: 1 replica, labels {app: web, track: b}
        args: ["netexec", "--http-port=9090"]
        ports: [{name: http, containerPort: 9090}]
---
apiVersion: v1
kind: Service
metadata: {name: web, namespace: ep-demo}
spec:
  selector: {app: web}
  ports: [{name: http, port: 80, targetPort: http}]
```

The controller looked up `http` on each Pod. A slice has one `ports` list for
all its endpoints, so Pods with different port numbers went into different
slices:

```text
$ kubectl -n ep-demo get endpointslice -o wide
NAME        ADDRESSTYPE   PORTS   ENDPOINTS                       AGE
web-2bxjv   IPv4          8080    10.244.120.115,10.244.120.116   13s
web-lhmxh   IPv4          9090    10.244.120.105                  6s
```

kube-proxy merged both slices into one `KUBE-SVC` chain with three endpoints.
Each `KUBE-SEP` DNATs to its own port:

```text
-A KUBE-SERVICES -d 10.104.181.209/32 -p tcp -m comment --comment "ep-demo/web:http cluster IP" -m tcp --dport 80 -j KUBE-SVC-YAFVY3TZMTDCY6NC
-A KUBE-SVC-YAFVY3TZMTDCY6NC ! -s 10.244.0.0/16 -d 10.104.181.209/32 -p tcp -m comment --comment "ep-demo/web:http cluster IP" -m tcp --dport 80 -j KUBE-MARK-MASQ
-A KUBE-SVC-YAFVY3TZMTDCY6NC -m comment --comment "ep-demo/web:http -> 10.244.120.105:9090" -m statistic --mode random --probability 0.33333333349 -j KUBE-SEP-KJWWSRXPF3634JVS
-A KUBE-SVC-YAFVY3TZMTDCY6NC -m comment --comment "ep-demo/web:http -> 10.244.120.115:8080" -m statistic --mode random --probability 0.50000000000 -j KUBE-SEP-TYN4IXIJQUEY76TF
-A KUBE-SVC-YAFVY3TZMTDCY6NC -m comment --comment "ep-demo/web:http -> 10.244.120.116:8080" -j KUBE-SEP-BL5XC6TPUD5WZ7DE
-A KUBE-SEP-KJWWSRXPF3634JVS -p tcp -m comment --comment "ep-demo/web:http" -m tcp -j DNAT --to-destination 10.244.120.105:9090
-A KUBE-SEP-TYN4IXIJQUEY76TF -p tcp -m comment --comment "ep-demo/web:http" -m tcp -j DNAT --to-destination 10.244.120.115:8080
-A KUBE-SEP-BL5XC6TPUD5WZ7DE -p tcp -m comment --comment "ep-demo/web:http" -m tcp -j DNAT --to-destination 10.244.120.116:8080
```

(The hairpin `KUBE-MARK-MASQ` rule of each `KUBE-SEP` is left out.) 30 requests
to `web:80` reached all three Pods:

```text
$ for i in $(seq 1 30); do kubectl -n ep-demo exec client -- curl -s web/hostname; echo; done | sort | uniq -c
  10 web-a-ff46bcbdb-wghhp
   7 web-a-ff46bcbdb-zpc4x
  13 web-b-68c5b76485-hfhkh
```

This is how a rollout can change a container's port without breaking the
Service: during the rollout, old and new Pods sit in separate slices, and both
get traffic.

##### Scaling to 0 and back: only the slices change

```text
$ kubectl -n ep-demo scale deploy web-a web-b --replicas=0
$ kubectl -n ep-demo get endpointslice -l kubernetes.io/service-name=web -o wide
NAME        ADDRESSTYPE   PORTS     ENDPOINTS   AGE
web-lhmxh   IPv4          <unset>   <unset>     3m50s

nat rules for ep-demo/web: none
filter: -A KUBE-SERVICES -d 10.104.181.209/32 -p tcp -m comment --comment "ep-demo/web:http has no endpoints" -m tcp --dport 80 -j REJECT --reject-with icmp-port-unreachable
curl web/hostname: exit 7 (connection refused, at once)
```

```text
$ kubectl -n ep-demo scale deploy web-a --replicas=2; kubectl -n ep-demo scale deploy web-b --replicas=1
$ kubectl -n ep-demo get endpointslice -l kubernetes.io/service-name=web -o wide
NAME        ADDRESSTYPE   PORTS   ENDPOINTS                      AGE
web-lhmxh   IPv4          9090    10.244.120.91                  4m37s
web-zh5h2   IPv4          8080    10.244.120.99,10.244.120.111   2s
```

- At 0 replicas the controller kept one empty slice (`endpoints: null`,
  `ports: null`) and deleted the other.
- On scale-up it reused the empty slice and created a new one.
- The Service's `resourceVersion` was 491348 at 0 replicas and still 491348
  after scaling up: the Service object never changed.

##### A Service with no selector, and a hand-written EndpointSlice

`ext-db` stands for a database outside the cluster. In the lab its backend was
a plain Pod, `outside-db` at 10.244.120.84, listening on 5432 and carrying no
labels the Service could select.

```yaml
apiVersion: v1
kind: Service
metadata: {name: ext-db, namespace: ep-demo}
spec:
  ports: [{name: pg, port: 5432, targetPort: 5432}]   # no selector
```

What each step did:

| Step | Slices for `ext-db` | Rules | `curl ext-db:5432/hostname` |
|---|---|---|---|
| Service only | none | filter `REJECT ... "ep-demo/ext-db:pg has no endpoints"` | refused (exit 7) |
| + slice **without** the `kubernetes.io/service-name` label | not linked | still the `REJECT` | refused (exit 7) |
| + label `kubernetes.io/service-name=ext-db` | `ext-db-1`, 10.244.120.84 | `KUBE-SVC` + `KUBE-SEP` DNAT to 10.244.120.84:5432 | `outside-db` |
| slice port renamed `postgres` (Service port is `pg`) | linked | no working rule | refused (exit 7) |
| port renamed back to `pg` | linked | DNAT again | `outside-db` |

The slice that worked:

```yaml
apiVersion: discovery.k8s.io/v1
kind: EndpointSlice
metadata:
  name: ext-db-1                               # any name; the label does the linking
  namespace: ep-demo
  labels:
    kubernetes.io/service-name: ext-db
addressType: IPv4
ports: [{name: pg, port: 5432, protocol: TCP}] # name must match the Service port's name
endpoints:
- addresses: ["10.244.120.84"]                 # no conditions: treated as ready
```

And the rules kube-proxy wrote from it:

```text
-A KUBE-SERVICES -d 10.102.65.175/32 -p tcp -m comment --comment "ep-demo/ext-db:pg cluster IP" -m tcp --dport 5432 -j KUBE-SVC-KIAFYPMWTWAJLAID
-A KUBE-SVC-KIAFYPMWTWAJLAID ! -s 10.244.0.0/16 -d 10.102.65.175/32 -p tcp -m comment --comment "ep-demo/ext-db:pg cluster IP" -m tcp --dport 5432 -j KUBE-MARK-MASQ
-A KUBE-SVC-KIAFYPMWTWAJLAID -m comment --comment "ep-demo/ext-db:pg -> 10.244.120.84:5432" -j KUBE-SEP-F2AJJ44E6KNBJT4C
-A KUBE-SEP-F2AJJ44E6KNBJT4C -s 10.244.120.84/32 -m comment --comment "ep-demo/ext-db:pg" -j KUBE-MARK-MASQ
-A KUBE-SEP-F2AJJ44E6KNBJT4C -p tcp -m comment --comment "ep-demo/ext-db:pg" -m tcp -j DNAT --to-destination 10.244.120.84:5432
```

- These are the same rules a selector-based Service gets. kube-proxy doesn't
  care who wrote the slice.
- Nobody health-checks a hand-written endpoint. If the database moves or goes
  down, the rule stays until you edit the slice.
- To point a name at a DNS name instead of an IP, use `type: ExternalName`
  (§4.5).

##### The legacy `Endpoints` object

```text
$ kubectl -n ep-demo get endpoints
Warning: v1 Endpoints is deprecated in v1.33+; use discovery.k8s.io/v1 EndpointSlice
NAME   ENDPOINTS                                                     AGE
web    10.244.120.105:9090,10.244.120.115:8080,10.244.120.116:8080   44s
```

- `web` has a selector, so the control plane still writes a single `Endpoints`
  object for it, holding all three addresses from both slices.
- `ext-db` has no `Endpoints` object. Hand-written slices aren't copied back.

Copying works the other way, though. A selectorless Service `ext-legacy` with a
hand-written `Endpoints` object (`subsets: [{addresses: [{ip: 10.244.120.84}],
ports: [{name: pg, port: 5432}]}]`) got a slice created for it:

```text
NAME               ADDRESSTYPE   PORTS   ENDPOINTS       LABELS
ext-legacy-v2v6b   IPv4          5432    10.244.120.84   endpointslice.kubernetes.io/managed-by=endpointslicemirroring-controller.k8s.io,
                                                         kubernetes.io/service-name=ext-legacy
```

The EndpointSlice mirroring controller made it, and `curl ext-legacy:5432`
reached `outside-db`. Old manifests that write `Endpoints` by hand keep
working, but new ones should write EndpointSlices directly.

#### When the ClusterIP enters `KUBE-SERVICES`

Creating a Service doesn't put its ClusterIP in the nat table. kube-proxy adds
it only once the Service has at least one usable endpoint. Until then the only
rule is a `REJECT` in the filter table.

```text
kubectl apply -f svc.yaml
  │
  ▼
API server: allocates a ClusterIP, stores the Service in etcd
  │
  ▼
kube-proxy (watching Services) syncs:
  no endpoints yet →  filter KUBE-SERVICES:  -d ClusterIP --dport P ... "has no endpoints" -j REJECT
                      nat KUBE-SERVICES:     nothing for this Service
  │
  ▼
EndpointSlice controller: Pods match the selector and become ready → slice updated
  │
  ▼
kube-proxy (watching EndpointSlices) syncs again:
  filter: the REJECT is removed
  nat:    KUBE-SERVICES  -d ClusterIP/32 --dport P -j KUBE-SVC-…   ← now the ClusterIP appears
          KUBE-SVC-…     masquerade rule + one jump per endpoint
          KUBE-SEP-…     hairpin + DNAT, one chain per endpoint
```

What counts as usable:

- **Ready endpoints:** a slice entry with `ready: true`.
- **No ready endpoints, but some still serving:** endpoints with
  `serving: true, terminating: true`, for example during a rollout. kube-proxy
  keeps the rules, so connections still go through.
- **Only not-ready endpoints:** the nat rules go away and the `REJECT` comes
  back, as if there were no endpoints.
- **No endpoints at all:** for example after scaling to 0 or deleting every Pod,
  once the Pods have exited. The same thing happens: the nat rules go away and
  the `REJECT` comes back. While the Pods are still terminating and serving,
  the previous bullet applies instead.

The other rule types follow the same rule:

- **NodePort:** `KUBE-NODEPORTS` and `KUBE-EXT` appear together with the
  ClusterIP rule, when the first endpoint shows up.
- **LoadBalancer:** the LB-IP rules appear the same way (from the code,
  §4.4.1).
- **No endpoints:** both get a `REJECT` in filter instead. For node ports it's
  in `KUBE-EXTERNAL-SERVICES`.
- **Headless Service** (`clusterIP: None`): it never gets nat rules at all. DNS
  hands out the Pod IPs directly (§4.2).

##### Each case on a cluster (lab)

A NodePort Service `w` (ClusterIP 10.103.0.99, port 80, node port 30090) in a
namespace `when-demo`, in front of a Deployment that starts at 0 replicas. Its
Pods are only ready once `/tmp/ready` exists, and a `preStop` sleep keeps a
deleted Pod terminating for 90 seconds:

```yaml
apiVersion: apps/v1
kind: Deployment
metadata: {name: w, namespace: when-demo}
spec:
  replicas: 0
  selector: {matchLabels: {app: w}}
  template:
    metadata: {labels: {app: w}}
    spec:
      terminationGracePeriodSeconds: 120
      containers:
      - name: web
        image: registry.k8s.io/e2e-test-images/agnhost:2.53
        args: ["netexec", "--http-port=8080"]
        readinessProbe:
          exec: {command: ["cat", "/tmp/ready"]}   # not ready until the file exists
          periodSeconds: 2
          failureThreshold: 1
        lifecycle:
          preStop: {sleep: {seconds: 90}}         # keeps a deleted Pod terminating for 90 s
---
apiVersion: v1
kind: Service
metadata: {name: w, namespace: when-demo}
spec:
  type: NodePort
  selector: {app: w}
  ports: [{name: http, port: 80, targetPort: 8080, nodePort: 30090}]
```

After each step: the slice, every kube-proxy rule for `when-demo/w` (from
`iptables-save` in the kube-proxy Pod, `-m comment --comment` dropped), and a
curl to the ClusterIP from a client Pod and to `192.168.49.2:30090` from the
node.

**Step 1: Service created, 0 replicas.** No slice entries, no nat rules, two
`REJECT`s:

```text
slice: (empty)
-A KUBE-EXTERNAL-SERVICES -p tcp "when-demo/w:http has no endpoints" -m addrtype --dst-type LOCAL -m tcp --dport 30090 -j REJECT --reject-with icmp-port-unreachable
-A KUBE-SERVICES -d 10.103.0.99/32 -p tcp "when-demo/w:http has no endpoints" -m tcp --dport 80 -j REJECT --reject-with icmp-port-unreachable
curl ClusterIP: refused (exit 7)    curl nodeIP:30090: refused
```

The first `REJECT` is in `KUBE-EXTERNAL-SERVICES` (the node port), the second in
filter `KUBE-SERVICES` (the ClusterIP).

**Step 2: scaled to 1, the Pod is running but not ready.** The Pod is in the
slice, but that changes nothing:

```text
slice: 10.244.120.118 ready=false serving=false terminating=false
-A KUBE-EXTERNAL-SERVICES ... "when-demo/w:http has no endpoints" ... --dport 30090 -j REJECT ...
-A KUBE-SERVICES -d 10.103.0.99/32 ... "when-demo/w:http has no endpoints" ... --dport 80 -j REJECT ...
curl ClusterIP: refused (exit 7)    curl nodeIP:30090: refused
```

**Step 3: `touch /tmp/ready`, the Pod becomes ready.** Both `REJECT`s are gone,
and the ClusterIP and node port rules appear together:

```text
slice: 10.244.120.118 ready=true serving=true terminating=false
-A KUBE-SERVICES -d 10.103.0.99/32 -p tcp "when-demo/w:http cluster IP" -m tcp --dport 80 -j KUBE-SVC-4GI6F4H2OVCZ7N5B
-A KUBE-NODEPORTS -d 127.0.0.0/8 -p tcp "when-demo/w:http" -m tcp --dport 30090 -m nfacct --nfacct-name  localhost_nps_accepted_pkts -j KUBE-EXT-4GI6F4H2OVCZ7N5B
-A KUBE-NODEPORTS -p tcp "when-demo/w:http" -m tcp --dport 30090 -j KUBE-EXT-4GI6F4H2OVCZ7N5B
-A KUBE-EXT-4GI6F4H2OVCZ7N5B "masquerade traffic for when-demo/w:http external destinations" -j KUBE-MARK-MASQ
-A KUBE-SVC-4GI6F4H2OVCZ7N5B ! -s 10.244.0.0/16 -d 10.103.0.99/32 -p tcp "when-demo/w:http cluster IP" -m tcp --dport 80 -j KUBE-MARK-MASQ
-A KUBE-SVC-4GI6F4H2OVCZ7N5B "when-demo/w:http -> 10.244.120.118:8080" -j KUBE-SEP-AOUMQAM4FYASX4UK
-A KUBE-SEP-AOUMQAM4FYASX4UK -s 10.244.120.118/32 "when-demo/w:http" -j KUBE-MARK-MASQ
-A KUBE-SEP-AOUMQAM4FYASX4UK -p tcp "when-demo/w:http" -m tcp -j DNAT --to-destination 10.244.120.118:8080
curl nodeIP:30090: w-747c4dbbc8-smjqf
```

(`KUBE-EXT` also jumps to `KUBE-SVC`; that rule has no comment, so the grep
missed it.) The first curl to the ClusterIP, run right after the rules were
read, was still refused; three more a second later all reached the Pod.

**Step 4: the ready Pod is deleted.** It stays terminating for 90 s (`preStop`).
The Deployment's replacement isn't ready yet. With no ready endpoint, kube-proxy
falls back to the terminating one that's still serving:

```text
w-...-9dwzc   1/1   Terminating   10.244.120.66
w-...-xbnpw   0/1   Running       10.244.120.110

slice: 10.244.120.66  ready=false serving=true  terminating=true
       10.244.120.110 ready=false serving=false terminating=false
-A KUBE-SVC-4GI6F4H2OVCZ7N5B "when-demo/w:http -> 10.244.120.66:8080" -j KUBE-SEP-QXKTDY4V5YY3JR5V
curl ClusterIP: w-747c4dbbc8-9dwzc    curl nodeIP:30090: w-747c4dbbc8-9dwzc
```

No `REJECT` in filter, and both curls reached the terminating Pod. (Steps 4 and
5 are from a second run of steps 3–4, so the Pod IPs differ from step 3.)

**Step 5: the replacement becomes ready.** As soon as there's a ready endpoint,
the terminating one is dropped from the rules, though it's still in the slice
as serving:

```text
slice: 10.244.120.66  ready=false serving=true terminating=true
       10.244.120.110 ready=true  serving=true terminating=false
-A KUBE-SVC-4GI6F4H2OVCZ7N5B "when-demo/w:http -> 10.244.120.110:8080" -j KUBE-SEP-WM26RNQHKIXQ3QDU
curl ClusterIP: w-747c4dbbc8-xbnpw    curl nodeIP:30090: w-747c4dbbc8-xbnpw
```

**Step 6: scaled to 0.** The Pod is terminating, so the rules stay, pointing at
it:

```text
w-...-f2dsv   1/1   Terminating

slice: 10.244.120.93 ready=false serving=true terminating=true
-A KUBE-SVC-4GI6F4H2OVCZ7N5B "when-demo/w:http -> 10.244.120.93:8080" -j KUBE-SEP-MJALZYMCHNZ4OTBM
curl ClusterIP: w-747c4dbbc8-f2dsv    curl nodeIP:30090: w-747c4dbbc8-f2dsv
```

**Step 7: the last Pod has exited.** Back to step 1:

```text
slice: (empty)
-A KUBE-EXTERNAL-SERVICES ... "when-demo/w:http has no endpoints" ... --dport 30090 -j REJECT ...
-A KUBE-SERVICES -d 10.103.0.99/32 ... "when-demo/w:http has no endpoints" ... --dport 80 -j REJECT ...
curl ClusterIP: refused (exit 7)    curl nodeIP:30090: refused
```

In short:

| Step | Endpoints in the slice | nat rules | filter `REJECT` | curl |
|---|---|---|---|---|
| 1. Service, 0 replicas | none | none | ClusterIP + node port | refused |
| 2. 1 Pod, not ready | 1 not ready | none | ClusterIP + node port | refused |
| 3. Pod ready | 1 ready | ClusterIP + node port → Pod | none | Pod |
| 4. Pod deleted, replacement not ready | 1 terminating (serving), 1 not ready | → terminating Pod | none | terminating Pod |
| 5. Replacement ready | 1 terminating (serving), 1 ready | → ready Pod only | none | ready Pod |
| 6. Scaled to 0 | 1 terminating (serving) | → terminating Pod | none | terminating Pod |
| 7. Pod exited | none | none | ClusterIP + node port | refused |

The Service object never changed. Every change to the rules came from a change
to the EndpointSlice.

#### The Service object rarely changes

After you create a Service, it changes only when someone or something edits it.
Pods starting, stopping, becoming ready or failing probes never touch it. All of
that goes to the EndpointSlices. In the "Scaling to 0 and back" example above,
the Service's `resourceVersion` stayed the same throughout.

What does change a Service:

- **You edit the spec:** ports, selector, type, `externalTrafficPolicy`,
  `sessionAffinity`, and so on. The ClusterIP can't be changed after creation;
  you have to delete and recreate the Service.
- **A load balancer controller writes its status:** the cloud controller
  manager, the AWS Load Balancer Controller or MetalLB fills in
  `status.loadBalancer.ingress` with the LB's IP or hostname. It usually
  happens once, when the LB is created.
- **Controllers add metadata:** for example, the finalizer
  `service.kubernetes.io/load-balancer-cleanup` on LoadBalancer Services, so the
  LB is deleted before the Service. Tools like Helm or Argo CD also add labels
  and annotations, and server-side apply updates `managedFields`.

Why the design splits it this way:

- **Each change is sent to every watcher.** Every node's kube-proxy (plus
  CoreDNS, ingress controllers and so on) watches all Services and all
  EndpointSlices.
- **Keeping the Service still makes those updates cheap.** A Pod restart sends
  only the changed slice of up to 100 endpoints. Before slices, the whole
  `Endpoints` object was sent, and that object could hold thousands of
  addresses (§6.4).
- **Each object has one writer.** You (or Helm) own the Service, and the
  EndpointSlice controller owns the slices. They never fight over the same
  object.

##### Each kind of change, and what it does to the rules (lab)

One Service `web` (ClusterIP 10.107.53.29, port `http` 80 → 8080) in a
namespace `chg-demo`, selecting two `ver: v1` Pods (10.244.120.68, .92). A
third Pod, `ver: v2` (10.244.120.120), carries `app: web` but isn't selected at
first. Each change was applied on top of the previous one, then kube-proxy's
rules for the Service were diffed against the rules before the change
(`-m comment --comment` dropped; `<` removed, `>` added).

| Change | Command | Rules |
|---|---|---|
| Label, annotation | `kubectl label svc web team=payments`, `kubectl annotate svc web example.com/owner=alice` | none changed |
| Finalizer | `metadata.finalizers: [example.com/keep]` | none changed |
| `port` 80 → 8081 | `spec.ports[0].port` | 2 rules: the `--dport` in `KUBE-SERVICES` and the masquerade rule |
| `targetPort` 8080 → 9090 | `spec.ports[0].targetPort` | every `KUBE-SEP`, plus the `KUBE-SVC` jumps, through the slice |
| Selector widened | `{app: web, ver: v1}` → `{app: web}` | one more endpoint, through the slice |
| `sessionAffinity: ClientIP` | `spec.sessionAffinity` | every endpoint rule gets a `recent` match |
| `type: NodePort` | `spec.type`, `nodePort: 30091` | `KUBE-NODEPORTS` and `KUBE-EXT` added |
| `externalTrafficPolicy: Local` | `spec.externalTrafficPolicy` | `KUBE-EXT` rewritten, `KUBE-SVL` added |
| `type: LoadBalancer` | `spec.type` | none changed; `EXTERNAL-IP` is `<pending>` |
| LB status written | `status.loadBalancer.ingress: [{ip: 203.0.113.10}]` | one `KUBE-SERVICES` rule for the LB IP |
| `clusterIP` changed | `spec.clusterIP: 10.107.53.30` | refused by the API server |

The diffs:

**Labels, annotations, finalizers.** The Service's `resourceVersion` changed
(497214 → 497288 → 497295), and every node's kube-proxy received the update,
but nothing it uses changed, so the rules stayed the same.

**`port` 80 → 8081.** Only the rules that match the Service's own port:

```text
< -A KUBE-SERVICES -d 10.107.53.29/32 -p tcp "chg-demo/web:http cluster IP" -m tcp --dport 80 -j KUBE-SVC-VPOZHHPUPUFUZ4NW
< -A KUBE-SVC-VPOZHHPUPUFUZ4NW ! -s 10.244.0.0/16 -d 10.107.53.29/32 -p tcp "chg-demo/web:http cluster IP" -m tcp --dport 80 -j KUBE-MARK-MASQ
> -A KUBE-SERVICES -d 10.107.53.29/32 -p tcp "chg-demo/web:http cluster IP" -m tcp --dport 8081 -j KUBE-SVC-VPOZHHPUPUFUZ4NW
> -A KUBE-SVC-VPOZHHPUPUFUZ4NW ! -s 10.244.0.0/16 -d 10.107.53.29/32 -p tcp "chg-demo/web:http cluster IP" -m tcp --dport 8081 -j KUBE-MARK-MASQ
```

The chain name stayed `KUBE-SVC-VPOZHHPUPUFUZ4NW`: its hash comes from the
port's name (`http`), not its number. Clients using port 80 are now refused.

**`targetPort` 8080 → 9090.** kube-proxy doesn't read `targetPort`. The
EndpointSlice controller rewrote the slice (its `ports` became `9090`), and
kube-proxy followed the slice:

```text
< -A KUBE-SVC-VPOZHHPUPUFUZ4NW "chg-demo/web:http -> 10.244.120.68:8080" -m statistic --mode random --probability 0.50000000000 -j KUBE-SEP-KGNLLBBNFZW3FS7Z
< -A KUBE-SVC-VPOZHHPUPUFUZ4NW "chg-demo/web:http -> 10.244.120.92:8080" -j KUBE-SEP-6IC3V4VMMZCTL3CO
< -A KUBE-SEP-KGNLLBBNFZW3FS7Z -p tcp "chg-demo/web:http" -m tcp -j DNAT --to-destination 10.244.120.68:8080
< -A KUBE-SEP-6IC3V4VMMZCTL3CO -p tcp "chg-demo/web:http" -m tcp -j DNAT --to-destination 10.244.120.92:8080
> -A KUBE-SVC-VPOZHHPUPUFUZ4NW "chg-demo/web:http -> 10.244.120.68:9090" -m statistic --mode random --probability 0.50000000000 -j KUBE-SEP-IFNNBKRRVZD3JTJU
> -A KUBE-SVC-VPOZHHPUPUFUZ4NW "chg-demo/web:http -> 10.244.120.92:9090" -j KUBE-SEP-QXMDWAKSCVDCPCL3
> -A KUBE-SEP-IFNNBKRRVZD3JTJU -p tcp "chg-demo/web:http" -m tcp -j DNAT --to-destination 10.244.120.68:9090
> -A KUBE-SEP-QXMDWAKSCVDCPCL3 -p tcp "chg-demo/web:http" -m tcp -j DNAT --to-destination 10.244.120.92:9090
```

(The hairpin rule of each `KUBE-SEP` changed the same way and is left out.)
Every `KUBE-SEP` got a new name, because the endpoint's hash includes its port.
Nothing listens on 9090, so this change broke the Service; it was set back to
8080 before the next step.

**Selector widened to `{app: web}`.** Like `targetPort`, this reaches the
rules through the slice. The `v2` Pod joined, and its endpoint was added:

```text
> -A KUBE-SVC-VPOZHHPUPUFUZ4NW "chg-demo/web:http -> 10.244.120.120:8080" -m statistic --mode random --probability 0.33333333349 -j KUBE-SEP-XKVWLTG7W57YRGZP
> -A KUBE-SEP-XKVWLTG7W57YRGZP -s 10.244.120.120/32 "chg-demo/web:http" -j KUBE-MARK-MASQ
> -A KUBE-SEP-XKVWLTG7W57YRGZP -p tcp "chg-demo/web:http" -m tcp -j DNAT --to-destination 10.244.120.120:8080
```

The other two jumps didn't change: kube-proxy orders endpoints by their
address as a string, so `10.244.120.120` comes before `10.244.120.68`, and the
new endpoint took the first place, with 1/3. The `.68` rule kept its 0.5, and
`.92` stayed last.

**`sessionAffinity: ClientIP`.** Every endpoint rule gains a `recent` match
that records the client and sends it back to the same endpoint for 3 hours
(10800 s, the default `timeoutSeconds`). One endpoint shown; the other two
changed the same way:

```text
< -A KUBE-SEP-KGNLLBBNFZW3FS7Z -p tcp "chg-demo/web:http" -m tcp -j DNAT --to-destination 10.244.120.68:8080
> -A KUBE-SEP-KGNLLBBNFZW3FS7Z -p tcp "chg-demo/web:http" -m recent --set --name KUBE-SEP-KGNLLBBNFZW3FS7Z --mask 255.255.255.255 --rsource -m tcp -j DNAT --to-destination 10.244.120.68:8080
> -A KUBE-SVC-VPOZHHPUPUFUZ4NW "chg-demo/web:http -> 10.244.120.68:8080" -m recent --rcheck --seconds 10800 --reap --name KUBE-SEP-KGNLLBBNFZW3FS7Z --mask 255.255.255.255 --rsource -j KUBE-SEP-KGNLLBBNFZW3FS7Z
```

The new `KUBE-SVC` rules come before the probability jumps: a client seen in the
last 3 hours goes straight back to its endpoint (§4.7). Affinity was turned off
again before the next step.

**`type: NodePort`** (with `nodePort: 30091`, port now 8081). Four rules are
added; the ClusterIP rules don't change:

```text
> -A KUBE-NODEPORTS -d 127.0.0.0/8 -p tcp "chg-demo/web:http" -m tcp --dport 30091 -m nfacct --nfacct-name  localhost_nps_accepted_pkts -j KUBE-EXT-VPOZHHPUPUFUZ4NW
> -A KUBE-NODEPORTS -p tcp "chg-demo/web:http" -m tcp --dport 30091 -j KUBE-EXT-VPOZHHPUPUFUZ4NW
> -A KUBE-EXT-VPOZHHPUPUFUZ4NW "masquerade traffic for chg-demo/web:http external destinations" -j KUBE-MARK-MASQ
> -A KUBE-EXT-VPOZHHPUPUFUZ4NW -j KUBE-SVC-VPOZHHPUPUFUZ4NW
```

**`externalTrafficPolicy: Local`.** `KUBE-EXT` no longer masquerades
everything. It now sorts callers, and outside traffic goes to a new `KUBE-SVL`
chain (local endpoints only). `KUBE-EXT` in chain order:

```text
-A KUBE-EXT-VPOZHHPUPUFUZ4NW -s 10.244.0.0/16 "pod traffic for chg-demo/web:http external destinations" -j KUBE-SVC-VPOZHHPUPUFUZ4NW
-A KUBE-EXT-VPOZHHPUPUFUZ4NW "masquerade LOCAL traffic for chg-demo/web:http external destinations" -m addrtype --src-type LOCAL -j KUBE-MARK-MASQ
-A KUBE-EXT-VPOZHHPUPUFUZ4NW "route LOCAL traffic for chg-demo/web:http external destinations" -m addrtype --src-type LOCAL -j KUBE-SVC-VPOZHHPUPUFUZ4NW
-A KUBE-EXT-VPOZHHPUPUFUZ4NW -j KUBE-SVL-VPOZHHPUPUFUZ4NW
```

```text
> -A KUBE-SVL-VPOZHHPUPUFUZ4NW "chg-demo/web:http -> 10.244.120.120:8080" -m statistic --mode random --probability 0.33333333349 -j KUBE-SEP-XKVWLTG7W57YRGZP
> -A KUBE-SVL-VPOZHHPUPUFUZ4NW "chg-demo/web:http -> 10.244.120.68:8080" -m statistic --mode random --probability 0.50000000000 -j KUBE-SEP-KGNLLBBNFZW3FS7Z
> -A KUBE-SVL-VPOZHHPUPUFUZ4NW "chg-demo/web:http -> 10.244.120.92:8080" -j KUBE-SEP-6IC3V4VMMZCTL3CO
```

- Pods and the node itself still go through `KUBE-SVC`, to any endpoint.
- Outside clients go through `KUBE-SVL`, unmasqueraded, so the Pod sees the real
  client IP.
- `KUBE-SVL` holds only this node's endpoints. On this one-node cluster that's
  all three; on a bigger cluster each node's `KUBE-SVL` would differ. It shares
  the `VPOZHHPUPUFUZ4NW` hash with `KUBE-SVC` and `KUBE-EXT`.

The policy was set back to `Cluster` before the next step.

**`type: LoadBalancer`.** No rule changed. kube-proxy writes LB rules for the
IPs in the Service's status, and with no LB controller running, the status was
empty:

```text
NAME   TYPE           CLUSTER-IP     EXTERNAL-IP   PORT(S)          AGE
web    LoadBalancer   10.107.53.29   <pending>     8081:30091/TCP   91s
```

**LB status written.** A cloud controller would do this once the LB exists. In
the lab it was written by hand, standing in for one
(`kubectl patch svc web --subresource=status`). One rule appeared, sending the
LB IP into the same `KUBE-EXT` chain as the node port:

```text
> -A KUBE-SERVICES -d 203.0.113.10/32 -p tcp "chg-demo/web:http loadbalancer IP" -m tcp --dport 8081 -j KUBE-EXT-VPOZHHPUPUFUZ4NW
```

```text
NAME   TYPE           CLUSTER-IP     EXTERNAL-IP    PORT(S)          AGE
web    LoadBalancer   10.107.53.29   203.0.113.10   8081:30091/TCP   95s
```

**`clusterIP` changed.** The API server refuses it:

```text
$ kubectl -n chg-demo patch svc web --type=merge -p '{"spec":{"clusterIP":"10.107.53.30","clusterIPs":["10.107.53.30"]}}'
The Service "web" is invalid: spec.clusterIPs[0]: Invalid value: ["10.107.53.30"]: may not change once set
```

Two kinds of edit, then:

- **kube-proxy reads the field directly:** `port`, `type`, `nodePort`,
  `externalTrafficPolicy`, `sessionAffinity` and the LB status. The edit changes
  the rules on every node.
- **The field reaches the rules through the slice:** `selector` and
  `targetPort`. The EndpointSlice controller rewrites the slices, and kube-proxy
  follows those.

Labels, annotations and finalizers change nothing in the rules.

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

#### One chain per table, not one chain across tables (lab)

In iptables every chain, built-in or user-defined, belongs to exactly one table.
When the same name appears in two tables, those are two separate chains with
their own rules and nothing linking them. There's no single chain holding rules
from different tables.

kube-proxy's `KUBE-SERVICES` shows this. It exists twice on every node:

```text
*nat
:KUBE-SERVICES - [0:0]
-A PREROUTING -j KUBE-SERVICES
-A OUTPUT     -j KUBE-SERVICES
-A KUBE-SERVICES -d 10.107.53.29/32 -p tcp --dport 80 -j KUBE-SVC-VPOZHHPUPUFUZ4NW     ← DNAT path
-A KUBE-SERVICES -m addrtype --dst-type LOCAL -j KUBE-NODEPORTS
COMMIT

*filter
:KUBE-SERVICES - [0:0]
-A FORWARD -m conntrack --ctstate NEW -j KUBE-SERVICES
-A OUTPUT  -m conntrack --ctstate NEW -j KUBE-SERVICES
-A KUBE-SERVICES -d 10.103.0.99/32 -p tcp --dport 80 -j REJECT                         ← no-endpoints REJECT
COMMIT
```

(Trimmed; the two Service rules come from the labs in §3.6.)

- **Each table declares its own chain:** the `:KUBE-SERVICES` line appears once
  under `*nat` and once under `*filter`.
- **Each has its own rules:** the nat one leads to DNAT, and the filter one holds
  the REJECTs for Services with no endpoints.
- **Each is entered from its own table's built-in chains:** nat's from
  `PREROUTING` and `OUTPUT`; filter's from `FORWARD` and `OUTPUT` (new
  connections only).
- **A jump can't cross tables.** `-j KUBE-SVC-…` in nat can only reach a nat
  chain. That's why every per-Service chain (`KUBE-SVC`, `KUBE-SEP`, `KUBE-EXT`,
  `KUBE-SVL`, `KUBE-FW`) exists only in nat. The whole path from `PREROUTING`
  to the DNAT stays in one table.

Trying it on the minikube node, with throwaway chains (deleted afterwards):

```text
# a chain in nat, jumped to from filter
$ iptables -t nat -N DEMO-CHAIN
$ iptables -t filter -A FORWARD -j DEMO-CHAIN
iptables v1.8.7 (nf_tables): Chain 'DEMO-CHAIN' does not exist

# DNAT in a filter chain
$ iptables -t filter -N DEMO-F
$ iptables -t filter -A DEMO-F -p tcp -j DNAT --to-destination 10.0.0.1:80
iptables v1.8.7 (nf_tables):  RULE_APPEND failed (Invalid argument): rule in chain DEMO-F

# the same name in both tables: two chains, two sets of rules
$ iptables -t filter -N DEMO-CHAIN
$ iptables -t nat    -A DEMO-CHAIN -j RETURN
$ iptables -t filter -A DEMO-CHAIN -j ACCEPT
$ iptables-save -t nat    | grep DEMO-CHAIN
:DEMO-CHAIN - [0:0]
-A DEMO-CHAIN -j RETURN
$ iptables-save -t filter | grep DEMO-CHAIN
:DEMO-CHAIN - [0:0]
-A DEMO-CHAIN -j ACCEPT
```

##### Why tables keep their chains apart

Tables represent different jobs at different hook points in the Netfilter
pipeline. The nat table works with connection tracking and only sees the
**first packet** of a new connection: conntrack applies the same translation to
every later packet. The filter table sees **every packet**. The rules each table
allows follow from that: `DNAT`, `SNAT` and `MASQUERADE` only make sense where
the first packet of a connection decides its translation, so the kernel accepts
them only in nat. That's the `Invalid argument` above.

In iptables (and in the `iptables-nft` front end used here) a table is a closed
set: a jump names a chain inside the same table, and the rule is checked against
that table's type. So a user-defined chain inherits its table's capabilities,
and can't be shared with another table.

##### nftables: tables are namespaces, and the base chain sets the type

nftables works the same way at the bottom: chains belong to a table. The
difference is that a table there is just a namespace you create, not a fixed
type like nat or filter. You create the table first and then bind every chain
to it by name:

```bash
nft add table inet my_table
nft add chain inet my_table my_custom_chain                    # regular chain: no hook, only reachable by jump/goto
nft add chain inet my_table my_input '{ type filter hook input priority 0; }'    # base chain
```

The type moves from the table to the **base chain**. A base chain declares its
own `type` (filter, nat, route), `hook` and `priority`. So one table can hold nat
and filter base chains side by side, and two chains in one table can't share a
name.

That's what kube-proxy's nftables mode does. Run on the same minikube cluster
with `mode: "nftables"` in the `kube-proxy` ConfigMap (switched back to iptables
afterwards), `nft list table ip kube-proxy` held these base chains:

```text
table ip kube-proxy {
	chain filter-prerouting-pre-dnat {
		type filter hook prerouting priority dstnat - 10; policy accept;
		ct state new jump firewall-check
	}
	chain filter-output-pre-dnat {
		type filter hook output priority -110; policy accept;
		ct state new jump firewall-check
	}
	chain filter-input {
		type filter hook input priority filter; policy accept;
		ct state new jump nodeport-endpoints-check
		ct state new jump service-endpoints-check
	}
	chain filter-forward {
		type filter hook forward priority filter; policy accept;
		ct state new jump service-endpoints-check
		ct state new jump cluster-ips-check
	}
	chain filter-output {
		type filter hook output priority filter; policy accept;
		ct state new jump service-endpoints-check
		ct state new jump cluster-ips-check
	}
	chain nat-prerouting {
		type nat hook prerouting priority dstnat; policy accept;
		jump services
	}
	chain nat-output {
		type nat hook output priority -100; policy accept;
		jump services
	}
	chain nat-postrouting {
		type nat hook postrouting priority srcnat; policy accept;
		jump masquerading
	}
	...
}
```

Five filter base chains and three nat base chains, all in one table. The two
jobs that need two `KUBE-SERVICES` chains in iptables get two differently named
chains here:

```text
	chain services {                                   ← the nat KUBE-SERVICES: find the Service, go to its DNAT chain
		ip daddr . meta l4proto . th dport vmap @service-ips
		ip daddr @nodeport-ips meta l4proto . th dport vmap @service-nodeports
	}
	chain service-endpoints-check {                    ← the filter KUBE-SERVICES: reject Services with no endpoints
		ip daddr . meta l4proto . th dport vmap @no-endpoint-services
	}
	chain reject-chain {
		comment "helper for @no-endpoint-services / @no-endpoint-nodeports"
		reject
	}
```

Side by side:

| Job | iptables mode | nftables mode |
|---|---|---|
| Find the Service, then DNAT | nat `KUBE-SERVICES`, from nat `PREROUTING`/`OUTPUT` | `services`, from `nat-prerouting`/`nat-output` |
| Reject a Service with no endpoints | filter `KUBE-SERVICES`, from filter `FORWARD`/`OUTPUT` | `service-endpoints-check`, from `filter-forward`/`filter-output`/`filter-input` |
| Reject a node port with no endpoints | filter `KUBE-EXTERNAL-SERVICES` | `nodeport-endpoints-check`, from `filter-input` |
| Masquerade | nat `KUBE-POSTROUTING` | `masquerading`, from `nat-postrouting` |
| Tables used | `nat`, `filter` (and `mangle` canaries), shared with everyone else's rules | one table, `ip kube-proxy` (plus `ip6 kube-proxy`), its own |

The node's table list in nftables mode shows both worlds. The `ip nat` and
`ip filter` tables belong to `iptables-nft` (Calico and Docker), and kube-proxy
has its own:

```text
table ip nat
table ip filter
table ip6 nat
table ip6 filter
table ip mangle
table ip6 mangle
table ip raw
table arp calico-arp
table ip kube-proxy
table ip6 kube-proxy
```

##### What nftables checks instead

Since one table mixes nat and filter base chains, nftables can't check a rule
against "the table". It checks each rule against the **base chains that can
reach it**. Tested in throwaway tables from the kube-proxy Pod (the node has no
`nft` binary; the Pod uses the node's network namespace):

```text
# a jump into another table: refused, as in iptables
$ nft add table ip demo_a; nft add chain ip demo_a target
$ nft add table ip demo_b; nft add chain ip demo_b base '{ type filter hook forward priority 10; }'
$ nft add rule ip demo_b base jump target
Error: Could not process rule: No such file or directory

# dnat in a filter base chain: refused
$ nft add rule ip demo_b base tcp dport 80 dnat to 10.0.0.1
Error: Could not process rule: Operation not supported

# a regular chain that does dnat, reached from a nat base chain: accepted
$ nft add rule ip demo_a target tcp dport 9999 dnat to 10.0.0.1:80
$ nft add chain ip demo_a pre '{ type nat hook prerouting priority dstnat; }'
$ nft add rule ip demo_a pre jump target

# the same dnat chain, reached from a filter base chain in the SAME table: refused
$ nft add chain ip demo_a forward_f '{ type filter hook forward priority 10; }'
$ nft add rule ip demo_a forward_f jump target
Error: Could not process rule: Operation not supported

# a regular chain with only `accept`, reached from both: accepted
$ nft add chain ip demo_a other; nft add rule ip demo_a other tcp dport 22 accept
$ nft add rule ip demo_a forward_f jump other
$ nft add rule ip demo_a pre jump other
```

The table at the end:

```text
table ip demo_a {
	chain target {
		tcp dport 9999 dnat to 10.0.0.1:80
	}
	chain pre {
		type nat hook prerouting priority dstnat; policy accept;
		jump target
		jump other
	}
	chain forward_f {
		type filter hook forward priority filter + 10; policy accept;
		jump other
	}
	chain other {
		tcp dport 22 accept
	}
}
```

**In iptables a chain gets its abilities from its table. In nftables a regular
chain gets them from the base chains that jump to it.**

In detail:

- **Jumps still stay inside one table** in both systems.
- **iptables:** a chain gets its abilities from its table, so it can never be
  shared between nat and filter.
- **nftables:** a regular chain gets its abilities from the base chains that
  jump to it. `other` is reached from a nat and a filter base chain, because
  `accept` is valid in both. `target` can only be reached from nat ones, because
  `dnat` isn't valid in a filter chain.

kube-proxy's nftables mode keeps its nat and filter paths separate anyway: the
`dnat` rules sit in `endpoint-*` chains reached only from `services`, which is
reached only from the nat base chains. The rest of its nftables layout (maps,
`numgen`, chain names) is in [nftables.md §10](nftables.md).

#### If chains attach to hooks, what are tables for? (lab)

Only **base chains** attach to hooks. A table is the container that holds
chains, and every chain belongs to exactly one table. A table can hold any
number of chains on any hooks of its family, and one hook can have base chains
from any number of tables:

```text
                hook prerouting     hook input        hook forward      (no hook)
                ───────────────     ──────────        ────────────      ─────────
table inet a    chain pre (-100)    chain inbound     chain fwd (0)     chain helper  ← regular chain,
                                    (10)                                                 reached only by
                                                                                         jump from a's chains
table inet b                        chain inbound
                                    (-10)
table ip nat    chain PREROUTING                                                       (iptables-nft)
                (dstnat)

on hook input, packets run:  b/inbound (-10)  →  a/inbound (10)       ← ordered by priority, not by table
```

In the kernel, `struct nft_table` (`include/net/netfilter/nf_tables.h`) holds
lists of `chains`, `sets`, `objects` and `flowtables`, one `family` and its
`flags`. It has no hook field.

**What a table controls** (tested with nftables in a throwaway Alpine
container, except Ownership):

| What it controls | Lab result |
|---|---|
| **Family** | An `ip` table refused an `ip6 saddr` rule ("conflicting protocols"). An `inet` table accepted it. Every chain in the table gets the table's family. |
| **Name scope** | Tables `a` and `b` both had a base chain called `inbound` on the same hook, with no conflict. |
| **Jump scope** | `jump` from table `a` to a chain in table `b` failed with "No such file or directory". You can only jump within a table. |
| **Set scope** | `a` couldn't use `@blocked` from `b`. nft even suggested "did you mean set 'blocked' in table inet 'b'?". Sets and maps are local to the table. |
| **On/off switch** | `flags dormant` on table `b` unregistered all its hooks, and a ping that `b` dropped went through. Its rules stayed in place, just inactive. |
| **Lifetime** | `delete table inet b` removed its 2 chains and its set in one step. |
| **Ownership** (not tested) | `NFT_TABLE_F_OWNER` means the table is removed when the process that created it exits. Some tools, such as firewalld, use this so they don't leave rules behind. |

**Two tables on the same hook.** Each base chain is its own hook registration,
so the order comes from chain priority, not from the table:

- `b` (priority −10) dropped the ping. `a`'s counter didn't move: it never saw
  the packet.
- With `b` accepting and `a` dropping, the ping was still dropped. `accept`
  only ends the current base chain, and the packet goes on to the next one on
  the hook, in any table. A `drop` anywhere is final.

That's why each tool keeps its own table (kube-proxy's `table ip kube-proxy`,
Docker, firewalld, Cilium) without touching anyone else's rules, while every
tool's chains still see every packet.

**In iptables the tables are fixed.** Each table is a kernel module with fixed
hooks and one priority. For example, `iptable_filter.c` defines `filter` with
`valid_hooks` and `priority = NF_IP_PRI_FILTER`. Its built-in chains are just
those hook registrations. You can't create a table or choose its priority.
That's why an iptables chain gets its abilities from its table. With
iptables-nft, the classic tables show up as ordinary nft tables:

```text
table ip filter { … type filter hook input priority filter; … }
table ip nat    { … type nat hook output priority dstnat; … }
```

**Tables in iptables vs nftables.** Both are front ends to the same netfilter
hooks, and in both every chain belongs to one table and jumps stay inside it.
What a table means differs:

| | iptables | nftables |
|---|---|---|
| Tables | fixed: `filter`, `nat`, `mangle`, `raw`, `security` | you create your own, with any name: `table inet a` |
| What a table decides | **what its chains can do** (only `nat` can DNAT) and which hooks they're on | family, name scope, jump and set scope, on/off, lifetime |
| What a chain's job depends on | its table | the chain's own `type filter/nat/route` and `hook` |
| Base chains | built in: `INPUT`, `PREROUTING`, … | you declare them: `type filter hook input priority 10` |

**Do tables have fixed chains? In iptables, yes.** Each table has a fixed set of
built-in chains, one per hook it registers, named after the hook, and the set
differs per table. In the kernel source each table module lists its hooks in
`valid_hooks` (`net/ipv4/netfilter/iptable_*.c`):

| Table | PREROUTING | INPUT | FORWARD | OUTPUT | POSTROUTING | Its job |
|---|:-:|:-:|:-:|:-:|:-:|---|
| `raw` | ✓ | | | ✓ | | before conntrack (e.g. `NOTRACK`) |
| `mangle` | ✓ | ✓ | ✓ | ✓ | ✓ | change packets, set marks |
| `nat` | ✓ | ✓ | | ✓ | ✓ | DNAT / SNAT (only the first packet of a connection) |
| `filter` | | ✓ | ✓ | ✓ | | accept / drop |
| `security` | | ✓ | ✓ | ✓ | | SELinux labels |

`filter` has no `PREROUTING`, and `nat` has no `FORWARD`.

About the built-in chains:

- **Each one is attached to a hook, but it isn't the hook.** A hook is a point
  in the kernel's code (`NF_INET_LOCAL_IN`). A built-in chain is one table's list
  of rules for that hook. When the table loads, `xt_hook_ops_alloc`
  (`net/netfilter/x_tables.c`) registers `ipt_do_table` at each hook in
  `valid_hooks`, with the table's priority. At the hook, `ipt_do_table` starts at
  that hook's built-in chain (`private->hook_entry[hook]`). The chain is named
  after the hook, which is why the two are easy to confuse.
- **One hook can run several built-in chains**, one per table that uses it, in
  priority order. The full map is below.
- **You can't create, delete or rename them.** `iptables -t filter -N PREROUTING`
  just makes an ordinary custom chain with that name, which no hook ever calls.
- **Only built-in chains have a policy** (`-P INPUT DROP`). A custom chain just
  returns to its caller when it reaches the end.
- **They're the only way into the table.** That's why Docker has to put its jump
  in `PREROUTING` (see below).

**Every hook, and which tables run there, in order** (from the kernel source:
priorities in `include/uapi/linux/netfilter_ipv4.h`, conntrack's hooks in
`net/netfilter/nf_conntrack_proto.c`, nat's in `net/netfilter/nf_nat_proto.c`).
Each table that uses a hook has its own built-in chain there, and lower
priorities run first:

```text
        packet arrives                                               local process sends
               │                                                              │
               ▼                                                              ▼
  ┌─ PREROUTING ─────────────┐                                        routing decision
  │ -300  raw                │                                                │
  │ -200  conntrack          │                                                ▼
  │ -150  mangle             │                                  ┌─ OUTPUT ─────────────────┐
  │ -100  nat (DNAT)         │                                  │ -300  raw                │
  └────────────┬─────────────┘                                  │ -200  conntrack          │
               ▼                                                │ -150  mangle             │
         routing decision ── for this host ──┐                  │ -100  nat (DNAT)         │
               │                             ▼                  │    0  filter             │
               │                 ┌─ INPUT ──────────────────┐   │   50  security           │
               │                 │ -150  mangle             │   └─────────────┬────────────┘
               │                 │    0  filter             │                 │
               │                 │   50  security           │                 │
               │                 │  100  nat (SNAT)         │                 │
               │                 │  max  conntrack confirm  │                 │
               │                 └───────────┬──────────────┘                 │
               │                             ▼                                │
               │                       local process                          │
               ▼                                                              │
  ┌─ FORWARD ────────────────┐                                                │
  │ -150  mangle             │                                                │
  │    0  filter             │                                                │
  │   50  security           │                                                │
  └────────────┬─────────────┘                                                │
               │                                                              │
               └───────────────────►┌─ POSTROUTING ────────────┐◄─────────────┘
                                    │ -150  mangle             │
                                    │  100  nat (SNAT,         │
                                    │            MASQUERADE)   │
                                    │  max  conntrack confirm  │
                                    └────────────┬─────────────┘
                                                 ▼
                                           packet leaves
```

The order is the same at every hook: **raw → conntrack → mangle → nat
(destination) → filter → security → nat (source) → conntrack confirm**. Why
each sits where it does:

- **`raw` runs before conntrack.** That's why `NOTRACK` (skip connection
  tracking) can only go in `raw`: later tables are too late.
- **conntrack runs before `mangle`, `nat` and `filter`**, so they can all match
  connection state (`-m conntrack --ctstate ESTABLISHED`).
- **`nat` is at −100 on the destination hooks and +100 on the source hooks.**
  DNAT runs before `filter`, so filter rules see the real destination (the Pod,
  not the Service IP). SNAT runs after `filter`, so filter rules see the real
  source.
- **`nat` runs only for a connection's first packet.** Later packets are
  translated from the conntrack entry, which is confirmed (written to the table)
  last, at `max`.
- **nftables uses the same numbers.** `priority raw` = −300, `mangle` = −150,
  `dstnat` = −100, `filter` = 0, `security` = 50, `srcnat` = 100. That's why
  the earlier lab printed `priority filter + 10` for priority 10. An nft base
  chain at a given priority runs between the iptables tables around it.

Not verified on a running kernel: `nft list hooks`, which prints this list, gave
no output on Docker Desktop's kernel.

**Where the order comes from: priority numbers.** There's no rule like "mangle
comes after raw". Each registration carries a number, the list is sorted by it,
and mangle ends up after raw only because −150 > −300
(`include/uapi/linux/netfilter_ipv4.h`):

```c
enum nf_ip_hook_priorities {
	NF_IP_PRI_FIRST = INT_MIN,
	NF_IP_PRI_RAW_BEFORE_DEFRAG = -450,
	NF_IP_PRI_CONNTRACK_DEFRAG = -400,
	NF_IP_PRI_RAW = -300,
	NF_IP_PRI_SELINUX_FIRST = -225,
	NF_IP_PRI_CONNTRACK = -200,
	NF_IP_PRI_MANGLE = -150,           // ← mangle
	NF_IP_PRI_NAT_DST = -100,
	NF_IP_PRI_FILTER = 0,
	NF_IP_PRI_SECURITY = 50,
	NF_IP_PRI_NAT_SRC = 100,
	NF_IP_PRI_SELINUX_LAST = 225,
	NF_IP_PRI_CONNTRACK_HELPER = 300,
	NF_IP_PRI_CONNTRACK_CONFIRM = INT_MAX,
	NF_IP_PRI_LAST = INT_MAX,
};
```

- **A table has one number for all its points.** It's set on the table template
  (`packet_mangler.priority = NF_IP_PRI_MANGLE`) and copied into each
  registration by `xt_hook_ops_alloc` (`ops[i].priority = table->priority`). So
  each of mangle's 5 callbacks carries −150, at whichever point it sits. The
  same holds for raw (−300 at both of its points), filter (0) and security (50).
- **nat is the exception.** Its template has no priority, and each of its 4
  registrations sets its own: −100 (`NF_IP_PRI_NAT_DST`) at PREROUTING and
  OUTPUT, where it changes the destination, and +100 (`NF_IP_PRI_NAT_SRC`) at
  INPUT and POSTROUTING, where it changes the source.
- **The order is worked out at registration**, by comparing numbers in
  `nf_hook_entries_grow` (`net/netfilter/core.c`). The new entry goes before the
  first existing entry whose number is equal or larger:

  ```c
  if (inserted || reg->priority > orig_ops[i]->priority) {
  	/* keep the existing entry here, look further */
  } else {
  	/* put the new one here */
  }
  ```

  Whatever order the tables load in, PREROUTING ends up as
  −400 defrag < −300 raw < −200 conntrack < −150 mangle < −100 nat.
- **Change the number, and the order changes.** With the module parameter
  `raw_before_defrag=1`, raw registers at −450 (`NF_IP_PRI_RAW_BEFORE_DEFRAG`)
  and sees fragments before defrag reassembles them.
- **Anything else can slot in between.** An nftables chain at `priority -175`
  lands between conntrack (−200) and mangle (−150). nft's priority names are
  just these numbers: `raw` = −300, `mangle` = −150, `dstnat` = −100, `filter` = 0,
  `security` = 50, `srcnat` = 100.
- **Packets never read the numbers.** Once sorted, a packet just walks
  `hooks[0]`, `hooks[1]`, … in array order. Each number stays in the list's
  trailer (`orig_ops[i]->priority`), read again only when another callback
  registers.

**Why nat has two numbers.** Each of the other tables does the same job at
every point, so one position works everywhere:

- `raw` always runs before conntrack (−300).
- `mangle` always runs early (−150).
- `filter` always runs at 0.

`nat` does two opposite jobs, and each must happen on a different side of
`filter` and of routing:

| | Changes | Must run | So the priority is |
|---|---|---|---|
| **DNAT** (PREROUTING, OUTPUT) | the destination | **before routing**, so the packet is routed to the new address; and **before `filter`**, so filter rules see the real destination (the Pod, not the Service IP) | −100 (`NF_IP_PRI_NAT_DST`): after conntrack (−200) and mangle (−150), before filter (0) |
| **SNAT / MASQUERADE** (POSTROUTING, INPUT) | the source | **after routing**, because MASQUERADE needs to know the outgoing interface; and **after `filter`**, so filter rules see the original source | +100 (`NF_IP_PRI_NAT_SRC`): after filter (0) and security (50) |

A single number can't be both before and after `filter`, so the nat table's
template has no priority and each of its 4 registrations sets its own
(`iptable_nat.c`, `nf_nat_ipv4_ops[]`).

Both numbers are above conntrack's −200, because NAT keeps its decision in the
conntrack entry, which must exist first. The kernel enforces this for nftables
nat chains (`nf_tables_api.c`: `type->type == NFT_CHAIN_T_NAT && hook->priority
<= NF_IP_PRI_CONNTRACK` → `-EOPNOTSUPP`). Lab (throwaway Alpine container,
nftables 1.0.9):

```text
$ nft add chain ip t n1 '{ type nat hook prerouting priority -201; }'
Error: Chains of type "nat" must have a priority value above -200
$ nft add chain ip t n2 '{ type nat hook prerouting priority -199; }'
  -199: accepted
```

**nftables uses the same numbers.** They belong to the kernel's hook lists, not
to iptables: an nftables base chain is just another registration in the same
list. IPv6 has its own enum (`nf_ip6_hook_priorities`) with the same values. The
`nft` tool gives the common numbers names. Chains created with plain numbers
were printed back by name:

```text
table ip t {
	type filter hook prerouting  priority raw;        ← -300
	type filter hook prerouting  priority mangle;     ← -150
	type nat    hook prerouting  priority dstnat;     ← -100
	type filter hook input       priority filter;     ← 0
	type filter hook input       priority security;   ← 50
	type nat    hook postrouting priority srcnat;     ← 100
	type filter hook prerouting  priority -175;       ← no name: printed as the number
```

Three differences from iptables:

**1. In nftables you choose the number for every chain, for nat too.** iptables'
nat table always uses −100 and +100. In nftables you write the priority
yourself, usually `dstnat` on prerouting and output, and `srcnat` on postrouting
and input, following the same reasoning. Other values are allowed, as long as a
nat chain stays above −200. You can also write `priority dstnat - 5` or
`filter + 10` to land just before or after a standard slot.

**2. The names are tied to hooks.** `nft` refuses a name that doesn't make sense
where you use it:

```text
$ nft add chain ip t x '{ type filter hook input priority dstnat; }'
Error: invalid priority expression value in this context.        ← dstnat isn't for input
$ nft add chain ip t y '{ type nat hook prerouting priority srcnat; }'
Error: invalid priority expression value in this context.        ← srcnat isn't for prerouting
```

**3. The bridge family uses different numbers for the same names.** Bridge hooks
have their own scale:

```text
table bridge br {
	type filter hook prerouting  priority dstnat;     ← -300 in bridge (not -100)
	type filter hook forward     priority filter;     ← -200 in bridge (not 0)
	type filter hook postrouting priority srcnat;     ← 300 in bridge (not 100)
```

So a name like `filter` means 0 in `ip`, `ip6` and `inet`, but −200 in `bridge`.
When in doubt, use the number; `nft list` shows which name it matches.

| Name | `ip` / `ip6` / `inet` | `bridge` |
|---|---|---|
| `raw` | −300 | — |
| `mangle` | −150 | — |
| `dstnat` | −100 | −300 |
| `filter` | 0 | −200 |
| `security` | 50 | — |
| `srcnat` | 100 | 300 |

**What a hook holds.** Each hook has a list of entries (`nf_hook_entries`),
sorted by priority. Each entry is a function. With iptables, every table that
uses the hook adds one entry. Other parts of the kernel add entries too:

```text
hook PREROUTING: entries, sorted by priority
  -400  defrag                  (not a table: reassembles fragments for conntrack)
  -300  ipt_do_table(raw)       → runs raw's PREROUTING chain
  -200  conntrack               (not a table)
  -150  ipt_do_table(mangle)    → runs mangle's PREROUTING chain
  -100  nat core                → runs nat's PREROUTING chain, first packet only
```

`nat` is the exception in this list: `iptable_nat.c` hands its entries to the
NAT core (`nf_nat_ipv4_register_fn`), which runs the `nat` chain only while the
connection has no NAT decision yet (`!nf_nat_initialized(ct, …)` in
`nf_nat_core.c`).

**"Hook" means several things in the code.** In programming, to *hook* something
means to attach a function (a callback) to a place where existing code will call
it. Netfilter uses "hook" for the place, the function, and the things in
between:

| Name in the code | What it really is | Read it as |
|---|---|---|
| `NF_INET_PRE_ROUTING`, `.hooknum`, `hooks_ipv4[0]` | a **place** in the packet path | **hook point** |
| `nf_hookfn`, `.hook` | a function called at that place | **hook function** (a callback) |
| `struct nf_hook_ops` | a **request to attach**: function + place + priority + `priv` | **registration** |
| `struct nf_hook_entry`, `hooks[]` | the attached functions stored at one place, in order | **callbacks at this point** |

The structs, with the clearer names as comments:

```c
struct netns_nf {
	struct nf_hook_entries *hooks_ipv4[NF_INET_NUMHOOKS];
	//                       ^^^^^^^^^^ 5 hook POINTS: PREROUTING … POSTROUTING
};

struct nf_hook_entries {               // everything attached at ONE hook point
	u16                  num_hook_entries;
	struct nf_hook_entry hooks[];      // the CALLBACKS attached here, in run order
	//                   ^^^^^ = "the hooked-in functions", not "the hook points"
};

struct nf_hook_entry {
	nf_hookfn *hook;                   // the callback function
	void      *priv;                   // its data (&mangle, &raw, …)
};

struct nf_hook_ops {                   // a REGISTRATION
	nf_hookfn   *hook;                 // which callback
	unsigned int hooknum;              // at which hook POINT (0–4)
	int          priority;             // where in that point's list
	void        *priv;
};
```

So `net->nf.hooks_ipv4[NF_INET_PRE_ROUTING]->hooks[3].hook` reads as: at the
PREROUTING **point**, the 4th attached **callback**'s **function**
(`iptable_mangle_hook` on a node with raw and mangle loaded).

The kernel's own wording mixes the meanings too: "the PREROUTING hook" (a
point), "register a hook" (`nf_register_net_hook`, attach a callback),
`iptable_mangle_hook` (a callback function). When a sentence says "hook", ask
which is meant: **where** (a point, 0–4) or **what runs there** (a callback in
`hooks[]`).

**A hook stores neither tables, nor chains, nor rules.** It stores a sorted list
of pairs: a function to call, and a pointer to pass it. For an iptables table,
that pointer points at the table. In the kernel source:

Each network namespace has one array per family, with one slot per hook. The
complete struct (`include/net/netns/netfilter.h`):

```c
struct netns_nf {
#if defined CONFIG_PROC_FS
	struct proc_dir_entry *proc_netfilter;                          // /proc/net/netfilter
#endif
	const struct nf_logger __rcu *nf_loggers[NFPROTO_NUMPROTO];     // the LOG/NFLOG backend per family
#ifdef CONFIG_SYSCTL
	struct ctl_table_header *nf_log_dir_header;                     // net.netfilter.nf_log.* sysctls
#ifdef CONFIG_LWTUNNEL
	struct ctl_table_header *nf_lwtnl_dir_header;
#endif
#endif
	struct nf_hook_entries __rcu *hooks_ipv4[NF_INET_NUMHOOKS];     // array of 5 pointers: PRE_ROUTING … POST_ROUTING
	struct nf_hook_entries __rcu *hooks_ipv6[NF_INET_NUMHOOKS];     // array of 5 pointers, the same hooks for IPv6
#ifdef CONFIG_NETFILTER_FAMILY_ARP
	struct nf_hook_entries __rcu *hooks_arp[NF_ARP_NUMHOOKS];       // 3: NF_ARP_IN, NF_ARP_OUT, NF_ARP_FORWARD
#endif
#ifdef CONFIG_NETFILTER_FAMILY_BRIDGE
	struct nf_hook_entries __rcu *hooks_bridge[NF_INET_NUMHOOKS];   // 5: the bridge's own PRE_ROUTING … POST_ROUTING
#endif
#if IS_ENABLED(CONFIG_NF_DEFRAG_IPV4)
	unsigned int defrag_ipv4_users;                                 // how many users need IPv4 defrag (conntrack, …)
#endif
#if IS_ENABLED(CONFIG_NF_DEFRAG_IPV6)
	unsigned int defrag_ipv6_users;
#endif
};
```

The `#if` lines mean some fields only exist when the kernel is built with that
feature. The `netdev` family's `ingress` and `egress` hooks aren't here: they're
per network device, so their lists hang off each `struct net_device`.

It's embedded in each namespace as `struct net { … struct netns_nf nf; … }`
(`include/net/net_namespace.h:147`). That's why the code reads
`net->nf.hooks_ipv4[hook]`, and why every namespace has its own hook lists.

The array size and each hook's index come from an enum
(`include/uapi/linux/netfilter.h`):

```c
enum nf_inet_hooks {
	NF_INET_PRE_ROUTING,    // 0 → hooks_ipv4[0]
	NF_INET_LOCAL_IN,       // 1 → hooks_ipv4[1]
	NF_INET_FORWARD,        // 2
	NF_INET_LOCAL_OUT,      // 3
	NF_INET_POST_ROUTING,   // 4
	NF_INET_NUMHOOKS,       // 5 = the array size
	NF_INET_INGRESS = NF_INET_NUMHOOKS,   // the inet family's ingress hook: not one of the 5 slots
};
```

Each slot is a pointer, `NULL` until something registers on that hook. When
something does, it points to a separately allocated `struct nf_hook_entries`
(below). The pointer is swapped in one step, `rcu_assign_pointer`, whenever the
list changes. `__rcu` marks it as a pointer that must be read with
`rcu_dereference()` and written with `rcu_assign_pointer()`.

Each slot points at a list of entries, and each entry is two fields
(`include/linux/netfilter.h`):

```c
struct nf_hook_entries {
	u16				num_hook_entries;
	struct nf_hook_entry		hooks[];     // the entries, already sorted; a "flexible array":
						     // its length is set when the block is allocated
	/* trailer: orig_ops[] pointers, then an rcu_head */
};

struct nf_hook_entry {
	nf_hookfn			*hook;       // function to call
	void				*priv;       // pointer passed to it
};
```

All of it together, for one namespace with `raw` and `mangle` loaded and nothing
on FORWARD:

```text
struct net
└─ nf  (struct netns_nf)
   ├─ hooks_ipv4[0] ──► ┌─ nf_hook_entries ─────────────────────────────┐   PRE_ROUTING
   │                    │ num_hook_entries = 2                          │
   │                    │ hooks[0] { ipt_do_table,        &raw    }     │   -300 runs first
   │                    │ hooks[1] { iptable_mangle_hook, &mangle }     │   -150
   │                    │ orig_ops[0], orig_ops[1]   (the trailer)      │
   │                    │ rcu_head                                      │
   │                    └───────────────────────────────────────────────┘
   ├─ hooks_ipv4[1] ──► ┌─ nf_hook_entries … ┐                               LOCAL_IN
   ├─ hooks_ipv4[2] ──► NULL                                                 FORWARD (nothing registered)
   ├─ hooks_ipv4[3] ──► ┌─ nf_hook_entries … ┐                               LOCAL_OUT
   ├─ hooks_ipv4[4] ──► ┌─ nf_hook_entries … ┐                               POST_ROUTING
   └─ hooks_ipv6[0..4] ──► the same, for IPv6
```

- **`hooks[]` is what packets read.** `nf_hook_slow` walks it from 0 upward,
  so array order is run order.
- **The trailer is only for registering and unregistering.** `orig_ops[i]`
  points at the `nf_hook_ops` that created `hooks[i]`, which still holds its
  priority. `nf_hook_entries_grow` reads it to decide where a new entry goes.
- **Each block is allocated in one piece.** Count, entries and trailer sit
  together, sized for exactly `num_hook_entries`. Adding an entry means a new,
  larger block, swapped into the slot. The old one is freed through its
  `rcu_head` once no packet still uses it.

**The real PREROUTING and POSTROUTING blocks** on a typical node: iptables-legacy
with `raw`, `mangle`, `nat` and `filter` loaded, so conntrack and the NAT core are
active too. Every name is from the kernel source; `filter` doesn't appear because
it has no PREROUTING or POSTROUTING chain:

```text
net->nf.hooks_ipv4[0]   (NF_INET_PRE_ROUTING)
  │
  ┌─ struct nf_hook_entries ────────────────────────────────────────────────────────────────────────┐
  │ num_hook_entries = 5                                                                            │
  │                                                                                                 │
  │ hooks[0] { .hook = ipv4_conntrack_defrag,   .priv = NULL    }  -400 NF_IP_PRI_CONNTRACK_DEFRAG  │
  │ hooks[1] { .hook = ipt_do_table,            .priv = &raw    }  -300 NF_IP_PRI_RAW               │
  │ hooks[2] { .hook = ipv4_conntrack_in,       .priv = NULL    }  -200 NF_IP_PRI_CONNTRACK         │
  │ hooks[3] { .hook = iptable_mangle_hook,     .priv = &mangle }  -150 NF_IP_PRI_MANGLE            │
  │ hooks[4] { .hook = nf_nat_ipv4_pre_routing, .priv = lpriv   }  -100 NF_IP_PRI_NAT_DST           │
  │                                                                                                 │
  │ trailer:                                                                                        │
  │ orig_ops[0] ─► &ipv4_defrag_ops[0]     static, nf_defrag_ipv4.c                                 │
  │ orig_ops[1] ─► &raw->ops[0]            copy made by ipt_register_table                          │
  │ orig_ops[2] ─► &ipv4_conntrack_ops[0]  static, nf_conntrack_proto.c                             │
  │ orig_ops[3] ─► &mangle->ops[0]         copy made by ipt_register_table                          │
  │ orig_ops[4] ─► &nat_hook_ops[0]        the NAT core's copy (nf_nat_proto.c)                     │
  │ rcu_head                                                                                        │
  └┬────────────────────────────────────────────────────────────────────────────────────────────────┘
   │ hooks[4].priv = lpriv
   ▼
  ┌─ struct nf_nat_lookup_hook_priv  (lpriv, the NAT core's) ───────────────────────────────────────┐
  │ .entries ─► struct nf_hook_entries                                                              │
  │   num_hook_entries = 1                                                                          │
  │   hooks[0] { .hook = ipt_do_table, .priv = &nat }  -100 NF_IP_PRI_NAT_DST                       │
  │   trailer: orig_ops[0] ─► iptable_nat's ops[0]  (PREROUTING)                                    │
  └─────────────────────────────────────────────────────────────────────────────────────────────────┘

net->nf.hooks_ipv4[4]   (NF_INET_POST_ROUTING)
  │
  ┌─ struct nf_hook_entries ────────────────────────────────────────────────────────────────────────┐
  │ num_hook_entries = 3                                                                            │
  │                                                                                                 │
  │ hooks[0] { .hook = iptable_mangle_hook,     .priv = &mangle }  -150 NF_IP_PRI_MANGLE            │
  │ hooks[1] { .hook = nf_nat_ipv4_out,         .priv = lpriv   }   100 NF_IP_PRI_NAT_SRC           │
  │ hooks[2] { .hook = nf_confirm,              .priv = NULL    }   max NF_IP_PRI_CONNTRACK_CONFIRM │
  │                                                                                                 │
  │ trailer:                                                                                        │
  │ orig_ops[0] ─► &mangle->ops[4]         mangle's 5th op = POSTROUTING                            │
  │ orig_ops[1] ─► &nat_hook_ops[1]        the NAT core's POSTROUTING op                            │
  │ orig_ops[2] ─► &ipv4_conntrack_ops[2]  static, nf_conntrack_proto.c                             │
  │ rcu_head                                                                                        │
  └┬────────────────────────────────────────────────────────────────────────────────────────────────┘
   │ hooks[1].priv = lpriv
   ▼
  ┌─ struct nf_nat_lookup_hook_priv  (lpriv, the NAT core's) ───────────────────────────────────────┐
  │ .entries ─► struct nf_hook_entries                                                              │
  │   num_hook_entries = 1                                                                          │
  │   hooks[0] { .hook = ipt_do_table, .priv = &nat }   100 NF_IP_PRI_NAT_SRC                       │
  │   trailer: orig_ops[0] ─► iptable_nat's ops[1]  (POSTROUTING)                                   │
  └─────────────────────────────────────────────────────────────────────────────────────────────────┘
```

- **`orig_ops[i]` points at the `nf_hook_ops` that created `hooks[i]`.** Some
  are static arrays in the kernel (`ipv4_defrag_ops`, `ipv4_conntrack_ops`).
  Others are per-namespace copies: `ipt_register_table` copies a table's ops
  and sets `.priv`, and keeps them as `new_table->ops`. Each table's ops are in
  hook-number order, so `mangle->ops[0]` is PREROUTING and `mangle->ops[4]` is
  POSTROUTING. `raw` has only PREROUTING and OUTPUT, so `raw->ops[0]` is
  PREROUTING.
- **`lpriv` is the NAT core's, one per NAT hook.** `nf_nat_register_fn`
  allocates a `struct nf_nat_lookup_hook_priv` for each and registers the NAT
  core on the hook. `iptable_nat`'s ops then go into `lpriv->entries`, an
  `nf_hook_entries` of their own. An nftables `type nat` chain would join the
  same inner list.
- **POSTROUTING holds `nf_confirm` last** (`INT_MAX`): the conntrack entry
  is written only once the packet has passed everything else.

Two common variations:

- **With `br_netfilter` loaded** (most CNIs need it), PREROUTING gains
  `{ .hook = ip_sabotage_in, .priv = NULL }` at `NF_IP_PRI_FIRST` (`INT_MIN`).
  It becomes `hooks[0]` and everything moves down one.
- **With iptables-nft** (the default on current distributions), each built-in
  chain is an nftables base chain, so the table entries become
  `{ .hook = nft_do_chain_ipv4, .priv = &chain }`, with the same priorities.
  For example, `hooks[1]` is `{ nft_do_chain_ipv4, &PREROUTING chain of table ip raw }`.
  The nat chains become `{ nft_nat_do_chain, &chain }` inside `lpriv->entries`.
  Defrag, conntrack, the NAT core and `nf_confirm` are the same either way.

Every hook function has the same signature (`include/linux/netfilter.h`):

```c
typedef unsigned int nf_hookfn(void *priv,                         // ← the pointer from the hook entry
                               struct sk_buff *skb,                // the packet
                               const struct nf_hook_state *state); // which hook, in/out device, namespace, …
```

It's `void *priv` rather than a table type because the hook is generic. The same
slot holds entries for things that aren't tables:

- conntrack passes `NULL`;
- the NAT core passes its own lookup structure;
- nftables passes a base chain (`ops->priv = chain` in `nf_tables_api.c`).

Each function knows what its own `priv` points to and casts it
(`const struct xt_table *table = priv;` in `ipt_do_table`).

So the hook entry decides **which table**, through `priv`. The hook being run
decides **which of that table's built-in chains**, through `state->hook`:
`ipt_do_table` starts at `hook_entry[state->hook]` in the table's rules.

**In short: each hook point has its own list of callbacks, run in priority
order.**

```text
hooks_ipv4[PRE_ROUTING] → [ defrag ][ raw ][ conntrack ][ mangle ][ NAT core ]
hooks_ipv4[LOCAL_IN]    → [ mangle ][ filter ][ security ][ NAT core ][ confirm ]
hooks_ipv4[FORWARD]     → [ mangle ][ filter ][ security ]
hooks_ipv4[LOCAL_OUT]   → [ defrag ][ raw ][ conntrack ][ mangle ][ NAT core ][ filter ][ security ]
hooks_ipv4[POST_ROUTING]→ [ mangle ][ NAT core ][ confirm ]
```

Some of these callbacks act on tables. Those are the iptables ones:
`{ipt_do_table, &raw}`, `{iptable_mangle_hook, &mangle}` and so on. Each runs one
chain of its table, the chain for the point it's called from. The others act on
an nftables chain, on their own data, or on nothing (below). The hook list
doesn't care which: it calls them one by one and checks each verdict.

**A callback is not always a table.** Each one is a pair, a **function to run**
and **the data to run it on**:

```c
struct nf_hook_entry {
	nf_hookfn *hook;    // WHAT to run
	void      *priv;    // what to run it ON (may be a table, a chain, other data, or nothing)
};
```

For iptables tables, the function is the generic rule engine and `priv` is the
table, so that kind of callback means "run this table's rules":

```text
{ ipt_do_table,        &raw }      "run the raw table's chain for this point"
{ iptable_mangle_hook, &mangle }   "run the mangle table's chain (and reroute on OUTPUT)"
```

Many callbacks aren't tables at all:

| Callback | `.hook` | `.priv` | What it does |
|---|---|---|---|
| a table (iptables) | `ipt_do_table`, `iptable_mangle_hook` | the **table** | runs that table's chain for this point |
| a base chain (nftables, iptables-nft) | `nft_do_chain_ipv4` | the **chain** | runs that chain's rules |
| defrag | `ipv4_conntrack_defrag` | `NULL` | reassembles fragments |
| conntrack | `ipv4_conntrack_in`, `nf_confirm` | `NULL` | finds or creates the connection, then saves it |
| NAT core | `nf_nat_ipv4_pre_routing`, `nf_nat_ipv4_out` | its own lookup struct (`lpriv`) | runs the nat rules on a connection's first packet, then applies the saved translation |
| br_netfilter | `ip_sabotage_in` | `NULL` | stops bridged packets being processed twice |
| a BPF program | the BPF runner | the program | runs your eBPF code |

`priv` is `NULL` when the function needs no extra data.

So a hook point holds a list of things to run in order, and **a table is just
one kind of thing**. The hook doesn't know or care whether it's calling a table,
a chain, conntrack or a BPF program. It only calls `hook(priv, packet, state)`
and reads the verdict. That's why `priv` is `void *`: it can point to anything.

**Nothing switches on `priv`.** The function pointer already decides what `priv`
is, because whoever registered the callback set both fields together. The whole
dispatch is one indirect call (`include/linux/netfilter.h`):

```c
static inline int
nf_hook_entry_hookfn(const struct nf_hook_entry *entry, struct sk_buff *skb,
		     struct nf_hook_state *state)
{
	return entry->hook(entry->priv, skb, state);   // call the function, hand it its own priv
}
```

Calling `entry->hook` jumps to whichever function was registered, and `priv` is
passed through unopened. Each function was written for one kind of `priv`, and
casts it:

```c
// ip_tables.c: registered with priv = a table
unsigned int ipt_do_table(void *priv, …)
	const struct xt_table *table = priv;                    // "my priv is always a table"

// nft_chain_filter.c: registered with priv = a base chain
static unsigned int nft_do_chain_ipv4(void *priv, …)
	return nft_do_chain(&pkt, priv);                         // "my priv is always a chain"

// nf_nat_core.c: registered with priv = the NAT core's lookup struct
	struct nf_nat_lookup_hook_priv *lpriv = priv;            // "my priv is always lpriv"

// nf_conntrack_proto.c: registered with priv = NULL
static unsigned int ipv4_conntrack_in(void *priv, …)
	return nf_conntrack_in(skb, state);                      // "I don't use priv at all"
```

- **The pairing is fixed at registration.** `ipt_register_table` sets each op's
  `.hook` and `.priv = new_table` together, so an `ipt_do_table` entry can only
  ever carry a table. It's the C version of a method bound to its object (or a
  closure): `table.run(packet)` written as `{ipt_do_table, &table}`.
- **"Where" comes from `state`, not `priv`.** The networking code fills in
  `state->hook` when it enters a hook, e.g. `NF_HOOK(NFPROTO_IPV4,
  NF_INET_PRE_ROUTING, …)` in `ip_rcv`. That's how mangle's 5 identical entries,
  `{iptable_mangle_hook, &mangle}`, each run the right chain:
  `private->hook_entry[state->hook]`.
- **`hook_ops_type` isn't a dispatch tag.** `struct nf_hook_ops` has one
  (`NF_HOOK_OP_UNDEFINED`, `NF_HOOK_OP_NF_TABLES`, `NF_HOOK_OP_BPF`, …), but it's on
  the registration, not on the per-packet entry. It's only used when
  registering (BPF priorities must be unique) and listing hooks.

| Question | Answered by | Set when |
|---|---|---|
| **Which** callbacks run at this point, in what order? | the slot `hooks_ipv4[h]` and its sorted `hooks[]` | registration |
| **What** code runs? | `entry->hook`, an indirect function call | registration |
| **On what** object? | `entry->priv`, which only that function interprets | registration, together with `hook` |
| **Where** is it being called from? | `state->hook` (and `state->in`, `state->out`) | for each packet, by the caller |

**iptables reaches a chain through its table; nftables attaches the chain
directly.** An iptables callback isn't "the table" either: it's "run this
table's chain for this point", which is always exactly one built-in chain. The
table pointer and the calling point together pick it:

```text
iptables:   { iptable_mangle_hook, &mangle }        + state->hook  → mangle.hook_entry[PRE] → the PREROUTING chain
nftables:   { nft_do_chain_ipv4,   &chain "pre" }                  → the chain itself
```

So both systems end up with one callback per chain attached to a hook; only the
addressing differs:

| | iptables | nftables |
|---|---|---|
| What is attached to a hook | a built-in chain | a base chain |
| What `priv` points at | the **table** (the chain is found inside it) | the **chain** directly |
| How the chain is found | `hook_entry[state->hook]`, the table's index | no lookup needed |
| Priority comes from | the table, one number for all its chains (except nat) | each chain |
| Registered | **all of a table's built-in chains at once**, when the table is created in a namespace (`nf_register_net_hooks(net, ops, num_ops)`), and removed together | **one chain at a time**, when it's created or deleted |
| Table's role | container **and** what registers | container only |

- **Why iptables registers through the table:** its chains are fixed and share
  the table's priority, and all of a table's rules live in one block. Replacing
  rules swaps `table->private`. The callbacks hold `&table`, not a pointer into
  the block, so they never need updating.
- **Empty iptables chains still cost a call.** Once mangle exists in a
  namespace, every packet at all five points calls `iptable_mangle_hook`, even
  with all its chains empty (`iptables -F` doesn't unhook them). An nftables
  table only adds callbacks at the hooks its base chains use.
- **iptables-nft shows it's the same thing.** `iptables -t mangle` becomes an nft
  table `ip mangle` with a base chain `PREROUTING` at −150, so the callback
  becomes `{ nft_do_chain_ipv4, &PREROUTING chain }`. The rules and order stay
  the same, and only the pointer moves from the table to the chain.

**How the right chain is picked when the whole table is passed.** The callback
always receives two things together: the table (in `priv`) and the hook number
(in `state->hook`). The table holds an index from hook number to chain. So the
hook number is a key, and the table looks it up.

*Step 1: the caller writes the hook number into `state`.* When the networking
code enters a hook point, `NF_HOOK` fills a `struct nf_hook_state`
(`include/linux/netfilter.h`), which is passed to every callback:

```c
struct nf_hook_state {
	u8 hook;                    // ← which point: 0 = PREROUTING, 1 = INPUT, …
	u8 pf;                      // family (IPv4)
	struct net_device *in;      // incoming interface
	struct net_device *out;     // outgoing interface (once routed)
	struct sock *sk;
	struct net *net;
	int (*okfn)(…);             // what runs after the hook if the packet is accepted
};

// ip_rcv():  NF_HOOK(NFPROTO_IPV4, NF_INET_PRE_ROUTING, net, NULL, skb, dev, NULL, ip_rcv_finish)
//   → nf_hook_state_init(&state, NF_INET_PRE_ROUTING, …)   →   state.hook = 0
```

*Step 2: the callback gets both the table and the number.*

```c
verdict = entry->hook(entry->priv, skb, state);
//        iptable_mangle_hook(&mangle, skb, state)   with state->hook = 0
```

*Step 3: the table's index picks the chain.* `ipt_do_table`
(`net/ipv4/netfilter/ip_tables.c`):

```c
const struct xt_table *table = priv;                    // the mangle table
unsigned int hook = state->hook;                        // 0 (PREROUTING)
…
private    = READ_ONCE(table->private);                 // mangle's rules block + its index
table_base = private->entries;                          // all of mangle's rules, one block
e = get_entry(table_base, private->hook_entry[hook]);   // index[0] → start of the PREROUTING chain
```

`hook_entry[]` is a small array in the table, also indexed by hook number. Its
values are **byte offsets** into the rules block: where each built-in chain's
first rule is. `underflow[]` is the same for each chain's policy rule. Real
values, read from the kernel with `IPT_SO_GET_INFO` (the request `iptables`
itself makes) for mangle with one PREROUTING rule (lab, `iptables-legacy` in a
throwaway Alpine container):

```text
mangle.private                                    (7 entries, 1136 bytes)
  hook_entry[0] =   0   underflow[0] = 200   PREROUTING   ← state->hook = 0 lands here
  hook_entry[1] = 352   underflow[1] = 352   INPUT
  hook_entry[2] = 504   underflow[2] = 504   FORWARD
  hook_entry[3] = 656   underflow[3] = 656   OUTPUT
  hook_entry[4] = 808   underflow[4] = 808   POSTROUTING

  entries[]:   byte 0     [PREROUTING: -p tcp --dport 22 -j MARK]   200 bytes   ← hook_entry[0]
               byte 200   [PREROUTING policy ACCEPT]                152 bytes   ← underflow[0]
               byte 352   [INPUT policy ACCEPT]                                 ← hook_entry[1] = underflow[1]
               byte 504   [FORWARD policy ACCEPT]
               byte 656   [OUTPUT policy ACCEPT]
               byte 808   [POSTROUTING policy ACCEPT]
               byte 960   [ERROR: end of table]                     176 bytes
```

**Two different zeros in `hook_entry[0] = 0`.** The index 0 is the hook number
(`NF_INET_PRE_ROUTING`). The value 0 is a byte offset: chains are laid out in
hook-number order (`ipt_alloc_initial_table`), and mangle's first chain is
PREROUTING, so it starts at byte 0. In a table without PREROUTING, another
chain gets offset 0.

Mangle uses hook 0, so PREROUTING is laid out first and starts at byte 0. Each
empty chain is just its policy rule, 152 bytes long, so the next chain starts
152 bytes later. Empty tables from the same lab: 5 (or 3) policy rules, then the
end marker:

```text
table mangle: 6 entries, 936 bytes
  hook 0 PREROUTING   hook_entry[0] =    0   underflow[0] =    0
  hook 1 INPUT        hook_entry[1] =  152   underflow[1] =  152
  hook 2 FORWARD      hook_entry[2] =  304   underflow[2] =  304
  hook 3 OUTPUT       hook_entry[3] =  456   underflow[3] =  456
  hook 4 POSTROUTING  hook_entry[4] =  608   underflow[4] =  608

table filter: 4 entries, 632 bytes
  hook 1 INPUT        hook_entry[1] =    0   underflow[1] =    0    ← INPUT is first, so IT is at offset 0
  hook 2 FORWARD      hook_entry[2] =  152   underflow[2] =  152
  hook 3 OUTPUT       hook_entry[3] =  304   underflow[3] =  304
```

Comparing empty mangle with the one-rule mangle above: PREROUTING still starts
at 0, but its policy (`underflow[0]`) moved from 0 to 200, and every later chain
shifted by 200.

- **An empty chain is just its policy rule**, 152 bytes on 64-bit
  (`sizeof(struct ipt_standard)`), so `hook_entry[h] = underflow[h]` and each
  chain starts 152 bytes after the previous one.
- **Adding a rule shifts everything after it.** One 200-byte rule in PREROUTING
  moved PREROUTING's policy to byte 200 and every later chain by 200. That's why
  the kernel recomputes the index (`translate_table`) whenever the rules block is
  replaced.
- **Filter's `hook_entry[0]` is never read.** Filter has no PREROUTING callback,
  so no call ever arrives with `state->hook = 0`.

From there `ipt_do_table` runs rules until a verdict: `ACCEPT` or `DROP` ends
this table, `-j CHAIN` jumps to that chain's offset in the same block, and no
match falls through to the chain's policy rule (at `underflow[hook]`). It never
runs into another chain's rules, because each chain ends with its policy.

The same packet, forwarded, later reaches FORWARD. The callback is identical,
but the key isn't:

```text
ip_forward(): NF_HOOK(…, NF_INET_FORWARD, …)  → state->hook = 2
iptable_mangle_hook(&mangle, skb, state) → ipt_do_table → hook_entry[2] = 504 → mangle's FORWARD chain
```

| The callback gets | From | Answers |
|---|---|---|
| `priv` = `&mangle` | the hook entry, fixed at registration | **which table** |
| `state->hook` = 0 | `NF_HOOK(…)` in the networking code, set for each packet | **which point** |
| `hook_entry[state->hook]` | the table's own index | **which chain**, and where it starts |

**The PREROUTING slot, with the names used in the code.** `priv` belongs to
each entry, not to the hook. `nf_hook_slow` passes each entry's own `priv` to
its own function:

```c
verdict = nf_hook_entry_hookfn(&e->hooks[s], skb, state);
```

The diagram below uses C's designated-initializer notation, which is how the
kernel source fills these structs:

```c
static const struct nf_hook_ops ipv4_conntrack_ops[] = {   // nf_conntrack_proto.c
	{
		.hook		= ipv4_conntrack_in,
		.pf		= NFPROTO_IPV4,
		.hooknum	= NF_INET_PRE_ROUTING,
		.priority	= NF_IP_PRI_CONNTRACK,
	},
```

`.hook = ipv4_conntrack_in` means "the field `hook` of this struct holds
`ipv4_conntrack_in`". The diagram uses the same notation, so each entry reads as
the struct it is: `hooks[2] = { .hook = ipv4_conntrack_in, .priv = NULL }`. Note
the two names: `.hooknum` is the hook **point** (`NF_INET_PRE_ROUTING`), and
`.hook` is the hook **function** called there.

```text
net->nf.hooks_ipv4[NF_INET_PRE_ROUTING]  →  struct nf_hook_entries
  num_hook_entries = 5
  hooks[0] = { .hook = ipv4_conntrack_defrag,    .priv = NULL      }   NF_IP_PRI_CONNTRACK_DEFRAG  (-400)   nf_defrag_ipv4.c: ipv4_defrag_ops[]
  hooks[1] = { .hook = ipt_do_table,             .priv = new_table }   NF_IP_PRI_RAW               (-300)   iptable_raw.c: packet_raw
  hooks[2] = { .hook = ipv4_conntrack_in,        .priv = NULL      }   NF_IP_PRI_CONNTRACK         (-200)   nf_conntrack_proto.c: ipv4_conntrack_ops[]
  hooks[3] = { .hook = iptable_mangle_hook,      .priv = new_table }   NF_IP_PRI_MANGLE            (-150)   iptable_mangle.c: packet_mangler
  hooks[4] = { .hook = nf_nat_ipv4_pre_routing,  .priv = priv      }   NF_IP_PRI_NAT_DST           (-100)   nf_nat_proto.c: nf_nat_ipv4_ops[]
                                                          │
          struct nf_nat_lookup_hook_priv ◄────────────────┘          nf_nat_core.c: nat_ops[i].priv = priv
            .entries  →  struct nf_hook_entries
                           hooks[0] = { .hook = ipt_do_table,  .priv = table }   NF_IP_PRI_NAT_DST   iptable_nat.c: nf_nat_ipv4_ops[], ops[i].priv = table
```

Each entry stores only the two fields inside `{ }`, `.hook` and `.priv`. The
columns to the right are annotations, added to explain where each entry comes
from:

```text
hooks[2] = { .hook = ipv4_conntrack_in, .priv = NULL }   NF_IP_PRI_CONNTRACK (-200)   nf_conntrack_proto.c: ipv4_conntrack_ops[]
           └──────────── stored in the entry ─────────┘   └──────────────────── explanation only ─────────────────────────┘
```

The priority was used to sort the list when the entry was registered, so at
packet time it shows only as the entry's position (`hooks[2]`).

Where each `.priv` value is set:

| `.priv` | Set in | What it is |
|---|---|---|
| `new_table` | `ip_tables.c`, `ipt_register_table`: `ops[i].priv = new_table;` | the namespace's copy of the table template (`packet_raw`, `packet_mangler`), a `struct xt_table *` |
| `NULL` | defrag and conntrack ops set no `.priv` | nothing needed |
| `priv` | `nf_nat_core.c`, `nf_nat_register_fn`: `nat_ops[i].priv = priv;` | a `struct nf_nat_lookup_hook_priv *`, holding `.entries` |
| `table` | `iptable_nat.c`: `ops[i].priv = table;` | the nat table, a `struct xt_table *` |

Notes on the names:

- **`raw` has no wrapper:** it registers `ipt_do_table` directly
  (`iptable_raw.c`). `mangle` uses `iptable_mangle_hook`, which calls
  `ipt_do_table` and also reroutes a packet when mangle rules change it in
  `LOCAL_OUT`.
- **Two arrays share the name `nf_nat_ipv4_ops[]`.** The NAT core's (in
  `nf_nat_proto.c`, `.hook = nf_nat_ipv4_pre_routing`) sits in the hook. The
  nat table's (in `iptable_nat.c`, `.hook = ipt_do_table`) sits in the NAT
  core's `.entries` and runs only for a connection's first packet.
- **5 is this example's count.** Defrag and conntrack appear once connection
  tracking is in use. Each nftables base chain on prerouting adds an entry. A
  table whose module isn't loaded adds none. Each network namespace has its own
  list.

**Every hook has the entries its jobs need.** With conntrack, NAT and all five
iptables tables loaded:

| Hook | Entries | Count |
|---|---|---|
| PREROUTING | defrag, raw, conntrack, mangle, NAT (DNAT) | 5 |
| INPUT | mangle, filter, security, NAT (SNAT), conntrack confirm | 5 |
| FORWARD | mangle, filter, security | 3 |
| OUTPUT | defrag, raw, conntrack, mangle, NAT (DNAT), filter, security | 7 |
| POSTROUTING | mangle, NAT (SNAT), conntrack confirm | 3 |

- **PREROUTING has these 5** because each job must see the packet before the
  routing decision. DNAT must change the destination before routing, so the
  packet is routed to the new address. `mangle` marks must be set before
  routing, so `ip rule fwmark` can use them. `filter` and `security` aren't
  here: they run after routing, at INPUT or FORWARD.
- **OUTPUT has the most entries.** Locally created packets need everything
  PREROUTING does: defrag (`ipv4_defrag_ops[]` registers on `NF_INET_LOCAL_OUT`
  too), raw, conntrack and DNAT. They also need filtering, because there's no
  INPUT or FORWARD step for them.
- **FORWARD and POSTROUTING have only 3 entries.** By then the connection is
  already tracked and the destination already translated.

**Every hook, entry by entry, with all five iptables tables loaded.** Each
entry is `{ .hook, .priv }`, in priority order. A table with *n* valid hooks has
*n* entries, all with the same `.priv`. **[NAT]** marks the NAT core's entry,
whose own list holds the `nat` table's entry (from the kernel source:
`iptable_*.c`, `nf_conntrack_proto.c`, `nf_defrag_ipv4.c`, `nf_nat_proto.c`):

```text
hooks_ipv4[PRE_ROUTING]
   -400  { ipv4_conntrack_defrag,   NULL }
   -300  { ipt_do_table,            &raw }
   -200  { ipv4_conntrack_in,       NULL }
   -150  { iptable_mangle_hook,     &mangle }
   -100  { nf_nat_ipv4_pre_routing, priv } [NAT] → { ipt_do_table, &nat }

hooks_ipv4[LOCAL_IN]
   -150  { iptable_mangle_hook,     &mangle }
      0  { ipt_do_table,            &filter }
     50  { ipt_do_table,            &security }
    100  { nf_nat_ipv4_local_in,    priv } [NAT] → { ipt_do_table, &nat }
    max  { nf_confirm,              NULL }

hooks_ipv4[FORWARD]
   -150  { iptable_mangle_hook,     &mangle }
      0  { ipt_do_table,            &filter }
     50  { ipt_do_table,            &security }

hooks_ipv4[LOCAL_OUT]
   -400  { ipv4_conntrack_defrag,   NULL }
   -300  { ipt_do_table,            &raw }
   -200  { ipv4_conntrack_local,    NULL }
   -150  { iptable_mangle_hook,     &mangle }        ← reroutes on change
   -100  { nf_nat_ipv4_local_fn,    priv } [NAT] → { ipt_do_table, &nat }
      0  { ipt_do_table,            &filter }
     50  { ipt_do_table,            &security }

hooks_ipv4[POST_ROUTING]
   -150  { iptable_mangle_hook,     &mangle }
    100  { nf_nat_ipv4_out,         priv } [NAT] → { ipt_do_table, &nat }
    max  { nf_confirm,              NULL }
```

- **Only `mangle` has its own function.** `raw`, `filter`, `security` and `nat`
  register `ipt_do_table` directly. `iptable_mangle_hook` calls `ipt_do_table`
  too, but on `LOCAL_OUT` it saves the mark, addresses and TOS first, and calls
  `ip_route_me_harder` if a rule changed any of them. Locally created packets
  are routed before OUTPUT, so this reroute is what lets a mark set there
  change the route.
- **`max` is `NF_IP_PRI_CONNTRACK_CONFIRM` (`INT_MAX`).** `nf_confirm` writes
  the conntrack entry into the table after every other entry has accepted the
  packet.
- **`nat` has no priority of its own.** Its template (`nf_nat_ipv4_table`) has
  none. Each of its ops carries one: −100 (`NF_IP_PRI_NAT_DST`) on PREROUTING
  and OUTPUT, +100 (`NF_IP_PRI_NAT_SRC`) on INPUT and POSTROUTING.

**Each table's rules block: where the built-in chains are.** When a table is
built (`ipt_alloc_initial_table`), it gets one policy rule per valid hook, in
hook-number order: PREROUTING 0, INPUT 1, FORWARD 2, OUTPUT 3, POSTROUTING 4.
`hook_entry[h]` points at each:

```text
raw       [PREROUTING policy][OUTPUT policy][ERROR]
mangle    [PREROUTING][INPUT][FORWARD][OUTPUT][POSTROUTING][ERROR]
nat       [PREROUTING][INPUT][OUTPUT][POSTROUTING][ERROR]
filter    [INPUT][FORWARD (ACCEPT, or DROP if forward=0)][OUTPUT][ERROR]
security  [INPUT][FORWARD][OUTPUT][ERROR]
```

- **`ERROR` marks the end of the table.** Custom chains are added before it,
  and each custom chain also starts with an `ERROR` entry carrying its name.
- **Rules go before each built-in chain's policy.** `iptables -A FORWARD …`
  inserts before FORWARD's policy rule, which stays last, at `underflow[h]`.
- **filter's FORWARD policy is set in code.** `iptable_filter_table_init`
  overwrites entry 1 (FORWARD) with DROP when the `forward` module parameter
  is 0. Then the table is also built as soon as a namespace exists, not at
  first use.
- **Adding rules never touches the hook list.** `iptables -A` sends a new
  block, the kernel checks it (`translate_table`) and swaps `table->private`.
  The entries above still point at the same table.

**How each table registers its callbacks.** A table's built-in chains are hooked
in together, when the table is created in a namespace, and removed together.
When a table is first used, `ipt_register_table`
(`net/ipv4/netfilter/ip_tables.c`) runs:

```c
num_ops = hweight32(table->valid_hooks);           // how many hook points this table uses
ops = kmemdup_array(template_ops, num_ops, …);     // one op per point, from the module's template
for (i = 0; i < num_ops; i++)
	ops[i].priv = new_table;                       // all point at this table
new_table->ops = ops;                              // kept, so they can be unregistered later
ret = nf_register_net_hooks(net, ops, num_ops);    // ← all of them, in one call
```

The ops come from `xt_hook_ops_alloc`, which runs once at module load and
creates them in hook-number order: PREROUTING 0, INPUT 1, FORWARD 2, OUTPUT 3,
POSTROUTING 4. `nf_register_net_hooks` is a loop over `nf_register_net_hook`,
one per op, each rebuilding its own point's list. If one fails, the ones
already added are removed again: all or nothing.

*raw: 2 callbacks*

```c
// iptable_raw.c
#define RAW_VALID_HOOKS ((1 << NF_INET_PRE_ROUTING) | (1 << NF_INET_LOCAL_OUT))
rawtable_ops = xt_hook_ops_alloc(table, ipt_do_table);            // at module load
ret = ipt_register_table(net, table, repl, rawtable_ops);         // at first use
                                       // → nf_register_net_hooks(net, ops, 2)
```

```text
ops[0] { ipt_do_table, &raw, hooknum 0 PRE_ROUTING, -300 }  → hooks_ipv4[0]
ops[1] { ipt_do_table, &raw, hooknum 3 LOCAL_OUT,   -300 }  → hooks_ipv4[3]
```

*mangle: 5 callbacks*

```c
// iptable_mangle.c
mangle_ops = xt_hook_ops_alloc(&packet_mangler, iptable_mangle_hook);
ret = ipt_register_table(net, &packet_mangler, repl, mangle_ops);
                                       // → nf_register_net_hooks(net, ops, 5)
```

```text
ops[0] { iptable_mangle_hook, &mangle, 0 PRE_ROUTING,  -150 } → hooks_ipv4[0]
ops[1] { iptable_mangle_hook, &mangle, 1 LOCAL_IN,     -150 } → hooks_ipv4[1]
ops[2] { iptable_mangle_hook, &mangle, 2 FORWARD,      -150 } → hooks_ipv4[2]
ops[3] { iptable_mangle_hook, &mangle, 3 LOCAL_OUT,    -150 } → hooks_ipv4[3]
ops[4] { iptable_mangle_hook, &mangle, 4 POST_ROUTING, -150 } → hooks_ipv4[4]
```

*filter: 3 callbacks*

```c
// iptable_filter.c
#define FILTER_VALID_HOOKS ((1 << NF_INET_LOCAL_IN) | (1 << NF_INET_FORWARD) | (1 << NF_INET_LOCAL_OUT))
filter_ops = xt_hook_ops_alloc(&packet_filter, ipt_do_table);
err = ipt_register_table(net, &packet_filter, repl, filter_ops);
                                       // → nf_register_net_hooks(net, ops, 3)
```

```text
ops[0] { ipt_do_table, &filter, 1 LOCAL_IN,  0 } → hooks_ipv4[1]
ops[1] { ipt_do_table, &filter, 2 FORWARD,   0 } → hooks_ipv4[2]
ops[2] { ipt_do_table, &filter, 3 LOCAL_OUT, 0 } → hooks_ipv4[3]
```

There's one difference in timing. With the module parameter `forward=0`, filter
is registered as soon as a namespace is created (`iptable_filter_net_init`), not
at first use. That way FORWARD's DROP policy is in place from the start.

*security: 3 callbacks*

```c
// iptable_security.c
sectbl_ops = xt_hook_ops_alloc(&security_table, ipt_do_table);
ret = ipt_register_table(net, &security_table, repl, sectbl_ops);
                                       // → nf_register_net_hooks(net, ops, 3)
```

```text
ops[0] { ipt_do_table, &security, 1 LOCAL_IN,  50 } → hooks_ipv4[1]
ops[1] { ipt_do_table, &security, 2 FORWARD,   50 } → hooks_ipv4[2]
ops[2] { ipt_do_table, &security, 3 LOCAL_OUT, 50 } → hooks_ipv4[3]
```

*nat: 4 callbacks, one at a time, through the NAT core*

nat doesn't follow the pattern. Its table is created with **no** ops, so nothing
is hooked in at that stage. Its 4 ops are then handed to the NAT core one by one
(`iptable_nat.c`):

```c
ret = ipt_register_table(net, &nf_nat_ipv4_table, repl, NULL);   // table only: "No template? … return 0"
ret = ipt_nat_register_lookups(net);

ipt_nat_register_lookups(net):
	ops = kmemdup(nf_nat_ipv4_ops, …);                // iptable_nat's own 4 ops, each with its own priority
	for (i = 0; i < 4; i++) {
		ops[i].priv = table;                          // &nat
		ret = nf_nat_ipv4_register_fn(net, &ops[i]);  // ← one at a time, to the NAT core
		if (ret) { … unregister those already done …; return ret; }
	}
```

On the **first** of those 4 calls, the NAT core finds it isn't hooked in yet. It
hooks in its own 4 ops in one call, then files the nat table's op in its inner
list. The other 3 calls only file ops (`nf_nat_core.c`, `nf_nat_register_fn`):

```c
if (!nat_proto_net->nat_hook_ops) {                      // NAT core not in this namespace yet?
	nat_ops = kmemdup_array(orig_nat_ops, ops_count, …); // the NAT core's 4 ops (nf_nat_proto.c)
	…each gets its own lpriv…
	ret = nf_register_net_hooks(net, nat_ops, ops_count);   // ← the NAT core: all 4 at once
}
priv = nat_ops[hooknum].priv;
ret = nf_hook_entries_insert_raw(&priv->entries, ops);     // nat table's op → that point's inner list
```

```text
hook lists (registered once, by the NAT core):              inner lists (filled by iptable_nat, one by one):
hooks_ipv4[0] { nf_nat_ipv4_pre_routing, lpriv0 }, -100  →  lpriv0->entries: { ipt_do_table, &nat }, -100
hooks_ipv4[4] { nf_nat_ipv4_out,         lpriv1 },  100  →  lpriv1->entries: { ipt_do_table, &nat },  100
hooks_ipv4[3] { nf_nat_ipv4_local_fn,    lpriv2 }, -100  →  lpriv2->entries: { ipt_do_table, &nat }, -100
hooks_ipv4[1] { nf_nat_ipv4_local_in,    lpriv3 },  100  →  lpriv3->entries: { ipt_do_table, &nat },  100
```

(The order follows `nf_nat_ipv4_ops[]` in both files: PREROUTING, POSTROUTING,
OUTPUT, INPUT.) An nftables `type nat` chain registers through the same
`nf_nat_ipv4_register_fn`, so if one exists first, the NAT core is already
hooked in and iptables' nat only adds to the inner lists.

*All five side by side*

| Table | Ops | Hook points (slots) | Priority | Call that adds them | Where they go |
|---|---|---|---|---|---|
| raw | 2 | 0, 3 | −300 | `nf_register_net_hooks(net, ops, 2)` | hook lists |
| mangle | 5 | 0, 1, 2, 3, 4 | −150 | `nf_register_net_hooks(net, ops, 5)` | hook lists |
| filter | 3 | 1, 2, 3 | 0 | `nf_register_net_hooks(net, ops, 3)` | hook lists |
| security | 3 | 1, 2, 3 | 50 | `nf_register_net_hooks(net, ops, 3)` | hook lists |
| nat | 4 | 0, 4, 3, 1 | −100 / +100 / −100 / +100 | 4 × `nf_nat_ipv4_register_fn` | the NAT core's inner lists (the NAT core itself: `nf_register_net_hooks(net, nat_ops, 4)`, once) |

**Removal mirrors it.** When the namespace goes, `ipt_unregister_table_pre_exit`
removes a table's callbacks together,
`nf_unregister_net_hooks(net, table->ops, hweight32(table->valid_hooks))`, and
`ipt_nat_unregister_lookups` takes nat's 4 ops out of the NAT core's inner lists.
The IPv6 tables (`ip6table_*.c`) do all of this again for `hooks_ipv6[]`.

**What happens when a packet hits the hook.** The kernel's loop is
`nf_hook_slow` in `net/netfilter/core.c` (simplified):

```c
for (; s < e->num_hook_entries; s++) {
    verdict = nf_hook_entry_hookfn(&e->hooks[s], skb, state);   // run entry s
    switch (verdict) {
    case NF_ACCEPT: break;        // go on to the next entry
    case NF_DROP:   kfree_skb…;   return …;   // packet freed: later entries never run
    case NF_QUEUE:  …             // hand it to userspace (e.g. NFQUEUE)
    case NF_STOLEN: return …;     // the entry took the packet
    }
}
```

So at each hook the packet runs one chain per table, that table's chain for
this hook, in priority order, until something stops it. At PREROUTING that's
raw's `PREROUTING`, mangle's `PREROUTING` and nat's `PREROUTING`, not all four
of nat's chains. Over its whole path a packet meets `nat` at most twice:

| Packet | nat chains it runs | nat chains it never runs |
|---|---|---|
| forwarded (e.g. Pod → Pod on another node) | `PREROUTING` → `POSTROUTING` | `INPUT`, `OUTPUT` |
| arriving for a local process | `PREROUTING` → `INPUT` | `OUTPUT`, `POSTROUTING` |
| sent by a local process | `OUTPUT` → `POSTROUTING` | `PREROUTING`, `INPUT` |

Even those run only for the connection's first packet. That's why kube-proxy
jumps to `KUBE-SERVICES` from both nat `PREROUTING` (traffic from Pods and other
nodes) and nat `OUTPUT` (traffic from the node itself): a packet takes one route
or the other, never both.

The rules at each entry:

- **`ACCEPT` means "this table is done"**, not "let the packet through". The
  next entry still runs, as the earlier two-table lab showed.
- **`DROP` stops everything.** Later tables never see the packet.
- **Each entry runs only its table's chain for this hook**, and the custom
  chains that chain jumps to. The `mangle` entry at PREROUTING runs mangle's
  `PREROUTING` chain, never its `FORWARD` chain.

**What each verdict does to the rest of the list.** `nf_hook_slow` calls
`hooks[0]`, `hooks[1]`, … and checks each verdict:

- **`NF_ACCEPT`:** go on to the next callback.
- **`NF_DROP`:** the packet is freed and the remaining callbacks never run. A
  DROP rule in `raw` stops it before conntrack even sees it.
- **`NF_STOLEN`:** the callback took the packet, and the loop stops without
  freeing it. Defrag does this with fragments. While the packet is incomplete,
  each fragment is held and `NF_STOLEN` returned. When the last one arrives,
  `ip_defrag` builds the whole packet in that fragment's buffer, defrag returns
  `NF_ACCEPT`, and the reassembled packet continues to `hooks[1]`
  (`net/ipv4/netfilter/nf_defrag_ipv4.c`):

  ```c
  /* Gather fragments. */
  if (ip_is_fragment(ip_hdr(skb))) {
  	…
  	if (nf_ct_ipv4_gather_frags(state->net, skb, user))   // ip_defrag(): not complete yet
  		return NF_STOLEN;                                  // kept; later callbacks don't run
  }
  return NF_ACCEPT;                                          // whole packet: carry on
  ```

- **`NF_QUEUE`:** the packet is sent to a userspace program (`NFQUEUE`, used by
  IPS tools such as Suricata). When the program gives a verdict, the packet
  goes back into the list after the callback that queued it.

So the list is the most that can run for a packet, not what always runs.

**nftables: the same loop, but the entries are base chains rather than tables.**

- Each base chain adds its own entry with its own priority.
- A table can have entries on many hooks, and none of them has to be at the
  same priority.
- The verdicts mean the same thing: `accept` goes on to the next entry and
  `drop` ends it.

**Can you jump between a table's built-in chains? No** (lab, `iptables-legacy`
in a throwaway Alpine container). You can jump from them into custom chains in
the same table, and several built-in chains can share one custom chain if its
rules make sense at every one of those points. The `nat` table has 4 built-in
chains:

*1. Built-in chain → built-in chain: refused*

```text
$ iptables -t nat -A PREROUTING -j OUTPUT
    iptables: Invalid argument.
$ iptables -t nat -A OUTPUT -j POSTROUTING
    iptables: Invalid argument.
```

Each built-in chain is attached to a hook at a different point in the packet's
path: `PREROUTING` when a packet arrives, `POSTROUTING` as it leaves, after
routing. A jump only moves within the rules of the current hook call. A packet
gets from one hook to the next by travelling through the kernel, not by
jumping.

*2. Built-in chains → one shared custom chain: allowed*

```text
$ iptables -t nat -N SVC
$ iptables -t nat -A SVC -d 10.96.0.10 -p tcp --dport 80 -j DNAT --to-destination 10.244.0.5:8080
$ iptables -t nat -A PREROUTING -j SVC      ✓  packets arriving from outside
$ iptables -t nat -A OUTPUT -j SVC          ✓  packets sent by local processes
```

This is what kube-proxy does: `KUBE-SERVICES` is reached from both `PREROUTING`
(traffic from Pods and other nodes) and `OUTPUT` (traffic from the node itself).

*3. But only where the chain's rules are valid*

When you add a jump, iptables checks every rule the custom chain can reach
against the hook it's being jumped from:

```text
$ iptables -t nat -A POSTROUTING -j SVC     ✗  Invalid argument   ← DNAT isn't allowed at POSTROUTING
$ iptables -t nat -A POSTROUTING -j MASQ    ✓
$ iptables -t nat -A PREROUTING -j MASQ     ✗  Invalid argument   ← MASQUERADE isn't allowed at PREROUTING
```

Each target lists the hooks it allows (`.hooks` in `net/netfilter/xt_nat.c`,
`xt_REDIRECT.c`, `xt_MASQUERADE.c`):

| Target | Allowed in |
|---|---|
| `DNAT` / `REDIRECT` (change the destination) | `PREROUTING`, `OUTPUT`: before the routing decision, so the new destination is routed |
| `SNAT` (change the source) | `POSTROUTING`, `INPUT`: after the routing decision |
| `MASQUERADE` (SNAT to the outgoing interface's address) | `POSTROUTING` only: it needs to know the outgoing interface |

*4. Loops: refused*

```text
$ iptables -t nat -A LOOP -j SVC
$ iptables -t nat -A SVC -j LOOP
    iptables: Symbolic link loop.
```

Final ruleset:

```text
-A PREROUTING -j SVC          ┐
-A OUTPUT -j SVC              ┘ one DNAT chain, two entry points
-A POSTROUTING -j MASQ          one SNAT chain, one entry point
-A SVC … -j DNAT …
-A MASQ … -j MASQUERADE
```

**nftables has no fixed chains.** A table starts empty. You create base chains,
name them whatever you like, and choose each one's hook, type and priority:

```text
table inet a {
  chain anything_i_like {
    type filter hook input priority 10;   ← this line is what a built-in chain is in iptables
  }
}
```

iptables-nft fakes the fixed chains with ordinary nft base chains named `INPUT`,
`PREROUTING` and so on. It seems to create one only when you first use it: in
the lab, adding one rule to `nat OUTPUT` produced a `table ip nat` with only an
output hook and no `PREROUTING` chain.

In iptables, a table is a fixed set of hook chains with a fixed job, and you can
only add custom chains. In nftables, a table is just a container, and chains,
hooks and jobs are all yours to define.

#### Why tables matter: two programs sharing one firewall (lab)

Hooks and chains are enough to process packets. Tables matter when several
programs manage rules on the same machine. Tested in a throwaway Alpine
container (`iptables-legacy` and `nft`).

Two programs share one firewall:

- **VPN** blocks 10.66.0.0/16.
- **WEB** opens port 8080, then restarts and swaps its rules for port 9090.

**iptables: the programs share chains**

**1. Names clash.** VPN created a chain called `ALLOW-LIST`. When WEB tried to
create its own `ALLOW-LIST`:

```text
iptables: Chain already exists.
```

WEB had to choose another name. This is why real tools prefix everything:
`KUBE-`, `DOCKER-`, `CILIUM_`.

**2. Both programs' entry points are in the same chain.** Each hooks in by adding
a jump to the built-in `INPUT` chain:

```text
-A INPUT -j ALLOW-LIST      ← VPN's
-A INPUT -j WEB-ALLOW       ← WEB's
```

**3. WEB's cleanup breaks VPN.** WEB restarted and cleared "its" rules with
`iptables -F INPUT`:

```text
-N ALLOW-LIST
-N WEB-ALLOW
-A ALLOW-LIST -s 10.66.0.0/16 -j DROP              ← VPN's rule still exists…
-A WEB-ALLOW -p tcp -m tcp --dport 8080 -j ACCEPT
                                                    …but nothing jumps to it any more
```

VPN's jump was in the shared `INPUT` chain, so it was flushed too. 10.66.x.x is
no longer blocked, VPN doesn't know, and nothing reports an error. To clean up
safely, WEB would have to find and delete exactly its own rules, one by one.

**nftables: each program has its own table**

```text
table inet vpn { chain input { … ip saddr 10.66.0.0/16 drop } }
table inet web { chain input { … tcp dport 8080 accept } }
```

- **No name clash:** both chains are called `input`, one in each table.
- **No shared entry point:** each table has its own base chain on the input hook.

When WEB restarts, it replaces its whole table in one transaction:

```text
delete table inet web
table inet web { chain input { … tcp dport 9090 accept } }
```

Result: VPN's rule was untouched, and WEB went from 8080 straight to 9090, never
with a moment of no rules, because the delete and the re-create were committed
together. On a Kubernetes node, kube-proxy's nftables mode works this way: all
its rules live in `table ip kube-proxy`.

#### Can't each program just use its own custom chains? (lab)

It can, and Docker does (`DOCKER`, `DOCKER-USER`, `DOCKER-ISOLATION-STAGE-1`,
…). That isn't enough, because two things are still shared:

1. **The entry points.** A custom chain runs only if a rule in a built-in chain
   jumps to it, and the built-in chains belong to everyone. Every program has to
   hook in by adding rules to the same `INPUT`, `FORWARD` or `PREROUTING` chain,
   so programs' rules always sit side by side.
2. **The table.** firewalld's reload (with its iptables backend) rewrote whole
   tables, custom chains included.

**A custom chain runs only if a built-in chain jumps to it.** Docker's setup in
the lab: its own `DOCKER` chain, plus a jump to it from the built-in
`PREROUTING`. The `MASQUERADE` rule sits directly in the built-in
`POSTROUTING`:

```text
-N DOCKER                                                     ← Docker's own chain
-A PREROUTING -m addrtype --dst-type LOCAL -j DOCKER          ← its entry point, in a built-in chain
-A POSTROUTING -s 172.17.0.0/16 ! -o docker0 -j MASQUERADE    ← directly in a built-in chain
-A DOCKER -p tcp -m tcp --dport 8080 -j DNAT --to-destination 172.17.0.2:80
```

When another program deletes only that one jump, the `DOCKER` chain and its DNAT
rule are still there, but no packet ever reaches them:

```text
-N DOCKER
-A POSTROUTING -s 172.17.0.0/16 ! -o docker0 -j MASQUERADE
-A DOCKER -p tcp -m tcp --dport 8080 -j DNAT --to-destination 172.17.0.2:80   ← dead
```

In iptables, the built-in chains (`PREROUTING`, `INPUT`, `FORWARD`, `OUTPUT`,
`POSTROUTING`) are the only way into a table, one set per table, and every
program must put its entry jumps there.

**A whole-table reload removes everyone's chains.** firewalld's iptables backend
rebuilt each table from its own configuration on reload, which is what
`iptables-restore` without `--noflush` does. A firewalld-style restore of `nat`
in the lab left only firewalld's rules:

```text
-N PRE_public
-A PREROUTING -j PRE_public
```

Docker's chain, its DNAT rule and its `MASQUERADE` rule were all gone. This is
what happened for years with **firewalld and Docker**: `firewall-cmd --reload`
deleted Docker's rules, and containers lost networking until Docker was
restarted. Neither program was buggy on its own: firewalld treated the tables
as its own, and Docker had to put its rules in those same tables, because
iptables has only the fixed ones. (Docker now re-adds its rules when firewalld
signals a reload over D-Bus; from the docs, not tested.)

**kube-proxy watches for this** (from the kube-proxy and kubelet source, not
tested). It creates an empty chain, `KUBE-PROXY-CANARY`. If that chain
disappears, kube-proxy assumes someone flushed iptables and rebuilds all its
rules. The kubelet keeps a `KUBE-KUBELET-CANARY` chain for the same reason. They
exist because other programs wiping their rules is expected with iptables.

A custom chain gives you your own list of rules. It doesn't give you your own
entry point or a part of the table that others won't rewrite. An nftables table
gives you both: its own base chains on the hooks, and a container that another
program's `delete table` or reload doesn't touch.

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

#### A NodePort packet, rule by rule and hook by hook (lab, k3s)

A second NodePort lab, on a different cluster: a single-node k3s (v1.34, kube-proxy
in iptables mode with iptables-nft, flannel), the AKS cluster from
[azure-local-lab.md §7](azure-local-lab.md#7-aks). I created a NodePort Service
`np-lab/web` with 2 Pods, then sent one request from a client container outside
the node. The minikube version of the same rules is in
[A NodePort Service, one port, every rule](#a-nodeport-service-one-port-every-rule-lab).

```
service/web   NodePort   10.43.208.17   80:30080/TCP
pod  10.42.0.5:80   pod  10.42.0.6:80        node IP 172.17.0.3        client 172.17.0.4
```

**1. What NodePort adds to ClusterIP.** A NodePort Service is a ClusterIP Service
plus one extra way in, a port on every IP the node owns. All the ClusterIP rules
stay (`KUBE-SVC-…`, `KUBE-SEP-…`). Both paths enter through `KUBE-SERVICES`;
kube-proxy adds a second branch, from that chain's last rule, that ends in the
same `KUBE-SVC` chain:

```
PREROUTING / OUTPUT
        │  -j KUBE-SERVICES          (every packet, both paths)
        ▼
  KUBE-SERVICES
        ├─ rule: -d 10.43.208.17 --dport 80 ─────────────────────────────► KUBE-SVC-… ─► KUBE-SEP-… (DNAT to a Pod)
        │        (ClusterIP)                                                   ▲
        │                                                                      │
        └─ last rule: --dst-type LOCAL ─► KUBE-NODEPORTS ─► KUBE-EXT-… ────────┘
                 (NodePort: any node IP)    --dport 30080       │
                                                                └─ KUBE-MARK-MASQ: mark 0x4000, "SNAT this later"
```

**2. The rules kube-proxy wrote (nat table).** Entry: `KUBE-SERVICES`. Its last
rule sends anything addressed to the node itself to `KUBE-NODEPORTS`:

```
-A PREROUTING -m comment --comment "kubernetes service portals" -j KUBE-SERVICES
-A OUTPUT     -m comment --comment "kubernetes service portals" -j KUBE-SERVICES

-A KUBE-SERVICES -d 10.43.208.17/32 -p tcp … --dport 80 -j KUBE-SVC-6V2XWLK7W7RZJHNE           ← the ClusterIP rule
-A KUBE-SERVICES … "kubernetes service nodeports; NOTE: this must be the last rule in this chain"
                 -m addrtype --dst-type LOCAL -j KUBE-NODEPORTS
```

- **`--dst-type LOCAL`** matches any address this node owns: `eth0` 172.17.0.3,
  `cni0` 10.42.0.1, `lo` 127.0.0.1, `flannel.1`. That's why a NodePort answers on
  every node IP.
- **It must be last** because the ClusterIP rules are checked first. A packet to
  a ClusterIP isn't addressed to the node, so it never reaches this rule.

`KUBE-NODEPORTS`: one rule per NodePort, matched by port:

```
-A KUBE-NODEPORTS -d 127.0.0.0/8 -p tcp … --dport 30080 … -j KUBE-EXT-6V2XWLK7W7RZJHNE   ← localhost:30080 (needs route_localnet)
-A KUBE-NODEPORTS             -p tcp … --dport 30080     -j KUBE-EXT-6V2XWLK7W7RZJHNE   ← any node IP:30080
```

`KUBE-EXT-…`: the "external traffic" chain, which only exists for NodePort and
LoadBalancer Services:

```
-A KUBE-EXT-6V2XWLK7W7RZJHNE … "masquerade traffic for np-lab/web:http external destinations" -j KUBE-MARK-MASQ
-A KUBE-EXT-6V2XWLK7W7RZJHNE -j KUBE-SVC-6V2XWLK7W7RZJHNE
-A KUBE-MARK-MASQ -j MARK --set-xmark 0x4000/0x4000          ← only sets a mark; SNAT happens later
```

`KUBE-SVC` and `KUBE-SEP`, the same chains the ClusterIP uses:

```
-A KUBE-SVC-6V2XWLK7W7RZJHNE ! -s 10.42.0.0/16 -d 10.43.208.17/32 … --dport 80 -j KUBE-MARK-MASQ   ← ClusterIP-only rule (not matched here)
-A KUBE-SVC-6V2XWLK7W7RZJHNE … "-> 10.42.0.5:80" -m statistic --mode random --probability 0.5 -j KUBE-SEP-HBZKSHZKFESGC2QG
-A KUBE-SVC-6V2XWLK7W7RZJHNE … "-> 10.42.0.6:80"                                         -j KUBE-SEP-R7HKSTCACJLQFBSC

-A KUBE-SEP-R7HKSTCACJLQFBSC -s 10.42.0.6/32 … -j KUBE-MARK-MASQ                         ← hairpin (Pod calling itself)
-A KUBE-SEP-R7HKSTCACJLQFBSC -p tcp … -j DNAT --to-destination 10.42.0.6:80
```

SNAT at the end, in POSTROUTING:

```
-A POSTROUTING -m comment --comment "kubernetes postrouting rules" -j KUBE-POSTROUTING
-A KUBE-POSTROUTING -m mark ! --mark 0x4000/0x4000 -j RETURN                 ← not marked: leave the source alone
-A KUBE-POSTROUTING -j MARK --set-xmark 0x4000/0x0                           ← clear the mark
-A KUBE-POSTROUTING … "kubernetes service traffic requiring SNAT" -j MASQUERADE --random-fully
```

In the filter table, the forwarded packet is allowed by its mark:

```
-A FORWARD … -j KUBE-FORWARD
-A KUBE-FORWARD … -m mark --mark 0x4000/0x4000 -j ACCEPT
```

**Why NodePort marks for SNAT.** The client sent its packet to the node
(172.17.0.3:30080), and the node rewrote the destination to a Pod. The Pod's reply
has to come back through this same node, so conntrack can undo the DNAT and the
client gets an answer from 172.17.0.3:30080. If the Pod replied straight to the
client from 10.42.0.6, the client would discard it: it never talked to 10.42.0.6.
On a multi-node cluster the Pod may be on another node, and its reply would bypass
this node entirely. Rewriting the source to this node (MASQUERADE) makes the reply
return here. That's `externalTrafficPolicy: Cluster`, the default.

**3. One packet, hook by hook.** The client `172.17.0.4:49634` sends a TCP SYN to
`172.17.0.3:30080`. Counters were zeroed first; only this first packet goes
through the nat rules, so each nat counter read **1**:

```
1 arrives on eth0           src 172.17.0.4:49634  dst 172.17.0.3:30080

2 PREROUTING hook (nat, via the NAT core: first packet only)
   PREROUTING → KUBE-SERVICES
     ClusterIP rule?  dst isn't 10.43.208.17      → no
     last rule: dst-type LOCAL? 172.17.0.3 is ours → KUBE-NODEPORTS            1 pkt
   KUBE-NODEPORTS: tcp dpt 30080                    → KUBE-EXT-6V2X…            1 pkt
   KUBE-EXT: KUBE-MARK-MASQ → mark 0x4000                                       1 pkt
             → KUBE-SVC-6V2X…
   KUBE-SVC: random 0.5 → not the first → KUBE-SEP-R7HK… (10.42.0.6)            1 pkt
   KUBE-SEP: DNAT                                                               1 pkt
                            src 172.17.0.4:49634  dst 10.42.0.6:80   (mark 0x4000)

3 routing decision: 10.42.0.6 is not this node → 10.42.0.0/24 dev cni0 → FORWARD

4 FORWARD hook (filter)
   KUBE-FORWARD: mark 0x4000 → ACCEPT                                           1 pkt

5 POSTROUTING hook (nat, SNAT side)
   KUBE-POSTROUTING: marked → clear mark → MASQUERADE                           1 pkt
   source := the address of the outgoing interface cni0 = 10.42.0.1, random port
                            src 10.42.0.1:14718   dst 10.42.0.6:80

6 out of cni0 → the Pod
```

What the Pod (`traefik/whoami`) saw:

```
Hostname: web-7998959fd-f98bx           (the 10.42.0.6 Pod)
RemoteAddr: 10.42.0.1:14718             ← the node's cni0 address, not 172.17.0.4
Host: 172.17.0.3:30080
```

The conntrack entry holds both translations:

```
tcp … src=172.17.0.4 dst=172.17.0.3 sport=49634 dport=30080      ← what the client sent (original direction)
      src=10.42.0.6  dst=10.42.0.1  sport=80    dport=14718      ← what the reply looks like (after DNAT and SNAT)
```

The reply and every later packet don't run any rules. The Pod answers
`10.42.0.6:80 → 10.42.0.1:14718`. Conntrack matches it to the reply tuple and
reverses both changes. The SNAT is undone, so the destination becomes
`172.17.0.4:49634`. The DNAT is undone, so the source becomes `172.17.0.3:30080`.
The client gets its answer from the address it called. That's why every nat
counter read 1 for the whole connection.

**4. With `externalTrafficPolicy: Local`: no SNAT, real client IP.** Same Service,
patched to `Local`. Only `KUBE-EXT` changes:

```
-A KUBE-EXT-6V2X… -s 10.42.0.0/16 … "pod traffic …"           -j KUBE-SVC-6V2X…   ← Pods: normal path
-A KUBE-EXT-6V2X… … --src-type LOCAL -j KUBE-MARK-MASQ                            ← from the node itself: still SNAT
-A KUBE-EXT-6V2X… … --src-type LOCAL -j KUBE-SVC-6V2X…
-A KUBE-EXT-6V2X… -j KUBE-SVL-6V2X…                                               ← everyone else: no mark, local endpoints only

-A KUBE-SVL-6V2X… "-> 10.42.0.5:80" … --probability 0.5 -j KUBE-SEP-HBZK…        ← only Pods on THIS node
-A KUBE-SVL-6V2X… "-> 10.42.0.6:80"                     -j KUBE-SEP-R7HK…
```

The same request:

```
RemoteAddr: 172.17.0.4:59912          ← the real client IP
KUBE-POSTROUTING:  1 RETURN   0 MARK   0 MASQUERADE    ← not marked, so not SNATed
```

- **Why skipping SNAT is safe:** traffic only goes to a Pod on this node, so the
  reply naturally comes back through this node.
- **The cost:** a node with no local Pod for the Service drops NodePort traffic.
  A LoadBalancer adds a health-check NodePort so the load balancer avoids those
  nodes. Here `healthCheckNodePort` stayed empty, because it's only allocated for
  type `LoadBalancer`.

**5. ClusterIP vs NodePort:**

| | ClusterIP | NodePort |
|---|---|---|
| Matched by | `-d <ClusterIP> --dport <port>` | `--dst-type LOCAL --dport <nodePort>` (last rule of `KUBE-SERVICES`) |
| Extra chains | — | `KUBE-NODEPORTS`, `KUBE-EXT-…` (+ `KUBE-SVL-…` with ETP Local) |
| Marked for SNAT | only from outside the Pod network (`! -s 10.42.0.0/16`) | **always** (ETP Cluster); only node-local traffic (ETP Local) |
| Backend choice | `KUBE-SVC` → random `KUBE-SEP` | the **same** `KUBE-SVC` (Cluster) or `KUBE-SVL`, local Pods only (Local) |
| Source the Pod sees | the client Pod's IP | the node's IP (Cluster) or the real client (Local) |

**6. Exactly what NodePort adds (diff).** A NodePort adds nothing to
`KUBE-SERVICES`, `KUBE-SVC` or `KUBE-SEP`. The diff of the same Service as
`ClusterIP`, then as `NodePort` (`externalTrafficPolicy: Cluster`), nat and
filter tables, shows exactly 4 new rules, and none removed:

```
### rules added by NodePort:
-A KUBE-NODEPORTS -d 127.0.0.0/8 -p tcp … "np-lab/web:http" … --dport 30080 -j KUBE-EXT-6V2XWLK7W7RZJHNE   ← localhost:30080
-A KUBE-NODEPORTS -p tcp …            "np-lab/web:http" … --dport 30080 -j KUBE-EXT-6V2XWLK7W7RZJHNE   ← any node IP:30080
-A KUBE-EXT-6V2XWLK7W7RZJHNE … "masquerade traffic for np-lab/web:http external destinations" -j KUBE-MARK-MASQ
-A KUBE-EXT-6V2XWLK7W7RZJHNE -j KUBE-SVC-6V2XWLK7W7RZJHNE
### rules removed:
  (none)
```

| Chain | As ClusterIP | NodePort adds |
|---|---|---|
| `KUBE-SERVICES` | 1 rule: `-d 10.43.208.17 --dport 80 -j KUBE-SVC-…` | **nothing**: the jump to `KUBE-NODEPORTS` (`--dst-type LOCAL`) is one shared rule that kube-proxy keeps whether or not any NodePort exists (it was in the dump taken before this Service was created) |
| `KUBE-NODEPORTS` | — | **2 rules** per port: localhost and any node IP |
| `KUBE-EXT-…` | doesn't exist | **a new chain, 2 rules**: mark for SNAT, then `-j KUBE-SVC-…` |
| `KUBE-SVC-…` | 3 rules | **nothing**: reused |
| `KUBE-SEP-…` ×2 | 2 rules each | **nothing**: reused |
| `KUBE-MARK-MASQ`, `KUBE-POSTROUTING` | shared | **nothing** |

So NodePort only adds a new entrance (`KUBE-NODEPORTS` → `KUBE-EXT`) that leads
into the ClusterIP's existing chains.

With `externalTrafficPolicy: Local` (from the dump in part 4):

- `KUBE-EXT` has 4 rules instead of 2: Pod traffic, node-local traffic (mark +
  jump), and everyone else.
- One more chain, `KUBE-SVL-…`, has one rule per endpoint on this node.
- `KUBE-SVC` and `KUBE-SEP` are still unchanged. `KUBE-SVL` jumps into the same
  `KUBE-SEP` chains.

**7. Where the jump to `KUBE-NODEPORTS` is.** In the nat table, it's the last
rule of `KUBE-SERVICES`. It's a single rule shared by all NodePort Services, and
kube-proxy writes it whether or not any NodePort exists:

```
# nat table: how a packet gets there
-A PREROUTING -m comment --comment "kubernetes service portals" -j KUBE-SERVICES      ← packets arriving
-A OUTPUT     -m comment --comment "kubernetes service portals" -j KUBE-SERVICES      ← packets the node sends

-A KUBE-SERVICES -d 10.43.208.17/32 -p tcp … --dport 80 -j KUBE-SVC-6V2XWLK7W7RZJHNE   ← one rule per ClusterIP:port
…                                                                                      ← (all the other Services)
-A KUBE-SERVICES -m comment --comment "kubernetes service nodeports; NOTE: this must be the last rule in this chain"
                 -m addrtype --dst-type LOCAL -j KUBE-NODEPORTS                         ← the jump, always last
```

It's last so that every ClusterIP rule is checked first. Only a packet that
matched no ClusterIP, and is addressed to one of the node's own IPs
(`--dst-type LOCAL`), goes on to `KUBE-NODEPORTS`. Each NodePort Service only adds
its own rules inside `KUBE-NODEPORTS`, matched by `--dport`.

With kube-proxy's `--nodeport-addresses` set (e.g. `10.0.0.0/8`), NodePorts
answer only on those addresses, and the single `addrtype` rule becomes one
`-d <range> -j KUBE-NODEPORTS` rule per range (from kube-proxy's
`pkg/proxy/iptables/proxier.go`; not tested here, k3s uses the default).

**A second, unrelated `KUBE-NODEPORTS` in the filter table.** The filter table
also has a chain called `KUBE-NODEPORTS`, with its own jump from INPUT:

```
# filter table
-A INPUT -m comment --comment "kubernetes health check service ports" -j KUBE-NODEPORTS
```

These are two different chains with the same name in different tables. Chain
names are per table ([One chain per table, not one chain across tables](#one-chain-per-table-not-one-chain-across-tables-lab)).

- **nat `KUBE-NODEPORTS`:** NodePort traffic, leading to DNAT.
- **filter `KUBE-NODEPORTS`:** allows the health-check NodePort, which only exists
  for a `LoadBalancer` Service with `externalTrafficPolicy: Local`. It was empty
  here, because no Service has one.

*Is the jump always there, or only once a NodePort exists?* Always. kube-proxy writes it on every
sync, whether or not any NodePort or LoadBalancer Service exists. Lab (the three-node k3s cluster
from [network-policy.md](network-policy.md), after changing its only NodePort Service,
`shop/backend`, to ClusterIP):

```
Services that use NodePorts: (none)

KUBE-SERVICES last rule:
-A KUBE-SERVICES "kubernetes service nodeports; NOTE: this must be the last rule in this chain" -m addrtype --dst-type LOCAL -j KUBE-NODEPORTS

KUBE-NODEPORTS contents:
-N KUBE-NODEPORTS            ← the chain exists, but it's empty
```

So the jump and the `KUBE-NODEPORTS` chain are permanent. Creating a NodePort or LoadBalancer
Service only adds rules inside `KUBE-NODEPORTS`. With none, a packet to a node address goes into
the empty chain, comes back out, and continues as ordinary traffic to the node.

If kube-proxy is started with `--nodeport-addresses` (or `nodePortAddresses` in its config) set to
specific CIDRs, it writes one `-d <node address> -j KUBE-NODEPORTS` rule per matching node address
instead of the single `--dst-type LOCAL` rule. That way NodePorts only answer on those addresses.
The jump is still written whether or not any NodePort exists, and it's still last. **(kube-proxy
docs; not tested, since k3s uses the default)**

*Where `--dst-type LOCAL` comes from.* The node IPs are never written into a rule. `KUBE-SERVICES`
reaches the NodePort rules through its last rule, in the **nat** table:

```
-A KUBE-SERVICES -m comment --comment "kubernetes service nodeports; NOTE: this must be the last rule in this chain" -m addrtype --dst-type LOCAL -j KUBE-NODEPORTS
```

`--dst-type LOCAL` means "addressed to any IP this node owns", whatever that address is. Inside
`KUBE-NODEPORTS` only the port is checked.

`-m addrtype --dst-type LOCAL` isn't stored in any iptables chain or list. It's a question iptables
asks the kernel for each packet: "does this node own the destination address?" For each packet,
the `addrtype` match module asks the kernel's routing code what type of address the destination
is, and the kernel answers from the **local routing table**. On np-server (three-node lab from
[network-policy.md](network-policy.md)):

```
$ ip route show table local
local 10.42.0.0 dev flannel.1 proto kernel scope host src 10.42.0.0        ← flannel.1's address
local 10.42.0.1 dev cni0 proto kernel scope host src 10.42.0.1             ← cni0's address
local 127.0.0.0/8 dev lo proto kernel scope host src 127.0.0.1             ← all of 127.x
local 127.0.0.1 dev lo proto kernel scope host src 127.0.0.1
local 172.19.0.2 dev eth0 proto kernel scope host src 172.19.0.2           ← the node IP
broadcast 10.42.0.255 dev cni0 …  broadcast 172.19.255.255 dev eth0 …  broadcast 127.255.255.255 dev lo …

$ ip rule
0:      from all lookup local        ← the table checked first for every routing decision
32766:  from all lookup main
32767:  from all lookup default
```

The kernel adds an entry here automatically whenever an address is assigned to an interface
(`proto kernel`). So LOCAL means one of the addresses this node owns right now.

*Where those addresses come from.* Each `local` line is an address assigned to one of the node's
own interfaces. Here "the node" is the `k3s-np-server` container's network namespace, so these are
its interfaces, not the Mac's. `ip -4 addr` on np-server lists exactly the same four (lab):

```
lo         127.0.0.1/8
eth0       172.19.0.2/16
flannel.1  10.42.0.0/32
cni0       10.42.0.1/24
```

| Address | Interface | What the interface is | Who assigned the address |
|---|---|---|---|
| `127.0.0.1/8` | `lo` | loopback | the kernel, when the namespace was created. The `/8` gives the `local 127.0.0.0/8` line: every `127.x` address is local, not only `.1` |
| `172.19.0.2/16` | `eth0` | the node's network card (here a veth pair into Docker's `k3s-np` bridge) | **Docker**, from the `k3s-np` network `172.19.0.0/16` (gateway `172.19.0.1`). On a real machine: DHCP or static config |
| `10.42.0.0/32` | `flannel.1` | flannel's VXLAN tunnel device (`vxlan id 1`) | **flannel**: the first address of this node's Pod subnet `10.42.0.0/24`, as a `/32`. Other nodes use it as the "via" address in their routes (`10.42.0.0/24 via 10.42.0.0 dev flannel.1`, [network-policy.md](network-policy.md) §0) |
| `10.42.0.1/24` | `cni0` | the Linux bridge every local Pod's veth is attached to | **the CNI bridge plugin** (bundled in k3s), when the first Pod started: the gateway address of this node's `/24`, the Pods' default gateway |

The `broadcast` lines are each subnet's broadcast address (`10.42.0.255`, `172.19.255.255`,
`127.255.255.255`), added along with the address. `flannel.1` has none, because its address is a
`/32`.

Interfaces with no IPv4 address add nothing:

- `veth*` are the node's ends of the Pods' veth pairs. The Pods' own addresses (`10.42.0.x`) live
  inside the Pods' network namespaces, so they're in each Pod's local table, not the node's.
- `tunl0`, `gre0`, `sit0` and similar are created automatically when the tunnel kernel modules
  load, and are unused.

That's why a NodePort answers on the node IP, the `cni0` gateway and the `flannel.1` address, but
not on a Pod IP: a Pod IP isn't in the node's local table.

*Kubernetes rules or normal routing?* Normal Linux routing. Kubernetes doesn't write the `local`
table or the `ip rule` list. Kubernetes components only assign the addresses, and the kernel creates
those routes by itself. Both come from kernel code (`linux-master`):

The three `ip rule` lines are built into every network namespace when it's created:

```c
// net/ipv4/fib_rules.c
static int fib_default_rules_init(struct fib_rules_ops *ops)
{
    err = fib_default_rule_add(ops, 0,      RT_TABLE_LOCAL);     // 0:     from all lookup local
    err = fib_default_rule_add(ops, 0x7FFE, RT_TABLE_MAIN);      // 32766: from all lookup main
    err = fib_default_rule_add(ops, 0x7FFF, RT_TABLE_DEFAULT);   // 32767: from all lookup default
}
```

Any Linux machine, container or Pod has exactly these three until someone adds more (for example
the policy-based routing examples in [networking.md](networking.md)).

The `local` and `broadcast` entries are added automatically when an address is assigned:

```c
// net/ipv4/fib_frontend.c
void fib_add_ifaddr(struct in_ifaddr *ifa)          // called whenever an IPv4 address is added to an interface
{
    ...
    fib_magic(RTM_NEWROUTE, RTN_LOCAL, addr, 32, prim, 0);                  // "local 172.19.0.2 dev eth0"
    ...
    fib_magic(RTM_NEWROUTE, RTN_BROADCAST, ifa->ifa_broadcast, 32, ...);    // "broadcast 172.19.255.255"
    ...
    fib_magic(RTM_NEWROUTE,
              dev->flags & IFF_LOOPBACK ? RTN_LOCAL : RTN_UNICAST, ...);     // on lo the whole prefix is local: "local 127.0.0.0/8"
}
```

That's what `proto kernel` means in the output: the kernel added it, not a program. The
`192.0.2.50` test above showed it: `ip addr add` alone produced
`local 192.0.2.50 dev lo proto kernel`.

| What | Done by | Kubernetes involved? |
|---|---|---|
| `ip rule` 0 / 32766 / 32767 | kernel, at namespace creation | no |
| assigning `172.19.0.2` to `eth0` | Docker (on a real node: DHCP or OS config) | no |
| assigning `10.42.0.0/32` to `flannel.1` | flannel (embedded in k3s) | yes, the CNI layer |
| assigning `10.42.0.1/24` to `cni0` | CNI bridge plugin | yes, the CNI layer |
| the `local` / `broadcast` entries for each address | kernel (`fib_add_ifaddr`) | no, automatic |
| `--dst-type LOCAL` answering from that table | kernel (`xt_addrtype`) | no; kube-proxy only uses it |

kube-proxy in iptables mode adds no routes and no `ip rule` entries (part 16). It writes only
iptables rules and relies on the kernel's table to know which addresses are local.

The exception is kube-proxy in **IPVS mode**. It creates a dummy interface `kube-ipvs0` and assigns
every ClusterIP and LB IP to it. The kernel then adds a `local` entry for each one, so in IPVS mode
the local table fills up with Service IPs. That's how IPVS receives Service traffic on INPUT
(part 14). **(kube-proxy docs; IPVS not run in this lab)**

*One `KUBE-NODEPORTS` chain, two rules per port.* There's one `KUBE-NODEPORTS` chain for the whole
node, shared by every NodePort and LoadBalancer Service. There's no NodePort chain per Service.
Each Service **port** adds two rules to that shared chain. Lab dump, with `shop/backend` (one port)
and a test Service `lab3/c-np` (two ports):

```
nat KUBE-NODEPORTS                       ← the only NodePort chain in the nat table
-A KUBE-NODEPORTS -d 127.0.0.0/8 -p tcp "shop/backend"        --dport 30080 -j KUBE-EXT-E62D…   ┐ shop/backend :80
-A KUBE-NODEPORTS                -p tcp "shop/backend"        --dport 30080 -j KUBE-EXT-E62D…   ┘
-A KUBE-NODEPORTS -d 127.0.0.0/8 -p tcp "lab3/c-np:80-8080"   --dport 31860 -j KUBE-EXT-KUSK…   ┐ lab3/c-np :80
-A KUBE-NODEPORTS                -p tcp "lab3/c-np:80-8080"   --dport 31860 -j KUBE-EXT-KUSK…   ┘
-A KUBE-NODEPORTS -d 127.0.0.0/8 -p tcp "lab3/c-np:9090-8080" --dport 31220 -j KUBE-EXT-CPM5…   ┐ lab3/c-np :9090
-A KUBE-NODEPORTS                -p tcp "lab3/c-np:9090-8080" --dport 31220 -j KUBE-EXT-CPM5…   ┘
```

The two rules per port:

| Rule | Matches | Why it's separate |
|---|---|---|
| `-d 127.0.0.0/8 --dport N` | `localhost:N` / `127.x:N` | localhost NodePorts need `route_localnet` and are counted separately (the `nfacct` counter `localhost_nps_accepted_pkts`, removed from the output above). Part 8 |
| `--dport N` (no `-d`) | `N` on any other address the node owns | the `--dst-type LOCAL` jump already made sure the address is the node's, so only the port is checked |

Both rules jump to the same per-port `KUBE-EXT`. What's per what:

| Chain | How many | Holds |
|---|---|---|
| nat `KUBE-NODEPORTS` | 1 per node | 2 rules per NodePort Service port: just the port numbers |
| `KUBE-EXT-<hash>` | 1 per Service port that's reachable from outside | SNAT mark, then `KUBE-SVC` or `KUBE-SVL` |
| `KUBE-SVC-<hash>` | 1 per Service port | the choice between endpoints |
| `KUBE-SEP-<hash>` | 1 per endpoint per port | the DNAT |

The hash is computed from namespace, Service name, port name and protocol, so `c-np`'s two ports
got unrelated `KUBE-EXT` names (`KUSK…`, `CPM5…`), although both belong to one Service. The "per
Service" chain for NodePort traffic is really per Service port, and it's `KUBE-EXT`, not anything
named NODEPORT.

There's also a **filter** `KUBE-NODEPORTS`, a different chain with the same name. It only gets an
ACCEPT for the health-check NodePort of a `LoadBalancer` Service with
`externalTrafficPolicy: Local`, and it was empty here.

The kernel code (`linux-master`):

```c
// net/netfilter/xt_addrtype.c — the match iptables calls for "-m addrtype"
static inline bool match_type(struct net *net, const struct net_device *dev,
                              __be32 addr, u_int16_t mask)
{
    return !!(mask & (1 << inet_dev_addr_type(net, dev, addr)));   // is the address's type in the wanted set (LOCAL)?
}
...
    if (ret && info->dest)
        ret &= match_type(net, dev, iph->daddr, info->dest) ^ ...;  // --dst-type: check the packet's destination

// net/ipv4/fib_frontend.c
unsigned int inet_dev_addr_type(struct net *net, const struct net_device *dev, __be32 addr)
{
    u32 rt_table = l3mdev_fib_table(dev) ? : RT_TABLE_LOCAL;         // the "local" table (255), unless a VRF
    return __inet_dev_addr_type(net, dev, addr, rt_table);
}

static inline unsigned int __inet_dev_addr_type(..., __be32 addr, u32 tb_id)
{
    ...
    table = fib_get_table(net, tb_id);                                // the local table
    if (table) {
        ret = RTN_UNICAST;                                            // default: an ordinary remote address
        if (!fib_table_lookup(table, &fl4, &res, FIB_LOOKUP_NOREF))   // look the address up
            ...
                ret = res.type;                                       // RTN_LOCAL for the "local …" entries above
    }
    return ret;
}
```

The match is true when the lookup returns `RTN_LOCAL`, which is the `local` keyword at the start of
each line of `ip route show table local`.

- **A NodePort answers on every address in that table:** `172.19.0.2`, `10.42.0.1` (`cni0`),
  `10.42.0.0` (`flannel.1`) and anything in `127.0.0.0/8` (for 127.x, through the separate
  `-d 127.0.0.0/8` rule and `route_localnet`, part 8). Add a new address to the node and NodePorts
  work on it straight away, without kube-proxy changing anything. Tested on np-agent2 with a
  NodePort Service `lab3/c-np` (30099):

  | Step | `local` table has 192.0.2.50? | iptables rules mentioning it | `wget 192.0.2.50:30099` on the node |
  |---|---|---|---|
  | before | no | 0 | timed out |
  | `ip addr add 192.0.2.50/32 dev lo` | yes, `local 192.0.2.50 dev lo proto kernel` | 0 | **`Hostname: c`** |
  | `ip addr del …` | no | 0 | timed out |

  (The first try used `shop/backend:30080` and got "Connection refused". The NodePort matched, but
  backend's NetworkPolicies rejected traffic from another node; see network-policy.md §4.)
- **A cloud LB IP like `203.0.113.10` isn't in the table,** so it doesn't match LOCAL. That's why
  it needs its own `-d 203.0.113.10` rule further up in `KUBE-SERVICES` (part 16).

**8. The trace again, rule by rule: what each NodePort rule does.** Two
requests, with the nat counters zeroed before each and read per rule:

- **A:** from an outside client, `172.17.0.4 → 172.17.0.3:30080`.
- **B:** from the node itself, to `127.0.0.1:30080`.

The 4 rules, numbered as the kernel shows them:

```
nat KUBE-NODEPORTS
 #1  -d 127.0.0.0/8  tcp dpt:30080  -j KUBE-EXT-6V2X…        ← rule 1: localhost:30080
 #2                  tcp dpt:30080  -j KUBE-EXT-6V2X…        ← rule 2: any other node IP:30080
nat KUBE-EXT-6V2X…  (a chain created for this Service)
 #1  -j KUBE-MARK-MASQ                                        ← rule 3: mark "SNAT me"
 #2  -j KUBE-SVC-6V2X…                                        ← rule 4: hand over to the ClusterIP's chain
```

Everything else the packet passes through is either shared (`KUBE-SERVICES`'
last rule, `KUBE-MARK-MASQ`, `KUBE-POSTROUTING`) or belongs to the ClusterIP and
is reused (`KUBE-SVC`, `KUBE-SEP`).

*A. From outside: `172.17.0.4:59608 → 172.17.0.3:30080`*

```
rule                               pkts
KUBE-SERVICES #last                1     shared: dst is a node IP → KUBE-NODEPORTS
KUBE-NODEPORTS #1                  0     rule 1: not 127.x → skipped
KUBE-NODEPORTS #2                  1     rule 2: dpt 30080 → KUBE-EXT
KUBE-EXT #1                        1     rule 3: → KUBE-MARK-MASQ
KUBE-MARK-MASQ #1                  1     shared: mark 0x4000
KUBE-EXT #2                        1     rule 4: → KUBE-SVC
KUBE-SVC #1                        0     ClusterIP-only rule: needs dst 10.43.208.17 → skipped
KUBE-SVC #2                        0     random 0.5 → lost the toss
KUBE-SVC #3                        1     → KUBE-SEP-R7HK (10.42.0.6)
KUBE-SEP-R7HK #2                   1     DNAT → 10.42.0.6:80
KUBE-POSTROUTING #3                1     shared: marked → MASQUERADE
```

Step by step:

1. **The packet arrives on `eth0`** (`src 172.17.0.4:59608, dst 172.17.0.3:30080`)
   and reaches the PREROUTING hook. On a new connection, the nat callback runs
   `PREROUTING → KUBE-SERVICES`.
2. **`KUBE-SERVICES` (shared).** No ClusterIP rule matches: the destination is
   172.17.0.3, not a ClusterIP. The last rule, `--dst-type LOCAL`, matches
   because 172.17.0.3 belongs to the node. Jump to `KUBE-NODEPORTS`.
3. **`KUBE-NODEPORTS` #1, rule (1):** `-d 127.0.0.0/8`. The destination is
   172.17.0.3, so no match (counter 0).
4. **`KUBE-NODEPORTS` #2, rule (2):** `tcp dpt:30080`. It matches. This is the rule
   that ties port 30080 to this Service, so without it the node wouldn't know
   30080 means `np-lab/web`. Jump to `KUBE-EXT-6V2X…`.
5. **`KUBE-EXT` #1, rule (3):** `-j KUBE-MARK-MASQ`. The packet gets mark `0x4000`.
   Nothing about the packet changes yet. The mark is a note for POSTROUTING:
   "this came in through a NodePort, rewrite its source on the way out". Without
   this rule, the Pod would reply to 172.17.0.4 directly from 10.42.0.6. On a
   multi-node cluster that reply would skip this node, and the client would drop
   it.
6. **`KUBE-EXT` #2, rule (4):** `-j KUBE-SVC-6V2X…`. This is where the NodePort
   joins the ClusterIP. From here, the packet takes exactly the chains a
   ClusterIP packet takes.
7. **`KUBE-SVC` (reused from the ClusterIP):**
   - #1 is the ClusterIP's own masquerade check (`-d 10.43.208.17`). Counter 0:
     the destination is still the node IP, because DNAT hasn't happened yet.
     NodePort traffic is marked in rule (3) instead.
   - #2 is `random 0.5` to the 10.42.0.5 Pod. Counter 0: it lost the coin toss.
     The counter only counts packets that matched.
   - #3 is the remaining endpoint, the 10.42.0.6 Pod. Counter 1.
8. **`KUBE-SEP-R7HK` #2 (reused):** DNAT to `10.42.0.6:80`. The packet is now
   `172.17.0.4:59608 → 10.42.0.6:80`, still carrying mark `0x4000`.
9. **Routing:** 10.42.0.6 is on `cni0`, so the packet is forwarded: FORWARD hook,
   then `KUBE-FORWARD` (shared) accepts it because of the mark.
10. **POSTROUTING, `KUBE-POSTROUTING` (shared):**
    - #1 (`RETURN` if not marked): no match, because ours is marked. Its counter
      of 2 is other, unmarked traffic leaving the node in that moment.
    - #2: clears the mark.
    - #3: `MASQUERADE` rewrites the source to `cni0`'s address. The packet is now
      `10.42.0.1:20485 → 10.42.0.6:80`, the result of rule (3)'s mark.
11. **The Pod's view:** `RemoteAddr: 10.42.0.1:20485`. Conntrack has recorded both
    changes:

    ```
    src=172.17.0.4 dst=172.17.0.3 sport=59608 dport=30080   src=10.42.0.6 dst=10.42.0.1 sport=80 dport=20485
    ```

The reply `10.42.0.6:80 → 10.42.0.1:20485` comes back to this node, conntrack
reverses both changes, and the client sees `172.17.0.3:30080 → 172.17.0.4:59608`.

*B. From the node itself: `127.0.0.1 → 127.0.0.1:30080`*

```
rule                               pkts
KUBE-SERVICES #last                1     shared: 127.0.0.1 is LOCAL too
KUBE-NODEPORTS #1                  1     rule 1: -d 127.0.0.0/8 → KUBE-EXT     ← this time rule 1 matched
KUBE-NODEPORTS #2                  0     never reached
KUBE-EXT #1 / KUBE-MARK-MASQ       1     rule 3: mark
KUBE-EXT #2                        1     rule 4: → KUBE-SVC
KUBE-SVC #3 → KUBE-SEP-R7HK #2     1     DNAT → 10.42.0.6:80
KUBE-POSTROUTING #3                1     MASQUERADE
```

The differences from A:

- **It enters through the OUTPUT hook**, because the node itself created the
  packet: `-A OUTPUT -j KUBE-SERVICES`. From there the chains are the same.
- **Rule (1) matches instead of rule (2).** The localhost rule exists because 127.x
  addresses are special: Linux normally refuses to route them anywhere other
  than `lo`. kube-proxy turns on `route_localnet=1` (it read `all=1` here) so a
  `127.0.0.1` packet can be DNATed to a Pod. In the real kube-proxy rule, it's
  also counted separately (`nfacct localhost_nps_accepted_pkts`).
- **Rule (3)'s mark is essential here, even on one node.** The packet's source is
  `127.0.0.1`. Without MASQUERADE, the Pod would reply to `127.0.0.1`, which is the
  Pod itself. SNAT to `10.42.0.1` makes the reply come back to the node:

  ```
  RemoteAddr: 10.42.0.1:10151
  conntrack: src=127.0.0.1 dst=127.0.0.1 sport=37770 dport=30080   src=10.42.0.6 dst=10.42.0.1 sport=80 dport=10151
  ```

*What each NodePort rule contributes:*

| Rule | Chain | Its job in the path | If it were missing |
|---|---|---|---|
| (1) `-d 127.0.0.0/8 --dport 30080 -j KUBE-EXT` | `KUBE-NODEPORTS` | catches `localhost:30080` from the node itself | `curl 127.0.0.1:30080` on the node fails; other addresses still work |
| (2) `--dport 30080 -j KUBE-EXT` | `KUBE-NODEPORTS` | ties port 30080 on any node IP to this Service | no NodePort at all: the packet falls out of `KUBE-NODEPORTS` and finds nothing listening on 30080 |
| (3) `-j KUBE-MARK-MASQ` | `KUBE-EXT` | marks the packet so POSTROUTING rewrites its source | replies would go straight from the Pod to the client and be dropped (multi-node), and localhost requests would break |
| (4) `-j KUBE-SVC` | `KUBE-EXT` | hands the packet to the ClusterIP's load balancing and DNAT | the packet is marked but never DNATed |

**9. `KUBE-MARK-MASQ` isn't created by the NodePort.** `KUBE-MARK-MASQ` is a
single shared chain that kube-proxy creates on every node, Services or not (it
was in the dump taken before `np-lab/web` existed). Many rules jump to it, and
NodePort is just one of them. On this node, 14 rules jump to it, and only 1
comes from the NodePort:

```
:KUBE-MARK-MASQ - [0:0]
-A KUBE-MARK-MASQ -j MARK --set-xmark 0x4000/0x4000      ← the whole chain: one rule, "set bit 0x4000"

rules that jump to it, by chain kind:
      6 KUBE-SVC-…     ← one per Service port (ClusterIP)
      7 KUBE-SEP-…     ← one per endpoint
      1 KUBE-EXT-…     ← the NodePort's
```

The chain itself is just "set the mark". What varies is why each caller jumps to
it. Every case has the same underlying problem: a reply that wouldn't come back
through this node unless the source is rewritten.

*1. `KUBE-SVC`: a ClusterIP called from outside the Pod network.* There's one per
Service port. The 6 here are the Services already in k3s (kubernetes,
kube-dns ×3, metrics-server) plus `np-lab/web`:

```
-A KUBE-SVC-6V2X… ! -s 10.42.0.0/16 -d 10.43.208.17/32 … --dport 80 -j KUBE-MARK-MASQ
```

The source isn't a Pod, for example a process on the node calling the
ClusterIP. Without SNAT the Pod's reply might not route back correctly, so it's
marked.

*2. `KUBE-SEP`: hairpin, a Pod calling its own Service and landing on itself.*
There's one per endpoint (7 here):

```
-A KUBE-SEP-HBZK… -s 10.42.0.5/32 … -j KUBE-MARK-MASQ
```

If 10.42.0.5 calls the Service and the DNAT sends it back to 10.42.0.5, the Pod
would get a packet from itself and reply to itself, never reaching the
connection it opened. SNAT to the node's address makes the reply go through the
node, where conntrack fixes it.

*3. `KUBE-EXT`: NodePort and LoadBalancer traffic.* This is the one the NodePort
added:

```
-A KUBE-EXT-6V2X… … "masquerade traffic for np-lab/web:http external destinations" -j KUBE-MARK-MASQ
```

The reply must come back through the node that received the request, as in the
trace (step 5).

Other callers don't exist on this node, because it has no such Services. These
are from kube-proxy's code, not tested here:

- **`KUBE-FW-…`:** LoadBalancer traffic sent to the load balancer's IP.
- **external IPs:** rules for `spec.externalIPs`.
- **`externalTrafficPolicy: Local`:** as seen earlier, `KUBE-EXT` still marks
  traffic from the node itself (`--src-type LOCAL`).

Splitting it into a mark and a later step is what lets one shared chain serve
every Service. Each caller only decides whether SNAT is needed, and the shared
`KUBE-POSTROUTING` does the SNAT once, in POSTROUTING, for any marked packet.

**10. Why MASQUERADE picked 10.42.0.1.** MASQUERADE doesn't use a fixed address.
It takes the address of the interface the packet is about to leave through.

*The node has a bridge for its Pods: `cni0`, address 10.42.0.1.*

```
### the node's interfaces
  lo         127.0.0.1/8
  eth0       172.17.0.3/16     ← the node's "outside" address (Docker network)
  flannel.1  10.42.0.0/32      ← flannel's tunnel to other nodes
  cni0       10.42.0.1/24      ← a bridge for this node's Pods
### who is plugged into cni0
  veth1122e7bf  veth47f75644  veth827d2418  vethcc2de0fc  vethe00ba3b6     ← one veth per Pod
```

The CNI plugin, flannel in k3s, gives this node the Pod range `10.42.0.0/24` and
puts the bridge on its first address:

```
/run/flannel/subnet.env
FLANNEL_SUBNET=10.42.0.1/24        ← this node's Pod range; cni0 takes .1
```

Each Pod gets a veth pair: one end in the Pod as its `eth0` (10.42.0.6), the
other plugged into `cni0`. The CNI config also says `"isDefaultGateway": true`
(`/var/lib/rancher/k3s/agent/etc/cni/net.d/10-flannel.conflist`), so every Pod's
default route points at `10.42.0.1`. From inside a Pod, 10.42.0.1 is the node,
as its router.

*After DNAT, routing sends the packet out of `cni0`.* After DNAT the packet's
destination is `10.42.0.6`. The routing decision (step 9 of the trace) looks it
up:

```
### the node's routing table
  default via 172.17.0.1 dev eth0
  10.42.0.0/24 dev cni0 proto kernel scope link src 10.42.0.1     ← matches 10.42.0.6
  172.17.0.0/16 dev eth0 proto kernel scope link src 172.17.0.3

### what the kernel decides for the DNATed packet
  10.42.0.6 dev cni0 src 10.42.0.1
```

So the outgoing interface is `cni0`, and the kernel's preferred source address
for that route is `10.42.0.1`.

*MASQUERADE takes the outgoing interface's address* (`net/netfilter/nf_nat_masquerade.c`):

```c
newsrc = inet_select_addr(out, nh, RT_SCOPE_UNIVERSE);   // out = cni0  →  10.42.0.1
```

So `10.42.0.1` is cni0's address, picked because the packet leaves through cni0.
The port `20485` is just a free port chosen at random (`--random-fully`).

```
before POSTROUTING:  172.17.0.4:59608 → 10.42.0.6:80     (DNAT done, source untouched)
MASQUERADE:          out = cni0 → its address 10.42.0.1, random port 20485
after:               10.42.0.1:20485  → 10.42.0.6:80
```

*Why that's the right address.* The Pod replies to `10.42.0.1:20485`. That's its
own default gateway, the node, so the reply is guaranteed to reach the node.
There, conntrack matches it and reverses both translations, and the result goes
out of `eth0` to the client:

```
### route for the reply after conntrack rewrites it to dst 172.17.0.4
  172.17.0.4 dev eth0 src 172.17.0.3
```

The address depends on where the backend is, because MASQUERADE uses whichever
interface the route picks:

| Backend Pod is | Route to it | Outgoing interface | Source after MASQUERADE |
|---|---|---|---|
| on this node (this lab) | `10.42.0.0/24 dev cni0` | `cni0` | **10.42.0.1** |
| on another node | `10.42.1.0/24 via 10.42.1.0 dev flannel.1` | `flannel.1` | **10.42.0.0** (flannel.1's address) |
| outside the cluster (e.g. Pod traffic to the internet) | `default via 172.17.0.1 dev eth0` | `eth0` | **172.17.0.3** |

Only the first row was tested (this cluster has one node); the other two follow
from the same `inet_select_addr(out, …)`. That's also why the minikube lab in
§4.3 showed `10.244.120.64`: its node's address on the interface facing the Pod.

*MASQUERADE vs plain SNAT.* MASQUERADE is the kind of SNAT that picks the
address automatically from the outgoing interface. Plain SNAT uses an address
you write in the rule. kube-proxy uses MASQUERADE.

| Target | New source address | Typical use |
|---|---|---|
| `-j MASQUERADE` (nft: `masquerade`) | the outgoing interface's address, looked up for each new connection | kube-proxy, home routers, anywhere the address isn't fixed or known in advance |
| `-j SNAT --to-source 203.0.113.10` (nft: `snat to 203.0.113.10`) | exactly the address in the rule (or a range) | a fixed public IP on a gateway, egress gateways, cloud NAT |

How MASQUERADE picks "the interface's address" (`nf_nat_masquerade.c`):

```c
newsrc = inet_select_addr(out, nh, RT_SCOPE_UNIVERSE);
```

- **`out`** is the interface the route chose: `cni0` here.
- **`nh`** is the next hop. If the interface has several addresses,
  `inet_select_addr` prefers one in the same subnet as the next hop. With one
  address, as on `cni0`, it simply takes that one.
- **If the interface has no usable address, MASQUERADE drops the packet**
  (`"%s ate my IP address"`, `NF_DROP`).
- **It also remembers the interface** in the conntrack entry
  (`nat->masq_index = out->ifindex`). If that interface goes down, the kernel
  deletes those connections, since their source address may no longer be valid.
  Plain SNAT keeps its connections, because its address doesn't depend on any
  interface.

kube-proxy needs MASQUERADE because one rule has to give the right source for
every destination on every node: `cni0`'s address for a local Pod, `flannel.1`'s
for a Pod on another node, `eth0`'s for traffic leaving the cluster (the table
above). A fixed `--to-source` would be wrong for most of them.

**11. The packet itself, before and after each NAT (capture).** One more
connection (new ports, same path), captured with `tcpdump -i any` from a helper
container sharing the node's network namespace (`docker run --net
container:<node>`), SYN and SYN-ACK only.

*The real packets, on every interface:*

```
                     interface      packet as seen there
SYN      1 22:48:57.685778  eth0 In        172.17.0.4.52664 > 172.17.0.3.30080   [S]    ← as the client sent it
         2 22:48:57.685827  cni0 Out       10.42.0.1.46374  > 10.42.0.6.80       [S]    ← after DNAT and SNAT
         3 22:48:57.685834  veth827… Out   10.42.0.1.46374  > 10.42.0.6.80       [S]    ← into the Pod
SYN-ACK  4 22:48:57.686170  veth827… P     10.42.0.6.80     > 10.42.0.1.46374    [S.]   ← the Pod's reply, untouched
         5 22:48:57.686174  cni0 In        10.42.0.6.80     > 172.17.0.4.52664   [S.]   ← half undone: destination fixed
         6 22:48:57.686177  eth0 Out       172.17.0.3.30080 > 172.17.0.4.52664   [S.]   ← fully undone: as the client expects
```

The whole round trip took 0.4 ms, about 50 µs of it inside the node for the
SYN. In an earlier capture, the TCP sequence numbers were identical on `eth0` and
`cni0` (`seq 100306788`): NAT changes only addresses, ports and checksums.

*The SYN, step by step* (the mark and the conntrack entry aren't visible in a
capture; they follow from the rules and code above):

```
                          IP/TCP header                          skb->mark   conntrack entry
1 arrives on eth0         172.17.0.4:52664 → 172.17.0.3:30080    0x0         (none yet)

  PREROUTING  -200 conntrack: new entry, not yet in the table
                                                                             ORIG  172.17.0.4:52664 → 172.17.0.3:30080
                                                                             REPLY 172.17.0.3:30080 → 172.17.0.4:52664
              -100 nat: KUBE-EXT marks, KUBE-SEP chooses DNAT
                          the NAT core rewrites the destination
                          (and fixes the IP and TCP checksums)
  after DNAT:             172.17.0.4:52664 → 10.42.0.6:80        0x4000      REPLY 10.42.0.6:80 → 172.17.0.4:52664   (IPS_DST_NAT)

  routing: 10.42.0.6 → dev cni0  → FORWARD: KUBE-FORWARD sees 0x4000 → ACCEPT

  POSTROUTING +100 nat: KUBE-POSTROUTING sees 0x4000 → clears it → MASQUERADE
                          the NAT core rewrites the source
  after SNAT:             10.42.0.1:46374  → 10.42.0.6:80        0x0         REPLY 10.42.0.6:80 → 10.42.0.1:46374    (+IPS_SRC_NAT)
              max  nf_confirm: the entry goes into the table, findable by both tuples

2 leaves on cni0 / 3 veth   10.42.0.1:46374 → 10.42.0.6:80
```

So at step 8 of the trace, just after the DNAT rule:

- **The header** is `172.17.0.4:52664 → 10.42.0.6:80`: the destination is the Pod,
  and the source is still the real client.
- **The mark** is `0x4000`, a note in the kernel's copy of the packet, never on
  the wire.
- **The conntrack entry** exists but isn't in the table yet. Its reply tuple
  already says replies will come from `10.42.0.6:80`.

The capture never shows that in-between state: it only exists inside the node,
between PREROUTING and POSTROUTING.

*The reply is translated in two steps, which is why (5) is half done.* The NAT
core changes **destinations at PREROUTING and OUTPUT**, and **sources at
POSTROUTING and INPUT**, in both directions. For the reply, undoing the SNAT
means changing the destination, and undoing the DNAT means changing the source:

```
4 from the Pod (veth)     10.42.0.6:80 → 10.42.0.1:46374      matches the REPLY tuple

  PREROUTING  (here run on the bridge, via br_netfilter: bridge-nf-call-iptables = 1)
              -100 NAT core, destination side: undo the SNAT
5 seen on cni0            10.42.0.6:80 → 172.17.0.4:52664     ← destination already the client's

  routing: 172.17.0.4 → dev eth0 → FORWARD

  POSTROUTING +100 NAT core, source side: undo the DNAT
6 leaves on eth0          172.17.0.3:30080 → 172.17.0.4:52664 ← source is the address the client called
```

No rules run for the reply: the NAT core compares the header with the
connection's two tuples and applies the difference at each of its hooks. It's
visible half done on `cni0 In` because of `br_netfilter`: the bridge runs the
IPv4 PREROUTING hook as the Pod's packet enters it from the veth, before `cni0`'s
receive path, where tcpdump sees it.

The `mark=0` in the conntrack line (`… [ASSURED] mark=0 zone=0 use=2`) is the
**conntrack** mark (`ct mark`), a separate number stored on the connection.
kube-proxy's `0x4000` is the **packet** mark (`skb->mark`), which lives on one
packet only and was cleared in `KUBE-POSTROUTING`. tcpdump can't show the packet
mark, and conntrack doesn't record it.

**12. Both DNAT and SNAT, and what each side sees.** In plain IP routing a
packet's addresses never change from end to end; NAT is the deliberate
exception. For this request (NodePort, `externalTrafficPolicy: Cluster`, from
outside), both happen to the first packet, at different hooks:

```
client sends        172.17.0.4:52664 → 172.17.0.3:30080
DNAT  (PREROUTING)  172.17.0.4:52664 → 10.42.0.6:80        destination: node:30080 → Pod:80   (KUBE-SEP, DNAT)
SNAT  (POSTROUTING) 10.42.0.1:46374  → 10.42.0.6:80        source: client → node              (KUBE-POSTROUTING, MASQUERADE)
```

The request and the reply are two different packets. The reply is addressed
back to whoever sent the request, as the Pod received it:

```
REQUEST (client → Pod)
  client sends:          172.17.0.4:52664 → 172.17.0.3:30080
  DNAT  (PREROUTING):    172.17.0.4:52664 → 10.42.0.6:80        ← DESTINATION changed (node → Pod)
  SNAT  (POSTROUTING):   10.42.0.1:46374  → 10.42.0.6:80        ← SOURCE changed (client → node)
  the Pod receives:      10.42.0.1:46374  → 10.42.0.6:80

REPLY (Pod → client): a new packet, not the request changed
  the Pod sends:         10.42.0.6:80     → 10.42.0.1:46374     ← the request's addresses swapped (not NAT)
  undo SNAT (PREROUTING): 10.42.0.6:80    → 172.17.0.4:52664    ← DESTINATION changed (node → client)
  undo DNAT (POSTROUTING): 172.17.0.3:30080 → 172.17.0.4:52664  ← SOURCE changed (Pod → node)
  the client receives:   172.17.0.3:30080 → 172.17.0.4:52664
```

Each endpoint sees a consistent conversation: the client talks to
`172.17.0.3:30080` both ways, and the Pod talks to `10.42.0.1:46374` both ways.
Only the node, with its conntrack entry, sees both views.

With the default `externalTrafficPolicy: Cluster`, which this capture used, the
Pod sees the request as coming from `10.42.0.1`, the node. The client's address
`172.17.0.4` was replaced by SNAT before the packet reached the Pod. The Pod has
no way to find out that the real client was `172.17.0.4`. That information only
exists in the node's conntrack entry.

It does see `172.17.0.4` with `externalTrafficPolicy: Local`. There's no SNAT
then: `KUBE-EXT` skips `KUBE-MARK-MASQ` for external traffic (part 4):

```
externalTrafficPolicy: Local  →  RemoteAddr: 172.17.0.4:59912      ← the real client
KUBE-POSTROUTING:  1 RETURN   0 MARK   0 MASQUERADE                ← not SNATed
```

| `externalTrafficPolicy` | Source address the Pod sees | Why |
|---|---|---|
| `Cluster` (default) | `10.42.0.1` (the node) | SNAT, so the reply returns through this node |
| `Local` | `172.17.0.4` (the real client) | no SNAT; the Pod is on this node, so the reply comes back through it anyway |

It isn't always both. **DNAT always happens** for traffic to a Service (that's
how the Service address becomes a Pod address). **SNAT happens only when the
packet was marked:**

| Traffic | DNAT | SNAT | Source the Pod sees |
|---|---|---|---|
| NodePort, `externalTrafficPolicy: Cluster` (this lab) | ✅ | ✅ (`KUBE-EXT` marks it) | the node (`10.42.0.1`) |
| NodePort, `externalTrafficPolicy: Local` | ✅ | ❌ | the real client (`172.17.0.4`) |
| ClusterIP, called from a Pod | ✅ | ❌ (`! -s 10.42.0.0/16` doesn't match) | the calling Pod |
| ClusterIP, called from the node itself | ✅ | ✅ (not from the Pod network, so marked) | the node |
| ClusterIP, a Pod reaching itself (hairpin) | ✅ | ✅ (`KUBE-SEP` marks it) | the node |

**13. Where SNAT happens vs whether it happens.** Two separate questions: SNAT
happens in POSTROUTING because the packet is leaving the node, but it happens
to this packet because of the mark. Being forwarded isn't enough.

*Where: every packet leaving the node passes POSTROUTING.*

```
packet arrives ─► PREROUTING ─► routing ─┬─► INPUT ─► a process on the node             (no POSTROUTING)
                  (DNAT here)            └─► FORWARD ─► POSTROUTING ─► out an interface   ← our packet
a process on the node sends ─► OUTPUT ─► routing ─► POSTROUTING ─► out an interface
```

Our packet was forwarded (in on `eth0`, out on `cni0`), so it passed
POSTROUTING. That's the right place for SNAT for two reasons:

- MASQUERADE needs to know which interface the packet leaves through, and that's
  only decided by routing, after PREROUTING.
- The kernel only allows MASQUERADE in POSTROUTING (`xt_MASQUERADE.c`:
  `.hooks = 1 << NF_INET_POST_ROUTING`).

*Whether: only marked packets are SNATed.* Passing POSTROUTING doesn't mean
being SNATed. `KUBE-POSTROUTING` checks the mark first:

```
-A KUBE-POSTROUTING -m mark ! --mark 0x4000/0x4000 -j RETURN     ← not marked: leave the source alone
…
-A KUBE-POSTROUTING … -j MASQUERADE --random-fully                ← only marked packets get here
```

The lab shows forwarded packets that weren't SNATed:

- In the same trace (part 8), `KUBE-POSTROUTING #1 (RETURN) = 2`: two other,
  unmarked packets left the node through POSTROUTING, sources unchanged.
- With `externalTrafficPolicy: Local` (part 4), the same NodePort request, also
  forwarded `eth0 → cni0`, passed POSTROUTING with `1 RETURN, 0 MASQUERADE`, and
  the Pod saw `172.17.0.4`.

| | Forwarded? | Passes POSTROUTING? | Marked? | SNATed? |
|---|---|---|---|---|
| NodePort, ETP Cluster (this trace) | yes | yes | yes (`KUBE-EXT`) | **yes** |
| NodePort, ETP Local | yes | yes | no | no |
| Pod → ClusterIP → Pod on another node | yes | yes | no | no |
| node itself → ClusterIP | no (sent by the node) | yes, via OUTPUT | yes (`KUBE-SVC` #1) | **yes** |

So SNAT is applied in POSTROUTING because that's where the outgoing interface is
known. It's applied to this packet because `KUBE-EXT` marked it in PREROUTING,
when the node could still tell it came in through a NodePort.

**14. The INPUT hook and Services.** In iptables mode, a packet to a Service is
DNATed in PREROUTING. If the Pod it lands on uses its own Pod IP, as most do, the
packet is no longer addressed to the node, so it goes FORWARD → POSTROUTING and
never reaches INPUT. Every trace in this lab went that way.

INPUT is still used. It handles anything that is, or stays, addressed to the
node. These are the rules on this node's INPUT hook:

```
filter INPUT
-A INPUT … "kube-router netpol" -j KUBE-ROUTER-INPUT                                   ← k3s NetworkPolicy, for traffic to the node
-A INPUT --ctstate NEW … "kubernetes load balancer firewall" -j KUBE-PROXY-FIREWALL     ← loadBalancerSourceRanges
-A INPUT … "kubernetes health check service ports" -j KUBE-NODEPORTS                    ← health-check NodePorts (ETP Local LBs)
-A INPUT --ctstate NEW … "kubernetes externally-visible service portals" -j KUBE-EXTERNAL-SERVICES   ← Services with no endpoints
-A INPUT -j KUBE-FIREWALL                                                                ← protect 127.0.0.0/8
nat INPUT
(none)
```

When Service traffic does reach INPUT:

1. **The Service has no endpoints.** With no Pods there's no DNAT, so a NodePort
   packet is still addressed to the node and goes to INPUT. There
   `KUBE-EXTERNAL-SERVICES` rejects it. Lab: with the Deployment scaled to 0 (under
   both traffic policies), kube-proxy wrote

   ```
   -A KUBE-EXTERNAL-SERVICES -p tcp … "np-lab/web:http has no endpoints" -m addrtype --dst-type LOCAL … --dport 30080 -j REJECT --reject-with icmp-port-unreachable
   -A KUBE-SERVICES -d 10.43.208.17/32 -p tcp … "np-lab/web:http has no endpoints" … --dport 80 -j REJECT --reject-with icmp-port-unreachable
   ```

   and the client got `wget: can't connect to remote host (172.17.0.3): Connection refused`.
2. **The endpoint is on the node's own IP (hostNetwork Pods).** If the Pod uses
   `hostNetwork: true`, DNAT points at the node's address, so after routing the
   packet is local and goes to INPUT and then to the socket. Examples: the
   `kubernetes` Service, whose endpoint is the API server on a node IP; ingress
   controllers or CNI agents running with hostNetwork.
3. **kube-proxy's own health-check port.** For a `LoadBalancer` Service with
   `externalTrafficPolicy: Local`, kube-proxy listens on the
   `healthCheckNodePort`. The filter `KUBE-NODEPORTS` chain on INPUT lets those
   probes through to kube-proxy.
4. **`loadBalancerSourceRanges`.** If the load balancer's IP is on the node,
   `KUBE-PROXY-FIREWALL` drops sources outside the allowed ranges here.
5. **`KUBE-FIREWALL`, a safety rule.** kube-proxy turns on `route_localnet` for
   localhost NodePorts, which opens a hole: a remote host could send packets to
   `127.0.0.1`. This rule drops anything to `127.0.0.0/8` that didn't come from
   127.x, unless it was DNATed by a NodePort rule (`! --ctstate … DNAT`):

   ```
   -A KUBE-FIREWALL ! -s 127.0.0.0/8 -d 127.0.0.0/8 … "block incoming localnet connections" -m conntrack ! --ctstate RELATED,ESTABLISHED,DNAT -j DROP
   ```
6. **nat INPUT is empty.** The NAT core has a callback at INPUT (SNAT at +100,
   `nf_nat_ipv4_local_in`), but kube-proxy writes no rules there.

In **IPVS mode** INPUT is the main hook (from kube-proxy's IPVS docs; not tested,
this node runs iptables mode). kube-proxy puts every ClusterIP on a dummy
interface, `kube-ipvs0`, so Service addresses count as the node's own. A packet
to a ClusterIP is "for this host" and goes to INPUT, where IPVS (hooked at
`NF_INET_LOCAL_IN`) picks a Pod and sends it on.

| Hook | iptables mode (this lab) | IPVS mode |
|---|---|---|
| PREROUTING | DNAT for all Service traffic | marks only (no DNAT rules) |
| INPUT | edge cases: no endpoints, hostNetwork endpoints, health checks, firewall | **the main hook**: IPVS takes Service traffic here |
| FORWARD | Service traffic to Pods | traffic IPVS has already sent on |
| POSTROUTING | SNAT (MASQUERADE) | SNAT (MASQUERADE) |

**15. Cluster vs Local on two nodes: the four cases.** This lab has one node, so
the Pod was always local. The limitation of `externalTrafficPolicy: Local` only
shows with a node that has no Pod. Picture two nodes, and one Pod, running only
on node B:

```
client 203.0.113.50
                     node A  10.0.0.1      (no Pod here)
                     node B  10.0.0.2      Pod P  10.244.2.5:80
NodePort 30080 is open on BOTH nodes (kube-proxy runs on every node)
```

The client can send to `10.0.0.1:30080` or `10.0.0.2:30080`. Both are valid
NodePort addresses, and nothing in Kubernetes picks one: the client uses whichever
address it was given (DNS, an external load balancer, a list of node IPs). What
happens depends on which node it hits and on the policy.

*How node A knows Pod P exists, even though P is on node B.* kube-proxy on every
node reads the Service's EndpointSlice, which lists all ready Pods in the
cluster, whatever node they're on. So node A's rules include Pod P even though
it isn't local:

```
EndpointSlice for the Service:  endpoints: 10.244.2.5 (nodeName: node-b)

node A's rules (written by node A's kube-proxy):
-A KUBE-SVC-XXXX … -j KUBE-SEP-PPPP                  ← the only endpoint: Pod P
-A KUBE-SEP-PPPP -p tcp -j DNAT --to-destination 10.244.2.5:80
```

With `externalTrafficPolicy: Cluster`, `KUBE-SVC` uses every endpoint, so node A
DNATs to Pod P. (With Local, node A uses `KUBE-SVL`, which only lists Pods on
node A. For node A that's empty, and the packet is dropped: case 4.)

*How the packet gets from node A to node B: ordinary routing.* After DNAT, the
destination is `10.244.2.5`. Node A's routing decision handles it like any other
address. The CNI gives each node its own Pod range (from `node.spec.podCIDR`) and
installs a route on every node for the other nodes' ranges:

```
node A's routing table (native routing, e.g. a cloud VPC or Calico/flannel host-gw)
  10.244.1.0/24 dev cni0                 ← node A's own Pods
  10.244.2.0/24 via 10.0.0.2 dev eth0    ← node B's Pods: send to node B
  default via 10.0.0.254 dev eth0

ip route get 10.244.2.5  →  via 10.0.0.2 dev eth0      ← "send it to node B"
```

So node A forwards the packet to node B (FORWARD, then POSTROUTING, then out of
`eth0`). Node B receives a packet for one of its own Pods, routes it to `cni0`,
and Pod P gets it. Node B runs no Service rules on it: it's already addressed to
a Pod IP.

```
client ─► node A                                   ─► node B             ─► Pod P
          PREROUTING: DNAT → 10.244.2.5 (Pod P)        route: 10.244.2.0/24
          routing:   10.244.2.0/24 via node B             is mine → cni0
          POSTROUTING: MASQUERADE (marked)
```

With an overlay such as flannel VXLAN, the route says
`10.244.2.0/24 via 10.244.2.0 dev flannel.1`. Node A wraps the packet in UDP and
sends it to node B's real address, and node B unwraps it. The decision is the
same, only the transport differs.

*The SNAT address depends on that route.* MASQUERADE takes the address of the
interface the packet leaves through (part 10). So on node A the new source
depends on how the CNI reaches node B:

| How node A reaches node B's Pods | Outgoing interface on node A | Source after MASQUERADE |
|---|---|---|
| native routing (`via 10.0.0.2 dev eth0`) | `eth0` | `10.0.0.1`, node A's address |
| flannel VXLAN (`dev flannel.1`) | `flannel.1` | node A's `flannel.1` address, e.g. `10.244.1.0` |

The cases below assume native routing. Either way, the reply works the same way:

1. Pod P replies to the source it saw, an address belonging to node A.
2. Node B routes that reply back to node A.
3. Node A's conntrack entry matches it, undoes both translations, and sends it to
   the client from `10.0.0.1:30080`.

Without the SNAT, Pod P would reply directly to `203.0.113.50`. That reply would
go out through node B's default route, never passing node A, and the client
would get an answer from an address it never contacted.

*Case 1: Cluster (default), client hits node A, which has no Pod.*

```
client ──► node A:30080
             KUBE-SVC: choose from ALL Pods in the cluster → Pod P (on node B)
             DNAT: destination → 10.244.2.5:80
             route: 10.244.2.0/24 via 10.0.0.2 dev eth0 → forward to node B
             SNAT: source 203.0.113.50 → 10.0.0.1 (node A's eth0)   ← needed, see below
           ──► node B ──► Pod P
Pod P sees the request coming from 10.0.0.1 (node A).   Works ✓
Reply: Pod P → 10.0.0.1 (node A) → conntrack undoes both → node A → client
```

Node A has to SNAT. Without it, Pod P would reply straight to `203.0.113.50`,
from its own IP. The client is waiting for an answer from `10.0.0.1:30080`, not
from `10.244.2.5:80`, so it would throw the reply away. SNAT makes the reply
return to node A, which translates it back.

*Case 2: Cluster, client hits node B, which has the Pod.*

```
client ──► node B:30080
             KUBE-SVC: choose from ALL Pods → Pod P (local)
             DNAT → 10.244.2.5:80,  SNAT → node B's address
           ──► Pod P
Pod P sees the request coming from node B.   Works ✓
```

This is what this lab did: one node, so the Pod was always local. The Pod still
saw the node's address (`10.42.0.1`), never the client's. So with Cluster, every
node works, but the Pod never sees the real client IP.

*Case 3: Local, client hits node B, which has the Pod.*

```
client ──► node B:30080
             KUBE-SVL: choose only from Pods ON NODE B → Pod P
             DNAT → 10.244.2.5:80,  no SNAT
           ──► Pod P
Pod P sees the request coming from 203.0.113.50 (the real client).   Works ✓
Reply: Pod P → 203.0.113.50 goes back out through node B anyway (its gateway),
       node B undoes the DNAT → client
```

No SNAT is needed: the Pod is on node B, so its reply goes back through node B by
itself. This is the `RemoteAddr: 172.17.0.4` result from the lab (part 4).

*Case 4: Local, client hits node A, which has no Pod.*

```
client ──► node A:30080
             KUBE-SVL: choose only from Pods ON NODE A → there are none
             ✗ dropped
client gets no answer (a timeout).   Fails ✗
```

Node A isn't allowed to send the request to node B. If it did, it would have to
SNAT, as in case 1, and the client IP would be lost again. That's exactly what
Local exists to avoid.

| Policy | Client hits | Result | Pod sees |
|---|---|---|---|
| Cluster | node A (no Pod) | ✓ forwarded to node B | node A's IP |
| Cluster | node B (Pod) | ✓ | node B's IP |
| Local | node B (Pod) | ✓ | **the real client IP** |
| Local | node A (no Pod) | ✗ **dropped** | — |

The last row is the limitation, and the reason Cluster is the default: Cluster
works from any node with no extra setup, while Local needs something to keep
clients off nodes without a Pod. A cloud load balancer does that by
health-checking each node's `healthCheckNodePort`, which kube-proxy answers
"OK" only where a local Pod exists. Cases 2 and 3 were tested; cases 1 and 4 follow
from the rules (`KUBE-SVC` uses every endpoint, `KUBE-SVL` only local ones) and
need a second node to test.

**16. Making it a LoadBalancer: one rule, no routes.** A LoadBalancer Service adds
one kube-proxy rule. The load balancer IP is just one more address that leads
into the chains the NodePort already created. New chains only appear with two
optional settings: `loadBalancerSourceRanges` and `externalTrafficPolicy: Local`.

*What changed when `np-lab/web` became `type: LoadBalancer`.* The Service got a
load balancer IP from k3s's built-in load balancer, ServiceLB:

```
web    LoadBalancer   10.43.208.17   172.17.0.3    80:30080/TCP
status.loadBalancer: {"ingress":[{"ip":"172.17.0.3","ipMode":"VIP"}]}
```

kube-proxy added exactly one rule. No new chain: it jumps to the NodePort's
existing `KUBE-EXT` chain. The routing table (`ip route`, and the local table)
didn't change at all.

```
+ -A KUBE-SERVICES -d 172.17.0.3/32 -p tcp … "np-lab/web:http loadbalancer IP" … --dport 80 -j KUBE-EXT-6V2XWLK7W7RZJHNE
```

```
PREROUTING → KUBE-SERVICES
   ├─ -d 10.43.208.17 --dport 80            → KUBE-SVC ───────────────────────────► KUBE-SEP (DNAT to a Pod)
   ├─ -d 172.17.0.3   --dport 80  (LB IP)   → KUBE-EXT ─┐ (mark for SNAT)              ▲
   └─ last rule: --dst-type LOCAL           → KUBE-NODEPORTS → --dport 30080 → KUBE-EXT ┘
```

So there are now three ways in to the same Service:

- the ClusterIP, `10.43.208.17:80`;
- the NodePort, any node IP on `:30080`;
- the load balancer IP, `172.17.0.3:80`.

The last two share `KUBE-EXT`, so they get the same SNAT and the same
`externalTrafficPolicy` handling. One request to `172.17.0.3:80`, with counters:

```
PREROUTING #1 → KUBE-SERVICES          1 pkt
KUBE-SERVICES "loadbalancer IP" rule   1 pkt
KUBE-EXT #1 → KUBE-MARK-MASQ           1 pkt
KUBE-EXT #2 → KUBE-SVC                 1 pkt
Pod: RemoteAddr 10.42.0.1:34710        (SNATed, as for the NodePort)
```

*Where the IP comes from, and why kube-proxy needs a rule for it.* kube-proxy
doesn't choose the IP and doesn't make it reachable. A load balancer controller
does both, and writes the IP into the Service's `status.loadBalancer.ingress`.
The controller can be the cloud controller manager (Azure, AWS, GCP), MetalLB,
or k3s's ServiceLB. kube-proxy reads that status and adds the rule.

Whether the rule is used depends on what the load balancer does to the packet
before it reaches a node:

| Load balancer sends the packet to the node as… | Which rule catches it | Examples |
|---|---|---|
| **dst = the LB IP** (the address is kept) | the new `-d <LB IP>` rule in `KUBE-SERVICES` | MetalLB (it announces the LB IP itself), k3s ServiceLB (LB IP = node IP, this lab), Azure Load Balancer as AKS configures it (floating IP) |
| **dst = node IP:NodePort** (the address is rewritten) | the NodePort path, `KUBE-NODEPORTS` | AWS NLB in instance mode, classic proxy-style load balancers |

That's why a LoadBalancer Service always has a NodePort too: some load balancers
deliver to it. The `ipMode` field (`"VIP"` here) tells kube-proxy which kind it is:

- **`VIP` (default):** add the LB IP rule.
- **`Proxy`:** don't, because the load balancer rewrites the destination and may
  also add the PROXY protocol or terminate TLS.

The LB IP rule has one side effect. A Pod calling the LB IP from inside the
cluster is caught by this rule and sent straight to a Pod, never reaching the
load balancer. With `ipMode: Proxy`, in-cluster traffic goes out to the load
balancer like anyone else's.

The routing to the LB IP is outside kube-proxy:

- **Cloud:** the provider's network routes the LB IP to its load balancer, which
  sends to the nodes.
- **MetalLB:** it answers ARP for the IP (L2 mode) or announces it with BGP.
- **This lab:** the IP is the node's own address, so it needs no route at all.

(The cloud, MetalLB and `ipMode: Proxy` behaviour are from their docs; only
ServiceLB was tested.)

k3s's ServiceLB also started a Pod, `svclb-web-…` (`rancher/klipper-lb`), with
`hostPort: 80`, which forwards to the ClusterIP (`DEST_IPS=10.43.208.17`). Its
hostPort added CNI DNAT rules (`CNI-HOSTPORT-DNAT`), but they come after
`KUBE-SERVICES` in PREROUTING, so kube-proxy's rule caught the request first:

```
nat PREROUTING
1  KUBE-SERVICES       ← the LB IP rule caught the packet                1 pkt
2  CNI-HOSTPORT-DNAT   ← the svclb Pod's hostPort: never reached         0 pkts
```

*The two settings that do add chains.* `loadBalancerSourceRanges: [203.0.113.0/24]`,
which limits who can use the LB IP:

```
- -A KUBE-SERVICES -d 172.17.0.3/32 … "loadbalancer IP" --dport 80 -j KUBE-EXT-6V2X…
+ -A KUBE-SERVICES -d 172.17.0.3/32 … "loadbalancer IP" --dport 80 -j KUBE-FW-6V2X…     ← now via a firewall chain
+ -A KUBE-FW-6V2X… -s 203.0.113.0/24 … -j KUBE-EXT-6V2X…                                ← allowed sources continue
+ -A KUBE-FW-6V2X… … "other traffic to np-lab/web:http will be dropped by KUBE-PROXY-FIREWALL"
+ -A KUBE-PROXY-FIREWALL -d 172.17.0.3/32 -p tcp … --dport 80 -j DROP                   ← filter table: the drop
```

```
client 172.17.0.4 → LB IP 172.17.0.3:80        wget: download timed out     ← not in 203.0.113.0/24: dropped
client 172.17.0.4 → NodePort 172.17.0.3:30080  allowed                      ← the NodePort isn't restricted
```

The range only protects the LB IP. The same Service is still open on its
NodePort. In the cloud, a network security group usually closes NodePorts from
outside, but nothing in kube-proxy does.

`externalTrafficPolicy: Local`: the same `KUBE-EXT`/`KUBE-SVL` change as for the
NodePort (part 4), plus a health check:

```
+ -A KUBE-EXT-6V2X… … -j KUBE-SVL-6V2X…                                     (and the Pod / node-local rules)
+ -A KUBE-SVL-6V2X… "-> 10.42.0.7:80" … ;  "-> 10.42.0.8:80"
+ -A KUBE-NODEPORTS -p tcp … "np-lab/web:http health check node port" … --dport 32382 -j ACCEPT     ← filter table
```

```
healthCheckNodePort: 32382
GET http://172.17.0.3:32382/
{ "service": { "namespace": "np-lab", "name": "web" }, "localEndpoints": 2, "serviceProxyHealthy": true }
```

That's kube-proxy's own HTTP server. A cloud load balancer probes it on every
node, and only nodes reporting `localEndpoints > 0` (answering 200) get traffic.
That's what keeps clients off the "node A without a Pod" from case 4 (part 15).
The filter `KUBE-NODEPORTS` rule on INPUT lets the probe reach it.

| Service | kube-proxy adds | New chains | Routes |
|---|---|---|---|
| ClusterIP | `KUBE-SERVICES` rule, `KUBE-SVC`, `KUBE-SEP`s | yes | none |
| + NodePort | 2 `KUBE-NODEPORTS` rules, `KUBE-EXT` | `KUBE-EXT` | none |
| + LoadBalancer | **1** `KUBE-SERVICES` rule: `-d <LB IP> -j KUBE-EXT` | **none** | **none** |
| + `loadBalancerSourceRanges` | rule now jumps to `KUBE-FW`; a filter `DROP` | `KUBE-FW-…` | none |
| + `externalTrafficPolicy: Local` | `KUBE-SVL`, health-check `ACCEPT`; kube-proxy serves `healthCheckNodePort` | `KUBE-SVL-…` | none |

*"One rule" is for one port and one LB IP.* The rule goes into kube-proxy's
`KUBE-SERVICES` chain, not into the built-in PREROUTING (which already jumps to
`KUBE-SERVICES`, as OUTPUT does). kube-proxy writes one such rule per Service
port, for each IP in `status.loadBalancer.ingress`. A Service with ports 80 and
443 gets two, both pointing to that port's `KUBE-EXT` chain. Lab: port `https`
(443 → 80) added to `np-lab/web`:

```
web    LoadBalancer   10.43.208.17   172.17.0.3    80:30080/TCP,443:30669/TCP
status.loadBalancer: {"ingress":[{"ip":"172.17.0.3","ipMode":"VIP"}]}

-A KUBE-SERVICES -d 10.43.208.17/32 … "np-lab/web:http cluster IP"        --dport 80  -j KUBE-SVC-6V2XWLK7W7RZJHNE
-A KUBE-SERVICES -d 172.17.0.3/32   … "np-lab/web:http loadbalancer IP"   --dport 80  -j KUBE-EXT-6V2XWLK7W7RZJHNE
-A KUBE-SERVICES -d 10.43.208.17/32 … "np-lab/web:https cluster IP"       --dport 443 -j KUBE-SVC-AFZYE5TTI3WVOQOL
-A KUBE-SERVICES -d 172.17.0.3/32   … "np-lab/web:https loadbalancer IP"  --dport 443 -j KUBE-EXT-AFZYE5TTI3WVOQOL
-A KUBE-NODEPORTS … "np-lab/web:http"  --dport 30080 -j KUBE-EXT-6V2XWLK7W7RZJHNE      (+ the localhost rule)
-A KUBE-NODEPORTS … "np-lab/web:https" --dport 30669 -j KUBE-EXT-AFZYE5TTI3WVOQOL      (+ the localhost rule)
```

Everything below `KUBE-SERVICES` is per port: each port has its own `KUBE-EXT`,
`KUBE-SVC` and `KUBE-SEP` chains, even when both go to the same Pods:

```
PREROUTING / OUTPUT → KUBE-SERVICES
  │
  ├─ port http (80)
  │    10.43.208.17:80 ──────────────────────────────► KUBE-SVC-6V2X… ─┬─► KUBE-SEP-C24B…  DNAT 10.42.0.14:80
  │    172.17.0.3:80   (LB IP) ─► KUBE-EXT-6V2X… ─────────▲             └─► KUBE-SEP-JE4Z…  DNAT 10.42.0.15:80
  │    node IP:30080   (NodePort) ─► KUBE-EXT-6V2X… ──────┘
  │
  └─ port https (443)
       10.43.208.17:443 ─────────────────────────────► KUBE-SVC-AFZY… ─┬─► KUBE-SEP-I35W…  DNAT 10.42.0.14:80
       172.17.0.3:443  (LB IP) ─► KUBE-EXT-AFZY… ─────────▲             └─► KUBE-SEP-IBQQ…  DNAT 10.42.0.15:80
       node IP:30669   (NodePort) ─► KUBE-EXT-AFZY… ──────┘
```

The chain names are hashes of namespace, name, port name and protocol, so
`np-lab/web:http` and `np-lab/web:https` get unrelated names (`6V2X…`, `AFZY…`).

With **more than one LB IP** in `status.loadBalancer.ingress` (for example a
dual-stack Service, or a load balancer that reports several addresses), each IP
gets its own rule for each port, all pointing to that port's `KUBE-EXT` (from
kube-proxy's code; not tested, since ServiceLB on one node reports one IP):

```
ingress: [ {ip: 203.0.113.10}, {ip: 203.0.113.11} ],  ports 80 and 443  →  4 rules
-A KUBE-SERVICES -d 203.0.113.10/32 … --dport 80  -j KUBE-EXT-<http>
-A KUBE-SERVICES -d 203.0.113.11/32 … --dport 80  -j KUBE-EXT-<http>
-A KUBE-SERVICES -d 203.0.113.10/32 … --dport 443 -j KUBE-EXT-<https>
-A KUBE-SERVICES -d 203.0.113.11/32 … --dport 443 -j KUBE-EXT-<https>
```

(An IPv6 ingress IP goes into the IPv6 tables, `ip6tables`, not these.)

*Does the load balancer send traffic to the NodePort or to the LB IP?* A common description
says:

> The LoadBalancer service type uses this same mechanism to deploy and configure an external load
> balancer (often through a cloud-managed API) which forwards traffic to an application using the
> NodePort. When a request is routed to the configured port on a node, it forwards packets as
> needed to direct traffic to the destination Pods using kube-proxy.

That's true for some load balancers. The rule above, `-d <LB IP>`, is for others. kube-proxy
installs rules for both paths on every node, and the destination address of the packet when it
reaches the node decides which one is used.

From the LoadBalancer lab, a LoadBalancer Service gets:

```
-A KUBE-SERVICES   -d <LB IP>/32   -p tcp --dport 80    -j KUBE-EXT-…   ← path 1: packet still addressed to the LB IP
-A KUBE-NODEPORTS                  -p tcp --dport 3xxxx -j KUBE-EXT-…   ← path 2: packet addressed to node IP:NodePort
```

Both lead to the same `KUBE-EXT` → `KUBE-SVC` → `KUBE-SEP` → DNAT to a Pod. A LoadBalancer
Service is a NodePort Service plus the LB IP rule.

The two rules are in **different chains**. The LB IP rule is in `KUBE-SERVICES`, next to the same
Service's ClusterIP rule. The NodePort rule is in `KUBE-NODEPORTS`, which is reached only through
the last rule of `KUBE-SERVICES`. Lab (three-node k3s from [network-policy.md](network-policy.md),
Service `lab3/c-lb`, LB IP `203.0.113.10` set by hand in its status):

```
nat KUBE-SERVICES, in order
   5  -d 10.43.68.115/32 … "lab3/c-lb:80-8080 cluster IP"      --dport 80 -j KUBE-SVC-4RUJ…
   6  -d 203.0.113.10/32 … "lab3/c-lb:80-8080 loadbalancer IP" --dport 80 -j KUBE-EXT-4RUJ…   ← path 1
   7  -d 10.43.0.1/32    … "default/kubernetes:https cluster IP" …                             ← other Services follow
   …
  10  "kubernetes service nodeports; NOTE: this must be the last rule in this chain"
      -m addrtype --dst-type LOCAL -j KUBE-NODEPORTS                                           ← path 2 enters here
nat KUBE-NODEPORTS
      -d 127.0.0.0/8 -p tcp "lab3/c-lb:80-8080" --dport 32746 -j KUBE-EXT-4RUJ…                 (localhost)
      -p tcp "lab3/c-lb:80-8080" --dport 32746 -j KUBE-EXT-4RUJ…
```

The LB IP rule isn't necessarily directly above the last rule. kube-proxy writes each Service's
rules together, and Services come in no fixed order. Only the `KUBE-NODEPORTS` jump has a fixed
position: last.

Which path is used depends on how the load balancer delivers packets **(docs; only ServiceLB was
tested here)**:

| How the LB delivers | Packet arrives at the node as | Rule that catches it | Examples |
|---|---|---|---|
| The LB rewrites the destination to a node's NodePort (proxy or NAT style) | `dst = node IP : NodePort` | `KUBE-NODEPORTS`, as in the quote | AWS Classic ELB, NLB in instance mode, many on-prem LBs, `ipMode: Proxy` |
| The LB passes the packet through unchanged (DSR, "floating IP") | `dst = LB IP : Service port` | `-d <LB IP>` in `KUBE-SERVICES` | Azure LB (AKS turns on floating IP), GCP passthrough Network LB, MetalLB |
| The LB sends straight to Pod IPs | `dst = Pod IP` | neither: kube-proxy isn't involved | AWS NLB in IP mode, GKE container-native (NEG), Azure Application Gateway for Containers |

Why the LB IP rule exists even for NodePort-style LBs:

- **Pods inside the cluster calling the LB IP:** the rule catches the packet on the client's own
  node and DNATs it straight to a Pod. It never goes out to the LB and back. Setting
  `ipMode: Proxy` turns that shortcut off, so the traffic really goes through the LB.
- **Passthrough LBs like Azure's need the rule,** because nothing on the way rewrites the
  destination to a NodePort. On AKS the NodePort is still allocated (unless
  `allocateLoadBalancerNodePorts: false`), but data traffic doesn't need it.

The shortcut, tested (lab, `lab3/c-lb`, LB IP `203.0.113.10` written into the status by hand).
**No load balancer exists at that address:**

| `status.loadBalancer.ingress[0].ipMode` | LB IP rule in `KUBE-SERVICES` | Pod `a` → `203.0.113.10:80` |
|---|---|---|
| `VIP` (the default) | 1 | **OK**, `Hostname: c`: caught on a's node and DNATed to c, never left the cluster |
| `Proxy` | 0 | **timed out**: no rule, so the packet really heads for `203.0.113.10` |

So with `VIP`, in-cluster traffic to the LB IP skips the load balancer, and any feature that lives
in the LB (TLS termination, PROXY protocol, WAF, logs) is skipped too. That is what `Proxy` is for.

*What's in `KUBE-EXT`: no addresses.* The LB IP and the NodePort are in the rules that **jump
into** `KUBE-EXT`. `KUBE-EXT` only decides two things: whether to SNAT, and which list of Pods to
use. (lab, `lab3/c-lb`, c on np-agent2, NodePort varies per run)

With `externalTrafficPolicy: Cluster`:

```
# the ways in: this is where the addresses and ports are
-A KUBE-SERVICES  -d 203.0.113.10/32 -p tcp --dport 80 "c-lb loadbalancer IP" -j KUBE-EXT-4RUJ…   ← LB IP
-A KUBE-NODEPORTS -p tcp --dport 32216 "c-lb"                                 -j KUBE-EXT-4RUJ…   ← NodePort
-A KUBE-NODEPORTS -d 127.0.0.0/8 -p tcp --dport 32216 "c-lb"                  -j KUBE-EXT-4RUJ…   ← NodePort via localhost

# KUBE-EXT itself: no -d, no --dport
-A KUBE-EXT-4RUJ… "masquerade traffic for lab3/c-lb external destinations" -j KUBE-MARK-MASQ   ← mark for SNAT (0x4000)
-A KUBE-EXT-4RUJ… -j KUBE-SVC-4RUJ…                                                            ← any endpoint, any node
```

With `externalTrafficPolicy: Local`, on np-server, which runs no `c` Pod:

```
-A KUBE-EXT-4RUJ… -s 10.42.0.0/16 "pod traffic for … external destinations" -j KUBE-SVC-4RUJ…    ← Pods calling the LB IP: all endpoints, no SNAT
-A KUBE-EXT-4RUJ… --src-type LOCAL "masquerade LOCAL traffic …" -j KUBE-MARK-MASQ               ← the node itself: SNAT,
-A KUBE-EXT-4RUJ… --src-type LOCAL "route LOCAL traffic …"      -j KUBE-SVC-4RUJ…               ←   all endpoints
   (nothing for real outside clients: this node has no local endpoint)
```

On np-agent2, which runs `c`, the same chain has one more rule at the end, and `KUBE-SVL` lists
only the local endpoint:

```
-A KUBE-EXT-4RUJ… -j KUBE-SVL-4RUJ…                                         ← outside clients: local Pods only, no SNAT
-A KUBE-SVL-4RUJ… "lab3/c-lb:80-8080 -> 10.42.2.2:8080" -j KUBE-SEP-A5WK…
```

On np-server, outside traffic falls through `KUBE-EXT` without a DNAT, and the filter table drops
it:

```
-A KUBE-EXTERNAL-SERVICES -d 203.0.113.10/32 -p tcp "lab3/c-lb:80-8080 has no local endpoints" --dport 80 -j DROP
-A KUBE-EXTERNAL-SERVICES -p tcp "lab3/c-lb:80-8080 has no local endpoints" -m addrtype --dst-type LOCAL --dport 32601 -j DROP
-A KUBE-NODEPORTS -p tcp "lab3/c-lb:80-8080 health check node port" --dport 31323 -j ACCEPT    ← the LB's probe, answered 503 here
```

An outside client (`172.19.0.5`, a container on the lab's Docker network) calling the NodePort:

| Via | Result |
|---|---|
| np-server `172.19.0.2:32601` (no `c`) | timed out (DROP) |
| np-agent2 `172.19.0.4:32601` (runs `c`) | OK, `RemoteAddr: 172.19.0.5`: the client's own IP, no SNAT |

Layer by layer:

| Layer | Holds |
|---|---|
| `KUBE-SERVICES` / `KUBE-NODEPORTS` | **the addresses and ports**: ClusterIP, LB IP, NodePort |
| `KUBE-EXT-*` | **the policy for outside traffic**: SNAT or not, all Pods (`KUBE-SVC`) or local Pods only (`KUBE-SVL`) |
| `KUBE-SVC-*` / `KUBE-SVL-*` | the random choice between endpoints |
| `KUBE-SEP-*` | one endpoint each: the DNAT to `Pod IP:port` |

That is why both ways in can share one `KUBE-EXT`: once a packet is in it, it doesn't matter
whether it came through the LB IP or the NodePort.

*How many entries a `type: LoadBalancer` Service gets.* It isn't three entries in
`KUBE-SERVICES` (ClusterIP, NodePort, LB). For a Service of **type LoadBalancer**,
`KUBE-SERVICES` gets two entries for each port: the ClusterIP rule and the LB IP rule. The NodePort
entries are in a different chain, `KUBE-NODEPORTS`. For `lab3/c-lb` (one port, one LB IP) (lab):

| Chain | Rule | For |
|---|---|---|
| `KUBE-SERVICES` | `-d 10.43.68.115 --dport 80 -j KUBE-SVC-…` | ClusterIP |
| `KUBE-SERVICES` | `-d 203.0.113.10 --dport 80 -j KUBE-EXT-…` | LB IP |
| `KUBE-NODEPORTS` | `--dport 32746 -j KUBE-EXT-…` | NodePort on any node address |
| `KUBE-NODEPORTS` | `-d 127.0.0.0/8 --dport 32746 -j KUBE-EXT-…` | NodePort on `localhost` |

The NodePort is reached through a rule in `KUBE-SERVICES` that the Service doesn't own:

```
-A KUBE-SERVICES … --dst-type LOCAL -j KUBE-NODEPORTS     ← one rule for all NodePort Services, always last
```

How the counts grow:

- **Each port** gets one ClusterIP rule in `KUBE-SERVICES`.
- **Each port and each LB IP** gets one more rule in `KUBE-SERVICES`. Two LB IPs and two ports
  make 4 LB rules (above).
- **Each port** gets the NodePort rules in `KUBE-NODEPORTS`. The `127.0.0.0/8` one appears because
  NodePorts on localhost are allowed (`route_localnet`, part 8).
- **`externalIPs`**, if set, add one more `KUBE-SERVICES` rule per IP and port, the same way as LB
  IPs. **(from kube-proxy's code; not tested)**

So a one-port LoadBalancer Service is 2 rules in `KUBE-SERVICES` plus 2 in `KUBE-NODEPORTS`, and
they lead to 3 chains:

- `KUBE-SVC-…`, used by the ClusterIP rule;
- `KUBE-EXT-…`, which both the LB IP rule and the NodePort rules jump to (the chain itself holds no
  addresses or ports, see "What's in `KUBE-EXT`" above);
- `KUBE-SEP-…`, one per endpoint.

For comparison, a ClusterIP Service gets only the first row, and a NodePort Service gets rows 1, 3
and 4.

*The three ways in, side by side.* The LB IP is matched in `KUBE-SERVICES` and jumps to `KUBE-EXT`.
The last rule of `KUBE-SERVICES` jumps to `KUBE-NODEPORTS`, which jumps to the same `KUBE-EXT`. The
ClusterIP goes straight to `KUBE-SVC`:

```
nat PREROUTING (or OUTPUT for traffic from the node itself)
 └─ KUBE-SERVICES
     ├─ -d 10.43.68.115 --dport 80  ──────────────────────────────► KUBE-SVC-…   ClusterIP: skips KUBE-EXT
     ├─ -d 203.0.113.10 --dport 80  ─────────────► KUBE-EXT-… ────► KUBE-SVC-…   LB IP: address + Service port
     │   … other Services …                         ▲
     └─ (last) --dst-type LOCAL ─► KUBE-NODEPORTS   │
                                    └─ --dport 32746 ┘                           NodePort: node address, then port only

KUBE-SVC-… ─► KUBE-SEP-… ─► DNAT to Pod IP:8080      (all three paths end here)
```

- **LB IP:** the rule in `KUBE-SERVICES` matches the LB IP **and** the Service port
  (`--dport 80`), then jumps to `KUBE-EXT`.
- **NodePort:** the last rule of `KUBE-SERVICES` checks only that the address belongs to the node
  (`--dst-type LOCAL`, answered from the local routing table, part 7). Inside `KUBE-NODEPORTS` only
  the NodePort number is checked (`--dport 32746`), then it jumps to `KUBE-EXT`.
- **ClusterIP:** its rule jumps straight to `KUBE-SVC`, skipping `KUBE-EXT`. Traffic to a ClusterIP
  comes from inside the cluster, so there's no outside-traffic decision to make (no SNAT mark, no
  `Local` policy).

A packet takes only one of these paths, decided by its destination address. The first rule that
leads to a DNAT ends the search.

*What nat `KUBE-SERVICES` is: the list of Service addresses.* `KUBE-SERVICES` (nat table) is the
list of every Service address and port the node should intercept, written out explicitly as
`-d <IP> --dport <port>`. Three kinds of address go in it, plus the one fixed rule at the end:

| Entry in nat `KUBE-SERVICES` | Comes from | Jumps to |
|---|---|---|
| `-d <ClusterIP> --dport <port>` | `spec.clusterIP` (every Service except headless) | `KUBE-SVC-…` |
| `-d <LB IP> --dport <port>` | `status.loadBalancer.ingress[].ip` (type LoadBalancer, `ipMode: VIP`) | `KUBE-EXT-…` |
| `-d <external IP> --dport <port>` | `spec.externalIPs` (any type, if set) **(from kube-proxy's code; not tested)** | `KUBE-EXT-…` |
| last: `--dst-type LOCAL -j KUBE-NODEPORTS` | fixed, always present (part 7) | `KUBE-NODEPORTS` → `KUBE-EXT-…` |

From the lab (one rule per address and port):

```
-A KUBE-SERVICES -d 10.43.0.10/32   … "kube-system/kube-dns:dns cluster IP"      --dport 53  -j KUBE-SVC-…
-A KUBE-SERVICES -d 10.43.78.255/32 … "shop/backend cluster IP"                  --dport 80  -j KUBE-SVC-…
-A KUBE-SERVICES -d 10.43.68.115/32 … "lab3/c-lb:80-8080 cluster IP"             --dport 80  -j KUBE-SVC-…
-A KUBE-SERVICES -d 203.0.113.10/32 … "lab3/c-lb:80-8080 loadbalancer IP"        --dport 80  -j KUBE-EXT-…   ← the LB IP, alongside the ClusterIPs
-A KUBE-SERVICES -d 10.43.0.1/32    … "default/kubernetes:https cluster IP"      --dport 443 -j KUBE-SVC-…
-A KUBE-SERVICES … "kubernetes service nodeports; NOTE: this must be the last rule in this chain" --dst-type LOCAL -j KUBE-NODEPORTS
```

The comment on each rule says which kind it is: `cluster IP`, `loadbalancer IP` or `external IP`.

The one Service address that isn't listed is the NodePort, because it has no IP of its own. It's
"any node address + a port", so it's matched indirectly through the last rule.

There's also a **filter-table** `KUBE-SERVICES`, a different chain with the same name. It only
holds REJECT rules for Service addresses with **no endpoints**, so a client gets "connection
refused" instead of a hang. The address list is the nat one.

*When the LB IP rule appears.* kube-proxy writes the LB IP rule only once
`status.loadBalancer.ingress[].ip` holds an address, and it removes the rule again when the address
goes away. kube-proxy never assigns the address itself. Something else writes it into the
Service's status (a cloud controller manager, ServiceLB, MetalLB, or by hand), and kube-proxy
reacts to it on its next sync.

Lab: one Service `lab3/c-lb` taken through each state, with the status written by hand (ServiceLB
is off on this cluster, so nothing overwrites it):

```
kubectl patch svc c-lb -n lab3 --subresource=status --type=merge \
  -p '{"status":{"loadBalancer":{"ingress":[{"ip":"203.0.113.10"}]}}}'
```

| Status | `EXTERNAL-IP` | Rules for c-lb in `KUBE-SERVICES` | `KUBE-NODEPORTS` rules |
|---|---|---|---|
| 1. just created, status empty | `<pending>` | ClusterIP only | 2 |
| 2. `ingress: [{ip: 203.0.113.10}]` | 203.0.113.10 | ClusterIP + LB IP | 2 |
| 3. `ingress: [{hostname: my-lb.example.com}]`, no `ip` | my-lb.example.com | ClusterIP only | 2 |
| 4. `ip` with `ipMode: Proxy` | 203.0.113.10 | ClusterIP only | 2 |
| 5. two `ip`s | — | ClusterIP + 2 LB IP rules | 2 |
| 6. `ingress: null` | `<pending>` | ClusterIP only (rule removed) | 2 |

- **The ClusterIP and NodePort rules exist from the moment the Service is created,** even while
  it's `<pending>`. Only the LB IP rule waits for the status.
- **Only `ip` counts.** A `hostname` entry (what AWS's ELB integration reports) gets no rule,
  because iptables can't match a DNS name. Traffic to such an LB always arrives through the
  NodePort path.
- **`ipMode: Proxy` suppresses the rule** even though the IP is there.
- **One rule per IP:** a second address in `ingress[]` adds a second rule.
- **It's reversible:** clearing the status removes the rule on the next sync.

(To clear it, the patch must set `"ingress": null`. A merge patch with `{"loadBalancer":{}}` leaves
the existing list in place and changes nothing.)

*The two `ipMode` values.* There are only two, `VIP` and `Proxy`. The API server rejects anything
else, and the field can only be set together with `ip` (lab):

```
ipMode: Foo         → Unsupported value: "Foo": supported values: "Proxy", "VIP"
ipMode without ip   → Forbidden: may not be specified when `ip` is not set
```

The field's own definition (`kubectl explain service.status.loadBalancer.ingress.ipMode`):

> Setting this to "VIP" indicates that traffic is delivered to the node with the destination set
> to the load-balancer's IP and port. Setting this to "Proxy" indicates that traffic is delivered
> to the node or pod with the destination set to the node's IP and node port or the pod's IP and
> port.

So `ipMode` tells kube-proxy which kind of load balancer it's dealing with:

| `ipMode` | Meaning | kube-proxy writes the LB IP rule? | Delivery style (table above) |
|---|---|---|---|
| `VIP` (also the default when unset) | the LB passes packets through with `dst = LB IP` | **yes**: needed to catch them, and in-cluster callers get the shortcut | passthrough / floating IP (Azure, GCP passthrough, MetalLB) |
| `Proxy` | the LB rewrites `dst` to `node IP:NodePort` or `Pod IP:port` | **no**: the LB IP never reaches a node, and in-cluster callers really go through the LB | NodePort-style or straight-to-Pod |

- **Unset behaves as `VIP`.** State 2 in the table above had no `ipMode`, and the rule was written.
  That keeps the behaviour from before the field existed.
- **Who sets it:** whatever writes the status, typically the cloud controller manager. A provider
  whose LB proxies (for example one that terminates TLS or adds the PROXY protocol) should set
  `Proxy`, so in-cluster traffic isn't short-circuited past those features.
- **Versions:** `ipMode` was added in Kubernetes 1.29 (alpha), became beta and on by default in
  1.30, and GA in 1.32. Before it existed, kube-proxy always behaved as `VIP`. **(Kubernetes
  release notes; this lab is on 1.34)**

*Which addresses can be an LB IP.* Kubernetes doesn't restrict it. Any syntactically valid IP is
accepted, private or public, and kube-proxy programs it as given (lab, `lab3/c-lb`):

| Address | Kind | Accepted? | kube-proxy rule |
|---|---|---|---|
| `203.0.113.10` | documentation range | yes | `-d 203.0.113.10/32` |
| `8.8.8.8` | public | yes | `-d 8.8.8.8/32` |
| `10.20.30.40`, `192.168.1.50` | private | yes | yes |
| `172.19.0.4` | a node's own IP | yes | yes |
| `10.43.0.1` | the `kubernetes` ClusterIP | yes | yes |
| `10.42.2.2` | a Pod IP | yes | yes |
| `127.0.0.1` | loopback | yes | yes (minikube's tunnel really uses this, §4.4) |
| `169.254.169.254` | cloud metadata endpoint | yes | yes |
| `0.0.0.0`, `255.255.255.255`, `224.0.0.1` | unspecified, broadcast, multicast | yes | yes |
| `2001:db8::10` | IPv6 | yes | none here (IPv4-only cluster; it would go to `ip6tables`) |
| `not-an-ip` | — | **rejected** | "must be a valid IP address" |

The only check is "is it an IP?". Choosing a sensible address is up to whatever writes the status:

- **Cloud controller managers** use the provider's public IPs, or private VNet/VPC IPs for
  internal LBs (on AKS: `service.beta.kubernetes.io/azure-load-balancer-internal: "true"`).
- **MetalLB** uses the pools you configure, usually private LAN ranges.
- **ServiceLB** uses node IPs.

**(the three controllers: from their docs)**

*Why that matters: the LB IP is hijacked inside the cluster.* The LB IP rule catches every packet
to that IP and Service port on every node, whoever it was meant for. Lab, `lab3/c-lb` with LB IP
`1.1.1.1`, port 80:

| Pod `a` → `1.1.1.1` | Result |
|---|---|
| `:80` before | `HTTP/1.1 301`, `Server: cloudflare`: the real 1.1.1.1 |
| `:80` with the LB IP set | **`Hostname: c`**: answered by our Pod, never left the cluster |
| `:443` (not a Service port) at the same time | the real 1.1.1.1: only the Service's port is caught |
| `:80` after deleting the Service | `Server: cloudflare` again |

The same works for `169.254.169.254:80` (every Pod's cloud metadata calls), a node IP or a
ClusterIP port. This is **CVE-2020-8554**: anyone who can set `spec.externalIPs` or patch
`status.loadBalancer` can intercept cluster traffic to any IP. It wasn't fixed in code. The
mitigations are:

- Restrict who can write `services/status` in RBAC. Normally only the cloud controller manager or
  the LB controller should have that permission.
- Block or limit `externalIPs` with the `DenyServiceExternalIPs` admission plugin, or a
  ValidatingAdmissionPolicy, Gatekeeper or Kyverno rule.
- Use `ipMode: Proxy` for LBs that proxy anyway, so no hijacking rule is written.

**(the CVE and mitigations: Kubernetes security advisory)**

k3s's ServiceLB is a third style. It has no external LB: the `svclb-*` Pod on each node holds a
`hostPort` on the Service port, and the node IPs serve as the LB IPs (`172.17.0.3` above).

**17. How to read a rule, and what `-m comment` is.** Read a rule left to right.
Each `-m NAME` starts a match module, and the options after it (`--…`) belong to
that module, until the next `-m` or the `-j`. Every match must be true for the
target to run. Take this rule from the lab:

```
-A KUBE-SVC-6V2XWLK7W7RZJHNE ! -s 10.42.0.0/16 -d 10.43.208.17/32 -p tcp -m comment --comment "np-lab/web:http cluster IP" -m tcp --dport 80 -j KUBE-MARK-MASQ
```

```
-A KUBE-SVC-6V2XWLK7W7RZJHNE                       append this rule to chain KUBE-SVC-6V2X…

! -s 10.42.0.0/16                                  ┐ built-in matches (part of every rule):
-d 10.43.208.17/32                                 │   source is NOT in 10.42.0.0/16 (not a Pod)
-p tcp                                             ┘   destination 10.43.208.17, protocol TCP

-m comment --comment "np-lab/web:http cluster IP"  ← match module "comment", its one option --comment
                                                      checks nothing: always true, just a label

-m tcp --dport 80                                  ← match module "tcp", option --dport: destination port 80

-j KUBE-MARK-MASQ                                  ← target: if everything above matched, jump here
```

It reads as: "in chain `KUBE-SVC-6V2X…`, a TCP packet to `10.43.208.17:80` that
doesn't come from a Pod → jump to `KUBE-MARK-MASQ`" (label: `np-lab/web:http
cluster IP`). `iptables -L` shows the same rule as a table, with the comment
between `/* */`:

```
target          prot  source          destination
KUBE-MARK-MASQ  tcp   !10.42.0.0/16   10.43.208.17   /* np-lab/web:http cluster IP */ tcp dpt:80
                                                     └───── -m comment ─────────────┘ └ -m tcp ┘
```

A rule with two modules:

```
-A KUBE-SVC-6V2X… -m comment --comment "np-lab/web:http -> 10.42.0.7:80" -m statistic --mode random --probability 0.5 -j KUBE-SEP-MX3S…
                  └──────────── label: which endpoint ──────────────┘ └── module "statistic": true 50% of the time ──┘ └ jump to that endpoint's chain ┘
```

*What `-m comment` does in the kernel.* Nothing to the packet. Its match function
(`net/netfilter/xt_comment.c`) is:

```c
comment_mt(const struct sk_buff *skb, struct xt_action_param *par)
{
	/* We always match */
	return true;
}
```

The text (at most 256 bytes, `XT_MAX_COMMENT_LEN`) is stored inside the rule, so
`iptables-save` and `iptables -L` can show it. Removing a comment never changes
what a rule matches, and it can sit anywhere among the matches.

*Why kube-proxy labels almost every rule.* Its chain names are hashes of the
Service's namespace, name, port and protocol (`KUBE-SVC-6V2XWLK7W7RZJHNE`), so the
comment is what ties a rule to a Service and says why it exists:

| Comment (from this lab) | Tells you |
|---|---|
| `np-lab/web:http cluster IP` | the rule for Service `np-lab/web`, port `http`, at its ClusterIP |
| `np-lab/web:http -> 10.42.0.7:80` | this jump goes to the endpoint `10.42.0.7:80` |
| `np-lab/web:http loadbalancer IP` | the rule for the load balancer IP |
| `masquerade traffic for np-lab/web:http external destinations` | why `KUBE-EXT` marks for SNAT |
| `np-lab/web:http has no endpoints` | why this rule REJECTs |
| `kubernetes service nodeports; NOTE: this must be the last rule in this chain` | a warning to anyone editing the chain |

That's why `iptables-save -t nat | grep 'np-lab/web'` found every rule for the
Service throughout this lab.

*Cost.* In iptables (legacy, and iptables-nft as on this node) `comment` is a
real match: every packet that reaches the rule calls `comment_mt`, which returns
`true` at once, and the rule carries the 256 bytes. `nft list` on this node shows
it through the compatibility layer:

```
ip saddr != 10.42.0.0/16 ip daddr 10.43.208.17 ip protocol tcp xt match "comment" tcp dport 80 … jump KUBE-MARK-MASQ
```

Native nftables (kube-proxy's nftables mode) has a `comment "…"` keyword instead,
stored as rule metadata (`NFTA_RULE_USERDATA`) and never evaluated for packets.

**18. Every match module and target on this node.** Extracted from
`iptables-save` of the k3s node (nat, filter, mangle, raw), with counts per
table. Jumps to user chains (`-j KUBE-SVC-…`) aren't targets in this sense and
are left out.

*Match modules (`-m`):*

| Module | Uses (nat / filter) | What it matches | Options seen | Written by | Example from the node |
|---|---|---|---|---|---|
| **comment** | 52 / 118 | nothing: always true, a label | `--comment "…"` | everyone | `-m comment --comment "kubernetes service portals"` |
| **tcp** | 22 / – | TCP header fields | `--dport`, `--sport` | kube-proxy, CNI | `-p tcp -m tcp --dport 30080` |
| **udp** | 3 / – | UDP header fields | `--dport` | kube-proxy | `-p udp … "kube-system/kube-dns:dns" -m udp -j DNAT …` |
| **statistic** | 1 / – | true randomly, or every Nth packet | `--mode random --probability`, `--mode nth --every` | kube-proxy | `-m statistic --mode random --probability 0.5 -j KUBE-SEP-…` |
| **addrtype** | 3 / 8 | the address's *type*, from the routing table (`LOCAL`, `UNICAST`, …) | `--dst-type`, `--src-type` | kube-proxy, CNI | `-m addrtype --dst-type LOCAL -j KUBE-NODEPORTS` |
| **mark** | 3 / 16 | the packet mark (`skb->mark`) under a mask | `--mark V/M`, `! --mark` | kube-proxy, flannel, kube-router, CNI | `-m mark --mark 0x4000/0x4000` |
| **conntrack** | – / 22 | the connection's state in conntrack | `--ctstate NEW,ESTABLISHED,RELATED,INVALID,DNAT…` | kube-proxy, kube-router | `-m conntrack ! --ctstate RELATED,ESTABLISHED,DNAT -j DROP` |
| **nfacct** | 1 / 1 | always true; adds to a named counter | `--nfacct-name` | kube-proxy | `-m nfacct --nfacct-name localhost_nps_accepted_pkts` |
| **multiport** | 1 / 2 | several ports in one rule | `--dports 80,443` | CNI portmap, kube-router | `-m multiport --dports 80 -j CNI-DN-…` |
| **physdev** | – / 12 | the bridge port a bridged packet uses | `--physdev-is-bridged` | kube-router | `-m physdev --physdev-is-bridged … -j KUBE-POD-FW-…` |
| **limit** | – / 6 | true at most N times per period | `--limit 10/min --limit-burst 10` | kube-router | `… -m limit --limit 10/min … -j NFLOG` |
| **icmp** | – / 3 | ICMP type/code | `--icmp-type 8` (echo request) | kube-router | `-p icmp -m icmp --icmp-type 8 -j ACCEPT` |

`-s`, `-d`, `-p`, `-i`, `-o` and `!` are built-in matches, part of every rule,
with no `-m`. `-p tcp` makes the `tcp` module available, so `--dport` works
without `-m tcp`; `iptables-save` then prints `-m tcp` explicitly.

*Targets (`-j`):*

| Target | Uses (nat / filter) | What it does | Ends the chain? | Example |
|---|---|---|---|---|
| **ACCEPT** | – / 22 | final: this table is done, let it continue | yes | `-m mark --mark 0x4000/0x4000 -j ACCEPT` |
| **DROP** | – / 8 | discard silently | yes | `KUBE-FIREWALL … -j DROP` |
| **REJECT** | – / 6 | discard and send an error back | yes | `-j REJECT --reject-with icmp-port-unreachable` |
| **RETURN** | 5 / 3 | leave this chain, continue in the one that jumped here | ends this chain | `KUBE-POSTROUTING -m mark ! --mark 0x4000/0x4000 -j RETURN` |
| **DNAT** | 8 / – | rewrite the destination | yes | `-j DNAT --to-destination 10.42.0.7:80` |
| **MASQUERADE** | 4 / – | SNAT to the outgoing interface's address | yes | `-j MASQUERADE --random-fully` |
| **MARK** | 3 / 13 | set bits in `skb->mark` | **no**: carries on | `-j MARK --set-xmark 0x4000/0x4000` |
| **NFLOG** | – / 6 | copy the packet to a userspace logger | **no** | kube-router logging policy drops |

*Who uses what:*

| Program | Modules | Targets |
|---|---|---|
| **kube-proxy** | `comment`, `tcp`, `udp`, `statistic`, `addrtype`, `mark`, `conntrack`, `nfacct` | `DNAT`, `MASQUERADE`, `MARK`, `RETURN`, `ACCEPT`, `DROP`, `REJECT` |
| **kube-router** (k3s NetworkPolicy) | `comment`, `mark`, `conntrack`, `physdev`, `limit`, `icmp`, `multiport` | `ACCEPT`, `REJECT`, `DROP`, `MARK`, `NFLOG` |
| **flannel** | `mark`, `comment` | `RETURN`, `MASQUERADE` |
| **CNI portmap** (hostPort, used by ServiceLB) | `addrtype`, `tcp`, `multiport`, `mark`, `comment` | `DNAT`, `MARK`, `MASQUERADE` |

*Not on this node, but worth knowing* (from `iptables-extensions(8)`; not tested):

| Module | Matches | Where you'd see it |
|---|---|---|
| **recent** | whether a source was seen recently (a list per rule) | kube-proxy, for `sessionAffinity: ClientIP` (`-m recent --rcheck --seconds 10800 --reap --name KUBE-SEP-…`) |
| **sctp** | SCTP ports | kube-proxy, for SCTP Services |
| **set** | membership in an `ipset` | Calico, kube-proxy in IPVS mode, kube-router |
| **iprange** | a range like `10.0.0.5-10.0.0.20` | firewall scripts |
| **owner** | the local process's UID/GID (OUTPUT only) | per-user egress rules |
| **state** | the older form of `conntrack --ctstate` | older rulesets |
| **connlimit** / **hashlimit** | connections per source / per-key rate limits | DDoS protection |
| **string**, **length**, **mac**, **tos**, **policy**, **socket**, **bpf**, **u32** | payload text, length, source MAC, TOS, IPsec policy, a local socket, a BPF program, raw header bytes | special cases (TPROXY uses `socket`) |

On any node, `iptables-save | grep -o -- '-m [a-z0-9_]*' | sort | uniq -c` lists the
modules in use.

### 4.3.1 Traffic policies: `internalTrafficPolicy` and `externalTrafficPolicy` (lab)

You can set `.spec.internalTrafficPolicy` and `.spec.externalTrafficPolicy` to
control how Kubernetes routes traffic to healthy ("ready") backends (docs).
Both take `Cluster` (the default) or `Local`:

| Field | Applies to traffic sent to | `Cluster` | `Local` |
|---|---|---|---|
| `internalTrafficPolicy` | the ClusterIP, from Pods and nodes | any ready endpoint in the cluster | only endpoints on the caller's node |
| `externalTrafficPolicy` | node ports, LB IPs, external IPs, from outside | any ready endpoint, source SNATed | only endpoints on the receiving node, source kept |

`Local` only matters with more than one node, so a second node was added to
minikube for this lab (`minikube node add`, removed afterwards):

```text
NAME            INTERNAL-IP     Pods
minikube        192.168.49.2    web-n1-…-fjvmq 10.244.120.76, web-n1-…-q9pdd 10.244.120.118, client-n1
minikube-m04    192.168.49.5    web-n2-…-n86d5 10.244.59.129, client-n2
outside         192.168.49.6    a plain Docker container on the minikube network
```

Three Services select all three `app: web` Pods:

```yaml
# tp-default: both policies Cluster (the defaults)
type: NodePort
ports: [{name: http, port: 80, targetPort: 8080, nodePort: 30101}]
---
# tp-itp-local
type: ClusterIP
internalTrafficPolicy: Local
ports: [{name: http, port: 80, targetPort: 8080}]
---
# tp-etp-local
type: LoadBalancer                 # EXTERNAL-IP stays <pending>; the node port is enough
externalTrafficPolicy: Local
ports: [{name: http, port: 80, targetPort: 8080, nodePort: 30103}]
# healthCheckNodePort: 30225       ← allocated automatically (LoadBalancer + Local)
```

#### `internalTrafficPolicy: Local`

30 requests from a client Pod on each node:

| From | To `tp-default` (Cluster) | To `tp-itp-local` (Local) |
|---|---|---|
| `client-n1` (node 1) | 14 fjvmq, 10 q9pdd, 6 n86d5 | 10 fjvmq, 20 q9pdd |
| `client-n2` (node 2) | 16 fjvmq, 8 q9pdd, 6 n86d5 | 30 n86d5 |

With `Cluster`, both clients reach all three Pods. With `Local`, each stays on
its own node.

Each node's kube-proxy writes a different chain for the same ClusterIP. The
entry rule jumps to `KUBE-SVL` (local endpoints only) instead of `KUBE-SVC`,
and there's no `KUBE-SVC` for the Service at all:

```text
# node 1
-A KUBE-SERVICES -d 10.100.160.110/32 -p tcp "tp-demo/tp-itp-local:http cluster IP" -m tcp --dport 80 -j KUBE-SVL-APQ32VUKQCSTAL63
-A KUBE-SVL-APQ32VUKQCSTAL63 ! -s 10.244.0.0/16 -d 10.100.160.110/32 -p tcp "tp-demo/tp-itp-local:http cluster IP" -m tcp --dport 80 -j KUBE-MARK-MASQ
-A KUBE-SVL-APQ32VUKQCSTAL63 "tp-demo/tp-itp-local:http -> 10.244.120.118:8080" -m statistic --mode random --probability 0.50000000000 -j KUBE-SEP-O3LQTZKN3IN6P6VU
-A KUBE-SVL-APQ32VUKQCSTAL63 "tp-demo/tp-itp-local:http -> 10.244.120.76:8080" -j KUBE-SEP-KUG5DKKL5K4V7VO4

# node 2
-A KUBE-SERVICES -d 10.100.160.110/32 -p tcp "tp-demo/tp-itp-local:http cluster IP" -m tcp --dport 80 -j KUBE-SVL-APQ32VUKQCSTAL63
-A KUBE-SVL-APQ32VUKQCSTAL63 ! -s 10.244.0.0/16 -d 10.100.160.110/32 -p tcp "tp-demo/tp-itp-local:http cluster IP" -m tcp --dport 80 -j KUBE-MARK-MASQ
-A KUBE-SVL-APQ32VUKQCSTAL63 "tp-demo/tp-itp-local:http -> 10.244.59.129:8080" -j KUBE-SEP-W3DGPO4XSJW4CKTW
```

(`KUBE-SEP` chains left out; they're the usual hairpin + DNAT.)

**No local endpoint: dropped, not rejected, and no fallback.** After scaling
the node-2 Pods to 0, node 2's nat rules for `tp-itp-local` were gone, and
filter had a `DROP`:

```text
-A KUBE-SERVICES -d 10.100.160.110/32 -p tcp "tp-demo/tp-itp-local:http has no local endpoints" -m tcp --dport 80 -j DROP
```

```text
client-n2 → tp-itp-local    exit 28 after 5 s (timeout)       ← node 1 has two ready Pods, but they aren't used
client-n1 → tp-itp-local    web-n1-…-fjvmq
client-n2 → tp-default      web-n1-…-q9pdd                    ← Cluster still works from node 2
```

- **`DROP` means a timeout.** A Service with no endpoints anywhere gets
  `REJECT` instead, so callers fail at once (§4.6). Here the caller waits.
- **Use `Local` only when every node that calls the Service has an endpoint,**
  for example a DaemonSet such as a node-local DNS cache or a log agent.

#### `externalTrafficPolicy: Local`

From the outside container (192.168.49.6), still with no Pods on node 2:

| Request | Answered by | Pod sees the client as |
|---|---|---|
| node 1:30101 (Cluster) | web-n1-…-fjvmq | `10.244.120.64` (node 1's pod-network address: SNAT) |
| node 2:30101 (Cluster) | web-n1-…-q9pdd (forwarded to node 1) | `10.244.59.128` (node 2's pod-network address: SNAT) |
| node 1:30103 (Local) | web-n1-…-q9pdd | `192.168.49.6` (the real client) |
| node 2:30103 (Local) | nobody: timeout (exit 28) | – |

- **`Cluster`:** any node forwards to any Pod, and the Pod never sees the
  client's address.
- **`Local`:** the client's address survives, but a node without a local Pod
  drops the traffic.

On node 2, `KUBE-EXT` had no `KUBE-SVL` to go to. Outside traffic fell
through it without DNAT and was dropped in filter:

```text
# nat: KUBE-EXT on node 2, no local endpoints
-A KUBE-EXT-TPPZEBOYQD6FKWIH -s 10.244.0.0/16 "pod traffic for tp-demo/tp-etp-local:http external destinations" -j KUBE-SVC-TPPZEBOYQD6FKWIH
-A KUBE-EXT-TPPZEBOYQD6FKWIH "masquerade LOCAL traffic for tp-demo/tp-etp-local:http external destinations" -m addrtype --src-type LOCAL -j KUBE-MARK-MASQ
-A KUBE-EXT-TPPZEBOYQD6FKWIH "route LOCAL traffic for tp-demo/tp-etp-local:http external destinations" -m addrtype --src-type LOCAL -j KUBE-SVC-TPPZEBOYQD6FKWIH

# filter: the drop
-A KUBE-EXTERNAL-SERVICES -p tcp "tp-demo/tp-etp-local:http has no local endpoints" -m addrtype --dst-type LOCAL -m tcp --dport 30103 -j DROP
```

**Pods are exempt.** The first `KUBE-EXT` rule sends Pod traffic to
`KUBE-SVC`, the cluster-wide chain. So `client-n2`, a Pod on node 2, called
node 2's port 30103 and got an answer from node 1:

```text
client-n2 → 192.168.49.5:30103    web-n1-…-q9pdd
```

`externalTrafficPolicy` only applies to traffic from outside the cluster. The
node itself (`--src-type LOCAL`) is exempt in the same way.

After scaling `web-n2` back to 1, node 2 got its `KUBE-SVL` and the filter
`DROP` was removed:

```text
-A KUBE-EXT-TPPZEBOYQD6FKWIH -s 10.244.0.0/16 "pod traffic for tp-demo/tp-etp-local:http external destinations" -j KUBE-SVC-TPPZEBOYQD6FKWIH
-A KUBE-EXT-TPPZEBOYQD6FKWIH "masquerade LOCAL traffic for tp-demo/tp-etp-local:http external destinations" -m addrtype --src-type LOCAL -j KUBE-MARK-MASQ
-A KUBE-EXT-TPPZEBOYQD6FKWIH "route LOCAL traffic for tp-demo/tp-etp-local:http external destinations" -m addrtype --src-type LOCAL -j KUBE-SVC-TPPZEBOYQD6FKWIH
-A KUBE-EXT-TPPZEBOYQD6FKWIH -j KUBE-SVL-TPPZEBOYQD6FKWIH
-A KUBE-SVL-TPPZEBOYQD6FKWIH "tp-demo/tp-etp-local:http -> 10.244.59.131:8080" -j KUBE-SEP-7JMLWFTTEEM4QQM3
```

```text
outside → node 2:30103  ×10    10 web-n2-…-ll48n                ← only the local Pod
outside → node 2:30103         clientip 192.168.49.6
outside → node 1:30103  ×10    6 fjvmq, 4 q9pdd                 ← only node 1's Pods
```

Each node balances only over its own Pods. If a cloud LB spreads traffic
evenly across nodes, node 2's single Pod gets as much traffic as node 1's two
Pods together.

#### The health check node port

A LoadBalancer Service with `externalTrafficPolicy: Local` gets a
`healthCheckNodePort` (30225 here). kube-proxy answers it on every node with
the number of local endpoints. A filter rule lets it in:

```text
-A KUBE-NODEPORTS -p tcp "tp-demo/tp-etp-local:http health check node port" -m tcp --dport 30225 -j ACCEPT
```

```text
$ curl -i 192.168.49.2:30225/        $ curl -i 192.168.49.5:30225/   (no Pods on node 2)
HTTP/1.1 200 OK                       HTTP/1.1 503 Service Unavailable
  "localEndpoints": 2                   "localEndpoints": 0
```

After the node-2 Pod came back, node 2 answered `200 OK`, `"localEndpoints": 1`.
The cloud LB probes this port and stops sending to nodes that answer 503, so
the drops above never reach real clients. Without an LB, as with a plain
NodePort, nothing probes it, and clients that pick the wrong node time out.

#### Summary

| | `internalTrafficPolicy: Local` | `externalTrafficPolicy: Local` |
|---|---|---|
| Who it affects | Pods and nodes calling the ClusterIP | outside clients calling a node port, LB IP or external IP |
| Who it doesn't | outside clients | Pods and the node itself (they use `KUBE-SVC`) |
| Chain used | `KUBE-SVL` from `KUBE-SERVICES` | `KUBE-SVL` from `KUBE-EXT` |
| Client IP | already kept for Pod callers | kept (no SNAT) |
| No local endpoint | filter `DROP` in `KUBE-SERVICES`: timeout | filter `DROP` in `KUBE-EXTERNAL-SERVICES`: timeout |
| Protection | none: put an endpoint on every calling node | the LB's health checks on `healthCheckNodePort` |

### 4.4 LoadBalancer: needs something to provision it

What the Kubernetes docs say (docs):

- On clouds that support external load balancers, `type: LoadBalancer` makes
  the cloud provision one for the Service, in the region where the cluster runs.
- The LB is created **asynchronously**. Details of the provisioned LB are
  published in the Service's `.status.loadBalancer` field.
- Kubernetes first does the equivalent of a `type: NodePort` Service. The
  cloud-controller-manager then configures the external LB to forward traffic to
  that node port.
- Traffic from the LB is directed at the backend Pods; how it's balanced is up
  to the cloud provider.

The docs' example:

```yaml
apiVersion: v1
kind: Service
metadata:
  name: my-service
spec:
  selector:
    app.kubernetes.io/name: MyApp
  ports:
    - protocol: TCP
      port: 80
      targetPort: 9376
  clusterIP: 10.0.171.239
  type: LoadBalancer
status:                       # written by the controller, not by you
  loadBalancer:
    ingress:
    - ip: 192.0.2.127
```

How that lines up with the rest of this note:

- **`status` is not part of what you apply.** The controller writes it once the
  LB exists. Until then `kubectl get svc` shows `EXTERNAL-IP <pending>` (below).
  kube-proxy only writes its LB-IP rule after the status has an IP (the
  `type: LoadBalancer` and "LB status written" rows in §3.6, "Each kind of
  change").
- **`clusterIP` is normally left out** and allocated by the API server. It's in
  the example only because the docs show a created Service.
- **"Forward traffic to that node port" is the common case, not the only one.**
  Some LBs pass the packet through with the LB IP as its destination, and some
  send straight to Pod IPs and skip the node port ("How outside traffic reaches
  the LB-IP rule" below).
- **The cloud-controller-manager is one of several controllers that can do this.**
  The AWS Load Balancer Controller and MetalLB also fill in the status
  ([cloud-control-manager.md](cloud-control-manager.md)).

On minikube there's no cloud controller, so the Service waits. It still gets its
ClusterIP and NodePort straight away:

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

#### Internal load balancers (docs, not tested)

In a mixed environment it's sometimes necessary to route traffic from Services
inside the same (virtual) network address block. An internal LB gets a private
IP from the cluster's VPC or VNet instead of a public one, so only clients that
can route to that network reach it.

To ask for one, add an annotation to the Service. Which one depends on the cloud
and on which controller handles the Service.

GKE:

```yaml
metadata:
  name: my-service
  annotations:
    networking.gke.io/load-balancer-type: "Internal"
```

AWS:

```yaml
metadata:
  name: my-service
  annotations:
    service.beta.kubernetes.io/aws-load-balancer-scheme: "internal"
```

Azure (AKS):

```yaml
metadata:
  name: my-service
  annotations:
    service.beta.kubernetes.io/azure-load-balancer-internal: "true"
```

Notes on these:

- **AWS has two controllers, with different annotations and defaults.**
  - `aws-load-balancer-scheme` is read by the **AWS Load Balancer Controller**.
    For a Service it handles, `internal` is the default, so you set
    `internet-facing` to get a public NLB.
  - The older controller built into the AWS cloud provider reads
    `service.beta.kubernetes.io/aws-load-balancer-internal: "true"` instead,
    and makes public LBs by default.
- **Azure** can also pick the subnet the private IP comes from, with
  `service.beta.kubernetes.io/azure-load-balancer-internal-subnet`.
- **OpenStack** uses `service.beta.kubernetes.io/openstack-internal-load-balancer: "true"`,
  which skips the Floating IP (§2.2, and
  [cloud-control-manager.md](cloud-control-manager.md) §14.4).
- **Nothing changes inside the cluster.** The Service is still a NodePort plus
  an LB. The controller writes a private IP into `status.loadBalancer.ingress`,
  and kube-proxy writes the same LB-IP rule for it as for a public one.

##### Split-horizon DNS: two Services, same Pods

In a split-horizon DNS setup, one name resolves to different addresses
depending on who asks: a public IP for the internet, a private IP inside the
network. A Service has only one LB type, so serving both takes **two Services**
with the same selector, one public and one internal (AKS shown):

```yaml
apiVersion: v1
kind: Service
metadata:
  name: web-public
spec:
  type: LoadBalancer                     # public IP, e.g. 20.50.10.7
  selector: {app: web}
  ports: [{port: 443, targetPort: 8443}]
---
apiVersion: v1
kind: Service
metadata:
  name: web-internal
  annotations:
    service.beta.kubernetes.io/azure-load-balancer-internal: "true"
spec:
  type: LoadBalancer                     # private IP from the VNet, e.g. 10.240.0.50
  selector: {app: web}
  ports: [{port: 443, targetPort: 8443}]
```

```text
public DNS   web.example.com → 20.50.10.7   (web-public's LB)   ← internet clients
private DNS  web.example.com → 10.240.0.50  (web-internal's LB) ← VNet, VPN, peered networks
                                   │
                    both Services select app=web → same EndpointSlice Pods
```

- **Both Services get their own ClusterIP, node port, LB and kube-proxy
  rules,** but their EndpointSlices list the same Pods.
- **The DNS split lives outside Kubernetes,** for example a public zone and a
  private zone (Azure Private DNS, Route 53 private hosted zones) holding the
  same name.

#### How outside traffic reaches the LB-IP rule (not tested)

The `KUBE-EXT` chain is where every "from outside" entry point ends up. The
LB-IP rule is one of those entry points, but it only matches if a packet
actually reaches a node with the LB IP still as its destination. Whether that
happens depends on how the load balancer forwards traffic.

```text
                    nat KUBE-SERVICES
                    ├── -d ClusterIP:port        → KUBE-SVC   (in-cluster callers)
packet arrives ───▶ ├── -d externalIP:port       → KUBE-EXT ┐
on a node           ├── -d LB IP:port            → KUBE-EXT ┤ (or KUBE-FW first,
                    └── (last) dst-type LOCAL    → KUBE-NODEPORTS → KUBE-EXT ┘  with loadBalancerSourceRanges)
                                                                   │
                                     KUBE-EXT: masquerade, or ETP Local sorting
                                                                   ▼
                                                    KUBE-SVC / KUBE-SVL → KUBE-SEP → Pod
```

The LB IP, an external IP and the node port all lead into the same
`KUBE-EXT-<hash>` chain. What differs is which of those rules a given packet
matches. That depends on what the load balancer does to the destination
address.

**1. The LB rewrites the destination to node IP:node port.** Examples are the
AWS Classic LB, the AWS NLB in instance mode, Hetzner and DigitalOcean.

```text
client → 203.0.113.10:80 → LB → 10.0.1.5:31845 (node) → KUBE-NODEPORTS → KUBE-EXT → Pod
```

The packet reaches the node addressed to the node port. The LB-IP rule never
matches outside traffic; the node port does all the work.

**2. The LB passes the packet through with the destination unchanged.**
Examples are GCP's network LB, Azure's LB (AKS turns on "floating IP" by
default), and MetalLB in L2 or BGP mode.

```text
client → 203.0.113.10:80 → (routed or ARP-answered to a node) → dst still 203.0.113.10:80
       → KUBE-SERVICES "-d 203.0.113.10/32 --dport 80" → KUBE-EXT → Pod
```

The node port isn't used for this traffic. The LB-IP rule is what catches the
packet, and that's why kube-proxy writes it.

This lab's `minikube tunnel` (Docker driver on macOS) is neither case. It
reports `127.0.0.1` as the LB IP and forwards ports from the host over SSH, so
it stands in for a cloud LB without behaving like one.

**3. A Pod inside the cluster calls the LB IP.** With `ipMode: VIP` (the
default), the LB-IP rule also matches traffic that starts inside the cluster:
`KUBE-SERVICES` is reached from both `PREROUTING` (Pods) and `OUTPUT` (the node
itself).

```text
Pod → 203.0.113.10:80 → KUBE-SERVICES LB-IP rule on its own node → KUBE-EXT → backend Pod
```

The packet never leaves the node or reaches the real load balancer. That's
quicker, but anything the LB would have done is skipped: the PROXY protocol
header, TLS termination, the LB's logs.

- **`ipMode: Proxy`** (GA in 1.32): kube-proxy doesn't write the LB-IP rule, so
  such calls really go out to the LB (step 6 below, and
  [cloud-control-manager.md §13.5](cloud-control-manager.md)).
- **AWS** reports a hostname instead of an IP, so kube-proxy has no IP to write
  a rule for, and traffic goes out to the LB anyway.

**4. The LB sends straight to Pod IPs.** Examples are the AWS Load Balancer
Controller with `target-type: ip` and GKE NEGs.

```text
client → LB → 10.244.3.17:8080 (Pod IP) → routed straight to the Pod
```

None of kube-proxy's Service rules are involved: not the LB IP, not
`KUBE-EXT`, not the node port. That's why `allocateLoadBalancerNodePorts: false`
is safe here.

What `KUBE-EXT` does once traffic is in (lab, see "Each kind of change, and what
it does to the rules" in §3.6):

- **`externalTrafficPolicy: Cluster`:** it marks the packet for masquerade, then
  jumps to `KUBE-SVC`, which can pick any endpoint on any node.
- **`externalTrafficPolicy: Local`:** it sends Pod and node-local traffic to
  `KUBE-SVC`, and outside traffic to `KUBE-SVL`, which holds only this node's
  endpoints. There's no masquerade, so the Pod sees the client's IP.
- **With `loadBalancerSourceRanges`:** the LB-IP rule first jumps to
  `KUBE-FW-<hash>`, which lets only the allowed source CIDRs on to `KUBE-EXT`
  (step 5 below).

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

#### Which slice changes when a Pod changes (not measured)

A Service's endpoints aren't stored in one object. The EndpointSlice controller
splits them across several EndpointSlices, each holding at most 100 endpoints
by default. Take a Service with 250 Pods:

```text
Service "web" (250 Pods)
  ├── EndpointSlice web-abc12   endpoints 1–100
  ├── EndpointSlice web-def34   endpoints 101–200
  └── EndpointSlice web-ghi56   endpoints 201–250
```

Now Pod 150 changes. What its slice records depends on what happened:

- **Its container restarts.** The Pod keeps its IP. `web-def34` records it as
  not ready, then as ready again once its readiness probe passes.
- **The Pod is replaced** (deleted, evicted, or rolled by a Deployment). The old
  endpoint is first marked terminating, then removed from `web-def34`. The new
  Pod has a new IP and goes into a slice with room. That is usually
  `web-def34`, which just freed a place, but the controller doesn't promise it.

Either way:

- **Only `web-def34` is rewritten** (or one more slice, if the new Pod lands
  elsewhere). The other slices aren't touched.
- **Each update sends that one slice to every kube-proxy:** about 100 endpoints
  per node.

Before slices, the same change rewrote the single `Endpoints` object for `web`,
with all 250 addresses, and sent all of it to every node.

At scale, for a Service with 5,000 Pods on a 1,000-node cluster:

| | One Pod update sends | Across 1,000 nodes |
|---|---|---|
| `Endpoints`, before the 1,000-address cap existed | the whole object, ~5,000 addresses | ~5 million addresses |
| EndpointSlices | one slice, ~100 addresses | ~100,000 addresses (50 times less) |

- **A rolling update makes this worse,** because every Pod goes through it in
  turn, each with several updates (terminating, removed, new, ready).
- **Today the `Endpoints` object stops at 1,000 addresses** (the cap above). For
  this Service it would be smaller, but wrong: 4,000 Pods missing. kube-proxy
  doesn't read it anyway.

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

For clients running inside the cluster, Kubernetes supports two primary ways of
finding a Service: environment variables and DNS (docs). DNS is §3.1. This
section is about the environment variables, and what they cost.

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

A one-port Service gives 7 variables, which is how 1,501 Services reached
10,520 (1,501 × 7, plus the 13 the Pod has anyway). The general count is below.

#### The docs' example, tested (lab)

The docs describe it this way: when a Pod is run on a Node, the kubelet adds a
set of environment variables for each active Service. It adds
`{SVCNAME}_SERVICE_HOST` and `{SVCNAME}_SERVICE_PORT`, where the Service name is
upper-cased and dashes are converted to underscores. Their example is a Service
`redis-primary` exposing TCP port 6379 on ClusterIP 10.0.0.11:

```text
REDIS_PRIMARY_SERVICE_HOST=10.0.0.11
REDIS_PRIMARY_SERVICE_PORT=6379
REDIS_PRIMARY_PORT=tcp://10.0.0.11:6379
REDIS_PRIMARY_PORT_6379_TCP=tcp://10.0.0.11:6379
REDIS_PRIMARY_PORT_6379_TCP_PROTO=tcp
REDIS_PRIMARY_PORT_6379_TCP_PORT=6379
REDIS_PRIMARY_PORT_6379_TCP_ADDR=10.0.0.11
```

The same Service on minikube, in a namespace `env-demo`. It selects no Pods, so
it has no endpoints:

```yaml
apiVersion: v1
kind: Service
metadata: {name: redis-primary, namespace: env-demo}
spec:
  selector: {app: redis}            # no such Pods: the Service has no endpoints
  ports: [{protocol: TCP, port: 6379}]
```

A `busybox` Pod started afterwards in the same namespace
(`kubectl -n env-demo exec client -- env | grep ^REDIS_PRIMARY | sort`):

```text
REDIS_PRIMARY_PORT=tcp://10.101.214.227:6379
REDIS_PRIMARY_PORT_6379_TCP=tcp://10.101.214.227:6379
REDIS_PRIMARY_PORT_6379_TCP_ADDR=10.101.214.227
REDIS_PRIMARY_PORT_6379_TCP_PORT=6379
REDIS_PRIMARY_PORT_6379_TCP_PROTO=tcp
REDIS_PRIMARY_SERVICE_HOST=10.101.214.227
REDIS_PRIMARY_SERVICE_PORT=6379
```

The same seven variables, with the lab's ClusterIP. "Active" doesn't mean
"has endpoints": a Service with no Pods behind it still gets its variables.

#### A Service with several named ports (lab)

The extra example: one Service with three named ports, two TCP and one UDP.

```yaml
apiVersion: v1
kind: Service
metadata: {name: api-gateway, namespace: env-demo}
spec:
  selector: {app: api}
  ports:
  - {name: http, protocol: TCP, port: 80, targetPort: 8080}
  - {name: grpc, protocol: TCP, port: 9090}
  - {name: metrics-udp, protocol: UDP, port: 8125}
```

```text
API_GATEWAY_SERVICE_HOST=10.99.162.244
API_GATEWAY_SERVICE_PORT=80                         ← the first port only
API_GATEWAY_SERVICE_PORT_HTTP=80                    ← one per named port, name upper-cased
API_GATEWAY_SERVICE_PORT_GRPC=9090
API_GATEWAY_SERVICE_PORT_METRICS_UDP=8125           ← "metrics-udp": dash → underscore
API_GATEWAY_PORT=tcp://10.99.162.244:80             ← the first port only
API_GATEWAY_PORT_80_TCP=tcp://10.99.162.244:80
API_GATEWAY_PORT_80_TCP_ADDR=10.99.162.244
API_GATEWAY_PORT_80_TCP_PORT=80
API_GATEWAY_PORT_80_TCP_PROTO=tcp
API_GATEWAY_PORT_9090_TCP=tcp://10.99.162.244:9090
API_GATEWAY_PORT_9090_TCP_ADDR=10.99.162.244
API_GATEWAY_PORT_9090_TCP_PORT=9090
API_GATEWAY_PORT_9090_TCP_PROTO=tcp
API_GATEWAY_PORT_8125_UDP=udp://10.99.162.244:8125  ← protocol in the name and the URL
API_GATEWAY_PORT_8125_UDP_ADDR=10.99.162.244
API_GATEWAY_PORT_8125_UDP_PORT=8125
API_GATEWAY_PORT_8125_UDP_PROTO=udp
```

- **`_SERVICE_PORT` and `_PORT` hold the first port only.** The others are
  reached by name (`_SERVICE_PORT_<NAME>`) or by number and protocol
  (`_PORT_<port>_<PROTO>`).
- **`targetPort` never appears.** The variables describe the ClusterIP side; the
  Pod port is the Service's business.
- **The count is 3 + 4 per port + 1 per named port.** For `redis-primary`,
  3 + 4 = 7. For `api-gateway`, 3 + 4 × 3 + 3 = 18.

A port name can also come from a tool. `kubectl create service clusterip late
--tcp=8080:8080` names its port `8080-8080`, which gives
`LATE_SERVICE_PORT_8080_8080=8080`.

#### Which Services get variables, and when (lab)

From the same `client` Pod, with two more Services in the namespace:

| Service | Variables? |
|---|---|
| `redis-primary` (ClusterIP, no endpoints) | yes, 7 |
| `api-gateway` (ClusterIP, 3 ports) | yes, 18 |
| `db-headless` (`clusterIP: None`) | none: no IP to put in them |
| `ext-name` (`type: ExternalName`) | none: no IP either |
| `default/kubernetes` (another namespace) | yes, 8 (`KUBERNETES_*`), in every Pod |
| `late`, created after the Pod started | none in the running Pod; a Pod started later has them |

The `client` Pod had 33 Service variables: 7 + 18 + 8.

- **They're written once, when the Pod starts.** A Service created later doesn't
  appear in an already-running Pod, which is one more reason to use DNS names
  instead.
- **Only the Pod's own namespace counts,** plus `KUBERNETES_*` for the API
  server.

To turn them off:

```yaml
spec:
  enableServiceLinks: false
```

A Pod started that way (`client-nolinks`) kept only the API server's
variables:

```text
KUBERNETES_PORT=tcp://10.96.0.1:443
KUBERNETES_PORT_443_TCP=tcp://10.96.0.1:443
KUBERNETES_PORT_443_TCP_ADDR=10.96.0.1
KUBERNETES_PORT_443_TCP_PORT=443
KUBERNETES_PORT_443_TCP_PROTO=tcp
KUBERNETES_SERVICE_HOST=10.96.0.1
KUBERNETES_SERVICE_PORT=443
KUBERNETES_SERVICE_PORT_HTTPS=443
```

In-cluster clients such as client-go need `KUBERNETES_SERVICE_HOST` and
`KUBERNETES_SERVICE_PORT` to find the API server, so these stay.

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
