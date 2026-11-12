# The control plane's static Pod manifests

On a kubeadm-style cluster such as minikube, the four control-plane components are **static Pods**. kubeadm writes their Pod manifests to a folder on the control-plane node once, at install, and the **kubelet** runs them straight from disk, with no API server, scheduler or etcd involved. See [deployments-and-replicasets.md](deployments-and-replicasets.md) §8.2 for why, and for how the mirror Pods appear in `kubectl get pods`.

Related: [etcd-storage.md](etcd-storage.md) (what etcd does with the data) ·
[secrets-and-env.md](secrets-and-env.md) §5, §7 (encryption at rest, ServiceAccount tokens) ·
[watching-the-api-server.md](watching-the-api-server.md) (who talks to the API server)

---

## 0. Where the files are

**(lab)**

```
$ sudo grep staticPodPath /var/lib/kubelet/config.yaml
staticPodPath: /etc/kubernetes/manifests

$ sudo ls -la /etc/kubernetes/manifests/
-rw------- 1 root root 2655 … etcd.yaml
-rw------- 1 root root 4171 … kube-apiserver.yaml
-rw------- 1 root root 3483 … kube-controller-manager.yaml
-rw------- 1 root root 1727 … kube-scheduler.yaml
```

The files are readable only by root. They hold flags and certificate **paths**, not key material: a search for `BEGIN`, `token=` and `password` across all four finds nothing **(lab)**.

```
 /etc/kubernetes/manifests/         kubelet (staticPodPath)          kubectl get pods -n kube-system
 ├─ etcd.yaml                 ──►   starts containers directly  ──►  etcd-minikube                   (mirror Pod)
 ├─ kube-apiserver.yaml       ──►   restarts them if they die   ──►  kube-apiserver-minikube         OWNER: Node
 ├─ kube-controller-manager.yaml                                ──►  kube-controller-manager-minikube SOURCE: file
 └─ kube-scheduler.yaml                                         ──►  kube-scheduler-minikube
```

---

## 0.1 Who writes them: kubeadm, even on minikube

minikube doesn't build the control plane itself. It starts a node (here a Docker container), puts the Kubernetes binaries in it, writes a kubeadm config file, and runs `kubeadm init`. kubeadm then writes the four static Pod manifests, the certificates and the kubeconfigs.

**1. minikube ships kubeadm with the other binaries.** **(lab)**

```
$ ls /var/lib/minikube/binaries/v1.34.0/
kubeadm  kubectl  kubelet
$ /var/lib/minikube/binaries/v1.34.0/kubeadm version -o short
v1.34.0
```

**2. minikube writes a kubeadm config file, then runs kubeadm with it.** **(lab)** `/var/tmp/minikube/kubeadm.yaml`, written when the cluster was created, at the same time as the manifests. Shortened here, with arrows to where each value ends up:

```yaml
apiVersion: kubeadm.k8s.io/v1beta4
kind: InitConfiguration
localAPIEndpoint:
  advertiseAddress: 192.168.49.2          # → --advertise-address in kube-apiserver.yaml
  bindPort: 8443                          # → --secure-port=8443 (kubeadm's own default is 6443)
bootstrapTokens:
  - groups: [system:bootstrappers:kubeadm:default-node-token]
    ttl: 24h0m0s
    usages: [signing, authentication]     # → the bootstrap-token-… Secret in kube-system
nodeRegistration:
  criSocket: unix:///var/run/cri-dockerd.sock
  name: "minikube"
---
apiVersion: kubeadm.k8s.io/v1beta4
kind: ClusterConfiguration
apiServer:
  certSANs: ["127.0.0.1", "localhost", "192.168.49.2"]
  extraArgs:
    - name: "enable-admission-plugins"
      value: "NamespaceLifecycle,…,ResourceQuota"     # → the API server flag
controllerManager:
  extraArgs:
    - {name: "allocate-node-cidrs", value: "true"}
    - {name: "leader-elect",        value: "false"}  # → why there are no leader Leases
scheduler:
  extraArgs:
    - {name: "leader-elect", value: "false"}
certificatesDir: /var/lib/minikube/certs              # → every cert path in the manifests
clusterName: mk                                       # → --cluster-name=mk
controlPlaneEndpoint: control-plane.minikube.internal:8443
etcd:
  local: {dataDir: /var/lib/minikube/etcd}            # → etcd --data-dir
kubernetesVersion: v1.34.0                            # → image tags :v1.34.0
networking:
  dnsDomain: cluster.local
  podSubnet: "10.244.0.0/16"                          # → --cluster-cidr
  serviceSubnet: 10.96.0.0/12                         # → --service-cluster-ip-range
---
apiVersion: kubelet.config.k8s.io/v1beta1
kind: KubeletConfiguration
containerRuntimeEndpoint: unix:///var/run/cri-dockerd.sock
staticPodPath: /etc/kubernetes/manifests              # → where the kubelet looks for those files
---
apiVersion: kubeproxy.config.k8s.io/v1alpha1
kind: KubeProxyConfiguration
clusterCIDR: "10.244.0.0/16"
```

Almost every value in the four manifests traces back to a line in this file. That's the relationship:

```
minikube start
   │ writes /var/tmp/minikube/kubeadm.yaml   (InitConfiguration, ClusterConfiguration,
   │                                           KubeletConfiguration, KubeProxyConfiguration)
   ▼
kubeadm init --config /var/tmp/minikube/kubeadm.yaml
   ├─ certificates      → /var/lib/minikube/certs/        (ca, apiserver, etcd, sa.key …)
   ├─ kubeconfigs       → /etc/kubernetes/*.conf          (scheduler.conf, controller-manager.conf …)
   ├─ static Pod files  → /etc/kubernetes/manifests/      (the four YAMLs)
   ├─ kubelet config    → /var/lib/kubelet/config.yaml    (staticPodPath …)
   └─ once the API is up: CoreDNS Deployment, kube-proxy DaemonSet, bootstrap token,
                          kubeadm-config ConfigMap
minikube then applies its CNI manifest (/var/tmp/minikube/cni.yaml → Calico here)
```

**3. kubeadm leaves its own records in the cluster.** **(lab)** The `kubeadm-config` ConfigMap in `kube-system` holds the same `ClusterConfiguration`, with kubeadm's defaults filled in. That's how a later `kubeadm join` or `kubeadm upgrade` knows how the cluster was set up:

```
caCertificateValidityPeriod: 87600h0m0s     # CA valid 10 years
certificateValidityPeriod: 8760h0m0s        # other certs valid 1 year
encryptionAlgorithm: RSA-2048
```

The API server Pod also carries kubeadm's annotation `kubeadm.kubernetes.io/kube-apiserver.advertise-address.endpoint: 192.168.49.2:8443`.

## 1. `etcd.yaml`

**(lab)** From `/etc/kubernetes/manifests/etcd.yaml` on the minikube node:

```yaml
apiVersion: v1
kind: Pod
metadata:
  annotations:
    kubeadm.kubernetes.io/etcd.advertise-client-urls: https://192.168.49.2:2379
  labels:
    component: etcd
    tier: control-plane
  name: etcd
  namespace: kube-system
spec:
  containers:
  - command:
    - etcd
    - --advertise-client-urls=https://192.168.49.2:2379
    - --cert-file=/var/lib/minikube/certs/etcd/server.crt
    - --client-cert-auth=true
    - --data-dir=/var/lib/minikube/etcd
    - --feature-gates=InitialCorruptCheck=true
    - --initial-advertise-peer-urls=https://192.168.49.2:2380
    - --initial-cluster=minikube=https://192.168.49.2:2380
    - --key-file=/var/lib/minikube/certs/etcd/server.key
    - --listen-client-urls=https://127.0.0.1:2379,https://192.168.49.2:2379
    - --listen-metrics-urls=http://127.0.0.1:2381
    - --listen-peer-urls=https://192.168.49.2:2380
    - --name=minikube
    - --peer-cert-file=/var/lib/minikube/certs/etcd/peer.crt
    - --peer-client-cert-auth=true
    - --peer-key-file=/var/lib/minikube/certs/etcd/peer.key
    - --peer-trusted-ca-file=/var/lib/minikube/certs/etcd/ca.crt
    - --snapshot-count=10000
    - --trusted-ca-file=/var/lib/minikube/certs/etcd/ca.crt
    - --watch-progress-notify-interval=5s
    image: registry.k8s.io/etcd:3.6.4-0
    imagePullPolicy: IfNotPresent
    livenessProbe:
      failureThreshold: 8
      httpGet:
        host: 127.0.0.1
        path: /livez
        port: probe-port
        scheme: HTTP
      initialDelaySeconds: 10
      periodSeconds: 10
      timeoutSeconds: 15
    name: etcd
    ports:
    - containerPort: 2381
      name: probe-port
      protocol: TCP
    readinessProbe:
      failureThreshold: 3
      httpGet:
        host: 127.0.0.1
        path: /readyz
        port: probe-port
        scheme: HTTP
      periodSeconds: 1
      timeoutSeconds: 15
    resources:
      requests:
        cpu: 100m
        memory: 100Mi
    startupProbe:
      failureThreshold: 24
      httpGet:
        host: 127.0.0.1
        path: /readyz
        port: probe-port
        scheme: HTTP
      initialDelaySeconds: 10
      periodSeconds: 10
      timeoutSeconds: 15
    volumeMounts:
    - mountPath: /var/lib/minikube/etcd
      name: etcd-data
    - mountPath: /var/lib/minikube/certs/etcd
      name: etcd-certs
  hostNetwork: true
  priority: 2000001000
  priorityClassName: system-node-critical
  securityContext:
    seccompProfile:
      type: RuntimeDefault
  volumes:
  - hostPath:
      path: /var/lib/minikube/certs/etcd
      type: DirectoryOrCreate
    name: etcd-certs
  - hostPath:
      path: /var/lib/minikube/etcd
      type: DirectoryOrCreate
    name: etcd-data
status: {}
```

| Flag | What it does |
|---|---|
| `--data-dir=/var/lib/minikube/etcd` | where the WAL, raft snapshots and the bbolt `db` file live ([etcd-storage.md](etcd-storage.md) §1). Mounted from the node via the `etcd-data` hostPath, so data survives container restarts |
| `--listen-client-urls=https://127.0.0.1:2379,https://192.168.49.2:2379` | port **2379** for clients (the API server) |
| `--listen-peer-urls=https://192.168.49.2:2380` | port **2380** for other etcd members (raft); unused with one member |
| `--initial-cluster=minikube=https://192.168.49.2:2380` | the member list at bootstrap: just this one |
| `--client-cert-auth=true`, `--trusted-ca-file=…/etcd/ca.crt` | **clients must present a certificate signed by etcd's CA.** This is why `etcdctl` needs `--cert`/`--key` |
| `--cert-file`, `--key-file` | etcd's own server certificate |
| `--peer-*` | the same, for member-to-member traffic |
| `--listen-metrics-urls=http://127.0.0.1:2381` | plain-HTTP metrics and health on **2381**, localhost only. The probes use this port (`probe-port`) |
| `--snapshot-count=10000` | raft snapshot every 10,000 entries; deprecated in etcd 3.6 ([etcd-storage.md](etcd-storage.md) §1.1) |
| `--watch-progress-notify-interval=5s` | etcd tells idle watchers "nothing changed up to revision R" every 5 s, so the API server's watch cache stays current |
| `--feature-gates=InitialCorruptCheck=true` | checks data consistency with peers at startup |

---

## 2. `kube-apiserver.yaml`

**(lab)** From `/etc/kubernetes/manifests/kube-apiserver.yaml`:

```yaml
apiVersion: v1
kind: Pod
metadata:
  annotations:
    kubeadm.kubernetes.io/kube-apiserver.advertise-address.endpoint: 192.168.49.2:8443
  labels:
    component: kube-apiserver
    tier: control-plane
  name: kube-apiserver
  namespace: kube-system
spec:
  containers:
  - command:
    - kube-apiserver
    - --advertise-address=192.168.49.2
    - --allow-privileged=true
    - --authorization-mode=Node,RBAC
    - --client-ca-file=/var/lib/minikube/certs/ca.crt
    - --enable-admission-plugins=NamespaceLifecycle,LimitRanger,ServiceAccount,DefaultStorageClass,DefaultTolerationSeconds,NodeRestriction,MutatingAdmissionWebhook,ValidatingAdmissionWebhook,ResourceQuota
    - --enable-bootstrap-token-auth=true
    - --etcd-cafile=/var/lib/minikube/certs/etcd/ca.crt
    - --etcd-certfile=/var/lib/minikube/certs/apiserver-etcd-client.crt
    - --etcd-keyfile=/var/lib/minikube/certs/apiserver-etcd-client.key
    - --etcd-servers=https://127.0.0.1:2379
    - --kubelet-client-certificate=/var/lib/minikube/certs/apiserver-kubelet-client.crt
    - --kubelet-client-key=/var/lib/minikube/certs/apiserver-kubelet-client.key
    - --kubelet-preferred-address-types=InternalIP,ExternalIP,Hostname
    - --proxy-client-cert-file=/var/lib/minikube/certs/front-proxy-client.crt
    - --proxy-client-key-file=/var/lib/minikube/certs/front-proxy-client.key
    - --requestheader-allowed-names=front-proxy-client
    - --requestheader-client-ca-file=/var/lib/minikube/certs/front-proxy-ca.crt
    - --requestheader-extra-headers-prefix=X-Remote-Extra-
    - --requestheader-group-headers=X-Remote-Group
    - --requestheader-username-headers=X-Remote-User
    - --secure-port=8443
    - --service-account-issuer=https://kubernetes.default.svc.cluster.local
    - --service-account-key-file=/var/lib/minikube/certs/sa.pub
    - --service-account-signing-key-file=/var/lib/minikube/certs/sa.key
    - --service-cluster-ip-range=10.96.0.0/12
    - --tls-cert-file=/var/lib/minikube/certs/apiserver.crt
    - --tls-private-key-file=/var/lib/minikube/certs/apiserver.key
    image: registry.k8s.io/kube-apiserver:v1.34.0
    imagePullPolicy: IfNotPresent
    livenessProbe:
      failureThreshold: 8
      httpGet:
        host: 192.168.49.2
        path: /livez
        port: probe-port
        scheme: HTTPS
      initialDelaySeconds: 10
      periodSeconds: 10
      timeoutSeconds: 15
    name: kube-apiserver
    ports:
    - containerPort: 8443
      name: probe-port
      protocol: TCP
    readinessProbe:
      failureThreshold: 3
      httpGet:
        host: 192.168.49.2
        path: /readyz
        port: probe-port
        scheme: HTTPS
      periodSeconds: 1
      timeoutSeconds: 15
    resources:
      requests:
        cpu: 250m
    startupProbe:
      failureThreshold: 24
      httpGet:
        host: 192.168.49.2
        path: /livez
        port: probe-port
        scheme: HTTPS
      initialDelaySeconds: 10
      periodSeconds: 10
      timeoutSeconds: 15
    volumeMounts:
    - mountPath: /etc/ssl/certs
      name: ca-certs
      readOnly: true
    - mountPath: /etc/ca-certificates
      name: etc-ca-certificates
      readOnly: true
    - mountPath: /var/lib/minikube/certs
      name: k8s-certs
      readOnly: true
    - mountPath: /usr/local/share/ca-certificates
      name: usr-local-share-ca-certificates
      readOnly: true
    - mountPath: /usr/share/ca-certificates
      name: usr-share-ca-certificates
      readOnly: true
  hostNetwork: true
  priority: 2000001000
  priorityClassName: system-node-critical
  securityContext:
    seccompProfile:
      type: RuntimeDefault
  volumes:
  - hostPath:
      path: /etc/ssl/certs
      type: DirectoryOrCreate
    name: ca-certs
  - hostPath:
      path: /etc/ca-certificates
      type: DirectoryOrCreate
    name: etc-ca-certificates
  - hostPath:
      path: /var/lib/minikube/certs
      type: DirectoryOrCreate
    name: k8s-certs
  - hostPath:
      path: /usr/local/share/ca-certificates
      type: DirectoryOrCreate
    name: usr-local-share-ca-certificates
  - hostPath:
      path: /usr/share/ca-certificates
      type: DirectoryOrCreate
    name: usr-share-ca-certificates
status: {}
```

| Flag | What it does |
|---|---|
| `--secure-port=8443`, `--tls-cert-file`, `--tls-private-key-file` | the HTTPS port everyone talks to (minikube uses 8443; kubeadm's default is 6443) and its server certificate |
| `--advertise-address=192.168.49.2` | the IP published as the endpoint of the `kubernetes` Service in `default` (via `/registry/masterleases/`, [etcd-storage.md](etcd-storage.md) §5) |
| `--etcd-servers=https://127.0.0.1:2379` | **the only component that talks to etcd.** Uses its own client certificate (`apiserver-etcd-client.crt`), trusted by etcd's CA |
| `--client-ca-file=…/ca.crt` | client certificates signed by this CA are accepted as users (kubelets, controller-manager, scheduler, your kubeconfig) |
| `--authorization-mode=Node,RBAC` | the **Node** authorizer limits each kubelet to its own node's objects; **RBAC** handles everyone else |
| `--enable-admission-plugins=…` | built-in admission plugins turned on. `ServiceAccount` adds the token volume to every Pod; `NodeRestriction` stops kubelets editing other nodes; `DefaultStorageClass` fills in `storageClassName`; the two webhook plugins allow admission webhooks |
| `--enable-bootstrap-token-auth=true` | accept `bootstrap.kubernetes.io/token` Secrets as credentials, for `kubeadm join` ([secrets-and-env.md](secrets-and-env.md) §2) |
| `--kubelet-client-certificate`, `--kubelet-client-key`, `--kubelet-preferred-address-types` | how the API server connects **to** kubelets, for `kubectl logs`, `exec` and `port-forward` |
| `--service-account-signing-key-file=…/sa.key` | signs ServiceAccount tokens (the JWTs in Pods) |
| `--service-account-key-file=…/sa.pub` | verifies them |
| `--service-account-issuer=https://kubernetes.default.svc.cluster.local` | the `iss` claim in those tokens ([secrets-and-env.md](secrets-and-env.md) §7) |
| `--service-cluster-ip-range=10.96.0.0/12` | where ClusterIPs come from (`10.96.0.1` for `kubernetes`, `10.96.0.10` for kube-dns) |
| `--requestheader-*`, `--proxy-client-*`, `front-proxy-*` | the **aggregation layer**: when the API server forwards a request to an extension API server (for example metrics-server), it proves itself with `front-proxy-client.crt` and passes the user in `X-Remote-User` / `X-Remote-Group` headers |
| `--allow-privileged=true` | Pods may set `privileged: true` (needed by CNI and CSI node agents) |

No `--encryption-provider-config` flag: **encryption at rest is off** on this cluster. That's why Secrets are readable in etcd ([secrets-and-env.md](secrets-and-env.md) §2, [etcd-storage.md](etcd-storage.md) §3.2). Turning it on means adding that flag and its config file here.

---

## 3. `kube-controller-manager.yaml`

**(lab)** From `/etc/kubernetes/manifests/kube-controller-manager.yaml`:

```yaml
apiVersion: v1
kind: Pod
metadata:
  labels:
    component: kube-controller-manager
    tier: control-plane
  name: kube-controller-manager
  namespace: kube-system
spec:
  containers:
  - command:
    - kube-controller-manager
    - --allocate-node-cidrs=true
    - --authentication-kubeconfig=/etc/kubernetes/controller-manager.conf
    - --authorization-kubeconfig=/etc/kubernetes/controller-manager.conf
    - --bind-address=127.0.0.1
    - --client-ca-file=/var/lib/minikube/certs/ca.crt
    - --cluster-cidr=10.244.0.0/16
    - --cluster-name=mk
    - --cluster-signing-cert-file=/var/lib/minikube/certs/ca.crt
    - --cluster-signing-key-file=/var/lib/minikube/certs/ca.key
    - --controllers=*,bootstrapsigner,tokencleaner
    - --kubeconfig=/etc/kubernetes/controller-manager.conf
    - --leader-elect=false
    - --requestheader-client-ca-file=/var/lib/minikube/certs/front-proxy-ca.crt
    - --root-ca-file=/var/lib/minikube/certs/ca.crt
    - --service-account-private-key-file=/var/lib/minikube/certs/sa.key
    - --service-cluster-ip-range=10.96.0.0/12
    - --use-service-account-credentials=true
    image: registry.k8s.io/kube-controller-manager:v1.34.0
    imagePullPolicy: IfNotPresent
    livenessProbe:
      failureThreshold: 8
      httpGet:
        host: 127.0.0.1
        path: /healthz
        port: probe-port
        scheme: HTTPS
      initialDelaySeconds: 10
      periodSeconds: 10
      timeoutSeconds: 15
    name: kube-controller-manager
    ports:
    - containerPort: 10257
      name: probe-port
      protocol: TCP
    resources:
      requests:
        cpu: 200m
    startupProbe:
      failureThreshold: 24
      httpGet:
        host: 127.0.0.1
        path: /healthz
        port: probe-port
        scheme: HTTPS
      initialDelaySeconds: 10
      periodSeconds: 10
      timeoutSeconds: 15
    volumeMounts:
    - mountPath: /etc/ssl/certs
      name: ca-certs
      readOnly: true
    - mountPath: /etc/ca-certificates
      name: etc-ca-certificates
      readOnly: true
    - mountPath: /usr/libexec/kubernetes/kubelet-plugins/volume/exec
      name: flexvolume-dir
    - mountPath: /var/lib/minikube/certs
      name: k8s-certs
      readOnly: true
    - mountPath: /etc/kubernetes/controller-manager.conf
      name: kubeconfig
      readOnly: true
    - mountPath: /usr/local/share/ca-certificates
      name: usr-local-share-ca-certificates
      readOnly: true
    - mountPath: /usr/share/ca-certificates
      name: usr-share-ca-certificates
      readOnly: true
  hostNetwork: true
  priority: 2000001000
  priorityClassName: system-node-critical
  securityContext:
    seccompProfile:
      type: RuntimeDefault
  volumes:
  - hostPath:
      path: /etc/ssl/certs
      type: DirectoryOrCreate
    name: ca-certs
  - hostPath:
      path: /etc/ca-certificates
      type: DirectoryOrCreate
    name: etc-ca-certificates
  - hostPath:
      path: /usr/libexec/kubernetes/kubelet-plugins/volume/exec
      type: DirectoryOrCreate
    name: flexvolume-dir
  - hostPath:
      path: /var/lib/minikube/certs
      type: DirectoryOrCreate
    name: k8s-certs
  - hostPath:
      path: /etc/kubernetes/controller-manager.conf
      type: FileOrCreate
    name: kubeconfig
  - hostPath:
      path: /usr/local/share/ca-certificates
      type: DirectoryOrCreate
    name: usr-local-share-ca-certificates
  - hostPath:
      path: /usr/share/ca-certificates
      type: DirectoryOrCreate
    name: usr-share-ca-certificates
status: {}
```

| Flag | What it does |
|---|---|
| `--kubeconfig=/etc/kubernetes/controller-manager.conf` | how it reaches the **API server**. It never talks to etcd |
| `--controllers=*,bootstrapsigner,tokencleaner` | run every default controller (Deployment, ReplicaSet, endpoints, namespace, …), plus two that are off by default and needed for `kubeadm join` tokens: `bootstrapsigner` signs the `cluster-info` ConfigMap, `tokencleaner` deletes expired bootstrap token Secrets |
| `--use-service-account-credentials=true` | each controller uses **its own ServiceAccount** (`kube-system/replicaset-controller`, `deployment-controller`, …). That's why those names appear as `request.userInfo.username` in admission policies ([secrets-and-env.md](secrets-and-env.md) §4) |
| `--allocate-node-cidrs=true`, `--cluster-cidr=10.244.0.0/16` | gives each node a slice of `10.244.0.0/16` for its Pod IPs |
| `--service-cluster-ip-range=10.96.0.0/12` | must match the API server's |
| `--cluster-signing-cert-file`, `--cluster-signing-key-file=…/ca.key` | **the cluster CA's private key.** Used to sign approved CertificateSigningRequests, such as new kubelet client certificates |
| `--root-ca-file=…/ca.crt` | the CA certificate published into every namespace as the `kube-root-ca.crt` ConfigMap, which ends up in every Pod at `/var/run/secrets/kubernetes.io/serviceaccount/ca.crt` |
| `--service-account-private-key-file=…/sa.key` | used by the token controller to sign legacy `kubernetes.io/service-account-token` Secrets ([secrets-and-env.md](secrets-and-env.md) §2, example 7) |
| `--leader-elect=false` | with one control-plane node there's nobody to elect against. In HA clusters this is `true`, and a Lease in `kube-system` decides which copy is active |
| `--bind-address=127.0.0.1` | its health/metrics port **10257** listens on localhost only |
| `--cluster-name=mk` | prefix used when naming cloud resources (minikube's "mk"; unused without a cloud provider) |

---

## 4. `kube-scheduler.yaml`

**(lab)** From `/etc/kubernetes/manifests/kube-scheduler.yaml`:

```yaml
apiVersion: v1
kind: Pod
metadata:
  labels:
    component: kube-scheduler
    tier: control-plane
  name: kube-scheduler
  namespace: kube-system
spec:
  containers:
  - command:
    - kube-scheduler
    - --authentication-kubeconfig=/etc/kubernetes/scheduler.conf
    - --authorization-kubeconfig=/etc/kubernetes/scheduler.conf
    - --bind-address=127.0.0.1
    - --kubeconfig=/etc/kubernetes/scheduler.conf
    - --leader-elect=false
    image: registry.k8s.io/kube-scheduler:v1.34.0
    imagePullPolicy: IfNotPresent
    livenessProbe:
      failureThreshold: 8
      httpGet:
        host: 127.0.0.1
        path: /livez
        port: probe-port
        scheme: HTTPS
      initialDelaySeconds: 10
      periodSeconds: 10
      timeoutSeconds: 15
    name: kube-scheduler
    ports:
    - containerPort: 10259
      name: probe-port
      protocol: TCP
    readinessProbe:
      failureThreshold: 3
      httpGet:
        host: 127.0.0.1
        path: /readyz
        port: probe-port
        scheme: HTTPS
      periodSeconds: 1
      timeoutSeconds: 15
    resources:
      requests:
        cpu: 100m
    startupProbe:
      failureThreshold: 24
      httpGet:
        host: 127.0.0.1
        path: /livez
        port: probe-port
        scheme: HTTPS
      initialDelaySeconds: 10
      periodSeconds: 10
      timeoutSeconds: 15
    volumeMounts:
    - mountPath: /etc/kubernetes/scheduler.conf
      name: kubeconfig
      readOnly: true
  hostNetwork: true
  priority: 2000001000
  priorityClassName: system-node-critical
  securityContext:
    seccompProfile:
      type: RuntimeDefault
  volumes:
  - hostPath:
      path: /etc/kubernetes/scheduler.conf
      type: FileOrCreate
    name: kubeconfig
status: {}
```

The smallest of the four: the scheduler only needs to reach the API server.

| Flag | What it does |
|---|---|
| `--kubeconfig=/etc/kubernetes/scheduler.conf` | its credentials and the API server address. Its only volume is this one file |
| `--authentication-kubeconfig`, `--authorization-kubeconfig` | used to check who's calling **its own** health/metrics endpoint |
| `--bind-address=127.0.0.1` | health/metrics on port **10259**, localhost only |
| `--leader-elect=false` | single node, as for the controller-manager |

It watches Pods with no `spec.nodeName`, picks a node, and writes the choice back with a `Binding` (the `bindings` resource, `create` only, from [keycloak-on-kubernetes.md](keycloak-on-kubernetes.md) §0.1).

---

## 5. What all four have in common

| Field | Value | Why |
|---|---|---|
| `kind: Pod` | a plain Pod, no Deployment | there's no API server yet to run a controller |
| `hostNetwork: true` | the Pod uses the **node's** network, not a Pod IP | they start before any CNI exists, so there's no Pod network yet |
| `volumes: hostPath` | certs, kubeconfigs and etcd's data come straight from node folders | there are no Secrets or PVCs yet: those need the API server |
| `priorityClassName: system-node-critical`, `priority: 2000001000` | the highest built-in priority | never evicted to make room for apps |
| `livenessProbe` / `startupProbe` on `/livez`, `/healthz`, `/readyz` | via a named port `probe-port` | the kubelet restarts a hung component by itself |
| `securityContext.seccompProfile: RuntimeDefault` | the container runtime's default syscall filter | basic hardening |
| `image: registry.k8s.io/…:v1.34.0` (etcd: `3.6.4-0`) | pinned to the cluster version | upgrading the cluster means kubeadm rewriting these files with new tags |
| `status: {}` | empty | it's a file on disk. The status lives only on the mirror Pod |

---

## 6. How they connect

```
                      your kubectl, kubelets, CoreDNS, kube-proxy, controllers in Pods …
                                            │  HTTPS :8443, client cert or token
                                            ▼
 ┌──────────────────────────── control-plane node (hostNetwork: all on 192.168.49.2) ───────────────────────┐
 │                                                                                                           │
 │  kube-scheduler ──scheduler.conf──────────►┌──────────────────────┐                                       │
 │   :10259 (127.0.0.1)                        │    kube-apiserver    │──client cert────► etcd                │
 │                                             │    :8443             │  apiserver-etcd-   :2379 clients      │
 │  kube-controller-manager ─controller-──────►│                      │  client.crt        :2380 peers        │
 │   :10257 (127.0.0.1)       manager.conf     │  signs SA tokens     │                    :2381 metrics      │
 │                                             │  with sa.key         │                    data: /var/lib/    │
 │                                             └──────────────────────┘                    minikube/etcd      │
 │                                                    │ apiserver-kubelet-client.crt                         │
 │                                                    ▼                                                      │
 │                                              kubelet :10250 (logs, exec, port-forward)                   │
 └──────────────────────────────────────────────────────────────────────────────────────────────────────────┘
```

- **Only the API server talks to etcd.** The scheduler and controller-manager have no etcd flags at all. They reach the API server through their kubeconfig files and bind their own ports to `127.0.0.1`.
- **Everything is mutual TLS.** etcd demands a client certificate (`--client-cert-auth=true`), and the API server accepts certificates signed by `ca.crt`. The scheduler and controller-manager authenticate with credentials from their kubeconfigs.
- **Two key pairs do the signing:**

  | Key | Held by | Signs |
  |---|---|---|
  | `ca.key` (cluster CA) | controller-manager (`--cluster-signing-key-file`) | client certificates from approved CSRs, such as kubelet certificates |
  | `sa.key` / `sa.pub` | API server (sign with `.key`, verify with `.pub`); controller-manager (legacy token Secrets) | ServiceAccount tokens (JWTs) |

- **The address ranges in the lab come from here:**

  | Flag | Value | Seen as |
  |---|---|---|
  | `--cluster-cidr` (controller-manager) | `10.244.0.0/16` | Pod IPs such as `10.244.120.x` |
  | `--service-cluster-ip-range` (API server and controller-manager) | `10.96.0.0/12` | ClusterIPs such as `10.96.0.1`, `10.96.0.10`, `10.107.59.191` |
  | `--advertise-address` (API server) | `192.168.49.2` | the node IP and the `kubernetes` Service's endpoint |

---

## 7. Changing them

Edit the file on the control-plane node. The kubelet notices the change and recreates that component's container. Editing the mirror Pod through `kubectl` doesn't change the file **(not tested)**.

```bash
minikube ssh
sudo vi /etc/kubernetes/manifests/kube-apiserver.yaml     # add or change a flag
# the kubelet restarts kube-apiserver; watch it come back:
sudo crictl ps --name kube-apiserver        # or: docker ps on the Docker runtime
```

Examples of changes that live in these files:

| Goal | File | Flag |
|---|---|---|
| Encryption at rest for Secrets | `kube-apiserver.yaml` | `--encryption-provider-config=…` plus a hostPath mount for the config ([secrets-and-env.md](secrets-and-env.md) §5) |
| Turn a feature gate on (for example `ImageVolume`) | `kube-apiserver.yaml` and the kubelet config | `--feature-gates=ImageVolume=true` |
| Cap ServiceAccount token lifetime | `kube-apiserver.yaml` | `--service-account-max-token-expiration=48h` ([secrets-and-env.md](secrets-and-env.md) §7) |
| Faster etcd compaction by the API server | `kube-apiserver.yaml` | `--etcd-compaction-interval=…` ([etcd-storage.md](etcd-storage.md) §4.4) |

On minikube, a `minikube start --extra-config=…` or `--feature-gates=…` regenerates these files for you **(not tested)**. On managed clusters (AKS, EKS, GKE) the control plane isn't on your nodes, so there are no such files to edit **(not tested)**.

---

## 8. Three API servers: high availability

A static Pod has no `replicas` field. Each control-plane node has its own copy of the manifest files, and its own kubelet runs one API server from them. **Three API servers means three control-plane nodes.**

**(lab)** The single-node lab has one API server identity Lease, one endpoint behind the `kubernetes` Service, and every client pointing straight at `192.168.49.2:8443`. There's no controller-manager or scheduler Lease at all, because both run with `--leader-elect=false`. Here's how each of those changes with three control-plane nodes:

```
                 clients: kubectl, kubelets, controllers, CoreDNS …
                                    │
                                    ▼
                 LOAD BALANCER  (the "controlPlaneEndpoint", e.g. k8s-api.example.com:6443)
                 ┌──────────────────┼──────────────────┐
                 ▼                  ▼                  ▼
 ┌─ cp-1 ───────────────┐ ┌─ cp-2 ───────────────┐ ┌─ cp-3 ───────────────┐
 │ /etc/kubernetes/      │ │ /etc/kubernetes/      │ │ /etc/kubernetes/      │
 │   manifests/          │ │   manifests/          │ │   manifests/          │
 │ kube-apiserver  ACTIVE│ │ kube-apiserver  ACTIVE│ │ kube-apiserver  ACTIVE│  ← all three serve requests
 │ controller-mgr  leader│ │ controller-mgr standby│ │ controller-mgr standby│  ← only ONE works at a time
 │ scheduler      standby│ │ scheduler       leader│ │ scheduler      standby│  ← only ONE works at a time
 │ etcd member ──────────┼─┼─ etcd member ─────────┼─┼─ etcd member          │  ← one raft cluster, quorum 2 of 3
 └───────────────────────┘ └───────────────────────┘ └───────────────────────┘
```

### 8.1 How each piece knows about the others

**1. The API servers find each other through Leases and the `kubernetes` Service.**

- Each API server writes its own **identity Lease**. **(lab)** One exists today: `apiserver-eqt674mfxb4j56mrjjkoe7b7ii`, labelled `apiserver.kubernetes.io/identity: kube-apiserver` and `kubernetes.io/hostname: minikube`. With 3 nodes there would be 3 of these.
- Each also adds its `--advertise-address` to the endpoints of the `kubernetes` Service in `default`. **(lab)** Today that's just `192.168.49.2`. With 3 there'd be 3 IPs, so Pods calling `kubernetes.default.svc` are spread across all of them. This uses the 15-second `masterleases` key in etcd ([etcd-storage.md](etcd-storage.md) §5).

**2. The controller-manager and scheduler pick one leader through a Lease.** With `--leader-elect=true` (kubeadm's default; minikube sets `false`, even in HA mode, see §8.3), each copy tries to take a Lease named `kube-controller-manager` (or `kube-scheduler`) in `kube-system`. The holder renews it every few seconds. The others watch it, and if renewals stop, one of them takes over.

**3. etcd members are listed in each other's flags.** On one node, the manifest has `--initial-cluster=minikube=https://192.168.49.2:2380`. With three:

```
--initial-cluster=cp-1=https://10.0.0.1:2380,cp-2=https://10.0.0.2:2380,cp-3=https://10.0.0.3:2380
```

That's the shape when all three start together. When members join one at a time (kubeadm join, minikube HA), each node's list only has the members that existed when it joined, plus `--initial-cluster-state=existing`. §8.3 shows this. The flag only matters on first start; after that, membership lives in etcd itself (`etcdctl member list`).

They talk over port **2380** (the `--listen-peer-urls` in §1). Instead of running on the control-plane nodes ("stacked"), etcd can also run on its own separate machines ("external etcd"). Then each API server's `--etcd-servers` lists all three.

**4. Everything else talks to one address, the load balancer.** **(lab)** Today the kubelet, scheduler and controller-manager kubeconfigs all say `server: https://192.168.49.2:8443`, one node's own IP. In HA they point at the load balancer address (kubeadm's `controlPlaneEndpoint`), so losing one control-plane node doesn't break them.

### 8.2 How the extra nodes get their manifests

You don't copy files by hand. kubeadm does it **(not tested)**:

```bash
# first control-plane node
kubeadm init --control-plane-endpoint "k8s-api.example.com:6443" --upload-certs

# each extra control-plane node
kubeadm join k8s-api.example.com:6443 --token … --discovery-token-ca-cert-hash sha256:… \
    --control-plane --certificate-key …
```

`--control-plane` makes kubeadm write the same four manifests into **that** node's `/etc/kubernetes/manifests/`, with its own IP in `--advertise-address`. It also adds the new etcd member and copies the shared certificates (`ca.key`, `sa.key` and so on, uploaded with `--upload-certs`). The join command uses a **bootstrap token**: the `bootstrap.kubernetes.io/token` Secret type from [secrets-and-env.md](secrets-and-env.md) §2.

### 8.3 Lab: a three-control-plane minikube cluster

**(lab)** `minikube start -p ha --ha` (containerd, Kubernetes v1.34.0) creates three control-plane nodes: `ha`, `ha-m02` and `ha-m03`, at `192.168.58.2`, `.3` and `.4`.

**Each node has its own manifest folder, with a fifth file.**

```
ha      etcd.yaml  kube-apiserver.yaml  kube-controller-manager.yaml  kube-scheduler.yaml  kube-vip.yaml
ha-m02  (same five)
ha-m03  (same five)
```

The files only differ in the node's own IP (`--advertise-address=192.168.58.2`, `.3`, `.4`). That's 15 control-plane Pods.

**The load balancer is kube-vip, running as a static Pod.** `kube-vip.yaml` runs on every node and they share one **virtual IP**, `192.168.58.254`:

- `/etc/hosts` maps `control-plane.minikube.internal` to it, and that name is the `controlPlaneEndpoint` in `/var/tmp/minikube/kubeadm.yaml`.
- `admin.conf` uses `https://control-plane.minikube.internal:8443`. The kubelet, scheduler and controller-manager kubeconfigs still point at each node's own IP.
- Only one node holds the VIP at a time, chosen through a Lease named `plndr-cp-lock` (holder `ha`, 5-second duration, `vip_leaderelection: true`). The holder adds `192.168.58.254/32` to its `eth0` and announces it with ARP (`vip_arp: true`).

```
               control-plane.minikube.internal → 192.168.58.254:8443  (VIP)
                                    │
                    held by whichever node owns Lease plndr-cp-lock
                                    ▼
 ┌─ ha  .2 ─────────────┐ ┌─ ha-m02  .3 ─────────┐ ┌─ ha-m03  .4 ─────────┐
 │ kube-vip   VIP holder│ │ kube-vip    standby  │ │ kube-vip    standby  │
 │ kube-apiserver ACTIVE│ │ kube-apiserver ACTIVE│ │ kube-apiserver ACTIVE│
 │ etcd           leader│ │ etcd       follower  │ │ etcd       follower  │
 └──────────────────────┘ └──────────────────────┘ └──────────────────────┘
```

**Three of everything the API servers publish.**

```
Leases:     apiserver-6kuwvy34…  apiserver-7rixuirb…  apiserver-aplgfnpn…   (3600 s each)
            plndr-cp-lock  holder ha  5 s
            (no kube-controller-manager or kube-scheduler Lease)
kubernetes endpoints:  192.168.58.2  192.168.58.3  192.168.58.4
```

**etcd was built one member at a time.**

```
ha      --initial-cluster=ha=https://192.168.58.2:2380
ha-m02  --initial-cluster=ha=…58.2:2380,ha-m02=…58.3:2380                   --initial-cluster-state=existing
ha-m03  --initial-cluster=ha-m03=…58.4:2380,ha=…58.2:2380,ha-m02=…58.3:2380 --initial-cluster-state=existing
```

`etcdctl member list` showed 3 started members and no learners. `ha` was the leader (raft term 2), and all three were at the same raft index.

#### No leader election: the controllers fight

minikube keeps `--leader-elect=false` on the scheduler and controller-manager **in HA mode too**, so all three copies of each are active. A Deployment with 6 replicas:

```
ReplicaSet events:  SuccessfulCreate ×23   SuccessfulDelete ×21
scheduler:          Pods bound by more than one scheduler instance
end state:          6 Pods, RS 6/6/6 after ~30 s
```

Three ReplicaSet controllers each saw "need 6", created Pods, then each saw too many and deleted some. It's the "two controllers, two opinions" fight from [deployments-and-replicasets.md](deployments-and-replicasets.md) §2.3. It settles, but with a lot of churn. This is what `--leader-elect=true` prevents: only the Lease holder acts.

#### Failover: stopping the node with the VIP and the etcd leader

`docker stop ha`, then checked from the surviving nodes:

- **VIP:** moved to `ha-m02` within 5 s, and `plndr-cp-lock` showed holder `ha-m02`.
- **API through the VIP:** `/livez` answered `ok` at every check.
- **etcd:** `.3` and `.4` healthy, `.2` "context deadline exceeded". 2 of 3 is still a majority, so a new leader was elected.
- **Writes:** `kubectl scale deploy w --replicas=3` worked.
- **`kubernetes` endpoints:** dropped to `192.168.58.3`, `192.168.58.4`.
- **API server identity Leases:** still 3, because the dead one's Lease lasts 3600 s.
- **Node:** `ha NotReady`.

The host's kubeconfig was **not** highly available. minikube points it at `127.0.0.1:<port>`, a Docker port mapped to the `ha` container only, so host `kubectl` failed while `ha` was down. Inside the cluster everything used the VIP and kept working. A real setup points clients at the load balancer.

#### Recovery: static Pods need the kubelet

After `docker start ha`, the kubelet was **inactive**. minikube starts it during `minikube start`, and a bare `docker start` skips that. With no kubelet, nothing reads `/etc/kubernetes/manifests/`, so none of the node's control-plane Pods came back.

After `systemctl start kubelet` inside the node, all five static Pods started again. The node went `Ready`, the endpoints went back to 3 IPs, and etcd `.2` rejoined as a **follower**. The leadership didn't move back: `ha-m02` stayed etcd leader (raft term 3, so one election happened) and kept the VIP. Docker also gave the `ha` container a new host port, so the old host kubeconfig was stale until `minikube update-context`.

`minikube update-context -p ha` also switches your **current** kubectl context to `ha`. Switch back with `kubectl config use-context minikube`.

| | kubeadm-style HA (§8 diagram) | minikube `--ha` (lab) |
|---|---|---|
| API servers | 3 active behind a load balancer | 3 active behind the kube-vip VIP |
| etcd | 3 members, survives losing 1 | same; new leader elected, writes kept working |
| controller-manager / scheduler | 1 active, chosen by Lease | all 3 active (`--leader-elect=false`) |
| Client address | load balancer | in the cluster: the VIP; from the host: one node's Docker port |
| Node restart | kubelet restarts the static Pods | same, once the kubelet is running |
