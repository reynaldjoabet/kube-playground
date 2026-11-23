# GKE and Google Cloud identity: clusters, kubectl, and Pods calling Google APIs (lab)

How Google Cloud runs Kubernetes, and how the three kinds of caller get their identities:

- the **cluster itself**, created through the GKE API;
- **people and tools running `kubectl`**, with a Google token;
- **Pods calling Google APIs**, with a Kubernetes token exchanged for a Google one.

Each flow was run with the Google Cloud Java SDK against floci-gcp, a local Google Cloud emulator, and
is shown request by request. Labels:

- **(lab)**: run here, output copied as printed;
- **(code)**: read in the source of google-auth-library-java 1.47.0 or floci-gcp;
- **(docs)**: Google's documentation; floci-gcp can't show it.

The same questions for AWS and Azure are in [workload-identity.md](workload-identity.md),
[irsa-walkthrough.md](irsa-walkthrough.md) and [azure-identity-flows.md](azure-identity-flows.md).

---

## 0. The pieces

| Piece | Real GKE (docs) | In this lab |
|---|---|---|
| Control plane | Google-managed, in a Google project; you get an endpoint and a CA | a `rancher/k3s` container that floci-gcp starts per cluster |
| Nodes | Compute Engine VMs in your project, running as the **node pool's service account** | the same k3s container (one node) |
| Cluster API | `container.googleapis.com` (ClusterManager v1) | floci-gcp, REST only |
| `kubectl` authentication | a Google OAuth access token from `gke-gcloud-auth-plugin`; the API server maps it to the Google identity | k3s sends the token to floci-gcp's token webhook, which accepts any token |
| `kubectl` authorization | IAM (`roles/container.*`) and Kubernetes RBAC | `system:masters` for every token |
| Pods → Google APIs | **Workload Identity Federation for GKE**: the GKE metadata server answers the Pod's metadata request | not modelled; the federation flow for non-GKE clusters is run instead (§4) |
| Token exchange | `sts.googleapis.com` | floci-gcp STS, which only exchanges access tokens (§4.3) |
| Impersonation | `iamcredentials.googleapis.com` `generateAccessToken` | floci-gcp, working (§4.4) |

Versions: floci-gcp **0.13.0** (revision `98829d4`); google-cloud-container 2.95.0, google-cloud-iamcredentials
2.92.0 and google-auth-library-oauth2-http 1.47.0 from libraries-bom 26.83.0; gax 2.80.0; Java 25;
kubectl 1.34.1; the cluster's k3s v1.34.1+k3s1.

**Setup.** floci-gcp ran from the README's compose settings, but without the `./data` volume, so no
state is written to the repo:

```console
$ docker run -d --name floci-gcp-lab -p 127.0.0.1:4588:4588 -v /var/run/docker.sock:/var/run/docker.sock \
    -e FLOCI_GCP_HOSTNAME=floci-gcp -e FLOCI_GCP_BASE_URL=http://localhost:4588 floci/floci-gcp:latest
```

The default project is `floci-local` (number `691406228548`). Every SDK and `curl` request went
through a small logging HTTPS proxy (`https://localhost:4590` → floci-gcp on `:4588`). It was needed
because google-auth-library refuses a non-HTTPS `token_url` (§4.2), and it prints each exchange as HTTP
messages:

- form bodies are URL-decoded;
- tokens are cut after 30 characters (`…`), and every JWT is decoded in a `json` block (a payload
  already shown isn't repeated);
- `User-Agent`, `Date` and `Content-Length` are left out.

The access token `ya29.lab-user-token-for-alice` stands in for a user's token from
`gcloud auth login`; floci-gcp doesn't check tokens.

---

## 1. Creating a cluster (lab)

With the Java client, `ClusterManagerClient`. floci-gcp serves GKE over REST only, so the client uses
the **HttpJson** transport (the default is gRPC). The endpoint host starts with `container.`: floci-gcp
serves every API on one port and routes GKE by that first DNS label, as Google does by hostname.

```java
ClusterManagerSettings settings = ClusterManagerSettings.newHttpJsonBuilder()   // floci-gcp serves GKE over REST, not gRPC
    .setEndpoint("container.localhost:4590")                                    // host mode: the first DNS label routes to GKE
    .setCredentialsProvider(FixedCredentialsProvider.create(userToken()))
    .build();
try (ClusterManagerClient gke = ClusterManagerClient.create(settings)) {
    Cluster spec = Cluster.newBuilder()
        .setName("gke-lab")
        .setInitialNodeCount(1)
        .setWorkloadIdentityConfig(WorkloadIdentityConfig.newBuilder()
            .setWorkloadPool("floci-local.svc.id.goog"))                         // Workload Identity Federation for GKE
        .setNodeConfig(NodeConfig.newBuilder()
            .setServiceAccount("gke-nodes@floci-local.iam.gserviceaccount.com")   // the nodes' own identity
            .addOauthScopes("https://www.googleapis.com/auth/cloud-platform"))
        .build();
    Operation op = gke.createCluster(CreateClusterRequest.newBuilder()
        .setParent("projects/floci-local/locations/us-central1").setCluster(spec).build());
    Cluster c = gke.getCluster("projects/floci-local/locations/us-central1/clusters/gke-lab");
}
```

```http
POST /v1/projects/floci-local/locations/us-central1/clusters?$alt=json;enum-encoding%3Dint HTTP/1.1
Authorization: Bearer ya29.lab-user-token-for-alice
x-goog-request-params: parent=projects%2Ffloci-local%2Flocations%2Fus-central1
x-goog-api-client: gl-java/25.0.5.1__Eclipse-Adoptium__Temurin-25.0.5.1-2 gapic/2.95.0--protobuf-4.33.2 gax/2.80.0 rest/ protobuf/4.33.2
Content-Type: application/json; charset=utf-8
Host: container.localhost:4590
Accept: */*

{"cluster":{"name":"gke-lab","initialNodeCount":1,"nodeConfig":{"oauthScopes":["https://www.googleapis.com/auth/cloud-platform"],"serviceAccount":"gke-nodes@floci-local.iam.gserviceaccount.com"},"workloadIdentityConfig":{"workloadPool":"floci-local.svc.id.goog"}}}
```

```http
HTTP/1.1 200 OK
Content-Type: application/json;charset=UTF-8

{
  "name": "operation-e5098907-b59d-4225-8d42-6148ad18d526",
  "operationType": "CREATE_CLUSTER",
  "status": "RUNNING",
  "zone": "us-central1",
  "location": "us-central1",
  "targetLink": "projects/floci-local/locations/us-central1/clusters/gke-lab",
  "selfLink": "projects/floci-local/locations/us-central1/operations/operation-e5098907-b59d-4225-8d42-6148ad18d526",
  "startTime": "2026-11-23T14:47:46.127977342Z"
}
```

- **Two identities are set at creation.** `workloadIdentityConfig.workloadPool` (always
  `PROJECT_ID.svc.id.goog`) turns on Workload Identity Federation for GKE, the identity Pods get (§3).
  `nodeConfig.serviceAccount` is the identity of the **nodes**, used by the kubelet and system
  components, for pulling images or writing logs. If it isn't set, GKE uses the project's Compute
  Engine default service account (docs).
- **`CreateCluster` returns a long-running Operation**, here `CREATE_CLUSTER`, `RUNNING`. The client
  polls `GetCluster` or the operation.
- **`$alt=json;enum-encoding=int`** is gax asking for JSON with numeric enums; floci-gcp still answered
  with names.

Right after creation the cluster is still `PROVISIONING`, with an endpoint but **no CA yet**:

```http
GET /v1/projects/floci-local/locations/us-central1/clusters/gke-lab?$alt=json;enum-encoding%3Dint HTTP/1.1
Authorization: Bearer ya29.lab-user-token-for-alice
x-goog-request-params: name=projects%2Ffloci-local%2Flocations%2Fus-central1%2Fclusters%2Fgke-lab
x-goog-api-client: gl-java/25.0.5.1__Eclipse-Adoptium__Temurin-25.0.5.1-2 gapic/2.95.0--protobuf-4.33.2 gax/2.80.0 rest/ protobuf/4.33.2
Host: container.localhost:4590
Accept: */*
```

```http
HTTP/1.1 200 OK
Content-Type: application/json;charset=UTF-8

{
  "masterAuth": {
    "clusterCaCertificate": ""
  },
  "labelFingerprint": "146bf8df163f4807",
  "currentMasterVersion": "1.30.5-gke.1014001",
  "network": "default",
  "endpoint": "localhost:6550",
  "initialNodeCount": 1,
  "zone": "us-central1",
  "nodePools": [
    {
      "initialNodeCount": 1,
      "instanceGroupUrls": [],
      "name": "default-pool",
      "locations": [
        "us-central1"
      ],
      "etag": "ee8daf4b07524725",
      "conditions": [],
      "config": {
        "serviceAccount": "gke-nodes@floci-local.iam.gserviceaccount.com",
        "oauthScopes": [
          "https://www.googleapis.com/auth/cloud-platform"
        ]
      },
      "version": "1.30.5-gke.1014001",
      "selfLink": "http://localhost:4588/container/v1/projects/floci-local/locations/us-central1/clusters/gke-lab/nodePools/default-pool",
      "status": "RUNNING"
    }
  ],
  "initialClusterVersion": "1.30.5-gke.1014001",
  "clusterIpv4Cidr": "10.0.0.0/14",
  "workloadIdentityConfig": {
    "workloadPool": "floci-local.svc.id.goog"
  },
  "monitoringService": "monitoring.googleapis.com/kubernetes",
  "networkConfig": {
    "subnetwork": "default",
    "network": "default"
  },
  "loggingService": "logging.googleapis.com/kubernetes",
  "selfLink": "http://localhost:4588/container/v1/projects/floci-local/locations/us-central1/clusters/gke-lab",
  "currentNodeVersion": "1.30.5-gke.1014001",
  "createTime": "2026-11-23T14:47:46.011048467Z",
  "subnetwork": "default",
  "name": "gke-lab",
  "resourceLabels": {},
  "location": "us-central1",
  "locations": [
    "us-central1"
  ],
  "etag": "db0e4c001c3542a0",
  "conditions": [],
  "status": "PROVISIONING"
}
```

Once `RUNNING` (about 12 s here), the same call has the CA, `currentMasterVersion` and the node pool
with its service account:

```http
GET /v1/projects/floci-local/locations/us-central1/clusters/gke-lab HTTP/1.1
Host: container.localhost:4590
Accept: */*
Authorization: Bearer ya29.lab-user-token-for-alice
```

```http
HTTP/1.1 200 OK
Content-Type: application/json;charset=UTF-8

{
  "masterAuth": {
    "clusterCaCertificate": "LS0tLS1CRUdJTiBDRVJUSUZJQ0FURS0tLS0tCk1JSUJkekNDQVIyZ0F3SUJBZ0lCQURBS0JnZ3Foa2pPUFFRREFqQWpNU0V3SHdZRFZRUUREQmhyTTNNdGMyVnkKZG1WeUxXTmhRREUzT1RVME5EVXlOemd3SGhjTk1qWXhNVEl6TVRRME56VTRXaGNOTXpZeE1USXdNVFEwTnpVNApXakFqTVNFd0h3WURWUVFEREJock0zTXRjMlZ5ZG1WeUxXTmhRREUzT1RVME5EVXlOemd3V1RBVEJnY3Foa2pPClBRSUJCZ2dxaGtqT1BRTUJCd05DQUFUdmd2UXgzSUdYam84ck0wYWxwdUJoQlZ6ZWtGT2R5VW1UYnlRR0l1c04KYmhhc3hCbzQ3akdoRi9CNmIreVJCVzZsQStSRmw3MmU0NjByWUxuaWtBaE5vMEl3UURBT0JnTlZIUThCQWY4RQpCQU1DQXFRd0R3WURWUjBUQVFIL0JBVXdBd0VCL3pBZEJnTlZIUTRFRmdRVUtRalJTQ0xPS3E5MjNVYkg2czJpCjFEWjB4QmN3Q2dZSUtvWkl6ajBFQXdJRFNBQXdSUUloQUpTSENOV3NzMGRZdEJBdXNVbU41ZURXRVZ0d2FGb3YKZG1zZ0U1WFV0TnU3QWlBRHNZcmhkUzI1M0NqQVUreis0VUtCVkJpUTNOaHpDSjVvcll1MGJHcG1LZz09Ci0tLS0tRU5EIENFUlRJRklDQVRFLS0tLS0K"
  },
  "labelFingerprint": "146bf8df163f4807",
  "currentMasterVersion": "1.30.5-gke.1014001",
  "network": "default",
  "endpoint": "localhost:6550",
  "initialNodeCount": 1,
  "zone": "us-central1",
  "nodePools": [
    {
      "initialNodeCount": 1,
      "instanceGroupUrls": [],
      "name": "default-pool",
      "locations": [
        "us-central1"
      ],
      "etag": "ee8daf4b07524725",
      "conditions": [],
      "config": {
        "serviceAccount": "gke-nodes@floci-local.iam.gserviceaccount.com",
        "oauthScopes": [
          "https://www.googleapis.com/auth/cloud-platform"
        ]
      },
      "version": "1.30.5-gke.1014001",
      "selfLink": "http://localhost:4588/container/v1/projects/floci-local/locations/us-central1/clusters/gke-lab/nodePools/default-pool",
      "status": "RUNNING"
    }
  ],
  "initialClusterVersion": "1.30.5-gke.1014001",
  "clusterIpv4Cidr": "10.0.0.0/14",
  "workloadIdentityConfig": {
    "workloadPool": "floci-local.svc.id.goog"
  },
  "monitoringService": "monitoring.googleapis.com/kubernetes",
  "networkConfig": {
    "subnetwork": "default",
    "network": "default"
  },
  "loggingService": "logging.googleapis.com/kubernetes",
  "selfLink": "http://localhost:4588/container/v1/projects/floci-local/locations/us-central1/clusters/gke-lab",
  "currentNodeVersion": "1.30.5-gke.1014001",
  "createTime": "2026-11-23T14:47:46.011048467Z",
  "subnetwork": "default",
  "name": "gke-lab",
  "resourceLabels": {},
  "location": "us-central1",
  "locations": [
    "us-central1"
  ],
  "etag": "db0e4c001c3542a0",
  "conditions": [],
  "status": "RUNNING"
}
```

The CA is the cluster's own, generated by k3s:

```console
$ echo "$CA" | base64 -d | openssl x509 -noout -subject -issuer
subject= /CN=k3s-server-ca@1795445278
issuer= /CN=k3s-server-ca@1795445278
```

What floci-gcp started for it (`GkeClusterManager.launchContainer`, **code**), and the container in
Docker (**lab**):

```console
$ docker ps --format '{{.Names}} {{.Ports}}' | grep 6550
floci-gcp-gke-floci-local-gke-lab 0.0.0.0:6550->6443/tcp, [::]:6550->6443/tcp

$ docker inspect floci-gcp-gke-floci-local-gke-lab --format '{{json .Config.Cmd}}'
["server","--disable=traefik","--tls-san=localhost",
 "--kube-apiserver-arg=authentication-token-webhook-config-file=/etc/gke-token-webhook.yaml",
 "--kube-apiserver-arg=authentication-token-webhook-version=v1",
 "--kube-apiserver-arg=authentication-token-webhook-cache-ttl=30s"]
```

The three `authentication-token-webhook` flags are the point of §2.

---

## 2. `kubectl` with a Google token

### 2.1 The kubeconfig (lab)

`gcloud container clusters get-credentials gke-lab --region us-central1` writes a kubeconfig whose
user is an **exec plugin**, not a stored token (docs). Built by hand here, from `GetCluster`'s
`endpoint` and `masterAuth.clusterCaCertificate`:

```yaml
clusters:
- name: gke_floci-local_us-central1_gke-lab
  cluster:
    server: https://localhost:6550
    certificate-authority-data: LS0tLS1CRUdJTiBDRVJUSUZJQ0FURS0tLS0tCk1J…
users:
- name: gke_floci-local_us-central1_gke-lab
  user:
    exec:
      apiVersion: client.authentication.k8s.io/v1beta1
      command: gke-gcloud-auth-plugin
      installHint: Install gke-gcloud-auth-plugin for use with kubectl by following https://cloud.google.com/kubernetes-engine/docs/how-to/cluster-access-for-kubectl#install_plugin
      provideClusterInfo: true
      interactiveMode: IfAvailable
```

When it needs a credential, `kubectl` runs `gke-gcloud-auth-plugin` and keeps the result until its
`expirationTimestamp`. The plugin prints an `ExecCredential` holding a Google OAuth access token, taken
from gcloud's credentials or ADC (docs). A stand-in printed the same shape:

```json
{"kind":"ExecCredential","apiVersion":"client.authentication.k8s.io/v1beta1","spec":{},
 "status":{"expirationTimestamp":"2099-01-01T00:00:00Z","token":"ya29.lab-user-token-for-alice"}}
```

and recorded what `kubectl` passed it in `KUBERNETES_EXEC_INFO` (because of `provideClusterInfo: true`):

```json
{"kind": "ExecCredential", "apiVersion": "client.authentication.k8s.io/v1beta1",
 "spec": {"cluster": {"server": "https://localhost:6550", "config": null, "certificate-authority-data": "…"},
          "interactive": false}}
```

### 2.2 The API server asks someone else (lab + code)

```console
$ kubectl get nodes -o wide
NAME           STATUS   ROLES           AGE     VERSION        INTERNAL-IP   EXTERNAL-IP   OS-IMAGE           KERNEL-VERSION    CONTAINER-RUNTIME
d827cd491172   Ready    control-plane   2m15s   v1.34.1+k3s1   172.17.0.4    <none>        K3s v1.34.1+k3s1   7.0.14-linuxkit   containerd://2.1.4-k3s2

$ kubectl auth whoami
ATTRIBUTE   VALUE
Username    floci:gcp-iam
UID         floci-gcp-iam
Groups      [system:masters system:authenticated]

$ kubectl auth can-i '*' '*'
yes
```

The API server can't verify a Google access token itself: it isn't a JWT it holds keys for. With
`--authentication-token-webhook-config-file`, any bearer token it doesn't recognise (not a
ServiceAccount token, not a client certificate) is sent to a webhook as a `TokenReview`, and the
webhook's answer becomes the user. floci-gcp's webhook (`GkeTokenWebhookController.review`) **(code)**:

```java
String token = extractToken(tokenReview);
boolean authenticated = token != null && !token.isBlank();
if (authenticated) {
    return Response.ok(Map.of(
            "apiVersion", apiVersion,
            "kind", "TokenReview",
            "status", Map.of(
                    "authenticated", true,
                    "user", Map.of(
                            "username", "floci:gcp-iam",
                            "uid", "floci-gcp-iam",
                            "groups", List.of("system:masters"))))).build();
}
```

So in the lab any non-empty token is an admin. In GKE the same step validates the token with Google
and the user is the **Google identity**: the user's email, or the service account's email for a
service account token (docs).

| | Real GKE (docs) | floci-gcp (lab) |
|---|---|---|
| Who validates the token | Google | nobody: any non-empty token |
| Kubernetes username | the Google account's email | `floci:gcp-iam` |
| Groups | Google Groups, if the cluster has Google Groups for RBAC (`gke-security-groups@<domain>`) | `system:masters` |
| Authorization | **IAM or RBAC**: a request is allowed if either allows it. IAM roles such as `roles/container.developer` apply to every cluster in the project; RBAC `RoleBinding`s to the email apply inside one cluster | `system:masters` → `cluster-admin` |
| Token cache | the API server caches webhook answers | `authentication-token-webhook-cache-ttl=30s` |

---

## 3. Pods calling Google APIs on GKE (docs + code)

### 3.1 What the application code does

Nothing GKE-specific. A Pod calls `GoogleCredentials.getApplicationDefault()`, and the auth library
looks in a fixed order. `DefaultCredentialsProvider.getDefaultCredentialsUnsynchronized`, 1.47.0
**(code)**:

```java
String credentialsPath = getEnv(CREDENTIAL_ENV_VAR);                       // 1. GOOGLE_APPLICATION_CREDENTIALS
if (credentialsPath != null && credentialsPath.length() > 0) {
  ...
  credentials = GoogleCredentials.fromStream(credentialsStream, transportFactory)
}
if (credentials == null) {
  File wellKnownFileLocation = getWellKnownCredentialsFile();             // 2. gcloud's application_default_credentials.json
  ...
}
...
  credentials = tryGetAppEngineCredential();                               // 3. App Engine
...
  credentials = tryGetCloudShellCredentials();                             // 4. Cloud Shell
...
  credentials = tryGetComputeCredentials(transportFactory);               // 5. the metadata server
  if (credentials != null) {
    credentials = credentials.withSource(String.format("Metadata Server URL set to %s",
                    ComputeEngineCredentials.getMetadataServerUrl(this)));
  }
```

On GKE, none of the first four apply, so it ends at `ComputeEngineCredentials` and the **metadata
server**. `ComputeEngineCredentials` **(code)**:

```java
static final String DEFAULT_METADATA_SERVER_URL = "http://metadata.google.internal";   // overridable with GCE_METADATA_HOST
...
public static String getTokenServerEncodedUrl(DefaultCredentialsProvider provider) {
  return getMetadataServerUrl(provider)
      + "/computeMetadata/v1/instance/service-accounts/default/token";
}
```

The request is `GET http://metadata.google.internal/computeMetadata/v1/instance/service-accounts/default/token`
with the header `Metadata-Flavor: Google`. The same code runs on a VM, on Cloud Run and on GKE; what
differs is **who answers**.

### 3.2 Who answers: the GKE metadata server (docs)

| Node pool setting | Who answers `169.254.169.254` / `metadata.google.internal` for a Pod | Token the Pod gets |
|---|---|---|
| Workload Identity off (`--workload-metadata=GCE_METADATA`) | the Compute Engine metadata server | the **node's** service account: every Pod on the node acts as the node |
| Workload Identity on (`--workload-metadata=GKE_METADATA`, always on Autopilot) | the **GKE metadata server**, a DaemonSet `gke-metadata-server` in `kube-system` that intercepts metadata traffic from Pods | a token for **that Pod's Kubernetes ServiceAccount** |

With the GKE metadata server, there are two ways to give the ServiceAccount access (docs):

| Way | Grant roles to | Setup |
|---|---|---|
| **Direct** (recommended) | the Kubernetes ServiceAccount itself, as a principal: `principal://iam.googleapis.com/projects/PROJECT_NUMBER/locations/global/workloadIdentityPools/PROJECT_ID.svc.id.goog/subject/ns/NAMESPACE/sa/KSA_NAME` | none on the Kubernetes side |
| **Through a Google service account** | a Google service account (GSA), as usual | annotate the ServiceAccount `iam.gke.io/gcp-service-account: GSA_EMAIL`, and grant `roles/iam.workloadIdentityUser` on the GSA to `serviceAccount:PROJECT_ID.svc.id.goog[NAMESPACE/KSA_NAME]` |

floci-gcp has no GKE metadata server, and its Compute metadata server answers only instance containers
by their IP (its docs). That binding can still be created, as data **(lab)**:

```http
POST /v1/projects/floci-local/serviceAccounts HTTP/1.1
Host: localhost:4590
Accept: */*
Authorization: Bearer ya29.lab-user-token-for-alice
Content-Type: application/json

{"accountId":"app-gsa","serviceAccount":{"displayName":"orders app"}}
```

```http
HTTP/1.1 200 OK
Content-Type: application/json;charset=UTF-8

{
  "name": "projects/floci-local/serviceAccounts/app-gsa@floci-local.iam.gserviceaccount.com",
  "projectId": "floci-local",
  "uniqueId": "166195868008257784692",
  "email": "app-gsa@floci-local.iam.gserviceaccount.com",
  "displayName": "orders app",
  "createTime": "2026-11-23T14:51:06.143563087Z",
  "etag": "aebe39e7"
}
```

```http
POST /v1/projects/floci-local/serviceAccounts/app-gsa@floci-local.iam.gserviceaccount.com:setIamPolicy HTTP/1.1
Host: localhost:4590
Accept: */*
Authorization: Bearer ya29.lab-user-token-for-alice
Content-Type: application/json

{"policy":{"bindings":[
 {"role":"roles/iam.workloadIdentityUser","members":["serviceAccount:floci-local.svc.id.goog[default/app-ksa]"]},
 {"role":"roles/iam.workloadIdentityUser","members":["principal://iam.googleapis.com/projects/691406228548/locations/global/workloadIdentityPools/k8s-pool/subject/system:serviceaccount:default:app-ksa"]}]}}
```

```http
HTTP/1.1 200 OK
Content-Type: application/json;charset=UTF-8

{
  "version": 1,
  "bindings": [
    {
      "role": "roles/iam.workloadIdentityUser",
      "members": [
        "serviceAccount:floci-local.svc.id.goog[default/app-ksa]"
      ]
    },
    {
      "role": "roles/iam.workloadIdentityUser",
      "members": [
        "principal://iam.googleapis.com/projects/691406228548/locations/global/workloadIdentityPools/k8s-pool/subject/system:serviceaccount:default:app-ksa"
      ]
    }
  ],
  "etag": "gBeVl8+pi/0="
}
```

The policy holds both member forms. `serviceAccount:floci-local.svc.id.goog[default/app-ksa]` is the
GKE form. `principal://…/workloadIdentityPools/k8s-pool/subject/system:serviceaccount:default:app-ksa`
is the federation form used in §4, where the subject is the Kubernetes token's `sub`. In enforce mode,
floci-gcp checks `iam.serviceAccounts.getAccessToken` on such bindings (its docs); in its default mode
it checks nothing.

---

## 4. The same identity from outside GKE: Workload Identity Federation (lab + code)

For a cluster that isn't GKE, or to see the exchange the GKE metadata server does for you, the Pod
runs it itself: its projected ServiceAccount token goes to **Google STS**, and the result is used to
**impersonate** a Google service account. Google's side needs a workload identity pool with an OIDC
provider that trusts the cluster's issuer (docs; floci-gcp has no pools).

```text
 ┌────────────────┐  ┌────────────────┐    ┌────────────┐    ┌─────────────────┐  ┌────────────┐
 │ kube-apiserver │  │ Pod (Java SDK) │    │ Google STS │    │ IAM Credentials │  │ Google API │
 └───────┬────────┘  └───────┬────────┘    └─────┬──────┘    └───────┬─────────┘  └─────┬──────┘
         │                   │                   │                   │                  │
         │ 1 token file      │                   │                   │                  │
         │──────────────────►│                   │                   │                  │
         │                   │                   │                   │                  │
         │                   │ 2 exchange        │                   │                  │
         │                   │──────────────────►│                   │                  │
         │                   │                   │                   │                  │
         │                   │                   │ 3 checks          │                  │
         │                   │                   │   iss, sig,       │                  │
         │                   │                   │   aud, sub        │                  │
         │                   │                   │                   │                  │
         │                   │ 4 federated       │                   │                  │
         │                   │◄──────────────────│                   │                  │
         │                   │                   │                   │                  │
         │                   │ 5 generate        │                   │                  │
         │                   │───────────────────┼──────────────────►│                  │
         │                   │                   │                   │                  │
         │                   │ 6 GSA token       │                   │                  │
         │                   │◄──────────────────┼───────────────────│                  │
         │                   │                   │                   │                  │
         │                   │ 7 API call        │                   │                  │
         │                   │───────────────────┼───────────────────┼─────────────────►│
         │                   │                   │                   │                  │
```

### 4.1 The Kubernetes token (lab)

From the lab cluster, with the provider's resource name as audience. `kubectl create token` calls
the same TokenRequest API a projected volume uses:

```console
$ kubectl create serviceaccount app-ksa
serviceaccount/app-ksa created
$ kubectl create token app-ksa --duration=1h \
    --audience=//iam.googleapis.com/projects/691406228548/locations/global/workloadIdentityPools/k8s-pool/providers/gke-lab
```

```json
{
 "aud": ["//iam.googleapis.com/projects/691406228548/locations/global/workloadIdentityPools/k8s-pool/providers/gke-lab"],
 "exp": 1795449087,
 "iat": 1795445487,
 "iss": "https://kubernetes.default.svc.cluster.local",
 "kubernetes.io": {"namespace": "default", "serviceaccount": {"name": "app-ksa", "uid": "192b6347-118f-4014-b3a4-9c4eb6f7e39b"}},
 "sub": "system:serviceaccount:default:app-ksa"
}
```

In a Pod, the same token comes from a projected volume with that `audience`, as for AWS and Azure
([workload-identity.md](workload-identity.md)).

### 4.2 The credential configuration file

`gcloud iam workload-identity-pools create-cred-config` writes a file the SDK reads through
`GOOGLE_APPLICATION_CREDENTIALS` (step 1 of the lookup in §3.1) (docs). Here it points at the
emulator through the proxy:

```json
{
  "universe_domain": "googleapis.com",
  "type": "external_account",
  "audience": "//iam.googleapis.com/projects/691406228548/locations/global/workloadIdentityPools/k8s-pool/providers/gke-lab",
  "subject_token_type": "urn:ietf:params:oauth:token-type:jwt",
  "token_url": "https://localhost:4590/v1/token",
  "credential_source": {
    "file": "<path to the token file>",
    "format": { "type": "text" }
  },
  "service_account_impersonation_url": "https://localhost:4590/v1/projects/-/serviceAccounts/app-gsa@floci-local.iam.gserviceaccount.com:generateAccessToken"
}
```

- `type: external_account` makes `GoogleCredentials.fromStream` build an **`IdentityPoolCredentials`**
  (**lab**: the program printed `credential class: IdentityPoolCredentials`).
- Without `token_url`, the library uses `https://sts.{UNIVERSE_DOMAIN}/v1/token`
  (`ExternalAccountCredentials.DEFAULT_TOKEN_URL`).
- **Both URLs must be `https`.** `ExternalAccountCredentials.isValidUrl`: "Scheme must be https and host
  must not be null" **(code)**. That's why the lab needed an HTTPS proxy in front of floci-gcp.
- Without `service_account_impersonation_url`, the STS result is used directly: the **direct** way,
  where roles are granted to the federated principal.

### 4.3 The exchange the SDK sends (lab + code)

```java
GoogleCredentials creds = GoogleCredentials.fromStream(new FileInputStream(credConfigPath))
    .createScoped(List.of("https://www.googleapis.com/auth/cloud-platform"));
creds.refresh();
```

`IdentityPoolCredentials.refreshAccessToken` reads the token file and builds an STS request
**(code)**:

```java
public AccessToken refreshAccessToken() throws IOException {
  String credential = retrieveSubjectToken();                        // the token file's content
  StsTokenExchangeRequest.Builder stsTokenExchangeRequest =
      StsTokenExchangeRequest.newBuilder(credential, getSubjectTokenType())
          .setAudience(getAudience());
  Collection<String> scopes = getScopes();
  if (scopes != null && !scopes.isEmpty()) {
    stsTokenExchangeRequest.setScopes(new ArrayList<>(scopes));
  }
  return exchangeExternalCredentialForAccessToken(stsTokenExchangeRequest.build());
}
```

and `ExternalAccountCredentials.exchangeExternalCredentialForAccessToken` decides who goes first
**(code)**:

```java
// Handle service account impersonation if necessary.
if (this.shouldBuildImpersonatedCredential()) {
  this.impersonatedCredentials = this.buildImpersonatedCredentials();
}
if (this.impersonatedCredentials != null) {
  return this.impersonatedCredentials.refreshAccessToken();    // impersonation, whose source is the STS exchange
}
StsRequestHandler.Builder requestHandler =
    StsRequestHandler.newBuilder(tokenUrl, stsTokenExchangeRequest, transportFactory.create().createRequestFactory());
```

The request on the wire:

```http
POST /v1/token HTTP/1.1
x-goog-api-client: gl-java/25.0.5.1 auth/1.47.0 google-byoid-sdk source/file sa-impersonation/true config-lifetime/false
Content-Type: application/x-www-form-urlencoded; charset=UTF-8
Host: localhost:4590
Accept: */*

grant_type=urn:ietf:params:oauth:grant-type:token-exchange&subject_token_type=urn:ietf:params:oauth:token-type:jwt&subject_token=eyJhbGciOiJSUzI1NiIsImtpZCI6Ij…&scope=https://www.googleapis.com/auth/cloud-platform&requested_token_type=urn:ietf:params:oauth:token-type:access_token&audience=//iam.googleapis.com/projects/691406228548/locations/global/workloadIdentityPools/k8s-pool/providers/gke-lab
```

```http
HTTP/1.1 400 Bad Request
Content-Type: application/json

{
  "error": "invalid_request",
  "error_description": "subject_token_type must be urn:ietf:params:oauth:token-type:access_token"
}
```

```json
// request subject_token, decoded: header, then payload
{"alg": "RS256", "kid": "6eNQHzwHCJbprN97pY-ls2m1UrKojJRyKaB_Qhfgbyk"}
{
  "aud": [
    "//iam.googleapis.com/projects/691406228548/locations/global/workloadIdentityPools/k8s-pool/providers/gke-lab"
  ],
  "exp": 1795449087,
  "iat": 1795445487,
  "iss": "https://kubernetes.default.svc.cluster.local",
  "jti": "d54c9ed8-c986-4174-af26-1b432cf3455e",
  "kubernetes.io": {
    "namespace": "default",
    "serviceaccount": {
      "name": "app-ksa",
      "uid": "192b6347-118f-4014-b3a4-9c4eb6f7e39b"
    }
  },
  "nbf": 1795445487,
  "sub": "system:serviceaccount:default:app-ksa"
}
```

- **It's RFC 8693 token exchange**: `grant_type=urn:ietf:params:oauth:grant-type:token-exchange`,
  `subject_token_type=urn:ietf:params:oauth:token-type:jwt`, the Kubernetes token, the pool provider as
  `audience`, and `requested_token_type=…:access_token`. There is **no client authentication**: as with
  AWS STS `AssumeRoleWithWebIdentity`, the token is the only credential.
- **`x-goog-api-client`** says what kind of credential sent it: `google-byoid-sdk source/file
  sa-impersonation/true`.
- **floci-gcp refused it**: "subject_token_type must be urn:ietf:params:oauth:token-type:access_token".
  Its STS only does access-token downscoping (its docs). Google STS would check the token against the
  provider: `iss` is the provider's issuer, the signature verifies against the issuer's JWKS (fetched,
  or uploaded to the provider), `aud` matches the provider. It would then map `sub` to `google.subject`
  through the provider's attribute mapping and return a **federated access token** (docs).
- The SDK reported it as `IOException: Unable to refresh sourceCredentials`: the STS step is the
  *source* of the impersonation.

### 4.4 Impersonating the Google service account (lab)

The second half, run on its own with `ImpersonatedCredentials`, whose source token stands in for
STS's output:

```java
ImpersonatedCredentials imp = ImpersonatedCredentials.newBuilder()
    .setSourceCredentials(GoogleCredentials.create(new AccessToken("ya29.federated-token-standing-in-for-sts-output", expiry)))
    .setTargetPrincipal("app-gsa@floci-local.iam.gserviceaccount.com")
    .setScopes(List.of("https://www.googleapis.com/auth/cloud-platform"))
    .setLifetime(3600)
    .setIamEndpointOverride("https://localhost:4590/v1/projects/-/serviceAccounts/app-gsa@floci-local.iam.gserviceaccount.com:generateAccessToken")
    .build();
imp.refresh();
```

```http
POST /v1/projects/-/serviceAccounts/app-gsa@floci-local.iam.gserviceaccount.com:generateAccessToken HTTP/1.1
Authorization: Bearer ya29.federated-token-standing-…
x-goog-api-client: gl-java/25.0.5.1 auth/1.47.0 auth-request-type/at cred-type/imp
Content-Type: application/json; charset=UTF-8
Host: localhost:4590
Accept: */*

{"delegates":[],"scope":["https://www.googleapis.com/auth/cloud-platform"],"lifetime":"3600s"}
```

```http
HTTP/1.1 200 OK
Content-Type: application/json;charset=UTF-8

{
  "accessToken": "floci-gcp-impersonated-3c5a900…",
  "expireTime": "2026-11-23T15:51:56.575203430Z"
}
```

```text
impersonated token floci-gcp-impersonated-3c5a900c-9059-4791-baea-3659a36bd773 expires Mon Nov 23 19:51:56 GMT+04:00 2026
```

- **`POST …/serviceAccounts/app-gsa@…:generateAccessToken`**, authenticated with the source token,
  asking for `scope` and `lifetime`. The answer is a token *for the Google service account*.
- Google checks that the source principal holds `iam.serviceAccounts.getAccessToken` on `app-gsa`:
  that's what `roles/iam.workloadIdentityUser` (granted in §3.2) gives (docs).
- **`setIamEndpointOverride` takes the full URL**, target included. A base URL (`https://localhost:4590`)
  was sent as `POST /` and got `404` (an earlier attempt in the lab). The Javadoc says "The full IAM
  endpoint override with the target_principal embedded" **(code)**.

### 4.5 The whole flow, GKE and non-GKE

| Step | Non-GKE cluster, run by the SDK (§4) | GKE with Workload Identity (docs) |
|---|---|---|
| Kubernetes token | projected volume, `aud` = the pool provider | handled by the GKE metadata server |
| Exchange at STS | `IdentityPoolCredentials` → `POST sts.googleapis.com/v1/token` | done for the Pod by the GKE metadata server |
| Google identity | federated principal `…/subject/system:serviceaccount:NS:SA` | `…/PROJECT_ID.svc.id.goog/subject/ns/NS/sa/SA` |
| Impersonation (optional) | `service_account_impersonation_url` in the config | `iam.gke.io/gcp-service-account` annotation |
| What the app calls | `GoogleCredentials.getApplicationDefault()` with `GOOGLE_APPLICATION_CREDENTIALS` | `GoogleCredentials.getApplicationDefault()`, nothing set |
| Pool | one you create, trusting the cluster's issuer | the fixed pool `PROJECT_ID.svc.id.goog`, created with the cluster |

---

## 5. Compared with EKS and AKS

| | GKE | EKS (IRSA) | AKS (Workload Identity) |
|---|---|---|---|
| `kubectl` token | Google OAuth access token from `gke-gcloud-auth-plugin` | a presigned STS `GetCallerIdentity` URL from `aws eks get-token` | an Entra ID token from `kubelogin` |
| Pod identity, platform-run | GKE metadata server answers the metadata request | (EKS Pod Identity: the node agent at `169.254.170.23`) | — |
| Pod identity, SDK-run exchange | STS token exchange (RFC 8693) + optional `generateAccessToken` | STS `AssumeRoleWithWebIdentity` | Entra ID `client_credentials` with the token as `client_assertion` |
| Where the trust is configured | IAM policy on the principal (direct) or `workloadIdentityUser` on a GSA | the IAM role's trust policy | a federated identity credential on the managed identity |
| Linked by | the fixed pool `PROJECT_ID.svc.id.goog` + namespace/name | the cluster's OIDC provider + `sub` | issuer + `sub` + audience |

The EKS and AKS columns are from [workload-identity.md](workload-identity.md),
[irsa-walkthrough.md](irsa-walkthrough.md) and [azure-identity-flows.md](azure-identity-flows.md).
The `kubectl` token rows for EKS and AKS are from AWS and Microsoft documentation, not tested.

---

## 6. Where floci-gcp differs from Google Cloud

| floci-gcp 0.13.0 | Google Cloud |
|---|---|
| a GKE cluster is one k3s container; nodes and node service accounts aren't real | Google-managed control plane, Compute Engine nodes running as the node pool's service account |
| the token webhook accepts any non-empty token as `system:masters` | Google validates the token; the user is the Google identity; IAM or RBAC authorizes |
| no GKE metadata server | `gke-metadata-server` on Workload Identity node pools |
| STS only exchanges access tokens (downscoping) | STS exchanges external JWTs, SAML and AWS credentials for federated tokens |
| no workload identity pools or providers | pools, providers, attribute mappings and conditions |
| IAM policies stored, checked only in enforce mode | IAM checked on every call |
| `CreateCluster` operation reported `RUNNING` (its docs say operations resolve synchronously) | a long-running operation |

---

## Related notes

- [workload-identity.md](workload-identity.md): the token files in a Pod, IRSA, EKS Pod Identity and Azure Workload Identity
- [irsa-walkthrough.md](irsa-walkthrough.md): the AWS equivalent end to end, against the AWS emulator
- [azure-identity-flows.md](azure-identity-flows.md): Azure users, service principals and managed identities with the Java SDK
- [rbac-and-identity.md](rbac-and-identity.md): ServiceAccount tokens and OIDC discovery
- [oauth-grants-and-client-auth.md](oauth-grants-and-client-auth.md): the token-exchange grant (RFC 8693) that STS implements
