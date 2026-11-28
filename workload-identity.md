# Workload identity: the token files in a Pod

A Pod can have several ServiceAccount tokens mounted under `/var/run/secrets/`. One is added to every Pod automatically. The others are added by a cloud provider's webhook, only for Pods that opt in. They're all JWTs for the same ServiceAccount, signed by the same cluster key. What differs is the **audience**: each one is meant for a different service.

```text
/var/run/secrets/
├── kubernetes.io/serviceaccount/token                         ← every Pod; sent to kube-apiserver
├── azure/tokens/azure-identity-token                          ← AKS Workload Identity;
│                                                                sent to Entra ID
│   (webhook v1.6+: azure/wi/<hash>/token/azure-identity-token, see §2)
├── eks.amazonaws.com/serviceaccount/token                     ← EKS IRSA; sent to AWS STS
└── pods.eks.amazonaws.com/serviceaccount/eks-pod-identity-token ← EKS Pod Identity;
                                                                  sent to the node's agent
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

## 0.5 How the OIDC token exchange works, end to end (lab)

The exchange works by turning your Kubernetes cluster into an **OIDC identity provider (IdP)**.
Kubernetes signs a short-lived, audience-scoped JWT with its private key, and the cloud's IAM
checks that signature against the cluster's **public JWKS** endpoint before issuing temporary
cloud credentials. No cloud secret is ever stored in the cluster.

**Step 1. Pod mutation (admission webhook).** When a Pod is created, a mutating admission webhook
changes its spec. The webhook is `amazon-eks-pod-identity-webhook` for IRSA (the name predates EKS
*Pod Identity*, §4, though its code now injects the env vars for that too, see §3), or the Azure
Workload Identity webhook. It injects:

- **a projected `serviceAccountToken` volume** with the cloud's `audience` and an
  `expirationSeconds`. The default lifetime differs: **86400** (24 h) for IRSA, changeable with the
  ServiceAccount annotation `eks.amazonaws.com/token-expiration`, and **3600** for Azure (§6);
- **environment variables** that tell the cloud SDK which identity to ask for and where the token
  file is (IRSA: `AWS_ROLE_ARN`, `AWS_WEB_IDENTITY_TOKEN_FILE`; Azure: `AZURE_CLIENT_ID`,
  `AZURE_TENANT_ID`, `AZURE_FEDERATED_TOKEN_FILE`, `AZURE_AUTHORITY_HOST`);
- **a `volumeMount`** in every container, so the token file appears at the path the env var names.

That's all. The webhook adds no sidecar, no annotations and no labels; it changes only `env`,
`volumeMounts` and `volumes`. In the lab, a Pod submitted as just `image` + `command` +
`serviceAccountName` was stored with these extra lines in each case (**lab**, both webhooks
installed on a k3s cluster; full output in §2 and §3):

```yaml
# IRSA (amazon-eks-pod-identity-webhook v0.6.19); trigger: SA annotation eks.amazonaws.com/role-arn
    env:
    - name: AWS_ROLE_ARN
      value: arn:aws:iam::111122223333:role/my-app
    - name: AWS_WEB_IDENTITY_TOKEN_FILE
      value: /var/run/secrets/eks.amazonaws.com/serviceaccount/token
    volumeMounts:
    - mountPath: /var/run/secrets/eks.amazonaws.com/serviceaccount
      name: aws-iam-token
      readOnly: true
  volumes:
  - name: aws-iam-token
    projected:
      sources:
      - serviceAccountToken: {audience: sts.amazonaws.com, expirationSeconds: 86400, path: token}

# Azure (workload-identity-webhook v1.6.1); trigger: Pod label azure.workload.identity/use: "true"
    env:
    - name: AZURE_CLIENT_ID
      value: 11111111-2222-3333-4444-555555555555
    - name: AZURE_TENANT_ID
      value: 00000000-0000-0000-0000-000000000000
    - name: AZURE_FEDERATED_TOKEN_FILE
      value: /var/run/secrets/azure/wi/a172cedcae47474b615c54d510a5d8/token/azure-identity-token
    - name: AZURE_AUTHORITY_HOST
      value: https://login.microsoftonline.com/
    volumeMounts:
    - mountPath: /var/run/secrets/azure/wi/a172cedcae47474b615c54d510a5d8
      name: azure-workload-identity-reserved-a172cedcae47474b615c54d510a5d8
      readOnly: true
  volumes:
  - name: azure-workload-identity-reserved-a172cedcae47474b615c54d510a5d8
    projected:
      sources:
      - serviceAccountToken:
          {audience: "api://AzureADTokenExchange", expirationSeconds: 3600,
           path: token/azure-identity-token}
```

The SDK only needs the env vars. It never looks for the volume: it opens whatever file
`AWS_WEB_IDENTITY_TOKEN_FILE` / `AZURE_FEDERATED_TOKEN_FILE` names.

**Step 2. JWT minting (kubelet → TokenRequest API).** The webhook itself never mints a token; it
only edits the Pod spec. Once the Pod is scheduled onto a node, that node's kubelet sees the
`projected.sources[].serviceAccountToken` volume and, before starting the containers, calls
kube-apiserver's TokenRequest API with that `audience` and `expirationSeconds`, plus a
`boundObjectRef` naming the Pod. kube-apiserver signs a JWT with the cluster's private signing key
and puts the key ID (`kid`) in the JOSE header. The kubelet writes it into a tmpfs at
`mountPath/path`, and replaces it in the background once the token is about 80% through its
lifetime (or 24 hours old, whichever comes first). The swap is atomic: a reader sees the old file
or the new one, never half of each. Details and lab: "Step 2 in detail" below.

**Step 3. The exchange.** The SDK reads the file and sends the token to the cloud's token service
(AWS STS `AssumeRoleWithWebIdentity`, Entra ID's token endpoint with a client assertion). The
service:

1. reads `iss` and fetches `<iss>/.well-known/openid-configuration`, then the JWKS it points to.
   The issuer must be a public URL the cloud can reach;
2. checks the **signature** with the key whose `kid` matches the token header;
3. checks `aud` (its own audience), `exp`/`nbf`, and that `iss` is registered (IAM OIDC provider /
   federated credential);
4. matches `sub` (`system:serviceaccount:<ns>:<name>`) against the role's trust policy or the
   federated credential;
5. returns temporary credentials.

How each cloud's service checks these, in order: §3 (STS) and §4 (EKS Auth), from the AWS
emulator's code.

### Step 2 in detail: the kubelet mints and rotates the token (lab + code)

**The webhook only edits YAML.** In the §2 and §3 labs the stored Pod held a `serviceAccountToken`
*source* (audience, lifetime, file name) and no token. The token exists only once a kubelet asks
for one.

**The request the kubelet builds** (`pkg/volume/projected/projected.go`, v1.34.1, **code**). For
each `serviceAccountToken` source:

```go
var auds []string
if len(tp.Audience) != 0 {
	auds = []string{tp.Audience}
}
tr, err := s.plugin.getServiceAccountToken(s.pod.Namespace, s.pod.Spec.ServiceAccountName, &authenticationv1.TokenRequest{
	Spec: authenticationv1.TokenRequestSpec{
		Audiences:         auds,
		ExpirationSeconds: tp.ExpirationSeconds,
		BoundObjectRef: &authenticationv1.BoundObjectReference{
			APIVersion: "v1",
			Kind:       "Pod",
			Name:       s.pod.Name,
			UID:        s.pod.UID,
		},
	},
})
```

That's `POST /api/v1/namespaces/<ns>/serviceaccounts/<sa>/token`. The `BoundObjectRef` is why the
token carries `kubernetes.io.pod` and why the API server stops accepting it once that Pod is gone.

**The kubelet can only ask for its own Pods.** Using cal-agent1's kubelet credential
(`/var/lib/rancher/k3s/agent/kubelet.kubeconfig`) by hand **(lab)**:

```console
$ kubectl --kubeconfig kubelet.kubeconfig auth whoami
Username   system:node:cal-agent1
Groups     [system:nodes system:authenticated]

# Pod rot-demo/rot, running on cal-agent1
$ kubectl --kubeconfig kubelet.kubeconfig create --raw /api/v1/namespaces/rot-demo/serviceaccounts/default/token -f - <<EOF
{"apiVersion":"authentication.k8s.io/v1","kind":"TokenRequest","spec":{"audiences":["demo.example.com"],
 "expirationSeconds":600,"boundObjectRef":{"apiVersion":"v1","kind":"Pod","name":"rot","uid":"c40abe8f-..."}}}
EOF
  "status": {
    "token": "eyJhbGciOiJSUzI1NiIsImtpZCI6IjVxN1JTcXIt...",
    "expirationTimestamp": "2026-11-23T01:08:30Z"
  }

# Pod l3/web2, running on cal-agent2
Error from server (Forbidden): serviceaccounts "default" is forbidden: node requested token bound to a pod scheduled on a different node

# no boundObjectRef at all
Error from server (Forbidden): serviceaccounts "default" is forbidden: node requested token not bound to a pod
```

A node's credential can mint tokens only for Pods scheduled to that node, and only Pod-bound ones.
A compromised node therefore can't mint cloud tokens for workloads on other nodes.

**Where the file goes (lab).** A Pod `rot-demo/rot` on cal-agent1 with one source
`{audience: demo.example.com, expirationSeconds: 600, path: token}` mounted at
`/var/run/secrets/demo`:

```console
# on the node
$ grep c40abe8f-.../volumes /proc/mounts
tmpfs /var/lib/kubelet/pods/c40abe8f-.../volumes/kubernetes.io~projected/kube-api-access-j55nw tmpfs rw,relatime,size=8124776k,noswap 0 0
tmpfs /var/lib/kubelet/pods/c40abe8f-.../volumes/kubernetes.io~projected/demo-token tmpfs rw,relatime,size=8124776k,noswap 0 0

# in the container
$ grep /var/run/secrets/demo /proc/mounts
tmpfs /var/run/secrets/demo tmpfs ro,relatime,size=8124776k,noswap 0 0
$ ls -la /var/run/secrets/demo/ /var/run/secrets/demo/..data/
/var/run/secrets/demo/:
drwxrwxrwt    3 root     root           100 Nov 23 00:57 .
drwxr-xr-x    4 root     root          4096 Nov 23 00:57 ..
drwxr-xr-x    2 root     root            60 Nov 23 00:57 ..2026_11_23_00_57_55.2935804886
lrwxrwxrwx    1 root     root            32 Nov 23 00:57 ..data -> ..2026_11_23_00_57_55.2935804886
lrwxrwxrwx    1 root     root            12 Nov 23 00:57 token -> ..data/token

/var/run/secrets/demo/..data/:
-rw-r--r--    1 root     root          1082 Nov 23 00:57 token
```

- Each projected volume is its own tmpfs on the node. The kubelet wraps it in an `emptyDir` with
  `medium: Memory` (`wrappedVolumeSpec` in `projected.go`), so the token is never on disk.
- The container sees the same tmpfs bind-mounted read-only at `mountPath`. `path: token` is the
  file name inside it.
- `token` → `..data/token` → `..<timestamp>/token`. To update, the kubelet writes a new timestamped
  directory, repoints `..data` with one `rename`, then deletes the old directory (`AtomicWriter`).

The token's claims, with `exp - iat` = 600:

```json
{
  "aud": ["demo.example.com"],
  "exp": 1795396075,
  "iat": 1795395475,
  "kubernetes.io": {
    "namespace": "rot-demo",
    "node": {"name": "cal-agent1", "uid": "8f7b3d07-..."},
    "pod": {"name": "rot", "uid": "c40abe8f-d8de-4519-bf0c-7c4668d131dd"},
    "serviceaccount": {"name": "default", "uid": "3fa7f49b-..."}
  },
  "sub": "system:serviceaccount:rot-demo:default"
}
```

**When it refreshes** (`pkg/kubelet/token/token_manager.go`, v1.34.1, **code**):

```go
const (
	maxTTL    = 24 * time.Hour
	maxJitter = 10 * time.Second
)

// requiresRefresh returns true if the token is older than 80% of its total
// ttl, or if the token is older than 24 hours.
func (m *Manager) requiresRefresh(ctx context.Context, tr *authenticationv1.TokenRequest) bool {
	...
	now := m.clock.Now()
	exp := tr.Status.ExpirationTimestamp.Time
	iat := exp.Add(-1 * time.Duration(*tr.Spec.ExpirationSeconds) * time.Second)

	jitter := time.Duration(rand.Float64()*maxJitter.Seconds()) * time.Second
	if now.After(iat.Add(maxTTL - jitter)) {
		return true
	}
	// Require a refresh if within 20% of the TTL plus a jitter from the expiration time.
	if now.After(exp.Add(-1*time.Duration((*tr.Spec.ExpirationSeconds*20)/100)*time.Second - jitter)) {
		return true
	}
	return false
}
```

`GetServiceAccountToken` in the same file uses a cached token until `requiresRefresh` says
otherwise. If the refresh call fails, it logs the error and keeps serving the old token while
it's still valid. Only an expired token with a failed refresh is an error.

| `expirationSeconds` | 80% point | 24 h cap | Refresh after |
|---|---|---|---|
| 600 (the minimum) | 480 s | — | ~8 min |
| 3600 (Azure) | 2880 s | — | ~48 min |
| 86400 (IRSA default) | 69120 s | 86400 s | ~19.2 h |
| 172800 (48 h) | 138240 s | 86400 s | **24 h** (the cap wins) |

**The rotation, watched (lab).** The same Pod's token sampled every 15 s, showing the `..data`
target and the token's `iat`, `exp` and the start of its `jti`:

```text
00:58:07 data=..2026_11_23_00_57_55.2935804886 iat=1795395475 exp=1795396075 jti=50888541
...      (unchanged for 8.5 minutes)
01:06:33 data=..2026_11_23_00_57_55.2935804886 iat=1795395475 exp=1795396075 jti=50888541
01:06:48 data=..2026_11_23_01_06_44.789582713 iat=1795396004 exp=1795396604 jti=b1116dfc
...      (unchanged)
01:09:06 data=..2026_11_23_01_06_44.789582713 iat=1795396004 exp=1795396604 jti=b1116dfc
```

| Moment | UTC | Seconds after first `iat` |
|---|---|---|
| first token issued (`iat`) | 00:57:55 | 0 |
| 80% point | 01:05:55 | 480 |
| **new token written** (new `..data`, new `iat`, new `jti`) | **01:06:44** | **529 (88%)** |
| first token's `exp` | 01:07:55 | 600 |

- It's a **new token**, not an extended one: new `jti`, `iat` and `exp`. The old one stays valid until
  its own `exp`; nothing revokes it.
- The old directory `..2026_11_23_00_57_55…` was gone afterwards. Only the new one remained.
- **80% is the earliest point, not the exact moment.** The kubelet checks `requiresRefresh` only
  when it re-syncs the Pod's volumes, so the swap came 49 s after the 80% mark. That fits the
  kubelet's periodic Pod sync, about once a minute **(inferred, not traced in the code)**. With
  600 s that left 71 s before the old token expired; with longer lifetimes the margin is large.
- The container's process does nothing. Readers that re-open the file (all the SDKs in Step 3 do)
  get the new token. A program that reads it once at startup keeps the old one until it expires.

A decoded IRSA token looks like this (illustrative values):

```json
{
  "iss": "https://oidc.eks.us-east-1.amazonaws.com/id/EXAMPLED539D4633E53DE1B716D3041E",
  "sub": "system:serviceaccount:prod:my-app",
  "aud": ["sts.amazonaws.com"],
  "exp": 1763770500,
  "iat": 1763766900,
  "kubernetes.io": {
    "namespace": "prod",
    "pod": { "name": "my-app-7d9f8b", "uid": "..." },
    "serviceaccount": { "name": "my-app", "uid": "..." }
  }
}
```

### Who signs the token, and with which key (lab)

Every Kubernetes ServiceAccount token is signed by the cluster's own control plane, with a private
key configured on the cluster.

- **Tokens from the TokenRequest API** (projected volumes, `kubectl create token`): signed by
  **kube-apiserver** with the key given in `--service-account-signing-key-file`. That covers both
  the default API-server token and the custom-audience tokens used by IRSA, Azure Workload Identity
  and EKS Pod Identity.
- **Legacy Secret-based tokens** (`type: kubernetes.io/service-account-token`): signed by
  **kube-controller-manager** with `--service-account-private-key-file`. **(docs)**
- **Verification** uses `--service-account-key-file`, which can hold several public or private
  keys. Old keys stay in it during a rotation, so tokens signed with them keep working until they
  expire. The JWKS endpoint publishes the public half of each.

The one exception to "a key file on the control plane" is new: since Kubernetes 1.32,
kube-apiserver can hand signing to an **external signer** over a Unix socket
(`--service-account-signing-endpoint`), for keys kept in a KMS or HSM. The v1.34.1 binary
describes it as:

```text
--service-account-signing-endpoint string   Path to socket where a external JWT signer is listening. This flag is
                                            mutually exclusive with --service-account-signing-key-file and --serv…
--service-account-signing-key-file string   Path to the file that contains the current private key of the service
                                            account token issuer. The issuer will sign issued ID tokens with this…
--service-account-key-file stringArray      File containing PEM-encoded x509 RSA or ECDSA private or public keys,
                                            used to verify ServiceAccount tokens. …
```

The signer is a plugin that signs, not an identity provider: kube-apiserver still decides what goes
in the token, and the cluster is still the issuer. **(flags: lab, `kube-apiserver --help`; the 1.32
version: docs)**

**Where the key lives:**

- **Self-managed** clusters keep an RSA or ECDSA key pair on the control-plane nodes:
  - **kubeadm:** `/etc/kubernetes/pki/sa.key` and `sa.pub`. **(docs)**
  - **k3s:** `/var/lib/rancher/k3s/server/tls/`, two files (lab):

    ```text
    service.current.key   -----BEGIN RSA PRIVATE KEY-----   → --service-account-signing-key-file   (kube-apiserver signs with it)
                                                            → --service-account-private-key-file   (kube-controller-manager, legacy tokens)
    service.key           -----BEGIN RSA PRIVATE KEY-----   → --service-account-key-file           (verification: current + old keys)
    ```

    Here the two files held the same key, since this cluster's key had never been rotated.
- **Managed** clusters (EKS, AKS, GKE): the provider generates, stores and rotates the private key
  inside the managed control plane. You never see it, only the public keys published at the
  cluster's JWKS endpoint (`/openid/v1/jwks`, or the provider's public issuer URL). **(docs)**

**The `kid` is a hash of the public key.** On the lab cluster, the key ID computed from the
signing key file, as `base64url(SHA-256(DER-encoded public key))`, matches the JWKS and every
token:

```text
$ openssl rsa -in service.current.key -pubout -outform DER | openssl dgst -sha256 -binary | base64url
kid computed from service.current.key:  5q7RSqr-V4vvoweOD0_qATLMWQ8upW61QL-ZiXDdwRc
kid published in /openid/v1/jwks:       5q7RSqr-V4vvoweOD0_qATLMWQ8upW61QL-ZiXDdwRc
kid in a freshly minted token:          5q7RSqr-V4vvoweOD0_qATLMWQ8upW61QL-ZiXDdwRc
```

So a verifier can match a token to its key without any lookup table, and a rotated key
automatically gets a different `kid`.

### Steps 2 and 3, reproduced on the lab cluster

On the Calico lab cluster ([calico-layer3.md](calico-layer3.md), k3s v1.34), a Pod got by hand what
the IRSA webhook would add. There's no AWS here, so step 3's signature check is done in a short
Python script using only the cluster's JWKS, as STS would:

```yaml
spec:
  serviceAccountName: my-app
  containers:
  - name: c
    env:
    - { name: AWS_ROLE_ARN, value: "arn:aws:iam::111122223333:role/my-app" }
    - { name: AWS_WEB_IDENTITY_TOKEN_FILE, value: /var/run/secrets/eks.amazonaws.com/serviceaccount/token }
    volumeMounts: [{ name: aws-iam-token, mountPath: /var/run/secrets/eks.amazonaws.com/serviceaccount, readOnly: true }]
  volumes:
  - name: aws-iam-token
    projected:
      sources:
      - serviceAccountToken: { audience: sts.amazonaws.com, expirationSeconds: 3600, path: token }
```

**The mount** (inside the Pod):

```text
tmpfs on /var/run/secrets/eks.amazonaws.com/serviceaccount type tmpfs (ro,…)
..2026_11_21_22_23_26.2834987253/      ← this version of the files
..data -> ..2026_11_21_22_23_26.…      ← the kubelet writes a new directory, then swaps this link
token -> ..data/token                  ← so a reader never sees a half-written token
```

**The token** the kubelet wrote:

```text
JOSE header  {"alg":"RS256","kid":"5q7RSqr-V4vvoweOD0_qATLMWQ8upW61QL-ZiXDdwRc"}
payload      {"aud":["sts.amazonaws.com"],
              "iss":"https://kubernetes.default.svc.cluster.local",
              "sub":"system:serviceaccount:oidc-demo:my-app",
              "iat":1795299806, "nbf":1795299806, "exp":1795303406,     ← exp − iat = 3600: as requested
              "kubernetes.io":{"namespace":"oidc-demo",
                               "node":{"name":"cal-server","uid":"ed112203-…"},    ← also present in current versions
                               "pod":{"name":"my-app","uid":"02a92f52-…"},
                               "serviceaccount":{"name":"my-app","uid":"fd0a3dae-…"}}}
```

The lifetime is exactly the requested 3600 s. The one-year extension the API server applies to
the default token (see [rbac-and-identity.md](rbac-and-identity.md), "A ServiceAccount is a Pod's
identity") only applies to tokens for the API server itself, not to a cloud audience like this one.

**The signature check, as a cloud IAM does it.** Find the JWKS key with the token's `kid`, then
verify RS256 (RSA PKCS#1 v1.5 over SHA-256 of `header.payload`):

```python
key = next(k for k in jwks["keys"] if k["kid"] == header["kid"])         # from /openid/v1/jwks
n, e = int.from_bytes(b64url(key["n"]), "big"), int.from_bytes(b64url(key["e"]), "big")
em = pow(int.from_bytes(b64url(sig), "big"), e, n).to_bytes(len(b64url(key["n"])), "big")
expected = b"\x00\x01" + b"\xff" * pad + b"\x00" + SHA256_DIGESTINFO + sha256(f"{h}.{p}".encode()).digest()
em == expected
```

```text
signature valid against JWKS key 5q7RSqr-V4vv…: True
same signature, payload changed to sub=system:serviceaccount:prod:admin:  False
```

Only the holder of the cluster's **private** key can produce a valid signature, and anyone with
the **public** JWKS can check it. That's why the cloud needs nothing from the cluster but the
issuer URL, and why a token can't be edited to claim a different ServiceAccount.

In this lab the issuer is `https://kubernetes.default.svc.cluster.local`, which no cloud can reach.
A real setup uses a public issuer (EKS's `oidc.eks.<region>.amazonaws.com/id/…`, AKS's
`--enable-oidc-issuer`), as in the example above. **(docs)**

### Step 3 in the SDKs: the code and the request it sends (code + lab)

When the application initializes the cloud SDK (`DefaultAzureCredential`, the AWS default
credential chain), the SDK reads the injected environment variables and the JWT file, then sends
one HTTPS request to the cloud's token endpoint. Below is the code that does it, from the **Java**
SDKs' source jars on Maven Central:

- **azure-identity 1.19.0**, which hands the token request to **MSAL4J 1.26.0**;
- **AWS SDK for Java v2 2.59.7** (`auth` and `sts` modules).

It also shows the requests captured when a small Java program ran both SDKs' **default credential
chains** against a local fake endpoint, with the env vars set as the webhooks set them and real
projected tokens from the lab cluster.

```java
// the whole application side
var azure = new DefaultAzureCredentialBuilder().disableInstanceDiscovery().build();
azure.getTokenSync(new TokenRequestContext().addScopes("https://management.azure.com/.default"));

var aws = DefaultCredentialsProvider.builder().build();
aws.resolveCredentials();
```

(`disableInstanceDiscovery()` only because the fake endpoint isn't a real Microsoft authority;
against `login.microsoftonline.com` it isn't needed.)

#### Azure (Entra ID): `client_credentials` with a JWT client assertion

`WorkloadIdentityCredential` reads the three variables, and wraps a `ClientAssertionCredential`
whose assertion is the token file (`com/azure/identity/WorkloadIdentityCredential.java`):

```java
String tenantIdInput = CoreUtils.isNullOrEmpty(tenantId)
    ? configuration.get(Configuration.PROPERTY_AZURE_TENANT_ID) : tenantId;             // AZURE_TENANT_ID
String federatedTokenFilePathInput = CoreUtils.isNullOrEmpty(federatedTokenFilePath)
    ? configuration.get(AZURE_FEDERATED_TOKEN_FILE) : federatedTokenFilePath;           // AZURE_FEDERATED_TOKEN_FILE
String clientIdInput = CoreUtils.isNullOrEmpty(clientId)
    ? configuration.get(Configuration.PROPERTY_AZURE_CLIENT_ID) : clientId;             // AZURE_CLIENT_ID
…
private ClientAssertionCredential buildClientAssertionCredential(String tenantId, String clientId,
    String federatedTokenFilePath, IdentityClientOptions identityClientOptions) {
    return new ClientAssertionCredential(clientId, tenantId,
        () -> readFederatedTokenFromFile(federatedTokenFilePath), identityClientOptions);
}

private String readFederatedTokenFromFile(String filePath) {
    byte[] bytes = Files.readAllBytes(Paths.get(filePath));       // read on every token request: picks up the
    return new String(bytes, StandardCharsets.UTF_8).trim();     // kubelet's rotated file, no caching here
}
```

If any of the three variables (or the authority host) is missing, the credential is created
anyway but `getToken` fails with "WorkloadIdentityCredential authentication unavailable", and
`DefaultAzureCredential` moves on to the next credential.

The identity client gives the assertion to MSAL4J as a client credential
(`com/azure/identity/implementation/IdentityClient.java`):

```java
ClientCredentialParameters.Builder builder = ClientCredentialParameters.builder(new HashSet<>(request.getScopes()))
    .tenant(IdentityUtil.resolveTenantId(tenantId, request, options));
if (clientAssertionSupplier != null) {
    builder.clientCredential(ClientCredentialFactory.createFromClientAssertion(clientAssertionSupplier.get()));
}
```

MSAL4J builds the form (`com/microsoft/aad/msal4j/`):

```java
// ClientCredentialRequest.java: the grant
params.put(GrantConstants.GRANT_TYPE_PARAMETER, GrantConstants.CLIENT_CREDENTIALS);         // "client_credentials"

// OAuthAuthorizationGrant.java: added to every grant
this.scopes = new HashSet<>(AbstractMsalAuthorizationGrant.COMMON_SCOPES);                 // openid, profile, offline_access
this.scopes.addAll(scopes);                                                                 // + the scope you asked for
this.params.put(SCOPE_PARAM_NAME, String.join(" ", this.scopes));
this.params.put("client_info", "1");

// TokenRequestExecutor.java: the client authentication
} else if (credentialToUse instanceof ClientAssertion) {
    …
    addJWTBearerAssertionParams(queryParameters, clientAssertion.assertion(options));
}
private void addJWTBearerAssertionParams(Map<String, String> queryParameters, String assertion) {
    queryParameters.put("client_assertion", assertion);                                      // the projected JWT
    queryParameters.put("client_assertion_type", ClientAssertion.ASSERTION_TYPE_JWT_BEARER); // RFC 7523
}
// ClientAssertion.java
static final String ASSERTION_TYPE_JWT_BEARER = "urn:ietf:params:oauth:client-assertion-type:jwt-bearer";
```

What the endpoint received:

```text
POST /aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee/oauth2/v2.0/token            ← <AZURE_AUTHORITY_HOST>/<AZURE_TENANT_ID>/oauth2/v2.0/token
Content-Type: application/x-www-form-urlencoded; charset=UTF-8
User-Agent: azsdk-java-azure-identity/1.19.0 …     x-client-SKU: MSAL.Java     x-client-VER: 1.26.0

grant_type            = client_credentials
client_info           = 1                                                  ← MSAL4J adds it to every request
scope                 = https://management.azure.com/.default openid profile offline_access
client_id             = 11111111-2222-3333-4444-555555555555               ← AZURE_CLIENT_ID
client_assertion      = eyJhbGciOiJSUzI1NiIsImtpZCI6IjVxN1JTcXItVjR2…     ← identical to the token file, byte for byte
client_assertion_type = urn:ietf:params:oauth:client-assertion-type:jwt-bearer
```

The Kubernetes JWT takes the place of a client secret or certificate. Entra ID checks it against
the app's **federated identity credential** (issuer, subject `system:serviceaccount:<ns>:<sa>`,
audience `api://AzureADTokenExchange`) and returns an access token for the requested scope.
The Java request carries more than the Python SDK's (which sends only the five fields):
`client_info=1` and the `openid profile offline_access` scopes come from MSAL4J, which builds the
same base grant for every flow.

#### AWS (STS): `AssumeRoleWithWebIdentity`

The default chain (`software/amazon/awssdk/auth/credentials/DefaultCredentialsProvider.java`):

```java
private DefaultCredentialsProvider(Builder builder) {
    this.profileFile = builder.profileFile;
    this.profileName = builder.profileName;
    this.reuseLastProviderEnabled = builder.reuseLastProviderEnabled;          // Builder default: true
    this.asyncCredentialUpdateEnabled = builder.asyncCredentialUpdateEnabled;  // Builder default: false
    this.providerChain = createChain(builder);
}

/** @deprecated … a singleton instance which can cause issues if one client closes the provider while
 *  others are still using it. Use {@code builder().build()} to create independent instances … */
@Deprecated
public static DefaultCredentialsProvider create() {
    return DEFAULT_CREDENTIALS_PROVIDER;
}

private static LazyAwsCredentialsProvider createChain(Builder builder) {
    boolean asyncCredentialUpdateEnabled = builder.asyncCredentialUpdateEnabled;
    boolean reuseLastProviderEnabled = builder.reuseLastProviderEnabled;

    return LazyAwsCredentialsProvider.create(() -> {                            // built on first resolveCredentials()
        AwsCredentialsProvider[] credentialsProviders = new AwsCredentialsProvider[] {
            SystemPropertyCredentialsProvider.create(),                           // -Daws.accessKeyId …
            EnvironmentVariableCredentialsProvider.create(),                      // AWS_ACCESS_KEY_ID …
            WebIdentityTokenFileCredentialsProvider.builder()                     // AWS_WEB_IDENTITY_TOKEN_FILE + AWS_ROLE_ARN  ← IRSA
                                                   .asyncCredentialUpdateEnabled(asyncCredentialUpdateEnabled)
                                                   .build(),
            ProfileCredentialsProvider.builder()                                  // ~/.aws/config, ~/.aws/credentials
                                      .profileFile(builder.profileFile)
                                      .profileName(builder.profileName)
                                      .build(),
            ContainerCredentialsProvider.builder()                                // ECS, and EKS Pod Identity's agent
                                        .asyncCredentialUpdateEnabled(asyncCredentialUpdateEnabled)
                                        .build(),
            InstanceProfileCredentialsProvider.builder()                          // EC2 instance metadata (the node's role)
                                              .asyncCredentialUpdateEnabled(asyncCredentialUpdateEnabled)
                                              .profileFile(builder.profileFile)
                                              .profileName(builder.profileName)
                                              .build()
        };

        return AwsCredentialsProviderChain.builder()
                                          .reuseLastProviderEnabled(reuseLastProviderEnabled)
                                          .credentialsProviders(credentialsProviders)
                                          .build();
    });
}
```

And the loop that walks it (`AwsCredentialsProviderChain.java`):

```java
public AwsCredentials resolveCredentials() {
    if (reuseLastProviderEnabled && lastUsedProvider != null) {
        return CredentialUtils.toCredentials(CompletableFutureUtils.joinLikeSync(lastUsedProvider.resolveIdentity()));
    }
    List<String> exceptionMessages = null;
    for (IdentityProvider<? extends AwsCredentialsIdentity> provider : credentialsProviders) {
        try {
            AwsCredentialsIdentity credentials = CompletableFutureUtils.joinLikeSync(provider.resolveIdentity());
            lastUsedProvider = provider;                                        // remembered for every later call
            return CredentialUtils.toCredentials(credentials);
        } catch (RuntimeException e) {
            … exceptionMessages.add(provider + ": " + e.getMessage());          // logged at debug, then the next provider
        }
    }
    throw SdkClientException.builder()
        .message("Unable to load credentials from any of the providers in the chain " + this + " : " + exceptionMessages)
        .build();
}
```

What this means for IRSA:

1. **The chain is built lazily.** `LazyAwsCredentialsProvider` constructs the providers on the
   first `resolveCredentials()`. That's when `WebIdentityTokenFileCredentialsProvider` reads
   `AWS_WEB_IDENTITY_TOKEN_FILE` and `AWS_ROLE_ARN`, not at `build()`.
2. **First success wins; failures are skipped silently.** A provider that throws is logged at debug
   level and the loop moves on. You only see an error when every provider fails.
3. **The choice is made once.** `reuseLastProviderEnabled` is `true` by default, so after the first
   success only `lastUsedProvider` is ever called again. If the web-identity provider failed on
   that first call (no `sts` module, variables not set) and the instance profile answered, the
   client keeps the **node's** role for its lifetime.
4. **`asyncCredentialUpdateEnabled`** (default `false`) goes to the three refreshing providers.
   With `true`, they refresh in a background thread before expiry instead of on the calling thread.
5. **Use `builder().build()`, not `create()`.** `create()` returns a shared singleton, which breaks
   every user when one of them closes it; it's deprecated for that reason.

The web-identity provider reads the variables and asks for an STS-backed provider
(`WebIdentityTokenFileCredentialsProvider.java`):

```java
webIdentityTokenFile = builder.webIdentityTokenFile != null ? builder.webIdentityTokenFile
    : Paths.get(trim(SdkSystemSetting.AWS_WEB_IDENTITY_TOKEN_FILE.getStringValueOrThrow()));
roleArn = builder.roleArn != null ? builder.roleArn
    : trim(SdkSystemSetting.AWS_ROLE_ARN.getStringValueOrThrow());
roleSessionName = builder.roleSessionName != null ? builder.roleSessionName
    : SdkSystemSetting.AWS_ROLE_SESSION_NAME.getStringValue().orElse(null);
…
credentialsProvider = WebIdentityCredentialsUtils.factory().create(credentialProperties);
```

That factory is loaded **by reflection from the `sts` module** (`auth/credentials/internal/WebIdentityCredentialsUtils.java`):

```java
private static final String STS_WEB_IDENTITY_CREDENTIALS_PROVIDER_FACTORY =
    "software.amazon.awssdk.services.sts.internal.StsWebIdentityCredentialsProviderFactory";

public static WebIdentityTokenCredentialsProviderFactory factory() {
    try {
        Class<?> stsCredentialsProviderFactory = ClassLoaderHelper.loadClass(STS_WEB_IDENTITY_CREDENTIALS_PROVIDER_FACTORY, …);
        return (WebIdentityTokenCredentialsProviderFactory) stsCredentialsProviderFactory.getConstructor().newInstance();
    } catch (ClassNotFoundException e) {
        String message = "To use web identity tokens, the 'sts' service module must be on the class path.";
        …
```

**So IRSA only works in Java if `software.amazon.awssdk:sts` is a dependency.** Tested by running
the same program with the `sts` jar removed from the classpath (and instance metadata disabled):

```text
SdkClientException: Unable to load credentials from any of the providers in the chain …
  SystemPropertyCredentialsProvider(): Unable to load credentials from system settings. …
  EnvironmentVariableCredentialsProvider(): Unable to load credentials from system settings. …
  WebIdentityTokenFileCredentialsProvider(): To use web identity tokens, the 'sts' service module must be on the class path.
  …                                                ← and on to the container and instance-metadata providers
```

The web-identity provider is skipped, not fatal, and the chain carries on. Here nothing else
answered, so the call failed. On an EC2 node, the instance-metadata provider would answer with the
**node's** IAM role, so the Pod would quietly run with the node's permissions instead of its own.
**(that last part: from the chain order, not run)**

The STS factory builds an **anonymous** (unsigned) STS client and the request
(`services/sts/internal/StsWebIdentityCredentialsProviderFactory.java`):

```java
String sessionName = roleSessionName != null ? roleSessionName : "aws-sdk-java-" + System.currentTimeMillis();
this.stsClient = StsClient.builder()
                          .applyMutation(this::configureEndpoint)
                          .credentialsProvider(AnonymousCredentialsProvider.create())   // no AWS credentials yet: unsigned
                          .overrideConfiguration(o -> o.retryPolicy(r -> r.retryCondition(retryCondition)))
                          .build();
AssumeRoleWithWebIdentityRequest.Builder requestBuilder = AssumeRoleWithWebIdentityRequest.builder()
    .roleArn(credentialProperties.roleArn())
    .roleSessionName(sessionName);
```

and the token is read from the file each time the credentials are refreshed
(`services/sts/internal/AssumeRoleWithWebIdentityRequestSupplier.java`):

```java
public AssumeRoleWithWebIdentityRequest get() {
    return request.toBuilder().webIdentityToken(getToken(webIdentityTokenFile)).build();
}
private String getToken(Path file) {
    try (InputStream webIdentityTokenStream = Files.newInputStream(file)) {
        return IoUtils.toUtf8String(webIdentityTokenStream);
    } …
}
```

What STS received:

```text
POST /
Content-Type: application/x-www-form-urlencoded; charset=utf-8
User-Agent: aws-sdk-java/2.59.7 md/io#sync md/http#Apache5 ua/2.1 api/STS#2.59.x … lang/java#25.0.5.1
(no Authorization header, no X-Amz-Date: the request is unsigned)

Action           = AssumeRoleWithWebIdentity
Version          = 2011-06-15
RoleArn          = arn:aws:iam::111122223333:role/my-app                  ← AWS_ROLE_ARN
RoleSessionName  = aws-sdk-java-1795300360799                             ← default when AWS_ROLE_SESSION_NAME isn't set
WebIdentityToken = eyJhbGciOiJSUzI1NiIsImtpZCI6IjVxN1JTcXItVjR2…           ← identical to the token file, byte for byte
```

STS checks the token against the IAM OIDC provider and the role's trust policy (§3) and returns
temporary `AccessKeyId`, `SecretAccessKey` and `SessionToken`. In the lab run,
`resolveCredentials()` returned `ASIAFAKE`, the fake endpoint's key.

**Not tested:** the real Entra ID and STS endpoints. The responses came from the local fake
endpoint, so what each cloud checks is from the docs and the emulator code in §3 and §4.

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

## 2. Azure Workload Identity: `/var/run/secrets/azure/wi/<hash>/token/azure-identity-token`

This one comes from the **Azure Workload Identity mutating webhook**, which runs on AKS when the workload identity feature is on. Two things opt a Pod in:

- **the Pod label `azure.workload.identity/use: "true"`** is what makes the webhook change the Pod;
- **the ServiceAccount annotation `azure.workload.identity/client-id: <client ID>`** says which managed identity or app registration to use. The webhook copies it into `AZURE_CLIENT_ID`.

It then adds its own projected token volume, a mount for it, and some environment variables.

A real Pod spec always splits a volume into two parts:

- **`volumes`** (at Pod level) says **what** the data is: here, a projected ServiceAccount token.
- **`volumeMounts`** (in each container) says **where** that container sees it: `mountPath`.

The two are linked by `name`. This is what the Azure webhook adds to the Pod in versions up to
v1.5 (**docs**; v1.6 changed the names and path, see "What v1.6.1 added on the lab cluster" below):

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

### What v1.6.1 added on the lab cluster (lab + code)

All output below is from one run on the k3s + Calico lab cluster (v1.34.1+k3s1), copied as printed.

**1. Install the webhook.** Helm isn't installed on the host, so the chart is rendered by a helm
container and the result applied with kubectl. `azureTenantID` is a dummy: nothing here talks to
Entra ID. The chart brings its own certificate rotation, so cert-manager isn't needed.

```console
$ docker run --rm --entrypoint sh -v $PWD:/out alpine/helm:3.17.3 -c '
    helm repo add azure-workload-identity https://azure.github.io/azure-workload-identity/charts >/dev/null &&
    helm search repo azure-workload-identity &&
    helm template workload-identity-webhook azure-workload-identity/workload-identity-webhook \
      --namespace azure-workload-identity-system \
      --set azureTenantID=00000000-0000-0000-0000-000000000000 > /out/rendered.yaml'
NAME                                             	CHART VERSION	APP VERSION	DESCRIPTION
azure-workload-identity/workload-identity-webhook	1.6.1        	v1.6.1     	A Helm chart to install the azure-workload-iden...

$ grep -E '^kind:|image:' rendered.yaml
kind: PodDisruptionBudget
kind: ServiceAccount
kind: Secret
kind: ConfigMap
kind: ClusterRole
kind: ClusterRoleBinding
kind: Role
kind: RoleBinding
kind: Service
kind: Deployment
        image: 'mcr.microsoft.com/oss/v2/azure/workload-identity/webhook:v1.6.1'
kind: MutatingWebhookConfiguration

$ kubectl create ns azure-workload-identity-system
$ kubectl apply -n azure-workload-identity-system -f rendered.yaml
namespace/azure-workload-identity-system created
poddisruptionbudget.policy/azure-wi-webhook-controller-manager created
serviceaccount/azure-wi-webhook-admin created
secret/azure-wi-webhook-server-cert created
configmap/azure-wi-webhook-config created
clusterrole.rbac.authorization.k8s.io/azure-wi-webhook-manager-role created
clusterrolebinding.rbac.authorization.k8s.io/azure-wi-webhook-manager-rolebinding created
role.rbac.authorization.k8s.io/azure-wi-webhook-manager-role created
rolebinding.rbac.authorization.k8s.io/azure-wi-webhook-manager-rolebinding created
service/azure-wi-webhook-webhook-service created
deployment.apps/azure-wi-webhook-controller-manager created
mutatingwebhookconfiguration.admissionregistration.k8s.io/azure-wi-webhook-mutating-webhook-configuration created

$ kubectl -n azure-workload-identity-system rollout status deploy/azure-wi-webhook-controller-manager
Waiting for deployment "azure-wi-webhook-controller-manager" rollout to finish: 0 of 2 updated replicas are available...
Waiting for deployment "azure-wi-webhook-controller-manager" rollout to finish: 1 of 2 updated replicas are available...
deployment "azure-wi-webhook-controller-manager" successfully rolled out
```

The rendered `Secret azure-wi-webhook-server-cert` has no `data`, and the rendered
MutatingWebhookConfiguration has no `caBundle`. The webhook's built-in cert-controller rotator
generates the certificate, stores it in that Secret, and patches the CA into `caBundle` **(lab:
rendered YAML; code comment: "webhook server certs generated and rotated as part of cert-controller
rotator")**.

**2. When the API server calls it.** The MutatingWebhookConfiguration the chart installed:

```console
$ kubectl get mutatingwebhookconfiguration azure-wi-webhook-mutating-webhook-configuration \
    -o jsonpath='{.webhooks[0].objectSelector}{"\n"}{.webhooks[0].failurePolicy}{"\n"}{.webhooks[0].rules}{"\n"}'
{"matchLabels":{"azure.workload.identity/use":"true"}}
Fail
[{"apiGroups":[""],"apiVersions":["v1"],"operations":["CREATE"],"resources":["pods"],"scope":"*"}]
```

- **`objectSelector`**: only Pods with the label are sent to the webhook. The API server does this
  filtering; the webhook never sees other Pods.
- **`failurePolicy: Fail`**: if the webhook is down, creating a labelled Pod fails, instead of the Pod
  starting without a token. The IRSA webhook does the opposite (§3).
- **`operations: CREATE`**: only new Pods. Adding the label to a running Pod changes nothing, and
  Pods created before the webhook was installed keep running without a token.

**3. Create a ServiceAccount and a labelled Pod:**

```console
$ kubectl create ns azwi-demo
$ kubectl apply -f - <<'EOF'
apiVersion: v1
kind: ServiceAccount
metadata:
  name: my-app
  namespace: azwi-demo
  annotations:
    azure.workload.identity/client-id: 11111111-2222-3333-4444-555555555555
---
apiVersion: v1
kind: Pod
metadata:
  name: app
  namespace: azwi-demo
  labels:
    azure.workload.identity/use: "true"
spec:
  serviceAccountName: my-app
  containers:
  - name: app
    image: busybox:1.36
    command: ["sleep", "3600"]
EOF
namespace/azwi-demo created
serviceaccount/my-app created
pod/app created
```

**4. The Pod as stored.** The filter only drops five lines of defaults to keep it short
(`terminationMessage*`, `schedulerName`, `preemptionPolicy`, `priority`, `enableServiceLinks`):

```console
$ kubectl -n azwi-demo get pod app -o yaml | sed -n '/^spec:/,/^status:/p' \
    | grep -v -E 'terminationMessage|schedulerName|preemptionPolicy|priority:|enableServiceLinks'
spec:
  containers:
  - command:
    - sleep
    - "3600"
    env:
    - name: AZURE_CLIENT_ID
      value: 11111111-2222-3333-4444-555555555555
    - name: AZURE_TENANT_ID
      value: 00000000-0000-0000-0000-000000000000
    - name: AZURE_FEDERATED_TOKEN_FILE
      value: /var/run/secrets/azure/wi/a172cedcae47474b615c54d510a5d8/token/azure-identity-token
    - name: AZURE_AUTHORITY_HOST
      value: https://login.microsoftonline.com/
    image: busybox:1.36
    imagePullPolicy: IfNotPresent
    name: app
    resources: {}
    volumeMounts:
    - mountPath: /var/run/secrets/kubernetes.io/serviceaccount
      name: kube-api-access-prf7l
      readOnly: true
    - mountPath: /var/run/secrets/azure/wi/a172cedcae47474b615c54d510a5d8
      name: azure-workload-identity-reserved-a172cedcae47474b615c54d510a5d8
      readOnly: true
  dnsPolicy: ClusterFirst
  nodeName: cal-server
  restartPolicy: Always
  securityContext: {}
  serviceAccount: my-app
  serviceAccountName: my-app
  terminationGracePeriodSeconds: 30
  tolerations:
  - effect: NoExecute
    key: node.kubernetes.io/not-ready
    operator: Exists
    tolerationSeconds: 300
  - effect: NoExecute
    key: node.kubernetes.io/unreachable
    operator: Exists
    tolerationSeconds: 300
  volumes:
  - name: kube-api-access-prf7l
    projected:
      defaultMode: 420
      sources:
      - serviceAccountToken:
          expirationSeconds: 3607
          path: token
      - configMap:
          items:
          - key: ca.crt
            path: ca.crt
          name: kube-root-ca.crt
      - downwardAPI:
          items:
          - fieldRef:
              apiVersion: v1
              fieldPath: metadata.namespace
            path: namespace
  - name: azure-workload-identity-reserved-a172cedcae47474b615c54d510a5d8
    projected:
      defaultMode: 420
      sources:
      - serviceAccountToken:
          audience: api://AzureADTokenExchange
          expirationSeconds: 3600
          path: token/azure-identity-token
status:
```

Reading it against what was submitted (`command`, `image`, `name`, `serviceAccountName`):

| In the stored Pod | Added by |
|---|---|
| `env`: the four `AZURE_*` variables | **the Azure webhook** |
| `volumeMounts[1]` and `volumes[1]` (`azure-workload-identity-reserved-…`) | **the Azure webhook** |
| `volumeMounts[0]` and `volumes[0]` (`kube-api-access-prf7l`), the default token from §1 | the ServiceAccount admission plugin, on every Pod |
| `serviceAccount`, `dnsPolicy`, `restartPolicy`, `tolerations`, `defaultMode: 420`, … | API server defaults and the DefaultTolerationSeconds plugin |
| `nodeName: cal-server` | the scheduler, after admission |

Where each `AZURE_*` value comes from:

| Variable | Value in the lab | Source |
|---|---|---|
| `AZURE_CLIENT_ID` | `11111111-2222-3333-4444-555555555555` | the ServiceAccount annotation `azure.workload.identity/client-id` |
| `AZURE_TENANT_ID` | `00000000-0000-0000-0000-000000000000` | the webhook's config (chart value `azureTenantID`); a ServiceAccount annotation `azure.workload.identity/tenant-id` overrides it **(code, `getTenantID`)** |
| `AZURE_FEDERATED_TOKEN_FILE` | `/var/run/secrets/azure/wi/<hash>/token/azure-identity-token` | mount path + the projected file's `path` |
| `AZURE_AUTHORITY_HOST` | `https://login.microsoftonline.com/` | the webhook's config (the cloud's Entra ID login host) |

The two audiences show side by side: the default token (`kube-api-access-prf7l`) has no `audience`,
so it gets the API server's own; the Azure one says `api://AzureADTokenExchange`. Both are tokens
for the same ServiceAccount `my-app`. The default asks for 3607 s: the ServiceAccount admission
plugin writes `WarnOnlyBoundTokenExpirationSeconds = 60*60 + 7`, an odd value the API server
recognises, so it can extend that token and log warnings when it's used late (see
[rbac-and-identity.md](rbac-and-identity.md)) **(code, `plugin/pkg/admission/serviceaccount`,
`pkg/serviceaccount/claims.go`)**. The Azure one asks for a plain 3600 s.

**5. No annotations or labels added.** The Pod's annotations are only Calico's and kubectl's:

```console
$ kubectl -n azwi-demo get pod app -o jsonpath='{.metadata.annotations}'
{"cni.projectcalico.org/containerID":"018b8bc99648c369c1d8dc412b35d33f1001ab61f69163e20f298f2529635e40","cni.projectcalico.org/podIP":"10.244.70.10/32","cni.projectcalico.org/podIPs":"10.244.70.10/32","kubectl.kubernetes.io/last-applied-configuration":"{\"apiVersion\":\"v1\",\"kind\":\"Pod\",\"metadata\":{\"annotations\":{},\"labels\":{\"azure.workload.identity/use\":\"true\"},\"name\":\"app\",\"namespace\":\"azwi-demo\"},\"spec\":{\"containers\":[{\"command\":[\"sleep\",\"3600\"],\"image\":\"busybox:1.36\",\"name\":\"app\"}],\"serviceAccountName\":\"my-app\"}}\n"}
```

**6. Without the label: nothing.** Same ServiceAccount, no label:

```console
$ kubectl -n azwi-demo run plain --image=busybox:1.36 \
    --overrides='{"spec":{"serviceAccountName":"my-app"}}' -- sleep 3600
pod/plain created

$ kubectl -n azwi-demo get pod plain -o jsonpath='{.spec.containers[0].env}|{.spec.volumes[*].name}{"\n"}'
|kube-api-access-prnsj
```

Empty `env`, and only the default token volume. The ServiceAccount annotation alone does nothing.

**7. Inside the container:**

```console
$ kubectl -n azwi-demo exec app -- ls -lR /var/run/secrets/azure/
/var/run/secrets/azure/:
total 4
drwxr-xr-x    3 root     root          4096 Nov 23 00:41 wi

/var/run/secrets/azure/wi:
total 0
drwxrwxrwt    3 root     root           100 Nov 23 00:41 a172cedcae47474b615c54d510a5d8

/var/run/secrets/azure/wi/a172cedcae47474b615c54d510a5d8:
total 0
lrwxrwxrwx    1 root     root            12 Nov 23 00:41 token -> ..data/token
```

The mount point (`drwxrwxrwt`, size 100) is the tmpfs the kubelet creates for the projected volume.
`token` is a symlink to `..data/token`. `..data` is itself a symlink to a timestamped directory that
the kubelet swaps atomically when it rewrites the token. `ls` hides the dot names and `-R` doesn't
descend into symlinked directories, so the file `azure-identity-token` inside isn't listed. It's
there: `cat $AZURE_FEDERATED_TOKEN_FILE` prints the JWT.

**8. Where the hash comes from.** It's the first 30 hex characters of SHA-256 of the Pod's name:

```console
$ printf app | shasum -a 256 | cut -c1-30
a172cedcae47474b615c54d510a5d8
```

The code (`pkg/webhook/webhook.go`, v1.6.1):

```go
podName := pod.GetName()
if podName == "" {
	podName = pod.GetGenerateName() + " (prefix)"
}
...
func volumeHash(podName string) string {
	hash := sha256.Sum256([]byte(podName))
	return fmt.Sprintf("%x", hash)[:63-len(ProjectedVolumeNamePrefix)] // 63 is the max length for volume names
}
```

63 minus the 33 characters of `azure-workload-identity-reserved-` is 30. A Pod from a Deployment
has no name yet when admission runs, only `generateName`, so the hash is of `"<generateName> (prefix)"`.
Checked with a Deployment `web` (ServiceAccount `my-app`, label in the template): the Pod
`web-97df77db5-lmxxf` was stored with

```text
AZURE_FEDERATED_TOKEN_FILE=/var/run/secrets/azure/wi/1e35a93ae1a02b3641b1cce2e5c631/token/azure-identity-token
volume: azure-workload-identity-reserved-1e35a93ae1a02b3641b1cce2e5c631
```

```console
$ printf 'web-97df77db5- (prefix)' | shasum -a 256 | cut -c1-30
1e35a93ae1a02b3641b1cce2e5c631
```

So every Pod of one ReplicaSet gets the same path, and a new ReplicaSet (new `pod-template-hash`)
gets a new one. The source comment gives the reason for the hash: if another webhook sets
`metadata.name` and this webhook runs again (`reinvocationPolicy: IfNeeded`), the second mount lands
at a different path instead of a duplicate one, which the API server would reject **(code,
`buildVolumeMountPath`)**.

**9. What changed from v1.5.** The constants in `pkg/webhook/consts.go` **(code)**:

```go
// v1.5.1
TokenFilePathName  = "azure-identity-token"
TokenFileMountPath = "/var/run/secrets/azure/tokens"

// v1.6.0 and v1.6.1
ProjectedVolumeNamePrefix = "azure-workload-identity-reserved-"
TokenFilePath             = "token/azure-identity-token"
VolumeMountPath           = "/var/run/secrets/azure/wi"   // v1.6.1 calls it ProjectedVolumePathPrefix
```

Code or config that hard-codes `/var/run/secrets/azure/tokens/azure-identity-token` breaks on v1.6+.
Read `AZURE_FEDERATED_TOKEN_FILE` instead, as the SDKs do.

### How `volumes`, `volumeMounts`, `mountPath` and `path` fit together

Think of it as two separate questions, answered in two separate places. The example is the v1.5
layout because it's the simplest. v1.6 puts the file one level deeper
(`path: token/azure-identity-token`) and mounts the volume at `/var/run/secrets/azure/wi/<hash>`.

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

#### The webhook side of identity bindings (code)

The webhook code for this is the "custom token endpoint". It is in v1.6.1 and unchanged at commit
`6bc8918` (v1.6.3), in `pkg/webhook/webhook.go`, `pkg/config/config.go` and `cmd/webhook/main.go`.
It's off unless the webhook is started with both flags:

```text
--custom-token-endpoint-annotation-suffix=<suffix>   Pod annotation becomes azure.workload.identity/use-<suffix>
--custom-token-endpoint-audience=<audience>          audience of the Pod's token; must not be api://AzureADTokenExchange
```

Setting only one of the two, or reusing the default audience, stops the webhook at startup. The SDK
README's annotation `azure.workload.identity/use-identity-binding` matches a suffix of
`identity-binding`. That's an inference: AKS's own flag values weren't seen.

For a Pod that has the label **and** that annotation set to `true` (any case):

- **A different token.** The projected token's audience is the custom audience instead of
  `api://AzureADTokenExchange`. It's still at the same path, `token/azure-identity-token`.
- **A CA in the same volume.** The proxy's CA is added to the projected volume at `ca-cert/ca.crt`.
  It comes from exactly one source, configured on the webhook (setting more than one is a startup
  error):
  - `AZURE_KUBERNETES_CA_DATA`: inline PEM, passed to the Pod as an environment variable;
  - `AZURE_KUBERNETES_CA_CONFIGMAP_NAME`: a ConfigMap's `ca.crt` key, projected into the volume;
  - `AZURE_KUBERNETES_CA_CTB_SIGNER_NAME` (plus an optional `…_CTB_LABEL_SELECTOR`): a
    `ClusterTrustBundle` projection.
- **Four more environment variables**, `AZURE_KUBERNETES_TOKEN_PROXY`, `…_SNI_NAME`, `…_CA_FILE`,
  `…_CA_DATA`, each added only if its value is non-empty. `…_CA_FILE` is always the path
  `<mount>/ca-cert/ca.crt`, so it's always added. With `AZURE_KUBERNETES_CA_DATA` configured, the
  Pod gets both `…_CA_FILE` (pointing at a file the volume doesn't contain) and `…_CA_DATA`. The
  azure-identity README above says to set only one of them. Not tested.

#### Other webhook options the lab didn't use (code)

From the same files, v1.6.1 to v1.6.3:

| Where | Annotation | Effect |
|---|---|---|
| ServiceAccount | `azure.workload.identity/tenant-id` | `AZURE_TENANT_ID`; without it, the webhook's own `AZURE_TENANT_ID` (the cluster's tenant) |
| Pod, then ServiceAccount | `azure.workload.identity/service-account-token-expiration` | the token's `expirationSeconds`. The Pod's annotation wins over the ServiceAccount's; default 3600 |
| Pod | `azure.workload.identity/skip-containers` | containers (separated by `;`) that get no env vars and no mount |
| Pod | `azure.workload.identity/inject-proxy-sidecar` | add the IMDS proxy (init container plus sidecar); only its presence is checked; refused for `hostNetwork` Pods ([azure.md](azure.md), "Migrating from aad-pod-identity") |
| Pod | `azure.workload.identity/proxy-sidecar-port` | the proxy's port, default 8000 |

- **Bad values refuse the Pod.** An expiration outside 3600 to 86400 seconds, or not a number,
  makes the webhook return an error, so the Pod isn't created. That's stricter than the IRSA
  webhook, which ignores a non-numeric value and has a minimum of 600 and no maximum
  ([irsa-walkthrough.md](irsa-walkthrough.md) §4.3).
- **Existing values are kept.** An environment variable the container already sets is left alone,
  and one whose value would be empty is not added. A ServiceAccount without `client-id` still gets
  the volume and the other variables, but no `AZURE_CLIENT_ID`.
- **v1.6.3 stops dropping unknown Pod fields.** In v1.6.1 the webhook re-serialised the whole Pod
  from its compiled `corev1.Pod` type and returned the difference from the original. If the API
  server knew Pod fields that the webhook's Kubernetes library didn't, that difference removed
  them. v1.6.3 builds a strategic merge patch of only its own changes and applies that to the raw
  Pod, so unknown fields survive (`mutatedPodResponse`).

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

### What the webhook added on the lab cluster (lab + code)

The webhook is open source and runs on any cluster. All output below is from one run on the
k3s + Calico lab cluster (v1.34.1+k3s1), copied as printed.

**1. Install the webhook.** The repo's `deploy/` manifests (`auth.yaml`, `deployment-base.yaml`,
`service.yaml`, `mutatingwebhook.yaml`) with three changes:

- the `IMAGE` placeholder set to `public.ecr.aws/eks/amazon-eks-pod-identity-webhook:v0.6.19`
  (Docker Hub's `amazon/amazon-eks-pod-identity-webhook` stops at v0.6.7);
- cert-manager replaced by a self-signed certificate from `openssl`, stored in the Secret the
  Deployment mounts at `/etc/webhook/certs`;
- the cert-manager annotation `cert-manager.io/inject-ca-from` replaced by that certificate in
  `caBundle`, so the API server trusts the webhook's TLS.

```console
$ openssl req -x509 -newkey rsa:2048 -nodes -days 30 -keyout aws.key -out aws.crt \
    -subj /CN=pod-identity-webhook.default.svc \
    -addext "subjectAltName=DNS:pod-identity-webhook.default.svc,DNS:pod-identity-webhook.default,DNS:pod-identity-webhook"
$ kubectl create secret tls pod-identity-webhook-cert --cert=aws.crt --key=aws.key
secret/pod-identity-webhook-cert created

$ kubectl apply -f all.yaml        # auth + deployment + service + webhook config with caBundle
serviceaccount/pod-identity-webhook created
role.rbac.authorization.k8s.io/pod-identity-webhook created
rolebinding.rbac.authorization.k8s.io/pod-identity-webhook created
clusterrole.rbac.authorization.k8s.io/pod-identity-webhook created
clusterrolebinding.rbac.authorization.k8s.io/pod-identity-webhook created
deployment.apps/pod-identity-webhook created
service/pod-identity-webhook created
mutatingwebhookconfiguration.admissionregistration.k8s.io/pod-identity-webhook created

$ kubectl rollout status deploy/pod-identity-webhook
Waiting for deployment "pod-identity-webhook" rollout to finish: 0 of 1 updated replicas are available...
deployment "pod-identity-webhook" successfully rolled out

$ kubectl logs deploy/pod-identity-webhook
W1123 00:37:53.947040       1 client_config.go:683] Neither --kubeconfig nor --master was specified.  Using the inClusterConfig.  This might not work.
I1123 00:37:53.947957       1 main.go:337] Creating server
I1123 00:37:53.947976       1 main.go:367] Waiting for webhook and metrics servers to shutdown...
I1123 00:37:53.947984       1 main.go:361] Listening on :9999 for metrics
I1123 00:37:53.948820       1 main.go:353] Listening on :443
```

The webhook's flags, as shipped in `deployment-base.yaml`:

```text
/webhook --in-cluster=false --namespace=default --service-name=pod-identity-webhook
         --annotation-prefix=eks.amazonaws.com --token-audience=sts.amazonaws.com --logtostderr
```

`--annotation-prefix` is why the ServiceAccount annotation is `eks.amazonaws.com/role-arn`, and
`--token-audience` is the `audience` it puts in the token volume. The ClusterRole lets it
`get/watch/list` ServiceAccounts: it keeps a cache of them, because the role ARN is on the
ServiceAccount, not on the Pod it's asked about.

The shipped MutatingWebhookConfiguration (from `mutatingwebhook.yaml`):

```yaml
webhooks:
- name: pod-identity-webhook.amazonaws.com
  failurePolicy: Ignore
  clientConfig:
    service:
      name: pod-identity-webhook
      namespace: default
      path: "/mutate"
  objectSelector:
    matchExpressions:
      - key: eks.amazonaws.com/skip-pod-identity-webhook
        operator: "DoesNotExist"
  rules:
  - operations: [ "CREATE" ]
    apiGroups: [""]
    apiVersions: ["v1"]
    resources: ["pods"]
```

Compared with Azure's (§2):

- **The `objectSelector` is inverted.** Every new Pod in the cluster is sent to this webhook unless
  it has the label `eks.amazonaws.com/skip-pod-identity-webhook`. The webhook then checks the Pod's
  ServiceAccount and changes nothing if it has no `role-arn` annotation. Azure's sends only labelled
  Pods.
- **`failurePolicy: Ignore`.** If the webhook is down, Pods are still created, just without the token
  or env, and the SDK falls through to the next credential provider (often the node's role via IMDS).
  Azure's is `Fail`.

**2. Create an annotated ServiceAccount and a plain Pod** (no label, no env, no volumes):

```console
$ kubectl create ns irsa-demo
namespace/irsa-demo created
$ kubectl apply -f - <<'EOF'
apiVersion: v1
kind: ServiceAccount
metadata:
  name: my-app
  namespace: irsa-demo
  annotations:
    eks.amazonaws.com/role-arn: arn:aws:iam::111122223333:role/my-app
---
apiVersion: v1
kind: Pod
metadata:
  name: app
  namespace: irsa-demo
spec:
  serviceAccountName: my-app
  containers:
  - name: app
    image: busybox:1.36
    command: ["sleep", "3600"]
EOF
serviceaccount/my-app created
pod/app created
```

**3. The Pod as stored**, unfiltered:

```console
$ kubectl -n irsa-demo get pod app -o yaml | sed -n '/^spec:/,/^status:/p'
spec:
  containers:
  - command:
    - sleep
    - "3600"
    env:
    - name: AWS_ROLE_ARN
      value: arn:aws:iam::111122223333:role/my-app
    - name: AWS_WEB_IDENTITY_TOKEN_FILE
      value: /var/run/secrets/eks.amazonaws.com/serviceaccount/token
    image: busybox:1.36
    imagePullPolicy: IfNotPresent
    name: app
    resources: {}
    terminationMessagePath: /dev/termination-log
    terminationMessagePolicy: File
    volumeMounts:
    - mountPath: /var/run/secrets/kubernetes.io/serviceaccount
      name: kube-api-access-j7hk2
      readOnly: true
    - mountPath: /var/run/secrets/eks.amazonaws.com/serviceaccount
      name: aws-iam-token
      readOnly: true
  dnsPolicy: ClusterFirst
  enableServiceLinks: true
  nodeName: cal-server
  preemptionPolicy: PreemptLowerPriority
  priority: 0
  restartPolicy: Always
  schedulerName: default-scheduler
  securityContext: {}
  serviceAccount: my-app
  serviceAccountName: my-app
  terminationGracePeriodSeconds: 30
  tolerations:
  - effect: NoExecute
    key: node.kubernetes.io/not-ready
    operator: Exists
    tolerationSeconds: 300
  - effect: NoExecute
    key: node.kubernetes.io/unreachable
    operator: Exists
    tolerationSeconds: 300
  volumes:
  - name: aws-iam-token
    projected:
      defaultMode: 420
      sources:
      - serviceAccountToken:
          audience: sts.amazonaws.com
          expirationSeconds: 86400
          path: token
  - name: kube-api-access-j7hk2
    projected:
      defaultMode: 420
      sources:
      - serviceAccountToken:
          expirationSeconds: 3607
          path: token
      - configMap:
          items:
          - key: ca.crt
            path: ca.crt
          name: kube-root-ca.crt
      - downwardAPI:
          items:
          - fieldRef:
              apiVersion: v1
              fieldPath: metadata.namespace
            path: namespace
status:
```

What the webhook added, and nothing else:

| In the stored Pod | Value | Source |
|---|---|---|
| `env` `AWS_ROLE_ARN` | `arn:aws:iam::111122223333:role/my-app` | the ServiceAccount annotation `eks.amazonaws.com/role-arn` |
| `env` `AWS_WEB_IDENTITY_TOKEN_FILE` | `/var/run/secrets/eks.amazonaws.com/serviceaccount/token` | mount path + `path: token` |
| `volumeMounts[1]` | `aws-iam-token` at `/var/run/secrets/eks.amazonaws.com/serviceaccount` | fixed in the code |
| `volumes[0]` `aws-iam-token` | `audience: sts.amazonaws.com`, `expirationSeconds: 86400` | `--token-audience`; default lifetime |

Unlike Azure (§2), the names and paths are fixed: same volume name, same path, in every Pod. The
other volume (`kube-api-access-j7hk2`, 3607 s, no `audience`) is the default one every Pod gets (§1).

**4. No annotations or labels added.** Only Calico's and kubectl's:

```console
$ kubectl -n irsa-demo get pod app -o jsonpath='{.metadata.annotations}'
{"cni.projectcalico.org/containerID":"eb4d21b0c85a5c769b72179039c4c1b30e30476850fa73c6ea6e03ec021277bc","cni.projectcalico.org/podIP":"10.244.70.7/32","cni.projectcalico.org/podIPs":"10.244.70.7/32","kubectl.kubernetes.io/last-applied-configuration":"{\"apiVersion\":\"v1\",\"kind\":\"Pod\",\"metadata\":{\"annotations\":{},\"name\":\"app\",\"namespace\":\"irsa-demo\"},\"spec\":{\"containers\":[{\"command\":[\"sleep\",\"3600\"],\"image\":\"busybox:1.36\",\"name\":\"app\"}],\"serviceAccountName\":\"my-app\"}}\n"}
```

**5. Inside the container:**

```console
$ kubectl -n irsa-demo exec app -- env | grep AWS
AWS_ROLE_ARN=arn:aws:iam::111122223333:role/my-app
AWS_WEB_IDENTITY_TOKEN_FILE=/var/run/secrets/eks.amazonaws.com/serviceaccount/token

$ kubectl -n irsa-demo exec app -- ls -l /var/run/secrets/eks.amazonaws.com/serviceaccount/
total 0
lrwxrwxrwx    1 root     root            12 Nov 23 00:37 token -> ..data/token

$ kubectl -n irsa-demo exec app -- cut -d. -f2 /var/run/secrets/eks.amazonaws.com/serviceaccount/token
eyJhdWQiOlsic3RzLmFtYXpvbmF3cy5jb20iXSwiZXhwIjoxNzk1NDgwNjc1LCJpYXQiOjE3OTUzOTQyNzUsImlzcyI6Imh0dHBzOi8va3ViZXJuZXRlcy5kZWZhdWx0LnN2Yy5jbHVzdGVyLmxvY2FsIiwianRpIjoiZDNlZjZhMTMtZTNjYS00ODBkLTlmZTQtNDU5M2YzZjA1MTJjIiwia3ViZXJuZXRlcy5pbyI6eyJuYW1lc3BhY2UiOiJpcnNhLWRlbW8iLCJub2RlIjp7Im5hbWUiOiJjYWwtc2VydmVyIiwidWlkIjoiZWQxMTIyMDMtYmViMy00YmEwLTkxZWItMWQ2YzdkODZlM2ZjIn0sInBvZCI6eyJuYW1lIjoiYXBwIiwidWlkIjoiMWFmODA3NDktOTYyMS00MTYwLThhOTgtM2I3NzBiMTM3OTY1In0sInNlcnZpY2VhY2NvdW50Ijp7Im5hbWUiOiJteS1hcHAiLCJ1aWQiOiJjNjZkMzk5NS1jN2Q3LTQ2ODctOWZiMC1kY2I3NTc1YWFkZDIifX0sIm5iZiI6MTc5NTM5NDI3NSwic3ViIjoic3lzdGVtOnNlcnZpY2VhY2NvdW50Omlyc2EtZGVtbzpteS1hcHAifQ
```

That's base64url without padding, so plain `base64 -d` rejects it. Decoded with Python
(`base64.urlsafe_b64decode(s + "=" * (-len(s) % 4))`):

```json
{
  "aud": [
    "sts.amazonaws.com"
  ],
  "exp": 1795480675,
  "iat": 1795394275,
  "iss": "https://kubernetes.default.svc.cluster.local",
  "jti": "d3ef6a13-e3ca-480d-9fe4-4593f3f0512c",
  "kubernetes.io": {
    "namespace": "irsa-demo",
    "node": {
      "name": "cal-server",
      "uid": "ed112203-beb3-4ba0-91eb-1d6c7d86e3fc"
    },
    "pod": {
      "name": "app",
      "uid": "1af80749-9621-4160-8a98-3b770b137965"
    },
    "serviceaccount": {
      "name": "my-app",
      "uid": "c66d3995-c7d7-4687-9fb0-dcb7575aadd2"
    }
  },
  "nbf": 1795394275,
  "sub": "system:serviceaccount:irsa-demo:my-app"
}
```

- `exp - iat` = 1795480675 - 1795394275 = **86400**, the volume's `expirationSeconds`.
- `aud` is the volume's `audience`; `sub` is what the role's trust policy pins.
- `iss` is the k3s default, which STS can't reach, so this token can't actually be exchanged. On
  EKS it's `https://oidc.eks.<region>.amazonaws.com/id/<ID>`.
- The token names the Pod and the node. The **API server** rejects it once that Pod is deleted, even
  before `exp`. **STS can't know that**: it only checks the signature against the JWKS, plus `iss`,
  `aud`, `sub` and `exp`. So a copied IRSA token works against STS until it expires, which is one
  reason to keep `token-expiration` short.

**6. The optional variables.** Only two env vars so far. The region and STS-endpoint variables
need a webhook flag and a ServiceAccount annotation. The token lifetime also has an annotation:

```console
$ kubectl patch deploy pod-identity-webhook --type json \
    -p '[{"op":"add","path":"/spec/template/spec/containers/0/command/-","value":"--aws-default-region=eu-west-1"}]'
deployment.apps/pod-identity-webhook patched
$ kubectl rollout status deploy/pod-identity-webhook
Waiting for deployment "pod-identity-webhook" rollout to finish: 1 old replicas are pending termination...
Waiting for deployment "pod-identity-webhook" rollout to finish: 1 old replicas are pending termination...
deployment "pod-identity-webhook" successfully rolled out

$ kubectl -n irsa-demo annotate sa my-app \
    eks.amazonaws.com/sts-regional-endpoints=true eks.amazonaws.com/token-expiration=3600
serviceaccount/my-app annotated

$ kubectl -n irsa-demo run app2 --image=busybox:1.36 \
    --overrides='{"spec":{"serviceAccountName":"my-app"}}' -- sleep 3600
pod/app2 created

$ kubectl -n irsa-demo get pod app2 \
    -o jsonpath='{range .spec.containers[0].env[*]}{.name}={.value}{"\n"}{end}{.spec.volumes[0]}{"\n"}'
AWS_STS_REGIONAL_ENDPOINTS=regional
AWS_DEFAULT_REGION=eu-west-1
AWS_REGION=eu-west-1
AWS_ROLE_ARN=arn:aws:iam::111122223333:role/my-app
AWS_WEB_IDENTITY_TOKEN_FILE=/var/run/secrets/eks.amazonaws.com/serviceaccount/token
{"name":"aws-iam-token","projected":{"defaultMode":420,"sources":[{"serviceAccountToken":{"audience":"sts.amazonaws.com","expirationSeconds":3600,"path":"token"}}]}}
```

| Output | Comes from |
|---|---|
| `AWS_STS_REGIONAL_ENDPOINTS=regional` | the annotation `eks.amazonaws.com/sts-regional-endpoints: "true"`, or the webhook flag `--sts-regional-endpoint` |
| `AWS_DEFAULT_REGION`, `AWS_REGION` | the webhook flag `--aws-default-region` |
| `expirationSeconds: 3600` | the annotation `eks.amazonaws.com/token-expiration` |

The first Pod, `app`, kept its two variables and 86400: the webhook only runs on `CREATE`, so
changing the ServiceAccount or the webhook affects new Pods only. The AWS EKS docs list all five
variables as injected on EKS, which fits EKS running its copy of the webhook with the region set
**(docs; EKS's flags not visible, not tested)**.

**7. In the code** (`pkg/handler/handler.go`, v0.6.19) **(code)**:

- **It doesn't overwrite.** Before adding anything it scans the container's `env`. If
  `AWS_ROLE_ARN` or `AWS_WEB_IDENTITY_TOKEN_FILE` is already set, it doesn't add the web-identity
  pair. If either `AWS_REGION` or `AWS_DEFAULT_REGION` is set, it adds neither. If
  `AWS_STS_REGIONAL_ENDPOINTS` is set, it leaves that alone:

  ```go
  if !regionalStsKeyDefined && patchConfig.UseRegionalSTS {
      env = append(env, corev1.EnvVar{Name: stsKey, Value: "regional"})
  }
  if !regionKeyDefined && m.Region != "" {
      env = append(env, corev1.EnvVar{Name: "AWS_DEFAULT_REGION", Value: m.Region},
          corev1.EnvVar{Name: "AWS_REGION", Value: m.Region})
  }
  ```

- **The same webhook also handles EKS Pod Identity (§4).** The handler has a second branch that adds
  `AWS_CONTAINER_CREDENTIALS_FULL_URI` and `AWS_CONTAINER_AUTHORIZATION_TOKEN_FILE` in place of the
  web-identity pair. Only one branch runs per container. Not tested, because it needs the EKS Pod
  Identity association API.

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

Each cluster has its own OIDC provider. So using one role from several clusters means adding each cluster's provider to the trust policy, and IAM has a limit on OIDC providers per account. That's the main reason Pod Identity was added. A full IRSA walkthrough is in [cloud-kubernetes.md](cloud-kubernetes.md) ("IRSA Deep Dive: The Two Tokens Inside Your Pod"). The webhook README's EKS walkthrough, run step by step with every IAM/STS/S3 request and response against the local AWS emulator, is in [irsa-walkthrough.md](irsa-walkthrough.md).

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

### Why it removes the OIDC setup

EKS Pod Identity moves the trust boundary from per-cluster OIDC federation to one AWS service
principal (`pods.eks.amazonaws.com`) and a node-local DaemonSet agent. Instead of AWS STS
validating your cluster's public JWKS keys, **EKS itself** validates the Pod's ServiceAccount
token and exchanges it for temporary credentials through the **EKS Auth API**.

With IRSA, every cluster needs its own **IAM OIDC identity provider**, and every role's trust
policy names that cluster's issuer URL. The IAM quota for OpenID Connect providers is **100 per
account by default, adjustable up to 700** (IAM quotas page). Pod Identity removes both:

1. **A generic trust policy.** The role trusts the service principal `pods.eks.amazonaws.com`
   for `sts:AssumeRole` and `sts:TagSession`. No cluster URL appears, so the role can exist before
   the cluster and be reused by any number of clusters without editing the trust policy.
2. **A Pod Identity association** instead of a ServiceAccount annotation:
   `CreatePodIdentityAssociation` maps (cluster, namespace, ServiceAccount) → role ARN inside EKS.
3. **Pod mutation.** For a Pod using that ServiceAccount, the EKS-managed webhook injects a
   projected token (audience `pods.eks.amazonaws.com`) and two variables:
   `AWS_CONTAINER_AUTHORIZATION_TOKEN_FILE` (the token's path) and
   `AWS_CONTAINER_CREDENTIALS_FULL_URI=http://169.254.170.23/v1/credentials` (the node-local agent).
4. **The node-local exchange.** The SDK sends the token to the `eks-pod-identity-agent` DaemonSet
   on the same node. The agent calls the EKS Auth API `AssumeRoleForPodIdentity`, which verifies
   the token, checks the association, and returns temporary credentials with **session tags**
   attached for ABAC and CloudTrail: `eks-cluster-arn`, `eks-cluster-name`, `kubernetes-namespace`,
   `kubernetes-service-account`, `kubernetes-pod-name`, `kubernetes-pod-uid`. Tags can be turned
   off per association. **(docs: EKS user guide, "Define permissions for EKS Pod Identities to
   assume roles based on tags")**

The trade-off is that it only works on EKS, because it depends on the EKS Auth API and the EKS
add-on agent. IRSA works with any cluster whose OIDC issuer AWS can reach. **(docs)**

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

### What `AWS_CONTAINER_CREDENTIALS_FULL_URI` is for

It tells the SDK where to fetch credentials instead of calling STS: the Pod Identity agent, on the
link-local address `169.254.170.23` of every node. What the SDK does with it (code and lab, "The SDK
side" below):

```http
GET /v1/credentials HTTP/1.1
Host: 169.254.170.23
Authorization: eyJhbGciOiJSUzI1NiIsImtpZCI6IjVxN1JT…    ← the token file, as is, no "Bearer"
```

The token comes from the second variable the webhook sets, `AWS_CONTAINER_AUTHORIZATION_TOKEN_FILE`,
which points to the projected ServiceAccount token (audience `pods.eks.amazonaws.com`). The SDK
reads the file again on every refresh. The agent answers with temporary credentials:

```json
{ "AccessKeyId": "ASIA…", "SecretAccessKey": "…", "Token": "…", "AccountId": "…", "Expiration": "…" }
```

Those are the same three values STS returns for IRSA. The SDK signs requests with them and fetches
new ones before `Expiration`.

Behind the agent:

```text
Pod
 │ GET http://169.254.170.23/v1/credentials
 │ Authorization: <SA token>
 ▼
eks-pod-identity-agent (host network, same node)
 │ AssumeRoleForPodIdentity(cluster, token)
 ▼
EKS Auth API: checks the token and the association (namespace, SA) → role,
              returns credentials with session tags
```

The Pod never talks to STS or EKS itself, and never learns which role it has until the credentials
arrive. The mapping to a role lives in EKS.

### The SDK side: Java and Python (code + lab)

In the AWS SDK for Java v2 (2.59.7), Pod Identity is handled by `ContainerCredentialsProvider`,
the fifth provider in the default chain (§0.5) and the same one ECS uses
(`software/amazon/awssdk/auth/credentials/ContainerCredentialsProvider.java`):

```java
private static final Predicate<InetAddress> ALLOWED_HOSTS_RULES = InetAddress::isLoopbackAddress;
private static final String ECS_CONTAINER_HOST = "169.254.170.2";
private static final String EKS_CONTAINER_HOST_IPV6 = "[fd00:ec2::23]";
private static final String EKS_CONTAINER_HOST_IPV4 = "169.254.170.23";

public URI endpoint() throws IOException {
    …   // AWS_CONTAINER_CREDENTIALS_RELATIVE_URI (ECS) or AWS_CONTAINER_CREDENTIALS_FULL_URI (EKS Pod Identity)
    URI resolvedURI = SdkSystemSetting.AWS_CONTAINER_CREDENTIALS_RELATIVE_URI.getStringValue().map(this::createUri)
        .orElseGet(() -> URI.create(SdkSystemSetting.AWS_CONTAINER_CREDENTIALS_FULL_URI.getStringValueOrThrow()));
    validateURI(resolvedURI);   // plain http only to loopback or the ECS/EKS agent addresses
    return resolvedURI;
}

public Map<String, String> headers() {
    Map<String, String> requestHeaders = new HashMap<>();
    requestHeaders.put("User-Agent", SdkUserAgent.create().userAgent());
    getTokenValue().filter(StringUtils::isNotBlank)
                   .ifPresent(t -> requestHeaders.put("Authorization", t));   // the raw token, no "Bearer " prefix
    return requestHeaders;
}

private Optional<String> getTokenValue() {
    if (SdkSystemSetting.AWS_CONTAINER_AUTHORIZATION_TOKEN.getStringValue().isPresent()) {
        return SdkSystemSetting.AWS_CONTAINER_AUTHORIZATION_TOKEN.getStringValue();
    }
    return SdkSystemSetting.AWS_CONTAINER_AUTHORIZATION_TOKEN_FILE.getStringValue().map(this::readToken);  // reread each refresh
}
```

Run with the variables the webhook sets, against a fake agent on loopback, with a real lab token
for audience `pods.eks.amazonaws.com` (`sub system:serviceaccount:sdk-demo:app-sa`, `exp − iat` = 3600):

```text
GET /v1/credentials
Authorization: eyJhbGciOiJSUzI1NiIsImtpZCI6IjVxN1JTcXItVjR2…      ← identical to the token file, byte for byte; no "Bearer"
User-Agent: aws-sdk-java/2.59.7 …
```

The fake agent answered with `{"AccessKeyId": "ASIAPODID", "SecretAccessKey": …, "Token": …,
"AccountId": …, "Expiration": …}`, and `resolveCredentials()` returned `ASIAPODID`.

With a plain-HTTP URI to any other host, the provider refuses before sending anything:

```text
ContainerCredentialsProvider(): The full URI (http://example.com/v1/credentials) contained within environment variable
AWS_CONTAINER_CREDENTIALS_FULL_URI has an invalid host. Host should resolve to a loopback address or have the full URI be HTTPS.
```

That check keeps the Pod's token from being sent in clear text anywhere but the node-local agent.
Unlike IRSA, the Pod never talks to STS or EKS Auth itself: the agent does, from the node.

The Python SDK does the same (botocore, `credentials.py` `ContainerProvider` and `utils.py`
`ContainerMetadataFetcher`):

```python
class ContainerProvider(CredentialProvider):
    METHOD = 'container-role'
    ENV_VAR = 'AWS_CONTAINER_CREDENTIALS_RELATIVE_URI'          # ECS
    ENV_VAR_FULL = 'AWS_CONTAINER_CREDENTIALS_FULL_URI'         # EKS Pod Identity
    ENV_VAR_AUTH_TOKEN = 'AWS_CONTAINER_AUTHORIZATION_TOKEN'
    ENV_VAR_AUTH_TOKEN_FILE = 'AWS_CONTAINER_AUTHORIZATION_TOKEN_FILE'

    def _build_headers(self):
        auth_token = None
        if self.ENV_VAR_AUTH_TOKEN_FILE in self._environ:
            with open(self._environ[self.ENV_VAR_AUTH_TOKEN_FILE]) as token_file:
                auth_token = token_file.read()                  # reread on every refresh
        elif self.ENV_VAR_AUTH_TOKEN in self._environ:
            auth_token = self._environ[self.ENV_VAR_AUTH_TOKEN]
        if auth_token is not None:
            self._validate_auth_token(auth_token)               # rejects \r or \n
            return {'Authorization': auth_token}                # raw token, no "Bearer "

class ContainerMetadataFetcher:
    IP_ADDRESS = '169.254.170.2'
    _ALLOWED_HOSTS = [IP_ADDRESS, '169.254.170.23', 'fd00:ec2::23', 'localhost']

    def _validate_allowed_url(self, full_url):
        parsed = botocore.compat.urlparse(full_url)
        if parsed.scheme == 'https':
            return
        if self._is_loopback_address(parsed.hostname):
            return
        if not self._check_if_whitelisted_host(parsed.hostname):
            raise ValueError(f"Unsupported host '{parsed.hostname}'.  Can only retrieve metadata "
                             f"from a loopback address or one of these hosts: {', '.join(self._ALLOWED_HOSTS)}")

    def _get_response(self, full_url, headers, timeout):
        request = AWSRequest(method='GET', url=full_url, headers=headers)   # headers also carry Accept: application/json
        response = self._session.send(request.prepare())
        …
```

**On the real link-local addresses (lab).** To check the actual destination and port, a Linux
container was given the agent's addresses on its loopback (`ip addr add 169.254.170.23/32 dev lo`,
`ip -6 addr add fd00:ec2::23/128 dev lo`) and ran a fake agent listening on port 80 of both. Both
SDKs then ran with exactly the URIs the EKS webhook sets: Python (botocore 1.44.9) in the same
container, Java (SDK v2 2.59.7, `eclipse-temurin:21-jre`) in a second container sharing its network
namespace. Every request the agent received:

```text
GET /v1/credentials HTTP/1.1   to 169.254.170.23:80   Host: 169.254.170.23   Authorization == token file   UA: python-urllib3/2.8.0
GET /v1/credentials HTTP/1.1   to fd00:ec2::23:80     Host: [fd00:ec2::23]   Authorization == token file   UA: python-urllib3/2.8.0
GET /v1/credentials HTTP/1.1   to 169.254.170.23:80   Host: 169.254.170.23   Authorization == token file   UA: aws-sdk-java/2.59.7 os/Linux
GET /v1/credentials HTTP/1.1   to fd00:ec2::23:80     Host: [fd00:ec2::23]   Authorization == token file   UA: aws-sdk-java/2.59.7 os/Linux
```

So the SDK sends a plain HTTP `GET` to `169.254.170.23:80` (or `[fd00:ec2::23]:80` on IPv6), with
the projected JWT, unmodified, as the `Authorization` header. Port 80 isn't in the SDKs: it's the
default for the `http://` URI the webhook writes into `AWS_CONTAINER_CREDENTIALS_FULL_URI`.
botocore reported the result as `container-role`. Which address a Pod uses is up to the webhook
(the IPv6 one on IPv6 clusters). **(docs)**

**The request in full.** When the Pod starts, the EKS Pod Identity webhook injects:

```text
AWS_CONTAINER_CREDENTIALS_FULL_URI=http://169.254.170.23/v1/credentials
AWS_CONTAINER_AUTHORIZATION_TOKEN_FILE=/var/run/secrets/pods.eks.amazonaws.com/serviceaccount/eks-pod-identity-token
```

When the app makes its first AWS call, the SDK's container credential provider reads the JWT from
disk and sends this request. The core is the same in both SDKs; the other headers come from each
SDK's HTTP client (lab, all headers as received):

```text
Python (botocore, urllib3)                           Java (AWS SDK v2)
GET /v1/credentials HTTP/1.1                         GET /v1/credentials HTTP/1.1
Host: 169.254.170.23                                 Host: 169.254.170.23
Authorization: <raw projected JWT>                   Authorization: <raw projected JWT>
Accept: application/json                             Accept: */*
Accept-Encoding: identity                            Connection: keep-alive
User-Agent: python-urllib3/…                         User-Agent: aws-sdk-java/2.59.7 os/… lang/java#…
```

What both always send is the request line, `Host: 169.254.170.23` (or `[fd00:ec2::23]`) and
`Authorization: <raw projected JWT>`. The `Accept`, `Accept-Encoding`, `Connection` and
`User-Agent` headers differ between the two SDKs.

**Does the request leave the node? (lab)** `169.254.170.23` is a link-local address
(`169.254.0.0/16`, RFC 3927), and routers don't forward link-local addresses. But that's not what
keeps this request on the node. What does is that the agent's address is **configured on the node
itself**, so the node's kernel delivers the packet locally: the Pod's veth carries it into the
node's root network namespace, and it never reaches the NIC.

Reproduced on the Calico lab cluster ([calico-layer3.md](calico-layer3.md)), node `cal-agent1`:

- the node got `169.254.170.23/32` on `lo`, and a fake agent ran as a **host-network** Pod
  listening on `169.254.170.23:80`, which is how the real `eks-pod-identity-agent` DaemonSet runs;
- two ordinary Pods on the same node used the webhook's variables: `py-app` (botocore 1.44.9) and
  `java-app` (AWS SDK v2 2.59.7, `eclipse-temurin:21-jre`);
- tcpdump ran on each Pod's `cali` veth and on the node's `eth0` at the same time.

```text
cal-agent1$ ip route get 169.254.170.23
local 169.254.170.23 dev lo src 169.254.170.23          ← this node owns it: delivered here
cal-agent1$ ip route get 169.254.169.254
169.254.169.254 via 172.21.0.1 dev eth0 src 172.21.0.3  ← the IMDS address: sent out of the node
```

| Capture | `169.254.170.23` (Pod Identity agent) | `169.254.169.254` (tried from `py-app` for contrast) |
|---|---|---|
| `py-app`'s veth `calibb3cc664f0d` | SYN, SYN-ACK, request, response: the whole exchange | `10.244.189.7 > 169.254.169.254.80` SYN |
| `java-app`'s veth `cali0ea5209b59a` | the whole exchange | — |
| **node `eth0`** | **0 packets** | `172.21.0.3 > 169.254.169.254.80` SYN: **left the node**, source NATed to the node IP |

The fake agent's log, from the host network namespace:

```text
GET /v1/credentials HTTP/1.1  to 169.254.170.23:80  from 10.244.189.7  Authorization == token file  UA: python-urllib3/2.8.0
GET /v1/credentials HTTP/1.1  to 169.254.170.23:80  from 10.244.189.6  Authorization == token file  UA: aws-sdk-java/2.59.7 …
```

Both SDKs got credentials (`container-role` / `ASIAPODID`). The agent saw the **Pod IPs** as the
source: Pod → node over the veth, routed locally, no NAT. (The IMDS attempt was NATed by Calico's
`natOutgoing` because it was leaving the node.)

**IMDS is a different pattern, though both use `169.254.x.x`.** The Pod Identity agent is a
process on the node, reached through the node's own kernel. `169.254.169.254` is not on the node at
all: the instance sends those packets out of its network interface, and EC2's Nitro system answers
them outside the instance's operating system. On a real EC2 node, `ip route get 169.254.169.254`
shows the same `dev eth0` route as here. **(the Nitro part: docs; there's no IMDS in this lab, so
the SYN above got no real answer)**

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
| Path under `/var/run/secrets/` | `kubernetes.io/serviceaccount/token` | `azure/wi/<hash of pod name>/token/azure-identity-token` (v1.6+; `azure/tokens/azure-identity-token` up to v1.5) | `eks.amazonaws.com/serviceaccount/token` | `pods.eks.amazonaws.com/serviceaccount/eks-pod-identity-token` |
| Added by | ServiceAccount admission plugin | Azure Workload Identity webhook | EKS webhook | EKS webhook |
| Opt-in | automatic, opt out with `automountServiceAccountToken: false` | Pod label `azure.workload.identity/use: "true"` + SA annotation `client-id` | SA annotation `eks.amazonaws.com/role-arn` | Pod Identity association in the EKS API |
| Audience (`aud`) | the API server | `api://AzureADTokenExchange` | `sts.amazonaws.com` | `pods.eks.amazonaws.com` |
| Exchanged with | nothing, used directly | Entra ID token endpoint | STS `AssumeRoleWithWebIdentity` | node agent, then EKS Auth `AssumeRoleForPodIdentity` |
| SDK env var | none (client-go reads the fixed path) | `AZURE_FEDERATED_TOKEN_FILE` | `AWS_WEB_IDENTITY_TOKEN_FILE` | `AWS_CONTAINER_AUTHORIZATION_TOKEN_FILE` |
| Cloud-side link | RBAC on the ServiceAccount | federated identity credential (issuer + subject) | role trust policy naming the cluster's OIDC provider | association; the trust policy names `pods.eks.amazonaws.com` |
| Subject (`sub`) | `system:serviceaccount:<ns>:<sa>` | the same | the same | the same |
| Signed by | the cluster's ServiceAccount key | the same | the same | the same |

Because the audiences differ, each service rejects a token meant for another. A leaked Azure or AWS token can't be used against the Kubernetes API, and the default token can't be traded for cloud credentials.

### Azure Workload Identity next to IRSA, end to end

IRSA works the same way: the Pod's ServiceAccount token is exchanged for a cloud credential, and the
cloud trusts the cluster's OIDC issuer for one subject. EKS also has a second method, Pod Identity
(§4), which Azure has no equivalent for. The real differences are in where the trust is written
down and in what the exchange returns.

**1. Who may become the identity.** On Azure, a federated credential on the identity. On AWS, a
trust policy on the role. On AWS you also register the cluster's issuer in the account once.

```text
Azure: user-assigned identity orders-mi
  federatedIdentityCredential:
    issuer   = https://eastus.oic.prod-aks.azure.com/<tenant>/<guid>/
    subject  = system:serviceaccount:orders:orders-sa
    audience = api://AzureADTokenExchange
```

```sh
# AWS, once per cluster per account
aws iam create-open-id-connect-provider \
  --url https://oidc.eks.us-east-1.amazonaws.com/id/49164ADE… --client-id-list sts.amazonaws.com
```

```jsonc
// AWS: the role orders-role, trust policy
{
  "Effect": "Allow",
  "Principal": { "Federated": "arn:aws:iam::111122223333:oidc-provider/oidc.eks.us-east-1.amazonaws.com/id/49164ADE…" },
  "Action": "sts:AssumeRoleWithWebIdentity",
  "Condition": { "StringEquals": {
    "oidc.eks.us-east-1.amazonaws.com/id/49164ADE…:sub": "system:serviceaccount:orders:orders-sa",
    "oidc.eks.us-east-1.amazonaws.com/id/49164ADE…:aud": "sts.amazonaws.com" } }
}
```

Because the AWS check is a policy condition, it can use wildcards: `StringLike` with
`system:serviceaccount:orders:*` lets every ServiceAccount in the namespace in. An Azure federated
credential matches the subject exactly, one credential per ServiceAccount.

**2. The Kubernetes side.** It's almost the same, except that AWS needs no Pod label: the
annotation on the ServiceAccount is enough.

```yaml
# Azure                                           # AWS (IRSA)
kind: ServiceAccount                              kind: ServiceAccount
metadata:                                         metadata:
  name: orders-sa                                   name: orders-sa
  annotations:                                      annotations:
    azure.workload.identity/client-id: <clientId>     eks.amazonaws.com/role-arn: arn:aws:iam::111122223333:role/orders-role
# + Pod label azure.workload.identity/use: "true"
```

What each webhook injects (lab, §2 and §3):

| | Azure | AWS IRSA |
|---|---|---|
| Token audience | `api://AzureADTokenExchange` | `sts.amazonaws.com` |
| Token file | `/var/run/secrets/azure/wi/<hash of pod name>/token/azure-identity-token` (v1.6+; `/var/run/secrets/azure/tokens/azure-identity-token` up to v1.5) | `/var/run/secrets/eks.amazonaws.com/serviceaccount/token` |
| Environment | `AZURE_CLIENT_ID`, `AZURE_TENANT_ID`, `AZURE_FEDERATED_TOKEN_FILE`, `AZURE_AUTHORITY_HOST` | `AWS_ROLE_ARN`, `AWS_WEB_IDENTITY_TOKEN_FILE`, `AWS_STS_REGIONAL_ENDPOINTS`, region |

**3. Hop 1, the exchange.** On Azure the token is a client assertion in an OAuth request. On AWS it
goes to STS in an unsigned request: the token is the only credential. From the lab
([irsa-walkthrough.md](irsa-walkthrough.md) §7.3; the emulator couldn't fetch the k3s cluster's
keys, so this run used a Cognito-issued token in its place):

```text
POST https://sts.us-east-1.amazonaws.com/      (no Authorization header)
Action           = AssumeRoleWithWebIdentity
RoleArn          = arn:aws:iam::000000000000:role/s3-reader-cognito
RoleSessionName  = my-pod
WebIdentityToken = eyJhbGciOi…                  ← the token file
```

What comes back is the biggest difference. Entra returns a bearer token that you send as is. STS
returns three values that make up a temporary key:

```xml
<Credentials>
  <AccessKeyId>EMULATORASTMZY22CZHQ</AccessKeyId>     <!-- ASIA… on AWS -->
  <SecretAccessKey>EfuuwJ…</SecretAccessKey>           <!-- never sent again: used to sign -->
  <SessionToken>EjQTkE/xyfBk9U6sAeD+…</SessionToken>
  <Expiration>2026-11-23T09:12:57Z</Expiration>
</Credentials>
<AssumedRoleUser><Arn>arn:aws:sts::000000000000:assumed-role/s3-reader-cognito/my-pod</Arn></AssumedRoleUser>
```

**4. Hop 2, the API call.** On Azure, `Authorization: Bearer <token>`. On AWS, every request is
signed with SigV4 (lab, [irsa-walkthrough.md](irsa-walkthrough.md) §7.4):

```http
X-Amz-Security-Token: EjQTkE/xyfBk9U6sAeD+…
Authorization: AWS4-HMAC-SHA256 Credential=EMULATORASTMZY22CZHQ/20261123/us-east-1/sts/aws4_request, SignedHeaders=…, Signature=<64 hex>
```

So on Azure a stolen access token can be replayed by anyone until it expires. On AWS a request on
its own can't be replayed for long: its signature covers its time stamp and expires within
minutes. To make new requests, an attacker needs the secret key, which never goes over the wire.

**5. The application code** is the same idea on both: the default credential chain finds the
environment variables.

```python
# Azure
from azure.identity import DefaultAzureCredential      # → WorkloadIdentityCredential
from azure.keyvault.secrets import SecretClient
SecretClient("https://orders-kv.vault.azure.net", DefaultAzureCredential()).get_secret("db-password")

# AWS
import boto3                                           # → AssumeRoleWithWebIdentityProvider
boto3.client("secretsmanager").get_secret_value(SecretId="db-password")
```

Both read the token file again at each exchange, so they pick up the kubelet's rotated token, and
both cache the result until shortly before it expires.

**Where the two differ:**

| | Azure Workload Identity | AWS IRSA |
|---|---|---|
| The identity | a user-assigned managed identity or an app registration (two kinds) | an IAM role (one kind) |
| Where the trust is | on the identity: a federated credential, up to 20 per identity | on the role: its trust policy, plus one IAM OIDC provider per cluster per account |
| Subject matching | exact | any IAM condition operator, so wildcards work |
| What the exchange returns | an OAuth bearer token, one per audience (Key Vault, ARM, Storage…) | one set of temporary keys, valid for every AWS service the role allows |
| How it's used | `Authorization: Bearer` | SigV4 signature |
| Who the API sees | `oid` of the identity | `assumed-role/orders-role/<session name>` |

On Azure each resource needs its own token: one for Key Vault, another for ARM. On AWS one set of
credentials covers every service, and the role's permissions policy decides what's allowed.

**EKS Pod Identity: the AWS method Azure doesn't have.** AWS's newer method doesn't use an OIDC
provider at all (docs, §4):

```sh
aws eks create-pod-identity-association --cluster-name prod \
  --namespace orders --service-account orders-sa --role-arn arn:aws:iam::111122223333:role/orders-role
```

```jsonc
// orders-role trust policy: no cluster URL in it, reusable by any cluster
{ "Effect": "Allow", "Principal": { "Service": "pods.eks.amazonaws.com" },
  "Action": ["sts:AssumeRole", "sts:TagSession"] }
```

1. The Pod gets a token with audience `pods.eks.amazonaws.com`, plus
   `AWS_CONTAINER_CREDENTIALS_FULL_URI=http://169.254.170.23/v1/credentials`.
2. The SDK sends the token to the node-local agent.
3. The agent calls the EKS Auth API `AssumeRoleForPodIdentity`, and EKS itself checks the
   association.
4. The credentials come back with session tags (`kubernetes-namespace`,
   `kubernetes-service-account`, `eks-cluster-name`…) that policies can match on.

In this method the link from ServiceAccount to role is stored in EKS, not on the identity or the
role. Azure always keeps it on the identity: a federated credential per cluster issuer.

**The node problem is the same on both.** An EC2 node's instance profile is available to every Pod
through EC2 IMDS, just as a scale set's managed identities are through Azure IMDS
([azure.md](azure.md), "Can a managed identity be assigned to a Pod?"). The common fix differs:

- **AWS:** IMDSv2 with a hop limit of 1, so a reply never reaches a Pod's network namespace.
- **Azure:** a NetworkPolicy, or AKS's IMDS restriction.

Either way, IRSA, Pod Identity and Workload Identity all exist to give each ServiceAccount its own
identity instead of the node's.

### Does `automountServiceAccountToken: false` remove the cloud tokens?

The cloud webhooks add their own projected volumes, separate from `kube-api-access-…`. So turning off the automatic mount should leave the cloud token in place, and the app can still reach Azure or AWS without being able to call the Kubernetes API **(not tested)**.

### Looking at a token

```sh
# inside the container: print the claims (the middle part of the JWT)
cut -d. -f2 "$AZURE_FEDERATED_TOKEN_FILE" | base64 -d 2>/dev/null; echo   # the path has a per-Pod hash from v1.6
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

The Azure Workload Identity webhook adds this to Pods labelled `azure.workload.identity/use: "true"`
(see §2). This is the v1.6.1 volume from the lab; up to v1.5 it was called `azure-identity-token`,
with `path: azure-identity-token`:

```yaml
- name: azure-workload-identity-reserved-a172cedcae47474b615c54d510a5d8
  projected:
    sources:
    - serviceAccountToken:
        audience: api://AzureADTokenExchange
        expirationSeconds: 3600
        path: token/azure-identity-token
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

Each example below shows the Pod part the webhook injects, written out for a hand-written Pod, and the federated credential **(not tested)**. They use the short v1.5 mount path, `/var/run/secrets/azure/tokens`. In a hand-written Pod any path works, as long as `AZURE_FEDERATED_TOKEN_FILE` points to the file. The v1.6+ webhook uses `/var/run/secrets/azure/wi/<hash>/token/azure-identity-token`.

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
- [irsa-walkthrough.md](irsa-walkthrough.md): the IRSA walkthrough end to end, with the wire traffic (emulator + lab cluster)
- [gke-and-google-identity.md](gke-and-google-identity.md): GKE: creating clusters, `kubectl` with Google tokens, Workload Identity Federation with the Java SDK (floci-gcp)
- [untested-claims.md](untested-claims.md): the claims here marked "docs" or "not tested", as a checklist.

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
