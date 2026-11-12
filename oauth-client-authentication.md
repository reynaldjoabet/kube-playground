# OAuth 2.0 client authentication

How an OAuth 2.0 client proves who it is to the authorization server's token endpoint, the methods for doing it, and why private-key methods are stronger than shared secrets.

---

## 0. Terms

| Term | Meaning |
|---|---|
| **client** | The application asking for tokens (a web backend, a service, a CLI). Not the user. |
| **authorization server (AS)** | Issues tokens, at its **token endpoint**. Keycloak, Entra ID, Okta, Connect2id, etc. |
| **resource server (RS)** | The API that accepts the access token. |
| **confidential client** | A client that can keep a credential secret (runs on a server). Only these authenticate. |
| **public client** | A client that can't keep a secret (SPA, mobile app, CLI). It has no client credential and uses PKCE instead. |
| **client assertion** | A short-lived JWT the client signs and sends instead of a password (RFC 7523). |
| **sender-constrained token** | An access token bound to a key, so a stolen copy is useless without that key. |

---

## 1. Why authenticate OAuth 2.0 clients?

Client authentication ensures the tokens get issued to a legitimate client and not some other, potentially malicious, party.

It happens at the **token endpoint**, on every grant where a confidential client gets tokens:

- `authorization_code`: when exchanging the code. Without client auth, anyone who steals a code can redeem it.
- `refresh_token`: when refreshing. A stolen refresh token is useless without the client credential.
- `client_credentials`: service-to-service. Here the client credential is the *only* credential.

---

## 2. The two types of client credential

The credential a client uses to authenticate falls into one of these two types:

- **Shared secret**: the authorization server issues the client with a secret (password) that is stored by the server as well as the client.
- **Private key**: the client generates a private RSA or EC key and stores it securely. The client then authenticates either by signing an assertion (a JWT) with the private key, or by using the private key for client X.509 certificate authentication during the TLS handshake of the HTTPS connection (mutual TLS).

### The registered methods

The `token_endpoint_auth_method` values from the OAuth/OIDC registries, grouped by type:

| Method | Type | How the client proves itself | Spec |
|---|---|---|---|
| `client_secret_basic` | shared secret | `Authorization: Basic base64(client_id:client_secret)` | RFC 6749 |
| `client_secret_post` | shared secret | `client_id` and `client_secret` in the form body | RFC 6749 |
| `client_secret_jwt` | shared secret | a JWT signed with HMAC, using the secret as the key | RFC 7523, OIDC Core |
| `private_key_jwt` | private key | a JWT signed with the client's private key; the AS holds only the public key (or a JWKS URL) | RFC 7523, OIDC Core |
| `tls_client_auth` | private key | mutual TLS with a CA-issued certificate; the AS matches the subject DN or SAN | RFC 8705 |
| `self_signed_tls_client_auth` | private key | mutual TLS with a self-signed certificate; the AS matches the registered public key | RFC 8705 |
| `none` | none | public client: no credential, PKCE protects the code | RFC 6749, RFC 7636 |

`client_secret_jwt` sends a JWT, but it's still a shared secret: both sides hold the HMAC key, so it has the same weakness on the server side.

### Shared-secret methods

The examples are Connect2id's, using the RFC 6749 example client: `client_id=s6BhdRkqt3`, `client_secret=7Fjfp0ZBr1KtDRbnfVdmIw`.

#### `client_secret_basic`

This is HTTP Basic authentication, tweaked for OAuth 2.0: the `client_id` and `client_secret` are first encoded with `application/x-www-form-urlencoded`, then joined with `:` and base64-encoded. The result goes in the `Authorization` header. It's the simplest method, and the least secure.

```http
POST /token HTTP/1.1
Host: c2id.com
Content-Type: application/x-www-form-urlencoded
Authorization: Basic czZCaGRSa3F0Mzo3RmpmcDBaQnIxS3REUmJuZlZkbUl3

grant_type=authorization_code
&code=n0esc3NRze7LTCu7iYzS6a5acc3f0ogp4
&redirect_uri=https%3A%2F%2Fclient.example.org%2Fcb
```

Base64 is an encoding, not encryption. The header decodes straight back to the secret:

```sh
echo czZCaGRSa3F0Mzo3RmpmcDBaQnIxS3REUmJuZlZkbUl3 | base64 -d
# s6BhdRkqt3:7Fjfp0ZBr1KtDRbnfVdmIw
```

The form-encoding step matters when the ID or secret contains characters like `:` or `+`: both sides must encode them the same way, or authentication fails. It's a common interop bug.

#### `client_secret_post`

The same credentials, sent as form parameters in the request body instead of a header:

```http
POST /token HTTP/1.1
Host: c2id.com
Content-Type: application/x-www-form-urlencoded

grant_type=authorization_code
&code=n0esc3NRze7LTCu7iYzS6a5acc3f0ogp4
&redirect_uri=https%3A%2F%2Fclient.example.org%2Fcb
&client_id=s6BhdRkqt3
&client_secret=7Fjfp0ZBr1KtDRbnfVdmIw
```

It's no weaker or stronger than `client_secret_basic`: the secret is sent in clear inside TLS either way. RFC 6749 says servers must support Basic, and that sending the secret in the body is "NOT RECOMMENDED", for clients that can't use Basic.

#### `client_secret_jwt`

This also uses the client secret, but as the key for an HMAC over a JWT assertion. It's stronger than `client_secret_basic` and `client_secret_post` because **the secret itself never reaches the server**. If the token request is accidentally sent over plain HTTP, only a short-lived JWT leaks, not the secret. The JWT expiration limits the time window for replay.

The JWT header must include `alg`, naming the HMAC algorithm. No other header parameters are required:

```json
{
  "alg" : "HS256"
}
```

The JWT claims:

- **`iss` and `sub`**: both set to the `client_id`.
- **`aud`**: the AS's issuer URL, or the endpoint URL where the client authenticates, such as the token endpoint. With strict audience checking configured, Connect2id accepts only the issuer URL.
- **`exp`**: the expiration time.
- **`jti`** (optional): a unique identifier, used to prevent replay.
- **`nbf`** (optional): the time before which the JWT must not be accepted.
- **`iat`** (optional): the time the JWT was issued.

```json
{
  "iss" : "oe7aiz60",
  "sub" : "oe7aiz60",
  "aud" : "https://demo.c2id.com",
  "exp" : 1453021544,
  "jti" : "Eefaevo0"
}
```

The client sends `client_assertion_type` set to `urn:ietf:params:oauth:client-assertion-type:jwt-bearer`, and `client_assertion` set to the serialised JWT:

```http
POST /token HTTP/1.1
Host: c2id.com
Content-Type: application/x-www-form-urlencoded

grant_type=authorization_code
&code=n0esc3NRze7LTCu7iYzS6a5acc3f0ogp4
&redirect_uri=https%3A%2F%2Fclient.example.org%2Fcb
&client_assertion_type=urn%3Aietf%3Aparams%3Aoauth%3Aclient-assertion-type%3Ajwt-bearer
&client_assertion=eyJhbGciOiJSUzI1NiIsImtpZCI6IjIyIn0.eyJpc3Mi...
```

**The example assertion has the wrong header for this method.** `eyJhbGciOiJSUzI1NiIsImtpZCI6IjIyIn0` decodes to `{"alg":"RS256","kid":"22"}`, an RSA signature, which is `private_key_jwt`. A real `client_secret_jwt` assertion would start with `eyJhbGciOiJIUzI1NiJ9` (`{"alg":"HS256"}`).

- **Replay:** Connect2id refuses a second use of an authentication JWT that has a `jti`, unless that feature is turned off.
- **Algorithms:** Connect2id supports `HS256`, `HS384` and `HS512`.
- **Spec:** OpenID Connect Core 1.0, §9, and RFC 7523, §2.2.

**Why it's still a shared secret.** HMAC is symmetric: the AS needs the same secret to check the MAC, so it must store it in usable (not hashed) form. A breach of the AS's client store exposes it, just as with the two methods above. That's the gap `private_key_jwt` closes (§3).

### Private-key methods

#### `private_key_jwt`

The client signs a JWT assertion with its own private RSA or EC key. The matching **public key** is registered with the AS in JSON Web Key (JWK) format, in one of two ways:

- **by value** (`jwks` in the client registration): the key set is stored in the registration;
- **by URL** (`jwks_uri`): the AS fetches the key set from the client. This allows key rollover without updating the client registration: the client publishes the new key, starts signing with it, then removes the old one.

A client can instead put a **qualified X.509 certificate** in the JWT header (the `x5c` parameter) to pass its public key. In that case no JWK set is registered. ("Qualified" here means a certificate the AS trusts through a CA, such as an eIDAS qualified certificate, not a self-made one.)

The JWT header must include `alg`, naming the signing algorithm. If the key was registered with a key ID (`kid`), include it too. When the set holds several keys (during rollover), the `kid` lets the AS pick the right one. Without it, the AS has to try each matching key.

```json
{
  "alg" : "RS256",
  "kid" : "1"
}
```

With a qualified certificate, the certificate goes in `x5c`, followed by any intermediate CA certificates:

```json
{
  "alg" : "RS256",
  "x5c" : [ "MIIE3jCCA8agAwIBAgICAwEwDQYJKoZIhvcNAQEFBQAwYzELMAkGA1UEBhMC..." ]
}
```

The claims are the same as for `client_secret_jwt`:

- **`iss` and `sub`**: both set to the `client_id`.
- **`aud`**: the AS's issuer URL, or the endpoint URL where the client authenticates, such as the token endpoint. With strict audience checking configured, Connect2id accepts only the issuer URL.
- **`exp`**: the expiration time.
- **`jti`** (optional): a unique identifier, used to prevent replay.
- **`nbf`** (optional): the time before which the JWT must not be accepted.
- **`iat`** (optional): the time the JWT was issued.

```json
{
  "iss" : "oe7aiz60",
  "sub" : "oe7aiz60",
  "aud" : "https://demo.c2id.com",
  "exp" : 1453021544,
  "jti" : "Eefaevo0"
}
```

The request is also the same shape: `client_assertion_type` set to `urn:ietf:params:oauth:client-assertion-type:jwt-bearer`, and `client_assertion` set to the serialised JWT:

```http
POST /token HTTP/1.1
Host: c2id.com
Content-Type: application/x-www-form-urlencoded

grant_type=authorization_code
&code=n0esc3NRze7LTCu7iYzS6a5acc3f0ogp4
&redirect_uri=https%3A%2F%2Fclient.example.org%2Fcb
&client_assertion_type=urn%3Aietf%3Aparams%3Aoauth%3Aclient-assertion-type%3Ajwt-bearer
&client_assertion=eyJhbGciOiJSUzI1NiIsImtpZCI6IjIyIn0.eyJpc3Mi...
```

Here the header does match the method: `eyJhbGciOiJSUzI1NiIsImtpZCI6IjIyIn0` decodes to `{"alg":"RS256","kid":"22"}`. (The `kid` differs from the `"1"` in the header example above; they're separate examples.)

So on the wire, `private_key_jwt` and `client_secret_jwt` look the same. The difference is the `alg` (`RS*`/`PS*`/`ES*` vs `HS*`), and what the AS checks the signature with: a public key vs the shared secret.

**Algorithms:**

| Key | JWS algorithms | Requirement |
|---|---|---|
| RSA | `RS256`, `RS384`, `RS512` (PKCS#1 v1.5), `PS256`, `PS384`, `PS512` (PSS) | key at least 2048 bits |
| EC | `ES256`, `ES384`, `ES512` | matching curve: P-256, P-384, P-521 respectively |

**Spec:** OpenID Connect Core 1.0, §9, and RFC 7523, §2.2.

#### `tls_client_auth`

The client authenticates with an **X.509 certificate presented during the TLS handshake** (mutual TLS), not with anything in the request body. The certificate must be issued by a trusted Certificate Authority (CA). It's typically validated by a dedicated TLS-terminating proxy in front of the AS, which then passes the verified certificate on to the AS.

When the client registers for `tls_client_auth`, it sets the **expected subject DN** of its certificate (`tls_client_auth_subject_dn`). RFC 8705 also allows matching on a subject alternative name instead: `tls_client_auth_san_dns`, `_san_uri`, `_san_ip` or `_san_email`.

```http
POST /token HTTP/1.1
Host: c2id.com
Content-Type: application/x-www-form-urlencoded

grant_type=authorization_code
&code=n0esc3NRze7LTCu7iYzS6a5acc3f0ogp4
&redirect_uri=https%3A%2F%2Fclient.example.org%2Fcb
&client_id=s6BhdRkqt3
```

The client must include its `client_id` in the request body to identify itself. It's the only client parameter: it tells the AS which registration to match the certificate against. The proof is the TLS handshake, where the client signed part of it with the certificate's private key.

```text
client ──TLS handshake + client certificate──► TLS proxy
                                               ├─ checks the chain against trusted CAs
                                               └─ forwards the request + verified certificate
                                                        │
                                                        ▼
                                                       AS: looks up client_id, compares the
                                                           certificate's subject DN (or SAN)
                                                           with the registered value
```

**Benefit: the access token is bound to the client certificate.** This fixes the bearer weakness of regular OAuth 2.0 access tokens. The AS puts the certificate's SHA-256 thumbprint in the token (`cnf.x5t#S256`). A resource server that checks it accepts the token only over a TLS connection made with the same certificate, so a stolen token is useless on its own (§4).

The private key never leaves the client, and the AS stores only the expected DN, so the private-key benefits in §3 apply. The cost is operational: every hop up to the AS (and up to each resource server that checks the binding) has to pass the client certificate along, and the client needs a CA-issued certificate.

**Spec:** this client authentication method and the token binding are both in the mTLS OAuth 2.0 extension, RFC 8705 (§2.1 for the method, §3 for the binding).

#### `self_signed_tls_client_auth`

Like `tls_client_auth`, the client authenticates with an X.509 certificate presented during the TLS handshake. But the certificate can be **self-signed** (self-issued), so no CA and no X.509 public key infrastructure (PKIX) is needed. The TLS-terminating proxy is configured to accept self-signed certificates.

Because no CA vouches for the certificate, trust comes from **registration**. The client registers the certificate's public key with the AS in JWK format, by value (`jwks`) or by URL (`jwks_uri`), as with `private_key_jwt`. The AS accepts the handshake if the presented certificate's public key matches a registered key.

The client must include its `client_id` in the request body to identify itself:

```http
POST /token HTTP/1.1
Host: c2id.com
Content-Type: application/x-www-form-urlencoded

grant_type=authorization_code
&code=n0esc3NRze7LTCu7iYzS6a5acc3f0ogp4
&redirect_uri=https%3A%2F%2Fclient.example.org%2Fcb
&client_id=s6BhdRkqt3
```

The issued access token is bound to the client certificate, as with `tls_client_auth`, which addresses the bearer weakness of regular OAuth 2.0 access tokens.

| | `tls_client_auth` | `self_signed_tls_client_auth` |
|---|---|---|
| Certificate issued by | a trusted CA | the client itself |
| The proxy checks | the chain up to a trusted CA | nothing about the issuer: it accepts self-signed certificates |
| The AS matches | the registered subject DN or SAN | the registered public key (JWK by value or URL) |
| Key rollover | get a new certificate with the same DN: no registration change | publish the new key at `jwks_uri`, or update `jwks` |
| PKI needed | yes | no |
| Token binding | yes (`cnf.x5t#S256`) | yes, the same |

**Spec:** RFC 8705, §2.2 for the method, §3 for the binding.

---

## 3. Private-key authentication is more secure

Of the two methods, the one based on a private key has stronger security properties:

- **The key can live in hardware.** A client can store the private key in a Hardware Security Module (HSM) or a Trusted Platform Module (TPM), where the signing is performed inside the module and the key can't be extracted. This can reduce the risk of client impersonation significantly. A shared secret has to be sent in each request, so it must exist in the client's memory in plain form.
- **The authorization server keeps no client credentials.** It stores only public keys, so a breach of the AS database doesn't expose anything that lets an attacker act as a client.
- **What goes over the wire expires.** The signed assertions (JWTs) expire, which limits the time window for replay if one is accidentally leaked. Client certificates can also be made to expire quickly. A leaked shared secret works until someone rotates it.
- **Tokens can be bound to the key.** The issued tokens can be bound to the private key, so resource servers can require the client to prove it holds the key when it presents the token. That upgrades OAuth 2.0 bearer tokens, which anyone holding them can use, to sender-constrained tokens. See §4.

| | Shared secret | Private key |
|---|---|---|
| Stored by the AS | the secret (ideally hashed, but it must be checkable) | only the public key |
| Sent over the wire | the secret itself, every time | a short-lived signed JWT, or a TLS handshake signature |
| Hardware protection (HSM/TPM) | not possible: the client must send the raw value | possible: signing happens inside the module |
| A leak gives the attacker | the client's identity until rotation | one assertion valid for a minute, or nothing (mTLS) |
| Rotation | coordinate a new secret on both sides | publish a new public key (a JWKS URL makes it automatic) |
| Token binding | no | yes, with mTLS or DPoP |

---

## 4. Binding tokens to the key (sender-constrained tokens)

A normal access token is a **bearer** token: whoever holds it can use it. Binding ties it to the client's key, so the AS puts a key thumbprint in the token's `cnf` (confirmation) claim, and the RS checks the caller proves it holds that key.

| Mechanism | How the client proves possession | `cnf` member | Spec |
|---|---|---|---|
| **mTLS certificate-bound tokens** | presents the same client certificate in the TLS handshake to the RS | `x5t#S256` (certificate thumbprint) | RFC 8705 |
| **DPoP** | sends a `DPoP` header: a fresh JWT signed with the key, covering the HTTP method, URL and a hash of the access token | `jkt` (JWK thumbprint) | RFC 9449 |

mTLS binding needs TLS client certificates all the way to the RS, which proxies and load balancers that end TLS make hard. DPoP works at the HTTP layer, so it passes through them.

---

## 5. A third kind: federated client assertions

Some workload identity flows use `client_assertion_type=...jwt-bearer` too, but the JWT isn't signed by a key the client owns. It's the Pod's **Kubernetes ServiceAccount token**, signed by the **cluster's** key:

- **Azure Workload Identity** sends the projected token to Entra ID as the client assertion ([workload-identity.md](workload-identity.md) §2).
- **Keycloak's "Signed JWT - Federated"** accepts the same kind of token for `client_credentials` ([keycloak-on-kubernetes.md](keycloak-on-kubernetes.md) §17).

The client holds no long-lived credential at all. The AS trusts a third party (the cluster's OIDC issuer) to vouch for the client, the way it would trust a `private_key_jwt` key. It keeps the private-key properties above: no secret stored by the AS, short-lived assertions, and the signing key stays with the cluster.

### Which cloud flows are client assertions? Azure vs AWS IRSA vs EKS Pod Identity

Kubernetes itself doesn't do any OAuth. It only issues the JWTs: the kubelet asks the API server for a ServiceAccount token with a given audience (the TokenRequest API) and writes it to a file. The cloud SDK in the container decides how to present it, and only Azure uses OAuth client authentication.

| | Azure Workload Identity | AWS IRSA | AWS EKS Pod Identity |
|---|---|---|---|
| Token file | `azure-identity-token` | `eks.amazonaws.com/.../token` | `eks-pod-identity-token` |
| `aud` | `api://AzureADTokenExchange` | `sts.amazonaws.com` | `pods.eks.amazonaws.com` |
| Sent to | Entra ID's OAuth token endpoint | AWS STS (not OAuth) | the node's Pod Identity agent, then the EKS Auth API (not OAuth) |
| How the JWT is used | **client assertion**: it authenticates the client | **the only credential**: the call itself is unsigned | **a parameter**: the call is signed with the node's IAM credentials |
| Result | an OAuth access token | temporary AWS credentials | temporary AWS credentials |

The token files, audiences and webhooks behind each column are in [workload-identity.md](workload-identity.md).

#### Azure: an RFC 7523 client assertion, the federated kind

```http
POST https://login.microsoftonline.com/<tenant>/oauth2/v2.0/token
grant_type=client_credentials
&client_id=<managed identity / app client ID>
&client_assertion_type=urn:ietf:params:oauth:client-assertion-type:jwt-bearer
&client_assertion=<ServiceAccount token>
&scope=https://vault.azure.net/.default
```

On the wire this looks exactly like `private_key_jwt`, but two claims don't fit that method:

- **The JWT isn't signed by a key the client registered.** It's signed by the cluster's key. Entra fetches that key from the cluster's OIDC issuer, and the "registration" is the federated identity credential: issuer, `sub` and `aud`.
- **`iss` and `sub` aren't the client ID.** They're the cluster issuer and `system:serviceaccount:<ns>:<sa>`.

Entra calls it workload identity federation.

#### AWS IRSA: no OAuth and no client authentication

```text
STS AssumeRoleWithWebIdentity
  RoleArn=<AWS_ROLE_ARN>
  RoleSessionName=...
  WebIdentityToken=<ServiceAccount token>
```

This is AWS's own API. The request isn't signed with any AWS credentials (no SigV4), so the JWT is the entire proof. STS checks it against the IAM OIDC provider registered for the cluster, and the role's trust policy pins `sub` and `aud`. In OAuth terms it's closer to using a JWT as the grant itself (RFC 7523 §2.1) than to client authentication (§2.2). But AWS defines it, and it returns AWS credentials, not OAuth tokens.

#### AWS EKS Pod Identity: no OAuth, and two separate checks

```text
Pod   ── GET http://169.254.170.23/v1/credentials
         Authorization: <contents of eks-pod-identity-token>
Agent ── EKS Auth AssumeRoleForPodIdentity(clusterName, token)   ← SigV4-signed with the NODE's IAM role
```

- **The agent is authenticated as the node**, by the SigV4 signature. The node role needs `eks-auth:AssumeRoleForPodIdentity`.
- **The Pod is identified by its token.** EKS checks it directly, because it's EKS's own cluster issuer, so no IAM OIDC provider is involved. EKS then looks up the association for that namespace and ServiceAccount.

---

## Related notes

- [workload-identity.md](workload-identity.md): Azure Workload Identity, AWS IRSA and EKS Pod Identity, all built on signed ServiceAccount tokens
- [keycloak-on-kubernetes.md](keycloak-on-kubernetes.md): Keycloak clients (§13) and ServiceAccount tokens as client assertions (§17)
- [rbac-and-identity.md](rbac-and-identity.md): OIDC federation and ServiceAccounts
- [secrets-and-env.md](secrets-and-env.md): "secret zero" (§7), the problem client secrets create

## Sources

- Connect2id, OAuth 2.0 client authentication (§1 to §3 text): https://connect2id.com/products/server/docs/guides/oauth-client-authentication
- RFC 6749, The OAuth 2.0 Authorization Framework (§2.3 client authentication): https://www.rfc-editor.org/rfc/rfc6749
- RFC 7523, JWT Profile for OAuth 2.0 Client Authentication and Authorization Grants: https://www.rfc-editor.org/rfc/rfc7523
- OpenID Connect Core 1.0, §9 Client Authentication: https://openid.net/specs/openid-connect-core-1_0.html#ClientAuthentication
- RFC 8705, OAuth 2.0 Mutual-TLS Client Authentication and Certificate-Bound Access Tokens: https://www.rfc-editor.org/rfc/rfc8705
- RFC 9449, OAuth 2.0 Demonstrating Proof of Possession (DPoP): https://www.rfc-editor.org/rfc/rfc9449
- RFC 7636, Proof Key for Code Exchange (PKCE): https://www.rfc-editor.org/rfc/rfc7636
- Microsoft Learn, Workload identity federation: https://learn.microsoft.com/entra/workload-id/workload-identity-federation
- Microsoft identity platform, client credentials flow with a certificate or federated assertion: https://learn.microsoft.com/entra/identity-platform/v2-oauth2-client-creds-grant-flow
- AWS docs, STS AssumeRoleWithWebIdentity: https://docs.aws.amazon.com/STS/latest/APIReference/API_AssumeRoleWithWebIdentity.html
- AWS docs, EKS Auth AssumeRoleForPodIdentity: https://docs.aws.amazon.com/eks/latest/APIReference/API_auth_AssumeRoleForPodIdentity.html
