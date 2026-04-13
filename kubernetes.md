# Kubernetes Playground
## Node Components
- `kubelet` (kubelet and kubelet): An agent that runs on each node in the cluster. It ensures that containers are running in a Pod according to the PodSpecs it receives from the API server.
- `kube-proxy` (kube-proxy): A network proxy that runs on each node, implementing part of the Kubernetes Service concept (managing iptables/IPVS rules).
- `Container Runtime` (containerd, CRI-O): The software that is responsible for running containers, accessed via the Container Runtime Interface (CRI).

## Pod and Workload Components
- `Pod`: The smallest deployable unit. A group of one or more containers that share shared storage and network resources.
- `ReplicaSet` / `Deployment`: Manages rolling updates and ensures a specific number of Pod replicas are running.
- `Service`: An abstract way to expose an application running on a set of Pods as a network service.
- `Ingress`: Manages external access to the services in a cluster, typically HTTP/HTTPS.
- `ConfigMaps` & `Secrets`: Ways to inject configuration and sensitive data into Pods.
- `Volumes` / `PersistentVolumeClaims (PVCs)`: How storage is abstracted and tied to Pods.

`kubectl run my-app --image=nginx`

```sh
+-----------------------------------------------------------------+
|                        User / kubectl                           |
+-----------------------------------------------------------------+
                                |
                                v (REST API Requests)
+-----------------------------------------------------------------+
|                   Control Plane  (The Brain)                    |
|                                                                 |
|   +------------------+       +------------------------------+   |
|   |       etcd       |<----->|                              |   |
|   +------------------+       |                              |   |
|                              |                              |   |
|   +------------------+       |        kube-apiserver        |   |
|   |  kube-scheduler  |<----->|                              |   |
|   +------------------+       |                              |   |
|                              |                              |   |
|   +------------------+       |                              |   |
|   | kube-controller- |<----->|                              |   |
|   |     manager      |       +------------------------------+   |
+-----------------------------------------------------------------+
                                ^
                                | (Instructions & Status)
                                v
+-----------------------------------------------------------------+
|                   Worker Nodes (The Brawn)                      |
|                                                                 |
|  +-----------------------------+ +-----------------------------+|
|  | Node 1                      | | Node 2                      ||
|  |                             | |                             ||
|  |    +-------------------+    | |    +-------------------+    ||
|  |    |     kubelet       |    | |    |     kubelet       |    ||
|  |    +-------------------+    | |    +-------------------+    ||
|  |              |              | |              |              ||
|  |              v              | |              v              ||
|  |    +-------------------+    | |    +-------------------+    ||
|  |    | Container Runtime |    | |    | Container Runtime |    ||
|  |    +-------------------+    | |    +-------------------+    ||
|  |      |               |      | |      |               |      ||
|  |      v               v      | |      v               v      ||
|  |  +-------+       +-------+  | |  +-------+       +-------+  ||
|  |  |  Pod  |       |  Pod  |  | |  |  Pod  |       |  Pod  |  ||
|  |  +-------+       +-------+  | |  +-------+       +-------+  ||
|  |                             | |                             ||
|  |    +-------------------+    | |    +-------------------+    ||
|  |    |    kube-proxy     |    | |    |    kube-proxy     |    ||
|  |    +-------------------+    | |    +-------------------+    ||
|  +-----------------------------+ +-----------------------------+|
+-----------------------------------------------------------------+
```
- `Scheduling`: The `kube-scheduler` loop notices a new Pod in the API with no node assigned to it. It evaluates the available resources on Node 1 and Node 2, decides Node 1 is the best fit, and updates the `kube-apiserver` with that assignment.
- `Node Action`: The `kubelet` on Node 1 is constantly talking to the `kube-apiserver`. It sees that it has been assigned a new Pod.
- `Container Creation`: The `kubelet` tells its `Container Runtime` (containerd, CRI-O, docker) to spin up the actual Nginx container.
- `Networking`: Concurrently, `kube-proxy` updates local network rules (like iptables) so traffic can route correctly to your new application. `kube-proxy `does absolutely nothing if you just create a Pod or a Deployment. It only cares about `Service` objects and their corresponding `Endpoints` (or `EndpointSlices`)
- `Reporting Status`: The `kubelet` reports back to the `kube-apiserver` that the container is running successfully, updating the Actual State in `etcd` to match your Desired State.

The `kubelet` initiates the connection (pull), but it uses a streaming "Watch" so the API server can immediately stream (push) updates down that open pipe.

## Interfaces and Plugins
Kubernetes doesn't know how to run a container, how to route a network packet across nodes, or how to format an AWS EBS volume. It uses standard gRPC interfaces to talk to third-party plugins.

- `CRI (Container Runtime Interface)`: The kubelet uses CRI to talk to a runtime on the node (like containerd or CRI-O). When K8s says "run this pod", the CRI plugin actually pulls the image and sets up the Linux cgroups and namespaces.
  In the code: Look at pkg/kubelet/cri/.
- `CNI (Container Network Interface)`: When a pod starts, the kubelet calls a CNI plugin (like Calico, Cilium, or Flannel). The plugin gives the pod an IP address and wires up the virtual ethernet interfaces (veth pairs) so it can talk to the rest of the cluster.
- `CSI (Container Storage Interface)`: When a Pod needs a Persistent Volume, the CSI driver talks to the cloud provider (AWS, GCP, NetApp), creates the disk, mounts it to the Node, and then the kubelet bind-mounts it into the container

```sh
+-------------------------------------------------------------+
|                          Kubelet                            |
|                                                             |
|   +---------------+   +---------------+   +-------------+   |
|   |      CRI      |   |      CNI      |   |     CSI     |   |
|   | (gRPC Client) |   | (Exec/Binary) |   |(gRPC Client)|   |
|   +---------------+   +---------------+   +-------------+   |
+-----------|-------------------|-------------------|---------+
            |                   |                   |
            v                   v                   v
      +------------+      +-----------+       +-----------+
      | containerd |      |  Calico   |       | AWS EBS   |
      |   CRI-O    |      |  Cilium   |       | CSI Driver|
      +------------+      +-----------+       +-----------+
      (Runs config)       (IP & Routes)       (Disk Mount)
```      

### Pod-to-Pod Networking (The CNI's Job)
When you create a Pod, it needs a real IP address and network routes so it can talk to other Pods.

### Service Load Balancing (kube-proxy's Job)
Pods are ephemeral. If a Pod dies and comes back, its IP address changes. To solve this, you create a `Service`, which provides a stable, virtual IP (ClusterIP).

`iptables-save -t nat` or `iptables -t nat -L` on any Kubernetes worker node running `kube-proxy`
`kube-proxy `essentially abuses the Linux kernel's Network Address Translation (NAT) table to build a distributed load balancer

### The Global Intercept (KUBE-SERVICES chain)
All traffic leaving a Pod or a Node first hits the `PREROUTING` or `OUTPUT` chains, which immediately jump to a custom Kubernetes chain called `KUBE-SERVICES`.

`kube-proxy writes a rule here that says: "If a packet is destined for the fake ClusterIP 10.96.0.100 on port 80, jump to a specific Service chain."`
`-A KUBE-SERVICES -d 10.96.0.100/32 -p tcp -m tcp --dport 80 -j KUBE-SVC-4N57TF3SQZZZZZZZ`
(The `4N57TF3SQZZZZZZZ`... is a hash generated by K8s based on the Service name and namespace).

### The Load Balancer (KUBE-SVC-... chain)
This chain acts as the actual load balancer. Since there are 2 backing Pods, `kube-proxy` uses the `iptables statistic` module to distribute traffic randomly.
```sh
# Give the first Pod a 50% chance of receiving the packet
-A KUBE-SVC-4N57TF3SQZZZZZZZ -m statistic --mode random --probability 0.50000000000 -j KUBE-SEP-W5KXYZ1234567890

# If the first rule didn't trigger, the remaining 50% always hits the second Pod
-A KUBE-SVC-4N57TF3SQZZZZZZZ -j KUBE-SEP-ABCDEF9876543210
```
Note: `If there were 3 pods, the probabilities would be 0.333, then 0.500, then 1.000`.

###  Destination NAT (KUBE-SEP-... chain)
"SEP" stands for Service EndPoint. This chain does the actual magic. It rewrites the destination IP address of the packet (DNAT) from the fake ClusterIP to the real Pod IP.

Let's say the 50% probability hit the first Pod:
```sh
# (Optional) Source NAT if the Pod is trying to talk to its own Service (Hairpin NAT)
-A KUBE-SEP-W5KXYZ1234567890 -s 10.244.1.10/32 -j KUBE-MARK-MASQ 

# The actual DNAT translation! Change the destination to 10.244.1.10:80
-A KUBE-SEP-W5KXYZ1234567890 -p tcp -m tcp -j DNAT --to-destination 10.244.1.10:80
```

#### Why IPVS is replacing iptables for massive clusters
In a cluster with 10,000 Services, `kube-proxy` creates tens of thousands of these `iptables` rules. Because `iptables` evaluates rules sequentially (top to bottom), routing a packet becomes `O(n)`. Every new service makes the network slightly slower.

To fix this, pros switch `kube-proxy` to IPVS mode. IPVS is a highly optimized load balancer built into the Linux kernel that uses hash tables (`O(1)` lookup), so routing takes the exact same amount of time whether you have 10 Services or 100,000 Services.

### LoadBalancer (Uses an External REAL IP)
Kubernetes itself does not have a native load balancer for external traffic. If you create a Service of type LoadBalancer, Kubernetes delegates the job to the Cloud Provider (AWS, GCP, Azure) via the Cloud Controller Manager.


## Service Accounts and RBAC
- `Service Accounts`: Every Pod gets a default Service Account, which provides an identity for processes running in the Pod. The kubelet automatically mounts a token for this Service Account into the Pod, allowing it to authenticate to the API server.
- `RBAC (Role-Based Access Control)`: Kubernetes uses RBAC to control who can do what within the cluster. You define `Roles` (namespaced) or `ClusterRoles` (cluster-wide) that specify permissions, and then bind those roles to users or service accounts with `RoleBindings` or `ClusterRoleBindings`. This way, you can ensure that a Pod only has the permissions it needs to function, following the principle of least privilege.

If a human wants to talk to the Kubernetes API, they use a User Account (usually backed by an external identity provider like Okta, Google, or client certificates).
But what if your application (running inside a Pod) needs to talk to the Kubernetes API?
For example, what if you are running Prometheus and it needs to ask the API server for a list of all Pods to monitor?

That is what a Service Account is for. It is an identity for a machine/process running inside your cluster.
When you create a Pod, Kubernetes automatically mounts a token for the `default` Service Account into the Pod at `/var/run/secrets/kubernetes.io/serviceaccount/token`. This token can be used to authenticate to the API server and perform actions based on the permissions granted to that Service Account.

When a Pod is created, it is usually assigned a ServiceAccount. If you don't specify one, it defaults to the `default` account in that namespace.
On its own, a ServiceAccount has almost no permissions. You must use `Role-Based Access Control `(RBAC) to link a ServiceAccount to a `Role` or `ClusterRole` via a `RoleBinding` or `ClusterRoleBinding`.

## Defining a ServiceAccount
```yaml
apiVersion: v1
kind: ServiceAccount
metadata:
  name: my-service-account
  namespace: default
```
To use this in a Pod, you simply reference it in the spec:
```yaml
apiVersion: v1
kind: Pod
metadata:
  name: my-pod
spec:
  serviceAccountName: my-service-account
  containers:
  - name: my-container
    image: nginx
```
Now, when `my-pod` starts, it will have the token for `my-service-account` mounted inside it, allowing it to authenticate to the Kubernetes API server with whatever permissions are granted to `my-service-account` via RBAC.

## Role/ClusterRole and RoleBinding/ClusterRoleBinding
A `Role` defines a set of rules for what can be done within a specific namespace. If you need the permissions to apply across the entire cluster, you would use a `ClusterRole`.
```yaml
apiVersion: rbac.authorization.k8s.io/v1
kind: Role
metadata:
  name: pod-reader
  namespace: default
rules:
- apiGroups: [""] # "" indicates the core API group
  resources: ["pods"]
  verbs: ["get", "watch", "list"]
```
This `Role` allows read-only access to Pods in the `default` namespace. To grant this Role to our ServiceAccount, we create a `RoleBinding`:
```yaml
apiVersion: rbac.authorization.k8s.io/v1
kind: RoleBinding
metadata:
  name: read-pods-binding
  namespace: default
subjects:
- kind: ServiceAccount
  name: my-service-account
  namespace: default
roleRef:
  kind: Role
  name: pod-reader
  apiGroup: rbac.authorization.k8s.io
```
Now, `my-service-account` has the permissions defined in the `pod-reader` Role, allowing any Pod that uses `my-service-account` to read Pod information in the `default` namespace.

```yaml
apiVersion: v1
kind: ServiceAccount
metadata:
  name: internal-app-sa
  namespace: development
```
The `RoleBinding` grants the permissions defined in the Role to the ServiceAccount.
```yaml
apiVersion: rbac.authorization.k8s.io/v1
kind: RoleBinding
metadata:
  name: read-pods-binding
  namespace: development
subjects:
- kind: ServiceAccount
  name: internal-app-sa # Name of your ServiceAccount
  namespace: development
roleRef:
  kind: Role
  name: pod-reader # Name of your Role
  apiGroup: rbac.authorization.k8s.io
```    
If you just run `kubectl run my-nginx --image=nginx`, you didn't specify a Service Account. But Kubernetes quietly gives it one anyway.

Every namespace has a Service Account named default. When you submit a Pod to the API server:
- The `Service Account Admission Controller` (part of that pipeline we discussed earlier) intercepts the Pod creation request.
- It mutates the Pod definition, automatically injecting a volume mount into every container.
- It mounts a specific directory: `/var/run/secrets/kubernetes.io/serviceaccount/`

If you were to `exec` into literally almost any Pod and look in that directory, you would find three files:
- `token`: A JSON Web Token (JWT) signed by the cluster.
- `ca.crt`: The certificate authority so the Pod can verify it's securely talking to the real API server.
- `namespace`: A text file containing the Pod's namespace.

When your application inside the Pod makes an HTTPS request to the `kube-apiserver`, it includes that token file in the HTTP `Authorization: Bearer <token> header`.

By default, creating a Service Account gives you an identity, but it gives you absolutely zero permissions.

If your Prometheus Pod uses its Service Account to say "Give me a list of all Pods", the API Server's Authorization module (RBAC) will immediately reject it with a 403 Forbidden, because that Service Account doesn't have permission to read Pods.

```yaml
//sa- service account
apiVersion: v1
kind: ServiceAccount
metadata:
  name: prometheus-sa
  namespace: monitoring 
```  

Kubernetes uses `Bound Service Account Token Volumes`.
- The API Server dynamically generates the JWT on the fly when the Kubelet starts the Pod.
- The token has an expiration time (e.g., 1 hour).
- The Kubelet automatically reaches out to the API server to refresh the token and rotates the file on disk before it expires.
- The token is directly tied to the exact Pod UID. If the Pod is deleted, the token instantly becomes invalid, making the cluster highly resistant to stolen credentials theft.

`Always prefer Roles over ClusterRoles whenever possible.`

In Kubernetes, resources are categorized by API Groups. When you define a Role, you need to specify the resource name and its corresponding group

### The Core Group ("")
In your YAML snippet, apiGroups: [""] refers to the Core API Group (sometimes called the "legacy" group).
Because `pods`, `services`, `namespaces`, `configmaps`, and `secrets` were created in K8s v1.0 before API Groups existed, they are permanently grandfathered into this unnamed, empty-string group.
```sh
apiGroups: [""]
pods
services
configmaps
secrets
namespaces
nodes (Requires ClusterRole)
persistentvolumes (Requires ClusterRole)
persistentvolumeclaims
serviceaccounts
endpoints
events
```
### The Workloads & Scaling Groups
These manage the lifecycle of your Pods.
```sh
apiGroups: ["apps"]
deployments
statefulsets
daemonsets
replicasets
apiGroups: ["batch"]
jobs
cronjobs
apiGroups: ["autoscaling"]
horizontalpodautoscalers
```
### Networking (API Group: "networking.k8s.io")
These handle routing traffic into and around the cluster.
```sh
apiGroups: ["networking.k8s.io"]
ingresses
networkpolicies
ingressclasses
```

### The Security & Access Groups
These control who can do what, and structural constraints.
```sh
apiGroups: ["rbac.authorization.k8s.io"]
roles
rolebindings
clusterroles
clusterrolebindings
apiGroups: ["policy"]
poddisruptionbudgets (PDBs)
```

### The Storage Group
For dynamic volume provisioning.
```sh
apiGroups: ["storage.k8s.io"]
storageclasses (Requires ClusterRole)
volumeattachments
csidrivers
```

### Custom Resources (CRDs)
If you have installed operators or tools they will have their own API groups.
Example: `prometheuses.monitoring.coreos.com` or `postgresqls.acid.zalan.do`

### Sub-resources
Some resources have "sub-resources" that allow for more granular control. These are defined using a forward slash `/`.
- `pods/log`: Allows reading logs without full access to the Pod object.
- `pods/exec`: Allows executing commands inside a container.
- `pods/portforward`: Allows opening a port tunnel to a pod.
- `deployments/scale`: Allows an identity to change the replica count without editing the entire deployment spec

If you give someone the `update` verb on `pods`, they can edit the labels. BUT, they cannot read the logs or execute into the container (`kubectl exec`).  
Why? Because reading logs and executing commands are considered Subresources. They live "underneath" the main resource.  
To grant access to these in a Role, you use a slash `/`.  

```yaml
rules:
- apiGroups: [""]
  resources: ["pods"]
  verbs: ["get", "list"]             # Can see the pods
- apiGroups: [""]
  resources: ["pods/log"]            # SUBRESOURCE for logs
  verbs: ["get"]                     # Can run `kubectl logs`
- apiGroups: [""]
  resources: ["pods/exec"]           # SUBRESOURCE for shell access
  verbs: ["create"]                  # Can run `kubectl exec` (Notice the verb is 'create' a session!)
- apiGroups: ["apps"]
  resources: ["deployments/scale"]   # SUBRESOURCE for scaling
  verbs: ["patch", "update"]         # Can run `kubectl scale deployment`
```  
If you ever forget which API Group a resource belongs to, just run `kubectl api-resources`. It prints a table perfectly mapping every resource to its correct API Group!

### The CI/CD Pipeline Deployer
Imagine you have GitHub Actions or Jenkins deploying your application to a production namespace. It needs to update `Deployments`, update `ConfigMaps/Secrets`, and tweak `Services/Ingresses`. It should NOT be allowed to delete the namespace itself or create new RBAC roles

```yaml
---
apiVersion: v1
kind: ServiceAccount
metadata:
  name: cicd-deployer
  namespace: production
---
apiVersion: rbac.authorization.k8s.io/v1
kind: Role
metadata:
  name: cicd-deployer-role
  namespace: production
rules:
  # 1. Manage core application networking and config
  - apiGroups: [""]
    resources: ["services", "configmaps", "secrets"]
    verbs: ["get", "list", "watch", "create", "update", "patch", "delete"]
  
  # 2. Manage workload lifecycle (Deployments, StatefulSets)
  - apiGroups: ["apps"]
    resources: ["deployments", "statefulsets", "daemonsets"]
    verbs: ["get", "list", "watch", "create", "update", "patch", "delete"]
  
  # 3. Allow CI/CD to trigger restarts and scaling directly
  - apiGroups: ["apps"]
    resources: ["deployments/scale", "statefulsets/scale"]
    verbs: ["get", "patch", "update"]
  
  # 4. Manage Ingress routing
  - apiGroups: ["networking.k8s.io"]
    resources: ["ingresses"]
    verbs: ["get", "list", "watch", "create", "update", "patch", "delete"]
---
apiVersion: rbac.authorization.k8s.io/v1
kind: RoleBinding
metadata:
  name: cicd-deployer-binding
  namespace: production
subjects:
  - kind: ServiceAccount
    name: cicd-deployer
    namespace: production
roleRef:
  kind: Role
  name: cicd-deployer-role
  apiGroup: rbac.authorization.k8s.io
 ```

 ###  The "Developer Troubleshooting" Role
In production, developers should theoretically have read-only access. However, when things break, they need to read logs, look at events, and port-forward to a database to debug. They should NOT be allowed to edit `ConfigMaps`, read `Secrets`, or delete `Deployments`.

```yaml 
---
apiVersion: v1
kind: ServiceAccount
metadata:
  # Note: A real developer would use their User Account, 
  # but a troubleshooting pod/script would use this ServiceAccount.
  name: dev-troubleshooter
  namespace: production
---
apiVersion: rbac.authorization.k8s.io/v1
kind: Role
metadata:
  name: dev-troubleshooter-role
  namespace: production
rules:
  # 1. View all core components (Notice Secrets are EXCLUDED)
  - apiGroups: [""]
    resources: ["pods", "services", "configmaps", "endpoints", "persistentvolumeclaims"]
    verbs: ["get", "list", "watch"]
  
  # 2. View all workload controllers
  - apiGroups: ["apps"]
    resources: ["deployments", "statefulsets", "replicasets"]
    verbs: ["get", "list", "watch"]
  
  # 3. Read the cluster event log to see *why* things are crashing
  - apiGroups: ["events.k8s.io"]
    resources: ["events"]
    verbs: ["get", "list", "watch"]
  
  # 4. CRITICAL: The exact subresources needed for debugging
  - apiGroups: [""]
    resources: ["pods/log"]
    verbs: ["get", "list", "watch"]
  - apiGroups: [""]
    resources: ["pods/portforward"]
    verbs: ["create"] # 'create' is required to open a port-forward tunnel
  - apiGroups: [""]
    resources: ["pods/exec"]
    verbs: ["create"] # Optional: Only add if they are allowed to shell into containers
---
apiVersion: rbac.authorization.k8s.io/v1
kind: RoleBinding
metadata:
  name: dev-troubleshooter-binding
  namespace: production
subjects:
  - kind: ServiceAccount
    name: dev-troubleshooter
    namespace: production
roleRef:
  kind: Role
  name: dev-troubleshooter-role
  apiGroup: rbac.authorization.k8s.io
```

### The Monitoring Stack (Prometheus / Grafana)
Prometheus needs to discover every `Pod`, `Service`, and `Endpoint` across ALL namespaces to scrape metrics. This requires a `ClusterRole` (not a namespace-scoped Role), but it should be strictly read-only.
```yaml
---
apiVersion: v1
kind: Namespace
metadata:
  name: monitoring
---
apiVersion: v1
kind: ServiceAccount
metadata:
  name: prometheus
  namespace: monitoring
---
apiVersion: rbac.authorization.k8s.io/v1
kind: ClusterRole
metadata:
  name: prometheus-reader
rules:
  # 1. Discover all targets across all namespaces
  - apiGroups: [""]
    resources: ["pods", "services", "endpoints", "nodes"]
    verbs: ["get", "list", "watch"]

  # 2. Read node-level metrics (required for node-exporter)
  - apiGroups: [""]
    resources: ["nodes/metrics", "nodes/proxy"]
    verbs: ["get"]

  # 3. Discover Ingresses for blackbox probing
  - apiGroups: ["networking.k8s.io"]
    resources: ["ingresses"]
    verbs: ["get", "list", "watch"]

  # 4. If using ServiceMonitor CRDs (Prometheus Operator)
  - apiGroups: ["monitoring.coreos.com"]
    resources: ["servicemonitors", "podmonitors", "prometheusrules"]
    verbs: ["get", "list", "watch"]

  # 5. Read ConfigMaps for Prometheus recording rules
  - apiGroups: [""]
    resources: ["configmaps"]
    verbs: ["get"]
---
apiVersion: rbac.authorization.k8s.io/v1
kind: ClusterRoleBinding
metadata:
  name: prometheus-reader-binding
subjects:
  - kind: ServiceAccount
    name: prometheus
    namespace: monitoring    # <-- CRITICAL: Must match the SA's namespace
roleRef:
  kind: ClusterRole
  name: prometheus-reader
  apiGroup: rbac.authorization.k8s.io
```

### The Namespace Admin (Multi-Tenancy)
In large organizations, you give each team their own namespace and a "Team Admin" who has full control within that namespace, but zero visibility into other teams' namespaces. They can even create their own `Roles` and `RoleBindings` internally—but they can never escalate to `ClusterRole`.
```yaml
---
apiVersion: v1
kind: Namespace
metadata:
  name: team-payments
  labels:
    team: payments
---
apiVersion: v1
kind: ServiceAccount
metadata:
  name: team-payments-admin
  namespace: team-payments
---
apiVersion: rbac.authorization.k8s.io/v1
kind: Role
metadata:
  name: namespace-admin
  namespace: team-payments
rules:
  # 1. Full control over all core resources
  - apiGroups: [""]
    resources: ["pods", "services", "configmaps", "secrets",
                "persistentvolumeclaims", "serviceaccounts", "endpoints"]
    verbs: ["*"]

  # 2. Full control over workloads
  - apiGroups: ["apps"]
    resources: ["deployments", "statefulsets", "daemonsets", "replicasets"]
    verbs: ["*"]

  # 3. Full control over jobs
  - apiGroups: ["batch"]
    resources: ["jobs", "cronjobs"]
    verbs: ["*"]

  # 4. Full control over networking
  - apiGroups: ["networking.k8s.io"]
    resources: ["ingresses", "networkpolicies"]
    verbs: ["*"]

  # 5. Can manage autoscaling
  - apiGroups: ["autoscaling"]
    resources: ["horizontalpodautoscalers"]
    verbs: ["*"]

  # 6. Can create Roles and RoleBindings WITHIN their namespace
  #    (Cannot touch ClusterRoles — that would be privilege escalation)
  - apiGroups: ["rbac.authorization.k8s.io"]
    resources: ["roles", "rolebindings"]
    verbs: ["*"]

  # 7. Debugging subresources
  - apiGroups: [""]
    resources: ["pods/log", "pods/exec", "pods/portforward"]
    verbs: ["*"]

  # 8. View events
  - apiGroups: ["events.k8s.io"]
    resources: ["events"]
    verbs: ["get", "list", "watch"]
---
apiVersion: rbac.authorization.k8s.io/v1
kind: RoleBinding
metadata:
  name: team-payments-admin-binding
  namespace: team-payments
subjects:
  - kind: ServiceAccount
    name: team-payments-admin
    namespace: team-payments
  # You can also bind to real users from your identity provider:
  - kind: User
    name: jane@company.com
    apiGroup: rbac.authorization.k8s.io
  # Or to an entire group from OIDC/LDAP:
  - kind: Group
    name: payments-team
    apiGroup: rbac.authorization.k8s.io
roleRef:
  kind: Role
  name: namespace-admin
  apiGroup: rbac.authorization.k8s.io
```

### The Read-Only Cluster Auditor (Compliance / SOC2)
For compliance audits, security teams need a single account that can read everything across the entire cluster (including Secrets, RBAC, and Node configurations) but cannot modify a single byte. This is how you pass SOC2 and ISO 27001 audits for Kubernetes.
```yaml
---
apiVersion: v1
kind: ServiceAccount
metadata:
  name: cluster-auditor
  namespace: kube-system
---
apiVersion: rbac.authorization.k8s.io/v1
kind: ClusterRole
metadata:
  name: cluster-auditor
rules:
  # Read EVERYTHING across every API group
  - apiGroups: ["*"]
    resources: ["*"]
    verbs: ["get", "list", "watch"]
---
apiVersion: rbac.authorization.k8s.io/v1
kind: ClusterRoleBinding
metadata:
  name: cluster-auditor-binding
subjects:
  - kind: ServiceAccount
    name: cluster-auditor
    namespace: kube-system
roleRef:
  kind: ClusterRole
  name: cluster-auditor
  apiGroup: rbac.authorization.k8s.io
```  

### Configuration Management (ConfigMaps & Secrets)
```yaml
# ConfigMap
apiVersion: v1
kind: ConfigMap
metadata:
  name: app-config
data:
  DATABASE_HOST: "postgres.production.svc"
  LOG_LEVEL: "info"
  nginx.conf: |
    server {
      listen 80;
      location / { proxy_pass http://backend; }
    }
 ```
```yaml
# Secret
apiVersion: v1
kind: Secret
metadata:
  name: app-secrets
type: Opaque
data:
  DATABASE_PASSWORD: cGFzc3dvcmQxMjM=    # base64 encoded
  API_KEY: c2VjcmV0LWtleS14eXo=          # base64 encoded
```
`kubectl get secret app-secrets -o yaml` shows base64 encoded values. To decode them, use:
```sh
echo "cGFzc3dvcmQxMjM=" | base64 --decode
```
```
kubectl get configmap app-config -o yaml shows values in plain text
```

Kubernetes Secrets are NOT encrypted by default. They are only base64 encoded.
- Encrypt etcd at rest. Configure the API server's `EncryptionConfiguration` to use `AES-CBC` or `AES-GCM` encryption. The API server will encrypt Secret data before writing it to etcd, and decrypt it on the way out. ConfigMaps remain unencrypted.
- External Secrets Operator → Syncs secrets from AWS Secrets Manager, HashiCorp Vault, GCP Secret Manager, or Azure Key Vault directly into K8s Secret objects at runtime.
- Sealed Secrets (Bitnami) → Encrypts the Secret YAML with a cluster-specific public key. The encrypted YAML is safe to commit to Git. Only the cluster's controller can decrypt it.
- Disable auto-mounting. If a Pod doesn't need to talk to the API server, prevent the default ServiceAccount token from being mounted:
```yaml
apiVersion: v1
kind: Pod
metadata:
  name: my-app
spec:
  automountServiceAccountToken: false   # <-- No token file injected
  containers:
    - name: app
      image: nginx
```
Both ConfigMaps and Secrets can be consumed in exactly two ways:

Way 1: Environment Variables
```yaml
containers:
  - name: app
    image: my-app
    env:
      - name: DB_HOST
        valueFrom:
          configMapKeyRef:
            name: app-config
            key: DATABASE_HOST
      - name: DB_PASSWORD
        valueFrom:
          secretKeyRef:
            name: app-secrets
            key: DATABASE_PASSWORD
```

Way 2: Volume Mounts (Files)
```yaml
containers:
  - name: app
    image: my-app
    volumeMounts:
      - name: config-volume
        mountPath: /etc/config        # ConfigMap keys become files in this directory
      - name: secret-volume
        mountPath: /etc/secrets       # Secret keys become files (on tmpfs / RAM)
        readOnly: true
volumes:
  - name: config-volume
    configMap:
      name: app-config
  - name: secret-volume
    secret:
      secretName: app-secrets
```

Pro-Tip on Volume Mounts: If you mount a ConfigMap as a volume and then update the ConfigMap with `kubectl apply`, the Kubelet will automatically update the files inside the running container (usually within 30-60 seconds) without restarting the Pod. This is incredibly useful for hot-reloading configuration. Environment variables, however, are baked in at Pod start and never update.

The External Secrets Operator (ESO) is itself a Kubernetes Operator 
### The Two CRDs ESO Installs
1. SecretStore (or ClusterSecretStore): This tells ESO where to find secrets and how to authenticate to the external provider.

2. ExternalSecret: This tells ESO which specific secret to fetch and what K8s Secret to create from it.

```sh
+---------------------------+       +---------------------------+
|   AWS Secrets Manager     |       |   HashiCorp Vault         |
|                           |       |                           |
|  "prod/db-password"       |       |  "secret/data/api-key"    |
|   -> "S3cur3P@ss!"        |       |   -> "xyz-token-999"      |
+-------------+-------------+       +-------------+-------------+
              |                                   |
              |          HTTPS / gRPC             |
              +----------------+------------------+
                               |
                               v
              +----------------+------------------+
              |   External Secrets Operator       |
              |   (Controller Pod in cluster)     |
              |                                   |
              |  1. Watches ExternalSecret CRDs   |
              |  2. Authenticates to provider     |
              |  3. Fetches the secret value      |
              |  4. Creates/Updates K8s Secret    |
              +----------------+------------------+
                               |
                               v
              +----------------+------------------+
              |   Native Kubernetes Secret        |
              |   (in etcd, auto-refreshed)       |
              |                                   |
              |   name: db-credentials            |
              |   data:                           |
              |     password: UzNjdXIzUEBzcyE=    |
              +----------------+------------------+
                               |
                               v
              +----------------+------------------+
              |   Your Application Pod            |
              |   (mounts the K8s Secret normally)|
              +-----------------------------------+
```              

###  A Complete Working Example (AWS Secrets Manager)
Step 1: Create a SecretStore — Tell ESO how to authenticate to AWS.
```yaml
apiVersion: external-secrets.io/v1beta1
kind: SecretStore
metadata:
  name: aws-secret-store
  namespace: production
spec:
  provider:
    aws:
      service: SecretsManager
      region: us-east-1
      auth:
        # Option A: Use a K8s Secret containing AWS credentials
        secretRef:
          accessKeyIDSecretRef:
            name: aws-credentials        # A manually created K8s Secret
            key: access-key-id
          secretAccessKeySecretRef:
            name: aws-credentials
            key: secret-access-key
        # Option B (Better): Use IRSA (IAM Roles for Service Accounts)
        # No static credentials needed. The ESO Pod's ServiceAccount
        # is mapped to an IAM Role via AWS STS federation.
        # jwt:
        #   serviceAccountRef:
        #     name: external-secrets-sa
```
Step 2: Create an ExternalSecret — Tell ESO which secret to fetch and what K8s Secret to produce.
```yaml
apiVersion: external-secrets.io/v1beta1
kind: ExternalSecret
metadata:
  name: db-credentials
  namespace: production
spec:
  refreshInterval: 5m       # <-- ESO re-fetches from AWS every 5 minutes!

  secretStoreRef:
    name: aws-secret-store   # <-- Points to the SecretStore above
    kind: SecretStore

  target:
    name: db-credentials     # <-- The K8s Secret that ESO will create/manage
    creationPolicy: Owner    # <-- If the ExternalSecret is deleted, the K8s Secret is also deleted

  data:
    # Map individual keys from AWS to keys in the K8s Secret
    - secretKey: password              # Key name in the resulting K8s Secret
      remoteRef:
        key: prod/db-password          # The secret name in AWS Secrets Manager
        property: password             # If the AWS secret is JSON, extract this field

    - secretKey: username
      remoteRef:
        key: prod/db-password
        property: username
```
Step 3: Your Pod uses the resulting Secret normally. It has no idea ESO exists.
```yaml
apiVersion: v1
kind: Pod
metadata:
  name: my-app
  namespace: production
spec:
  containers:
    - name: app
      image: my-app:latest
      env:
        - name: DB_PASSWORD
          valueFrom:
            secretKeyRef:
              name: db-credentials    # <-- The Secret ESO created automatically
              key: password
        - name: DB_USERNAME
          valueFrom:
            secretKeyRef:
              name: db-credentials
              key: username
```

### OIDC
Kubernetes ServiceAccount tokens ARE OAuth2/OIDC-compatible JWTs (JSON Web Tokens). The API server is itself an OIDC Identity Provider.

```sh
# Inside a pod:
cat /var/run/secrets/kubernetes.io/serviceaccount/token | cut -d'.' -f2 | base64 -d | jq .
```
```json
{
  "aud": ["https://kubernetes.default.svc"],
  "exp": 1744300800,
  "iat": 1744214400,
  "iss": "https://oidc.eks.us-east-1.amazonaws.com/id/ABCDEF1234567890",
  "kubernetes.io": {
    "namespace": "production",
    "pod": {
      "name": "my-app-7b9f4d6c8-x2k9m",
      "uid": "a1b2c3d4-e5f6-7890-abcd-ef1234567890"
    },
    "serviceaccount": {
      "name": "my-app-sa",
      "uid": "f6e5d4c3-b2a1-0987-6543-210fedcba987"
    }
  },
  "sub": "system:serviceaccount:production:my-app-sa"
}
```

### Workload Identity Federation (Talking to AWS/GCP/Azure)
Here is where it gets powerful. Your Pod has a JWT signed by Kubernetes. AWS has IAM Roles. How do you connect them without storing any AWS access keys?

The answer is OIDC Federation 
#### How AWS IRSA (IAM Roles for Service Accounts) Works
```sh
+------------------+     +------------------+     +------------------+     +------------------+
|   Pod in EKS     |     |   AWS SDK        |     |   AWS STS        |     | EKS OIDC Endpoint|
|   (has K8s JWT)  |     |   (inside Pod)   |     |  (Token Exchange)|     |  (Public Internet)|
+--------+---------+     +--------+---------+     +--------+---------+     +--------+---------+
         |                        |                        |                        |
         | Pod starts. Kubelet mounts JWT at:              |                        |
         | /var/run/secrets/eks.amazonaws.com/.../token    |                        |
         |                        |                        |                        |
         | EKS webhook injected env vars:                  |                        |
         | AWS_ROLE_ARN=arn:aws:iam::123..:role/MyAppRole   |                        |
         | AWS_WEB_IDENTITY_TOKEN_FILE=/var/run/.../token   |                        |
         |                        |                        |                        |
         |  1. app calls          |                        |                        |
         |     s3.ListBuckets()   |                        |                        |
         |----------------------->|                        |                        |
         |                        |                        |                        |
         |                        |  2. AssumeRoleWith     |                        |
         |                        |     WebIdentity(       |                        |
         |                        |       RoleArn, JWT)    |                        |
         |                        |----------------------->|                        |
         |                        |                        |                        |
         |                        |    +-------------------------------------------------+
         |                        |    |  VERIFICATION PHASE                             |
         |                        |    +-------------------------------------------------+
         |                        |                        |                        |
         |                        |                        |  3. GET /.well-known/  |
         |                        |                        |     openid-configuration
         |                        |                        |----------------------->|
         |                        |                        |                        |
         |                        |                        |  4. Response:          |
         |                        |                        |     { jwks_uri: ".." } |
         |                        |                        |<-----------------------|
         |                        |                        |                        |
         |                        |                        |  5. GET /keys (JWKS)   |
         |                        |                        |----------------------->|
         |                        |                        |                        |
         |                        |                        |  6. Response:          |
         |                        |                        |     { keys: [{RSA..}]} |
         |                        |                        |<-----------------------|
         |                        |                        |                        |
         |                        |                        |  7. Verify JWT         |
         |                        |                        |     signature using    |
         |                        |                        |     public key         |
         |                        |                        |                        |
         |                        |                        |  8. Check exp          |
         |                        |                        |     (not expired?)     |
         |                        |                        |                        |
         |                        |                        |  9. Check aud ==       |
         |                        |                        |     "sts.amazonaws.com"|
         |                        |    +-------------------------------------------------+
         |                        |    |  AUTHORIZATION PHASE                            |
         |                        |    +-------------------------------------------------+
         |                        |                        |                        |
         |                        |                        | 10. Fetch IAM Role     |
         |                        |                        |     trust policy       |
         |                        |                        |                        |
         |                        |                        | 11. Match JWT claims:  |
         |                        |                        |     iss == "https://   |
         |                        |                        |       oidc.eks.../     |
         |                        |                        |       id/ABCDEF"       |
         |                        |                        |     sub == "system:    |
         |                        |                        |       serviceaccount:  |
         |                        |                        |       production:      |
         |                        |                        |       my-app-sa"       |
         |                        |                        |                        |
         |          +------------------------------------------+                    |
         |          | IF ALL CHECKS PASS                       |                    |
         |          +------------------------------------------+                    |
         |                        |                        |                        |
         |                        | 12. Temporary AWS      |                        |
         |                        |     credentials:       |                        |
         |                        |     AccessKeyId        |                        |
         |                        |     SecretAccessKey    |                        |
         |                        |     SessionToken       |                        |
         |                        |     (expires 1 hour)   |                        |
         |                        |<-----------------------|                        |
         |                        |                        |                        |
         |                        |                        |                        |
         |                        |                +------------------+             |
         |                        |                |    AWS S3        |             |
         |                        |                | (Target Resource)|             |
         |                        |                +--------+---------+             |
         |                        |                         |                       |
         |                        | 13. ListBuckets() with  |                       |
         |                        |     temporary creds     |                       |
         |                        |------------------------>|                       |
         |                        |                         |                       |
         |                        | 14. { Buckets: [...] }  |                       |
         |                        |<------------------------|                       |
         |                        |                         |                       |
         | 15. Results returned   |                         |                       |
         |<-----------------------|                         |                       |
         |                        |                         |                       |
         |          +------------------------------------------+                    |
         |          | IF ANY CHECK FAILS                       |                    |
         |          +------------------------------------------+                    |
         |                        |                        |                        |
         |                        | AccessDenied           |                        |
         |                        |<-----------------------|                        |
         | Error raised           |                        |                        |
         |<-----------------------|                        |                        |
         |                        |                        |                        |
         |                        |                        |                        |
         | +--------------------------------------------------------------------+   |
         | | 55 MINUTES LATER...                                                 |  |
         | | Kubelet has already rotated the JWT file on disk.                    |  |
         | | AWS SDK detects credentials are about to expire.                    |  |
         | | SDK re-reads the new JWT and calls STS again (step 2).              |  |
         | | Zero downtime. Zero human intervention.                             |  |
         | +--------------------------------------------------------------------+   |
           
```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Principal": {
        "Federated": "arn:aws:iam::123456789012:oidc-provider/oidc.eks.us-east-1.amazonaws.com/id/ABCDEF"
      },
      "Action": "sts:AssumeRoleWithWebIdentity",
      "Condition": {
        "StringEquals": {
          "oidc.eks.us-east-1.amazonaws.com/id/ABCDEF:sub": "system:serviceaccount:production:my-app-sa",
          "oidc.eks.us-east-1.amazonaws.com/id/ABCDEF:aud": "sts.amazonaws.com"
        }
      }
    }
  ]
}
```
This trust policy says: "Only accept JWTs from my specific EKS cluster, AND only if the sub claim matches the exact ServiceAccount my-app-sa in the production namespace."

#### The Kubernetes Side: Annotate the ServiceAccount
```yaml
apiVersion: v1
kind: ServiceAccount
metadata:
  name: my-app-sa
  namespace: production
  annotations:
    # This annotation tells the AWS SDK inside the Pod which IAM Role to assume
    eks.amazonaws.com/role-arn: "arn:aws:iam::123456789012:role/MyAppS3AccessRole"
   ```

   #### The Pod: Zero Configuration 
   ```yaml
   apiVersion: apps/v1
kind: Deployment
metadata:
  name: my-app
  namespace: production
spec:
  template:
    spec:
      serviceAccountName: my-app-sa    # <-- Just use the annotated SA
      containers:
        - name: app
          image: my-app:latest
          # NO env vars with AWS_ACCESS_KEY_ID needed!
          # The AWS SDK automatically:
          # 1. Reads the projected JWT from the mounted volume
          # 2. Calls STS AssumeRoleWithWebIdentity
          # 3. Gets temporary credentials
          # 4. Uses them transparently
```
     
### Custom Audiences: Using K8s Tokens With Your Own Services
You can also request a token with a custom audience so your own microservices can verify it. This is how you build zero-trust service-to-service authentication without a service mesh.
```yaml
apiVersion: v1
kind: Pod
metadata:
  name: my-app
spec:
  serviceAccountName: my-app-sa
  containers:
    - name: app
      image: my-app
      volumeMounts:
        - name: vault-token
          mountPath: /var/run/secrets/vault
  volumes:
    - name: vault-token
      projected:
        sources:
          - serviceAccountToken:
              path: token
              expirationSeconds: 3600           # Expires in 1 hour
              audience: "https://vault.company.com"  # Custom audience!
```              

#### The Problem OIDC Solves
Without OIDC, there are only two ways to authenticate a human to Kubernetes:

Client Certificates — You generate a TLS certificate for every developer. Nightmare to manage and impossible to revoke without rotating the entire cluster CA.
Static Token Files — You hardcode usernames and passwords in a file on the API server. Insecure and doesn't scale.
OIDC lets you say: "If Okta says you are jane@company.com and you belong to the platform-team group, I trust you."
```sh
+------------------+     +------------------+     +------------------+     +------------------+
|   Developer      |     |   kubectl /      |     |   Identity       |     |  kube-apiserver  |
|   (Jane)         |     |   kubelogin      |     |   Provider       |     |                  |
|                  |     |   (OIDC Plugin)  |     |   (Okta/Google)  |     |                  |
+--------+---------+     +--------+---------+     +--------+---------+     +--------+---------+
         |                        |                        |                        |
         |  1. kubectl get pods   |                        |                        |
         |----------------------->|                        |                        |
         |                        |                        |                        |
         |                        |  2. No valid token     |                        |
         |                        |     cached. Open       |                        |
         |                        |     browser for login  |                        |
         |  3. Browser opens      |                        |                        |
         |     Okta login page    |                        |                        |
         |<-----------------------|                        |                        |
         |                        |                        |                        |
         |  4. Jane enters        |                        |                        |
         |     username/password  |                        |                        |
         |     + MFA              |                        |                        |
         |--------------------------------------->|                                 |
         |                        |                        |                        |
         |                        |  5. Okta verifies       |                        |
         |                        |     credentials and    |                        |
         |                        |     returns:           |                        |
         |                        |     - ID Token (JWT)   |                        |
         |                        |     - Refresh Token    |                        |
         |                        |<-----------------------|                        |
         |                        |                        |                        |
         |                        |  6. kubectl sends      |                        |
         |                        |     API request with   |                        |
         |                        |     Authorization:     |                        |
         |                        |     Bearer <ID Token>  |                        |
         |                        |-----------------------------------------------> |
         |                        |                        |                        |
         |                        |                        |     7. API server      |
         |                        |                        |        verifies JWT:    |
         |                        |                        |        - Fetch JWKS    |
         |                        |                        |          from Okta     |
         |                        |                        |        - Check sig     |
         |                        |                        |        - Check exp     |
         |                        |                        |        - Check iss     |
         |                        |                        |        - Extract:      |
         |                        |                        |          sub (user)    |
         |                        |                        |          groups        |
         |                        |                        |                        |
         |                        |                        |     8. Map claims      |
         |                        |                        |        to K8s identity:|
         |                        |                        |        User: jane@     |
         |                        |                        |          company.com   |
         |                        |                        |        Groups:         |
         |                        |                        |          [platform-    |
         |                        |                        |           team]        |
         |                        |                        |                        |
         |                        |                        |     9. RBAC check:     |
         |                        |                        |        Does "jane" or  |
         |                        |                        |        "platform-team" |
         |                        |                        |        have permission |
         |                        |                        |        to GET pods?    |
         |                        |                        |                        |
         |                        |  10. 200 OK            |                        |
         |                        |      { pods: [...] }   |                        |
         |                        |<----------------------------------------------- |
         |                        |                        |                        |
         |  11. Results displayed  |                       |                        |
         |<-----------------------|                        |                        |
  ```       
  ### Configuring the API Server
The kube-apiserver must be started with flags that tell it which OIDC provider to trust:
```sh
kube-apiserver \
  --oidc-issuer-url="https://company.okta.com/oauth2/default" \
  --oidc-client-id="kubernetes-cluster-prod" \
  --oidc-username-claim="email" \
  --oidc-groups-claim="groups" \
  --oidc-username-prefix="oidc:" \
  --oidc-groups-prefix="oidc:"
```

### Binding OIDC Users and Groups to K8s Roles
Once the API server extracts the user and groups from the JWT, you bind them to Roles using standard RBAC.

Bind a specific user:
```yaml
apiVersion: rbac.authorization.k8s.io/v1
kind: ClusterRoleBinding
metadata:
  name: jane-cluster-admin
subjects:
  - kind: User
    name: "oidc:jane@company.com"     # <-- Must match the prefix + claim value
    apiGroup: rbac.authorization.k8s.io
roleRef:
  kind: ClusterRole
  name: cluster-admin
  apiGroup: rbac.authorization.k8s.io
```
#### Bind an entire team/group (the scalable approach):
```yaml
---
# The Role: What can be done
apiVersion: rbac.authorization.k8s.io/v1
kind: Role
metadata:
  name: developer-access
  namespace: production
rules:
  - apiGroups: [""]
    resources: ["pods", "services", "configmaps"]
    verbs: ["get", "list", "watch"]
  - apiGroups: [""]
    resources: ["pods/log", "pods/portforward"]
    verbs: ["get", "create"]
  - apiGroups: ["apps"]
    resources: ["deployments", "replicasets"]
    verbs: ["get", "list", "watch"]
---
# The Binding: WHO gets this Role
apiVersion: rbac.authorization.k8s.io/v1
kind: RoleBinding
metadata:
  name: platform-team-dev-access
  namespace: production
subjects:
  - kind: Group
    name: "oidc:platform-team"           # <-- Every member of this Okta group
    apiGroup: rbac.authorization.k8s.io
  - kind: Group
    name: "oidc:backend-team"            # <-- Another Okta group gets the same access
    apiGroup: rbac.authorization.k8s.io
roleRef:
  kind: Role
  name: developer-access
  apiGroup: rbac.authorization.k8s.io
 ```
 Now when a new developer joins the platform-team group in Okta, they instantly get Kubernetes access without anyone touching kubectl or creating certificates. When they leave the company and HR disables their Okta account, their JWT can no longer be issued, and access is revoked automatically.

 ### The kubectl Side: kubeconfig
The developer's` ~/.kube/config` uses the oidc auth provider (or the newer kubelogin exec plugin):   
```yaml
apiVersion: v1
kind: Config
clusters:
  - name: prod-cluster
    cluster:
      server: https://k8s-api.company.com:6443
      certificate-authority-data: LS0tLS1CRU...
users:
  - name: oidc-user
    user:
      exec:
        apiVersion: client.authentication.k8s.io/v1beta1
        command: kubectl
        args:
          - oidc-login
          - get-token
          - --oidc-issuer-url=https://company.okta.com/oauth2/default
          - --oidc-client-id=kubernetes-cluster-prod
          - --oidc-extra-scope=groups
          - --oidc-extra-scope=email
contexts:
  - name: prod
    context:
      cluster: prod-cluster
      user: oidc-user
      namespace: production
current-context: prod
```
When the developer runs `kubectl get pods`:
- The exec plugin (`kubelogin`) checks if a cached token exists and is still valid.
- If not, it opens a browser window to Okta's login page.
- After login + MFA, it receives the ID token.
- It passes the token to kubectl, which sends it to the API server.

### ServiceAccount Token — API Server IS the Issuer
```sh
+------------------+                    +------------------+
|    Kubelet       |                    |  kube-apiserver  |
+--------+---------+                    +--------+---------+
         |                                       |
         |  1. "Pod X just started.              |
         |     Give me a token for               |
         |     ServiceAccount 'my-app-sa'"       |
         |  POST /api/v1/serviceaccounts/        |
         |       my-app-sa/token                 |
         |-------------------------------------->|
         |                                       |
         |                            2. API server generates
         |                               a JWT and signs it
         |                               with ITS OWN private key
         |                                       |
         |  3. Here is the signed JWT            |
         |<--------------------------------------|
         |                                       |
         |  4. Kubelet mounts it into            |
         |     the Pod's filesystem               |
```         
The API server is both the issuer and the validator. It signs the token with its private key, and later when the Pod presents the token back, it verifies it with the corresponding public key.
`The Kubernetes ServiceAccount JWT is ALWAYS issued by the kube-apiserver.`

### OIDC Token — API Server is ONLY the Validator

```sh
+------------------+     +------------------+     +------------------+
|   Developer      |     |   Okta / Google  |     |  kube-apiserver  |
|   (Browser)      |     |   (IdP)          |     |                  |
+--------+---------+     +--------+---------+     +--------+---------+
         |                        |                        |
         |  1. Login to Okta      |                        |
         |     (username + MFA)   |                        |
         |----------------------->|                        |
         |                        |                        |
         |  2. Okta generates     |                        |
         |     a JWT and signs it |                        |
         |     with OKTA'S        |                        |
         |     private key        |                        |
         |                        |                        |
         |  3. Here is the JWT    |                        |
         |<-----------------------|                        |
         |                        |                        |
         |  4. kubectl sends the Okta JWT to the API server|
         |-----------------------------------------------> |
         |                        |                        |
         |                        |    5. API server       |
         |                        |       DOES NOT         |
         |                        |       recognize this   |
         |                        |       token. It did    |
         |                        |       not sign it.     |
         |                        |                        |
         |                        |    6. API server       |
         |                        |       fetches Okta's   |
         |                        |       public keys      |
         |                        |       from the OIDC    |
         |                        |       endpoint to      |
         |                        |       verify the       |
         |                        |       signature.       |
         |                        |                        |
         |                        |    7. Signature valid. |
         |                        |       Extract claims:  |
         |                        |       user: jane@...   |
         |                        |       groups: [...]    |
         |                        |                        |
         |                        |    8. Run RBAC.        |
         |                        |                        |
         |  9. 200 OK             |                        |
         |<----------------------------------------------- |
```     
The API server never talked to Okta to get the token. It never even knew Jane was logging in. It only saw the token after kubectl sent it, and it validated it by downloading Okta's public keys.    

` The Kubernetes ServiceAccount JWT is ALWAYS issued by the kube-apiserver. What AWS gives you is a DIFFERENT credential entirely.`

```sh
TOKEN 1                                    TOKEN 2
(Kubernetes ServiceAccount JWT)            (AWS Temporary Credentials)
Issued by: kube-apiserver                  Issued by: AWS STS
Signed by: API server's private key        Signed by: AWS
Used for:  Proving "I am my-app-sa"        Used for:  Calling AWS APIs (S3, DynamoDB)
Format:    JWT (eyJhbGciOi...)             Format:    AccessKeyId + SecretAccessKey
                                                      + SessionToken
Lives at:  /var/run/secrets/.../token       Lives at:  Environment vars or
                                                      ~/.aws/credentials (in memory)

           TOKEN 1 is EXCHANGED for TOKEN 2
           ================================
           The Pod gives Token 1 to AWS STS.
           AWS STS verifies Token 1.
           AWS STS hands back Token 2.
 ```
 ```json
 {
  "iss": "https://oidc.eks.us-east-1.amazonaws.com/id/ABCDEF1234567890"
}
```
The EKS-managed `kube-apiserver` signed the JWT with its own private key   

### The EKS Pod Identity Agent (Newer Method)
AWS introduced a newer system called EKS Pod Identity (replacing IRSA) 
```sh
OLD WAY (IRSA):
+--------+     JWT      +----------+     AWS Creds    +--------+
|  Pod   |------------->| AWS STS  |----------------->| AWS S3 |
+--------+              +----------+                  +--------+
  Pod does the token exchange itself via the AWS SDK.

NEW WAY (EKS Pod Identity):
+--------+              +--------------------+     AWS Creds    +--------+
|  Pod   |------------->| EKS Pod Identity   |----------------->| AWS S3 |
+--------+   localhost  | Agent (DaemonSet   |                  +--------+
                        | on every Node)     |
                        +--------------------+
  A local agent on the Node handles the exchange.
  The Pod just calls a local HTTP endpoint.
 ```
 With EKS Pod Identity:
- AWS runs a DaemonSet on every node called the `eks-pod-identity-agent`.
- Your Pod makes a request to `http://169.254.170.23/v1/credentials` (a link-local address on the node).
- The agent authenticates the Pod using its K8s ServiceAccount token (still issued by the API server).
- The agent calls AWS STS on behalf of the Pod and returns the temporary AWS credentials.
From the Pod's perspective, it just called a local endpoint and got AWS credentials. It never directly talked to STS or used a JWT explicitly. 

## TLS Certificates (The Biggest Category Most People Forget)

| Use Case | Details |
|---|---|
| Cluster CA certificates | The root CA that signs every internal TLS certificate. Stored as a Secret in kube-system. If compromised, the entire cluster must be rebuilt |
| Kubelet client/server certs | Every kubelet has a TLS cert to authenticate to the API server AND to serve its own HTTPS endpoints. Auto-rotated by the kubelet |
| etcd TLS certs | etcd uses mutual TLS (mTLS). The API server must present a client cert to etcd, and etcd presents its server cert back |
| Ingress TLS certs | Your public-facing HTTPS certificates (e.g., *.company.com). Stored as kubernetes.io/tls type Secrets |
| Service Mesh mTLS certs | If you run Istio/Linkerd, every single Pod gets an automatically issued and rotated mTLS certificate so that all pod-to-pod traffic is encrypted |

Ingress TLS Secret example:
```yaml
apiVersion: v1
kind: Secret
metadata:
  name: company-tls
  namespace: production
type: kubernetes.io/tls         # <-- Special type
data:
  tls.crt: LS0tLS1CRUdJTi...   # Base64-encoded certificate chain
  tls.key: LS0tLS1CRUdJTi...   # Base64-encoded private key
```  
```yaml
apiVersion: networking.k8s.io/v1
kind: Ingress
metadata:
  name: my-app
  namespace: production
spec:
  tls:
    - hosts:
        - app.company.com
      secretName: company-tls    # <-- References the TLS Secret
  rules:
    - host: app.company.com
      http:
        paths:
          - path: /
            pathType: Prefix
            backend:
              service:
                name: my-app
                port:
                  number: 80
```

`cert-manager` (Auto-renewal): In production, nobody manually manages TLS certs. You install `cert-manager`, which is an Operator that automatically requests certificates from Let's Encrypt (or an internal CA), stores them as K8s Secrets, and renews them before they expire.

```yaml
apiVersion: cert-manager.io/v1
kind: Certificate
metadata:
  name: company-tls
  namespace: production
spec:
  secretName: company-tls           # cert-manager creates this Secret automatically
  issuerRef:
    name: letsencrypt-prod
    kind: ClusterIssuer
  dnsNames:
    - app.company.com
    - api.company.com
  duration: 2160h                    # 90 days
  renewBefore: 720h                  # Renew 30 days before expiry
```

### Container Registry Pull Secrets
If your images are in a private registry, every Pod needs credentials to pull them:
```yaml
apiVersion: v1
kind: Secret
metadata:
  name: registry-credentials
  namespace: production
type: kubernetes.io/dockerconfigjson    # <-- Special type
data:
  .dockerconfigjson: eyJhdXRocyI6...    # Base64-encoded Docker config
```
```yaml
apiVersion: v1
kind: Pod
metadata:
  name: my-app
spec:
  imagePullSecrets:
    - name: registry-credentials    # <-- Pod uses this to authenticate to the registry
  containers:
    - name: app
      image: private-registry.company.com/my-app:v2.1
```

Pro-Tip: You can attach `imagePullSecrets` directly to a ServiceAccount so every Pod using that SA automatically gets the pull credentials without specifying it in every Pod spec:
```yaml
apiVersion: v1
kind: ServiceAccount
metadata:
  name: default
  namespace: production
imagePullSecrets:
  - name: registry-credentials
```

CSI Secret Store Driver is worth explaining because it is the most paranoid approach. With External Secrets Operator, the secret value still ends up as a K8s Secret in etcd. With the CSI driver, the secret is fetched from Vault at Pod startup and mounted directly into the container's filesystem. It never exists as a Kubernetes Secret object, so even a cluster admin running `kubectl get secrets` cannot see it.

### The Complete Secret Types Map
Kubernetes has a `type` field on every Secret that controls validation:

```sh
+--------------------------------------+--------------------------------------------+
| Secret Type                          | What It Holds                              |
+--------------------------------------+--------------------------------------------+
| Opaque                               | Arbitrary user-defined data (default)       |
| kubernetes.io/tls                    | tls.crt + tls.key (TLS certificates)        |
| kubernetes.io/dockerconfigjson       | .dockerconfigjson (registry credentials)     |
| kubernetes.io/basic-auth             | username + password                        |
| kubernetes.io/ssh-auth               | ssh-privatekey                             |
| kubernetes.io/service-account-token  | Legacy SA token (auto-generated)           |
| bootstrap.kubernetes.io/token        | Bootstrap tokens for kubeadm               |
+--------------------------------------+--------------------------------------------+
```

### Production Secrets Architecture (Putting It All Together)
Here is what a mature production cluster's secrets landscape looks like:
```sh
+------------------------------------------------------------------+
|                    EXTERNAL VAULTS                                |
|  +------------------+  +------------------+  +------------------+ |
|  | AWS Secrets Mgr  |  | HashiCorp Vault  |  | Let's Encrypt    | |
|  +--------+---------+  +--------+---------+  +--------+---------+ |
+-----------|----------------------|----------------------|---------+
            |                      |                      |
            v                      v                      v
+------------------------------------------------------------------+
|                    OPERATORS (Controllers)                        |
|  +------------------+  +------------------+  +------------------+ |
|  | External Secrets |  | CSI Secret Store |  | cert-manager     | |
|  | Operator         |  | Driver           |  |                  | |
|  +--------+---------+  +--------+---------+  +--------+---------+ |
+-----------|----------------------|----------------------|---------+
            |                      |                      |
            v                      v                      v
+------------------------------------------------------------------+
|                    KUBERNETES SECRETS (etcd)                     |
|                    Encrypted at rest (AES-256)                   |
|                                                                  |
|  +-------------+  +-------------+  +-------------+  +---------+  |
|  | DB Creds    |  | API Keys    |  | TLS Certs   |  | Registry|  |
|  | (Opaque)    |  | (Opaque)    |  | (tls type)  |  | (docker)|  |
|  +------+------+  +------+------+  +------+------+  +----+----+  |
+---------|----------------|----------------|---------------|-------+
          |                |                |               |
          v                v                v               v
+------------------------------------------------------------------+
|                    PODS                                           |
|  +------------------------------------------------------------+  |
|  | env: DB_PASSWORD     | env: STRIPE_KEY  | volumeMount:     |  |
|  |   (from secretKeyRef)|  (from secretRef)| /etc/tls (cert)  |  |
|  +------------------------------------------------------------+  |
+------------------------------------------------------------------+
```

### The Plugin Way: kubelogin (Most Common for Developers)
This is what most companies use. You install the `kubelogin` plugin once, and it handles everything automatically forever.

```yaml
users:
  - name: oidc-user
    user:
      exec:
        apiVersion: client.authentication.k8s.io/v1beta1
        command: kubectl
        args:
          - oidc-login
          - get-token
          - --oidc-issuer-url=https://company.okta.com/oauth2/default
          - --oidc-client-id=kubernetes-cluster-prod
 ```
 What happens when Jane runs `kubectl get pods`:
 ```sh
 FIRST TIME:
+----------+     +----------+     +----------+     +----------+
|  Jane    |     | kubelogin|     |   Okta   |     | API Svr  |
+----+-----+     +----+-----+     +----+-----+     +----+-----+
     |                |                |                |
     | kubectl get    |                |                |
     | pods           |                |                |
     |--------------->|                |                |
     |                | No cached      |                |
     |                | token. Open    |                |
     |                | browser.       |                |
     |  Browser pops  |                |                |
     |  up Okta login |                |                |
     |<---------------|                |                |
     |                |                |                |
     | Login + MFA    |                |                |
     |------------------------------>  |                |
     |                |                |                |
     |                | ID Token +     |                |
     |                | Refresh Token  |                |
     |                |<---------------|                |
     |                |                |                |
     |                | Cache tokens   |                |
     |                | locally at     |                |
     |                | ~/.kube/cache/ |                |
     |                |                |                |
     |                | Send ID Token  |                |
     |                |------------------------------>  |
     |                |                |                |
     | Results        |                |  200 OK        |
     |<---------------|<------------------------------  |

EVERY SUBSEQUENT TIME (for hours/days):
+----------+     +----------+                   +----------+
|  Jane    |     | kubelogin|                   | API Svr  |
+----+-----+     +----+-----+                   +----+-----+
     |                |                              |
     | kubectl get    |                              |
     | pods           |                              |
     |--------------->|                              |
     |                | Cached token                 |
     |                | still valid?                 |
     |                | YES -> use it                |
     |                | NO  -> use Refresh           |
     |                |        Token to silently     |
     |                |        get new ID Token      |
     |                |        from Okta (no browser)|
     |                |                              |
     |                | Send token                   |
     |                |----------------------------->|
     |                |                              |
     | Results        |                   200 OK     |
     |<---------------|<-----------------------------|
```     
### The CI/CD Way (Fully Automated, No Human)
For GitHub Actions, GitLab CI, or Jenkins, there is never a browser or a human. The CI system itself has its own identity.

GitHub Actions with OIDC (no static credentials at all):
```yaml
# .github/workflows/deploy.yml
jobs:
  deploy:
    permissions:
      id-token: write               # GitHub generates an OIDC JWT for this workflow
    steps:
      - name: Configure AWS
        uses: aws-actions/configure-aws-credentials@v4
        with:
          role-to-assume: arn:aws:iam::123456789012:role/GitHubDeployRole
          aws-region: us-east-1
          # GitHub's OIDC token is EXCHANGED for AWS creds (same pattern as IRSA!)

      - name: Configure kubectl
        run: aws eks update-kubeconfig --name prod-cluster

      - name: Deploy
        run: kubectl apply -f manifests/
```

### The Cloud Console Way (EKS/GKE/AKS — Zero OIDC Config Needed)
On managed Kubernetes services, the cloud CLI handles everything natively. You don't even need kubelogin.

AWS EKS:
```yaml
users:
  - name: eks-user
    user:
      exec:
        apiVersion: client.authentication.k8s.io/v1beta1
        command: aws
        args:
          - eks
          - get-token
          - --cluster-name
          - prod-cluster
          - --region
          - us-east-1
```          
 Every time `kubectl` runs, it calls `aws eks get-token`, which uses your existing AWS CLI credentials (from `aws sso login` or `~/.aws/credentials`) to generate a presigned STS token. No OIDC configuration needed on the API server at all. EKS uses a different auth mechanism called AWS IAM Authenticator.        

GCP GKE:
```yaml
users:
  - name: gke-user
    user:
      exec:
        apiVersion: client.authentication.k8s.io/v1beta1
        command: gke-gcloud-auth-plugin
```        
It uses your `gcloud auth login` session automatically.

Azure AKS:
```yaml
users:
  - name: aks-user
    user:
      exec:
        apiVersion: client.authentication.k8s.io/v1beta1
        command: kubelogin
        args:
          - get-token
          - --login
          - azurecli           # Uses your existing `az login` session
          - --server-id
          - 6dae42f8-...
```          
Fetch database passwords at startup	AWS Secrets Manager
"Why not just attach an IAM Role to the EC2 instance (the Node) and let every Pod on that node inherit it?"

This is the old, insecure way (called the Instance Metadata approach). The problem:
```sh
BAD: Node-level IAM Role
+------------------------------------------------------------------+
|  Node (EC2 Instance with IAM Role: FullS3Access + FullRDSAccess) |
|                                                                  |
|  +------------------+  +------------------+  +------------------+|
|  | Payment Pod      |  | Logging Pod      |  | Hacker's        | |
|  | (needs S3)       |  | (needs CloudWatch)|  | compromised Pod| |
|  +------------------+  +------------------+  +------------------+|
|                                                                  |
|  ALL THREE PODS get FullS3Access + FullRDSAccess                 |
|  The hacker can delete your database.                            |
+------------------------------------------------------------------+

GOOD: Pod-level IAM Role (IRSA / Pod Identity)
+------------------------------------------------------------------+
|  Node (EC2 Instance with NO IAM Role)                            |
|                                                                  |
|  +------------------+  +------------------+  +------------------+|
|  | Payment Pod      |  | Logging Pod      |  | Hacker's        | |
|  | SA: payment-sa   |  | SA: logging-sa   |  | compromised Pod | |
|  | Role: S3 only    |  | Role: CW only    |  | SA: default     | |
|  +------------------+  +------------------+  | Role: NONE      | |
|                                              +------------------+|
|  Each Pod gets ONLY what it needs. The hacker gets nothing.      |
+------------------------------------------------------------------+
```
IRSA / Pod Identity gives each Pod its own isolated AWS permissions. This is the Principle of Least Privilege applied at the Pod level, and it is the entire reason the IRSA mechanism exists.

`A ServiceAccount is an identity. A Workload is a running application.`
A Workload uses a ServiceAccount. The ServiceAccount does not know or care what Workload is using it. Multiple Workloads can share the same ServiceAccount.

```yaml
apiVersion: apps/v1
kind: Deployment            # <-- This is the WORKLOAD (the running app)
metadata:
  name: payment-service
spec:
  replicas: 3
  template:
    spec:
      serviceAccountName: payment-sa    # <-- This is the IDENTITY (the badge)
      containers:
        - name: app
          image: payment-service:v2.1
          resources:
            requests:
              cpu: "500m"
              memory: "256Mi"
```
```yaml
apiVersion: v1
kind: ServiceAccount        # <-- Just a name. No containers. No CPU.
metadata:
  name: payment-sa
  annotations:
    eks.amazonaws.com/role-arn: arn:aws:iam::123456789012:role/PaymentS3Role
```

```sh
+------------------------------------------------------------------+
|  Worker Node                                                     |
|                                                                  |
|  1. CNI ROUTES (Pod-to-Pod networking)                           |
|     - Routes to local Pods on THIS node (veth pairs)             |
|     - Routes to remote Pods on OTHER nodes (tunnels/BGP)         |
|                                                                  |
|  2. KUBE-PROXY RULES (Service load balancing)                    |
|     - iptables/IPVS rules for EVERY Service in the cluster       |
|     - These exist on EVERY node, even if the node runs zero      |
|       Pods for that Service                                      |
+------------------------------------------------------------------+
```

### CNI Routes: How Pods Are Reached
Every node needs to know how to reach every Pod in the cluster. But how this works depends on the CNI plugin.

Example: A 3-Node cluster with 6 Pods total
```sh
Node 1 (10.0.1.10)              Node 2 (10.0.1.11)              Node 3 (10.0.1.12)
Pod CIDR: 10.244.0.0/24         Pod CIDR: 10.244.1.0/24         Pod CIDR: 10.244.2.0/24

+-----------------------+        +-----------------------+        +-----------------------+
| Pod A: 10.244.0.2     |        | Pod C: 10.244.1.2     |        | Pod E: 10.244.2.2     |
| Pod B: 10.244.0.3     |        | Pod D: 10.244.1.3     |        | Pod F: 10.244.2.3     |
+-----------------------+        +-----------------------+        +-----------------------+
```
What Node 1's route table looks like:
```sh
# Routes on Node 1
ip route show

# LOCAL pods (direct, via veth pairs on this node)
10.244.0.2 dev veth1234     # Pod A — directly connected
10.244.0.3 dev veth5678     # Pod B — directly connected

# REMOTE pods (via tunnels or direct routing to other nodes)
10.244.1.0/24 via 10.0.1.11  # "Everything in 10.244.1.x? Send to Node 2"
10.244.2.0/24 via 10.0.1.12  # "Everything in 10.244.2.x? Send to Node 3"
```
So even if Node 2 has 50 Pods, Node 1 just has one route: 10.244.1.0/24 via Node 2. The routing is done at the subnet level, not the individual Pod level.


### Kube-Proxy Rules: The Real Scaling Problem
This is where it gets painful. Unlike CNI routes, `kube-proxy` creates rules for every `Service` AND every `Endpoint` in the entire cluster, on every single node
```sh
CLUSTER: 500 Services, each backed by 3 Pods = 1,500 Endpoints

EVERY NODE gets ALL of these rules, even if the node runs zero Pods:

+------------------------------------------------------------------+
|  Node 1 iptables rules:                                          |
|                                                                  |
|  KUBE-SERVICES chain:                                            |
|    500 rules  (one per Service ClusterIP)                        |
|                                                                  |
|  KUBE-SVC-* chains:                                              |
|    500 chains (one per Service, each with 3 probability rules)   |
|    = 1,500 rules                                                 |
|                                                                  |
|  KUBE-SEP-* chains:                                              |
|    1,500 chains (one per Endpoint, each with a DNAT rule)        |
|    = 1,500 rules                                                 |
|                                                                  |
|  TOTAL: ~3,500 iptables rules on this ONE node                   |
+------------------------------------------------------------------+

+------------------------------------------------------------------+
|  Node 2: EXACT SAME 3,500 rules                                  |
|  Node 3: EXACT SAME 3,500 rules                                  |
|  ...                                                             |
|  Node 500: EXACT SAME 3,500 rules                                |
+------------------------------------------------------------------+
```
Why every node? Because any Pod on any node might try to reach any Service. The iptables rules are evaluated locally in the kernel, so they must exist everywhere.

## NETWORKING
### Network Policies (Pod-level firewall) 
Network Policies — The Pod-Level Firewall
```yaml
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata:
  name: payment-isolation
  namespace: production
spec:
  podSelector:
    matchLabels:
      app: payment-service
  policyTypes: ["Ingress", "Egress"]
  ingress:
    - from:
        - podSelector:
            matchLabels:
              app: api-gateway          # ONLY the API gateway can talk to payment
      ports:
        - port: 8080
  egress:
    - to:
        - podSelector:
            matchLabels:
              app: postgres             # Payment can ONLY talk to Postgres
      ports:
        - port: 5432
    - to:                               # And DNS (required for service discovery)
        - namespaceSelector: {}
          podSelector:
            matchLabels:
              k8s-app: kube-dns
      ports:
        - port: 53
          protocol: UDP
```          

### CNI interface and Pod IP assignment
### kube-proxy iptables chains (KUBE-SERVICES, KUBE-SVC, KUBE-SEP)

## SECURITY
### ServiceAccounts and JWT tokens
### OIDC authentication for humans
### RBAC (Roles, ClusterRoles, Bindings, Subresources)
### Workload Identity / IRSA (Pod-to-cloud federation)
### Pod Security Standards (PSS)                     
### automountServiceAccountToken: false

## SECRETS & CONFIG
### ConfigMaps vs Secrets
### Secret types (Opaque, TLS, dockerconfig, etc.)
### External Secrets Operator
### Sealed Secrets
### CSI Secret Store Driver
### cert-manager (automatic TLS)
### imagePullSecrets
### etcd encryption at rest

## WORKLOADS & SCHEDULING
### Pod, Deployment, StatefulSet, DaemonSet, Job, CronJob
### Scheduler Framework (Filter, Score, Bind plugins)
### QoS Classes (Guaranteed, Burstable, BestEffort)
### NUMA / Topology Manager / CPU pinning
### CRI, CNI, CSI interfaces
### Taints & Tolerations                              
### Node Affinity & Pod Anti-Affinity  
Pod Anti-Affinity — Spread Replicas Across Failure Domains
```yaml
# "Never put two replicas of this app on the same node"
spec:
  affinity:
    podAntiAffinity:
      requiredDuringSchedulingIgnoredDuringExecution:
        - labelSelector:
            matchLabels:
              app: payment-service
          topologyKey: kubernetes.io/hostname    # One per node
```
```yaml
spec:
  affinity:
    nodeAffinity:
      requiredDuringSchedulingIgnoredDuringExecution:      # HARD requirement
        nodeSelectorTerms:
          - matchExpressions:
              - key: topology.kubernetes.io/zone
                operator: In
                values: ["us-east-1a", "us-east-1b"]
      preferredDuringSchedulingIgnoredDuringExecution:     # SOFT preference
        - weight: 80
          preference:
            matchExpressions:
              - key: node-type
                operator: In
                values: ["high-memory"]
```                                          
### Priority Classes & Preemption 

## RESILIENCE & OPERATIONS
### Probes (Startup, Liveness, Readiness)  
Probes — How K8s Knows If Your App Is Healthy
```yaml
containers:
  - name: app
    image: my-app:v2
    
    startupProbe:              # "Has the app finished booting?"
      httpGet:                 # Slow-starting apps (Java, .NET) need this
        path: /healthz         # Until this passes, K8s won't check the other probes
        port: 8080
      failureThreshold: 30     # Give it 30 * 10s = 5 minutes to boot
      periodSeconds: 10

    livenessProbe:             # "Is the app still alive, or is it deadlocked?"
      httpGet:                 # If this FAILS, K8s KILLS and RESTARTS the container
        path: /healthz
        port: 8080
      periodSeconds: 15
      failureThreshold: 3

    readinessProbe:            # "Can the app accept traffic right now?"
      httpGet:                 # If this FAILS, K8s REMOVES the pod from the Service
        path: /ready           # endpoints. Traffic stops flowing to it but it is NOT killed
        port: 8080
      periodSeconds: 5
      failureThreshold: 3
 ```                 
### Pod Disruption Budgets (PDBs)                      
### Graceful Shutdown (SIGTERM, preStop, grace period)  
### Rolling Updates (maxSurge, maxUnavailable)         
### Blue/Green and Canary strategies   
```sh
ROLLING UPDATE (default):
  v1: [####]  v2: []
  v1: [###]   v2: [#]
  v1: [##]    v2: [##]
  v1: [#]     v2: [###]
  v1: []      v2: [####]

BLUE/GREEN:
  v1 (Blue):  [####]  <-- All traffic here
  v2 (Green): [####]  <-- Deployed, tested, no traffic
  --- Switch Service selector ---
  v1 (Blue):  [####]  <-- No traffic (keep as rollback)
  v2 (Green): [####]  <-- All traffic here now

CANARY:
  v1: [####]  <-- 95% traffic
  v2: [#]     <-- 5% traffic (monitor for errors)
  If OK:
  v1: [###]   <-- 75% traffic
  v2: [##]    <-- 25% traffic
  If OK:
  v1: []      <-- 0%
  v2: [####]  <-- 100%
```                   
### HPA, VPA, Cluster Autoscaler    
```yaml
apiVersion: autoscaling/v2
kind: HorizontalPodAutoscaler
metadata:
  name: payment-hpa
spec:
  scaleTargetRef:
    apiVersion: apps/v1
    kind: Deployment
    name: payment-service
  minReplicas: 3
  maxReplicas: 100
  metrics:
    - type: Resource
      resource:
        name: cpu
        target:
          type: Utilization
          averageUtilization: 70     # Scale up when average CPU > 70%
    - type: Resource
      resource:
        name: memory
        target:
          type: Utilization
          averageUtilization: 80
```                             
### etcd backup and restore                            
### Debugging tools (kubectl debug, ephemeral containers) 

## ECOSYSTEM
### CRDs and Operators
### Helm, Kustomize
### ArgoCD / Flux (GitOps)

`kubeadm` is the official tool for bootstrapping a Kubernetes cluster from scratch. It is how you turn a set of bare Linux machines (VMs, bare metal, cloud instances) into a functioning Kubernetes cluster.

The Two-Command Cluster
On the control plane machine:
`kubeadm init --pod-network-cidr=10.244.0.0/16`
On every worker node: 
```sh
kubeadm join 192.168.1.100:6443 --token abcdef.0123456789abcdef \
  --discovery-token-ca-cert-hash sha256:1234567890abcdef...
```
```sh
+-------------------------------------------------------------------+
|  kubeadm init                                                     |
|                                                                   |
|  Step 1: PREFLIGHT CHECKS                                         |
|    - Is this machine Linux? ✓                                     |
|    - Is swap disabled? ✓  (K8s hates swap)                        |
|    - Is a container runtime installed? ✓                          |
|    - Are required ports free? (6443, 2379, 10250...) ✓            |
|                                                                   |
|  Step 2: GENERATE CERTIFICATES                                    |
|    - /etc/kubernetes/pki/ca.crt + ca.key         (Cluster CA)     |
|    - /etc/kubernetes/pki/apiserver.crt + .key    (API server TLS) |
|    - /etc/kubernetes/pki/etcd/ca.crt + .key      (etcd CA)        |
|    - /etc/kubernetes/pki/front-proxy-ca.crt      (Aggregation)    |
|    - /etc/kubernetes/pki/sa.key + sa.pub         (SA token signing)|
|    Total: ~10 certificate files                                   |
|                                                                   |
|  Step 3: GENERATE KUBECONFIGS                                     |
|    - /etc/kubernetes/admin.conf          (cluster-admin access)   |
|    - /etc/kubernetes/kubelet.conf        (this node's kubelet)    |
|    - /etc/kubernetes/controller-manager.conf                      |
|    - /etc/kubernetes/scheduler.conf                               |
|                                                                   |
|  Step 4: START ETCD                                               |
|    - Runs as a static Pod (YAML in /etc/kubernetes/manifests/)    |
|    - The kubelet watches this directory and starts whatever is     |
|      there, even before the API server exists                     |
|                                                                   |
|  Step 5: START CONTROL PLANE COMPONENTS                           |
|    - kube-apiserver      (static Pod)                             |
|    - kube-controller-manager (static Pod)                         |
|    - kube-scheduler      (static Pod)                             |
|    All are dropped as YAML files into /etc/kubernetes/manifests/  |
|                                                                   |
|  Step 6: UPLOAD CONFIG                                            |
|    - Stores the cluster config as a ConfigMap in kube-system      |
|      so that `kubeadm join` on worker nodes can discover it       |
|                                                                   |
|  Step 7: GENERATE BOOTSTRAP TOKEN                                 |
|    - Creates a temporary token (valid 24 hours)                   |
|    - Worker nodes use this to authenticate their first request    |
|                                                                   |
|  Step 8: INSTALL ADDONS                                           |
|    - CoreDNS (cluster DNS)                                        |
|    - kube-proxy (DaemonSet)                                       |
+-------------------------------------------------------------------+

OUTPUT:
  "Your Kubernetes control-plane has initialized successfully!"
  "To start using your cluster, run:"
  "  mkdir -p $HOME/.kube"
  "  sudo cp /etc/kubernetes/admin.conf $HOME/.kube/config"
  ""
  "Then join worker nodes with:"
  "  kubeadm join 192.168.1.100:6443 --token abcdef.012345 \"
  "    --discovery-token-ca-cert-hash sha256:1234..."
  ```

### The Static Pods Trick
One of the most clever design decisions in kubeadm: How do you start the API server if the API server doesn't exist yet?

The answer is Static Pods. The `kubelet` has a special feature: it watches a local directory (/`etc/kubernetes/manifests/`) for YAML files. If it finds a Pod manifest there, it starts it directly — no API server needed.
```sh
/etc/kubernetes/manifests/
├── etcd.yaml                    # Static Pod
├── kube-apiserver.yaml          # Static Pod
├── kube-controller-manager.yaml # Static Pod
└── kube-scheduler.yaml          # Static Pod

The kubelet reads these files directly from disk,
starts the containers, and keeps them alive.
No API server is required. This is how the
control plane bootstraps itself.
```

- kubelet starts (it's a systemd service)
- kubelet sees YAML files in `/etc/kubernetes/manifests/`
- kubelet starts etcd as a container
- kubelet starts the API server as a container
- The API server connects to etcd
- Now the cluster exists, and everything else can be scheduled normally

### 1. Create Namespace and Secret

```bash
# Create dedicated namespace
kubectl create namespace my-namespace
# Create Secret with AWS credentials
kubectl create secret generic aws-creds \
  --namespace my-namespace \
  --from-literal=aws_access_key_id=YOUR_ACCESS_KEY_ID \
  --from-literal=aws_secret_access_key=YOUR_SECRET_ACCESS_KEY

# Create a ServiceAccount that will be used by the Pod
kubectl create serviceaccount aws-sa \
  --namespace my-namespace 

# Bind the ServiceAccount to a Role with permissions to read the Secret
kubectl create role aws-secret-reader \
  --namespace my-namespace \
  --verb=get \
  --resource=secrets  

kubectl create rolebinding aws-secret-reader-binding \
  --namespace my-namespace \
  --role=aws-secret-reader \
  --serviceaccount=my-namespace:aws-sa
  
# Create Kubernetes secret from .env file
kubectl create secret generic app-config \
  --namespace my-namespace \
  --from-env-file=./config.env

# Create Kubernetes secret from .env file
kubectl -n eudiplo create secret generic eudiplo-env --from-env-file=.env
```

### 2. Deploy All Resources
Using Kustomize (recommended):

```bash
kubectl apply -k .
```
Or apply individual manifests:

```bash
kubectl apply -f namespace.yaml
kubectl apply -f postgres-statefulset.yaml
kubectl apply -f postgres-service.yaml
kubectl apply -f my-namespace-deployment.yaml
kubectl apply -f my-namespace-service.yaml
kubectl apply -f my-namespace-client-deployment.yaml
kubectl apply -f my-namespace-client-service.yaml
kubectl apply -f ingress.yaml
```
### Database Connection

Connect to PostgreSQL:
```bash
kubectl exec -n my-namespace -it $(kubectl get pods -n my-namespace -l app=postgres -o jsonpath="{.items[0].metadata.name}") -- psql -U postgres
```

Check Persistent Volume Claims:

```bash
kubectl -n my-namespace get pvc
```

Describe PVC for issues:
```bash
kubectl -n my-namespace describe pvc postgres-pvc
``` 

**Use external secret management** (HashiCorp Vault, AWS Secrets Manager, Azure Key Vault)

### Secret Management Strategies
Applications require several secrets (database credentials, JWT secret, encryption key, etc.). The recommended approach is to use **infrastructure-level secret injection** rather than storing secrets in `.env` files or passing them directly as environment variables.

### Option 1: External Secrets Operator (Recommended)

The [External Secrets Operator](https://external-secrets.io/) syncs secrets from external providers into Kubernetes Secrets.

```yaml
# Install External Secrets Operator
helm repo add external-secrets https://charts.external-secrets.io
helm install external-secrets external-secrets/external-secrets \
-n external-secrets --create-namespace
```

Example SecretStore for AWS Secrets Manager:

```yaml
apiVersion: external-secrets.io/v1beta1
kind: SecretStore
metadata:
    name: aws-secrets-manager
    namespace: my-namespace
spec:
    provider:
        aws:
            service: SecretsManager
            region: eu-central-1
            auth:
                jwt:
                    serviceAccountRef:
                        name: my-namespace-sa
---
apiVersion: external-secrets.io/v1beta1
kind: ExternalSecret
metadata:
    name: my-namespace-secrets
    namespace: my-namespace
spec:
    refreshInterval: 1h
    secretStoreRef:
        name: aws-secrets-manager
        kind: SecretStore
    target:
        name: my-namespace-env
        creationPolicy: Owner
    data:
        - secretKey: DB_PASSWORD
          remoteRef:
              key: my-namespace/production
              property: db_password
        - secretKey: MASTER_SECRET
          remoteRef:
              key: my-namespace/production
              property: master_secret
        - secretKey: AUTH_CLIENT_SECRET
          remoteRef:
              key: my-namespace/production
              property: auth_client_secret
```
 CoreDNS — How Service Discovery Actually Works
When your app calls `http://payment-service:8080`, how does Linux inside the container resolve payment-service to a ClusterIP?

The kubelet configures every Pod's resolv.conf:
```sh
# Inside any Pod:
cat /etc/resolv.conf

nameserver 10.96.0.10           # <-- CoreDNS ClusterIP (a Service itself!)
search production.svc.cluster.local svc.cluster.local cluster.local
ndots: 5
```
The DNS resolution chain:
```sh
App calls: "payment-service"
Step 1: ndots:5 means if the name has fewer than 5 dots, the system appends the search domains and tries each:
  Try: payment-service.production.svc.cluster.local  --> FOUND! Returns 10.96.0.100
  Try: payment-service.svc.cluster.local             (would try if first failed)
  Try: payment-service.cluster.local                 (would try if second failed)
  Try: payment-service                               (absolute, last resort)

Step 2: CoreDNS receives the query, looks up the Service
        "payment-service" in namespace "production",
        and returns the ClusterIP.
```        


Considered advanced Kubernetes topics for further exploration
Here are the remaining deep topics that round out true expert-level knowledge.

1. CoreDNS — How Service Discovery Actually Works
When your app calls http://payment-service:8080, how does Linux inside the container resolve payment-service to a ClusterIP?

The kubelet configures every Pod's resolv.conf:


# Inside any Pod:cat /etc/resolv.confnameserver 10.96.0.10           # <-- CoreDNS ClusterIP (a Service itself!)search production.svc.cluster.local svc.cluster.local cluster.localndots: 5
The DNS resolution chain:


App calls: "payment-service"Step 1: ndots:5 means if the name has fewer than 5 dots,         the system appends the search domains and tries each:  Try: payment-service.production.svc.cluster.local  --> FOUND! Returns 10.96.0.100  Try: payment-service.svc.cluster.local             (would try if first failed)  Try: payment-service.cluster.local                 (would try if second failed)  Try: payment-service                               (absolute, last resort)Step 2: CoreDNS receives the query, looks up the Service        "payment-service" in namespace "production",        and returns the ClusterIP.
DNS record types that CoreDNS creates automatically:

| Record | Example | Resolves To |
|---|---|---|
| A record (Service) | payment-service.production.svc.cluster.local | ClusterIP (10.96.0.100) |
| A record (Pod) | 10-244-1-10.production.pod.cluster.local | Pod IP (10.244.1.10) |
| SRV record | _http._tcp.payment-service.production.svc.cluster.local | Port + host for each endpoint |
| A record (Headless Service) | payment-service.production.svc.cluster.local | Returns ALL Pod IPs directly (no ClusterIP) |

Headless Services (`clusterIP: None`) are critical for StatefulSets:
```yaml
apiVersion: v1
kind: Service
metadata:
  name: postgres
  namespace: production
spec:
  clusterIP: None            # <-- Headless! No virtual IP.
  selector:
    app: postgres
  ports:
    - port: 5432
```
Instead of returning one fake ClusterIP, CoreDNS returns the actual Pod IPs. Each StatefulSet pod also gets a unique DNS name:
```sh
postgres-0.postgres.production.svc.cluster.local  -->  10.244.1.10
postgres-1.postgres.production.svc.cluster.local  -->  10.244.2.11
postgres-2.postgres.production.svc.cluster.local  -->  10.244.3.12
```
This is how database replicas find each other.    

### Init Containers & Sidecars
Init Containers run BEFORE your main container starts. They run sequentially, one at a time, and must all succeed before the main container starts.
```yaml
apiVersion: v1
kind: Pod
metadata:
  name: my-app
spec:
  initContainers:
    # 1. Wait for the database to be ready
    - name: wait-for-db
      image: busybox
      command: ['sh', '-c', 'until nc -z postgres 5432; do sleep 2; done']

    # 2. Run database migrations
    - name: run-migrations
      image: my-app:v2
      command: ['./migrate', '--up']

  containers:
    # 3. Only THEN start the actual app
    - name: app
      image: my-app:v2
 ```
 Sidecar Containers (native in K8s 1.28+) run alongside your main container for the entire Pod lifecycle. Common sidecars:
 ```yaml
 spec:
  initContainers:
    # Native sidecar: restartPolicy: Always makes it a TRUE sidecar
    - name: log-shipper
      image: fluent-bit:latest
      restartPolicy: Always          # <-- This is what makes it a sidecar, not an init container
      volumeMounts:
        - name: logs
          mountPath: /var/log/app

  containers:
    - name: app
      image: my-app:v2
      volumeMounts:
        - name: logs
          mountPath: /var/log/app

  volumes:
    - name: logs
      emptyDir: {}
 ```
 ```yaml
 # vault agent sidecar for dynamic secrets
 initContainers:
    - name: vault-agent
      image: vault:latest
      command: ["vault", "agent", "-config=/vault/config/agent.hcl"]
      volumeMounts:
        - name: vault-config
          mountPath: /vault/config
        - name: vault-secrets
          mountPath: /vault/secrets
  containers:
    - name: app
      image: my-app:v2
      volumeMounts:
        - name: vault-secrets
          mountPath: /app/secrets
  volumes:
    - name: vault-config
      configMap:
        name: vault-agent-config
    - name: vault-secrets
      emptyDir: {}
 ```

 ### Stateful Sets & Persistent Volumes
 ```sh
 Deployment Pods:              StatefulSet Pods:
  my-app-7b9f4d-x2k9m          postgres-0
  my-app-7b9f4d-r8m2n          postgres-1
  my-app-7b9f4d-p3k7j          postgres-2
  (random names, interchangeable)  (ordered, sticky, unique)
  ```

| Property | Deployment | StatefulSet |
|---|---|---|
| Pod names | Random hash | Ordered index (-0, -1, -2) |
| Startup order | All at once | Sequential (-0 first, then -1, then -2) |
| Shutdown order | All at once | Reverse sequential (-2 first, then -1, then -0) |
| Storage | Shared or no PVC | Each Pod gets its OWN dedicated PVC that survives restarts |
| Network identity | Random, changes on restart | Stable DNS name via Headless Service |

```yaml
apiVersion: apps/v1
kind: StatefulSet
metadata:
  name: postgres
spec:
  serviceName: postgres          # Must point to a Headless Service
  replicas: 3
  selector:
    matchLabels:
      app: postgres
  template:
    metadata:
      labels:
        app: postgres
    spec:
      containers:
        - name: postgres
          image: postgres:15
          ports:
            - containerPort: 5432
          volumeMounts:
            - name: data
              mountPath: /var/lib/postgresql/data
  volumeClaimTemplates:           # <-- Each Pod gets its OWN PVC
    - metadata:
        name: data
      spec:
        accessModes: ["ReadWriteOnce"]
        resources:
          requests:
            storage: 100Gi
```
```sh
Pod: postgres-0  -->  PVC: data-postgres-0  -->  PV: 100GB EBS volume (us-east-1a)
Pod: postgres-1  -->  PVC: data-postgres-1  -->  PV: 100GB EBS volume (us-east-1b)
Pod: postgres-2  -->  PVC: data-postgres-2  -->  PV: 100GB EBS volume (us-east-1c)
```
If `postgres-1` crashes and restarts, it reattaches to `data-postgres-1`. The data survives.

### Kubernetes Audit Logging
For compliance (SOC2, HIPAA, PCI-DSS), you need a record of every single API call made to the cluster
```yaml
# /etc/kubernetes/audit-policy.yaml
apiVersion: audit.k8s.io/v1
kind: Policy
rules:
  # Log all Secret access at the highest level (who read what password?)
  - level: RequestResponse
    resources:
      - group: ""
        resources: ["secrets"]

  # Log all RBAC changes
  - level: RequestResponse
    resources:
      - group: "rbac.authorization.k8s.io"
        resources: ["roles", "rolebindings", "clusterroles", "clusterrolebindings"]

  # Log Pod exec (who shelled into what container?)
  - level: Request
    resources:
      - group: ""
        resources: ["pods/exec", "pods/attach"]

  # Log everything else at metadata level (who did what, but not the body)
  - level: Metadata
    omitStages: ["RequestReceived"]
```    
Audit levels:
```sh
None            -->  Don't log this event
Metadata        -->  Log who, what, when (but not the request/response body)
Request         -->  Log metadata + request body
RequestResponse -->  Log metadata + request body + response body (most verbose)
```
A sample audit log entry:
```json
{
  "kind": "Event",
  "apiVersion": "audit.k8s.io/v1",
  "metadata": {
    "creationTimestamp": "2024-06-01T12:00:00Z"
  },
  "level": "RequestResponse",
  "timestamp": "2024-06-01T12:00:00Z",
  "auditID": "12345678-90ab-cdef-1234-567890abcdef",
  "stage": "ResponseComplete",
  "requestURI": "/api/v1/namespaces/production/secrets/my-secret",
  "verb": "get",
  "user": {
    "username": "alice",
    "uid": "abcdef123456",
    "groups": ["developers", "system:authenticated"]
  },
  "sourceIPs": ["192.168.1.1"],
  "userAgent": "kubectl/v1.28.0",
  "responseStatus": {
    "metadata": {},
    "code": 200
  },
  "requestObject": {  
    "kind": "Secret",
    "apiVersion": "v1",
    "metadata": {
      "name": "my-secret",
      "namespace": "production"
    }
  },
  "responseObject": {
    "kind": "Secret",
    "apiVersion": "v1",
    "metadata": {
      "name": "my-secret",  
      "namespace": "production"
    },
    "data": {
      "password": "cGFzc3dvcmQxMjM="  # Base64 encoded "password123"
    }
  }
}
```
```json
{
  "kind": "Event",
  "apiVersion": "audit.k8s.io/v1",
  "level": "RequestResponse",
  "verb": "get",
  "user": {
    "username": "oidc:jane@company.com",
    "groups": ["oidc:platform-team"]
  },
  "objectRef": {
    "resource": "secrets",
    "namespace": "production",
    "name": "db-credentials"
  },
  "sourceIPs": ["10.0.1.50"],
  "requestReceivedTimestamp": "2026-04-10T14:23:01Z",
  "responseStatus": {
    "code": 200
  }
}
```

## Multi-Cluster Patterns
At true enterprise scale, you don't have one cluster. You have many.
```sh
+------------------+     +------------------+     +------------------+
|  prod-us-east    |     |  prod-eu-west    |     |  staging         |
|  (EKS cluster)   |     |  (EKS cluster)   |     |  (EKS cluster)   |
+------------------+     +------------------+     +------------------+
         |                        |                        |
         +------------------------+------------------------+
                                  |
                    +-------------+-------------+
                    | Management Layer          |
                    | (ArgoCD / Rancher / Fleet)|
                    +---------------------------+
```                    

### Certificate Rotation (The Silent Cluster Killer)
kubeadm certificates expire after 1 year by default. If you forget to renew them, one morning the API server stops accepting connections and the entire cluster is dead.
```sh
# Check when certs expire:
kubeadm certs check-expiration

# Output:
# CERTIFICATE                EXPIRES                  RESIDUAL TIME
# admin.conf                 Apr 10, 2027 00:00 UTC   364d
# apiserver                  Apr 10, 2027 00:00 UTC   364d
# etcd-server                Apr 10, 2027 00:00 UTC   364d
# ...

# Renew all certs:
kubeadm certs renew all

# Then restart control plane static pods:
# (kubeadm doesn't do this automatically)
kill $(crictl ps | grep kube-apiserver | awk '{print $1}')
```

### Azure Kubernetes Service (AKS)
AKS is Microsoft's managed Kubernetes offering. The critical thing to understand: Microsoft manages the control plane for free. You only pay for the worker nodes (VMs).

```sh
+------------------------------------------------------------------+
|  MICROSOFT MANAGES (You never see or touch these)                 |
|                                                                   |
|  +------------------+  +------------------+  +------------------+ |
|  | kube-apiserver   |  | kube-scheduler   |  | controller-mgr   | |
|  | (3 replicas, HA) |  |                  |  |                  | |
|  +------------------+  +------------------+  +------------------+ |
|  +------------------+                                             |
|  | etcd             |  - Automatic upgrades                       |
|  | (managed, backed |  - Automatic certificate rotation            |
|  |  up by Azure)    |  - SLA: 99.95% (paid) or 99.5% (free)       |
|  +------------------+                                             |
+------------------------------------------------------------------+
                                |
                                | (API server endpoint:
                                |  https://mycluster-dns-abc123.hcp.eastus.azmk8s.io:443)
                                |
                                v
+------------------------------------------------------------------+
|  YOU MANAGE                                                      |
|                                                                  |
|  +-----------------------------+ +-----------------------------+ |
|  | Node Pool 1 ("system")     | | Node Pool 2 ("gpu")         |  |
|  | VM Size: Standard_D4s_v5   | | VM Size: Standard_NC6s_v3   |  |
|  | Nodes: 3                   | | Nodes: 2                    |  |
|  | OS: Ubuntu / Azure Linux   | | OS: Ubuntu                  |  |
|  |                            | |                             |  |
|  | +------+ +------+ +------+ | | +------+ +------+           |  |
|  | |Node 1 | |Node 2 | |Node 3 | | Node 1| |Node 2 |          |  |
|  | |kubelet| |kubelet| |kubelet| |kubelet| |kubelet|          |  |
|  | +------+ +------+ +------+  | | +------+ +------+          |  |
|  +-----------------------------+ +-----------------------------+ |
|                                                                  |
|  Your Pods, Services, Ingresses, RBAC, Secrets, etc.             |
+------------------------------------------------------------------+
```

### Creating an AKS Cluster
Using Azure CLI:
```bash
# Create a resource group
az group create --name my-aks-group --location eastus
# Create an AKS cluster with 3 nodes in the "system" node pool
az aks create \
  --resource-group my-aks-group \
  --name my-aks-cluster \
  --node-count 3 \
  --node-vm-size Standard_D4s_v5 \
  --generate-ssh-keys
# Get kubeconfig to connect with kubectl
az aks get-credentials --resource-group my-aks-group --name my-aks-cluster


# Verify
kubectl get nodes
```

Using Terraform:
```hcl
resource "azurerm_kubernetes_cluster" "prod" {
  name                = "prod-cluster"
  location            = "eastus"
  resource_group_name = azurerm_resource_group.main.name
  dns_prefix          = "prod"
  kubernetes_version  = "1.30"
  sku_tier            = "Standard"
  oidc_issuer_enabled = true
  workload_identity_enabled = true

  default_node_pool {
    name                = "system"
    node_count          = 3
    vm_size             = "Standard_D4s_v5"
    zones               = [1, 2, 3]
    os_disk_size_gb     = 128
    max_pods            = 110
    enable_auto_scaling = true
    min_count           = 3
    max_count           = 10
  }

  identity {
    type = "SystemAssigned"
  }

  network_profile {
    network_plugin    = "azure"
    network_policy    = "calico"
    load_balancer_sku = "standard"
    service_cidr      = "10.0.0.0/16"
    dns_service_ip    = "10.0.0.10"
  }
}
```
### Azure Kubernetes Service (AKS) Networking

```sh
KUBENET (basic, default):
+------------------------------------------------------------------+
|  Azure VNet: 10.0.0.0/16                                        |
|                                                                  |
|  Node 1: 10.0.1.4 (real Azure IP)                               |
|    Pod A: 10.244.0.2  (Pod network, NOT on the VNet)             |
|    Pod B: 10.244.0.3  (Pod network, NOT on the VNet)             |
|                                                                  |
|  Node 2: 10.0.1.5 (real Azure IP)                               |
|    Pod C: 10.244.1.2  (Pod network, NOT on the VNet)             |
|                                                                  |
|  Problem: Pods have IPs from a SEPARATE overlay network.         |
|  Azure services (VMs, databases) cannot reach Pod IPs directly.  |
|  Requires NAT (SNAT) for outbound traffic.                       |
|  Max 400 nodes per cluster.                                      |
+------------------------------------------------------------------+

AZURE CNI (advanced, recommended for production):
+------------------------------------------------------------------+
|  Azure VNet: 10.0.0.0/16                                        |
|  Subnet: 10.0.1.0/24                                            |
|                                                                  |
|  Node 1: 10.0.1.4 (real Azure IP)                               |
|    Pod A: 10.0.1.10 (REAL Azure IP from the SAME subnet!)       |
|    Pod B: 10.0.1.11 (REAL Azure IP from the SAME subnet!)       |
|                                                                  |
|  Node 2: 10.0.1.5 (real Azure IP)                               |
|    Pod C: 10.0.1.20 (REAL Azure IP from the SAME subnet!)       |
|                                                                  |
|  Benefit: Pods are first-class citizens on the Azure network.    |
|  Azure VMs, Private Endpoints, and other services can reach      |
|  Pod IPs directly. No NAT needed. No overlay network.            |
|  BUT: You burn through IP addresses FAST.                        |
|  30 pods/node * 100 nodes = 3,000 IPs from your subnet.         |
+------------------------------------------------------------------+

AZURE CNI OVERLAY (newer, best of both worlds):
+------------------------------------------------------------------+
|  Pods get overlay IPs (10.244.x.x) like kubenet,                |
|  BUT routing is handled by Azure CNI (faster, no UDR needed).   |
|  Pods can reach Azure services via the node's real IP (SNAT).   |
|  Supports up to 1,000 nodes and 250 pods/node.                  |
|  Uses far fewer VNet IPs.                                        |
+------------------------------------------------------------------+
```
###  AKS Identity: Managed Identity vs Service Principal
In the old days, AKS clusters used an Azure AD Service Principal (a client ID + client secret). The secret expired every 1-2 years, causing cluster outages if not rotated.

Modern AKS uses Managed Identities — Azure handles the credentials entirely. No secrets to manage or rotate.
```sh
+------------------------------------------------------------------+
|  AKS Cluster Identities                                          |
|                                                                  |
|  1. CLUSTER IDENTITY (control plane)                             |
|     - Used by: AKS to manage Azure resources                     |
|       (load balancers, disks, public IPs, VNets)                 |
|     - Type: System-Assigned Managed Identity                     |
|     - You never see a password or certificate                     |
|                                                                  |
|  2. KUBELET IDENTITY (node-level)                                |
|     - Used by: kubelet on each node                              |
|       (pull images from ACR, attach disks)                       |
|     - Type: User-Assigned Managed Identity                       |
|     - Assigned to the VMSS (node pool)                           |
|                                                                  |
|  3. WORKLOAD IDENTITY (pod-level)                                |
|     - Used by: individual Pods to access Azure resources         |
|     - Type: User-Assigned Managed Identity + Federated Credential|
|     - The IRSA equivalent for Azure                              |
+------------------------------------------------------------------+
```

### AKS Workload Identity (The Azure IRSA Equivalent)
This is the exact same OIDC federation pattern as AWS IRSA, but using Azure AD instead of AWS STS.
```sh
+------------------+          +------------------+          +------------------+
|   Pod in AKS     |          |   Azure AD       |          |  Azure Key Vault |
|   (has K8s JWT)  |          |  (Token Exchange)|          |  (The resource)  |
+--------+---------+          +--------+---------+          +--------+---------+
         |                             |                             |
         |  1. Present K8s JWT         |                             |
         |  to Azure AD                |                             |
         |---------------------------->|                             |
         |                             |                             |
         |  2. Azure AD verifies        |                             |
         |     JWT against AKS         |                             |
         |     OIDC issuer endpoint    |                             |
         |                             |                             |
         |  3. Azure AD checks         |                             |
         |     Federated Credential:   |                             |
         |     "Does this Managed      |                             |
         |      Identity trust this    |                             |
         |      K8s ServiceAccount?"   |                             |
         |                             |                             |
         |  4. Azure AD Token          |                             |
         |     returned                |                             |
         |<----------------------------|                             |
         |                                                           |
         |  5. Access Key Vault with Azure AD token                  |
         |---------------------------------------------------------->|
```         
Setup:
```sh
# 1. Create a User-Assigned Managed Identity for the workload
az identity create \
  --name my-workload-identity \
  --resource-group my-aks-group \
  --location eastus

# 2. Assign the Managed Identity to the AKS cluster
az aks update \
  --name my-aks-cluster \
  --resource-group my-aks-group \
  --assign-identity my-workload-identity

# 3. Create a Federated Credential in Azure AD
az identity federated-credential create \
  --name my-federated-credential \
  --identity-name my-workload-identity \
  --resource-group my-aks-group \
  --issuer https://mycluster-dns-abc123.hcp.eastus.azmk8s.io:443 \
  --subject system:serviceaccount:production:my-service-account

# 4. Grant the Managed Identity access to Azure Key Vault
az role assignment create \
  --assignee <client-id-of-managed-identity> \
  --role "Key Vault Secrets User" \
  --scope /subscriptions/<subscription-id>/resourceGroups/my-aks-group/providers/Microsoft.KeyVault/vaults/my-key-vault
```
```sh
# 1. Create a Managed Identity
az identity create \
  --name my-app-identity \
  --resource-group myapp-rg

# 2. Create a Federated Credential (the trust link)
az identity federated-credential create \
  --name my-app-fedcred \
  --identity-name my-app-identity \
  --resource-group myapp-rg \
  --issuer "$(az aks show -n prod-cluster -g myapp-rg --query oidcIssuerProfile.issuerUrl -o tsv)" \
  --subject "system:serviceaccount:production:my-app-sa" \
  --audience "api://AzureADTokenExchange"

# 3. Grant the Managed Identity access to Key Vault
az keyvault set-policy \
  --name my-keyvault \
  --object-id "$(az identity show -n my-app-identity -g myapp-rg --query principalId -o tsv)" \
  --secret-permissions get list
```
```sh
# 4. Create the K8s ServiceAccount (annotated with the Managed Identity)
apiVersion: v1
kind: ServiceAccount
metadata:
  name: my-app-sa
  namespace: production
  annotations:
    azure.workload.identity/client-id: "12345678-abcd-efgh-ijkl-123456789012"
  labels:
    azure.workload.identity/use: "true"
---
# 5. Deploy the Pod
apiVersion: apps/v1
kind: Deployment
metadata:
  name: my-app
  namespace: production
spec:
  template:
    metadata:
      labels:
        azure.workload.identity/use: "true"    # Webhook injects env vars
    spec:
      serviceAccountName: my-app-sa
      containers:
        - name: app
          image: myacr.azurecr.io/my-app:v2
          # Azure SDK automatically picks up the injected
          # AZURE_CLIENT_ID, AZURE_TENANT_ID, and
          # AZURE_FEDERATED_TOKEN_FILE environment variables
```

### AKS Networking: Ingress Options
```sh
+------------------------------------------------------------------+
|  OPTION 1: Azure Load Balancer (Layer 4 — default)               |
|                                                                  |
|  Internet --> Azure LB (public IP) --> NodePort --> Pod          |
|  - Created automatically for every Service type: LoadBalancer    |
|  - TCP/UDP only, no HTTP routing                                 |
+------------------------------------------------------------------+

+------------------------------------------------------------------+
|  OPTION 2: NGINX Ingress Controller (Layer 7)                    |
|                                                                  |
|  Internet --> Azure LB --> NGINX Pod --> Pod                     |
|               (auto)      (you install)  (your app)              |
|  - HTTP/HTTPS path-based routing                                 |
|  - TLS termination                                               |
|  - Community standard                                            |
+------------------------------------------------------------------+

+------------------------------------------------------------------+
|  OPTION 3: Application Gateway Ingress Controller (AGIC)         |
|                                                                  |
|  Internet --> Azure App Gateway --> Pod (directly!)              |
|  - Azure-native L7 load balancer                                 |
|  - WAF (Web Application Firewall) built-in                       |
|  - Pods get traffic directly (no kube-proxy/iptables hop)         |
|  - More expensive                                                |
+------------------------------------------------------------------+

+------------------------------------------------------------------+
|  OPTION 4: Azure Gateway API / Istio Service Mesh                |
|  - Newest options, use Gateway API CRDs                          |
+------------------------------------------------------------------+
```

### Node Pools (Multi-Pool Strategy)
```yaml
# Production node pool strategy:

System Pool:        # Runs cluster addons (CoreDNS, kube-proxy, metrics-server)
  VM: Standard_D2s_v5 (2 vCPU, 8GB)
  Nodes: 3
  Taint: CriticalAddonsOnly=true:NoSchedule
  Mode: System

General Pool:       # Runs stateless microservices
  VM: Standard_D4s_v5 (4 vCPU, 16GB)
  Nodes: 3-20 (autoscaling)
  Mode: User

Memory Pool:        # Runs databases, caches (Redis, Elasticsearch)
  VM: Standard_E8s_v5 (8 vCPU, 64GB)
  Nodes: 2-5 (autoscaling)
  Taint: workload=memory:NoSchedule
  Mode: User

GPU Pool:           # Runs ML training/inference
  VM: Standard_NC6s_v3 (6 vCPU, 112GB, 1x V100 GPU)
  Nodes: 0-4 (autoscaling, scale to zero!)
  Taint: workload=gpu:NoSchedule
  Mode: User

Spot Pool:          # Runs batch jobs, non-critical workloads (up to 90% cheaper)
  VM: Standard_D4s_v5
  Nodes: 0-10 (autoscaling)
  Priority: Spot (Azure can evict these anytime!)
  Taint: kubernetes.azure.com/scalesetpriority=spot:NoSchedule
  Mode: User
```

### IRSA Deep Dive: The Two Tokens Inside Your Pod
When IRSA is configured, a Pod actually gets two separate JWTs:
```sh
+------------------------------------------------------------------+
|  Pod: my-app                                                     |
|                                                                  |
|  TOKEN 1 (Default SA Token):                                     |
|  Path: /var/run/secrets/kubernetes.io/serviceaccount/token       |
|  Audience: "https://kubernetes.default.svc"                      |
|  Purpose: Talk to the Kubernetes API server                      |
|  {                                                               |
|    "aud": ["https://kubernetes.default.svc"],  <-- For K8s only  |
|    "sub": "system:serviceaccount:prod:my-app-sa"                 |
|  }                                                               |
|                                                                  |
|  TOKEN 2 (IRSA Projected Token):                                 |
|  Path: /var/run/secrets/eks.amazonaws.com/serviceaccount/token   |
|  Audience: "sts.amazonaws.com"                                   |
|  Purpose: Exchange with AWS STS for cloud credentials            |
|  {                                                               |
|    "aud": ["sts.amazonaws.com"],               <-- For AWS only  |
|    "sub": "system:serviceaccount:prod:my-app-sa"                 |
|  }                                                               |
+------------------------------------------------------------------+
```
Both tokens are signed by the same API server, for the same ServiceAccount. But they have different audiences. AWS STS will reject Token 1 because its `aud` doesn't match `sts.amazonaws.com`. And the K8s API server would reject Token 2 if you tried to use it for `kubectl` operations.

#### How Token 2 gets into the Pod:

The EKS mutating admission webhook modifies your Pod spec before it's saved. You write this:
```yaml
spec:
  serviceAccountName: my-app-sa
  containers:
    - name: app
      image: my-app
```
But what actually gets saved in etcd (after the webhook mutates it) is:
```yaml
spec:
  serviceAccountName: my-app-sa
  containers:
    - name: app
      image: my-app
      # --- INJECTED BY EKS WEBHOOK ---
      env:
        - name: AWS_ROLE_ARN
          value: "arn:aws:iam::123456789012:role/MyAppRole"
        - name: AWS_WEB_IDENTITY_TOKEN_FILE
          value: "/var/run/secrets/eks.amazonaws.com/serviceaccount/token"
      volumeMounts:
        - name: aws-iam-token
          mountPath: /var/run/secrets/eks.amazonaws.com/serviceaccount
          readOnly: true
  # --- INJECTED BY EKS WEBHOOK ---
  volumes:
    - name: aws-iam-token
      projected:
        sources:
          - serviceAccountToken:
              audience: "sts.amazonaws.com"      # <-- Custom audience!
              expirationSeconds: 86400
              path: token
```
The `Kubelet` sees the projected volume with audience: `"sts.amazonaws.com"` and calls the API server's TokenRequest API: "Give me a token for `my-app-sa`, but set the audience to `sts.amazonaws.com`." The API server generates and signs a second, separate JWT.              

The AWS SDK inside the container automatically detects the second token and uses it to get temporary AWS credentials.
The AWS SDK (boto3 for Python, aws-sdk for Node/Go/Java) has the exchange logic hardcoded into it.

Here is the exact chain from your application's perspective:
```sh
WHO                          DOES WHAT
---                          ---------

You (DevOps):                1. Annotate the ServiceAccount with the IAM Role ARN
                             2. Label the Pod with azure.workload.identity/use: "true"
                                (or the EKS equivalent)

EKS Mutating Webhook:        3. Sees the annotated SA on the Pod
(runs inside the API          4. INJECTS env vars:
server admission pipeline)       AWS_ROLE_ARN
                                  AWS_WEB_IDENTITY_TOKEN_FILE
                              5. INJECTS a projected volume with
                                  audience: "sts.amazonaws.com"

Kubelet:                      6. Calls API server TokenRequest API:
                                  "Give me a JWT for my-app-sa
                                   with aud=sts.amazonaws.com"
                              7. Mounts the JWT file into the container

API Server:                   8. Signs the JWT with its private key
                              9. Publishes the public key at the OIDC endpoint

AWS SDK (inside your app):   10. Reads env vars, reads JWT file
                             11. Calls STS.AssumeRoleWithWebIdentity()
                             12. Gets temporary AWS credentials
                             13. Uses them for S3/RDS/SQS/etc.
                             14. Auto-refreshes before expiry

Your application code:       15. Just says: boto3.client('s3')
                                 Knows NOTHING about any of this.
```    
You create two things. A ServiceAccount and a Deployment:
```yaml
apiVersion: v1
kind: ServiceAccount
metadata:
  name: my-app-sa
  namespace: production
  annotations:
    eks.amazonaws.com/role-arn: "arn:aws:iam::123456789012:role/MyAppRole"
 ```
 ```yaml
 apiVersion: apps/v1
kind: Deployment
metadata:
  name: my-app
spec:
  template:
    spec:
      serviceAccountName: my-app-sa
      containers:
        - name: app
          image: my-app:v1
```
When you apply this, the EKS mutating webhook adds the env vars and projected volume to the Pod spec. The kubelet then requests the second token with the correct audience. Your app gets AWS credentials without you having to manage any secrets!
Notice: Your Deployment YAML says NOTHING about AWS, tokens, volumes, or environment variables. It just references the ServiceAccount name.

#### Your YAML Arrives at the API Server
When you run kubectl apply -f deployment.yaml, the API server receives the Pod spec. But before saving it to etcd, it passes through the Admission Controller pipeline.
```sh
Your Pod YAML
     |
     v
+----+--------------------------------------------+
|  ADMISSION PIPELINE                             |
|                                                 |
|  AuthN --> AuthZ --> Mutating Webhooks --> ...  |
|                           |                     |
|                           v                     |
|                  +-------------------+          |
|                  | EKS Pod Identity  |          |
|                  | Mutating Webhook  |          |
|                  | (installed by AWS |          |
|                  |  when you created |          |
|                  |  the EKS cluster) |          |
|                  +-------------------+          |
+--------------------------------------------------+
```

#### The EKS Mutating Webhook Intercepts
This webhook is a Pod that AWS installed in your cluster's `kube-system` namespace when you created the EKS cluster. It runs automatically. You didn't install it.

The webhook receives the Pod spec and does the following logic:
```sh
WEBHOOK LOGIC:

1. Read the Pod spec.
2. What ServiceAccount does it use? --> "my-app-sa"
3. Look up that ServiceAccount from the API server.
4. Does it have the annotation "eks.amazonaws.com/role-arn"? 
   --> YES: "arn:aws:iam::123456789012:role/MyAppRole"
5. MODIFY THE POD SPEC before it gets saved.
```
#### The Webhook REWRITES Your Pod Spec
This is the critical step. The webhook takes your simple Pod spec and adds things to it. Here is a before/after comparison:
```sh
BEFORE (what you wrote):              AFTER (what gets saved to etcd):
================================      ================================

spec:                                 spec:
  serviceAccountName: my-app-sa         serviceAccountName: my-app-sa
  containers:                           containers:
    - name: app                           - name: app
      image: my-app:v1                      image: my-app:v1
                                            env:                          <-- ADDED
                                              - name: AWS_ROLE_ARN        <-- ADDED
                                                value: "arn:aws:iam::     <-- ADDED
                                                  123456789012:role/      <-- ADDED
                                                  MyAppRole"              <-- ADDED
                                              - name: AWS_WEB_IDENTITY_   <-- ADDED
                                                  TOKEN_FILE              <-- ADDED
                                                value: "/var/run/secrets/ <-- ADDED
                                                  eks.amazonaws.com/      <-- ADDED
                                                  serviceaccount/token"   <-- ADDED
                                            volumeMounts:                 <-- ADDED
                                              - name: aws-iam-token       <-- ADDED
                                                mountPath: /var/run/      <-- ADDED
                                                  secrets/eks.amazonaws.  <-- ADDED
                                                  com/serviceaccount      <-- ADDED
                                                readOnly: true            <-- ADDED
                                        volumes:                          <-- ADDED
                                          - name: aws-iam-token           <-- ADDED
                                            projected:                    <-- ADDED
                                              sources:                    <-- ADDED
                                                - serviceAccountToken:    <-- ADDED
                                                    audience: "sts.       <-- ADDED
                                                      amazonaws.com"      <-- ADDED
                                                    expirationSeconds:    <-- ADDED
                                                      86400               <-- ADDED
                                                    path: token           <-- ADDED
```
The modified version is what gets saved to etcd. Your original version is gone.

#### The Scheduler Assigns the Pod to a Node
Nothing special here. The scheduler picks a node and writes the nodeName into the Pod object.

#### The Kubelet on That Node Sees the Pod
The Kubelet reads the Pod spec from the API server (via the Watch mechanism). It sees there are volumes to mount. It processes each volume:
```sh
KUBELET READS THE POD SPEC:

Volume "aws-iam-token":
  Type: projected
  Source: serviceAccountToken
    audience: "sts.amazonaws.com"
    expirationSeconds: 86400
    path: token

KUBELET THINKS:
  "I need to mount a ServiceAccount token into this Pod.
   But this isn't the normal default token.
   This one has a CUSTOM AUDIENCE: sts.amazonaws.com
   I need to call the API server to get this specific token."
```
#### The Kubelet Calls the API Server's TokenRequest API
```sh
KUBELET --> API SERVER:

POST /api/v1/namespaces/production/serviceaccounts/my-app-sa/token

{
  "apiVersion": "authentication.k8s.io/v1",
  "kind": "TokenRequest",
  "spec": {
    "audiences": ["sts.amazonaws.com"],     <-- "Make the aud claim this"
    "expirationSeconds": 86400,              <-- "Make it valid for 24 hours"
    "boundRef": {
      "kind": "Pod",
      "name": "my-app-7b9f4d-x2k9m",        <-- "Bind it to this specific Pod"
      "uid": "a1b2c3d4-..."                  <-- "If this Pod dies, invalidate the token"
    }
  }
}
```

#### The API Server Signs and Returns the Token
The API server:
- Takes its private signing key (the `--service-account-signing-key-file`)
- Creates a JWT with the requested audience, expiration, and Pod binding
- Signs it
- Returns it to the Kubelet
```sh
API SERVER --> KUBELET:

{
  "status": {
    "token": "eyJhbGciOiJSUzI1NiIs...",     <-- The signed JWT
    "expirationTimestamp": "2026-04-12T14:00:00Z"
  }
}
```

#### The Kubelet Writes the Token to a File Inside the Container
The Kubelet takes the JWT string and writes it as a plain text file at the mount path:
```sh
INSIDE THE CONTAINER'S FILESYSTEM:

/var/run/secrets/eks.amazonaws.com/serviceaccount/token
  --> Contains: eyJhbGciOiJSUzI1NiIs...  (the raw JWT string)
```

### Kubernetes Admission Webhooks
```sh
TYPE 1: MUTATING WEBHOOK ("Modify the request")
  - Like Express middleware that modifies req.body before the handler sees it
  - CAN change the object
  - Runs FIRST

  Example:
    Request comes in:  { containers: [{ image: "nginx" }] }
    Webhook modifies:  { containers: [{ image: "nginx" }, { image: "istio-proxy" }] }
    What gets saved:   The modified version with the sidecar injected

TYPE 2: VALIDATING WEBHOOK ("Accept or reject the request")
  - Like Express middleware that calls next() or returns 403
  - CANNOT change the object
  - Can only say YES (allow) or NO (deny with a reason)
  - Runs AFTER mutating webhooks

  Example:
    Request comes in:  { containers: [{ securityContext: { runAsRoot: true } }] }
    Webhook response:  { allowed: false, reason: "Running as root is forbidden" }
    Result:            kubectl gets a 403 error. Object is never saved.
```
#### The Exact Pipeline
```sh
    kubectl apply -f pod.yaml
         |
         v
+--------+----------------------------------------------------------+
|  API SERVER REQUEST PIPELINE                                      |
|                                                                   |
|  1. Authentication (AuthN)                                        |
|     "Who are you?" (OIDC token, client cert, SA token)            |
|     Like: passport.js middleware                                  |
|                                                                   |
|  2. Authorization (AuthZ / RBAC)                                  |
|     "Are you allowed to do this?" (Role, ClusterRole)             |
|     Like: checkPermissions() middleware                           |
|                                                                   |
|  3. MUTATING ADMISSION WEBHOOKS  (run in order, can be chained)   |
|     +---------------------------+                                 |
|     | Istio Sidecar Injector    |  Adds envoy proxy container     |
|     +---------------------------+                                 |
|     +---------------------------+                                 |
|     | EKS IRSA Webhook          |  Adds AWS token volume + env    |
|     +---------------------------+                                 |
|     +---------------------------+                                 |
|     | Default SA Admission      |  Adds default SA if none set    |
|     +---------------------------+                                 |
|     +---------------------------+                                 |
|     | Your Custom Webhook       |  Whatever you want              |
|     +---------------------------+                                 |
|                                                                   |
|  4. SCHEMA VALIDATION                                             |
|     "Does this JSON match the OpenAPI spec for this resource?"    |
|     Like: joi/zod validation middleware                           |
|                                                                   |
|  5. VALIDATING ADMISSION WEBHOOKS  (run in parallel, accept/deny) |
|     +---------------------------+                                 |
|     | OPA Gatekeeper            |  "No pods without resource      |
|     |                           |   limits allowed"               |
|     +---------------------------+                                 |
|     +---------------------------+                                 |
|     | Kyverno                   |  "Images must come from our     |
|     |                           |   private registry only"        |
|     +---------------------------+                                 |
|     +---------------------------+                                 |
|     | Your Custom Webhook       |  "Deny if namespace has no      |
|     |                           |   cost-center label"            |
|     +---------------------------+                                 |
|                                                                   |
|  6. PERSIST TO ETCD                                               |
|     Object is saved. Done.                                        |
+-------------------------------------------------------------------+
```
```sh
REGISTRATION (you give K8s your URL):
  You --> "Here is my URL: https://sidecar-injector.kube-system/mutate" --> API Server
          "Call me whenever someone creates a Pod."

EXECUTION (K8s calls your URL when it happens):

  User creates a Pod
       |
       v
  API Server --> POST https://sidecar-injector.kube-system/mutate  --> Your Webhook Pod
                 { "request": { "object": { Pod spec... } } }
                 
  Your Webhook Pod --> Response: { "patch": "add sidecar container" } --> API Server
 ```
 Webhook: Runs as a separate service, called over HTTP/HTTPS	eg Kubernetes admission webhooks, GitHub webhooks, Stripe webhooks
 
 ```sh
 1. YOU CREATE a ServiceAccount:
   
   apiVersion: v1
   kind: ServiceAccount
   metadata:
     name: my-app-sa          <-- Just a name. Nothing else.
     namespace: production
   
   At this point, NO token exists yet.

2. YOU CREATE a Pod that references it:
   
   spec:
     serviceAccountName: my-app-sa    <-- "Use this identity"
   
   Still no token. The Pod spec is just saved to etcd.

3. KUBELET starts the Pod on a Node.
   It sees the projected volume and calls the API server:
   
   POST /api/v1/namespaces/production/serviceaccounts/my-app-sa/token
   {
     "spec": {
       "audiences": ["https://kubernetes.default.svc"],
       "expirationSeconds": 3600,
       "boundRef": {
         "kind": "Pod",
         "name": "my-app-7b9f4d-x2k9m",     <-- THIS specific Pod
         "uid": "a1b2c3d4-e5f6-..."          <-- THIS specific Pod instance
       }
     }
   }
   
   NOW the API server generates the token.

4. THE TOKEN contains the ServiceAccount identity inside it:
   
   {
     "sub": "system:serviceaccount:production:my-app-sa",   <-- WHO
     "aud": ["https://kubernetes.default.svc"],              <-- FOR WHOM
     "exp": 1744480000,                                      <-- UNTIL WHEN
     "kubernetes.io": {
       "pod": {
         "name": "my-app-7b9f4d-x2k9m",                     <-- BOUND TO
         "uid": "a1b2c3d4-e5f6-..."                          <-- THIS POD
       },
       "serviceaccount": {
         "name": "my-app-sa",                                <-- THIS SA
         "uid": "f6e5d4c3-b2a1-..."
       }
     }
   }
``` 
#### One ServiceAccount, Many Tokens
A single ServiceAccount can have multiple tokens alive simultaneously:
```sh
ServiceAccount: my-app-sa
  │
  ├── Token for Pod my-app-7b9f4d-x2k9m  (aud: kubernetes API)
  ├── Token for Pod my-app-7b9f4d-x2k9m  (aud: sts.amazonaws.com)  ← IRSA token
  ├── Token for Pod my-app-7b9f4d-r8m2n  (aud: kubernetes API)      ← second replica
  ├── Token for Pod my-app-7b9f4d-r8m2n  (aud: sts.amazonaws.com)
  └── Token for Pod my-app-7b9f4d-p3k7j  (aud: kubernetes API)      ← third replica
 ``` 
 `The default Kubernetes token is ALWAYS created, regardless of whether the ServiceAccount has AWS annotations or not.`
 ```yaml
 apiVersion: v1
kind: ServiceAccount
metadata:
  name: my-app-sa
  namespace: production
  annotations:
    eks.amazonaws.com/role-arn: "arn:aws:iam::123456789012:role/MyAppRole"
 ```  
 When a Pod uses this ServiceAccount, two completely independent systems both act on it:
 ```sh
 SYSTEM 1: Built-in ServiceAccount Admission Controller
  
  READS: serviceAccountName: my-app-sa
  CHECKS: Does the annotation matter?  NO. It ignores ALL annotations.
  ACTION: ALWAYS injects the default K8s token volume.
  
  This system doesn't care about eks.amazonaws.com/role-arn.
  It doesn't even look at annotations.
  It ONLY looks at the serviceAccountName field.
  If a ServiceAccount is referenced, it injects a token. Period.

SYSTEM 2: EKS Mutating Webhook

  READS: serviceAccountName: my-app-sa
  CHECKS: Does the SA have annotation eks.amazonaws.com/role-arn?  YES.
  ACTION: Injects the SECOND token volume + env vars for AWS.
  
  If the annotation didn't exist, this webhook would do NOTHING.
```
```sh
SCENARIO A: SA with AWS annotation
  apiVersion: v1
  kind: ServiceAccount
  metadata:
    name: my-app-sa
    annotations:
      eks.amazonaws.com/role-arn: "arn:aws:iam::..."
  
  RESULT:
    Token 1 (K8s):  ✅ Created (built-in controller, always runs)
    Token 2 (AWS):  ✅ Created (webhook sees annotation)

---

SCENARIO B: SA WITHOUT AWS annotation
  apiVersion: v1
  kind: ServiceAccount
  metadata:
    name: my-app-sa
    # no annotations
  
  RESULT:
    Token 1 (K8s):  ✅ Created (built-in controller, always runs)
    Token 2 (AWS):  ❌ Not created (webhook sees no annotation, does nothing)

---

SCENARIO C: SA with AWS annotation + automountServiceAccountToken: false
  apiVersion: v1
  kind: ServiceAccount
  metadata:
    name: my-app-sa
    annotations:
      eks.amazonaws.com/role-arn: "arn:aws:iam::..."
  automountServiceAccountToken: false      # <-- Disable Token 1
  
  RESULT:
    Token 1 (K8s):  ❌ Suppressed (you explicitly disabled it)
    Token 2 (AWS):  ✅ Created (webhook still sees annotation and injects it)
```   
You cannot prevent a ServiceAccount from being assigned. But you CAN prevent the token from being mounted    

```sh
YOU RUN:
  aws eks create-cluster --name prod-cluster ...

AWS DOES (behind the scenes):
  
  1. Provisions 3 control plane nodes (you never see these)
  2. Installs etcd
  3. Installs kube-apiserver
  4. Installs kube-scheduler
  5. Installs kube-controller-manager
  6. Configures the OIDC issuer endpoint
  7. Installs CoreDNS as a Deployment in kube-system
  8. Installs kube-proxy as a DaemonSet in kube-system
  9. Installs the VPC CNI plugin as a DaemonSet
  
  10. INSTALLS THE IRSA WEBHOOK:                          <-- HERE
      - Creates a Deployment in kube-system called
        "pod-identity-webhook" (or similar)
      - Creates a Service pointing to it
      - Creates a MutatingWebhookConfiguration that
        tells the API server to call this webhook
        whenever a Pod is created
```
```yaml
apiVersion: v1
kind: ServiceAccount
metadata:
  name: my-serviceaccount
  namespace: default
  annotations:
    eks.amazonaws.com/role-arn: "arn:aws:iam::111122223333:role/s3-reader"
    # optional: Defaults to "sts.amazonaws.com" if not set
    eks.amazonaws.com/audience: "sts.amazonaws.com"
    # optional: When set to "true", adds AWS_STS_REGIONAL_ENDPOINTS env var
    #   to containers
    eks.amazonaws.com/sts-regional-endpoints: "true"
    # optional: Defaults to 86400 for expirationSeconds if not set
    #   Note: This value can be overwritten if specified in the pod 
    #         annotation as shown in the next step.
    eks.amazonaws.com/token-expiration: "86400"
```            
### AKS Egress Options — How Traffic Leaves the Cluster
When a Pod makes an outbound request to the internet (e.g., calling a third-party API, pulling a container image, or hitting an external database), that traffic needs to leave the Azure VNet. How it leaves is the egress question, and it has significant cost, security, and compliance implications.
```sh
+------------------------------------------------------------------+
|  AKS Cluster (inside Azure VNet)                                 |
|                                                                  |
|  Pod: 10.244.0.5  wants to reach https://api.stripe.com          |
|                                                                  |
|  How does this packet leave the VNet?                            |
|                                                                  |
|  OPTION 1: Load Balancer (default)                               |
|  OPTION 2: NAT Gateway (recommended for production)              |
|  OPTION 3: User-Defined Routes + Azure Firewall / NVA             |
|  OPTION 4: Managed NAT Gateway (AKS-managed)                     |
+------------------------------------------------------------------+
```
####  Load Balancer (Default)
When you create an AKS cluster without specifying anything, Azure automatically creates a Standard Load Balancer with a public IP. ALL outbound traffic from ALL Pods is SNAT'd through this load balancer.
```sh
+------------------+     +--------------------+     +------------------+
|  Pod             |     | Azure Load Balancer|     | Internet         |
|  10.244.0.5      |---->| Public IP:         |---->| api.stripe.com   |
|                  |     | 20.120.50.100      |     |                  |
+------------------+     +--------------------+     +------------------+
                          (Source NAT: Pod IP
                           becomes 20.120.50.100)
```
```sh
az aks create \
  --resource-group myapp-rg \
  --name prod-cluster \
  --outbound-type loadBalancer          # <-- Default, you don't need to specify this
```

| Pro | Con |
|---|---|
| Zero config, works out of the box | SNAT port exhaustion at scale. Each public IP gives you ~64,000 SNAT ports. If you have hundreds of Pods making thousands of connections, you run out |
| Can add multiple outbound IPs to get more SNAT ports | Hard to control/audit which IP traffic comes from |
| Cheapest option | All traffic exits through the same public IP(s) — no granular control |


Scaling SNAT ports:
```sh
# Add more outbound IPs to the load balancer (each gives ~64K ports)
az aks update \
  --resource-group myapp-rg \
  --name prod-cluster \
  --load-balancer-managed-outbound-ip-count 5    # 5 IPs = ~320K SNAT ports
```
#### NAT Gateway (Recommended for Production)
A NAT Gateway is a dedicated, fully managed Azure service designed specifically for outbound traffic. It replaces the load balancer as the egress path.
```sh
+------------------+     +--------------------+     +------------------+
|  Pod             |     | Azure NAT Gateway  |     | Internet         |
|  10.244.0.5      |---->| Public IP(s):      |---->| api.stripe.com   |
|                  |     | 20.120.60.10       |     |                  |
+------------------+     | 20.120.60.11       |     +------------------+
                          +--------------------+
                          Up to 16 IPs
                          = 1,024,000+ SNAT ports
                          No port exhaustion concerns
```
```sh
# Create a NAT Gateway
az network nat gateway create \
  --resource-group myapp-rg \
  --name aks-natgw \
  --public-ip-addresses aks-natgw-pip \
  --idle-timeout 10

# Create AKS cluster using NAT Gateway for egress
az aks create \
  --resource-group myapp-rg \
  --name prod-cluster \
  --outbound-type managedNATGateway       # AKS manages the NAT Gateway
  # OR
  --outbound-type userAssignedNATGateway  # You pre-create and manage it yourself
  ```
 ```sh
# One command. AKS does everything.
az aks create \
  --resource-group myapp-rg \
  --name prod-cluster \
  --outbound-type managedNATGateway \
  --nat-gateway-managed-outbound-ip-count 2 \    # 2 public IPs = ~128K SNAT ports
  --nat-gateway-idle-timeout 4                    # TCP idle timeout in minutes
  ```
To find out what public IP your pods are using:
```sh
# Get the outbound IPs
az aks show \
  --resource-group myapp-rg \
  --name prod-cluster \
  --query "networkProfile.natGatewayProfile.effectiveOutboundIPs[].id" -o tsv

# Resolve to actual IP addresses
az network public-ip show --ids <id-from-above> --query "ipAddress" -o tsv
# Output: 20.120.60.10
```
User-Assigned NAT Gateway 
```sh
# STEP 1: You create everything yourself first
az network public-ip create \
  --resource-group myapp-rg \
  --name aks-egress-pip \
  --sku Standard \
  --allocation-method Static

az network nat gateway create \
  --resource-group myapp-rg \
  --name aks-egress-natgw \
  --public-ip-addresses aks-egress-pip \
  --idle-timeout 10

# STEP 2: Attach NAT Gateway to your AKS subnet
az network vnet subnet update \
  --resource-group myapp-rg \
  --vnet-name aks-vnet \
  --name aks-subnet \
  --nat-gateway aks-egress-natgw

# STEP 3: Create AKS and tell it to use whatever NAT Gateway is on the subnet
az aks create \
  --resource-group myapp-rg \
  --name prod-cluster \
  --outbound-type userAssignedNATGateway \       # <-- "I already set it up, just use it"
  --vnet-subnet-id /subscriptions/.../subnets/aks-subnet
```  

| Pro | Con |
|---|---|
| Massive SNAT port capacity (1M+ ports with 16 IPs) | Costs ~$0.045/hr + data processing charges |
| Predictable, stable outbound IP(s) — great for allowlisting | Slightly more complex setup |
| No SNAT port exhaustion | |
| Better performance than LB egress | |

Why predictable IPs matter: If your app calls a third-party API (like Stripe or a partner's firewall), they often need to allowlist your IP address. With a NAT Gateway, you know exactly which IP(s) your traffic comes from

```sh
+-----------------------------------------------------------------+
|  managedNATGateway                                              |
|  WHO MANAGES IT: AKS (Azure creates it for you)                 |
|                                                                 |
|  - AKS creates the NAT Gateway automatically                    |
|  - AKS creates the public IP automatically                      |
|  - AKS attaches it to the node subnet automatically             |
|  - AKS manages the lifecycle (creates, updates, deletes with    |
|    the cluster)                                                 |
|  - You can configure it via AKS CLI flags                         |
|  - You CANNOT share it with other resources outside AKS         |
+-----------------------------------------------------------------+

+-----------------------------------------------------------------+
|  userAssignedNATGateway                                         |
|  WHO MANAGES IT: You (you pre-create it, AKS just uses it)      |
|                                                                 |
|  - YOU create the NAT Gateway in advance                        |
|  - YOU create and assign the public IP(s)                       |
|  - YOU attach it to the subnet                                  |
|  - AKS just uses whatever NAT Gateway is on the subnet          |
|  - You CAN share it with VMs, other services, other clusters    |
|  - You have full control over the NAT Gateway's settings        |
+-----------------------------------------------------------------+
```
#### User-Defined Routes + Azure Firewall (Maximum Control)
For enterprises with strict compliance requirements (finance, healthcare, government), ALL egress traffic must pass through a central inspection point. You route everything through an Azure Firewall or a third-party Network Virtual Appliance (NVA) like Palo Alto

```sh
+------------------+     +--------------------+     +------------------+
|  Pod             |     | Azure Firewall     |     | Internet         |
|  10.244.0.5      |---->| (inspects traffic)  |---->| api.stripe.com   |
|                  |     |                    |     |                  |
+------------------+     | Rules:             |     +------------------+
                         | ✓ Allow: stripe.com|
                         | ✓ Allow: acr.io    |
                         | ✗ Deny: *          |
                         +--------------------+
                          All other traffic BLOCKED
```       
```sh
# Create AKS with user-defined routing
az aks create \
  --resource-group myapp-rg \
  --name prod-cluster \
  --outbound-type userDefinedRouting       # <-- No public IP at all on the cluster!
  --vnet-subnet-id /subscriptions/.../subnets/aks-subnet
```
Then you create a Route Table that sends all traffic (0.0.0.0/0) to the Azure Firewall:
```sh
# Create route table
az network route-table create \
  --resource-group myapp-rg \
  --name aks-udr

# Default route: ALL traffic goes to the firewall
az network route-table route create \
  --resource-group myapp-rg \
  --route-table-name aks-udr \
  --name default-route \
  --address-prefix 0.0.0.0/0 \
  --next-hop-type VirtualAppliance \
  --next-hop-ip-address 10.0.3.4          # Azure Firewall's private IP

# Associate route table with the AKS subnet
az network vnet subnet update \
  --resource-group myapp-rg \
  --vnet-name aks-vnet \
  --name aks-subnet \
  --route-table aks-udr
```
Azure Firewall rules (what you allow through):
```sh
REQUIRED for AKS to function (minimum egress rules):

+---------------------------------------------------------------+
| FQDN Rule                              | Port | Purpose       |
+---------------------------------------------------------------+
| *.hcp.<region>.azmk8s.io               | 443  | API server    |
| mcr.microsoft.com                      | 443  | Microsoft     |
|                                        |      | container     |
|                                        |      | registry      |
| *.data.mcr.microsoft.com               | 443  | MCR data      |
| management.azure.com                   | 443  | Azure APIs    |
| login.microsoftonline.com              | 443  | Azure AD auth |
| packages.microsoft.com                 | 443  | OS packages   |
| acs-mirror.azureedge.net               | 443  | OS packages   |
+---------------------------------------------------------------+

YOUR APP-SPECIFIC RULES:
+---------------------------------------------------------------+
| api.stripe.com                         | 443  | Payments      |
| *.s3.amazonaws.com                     | 443  | Cross-cloud   |
| myacr.azurecr.io                       | 443  | Your ACR      |
+---------------------------------------------------------------+

EVERYTHING ELSE: DENIED
```

| Pro | Con |
|---|---|
| Full visibility into ALL egress traffic | Azure Firewall costs $1.25/hr ($900/month) |
| Can block data exfiltration (no unauthorized uploads) | Complex setup |
| Compliance teams love it (SOC2, HIPAA, PCI-DSS) | Must manually allow required AKS FQDNs |
| Can log every outbound connection | Breaking changes if Azure adds new required endpoints |

#### No Egress At All (Private Cluster)
For the most locked-down environments, you can create a fully private cluster where nothing has internet access. The API server itself gets a private IP, not a public one.

```sh
az aks create \
  --resource-group myapp-rg \
  --name prod-cluster \
  --enable-private-cluster \
  --outbound-type none                   # <-- No internet egress at all!
```
```sh
+------------------------------------------------------------------+
|  Private Cluster                                                 |
|                                                                  |
|  API Server: 10.0.0.4 (private IP only, no public endpoint)      |
|  Egress: NONE (Pods cannot reach the internet)                   |
|                                                                  |
|  How do Pods pull images?                                        |
|  --> Azure Private Endpoint to ACR (stays on private network)    |
|                                                                  |
|  How do Pods talk to Azure SQL?                                  |
|  --> Azure Private Endpoint to SQL (stays on private network)    |
|                                                                  |
|  How do developers run kubectl?                                  |
|  --> Azure Bastion / VPN / Jumpbox inside the VNet               |
+------------------------------------------------------------------+
```  
```sh
SMALL STARTUP:
  outbound-type: loadBalancer (default)
  Simple, cheap, works fine under 100 pods.

GROWING COMPANY:
  outbound-type: managedNATGateway
  Predictable IPs, no SNAT exhaustion, easy to set up.

ENTERPRISE / REGULATED:
  outbound-type: userDefinedRouting + Azure Firewall
  All traffic inspected, logged, and filtered.
  Required for SOC2 Type II, HIPAA, PCI-DSS in most auditors' eyes.

GOVERNMENT / AIR-GAPPED:
  outbound-type: none + Private Cluster
  Zero internet access. Everything over Private Endpoints.
```  

### SNAT Port Exhaustion — Why It Happens
To understand this, you need to understand how a computer identifies a network connection.
`(Source IP, Source Port, Destination IP, Destination Port, Protocol)`

When your Pod at private IP `10.244.0.5` makes an HTTPS request to `api.stripe.com:443`, the connection looks like this before it leaves the cluster:
```sh
Source IP:    10.244.0.5     (Pod's private IP — not routable on the internet)
Source Port:  49152           (random port picked by the OS)
Dest IP:      54.187.174.169 (Stripe's IP)
Dest Port:    443
```
But `10.244.0.5` is a private IP. The internet doesn't know how to send a response back to it. So the packet must be translated to a public IP before it leaves Azure/AWS.

#### What SNAT (Source NAT) Does
The Load Balancer or NAT Gateway rewrites the source of the packet:
```sh
BEFORE SNAT:                              AFTER SNAT:
Source: 10.244.0.5:49152                  Source: 20.120.50.100:30001
Dest:   54.187.174.169:443                Dest:   54.187.174.169:443

         Pod's private IP                          Public IP
         Pod's random port                         Translated port
```
- `49152` — the pod picked this randomly from its own local ephemeral range. Every OS picks a random high port when opening a connection. This number is irrelevant to SNAT exhaustion because it's in the pod's private address space — millions of pods could all use port 49152 simultaneously since they have different IPs (10.244.0.5:49152, 10.244.0.9:49152, etc. are all distinct).

- `30001` — the NAT gateway assigned this from its own pool of ports on the single public IP. This is the one that causes exhaustion.
The NAT device keeps a translation table:
```sh
+------------------------------------------------------------------+
|  NAT TRANSLATION TABLE                                           |
|                                                                  |
|  Public 20.120.50.100:30001 <--> Private 10.244.0.5:49152        |
|  Public 20.120.50.100:30002 <--> Private 10.244.0.5:49153        |
|  Public 20.120.50.100:30003 <--> Private 10.244.1.8:52001        |
|  ...                                                             |
+------------------------------------------------------------------+
```
When Stripe responds, the NAT device receives the response at `20.120.50.100:30001`, looks up the table, and forwards it back to `10.244.0.5:49152`      
#### Where the Limit Comes From
A port number is a 16-bit integer (0–65535). Ports `0–1023` are reserved. So each public IP has roughly `~64,000` usable ports for `SNAT`.   

Each simultaneous outbound connection to a unique destination consumes one SNAT port. The port is held for the duration of the connection PLUS a cooldown timer (usually 4 minutes for TCP after the connection closes).

```sh
ONE public IP = ~64,000 SNAT ports

Each outbound connection uses 1 port.
Each port is locked for: connection duration + 4 min cooldown.

If 100 Pods each make 700 simultaneous connections:
  100 × 700 = 70,000 ports needed
  64,000 ports available
  = EXHAUSTION. New connections FAIL with timeout errors.
 ``` 

 So the pod's ~64K ephemeral ports are per unique destination:
 ```sh
 To api.stripe.com:443     → 64K ports available
To api.github.com:443     → 64K ports available (separate pool)
To db.internal:5432       → 64K ports available (separate pool)
```
If one pod opens 70K simultaneous connections to the same destination (same IP + same port), then yes — the pod itself would run out of ports before the NAT gateway even becomes relevant.
The OS (Linux kernel) tracks connections by this key:
```sh
(protocol, src IP, src port, dest IP, dest port)
```
Two connections are different as long as any one of those five fields differs. So:
```sh
Connection 1: (TCP, 10.244.0.5, 49152, 54.187.1.1, 443)  ← to Stripe
Connection 2: (TCP, 10.244.0.5, 49152, 140.82.1.1, 443)  ← to GitHub
                                 ^same     ^different
                                 port       dest IP
```
These are two distinct connections even though they use the same source port (49152). The kernel can tell them apart because the destination IP is different. When a response comes back from `54.187.1.1`, the kernel knows it belongs to Connection 1. When a response comes from `140.82.1.1`, it belongs to Connection 2.

So for each unique (dest IP, dest port) pair, the pod can reuse all ~64K source ports independently:   
```sh
To 54.187.1.1:443  → can use ports 1024–65535  (64K connections)
To 140.82.1.1:443  → can use ports 1024–65535  (64K connections)
To 10.0.0.50:5432  → can use ports 1024–65535  (64K connections)
```
But to the same destination, the `source port` is the only field left to vary:                              
```sh
(TCP, 10.244.0.5, ???, 54.187.1.1, 443)
                   ^
                   only this can change
                   = max ~64K unique values
```
Why does the NAT gateway NOT get this benefit? Because it translates all internal connections onto one IP. The NAT gateway's 5-tuple is:
```sh    
(TCP, 20.120.50.100, ???, dest IP, dest port)
       ^one IP        ^only varying field
```

the load balancer pre-assigns a fixed chunk of ports to each VM, and that VM can only use those ports no matter who it's talking to.

Azure Load Balancer SNAT 
```sh
Public IP: 20.120.50.100 (64K ports)
Cluster has 4 VMs:

VM 1 gets ports 1024–17407    (16K ports — that's ALL it ever gets)
VM 2 gets ports 17408–33791   (16K ports)
VM 3 gets ports 33792–50175   (16K ports)
VM 4 gets ports 50176–65535   (16K ports)
```
Now VM 1 can only use ports 1024–17407 for all its outbound connections — whether it's talking to Stripe, GitHub, a database, or anything else:
```sh
VM 1 → Stripe:443      uses port 1024   ┐
VM 1 → GitHub:443      uses port 1025   │ all from the
VM 1 → Stripe:443      uses port 1026   │ same 16K pool
VM 1 → database:5432   uses port 1027   ┘

After 16,384 concurrent connections → VM 1 is OUT. Done.
Even though it's talking to different destinations.
```
Azure NAT Gateway (smart approach):

No pre-allocation. It uses the full 5-tuple. Ports are reused per destination:
```sh
VM 1 → Stripe:443      uses port 30000  ┐ pool for Stripe
VM 1 → Stripe:443      uses port 30001  ┘ (up to 64K)

VM 1 → GitHub:443      uses port 30000  ┐ pool for GitHub
VM 1 → GitHub:443      uses port 30001  ┘ (up to 64K, reuses same port numbers!)
```
Port 30000 is used twice — but that's fine because the destinations differ, so the 5-tuples are unique.

`That's why adding more VMs to a Load Balancer actually makes port exhaustion worse — each VM gets a smaller slice of the pie.`

Because even though you have 64K per destination, one busy destination can eat all 64K by itself.

If your pods make 10,000 requests/sec to just Stripe (one destination), with the 4-minute cooldown:
```sh
10,000 conn/sec × 240 sec cooldown = 2,400,000 ports held

But you only have 64K for Stripe → exhausted in ~6 seconds
```
Adding more IPs helps because the NAT gateway spreads connections across them:
```
1 IP:   64K ports to Stripe:443
4 IPs:  256K ports to Stripe:443
16 IPs: ~1M ports to Stripe:443
```
From the TCP specification — it's called TIME_WAIT.

When a TCP connection closes, the side that initiates the close enters the TIME_WAIT state. RFC 793 (the original TCP spec) defines it as 2 × MSL (Maximum Segment Lifetime):
```sh
MSL = 2 minutes (defined in RFC 793)
TIME_WAIT = 2 × MSL = 4 minutes
```
Why does it exist? To handle stale packets that might still be floating around the network. Imagine:
```sh
t=0s    Connection A  (port 30000 → Stripe:443) closes
t=1s    Connection B  (port 30000 → Stripe:443) opens ← reuses same port
t=2s    A late packet from Connection A arrives
        → Kernel thinks it belongs to Connection B ← DATA CORRUPTION
```        
In practice, Linux defaults to 60 seconds, not the full 4 minutes:
```sh
$ cat /proc/sys/net/ipv4/tcp_fin_timeout
60
```

[load balancer outbound rules](https://learn.microsoft.com/en-us/azure/load-balancer/outbound-rules)
Outbound idle timeouts default to 4 minutes

### Azure SNAT Ports
When multiple subnets within a virtual network are attached to the same NAT gateway resource, the SNAT port inventory provided by NAT Gateway is shared across all subnets.

SNAT ports serve as unique identifiers to distinguish different connection flows from one another. The same SNAT port can be used to connect to different destination endpoints at the same time.
Different SNAT ports are used to make connections to the same destination endpoint in order to distinguish different connection flows from one another. SNAT ports being reused to connect to the same destination are placed on a reuse cool down timer before they can be reused.
A single NAT gateway can scale by the number of public IP addresses associated to it. Each NAT gateway public IP address provides 64,512 SNAT ports to make outbound connections. A NAT gateway can scale up to over 1 million SNAT ports. TCP and UDP are separate SNAT port inventories and are unrelated to NAT Gateway.

##### TCP idle timeout(NAT Gateway)

A NAT gateway provides a configurable idle timeout range of 4 minutes to 120 minutes for TCP protocols. UDP protocols have a nonconfigurable idle timeout of 4 minutes.

When a connection goes idle, the NAT gateway holds onto the SNAT port until the connection idle times out. Because long idle timeout timers can unnecessarily increase the likelihood of SNAT port exhaustion, it isn't recommended to increase the TCP idle timeout duration to longer than the default time of 4 minutes. The idle timer doesn't affect a flow that never goes idle.

[Learn more about Azure NAT Gateway SNAT](https://learn.microsoft.com/en-us/azure/nat-gateway/nat-gateway-snat)

### Kube-System Namespace
A Kubernetes namespace that comes built-in with every cluster. It holds the system components that make the cluster work.
```sh
$ kubectl get pods -n kube-system

NAME                                    READY
coredns-5d78c9869d-abc12                1/1
coredns-5d78c9869d-def34                1/1
etcd-master                             1/1
kube-apiserver-master                   1/1
kube-controller-manager-master          1/1
kube-scheduler-master                   1/1
kube-proxy-node1                        1/1
kube-proxy-node2                        1/1
```
On managed clusters (AKS/EKS/GKE), you'll also see cloud-specific components there:
```sh
# AKS example
cloud-node-manager-xxxxx
azure-ip-masq-agent-xxxxx
tunnelfront-xxxxx
```
Every cluster starts with three namespaces:

| Namespace | Purpose |
|---|---|
| default | Where your stuff goes if you don't specify a namespace |
| kube-system | Cluster infrastructure components |
| kube-public | Readable by everyone, rarely used (holds cluster-info ConfigMap) |

Ephemeral port range

Defined by the sysctl `net.ipv4.ip_local_port_range` (default 32768–60999). The kernel picks from this range.

### Selection algorithm
A per-connection-group offset is computed by hashing the (src IP, dst IP, dst port) — so different destinations start searching at different points in the range, spreading ports out.
Starting from that offset, the kernel walks through the range (with a stride based on the number of table buckets) looking for a port not already used for the same 5-tuple destination.
If `net.ipv4.ip_local_port_range` is exhausted for a given destination, you get EADDRNOTAVAIL ("Cannot assign requested address").

A port number is a 16-bit integer (0–65535). Ports `0–1023` are reserved. So each public IP has roughly `~64,000` usable ports for `SNAT`
#### Get the Port Range
```c
//With the default sysctl net.ipv4.ip_local_port_range = 32768 60999:
local_ports = inet_sk_get_local_port_range(sk, &low, &high);
high++;                        // make it exclusive: [32768, 61000)
remaining = high - low;
```

#### Compute the hash-based starting offset
```c
index = port_offset & (INET_TABLE_PERTURB_SIZE - 1);
offset = READ_ONCE(table_perturb[index]) + (port_offset >> 32);
offset %= remaining;
```
`port_offset` comes from `inet_sk_port_offset(sk)` which hashes (src_ip, dst_ip, dst_port). The `table_perturb[]` array adds randomness that shifts over time.
```c
static u64 inet_sk_port_offset(const struct sock *sk)
{
    return secure_ipv4_port_ephemeral(inet->inet_rcv_saddr,   // src IP
                                      inet->inet_daddr,        // dst IP
                                      inet->inet_dport);       // dst port
}
```
#### Linear scan from that offset
```c
port = low + offset;
for (i = 0; i < remaining; i += step, port += step) {
    if (unlikely(port >= high))
        port -= remaining;              // wrap around
    if (inet_is_local_reserved_port(net, port))
        continue;
    // ... look up port in bind hash table ...
    // if bucket empty or check_established() says OK → goto ok
    // otherwise → next_port
}
return -EADDRNOTAVAIL;                  // all ports exhausted
```
`check_established` is the key uniqueness check. It calls `__inet_check_established()` which checks whether a matching (`proto, src_ip, port, dst_ip, dst_port`) 5-tuple already exists in the established hash table. If not → the port is available.

### Kubenet
Kubenet is the simplest, most basic networking plugin for Kubernetes 
Each node gets a /24 subnet (256 IPs) from a separate, pod-only address space. Pods get IPs from this space — not from your Azure VNet

```sh
Azure VNet: 10.0.0.0/16

Node 1 (10.0.0.4):    Pods get IPs from 10.244.0.0/24    ← NOT in the VNet
Node 2 (10.0.0.5):    Pods get IPs from 10.244.1.0/24    ← NOT in the VNet
Node 3 (10.0.0.6):    Pods get IPs from 10.244.2.0/24    ← NOT in the VNet
```
The key problem: Since pod IPs aren't real VNet IPs, Azure doesn't know how to route to them. So Kubenet uses User Defined Routes (UDRs) to teach Azure:
```sh
Route Table:
10.244.0.0/24 → forward to Node 1 (10.0.0.4)
10.244.1.0/24 → forward to Node 2 (10.0.0.5)
10.244.2.0/24 → forward to Node 3 (10.0.0.6)
```

### Azure Container Networking
```sh
┌───────────────────────────────────────────────────────┐
│                    Kubernetes                         │
│  kubelet calls CNI plugin on pod create/delete        │
└──────────────┬────────────────────────────────────────┘
               │
       ┌───────▼───────┐      ┌──────────────────┐
       │  azure-vnet   │◄────►│  azure-vnet-ipam │
       │  (CNI Plugin) │      │  (IPAM Plugin)   │
       └───────┬───────┘      └────────┬─────────┘
               │                       │
       ┌───────▼───────────────────────▼──────────┐
       │          Azure CNS (REST Service)        │
       │  Manages Network Containers & IP pools   │
       └───────┬──────────────────────────────────┘
               │
       ┌───────▼───────────────────────────────────┐
       │     Azure Platform APIs                   │
       │  (Wireserver / IMDS / NMAgent / ARM)      │
       └───────────────────────────────────────────┘
```       
### Amazon VPC CNI
```sh
┌──────────┐     ADD Pod      ┌──────────────────┐    gRPC: AddNetwork  ┌────────────┐
│  kubelet │ ───────────────▶ │  CNI Plugin      │ ─────────────────────▶│  ipamd    │
│          │                  │  (routed-eni)    │◀─────────────────────│  (L-IPAM)  │
└──────────┘                  │                  │   Return IP addr     │            │
                              │  Wire up veth,   │                      │  Warm Pool │
                              │  routes, ARP     │                      │  of IPs    │
                              └──────────────────┘                      └──────┬─────┘
                                                                               │
                                                                     EC2 API: Attach ENI,
                                                                     Assign Secondary IPs
                                                                               │
                                                                        ┌──────▼──────┐
                                                                        │  AWS VPC    │
                                                                        │  (ENIs)     │
                                                                        └─────────────┘
```

### ipamd (L-IPAM Daemon) — the IP Address Manager
- A long-running daemon (cmd/aws-k8s-agent) deployed as a DaemonSet (aws-node) on every worker node.
- Manages a warm pool of pre-allocated IP addresses so pods can get IPs instantly. Communicates with the AWS EC2 API to create/attach Elastic Network Interfaces (ENIs) and allocate secondary IP addresses on them.
- Exposes a gRPC server that the CNI plugin calls to request/release IPs.
### CNI Plugin Binary (cmd/routed-eni-cni-plugin)
A short-lived binary invoked by kubelet every time a pod is created (ADD) or deleted (DEL).
    Contacts ipamd over gRPC to get an IP address, then wires up the pod's network namespace.

- Kubelet calls the CNI plugin with an ADD command when a pod is scheduled.
- CNI plugin contacts ipamd via gRPC (AddNetwork RPC) to get a free secondary IP address.
ipamd returns an IP from its pre-warmed pool.
- CNI plugin wires the network:
  - Creates a veth pair (one end in the host namespace, one in the pod's namespace).
  - Assigns the IP (/32) to the pod's eth0.
  - Sets up a default route via a link-local gateway (169.254.1.1) and a static ARP entry pointing to the host-side veth's MAC address.
  - On the host side, adds a host route to the pod IP and policy routing rules to direct traffic from the pod out through the correct ENI.

### ENI & IP Warm Pool Management

This is the key innovation — `ipamd` pre-allocates ENIs and IPs so pod startup isn't blocked by EC2 API calls.  

### IP capacity formula
`Max Pod IPs per node = (number of ENIs × IPs per ENI) - number of ENIs`
For example, an m4.4xlarge supports 8 ENIs × 30 IPs each = 232 pod IPs

### Copilot finished thinking
Copilot said: Let me look at the AWS SDK

Let me look at the AWS SDK interactions to make the secondary IP concept concrete.## Secondary IPs — The Core Concept Explained

The "secondary IP" concept is best understood by first understanding what an ENI (Elastic Network Interface) is at the AWS level, and then seeing how the VPC CNI plugin exploits it.
What is an ENI?

An ENI is a virtual network card that AWS attaches to your EC2 instance. Think of it like a physical NIC on a server. When your EC2 instance launches, it automatically gets one ENI — the primary ENI. This primary ENI gets one primary private IP address (e.g., 10.0.1.50), and that's the IP your EC2 instance is known by.

### What are Secondary IPs?

Here's the key AWS feature the CNI exploits: every ENI can hold multiple IP addresses, not just one.

AWS allows you to assign additional private IP addresses to any ENI. These additional IPs are called secondary IPs. They all come from the same subnet as the ENI, and AWS's VPC networking fabric routes traffic to ALL of them — both primary and secondary — to that ENI.
```sh
┌──────────────────────────────────────────────┐
│              EC2 Instance                    │
│                                              │
│   ENI-0 (Primary ENI, device eth0)           │
│   ├── 10.0.1.50  ← Primary IP (the node's)   │
│   ├── 10.0.1.51  ← Secondary IP  → Pod A     │
│   ├── 10.0.1.52  ← Secondary IP  → Pod B     │
│   └── 10.0.1.53  ← Secondary IP  → Pod C     │
│                                              │
│   ENI-1 (Secondary ENI, device eth1)         │
│   ├── 10.0.1.80  ← Primary IP (NOT used)     │
│   ├── 10.0.1.81  ← Secondary IP  → Pod D     │
│   ├── 10.0.1.82  ← Secondary IP  → Pod E     │
│   └── 10.0.1.83  ← Secondary IP  → Pod F     │
│                                              │
└──────────────────────────────────────────────┘
```
Each secondary IP is a real, routable VPC IP address. AWS's network fabric already knows how to deliver packets destined for 10.0.1.51 to ENI-0 on this instance. The CNI just needs to make sure those packets reach the right pod inside the instance.

#### Packets arrive for pod
Since 10.0.1.51 is a secondary IP on ENI-0, the VPC router delivers the packet to the EC2 instance. The Linux kernel on the host then uses the route table to forward it into the pod via the veth pair.
```sh
Internet → VPC Router → ENI-0 → Host sees dst=10.0.1.51 → 
   host route: "10.0.1.51 dev eni-veth-xyz" → Pod A's network namespace
```
#### Pod sends a packet
```sh
Pod A (src=10.0.1.51) → veth → Host → ip rule: "from 10.0.1.51 use table eni-0" → 
   ENI-0's route table → ENI-0 → VPC Router → destination
```
Policy routing ensures the packet exits through the same ENI that owns the IP — this is critical because if it went out through a different ENI, VPC would drop it 

```go
// From awsutils.go line 2018-2025 — subtracting 1 for the primary IP
func (cache *EC2InstanceMetadataCache) GetENIIPv4Limit() int {
    ipv4Limit, err := vpc.GetIPv4Limit(cache.instanceType)
    if err != nil {
        return -1
    }
    // Subtract one from the IPv4Limit since we don't use the primary IP on each ENI for pods.
    return ipv4Limit - 1
}
```
nd when adding IPs to the datastore, the primary is skipped:
```go
// From ipamd.go line 1351-1366 — only secondary IPs are added
func (c *IPAMContext) addENIsecondaryIPsToDataStore(ec2PrivateIpAddrs []ec2types.NetworkInterfacePrivateIpAddress, eni string, networkCard int) {
    for _, ec2PrivateIpAddr := range ec2PrivateIpAddrs {
        if aws.ToBool(ec2PrivateIpAddr.Primary) {
            continue   // ← Skip the primary IP!
        }
        cidr := net.IPNet{IP: net.ParseIP(aws.ToString(ec2PrivateIpAddr.PrivateIpAddress)), Mask: net.IPv4Mask(255, 255, 255, 255)}
        err := c.dataStoreAccess.GetDataStore(networkCard).AddIPv4CidrToStore(eni, cidr, false)
        ...
    }
}
```
### Capacity Limits
Every EC2 instance type has fixed limits set by AWS:

| Instance Type | Max ENIs | Max IPs per ENI | Usable Secondary IPs per ENI | Total Pod IPs |
|---|---|---|---|---|
| t3.medium | 3 | 6 | 5 | 15 |
| m5.large | 3 | 10 | 9 | 27 |
| m5.xlarge | 4 | 15 | 14 | 56 |
| m5.4xlarge | 8 | 30 | 29 | 232 |

### Prefix Delegation — The Evolution Beyond Secondary IPs

Secondary IPs have a hard limit (e.g., 29 per ENI on m5.4xlarge). To go beyond this, the CNI supports prefix delegation (ENABLE_PREFIX_DELEGATION=true), which allocates /28 CIDR blocks (16 IPs each) instead of individual IPs:
```go
// From awsutils.go line 1160-1168
if cache.enablePrefixDelegation {
    input.Ipv4PrefixCount = aws.Int32(int32(needIPs))   // Allocate /28 prefixes
} else {
    input.SecondaryPrivateIpAddressCount = aws.Int32(int32(needIPs))  // Allocate individual IPs
}
```
With prefix delegation, instead of getting 29 individual IPs per ENI, you get 29 prefixes × 16 IPs each = 464 IPs per ENI, dramatically increasing pod density.

```sh
Layer 1: AWS EC2 Level (what's allocated to the ENI)
┌──────────────────────────────────────────────────────┐
│  /28 Prefix: 10.0.1.0/28  (16 IPs: 10.0.1.0-15)       │
│  Allocated as a single unit from the EC2 API         │
└──────────────────────────────────────────────────────┘

Layer 2: Datastore Level (what's given to pods)
┌──────────────────────────────────────────────────────┐
│  10.0.1.0/32  → Pod A                                │
│  10.0.1.1/32  → Pod B                                │
│  10.0.1.2/32  → (free)                               │
│  10.0.1.3/32  → Pod C                                │
│  ...                                                 │
│  10.0.1.15/32 → (free)                               │
└──────────────────────────────────────────────────────┘
```

### Directly Connected Routes
When you assign an IP address to an interface, the kernel automatically creates a directly connected route for that subnet. No manual entry needed.
```sh
$ ip addr add 192.168.1.10/24 dev eth0
$ ip route show
192.168.1.0/24 dev eth0 proto kernel scope link src 192.168.1.10
```
That route means: "to reach any IP in 192.168.1.0/24, just send frames directly out eth0 — no gateway needed."

```sh
192.168.1.0/24 dev eth0 proto kernel scope link src 192.168.1.10
                         │            │
                         │            └─ scope link: destination is on the local 
                         │               network (directly connected, no gateway)
                         │
                         └─ proto kernel: this route was auto-created by the 
                            kernel when the IP was assigned (not manually added,
                            not from DHCP daemon, not from a routing protocol)
```
`src` is the IP address of the interface

```sh
$ ip route show
192.168.1.0/24  dev eth0  proto kernel  scope link  src 192.168.1.10  ← direct (auto)
10.50.0.0/16    via 192.168.1.1  dev eth0  proto static               ← manual
10.0.0.0/8      via 192.168.1.2  dev eth0  proto bird                 ← routing protocol
default         via 192.168.1.1  dev eth0  proto dhcp                  ← DHCP
```

| Field | Meaning |
|---|---|
| via 192.168.1.1 | Gateway — this is what makes it indirect. The gateway must itself be reachable via a direct route |
| proto static | Added manually with ip route add |
| proto dhcp | Added by DHCP client |
| proto bird/ospf | Added by a routing daemon |
| No scope link | Not directly connected — goes through a gateway |

A gateway route requires that the gateway IP is reachable via a directly connected route

```sh
$ ip route show
192.168.1.0/24  dev eth0  scope link  src 192.168.1.10     ← direct (connected)
default         via 192.168.1.1  dev eth0                   ← indirect (via gateway)
```
Both routes use eth0. The difference is just what MAC address goes in the frame:
```sh
Destination: 192.168.1.50 (direct)
  → ARP for 192.168.1.50
  → Frame: dst MAC = 192.168.1.50's MAC
  → out eth0

Destination: 54.187.1.1 (indirect, via gateway)
  → ARP for 192.168.1.1 (the gateway)
  → Frame: dst MAC = gateway's MAC, dst IP still = 54.187.1.1
  → out eth0
```
 The only difference is the destination MAC in the Ethernet frame. The gateway (router) is just another host on the same switch — your machine sends the packet to it, and the router forwards it onward.

the `via` address must be a device that forwards packets (a router). It must:
- Be on a directly connected subnet — so your machine can ARP for it
- Have IP forwarding enabled (`net.ipv4.ip_forward = 1`) — otherwise it drops packets not addressed to itself 

```sh
You (eth0)  →  Router A  →  Router B  →  Stripe server

Hop 1:  src MAC = your MAC,      dst MAC = Router A's MAC
Hop 2:  src MAC = Router A's MAC, dst MAC = Router B's MAC
Hop 3:  src MAC = Router B's MAC, dst MAC = Stripe's MAC

But the IP header stays constant the whole way:
        src IP = 192.168.1.10,   dst IP = 54.187.1.1
```        
### Connected route (local subnet):
When Pod A (10.0.0.10) talks to Pod B (10.0.0.11) on the same subnet, Azure sees them as two IPs on the same NIC (or same subnet). No routing needed — it's a direct Layer 2 delivery, just like two VMs in the same subnet:


Pod A (10.0.0.10) → "dest is 10.0.0.11, that's my own subnet" → direct delivery

```sh
Pod A (10.0.0.10) → "dest is 10.0.0.11, that's my own subnet" → direct delivery
```
### Default gateway (anything outside the subnet):
When a pod talks to the internet or another VNet/subnet, it goes through the subnet's default gateway:
```sh
Pod A (10.0.0.10) → api.stripe.com
  → default gateway (first IP of subnet, e.g., 10.0.0.1)
  → Azure routes it out → NAT gateway/LB → internet
```  

### Routes vs Policy Routing Rules
A route says: "To reach IP X, send the packet out interface Y via gateway Z."
```sh
10.0.1.5/32 dev eni-veth-aaa scope link       ← "to reach 10.0.1.5, use veth eni-veth-aaa"
0.0.0.0/0 via 10.0.1.1 dev eth1               ← "for everything else, go via gateway 10.0.1.1 out eth1"
```
Routes live inside a specific route table. Linux has multiple route tables — main (254), local, and custom ones 

A rule says: "If the packet matches condition C, look up routes in table T."

```sh
from 10.0.1.5/32 lookup table 3               ← "if packet is FROM 10.0.1.5, use table 3 for routing"
to 10.0.1.5/32   lookup main                  ← "if packet is TO 10.0.1.5, use the main table"
```
Rules are evaluated in priority order (lowest number = highest priority). They act as a dispatcher that selects which route table to use before any route lookup happens.

```sh
Packet arrives
     │
     ▼
┌─────────────────────────────────────┐
│  POLICY RULES (ip rule list)        │  ← "Which table do I check?"
│                                     │
│  Priority 512:  to 10.0.1.5/32      │──→ lookup main
│  Priority 512:  to 10.0.1.6/32      │──→ lookup main
│  Priority 1024: from 10.0.1.5/32    │──→ lookup table 3
│  Priority 1024: from 10.0.1.6/32    │──→ lookup table 3
│  Priority 32766: (default)          │──→ lookup main
└─────────────────────────────────────┘
                    │
                    │ selected table
                    ▼
┌─────────────────────────────────────┐
│  ROUTE TABLE (ip route show table X)│  ← "How do I get there?"
│                                     │
│  Table main:                        │
│    10.0.1.5/32 dev eni-veth-aaa     │
│    10.0.1.6/32 dev eni-veth-bbb     │
│    10.0.0.0/16 dev eth0             │
│                                     │
│  Table 3 (ENI-1):                   │
│    10.0.1.1/32 dev eth1 scope link  │
│    0.0.0.0/0 via 10.0.1.1 dev eth1  │
└─────────────────────────────────────┘
```
#### Why This Matters — The Asymmetric Routing Problem
Without policy rules, all traffic would use the main route table, which sends everything out eth0 (the primary ENI). But the pod's IP 10.0.1.5 belongs to eth1 (ENI-1). If a reply goes out eth0 with source 10.0.1.5, the VPC will drop it — the source/destination check sees an IP that doesn't belong to that ENI.

Policy rules solve this by creating asymmetric routing paths:
```sh
INBOUND (to pod):
  Rule: "to 10.0.1.5/32 → lookup main"
  Main table: "10.0.1.5/32 dev eni-veth-aaa"
  → Delivered to pod via its veth ✓

OUTBOUND (from pod):
  Rule: "from 10.0.1.5/32 → lookup table 3"
  Table 3: "0.0.0.0/0 via 10.0.1.1 dev eth1"
  → Exits via ENI-1 (where 10.0.1.5 actually belongs) ✓
```
If there were no `from` rule, the reply would go out via `eth0` (the primary ENI's default route in `main`), and the VPC would drop it because `10.0.1.5` isn't assigned to `eth0`  

Policy routing adds a layer before the route lookup. You can have multiple routing tables and rules that pick which table to use based on source IP, interface, firewall mark, or anything else:
```sh
$ ip rule list

0:     from all lookup local
32766: from all lookup main        ← the normal routing table
32767: from all lookup default
```

### Three Things Are Created Per Pod(AWS VPC CNI)
- One Route (in the main table)
`ip route add 10.0.1.5/32 dev eni-veth-aaa scope link table main`
- One "toContainer" Policy Rule
`ip rule add to 10.0.1.5/32 priority 512 lookup main`
This ensures inbound packets hit the main table (where the /32 veth route lives) before any ENI-specific table could intercept them.
- One "fromContainer" Policy Rule (only for secondary ENIs)
`ip rule add from 10.0.1.5/32 priority 1024 lookup 3`
This ensures outbound traffic exits through the correct ENI. This rule is skipped for pods on the primary ENI (where rtTable == main), because the main table's default route already goes out the primary ENI

### 3 Pods on a Node
```sh
Pod A: 10.0.1.5  from ENI-1 (table 3)
Pod B: 10.0.1.6  from ENI-1 (table 3)
Pod C: 10.0.0.20 from primary ENI (table main)
```
After all three pods are created, the host has:
```sh
# ip rule list (policy rules)
  0:     from all lookup local
  512:   to 10.0.1.5/32 lookup main        ← Pod A inbound
  512:   to 10.0.1.6/32 lookup main        ← Pod B inbound
  512:   to 10.0.0.20/32 lookup main       ← Pod C inbound
  1024:  from 10.0.1.5/32 lookup 3         ← Pod A outbound → ENI-1
  1024:  from 10.0.1.6/32 lookup 3         ← Pod B outbound → ENI-1
                                            (no "from" rule for Pod C —
                                             it's on primary ENI, main table
                                             default route already exits via eth0)
  32766: from all lookup main

# ip route show table main (routes)
  10.0.1.5/32 dev eni-veth-aaa scope link  ← Pod A's veth
  10.0.1.6/32 dev eni-veth-bbb scope link  ← Pod B's veth
  10.0.0.20/32 dev eni-veth-ccc scope link ← Pod C's veth
  10.0.0.0/16 dev eth0 ...                 ← VPC subnet

# ip route show table 3 (ENI-1's routes — set up once when ENI attached)
  10.0.1.1/32 dev eth1 scope link          ← gateway
  0.0.0.0/0 via 10.0.1.1 dev eth1         ← default route out ENI-1
```

### 
```sh
sudo iptables -L -n -v --line-numbers
Chain INPUT (policy ACCEPT 0 packets, 0 bytes)
num   pkts bytes target     prot opt in     out     source               destination         

Chain FORWARD (policy ACCEPT 0 packets, 0 bytes)
num   pkts bytes target     prot opt in     out     source               destination         

Chain OUTPUT (policy ACCEPT 0 packets, 0 bytes)
num   pkts bytes target     prot opt in     out     source               destination         
```

```sh
sudo apt install docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
```
```sh
 sudo iptables -L -n -v --line-numbers
Chain INPUT (policy ACCEPT 0 packets, 0 bytes)
num   pkts bytes target     prot opt in     out     source               destination         

Chain FORWARD (policy DROP 0 packets, 0 bytes)
num   pkts bytes target     prot opt in     out     source               destination         
1        0     0 DOCKER-USER  0    --  *      *       0.0.0.0/0            0.0.0.0/0           
2        0     0 DOCKER-FORWARD  0    --  *      *       0.0.0.0/0            0.0.0.0/0           

Chain OUTPUT (policy ACCEPT 0 packets, 0 bytes)
num   pkts bytes target     prot opt in     out     source               destination         

Chain DOCKER (1 references)
num   pkts bytes target     prot opt in     out     source               destination         
1        0     0 DROP       0    --  !docker0 docker0  0.0.0.0/0            0.0.0.0/0           

Chain DOCKER-BRIDGE (1 references)
num   pkts bytes target     prot opt in     out     source               destination         
1        0     0 DOCKER     0    --  *      docker0  0.0.0.0/0            0.0.0.0/0           

Chain DOCKER-CT (1 references)
num   pkts bytes target     prot opt in     out     source               destination         
1        0     0 ACCEPT     0    --  *      docker0  0.0.0.0/0            0.0.0.0/0            ctstate RELATED,ESTABLISHED

Chain DOCKER-FORWARD (1 references)
num   pkts bytes target     prot opt in     out     source               destination         
1        0     0 DOCKER-CT  0    --  *      *       0.0.0.0/0            0.0.0.0/0           
2        0     0 DOCKER-INTERNAL  0    --  *      *       0.0.0.0/0            0.0.0.0/0           
3        0     0 DOCKER-BRIDGE  0    --  *      *       0.0.0.0/0            0.0.0.0/0           
4        0     0 ACCEPT     0    --  docker0 *       0.0.0.0/0            0.0.0.0/0           

Chain DOCKER-INTERNAL (1 references)
num   pkts bytes target     prot opt in     out     source               destination         

Chain DOCKER-USER (1 references)
num   pkts bytes target     prot opt in     out     source               destination  
```
```sh
# 1. Start a container with a published port
docker run -d -p 8080:80 --name test-nginx nginx:alpine

# 2. Check iptables again
sudo iptables -L -n -v --line-numbers
```

```sh
sudo iptables -L -n -v --line-numbers
Chain INPUT (policy ACCEPT 0 packets, 0 bytes)
num   pkts bytes target     prot opt in     out     source               destination         

Chain FORWARD (policy DROP 0 packets, 0 bytes)
num   pkts bytes target     prot opt in     out     source               destination         
1        0     0 DOCKER-USER  0    --  *      *       0.0.0.0/0            0.0.0.0/0           
2        0     0 DOCKER-FORWARD  0    --  *      *       0.0.0.0/0            0.0.0.0/0           

Chain OUTPUT (policy ACCEPT 0 packets, 0 bytes)
num   pkts bytes target     prot opt in     out     source               destination         

Chain DOCKER (1 references)
num   pkts bytes target     prot opt in     out     source               destination         
1        0     0 ACCEPT     6    --  !docker0 docker0  0.0.0.0/0            172.17.0.2           tcp dpt:80
2        0     0 DROP       0    --  !docker0 docker0  0.0.0.0/0            0.0.0.0/0           

Chain DOCKER-BRIDGE (1 references)
num   pkts bytes target     prot opt in     out     source               destination         
1        0     0 DOCKER     0    --  *      docker0  0.0.0.0/0            0.0.0.0/0           

Chain DOCKER-CT (1 references)
num   pkts bytes target     prot opt in     out     source               destination         
1        0     0 ACCEPT     0    --  *      docker0  0.0.0.0/0            0.0.0.0/0            ctstate RELATED,ESTABLISHED

Chain DOCKER-FORWARD (1 references)
num   pkts bytes target     prot opt in     out     source               destination         
1        0     0 DOCKER-CT  0    --  *      *       0.0.0.0/0            0.0.0.0/0           
2        0     0 DOCKER-INTERNAL  0    --  *      *       0.0.0.0/0            0.0.0.0/0           
3        0     0 DOCKER-BRIDGE  0    --  *      *       0.0.0.0/0            0.0.0.0/0           
4        0     0 ACCEPT     0    --  docker0 *       0.0.0.0/0            0.0.0.0/0           

Chain DOCKER-INTERNAL (1 references)
num   pkts bytes target     prot opt in     out     source               destination         

Chain DOCKER-USER (1 references)
num   pkts bytes target     prot opt in     out     source               destination         
```
- Rule 1 (new): ACCEPT TCP traffic coming from NOT docker0 (!docker0), going TO docker0, destined for `172.17.0.2` port 80
- Rule 2 (existing): DROP everything else from outside → docker0. This is the default deny.

```sh
sudo iptables -t nat -L -n -v --line-numbers
Chain PREROUTING (policy ACCEPT 1 packets, 357 bytes)
num   pkts bytes target     prot opt in     out     source               destination         
1        0     0 DOCKER     0    --  *      *       0.0.0.0/0            0.0.0.0/0            ADDRTYPE match dst-type LOCAL

Chain INPUT (policy ACCEPT 0 packets, 0 bytes)
num   pkts bytes target     prot opt in     out     source               destination         

Chain OUTPUT (policy ACCEPT 108 packets, 9562 bytes)
num   pkts bytes target     prot opt in     out     source               destination         
1        0     0 DOCKER     0    --  *      *       0.0.0.0/0           !127.0.0.0/8          ADDRTYPE match dst-type LOCAL

Chain POSTROUTING (policy ACCEPT 108 packets, 9562 bytes)
num   pkts bytes target     prot opt in     out     source               destination         
1        0     0 MASQUERADE  0    --  *      !docker0  172.17.0.0/16        0.0.0.0/0           

Chain DOCKER (2 references)
num   pkts bytes target     prot opt in     out     source               destination         
1        0     0 DNAT       6    --  !docker0 *       0.0.0.0/0            0.0.0.0/0            tcp dpt:8080 to:172.17.0.2:80
```
POSTROUTING (nat table):

```sh
Rule 1: MASQUERADE — if source is 172.17.0.0/16 and NOT going to docker0
         → rewrite source IP to the host's IP
```
This is SNAT for containers going to the internet. When your container calls an external API, its source IP (172.17.0.2) gets replaced with your host's IP.

```sh
# 1. Create a deployment
kubectl create deployment test-web --image=nginx --port=80

# 2. Expose it as a ClusterIP service
kubectl expose deployment test-web --port=80 --target-port=80

# 3. Wait for the pod to be ready
kubectl wait --for=condition=ready pod -l app=test-web --timeout=60s

# 4. Check the service IP
kubectl get svc test-web

# 5. SSH into the minikube node and check iptables
minikube ssh -- sudo iptables -t nat -L -n -v | grep -A5 "KUBE-SVC\|KUBE-SEP\|KUBE-SERVICES"
```

```sh
 minikube ssh -- sudo iptables -t nat -L -n -v 
Chain PREROUTING (policy ACCEPT 37 packets, 2960 bytes)
 pkts bytes target     prot opt in     out     source               destination         
    9   591 KUBE-SERVICES  0    --  *      *       0.0.0.0/0            0.0.0.0/0            /* kubernetes service portals */
    1    85 DOCKER_OUTPUT  0    --  *      *       0.0.0.0/0            192.168.49.1        
    7   420 DOCKER     0    --  *      *       0.0.0.0/0            0.0.0.0/0            ADDRTYPE match dst-type LOCAL

Chain INPUT (policy ACCEPT 29 packets, 1765 bytes)
 pkts bytes target     prot opt in     out     source               destination         

Chain OUTPUT (policy ACCEPT 385 packets, 23040 bytes)
 pkts bytes target     prot opt in     out     source               destination         
  192 11500 KUBE-SERVICES  0    --  *      *       0.0.0.0/0            0.0.0.0/0            /* kubernetes service portals */
    1    72 DOCKER_OUTPUT  0    --  *      *       0.0.0.0/0            192.168.49.1        
  100  6000 DOCKER     0    --  *      *       0.0.0.0/0           !127.0.0.0/8          ADDRTYPE match dst-type LOCAL

Chain POSTROUTING (policy ACCEPT 386 packets, 23112 bytes)
 pkts bytes target     prot opt in     out     source               destination         
  192 11500 KUBE-POSTROUTING  0    --  *      *       0.0.0.0/0            0.0.0.0/0            /* kubernetes postrouting rules */
    0     0 MASQUERADE  0    --  *      !docker0  172.17.0.0/16        0.0.0.0/0           
    0     0 DOCKER_POSTROUTING  0    --  *      *       0.0.0.0/0            192.168.49.1        
    0     0 CNI-df77da144ac555db33299d5a  0    --  *      *       10.244.0.2           0.0.0.0/0            /* name: "bridge" id: "d6ca5f785951531d05f39be0729b4857046a2b05f1c9ef572cd6b9b12389e3bf" */

Chain CNI-df77da144ac555db33299d5a (1 references)
 pkts bytes target     prot opt in     out     source               destination         
    0     0 ACCEPT     0    --  *      *       0.0.0.0/0            10.244.0.0/16        /* name: "bridge" id: "d6ca5f785951531d05f39be0729b4857046a2b05f1c9ef572cd6b9b12389e3bf" */
    0     0 MASQUERADE  0    --  *      *       0.0.0.0/0           !224.0.0.0/4          /* name: "bridge" id: "d6ca5f785951531d05f39be0729b4857046a2b05f1c9ef572cd6b9b12389e3bf" */

Chain DOCKER (2 references)
 pkts bytes target     prot opt in     out     source               destination         

Chain DOCKER_OUTPUT (2 references)
 pkts bytes target     prot opt in     out     source               destination         
    0     0 DNAT       6    --  *      *       0.0.0.0/0            192.168.49.1         tcp dpt:53 to:127.0.0.11:39807
    2   157 DNAT       17   --  *      *       0.0.0.0/0            192.168.49.1         udp dpt:53 to:127.0.0.11:55756

Chain DOCKER_POSTROUTING (1 references)
 pkts bytes target     prot opt in     out     source               destination         
    0     0 SNAT       6    --  *      *       127.0.0.11           0.0.0.0/0            tcp spt:39807 to:192.168.49.1:53
    0     0 SNAT       17   --  *      *       127.0.0.11           0.0.0.0/0            udp spt:55756 to:192.168.49.1:53

Chain KUBE-KUBELET-CANARY (0 references)
 pkts bytes target     prot opt in     out     source               destination         

Chain KUBE-MARK-MASQ (8 references)
 pkts bytes target     prot opt in     out     source               destination         
    2   120 MARK       0    --  *      *       0.0.0.0/0            0.0.0.0/0            MARK or 0x4000

Chain KUBE-NODEPORTS (1 references)
 pkts bytes target     prot opt in     out     source               destination         

Chain KUBE-POSTROUTING (1 references)
 pkts bytes target     prot opt in     out     source               destination         
  185 11100 RETURN     0    --  *      *       0.0.0.0/0            0.0.0.0/0            mark match ! 0x4000/0x4000
    1    60 MARK       0    --  *      *       0.0.0.0/0            0.0.0.0/0            MARK xor 0x4000
    1    60 MASQUERADE  0    --  *      *       0.0.0.0/0            0.0.0.0/0            /* kubernetes service traffic requiring SNAT */ random-fully

Chain KUBE-PROXY-CANARY (0 references)
 pkts bytes target     prot opt in     out     source               destination         

Chain KUBE-SEP-IT2ZTR26TO4XFPTO (1 references)
 pkts bytes target     prot opt in     out     source               destination         
    0     0 KUBE-MARK-MASQ  0    --  *      *       10.244.0.2           0.0.0.0/0            /* kube-system/kube-dns:dns-tcp */
    0     0 DNAT       6    --  *      *       0.0.0.0/0            0.0.0.0/0            /* kube-system/kube-dns:dns-tcp */ tcp to:10.244.0.2:53

Chain KUBE-SEP-N4G2XR5TDX7PQE7P (1 references)
 pkts bytes target     prot opt in     out     source               destination         
    0     0 KUBE-MARK-MASQ  0    --  *      *       10.244.0.2           0.0.0.0/0            /* kube-system/kube-dns:metrics */
    0     0 DNAT       6    --  *      *       0.0.0.0/0            0.0.0.0/0            /* kube-system/kube-dns:metrics */ tcp to:10.244.0.2:9153

Chain KUBE-SEP-VPILYQBSPPXYB66K (1 references)
 pkts bytes target     prot opt in     out     source               destination         
    1    60 KUBE-MARK-MASQ  0    --  *      *       192.168.49.2         0.0.0.0/0            /* default/kubernetes:https */
    4   240 DNAT       6    --  *      *       0.0.0.0/0            0.0.0.0/0            /* default/kubernetes:https */ tcp to:192.168.49.2:8443

Chain KUBE-SEP-YIL6JZP7A3QYXJU2 (1 references)
 pkts bytes target     prot opt in     out     source               destination         
    0     0 KUBE-MARK-MASQ  0    --  *      *       10.244.0.2           0.0.0.0/0            /* kube-system/kube-dns:dns */
    0     0 DNAT       17   --  *      *       0.0.0.0/0            0.0.0.0/0            /* kube-system/kube-dns:dns */ udp to:10.244.0.2:53

Chain KUBE-SERVICES (2 references)
 pkts bytes target     prot opt in     out     source               destination         
    1    60 KUBE-SVC-NPX46M4PTMTKRN6Y  6    --  *      *       0.0.0.0/0            10.96.0.1            /* default/kubernetes:https cluster IP */ tcp dpt:443
    0     0 KUBE-SVC-ERIFXISQEP7F7OF4  6    --  *      *       0.0.0.0/0            10.96.0.10           /* kube-system/kube-dns:dns-tcp cluster IP */ tcp dpt:53
    0     0 KUBE-SVC-JD5MR3NA4I4DYORP  6    --  *      *       0.0.0.0/0            10.96.0.10           /* kube-system/kube-dns:metrics cluster IP */ tcp dpt:9153
    0     0 KUBE-SVC-TCOU7JCQXEZGVUNU  17   --  *      *       0.0.0.0/0            10.96.0.10           /* kube-system/kube-dns:dns cluster IP */ udp dpt:53
  183 10980 KUBE-NODEPORTS  0    --  *      *       0.0.0.0/0            0.0.0.0/0            /* kubernetes service nodeports; NOTE: this must be the last rule in this chain */ ADDRTYPE match dst-type LOCAL

Chain KUBE-SVC-ERIFXISQEP7F7OF4 (1 references)
 pkts bytes target     prot opt in     out     source               destination         
    0     0 KUBE-MARK-MASQ  6    --  *      *      !10.244.0.0/16        10.96.0.10           /* kube-system/kube-dns:dns-tcp cluster IP */ tcp dpt:53
    0     0 KUBE-SEP-IT2ZTR26TO4XFPTO  0    --  *      *       0.0.0.0/0            0.0.0.0/0            /* kube-system/kube-dns:dns-tcp -> 10.244.0.2:53 */

Chain KUBE-SVC-JD5MR3NA4I4DYORP (1 references)
 pkts bytes target     prot opt in     out     source               destination         
    0     0 KUBE-MARK-MASQ  6    --  *      *      !10.244.0.0/16        10.96.0.10           /* kube-system/kube-dns:metrics cluster IP */ tcp dpt:9153
    0     0 KUBE-SEP-N4G2XR5TDX7PQE7P  0    --  *      *       0.0.0.0/0            0.0.0.0/0            /* kube-system/kube-dns:metrics -> 10.244.0.2:9153 */

Chain KUBE-SVC-NPX46M4PTMTKRN6Y (1 references)
 pkts bytes target     prot opt in     out     source               destination         
    1    60 KUBE-MARK-MASQ  6    --  *      *      !10.244.0.0/16        10.96.0.1            /* default/kubernetes:https cluster IP */ tcp dpt:443
    4   240 KUBE-SEP-VPILYQBSPPXYB66K  0    --  *      *       0.0.0.0/0            0.0.0.0/0            /* default/kubernetes:https -> 192.168.49.2:8443 */

Chain KUBE-SVC-TCOU7JCQXEZGVUNU (1 references)
 pkts bytes target     prot opt in     out     source               destination         
    0     0 KUBE-MARK-MASQ  17   --  *      *      !10.244.0.0/16        10.96.0.10           /* kube-system/kube-dns:dns cluster IP */ udp dpt:53
    0     0 KUBE-SEP-YIL6JZP7A3QYXJU2  0    --  *      *       0.0.0.0/0            0.0.0.0/0            /* kube-system/kube-dns:dns -> 10.244.0.2:53 */
```

### Pod calls the API server (kubernetes service)

A pod hits https://kubernetes.default.svc:443 → resolves to 10.96.0.1:443
```sh
KUBE-SERVICES chain:
  → match: dest 10.96.0.1, tcp dpt:443
  → jump to KUBE-SVC-NPX46M4PTMTKRN6Y

KUBE-SVC-NPX46M4PTMTKRN6Y:
  Rule 1: KUBE-MARK-MASQ if source is NOT 10.244.0.0/16
          "if traffic came from outside the pod network, mark it for SNAT"
  Rule 2: → KUBE-SEP-VPILYQBSPPXYB66K

KUBE-SEP-VPILYQBSPPXYB66K:
  Rule 1: KUBE-MARK-MASQ if source is 192.168.49.2
          "if the API server calls itself, mark for SNAT (hairpin)"
  Rule 2: DNAT → 192.168.49.2:8443
          "rewrite destination to the actual API server"
```
Result: 10.96.0.1:443 → rewritten to → 192.168.49.2:8443 (the minikube node's real IP, port 8443)          

### Pod does a DNS lookup (kube-dns service)

A pod queries DNS → goes to 10.96.0.10:53 (UDP)
```sh
KUBE-SERVICES:
  → match: dest 10.96.0.10, udp dpt:53
  → jump to KUBE-SVC-TCOU7JCQXEZGVUNU

KUBE-SVC-TCOU7JCQXEZGVUNU:
  Rule 1: KUBE-MARK-MASQ if source NOT 10.244.0.0/16
  Rule 2: → KUBE-SEP-YIL6JZP7A3QYXJU2

KUBE-SEP-YIL6JZP7A3QYXJU2:
  Rule 2: DNAT udp → 10.244.0.2:53
 ```
 Result: 10.96.0.10:53 → rewritten to → 10.244.0.2:53 (the CoreDNS pod)

The pattern for every service is the same 3-layer chain:
 ```sh
 KUBE-SERVICES          "which service?"
  │                     matches ClusterIP + port
  ▼
KUBE-SVC-xxxxx         "which pod?" (load balancing)
  │                     if multiple pods, uses random probability here
  ▼
KUBE-SEP-xxxxx         "DNAT to pod IP:port"
                        the actual rewrite
```                         

```sh
kubectl create deployment test-web --image=nginx --port=80
kubectl expose deployment test-web --port=80
# wait for pod, then check again
minikube ssh -- sudo iptables -t nat -L -n
```

```sh
minikube ssh -- sudo iptables -t nat -L -n
Chain PREROUTING (policy ACCEPT)
target     prot opt source               destination         
KUBE-SERVICES  0    --  0.0.0.0/0            0.0.0.0/0            /* kubernetes service portals */
DOCKER_OUTPUT  0    --  0.0.0.0/0            192.168.49.1        
DOCKER     0    --  0.0.0.0/0            0.0.0.0/0            ADDRTYPE match dst-type LOCAL

Chain INPUT (policy ACCEPT)
target     prot opt source               destination         

Chain OUTPUT (policy ACCEPT)
target     prot opt source               destination         
KUBE-SERVICES  0    --  0.0.0.0/0            0.0.0.0/0            /* kubernetes service portals */
DOCKER_OUTPUT  0    --  0.0.0.0/0            192.168.49.1        
DOCKER     0    --  0.0.0.0/0           !127.0.0.0/8          ADDRTYPE match dst-type LOCAL

Chain POSTROUTING (policy ACCEPT)
target     prot opt source               destination         
KUBE-POSTROUTING  0    --  0.0.0.0/0            0.0.0.0/0            /* kubernetes postrouting rules */
MASQUERADE  0    --  172.17.0.0/16        0.0.0.0/0           
DOCKER_POSTROUTING  0    --  0.0.0.0/0            192.168.49.1        
CNI-df77da144ac555db33299d5a  0    --  10.244.0.2           0.0.0.0/0            /* name: "bridge" id: "d6ca5f785951531d05f39be0729b4857046a2b05f1c9ef572cd6b9b12389e3bf" */
CNI-0dd802bbb3396f98c8422ee4  0    --  10.244.0.3           0.0.0.0/0            /* name: "bridge" id: "91fde1c97fbe2a4ac689400da9b9b185fd6ba62e8c5a12fc3a01c245de6aa76e" */

Chain CNI-0dd802bbb3396f98c8422ee4 (1 references)
target     prot opt source               destination         
ACCEPT     0    --  0.0.0.0/0            10.244.0.0/16        /* name: "bridge" id: "91fde1c97fbe2a4ac689400da9b9b185fd6ba62e8c5a12fc3a01c245de6aa76e" */
MASQUERADE  0    --  0.0.0.0/0           !224.0.0.0/4          /* name: "bridge" id: "91fde1c97fbe2a4ac689400da9b9b185fd6ba62e8c5a12fc3a01c245de6aa76e" */

Chain CNI-df77da144ac555db33299d5a (1 references)
target     prot opt source               destination         
ACCEPT     0    --  0.0.0.0/0            10.244.0.0/16        /* name: "bridge" id: "d6ca5f785951531d05f39be0729b4857046a2b05f1c9ef572cd6b9b12389e3bf" */
MASQUERADE  0    --  0.0.0.0/0           !224.0.0.0/4          /* name: "bridge" id: "d6ca5f785951531d05f39be0729b4857046a2b05f1c9ef572cd6b9b12389e3bf" */

Chain DOCKER (2 references)
target     prot opt source               destination         

Chain DOCKER_OUTPUT (2 references)
target     prot opt source               destination         
DNAT       6    --  0.0.0.0/0            192.168.49.1         tcp dpt:53 to:127.0.0.11:39807
DNAT       17   --  0.0.0.0/0            192.168.49.1         udp dpt:53 to:127.0.0.11:55756

Chain DOCKER_POSTROUTING (1 references)
target     prot opt source               destination         
SNAT       6    --  127.0.0.11           0.0.0.0/0            tcp spt:39807 to:192.168.49.1:53
SNAT       17   --  127.0.0.11           0.0.0.0/0            udp spt:55756 to:192.168.49.1:53

Chain KUBE-KUBELET-CANARY (0 references)
target     prot opt source               destination         

Chain KUBE-MARK-MASQ (10 references)
target     prot opt source               destination         
MARK       0    --  0.0.0.0/0            0.0.0.0/0            MARK or 0x4000

Chain KUBE-NODEPORTS (1 references)
target     prot opt source               destination         

Chain KUBE-POSTROUTING (1 references)
target     prot opt source               destination         
RETURN     0    --  0.0.0.0/0            0.0.0.0/0            mark match ! 0x4000/0x4000
MARK       0    --  0.0.0.0/0            0.0.0.0/0            MARK xor 0x4000
MASQUERADE  0    --  0.0.0.0/0            0.0.0.0/0            /* kubernetes service traffic requiring SNAT */ random-fully

Chain KUBE-PROXY-CANARY (0 references)
target     prot opt source               destination         

Chain KUBE-SEP-IT2ZTR26TO4XFPTO (1 references)
target     prot opt source               destination         
KUBE-MARK-MASQ  0    --  10.244.0.2           0.0.0.0/0            /* kube-system/kube-dns:dns-tcp */
DNAT       6    --  0.0.0.0/0            0.0.0.0/0            /* kube-system/kube-dns:dns-tcp */ tcp to:10.244.0.2:53

Chain KUBE-SEP-N4G2XR5TDX7PQE7P (1 references)
target     prot opt source               destination         
KUBE-MARK-MASQ  0    --  10.244.0.2           0.0.0.0/0            /* kube-system/kube-dns:metrics */
DNAT       6    --  0.0.0.0/0            0.0.0.0/0            /* kube-system/kube-dns:metrics */ tcp to:10.244.0.2:9153

Chain KUBE-SEP-NDLNAREGU56CZHXZ (1 references)
target     prot opt source               destination         
KUBE-MARK-MASQ  0    --  10.244.0.3           0.0.0.0/0            /* default/test-web */
DNAT       6    --  0.0.0.0/0            0.0.0.0/0            /* default/test-web */ tcp to:10.244.0.3:80

Chain KUBE-SEP-VPILYQBSPPXYB66K (1 references)
target     prot opt source               destination         
KUBE-MARK-MASQ  0    --  192.168.49.2         0.0.0.0/0            /* default/kubernetes:https */
DNAT       6    --  0.0.0.0/0            0.0.0.0/0            /* default/kubernetes:https */ tcp to:192.168.49.2:8443

Chain KUBE-SEP-YIL6JZP7A3QYXJU2 (1 references)
target     prot opt source               destination         
KUBE-MARK-MASQ  0    --  10.244.0.2           0.0.0.0/0            /* kube-system/kube-dns:dns */
DNAT       17   --  0.0.0.0/0            0.0.0.0/0            /* kube-system/kube-dns:dns */ udp to:10.244.0.2:53

Chain KUBE-SERVICES (2 references)
target     prot opt source               destination         
KUBE-SVC-6AULZBW7RT7ROKZY  6    --  0.0.0.0/0            10.104.38.36         /* default/test-web cluster IP */ tcp dpt:80
KUBE-SVC-NPX46M4PTMTKRN6Y  6    --  0.0.0.0/0            10.96.0.1            /* default/kubernetes:https cluster IP */ tcp dpt:443
KUBE-SVC-ERIFXISQEP7F7OF4  6    --  0.0.0.0/0            10.96.0.10           /* kube-system/kube-dns:dns-tcp cluster IP */ tcp dpt:53
KUBE-SVC-JD5MR3NA4I4DYORP  6    --  0.0.0.0/0            10.96.0.10           /* kube-system/kube-dns:metrics cluster IP */ tcp dpt:9153
KUBE-SVC-TCOU7JCQXEZGVUNU  17   --  0.0.0.0/0            10.96.0.10           /* kube-system/kube-dns:dns cluster IP */ udp dpt:53
KUBE-NODEPORTS  0    --  0.0.0.0/0            0.0.0.0/0            /* kubernetes service nodeports; NOTE: this must be the last rule in this chain */ ADDRTYPE match dst-type LOCAL

Chain KUBE-SVC-6AULZBW7RT7ROKZY (1 references)
target     prot opt source               destination         
KUBE-MARK-MASQ  6    -- !10.244.0.0/16        10.104.38.36         /* default/test-web cluster IP */ tcp dpt:80
KUBE-SEP-NDLNAREGU56CZHXZ  0    --  0.0.0.0/0            0.0.0.0/0            /* default/test-web -> 10.244.0.3:80 */

Chain KUBE-SVC-ERIFXISQEP7F7OF4 (1 references)
target     prot opt source               destination         
KUBE-MARK-MASQ  6    -- !10.244.0.0/16        10.96.0.10           /* kube-system/kube-dns:dns-tcp cluster IP */ tcp dpt:53
KUBE-SEP-IT2ZTR26TO4XFPTO  0    --  0.0.0.0/0            0.0.0.0/0            /* kube-system/kube-dns:dns-tcp -> 10.244.0.2:53 */

Chain KUBE-SVC-JD5MR3NA4I4DYORP (1 references)
target     prot opt source               destination         
KUBE-MARK-MASQ  6    -- !10.244.0.0/16        10.96.0.10           /* kube-system/kube-dns:metrics cluster IP */ tcp dpt:9153
KUBE-SEP-N4G2XR5TDX7PQE7P  0    --  0.0.0.0/0            0.0.0.0/0            /* kube-system/kube-dns:metrics -> 10.244.0.2:9153 */

Chain KUBE-SVC-NPX46M4PTMTKRN6Y (1 references)
target     prot opt source               destination         
KUBE-MARK-MASQ  6    -- !10.244.0.0/16        10.96.0.1            /* default/kubernetes:https cluster IP */ tcp dpt:443
KUBE-SEP-VPILYQBSPPXYB66K  0    --  0.0.0.0/0            0.0.0.0/0            /* default/kubernetes:https -> 192.168.49.2:8443 */

Chain KUBE-SVC-TCOU7JCQXEZGVUNU (1 references)
target     prot opt source               destination         
KUBE-MARK-MASQ  17   -- !10.244.0.0/16        10.96.0.10           /* kube-system/kube-dns:dns cluster IP */ udp dpt:53
KUBE-SEP-YIL6JZP7A3QYXJU2  0    --  0.0.0.0/0            0.0.0.0/0            /* kube-system/kube-dns:dns -> 10.244.0.2:53 */
```

`New entry in KUBE-SERVICES`
```sh
KUBE-SVC-6AULZBW7RT7ROKZY  tcp  →  10.104.38.36:80  /* default/test-web */
```
Your service got ClusterIP 10.104.38.36.

New KUBE-SVC chain:
```sh
Chain KUBE-SVC-6AULZBW7RT7ROKZY:
  Rule 1: KUBE-MARK-MASQ if source NOT 10.244.0.0/16  (mark external traffic for SNAT)
  Rule 2: → KUBE-SEP-NDLNAREGU56CZHXZ               (send to the pod)
```

New KUBE-SEP chain:
```sh
Chain KUBE-SEP-NDLNAREGU56CZHXZ:
  Rule 1: KUBE-MARK-MASQ if source is 10.244.0.3      (hairpin — pod calling itself)
  Rule 2: DNAT tcp → 10.244.0.3:80                    (rewrite dest to pod IP)
```
New CNI chain in POSTROUTING:
```sh
CNI-0dd802bbb3396f98c8422ee4  src 10.244.0.3   ← SNAT rules for the new pod
  ACCEPT if dest is 10.244.0.0/16               ← pod→pod: no SNAT needed
  MASQUERADE if dest is not multicast            ← pod→outside: SNAT
 ```
 The full trace for curl `10.104.38.36:80`:
 ```sh 
 Packet: src=10.244.0.2 dst=10.104.38.36:80
  │
  ▼ KUBE-SERVICES
  match: 10.104.38.36 tcp:80 → KUBE-SVC-6AULZBW7RT7ROKZY
  │
  ▼ KUBE-SVC-6AULZBW7RT7ROKZY
  source is 10.244.0.2 (in 10.244.0.0/16) → skip MASQ mark
  → KUBE-SEP-NDLNAREGU56CZHXZ
  │
  ▼ KUBE-SEP-NDLNAREGU56CZHXZ
  DNAT → 10.244.0.3:80
  │
  ▼ Packet delivered to nginx pod at 10.244.0.3:80
```

```sh
kubectl scale deployment test-web --replicas=3
# Wait for pods
kubectl get pods -o wide
# Check again
minikube ssh -- sudo iptables -t nat -L KUBE-SVC-6AULZBW7RT7ROKZY -n
```
```sh
minikube ssh -- sudo iptables -t nat -L KUBE-SVC-6AULZBW7RT7ROKZY -n
Chain KUBE-SVC-6AULZBW7RT7ROKZY (1 references)
target     prot opt source               destination         
KUBE-MARK-MASQ  6    -- !10.244.0.0/16        10.104.38.36         /* default/test-web cluster IP */ tcp dpt:80
KUBE-SEP-NDLNAREGU56CZHXZ  0    --  0.0.0.0/0            0.0.0.0/0            /* default/test-web -> 10.244.0.3:80 */ statistic mode random probability 0.33333333349
KUBE-SEP-MWISH3ISRBAXW42X  0    --  0.0.0.0/0            0.0.0.0/0            /* default/test-web -> 10.244.0.4:80 */ statistic mode random probability 0.50000000000
KUBE-SEP-QLZV4KYPBTYB6TXX  0    --  0.0.0.0/0            0.0.0.0/0            /* default/test-web -> 10.244.0.5:80 */
```
You'll see the `KUBE-SVC-6AULZBW7RT7ROKZY` chain gain 3 `KUBE-SEP` entries with probability rules like `statistic mode random probability` 0.33333, 0.50000, and the last one with no probability (catches the rest). That's iptables doing round-robin load balancing.

The rules are evaluated top-down. Each rule either matches (and stops) or falls through:
```sh
Packet arrives
  │
  ├─ Rule 1: 33.3% chance → KUBE-SEP (Pod 10.244.0.3)    ← 1/3
  │  (miss: 66.7% fall through)
  │
  ├─ Rule 2: 50% chance → KUBE-SEP (Pod 10.244.0.4)       ← 50% of 66.7% = 1/3
  │  (miss: 50% fall through)
  │
  └─ Rule 3: no probability → KUBE-SEP (Pod 10.244.0.5)   ← catches the rest = 1/3
```  
```sh
Pod 1:  33.3%                          = 1/3
Pod 2:  66.7% × 50%                    = 1/3
Pod 3:  66.7% × 50%                    = 1/3
                                  Total: 3/3 ✓
 ```
 If you scaled to 4 replicas, you'd see: `0.25`, `0.33`, `0.50`, (none):     
 ```sh
Pod 1:  25%                            = 1/4
Pod 2:  75% × 33.3%                    = 1/4
Pod 3:  75% × 66.7% × 50%             = 1/4
Pod 4:  remainder                      = 1/4
```
The pattern is always `1/n`, `1/(n-1)`, `1/(n-2)`, ..., `1/1` (no annotation on the last).

This is why people say iptables doesn't scale well for services — with 1000 pods, that's 1000 rules evaluated linearly for every single packet. IPVS uses a hash table instead, so lookup is O(1) regardless of pod count.                            
When a packet hits KUBE-SVC-xxx, iptables checks each rule one by one, top to bottom:
```sh
3 pods → 3 rules to check:
  Rule 1: is random < 0.33? → Pod 1
  Rule 2: is random < 0.50? → Pod 2
  Rule 3: → Pod 3
  Fast. No problem.

1000 pods → 1000 rules to check:
  Rule 1:    is random < 0.001? → Pod 1
  Rule 2:    is random < 0.001? → Pod 2
  Rule 3:    is random < 0.001? → Pod 3
  ...
  Rule 999:  is random < 0.50?  → Pod 999
  Rule 1000: → Pod 1000
  
  If the packet lands on Pod 1000, the kernel checked 999 rules
  before reaching it. For EVERY packet.
```

```sh
minikube ssh -- sudo iptables -t filter -L -n
Chain INPUT (policy ACCEPT)
target     prot opt source               destination         
KUBE-PROXY-FIREWALL  0    --  0.0.0.0/0            0.0.0.0/0            ctstate NEW /* kubernetes load balancer firewall */
KUBE-NODEPORTS  0    --  0.0.0.0/0            0.0.0.0/0            /* kubernetes health check service ports */
KUBE-EXTERNAL-SERVICES  0    --  0.0.0.0/0            0.0.0.0/0            ctstate NEW /* kubernetes externally-visible service portals */
KUBE-FIREWALL  0    --  0.0.0.0/0            0.0.0.0/0           

Chain FORWARD (policy ACCEPT)
target     prot opt source               destination         
KUBE-PROXY-FIREWALL  0    --  0.0.0.0/0            0.0.0.0/0            ctstate NEW /* kubernetes load balancer firewall */
KUBE-FORWARD  0    --  0.0.0.0/0            0.0.0.0/0            /* kubernetes forwarding rules */
KUBE-SERVICES  0    --  0.0.0.0/0            0.0.0.0/0            ctstate NEW /* kubernetes service portals */
KUBE-EXTERNAL-SERVICES  0    --  0.0.0.0/0            0.0.0.0/0            ctstate NEW /* kubernetes externally-visible service portals */
CNI-FORWARD  0    --  0.0.0.0/0            0.0.0.0/0            /* CNI firewall plugin rules */
DOCKER-USER  0    --  0.0.0.0/0            0.0.0.0/0           
DOCKER-FORWARD  0    --  0.0.0.0/0            0.0.0.0/0           

Chain OUTPUT (policy ACCEPT)
target     prot opt source               destination         
KUBE-PROXY-FIREWALL  0    --  0.0.0.0/0            0.0.0.0/0            ctstate NEW /* kubernetes load balancer firewall */
KUBE-SERVICES  0    --  0.0.0.0/0            0.0.0.0/0            ctstate NEW /* kubernetes service portals */
KUBE-FIREWALL  0    --  0.0.0.0/0            0.0.0.0/0           

Chain CNI-ADMIN (1 references)
target     prot opt source               destination         

Chain CNI-FORWARD (1 references)
target     prot opt source               destination         
CNI-ADMIN  0    --  0.0.0.0/0            0.0.0.0/0            /* CNI firewall plugin admin overrides */
ACCEPT     0    --  0.0.0.0/0            10.244.0.2           ctstate RELATED,ESTABLISHED
ACCEPT     0    --  10.244.0.2           0.0.0.0/0           
ACCEPT     0    --  0.0.0.0/0            10.244.0.3           ctstate RELATED,ESTABLISHED
ACCEPT     0    --  10.244.0.3           0.0.0.0/0           
ACCEPT     0    --  0.0.0.0/0            10.244.0.5           ctstate RELATED,ESTABLISHED
ACCEPT     0    --  0.0.0.0/0            10.244.0.4           ctstate RELATED,ESTABLISHED
ACCEPT     0    --  10.244.0.5           0.0.0.0/0           
ACCEPT     0    --  10.244.0.4           0.0.0.0/0           

Chain DOCKER (1 references)
target     prot opt source               destination         
DROP       0    --  0.0.0.0/0            0.0.0.0/0           

Chain DOCKER-BRIDGE (1 references)
target     prot opt source               destination         
DOCKER     0    --  0.0.0.0/0            0.0.0.0/0           

Chain DOCKER-CT (1 references)
target     prot opt source               destination         
ACCEPT     0    --  0.0.0.0/0            0.0.0.0/0            ctstate RELATED,ESTABLISHED

Chain DOCKER-FORWARD (1 references)
target     prot opt source               destination         
DOCKER-CT  0    --  0.0.0.0/0            0.0.0.0/0           
DOCKER-INTERNAL  0    --  0.0.0.0/0            0.0.0.0/0           
DOCKER-BRIDGE  0    --  0.0.0.0/0            0.0.0.0/0           
ACCEPT     0    --  0.0.0.0/0            0.0.0.0/0           

Chain DOCKER-INTERNAL (1 references)
target     prot opt source               destination         

Chain DOCKER-USER (1 references)
target     prot opt source               destination         

Chain KUBE-EXTERNAL-SERVICES (2 references)
target     prot opt source               destination         

Chain KUBE-FIREWALL (2 references)
target     prot opt source               destination         
DROP       0    -- !127.0.0.0/8          127.0.0.0/8          /* block incoming localnet connections */ ! ctstate RELATED,ESTABLISHED,DNAT

Chain KUBE-FORWARD (1 references)
target     prot opt source               destination         
DROP       0    --  0.0.0.0/0            0.0.0.0/0            ctstate INVALID nfacct-name  ct_state_invalid_dropped_pkts
ACCEPT     0    --  0.0.0.0/0            0.0.0.0/0            /* kubernetes forwarding rules */ mark match 0x4000/0x4000
ACCEPT     0    --  0.0.0.0/0            0.0.0.0/0            /* kubernetes forwarding conntrack rule */ ctstate RELATED,ESTABLISHED

Chain KUBE-KUBELET-CANARY (0 references)
target     prot opt source               destination         

Chain KUBE-NODEPORTS (1 references)
target     prot opt source               destination         

Chain KUBE-PROXY-CANARY (0 references)
target     prot opt source               destination         

Chain KUBE-PROXY-FIREWALL (3 references)
target     prot opt source               destination         

Chain KUBE-SERVICES (2 references)
target     prot opt source               destination         
```

### Ingress and Gateway
They solve the same problem: how does external traffic get into your cluster?
```sh
                    Internet
                       │
        ┌──────────────┼──────────────┐
        ▼              ▼              ▼
   LB ($$$)       LB ($$$)       LB ($$$)
   api.example    pay.example    web.example
        │              │              │
    api-svc        pay-svc        web-svc
```
3 services = 3 load balancers = 3 public IPs = 3× the cost.
Ingress controller / Gateway solves this — one load balancer, many services:
```sh
                    Internet
                       │
                       ▼
                   ONE LB ($)
                       │
                       ▼
              ┌─────────────────┐
              │ Ingress Controller│
              │   or Gateway    │
              │   (nginx, envoy)│
              └────┬────┬────┬──┘
                   │    │    │
   api.example.com/    │    pay.example.com/
        │              │              │
    api-svc        web-svc        pay-svc
```      
It's a reverse proxy running inside your cluster. It receives all external traffic on one entry point and routes it to the right service based on hostname, path, headers, etc.
```sh
1. User hits https://api.example.com/users
2. DNS resolves to the load balancer's public IP
3. LB forwards to the ingress controller pod (NodePort or similar)
4. Ingress controller reads the Ingress/HTTPRoute rules
5. Matches: host=api.example.com, path=/users → api-svc
6. Forwards the request to api-svc pods
```
#### What the ingress controller / gateway actually IS:

It's just nginx, envoy, HAProxy, or a cloud-native proxy running as a pod in your cluster. It watches Ingress or HTTPRoute objects in the API server and reconfigures itself whenever they change:
```sh
┌──────────────────────────────┐
│  Ingress Controller Pod      │
│                              │
│  1. Watches API server       │
│     for Ingress/HTTPRoute    │
│                              │
│  2. Generates nginx.conf     │
│     or envoy config           │
│                              │
│  3. Proxies traffic based     │
│     on the rules             │
└──────────────────────────────┘
```   
```sh
                        Internet
                            │
                  ┌─────────┴─────────┐
                  │                   │
              INBOUND             OUTBOUND
              traffic              traffic
                  │                   │
          App Gateway /           NAT Gateway
          Ingress Controller          │
                  │                   │
                  ▼                   ▼
          "How does traffic      "How does traffic
           GET IN to pods?"       GET OUT from pods?"
```
Azure Application Gateway / ALB is the ingress controller.
```sh
Option 1: Self-managed (you run nginx in your cluster)

    Internet → Azure LB → nginx pod (in cluster) → services
                           ^^^^^^^^^^^^^^^^^^^^
                           YOU manage this

Option 2: Cloud-managed (Azure does it for you)

    Internet → Azure App Gateway → services (directly)
               ^^^^^^^^^^^^^^^^^^^^
               AZURE manages this
```                          
#### Two teams, one cluster.
Team A runs the API. Team B runs the payment service. Both need example.com with different paths.

```yaml
apiVersion: networking.k8s.io/v1
kind: Ingress
metadata:
  name: main-ingress
  namespace: ???              # whose namespace does this live in?
  annotations:
    nginx.ingress.kubernetes.io/rate-limit: "100"   # Team A wants 100
    # Team B wants 500 — TOO BAD, annotations are global
    nginx.ingress.kubernetes.io/timeout: "30s"      # Team A wants 30s
    # Team B wants 120s — CAN'T, one value per Ingress
spec:
  rules:
    - host: example.com
      http:
        paths:
          - path: /api          # Team A adds this
            backend:
              service:
                name: api-svc
          - path: /payments     # Team B adds this
            backend:
              service:
                name: pay-svc
```
Problems:
- Who owns this file? Both teams need to edit it → merge conflicts, stepping on each other
- Annotations are per-Ingress, not per-path. Team A wants rate-limit 100, Team B wants 500 — impossible. One setting applies to everything.
- Namespace? The Ingress has to live in one namespace but reference services in both — that doesn't even work. Services must be in the same namespace as the Ingress                

#### Separate Ingress per team:
```yaml
# Team A's Ingress (namespace: team-a)
kind: Ingress
metadata:
  name: api-ingress
  namespace: team-a
spec:
  rules:
    - host: example.com
      http:
        paths:
          - path: /api
            backend:
              service:
                name: api-svc
---
# Team B's Ingress (namespace: team-b)
kind: Ingress
metadata:
  name: pay-ingress
  namespace: team-b
spec:
  rules:
    - host: example.com
      http:
        paths:
          - path: /payments
            backend:
              service:
                name: pay-svc
```
But now:
- Two Ingresses claim the same host example.com — which one wins? It depends on the controller. Some merge them, some pick one randomly, some error.
- The platform team has no control over who can claim which hostname. Team C could create an Ingress for example.com/api and hijack Team A's traffic.
- There's no way for a platform admin to say "team-a can only use api.example.com"           

### With Gateway API — clean separation:

Platform team creates the Gateway (once):
```yaml
kind: Gateway
metadata:
  name: main
  namespace: infra
spec:
  listeners:
    - name: https
      port: 443
      hostname: "*.example.com"
      allowedRoutes:
        namespaces:
          from: Selector
          selector:
            matchLabels:
              gateway: "true"     # only labeled namespaces can attach
```
Team A creates their route (in their namespace):
```yaml
kind: HTTPRoute
metadata:
  name: api-route
  namespace: team-a
spec:
  parentRefs:
    - name: main
      namespace: infra
  hostnames: ["api.example.com"]
  rules:
    - matches:
        - path: { type: PathPrefix, value: /api }
      filters:
        - type: RequestHeaderModifier    # team A's own settings
          requestHeaderModifier:
            set: [{ name: x-rate-limit, value: "100" }]
      backendRefs:
        - name: api-svc
          port: 80
```
Team B creates their route (in their namespace):
```yaml
kind: HTTPRoute
metadata:
  name: pay-route
  namespace: team-b
spec:
  parentRefs:
    - name: main
      namespace: infra
  hostnames: ["pay.example.com"]
  rules:
    - matches:
        - path: { type: PathPrefix, value: /payments }
      backendRefs:
        - name: pay-svc
          port: 80
          weight: 90
        - name: pay-svc-v2
          port: 80
          weight: 10            # canary — Team B's choice, doesn't affect Team A
```
