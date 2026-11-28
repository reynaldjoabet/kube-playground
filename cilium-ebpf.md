# Cilium: Services and policy in eBPF, without kube-proxy

What changes when Cilium replaces both the CNI plugin and kube-proxy: where a ClusterIP is
translated, what's left of iptables, and how an L7 policy is enforced. Run on a two-node k3s
v1.34.1+k3s1 cluster in Docker (`cil-server`, `cil-agent`), started with `--flannel-backend=none
--disable-network-policy --disable-kube-proxy`, and Cilium 1.20.5 from its Helm chart with
`kubeProxyReplacement=true`, VXLAN tunnelling and Kubernetes IPAM.

Labels: **(lab)** seen in the runs below; **(docs)** from the Cilium documentation.

## 0. Short answers

- **No kube-proxy, no KUBE chains.** With Cilium in place, each node had 1 NAT rule mentioning
  `KUBE` and 42 `CILIUM` rules, against the thousands of `KUBE-SVC`/`KUBE-SEP` rules a kube-proxy
  cluster grows ([kubernetes-services.md](kubernetes-services.md)). Services live in BPF maps (§2).
- **The ClusterIP is gone before the packet leaves the Pod.** The client's SYN to `10.43.180.65:80`
  already had a Pod IP as its destination at the client's own interface; the reply was rewritten
  back to the ClusterIP on the way in (§3).
- **Two places Cilium can translate:** at `connect()` (socket LB, cgroup hooks; the packet never
  carries the ClusterIP) or per packet at the Pod's interface (tc). In this nested Docker setup
  socket LB couldn't attach, and Services only worked after limiting it to the host namespace
  (§4).
- **Policy is per identity, not per IP.** Each set of labels gets a numeric identity (the client
  11583, the web Pods 41003); BPF programs check identities, so Pod churn doesn't rewrite rules.
- **L7 rules go through Envoy.** A `CiliumNetworkPolicy` allowing only `GET /allowed` let that
  through (200), answered `403 Forbidden` for `/other`, and dropped connections from a Pod it
  didn't name (§5).

## 1. Starting without a network (lab)

```text
$ kubectl get nodes                       (k3s with no CNI and no kube-proxy)
cil-agent    NotReady
cil-server   NotReady
$ iptables-save -t nat | grep -c KUBE-SVC
0
```

After `kubectl apply` of the rendered chart (agent DaemonSet, operator, `cilium-envoy`
DaemonSet), both nodes went `Ready`. Two Docker-specific fixes were needed first:

- **`/sys/fs/bpf` must be a shared mount.** The agent failed with `path "/sys/fs/bpf" is mounted
  on "/sys" but it is not a shared mount` until `mount --make-rshared /` was run in each node
  container. kind and real nodes don't need this.
- **`k8sServiceHost` must resolve without cluster DNS.** The operator runs in the host network,
  before CoreDNS works; with the node's name it failed `lookup cil-server on 8.8.8.8:53: no such
  host`. The server's IP worked.

## 2. Services as BPF maps (lab)

A Deployment `web` (2 × `traefik/whoami`), Service `web` (ClusterIP `10.43.180.65`), a NodePort
Service on 30080, and a busybox client:

```text
$ cilium-dbg service list
5    10.43.180.65:80/TCP    ClusterIP      1 => 10.245.0.33:80/TCP (active)
                                           2 => 10.245.1.94:80/TCP (active)
6    0.0.0.0:30080/TCP      NodePort       1 => 10.245.0.33:80/TCP (active)
                                           2 => 10.245.1.94:80/TCP (active)

$ cilium-dbg bpf lb list
SERVICE ADDRESS            BACKEND ADDRESS (REVNAT_ID) (SLOT)
10.43.180.65:80/TCP (0)    0.0.0.0:0 (5) (0) [ClusterIP, non-routable]    ← the service entry
10.43.180.65:80/TCP (1)    10.245.0.33:80/TCP (5) (1)                     ← backend slot 1
10.43.180.65:80/TCP (2)    10.245.1.94:80/TCP (5) (2)                     ← backend slot 2
172.18.0.2:30080/TCP (0)   0.0.0.0:0 (7) (0) [NodePort]
```

- **Lookup is a hash map access,** whatever the number of Services; kube-proxy's iptables mode
  walks rules linearly per Service. A change updates one map entry, not a full table rewrite.
- **`REVNAT_ID`** names the reverse translation: replies from a backend are rewritten to the
  service address with it.
- **Requests spread over both Pods:** six calls to the ClusterIP went 3 and 3. `NodePort` worked
  from outside the cluster through `cil-agent`.

## 3. Where the translation happens (lab)

`cilium-dbg monitor` on the client's node while the client fetched `http://10.43.180.65`:

```text
-> endpoint 1465 … identity 11583->41003 state new ifindex lxc2fb57cd364ec orig-ip 10.245.0.156:
   10.245.0.156:35728 -> 10.245.0.33:80 tcp SYN                 ← the SYN, already to a Pod IP
-> endpoint 2765 … identity 41003->11583 state reply ifindex lxc139dff2a9678 orig-ip 10.245.0.33:
   10.43.180.65:80 -> 10.245.0.156:35728 tcp SYN, ACK           ← the reply, as from the ClusterIP
```

- **The client's interface (`lxc…`) is the NAT point.** The BPF program attached there picked a
  backend from the map, rewrote the destination and created a conntrack entry. No node-wide
  NAT table was involved.
- **Identities in every event:** 11583 is the client's label set, 41003 the web Pods'. Policy
  decisions compare these numbers.
- **Before the fix in §4** the same SYN went out with the ClusterIP still in it, through the node's
  `eth0` to the Docker network, and came back as a RST.

## 4. Socket LB and when it can't attach (lab + docs)

With `kubeProxyReplacement=true`, Cilium also translates at the socket: a BPF program on the cgroup
hook for `connect()` swaps the ClusterIP for a backend before any packet exists, and `getpeername()`
still reports the ClusterIP **(docs)**. Here:

```text
cilium-dbg status --verbose:  Socket LB: Enabled   Socket LB Coverage: Full
agent log:  "Failed to setup socket load-balancing tracing with Hubble …" error="no valid cgroup
            path provider found"
result:     every ClusterIP connection timed out, from Pods and from the node; CoreDNS couldn't
            reach the API server at 10.43.0.1, so DNS failed too
```

With socket LB considered on, Cilium skips per-packet translation for Pods, so nothing translated.
`socketLB.hostNamespaceOnly=true` (config `bpf-lb-sock-hostns-only: "true"`) keeps socket LB for
the host's own processes and translates Pod traffic per packet at the `lxc` interface; after that
the client, DNS and the NodePort all worked. The same setting is recommended with sidecar meshes
that need to see the ClusterIP **(docs)**.

## 5. An L7 policy (lab)

```yaml
apiVersion: cilium.io/v2
kind: CiliumNetworkPolicy
metadata: {name: web-l7, namespace: demo}
spec:
  endpointSelector: {matchLabels: {app: web}}
  ingress:
  - fromEndpoints: [{matchLabels: {run: client}}]
    toPorts:
    - ports: [{port: "80", protocol: TCP}]
      rules:
        http: [{method: GET, path: "/allowed"}]
```

```text
client  GET /allowed   HTTP/1.1 200 OK
client  GET /other     HTTP/1.1 403 Forbidden          ← answered by Envoy, not the app
other   GET /allowed   wget: download timed out        ← not in fromEndpoints: dropped in BPF
endpoint list:  web Pod  POLICY (ingress) Enabled  identity 41003
```

- **L3/L4 is decided in BPF;** a connection that's allowed at L4 but has HTTP rules is redirected
  to the node's Envoy (the `cilium-envoy` DaemonSet), which parses the request and applies the
  rules.
- **Standard `NetworkPolicy` works too** (`enable-k8s-networkpolicy: "true"`) and is enforced the
  same way, without the L7 part ([network-policy.md](network-policy.md) runs it on Calico).

## 6. Compared with kube-proxy and Calico (docs + lab)

| | kube-proxy (iptables) | Calico (iptables dataplane) | Cilium (eBPF) |
|---|---|---|---|
| Service translation | `KUBE-SVC`/`KUBE-SEP` NAT chains, per node | kube-proxy's chains | BPF maps, at `connect()` or the Pod's interface |
| Cost of a Service change | rewrite of the NAT table | same | one map update |
| Policy | — | iptables chains per endpoint | BPF, by identity; L7 through Envoy |
| Pod routing | — | BGP routes, no overlay ([calico-layer3.md](calico-layer3.md)) | VXLAN here; native routing possible |
| Visibility | `iptables -L -v` counters | same | `cilium-dbg monitor`, Hubble |

## Related notes

- [kubernetes-services.md](kubernetes-services.md): the iptables version of everything in §2–3.
- [iptables.md](iptables.md), [nftables.md](nftables.md), [conntrack.md](conntrack.md): the
  machinery Cilium bypasses.
- [calico-layer3.md](calico-layer3.md): the other CNI in these notes.
- [network-policy.md](network-policy.md): NetworkPolicy semantics.
