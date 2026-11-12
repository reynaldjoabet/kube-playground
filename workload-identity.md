# Workload identity: the token files in a Pod

A Pod can have several ServiceAccount tokens mounted under `/var/run/secrets/`. One is added to every Pod automatically. The others are added by a cloud provider's webhook, only for Pods that opt in. They're all JWTs for the same ServiceAccount, signed by the same cluster key. What differs is the **audience**: each one is meant for a different service.

```text
/var/run/secrets/
├── kubernetes.io/serviceaccount/token                         ← every Pod; sent to the kube-apiserver
├── azure/tokens/azure-identity-token                          ← AKS Workload Identity; sent to Entra ID
├── eks.amazonaws.com/serviceaccount/token                     ← EKS IRSA; sent to AWS STS
└── pods.eks.amazonaws.com/serviceaccount/eks-pod-identity-token ← EKS Pod Identity; sent to the node's Pod Identity agent
```

"Sent to" means the service that receives the token and checks it. Only that service accepts it, because the token's audience (`aud`) names it.

---

## 0. Terms

| Term | Meaning |
|---|---|
| **projected volume** | A volume that combines several sources in one directory: ServiceAccount token, ConfigMap, Secret, Downward API. |
| **`serviceAccountToken` source** | Asks the kubelet to request a token for the Pod's ServiceAccount from the API server (TokenRequest API) and keep the file fresh. |
| **audience (`aud`)** | The JWT claim naming who the token is for. A service rejects tokens with an audience that isn't its own. |
| **mutating admission webhook** | A service the API server calls when an object is created. It can change the object, e.g. add volumes and env vars to a Pod. |
| **OIDC issuer** | The cluster's public URL serving the keys that verify its ServiceAccount tokens. Cloud identity services read it to check the tokens. |
| **federated credential / trust policy** | The cloud-side rule that says "a token with this issuer, subject and audience may act as this identity". Azure calls it a federated identity credential; AWS puts it in the IAM role's trust policy. |

---

## 1. The automatic one: `/var/run/secrets/kubernetes.io/serviceaccount`

Every container gets a ServiceAccount token volume automatically, unless you turn it off.

The ServiceAccount object doesn't mount anything itself. The **ServiceAccount admission plugin** in the API server changes the Pod when it's created:

- it sets `serviceAccountName: default` if you didn't set one;
- it adds a projected volume named `kube-api-access-<random>`;
- it mounts that volume read-only into every container, including init containers.

```yaml
volumes:
- name: kube-api-access-vrgqx
  projected:
    sources:
    - serviceAccountToken:          # a short-lived JWT the kubelet asks the API server for
        expirationSeconds: 3607
        path: token
    - configMap:                    # the cluster CA, to verify the API server's certificate
        name: kube-root-ca.crt
        items: [{key: ca.crt, path: ca.crt}]
    - downwardAPI:                  # the Pod's namespace
        items: [{path: namespace, fieldRef: {fieldPath: metadata.namespace}}]
```

So the container sees three files: `token`, `ca.crt` and `namespace`. On the node, the volume is tmpfs (see [secrets-and-env.md](secrets-and-env.md), "Mounted vs unmounted volumes").

- **The token is tied to this Pod and expires.** The kubelet replaces the file before it expires. Its audience is the API server, so its only use is calling the Kubernetes API server (kube-apiserver), as the Pod's ServiceAccount.

### How a Pod uses it to call the API server

The token's audience is the API server's own: usually the issuer URL, e.g. `https://kubernetes.default.svc.cluster.local`. It's set by the API server's `--api-audiences` flag, which defaults to `--service-account-issuer` **(docs)**.

```sh
# inside any Pod
SA=/var/run/secrets/kubernetes.io/serviceaccount
curl --cacert $SA/ca.crt \
     -H "Authorization: Bearer $(cat $SA/token)" \
     https://kubernetes.default.svc/api/v1/namespaces/$(cat $SA/namespace)/pods
```

```text
Pod ── HTTPS to kubernetes.default.svc (the "kubernetes" Service → kube-apiserver)
 │     verifies the server certificate with ca.crt
 │     Authorization: Bearer <token>
 ▼
kube-apiserver
 ├─ authentication: checks signature, audience, expiry, and that the bound Pod still exists
 │                  → user "system:serviceaccount:<namespace>:<serviceaccount>"
 └─ authorization:  RBAC on that user
 ▼
200, or 403 Forbidden if no Role is bound to the ServiceAccount
```

- **`kubernetes.default.svc`** is the `kubernetes` Service in the `default` namespace, which points at the API server. The kubelet also sets `KUBERNETES_SERVICE_HOST` and `KUBERNETES_SERVICE_PORT` in every container.
- **The `default` ServiceAccount has no permissions** beyond what every authenticated user gets (such as API discovery). So the `curl` above returns 403 until you bind a Role to the ServiceAccount (not tested).
- **client-go's in-cluster config** (`rest.InClusterConfig()`) does exactly this: it reads the same three files and the two env vars. Controllers, operators and `kubectl` inside a Pod authenticate this way.
- **Other services can accept this token through the API server.** Vault's Kubernetes auth method sends the token to the API server's TokenReview API to ask "is this valid, and who is it?" Unless Vault is set to require a specific audience, it accepts the default token. Entra ID and AWS STS don't ask the API server. They verify the signature themselves via the cluster's OIDC issuer and require their own audience, which is why they need the separate tokens in §2 to §4.

### Turning it off

- **To turn it off,** set `automountServiceAccountToken: false` on the ServiceAccount or on the Pod. If both are set, the Pod's value wins. Turn it off for apps that never call the Kubernetes API. That's the hardening item in [pod-security-context.md](pod-security-context.md).

---

## 2. Azure Workload Identity: `/var/run/secrets/azure/tokens/azure-identity-token`

This one comes from the **Azure Workload Identity mutating webhook**, which runs on AKS when the workload identity feature is on. Two things opt a Pod in:

- **the Pod label `azure.workload.identity/use: "true"`** is what makes the webhook change the Pod;
- **the ServiceAccount annotation `azure.workload.identity/client-id: <client ID>`** says which managed identity or app registration to use. The webhook copies it into `AZURE_CLIENT_ID`.

It then adds its own projected token volume, a mount for it, and some environment variables.

A real Pod spec always splits a volume into two parts:

- **`volumes`** (at Pod level) says **what** the data is: here, a projected ServiceAccount token.
- **`volumeMounts`** (in each container) says **where** that container sees it: `mountPath`.

The two are linked by `name`. This is what the Azure webhook actually adds to the Pod:

```yaml
spec:
  containers:
  - name: app
    env:
    - name: AZURE_CLIENT_ID
      value: <client ID from the SA annotation>
    - name: AZURE_TENANT_ID
      value: <tenant ID>
    - name: AZURE_FEDERATED_TOKEN_FILE
      value: /var/run/secrets/azure/tokens/azure-identity-token
    - name: AZURE_AUTHORITY_HOST
      value: https://login.microsoftonline.com/
    volumeMounts:
    - name: azure-identity-token            # ← must match the volume's name
      mountPath: /var/run/secrets/azure/tokens   # the directory inside the container
      readOnly: true
  volumes:
  - name: azure-identity-token              # ← same name
    projected:
      sources:
      - serviceAccountToken:
          audience: api://AzureADTokenExchange   # a different audience from the default token
          expirationSeconds: 3600
          path: azure-identity-token        # the file name inside that directory
```

### How `volumes`, `volumeMounts`, `mountPath` and `path` fit together

Think of it as two separate questions, answered in two separate places.

**1. `volumes`: what's in the folder?**

A volume is a small folder of files that Kubernetes creates for the Pod. Each `path` is the name of one file in that folder:

```yaml
volumes:
- name: azure-identity-token
  projected:
    sources:
    - serviceAccountToken:
        path: azure-identity-token     # → one file, called "azure-identity-token"
```

So this volume is a folder containing one file:

```text
(volume "azure-identity-token")
└── azure-identity-token        ← the JWT
```

At this point the folder exists, but no container can see it yet.

**2. `volumeMounts`: where does the folder appear inside the container?**

```yaml
volumeMounts:
- name: azure-identity-token                   # which folder (the volume's name)
  mountPath: /var/run/secrets/azure/tokens      # where to put it in the container
```

This takes that folder and makes it appear at `/var/run/secrets/azure/tokens`. So inside the container:

```text
/var/run/secrets/azure/tokens/          ← mountPath: where the folder appears
└── azure-identity-token                 ← path: the file inside it
```

The full path to the file is the folder's location, then the file's name: `/var/run/secrets/azure/tokens/azure-identity-token`. That's what `AZURE_FEDERATED_TOKEN_FILE` points to.

**The same for the automatic token (§1).** That volume holds three files, one per `path`:

```yaml
volumes:
- name: kube-api-access-vrgqx
  projected:
    sources:
    - serviceAccountToken: {path: token}                          # file "token"
    - configMap: {name: kube-root-ca.crt, items: [{key: ca.crt, path: ca.crt}]}  # file "ca.crt"
    - downwardAPI: {items: [{path: namespace, ...}]}               # file "namespace"
```

```text
(volume "kube-api-access-vrgqx")
├── token
├── ca.crt
└── namespace
```

It's mounted at `/var/run/secrets/kubernetes.io/serviceaccount`, so inside the container you get:

```text
/var/run/secrets/kubernetes.io/serviceaccount/
├── token
├── ca.crt
└── namespace
```

**Why split it?** Because a Pod can have several containers, and each one decides separately whether to mount the folder, and where:

```yaml
spec:
  volumes:
  - name: shared-logs          # ONE folder, defined once
    emptyDir: {}
  containers:
  - name: app
    volumeMounts:
    - name: shared-logs
      mountPath: /var/log/app        # app sees it here and writes logs
  - name: log-shipper
    volumeMounts:
    - name: shared-logs
      mountPath: /logs               # sidecar sees the SAME folder here and reads them
  - name: metrics
    # no volumeMounts → this container doesn't see the folder at all
```

```text
                  volume "shared-logs" (one folder)
                   /                        \
   app: /var/log/app/          log-shipper: /logs/          metrics: (nothing)
```

If the "what" and the "where" were one field, you couldn't share one folder between containers at different paths, or keep it out of a container. That's also why the Azure webhook adds a `volumeMounts` entry to **each** container it wants to have the token: adding the volume alone isn't enough.

The AWS sections below show only the `volumes` part, with the mount directory as a comment. Their real Pod specs have the same `volumeMounts` + `env` shape.

### Where the files actually live: on the node

A Pod isn't a machine with its own disk. The files live **on the node** the Pod runs on. The kubelet writes them into a folder on the node, and each container that mounts the volume sees that folder through a bind mount:

```text
Node (the VM)
└── /var/lib/kubelet/pods/<pod-uid>/volumes/
    ├── kubernetes.io~projected/
    │   ├── kube-api-access-vrgqx/      ← tmpfs (RAM)
    │   │   ├── token
    │   │   ├── ca.crt
    │   │   └── namespace
    │   └── azure-identity-token/       ← tmpfs (RAM)
    │       └── azure-identity-token
    └── kubernetes.io~empty-dir/
        └── shared-logs/                 ← node disk (or RAM with medium: Memory)

Container "app"
  /var/run/secrets/kubernetes.io/serviceaccount  ──bind mount──► kube-api-access-vrgqx/
  /var/run/secrets/azure/tokens                  ──bind mount──► azure-identity-token/
```

On minikube, `kubernetes.io~projected/kube-api-access-vrgqx` was a tmpfs, and so was a Secret volume. A volume no container mounted was never created **(lab**, [secrets-and-env.md](secrets-and-env.md), "Mounted vs unmounted volumes"**)**. The Azure and `emptyDir` paths above follow the same layout but weren't checked on a node.

- **The files aren't in the image**, and they aren't in the container's writable layer. They're mounted over it at `mountPath`.
- **The ServiceAccount tokens aren't stored in etcd at all.** The API server signs them when asked and doesn't keep a copy. The kubelet holds them in RAM on the node and renews the files before they expire.
- **Containers in a Pod can share a volume** because they all run on the same node and bind-mount the same folder.
- **When the Pod is deleted, the kubelet deletes the folder.** The tmpfs ones never touched the disk.
- **Root on the node can read every Pod's tokens and Secrets** under `/var/lib/kubelet/pods/`. That's why a node compromise counts as a compromise of every workload on it.

#### Two Pods never share a volume

The volume itself belongs to one Pod. Everything under `spec.volumes` is created for that Pod, in that Pod's own folder on its node (`/var/lib/kubelet/pods/<pod-uid>/volumes/...`). Two Pods with an identical `volumes:` block each get their own copy:

```text
Pod A (uid aaa)  /var/lib/kubelet/pods/aaa/volumes/kubernetes.io~empty-dir/shared-logs/   ← A's folder
Pod B (uid bbb)  /var/lib/kubelet/pods/bbb/volumes/kubernetes.io~empty-dir/shared-logs/   ← B's folder, separate
```

So an `emptyDir`, a projected token volume or a Secret volume is never shared between Pods. Sharing a volume is something containers in the **same** Pod do.

Two Pods can share the storage *behind* a volume: both can name the same PersistentVolumeClaim, if its access mode allows it (`ReadWriteMany`, or `ReadWriteOnce` with both Pods on the same node).

### What the SDK does with the token

The Azure SDK reads `AZURE_FEDERATED_TOKEN_FILE` and sends the JWT to Entra ID as a client assertion. Entra ID returns an Azure access token for the managed identity.

```text
Pod ── reads azure-identity-token (aud api://AzureADTokenExchange)
 │
 ▼
POST https://login.microsoftonline.com/<tenant>/oauth2/v2.0/token
     client_id=<AZURE_CLIENT_ID>
     client_assertion_type=urn:ietf:params:oauth:client-assertion-type:jwt-bearer
     client_assertion=<the JWT>
 │
 ▼
Entra ID: fetches the cluster's OIDC keys, checks the signature,
          matches issuer + subject + audience against the federated identity credential
 │
 ▼
Azure access token ──► Key Vault, Storage, ...
```

The federated identity credential on the managed identity is what links them. Its subject is `system:serviceaccount:<namespace>:<serviceaccount>`. Setup commands are in [cloud-kubernetes.md](cloud-kubernetes.md) ("AKS Workload Identity") and [azure.md](azure.md) ("Azure Workload Identity").

#### What the Java SDK reads (azure-identity, code)

From `WorkloadIdentityCredential` and `WorkloadIdentityCredentialBuilder`:

- **Environment variables.** It reads `AZURE_TENANT_ID`, `AZURE_CLIENT_ID` and `AZURE_FEDERATED_TOKEN_FILE`. A value set on the builder (`tenantId`, `clientId`, `tokenFilePath`) wins over the environment. `AZURE_AUTHORITY_HOST` picks the Entra host, as for every credential.
- **The assertion.** It wraps a `ClientAssertionCredential` whose assertion supplier is `readFederatedTokenFromFile`. The file is read again each time a new assertion is needed, so the kubelet's token rotation is picked up without a restart.
- **The request.** It builds the request in `IdentityClientBase` as `client_assertion=<token>&client_assertion_type=urn:ietf:params:oauth:client-assertion-type:jwt-bearer&client_id=<id>`, the same as the diagram above.

#### AKS identity bindings: sharing one managed identity across clusters

A managed identity can have at most 20 federated credentials, so it can trust at most 20 cluster/ServiceAccount pairs. With AKS identity bindings, many clusters share one managed identity through a single AKS-managed federated credential. The token exchange goes through an AKS token proxy instead of straight to Entra. From the azure-identity README **(docs, not tested)**:

- **Opt in on the Pod.** The Pod needs the label `azure.workload.identity/use: "true"` and the annotation `azure.workload.identity/use-identity-binding: "true"`. For Jobs and Deployments, set both on the Pod template.
- **Opt in in the code.** Java needs `new WorkloadIdentityCredentialBuilder().enableAzureProxy().build()`. That's in `azure-identity` `1.19.0-beta.2` or later, not `1.18.x`. `DefaultAzureCredential` and `ManagedIdentityCredential` don't support it.
- **Extra environment variables.** The webhook adds these on top of the usual four:

| Variable | Purpose |
|---|---|
| `AZURE_KUBERNETES_TOKEN_PROXY` | HTTPS URL of the proxy (no credentials, query or fragment) |
| `AZURE_KUBERNETES_SNI_NAME` | TLS server name used to check the proxy's certificate |
| `AZURE_KUBERNETES_CA_FILE` or `AZURE_KUBERNETES_CA_DATA` | CA bundle for the proxy, as a file path or as inline PEM (not both, not base64) |

- **Keep the authority host.** `AZURE_AUTHORITY_HOST` stays set to the Entra host, not the proxy URL.
- **Proxy settings must be complete.** With `enableAzureProxy()` and no proxy variables, it uses the direct flow. With partial proxy variables, it fails rather than falling back.
- **Bindings don't grant access.** A binding only allows getting a token. The managed identity still needs Azure role assignments for the resources.

---

## 3. AWS, IRSA: `/var/run/secrets/eks.amazonaws.com/serviceaccount/token`

IRSA (IAM Roles for Service Accounts) is the older EKS method. It's the same pattern as Azure: a webhook adds a token with its own audience, and the SDK trades it with the cloud's token service.

The webhook (`amazon-eks-pod-identity-webhook`) runs in the EKS control plane. One thing opts a Pod in: **the ServiceAccount annotation `eks.amazonaws.com/role-arn: <IAM role ARN>`**. There's no Pod label. Every Pod using that ServiceAccount gets the token **(docs)**.

```yaml
volumes:
- name: aws-iam-token
  projected:
    sources:
    - serviceAccountToken:
        audience: sts.amazonaws.com
        expirationSeconds: 86400               # change with the SA annotation eks.amazonaws.com/token-expiration
        path: token
# mounted at /var/run/secrets/eks.amazonaws.com/serviceaccount
env:
  AWS_ROLE_ARN=<from the annotation>
  AWS_WEB_IDENTITY_TOKEN_FILE=/var/run/secrets/eks.amazonaws.com/serviceaccount/token
  AWS_STS_REGIONAL_ENDPOINTS=regional, AWS_REGION, AWS_DEFAULT_REGION
```

The AWS SDK's default credential chain sees `AWS_ROLE_ARN` + `AWS_WEB_IDENTITY_TOKEN_FILE` and calls STS:

```text
Pod ── reads token (aud sts.amazonaws.com)
 │
 ▼
STS AssumeRoleWithWebIdentity(RoleArn=<AWS_ROLE_ARN>, WebIdentityToken=<the JWT>)
 │
 ▼
STS: finds the IAM OIDC provider registered for the cluster's issuer, checks the signature,
     evaluates the role's trust policy (conditions on sub and aud)
 │
 ▼
temporary AWS credentials (access key, secret key, session token) ──► S3, Secrets Manager, ...
```

The IAM role's trust policy is the link. It names the cluster's OIDC provider and pins the ServiceAccount:

```json
{
  "Effect": "Allow",
  "Principal": {"Federated": "arn:aws:iam::111122223333:oidc-provider/oidc.eks.<region>.amazonaws.com/id/<ID>"},
  "Action": "sts:AssumeRoleWithWebIdentity",
  "Condition": {
    "StringEquals": {
      "oidc.eks.<region>.amazonaws.com/id/<ID>:sub": "system:serviceaccount:<namespace>:<serviceaccount>",
      "oidc.eks.<region>.amazonaws.com/id/<ID>:aud": "sts.amazonaws.com"
    }
  }
}
```

Each cluster has its own OIDC provider. So using one role from several clusters means adding each cluster's provider to the trust policy, and IAM has a limit on OIDC providers per account. That's the main reason Pod Identity was added. A full IRSA walkthrough is in [cloud-kubernetes.md](cloud-kubernetes.md) ("IRSA Deep Dive: The Two Tokens Inside Your Pod").

### What STS checks, in order (AWS Emulator code)

AWS Emulator (`aws/aws-emulator`) is AWS's local emulator of its APIs. Its STS
`AssumeRoleWithWebIdentity` checks the token in a fixed order, and each failure
returns a different error. The messages are copied from the text AWS returns.
This is an emulator modelling AWS's behaviour, not AWS's own code:

```text
1. not a compact JWT, or no exp / iss claim
     → InvalidIdentityToken  "The ID Token provided is not a valid JWT. (You may see this error if you sent an Access Token)"
2. exp has passed (checked before the signature)
     → ExpiredToken          "Token expired: current date/time <now> must be before the expiration date/time <exp>"
3. unsigned (alg none, or empty signature)
     → InvalidIdentityToken  "We don't accept unsigned JWTs"
4. no IAM OIDC provider registered for the iss
     → InvalidIdentityToken  "No OpenIDConnect provider found in your account for <issuer>"
5. the issuer's key set can't be fetched
     → InvalidIdentityToken  "Couldn't retrieve verification key from your identity provider, please reference AssumeRoleWithWebIdentity documentation for requirements"
6. signature doesn't verify against the issuer's keys
     → InvalidIdentityToken  "The web identity token provided could not be validated. See the AssumeRoleWithWebIdentity documentation for requirements."
7. token OK, but the role's trust policy doesn't admit this issuer with this aud and sub
     → AccessDenied
```

Two practical points:

- **The token is checked before the role.** A bad token with a role ARN that
  doesn't exist still gets the token error, not a "role not found".
- **Each message points at a different fix.** Step 4 is a missing IAM OIDC
  provider for the cluster. Step 5 means STS couldn't fetch the keys at
  `<issuer>/.well-known/openid-configuration` → `jwks_uri`, which is the usual
  error for a self-managed cluster whose issuer URL isn't public. Step 7 is the
  trust policy's `:sub` or `:aud` condition.

The model also documents the token rules STS enforces: signed with RSA (RS256,
RS384, RS512) or ECDSA (ES256, ES384, ES512), at most 20,000 characters, and
`DurationSeconds` from 900 up to the role's maximum session duration (1–12 h,
default 1 h).

**Using the emulator to test IRSA locally doesn't work** for a real cluster's
token. The emulator never makes outbound connections, so it can't fetch an
external issuer's key set. A minikube or EKS ServiceAccount token always stops
at step 5. Only tokens from issuers the emulator hosts itself (Amazon Cognito)
get through.

---

## 4. AWS, EKS Pod Identity: `/var/run/secrets/pods.eks.amazonaws.com/serviceaccount/eks-pod-identity-token`

EKS Pod Identity is the newer method. The link between ServiceAccount and IAM role is stored in EKS, not as an annotation, and credentials come from a node-local agent instead of STS directly.

Two things are needed **(docs)**:

- **the EKS Pod Identity Agent add-on**: a DaemonSet with host networking that listens on the link-local address `169.254.170.23` (IPv6 `[fd00:ec2::23]`);
- **a Pod Identity association**, created through the EKS API:

  ```sh
  aws eks create-pod-identity-association --cluster-name my-cluster \
    --namespace app --service-account app-sa \
    --role-arn arn:aws:iam::111122223333:role/app-role
  ```

When a Pod using that ServiceAccount is created, the EKS webhook adds:

```yaml
volumes:
- name: eks-pod-identity-token
  projected:
    sources:
    - serviceAccountToken:
        audience: pods.eks.amazonaws.com
        path: eks-pod-identity-token
# mounted at /var/run/secrets/pods.eks.amazonaws.com/serviceaccount
env:
  AWS_CONTAINER_CREDENTIALS_FULL_URI=http://169.254.170.23/v1/credentials
  AWS_CONTAINER_AUTHORIZATION_TOKEN_FILE=/var/run/secrets/pods.eks.amazonaws.com/serviceaccount/eks-pod-identity-token
```

The SDK uses its container credential provider, the same one ECS tasks use:

```text
Pod ── GET http://169.254.170.23/v1/credentials
       Authorization: <contents of eks-pod-identity-token>
 │
 ▼
Pod Identity Agent (on the same node)
 │
 ▼
EKS Auth API AssumeRoleForPodIdentity(clusterName, token)
 │
 ▼
EKS: checks the token, looks up the association for namespace + ServiceAccount,
     assumes the role with session tags (cluster name, namespace, ServiceAccount, Pod name, ...)
 │
 ▼
temporary AWS credentials ──► agent ──► Pod
```

The IAM role's trust policy no longer names a cluster. It trusts the EKS service, so the same role works from any cluster:

```json
{
  "Effect": "Allow",
  "Principal": {"Service": "pods.eks.amazonaws.com"},
  "Action": ["sts:AssumeRole", "sts:TagSession"]
}
```

### What EKS Auth checks, in order (AWS Emulator code)

The emulator's `AssumeRoleForPodIdentity` (the call the agent makes, in the EKS
Auth service) decides in this order:

```text
1. token is three base64url segments with a JOSE header         else InvalidTokenException (400)
     (checked before anything about the cluster is looked up)
2. the header's kid is a signing key of the cluster named
   in the URI                                                    else InvalidTokenException (400)
     (an unknown cluster publishes no keys, so it fails here too:
      ResourceNotFoundException is never returned)
3. the cluster issued this token for aud pods.eks.amazonaws.com
   and it hasn't expired                                         else InvalidToken / ExpiredTokenException (400)
4. a Pod Identity association exists for the token's
   namespace + ServiceAccount                                    else AccessDeniedException (400)
5. mint credentials for the associated role
```

The response, with the fields the model documents:

```json
{
  "subject":                 {"namespace": "app", "serviceAccount": "app-sa"},
  "audience":                "pods.eks.amazonaws.com",
  "podIdentityAssociation":  {"associationArn": "...", "associationId": "a-..."},
  "assumedRoleUser":         {"arn": "arn:aws:iam::111122223333:role/app-role",
                              "assumeRoleId": "<role id>:eks-<cluster>-<pod name>-<random UUID>"},
  "credentials":             {"accessKeyId": "...", "secretAccessKey": "...",
                              "sessionToken": "...", "expiration": "..."}
}
```

- **`audience` is always `pods.eks.amazonaws.com`.** That's why there's no
  audience to configure and no `:aud` condition on the role.
- **The session name is `eks-<cluster>-<pod name>-<UUID>`.** It shows up in
  CloudTrail as the role session, so you can tell which Pod made a call.
- **The session lifetime isn't published by AWS.** The emulator picks 6 hours
  for its own sessions; don't take that as AWS's value.

The Secrets Store CSI AWS provider supports both methods. Its chart requests tokens for both audiences (`sts.amazonaws.com` and `pods.eks.amazonaws.com`), and its Pod Identity code calls `http://169.254.170.23/v1/credentials` **(code)**. See [secrets-and-env.md](secrets-and-env.md) §8.

---

## 5. Side by side

| | Kubernetes default | Azure Workload Identity | AWS IRSA | AWS Pod Identity |
|---|---|---|---|---|
| Path under `/var/run/secrets/` | `kubernetes.io/serviceaccount/token` | `azure/tokens/azure-identity-token` | `eks.amazonaws.com/serviceaccount/token` | `pods.eks.amazonaws.com/serviceaccount/eks-pod-identity-token` |
| Added by | ServiceAccount admission plugin | Azure Workload Identity webhook | EKS webhook | EKS webhook |
| Opt-in | automatic, opt out with `automountServiceAccountToken: false` | Pod label `azure.workload.identity/use: "true"` + SA annotation `client-id` | SA annotation `eks.amazonaws.com/role-arn` | Pod Identity association in the EKS API |
| Audience (`aud`) | the API server | `api://AzureADTokenExchange` | `sts.amazonaws.com` | `pods.eks.amazonaws.com` |
| Exchanged with | nothing, used directly | Entra ID token endpoint | STS `AssumeRoleWithWebIdentity` | node agent, then EKS Auth `AssumeRoleForPodIdentity` |
| SDK env var | none (client-go reads the fixed path) | `AZURE_FEDERATED_TOKEN_FILE` | `AWS_WEB_IDENTITY_TOKEN_FILE` | `AWS_CONTAINER_AUTHORIZATION_TOKEN_FILE` |
| Cloud-side link | RBAC on the ServiceAccount | federated identity credential (issuer + subject) | role trust policy naming the cluster's OIDC provider | association; the trust policy names `pods.eks.amazonaws.com` |
| Subject (`sub`) | `system:serviceaccount:<ns>:<sa>` | the same | the same | the same |
| Signed by | the cluster's ServiceAccount key | the same | the same | the same |

Because the audiences differ, each service rejects a token meant for another. A leaked Azure or AWS token can't be used against the Kubernetes API, and the default token can't be traded for cloud credentials.

### Does `automountServiceAccountToken: false` remove the cloud tokens?

The cloud webhooks add their own projected volumes, separate from `kube-api-access-…`. So turning off the automatic mount should leave the cloud token in place, and the app can still reach Azure or AWS without being able to call the Kubernetes API **(not tested)**.

### Looking at a token

```sh
# inside the container: print the claims (the middle part of the JWT)
cut -d. -f2 /var/run/secrets/azure/tokens/azure-identity-token | base64 -d 2>/dev/null; echo
# look for "aud", "sub", "iss" and "exp"
```

Base64url padding can make `base64 -d` complain and cut the last few characters, so the output may end early. `jq -R 'split(".")[1] | @base64d | fromjson'` handles that if `jq` is in the image **(not tested)**.

## 6. The ServiceAccount object and token audiences

### Every ServiceAccount field

```yaml
apiVersion: v1
kind: ServiceAccount
metadata:
  name: orders
  namespace: orders
  labels:
    app: orders
  annotations:
    # Azure Workload Identity (read by the webhook)
    azure.workload.identity/client-id: "<managed-identity-client-id>"
    azure.workload.identity/tenant-id: "<tenant-id>"              # optional, defaults to the cluster's tenant
    azure.workload.identity/service-account-token-expiration: "3600"  # seconds, 3600–86400
    # AWS IRSA (read by the EKS pod-identity-webhook)
    eks.amazonaws.com/role-arn: arn:aws:iam::111122223333:role/orders
    eks.amazonaws.com/audience: sts.amazonaws.com                 # optional, this is the default
    eks.amazonaws.com/sts-regional-endpoints: "true"
    eks.amazonaws.com/token-expiration: "86400"
    # GKE Workload Identity (older style, linking to a Google service account)
    iam.gke.io/gcp-service-account: orders@my-project.iam.gserviceaccount.com
automountServiceAccountToken: false    # don't mount kube-api-access-* into Pods by default
imagePullSecrets:                      # copied into every Pod that uses this SA
- name: registry-creds
secrets:                               # legacy: long-lived token Secrets / mountable-secrets list
- name: orders-token
```

The core `ServiceAccount` object has only these fields besides metadata:

- `automountServiceAccountToken`: a Pod's own setting overrides it.
- `imagePullSecrets`: copied into each Pod that uses this ServiceAccount.
- `secrets`: a legacy list. Since 1.24, Kubernetes no longer creates token Secrets automatically. The `kubernetes.io/enforce-mountable-secrets` annotation that worked with this list is deprecated.

### Where the audience actually goes

A ServiceAccount has no audience field. The audience is chosen each time a token is requested.

```yaml
# projected volume in a Pod
volumes:
- name: vault-token
  projected:
    sources:
    - serviceAccountToken:
        audience: vault              # → "aud": ["vault"]
        expirationSeconds: 600
        path: token
```

```bash
kubectl create token orders -n orders --audience vault --duration 10m
```

If you give no audience, the token gets the API server's own audiences: every value of `--api-audiences`, or the `--service-account-issuer` values if that flag isn't set.

### Common audiences, one example each

For each one: what puts the token in the Pod, and the claims the receiver checks.

#### The API server: `https://kubernetes.default.svc.cluster.local` (minikube, kubeadm) or `https://kubernetes.default.svc` (EKS)

The kubelet adds this to every Pod as the `kube-api-access-*` volume. It has no `audience`, so the API server fills in its own **(lab, minikube 1.34)**:

```yaml
# kubectl get pod aud-test -o jsonpath='{.spec.volumes[0].projected.sources[0]}'
serviceAccountToken:
  expirationSeconds: 3607
  path: token
```

minikube's API server has no `--api-audiences` flag, only `--service-account-issuer=https://kubernetes.default.svc.cluster.local`, so the issuer is also the audience **(lab)**:

```json
{
  "aud": ["https://kubernetes.default.svc.cluster.local"],
  "iss": "https://kubernetes.default.svc.cluster.local",
  "sub": "system:serviceaccount:default:default",
  "kubernetes.io": { "namespace": "default", "serviceaccount": { "name": "default", "uid": "740a3f27-..." } }
}
```

That's from `kubectl create token default`. A token mounted in a Pod also has `pod` and `node` under `kubernetes.io`.

On EKS the issuer is the cluster's public OIDC URL, and the audience is fixed **(docs, not tested)**:

```json
{
  "aud": ["https://kubernetes.default.svc"],
  "iss": "https://oidc.eks.eu-west-1.amazonaws.com/id/EXAMPLED539D4633E53DE1B71EXAMPLE",
  "sub": "system:serviceaccount:orders:orders"
}
```

The receiver is the API server itself, which accepts the token only if `aud` is one of its own audiences.

#### Entra ID: `api://AzureADTokenExchange` (public cloud)

The Azure Workload Identity webhook adds this to Pods labelled `azure.workload.identity/use: "true"` (see §2):

```yaml
- name: azure-identity-token
  projected:
    sources:
    - serviceAccountToken:
        audience: api://AzureADTokenExchange
        expirationSeconds: 3600
        path: azure-identity-token
```

```json
{
  "aud": ["api://AzureADTokenExchange"],
  "iss": "https://westeurope.oic.prod-aks.azure.com/<tenant-id>/<guid>/",
  "sub": "system:serviceaccount:orders:orders"
}
```

Entra ID accepts it only if a federated identity credential on the managed identity has the same issuer, subject and audience:

```bash
az identity federated-credential create \
  --name orders --identity-name orders-id --resource-group rg-orders \
  --issuer "$AKS_OIDC_ISSUER" \
  --subject system:serviceaccount:orders:orders \
  --audiences api://AzureADTokenExchange
```

#### Entra ID, sovereign clouds: one audience per cloud

Every Entra cloud has its own audience. The Azure SDK for Java maps each authority host to its audience in `GitHubActionsCredentialBuilder.audienceForAuthority`, and the azure-identity README lists the same pairs **(code)**. The manifests below use those pairs.

From the same code:

- **Unknown hosts fail.** Any other host fails with "does not support the configured authority host". The SDK doesn't fall back to the public audience.
- **Host constants.** `AzureAuthorityHosts` has constants only for public, China, US Government and the old `AZURE_GERMANY`, which points to `https://login.microsoftonline.de/` and is deprecated: "Microsoft Cloud Germany was closed on October 29th, 2021". Delos is a different, new cloud. For France, Germany (Delos) and Singapore, pass the host as a string.
- **Docs vs code.** Microsoft Learn's federated credential page lists only the global, US Government and China audiences. The SDK code is the source for France, Germany and Singapore.

In Kubernetes the SDK doesn't choose the audience. `WorkloadIdentityCredential` just reads whatever token is in `AZURE_FEDERATED_TOKEN_FILE`, so the audience is fixed by whoever wrote the projected volume, normally the webhook. The audience mapping is used by `GitHubActionsCredential`, which requests its own token from GitHub. For a Pod, three things must belong to the same cloud: the projected volume's audience, `AZURE_AUTHORITY_HOST`, and the federated credential's audience.

Each example below shows the Pod part the webhook injects, written out for a hand-written Pod, and the federated credential **(not tested)**.

##### Global: `api://AzureADTokenExchange`

```yaml
env:
- name: AZURE_AUTHORITY_HOST
  value: https://login.microsoftonline.com/
- name: AZURE_FEDERATED_TOKEN_FILE
  value: /var/run/secrets/azure/tokens/azure-identity-token
volumes:
- name: azure-identity-token
  projected:
    sources:
    - serviceAccountToken:
        audience: api://AzureADTokenExchange
        expirationSeconds: 3600
        path: azure-identity-token
```

```bash
az cloud set --name AzureCloud
az identity federated-credential create --name orders \
  --identity-name orders-id --resource-group rg-orders \
  --issuer "$OIDC_ISSUER" \
  --subject system:serviceaccount:orders:orders \
  --audiences api://AzureADTokenExchange
```

##### China (21Vianet): `api://AzureADTokenExchangeChina`

```yaml
env:
- name: AZURE_AUTHORITY_HOST
  value: https://login.chinacloudapi.cn/
- name: AZURE_FEDERATED_TOKEN_FILE
  value: /var/run/secrets/azure/tokens/azure-identity-token
volumes:
- name: azure-identity-token
  projected:
    sources:
    - serviceAccountToken:
        audience: api://AzureADTokenExchangeChina
        expirationSeconds: 3600
        path: azure-identity-token
```

```bash
az cloud set --name AzureChinaCloud
az identity federated-credential create --name orders \
  --identity-name orders-id --resource-group rg-orders \
  --issuer "$OIDC_ISSUER" \
  --subject system:serviceaccount:orders:orders \
  --audiences api://AzureADTokenExchangeChina
```

##### US Government: `api://AzureADTokenExchangeUSGov`

```yaml
env:
- name: AZURE_AUTHORITY_HOST
  value: https://login.microsoftonline.us/
- name: AZURE_FEDERATED_TOKEN_FILE
  value: /var/run/secrets/azure/tokens/azure-identity-token
volumes:
- name: azure-identity-token
  projected:
    sources:
    - serviceAccountToken:
        audience: api://AzureADTokenExchangeUSGov
        expirationSeconds: 3600
        path: azure-identity-token
```

```bash
az cloud set --name AzureUSGovernment
az identity federated-credential create --name orders \
  --identity-name orders-id --resource-group rg-orders \
  --issuer "$OIDC_ISSUER" \
  --subject system:serviceaccount:orders:orders \
  --audiences api://AzureADTokenExchangeUSGov
```

##### France (Bleu): `api://AzureADTokenExchangeFrance`

```yaml
env:
- name: AZURE_AUTHORITY_HOST
  value: https://login.sovcloud-identity.fr/
- name: AZURE_FEDERATED_TOKEN_FILE
  value: /var/run/secrets/azure/tokens/azure-identity-token
volumes:
- name: azure-identity-token
  projected:
    sources:
    - serviceAccountToken:
        audience: api://AzureADTokenExchangeFrance
        expirationSeconds: 3600
        path: azure-identity-token
```

```bash
# not a built-in az cloud: register it from the cloud's published endpoints
az identity federated-credential create --name orders \
  --identity-name orders-id --resource-group rg-orders \
  --issuer "$OIDC_ISSUER" \
  --subject system:serviceaccount:orders:orders \
  --audiences api://AzureADTokenExchangeFrance
```

##### Germany (Delos): `api://AzureADTokenExchangeGermany`

```yaml
env:
- name: AZURE_AUTHORITY_HOST
  value: https://login.sovcloud-identity.de/
- name: AZURE_FEDERATED_TOKEN_FILE
  value: /var/run/secrets/azure/tokens/azure-identity-token
volumes:
- name: azure-identity-token
  projected:
    sources:
    - serviceAccountToken:
        audience: api://AzureADTokenExchangeGermany
        expirationSeconds: 3600
        path: azure-identity-token
```

```bash
# not a built-in az cloud: register it from the cloud's published endpoints
az identity federated-credential create --name orders \
  --identity-name orders-id --resource-group rg-orders \
  --issuer "$OIDC_ISSUER" \
  --subject system:serviceaccount:orders:orders \
  --audiences api://AzureADTokenExchangeGermany
```

##### Singapore (GovSG): `api://AzureADTokenExchangeGovSG`

```yaml
env:
- name: AZURE_AUTHORITY_HOST
  value: https://login.sovcloud-identity.sg/
- name: AZURE_FEDERATED_TOKEN_FILE
  value: /var/run/secrets/azure/tokens/azure-identity-token
volumes:
- name: azure-identity-token
  projected:
    sources:
    - serviceAccountToken:
        audience: api://AzureADTokenExchangeGovSG
        expirationSeconds: 3600
        path: azure-identity-token
```

```bash
# not a built-in az cloud: register it from the cloud's published endpoints
az identity federated-credential create --name orders \
  --identity-name orders-id --resource-group rg-orders \
  --issuer "$OIDC_ISSUER" \
  --subject system:serviceaccount:orders:orders \
  --audiences api://AzureADTokenExchangeGovSG
```

In Java, set the host on the credential instead of through the environment:

```java
WorkloadIdentityCredential cred = new WorkloadIdentityCredentialBuilder()
    .authorityHost(AzureAuthorityHosts.AZURE_GOVERNMENT)   // or "https://login.sovcloud-identity.fr/"
    .build();
```

The API server issues any audience you ask for **(lab)**:

```bash
kubectl create token default --audience api://AzureADTokenExchangeUSGov
# → "aud": ["api://AzureADTokenExchangeUSGov"]
```

Cross-cloud use is not supported. A public-cloud token sent to a US Government tenant is rejected, and so is every other mismatch. A wrong issuer, subject or audience on a federated credential is accepted when you create it, and only fails at the token exchange (Microsoft Learn).

AWS has no per-cloud audience. IRSA uses `sts.amazonaws.com` in every partition, and the China-region EKS docs say "For Audience, enter `sts.amazonaws.com`". What changes is the ARN partition (`arn:aws-cn:`, `arn:aws-us-gov:`) and the STS endpoint, which is regional only in China. For GovCloud, `sts.amazonaws.com` is from memory, not checked. Pod Identity in those partitions: not checked.

#### AWS STS (IRSA): `sts.amazonaws.com`

The EKS pod-identity-webhook adds this to Pods whose ServiceAccount has `eks.amazonaws.com/role-arn` (see §3):

```yaml
- name: aws-iam-token
  projected:
    sources:
    - serviceAccountToken:
        audience: sts.amazonaws.com
        expirationSeconds: 86400
        path: token
# env: AWS_ROLE_ARN, AWS_WEB_IDENTITY_TOKEN_FILE=/var/run/secrets/eks.amazonaws.com/serviceaccount/token
```

```json
{
  "aud": ["sts.amazonaws.com"],
  "iss": "https://oidc.eks.eu-west-1.amazonaws.com/id/EXAMPLED539D4633E53DE1B71EXAMPLE",
  "sub": "system:serviceaccount:orders:orders"
}
```

STS checks `aud` against the IAM OIDC provider's client IDs and the role's trust policy:

```json
"Condition": {
  "StringEquals": {
    "oidc.eks.eu-west-1.amazonaws.com/id/EXAMPLED539D4633E53DE1B71EXAMPLE:aud": "sts.amazonaws.com",
    "oidc.eks.eu-west-1.amazonaws.com/id/EXAMPLED539D4633E53DE1B71EXAMPLE:sub": "system:serviceaccount:orders:orders"
  }
}
```

To use a different audience, change it in three places, or STS rejects the token:

```yaml
# 1. ServiceAccount
metadata:
  annotations:
    eks.amazonaws.com/role-arn: arn:aws:iam::111122223333:role/orders
    eks.amazonaws.com/audience: orders-sts       # → projected volume asks for aud "orders-sts"
```

```bash
# 2. IAM OIDC provider: add the new client ID
aws iam add-client-id-to-open-id-connect-provider \
  --open-id-connect-provider-arn arn:aws:iam::111122223333:oidc-provider/oidc.eks.eu-west-1.amazonaws.com/id/EXAMPLED539D4633E53DE1B71EXAMPLE \
  --client-id orders-sts
# 3. trust policy: ":aud": "orders-sts"
```

#### EKS Pod Identity: `pods.eks.amazonaws.com`

The EKS Pod Identity webhook adds this to Pods whose ServiceAccount has a Pod Identity association (see §4):

```yaml
- name: eks-pod-identity-token
  projected:
    sources:
    - serviceAccountToken:
        audience: pods.eks.amazonaws.com
        expirationSeconds: 86400
        path: eks-pod-identity-token
# env: AWS_CONTAINER_CREDENTIALS_FULL_URI=http://169.254.170.23/v1/credentials
#      AWS_CONTAINER_AUTHORIZATION_TOKEN_FILE=/var/run/secrets/pods.eks.amazonaws.com/serviceaccount/eks-pod-identity-token
```

```json
{
  "aud": ["pods.eks.amazonaws.com"],
  "iss": "https://oidc.eks.eu-west-1.amazonaws.com/id/EXAMPLED539D4633E53DE1B71EXAMPLE",
  "sub": "system:serviceaccount:orders:orders"
}
```

You can't change this audience, and there is no trust-policy condition on it. The Pod Identity agent on the node passes the token to the EKS Auth API (`AssumeRoleForPodIdentity`). EKS checks the audience and looks up the association. The role trusts the service principal `pods.eks.amazonaws.com`, not the OIDC provider:

```json
"Principal": { "Service": "pods.eks.amazonaws.com" },
"Action": ["sts:AssumeRole", "sts:TagSession"]
```

#### Istio: `istio-ca`

The sidecar injector adds this so the proxy can get its workload certificate from istiod **(docs, not tested)**:

```yaml
- name: istio-token
  projected:
    sources:
    - serviceAccountToken:
        audience: istio-ca
        expirationSeconds: 43200
        path: istio-token
# mounted in istio-proxy at /var/run/secrets/tokens/istio-token
```

```json
{
  "aud": ["istio-ca"],
  "iss": "https://kubernetes.default.svc.cluster.local",
  "sub": "system:serviceaccount:orders:orders"
}
```

How the proxy gets its certificate:

1. The proxy sends the token with a CSR to istiod's CA (`CreateCertificate`, port 15012).
2. istiod validates the token with a TokenReview, asking for audience `istio-ca`.
3. istiod signs a certificate whose SPIFFE ID comes from the token's namespace and ServiceAccount: `spiffe://cluster.local/ns/orders/sa/orders`.

The issuer here is the cluster's own, because nothing outside the cluster needs to read this token.

#### Summary

| `aud` | Put there by | Checked by | Changeable? |
|---|---|---|---|
| `https://kubernetes.default.svc.cluster.local` / `https://kubernetes.default.svc` | kubelet (`kube-api-access-*`) | API server | `--api-audiences` (self-managed only) |
| `api://AzureADTokenExchange` | Azure WI webhook, Key Vault CSI provider | Entra ID, against the federated credential | must match the federated credential |
| `api://AzureADTokenExchange{China,USGov,France,Germany,GovSG}` | same, in sovereign clouds | Entra ID in that cloud | must match the cloud and the federated credential |
| `sts.amazonaws.com` | EKS pod-identity-webhook (IRSA) | AWS STS, against the OIDC provider and trust policy | `eks.amazonaws.com/audience` + OIDC client ID + trust policy |
| `pods.eks.amazonaws.com` | EKS Pod Identity webhook | EKS Auth API | no |
| `istio-ca` | Istio sidecar injector | istiod, via TokenReview | Istio config (rarely changed) |

---

## Related notes

- [pod-security-context.md](pod-security-context.md): `automountServiceAccountToken: false` as a hardening setting
- [secrets-and-env.md](secrets-and-env.md): the `kube-api-access` volume on the node, and the CSI providers that use these tokens (§8)
- [cloud-kubernetes.md](cloud-kubernetes.md): AKS Workload Identity setup and the IRSA deep dive
- [azure.md](azure.md): Azure Workload Identity
- [rbac-and-identity.md](rbac-and-identity.md): ServiceAccounts, RBAC and workload identity federation

## Sources

- Kubernetes docs, Configure Service Accounts for Pods: https://kubernetes.io/docs/tasks/configure-pod-container/configure-service-account/
- Kubernetes docs, Service Accounts (bound tokens, projected volumes): https://kubernetes.io/docs/concepts/security/service-accounts/
- Kubernetes docs, Projected Volumes: https://kubernetes.io/docs/concepts/storage/projected-volumes/
- Azure Workload Identity docs, Quick start and Service Account labels and annotations: https://azure.github.io/azure-workload-identity/docs/
- Microsoft Learn, Use Microsoft Entra Workload ID with AKS: https://learn.microsoft.com/azure/aks/workload-identity-overview
- AWS docs, IAM roles for service accounts: https://docs.aws.amazon.com/eks/latest/userguide/iam-roles-for-service-accounts.html
- amazon-eks-pod-identity-webhook (IRSA webhook): https://github.com/aws/amazon-eks-pod-identity-webhook
- AWS docs, EKS Pod Identity: https://docs.aws.amazon.com/eks/latest/userguide/pod-identities.html
- AWS docs, STS AssumeRoleWithWebIdentity: https://docs.aws.amazon.com/STS/latest/APIReference/API_AssumeRoleWithWebIdentity.html
- AWS docs, EKS Auth AssumeRoleForPodIdentity: https://docs.aws.amazon.com/eks/latest/APIReference/API_auth_AssumeRoleForPodIdentity.html
- secrets-store-csi-driver-provider-aws: https://github.com/aws/secrets-store-csi-driver-provider-aws
- Azure SDK for Java, azure-identity (GitHubActionsCredentialBuilder audience map, AzureAuthorityHosts, WorkloadIdentityCredential, README "AKS identity bindings"): https://github.com/Azure/azure-sdk-for-java/tree/main/sdk/identity/azure-identity
- Microsoft Learn, Configure an application to trust a managed identity (audiences per cloud): https://learn.microsoft.com/entra/workload-id/workload-identity-federation-config-app-trust-managed-identity
- Microsoft Learn, AKS identity bindings: https://learn.microsoft.com/azure/aks/identity-bindings-concepts
- Amazon EKS China docs, Create an IAM OIDC provider: https://docs.amazonaws.cn/en_us/eks/latest/userguide/enable-iam-roles-for-service-accounts.html
- AWS Emulator (STS `assume_role_with_web_identity.rs` and `state/web_identity.rs`, EKS Auth `assume_role_for_pod_identity.rs`): https://github.com/aws/aws-emulator
