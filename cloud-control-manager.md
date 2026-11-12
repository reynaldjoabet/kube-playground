# Cloud controller managers: how the Hetzner, DigitalOcean, Linode and Scaleway CCMs work

A **cloud controller manager (CCM)** is the part of Kubernetes that talks to a
cloud provider's API. Kubernetes itself knows nothing about servers, load
balancers or private networks. The CCM watches Kubernetes objects and turns
them into cloud API calls.

Related: [kubernetes-services.md](kubernetes-services.md) §4.4, where a
LoadBalancer Service stays `<pending>` because minikube has no CCM.

---

## 1. Where it sits

```
+------------------------- Kubernetes control plane -------------------------+
|                                                                            |
|  kube-apiserver  <-------- watch / update --------+                        |
|     |                                             |                        |
|     |                          +------------------+---------------------+  |
|     |                          | hcloud-cloud-controller-manager (pod)  |  |
|     |                          |                                        |  |
|     |                          |  node controller           --+         |  |
|     |                          |  node lifecycle controller   |  generic|  |
|     |                          |  service controller          |  loops  |  |
|     |                          |  route controller          --+  from   |  |
|     |                          |        |            k8s.io/cloud-provider |
|     |                          |        v                               |  |
|     |                          |  Hetzner plug-in (hcloud/, internal/)  |  |
|     |                          +--------+-------------------+-----------+  |
+-----|-----------------------------------|-------------------|--------------+
      |                                   |                   |
      v                                   v                   v
  kubelets on nodes             Hetzner Cloud API      Hetzner Robot API
                                (servers, LBs,         (dedicated servers,
                                 networks)              optional)
```

The control loops are not written by Hetzner. They come from the upstream
`k8s.io/cloud-provider` library: `main.go` calls
`app.NewCloudControllerManagerCommand(...)`. Hetzner supplies only a plug-in
that implements the `cloudprovider.Interface` methods (`hcloud/cloud.go`):

| Interface method | Hetzner returns | Used by |
|---|---|---|
| `InstancesV2()` | `internal/instance` | node and node-lifecycle controllers |
| `LoadBalancer()` | `hcloud/load_balancers.go` + `internal/hcops` | service controller |
| `Routes()` | `internal/routes` (only if a network is configured) | route controller |
| `Instances()`, `Zones()`, `Clusters()` | `nil, false` (not supported) | – |

## 2. Startup

```
main()
  |
  +-- cloudInitializer()
        |
        +-- config.Read()          env vars: HCLOUD_TOKEN, HCLOUD_NETWORK, ROBOT_*, ...
        +-- cfg.Validate()         e.g. token must be set
        +-- hcloud.NewClient(token, 15 s timeout)
        +-- Robot client           only if ROBOT_ENABLED + user + password
        |                          (wrapped in a cache and a rate limiter)
        +-- if HCLOUD_NETWORK set:
        |     Network.Get(name)    -> network ID
        |     metadata check       "is THIS server attached to that network?"
        +-- Location.List()        cheap call to prove token + connectivity work
        +-- server cache, LB-type cache
        |
        +-- returns cloud{...}  --> the generic controllers start using it
```

`HasClusterID()` returns `false`, so the CCM must be started with
`--allow-untagged-cloud`, as `deploy/ccm-networks.yaml` does. Otherwise
`main.go` exits with "no ClusterID found".

## 3. Node controller: from bare node to cloud node

The kubelet runs with `--cloud-provider=external`. A new node then registers
with the taint `node.cloudprovider.kubernetes.io/uninitialized`, and no
ordinary pod can be scheduled on it until the CCM has filled in the cloud
details.

```
kubelet starts on a server
   |
   v
Node "worker-1" created        taint: node.cloudprovider.kubernetes.io/uninitialized
   |                           spec.providerID: ""   addresses: (from kubelet)
   v
node controller sees it ---> InstanceMetadata(node)
                                |
                                +-- getServer(node)
                                |     providerID set?  yes -> lookup by ID
                                |                      no  -> lookup by NAME in Cloud API
                                |                             (and Robot API, if enabled;
                                |                              same name in both = error)
                                v
                             hcloudServer.Metadata()
                                |
   <----------------------------+  ProviderID     hcloud://12345678
   |                               InstanceType   cpx31          (server type)
   |                               Region         fsn1           (location)
   |                               Zone           fsn1-dc14      (if zone label enabled)
   |                               Addresses      Hostname, ExternalIP (public v4/v6),
   |                                              InternalIP (private net, if configured)
   v
Node updated:  providerID set, labels topology.kubernetes.io/region=fsn1 ...,
               node.kubernetes.io/instance-type=cpx31, taint REMOVED  -> pods can schedule
```

Node naming matters here. A new node has no provider ID yet, so the CCM finds
its server by the node's name (`getServer` in `internal/instance/instances.go`).
**The Kubernetes node name must equal the Hetzner server name**, or the lookup
fails and the node stays tainted.

Provider ID formats (`internal/providerid/providerid.go`):

```
hcloud://12345678      Cloud server ID
hrobot://987654        Robot (dedicated) server number
hcloud://bm-987654     old Robot format from the Syself fork, still accepted
```

Node addresses come from `hcloudNodeAddresses`:

| Address type | Source | When |
|---|---|---|
| `Hostname` | server name | always |
| `ExternalIP` | public IPv4 | address family `ipv4` or `dualstack` |
| `ExternalIP` | a host address from the server's public IPv6 /64 | address family `ipv6` or `dualstack` |
| `InternalIP` | the server's IP in the configured private network | `HCLOUD_NETWORK` set |

## 4. Node lifecycle controller: is the server still there?

This runs periodically for every node:

```
for each Node:
   InstanceExists(node)?
      |-- server not found in API -----------> Node object DELETED from Kubernetes
      |                                        (pods are rescheduled elsewhere)
      |-- found
           InstanceShutdown(node)?
              |-- Cloud server status == "off"
              |   or Robot status == "shut off" --> taint node.cloudprovider.kubernetes.io/shutdown
              |-- running ----------------------> nothing to do
```

This is how a server deleted in the Hetzner console disappears from
`kubectl get nodes` by itself.

## 5. Service controller: `type: LoadBalancer` to a Hetzner Load Balancer

```
kubectl apply  Service web  type: LoadBalancer  port 80 -> nodePort 31234
                   annotations: load-balancer.hetzner.cloud/location: fsn1
   |
   v
service controller ---> EnsureLoadBalancer(svc, nodes)
   |
   +-- lbspec.Resolve()          annotations + HCLOUD_LOAD_BALANCERS_* env defaults
   +-- filterNodes()             optional  .../node-selector
   |
   +-- find the LB:
   |     1. by label  hcloud-ccm/service-uid=<svc UID>
   |     2. else by name (default "a" + UID without dashes, or .../name)
   |     3. else Create()
   |
   +-- ReconcileHCLB()           type, algorithm, network attach/detach, public interface, rDNS
   +-- ReconcileHCLBServices()   one LB "service" per Service port:
   |                               listen 80  -->  destination port = nodePort 31234
   |                               (adds missing, updates existing, deletes extra)
   +-- ReconcileHCLBTargets()    LB targets = the selected nodes
   |                               Cloud servers by server ID (optionally private IP)
   |                               Robot servers by IP
   |
   v
Service status.loadBalancer.ingress = LB public IPv4/IPv6 (or hostname / private IP)
   -> kubectl get svc shows EXTERNAL-IP
```

The resulting traffic path:

```
client --> Hetzner LB (public IP :80) --> any node :31234 (NodePort)
                                            |
                                            +--> kube-proxy NAT --> a pod
```

The Hetzner LB only knows about **nodes**. Getting from a node to a pod is
still kube-proxy's NodePort rules
([kubernetes-services.md](kubernetes-services.md) §4.1).

Other behaviour:

- **Matching by UID label.** The LB is found by the Service UID label first
  (`LabelServiceUID` in `internal/lbspec/spec.go`), so renaming it in the
  console doesn't orphan it. The name fallback lets you adopt an existing LB.
- **The destination port is the NodePort.** Each Service port becomes one
  Hetzner LB "service" that listens on the Service `port` and forwards to the
  node port, and the health check uses the node port too
  (`internal/lbspec/opts.go`):

  ```go
  func (s ServiceSpec) AddServiceOpts(port corev1.ServicePort, ...) hcloud.LoadBalancerAddServiceOpts {
  	destinationPort := int(port.NodePort)
  	opts := hcloud.LoadBalancerAddServiceOpts{
  		ListenPort:      new(int(port.Port)),          // 80 on the LB
  		DestinationPort: new(destinationPort),         // 31234 on each node
  		...
  		HealthCheck:     s.addHealthCheckOpts(destinationPort),
  ```

  Which node address it connects to is decided by `use-private-ip`. The
  default comes from `HCLOUD_LOAD_BALANCERS_USE_PRIVATE_IP`, which is `false`
  (`internal/config/config.go`). So by default the LB connects to each node's
  **public** IP on the node port, and that port has to be reachable there. See
  [kubernetes-services.md](kubernetes-services.md) §2.3 for what that means
  for firewalls.
- **Annotations.** There are 33 `load-balancer.hetzner.cloud/*` annotations in
  `internal/annotation/load_balancer.go`. Examples: `location`, `type`,
  `use-private-ip`, `uses-proxyprotocol`, health-check settings,
  `http-managed-certificate-domains`.
- **Deleting a Service** calls `EnsureLoadBalancerDeleted`, which removes the
  Hetzner LB, unless it has delete protection (§13.7).
- **Disabling it.** `HCLOUD_LOAD_BALANCERS_ENABLED=false` turns this
  controller off.

An example Service:

```yaml
apiVersion: v1
kind: Service
metadata:
  name: web
  annotations:
    load-balancer.hetzner.cloud/location: fsn1
    load-balancer.hetzner.cloud/use-private-ip: "true"   # targets via the private network
spec:
  type: LoadBalancer
  selector: {app: web}
  ports:
  - port: 80
    targetPort: 8080
```

## 6. Route controller: pod traffic without an overlay

This controller is active only when `HCLOUD_NETWORK` is set and routes aren't
disabled (`HCLOUD_NETWORK_ROUTES_ENABLED`, default true). Each node gets a pod
CIDR (`node.spec.podCIDR`). The controller makes the Hetzner private network
route that CIDR to the node's private IP:

```
Hetzner private network 10.0.0.0/8
+--------------------------------------------------------------------+
|  route table (managed by the CCM)                                  |
|     10.244.0.0/24  via 10.0.0.2   (worker-1)                       |
|     10.244.1.0/24  via 10.0.0.3   (worker-2)                       |
|                                                                    |
|   worker-1 10.0.0.2                    worker-2 10.0.0.3           |
|   +------------------+                 +------------------+        |
|   | pod 10.244.0.5 --+---- packet ---->+--> pod 10.244.1.7|        |
|   +------------------+   dst 10.244.1.7, no encapsulation |        |
|                          network looks it up: via 10.0.0.3|        |
+--------------------------------------------------------------------+
```

The logic in `internal/routes/routes.go`:

```
CreateRoute(podCIDR, node)
  +-- IPv6 CIDR?  -> error: private networks don't support IPv6
  +-- resolveRouteTarget()      node's private IP in that network = gateway
  +-- gateway must be one of the node's InternalIP addresses
  +-- upsertRoute()
        existing route for this CIDR with the SAME gateway -> nothing to do
        existing route with a DIFFERENT gateway            -> DeleteRoute, then AddRoute
        none                                               -> Network.AddRoute
DeleteRoute()   for CIDRs of nodes that no longer exist
```

Your CNI must use this native routing instead of an overlay (VXLAN or IPIP),
for example Cilium in native routing mode. Its pod CIDRs must also match the
ones Kubernetes assigns; the CCM logs a warning on a mismatch
(`warnCIDRMismatch`). The shipped manifest enables the
`CloudControllerManagerWatchBasedRoutesReconciliation` feature gate, so routes
are reconciled on node changes as well as on a timer.

## 7. Configuration

All of it is environment variables (`internal/config/config.go`):

| Variable | Effect |
|---|---|
| `HCLOUD_TOKEN` | API token (required) |
| `HCLOUD_NETWORK` | Private network name or ID: enables InternalIPs, routes and private LB targets |
| `HCLOUD_NETWORK_ROUTES_ENABLED` | Route controller on or off |
| `HCLOUD_NETWORK_DISABLE_ATTACHED_CHECK` | Skip the "is this server in the network" startup check |
| `HCLOUD_LOAD_BALANCERS_ENABLED`, `HCLOUD_LOAD_BALANCERS_LOCATION`/`_NETWORK_ZONE`/`_TYPE`... | Service controller on or off, and defaults for annotations |
| `HCLOUD_INSTANCES_ADDRESS_FAMILY` | `ipv4`, `ipv6` or `dualstack` node addresses |
| `HCLOUD_SERVER_CACHE_MODE`, `_MAX_AGE` | How server lookups are cached, to save API calls |
| `ROBOT_ENABLED`, `ROBOT_USER`, `ROBOT_PASSWORD` | Support dedicated (Robot) servers as nodes |
| `HCLOUD_METRICS_ENABLED`, `_ADDRESS` | Prometheus metrics, default `:8233` |

The deployment's arguments (`deploy/ccm-networks.yaml`):

```
--allow-untagged-cloud
--cloud-provider=hcloud
--feature-gates=CloudControllerManagerWatchBasedRoutesReconciliation=true
```

## 8. All together: one node's life

```
1. server "worker-3" boots, kubelet --cloud-provider=external
        -> Node registered + uninitialized taint
2. node controller    -> providerID hcloud://<id>, type, region, IPs, taint removed
3. route controller   -> network route 10.244.3.0/24 via worker-3's private IP
4. service controller -> worker-3 added as a target to every LB whose node selector matches
5. ... server deleted in the Hetzner console ...
6. node lifecycle     -> InstanceExists = false -> Node object deleted
7. route controller   -> route for 10.244.3.0/24 removed
8. service controller -> worker-3 removed from LB targets
```

Without a CCM, none of this happens:

- **With `--cloud-provider=external`,** new nodes keep the `uninitialized`
  taint and ordinary pods never schedule on them.
- **Without that flag,** the kubelet guesses its own addresses and there's no
  provider ID.
- **In either case,** deleted servers linger as `NotReady` nodes, LoadBalancer
  Services stay `<pending>`, and pod traffic between nodes needs an overlay
  network.

---

## 9. DigitalOcean's CCM

DigitalOcean's CCM is built the same way: the control loops come from
`k8s.io/cloud-provider`, and DigitalOcean supplies only the plug-in. The
differences are in what the plug-in does, plus two extra controllers and a
separate webhook. Paths below are relative to `cloud-controller-manager/` in
that repo; the provider code is in `do/`.

### 9.1 The pieces

```
+--------------------------- Kubernetes control plane ----------------------------+
|                                                                                 |
|  kube-apiserver <----------- watch / update ------------+                       |
|     ^                                                   |                       |
|     | validate Service                +-----------------+-------------------+   |
|     | create/update                   | digitalocean-cloud-controller-      |   |
|     |                                 | manager (pod)                       |   |
|  +--+--------------------------+      |                                     |   |
|  | admission server (optional) |      |  node controller         --+ from   |   |
|  |  /lb-service webhook        |      |  node lifecycle controller | k8s.io/|   |
|  |  checks annotations, asks   |      |  service controller      --+ cloud- |   |
|  |  DO API "would this work?"  |      |                              provider|   |
|  +-------------+---------------+      |  ...........................         |   |
|                |                      |  tags controller        --+ DO's own |   |
|                |                      |  firewall controller    --+ (in      |   |
|                |                      |                             Initialize)  |
|                |                      +-----------------+-------------------+   |
+----------------|----------------------------------------|-----------------------+
                 |                                        |
                 v                                        v
           DigitalOcean API  <----------------------------+
           (droplets, load balancers, firewalls, tags, regions)
```

What `do/cloud.go` plugs in:

| Interface method | DigitalOcean | Compared with Hetzner |
|---|---|---|
| `InstancesV2()` | `do/droplets.go` | same role |
| `LoadBalancer()` | `do/loadbalancers.go` (always on) | Hetzner can disable it |
| `Routes()` | `nil, false`: **no route controller** | Hetzner programs private-network routes |
| `Zones()`, `Clusters()`, `Instances()` | `nil, false` | same |
| `Initialize()` | starts **two extra controllers**: tags and firewall | Hetzner only sets up an event recorder |

There's no route controller because pod networking between droplets is left
to the CNI. On DOKS (DigitalOcean's managed Kubernetes) that's Cilium, which
uses its own routing over the VPC.

### 9.2 Startup

```
main()
  opts.KubeCloudShared.CloudProvider.Name = "digitalocean"
  app.NewCloudControllerManagerCommand(opts, doInitializer, ...)
     |
     +-- newCloud()
           +-- DO_ACCESS_TOKEN            required, else exit
           +-- DO_OVERRIDE_URL            optional API endpoint
           +-- DO_API_RATE_LIMIT_QPS      optional client-side rate limit
           +-- godo client (oauth2 token source)
           +-- region:  REGION env, validated against the API's region list,
           |            else read from the droplet metadata service
           +-- DO_CLUSTER_ID, DO_CLUSTER_VPC_ID
           +-- PUBLIC_ACCESS_FIREWALL_NAME + _TAGS   both or neither, else exit
           +-- DEFAULT_LB_TYPE            REGIONAL or REGIONAL_NETWORK (default)
           +-- DEBUG_ADDR (/healthz), METRICS_ADDR
     |
     +-- Initialize()
           +-- shared Service informer
           +-- go ResourcesController.Run()     (tags, §9.6)
           +-- if firewall name set:  go FirewallController (§9.7)
```

`HasClusterID()` returns `false`, so here too the CCM runs with
`--allow-untagged-cloud=true`, as the release manifests show.

### 9.3 Node controller: finding the droplet

The kubelet runs with `--cloud-provider=external`, so a new node waits behind
the `node.cloudprovider.kubernetes.io/uninitialized` taint until the CCM fills
it in:

```
Node "pool-abc-1" registered (tainted)
   |
   v
InstanceMetadata(node)
   |
   +-- providerID set?  ---- yes --> dropletByID(id from "digitalocean://<id>")
   |
   +-- no --> dropletByName(node name):
   |            Droplets.ListByName(name), then the first droplet where
   |              droplet.Name     == node name   or
   |              private IPv4     == node name   or
   |              public IPv4      == node name   or
   |              public IPv6      == node name
   v
   ProviderID    digitalocean://412345678
   InstanceType  s-4vcpu-8gb        (droplet size slug)
   Region        fra1               (no Zone: DO regions have no zones here)
   Addresses     Hostname     = droplet name
                 InternalIP   = private IPv4   (REQUIRED: error if missing)
                 ExternalIP   = public IPv4    (if any)
                 ExternalIP   = public IPv6    (if any)
   |
   v
Node updated, taint removed
```

Two differences from Hetzner (§3):

- **The name match is looser.** The node name may be the droplet's name *or*
  any of its IPs. Hetzner requires the server name exactly.
- **A private IPv4 is mandatory.** Every droplet is in a VPC, so
  `nodeAddresses` (`do/common.go`) fails without one. A public address is
  optional, which allows private-only workers.

### 9.4 Node lifecycle controller

```
InstanceExists(node)
   dropletByID()
     |-- found ----------------------------> true
     |-- API says HTTP 404 ----------------> false  -> Node DELETED
     |-- any other error ------------------> error  -> nothing deleted, retried
InstanceShutdown(node)
   droplet.Status == "off" ----------------> shutdown taint
```

The code comment is explicit: returning `false` without an error deletes the
Node "immediately". That's why only a real 404 counts as "gone". An API outage
can't delete your nodes.

### 9.5 Service controller: LoadBalancer Services

```
Service type: LoadBalancer
   |
   v
EnsureLoadBalancer(svc, nodes)
   |
   +-- annotation service.kubernetes.io/do-loadbalancer-disown: "true"?
   |      -> stop here, leave the LB alone (hand it over to someone else)
   |
   +-- prepareNodesForLBSync()   which nodes may be backends:
   |      ready, no pending-critical-components taint,
   |      public vs private-only (label doks.digitalocean.com/node-network-type)
   |
   +-- find the LB:
   |      1. annotation kubernetes.digitalocean.com/load-balancer-id  -> Get(id)
   |      2. else list all LBs, match by name (.../do-loadbalancer-name, or "a"+UID)
   |
   +-- build request: forwarding rules, health check, firewall (allow/deny rules),
   |                  sticky sessions, TLS / certificates, type, network, size
   |
   +-- exists?  -> updateLoadBalancer()
   |   missing? -> LoadBalancers.Create()   (+ loadBalancerIP only here: BYOIP is create-only)
   |               write load-balancer-id and type annotations back onto the Service
   |
   +-- LB status "new"     -> retry in 15 s (provisioning takes a while)
   +-- LB status not active -> error
   +-- nodes still pending (no IP yet / not ready) -> schedule a retry
   |
   v
status.loadBalancer.ingress = LB IPv4 (+ IPv6), or the hostname annotation
```

The LB ID is written back as an annotation. From then on the LB is found by
ID, so renaming the LB in the DO console doesn't orphan it. Hetzner gets the
same effect with a label on the LB instead (§5).

An example Service:

```yaml
apiVersion: v1
kind: Service
metadata:
  name: web
  annotations:
    service.beta.kubernetes.io/do-loadbalancer-type: REGIONAL
    service.beta.kubernetes.io/do-loadbalancer-healthcheck-path: /healthz
spec:
  type: LoadBalancer
  selector: {app: web}
  ports:
  - port: 80
    targetPort: 8080
```

**The two LB types route differently:**

```
REGIONAL  (layer-7 / proxying LB)
  client --> LB :80 --------------------> any node :31234 (NodePort) --> kube-proxy --> pod
             forwarding rule: entry 80 -> target = port.NodePort

REGIONAL_NETWORK  (layer-4 network LB, the CCM default)
  client --> LB :80 --------------------> node :80 (target = the Service port itself)
             forwarding rule: entry 80 -> target 80
```

This is visible in `buildForwardingRule` (`TargetPort = port.NodePort`) versus
`buildRegionalNetworkForwardingRule` (`TargetPort = port.Port`).

*Inferred, not shown in the code:* the code doesn't show how a node handles
traffic on the Service port. The usual way a network LB works is to deliver
packets with the LB's IP still as the destination. kube-proxy already has a
rule for a Service's load balancer IP: the `"... loadbalancer IP"` entry in
`KUBE-SERVICES` ([kubernetes-services.md](kubernetes-services.md) §4.1). That
rule would catch them and DNAT them to a pod. It also explains why the
firewall controller (§9.7) opens the Service ports on the nodes for this
type.

Which nodes can be backends (from `docs/getting-started.md`):

| `do-loadbalancer-type` | `do-loadbalancer-network` | Private-only droplets as backends |
|---|---|---|
| `REGIONAL` | `EXTERNAL` | yes |
| `REGIONAL_NETWORK` (default) | `EXTERNAL` | **no**: needs nodes with public IPv4 |
| `REGIONAL` | `INTERNAL` | yes |
| `REGIONAL_NETWORK` | `INTERNAL` | yes |

#### Backends are droplets, not IPs

Unlike Hetzner, Scaleway and OpenStack, the CCM never sends a node *address*
to the LB. `buildLoadBalancerRequest` ends with:

```go
dropletIDs, err := l.nodesToDropletIDs(ctx, admit)   // from providerID "digitalocean://<id>", else by name/IP
req.DropletIDs = dropletIDs
req.Region = l.region
req.VPCUUID = l.resources.clusterVPCID              // DO_CLUSTER_VPC_ID
```

DigitalOcean's LB service then decides which interface of each droplet to use.
The repo's `docs/getting-started.md` says DO load balancers "access the cluster
over the VPC interface". For `REGIONAL_NETWORK` + `EXTERNAL`, private-only
droplets aren't supported as backends. That points to that type delivering to
the droplet's **public** IPv4, but the code can't show it, since it's on DO's side.

**Which nodes become backends.** Two filters run before the IDs are built:

```
filterAndClassifyNodes(nodes, lbType, lbNetwork)          (by address)
  network INTERNAL                    → every node passes
  else classifyNode: look at ExternalIPs
       IPv4 + IPv6  → dualStack       ┐
       IPv4 only    → singleStackV4   ├ kept
       IPv6 only    → dropped (IPv6-only not supported for EXTERNAL)
       none         → "publicNetUnready", unless allowsPrivateOnlyBackends:
                        REGIONAL + EXTERNAL → the private InternalIP counts as IPv4, kept
                        REGIONAL_NETWORK + EXTERNAL → dropped
filterByReadiness(kept, droplets already on the LB)       (by state)
  already a backend                   → stays, even if NodeReady flips
  new node                            → only if NodeReady=True and no
                                        readiness.k8s.io/DOKSCriticalComponentsReady taint
```

What happens to the nodes held back:

| Node | Result |
|---|---|
| no public IP yet, label `doks.digitalocean.com/node-network-type` missing or `public` | "still initializing": backends written without it, and a `RetryError` so the sync runs again. Upstream wouldn't re-sync when an ExternalIP appears |
| no public IP, label `private` | left out for good: `permanently omitting ... private-network node(s)` |
| not Ready yet | held back, with a longer retry |
| **none** eligible for `REGIONAL_NETWORK` + `EXTERNAL` | `ErrNoEligibleBackends` and a `LoadBalancerConfigError` event. It never writes an empty backend set |

**Network stack** (`getNetworkStack`, annotation `do-loadbalancer-network-stack`):

| LB | Default | `DUALSTACK` allowed? |
|---|---|---|
| `INTERNAL` | `IPV4` | no (error) |
| `REGIONAL` | `DUALSTACK` (the LB terminates connections, so IPv6 clients can reach IPv4 nodes) | yes |
| `REGIONAL_NETWORK` | `DUALSTACK` only if **all** admitted nodes have an IPv6 ExternalIP, else `IPV4` | only if all do; an explicit `DUALSTACK` with an IPv4-only node is an error |

The LB's IPv6 then appears as a second `status.loadBalancer.ingress` entry
(`if lb.IPv6 != ""`).

**Other defaults in the request:**

| Setting | Default and source |
|---|---|
| health check | the Service's `healthCheckNodePort` path when it has one (`externalTrafficPolicy: Local`), else kube-proxy's `/healthz` on `kubeProxyHealthPort = 10256` (`healthCheckPathAndPort`) |
| LB IP | the `loadBalancerIP` annotation is a BYOIP address, sent on **create only**: "IP is create-only (BYOIP); never send it on update" |
| subnet | `do-loadbalancer-subnet-uuid` picks the VPC subnet, else DO chooses |
| status | `do-loadbalancer-hostname` replaces the IP with a hostname, the workaround for kube-proxy's LB-IP shortcut (kubernetes/kubernetes#66607). There's no `ipMode` |

There are 36 `service.beta.kubernetes.io/do-loadbalancer-*` annotations in
`do/lb_annotations.go`. A few: `protocol`, `tls-ports`, `certificate-id`,
`size-unit`, `algorithm`, `sticky-sessions-type`, `enable-proxy-protocol`,
`allow-rules`/`deny-rules`, `type`, `network`, `healthcheck-*`.

### 9.6 Tags controller (DigitalOcean only)

Every 15 minutes, starting after a random delay of up to 10 minutes
(`do/resources.go`):

```
list Services of type LoadBalancer
   -> find each one's LB (by ID annotation, else by name)
   -> tag all of them  "k8s:<DO_CLUSTER_ID>"
      (creates the tag if it doesn't exist, then retries)
```

This tag is how the DO side, for example DOKS cluster deletion, knows which
load balancers belong to which cluster.

### 9.7 Firewall controller (DigitalOcean only)

This runs only when `PUBLIC_ACCESS_FIREWALL_NAME` and `_TAGS` are set. It
maintains **one** DO cloud firewall, applied to the worker droplets by tag,
and rebuilds its inbound rules from the cluster's Services
(`do/firewall_controller.go`):

```
on every Service change, and every 5 minutes:
   rules = []
   for each Service:
     NodePort, not opted out (kubernetes.digitalocean.com/firewall-managed: "false"):
         allow tcp/udp <nodePort>  from 0.0.0.0/0, ::/0
     LoadBalancer with type REGIONAL_NETWORK + network EXTERNAL:
         allow tcp <health-check port>  from that LB only (by LB UID)
         allow tcp/udp <service port>   from 0.0.0.0/0, ::/0
   outbound: allow all
   --> create or update firewall "<name>", tags "<tags>"
```

Example: one NodePort Service on 30080, and one network LB Service on port
443 with its health check on 31000.

```
worker firewall  "k8s-public-access"   applied to tag "k8s:worker"
  inbound  tcp 30080   0.0.0.0/0, ::/0          <- NodePort Service
  inbound  tcp 31000   LB 6f2c...               <- health check, only from its LB
  inbound  tcp 443     0.0.0.0/0, ::/0          <- network-LB traffic arrives on the Service port
  outbound all
```

The rules are sorted before they're sent, so an unchanged cluster always
produces the same request.

### 9.8 Admission server (separate binary, optional)

`digitalocean-cloud-controller-manager-admission-server` is a validating
webhook on `/lb-service`:

```
kubectl apply Service (type LoadBalancer, bad annotation)
   |
   v
kube-apiserver --> admission webhook /lb-service
                      +-- annotations parse and have legal values?
                      +-- ask the DO API with a validate-only flag (no changes made)
                   <-- deny with an error message
   |
   v
kubectl prints the error; the bad Service is never stored
```

The reason, from `docs/admission-server.md`: the service controller works
asynchronously. A typo in an annotation would otherwise only show up later,
as an event, and could block every later update of that LB, including
changes to its node list. It needs cert-manager, for the webhook's TLS
certificate, and a DO token. DOKS installs it by default.

### 9.9 Hetzner and DigitalOcean side by side

| | Hetzner CCM | DigitalOcean CCM |
|---|---|---|
| Provider ID | `hcloud://<server-id>`, `hrobot://<number>` | `digitalocean://<droplet-id>` |
| Node lookup without provider ID | server name must match | droplet name, private IPv4, public IPv4 or IPv6 |
| Zone label | yes (from location, can be disabled) | no, region only |
| Private address | only with `HCLOUD_NETWORK` | always required (VPC) |
| Route controller | yes, programs private-network routes | none, the CNI handles it |
| Finding a Service's LB | label `hcloud-ccm/service-uid` on the LB, then name | annotation `load-balancer-id` on the Service, then name |
| LB target port | always the NodePort | NodePort (`REGIONAL`) or the Service port (`REGIONAL_NETWORK`) |
| Hand-off of an LB | – | `do-loadbalancer-disown: "true"` |
| Extra controllers | – | tags (`k8s:<cluster-id>`), worker firewall |
| Annotation validation | at reconcile time (events) | also up front, through the admission webhook |
| Bare metal | Robot servers | – |
| Config | env vars | env vars |

---

## 10. Linode's CCM

Linode (now Akamai Cloud) calls its servers **Linodes** and its load balancers
**NodeBalancers**. Paths below are relative to `cloud/` in that repo; the
provider code is in `linode/`.

### 10.1 The pieces

Linode uses the upstream loops like the others, but it also runs more of its
own controllers than any of them, all started from `Initialize()`:

```
+------------------------- generic loops (k8s.io/cloud-provider) --------------+
|  node controller, node lifecycle controller,                                  |
|  service controller, route controller (if enabled)                            |
+-------------------------------------------------------------------------------+
|  Linode's own controllers, started in Initialize():                           |
|                                                                               |
|  node IPAM controller        assigns pod CIDRs to nodes   (--allocate-node-cidrs)
|  node controller (extra)     labels/annotations from the Linode API          |
|  service controller (extra)  deletes NodeBalancers on Service delete/type change
|  token health checker        checks the API token keeps working              |
+-------------------------------------------------------------------------------+
                                     |
                                     v
             Linode API: instances, NodeBalancers, VPCs, Cloud Firewalls
```

| Interface method | Linode | Notes |
|---|---|---|
| `InstancesV2()` | `linode/services/instances.go` | cached list of all Linodes |
| `LoadBalancer()` | `linode/loadbalancers.go` | NodeBalancers |
| `Routes()` | `linode/route_controller.go` | only with `--configure-cloud-routes` / `EnableRouteController` |
| `Zones()`, `Clusters()`, `Instances()` | `nil, false` | |

### 10.2 Startup

```
newCloud()
  +-- LINODE_REGION                    required, else exit
  +-- token: LINODE_API_TOKEN_FILE     (re-read, cached for LINODE_API_TOKEN_CACHE_TTL_SECONDS)
  |          else LINODE_API_TOKEN
  +-- Linode client with Prometheus metrics, request timeout
  +-- token health checker
  +-- validate VPC and subnet flags    (--vpc-names, subnet names)
  +-- NodeBalancer backend subnet setup
  +-- instance cache (all Linodes, refreshed periodically)
  +-- routes client
  +-- validate NodeBalancer options
Initialize()
  +-- node IPAM controller (if --allocate-node-cidrs)
  +-- token health checker loop
  +-- extra service controller   (deletions)
  +-- extra node controller      (metadata)
```

Reading the token from a file that is re-read means a rotated Secret takes
effect without restarting the CCM.

### 10.3 Node controller: finding the Linode

```
InstanceMetadata(node)
   |
   +-- LookupLinode(node)
   |     refresh the cached list of all Linodes
   |     providerID "linode://<id>"?  --> by ID
   |     else                         --> by node name == Linode label
   |     else                         --> by one of the node's IPs
   v
   ProviderID    linode://12345678
   InstanceType  g6-standard-4         (Linode plan)
   Region        eu-central            (no Zone: "not a thing in Linode", per the code)
   Addresses     Hostname     = Linode label
                 InternalIP   = VPC IPv4 addresses (listed first)
                 ExternalIP / InternalIP = each IPv4, by whether it's in a private range
                 VPC IPv6, then public IPv6 (/128 stripped) as ExternalIP
                 + any InternalIP the kubelet itself reported
```

Lookup works off **one cached list of all Linodes** instead of one API call
per node. Address order matters: Kubernetes uses the first `ExternalIP`, so
IPv4 is listed before IPv6 on purpose.

**The extra node controller** fills in what the generic one doesn't:

```
for each Node (re-checked after a TTL):
   LookupLinode()
   set if missing or changed:
     label       node.k8s.linode.com/host-uuid   = UUID of the physical host
     spec.providerID                              = linode://<id>
     annotation  node.k8s.linode.com/private-ip  = the Linode's private IPv4
     annotation  node.k8s.linode.com/public-ipv6 = its public IPv6
```

The `host-uuid` label tells you which **physical machine** a VM runs on. That
means you can spread replicas across hosts with topology spread constraints,
for example `topologyKey: node.k8s.linode.com/host-uuid`. No other CCM here
exposes that.

### 10.4 Node lifecycle controller

```
InstanceExists(node)    LookupLinode -> InstanceNotFound?  -> false -> Node deleted
                                     -> other error        -> error, nothing deleted
InstanceShutdown(node)  status "offline" OR "shutting_down" -> shutdown taint
```

"Shutting down" already counts as shut down, so the taint arrives before the
VM is fully off.

### 10.5 Service controller: NodeBalancers

```
EnsureLoadBalancer(svc, nodes)
   |
   +-- find the NodeBalancer:
   |     1. annotation service.beta.kubernetes.io/linode-loadbalancer-nodebalancer-id
   |     2. else the IP or hostname already in the Service's status.loadBalancer
   |
   +-- found   -> updateNodeBalancer(): one NodeBalancer *config* per Service port,
   |              backend nodes = <node IP>:<nodePort>
   |              (VPC backends via backend-vpc-name / subnet annotations)
   +-- missing -> create it (optionally with a CCM-managed Cloud Firewall)
   |
   +-- nodebalancer-id annotation changed to another NB?
   |     -> delete the OLD NodeBalancer (unless linode-loadbalancer-preserve: "true")
   v
status.loadBalancer.ingress = NodeBalancer IPv4 (+ IPv6 if enabled), or hostname only
```

Traffic path, the classic NodePort design like Hetzner's:

```
client --> NodeBalancer :80 --> node IP :31234 (NodePort) --> kube-proxy --> pod
```

Points worth knowing:

- **Lookup falls back to the Service's own status.** With no ID annotation,
  the NodeBalancer is found by the IP or hostname already recorded on the
  Service. Pointing the ID annotation at a different NodeBalancer makes the
  CCM delete the old one.
- **Firewalls per Service.** `linode-loadbalancer-firewall-acl` takes a JSON
  `allowList` or `denyList` of IPv4/IPv6 CIDRs, and the CCM creates and
  manages a Cloud Firewall on the NodeBalancer from it.
  `linode-loadbalancer-firewall-id` attaches one you manage yourself.
- **Reserved IPs.** `linode-loadbalancer-reserved-ipv4` keeps a fixed
  address, and `retain-reserved-ipv4` keeps it after the Service is deleted.
- **An extra deletion watcher.** Linode's own service controller watches for
  LoadBalancer Services that are deleted, or changed to another type, and
  deletes their NodeBalancer. It retries every minute on failure.

About 35 annotations live in `annotations/annotations.go`, most under
`service.beta.kubernetes.io/linode-loadbalancer-*`. Per-port settings use
`linode-loadbalancer-port-<port>`.

```yaml
apiVersion: v1
kind: Service
metadata:
  name: web
  annotations:
    service.beta.kubernetes.io/linode-loadbalancer-default-protocol: http
    service.beta.kubernetes.io/linode-loadbalancer-check-path: /healthz
    service.beta.kubernetes.io/linode-loadbalancer-firewall-acl: |
      {"allowList": {"ipv4": ["203.0.113.0/24"]}}
spec:
  type: LoadBalancer
  selector: {app: web}
  ports:
  - port: 80
    targetPort: 8080
```

### 10.6 Route controller: pod CIDRs as VPC IP ranges

This is enabled with `--configure-cloud-routes=true` and `--vpc-names=...`.
Instead of a route table like Hetzner's, Linode lets an interface own extra
**IP ranges**. The route controller adds each node's pod CIDR as a range on
that node's VPC interface:

```
Linode VPC "vpc-prod"
+---------------------------------------------------------------------+
|  worker-1 VPC interface 10.0.0.2                                    |
|     ip_ranges: [ 10.192.0.0/24 ]     <- pod CIDR added by the CCM   |
|  worker-2 VPC interface 10.0.0.3                                    |
|     ip_ranges: [ 10.192.1.0/24 ]                                    |
|                                                                     |
|  pod 10.192.0.5 (worker-1) --> dst 10.192.1.7                       |
|     the VPC delivers it to whichever interface owns 10.192.1.0/24   |
+---------------------------------------------------------------------+
```

```
CreateRoute(podCIDR, node)
  +-- IPv6 CIDR?  -> recorded as "configured", nothing sent (avoids endless retries)
  +-- find the node's VPC interface (the entry with an Address)
  +-- this CIDR already in its ranges?  -> done
  +-- else  existing ranges + this CIDR  -> update the interface
```

### 10.7 Node IPAM controller (Linode only)

Normally kube-controller-manager hands out pod CIDRs. Linode's CCM can do it
instead (`--allocate-node-cidrs=true --cluster-cidr=...`), with a
cloud-aware allocator:

```
IPv4: a /24 per node from --cluster-cidr          (--node-cidr-mask-size-ipv4)
IPv6: a /112 per node, carved out of that Linode's own /64,
      at the fixed sub-prefix  <node /64>:0:c::/112   (--node-cidr-mask-size-ipv6)
```

The IPv6 pod range comes from the /64 Linode already routes to that VM. That
is presumably why the route controller skips IPv6 CIDRs (§10.6), though the
code doesn't say so. kube-controller-manager's own
allocator must be off (no `--allocate-node-cidrs` there), or the two fight
over `node.spec.podCIDRs`.

---

## 11. Scaleway's CCM

Scaleway has two kinds of server: **Instances** (VMs) and **Elastic Metal**
(bare metal, called `baremetal` in the code). Paths are relative to
`scaleway/` in that repo.

### 11.1 The pieces

```
+------------------------- generic loops (k8s.io/cloud-provider) -------------+
|  node controller, node lifecycle controller, service controller            |
|  (no route controller)                                                     |
+----------------------------------------------------------------------------+
|  Scaleway plug-in                                                          |
|    servers  --+-- instances  (Instance API)                                |
|               +-- baremetal  (Elastic Metal API)                           |
|    zones, loadbalancers                                                    |
|  sync controller (Initialize, unless DISABLE_TAGS_SYNC)                    |
|    server tags -> node labels/taints,  LB tags -> Service labels           |
+----------------------------------------------------------------------------+
                                     |
                                     v
     Scaleway APIs: Instance, Elastic Metal, Load Balancer, IPAM, Private Networks
```

| Interface method | Scaleway | Notes |
|---|---|---|
| `InstancesV2()` **and** `Instances()` | `servers.go`, dispatching to `instances.go` or `baremetal.go` | the only one of the four that still implements the old `Instances` too |
| `Zones()` | `zones.go` | zones are real here: `fr-par-1`, `nl-ams-2` ... |
| `LoadBalancer()` | `loadbalancers.go` | |
| `Routes()`, `Clusters()` | `nil, false` | |

Any of them can be switched off with `DISABLE_INTERFACES=instances,loadbalancer,zones`.

### 11.2 Startup

```
newCloud()
  +-- scw.NewClient(scw.WithEnv())     SCW_ACCESS_KEY, SCW_SECRET_KEY,
  |                                    SCW_DEFAULT_ORGANIZATION_ID / project,
  |                                    SCW_DEFAULT_REGION (required), SCW_DEFAULT_ZONE
  +-- PN_ID                            optional Private Network ID
  +-- LB_DEFAULT_TYPE                  default LB offer
  +-- SCW_CCM_PREFIX                   prefix for LB names
  +-- SCW_CCM_TAGS (+ _DELIMITER)      tags put on created LBs
  +-- DISABLE_INTERFACES               drop instances / loadbalancer / zones
Initialize()
  +-- sync controller (every 5 min, plus node-add events), unless DISABLE_TAGS_SYNC
```

### 11.3 Node controller: Instance or bare metal?

```
InstanceMetadata(node)
   |
   +-- node has label k8s.scw.cloud/node-public-ip=<ip>?
   |     -> only override the ExternalIP, keep the rest
   |
   +-- providerID empty?
   |     try Instance API by name;  not found -> try Elastic Metal API
   +-- providerID "scaleway://baremetal/..."  -> Elastic Metal API
   +-- providerID anything else               -> Instance API
   v
   ProviderID    scaleway://instance/fr-par-1/<uuid>
   InstanceType  PRO2-S                      (commercial type)
   Region        fr-par
   Zone          fr-par-1                    <- a real zone label
   Addresses     Hostname
                 ExternalIP   first DHCP-provisioned public IPv4
                 ExternalDNS  <id>.pub.instances.scw.cloud
                 InternalIP   Private Network IPs (only PN_ID's network, or all)
                 or legacy    private IP + <id>.priv.instances.scw.cloud
```

Older provider IDs of the form `scaleway://<id>` are still parsed and treated
as Instances.

### 11.4 Node lifecycle controller

```
InstanceExists(node)
   label k8s.scw.cloud/disable-lifecycle present?  -> always "exists" (never deleted)
   else Instance API, then Elastic Metal API
InstanceShutdown(node)
   label k8s.scw.cloud/disable-lifecycle present?  -> never "shut down"
   Instance state running or starting               -> false
   any other state (stopped, stopping, ...)         -> true -> shutdown taint
```

The `disable-lifecycle` label opts a node out entirely. That's useful for
nodes that aren't Scaleway servers at all, in a hybrid cluster.

### 11.5 Service controller: Load Balancers

```
EnsureLoadBalancer(svc, nodes)
   |
   +-- spec.loadBalancerClass set?            -> refuse (not ours)
   +-- scw-loadbalancer-private: "true" but no PN_ID -> error
   |
   +-- find the LB:
   |     1. annotation service.beta.kubernetes.io/scw-loadbalancer-id  "<zone>/<id>"
   |     2. else by name  SCW_CCM_PREFIX + <Service UID>
   |     missing -> create (type from annotation or LB_DEFAULT_TYPE, SCW_CCM_TAGS)
   |
   +-- private/public mode or static IP no longer match the Service?
   |     -> DELETE the LB and create a new one
   |
   +-- LB not "ready" yet -> retry
   |
   +-- updateLoadBalancer():
   |     target IPs = nodes' InternalIPs  if PN_ID / private networks / force-internal-ip
   |                  else nodes' ExternalIPs
   |     one frontend (inbound port) + one backend (forward to <node IP>:<nodePort>) per port
   |     delete frontends/backends that no longer match
   |     spec.loadBalancerSourceRanges -> ACLs on each frontend (max 60 entries per ACL)
   v
status.loadBalancer.ingress = public IPs, or private IPs for a private LB
```

```
client --> Scaleway LB frontend :80 --> backend <node IP>:31234 (NodePort) --> kube-proxy --> pod
```

The NodePort target, in code. Each Service port gets a frontend on the
Service `port` and a backend that forwards to the node port on every node in
`Pool` (`scaleway/loadbalancers.go`, `servicePortToFrontend` and
`servicePortToBackend`):

```go
// frontend: what clients connect to
return &scwlb.Frontend{
	Name:        fmt.Sprintf("%s_%s_%d", string(service.UID), strings.ToLower(string(port.Protocol)), port.Port),
	InboundPort: port.Port,                                   // 80 on the LB
	...

// backend: where the LB sends it
backend := &scwlb.Backend{
	Name:            fmt.Sprintf("%s_%s_%d", string(service.UID), strings.ToLower(string(port.Protocol)), port.NodePort),
	Pool:            nodeIPs,                                  // every matching node's IP
	ForwardProtocol: protocol,
	ForwardPort:     port.NodePort,                            // 31234 on each node
	...

// health check, unless the healthCheckNodePort feature is enabled for that port
healthCheck = &scwlb.HealthCheck{
	Port: port.NodePort,
```

`nodeIPs` are internal or external depending on one line in
`updateLoadBalancer`:

```go
useInternalIPs := getForceInternalIP(service) || l.pnID != "" || len(getPrivateNetworkIDs(service)) > 0
...
if useInternalIPs {
	targetIPs = extractNodesInternalIps(nodes)
} else {
	targetIPs = extractNodesExternalIps(nodes)
}
```

So with no private network (`PN_ID` unset, no private-network annotation,
no `force-internal-ip`), the Scaleway LB connects to each node's **external** IP
on the node port, which has to be reachable there. This is the same situation
as Hetzner's default.

Points worth knowing:

- **Changing `private` or the static IP replaces the LB.** It's a delete and a
  create, so the LB gets a new address unless you pinned one.
- **Externally managed LBs** (marked experimental in the docs). With
  `scw-loadbalancer-externally-managed: "true"`, you create the LB yourself
  and give its ID in `scw-loadbalancer-id`. The CCM then never creates or
  deletes it, ignores size, private mode and IP settings, and only manages
  frontends and backends whose names start with the Service's ID. It refuses
  an LB whose name starts with `SCW_CCM_PREFIX`, i.e. one it created itself.
  Other frontends on the same LB, such as ones for things outside
  Kubernetes, are left alone.
- **The standard field is used for source filtering.** Scaleway uses
  `spec.loadBalancerSourceRanges`, the built-in Kubernetes field, and turns it
  into LB ACLs. The others use their own annotations for this.
- **Many annotations.** There are 49 `service.beta.kubernetes.io/scw-loadbalancer-*`
  annotations in `loadbalancers_annotations.go`.

```yaml
apiVersion: v1
kind: Service
metadata:
  name: web
  annotations:
    service.beta.kubernetes.io/scw-loadbalancer-type: LB-S
    service.beta.kubernetes.io/scw-loadbalancer-health-check-type: http
spec:
  type: LoadBalancer
  selector: {app: web}
  loadBalancerSourceRanges: [203.0.113.0/24]   # becomes an ACL on the LB frontend
  ports:
  - port: 80
    targetPort: 8080
```

### 11.6 Sync controller: tags become labels and taints (Scaleway only)

Scaleway lets you manage node labels and taints **from the cloud side**, by
tagging the server (`sync.go`):

```
every 5 min, and when a node is added:
  for each Node (Instances only, not bare metal yet):
    read the server's tags
    tag "foo=bar"                     -> label  k8s.scaleway.com/foo=bar
    tag "noprefix=example.com/foo=bar"-> label  example.com/foo=bar
    tag "taint=foo=bar:NoSchedule"    -> taint  k8s.scaleway.com/foo=bar:NoSchedule
    tag "node.kubernetes.io/exclude-from-external-load-balancers"
                                      -> that label, value managed-by-scaleway-ccm
    labels/taints with the k8s.scaleway.com/ prefix that no longer have a tag -> removed
  for each LoadBalancer Service with an scw-loadbalancer-id:
    LB tags -> Service labels (same parsing, same cleanup)
```

Only labels the CCM owns (the `k8s.scaleway.com/` prefix, or the exclude
label with its special value) are ever removed. Labels you set by hand on the
node are left alone.

---

## 12. All four side by side

| | Hetzner | DigitalOcean | Linode | Scaleway |
|---|---|---|---|---|
| Provider ID | `hcloud://<id>`, `hrobot://<n>` | `digitalocean://<id>` | `linode://<id>` | `scaleway://instance/<zone>/<uuid>`, `scaleway://baremetal/...` |
| Node lookup without provider ID | server name | name, private or public IP | label, then any IP | name (Instance API, then Elastic Metal) |
| Zone label | yes (location) | no | no | yes (`fr-par-1`) |
| Bare metal | Robot servers | – | – | Elastic Metal |
| Route controller | private-network routes | none | pod CIDR as VPC interface IP range | none |
| Pod CIDR allocation | – | – | own IPAM controller (IPv6 from the node's /64) | – |
| LB found by | label on LB, then name | annotation on Service, then name | annotation on Service, then Service status IP | annotation on Service, then name |
| LB backend port | NodePort | NodePort or Service port (network LB) | NodePort | NodePort |
| Source filtering | annotations | `allow-rules`/`deny-rules` annotations | Cloud Firewall from `firewall-acl` JSON | `spec.loadBalancerSourceRanges` → ACLs |
| Use an LB you created | adopt by name | `disown` hands it off | point `nodebalancer-id` at it | `externally-managed`, shares the LB |
| Extra controllers | – | tags, worker firewall | node metadata (`host-uuid`), deletion watcher, IPAM, token health | tag → label/taint sync |
| Extra admission webhook | – | yes (`/lb-service`) | – | – |
| Opt a node out | – | – | – | `k8s.scw.cloud/disable-lifecycle` label |
| Credentials | `HCLOUD_TOKEN` | `DO_ACCESS_TOKEN` | `LINODE_API_TOKEN` or a re-read token file | `SCW_ACCESS_KEY` + `SCW_SECRET_KEY` |

What they all share is the part that comes from upstream:

```
          the same k8s.io/cloud-provider loops in every CCM
 +------------------------------------------------------------------+
 | node controller          -> InstanceMetadata  -> providerID, type,|
 |                                                  region, IPs, untaint
 | node lifecycle controller-> InstanceExists / InstanceShutdown    |
 | service controller       -> EnsureLoadBalancer / ...Deleted      |
 | route controller         -> CreateRoute / DeleteRoute (optional) |
 +------------------------------------------------------------------+
                 |  each provider only answers these calls
                 v
     Hetzner API | DigitalOcean API | Linode API | Scaleway APIs
```

OVHcloud has no CCM of its own. It runs the OpenStack one; see §14.

Everything in the table above the shared part is each provider's choice
about how to answer those calls, plus whatever extra controllers it decided
to run alongside them.

---

## 13. A LoadBalancer Service, end to end: Hetzner and Scaleway in depth

§5 and §11.5 show what each CCM's service controller does. This section follows one Service through its whole life, using the Hetzner and Scaleway source. Files: Hetzner `hcloud/load_balancers.go`, `internal/hcops/load_balancer.go` and `internal/lbspec/`. Scaleway `scaleway/loadbalancers.go`.

The example is the alternative Service from [deploy/moneystack/service-loadbalancer.yaml](deploy/moneystack/service-loadbalancer.yaml):

```yaml
kind: Service
metadata: {name: moneystack-public, namespace: moneystack}   # UID e.g. 3f2a...-...
spec:
  type: LoadBalancer
  ports: [{name: http, port: 80, targetPort: http}]          # nodePort allocated: say 31845
```

**In short.** Kubernetes decides *when* to act; the provider's code decides *what* to call. Every CCM runs the same generic service controller from Kubernetes, and that controller calls one of the provider's methods:

```
Service created/changed      ──► EnsureLoadBalancer        (create or fix everything, return the IPs)
node added/removed/excluded  ──► UpdateLoadBalancer        (fix the node list only)
Service deleted              ──► EnsureLoadBalancerDeleted (a finalizer holds the Service until this succeeds)
```

A node going NotReady triggers none of them. The cloud LB's own health check takes the node out.

The two clouds structure an LB differently, but the result is the same. Both listen on the Service's `port` (80) and forward to `<node IP>:nodePort` (31845):

```
Hetzner:  LB ─ "services" (listen 80 → dest 31845, health check) ─ targets (servers, shared by all ports)
Scaleway: LB ─ frontend :80 (+ACLs) ─► backend (pool of node IPs, forward 31845, health check)
```

§13.1 and §13.2 below expand these two pictures. The rest of the section follows the code.

### 13.1 Two layers: generic controller, provider code

Every CCM runs the same generic service controller from `k8s.io/cloud-provider` ([watching-the-api-server.md](watching-the-api-server.md) §5). It decides **when** to act. The provider decides **what** to do, by implementing four methods:

```
 generic service controller (same code in every CCM)          provider (Hetzner / Scaleway)
 ───────────────────────────────────────────────────          ─────────────────────────────
 Service created / spec changed       ──► EnsureLoadBalancer(svc, nodes)  → returns status (IPs)
 node added / removed / excluded      ──► UpdateLoadBalancer(svc, nodes)  → targets only
 Service deleted or type changed      ──► EnsureLoadBalancerDeleted(svc)
 (status checks)                      ──► GetLoadBalancer(svc)

 then the generic controller PATCHes status.loadBalancer.ingress → EXTERNAL-IP
 and holds the finalizer until EnsureLoadBalancerDeleted succeeds
```

The node trigger is narrow. A node only causes `UpdateLoadBalancer` when it's added or removed, when its `node.kubernetes.io/exclude-from-external-load-balancers` label changes, or when its `providerID` changes. A node going NotReady doesn't count: the cloud LB's own health check takes it out of rotation.

### 13.2 The provider's own model: what a "load balancer" is made of

The two clouds split an LB into different parts. That's why the code looks different even though the result is the same: listen on `port`, forward to `<node>:nodePort`.

```
 HETZNER                                         SCALEWAY
 Load Balancer  (labels: hcloud-ccm/service-uid) Load Balancer  (name: SCW_CCM_PREFIX + Service UID;
  │  type lb11/21/31, location, algorithm         │   Service gets annotation scw-loadbalancer-id)
  │  public IPv4/IPv6, optional private network   │  IPs (ephemeral or reserved), optional private networks
  │                                               │
  ├─ "services"  ← one per Service port           ├─ frontends  ← one per Service port
  │    listen_port      80                        │    inbound_port 80, ACLs (loadBalancerSourceRanges)
  │    destination_port 31845  (the nodePort)     │      │ points at ↓
  │    protocol tcp | http | https (+certs)       ├─ backends   ← one per Service port
  │    health check → 31845                       │    forward_port 31845 (the nodePort)
  │                                               │    pool: [node IP, node IP, ...]   ← targets live HERE
  └─ targets     ← shared by all services         │    health check → 31845
       server by ID (Cloud) / IP (Robot)          │
       use_private_ip true|false                  └─ (no LB-wide target list)
```

A Hetzner LB **"service"** is a listener: a listen port, a destination port and a protocol. It isn't a Kubernetes Service. One Kubernetes Service with two ports becomes one Hetzner LB with two "services" sharing one target list. On Scaleway, each port's backend carries its own pool of node IPs.

### 13.3 Creating it: Service applied, first EnsureLoadBalancer

```
 kubectl apply service-loadbalancer.yaml
   │  apiserver allocates ClusterIP + nodePort 31845; kube-proxy opens :31845 on every node
   ▼
 generic controller: wantsLoadBalancer → adds finalizer service.kubernetes.io/load-balancer-cleanup
   ▼
 EnsureLoadBalancer(svc, nodes)
```

**Hetzner** (`EnsureLoadBalancer`, quoted in part):

```go
spec, err := lbspec.Resolve(l.recorder, svc, *l.cfg)    // annotations + HCLOUD_LOAD_BALANCERS_* defaults
selectedNodes := filterNodes(spec.NodeSelector, nodes)  // optional node-selector annotation

lb, err = l.lbOps.GetByK8SServiceUID(ctx, svc)          // 1. label hcloud-ccm/service-uid=<UID>
if errors.Is(err, hcops.ErrNotFound) {
	lb, err = l.lbOps.GetByName(ctx, spec.Name)         // 2. adopt by name (migration / import)
}
if errors.Is(err, hcops.ErrNotFound) {
	lb, err = l.lbOps.Create(ctx, svc, spec)            // 3. create
}
lbChanged, err := l.lbOps.ReconcileHCLB(ctx, lb, svc, spec)                     // type, algorithm, network, public iface, rDNS
servicesChanged, err := l.lbOps.ReconcileHCLBServices(ctx, lb, svc, spec)       // listeners
targetsChanged, err := l.lbOps.ReconcileHCLBTargets(ctx, lb, svc, spec, selectedNodes)  // nodes
return &corev1.LoadBalancerStatus{Ingress: l.buildLoadBalancerStatusIngress(lb, spec)}, nil
```

- `Create` refuses to run without a location or network zone, from the annotation or `HCLOUD_LOAD_BALANCERS_LOCATION` / `_NETWORK_ZONE`. A brand-new cluster without either gets an error event and stays `<pending>`.
- Every Hetzner API call returns an **action**, and the CCM waits for each one (`l.ActionClient.WaitFor(ctx, a)`). So EnsureLoadBalancer is a series of blocking steps, not one request.
- If `ReconcileHCLB` changed the network attachment, the LB is reloaded before the targets step. The code comment explains why: detaching and re-attaching drops all private-network targets.

**Scaleway** (`EnsureLoadBalancer`, quoted in part):

```go
if service.Spec.LoadBalancerClass != nil { return nil, fmt.Errorf(... "cannot handle loadBalancerClass" ...) }
if lbPrivate && l.pnID == "" { return nil, fmt.Errorf(... "cannot create private load balancers without a private network") }

lb, err := l.fetchLoadBalancer(ctx, service)            // 1. annotation scw-loadbalancer-id, 2. name prefix + UID
case LoadBalancerNotFound:
	lb, err = l.createLoadBalancer(ctx, service)         //    then annotateAndPatch: writes "<zone>/<id>" onto the Service

privateModeMismatch := lbPrivate != (len(lb.IP) == 0)
reservedIPMismatch := hasLoadBalancerStaticIPs(service) && !hasEqualLoadBalancerStaticIPs(service, lb)
if privateModeMismatch || reservedIPMismatch {           // can't be changed in place:
	l.deleteLoadBalancer(...); l.createLoadBalancer(...) //   delete + recreate (new IP unless reserved)
}
if lb.Status != scwlb.LBStatusReady { return nil, LoadBalancerNotReady }   // generic controller retries (5 s … 300 s)
err = l.updateLoadBalancer(ctx, lb, service, nodes)
status, err := l.createServiceStatus(ctx, service, lb)
```

Scaleway doesn't block on a just-created LB. It returns `LoadBalancerNotReady`, and the generic controller's rate-limited retry calls again later. Hetzner waits inside the call.

### 13.4 Reconciling: diff, then add, update, remove

Both work out the **desired** state from the Service and the nodes, read the **actual** state from the cloud, and fix the differences. Neither keeps state of its own. The cloud resources plus the Service are the whole record.

**Hetzner listeners** (`ReconcileHCLBServices`):

```go
for _, hclbService := range lb.Services { hclbListenPorts[hclbService.ListenPort] = true }   // actual
for _, port := range svc.Spec.Ports {                                                       // desired
	if port.Protocol != "" && port.Protocol != corev1.ProtocolTCP {
		// warning event "UnsupportedProtocolConfigured"; skip (UDP isn't offered)
		continue
	}
	if portExists { l.LBClient.UpdateService(ctx, lb, portNo, opts) }   // every sync, even if unchanged
	else          { l.LBClient.AddService(ctx, lb, opts) }              // opts = AddServiceOpts: 80 -> nodePort
	delete(hclbListenPorts, portNo)
}
for p := range hclbListenPorts { l.LBClient.DeleteService(ctx, lb, p) }  // ports removed from the Service
```

**Hetzner targets** (`ReconcileHCLBTargets`):

```
desired: node.spec.providerID → server ID   ("hcloud://123" Cloud, "hrobot://456" Robot)
           unknown prefix → warning event UnknownProviderIDPrefix, node skipped
actual:  lb.Targets
remove:  target not a current node, OR its use_private_ip differs from the spec (recreated)
add:     current node not a target → AddServerTarget{Server: id, UsePrivateIP: spec.UsePrivateIP}
limit:   numberOfTargets >= lb.LoadBalancerType.MaxTargets → error event, node not added
```

So a cluster that outgrows its LB type, for example 25 targets on `lb11`, keeps working, but the extra nodes never receive LB traffic. Look for the max-targets event on the Service.

**Scaleway frontends and backends** (`updateLoadBalancer`). The order matters, because a frontend refers to a backend:

```
1. nodesInitialized()          wait until nodes have the IPs it needs (internal or external)
2. attachPrivateNetworks()
3. desired = serviceToLB(...)  per TCP port: frontend{inbound 80} + backend{pool node IPs, forward 31845}
                               (non-TCP ports skipped: "skipping unsupported protocol")
4. diff (compareFrontends / compareBackends), only objects whose name starts with the Service UID
5. delete stale frontends      ─┐
6. update / create backends     │  backends must exist before a frontend can point at them
7. update / create frontends   ─┘  then ACLs on each frontend (loadBalancerSourceRanges)
8. delete stale backends           only now that no frontend points at them
```

Comparing only names that start with the Service UID is what makes the externally managed mode safe. Frontends on a shared LB that belong to something else are never in the diff.

### 13.5 What the status says, and why ipMode matters

Hetzner (`buildLoadBalancerStatusIngress`):

```go
if spec.Hostname != "" { return []corev1.LoadBalancerIngress{{Hostname: spec.Hostname}} }
ipMode := corev1.LoadBalancerIPModeVIP
if spec.Service.ProxyProtocol != nil && *spec.Service.ProxyProtocol {
	ipMode = corev1.LoadBalancerIPModeProxy
}
// public IPv4 (+ IPv6 if enabled) when the public interface is on; private IPs if spec.PrivateIngress
```

Scaleway's `ipMode()` does the same: `Proxy` only when **every** port uses PROXY protocol v1 or v2, otherwise `VIP`, unless an annotation sets it explicitly.

Why the CCM reports this. With `ipMode: VIP`, kube-proxy treats the LB IP as a local address, so a Pod that calls the LB IP is short-circuited to a backend Pod without leaving the node. That's fine for plain TCP. With the PROXY protocol, the backend (an ingress controller, for example) expects every connection to start with a PROXY header that only the real LB adds, and a short-circuited connection doesn't have one. With `ipMode: Proxy`, kube-proxy leaves the LB IP alone and traffic really goes out to the LB. The kube-proxy behaviour is from KEP-1860 (`ipMode`, GA in Kubernetes 1.32). It wasn't tested in the lab.

```
 ipMode VIP:    Pod ──► 203.0.113.10 ──(kube-proxy intercepts on the node)──► backend Pod     no PROXY header
 ipMode Proxy:  Pod ──► 203.0.113.10 ──► cloud LB (adds PROXY header) ──► node:31845 ──► backend Pod
```

### 13.6 Node changes: UpdateLoadBalancer

```
 new node joins → node controller sets providerID → generic controller: node added
   → UpdateLoadBalancer(svc, nodes) for EVERY LoadBalancer Service
```

| | Hetzner | Scaleway |
|---|---|---|
| Finds the LB | UID label, then name. If not found: **return nil**, nothing to do. | `fetchLoadBalancer`. Not found is an error. |
| Does | `ReconcileHCLB`, then `ReconcileHCLBTargets`, then `ReconcileHCLBServices` | the whole `updateLoadBalancer` (13.4): pools are inside the backends, so backends are updated |
| Doesn't | create the LB | create the LB |

A node labelled `node.kubernetes.io/exclude-from-external-load-balancers` is dropped from the node list the generic controller passes in, so it's removed as a target on the next update. That's how you keep control-plane nodes out of the LB.

### 13.7 Deleting: EnsureLoadBalancerDeleted, and what stops it

```
 kubectl delete svc moneystack-public
   → deletionTimestamp set; finalizer service.kubernetes.io/load-balancer-cleanup keeps the object
   → EnsureLoadBalancerDeleted(svc)
   → success: finalizer removed → Service disappears.  Failure: retried; Service stays Terminating.
```

Both providers have safety valves that leave the cloud LB in place on purpose:

| Case | Hetzner | Scaleway |
|---|---|---|
| Found by | UID label only (not by name) | annotation, then name |
| Deletion protection / renamed | `if loadBalancer.Protection.Delete` → log "ignored: load balancer deletion protected", return nil | `if lb.Name != GetLoadBalancerName(...)` → warning "was renamed, not removing", return nil |
| Externally managed | n/a | `removeExternallyManagedResources`: only its own frontends and backends |
| IP | the LB's own IP goes with it. An existing **Primary IP** given by ID in `load-balancer.hetzner.cloud/public-net-ipv4` (or `-ipv6`) survives, unless that Primary IP has auto-delete enabled. Changing the annotation later has no effect: the CCM only emits `PrimaryIPChangeUnsupported`, and a different IP needs a new LB. | `ReleaseIP: !hasLoadBalancerStaticIPs(service)`: a reserved IP is kept |

Both return nil in these cases, so the Service is deleted and the finalizer removed while the cloud LB **keeps running and keeps being billed**. To keep an LB on purpose, enable delete protection on Hetzner or rename the LB on Scaleway. If an LB is unexpectedly still there after the Service is gone, check those first.

### 13.8 One Service's life, both clouds

```
 event                         Hetzner calls                                 Scaleway calls
 ─────                         ─────────────                                 ──────────────
 Service created           →   Create LB (wait) · AddService 80→31845 ·      CreateLB · annotate Service ·
                               AddServerTarget × nodes · status IPs          "not ready" → retry · backend
                                                                             (pool, 31845) · frontend 80 ·
                                                                             ACLs · status IPs
 port 443 added            →   AddService 443→32053 (others: UpdateService)  backend + frontend for 443
 node joins                →   AddServerTarget                               UpdateBackend (pool + IP)
 node excluded (label)     →   RemoveServerTarget                            UpdateBackend (pool − IP)
 node NotReady             →   nothing (LB health check marks it down)       nothing (same)
 private toggled           →   attach/detach network, re-add targets         DELETE LB + CREATE LB (new IP)
 Service deleted           →   Delete LB (unless protected)                  DeleteLB, release IP unless
                                                                             reserved (unless renamed)
```

The rest of the packet path doesn't change between the two: cloud LB → node IP:31845 (public or private, see §5 and §11.5) → kube-proxy → a moneystack Pod ([kubernetes-services.md](kubernetes-services.md) §2.3).

---

## 14. OVHcloud: the OpenStack CCM, with Octavia load balancers

### 14.1 There is no "OVH CCM" repository

OVHcloud Public Cloud *is* OpenStack (Nova servers, Neutron networks, Octavia
load balancers), so Managed Kubernetes (MKS) uses the community OpenStack CCM.
From the docs page:

> Our Public Cloud Load Balancer is relying on the OpenStack Octavia project which provides a Cloud Controller Manager (CCM) ...
> For Managed Kubernetes Service (MKS), this Cloud Controller is installed and configured by our team

OVH configures that CCM and runs it on its side; you don't see it as a Pod. On
top of it OVH adds something that isn't in the upstream code. Grepping
cloud-provider-openstack for `ovhcloud` finds nothing:

| OVH-only annotation | Meaning (from OVH's docs) |
|---|---|
| `loadbalancer.ovhcloud.com/class: octavia \| iolb` | which LB product to use. `octavia` is the default from Kubernetes 1.31, `iolb` before that. `iolb` (the older "Load Balancer for Kubernetes") is OVH's own, closed-source, and on its way out |
| `loadbalancer.ovhcloud.com/flavor: small \| medium \| large \| xl` | LB size, MKS Free plan only. Standard uses upstream `loadbalancer.openstack.org/flavor-id` |

So something in front routes a Service to either the Octavia path (the code
below) or IOLB. Which component does that isn't public. The rest of this
section is the Octavia path.

### 14.2 The pieces (what the OpenStack CCM implements)

| Interface | Implementation |
|---|---|
| `InstancesV2` | provider ID `openstack://<region>/<server-uuid>` (`instances.go`). Without one, it looks the server up **by node name** (`getServerByName`). `InstanceShutdown` is true for Nova state `SHUTOFF` |
| `LoadBalancer` | Octavia, only if `[LoadBalancer] enabled` (`openstack.go`) |
| `Routes` | only if Neutron has the `extraroute` extension. Whether OVH turns it on isn't documented |
| `Zones`, `Clusters`, `Instances` (v1) | return `nil, false`: not implemented |

**Node addresses** (`instances_addresses.go`, `nodeAddresses`):

```
every fixed IP on the server's Neutron ports   → InternalIP
server.AccessIPv4 / AccessIPv6                  → ExternalIP
addresses of type "floating"                    → ExternalIP
a fixed IP on a network named in the config's
  [Networking] public-network-name             → ExternalIP (and removed from InternalIP)
```

So "internal" here means a **fixed** IP, not a private one. A node attached
straight to OVH's public network (`Ext-Net`) has a public *fixed* IP. That's an
InternalIP unless OVH lists that network as public in the CCM config, which
isn't visible.

### 14.3 The Octavia model

```
 Octavia LB  (VIP: a private IP on a Neutron subnet)  ◄── optional Floating IP (public)
   └─ listener  :80   (= Service port, AllowedCIDRs = loadBalancerSourceRanges)
        └─ pool   (lb-method, default ROUND_ROBIN)
             ├─ member  <node IP>:31845   (= nodePort)
             ├─ member  <node IP>:31845
             └─ health monitor  (healthCheckNodePort when externalTrafficPolicy: Local)
```

The code behind each line (`loadbalancer.go`):

| Line | Code |
|---|---|
| listener port | `ProtocolPort: int(port.Port)` |
| member port | `ProtocolPort: int(port.NodePort)`. Skipped when `allocateLoadBalancerNodePorts: false` (`NodePort == 0`) |
| member address | `nodeAddressForLB`: tries **InternalIP first**, then ExternalIP, matching the Service's first IP family |
| member subnet | `loadbalancer.openstack.org/member-subnet-id`, else the config's `subnet-id`, else the VIP's subnet |
| health monitor | for ETP `Local`, it probes `MonitorPort = healthCheckNodePort` (`svcConf.healthCheckNodePort`) |
| source ranges | `listenerCreateOpt.AllowedCIDRs = svcConf.allowedCIDR`: filtered on the LB, not by a firewall |

That's the same NodePort design as Hetzner and Scaleway: the LB listens on
`port` and forwards to `nodeIP:nodePort` (§13).

### 14.4 Public or private: the Floating IP

The VIP is **always private**, on a Neutron subnet. Public means a Floating IP
attached to the VIP's port (`ensureFloatingIP`):

```
 openstack-internal-load-balancer: "true"  ──► no FIP. A FIP already attached is detached (and deleted if the CCM made it)
 otherwise, a floating network configured  ──► reuse a FIP (loadBalancerIP), or create one on floating-network-id
 otherwise, no floating network known       ──► warning event "forcing to ensure an internal load balancer service"
 config internal-lb = true                  ──► internal for every Service, whatever the annotation says
```

It returns `floatIP.FloatingIP` if there is one, else `lb.VipAddress`. That
becomes `EXTERNAL-IP` (`createLoadBalancerStatus`):

- **`loadbalancer.openstack.org/hostname` set:** the status is that hostname.
- **PROXY protocol on, and `enable-ingress-hostname`:** the status is a fake
  hostname `<ip>.<suffix>`, so kube-proxy can't take the LB-IP shortcut.
- **PROXY protocol on, otherwise:** `ipMode: Proxy`, for the same reason
  ([kubernetes-services.md](kubernetes-services.md) §2.3).
- **Otherwise:** the IP with `ipMode: VIP`.

The FIP needs a Neutron router on the VIP's subnet. OVH's docs say a public LB
requires an "OVHcloud Gateway (an OpenStack router)" there. A cluster created
with `disablePublicNetworking` can't have public LBs at all. That plausibly
maps to `internal-lb = true` in the config, but it's not visible.

**Security groups:** with `manage-security-groups = true` the CCM creates a
group per Service. It opens each `nodePort`, plus `healthCheckNodePort`, from
the LB subnet's CIDR (`loadbalancer_sg.go`) and attaches the group to the
nodes. The default in code is `false`, and whether OVH enables it isn't
documented.

### 14.5 OVH's three network scenarios, mapped to the code

| OVH name | Cluster | Service annotation | VIP | FIP | Members |
|---|---|---|---|---|---|
| Public-to-Public | no private network: nodes on the public network only | none | a network OVH creates, with "a single OpenStack Gateway ... for all your LoadBalancers" | yes | the node's InternalIP, which here can only be its public fixed IP (inferred) |
| Public-to-Private | nodes on your private network/subnet | none | the cluster's subnet | yes (needs a Gateway on the subnet) | node private IP |
| Private-to-Private | same | `service.beta.kubernetes.io/openstack-internal-load-balancer: "true"` | the cluster's subnet | no | node private IP |

OVH's warning that the Gateway "may throttle" LBs in Public-to-Public mode fits
the inference: there, LB → node traffic goes out through that router to the
nodes' public addresses.

### 14.6 Side by side with §12

| | OVHcloud (Octavia) |
|---|---|
| Provider ID | `openstack://<region>/<uuid>` |
| Node lookup without provider ID | server name |
| Zone label | no (`Zones` not implemented) |
| Route controller | only with Neutron `extraroute` |
| LB found by | annotation `loadbalancer.openstack.org/load-balancer-id`, then name |
| LB backend port | NodePort |
| Backend IP | InternalIP (any *fixed* IP), then ExternalIP |
| Public/private switch | Floating IP or not (`openstack-internal-load-balancer`) |
| Source filtering | `spec.loadBalancerSourceRanges` → listener `AllowedCIDRs` |
| Use an LB you created | `load-balancer-id` (OVH: shared by up to two Services) |
| Credentials | OpenStack (Keystone) credentials in the cloud config, managed by OVH on MKS |
