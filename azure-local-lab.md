# Azure locally with floci-az (lab)

Entra ID, Graph, ARM, virtual networks, virtual machines, managed identity, Key Vault, AKS, Container
Apps and ARM templates, each tried against [floci-az](https://github.com/floci-io/floci-az), a local
Azure emulator, with `curl` and `jq` only (no `az` CLI). Every command was run and every output is
real, except where a section says otherwise.

How the code behind it works (token minting, validation, IMDS) is in
[azure.md §4, Entra ID locally](azure.md#entra-id-locally-floci-az-from-the-source-not-run).

- [Setup](#setup)
- [1. Entra ID](#1-entra-id)
- [2. Microsoft Graph](#2-microsoft-graph)
- [3. Resource groups and virtual networks](#3-resource-groups-and-virtual-networks)
- [4. Virtual machines](#4-virtual-machines)
- [5. Managed identity](#5-managed-identity)
- [6. Key Vault](#6-key-vault)
- [7. AKS](#7-aks)
- [8. Container Apps](#8-container-apps)
- [9. ARM templates](#9-arm-templates)
- [10. Azure RBAC (not available in 0.16.0)](#10-azure-rbac-not-available-in-0160)
- [11. User and application identities](#11-user-and-application-identities)
- [12. Signing in as a user or as an app](#12-signing-in-as-a-user-or-as-an-app)
- [13. Load balancer](#13-load-balancer)
- [Where floci-az differs from Azure](#where-floci-az-differs-from-azure)

---

## Setup

floci-az **0.16.0** in Docker, listening on `localhost:4577`, with the Docker socket mounted (AKS
starts real k3s containers through it):

```
$ curl -s localhost:4577/_floci/health
{"status":"UP","version":"0.16.0","edition":"floci-az-always-free"}
```

Each service is either **real** (it starts containers) or **mocked** (ARM state only). With the
defaults:

| Service | Mode | What that means |
|---|---|---|
| Entra ID, Graph, Key Vault, managed identity, ARM, deployments | in-process | fully working HTTP APIs |
| AKS | **real** | one `rancher/k3s` container per cluster |
| Virtual machines | mocked | power state only. `FLOCI_AZ_SERVICES_VM_MOCKED=false` backs each VM with a container |
| Container Apps | mocked | ARM state and revisions, no containers, no ingress. `FLOCI_AZ_SERVICES_CONTAINER_APPS_MOCKED=false` runs them |
| Virtual networks | control plane only | stored and returned, no packets, no isolation |

Variables and helpers used below (`common.sh`):

```bash
E=http://127.0.0.1:4577
T=00000000-0000-0000-0000-000000000002        # default tenant
C=11111111-1111-1111-1111-111111111111        # seeded dev app (client id)
SUB=00000000-0000-0000-0000-000000000001
# decode a JWT's payload (base64url → JSON)
claims() { cut -d. -f2 | tr '_-' '/+' | awk '{l=length($0)%4; if(l==2)$0=$0"=="; else if(l==3)$0=$0"="; print}' | base64 -d; }
# an app-only token for ARM
armtoken() { curl -s $E/$T/oauth2/v2.0/token -d grant_type=client_credentials -d client_id=$C \
  -d client_secret=floci-az-dev-secret -d scope=https://management.azure.com/.default | jq -r .access_token; }
```

ARM calls below send `Authorization: Bearer $(armtoken)`, as real clients do. In this setup ARM
doesn't require it (§10).

---

## 1. Entra ID

**Discovery.** Clients configure themselves from this document:

```
$ curl -s $E/$T/v2.0/.well-known/openid-configuration | jq '{issuer, token_endpoint, jwks_uri, grant_types_supported}'
{
  "issuer": "http://127.0.0.1:4577/00000000-0000-0000-0000-000000000002/v2.0",
  "token_endpoint": "http://127.0.0.1:4577/00000000-0000-0000-0000-000000000002/oauth2/v2.0/token",
  "jwks_uri": "http://127.0.0.1:4577/00000000-0000-0000-0000-000000000002/discovery/v2.0/keys",
  "grant_types_supported": [ "authorization_code", "client_credentials", "password", "refresh_token" ]
}
```

**App-only token (client credentials).** The scope `{resource}/.default` becomes the audience:

```
$ curl -s $E/$T/oauth2/v2.0/token -d grant_type=client_credentials -d client_id=$C \
    -d client_secret=floci-az-dev-secret -d scope=https://management.azure.com/.default
{ "token_type": "Bearer", "expires_in": 3599, "access_token": "eyJ0eXAiOiJKV1QiLCJhbGciOiJSUzI1NiIsImtp…" }

header: {"typ":"JWT","alg":"RS256","kid":"Cu5E1EL1wAyWHyZ4xv98NvsG"}
claims:
{
  "iss": "http://127.0.0.1:4577/00000000-0000-0000-0000-000000000002/v2.0",
  "sub": "33333333-3333-3333-3333-333333333333",     ← the app's service principal
  "aud": "https://management.azure.com",              ← from scope=…/.default
  "exp": 1794697919, "iat": 1794694320, "nbf": 1794694320,
  "oid": "33333333-3333-3333-3333-333333333333",
  "tid": "00000000-0000-0000-0000-000000000002",
  "ver": "2.0",
  "azp": "11111111-1111-1111-1111-111111111111",     ← v2 name for the client id
  "idtyp": "app",                                     ← app-only, no user
  "uti": "3tDpBp2CGOogjS8hEtF1ww"
}
```

The v1 endpoint (`/oauth2/token`, `resource=` instead of `scope=`) gives the v1 shape:

```
{"ver":"1.0","aud":"https://vault.azure.net","appid":"11111111-1111-1111-1111-111111111111","azp":null,"idtyp":"app"}
```

**Verifying a token** with the public key from the JWKS (`x5c` is the certificate holding it):

```
$ curl -s $E/$T/discovery/v2.0/keys | jq '.keys[0] | {kty, use, alg, kid}'
{ "kty": "RSA", "use": "sig", "alg": "RS256", "kid": "Cu5E1EL1wAyWHyZ4xv98NvsG" }

$ openssl x509 -in jwks-cert.pem -pubkey -noout > k.pub
$ openssl dgst -sha256 -verify k.pub -signature sig.bin header.payload
Verified OK
# the same signature over a payload with one claim changed ("oid":"evil")
Verification Failure
```

**Browser sign-in: authorization code + PKCE.** There's no login page: `/authorize` approves the
seeded dev user (or the `login_hint` user) and redirects straight back:

```
$ curl -s -o /dev/null -w '%{redirect_url}' "$E/$T/oauth2/v2.0/authorize?client_id=$C\
&redirect_uri=https://app.local/callback&response_type=code&scope=openid%20profile%20offline_access\
%20https://management.azure.com/user_impersonation&state=xyz&nonce=n-123\
&code_challenge=$CHALLENGE&code_challenge_method=S256"
https://app.local/callback?code=14f4717a-d09…&state=xyz

# redeem with the wrong code_verifier
{"error":"invalid_grant","code":[70000]}
# …which also uses the code up
AADSTS70000: authorization code is invalid or has already been used

# a new code, redeemed with the right verifier
{"token_type":"Bearer","scope":"https://management.azure.com/user_impersonation","id_token":true,"refresh_token":true}
access token: {"aud":"11111111-1111-1111-1111-111111111111","scp":"openid profile https://management.azure.com/user_impersonation","preferred_username":"dev-user@floci-az.local","ver":"2.0"}
ID token:     {"aud":"11111111-1111-1111-1111-111111111111","nonce":"n-123","preferred_username":"dev-user@floci-az.local","name":"floci-az dev user"}

# refresh, asking for another resource
$ curl -s $E/$T/oauth2/v2.0/token -d grant_type=refresh_token -d client_id=$C \
    --data-urlencode "refresh_token=$RT" -d 'scope=https://graph.microsoft.com/User.Read'
access token: {"aud":"11111111-1111-1111-1111-111111111111","scp":"https://graph.microsoft.com/User.Read"}
```

- **The ID token is right:** `aud` is the client, and `nonce` echoes the request (MSAL rejects a
  mismatch).
- **The user's *access* token isn't shaped like Azure's.** Azure would issue
  `aud: https://management.azure.com` and `scp: user_impersonation`. floci-az puts the client id in
  `aud` and the full scope URLs in `scp`. The password grant (ROPC) does the same.

**Errors** are shaped like Entra's, with `AADSTS` codes:

```
{"error":"unsupported_grant_type","error_codes":[70003],"error_description":"AADSTS70003: grant_type 'device_code' is not supported in this phase"}
```

---

## 2. Microsoft Graph

A token for Graph has `aud: https://graph.microsoft.com`. Graph is served at `$E/v1.0/…`.

**The seeded directory:**

```
$ curl -s $E/v1.0/groups | jq -c '.value[] | {id, displayName}'
{"id":"44444444-4444-4444-4444-444444444444","displayName":"floci-az dev group"}
$ curl -s "$E/v1.0/servicePrincipals?\$filter=appId eq '$C'" | jq -c '.value[] | {id, appId}'
{"id":"33333333-3333-3333-3333-333333333333","appId":"11111111-1111-1111-1111-111111111111"}
```

**An app registration and its service principal.** Creating an application also creates its service
principal. They're two objects with different ids:

```
$ curl -s -X POST $E/v1.0/applications -d '{"displayName":"orders-api","signInAudience":"AzureADMyOrg"}'
{"id":"0b18e3ac-…","appId":"b49edda6-e05d-42e5-93fa-73656d6d30af","displayName":"orders-api"}   ← application object
$ curl -s "$E/v1.0/servicePrincipals?\$filter=appId eq 'b49edda6-…'"
{"id":"fbb9ca78-d623-3b84-9419-6af9ac319940","appId":"b49edda6-e05d-42e5-93fa-73656d6d30af"}    ← service principal
```

**A client secret** is returned once, then never again:

```
$ curl -s -X POST $E/v1.0/applications/$OID/addPassword -d '{"passwordCredential":{"displayName":"ci"}}'
{"keyId":"33fc61d8-…","displayName":"ci","secretText":"_94d5M…","endDateTime":"2028-11-13T22:12:18Z"}
$ curl -s $E/v1.0/applications/$OID | jq -c '.passwordCredentials[]'
{"keyId":"33fc61d8-…","displayName":"ci","secretText":null}
```

The new app gets its own token. `oid` and `sub` are the **service principal's** id, not the
application object's, as in Azure:

```
{"azp":"b49edda6-e05d-42e5-93fa-73656d6d30af","oid":"fbb9ca78-d623-3b84-9419-6af9ac319940","sub":"fbb9ca78-…","idtyp":"app"}
```

**Group membership.** Tokens carry no `groups` claim; apps ask Graph:

```
$ curl -s -X POST "$E/v1.0/users/dev-user@floci-az.local/getMemberGroups" -d '{"securityEnabledOnly":false}'
{"value":["44444444-4444-4444-4444-444444444444"]}
$ curl -s -X POST "$E/v1.0/groups/$GID/members/\$ref" \
    -d '{"@odata.id":"https://graph.microsoft.com/v1.0/directoryObjects/fbb9ca78-…"}'      → 204
$ curl -s "$E/v1.0/groups?\$filter=id in ('$GID')&\$expand=members"
{"displayName":"floci-az dev group","members":[
  {"id":"9cec5cf5-…","t":"#microsoft.graph.user"},
  {"id":"fbb9ca78-…","t":"#microsoft.graph.servicePrincipal"}]}
```

Graph answered `GET /v1.0/groups` with **no token at all** (`200`).

---

## 3. Resource groups and virtual networks

All of these are ARM `PUT`s to `/subscriptions/{sub}/resourceGroups/{rg}/providers/{namespace}/{type}/{name}`.

```
# resource group
PUT …/resourceGroups/lab?api-version=2021-04-01   {"location":"westeurope"}
{"id":"/subscriptions/…/resourceGroups/lab","location":"westeurope","state":"Succeeded"}

# NSG with one rule; Azure's 6 default rules are added
PUT …/Microsoft.Network/networkSecurityGroups/web-nsg?api-version=2024-05-01
{"name":"web-nsg","rules":[{"name":"allow-https","priority":100,"port":"443"}],"defaults":6}

# VNet 10.10.0.0/16 with two subnets, the NSG on "web"
PUT …/Microsoft.Network/virtualNetworks/hub?api-version=2024-05-01
{"name":"hub","space":["10.10.0.0/16"],"subnets":[
  {"name":"web","prefix":"10.10.1.0/24","nsg":"web-nsg"},
  {"name":"data","prefix":"10.10.2.0/24","nsg":null}]}

# public IP and a NIC in the web subnet
PUT …/publicIPAddresses/vm1-pip   {"name":"vm1-pip","ip":"20.0.0.4","method":"Static"}
PUT …/networkInterfaces/vm1-nic   {"name":"vm1-nic","privateIp":"10.0.0.4","alloc":"Dynamic"}
```

Two things Azure would refuse were accepted:

```
PUT …/virtualNetworks/hub/subnets/bad      {"properties":{"addressPrefix":"192.168.0.0/24"}}   ← outside 10.10.0.0/16: accepted
PUT …/virtualNetworks/hub/subnets/overlap  {"properties":{"addressPrefix":"10.10.1.128/25"}}   ← overlaps "web": accepted
```

The NIC's address isn't taken from its subnet either: `10.0.0.4` in a subnet of `10.10.1.0/24`.
Azure would give `10.10.1.4`: the first 4 addresses of a subnet are reserved.

Network resources are stored and returned only. No packets flow, NSG rules aren't enforced, and
nothing is routed.

---

## 4. Virtual machines

```
PUT …/Microsoft.Compute/virtualMachines/vm1?api-version=2024-11-01
  {"identity":{"type":"SystemAssigned"}, "properties":{"hardwareProfile":{"vmSize":"Standard_B2s"},
   "storageProfile":{"imageReference":{"publisher":"Canonical","offer":"ubuntu-24_04-lts",…}},
   "networkProfile":{"networkInterfaces":[{"id":"…/networkInterfaces/vm1-nic"}]}, …}}
{"name":"vm1","size":"Standard_B2s","state":"Succeeded","identity":null,"principalId":null}

GET …/vm1/instanceView     ["ProvisioningState/succeeded","PowerState/running"]
POST …/vm1/powerOff        → PowerState/stopped
POST …/vm1/start           → PowerState/running
POST …/vm1/deallocate      → PowerState/deallocated
```

- **Power states follow Azure's.** `stopped` still holds the compute (and is billed in Azure);
  `deallocated` releases it.
- **The `identity` block was dropped**: `identity` is `null` on both PUT and GET. A system-assigned
  identity can still be read from its own endpoint, with a `principalId` derived from the VM's id:

  ```
  GET …/vm1/providers/Microsoft.ManagedIdentity/identities/default?api-version=2023-01-31
  {"type":"Microsoft.ManagedIdentity/identities","principalId":"cc73e3d5-8cfb-3fa6-852c-1e2d31a74a0e","tenantId":"00000000-…-000000000002"}
  ```
- **The NIC doesn't record which VM uses it** (`properties.virtualMachine` stays `null`).

---

## 5. Managed identity

**A user-assigned identity** gets two ids. `principalId` is its object id in Entra (used in role
assignments); `clientId` is its app id (used to ask for tokens):

```
PUT …/Microsoft.ManagedIdentity/userAssignedIdentities/orders-id?api-version=2023-01-31
{"name":"orders-id","principalId":"dea7eff2-d136-4448-b359-6da5a2cd95d3","clientId":"fc08d3dd-e722-419b-8d5d-0528dc4da9a7","tenantId":"00000000-…-000000000002"}
```

In Azure a managed identity **is a service principal** of type `ManagedIdentity`: `principalId` is
that service principal's object id and `clientId` is its `appId` (see
[azure.md §4](azure.md#a-managed-identity-is-a-service-principal)). floci-az doesn't link the two.
Graph has no service principal under the identity's `principalId`, and a lookup by its `clientId`
returns a placeholder that Graph returns for **any** `appId`, even one that doesn't exist:

```
GET $E/v1.0/servicePrincipals?$filter=appId eq 'fc08d3dd-…'           ← the identity's clientId
[{"id":"00000000-0000-0000-0000-000000000010","appId":"fc08d3dd-…","servicePrincipalType":null}]
GET $E/v1.0/servicePrincipals?$filter=appId eq 'deadbeef-…-000000000099' ← no such app
[{"id":"00000000-0000-0000-0000-000000000010","appId":"deadbeef-…-000000000099"}]
GET $E/v1.0/servicePrincipals/dea7eff2-…                               ← the identity's principalId
{"error":{"code":"BadRequest",…}}
GET $E/v1.0/applications?$filter=appId eq 'fc08d3dd-…'                 ← no app registration, as in Azure
{"value":[]}
```

Azure would return one service principal with `id` = `principalId` (`dea7eff2-…`) and
`servicePrincipalType: ManagedIdentity`. Tokens are still right: the IMDS token below carries
`oid` = `principalId` and `appid` = `clientId`.

**A federated identity credential** for Workload Identity, trusting one AKS ServiceAccount:

```
PUT …/orders-id/federatedIdentityCredentials/orders-sa?api-version=2023-01-31
{"properties":{"issuer":"https://oidc.prod-aks.azure.com/abc/","subject":"system:serviceaccount:orders:orders-api","audiences":["api://AzureADTokenExchange"]}}
```

**IMDS**, the endpoint an app inside Azure calls (`169.254.169.254` in Azure):

```
$ curl -s "$E/metadata/identity/oauth2/token?api-version=2018-02-01&resource=https://vault.azure.net&client_id=$CID"
{"error":"invalid_request","error_description":"Required metadata header not specified"}

$ curl -s -H 'Metadata: true' "$E/metadata/identity/oauth2/token?api-version=2018-02-01&resource=https://vault.azure.net&client_id=$CID"
{"token_type":"Bearer","resource":"https://vault.azure.net","client_id":"fc08d3dd-…","expires_in":"3599","expires_on":"1794697998"}
claims: {"ver":"1.0","aud":"https://vault.azure.net","appid":"fc08d3dd-…","oid":"dea7eff2-…","sub":"dea7eff2-…","idtyp":"app",
         "xms_mirid":"/subscriptions/…/resourceGroups/lab/providers/Microsoft.ManagedIdentity/userAssignedIdentities/orders-id"}
```

- **`Metadata: true` is required**, as in Azure. A server tricked into fetching a URL (SSRF) can't
  add that header, so it can't steal the identity's token.
- **`oid` = the identity's `principalId`**, and `xms_mirid` names the identity's resource id.
- **IMDS returns every number as a string** (`"expires_in":"3599"`), as Azure does.

**The Workload Identity exchange isn't verified.** An unsigned assertion (`alg: none`) from an
issuer no credential trusts still got a token:

```
$ curl -s $E/$T/oauth2/v2.0/token -d grant_type=client_credentials -d client_id=$CID \
    -d client_assertion_type=urn:ietf:params:oauth:client-assertion-type:jwt-bearer \
    --data-urlencode "client_assertion=<alg:none, iss:https://evil.example/>" -d scope=https://vault.azure.net/.default
{"got_token":true,"error":null}
```

Azure would check the assertion's signature against the issuer's keys, and match `iss`, `sub` and
`aud` against a federated credential. floci-az stores federated credentials but never uses them, so it
can't show whether a federation is set up correctly.

---

## 6. Key Vault

Key Vault has two APIs: **ARM** (`Microsoft.KeyVault/vaults`) creates the vault, and the **data
plane** (`https://{vault}.vault.azure.net`, here `$E/{vault}-keyvault`) holds secrets, keys and
certificates.

```
PUT …/Microsoft.KeyVault/vaults/orders-kv?api-version=2023-07-01
  {"properties":{"tenantId":"…","sku":{"family":"A","name":"standard"},"enableRbacAuthorization":true,"enableSoftDelete":true,…}}
{"name":"orders-kv","vaultUri":"https://orders-kv.vault.azure.net/","rbac":null,"softDelete":false}
```

The `vaultUri` matches Azure's, but `enableRbacAuthorization` and the soft-delete settings weren't
kept.

**Secrets** need a token for `https://vault.azure.net`:

```
V=$E/orders-kv-keyvault
PUT $V/secrets/db-password?api-version=7.5   {"value":"s3cr3t-v1","contentType":"text/plain","tags":{"app":"orders"}}
{"id":"https://orders-kv.vault.azure.net/secrets/db-password/eac2f63f9bd944c685487fe679216d54","value":"s3cr3t-v1","enabled":true}
PUT $V/secrets/db-password                   {"value":"s3cr3t-v2"}      → new version 99efb430…

GET $V/secrets/db-password                   {"value":"s3cr3t-v2"}      ← latest
GET $V/secrets/db-password/versions          [{"v":"99efb430","enabled":true},{"v":"eac2f63f","enabled":true}]
GET $V/secrets                               [{"id":"https://orders-kv.vault.azure.net/secrets/db-password","has_value":false}]   ← list never shows values
```

**Soft delete and recover:**

```
DELETE $V/secrets/db-password                {"recoveryId":"https://orders-kv.vault.azure.net/deletedsecrets/db-password","scheduledPurgeDate":1795299237}
GET    $V/secrets/db-password                → 404
GET    $V/deletedsecrets                     ["https://orders-kv.vault.azure.net/deletedsecrets/db-password"]
POST   $V/deletedsecrets/db-password/recover → back, value "s3cr3t-v2"
```

**Keys never leave the vault.** You send a digest and the vault signs it:

```
POST $V/keys/signing/create   {"kty":"RSA","key_size":2048,"key_ops":["sign","verify"]}
{"kid":"signing/ccbb8c0c…","kty":"RSA","ops":["sign","verify"],"has_private_d":false}   ← no private part returned

POST $V/keys/signing/sign     {"alg":"RS256","value":"<base64url SHA-256 of 'hello'>"}   → signature
POST $V/keys/signing/verify   {"alg":"RS256","digest":"…","value":"<signature>"}          → {"value":true}
```

**Authentication:**

```
GET $V/secrets/db-password  (no token)
401   WWW-Authenticate: Bearer authorization="https://login.microsoftonline.com/common", resource="https://vault.azure.net"
GET $V/secrets/db-password  (a token with aud=https://management.azure.com)
200
```

The `401` challenge is how Azure SDKs learn which tenant and resource to get a token for, and
floci-az sends it. But any token is accepted after that: Azure would refuse a token for the wrong
audience, and would check the caller's Key Vault RBAC role or access policy.

---

## 7. AKS

AKS runs for real: each cluster is a `rancher/k3s` container.

```
PUT …/Microsoft.ContainerService/managedClusters/orders-aks?api-version=2024-04-01
  {"identity":{"type":"SystemAssigned"},"properties":{"kubernetesVersion":"1.30","dnsPrefix":"orders",
   "agentPoolProfiles":[{"name":"system","count":1,"vmSize":"Standard_D2s_v5","mode":"System"}],
   "oidcIssuerProfile":{"enabled":true},"securityProfile":{"workloadIdentity":{"enabled":true}},
   "networkProfile":{"networkPlugin":"azure","networkPluginMode":"overlay","podCidr":"192.168.0.0/16"}}}
{"name":"orders-aks","state":"Creating","version":"1.30","fqdn":"floci-az-aks-4e03699b:6443",
 "oidc":null,"wi":null,"identity":null,"nodeRG":"MC_lab_orders-aks_westeurope"}
… polling …
state after waiting: Succeeded

$ docker ps --filter name=floci-az
floci-az-aks-4e03699b   rancher/k3s:latest      0.0.0.0:6443->6443/tcp
floci-az                floci/floci-az:latest   0.0.0.0:4577->4577/tcp
```

**The kubeconfig** comes from `listClusterAdminCredential`, base64-encoded as in Azure. Its server is
the container's name, so from the host replace it with `127.0.0.1:6443`:

```
$ curl -s -X POST "$AKS/listClusterAdminCredential?api-version=2024-04-01" | jq -r '.kubeconfigs[0].value' | base64 -d > aks.kubeconfig
$ sed -i '' 's#https://floci-az-aks-4e03699b:6443#https://127.0.0.1:6443#' aks.kubeconfig
$ kubectl --kubeconfig aks.kubeconfig get nodes -o wide
NAME           STATUS   ROLES           AGE    VERSION        INTERNAL-IP   OS-IMAGE           CONTAINER-RUNTIME
413e260f2e9a   Ready    control-plane   111s   v1.34.1+k3s1   172.17.0.3    K3s v1.34.1+k3s1   containerd://2.1.4-k3s2
$ kubectl --kubeconfig aks.kubeconfig get pods -A
kube-system   coredns-7896679cc-bzwl8                   1/1     Running
kube-system   local-path-provisioner-578895bd58-7pcfz   1/1     Running
kube-system   metrics-server-7b9c9c4b9c-x9nq8           1/1     Running
```

**Scaling the node pool** changes only the ARM record:

```
PUT …/orders-aks/agentPools/system   {"properties":{"count":3,…}}
{"name":"system","count":3,"state":"Succeeded"}          ← kubectl still shows 1 node
```

What's real and what isn't:

- **Real:** a working Kubernetes API, so manifests, Helm and controllers can be tested.
- **Not real:** the version (asked for 1.30, got k3s 1.34); OIDC issuer, Workload Identity, the
  cluster identity and the network profile were all dropped (`null`); node count; Azure CNI (k3s
  uses flannel). The node resource group name (`MC_lab_orders-aks_westeurope`) follows Azure's
  pattern but nothing is created in it.

### How floci-az builds the cluster (source)

The cluster is made by `AksClusterManager`
(`src/main/java/io/floci/az/services/aks/AksClusterManager.java`). `startCluster` runs when the
`PUT …/managedClusters/{name}` arrives:

```java
@Inject
public AksClusterManager(ContainerBuilder containerBuilder,
                          ContainerLifecycleManager lifecycleManager,
                          ContainerDetector containerDetector,
                          PortAllocator portAllocator,
                          EmulatorConfig config) {
    this.containerBuilder = containerBuilder;
    this.lifecycleManager = lifecycleManager;
    this.containerDetector = containerDetector;
    this.portAllocator = portAllocator;
    this.config = config;
}

/**
 * Starts a k3s container for the given cluster. Sets containerId and endpoint on the cluster.
 * Status remains "Creating" until {@link #isReady} returns true and {@link #finalizeCluster} is called.
 */
public void startCluster(ManagedCluster cluster) {
    String image = config.services().aks().defaultImage();
    String containerName = containerName(cluster);

    LOG.infov("Starting k3s container for AKS cluster: {0} using image {1}", cluster.getName(), image);

    releaseHostPort(cluster.getContainerId());
    int hostPort = portAllocator.allocate(
            config.services().aks().apiServerBasePort(),
            config.services().aks().apiServerMaxPort());

    // Named volume for k3s data — prevents macOS APFS chmod(EINVAL) that crashes kine.
    // Created explicitly (not implicitly by the mount) so it carries the emulator labels.
    String volumeName = containerName;
    boolean volumeCreatedByThisStart = false;
    ContainerLifecycleManager.ContainerInfo info;
    try {
        // Everything after the allocation runs inside this block, so a failure in the
        // stale-container cleanup or the volume setup also gives the port back.
        lifecycleManager.removeIfExists(containerName);
        volumeCreatedByThisStart = !lifecycleManager.volumeExists(volumeName);
        lifecycleManager.ensureVolume(volumeName);
        ContainerSpec spec = containerBuilder.newContainer(image)
                .withName(containerName)
                .withLabels(ContainerStorageHelper.resourceIdentityLabels("aks", cluster.getName(),
                        cluster.getSubscriptionId(), cluster.getResourceGroup(), cluster.getLocation()))
                .withCmd(k3sServerArgs())
                .withEnv("K3S_KUBECONFIG_MODE", "644")
                .withPortBinding(K3S_API_SERVER_PORT, hostPort)
                .withNamedVolume(volumeName, "/var/lib/rancher/k3s")
                .withDockerNetwork(config.services().dockerNetwork())
                .withPrivileged(true)
                .withLogRotation()
                .build();
        info = lifecycleManager.createAndStart(spec);
    }
    … (on failure: remove the volume if this start created it, release the port, rethrow)
```

What each step does, and where it showed up in the lab:

| Step | Code | Value (defaults, `application.yml`) | Seen in the lab |
|---|---|---|---|
| Image | `config.services().aks().defaultImage()` | `rancher/k3s:latest` | k3s **v1.34.1**, whatever `kubernetesVersion` the request asked for (1.30) |
| Container name | `containerName(cluster)` = `"aks-" + cluster.getInstanceId()`, prefixed | — | `floci-az-aks-4e03699b` |
| API server host port | `portAllocator.allocate(apiServerBasePort, apiServerMaxPort)` | a free port in **6443–7443** | `0.0.0.0:6443->6443/tcp` (the first free one) |
| k3s data | a **named volume** at `/var/lib/rancher/k3s` | named like the container | kept across restarts of the container; avoids a macOS APFS `chmod` error that crashes k3s's datastore (kine) |
| Labels | `resourceIdentityLabels("aks", name, subscription, resource group, location)` | — | lets the emulator find its containers again |
| Command | `k3sServerArgs()` | `server --disable=traefik --tls-san=localhost` (+ the Docker host's address, if published) | **Traefik is off, ServiceLB is on**: that's why `type: LoadBalancer` got the `svclb-…` Pod in the NodePort lab |
| Kubeconfig file mode | `K3S_KUBECONFIG_MODE=644` | — | lets the emulator read `/etc/rancher/k3s/k3s.yaml` |
| Network | `withDockerNetwork(…)` | the emulator's Docker network | the node got `172.17.0.3`, next to floci-az at `172.17.0.2` |
| Privileged | `withPrivileged(true)` | — | k3s needs it for iptables, mounts and containerd inside the container |

One container is one node, so the requested `agentPoolProfiles[].count` and `vmSize` only exist in
the ARM record. That's why scaling the pool to 3 changed nothing in `kubectl get nodes`.

**After the container starts** (the rest of `startCluster`, then `isReady` and `finalizeCluster`):

```java
// startCluster: which address the kubeconfig will point at
if (lifecycleManager.publishedEndpoints()) {
    cluster.setEndpoint("https://" + lifecycleManager.daemonHost() + ":" + hostPort);
} else if (containerDetector.isRunningInContainer()) {          // floci-az itself runs in Docker (this lab)
    cluster.setEndpoint("https://" + containerName + ":" + K3S_API_SERVER_PORT);
} else {
    cluster.setEndpoint("https://localhost:" + hostPort);
}
cluster.setFqdn(cluster.getEndpoint().replace("https://", ""));   // → "floci-az-aks-4e03699b:6443"

// isReady: polled while provisioningState is "Creating"
String livezUrl = endpoint + "/livez";                            // k3s's API server liveness endpoint

// finalizeCluster: the kubeconfig that listClusterAdminCredential returns
String kubeconfigYaml = execInContainer(containerId, new String[]{"cat", "/etc/rancher/k3s/k3s.yaml"});
String rewritten = kubeconfigYaml.replaceAll("server: https://[^\\n]+", "server: " + publicEndpoint);
cluster.setKubeconfig(Base64.getEncoder().encodeToString(rewritten.getBytes(StandardCharsets.UTF_8)));
```

- **The endpoint explains the `sed` in the lab.** floci-az runs in a container, so it points the
  kubeconfig at the container name (`https://floci-az-aks-4e03699b:6443`), which only resolves on
  the Docker network. From the Mac, the published port is used instead: `127.0.0.1:6443`.
- **`provisioningState` stays `Creating`** until `/livez` answers; then `finalizeCluster` reads
  k3s's own kubeconfig, rewrites its `server:` line, and stores it base64-encoded. That's exactly
  what `listClusterAdminCredential` returned (`.kubeconfigs[0].value`, decoded with `base64 -d`).
- **Everything else in the request is stored, not applied:** the OIDC issuer, Workload Identity,
  the network profile and the cluster identity never reach `startCluster`, so k3s runs with its
  defaults (flannel, its NetworkPolicy controller, ServiceLB).

### Where the cluster's routes come from

None of the routes come from floci-az's code: it only picks the Docker network, publishes port
6443 and starts k3s (the `route` matches in its AKS code are `ServiceRoutes`, its HTTP request
routing). They come from **Docker** (the node's outside network) and from **k3s's bundled flannel
and CNI plugins** (the Pod network). `ip -d route` shows `proto`, who added each route:

*The node's routes:*

```
default via 172.17.0.1 dev eth0 proto boot          ← Docker
172.17.0.0/16 dev eth0 proto kernel src 172.17.0.3  ← the kernel, after Docker set the address
10.42.0.0/24 dev cni0  proto kernel src 10.42.0.1   ← the kernel, after k3s's CNI set cni0's address
```

| Route | Who caused it | How |
|---|---|---|
| `default via 172.17.0.1 dev eth0` | **Docker** | the container joined Docker's `bridge` network (`withDockerNetwork`); Docker gave `eth0` its address and a default route to the bridge's gateway. `proto boot` marks a route added by a program, not by the kernel |
| `172.17.0.0/16 dev eth0` | **the kernel**, triggered by Docker | `proto kernel`: adding an address with a prefix (`172.17.0.3/16`) makes the kernel add the matching connected route |
| `10.42.0.0/24 dev cni0` | **the kernel**, triggered by **k3s** | k3s's embedded flannel leased this node `10.42.0.1/24` (`/run/flannel/subnet.env`, from the default cluster CIDR `10.42.0.0/16`); the CNI bridge plugin gave `cni0` that address on the first Pod |

`flannel.1` (VXLAN id 1, UDP 8472, over `eth0`) has an address (`10.42.0.0/32`) but no routes:
flannel adds one per **other** node, and this cluster has one node. With a second node it would add
`10.42.1.0/24 via 10.42.1.0 dev flannel.1 onlink`, plus ARP and FDB entries mapping `10.42.1.0` to
that node's real IP (from flannel's docs; the FDB list here was empty).

*The Pod's routes,* from inside the Pod (10.42.0.14):

```
default via 10.42.0.1 dev eth0                          ← CNI bridge plugin ("isDefaultGateway": true)
10.42.0.0/24 dev eth0 proto kernel src 10.42.0.14       ← the kernel (the Pod's /24 address)
10.42.0.0/16 via 10.42.0.1 dev eth0                     ← flannel's CNI plugin: the whole cluster's Pod range
```

These make `10.42.0.1` (`cni0`) the Pod's gateway, the address kube-proxy's MASQUERADE used in the
NodePort lab ([kubernetes-services.md](kubernetes-services.md#a-nodeport-packet-rule-by-rule-and-hook-by-hook-lab-k3s), part 10).

| Source | What it created |
|---|---|
| **floci-az's code** | nothing in the routing tables |
| **Docker** | `eth0`, `172.17.0.3/16`, the default route via `172.17.0.1` |
| **k3s (Rancher)**: embedded flannel, bundled CNI plugins | `cni0` and `10.42.0.0/24`, `flannel.1`, every Pod's `eth0` and routes, and routes to other nodes when there are some |
| **the kernel** | every `proto kernel` route, automatically, when an address is added |
| **kube-proxy** | no routes, only iptables rules (in IPVS mode it would add Service IPs to `kube-ipvs0`) |

The published port (`0.0.0.0:6443->6443` on the Mac) is handled by Docker Desktop on its VM, not
inside the node, so it adds nothing to the node's tables.

### k3s vs upstream Kubernetes

k3s is Kubernetes. It is a CNCF-certified Kubernetes distribution made by Rancher (now part of
SUSE). It runs the same API server, scheduler, controllers, kubelet and kube-proxy, and passes the
same conformance tests. Your manifests, `kubectl` and Helm charts work the same way. What differs is
how it is packaged and which parts it bundles or leaves out.

"k8s" usually means upstream Kubernetes, put together by you (often with `kubeadm`) or by a cloud
provider (AKS, EKS, GKE). Rancher the company is not the same as Rancher the product, a web UI for
managing many clusters. k3s doesn't need that product; `rancher/k3s` only says who publishes the
image.

| | Upstream (e.g. kubeadm) | k3s |
|---|---|---|
| **Packaging** | Separate binaries and static Pods: kube-apiserver, kube-controller-manager, kube-scheduler, etcd, plus the kubelet installed on the host | One binary (`/bin/k3s`, about 190 MB in v1.34.1+k3s1 because it carries containerd, runc and the CNI plugins); `k3s server` runs the whole control plane, kubelet and kube-proxy in one process; `k3s agent` runs a worker |
| **Datastore** | etcd | SQLite through **kine** by default; embedded etcd for HA; or an external MySQL, Postgres or etcd |
| **Container runtime** | You install one (containerd, CRI-O) | containerd built in (started by k3s as a child process) |
| **CNI** | You pick and install one (Calico, Cilium…) | flannel built in (the `cni0`, `10.42.0.0/16` and `flannel.1` above) |
| **LoadBalancer Services** | Stay `<pending>` unless a cloud controller or MetalLB handles them | **ServiceLB** (klipper-lb) built in: the `svclb-*` Pods from the LoadBalancer lab |
| **Ingress** | You install one | Traefik built in (floci-az turns it off with `--disable=traefik`) |
| **Add-ons included** | kubeadm installs CoreDNS and kube-proxy; everything else is yours to add | CoreDNS, metrics-server, local-path storage provisioner, a network-policy controller (kube-router's), a Helm controller (`HelmChart` CRD), and auto-deploy of every manifest in `/var/lib/rancher/k3s/server/manifests` |
| **Removed** | Nothing | In-tree cloud providers and storage drivers, plus alpha and legacy features (use external CSI drivers or cloud controllers instead) |
| **Install** | Several steps: prerequisites, `kubeadm init` and `join`, then a CNI | `curl -sfL https://get.k3s.io \| sh -`; a node joins with a token |
| **Footprint** | Control plane needs about 2 GB+ of RAM | Runs in about 512 MB; ARM builds available (a Raspberry Pi works) |
| **Certificates** | You manage them with `kubeadm certs` | Created and renewed automatically |

**Seen on the floci-az node (lab, v1.34.1+k3s1):**

```text
$ ps -o pid,args                     # inside the k3s container
    1 /bin/k3s init
   54 {exe} k3s server               ← API server, scheduler, controllers, kubelet, kube-proxy, flannel: one process (RSS ~512 MB)
   75 containerd                     ← started by k3s
  739 local-path-provisioner ...     ← bundled add-ons run as ordinary Pods
  846 /coredns -conf /etc/coredns/Corefile
  948 /metrics-server ...

$ ls /var/lib/rancher/k3s/server/db/
etcd  state.db  state.db-shm  state.db-wal     ← state.db is the SQLite file kine serves
                                                  (etcd/ holds only a name file; no etcd runs)
$ ls /var/lib/rancher/k3s/server/manifests/
ccm.yaml  coredns.yaml  local-storage.yaml  metrics-server  rolebindings.yaml  runtimes.yaml
                                               ← no traefik.yaml: disabled by floci-az's flag

$ kubectl get pods -n kube-system
coredns-...  local-path-provisioner-...  metrics-server-...  svclb-web-...
                                               ← no kube-apiserver, kube-proxy or etcd Pods

$ kubectl get crd | grep helm
helmchartconfigs.helm.cattle.io
helmcharts.helm.cattle.io
```

The kube-proxy rules we traced in [kubernetes-services.md](kubernetes-services.md) (`KUBE-SERVICES`,
`KUBE-NODEPORTS`, `KUBE-EXT`, `KUBE-MARK-MASQ`) are the upstream ones: k3s runs the upstream
kube-proxy code inside its process.

**Why pick k3s:**

- **Local labs and development:** one container gives you a real cluster in seconds. That's why
  floci-az uses it for "AKS", and why k3d (k3s in Docker) is popular.
- **CI:** start a real cluster for each test job and throw it away afterwards.
- **Edge and IoT:** small or ARM machines, often single-node, sometimes with poor connectivity.
- **Small self-hosted clusters:** LB, ingress, storage and DNS come in the box, so there's less to
  install and keep up to date.
- **HA without a separate etcd cluster:** three servers with embedded etcd, or an external database
  you already run.

**Why not:**

- **Managed cloud:** on AKS, EKS or GKE you aren't choosing a distribution; the provider runs
  upstream Kubernetes for you.
- **Bundled defaults may not fit:** flannel lacks Cilium's or Calico's features, Traefik may not be
  the ingress you want, and ServiceLB only publishes node ports on the node IPs. Each can be turned
  off (`--disable=traefik,servicelb`, `--flannel-backend=none`), but then you're gradually rebuilding
  upstream Kubernetes yourself.
- **SQLite is single-server only:** for HA you need embedded etcd or an external database.
- **Versions come together:** the bundled components upgrade with each k3s release, not separately.
- **Large production or enterprise use:** many choose **RKE2**, Rancher's other distribution. It
  packages things the same way but stays closer to upstream and is hardened (CIS profile, FIPS,
  etcd by default).

---

## 8. Container Apps

An **environment** is the shared boundary (in Azure: a VNet, a log workspace, a Kubernetes cluster
you don't see). **Apps** run in it, and every change to an app's template creates a new
**revision**.

```
PUT …/Microsoft.App/managedEnvironments/orders-env?api-version=2025-07-01   {"location":"westeurope","properties":{}}
{"name":"orders-env","state":"Succeeded","defaultDomain":"orders-env.eb0bd814589a32ab929623cbba67178f.azurecontainerapps.io","staticIp":"127.0.0.1"}

PUT …/Microsoft.App/containerApps/orders-web?api-version=2025-07-01
  {"properties":{"environmentId":"…/managedEnvironments/orders-env",
   "configuration":{"activeRevisionsMode":"Single","secrets":[{"name":"api-key","value":"local-secret"}],
                    "ingress":{"external":true,"targetPort":80}},
   "template":{"revisionSuffix":"v1","containers":[{"name":"web","image":"traefik/whoami:v1.10",
               "env":[{"name":"API_KEY","secretRef":"api-key"}]}],"scale":{"minReplicas":1,"maxReplicas":3}}}}
{"name":"orders-web","state":"Succeeded","fqdn":"orders-web.orders-env.eb0bd814….azurecontainerapps.io","latestRevision":"orders-web--v1"}
```

**A new revision.** The same PUT with `revisionSuffix: v2`. In `Single` mode the new revision takes
all traffic and the old one stops:

```
GET …/orders-web/revisions
[{"name":"orders-web--v1","active":false,"traffic":0,"state":"Stopped"},
 {"name":"orders-web--v2","active":true,"traffic":100,"state":"Running"}]
```

(`Multiple` mode keeps several revisions active, with `traffic` weights for blue/green or canary.)

**Secrets** are never returned by GET, only by `listSecrets`:

```
GET  …/orders-web               .properties.configuration.secrets → [{"name":"api-key"}]
POST …/orders-web/listSecrets   {"value":[{"name":"api-key","value":"local-secret"}]}
```

**In the default mocked mode there are no containers and no ingress.** `Running` above is only ARM
state:

```
$ curl -i -H "Host: orders-web.orders-env.eb0bd814….azurecontainerapps.io" $E/
HTTP/1.1 503 Service Unavailable
{"error":{"code":"ContainerAppMocked","message":"Container App is configured in mocked mode; ingress data plane is unavailable."}}
```

With `FLOCI_AZ_SERVICES_CONTAINER_APPS_MOCKED=false`, each replica runs as a container and floci-az
routes requests by `Host` header (not tested here: it needs the emulator restarted).

---

## 9. ARM templates

A template with parameters, variables, a `copy` loop, `dependsOn`, `uniqueString()` and
`reference()` outputs:

```json
{
  "parameters": {
    "prefix":   { "type": "string", "defaultValue": "pay" },
    "location": { "type": "string", "defaultValue": "[resourceGroup().location]" },
    "subnets":  { "type": "array",  "defaultValue": [ "app", "data" ] }
  },
  "variables": {
    "vnetName": "[concat(parameters('prefix'), '-vnet')]",
    "idName":   "[concat(parameters('prefix'), '-id')]"
  },
  "resources": [
    { "type": "Microsoft.Network/virtualNetworks", "apiVersion": "2024-05-01", "name": "[variables('vnetName')]",
      "properties": { "addressSpace": { "addressPrefixes": [ "10.20.0.0/16" ] },
        "copy": [ { "name": "subnets", "count": "[length(parameters('subnets'))]",
          "input": { "name": "[parameters('subnets')[copyIndex('subnets')]]",
                     "properties": { "addressPrefix": "[concat('10.20.', copyIndex('subnets', 1), '.0/24')]" } } } ] } },
    { "type": "Microsoft.ManagedIdentity/userAssignedIdentities", "apiVersion": "2023-01-31", "name": "[variables('idName')]" },
    { "type": "Microsoft.KeyVault/vaults", "apiVersion": "2023-07-01",
      "name": "[concat(parameters('prefix'), '-kv-', uniqueString(resourceGroup().id))]",
      "dependsOn": [ "[resourceId('Microsoft.ManagedIdentity/userAssignedIdentities', variables('idName'))]" ],
      "properties": { "tenantId": "[subscription().tenantId]", "sku": { "family": "A", "name": "standard" } } }
  ],
  "outputs": {
    "vnetId":      { "type": "string", "value": "[resourceId('Microsoft.Network/virtualNetworks', variables('vnetName'))]" },
    "principalId": { "type": "string", "value": "[reference(resourceId('Microsoft.ManagedIdentity/userAssignedIdentities', variables('idName'))).principalId]" },
    "subnetCount": { "type": "int",    "value": "[length(reference(variables('vnetName')).subnets)]" }
  }
}
```

(Each resource also has `"location": "[parameters('location')]"`, and the file has the usual
`$schema` and `contentVersion`; left out here to keep it short.)

```
D=$E/subscriptions/$SUB/resourcegroups/lab/providers/Microsoft.Resources/deployments
BODY={"properties":{"mode":"Incremental","template":<template>,"parameters":{"prefix":{"value":"pay"}}}}

POST $D/pay-1/validate?api-version=2024-03-01    {"state":"Succeeded","error":null}

POST $D/pay-1/whatIf                             ← what would change, before deploying
[{"changeType":"Create","id":"virtualNetworks/pay-vnet"},
 {"changeType":"Create","id":"userAssignedIdentities/pay-id"},
 {"changeType":"Create","id":"vaults/pay-kv-wq6hmixbuy3ux"}]

PUT $D/pay-1
HTTP/1.1 201 Created
Azure-AsyncOperation: …/operationStatuses/08446065772737832278?api-version=2024-03-01   ← poll this

GET $D/pay-1
{
  "state": "Succeeded",
  "outputs": {
    "vnetId": "/subscriptions/…/resourceGroups/lab/providers/Microsoft.Network/virtualNetworks/pay-vnet",
    "principalId": "2f4309f0-6cfc-48bc-8bcc-7c0f763664ed",     ← reference() read the created identity
    "subnetCount": 2
  },
  "outputResources": [ "pay-vnet", "pay-id", "pay-kv-wq6hmixbuy3ux" ]
}

GET $D/pay-1/operations                           ← vault after identity (dependsOn)
  22:17:21.383  Create  Microsoft.Network/virtualNetworks/pay-vnet                  Succeeded
  22:17:21.384  Create  Microsoft.ManagedIdentity/userAssignedIdentities/pay-id     Succeeded
  22:17:21.386  Create  Microsoft.KeyVault/vaults/pay-kv-wq6hmixbuy3ux              Succeeded

GET …/virtualNetworks/pay-vnet                    ← the copy loop's subnets
[{"name":"app","prefix":"10.20.1.0/24"},{"name":"data","prefix":"10.20.2.0/24"}]
```

**What-If after deploying** should say `NoChange` for everything. It doesn't:

```
[{"changeType":"Modify","id":"pay-vnet"},{"changeType":"NoChange","id":"pay-id"},{"changeType":"Modify","id":"pay-kv-wq6hmixbuy3ux"}]
```

The stored vault and VNet differ from the template, because some properties weren't kept (§6).

**A template error** fails validation before anything is created:

```
POST $D/bad/validate     (variables.vnetName uses parameters('nope'))
{"code":"InvalidTemplate","message":"Deployment template validation failed: 'The template parameter 'nope' is not found.'"}
```

- **`mode: Incremental`** (the default) adds and updates resources and leaves others in the group.
  **`Complete`** also deletes resources in the group that the template doesn't list.
- **Bicep compiles to this JSON** (`az bicep build`), so a Bicep file deploys the same way once built.

---

## 10. Azure RBAC (not available in 0.16.0)

`Microsoft.Authorization` (role definitions and role assignments) isn't in 0.16.0's service list:

```
GET …/providers/Microsoft.Authorization/roleDefinitions?api-version=2022-04-01
{"error":{"code":"ResourceNotFound","message":"Resource not found: subscriptions/…/providers/Microsoft.Authorization/roleDefinitions"}}
```

It's in floci-az's `main` branch (from its docs, not tested):

- **Built-in role definitions**, generated from Azure's own list (Owner, Contributor, Reader,
  AcrPull, Key Vault Secrets User, …), looked up with `$filter=roleName eq '<name>'`.
- **Role assignments** at any scope (tenant root, management group, subscription, resource group,
  resource), with Azure's refusals: `409 RoleAssignmentExists` for the same principal, role and
  scope under a second name, and `400 RoleDefinitionDoesNotExist` for an unknown role.
- **"Stored, not enforced":** an assignment grants nothing and its absence denies nothing.

How an assignment would look (Azure's API):

```
PUT /subscriptions/{sub}/resourceGroups/lab/providers/Microsoft.Authorization/roleAssignments/{new GUID}?api-version=2022-04-01
{"properties":{
   "roleDefinitionId":"/subscriptions/{sub}/providers/Microsoft.Authorization/roleDefinitions/4633458b-17de-408a-b874-0445c86b69e6",
   "principalId":"dea7eff2-d136-4448-b359-6da5a2cd95d3"}}         ← the identity's principalId (§5), not its clientId
```

(`4633458b-…` is Key Vault Secrets User.) An assignment is **who** (`principalId`) + **what**
(`roleDefinitionId`) + **where** (the URL's scope). It applies to that scope and everything below
it.

Nothing is enforced in 0.16.0 either. An ARM write with **no token at all** succeeded:

```
PUT …/resourceGroups/no-token-rg   (no Authorization header)   → 201
```

`parity: strict` (or `entra.validate-tokens: true`) makes ARM require a valid token, which checks
**who you are**. Roles, which decide **what you may do**, are not checked.

---

## 11. User and application identities

Checks of [azure.md §4, User and application identities](azure.md#user-and-application-identities).

**Token shapes: who is in the token.** These match Azure:

```
app-only  (client credentials): {"oid":"33333333-…","idtyp":"app","upn":null,"preferred_username":null,"scp":null,"roles":null}
delegated (password grant)    : {"oid":"9cec5cf5-…","idtyp":null,"preferred_username":"dev-user@floci-az.local","scp":"User.Read","roles":null}
```

The app-only token names the **service principal** (`oid` = its object id, `idtyp: app`, no user
claims). The delegated token names the **user** and carries the delegated scope in `scp`.

**Application permissions.** An app registration can ask for one, and floci-az stores the request:

```
POST $E/v1.0/applications
  {"displayName":"hr-sync","requiredResourceAccess":[{"resourceAppId":"00000003-0000-0000-c000-000000000000",
    "resourceAccess":[{"id":"df021288-bdef-4463-88db-98f22de89214","type":"Role"}]}]}   ← Graph User.Read.All, application type
{"appId":"9c7bd947-…","requiredResourceAccess":[…stored as sent…]}
```

In Azure, admin consent then creates an **app role assignment** on the app's service principal, and
its app-only Graph token gets `"roles":["User.Read.All"]`. floci-az has neither:

```
POST $E/v1.0/servicePrincipals/{id}/appRoleAssignments
{"status":"BadRequest","message":"Unsupported Graph endpoint: v1.0/servicePrincipals/2edb0db1-…/appRoleAssignments"}
hr-sync's app-only Graph token: {"aud":"https://graph.microsoft.com","idtyp":"app","roles":null,"scp":null}
```

**Permissions aren't checked.** A delegated `User.Read` token, and no token at all, can both list
every app registration:

```
user token (scp User.Read), GET /v1.0/applications: 200      ← Azure: 403, needs Application.Read.All
no token,                   GET /v1.0/applications: 200      ← Azure: 401
```

**Not available in 0.16.0:**

```
GET /v1.0/directoryRoles                               404    (Entra directory roles)
GET /v1.0/roleManagement/directory/roleDefinitions     404
GET …/providers/Microsoft.Authorization/roleAssignments  ResourceNotFound   (Azure RBAC, §10)
GET /v1.0/users/dev-user@floci-az.local                BadRequest   (users can't be read by UPN)
```

So floci-az shows **who** a token is for, correctly for both kinds of identity. It can't show
**what** either may do: none of the three permission systems (Azure RBAC, Entra API permissions,
directory roles) is modelled.

## 12. Signing in as a user or as an app

Checks of [azure.md §4, Users and apps both authenticate](azure.md#users-and-apps-both-authenticate).

**The credential is on the application object; the identity is the service principal.**

```
POST $E/v1.0/applications {"displayName":"billing-worker"}, then addPassword
application object id : a39fc866-ae1a-4c21-b6a5-a332e1bb0971   (secret added here)
service principal id  : 9088f456-4c80-3cc8-9707-b966aa71b1ab
appId (client_id)     : fb08020e-2d83-4be9-9a19-a2817dec3450

GET application        {"passwordCredentials":["ci"]}            ← the secret is here
GET servicePrincipal   {"passwordCredentials":null}              ← not here

client_credentials with that client_id + secret:
token: {"oid":"9088f456-…","azp":"fb08020e-…"}     oid == service principal id: yes
```

`azp` (the `client_id`) says which app definition was used; `oid` says which identity signed in.

**A user signing in through the app.** Both kinds of client get the **user's** token, and `azp`
records the app:

```
public client (PKCE, no secret)       : {"oid":"9cec5cf5-…","preferred_username":"dev-user@floci-az.local","scp":"openid User.Read","azp":"fb08020e-…"}
confidential client (PKCE + secret)   : {"oid":"9cec5cf5-…","preferred_username":"dev-user@floci-az.local","scp":"openid User.Read","azp":"fb08020e-…"}
redeemed with a different redirect_uri: {"error":"invalid_grant","error_codes":[70000]}
```

A code is bound to the `redirect_uri` it was issued for, so a code stolen in transit can't be redeemed
by a different redirect URI. PKCE adds the same protection for public clients: without the verifier,
the code is useless.

**A managed identity has no application object**, so there's nowhere to put a secret:

```
GET $E/v1.0/applications?$filter=appId eq '<the identity's clientId>'   {"applications_with_mi_clientId":0}
```

**Not enforced by floci-az:**

```
client_id with no app registration and no service principal → {"got_token":true}
    ← Azure: AADSTS7000229 (missing service principal); with validate-client-credentials: AADSTS700016

the same app's token from another tenant:
    home tenant : {"tid":"00000000-…-000000000002","oid":"9088f456-…"}
    other tenant: {"tid":"aaaaaaaa-…-000000000001","oid":"9088f456-…"}
    ← Azure: a different oid, because each tenant has its own service principal (or AADSTS7000229 if none)
```

Any tenant id is served from the default directory, so a multi-tenant app looks single-tenant here.

## 13. Load balancer

An Azure Load Balancer (`Microsoft.Network/loadBalancers`) is an L4 (TCP/UDP)
load balancer. It's one resource with several kinds of child objects, which
refer to each other by resource id:

```
load balancer (sku Standard)
├── frontendIPConfigurations   the address clients connect to: a public IP, or a subnet's private IP
├── backendAddressPools        where traffic goes: NIC ipConfigurations (or IPs) join a pool
├── probes                     health checks on each backend (TCP, HTTP, HTTPS)
├── loadBalancingRules         frontend:port → pool:port, only to backends the probe marks healthy
├── inboundNatRules            frontend:port → ONE backend:port (e.g. SSH to a single VM)
└── outboundRules              SNAT for the pool's outbound traffic through the frontend's IP
```

In floci-az all of these are **ARM state only**: they're created, stored and read
back, but nothing listens on the frontend and no traffic is balanced.

**1. A public Standard load balancer**, with a public IP frontend, a backend pool,
an HTTP health probe, a load-balancing rule (80 → 8080) and an inbound NAT rule
(50001 → 22). First the public IP, then the load balancer:

```
PUT …/Microsoft.Network/publicIPAddresses/web-lb-pip?api-version=2024-05-01
{"location":"westeurope","sku":{"name":"Standard"},"properties":{"publicIPAllocationMethod":"Static"}}
→ {"name":"web-lb-pip","ip":"20.0.0.4"}
```

```
PUT …/Microsoft.Network/loadBalancers/web-lb?api-version=2024-05-01
{"location":"westeurope","sku":{"name":"Standard","tier":"Regional"},
 "properties":{
  "frontendIPConfigurations":[{"name":"fe-public","properties":{"publicIPAddress":{"id":"…/publicIPAddresses/web-lb-pip"}}}],
  "backendAddressPools":[{"name":"web-pool"}],
  "probes":[{"name":"http-probe","properties":{"protocol":"Http","port":80,"requestPath":"/healthz","intervalInSeconds":5,"numberOfProbes":2}}],
  "loadBalancingRules":[{"name":"http","properties":{
     "frontendIPConfiguration":{"id":"…/loadBalancers/web-lb/frontendIPConfigurations/fe-public"},
     "backendAddressPool":{"id":"…/loadBalancers/web-lb/backendAddressPools/web-pool"},
     "probe":{"id":"…/loadBalancers/web-lb/probes/http-probe"},
     "protocol":"Tcp","frontendPort":80,"backendPort":8080,"enableFloatingIP":false,
     "idleTimeoutInMinutes":4,"loadDistribution":"Default","disableOutboundSnat":true}}],
  "inboundNatRules":[{"name":"ssh-vm1","properties":{
     "frontendIPConfiguration":{"id":"…/loadBalancers/web-lb/frontendIPConfigurations/fe-public"},
     "protocol":"Tcp","frontendPort":50001,"backendPort":22}}]
 }}
```

The child objects reference each other **inside the same request**, by the ids
they will have (`…/loadBalancers/web-lb/probes/http-probe`). That's why ARM
templates build those ids with `resourceId()` or `concat(lbId, '/probes/…')`.
In summary:

```
web-lb   sku Standard/Regional   Succeeded
  frontends:  fe-public → public IP web-lb-pip
  pools:      web-pool
  probes:     http-probe   Http :80 /healthz
  rules:      http          80 → 8080, probe http-probe
  NAT:        ssh-vm1       50001 → 22
```

The full response (`GET …/loadBalancers/web-lb`, ids shortened to `…/`). Every
child gets an `id` and its own `provisioningState`, and the empty collections are
returned as `[]`:

```json
{
  "id": "…/loadBalancers/web-lb", "name": "web-lb", "type": "Microsoft.Network/loadBalancers",
  "location": "westeurope", "sku": { "name": "Standard", "tier": "Regional" },
  "properties": {
    "frontendIPConfigurations": [ { "name": "fe-public", "id": "…/loadBalancers/web-lb/frontendIPConfigurations/fe-public",
        "properties": { "publicIPAddress": { "id": "…/publicIPAddresses/web-lb-pip" },
                        "privateIPAllocationMethod": "Dynamic", "privateIPAddress": "10.0.0.6",
                        "provisioningState": "Succeeded" } } ],
    "backendAddressPools": [ { "name": "web-pool", "id": "…/loadBalancers/web-lb/backendAddressPools/web-pool",
                               "properties": { "provisioningState": "Succeeded" } } ],
    "probes": [ { "name": "http-probe", "id": "…/loadBalancers/web-lb/probes/http-probe",
        "properties": { "protocol": "Http", "port": 80, "requestPath": "/healthz",
                        "intervalInSeconds": 5, "numberOfProbes": 2, "provisioningState": "Succeeded" } } ],
    "loadBalancingRules": [ { "name": "http", "id": "…/loadBalancers/web-lb/loadBalancingRules/http",
        "properties": { "frontendIPConfiguration": { "id": "…/frontendIPConfigurations/fe-public" },
                        "backendAddressPool": { "id": "…/backendAddressPools/web-pool" },
                        "probe": { "id": "…/probes/http-probe" },
                        "protocol": "Tcp", "frontendPort": 80, "backendPort": 8080, "enableFloatingIP": false,
                        "idleTimeoutInMinutes": 4, "loadDistribution": "Default", "disableOutboundSnat": true,
                        "provisioningState": "Succeeded" } } ],
    "inboundNatRules": [ { "name": "ssh-vm1", "id": "…/loadBalancers/web-lb/inboundNatRules/ssh-vm1",
        "properties": { "frontendIPConfiguration": { "id": "…/frontendIPConfigurations/fe-public" },
                        "protocol": "Tcp", "frontendPort": 50001, "backendPort": 22, "provisioningState": "Succeeded" } } ],
    "inboundNatPools": [], "outboundRules": [], "provisioningState": "Succeeded"
  }
}
```

The rule's settings, as Azure defines them:

| Setting | Meaning |
|---|---|
| `frontendPort` → `backendPort` | the port clients use, and the port on the backends (80 → 8080 here) |
| `probe` | only backends that pass this probe receive traffic |
| `loadDistribution: Default` | spread by a 5-tuple hash (src IP, src port, dst IP, dst port, protocol); `SourceIP` or `SourceIPProtocol` keeps a client on one backend |
| `enableFloatingIP` | `false`: the backend sees its own IP as destination. `true` (Direct Server Return): it sees the frontend IP; used by SQL Always On and some NVAs |
| `idleTimeoutInMinutes` | how long an idle TCP flow is kept |
| `disableOutboundSnat: true` | don't use this rule's frontend for the backends' outbound traffic; recommended when an outbound rule or NAT gateway handles egress |

**2. A backend pool created separately**, through its child endpoint, the way
Terraform's `azurerm_lb_backend_address_pool` does it (it manages the pool as its
own resource, not inside the load balancer's body):

```
PUT …/loadBalancers/web-lb/backendAddressPools/api-pool?api-version=2024-05-01
{"properties":{}}
→ HTTP 200
{"name":"api-pool","id":"…/loadBalancers/web-lb/backendAddressPools/api-pool","properties":{"provisioningState":"Succeeded"}}

GET …/loadBalancers/web-lb/backendAddressPools                                  → ["web-pool","api-pool"]
```

**Putting a VM in a pool is done on the NIC**, not on the load balancer: the NIC's
ipConfiguration lists the pools it belongs to (`azurerm_network_interface_backend_address_pool_association`
in Terraform):

```
PUT …/networkInterfaces/vm1-nic   (ipConfigurations[0].properties.loadBalancerBackendAddressPools = [web-pool])
GET …/networkInterfaces/vm1-nic → ipConfigurations[0]:
{
  "name": "ipconfig1",
  "properties": {
    "privateIPAddress": "10.0.0.4",
    "subnet": { "id": "…/virtualNetworks/hub/subnets/web" },
    "loadBalancerBackendAddressPools": [ { "id": "…/loadBalancers/web-lb/backendAddressPools/web-pool" } ]
  }
}
```

In Azure the pool then lists that NIC back in `backendIPConfigurations`. floci-az
doesn't fill that in:

```
GET …/loadBalancers/web-lb/backendAddressPools/web-pool
{"name":"web-pool","id":"…/backendAddressPools/web-pool","properties":{"provisioningState":"Succeeded"}}   ← no backendIPConfigurations
```

**3. An internal load balancer** with a static private frontend in the `data`
subnet. An internal frontend has a `subnet` instead of a `publicIPAddress`:

```
PUT …/loadBalancers/db-ilb?api-version=2024-05-01
{"location":"westeurope","sku":{"name":"Standard"},"properties":{
  "frontendIPConfigurations":[{"name":"fe-private","properties":{
     "subnet":{"id":"…/virtualNetworks/hub/subnets/data"},
     "privateIPAllocationMethod":"Static","privateIPAddress":"10.10.2.10"}}],
  "backendAddressPools":[{"name":"db-pool"}]}}
```

```
db-ilb   fe-private   10.10.2.10 (Static)   subnet data
```

```json
{
  "id": "…/loadBalancers/db-ilb", "name": "db-ilb", "type": "Microsoft.Network/loadBalancers",
  "location": "westeurope", "sku": { "name": "Standard" },
  "properties": {
    "frontendIPConfigurations": [ { "name": "fe-private", "id": "…/loadBalancers/db-ilb/frontendIPConfigurations/fe-private",
        "properties": { "subnet": { "id": "…/virtualNetworks/hub/subnets/data" },
                        "privateIPAllocationMethod": "Static", "privateIPAddress": "10.10.2.10",
                        "provisioningState": "Succeeded" } } ],
    "backendAddressPools": [ { "name": "db-pool", "id": "…/loadBalancers/db-ilb/backendAddressPools/db-pool",
                               "properties": { "provisioningState": "Succeeded" } } ],
    "loadBalancingRules": [], "probes": [], "inboundNatRules": [], "inboundNatPools": [],
    "outboundRules": [], "provisioningState": "Succeeded"
  }
}
```

This is the shape of the
[AKS egress example in azure.md](azure.md#example-aks-egress-through-palo-alto-firewalls-behind-an-internal-load-balancer):
a Standard internal load balancer in front of firewalls.

**4. An outbound rule.** Outbound traffic from the pool leaves through the
frontend's public IP, with a fixed number of SNAT ports per backend instance.
Added by re-PUTting the load balancer with `outboundRules`:

```
"outboundRules":[{"name":"egress","properties":{
   "frontendIPConfigurations":[{"id":"…/loadBalancers/web-lb/frontendIPConfigurations/fe-public"}],
   "backendAddressPool":{"id":"…/loadBalancers/web-lb/backendAddressPools/web-pool"},
   "protocol":"All","allocatedOutboundPorts":1024,"idleTimeoutInMinutes":4}}]
→ {"outboundRules":[{"name":"egress","ports":1024,"protocol":"All"}]}
```

That's why the `http` rule has `disableOutboundSnat: true`: outbound SNAT is the
outbound rule's job, not the load-balancing rule's.

**5. From an ARM template.** A public IP and a load balancer with a TCP probe and an
HTTPS rule; the load balancer `dependsOn` the public IP, and its children refer to
each other through a `lbId` variable:

```json
"variables": {
  "pipName": "[concat(parameters('name'), '-pip')]",
  "lbId": "[resourceId('Microsoft.Network/loadBalancers', parameters('name'))]"
},
…
"loadBalancingRules": [ { "name": "https", "properties": {
  "frontendIPConfiguration": { "id": "[concat(variables('lbId'), '/frontendIPConfigurations/fe')]" },
  "backendAddressPool":      { "id": "[concat(variables('lbId'), '/backendAddressPools/pool')]" },
  "probe":                   { "id": "[concat(variables('lbId'), '/probes/tcp443')]" },
  "protocol": "Tcp", "frontendPort": 443, "backendPort": 443, "disableOutboundSnat": true } } ],
…
"outputs": {
  "publicIp":  { "type": "string", "value": "[reference(variables('pipName')).ipAddress]" },
  "ruleCount": { "type": "int",    "value": "[length(reference(parameters('name')).loadBalancingRules)]" }
}
```

```
POST …/deployments/lb-1/whatIf   [{"changeType":"Create","id":"publicIPAddresses/api-lb-pip"},{"changeType":"Create","id":"loadBalancers/api-lb"}]
PUT  …/deployments/lb-1          201
GET  …/deployments/lb-1          {"state":"Succeeded","outputs":{"publicIp":"20.0.0.4","ruleCount":1}}
```

**6. Listing.** All load balancers in the group came back:

```
GET …/Microsoft.Network/loadBalancers   [{"name":"db-ilb","sku":"Standard"},{"name":"bad-lb","sku":"Standard"},{"name":"web-lb","sku":"Standard"},…]
```

**What floci-az doesn't check or model:**

```
# a rule pointing at a probe that doesn't exist
PUT …/loadBalancers/bad-lb   (loadBalancingRules[0].probe.id = …/probes/nope)
{"name":"bad-lb","state":"Succeeded","error":null}                         ← Azure: 400 InvalidResourceReference

# deleting a pool that a rule (and a NIC) still use
DELETE …/loadBalancers/web-lb/backendAddressPools/web-pool   → HTTP 200   ← Azure refuses while it's referenced
GET …/loadBalancers/web-lb  {"pools":["api-pool"],"rule_pool":"…/backendAddressPools/web-pool"}   ← the rule now points at nothing
```

- **A public frontend gets a made-up private IP** (`"privateIPAddress": "10.0.0.6"`).
  In Azure a public frontend has only its public IP.
- **Every public IP got `20.0.0.4`**: `vm1-pip` (§3), `web-lb-pip` and the
  template's `api-lb-pip`. Azure gives each its own address.
- **No traffic and no health checks**: the frontend isn't reachable and probes
  never run.

Good for testing code that creates and wires load balancers (Terraform
`azurerm_lb*`, ARM/Bicep, SDK calls), including the resource ids that link the
parts. For traffic through a load balancer, use real Azure; for a Kubernetes
`type: LoadBalancer` Service locally, the AKS (k3s) cluster in §7 has k3s's own
ServiceLB, which gives the Service the node's IP.

## Where floci-az differs from Azure

| Area | Azure | floci-az 0.16.0 (tested) |
|---|---|---|
| App with no service principal | `AADSTS7000229` | gets a token |
| Multi-tenant app | one service principal (and `oid`) per tenant | any tenant id served from the default directory, same `oid` |
| Client secrets | checked | any secret accepted (`validate-client-credentials` to check) |
| User access tokens | `aud` = the resource, `scp` = short names | `aud` = client id, `scp` = full scope URLs |
| Workload Identity assertion | signature and federated credential checked | **anything accepted**, even `alg: none` |
| Graph | needs a token with the right permissions | answered with no token |
| ARM | token required, RBAC enforced | no token needed (default `parity: compat`), no RBAC |
| Key Vault data plane | token audience and RBAC/access policy checked | any token accepted (a missing one is `401`) |
| Key Vault properties | `enableRbacAuthorization`, soft delete kept | dropped |
| Subnets | must be inside the VNet, may not overlap | both accepted |
| Load balancer | balances traffic, runs probes, checks references | ARM state only; a rule may point at a missing probe, an in-use pool can be deleted, pools don't list their NICs, a public frontend gets a made-up private IP |
| Public IP addresses | unique per resource | every public IP got `20.0.0.4` |
| NIC private IP | from the subnet (`.4` first) | `10.0.0.4` whatever the subnet |
| Managed identity in Graph | a service principal (`ManagedIdentity`) with `id` = `principalId` | not linked; any `appId` returns placeholder id `…0010` |
| VM identity | `identity` stored, principal created | `identity` dropped |
| AKS | requested version, Azure CNI, node count, OIDC issuer | k3s (1.34), flannel, 1 node, OIDC and Workload Identity dropped |
| Container Apps | running replicas | mocked by default: ARM state only |
| App permissions | admin consent → app role assignment → `roles` claim | stored in `requiredResourceAccess` only; no `appRoleAssignments`, no `roles` claim |
| Directory roles | e.g. Application Administrator | `404` |
| RBAC | assignments enforced | not in 0.16.0; stored only in `main` |

floci-az is good for testing what **your code** does: getting and validating tokens, calling ARM,
Graph and Key Vault, deploying templates, running manifests on a cluster. It can't tell you whether
**Azure** would allow it: identity checks, roles and network rules are mostly not enforced.

## Sources

- floci-az: https://github.com/floci-io/floci-az (docs in `docs/services/`)
- Microsoft identity platform, access tokens: https://learn.microsoft.com/entra/identity-platform/access-tokens
- Azure Instance Metadata Service, managed identity tokens: https://learn.microsoft.com/entra/identity/managed-identities-azure-resources/how-to-use-vm-token
- Key Vault soft delete: https://learn.microsoft.com/azure/key-vault/general/soft-delete-overview
- ARM template deployment What-If: https://learn.microsoft.com/azure/azure-resource-manager/templates/deploy-what-if
- Azure built-in roles: https://learn.microsoft.com/azure/role-based-access-control/built-in-roles
- k3s architecture and packaged components: https://docs.k3s.io/architecture, https://docs.k3s.io/installation/packaged-components
- k3s datastore options (kine, embedded etcd, external DB): https://docs.k3s.io/datastore
