# Untested claims: a checklist for a real-cloud session

Every claim the notes mark as not tested, plus the cloud and identity claims that come only
from vendor documentation. The labs ran on minikube, k3s and local emulators, so these are the
statements a session on a real AWS, Azure or Google Cloud account (or a real cluster) can confirm
or correct. When one is checked, change its label in the note to **(lab)** and delete it here.

Generated on 2026-11-26 by searching the notes for "not tested"/"untested", and for
"(docs" in the cloud notes. Line numbers are as of that date.

## 1. Marked "not tested"

### [azure-identity-flows.md](azure-identity-flows.md)

- [ ] [6.2 Reading the secret (lab)](azure-identity-flows.md#62-reading-the-secret-lab) (line 970): Entra issues the token for the resource in `…/.default` (not tested here). floci-az's Key Vault

### [azure-local-lab.md](azure-local-lab.md)

- [ ] [8. Container Apps](azure-local-lab.md#8-container-apps) (line 762): routes requests by `Host` header (not tested here: it needs the emulator restarted).
- [ ] [10. Azure RBAC (not available in 0.16.0)](azure-local-lab.md#10-azure-rbac-not-available-in-0160) (line 870): It's in floci-az's `main` branch (from its docs, not tested):

### [azure.md](azure.md)

- [ ] [A managed identity is a service principal](azure.md#a-managed-identity-is-a-service-principal) (line 1004): From Microsoft Graph's `servicePrincipal` reference; not tested in a real tenant. floci-az leaves
- [ ] [A managed identity is a service principal](azure.md#a-managed-identity-is-a-service-principal) (line 1038): In Entra (from Microsoft's docs, not tested in a real tenant): managed identities appear under

### [control-plane-manifests.md](control-plane-manifests.md)

- [ ] [7. Changing them](control-plane-manifests.md#7-changing-them) (line 688): Edit the file on the control-plane node. The kubelet notices the change and recreates that component's container. Editing the mirror Pod through `kubectl` doesn't …
- [ ] [7. Changing them](control-plane-manifests.md#7-changing-them) (line 706): On minikube, a `minikube start --extra-config=…` or `--feature-gates=…` regenerates these files for you **(not tested)**. On managed clusters (AKS, EKS, GKE) the …
- [ ] [8.2 How the extra nodes get their manifests](control-plane-manifests.md#82-how-the-extra-nodes-get-their-manifests) (line 760): You don't copy files by hand. kubeadm does it **(not tested)**:

### [deployments-and-replicasets.md](deployments-and-replicasets.md)

- [ ] [Where `containers:` lives in each kind](deployments-and-replicasets.md#where-containers-lives-in-each-kind) (line 359): `ephemeralContainers` — added to a running Pod for debugging (`kubectl debug`) through the `pods/ephemeralcontainers` subresource; can't be set at creation (docs; not …
- [ ] [1.7 Reading a Pod's name: why Pods share a hash, and where the last part comes from](deployments-and-replicasets.md#17-reading-a-pods-name-why-pods-share-a-hash-and-where-the-last-part-comes-from) (line 455): So **Pods share the hash because they share a template**, and **they differ only in the last part because the API server picks it**. The ReplicaSet never chooses a …
- [ ] [When bare Pod manifests *are* written](deployments-and-replicasets.md#when-bare-pod-manifests-are-written) (line 1223): The mirror's owner is the **Node**, and `kubernetes.io/config.source: file` says it came from disk. To change or remove a static Pod, you edit or remove the file on …
- [ ] [1. The control plane: static Pods](deployments-and-replicasets.md#1-the-control-plane-static-pods) (line 1286): To change a static Pod (for example, to add an API server flag), you edit the **file on the node**, and the kubelet restarts it. Editing the mirror through `kubectl` …

### [etcd-storage.md](etcd-storage.md)

- [ ] [1.1 Raft and the WAL: durability first](etcd-storage.md#11-raft-and-the-wal-durability-first) (line 90): WAL segments are pre-allocated at 64 MB, so their size says nothing about how much data you have. The `.wal.broken` file is from an earlier unclean stop: the Mac …
- [ ] [1.5 Memory in the lab](etcd-storage.md#15-memory-in-the-lab) (line 192): **The raft index (578,802) and the MVCC revision (≈461,800) are different counters.** Raft entries include things that don't create a key revision, such as lease …
- [ ] [3.3 Every demo object, decoded](etcd-storage.md#33-every-demo-object-decoded) (line 367): The node's heartbeat **Lease** has been written **22,784 times** since revision 230. The kubelet renews it about every 10 s **(not tested)**, and 22,784 × 10 s ≈ 63 …
- [ ] [4.6 The API server's own in-memory copy: the watch cache](etcd-storage.md#46-the-api-servers-own-in-memory-copy-the-watch-cache) (line 505): Clients don't watch etcd directly. **Each API server holds one etcd watch per resource type**, keeps the current objects plus a window of recent events **in its own …
- [ ] [7. Practical consequences](etcd-storage.md#7-practical-consequences) (line 595): 1.5 MiB max request (etcd default) and 1 MiB Secret/ConfigMap limit — Big CRDs (153 KB here) and huge ConfigMaps eat into this. The Kubernetes limits keep objects …

### [gke-and-google-identity.md](gke-and-google-identity.md)

- [ ] [5. Compared with EKS and AKS](gke-and-google-identity.md#5-compared-with-eks-and-aks) (line 788): The `kubectl` token rows for EKS and AKS are from AWS and Microsoft documentation, not tested.

### [grpc-in-kubernetes.md](grpc-in-kubernetes.md)

- [ ] [1.7 Transports: TCP+TLS, plaintext TCP, Unix socket (docs; lab for the last two)](grpc-in-kubernetes.md#17-transports-tcptls-plaintext-tcp-unix-socket-docs-lab-for-the-last-two) (line 242): **TCP + TLS** — TLS handshake with ALPN `h2`, then the preface — `https` / host:port

### [ingress-and-gateway-api.md](ingress-and-gateway-api.md)

- [ ] [Why use Ingress at all, and when to skip it](ingress-and-gateway-api.md#why-use-ingress-at-all-and-when-to-skip-it) (line 229): Example (not tested), three apps:
- [ ] [Ingress as an application-layer load balancer, and what that means for NetworkPolicy](ingress-and-gateway-api.md#ingress-as-an-application-layer-load-balancer-and-what-that-means-for-networkpolicy) (line 628): Allowing only the ingress controller's Pods to reach an app (not tested; the label is ingress-nginx's default):
- [ ] [Ingress as an application-layer load balancer, and what that means for NetworkPolicy](ingress-and-gateway-api.md#ingress-as-an-application-layer-load-balancer-and-what-that-means-for-networkpolicy) (line 644): To filter on the **original** client, do it before connection 2 (not tested):
- [ ] [In iptables terms: two connections, one set of kube-proxy rules (lab)](ingress-and-gateway-api.md#in-iptables-terms-two-connections-one-set-of-kube-proxy-rules-lab) (line 722): `KUBE-SERVICES` and be DNATed too. **(not tested)**
- [ ] [In-cluster ingress](ingress-and-gateway-api.md#in-cluster-ingress) (line 2646): Restricting the proxy Pods to a client range (not tested; the labels are
- [ ] [4.6 What kind of Service works as a backend](ingress-and-gateway-api.md#46-what-kind-of-service-works-as-a-backend) (line 6405): `ExternalName` (a DNS name) — depends on the implementation (not tested here) — there are no EndpointSlices to read; some controllers resolve the name, others reject it

### [irsa-walkthrough.md](irsa-walkthrough.md)

- [ ] [4.3 Every annotation, tested (lab + code)](irsa-walkthrough.md#43-every-annotation-tested-lab--code) (line 980): The comment says 24 h is EKS's maximum **(code comment; not tested on EKS)**.
- [ ] [6.1 From inside the Pod (lab)](irsa-walkthrough.md#61-from-inside-the-pod-lab) (line 1142): `AWS_STS_REGIONAL_ENDPOINTS=regional` **(not tested)**.
- [ ] [8. Trust-policy conditions, tested (lab)](irsa-walkthrough.md#8-trust-policy-conditions-tested-lab) (line 1651): anyone who can create a Pod anywhere in the cluster gets the role **(not tested on AWS; follows

### [keycloak-on-kubernetes.md](keycloak-on-kubernetes.md)

- [ ] [7. NetworkPolicy (on by default)](keycloak-on-kubernetes.md#7-networkpolicy-on-by-default) (line 522): Probes come from the kubelet on the node. Most CNI plugins let host-to-Pod traffic through NetworkPolicy. **(not tested)** whether that holds for your CNI if you set …
- [ ] [10. Graceful shutdown: SIGTERM, `exec`, and the shutdown delay](keycloak-on-kubernetes.md#10-graceful-shutdown-sigterm-exec-and-the-shutdown-delay) (line 640): **The pre-shutdown delay** covers the race. Without it, Keycloak would stop accepting connections while the load balancer still sends it traffic, and users would get …
- [ ] [16.1 Scaling](keycloak-on-kubernetes.md#161-scaling) (line 823): A HorizontalPodAutoscaler can target `kind: Keycloak` directly **(not tested)**. The HPA finds Pods through `status.selector` to read their metrics. If you scaled the …

### [kubernetes-services.md](kubernetes-services.md)

- [ ] [Is NAT all a Service does?](kubernetes-services.md#is-nat-all-a-service-does) (line 1665): (from their docs; not tested here):
- [ ] [Hairpin tests](kubernetes-services.md#hairpin-tests) (line 4088): Not tested: whether `accept_local=1` would turn this into the
- [ ] [If chains attach to hooks, what are tables for? (lab)](kubernetes-services.md#if-chains-attach-to-hooks-what-are-tables-for-lab) (line 5404): **Ownership** (not tested) — `NFT_TABLE_F_OWNER` means the table is removed when the process that created it exits. Some tools, such as firewalld, use this so they …
- [ ] [Can't each program just use its own custom chains? (lab)](kubernetes-services.md#cant-each-program-just-use-its-own-custom-chains-lab) (line 6867): signals a reload over D-Bus; from the docs, not tested.)
- [ ] [A NodePort packet, rule by rule and hook by hook (lab, k3s)](kubernetes-services.md#a-nodeport-packet-rule-by-rule-and-hook-by-hook-lab-k3s) (line 7595): `pkg/proxy/iptables/proxier.go`; not tested here, k3s uses the default).
- [ ] [A NodePort packet, rule by rule and hook by hook (lab, k3s)](kubernetes-services.md#a-nodeport-packet-rule-by-rule-and-hook-by-hook-lab-k3s) (line 7636): docs; not tested, since k3s uses the default)**
- [ ] [A NodePort packet, rule by rule and hook by hook (lab, k3s)](kubernetes-services.md#a-nodeport-packet-rule-by-rule-and-hook-by-hook-lab-k3s) (line 8036): are from kube-proxy's code, not tested here:
- [ ] [A NodePort packet, rule by rule and hook by hook (lab, k3s)](kubernetes-services.md#a-nodeport-packet-rule-by-rule-and-hook-by-hook-lab-k3s) (line 8416): In **IPVS mode** INPUT is the main hook (from kube-proxy's IPVS docs; not tested,
- [ ] [A NodePort packet, rule by rule and hook by hook (lab, k3s)](kubernetes-services.md#a-nodeport-packet-rule-by-rule-and-hook-by-hook-lab-k3s) (line 8775): kube-proxy's code; not tested, since ServiceLB on one node reports one IP):
- [ ] [A NodePort packet, rule by rule and hook by hook (lab, k3s)](kubernetes-services.md#a-nodeport-packet-rule-by-rule-and-hook-by-hook-lab-k3s) (line 8948): IPs. **(from kube-proxy's code; not tested)**
- [ ] [A NodePort packet, rule by rule and hook by hook (lab, k3s)](kubernetes-services.md#a-nodeport-packet-rule-by-rule-and-hook-by-hook-lab-k3s) (line 9001): `-d <external IP> --dport <port>` — `spec.externalIPs` (any type, if set) **(from kube-proxy's code; not tested)** — `KUBE-EXT-…`
- [ ] [A NodePort packet, rule by rule and hook by hook (lab, k3s)](kubernetes-services.md#a-nodeport-packet-rule-by-rule-and-hook-by-hook-lab-k3s) (line 9278): *Not on this node, but worth knowing* (from `iptables-extensions(8)`; not tested):

### [network-policy.md](network-policy.md)

- [ ] [Why the open default is a security risk](network-policy.md#why-the-open-default-is-a-security-risk) (line 63): they were checked on a cluster, and **(not tested)** otherwise. The lab is a throwaway k3s cluster
- [ ] [3.2 ipBlock: one IP, a subnet, a range with exceptions (lab)](network-policy.md#32-ipblock-one-ip-a-subnet-a-range-with-exceptions-lab) (line 669): e4's `10.43.0.0/16` exception therefore probably changes nothing. **(not tested on its own)**
- [ ] [4. Things that catch people out](network-policy.md#4-things-that-catch-people-out) (line 712): (e.g. `tcpdump -i nflog:100`). **(docs; NFLOG rules seen in the lab, reading them not tested)**
- [ ] [How kube-router does it (lab, k3s v1.34.1+k3s1, np-agent)](network-policy.md#how-kube-router-does-it-lab-k3s-v1341k3s1-np-agent) (line 781): rules. **(that last part: not tested)**

### [nftables.md](nftables.md)

- [ ] [3. Families, tables and chains](nftables.md#3-families-tables-and-chains) (line 652): not tested; several of these modes are newer than the iptables ones and

### [oauth-grants-and-client-auth.md](oauth-grants-and-client-auth.md)

- [ ] [4.6 `token-exchange` (RFC 8693)](oauth-grants-and-client-auth.md#46-token-exchange-rfc-8693) (line 916): requesting client to be an audience of the `subject_token` (Keycloak docs; not tested without the

### [pod-security-context.md](pod-security-context.md)

- [ ] [2. Lab: five Pods compared](pod-security-context.md#2-lab-five-pods-compared) (line 124): **PSA `restricted` doesn't check `supplementalGroupsPolicy` or `readOnlyRootFilesystem`.** Pod e passes with them, but it would also pass without them. To require …
- [ ] [4. Outside the manifest](pod-security-context.md#4-outside-the-manifest) (line 189): **`hostUsers: false`** (user namespaces): even root in the container maps to an unprivileged UID on the node, so a container escape doesn't give root on the node …
- [ ] [4. Outside the manifest](pod-security-context.md#4-outside-the-manifest) (line 190): **The kubelet's `seccompDefault: true`**: applies `RuntimeDefault` to every Pod that doesn't set a profile **(not tested)**.

### [secrets-and-env.md](secrets-and-env.md)

- [ ] [Creating one with several keys](secrets-and-env.md#creating-one-with-several-keys) (line 101): All of these create a single Secret with several keys **(docs, not tested here)**:
- [ ] [Example: a Kafka client (SASL/SCRAM over TLS)](secrets-and-env.md#example-a-kafka-client-saslscram-over-tls) (line 201): Two Secrets, split by how they're used (**not tested here**):
- [ ] [Example: Confluent Cloud, all credentials in one Secret](secrets-and-env.md#example-confluent-cloud-all-credentials-in-one-secret) (line 445): The client config can then point at those files with Kafka's `DirectoryConfigProvider`. `${dir:<directory>:<file>}` is replaced with the file's contents when the …
- [ ] [Related, but not `valueFrom`](secrets-and-env.md#related-but-not-valuefrom) (line 1953): **The same Downward API data can be mounted as files** (`volumes[].downwardAPI`). Unlike env vars, those files **update** when labels or annotations change **(docs; …
- [ ] [Doing it yourself](secrets-and-env.md#doing-it-yourself) (line 2201): For self-managed clusters without a KMS, give the API server a key in a file **(not tested on this lab)**:
- [ ] [Limits](secrets-and-env.md#limits) (line 2502): There's no upper cap here, because minikube doesn't set `--service-account-max-token-expiration`. On a real cluster, set it, for example to `48h`, so nobody can mint …
- [ ] [Why the default Pod token says 365 days](secrets-and-env.md#why-the-default-pod-token-says-365-days) (line 2543): The kubelet **rewrites the file** well before the hour is up, so apps that re-read it always have a fresh token **(not tested: about 80% of the TTL, per the docs)**.
- [ ] [Long-lived vs short-lived](secrets-and-env.md#long-lived-vs-short-lived) (line 2556): Revoke — delete the Secret — wait for expiry; bound tokens die with their Pod or Secret; or delete the ServiceAccount (not tested)
- [ ] [The `secretObjects` flow end to end (Azure Key Vault example)](secrets-and-env.md#the-secretobjects-flow-end-to-end-azure-key-vault-example) (line 3198): **If `syncSecret.enabled` is off**, a `secretKeyRef` to the synced Secret never resolves, and the container stays in `CreateContainerConfigError` (reasoned from the …
- [ ] [The `secretObjects` flow end to end (Azure Key Vault example)](secrets-and-env.md#the-secretobjects-flow-end-to-end-azure-key-vault-example) (line 3299): **`/tmp` becomes read-only.** The kubelet makes a container mount read-only if either the volumeMount says `readOnly` or the volume itself is read-only: `ReadOnly: …
- [ ] [The `secretObjects` flow end to end (Azure Key Vault example)](secrets-and-env.md#the-secretobjects-flow-end-to-end-azure-key-vault-example) (line 3306): **Does that break nginx?** Most likely not **(not tested)**. The official nginx image keeps its temporary files under `/var/cache/nginx`, and none of its startup …
- [ ] [The `secretObjects` flow end to end (Azure Key Vault example)](secrets-and-env.md#the-secretobjects-flow-end-to-end-azure-key-vault-example) (line 3366): **2. Easier compliance and audits.** Security standards and auditors typically ask *where* secrets are stored, *who* can read them, whether every read is *logged*, …
- [ ] [The `secretObjects` flow end to end (Azure Key Vault example)](secrets-and-env.md#the-secretobjects-flow-end-to-end-azure-key-vault-example) (line 3381): Root on the node — Reads the tmpfs directory directly, for every Pod on that node. Root can also dial the provider socket and call `Mount` for any Pod (§ "The secrets …
- [ ] [Why drivers exist at all](secrets-and-env.md#why-drivers-exist-at-all) (line 3501): Having that code inside Kubernetes caused real problems **(general knowledge, not tested)**:
- [ ] [One driver for every cloud, one provider per cloud](secrets-and-env.md#one-driver-for-every-cloud-one-provider-per-cloud) (line 3644): The `/tmp/...` paths in the providers' code are only defaults; the shipped manifests point the sockets into the shared node directory, because that's where the driver …
- [ ] [One driver for every cloud, one provider per cloud](secrets-and-env.md#one-driver-for-every-cloud-one-provider-per-cloud) (line 3701): The `parameters` are specific to each driver. These values are from AWS docs knowledge, **not tested**.
- [ ] [The secrets chain end to end: every socket, every gRPC call](secrets-and-env.md#the-secrets-chain-end-to-end-every-socket-every-grpc-call) (line 4053): **`:scheme: http`, not https:** there's no TLS. Anyone who can open the socket file can call `Mount` for any Pod. The only protection is who can reach that `hostPath` …
- [ ] [What "external API" means: the calls behind `Mount`](secrets-and-env.md#what-external-api-means-the-calls-behind-mount) (line 4095): Without rotation, a value changed in the store isn't seen until the Pod is recreated. With rotation, each poll costs one identity exchange (unless cached) plus one …
- [ ] [Example 4: syncing to a Kubernetes Secret, for env vars](secrets-and-env.md#example-4-syncing-to-a-kubernetes-secret-for-env-vars) (line 4471): Note the timing problem. The env var needs the Secret, but the Secret needs the mount, and both belong to the same Pod start. The container waits on …
- [ ] [The `syncSecret.enabled` switch](secrets-and-env.md#the-syncsecretenabled-switch) (line 4486): `env: secretKeyRef` on the synced Secret — the container stays in `CreateContainerConfigError` (`secret "…" not found`), because the Secret never appears (reasoned …
- [ ] [The `syncSecret.enabled` switch](secrets-and-env.md#the-syncsecretenabled-switch) (line 4518): For a Deployment, the owner is its ReplicaSet. So, reasoning from this code (not tested):
- [ ] [When it fails: the real error messages](secrets-and-env.md#when-it-fails-the-real-error-messages) (line 4674): These are strings from the code. They should appear in the Pod's `FailedMount` events, with the Pod stuck in `ContainerCreating` as in §2 (not tested).
- [ ] [9. Rotation](secrets-and-env.md#9-rotation) (line 5048): For restarts, `kubectl rollout restart deployment/x` replaces Pods one at a time. Tools such as Stakater Reloader do this when a Secret changes (not tested).
- [ ] [Example: a new secret version in Azure Key Vault](secrets-and-env.md#example-a-new-secret-version-in-azure-key-vault) (line 5092): Automatically, on Secret change — a controller like **Stakater Reloader** (`reloader.stakater.com/auto: "true"` on the Deployment) watches Secrets and triggers a …
- [ ] [IRSA](secrets-and-env.md#irsa) (line 5451): Works on — EKS on Linux EC2 nodes — also Fargate and others (not tested)
- [ ] [IRSA](secrets-and-env.md#irsa) (line 5452): Cross-account — delegate via roles — natively in the trust policy (not tested)
- [ ] [Your options](secrets-and-env.md#your-options) (line 5695): Secret zero doesn't vanish. It moves to Vault's unseal keys, which you must keep outside the cluster. Normally a cloud KMS auto-unseals Vault, but Hetzner has none, …
- [ ] [Your options](secrets-and-env.md#your-options) (line 5714): **Option B can avoid the static key too.** A self-managed cluster can publish its OIDC issuer and public keys at a public URL, and AWS or Azure can then trust it, in …
- [ ] [15. Side by side](secrets-and-env.md#15-side-by-side) (line 5728): CSI provider — ASCP — Key Vault provider
- [ ] [17.2 Generating: ESO generators](secrets-and-env.md#172-generating-eso-generators) (line 5856): Now Key Vault is the source of truth. Everything else (the app, the database setup job, other clusters) reads it from there with an ordinary ExternalSecret or the CSI …
- [ ] [17.3 Other things that create Secrets for you](secrets-and-env.md#173-other-things-that-create-secrets-for-you) (line 5860): **kustomize `secretGenerator`:** builds a Secret at build time and adds a hash of the content to its name. When the value changes, the name changes, so every …
- [ ] [17.3 Other things that create Secrets for you](secrets-and-env.md#173-other-things-that-create-secrets-for-you) (line 5872): **cert-manager:** creates and renews `kubernetes.io/tls` Secrets from `Certificate` objects (§2) **(not tested)**.
- [ ] [17.3 Other things that create Secrets for you](secrets-and-env.md#173-other-things-that-create-secrets-for-you) (line 5873): **Operators that make credentials for their own app:** the Keycloak operator creates the initial admin Secret if you don't give it one …
- [ ] [17.3 Other things that create Secrets for you](secrets-and-env.md#173-other-things-that-create-secrets-for-you) (line 5874): **Vault dynamic secrets:** Vault's database engine creates a **new database user** per request, with an expiry. These reach Pods through the Vault Secrets Operator, …
- [ ] [17.3 Other things that create Secrets for you](secrets-and-env.md#173-other-things-that-create-secrets-for-you) (line 5875): **Terraform:** `random_password` plus `azurerm_key_vault_secret` creates the value straight into Key Vault, and ESO or the CSI driver delivers it. Note that the …

### [workload-identity.md](workload-identity.md)

- [ ] [AWS (STS): `AssumeRoleWithWebIdentity`](workload-identity.md#aws-sts-assumerolewithwebidentity) (line 755): **Not tested:** the real Entra ID and STS endpoints. The responses came from the local fake
- [ ] [How a Pod uses it to call the API server](workload-identity.md#how-a-pod-uses-it-to-call-the-api-server) (line 815): **The `default` ServiceAccount has no permissions** beyond what every authenticated user gets (such as API discovery). So the `curl` above returns 403 until you bind …
- [ ] [AKS identity bindings: sharing one managed identity across clusters](workload-identity.md#aks-identity-bindings-sharing-one-managed-identity-across-clusters) (line 1361): A managed identity can have at most 20 federated credentials, so it can trust at most 20 cluster/ServiceAccount pairs. With AKS identity bindings, many clusters share …
- [ ] [The webhook side of identity bindings (code)](workload-identity.md#the-webhook-side-of-identity-bindings-code) (line 1407): azure-identity README above says to set only one of them. Not tested.
- [ ] [What the webhook added on the lab cluster (lab + code)](workload-identity.md#what-the-webhook-added-on-the-lab-cluster-lab--code) (line 1767): **(docs; EKS's flags not visible, not tested)**.
- [ ] [What the webhook added on the lab cluster (lab + code)](workload-identity.md#what-the-webhook-added-on-the-lab-cluster-lab--code) (line 1788): web-identity pair. Only one branch runs per container. Not tested, because it needs the EKS Pod
- [ ] [Does `automountServiceAccountToken: false` remove the cloud tokens?](workload-identity.md#does-automountserviceaccounttoken-false-remove-the-cloud-tokens) (line 2421): The cloud webhooks add their own projected volumes, separate from `kube-api-access-…`. So turning off the automatic mount should leave the cloud token in place, and …
- [ ] [Looking at a token](workload-identity.md#looking-at-a-token) (line 2431): Base64url padding can make `base64 -d` complain and cut the last few characters, so the output may end early. `jq -R 'split(".")[1] | @base64d | fromjson'` handles …
- [ ] [The API server: `https://kubernetes.default.svc.cluster.local` (minikube, kubeadm) or `https://kubernetes.default.svc` (EKS)](workload-identity.md#the-api-server-httpskubernetesdefaultsvcclusterlocal-minikube-kubeadm-or-httpskubernetesdefaultsvc-eks) (line 2520): On EKS the issuer is the cluster's public OIDC URL, and the audience is fixed **(docs, not tested)**:
- [ ] [Entra ID, sovereign clouds: one audience per cloud](workload-identity.md#entra-id-sovereign-clouds-one-audience-per-cloud) (line 2578): Each example below shows the Pod part the webhook injects, written out for a hand-written Pod, and the federated credential **(not tested)**. They use the short v1.5 …
- [ ] [Istio: `istio-ca`](workload-identity.md#istio-istio-ca) (line 2846): The sidecar injector adds this so the proxy can get its workload certificate from istiod **(docs, not tested)**:

## 2. Cloud behaviour taken from vendor docs (labelled "docs")

### [private-endpoints.md](private-endpoints.md)

- [ ] [Private endpoints on AWS and Azure](private-endpoints.md#private-endpoints-on-aws-and-azure) (line 3): How a private endpoint gives a service you don't run an address inside your own network, on AWS and on Azure. Both sides were run on local emulators: the AWS Emulator …
- [ ] [1. The pieces, side by side](private-endpoints.md#1-the-pieces-side-by-side) (line 21): Route-only variant — gateway VPC endpoint, S3 and DynamoDB only: a prefix-list route in the route tables you choose (lab) — service endpoint on the subnet (docs), not …
- [ ] [1. The pieces, side by side](private-endpoints.md#1-the-pieces-side-by-side) (line 25): Restrict the caller side — endpoint policy on the endpoint (lab) — none on the endpoint; NSGs on the endpoint's subnet when `privateEndpointNetworkPolicies` is …
- [ ] [3.1 Interface endpoint for STS](private-endpoints.md#31-interface-endpoint-for-sts) (line 240): **The `hostedZoneId` values** belong to zones AWS manages. They aren't Route 53 zones in your account (docs). The emulator agrees: `ListHostedZonesByVPC` for …
- [ ] [3.1 Interface endpoint for STS](private-endpoints.md#31-interface-endpoint-for-sts) (line 305): **One endpoint per service per VPC can own the private DNS name (docs).** AWS refuses a second `PrivateDnsEnabled=true` endpoint for STS in the same VPC, because the …
- [ ] [3.2 Gateway endpoint for S3](private-endpoints.md#32-gateway-endpoint-for-s3) (line 512): Gateway endpoints are free, but only usable from inside the VPC: not from peered VPCs, Transit Gateway or on-premises (docs). Those need an S3 *interface* endpoint.
- [ ] [3.3 Policies on both sides](private-endpoints.md#33-policies-on-both-sides) (line 595): From the host, which has no endpoint, AWS would refuse the next request. The request context has no `aws:SourceVpce`, and IAM treats a negated operator …
- [ ] [3.4 PrivateLink: publishing your own service](private-endpoints.md#34-privatelink-publishing-your-own-service) (line 729): `privateDnsNameConfiguration` is the proof-of-ownership step. The provider must create a TXT record named `_33413bd985df2649.orders.internal.example.com` with the …
- [ ] [3.4 PrivateLink: publishing your own service](private-endpoints.md#34-privatelink-publishing-your-own-service) (line 862): **What AWS does next (docs):**
- [ ] [4.1 The target and the network](private-endpoints.md#41-the-target-and-the-network) (line 1043): A VNet with a node subnet and a subnet for private endpoints. `privateEndpointNetworkPolicies: Enabled` makes NSGs and route tables apply to private endpoints in that …
- [ ] [4.2 The private DNS zone](private-endpoints.md#42-the-private-dns-zone) (line 1119): The zone's name is fixed by the service: `privatelink.blob.core.windows.net` for Blob, `privatelink.vaultcore.azure.net` for Key Vault, and so on (docs). That name is …
- [ ] [4.3 The private endpoint](private-endpoints.md#43-the-private-endpoint) (line 1268): **`privateLinkServiceConnectionState`**: `Approved` / `Auto-approved`. In Azure, automatic approval happens only if the caller has the target's approval permission, …
- [ ] [4.3 The private endpoint](private-endpoints.md#43-the-private-endpoint) (line 1302): The NIC's address is `10.0.0.4`, which isn't in `pe-subnet` (10.20.8.0/24). floci-az writes the same address for every endpoint (code: `synthesizeProperties` defaults …
- [ ] [4.4 The private DNS zone group](private-endpoints.md#44-the-private-dns-zone-group) (line 1306): A zone group ties the endpoint to the zone. In Azure the endpoint then writes `pelabsa` → `10.20.8.4` into the zone, and deletes it again with the endpoint (docs):
- [ ] [4.4 The private DNS zone group](private-endpoints.md#44-the-private-dns-zone-group) (line 1442): From outside, the second name continues through public `CNAME`s to the account's public address, and `publicNetworkAccess: Disabled` refuses it there (docs).
- [ ] [4.5 Manual approval](private-endpoints.md#45-manual-approval) (line 1520): **In Azure (docs):**
- [ ] [4.6 Private link service: publishing your own](private-endpoints.md#46-private-link-service-publishing-your-own) (line 1548): The Azure counterpart of §3.4. A private link service sits in front of a Standard Load Balancer frontend. It takes NAT addresses from a subnet in the provider's VNet: …
- [ ] [4.7 What floci-az doesn't check](private-endpoints.md#47-what-floci-az-doesnt-check) (line 1661): → `201 Created`, connection `Approved` / `Auto-approved`, and a new NIC `pe-bogus.nic.cd0f33a9` with `10.0.0.4`. Azure answers `400` for a subnet it can't find, and …
- [ ] [4.8 The data plane ignores `publicNetworkAccess`](private-endpoints.md#48-the-data-plane-ignores-publicnetworkaccess) (line 1684): Azure Storage answers that request with `403 AuthorizationFailure` ("This request is not authorized to perform this operation"), whatever key or token is used (docs). …
- [ ] [5. Kubernetes on top](private-endpoints.md#5-kubernetes-on-top) (line 1688): All of this is (docs) except the IRSA environment variable, which was seen in [irsa-walkthrough.md](irsa-walkthrough.md).

### [workload-identity.md](workload-identity.md)

- [ ] [Who signs the token, and with which key (lab)](workload-identity.md#who-signs-the-token-and-with-which-key-lab) (line 330): **kube-controller-manager** with `--service-account-private-key-file`. **(docs)**
- [ ] [Who signs the token, and with which key (lab)](workload-identity.md#who-signs-the-token-and-with-which-key-lab) (line 356): **kubeadm:** `/etc/kubernetes/pki/sa.key` and `sa.pub`. **(docs)**
- [ ] [Who signs the token, and with which key (lab)](workload-identity.md#who-signs-the-token-and-with-which-key-lab) (line 368): cluster's JWKS endpoint (`/openid/v1/jwks`, or the provider's public issuer URL). **(docs)**
- [ ] [Steps 2 and 3, reproduced on the lab cluster](workload-identity.md#steps-2-and-3-reproduced-on-the-lab-cluster) (line 455): `--enable-oidc-issuer`), as in the example above. **(docs)**
- [ ] [How a Pod uses it to call the API server](workload-identity.md#how-a-pod-uses-it-to-call-the-api-server) (line 791): The token's audience is the API server's own: usually the issuer URL, e.g. `https://kubernetes.default.svc.cluster.local`. It's set by the API server's …
- [ ] [AKS identity bindings: sharing one managed identity across clusters](workload-identity.md#aks-identity-bindings-sharing-one-managed-identity-across-clusters) (line 1361): A managed identity can have at most 20 federated credentials, so it can trust at most 20 cluster/ServiceAccount pairs. With AKS identity bindings, many clusters share …
- [ ] [3. AWS, IRSA: `/var/run/secrets/eks.amazonaws.com/serviceaccount/token`](workload-identity.md#3-aws-irsa-varrunsecretseksamazonawscomserviceaccounttoken) (line 1440): The webhook (`amazon-eks-pod-identity-webhook`) runs in the EKS control plane. One thing opts a Pod in: **the ServiceAccount annotation `eks.amazonaws.com/role-arn: …
- [ ] [Why it removes the OIDC setup](workload-identity.md#why-it-removes-the-oidc-setup) (line 1901): off per association. **(docs: EKS user guide, "Define permissions for EKS Pod Identities to
- [ ] [Why it removes the OIDC setup](workload-identity.md#why-it-removes-the-oidc-setup) (line 1905): add-on agent. IRSA works with any cluster whose OIDC issuer AWS can reach. **(docs)**
- [ ] [Why it removes the OIDC setup](workload-identity.md#why-it-removes-the-oidc-setup) (line 1907): Two things are needed **(docs)**:
- [ ] [The SDK side: Java and Python (code + lab)](workload-identity.md#the-sdk-side-java-and-python-code--lab) (line 2122): (the IPv6 one on IPv6 clusters). **(docs)**
- [ ] [Azure Workload Identity next to IRSA, end to end](workload-identity.md#azure-workload-identity-next-to-irsa-end-to-end) (line 2385): provider at all (docs, §4):

### [azure.md](azure.md)

- [ ] [Can a managed identity be assigned to a Pod?](azure.md#can-a-managed-identity-be-assigned-to-a-pod) (line 1050): **1. The node's identity (any Pod on the node can use it)** (docs)
- [ ] [Can a managed identity be assigned to a Pod?](azure.md#can-a-managed-identity-be-assigned-to-a-pod) (line 1059): What an IMDS token request looks like (docs):
- [ ] [Can a managed identity be assigned to a Pod?](azure.md#can-a-managed-identity-be-assigned-to-a-pod) (line 1108): How Azure decides to hand it out without a credential (docs):

### [azure-identity-flows.md](azure-identity-flows.md)

- [ ] [3. A user signs in through an app (authorization code + PKCE)](azure-identity-flows.md#3-a-user-signs-in-through-an-app-authorization-code--pkce) (line 303): Conditional Access **(docs)**). The app proves who *it* is at `/token` with `client_secret`, and
- [ ] [3. A user signs in through an app (authorization code + PKCE)](azure-identity-flows.md#3-a-user-signs-in-through-an-app-authorization-code--pkce) (line 315): `orders-web`'s service principal **(docs)**. floci-az doesn't model consent.
- [ ] [4.2 Granting (lab)](azure-identity-flows.md#42-granting-lab) (line 631): Role Based Access Control Administrator **(docs)**. floci-az let Alice do it because it checks no
- [ ] [4.2 Granting (lab)](azure-identity-flows.md#42-granting-lab) (line 634): Bob included. Azure expands membership when it evaluates **(docs)**.
- [ ] [5. The user's own calls (delegated)](azure-identity-flows.md#5-the-users-own-calls-delegated) (line 756): Owner has `actions: *` but no `dataActions` **(docs)**. floci-az's Key Vault accepts any token.
- [ ] [6.1 The token, and the worker's role assignments (lab)](azure-identity-flows.md#61-the-token-and-the-workers-role-assignments-lab) (line 890): granted on that resource": in Azure, admin-consented app roles appear as a `roles` claim **(docs)**.
- [ ] [8. On-behalf-of: an API calls another API as the user](azure-identity-flows.md#8-on-behalf-of-an-api-calls-another-api-as-the-user) (line 1248): Vault, and `azp` = the API **(docs)**. Key Vault then decides on Alice's roles, so the API can't
- [ ] [10.2 Roles (docs; floci-az doesn't enforce them)](azure-identity-flows.md#102-roles-docs-floci-az-doesnt-enforce-them) (line 1526): assignments **(docs)**:
- [ ] [10.2 Roles (docs; floci-az doesn't enforce them)](azure-identity-flows.md#102-roles-docs-floci-az-doesnt-enforce-them) (line 1660): '…/orders-id' or the scope is invalid" **(docs; message shape, not captured)**.

### [azure-local-lab.md](azure-local-lab.md)

- [ ] [Sources](azure-local-lab.md#sources) (line 1296): floci-az: https://github.com/floci-io/floci-az (docs in `docs/services/`)

### [irsa-walkthrough.md](irsa-walkthrough.md)

- [ ] [2.1 Every EKS cluster has an issuer (lab)](irsa-walkthrough.md#21-every-eks-cluster-has-an-issuer-lab) (line 429): `<issuer>/.well-known/openid-configuration` and the JWKS publicly **(docs)**.
- [ ] [2.2 What STS will fetch from the issuer (lab, k3s)](irsa-walkthrough.md#22-what-sts-will-fetch-from-the-issuer-lab-k3s) (line 465): `--service-account-issuer` set to that URL **(docs)**. EKS does this for you.
- [ ] [2.3 CreateOpenIDConnectProvider (lab)](irsa-walkthrough.md#23-createopenidconnectprovider-lab) (line 555): through trusted CAs for providers like EKS's **(docs)**.
- [ ] [3.1 The README's trust policy, as written (lab)](irsa-walkthrough.md#31-the-readmes-trust-policy-as-written-lab) (line 657): **(docs)**. Without it, any audience listed on the OIDC provider is enough.
- [ ] [7.1 Two tokens (lab)](irsa-walkthrough.md#71-two-tokens-lab) (line 1340): (§9). Real Cognito signs its tokens **(docs)**.
- [ ] [7.3 AssumeRoleWithWebIdentity succeeds (lab)](irsa-walkthrough.md#73-assumerolewithwebidentity-succeeds-lab) (line 1464): `Credentials.AccessKeyId` — a temporary key ID. On AWS these start with `ASIA`; long-term user keys start with `AKIA` **(docs)**
- [ ] [10. What the README leaves out or gets wrong](irsa-walkthrough.md#10-what-the-readme-leaves-out-or-gets-wrong) (line 1692): trust policy without `:aud` — add `"<issuer>:aud": "sts.amazonaws.com"` **(docs)**

### [gke-and-google-identity.md](gke-and-google-identity.md)

- [ ] [0. The pieces](gke-and-google-identity.md#0-the-pieces) (line 23): Piece — Real GKE (docs) — In this lab
- [ ] [1. Creating a cluster (lab)](gke-and-google-identity.md#1-creating-a-cluster-lab) (line 120): Engine default service account (docs).
- [ ] [2.1 The kubeconfig (lab)](gke-and-google-identity.md#21-the-kubeconfig-lab) (line 302): user is an **exec plugin**, not a stored token (docs). Built by hand here, from `GetCluster`'s
- [ ] [2.1 The kubeconfig (lab)](gke-and-google-identity.md#21-the-kubeconfig-lab) (line 324): from gcloud's credentials or ADC (docs). A stand-in printed the same shape:
- [ ] [2.2 The API server asks someone else (lab + code)](gke-and-google-identity.md#22-the-api-server-asks-someone-else-lab--code) (line 379): service account token (docs).
- [ ] [2.2 The API server asks someone else (lab + code)](gke-and-google-identity.md#22-the-api-server-asks-someone-else-lab--code) (line 381): Real GKE (docs) — floci-gcp (lab)
- [ ] [3.2 Who answers: the GKE metadata server (docs)](gke-and-google-identity.md#32-who-answers-the-gke-metadata-server-docs) (line 444): With the GKE metadata server, there are two ways to give the ServiceAccount access (docs):
- [ ] [4. The same identity from outside GKE: Workload Identity Federation (lab + code)](gke-and-google-identity.md#4-the-same-identity-from-outside-gke-workload-identity-federation-lab--code) (line 528): provider that trusts the cluster's issuer (docs; floci-gcp has no pools).
- [ ] [4.2 The credential configuration file](gke-and-google-identity.md#42-the-credential-configuration-file) (line 588): `GOOGLE_APPLICATION_CREDENTIALS` (step 1 of the lookup in §3.1) (docs). Here it points at the
- [ ] [4.3 The exchange the SDK sends (lab + code)](gke-and-google-identity.md#43-the-exchange-the-sdk-sends-lab--code) (line 710): through the provider's attribute mapping and return a **federated access token** (docs).
- [ ] [4.4 Impersonating the Google service account (lab)](gke-and-google-identity.md#44-impersonating-the-google-service-account-lab) (line 758): that's what `roles/iam.workloadIdentityUser` (granted in §3.2) gives (docs).
- [ ] [4.5 The whole flow, GKE and non-GKE](gke-and-google-identity.md#45-the-whole-flow-gke-and-non-gke) (line 765): Step — Non-GKE cluster, run by the SDK (§4) — GKE with Workload Identity (docs)

## Related notes

- [private-endpoints.md](private-endpoints.md) §6: where the emulators differ from the real clouds.
- [workload-identity.md](workload-identity.md): the identity flows these claims come from.
