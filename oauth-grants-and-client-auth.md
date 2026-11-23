# OAuth grants × client authentication methods (lab)

In OAuth 2.0 and 2.1, **client authentication** (how the client proves who it is at the token
endpoint) and the **grant type** (what is exchanged for a token) are separate parts of the request.
Any confidential method can be combined with any grant that uses the token endpoint:

- `client_secret_basic` and `client_secret_post` (RFC 6749 §2.3.1);
- `client_secret_jwt` and `private_key_jwt` (OIDC Core §9, RFC 7523 §2.2);
- `tls_client_auth` (RFC 8705).

The implicit grant never calls the token endpoint, so client authentication doesn't apply to it.
This file tests that claim against Keycloak, **every grant with every method**, and shows the
requests. Labels:

- **(lab)**: run here, output from the run;
- **(code)**: read in the Keycloak or nimbus-jose-jwt source;
- **(spec)**: from the RFC or specification named.

How each authentication method works on its own (secrets, JWT assertions, mTLS, binding tokens to
keys) is in [oauth-client-authentication.md](oauth-client-authentication.md). This file is about
combining them with grants.

**What an authorization grant is.** An authorization grant is the proof of authorization: the
credential representing who approved access, and in what context, that a client presents to the
token endpoint (named by `grant_type`) to obtain an access token. RFC 6749 §1.3: "An authorization
grant is a credential representing the resource owner's authorization (to access its protected
resources) used by the client to obtain an access token" **(spec)**.

RFC 6749 uses "grant" for both a credential (§1.3) and a flow (the four grant types, and any
`grant_type` value), and not every flow has a separate credential. Three layers keep them apart:

- **Authorization:** someone with authority approved access. It can be the user at a login page,
  the user on a phone, a trusted issuer vouching for the user, or an arrangement made in advance
  (as for `client_credentials`).
- **Authorization grant:** the portable evidence of that approval, which the client can carry to the
  token endpoint. Usually a separate credential; for `client_credentials` the client's own
  authentication; for implicit, none.
- **Grant type:** the name of the flow. For token-endpoint flows, it's the `grant_type` value that
  says how the evidence is presented.

**The grant types:**

- `authorization_code` (RFC 6749 §4.1)
- implicit (RFC 6749 §4.2; `response_type=token`, no `grant_type` value; removed in OAuth 2.1)
- `password` (RFC 6749 §4.3; removed in OAuth 2.1)
- `client_credentials` (RFC 6749 §4.4)
- `refresh_token` (RFC 6749 §6)
- `urn:ietf:params:oauth:grant-type:saml2-bearer` (RFC 7522)
- `urn:ietf:params:oauth:grant-type:jwt-bearer` (RFC 7523 §2.1)
- `urn:ietf:params:oauth:grant-type:device_code` (RFC 8628)
- `urn:ietf:params:oauth:grant-type:token-exchange` (RFC 8693)
- `urn:openid:params:grant-type:ciba` (OpenID CIBA Core 1.0)
- `urn:ietf:params:oauth:grant-type:uma-ticket` (UMA 2.0 Grant)
- `urn:ietf:params:oauth:grant-type:pre-authorized_code` (OpenID4VCI 1.0)

So every token request answers two separate questions:

| | Client authentication | Authorization grant |
|---|---|---|
| Question | **Which application** is making this HTTP request? | **Whose authority** is being exercised, and **what proof** backs it up? |
| In the request | `Authorization: Basic`, `client_secret`, `client_assertion`, or the TLS client certificate | `grant_type` and its parameters (`code`, `assertion`, `device_code`, …) |
| Shows up in the token as | `azp` / `client_id` | `sub`: the user (or the client itself, for `client_credentials`) |

Whose authority each grant carries, and the proof:

| Grant | Whose authority | The proof presented |
|---|---|---|
| `authorization_code` | the user, who approved at the authorization endpoint | the one-time `code` (+ PKCE `code_verifier`) |
| `password` | the user | the user's own password, handed to the client |
| `client_credentials` | the client's own ("acting on its own behalf", RFC 6749 §1.3.4) | nothing beyond the client authentication itself |
| `refresh_token` | whoever the original grant was for | the refresh token from that grant |
| `device_code` | the user, who approved on a second device | the `device_code` |
| `ciba` | the user, who approved on their own device | the `auth_req_id` |
| `token-exchange` | the subject of the `subject_token` (and an actor, for delegation) | the `subject_token` (and `actor_token`) |
| `jwt-bearer` | the `sub` of the assertion, vouched for by its issuer | the signed `assertion` |
| `uma-ticket` | the requesting party, for permissions on a resource server | a permission `ticket` or the party's token |
| `pre-authorized_code` | the user the credential offer was made for | the `pre-authorized_code` (+ `tx_code`) |

For `client_credentials` the two answers are the same: RFC 6749 §1.3.4 says "the client
credentials (or other forms of client authentication) can be used as an authorization grant". The
implicit grant is the exception the other way round: the user's approval comes back straight from
the authorization endpoint as a token, with no grant presented to the token endpoint (§5).

**Not grant types.** PKCE (RFC 7636), PAR (RFC 9126), JAR (RFC 9101), RAR (RFC 9396) and DPoP
(RFC 9449) change how a grant is requested or bound to a key. None of them is a grant type, and none
has its own `grant_type` value **(spec)**:

| Spec | What it changes | Where |
|---|---|---|
| PKCE (RFC 7636) | binds the authorization code to the client that started the flow: `code_challenge` at the authorization endpoint, `code_verifier` with the code | `authorization_code` |
| PAR (RFC 9126) | the client sends the authorization request parameters to the server first (authenticated, like a token request) and gets a `request_uri` to put in the browser redirect | before `authorization_code` |
| JAR (RFC 9101) | the authorization request parameters are sent as a signed JWT (`request` or `request_uri`) | before `authorization_code` |
| RAR (RFC 9396) | fine-grained permissions as an `authorization_details` JSON array, in place of or next to `scope` | authorization and token requests |
| DPoP (RFC 9449) | binds the issued token to a key the client holds, with a `DPoP` proof header | any grant at the token endpoint |

In Keycloak, DPoP is handled in `processGrantRequest` after client authentication and before the
grant runs, for every grant ("applicable for all access token requests regardless of grant type",
§1) **(code)**.

**Vendor variants that reuse a grant type.** Microsoft Entra's on-behalf-of flow is
`grant_type=urn:ietf:params:oauth:grant-type:jwt-bearer` plus `requested_token_use=on_behalf_of`.
The `assertion` is the user's access token that the API received, not a JWT from a separate issuer.
The request is captured in [azure-identity-flows.md](azure-identity-flows.md) §8 (floci-az refused
it, but the request MSAL sent is shown). It is the same grant type as RFC 7523 §2.1; the extra
parameter tells Entra how to treat the assertion.

---

## 0. The result

| grant | `client_secret_basic` | `client_secret_post` | `client_secret_jwt` | `private_key_jwt` | `tls_client_auth` | `none` |
|---|---|---|---|---|---|---|
| `authorization_code` | ✓ 200 | ✓ 200 | ✓ 200 | ✓ 200 | ✓ 200 | ✓ 200 |
| `password` | ✓ 200 | ✓ 200 | ✓ 200 | ✓ 200 | ✓ 200 | ✓ 200 |
| `client_credentials` | ✓ 200 | ✓ 200 | ✓ 200 | ✓ 200 | ✓ 200 | ✗ 401 |
| `refresh_token` | ✓ 200 | ✓ 200 | ✓ 200 | ✓ 200 | ✓ 200 | ✓ 200 |
| `device_code` | ✓ 200 | ✓ 200 | ✓ 200 | ✓ 200 | ✓ 200 | ✓ 200 |
| `token-exchange` | ✓ 200 | ✓ 200 | ✓ 200 | ✓ 200 | ✓ 200 | — |
| `jwt-bearer` | ✓ 200 | ✓ 200 | ✓ 200 | ✓ 200 | ✓ 200 | ✗ 400 |
| `ciba` | ✓ 200 | ✓ 200 | ✓ 200 | ✓ 200 | ✓ 200 | — |
| `uma-ticket` | ✓ 200 | ✓ 200 | ✓ 200 | ✓ 200 | ✓ 200 | — |
| `pre-authorized_code` | ✓ auth, 400 code | ✓ auth, 400 code | ✓ auth, 400 code | ✓ auth, 400 code | ✓ auth, 400 code | 400 code |

- **✓**: a token was issued, and its `azp` was the client that authenticated with that method.
- **✗**: refused, by a rule about the *client type* rather than the method (see below).
- **—**: not run (CIBA and token exchange are for confidential clients).
- **`none`** is a public client sending only `client_id`, shown for comparison. It isn't client
  authentication.
- **`pre-authorized_code`**: no real credential offer was set up. Every method got past client
  authentication and was then refused on the made-up code; a wrong secret was refused *before* the
  code was looked at (§4.11).

**The claim holds** (lab + code). Every grant that uses the token endpoint accepted every
confidential method, and a wrong credential failed for every method (§3.6). The refusals in the
table come from the grant's own rules about which clients may use it, and are the same whatever the
method:

| Refusal | Rule (Keycloak) |
|---|---|
| `client_credentials` + public client | a public client has no service account: `Public client not allowed to retrieve service account` |
| `jwt-bearer` + public client | `Public client not allowed to use authorization grant` (`DefaultJWTAuthorizationGrantValidator.validateClient`) |

Two Keycloak behaviours to know:

- **`uma-ticket` with `Authorization: Bearer <user token>` does no client authentication.** The
  client is taken from the token's `azp` (§4.10). The `Authorization` header can carry a bearer
  token or Basic credentials, not both, so this variant can't be combined with
  `client_secret_basic`.
- **`pre-authorized_code` skipped client authentication until 2 November 2026.** Keycloak commit
  `753cace5c7`, "[OID4VCI] Authenticate clients during pre-authorized code grant (#54236)", removed
  the exception. The `keycloak-main` download used here (files dated 1 October) still has it; the
  `nightly` image that ran doesn't (§1).

Client authentication isn't only for the token endpoint. The same check, with the same methods,
runs at the **device authorization endpoint** (§4.5), the **CIBA backchannel authentication
endpoint** (§4.8), and in Keycloak also at PAR, introspection, revocation and back-channel logout
(`AuthorizeClientUtil.authorizeClient` is called from `ParEndpoint`, `TokenIntrospectionEndpoint`,
`TokenRevocationEndpoint`, `LogoutEndpoint`) **(code)**.

---

## 1. Why it's orthogonal: Keycloak's token endpoint (code)

`TokenEndpoint.processGrantRequest`, from the `keycloak-main` download (`services/…/oidc/endpoints/
TokenEndpoint.java`). Client authentication (`checkClient`) runs **once, before** the grant, and
doesn't depend on which grant it is:

```java
grantType = formParams.getFirst(OIDCLoginProtocol.GRANT_TYPE_PARAM);
...
checkSsl();
checkRealm();
checkGrantType();                       // grant = session.getProvider(OAuth2GrantType.class, grantType)
...
// pre-authorized grants are not necessarily used by known clients.
if (!grantType.equals(UMA_GRANT_TYPE) && !grantType.equals(PRE_AUTH_GRANT_TYPE)) {
    checkClient();                      // ← client authentication, whatever the grant
    checkParameterDuplicated();
}
checkParameters();
DPoPUtil.handleDPoPHeader(session, event, cors, clientConfig);   // DPoP is also grant-independent
OAuth2GrantType.Context context = new OAuth2GrantType.Context(session, clientConfig, clientAuthAttributes,
                                                              formParams, event, cors, tokenManager);
return grant.process(context);          // ← the grant, given the already-authenticated client
```

In today's `main` (and the `nightly` image that ran) that condition is only
`if (!grantType.equals(UMA_GRANT_TYPE))`. Upstream removed the `PRE_AUTH_GRANT_TYPE` part on
2026-11-02 (`753cace5c7`).

`checkClient` calls `AuthorizeClientUtil.authorizeClient`, which runs the realm's **client
authentication flow** ("clients"). The flow tries its authenticators; the one named in the client's
`clientAuthenticatorType` has to succeed (`ClientAuthenticationFlow.processFlow`) **(code)**:

```java
for (AuthenticationExecutionModel model : executions) {
    ClientAuthenticatorFactory factory = ... getProviderFactory(ClientAuthenticator.class, model.getAuthenticator());
    ClientAuthenticator authenticator = factory.create();
    ...
    authenticator.authenticateClient(context);
    ClientModel client = processor.getClient();
    if (client != null) {
        String expectedClientAuthType = client.getClientAuthenticatorType();
        // ... for public clients, ignore the "clientAuthenticatorType" ... and stick to the
        // default, which set the client just based on "client_id" parameter
        if (expectedClientAuthType == null || client.isPublicClient()) { ... }
```

| Method (OAuth / OIDC name) | Keycloak `clientAuthenticatorType` | Authenticator class |
|---|---|---|
| `client_secret_basic`, `client_secret_post` | `client-secret` (one setting, both accepted) | `ClientIdAndSecretAuthenticator` |
| `client_secret_jwt` | `client-secret-jwt` | `JWTClientSecretAuthenticator` |
| `private_key_jwt` | `client-jwt` | `JWTClientAuthenticator` |
| `tls_client_auth` | `client-x509` | `X509ClientAuthenticator` |
| (federated assertions, client attestation) | `federated-jwt`, attestation | `FederatedJWTClientAuthenticator`, `AttestationBasedClientAuthenticator` |

`ClientIdAndSecretAuthenticator` reads the header first, then the form, so one client setting
covers both methods. It records which one was used **(code)**:

```java
if (authorizationHeader != null) {
    String[] usernameSecret = BasicAuthHelper.RFC6749.parseHeader(authorizationHeader);
    if (usernameSecret != null) {
        client_id = usernameSecret[0];
        clientSecret = usernameSecret[1];
        clientSecretRetrievalUsedMethod = OIDCLoginProtocol.CLIENT_SECRET_BASIC;
    } ...
}
if (formData != null) {
    if (formData.containsKey(OAuth2Constants.CLIENT_ID)) client_id = formData.getFirst(OAuth2Constants.CLIENT_ID);
    if (formData.containsKey(OAuth2Constants.CLIENT_SECRET)) {
        clientSecret = formData.getFirst(OAuth2Constants.CLIENT_SECRET);
        clientSecretRetrievalUsedMethod = OIDCLoginProtocol.CLIENT_SECRET_POST;
    }
}
```

**The token-endpoint grants Keycloak has** (`OAuth2GrantTypeFactory` implementations, `services/…/
oidc/grants/`) **(code)**:

| `grant_type` | Spec | Factory |
|---|---|---|
| `authorization_code` | RFC 6749 §4.1 | `AuthorizationCodeGrantTypeFactory` |
| `password` | RFC 6749 §4.3, **removed in OAuth 2.1** | `ResourceOwnerPasswordCredentialsGrantTypeFactory` |
| `client_credentials` | RFC 6749 §4.4 | `ClientCredentialsGrantTypeFactory` |
| `refresh_token` | RFC 6749 §6 | `RefreshTokenGrantTypeFactory` |
| `urn:ietf:params:oauth:grant-type:device_code` | RFC 8628 (2019) | `DeviceGrantTypeFactory` |
| `urn:ietf:params:oauth:grant-type:token-exchange` | RFC 8693 (2020) | `TokenExchangeGrantTypeFactory` |
| `urn:ietf:params:oauth:grant-type:jwt-bearer` | RFC 7523 §2.1 (2015) | `JWTAuthorizationGrantTypeFactory` |
| `urn:openid:params:grant-type:ciba` | OpenID CIBA Core 1.0 (2021) | `CibaGrantTypeFactory` |
| `urn:ietf:params:oauth:grant-type:uma-ticket` | UMA 2.0 Grant (Kantara, 2018) | `PermissionGrantTypeFactory` |
| `urn:ietf:params:oauth:grant-type:pre-authorized_code` | OpenID4VCI 1.0 | `PreAuthorizedCodeGrantTypeFactory` |
| `urn:ietf:params:oauth:grant-type:saml2-bearer` | RFC 7522 (2015) | **none**: `unsupported_grant_type` (§4.12) |
| implicit (`response_type=token`) | RFC 6749 §4.2, **removed in OAuth 2.1** | not a grant type: the authorization endpoint (§5) |

---

## 2. Lab setup

- **Keycloak** `quay.io/keycloak/keycloak:nightly` (999.0.0-SNAPSHOT, 2026-11-23). It runs on
  HTTPS with `--https-client-auth=request`, so it asks every TLS client for a certificate but
  doesn't require one. Its truststore holds the lab CA, which issued the client certificate
  `O=oauth-lab, CN=c-mtls`. The OID4VCI features are on
  (`--features=oid4vc-vci,oid4vc-vci-preauth-code`).
- **Realm `lab`**: user `alice`. Every client has standard flow, direct access, service account,
  device, CIBA, standard token exchange (v2) and JWT authorization grant enabled, and an audience
  mapper that puts the client in its own tokens' `aud`.

| Client | `clientAuthenticatorType` | Credential |
|---|---|---|
| `c-secret` | `client-secret` | secret `secret-for-basic-and-post-0123456789` |
| `c-secret-jwt` | `client-secret-jwt`, `token.endpoint.auth.signing.alg=HS256` | secret `hmac-secret-for-client-secret-jwt-0123456789` (the HMAC key) |
| `c-pkjwt` | `client-jwt`, `use.jwks.string=true`, `jwks.string=<its public JWKS>` | an RSA key, `kid: pk-1` |
| `c-mtls` | `client-x509`, `x509.subjectdn=O=oauth-lab, CN=c-mtls`, `x509.casubjectdn=CN=oauth-lab-ca` | the TLS client certificate |
| `c-public` | public (`publicClient: true`) | none |
| `api` | resource server, Authorization Services on, policy enforcement `PERMISSIVE`, one resource `orders` | for UMA |

- **The CIBA authentication channel** is Keycloak's HTTP channel, pointed at a stand-in for the
  user's phone (`--spi-ciba-auth-channel--ciba-http-auth-channel--http-authentication-channel-uri`).
  The stand-in approves each request through Keycloak's callback.
- **The JWT authorization grant** has an identity provider `ext-idp` of type
  `jwt-authorization-grant`: issuer `https://ext-idp.example.test`, its JWKS served from the host.
  `alice` is linked to it as `ext-alice`.
- **The client** is a Python script sending each request with `http.client`. It follows Keycloak's
  login and consent pages for the browser steps, and presents the client certificate only for
  `c-mtls`.

**JWTs are signed with nimbus-jose-jwt**, compiled from `nimbus-jose-jwt/src` (11.0-SNAPSHOT,
`target/classes`, plus Gson 2.14.0, which the release jar shades). The helper, `Jwt.java`
**(code)**:

```java
/** RFC 7523 §3 client assertion: iss = sub = client_id, aud = the authorization server, short exp, unique jti. */
static JWTClaimsSet clientClaims(String clientId, String aud) {
    Date now = new Date();
    return new JWTClaimsSet.Builder().issuer(clientId).subject(clientId).audience(aud)
        .issueTime(now).expirationTime(new Date(now.getTime() + 60_000)).jwtID(UUID.randomUUID().toString()).build();
}

case "client_secret_jwt" ->     // HMAC with the client secret as the key
    System.out.println(sign(new JWSHeader(JWSAlgorithm.HS256), clientClaims(a[1], a[3]), new MACSigner(a[2].getBytes())));
case "private_key_jwt" -> {     // RSA with the client's private key; kid tells the server which public key to use
    RSAKey k = RSAKey.parse(Files.readString(Path.of(a[2])));
    System.out.println(sign(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(k.getKeyID()).type(JOSEObjectType.JWT).build(),
        clientClaims(a[1], a[3]), new RSASSASigner(k)));
}
case "grant" -> {               // RFC 7523 §2.1 authorization grant: iss = the external issuer, sub = the user there
    RSAKey k = RSAKey.parse(Files.readString(Path.of(a[4])));
    Date now = new Date();
    JWTClaimsSet c = new JWTClaimsSet.Builder().issuer(a[1]).subject(a[2]).audience(a[3])
        .issueTime(now).expirationTime(new Date(now.getTime() + 120_000)).jwtID(UUID.randomUUID().toString()).build();
    System.out.println(sign(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(k.getKeyID()).type(JOSEObjectType.JWT).build(), c, new RSASSASigner(k)));
}
...
static String sign(JWSHeader h, JWTClaimsSet c, JWSSigner s) throws Exception {
    SignedJWT jwt = new SignedJWT(h, c); jwt.sign(s); return jwt.serialize();
}
// keys: new RSAKeyGenerator(2048).keyID(kid).keyUse(KeyUse.SIGNATURE).algorithm(JWSAlgorithm.RS256).generate()
```

nimbus-jose-jwt has the JOSE and JWT classes only. The OAuth request types
(`ClientSecretBasic`, `PrivateKeyJWT`, `TokenRequest`, the grant classes) are in its sibling
library, Nimbus OAuth 2.0 SDK with OpenID Connect extensions, which wasn't used here.

How the requests are shown: form bodies are **URL-decoded** for reading (on the wire they are
`application/x-www-form-urlencoded`); assertions, codes and tokens are cut after 30 characters
(`…`); only `Content-Type`, `Location`, `Cache-Control` and `Pragma` response headers are kept.

---

## 3. The five methods, one request each (lab)

The same grant, `client_credentials`, so that only the authentication differs.

### 3.1 `client_secret_basic`

```http
POST /realms/lab/protocol/openid-connect/token HTTP/1.1
Host: localhost:8443
Authorization: Basic Yy1zZWNyZXQ6c2VjcmV0LWZvci1iYXNpYy1hbmQtcG9zdC0wMTIzNDU2Nzg5
Content-Type: application/x-www-form-urlencoded

grant_type=client_credentials
```

```http
HTTP/1.1 200 OK
Cache-Control: no-store
Pragma: no-cache
Content-Type: application/json

{
  "access_token": "eyJhbGciOiJSUzI1NiIsInR5cCIgOi…",
  "expires_in": 600,
  "refresh_expires_in": 0,
  "token_type": "Bearer",
  "not-before-policy": 0,
  "scope": "email profile"
}
```

`Basic` + base64 of `client_id:client_secret`, each form-urlencoded first (RFC 6749 §2.3.1); here
the base64 of `c-secret:secret-for-basic-and-post-0123456789`. `Cache-Control: no-store` and
`Pragma: no-cache` are on every token response (RFC 6749 §5.1, set in `processGrantRequest`).

```json
// the access token's payload (selected claims)
{
  "iss": "https://localhost:8443/realms/lab",
  "sub": "a30eb176-79dd-4a6a-b88e-cf4e2ace26e4",
  "preferred_username": "service-account-c-secret",
  "azp": "c-secret",
  "aud": "c-secret",
  "typ": "Bearer",
  "scope": "email profile"
}
```

### 3.2 `client_secret_post`

```http
POST /realms/lab/protocol/openid-connect/token HTTP/1.1
Host: localhost:8443
Content-Type: application/x-www-form-urlencoded

grant_type=client_credentials&client_id=c-secret&client_secret=secret-for-basic-and-post-0123456789
```

→ `200`, token for `azp: c-secret`. The same secret, in the body. RFC 6749 §2.3.1:
the server MUST support Basic; sending the credentials in the body is NOT RECOMMENDED and limited to
clients that can't use Basic **(spec)**.

### 3.3 `client_secret_jwt`

```http
POST /realms/lab/protocol/openid-connect/token HTTP/1.1
Host: localhost:8443
Content-Type: application/x-www-form-urlencoded

grant_type=client_credentials&client_id=c-secret-jwt&client_assertion_type=urn:ietf:params:oauth:client-assertion-type:jwt-bearer&client_assertion=eyJhbGciOiJIUzI1NiJ9.eyJpc3MiO…
```

→ `200`, token for `azp: c-secret-jwt`.

```json
// client_assertion, decoded: header, then payload
{"alg": "HS256"}
{
  "iss": "c-secret-jwt",
  "sub": "c-secret-jwt",
  "aud": "https://localhost:8443/realms/lab",
  "exp": 1795439578,
  "iat": 1795439518,
  "jti": "f0799041-63d3-4df4-8854-2901fa83b69a"
}
```

HMAC-SHA256 with the client secret as the key. The secret itself never goes on the wire. `aud` is
the realm's issuer URL, `exp` one minute away, `jti` unique per assertion.

### 3.4 `private_key_jwt`

```http
POST /realms/lab/protocol/openid-connect/token HTTP/1.1
Host: localhost:8443
Content-Type: application/x-www-form-urlencoded

grant_type=client_credentials&client_id=c-pkjwt&client_assertion_type=urn:ietf:params:oauth:client-assertion-type:jwt-bearer&client_assertion=eyJraWQiOiJway0xIiwidHlwIjoiSl…
```

→ `200`, token for `azp: c-pkjwt`.

```json
// client_assertion, decoded: header, then payload
{"kid": "pk-1", "typ": "JWT", "alg": "RS256"}
{
  "iss": "c-pkjwt",
  "sub": "c-pkjwt",
  "aud": "https://localhost:8443/realms/lab",
  "exp": 1795439578,
  "iat": 1795439518,
  "jti": "c0670cf2-9312-4539-8195-4b6546011f7a"
}
```

The same claims, signed RS256 with the client's private key. Keycloak verifies it with the key
whose `kid` (`pk-1`) is in the client's registered JWKS. The server holds no secret at all.

### 3.5 `tls_client_auth`

```http
POST /realms/lab/protocol/openid-connect/token HTTP/1.1
Host: localhost:8443
Content-Type: application/x-www-form-urlencoded

grant_type=client_credentials&client_id=c-mtls
```

→ `200`, token for `azp: c-mtls`.

Only `client_id` in the HTTP request. The credential is in the TLS handshake: the client presented
the certificate `O=oauth-lab, CN=c-mtls`, issued by `CN=oauth-lab-ca`. Keycloak compares the
certificate's subject DN with `x509.subjectdn` and its issuer with `x509.casubjectdn`.

**DN string order (lab).** The certificate was made with `-subj "/CN=c-mtls/O=oauth-lab"`, but
Keycloak's debug log showed `Checked Subject DN: O=oauth-lab, CN=c-mtls`, so
`x509.subjectdn=CN=c-mtls,O=oauth-lab` failed with `invalid_client`. Configure the DN in the order
Keycloak prints it. Without `x509.casubjectdn`, Keycloak logs that the setting is deprecated: then
any CA in the truststore could issue a certificate with that subject.

### 3.6 Each method with a wrong credential (lab)

The control that shows the check is real: a wrong secret, a JWT signed with the wrong key, and no
certificate.

**client_secret_basic** → `401` `unauthorized_client`: Invalid client or Invalid client credentials

```http
HTTP/1.1 401 Unauthorized
Cache-Control: no-store
Pragma: no-cache
Content-Type: application/json

{
  "error": "unauthorized_client",
  "error_description": "Invalid client or Invalid client credentials"
}
```

**client_secret_post** → `401` `unauthorized_client`: Invalid client or Invalid client credentials

**client_secret_jwt** (signed with another key) → `400` `unauthorized_client`: Client authentication with client secret signed JWT failed: Signature on JWT token by client secret  failed validation

**private_key_jwt** (signed with the `ext-idp` key, `kid: idp-1`, which isn't in the client's JWKS) → `400` `invalid_client`: Unable to load public key

**tls_client_auth** (no certificate in the handshake) → `401` `invalid_client`: Invalid client or Invalid client credentials

**Why the controls matter (lab).** On the first run every client was **public**: the admin API's
default for `publicClient`, since the setup didn't set it. Every method "worked", because Keycloak
ignores `clientAuthenticatorType` for public clients and doesn't check credentials at all (the
comment in `processFlow` above). Only `client_credentials` failed, with "Public client not allowed
to retrieve service account". A matrix of 200s proves nothing without a failing control.

---

## 4. Every grant with every method (lab)

Each grant shows one complete exchange, using a different method each time, then the request the
other methods sent, with the result.

### 4.1 `authorization_code` (RFC 6749 §4.1)

The browser step first (once, for `c-secret`). The authorization endpoint takes no client
authentication, only `client_id` and `redirect_uri`:

```http
POST /realms/lab/login-actions/authenticate?session_code=gOuUUPO5Zqb004dCY3wmgqkCx16TdXEvGFiUGMkD7tw&execution=9bc0f46b-2473-4c71-b477-832c74d265… HTTP/1.1
Host: localhost:8443
Content-Type: application/x-www-form-urlencoded
Cookie: AUTH_SESSION_ID=c0pKdWFzRVJJcWRLNGxRRkE3eFo3MGQ5LlZjU2RpQzM5WFpZLUNNdXRLRzhBQXNMX3d6MXJnSzNxWi1ZQ1l4RTNyZkQ0Q0RsQTl4LVhRUlRjaXBqOVNpS0ZJMU1UV291MXpCcW9TM1dJV2pCd2l3; KC_AUTH_SESSION_HASH=66kmXTc8SD9X8mjN9zomzjyB3cAcjTrl1DKU9fCjR+sLkB8sAzmOghMwLdBEVXPA; KC_RESTART=eyJhbGciOiJkaXIiLCJlbmMiOiJBMjU2R0NNIiwia2lkIjoiNjc5NWM1NWQtMjYzNy00M2NlLThmNTgtMDIwNjJlZDMzMjExIn0..e8SzwRPuPMXlPEIz.YFGJEZ3p6mzryqtaBklKo0BO_0Id_tm37Dfkyze9hwH_EQ7qEOLp3fUYI4fx0DC-idDS7L0--n0gfNopHx5aW4-EC-G1sbveO6IFInIhBSiTBeGo-8Vrw_Tv95SyH68sBjI8LC-b6krP_abH7Q8xVCkkjEvJSXS0AkU15a4N3Sa2Uz47ts6dhGl0DUPIylinQwc--9xyb2t4WqsHwSlVAlekoy3n34vHX1F8mIufMYbdi6Zv5Eq_aKSfZAUwN2KtZpwFXp7Q8q-726tKyfonE5M0YENf5CvyzqxO9maUUlstcGowZGLC6vCNemQdBBrfm8DHrZufEIphRKLZf7V-OTnGIY1v0oR--CgfK9RF0WMqavHaZ7Kn_96RRmCgQEimqX3QMzTu3iP7umV9GxerVYx4ltUED9JmXO-TwqxFfwBaAehLapwOLSylA-JaXSGd4kRUELnfRpJdbN5Hx-RTOEPwME_rThbfVKd-chyDZRl0M8fXCjui_-7B_cFE3kaNV1jYLJhLm-d_HImeyykfS5PBazwmj4VFsWN436fQuzo8H7EPWSgadQy4qCsYmRd5FM97xIBdSZaJSK3QKpYdtbOYOmH7gWfUU3ItiuokvVOo0EZJl-qH3vcAuB4DchK1tqIx1zbpZib3wMM98VmxhZWmyTBkAU7C.GYfFWwwnaaGIKBA1NhBqfw

username=alice&password=alice-pass
```

```http
HTTP/1.1 302 Found
Cache-Control: no-store, must-revalidate, max-age=0
Location: https://app.local/cb?state=st&session_state=sJJuasERIqdK4lQFA7xZ70d9&iss=https%3A%2F%2Flocalhost%3A8443%2Frealms%2Flab&code=ee4a8d7c-3fd7-0e3b-e6d7-8420c185f38c…
```

(The login page's form is posted with Alice's password, and Keycloak redirects to
`https://app.local/cb?code=…&state=st`.) Then the code goes to the token endpoint, with the client's
authentication:

```http
POST /realms/lab/protocol/openid-connect/token HTTP/1.1
Host: localhost:8443
Authorization: Basic Yy1zZWNyZXQ6c2VjcmV0LWZvci1iYXNpYy1hbmQtcG9zdC0wMTIzNDU2Nzg5
Content-Type: application/x-www-form-urlencoded

grant_type=authorization_code&code=ee4a8d7c-3fd7-0e3b-e6d7-8420c1…&redirect_uri=https://app.local/cb
```

```http
HTTP/1.1 200 OK
Cache-Control: no-store
Pragma: no-cache
Content-Type: application/json

{
  "access_token": "eyJhbGciOiJSUzI1NiIsInR5cCIgOi…",
  "expires_in": 600,
  "refresh_expires_in": 1800,
  "refresh_token": "eyJhbGciOiJIUzUxMiIsInR5cCIgOi…",
  "token_type": "Bearer",
  "id_token": "eyJhbGciOiJSUzI1NiIsInR5cCIgOi…",
  "not-before-policy": 0,
  "session_state": "sJJuasERIqdK4lQFA7xZ70d9",
  "scope": "openid email profile"
}
```

```json
// the access token's payload (selected claims)
{
  "iss": "https://localhost:8443/realms/lab",
  "sub": "de922e8b-c117-4bb9-bf05-abe7468dff3f",
  "preferred_username": "alice",
  "azp": "c-secret",
  "aud": "c-secret",
  "typ": "Bearer",
  "scope": "openid email profile"
}
```

**client_secret_post** → `200`, token for `azp: c-secret`

```http
POST /realms/lab/protocol/openid-connect/token HTTP/1.1
Host: localhost:8443
Content-Type: application/x-www-form-urlencoded

grant_type=authorization_code&code=e0834a73-0f96-728a-cd9d-b5bade…&redirect_uri=https://app.local/cb&client_id=c-secret&client_secret=secret-for-basic-and-post-0123456789
```

**client_secret_jwt** → `200`, token for `azp: c-secret-jwt`

```http
POST /realms/lab/protocol/openid-connect/token HTTP/1.1
Host: localhost:8443
Content-Type: application/x-www-form-urlencoded

grant_type=authorization_code&code=2827edcd-87ec-76c0-bb98-37e16d…&redirect_uri=https://app.local/cb&client_id=c-secret-jwt&client_assertion_type=urn:ietf:params:oauth:client-assertion-type:jwt-bearer&client_assertion=eyJhbGciOiJIUzI1NiJ9.eyJpc3MiO…
```

**private_key_jwt** → `200`, token for `azp: c-pkjwt`

```http
POST /realms/lab/protocol/openid-connect/token HTTP/1.1
Host: localhost:8443
Content-Type: application/x-www-form-urlencoded

grant_type=authorization_code&code=f0a2adf8-aaa3-8b04-febc-a10f87…&redirect_uri=https://app.local/cb&client_id=c-pkjwt&client_assertion_type=urn:ietf:params:oauth:client-assertion-type:jwt-bearer&client_assertion=eyJraWQiOiJway0xIiwidHlwIjoiSl…
```

**tls_client_auth** (the TLS handshake carries the client certificate) → `200`, token for `azp: c-mtls`

```http
POST /realms/lab/protocol/openid-connect/token HTTP/1.1
Host: localhost:8443
Content-Type: application/x-www-form-urlencoded

grant_type=authorization_code&code=39993665-3aa9-8a12-1b49-ff862c…&redirect_uri=https://app.local/cb&client_id=c-mtls
```

**none** → `200`, token for `azp: c-public`

```http
POST /realms/lab/protocol/openid-connect/token HTTP/1.1
Host: localhost:8443
Content-Type: application/x-www-form-urlencoded

grant_type=authorization_code&code=be75c989-0113-5e54-9aa0-0a6a1a…&redirect_uri=https://app.local/cb&client_id=c-public
```

### 4.2 `password` (RFC 6749 §4.3; removed in OAuth 2.1)

```http
POST /realms/lab/protocol/openid-connect/token HTTP/1.1
Host: localhost:8443
Content-Type: application/x-www-form-urlencoded

grant_type=password&username=alice&password=alice-pass&scope=openid&client_id=c-secret&client_secret=secret-for-basic-and-post-0123456789
```

```http
HTTP/1.1 200 OK
Cache-Control: no-store
Pragma: no-cache
Content-Type: application/json

{
  "access_token": "eyJhbGciOiJSUzI1NiIsInR5cCIgOi…",
  "expires_in": 600,
  "refresh_expires_in": 1800,
  "refresh_token": "eyJhbGciOiJIUzUxMiIsInR5cCIgOi…",
  "token_type": "Bearer",
  "id_token": "eyJhbGciOiJSUzI1NiIsInR5cCIgOi…",
  "not-before-policy": 0,
  "session_state": "TYvBbtLilH3cr81hmZruqaFh",
  "scope": "openid email profile"
}
```

**client_secret_basic** → `200`, token for `azp: c-secret`

```http
POST /realms/lab/protocol/openid-connect/token HTTP/1.1
Host: localhost:8443
Authorization: Basic Yy1zZWNyZXQ6c2VjcmV0LWZvci1iYXNpYy1hbmQtcG9zdC0wMTIzNDU2Nzg5
Content-Type: application/x-www-form-urlencoded

grant_type=password&username=alice&password=alice-pass&scope=openid
```

**client_secret_jwt** → `200`, token for `azp: c-secret-jwt`

```http
POST /realms/lab/protocol/openid-connect/token HTTP/1.1
Host: localhost:8443
Content-Type: application/x-www-form-urlencoded

grant_type=password&username=alice&password=alice-pass&scope=openid&client_id=c-secret-jwt&client_assertion_type=urn:ietf:params:oauth:client-assertion-type:jwt-bearer&client_assertion=eyJhbGciOiJIUzI1NiJ9.eyJpc3MiO…
```

**private_key_jwt** → `200`, token for `azp: c-pkjwt`

```http
POST /realms/lab/protocol/openid-connect/token HTTP/1.1
Host: localhost:8443
Content-Type: application/x-www-form-urlencoded

grant_type=password&username=alice&password=alice-pass&scope=openid&client_id=c-pkjwt&client_assertion_type=urn:ietf:params:oauth:client-assertion-type:jwt-bearer&client_assertion=eyJraWQiOiJway0xIiwidHlwIjoiSl…
```

**tls_client_auth** (the TLS handshake carries the client certificate) → `200`, token for `azp: c-mtls`

```http
POST /realms/lab/protocol/openid-connect/token HTTP/1.1
Host: localhost:8443
Content-Type: application/x-www-form-urlencoded

grant_type=password&username=alice&password=alice-pass&scope=openid&client_id=c-mtls
```

**none** → `200`, token for `azp: c-public`

```http
POST /realms/lab/protocol/openid-connect/token HTTP/1.1
Host: localhost:8443
Content-Type: application/x-www-form-urlencoded

grant_type=password&username=alice&password=alice-pass&scope=openid&client_id=c-public
```

### 4.3 `client_credentials` (RFC 6749 §4.4)

Shown in §3: all five methods with requests, and the public client:
`none` → `401` `unauthorized_client`: Public client not allowed to retrieve service account.

### 4.4 `refresh_token` (RFC 6749 §6)

Each method refreshes the refresh token from its own `password` grant in §4.2. A refresh token is
bound to the client it was issued to, so the client must authenticate as that client again:

```http
POST /realms/lab/protocol/openid-connect/token HTTP/1.1
Host: localhost:8443
Content-Type: application/x-www-form-urlencoded

grant_type=refresh_token&refresh_token=eyJhbGciOiJIUzUxMiIsInR5cCIgOi…&client_id=c-secret-jwt&client_assertion_type=urn:ietf:params:oauth:client-assertion-type:jwt-bearer&client_assertion=eyJhbGciOiJIUzI1NiJ9.eyJpc3MiO…
```

```http
HTTP/1.1 200 OK
Cache-Control: no-store
Pragma: no-cache
Content-Type: application/json

{
  "access_token": "eyJhbGciOiJSUzI1NiIsInR5cCIgOi…",
  "expires_in": 600,
  "refresh_expires_in": 1800,
  "refresh_token": "eyJhbGciOiJIUzUxMiIsInR5cCIgOi…",
  "token_type": "Bearer",
  "id_token": "eyJhbGciOiJSUzI1NiIsInR5cCIgOi…",
  "not-before-policy": 0,
  "session_state": "Y_CEq7wyq-8fkr-k9d3okHcG",
  "scope": "openid email profile"
}
```

**client_secret_basic** → `200`, token for `azp: c-secret`

```http
POST /realms/lab/protocol/openid-connect/token HTTP/1.1
Host: localhost:8443
Authorization: Basic Yy1zZWNyZXQ6c2VjcmV0LWZvci1iYXNpYy1hbmQtcG9zdC0wMTIzNDU2Nzg5
Content-Type: application/x-www-form-urlencoded

grant_type=refresh_token&refresh_token=eyJhbGciOiJIUzUxMiIsInR5cCIgOi…
```

**client_secret_post** → `200`, token for `azp: c-secret`

```http
POST /realms/lab/protocol/openid-connect/token HTTP/1.1
Host: localhost:8443
Content-Type: application/x-www-form-urlencoded

grant_type=refresh_token&refresh_token=eyJhbGciOiJIUzUxMiIsInR5cCIgOi…&client_id=c-secret&client_secret=secret-for-basic-and-post-0123456789
```

**private_key_jwt** → `200`, token for `azp: c-pkjwt`

```http
POST /realms/lab/protocol/openid-connect/token HTTP/1.1
Host: localhost:8443
Content-Type: application/x-www-form-urlencoded

grant_type=refresh_token&refresh_token=eyJhbGciOiJIUzUxMiIsInR5cCIgOi…&client_id=c-pkjwt&client_assertion_type=urn:ietf:params:oauth:client-assertion-type:jwt-bearer&client_assertion=eyJraWQiOiJway0xIiwidHlwIjoiSl…
```

**tls_client_auth** (the TLS handshake carries the client certificate) → `200`, token for `azp: c-mtls`

```http
POST /realms/lab/protocol/openid-connect/token HTTP/1.1
Host: localhost:8443
Content-Type: application/x-www-form-urlencoded

grant_type=refresh_token&refresh_token=eyJhbGciOiJIUzUxMiIsInR5cCIgOi…&client_id=c-mtls
```

**none** → `200`, token for `azp: c-public`

```http
POST /realms/lab/protocol/openid-connect/token HTTP/1.1
Host: localhost:8443
Content-Type: application/x-www-form-urlencoded

grant_type=refresh_token&refresh_token=eyJhbGciOiJIUzUxMiIsInR5cCIgOi…&client_id=c-public
```

### 4.5 `device_code` (RFC 8628)

Three steps. The **device authorization request** authenticates the client too (RFC 8628 §3.1:
"The client authentication requirements of Section 3.2.1 of [RFC6749] apply to requests on this
endpoint"):

```http
POST /realms/lab/protocol/openid-connect/auth/device HTTP/1.1
Host: localhost:8443
Content-Type: application/x-www-form-urlencoded

client_id=c-pkjwt&client_assertion_type=urn:ietf:params:oauth:client-assertion-type:jwt-bearer&client_assertion=eyJraWQiOiJway0xIiwidHlwIjoiSl…&scope=openid
```

```http
HTTP/1.1 200 OK
Cache-Control: no-store, must-revalidate, max-age=0
Content-Type: application/json

{
  "device_code": "3oZ2pRr5lLyAxzn36BmMWKFmjG83XP…",
  "user_code": "UTTI-ZGRN",
  "verification_uri": "https://localhost:8443/realms/lab/device",
  "verification_uri_complete": "https://localhost:8443/realms/lab/device?user_code=UTTI-ZGRN",
  "expires_in": 600,
  "interval": 1
}
```

Then Alice opens `verification_uri_complete` in a browser, signs in, and accepts Keycloak's consent
screen (`required-action?execution=OAUTH_GRANT`; the device grant always shows one). Then the device
polls the token endpoint:

```http
POST /realms/lab/protocol/openid-connect/token HTTP/1.1
Host: localhost:8443
Content-Type: application/x-www-form-urlencoded

grant_type=urn:ietf:params:oauth:grant-type:device_code&device_code=3oZ2pRr5lLyAxzn36BmMWKFmjG83XP…&client_id=c-pkjwt&client_assertion_type=urn:ietf:params:oauth:client-assertion-type:jwt-bearer&client_assertion=eyJraWQiOiJway0xIiwidHlwIjoiSl…
```

```http
HTTP/1.1 200 OK
Cache-Control: no-store
Pragma: no-cache
Content-Type: application/json

{
  "access_token": "eyJhbGciOiJSUzI1NiIsInR5cCIgOi…",
  "expires_in": 600,
  "refresh_expires_in": 1800,
  "refresh_token": "eyJhbGciOiJIUzUxMiIsInR5cCIgOi…",
  "token_type": "Bearer",
  "id_token": "eyJhbGciOiJSUzI1NiIsInR5cCIgOi…",
  "not-before-policy": 0,
  "session_state": "7lNGjct6aIkGJDpL31Wv7psS",
  "scope": "openid email profile"
}
```

**client_secret_basic** → `200`, token for `azp: c-secret`

```http
POST /realms/lab/protocol/openid-connect/token HTTP/1.1
Host: localhost:8443
Authorization: Basic Yy1zZWNyZXQ6c2VjcmV0LWZvci1iYXNpYy1hbmQtcG9zdC0wMTIzNDU2Nzg5
Content-Type: application/x-www-form-urlencoded

grant_type=urn:ietf:params:oauth:grant-type:device_code&device_code=CwAGgobkvfTGwxp5iIiVVsMzJWTAFi…
```

**client_secret_post** → `200`, token for `azp: c-secret`

```http
POST /realms/lab/protocol/openid-connect/token HTTP/1.1
Host: localhost:8443
Content-Type: application/x-www-form-urlencoded

grant_type=urn:ietf:params:oauth:grant-type:device_code&device_code=Vj1mkx74PJoOgygyeiCti0GXX2Vq1I…&client_id=c-secret&client_secret=secret-for-basic-and-post-0123456789
```

**client_secret_jwt** → `200`, token for `azp: c-secret-jwt`

```http
POST /realms/lab/protocol/openid-connect/token HTTP/1.1
Host: localhost:8443
Content-Type: application/x-www-form-urlencoded

grant_type=urn:ietf:params:oauth:grant-type:device_code&device_code=CHYveUtjy9P96MKK_6Uui6Sy0LGHf3…&client_id=c-secret-jwt&client_assertion_type=urn:ietf:params:oauth:client-assertion-type:jwt-bearer&client_assertion=eyJhbGciOiJIUzI1NiJ9.eyJpc3MiO…
```

**tls_client_auth** (the TLS handshake carries the client certificate) → `200`, token for `azp: c-mtls`

```http
POST /realms/lab/protocol/openid-connect/token HTTP/1.1
Host: localhost:8443
Content-Type: application/x-www-form-urlencoded

grant_type=urn:ietf:params:oauth:grant-type:device_code&device_code=V7jbWOg2stJSTrpmbIQu3Mb_cNvmYx…&client_id=c-mtls
```

**none** → `200`, token for `azp: c-public`

```http
POST /realms/lab/protocol/openid-connect/token HTTP/1.1
Host: localhost:8443
Content-Type: application/x-www-form-urlencoded

grant_type=urn:ietf:params:oauth:grant-type:device_code&device_code=IgRPxLScg6VcLei6BEUkO9Xh3Dv0Uy…&client_id=c-public
```

Before approval the same request gets `400 authorization_pending` (seen in an earlier run, when the
consent screen wasn't accepted). With `oauth2DevicePollingInterval: 0` this Keycloak build failed
with `500` (`IllegalArgumentException: lifespanInSeconds must be positive`), so the lab uses 1 s.

### 4.6 `token-exchange` (RFC 8693)

Each client exchanges the access token from its own `password` grant (§4.2), in which the audience
mapper put the client itself in `aud`, because Keycloak's standard token exchange (v2) expects the
requesting client to be an audience of the `subject_token` (Keycloak docs; not tested without the
mapper):

```http
POST /realms/lab/protocol/openid-connect/token HTTP/1.1
Host: localhost:8443
Content-Type: application/x-www-form-urlencoded

grant_type=urn:ietf:params:oauth:grant-type:token-exchange&subject_token=eyJhbGciOiJSUzI1NiIsInR5cCIgOi…&subject_token_type=urn:ietf:params:oauth:token-type:access_token&requested_token_type=urn:ietf:params:oauth:token-type:access_token&client_id=c-mtls
```

```http
HTTP/1.1 200 OK
Cache-Control: no-store
Pragma: no-cache
Content-Type: application/json

{
  "access_token": "eyJhbGciOiJSUzI1NiIsInR5cCIgOi…",
  "expires_in": 600,
  "refresh_expires_in": 0,
  "token_type": "Bearer",
  "not-before-policy": 0,
  "session_state": "goxNLs6nx4WhYGekxre7Cy9C",
  "scope": "openid email profile",
  "issued_token_type": "urn:ietf:params:oauth:token-type:access_token"
}
```

That one used `tls_client_auth`: the form has only `client_id`, the certificate is in the handshake.

**client_secret_basic** → `200`, token for `azp: c-secret`

```http
POST /realms/lab/protocol/openid-connect/token HTTP/1.1
Host: localhost:8443
Authorization: Basic Yy1zZWNyZXQ6c2VjcmV0LWZvci1iYXNpYy1hbmQtcG9zdC0wMTIzNDU2Nzg5
Content-Type: application/x-www-form-urlencoded

grant_type=urn:ietf:params:oauth:grant-type:token-exchange&subject_token=eyJhbGciOiJSUzI1NiIsInR5cCIgOi…&subject_token_type=urn:ietf:params:oauth:token-type:access_token&requested_token_type=urn:ietf:params:oauth:token-type:access_token
```

**client_secret_post** → `200`, token for `azp: c-secret`

```http
POST /realms/lab/protocol/openid-connect/token HTTP/1.1
Host: localhost:8443
Content-Type: application/x-www-form-urlencoded

grant_type=urn:ietf:params:oauth:grant-type:token-exchange&subject_token=eyJhbGciOiJSUzI1NiIsInR5cCIgOi…&subject_token_type=urn:ietf:params:oauth:token-type:access_token&requested_token_type=urn:ietf:params:oauth:token-type:access_token&client_id=c-secret&client_secret=secret-for-basic-and-post-0123456789
```

**client_secret_jwt** → `200`, token for `azp: c-secret-jwt`

```http
POST /realms/lab/protocol/openid-connect/token HTTP/1.1
Host: localhost:8443
Content-Type: application/x-www-form-urlencoded

grant_type=urn:ietf:params:oauth:grant-type:token-exchange&subject_token=eyJhbGciOiJSUzI1NiIsInR5cCIgOi…&subject_token_type=urn:ietf:params:oauth:token-type:access_token&requested_token_type=urn:ietf:params:oauth:token-type:access_token&client_id=c-secret-jwt&client_assertion_type=urn:ietf:params:oauth:client-assertion-type:jwt-bearer&client_assertion=eyJhbGciOiJIUzI1NiJ9.eyJpc3MiO…
```

**private_key_jwt** → `200`, token for `azp: c-pkjwt`

```http
POST /realms/lab/protocol/openid-connect/token HTTP/1.1
Host: localhost:8443
Content-Type: application/x-www-form-urlencoded

grant_type=urn:ietf:params:oauth:grant-type:token-exchange&subject_token=eyJhbGciOiJSUzI1NiIsInR5cCIgOi…&subject_token_type=urn:ietf:params:oauth:token-type:access_token&requested_token_type=urn:ietf:params:oauth:token-type:access_token&client_id=c-pkjwt&client_assertion_type=urn:ietf:params:oauth:client-assertion-type:jwt-bearer&client_assertion=eyJraWQiOiJway0xIiwidHlwIjoiSl…
```

### 4.7 `jwt-bearer` (RFC 7523 §2.1, "JWT as an authorization grant")

**The `jwt-bearer` grant is not the `jwt-bearer` client assertion.** They come from the same RFC
(7523) and both carry a JWT, but they do different jobs in the request **(spec)**:

| | Client authentication (RFC 7523 §2.2) | Authorization grant (RFC 7523 §2.1) |
|---|---|---|
| Parameters | `client_assertion_type=urn:ietf:params:oauth:client-assertion-type:jwt-bearer` + `client_assertion=<JWT>` | `grant_type=urn:ietf:params:oauth:grant-type:jwt-bearer` + `assertion=<JWT>` |
| Answers | **who the client is** (it replaces `client_secret`) | **what is exchanged for a token** (it replaces a code or a password) |
| Who signs | the client: its secret (`client_secret_jwt`) or its private key (`private_key_jwt`) | a trusted issuer, e.g. an external IdP |
| `iss` / `sub` | `iss` = `sub` = the `client_id` | `iss` = that issuer; `sub` = the user (or other principal) the token will be for |
| Token you get | for whatever the grant says | for the `sub`, i.e. the user |
| Used with | any grant, and also PAR, introspection, revocation, device and CIBA requests | only the token endpoint, as the grant |

The URNs differ in the middle part, `client-assertion-type` versus `grant-type`. Both end in
`jwt-bearer` because RFC 7521 defines one framework for assertions with these two uses; RFC 7523
profiles it for JWTs and RFC 7522 for SAML.

**What RFC 7523 says about using them together (spec).** §1, in the same words as RFC 7521's note
for assertions in general:

> The use of a security token for client authentication is orthogonal to and separable from using
> a security token as an authorization grant. They can be used either in combination or
> separately. Client authentication using a JWT is nothing more than an alternative way for a
> client to authenticate to the token endpoint and must be used in conjunction with some grant
> type to form a complete and meaningful protocol request. JWT authorization grants may be used
> with or without client authentication or identification.

That gives one rule for each half:

- **The client assertion needs some grant, but any grant will do.** It "must be used in
  conjunction with some grant type", because on its own it's only "an alternative way for a client
  to authenticate". The RFC's own example (§2.2) pairs it with `authorization_code`, not with the
  `jwt-bearer` grant:

  ```text
  grant_type=authorization_code&
  code=n0esc3NRze7LTCu7iYzS6a5acc3f0ogp4&
  client_assertion_type=urn%3Aietf%3Aparams%3Aoauth%3Aclient-assertion-type%3Ajwt-bearer&
  client_assertion=eyJhbGciOiJSUzI1NiIsImtpZCI6IjIyIn0...
  ```

- **The `jwt-bearer` grant doesn't need client authentication at all.** §2.1: "Authentication of
  the client is optional … consequently, the `client_id` is only needed when a form of client
  authentication that relies on the parameter is used." Its example (§2.1) is just `grant_type`
  and `assertion`, with no client credentials:

  ```text
  grant_type=urn%3Aietf%3Aparams%3Aoauth%3Agrant-type%3Ajwt-bearer
  &assertion=eyJhbGciOiJFUzI1NiIsImtpZCI6IjE2In0...
  ```

The RFC leaves the rest to the server (§3.1):

> Whether or not client authentication is needed in conjunction with a JWT authorization grant, as
> well as the supported types of client authentication, are policy decisions at the discretion of
> the authorization server. However, if client credentials are present in the request, the
> authorization server MUST validate them.

Keycloak's policy is to require a confidential client for this grant: the public client in §0 was
refused with "Public client not allowed to use authorization grant". The last sentence is why a
wrong credential fails even on a grant where client authentication would be optional.

**They can appear in the same request**, as in this lab run with `client_secret_jwt` (form fields
on separate lines, values cut):

```text
grant_type=urn:ietf:params:oauth:grant-type:jwt-bearer
&assertion=eyJraWQiOiJpZHAtMSIs…        ← the grant: iss https://ext-idp.example.test, sub ext-alice
&client_id=c-secret-jwt
&client_assertion_type=urn:ietf:params:oauth:client-assertion-type:jwt-bearer
&client_assertion=eyJhbGciOiJIUzI1NiJ9…  ← client authentication: iss = sub = c-secret-jwt
```

- Keycloak checked `client_assertion` with the client's authenticator (`client-secret-jwt`), as for
  any other grant.
- It checked `assertion` against the `ext-idp` identity provider's JWKS, and found Alice through her
  federated link `ext-idp` / `ext-alice`.
- The token is Alice's, with `azp: c-secret-jwt`. With the other methods (below) only the client
  authentication part changes; the grant part stays the same.

**Each half without the other (lab).** Either half can be paired with something else, as the RFC
allows (above).

1. Keep the `jwt-bearer` grant, authenticate with `client_secret_post`:

   ```text
   grant_type=urn:ietf:params:oauth:grant-type:jwt-bearer
   &assertion=eyJraWQiOiJpZHAtMSIs…              ← the grant: iss https://ext-idp.example.test, sub ext-alice
   &client_id=c-secret
   &client_secret=secret-for-basic-and-post-…    ← client authentication: the secret in the body
   ```

   The token was Alice's (`preferred_username: alice`), with `azp: c-secret`. In the lab this grant
   also worked with `client_secret_basic`, `private_key_jwt` and `tls_client_auth`; only the
   client-authentication lines change. The one refusal was the public client: Keycloak requires a
   confidential client for this grant. The RFC leaves that to the server: client authentication
   with this grant is "a policy decision at the discretion of the authorization server" (§3.1).

2. Keep the client assertion, use `client_credentials`:

   ```text
   grant_type=client_credentials
   &client_id=c-secret-jwt
   &client_assertion_type=urn:ietf:params:oauth:client-assertion-type:jwt-bearer
   &client_assertion=eyJhbGciOiJIUzI1NiJ9…       ← client authentication: iss = sub = c-secret-jwt
   ```

   The token was the client's own (`preferred_username: service-account-c-secret-jwt`): there's no
   user, because `client_credentials` exchanges nothing but the client's identity. The same
   `client_assertion` worked with every other grant in §0, and the RFC's own example pairs it with
   `authorization_code` (RFC 7523 §2.2).

So the grant decides whose token you get (the user's for `jwt-bearer`, the client's for
`client_credentials`); the client-authentication lines only prove which client is asking.

**Where they get mixed up:** Azure Workload Identity and other federated credentials send a
Kubernetes or GitHub token as the **client assertion**, with `grant_type=client_credentials`. The
token is for the app's identity, not a user. That assertion's `iss` and `sub` are the external
issuer and the ServiceAccount, not the `client_id`. RFC 7523 §3 expects `sub` to be the `client_id`
for client authentication, so Entra's federated credentials extend the rule. The captured request
is in [workload-identity.md](workload-identity.md) ("Step 3 in the SDKs"); federated assertions are
covered in [oauth-client-authentication.md](oauth-client-authentication.md) §5.

The full exchange:

```http
POST /realms/lab/protocol/openid-connect/token HTTP/1.1
Host: localhost:8443
Content-Type: application/x-www-form-urlencoded

grant_type=urn:ietf:params:oauth:grant-type:jwt-bearer&assertion=eyJraWQiOiJpZHAtMSIsInR5cCI6Ik…&client_id=c-secret-jwt&client_assertion_type=urn:ietf:params:oauth:client-assertion-type:jwt-bearer&client_assertion=eyJhbGciOiJIUzI1NiJ9.eyJpc3MiO…
```

```http
HTTP/1.1 200 OK
Cache-Control: no-store
Pragma: no-cache
Content-Type: application/json

{
  "access_token": "eyJhbGciOiJSUzI1NiIsInR5cCIgOi…",
  "expires_in": 600,
  "refresh_expires_in": 0,
  "token_type": "Bearer",
  "not-before-policy": 0,
  "scope": "email profile"
}
```

```json
// client_assertion, decoded: header, then payload
{"alg": "HS256"}
{
  "iss": "c-secret-jwt",
  "sub": "c-secret-jwt",
  "aud": "https://localhost:8443/realms/lab",
  "exp": 1795439588,
  "iat": 1795439528,
  "jti": "1da2b48f-8c83-424d-9849-5689d9e80706"
}
```

```json
// assertion, decoded: header, then payload
{"kid": "idp-1", "typ": "JWT", "alg": "RS256"}
{
  "iss": "https://ext-idp.example.test",
  "sub": "ext-alice",
  "aud": "https://localhost:8443/realms/lab",
  "exp": 1795439648,
  "iat": 1795439528,
  "jti": "9c06f43b-aba0-423c-a14d-5dc04c39b185"
}
```

```json
// the access token's payload (selected claims)
{
  "iss": "https://localhost:8443/realms/lab",
  "sub": "de922e8b-c117-4bb9-bf05-abe7468dff3f",
  "preferred_username": "alice",
  "azp": "c-secret-jwt",
  "aud": "c-secret-jwt",
  "typ": "Bearer",
  "scope": "email profile"
}
```

The token is Alice's (`preferred_username: alice`): Keycloak found her through the federated link
`ext-idp` / `ext-alice`. The client is the one that authenticated.

**client_secret_basic** → `200`, token for `azp: c-secret`

```http
POST /realms/lab/protocol/openid-connect/token HTTP/1.1
Host: localhost:8443
Authorization: Basic Yy1zZWNyZXQ6c2VjcmV0LWZvci1iYXNpYy1hbmQtcG9zdC0wMTIzNDU2Nzg5
Content-Type: application/x-www-form-urlencoded

grant_type=urn:ietf:params:oauth:grant-type:jwt-bearer&assertion=eyJraWQiOiJpZHAtMSIsInR5cCI6Ik…
```

**client_secret_post** → `200`, token for `azp: c-secret`

```http
POST /realms/lab/protocol/openid-connect/token HTTP/1.1
Host: localhost:8443
Content-Type: application/x-www-form-urlencoded

grant_type=urn:ietf:params:oauth:grant-type:jwt-bearer&assertion=eyJraWQiOiJpZHAtMSIsInR5cCI6Ik…&client_id=c-secret&client_secret=secret-for-basic-and-post-0123456789
```

**private_key_jwt** → `200`, token for `azp: c-pkjwt`

```http
POST /realms/lab/protocol/openid-connect/token HTTP/1.1
Host: localhost:8443
Content-Type: application/x-www-form-urlencoded

grant_type=urn:ietf:params:oauth:grant-type:jwt-bearer&assertion=eyJraWQiOiJpZHAtMSIsInR5cCI6Ik…&client_id=c-pkjwt&client_assertion_type=urn:ietf:params:oauth:client-assertion-type:jwt-bearer&client_assertion=eyJraWQiOiJway0xIiwidHlwIjoiSl…
```

**tls_client_auth** (the TLS handshake carries the client certificate) → `200`, token for `azp: c-mtls`

```http
POST /realms/lab/protocol/openid-connect/token HTTP/1.1
Host: localhost:8443
Content-Type: application/x-www-form-urlencoded

grant_type=urn:ietf:params:oauth:grant-type:jwt-bearer&assertion=eyJraWQiOiJpZHAtMSIsInR5cCI6Ik…&client_id=c-mtls
```

**none** → `400` `invalid_grant`: Public client not allowed to use authorization grant

```http
POST /realms/lab/protocol/openid-connect/token HTTP/1.1
Host: localhost:8443
Content-Type: application/x-www-form-urlencoded

grant_type=urn:ietf:params:oauth:grant-type:jwt-bearer&assertion=eyJraWQiOiJpZHAtMSIsInR5cCI6Ik…&client_id=c-public
```

### 4.8 `ciba` (OpenID CIBA Core 1.0)

The client starts the login on the **backchannel authentication endpoint**, which authenticates the
client (CIBA Core §7.1: the client MUST authenticate to it with the method registered for its
`client_id`) **(spec)**:

```http
POST /realms/lab/protocol/openid-connect/ext/ciba/auth HTTP/1.1
Host: localhost:8443
Content-Type: application/x-www-form-urlencoded

client_id=c-secret&client_secret=secret-for-basic-and-post-0123456789&scope=openid&login_hint=alice&binding_message=lab-client_s
```

```http
HTTP/1.1 200 OK
Cache-Control: no-cache
Content-Type: application/json;charset=UTF-8

{
  "auth_req_id": "eyJhbGciOiJkaXIiLCJlbmMiOiJBMj…",
  "expires_in": 120,
  "interval": 1
}
```

Keycloak then POSTs to the authentication channel, the stand-in for Alice's phone:

```json
{
  "path": "/ciba",
  "authorization": "Bearer eyJhbGciOiJSUzI1NiIsInR5cCIgOiAiS…",
  "body": {
    "scope": "openid roles profile email",
    "binding_message": "fapi",
    "login_hint": "alice",
    "is_consent_required": false
  }
}
```

The stand-in answers through `POST /realms/lab/protocol/openid-connect/ext/ciba/auth/callback` with
that bearer token and `{"status":"SUCCEED"}`. The client polls the token endpoint:

```http
POST /realms/lab/protocol/openid-connect/token HTTP/1.1
Host: localhost:8443
Content-Type: application/x-www-form-urlencoded

grant_type=urn:openid:params:grant-type:ciba&auth_req_id=eyJhbGciOiJkaXIiLCJlbmMiOiJBMj…&client_id=c-secret&client_secret=secret-for-basic-and-post-0123456789
```

```http
HTTP/1.1 200 OK
Cache-Control: no-store
Pragma: no-cache
Content-Type: application/json

{
  "access_token": "eyJhbGciOiJSUzI1NiIsInR5cCIgOi…",
  "expires_in": 600,
  "refresh_expires_in": 1800,
  "refresh_token": "eyJhbGciOiJIUzUxMiIsInR5cCIgOi…",
  "token_type": "Bearer",
  "id_token": "eyJhbGciOiJSUzI1NiIsInR5cCIgOi…",
  "not-before-policy": 0,
  "session_state": "WYYprWytxfIaBsz1j3fejWBI",
  "scope": "openid email profile"
}
```

**client_secret_basic** → `200`, token for `azp: c-secret`

```http
POST /realms/lab/protocol/openid-connect/token HTTP/1.1
Host: localhost:8443
Authorization: Basic Yy1zZWNyZXQ6c2VjcmV0LWZvci1iYXNpYy1hbmQtcG9zdC0wMTIzNDU2Nzg5
Content-Type: application/x-www-form-urlencoded

grant_type=urn:openid:params:grant-type:ciba&auth_req_id=eyJhbGciOiJkaXIiLCJlbmMiOiJBMj…
```

**client_secret_jwt** → `200`, token for `azp: c-secret-jwt`

```http
POST /realms/lab/protocol/openid-connect/token HTTP/1.1
Host: localhost:8443
Content-Type: application/x-www-form-urlencoded

grant_type=urn:openid:params:grant-type:ciba&auth_req_id=eyJhbGciOiJkaXIiLCJlbmMiOiJBMj…&client_id=c-secret-jwt&client_assertion_type=urn:ietf:params:oauth:client-assertion-type:jwt-bearer&client_assertion=eyJhbGciOiJIUzI1NiJ9.eyJpc3MiO…
```

**private_key_jwt** → `200`, token for `azp: c-pkjwt`

```http
POST /realms/lab/protocol/openid-connect/token HTTP/1.1
Host: localhost:8443
Content-Type: application/x-www-form-urlencoded

grant_type=urn:openid:params:grant-type:ciba&auth_req_id=eyJhbGciOiJkaXIiLCJlbmMiOiJBMj…&client_id=c-pkjwt&client_assertion_type=urn:ietf:params:oauth:client-assertion-type:jwt-bearer&client_assertion=eyJraWQiOiJway0xIiwidHlwIjoiSl…
```

**tls_client_auth** (the TLS handshake carries the client certificate) → `200`, token for `azp: c-mtls`

```http
POST /realms/lab/protocol/openid-connect/token HTTP/1.1
Host: localhost:8443
Content-Type: application/x-www-form-urlencoded

grant_type=urn:openid:params:grant-type:ciba&auth_req_id=eyJhbGciOiJkaXIiLCJlbmMiOiJBMj…&client_id=c-mtls
```

### 4.9 `uma-ticket` (UMA 2.0 Grant)

Without a bearer token, Keycloak authenticates the client and gets a requesting party token (RPT)
for the client's own service account (`PermissionGrantType`: "in case no bearer token is provided,
we force client authentication") **(code)**:

```http
POST /realms/lab/protocol/openid-connect/token HTTP/1.1
Host: localhost:8443
Authorization: Basic Yy1zZWNyZXQ6c2VjcmV0LWZvci1iYXNpYy1hbmQtcG9zdC0wMTIzNDU2Nzg5
Content-Type: application/x-www-form-urlencoded

grant_type=urn:ietf:params:oauth:grant-type:uma-ticket&audience=api
```

```http
HTTP/1.1 200 OK
Cache-Control: no-store
Pragma: no-cache
Content-Type: application/json

{
  "upgraded": false,
  "access_token": "eyJhbGciOiJSUzI1NiIsInR5cCIgOi…",
  "expires_in": 600,
  "refresh_expires_in": 0,
  "token_type": "Bearer",
  "not-before-policy": 0
}
```

```json
// the access token's payload (selected claims)
{
  "iss": "https://localhost:8443/realms/lab",
  "sub": "a30eb176-79dd-4a6a-b88e-cf4e2ace26e4",
  "preferred_username": "service-account-c-secret",
  "azp": "c-secret",
  "aud": "api",
  "typ": "Bearer",
  "scope": "email profile",
  "authorization": {
    "permissions": [
      {
        "scopes": [
          "read"
        ],
        "rsid": "da90a354-7a57-4477-a895-7f6fd7ba770f",
        "rsname": "orders"
      }
    ]
  }
}
```

`authorization.permissions` lists the resource `orders` on `api` (granted because the resource
server is `PERMISSIVE`).

**client_secret_post** → `200`, token for `azp: c-secret`

```http
POST /realms/lab/protocol/openid-connect/token HTTP/1.1
Host: localhost:8443
Content-Type: application/x-www-form-urlencoded

grant_type=urn:ietf:params:oauth:grant-type:uma-ticket&audience=api&client_id=c-secret&client_secret=secret-for-basic-and-post-0123456789
```

**client_secret_jwt** → `200`, token for `azp: c-secret-jwt`

```http
POST /realms/lab/protocol/openid-connect/token HTTP/1.1
Host: localhost:8443
Content-Type: application/x-www-form-urlencoded

grant_type=urn:ietf:params:oauth:grant-type:uma-ticket&audience=api&client_id=c-secret-jwt&client_assertion_type=urn:ietf:params:oauth:client-assertion-type:jwt-bearer&client_assertion=eyJhbGciOiJIUzI1NiJ9.eyJpc3MiO…
```

**private_key_jwt** → `200`, token for `azp: c-pkjwt`

```http
POST /realms/lab/protocol/openid-connect/token HTTP/1.1
Host: localhost:8443
Content-Type: application/x-www-form-urlencoded

grant_type=urn:ietf:params:oauth:grant-type:uma-ticket&audience=api&client_id=c-pkjwt&client_assertion_type=urn:ietf:params:oauth:client-assertion-type:jwt-bearer&client_assertion=eyJraWQiOiJway0xIiwidHlwIjoiSl…
```

**tls_client_auth** (the TLS handshake carries the client certificate) → `200`, token for `azp: c-mtls`

```http
POST /realms/lab/protocol/openid-connect/token HTTP/1.1
Host: localhost:8443
Content-Type: application/x-www-form-urlencoded

grant_type=urn:ietf:params:oauth:grant-type:uma-ticket&audience=api&client_id=c-mtls
```

### 4.10 `uma-ticket` with a bearer token: no client authentication (lab + code)

With a user's access token in `Authorization`, Keycloak skips client authentication. The
`TokenEndpoint` condition excludes `UMA_GRANT_TYPE`, and `PermissionGrantType` takes the client
from the token's `azp`:

```http
POST /realms/lab/protocol/openid-connect/token HTTP/1.1
Host: localhost:8443
Authorization: Bearer eyJhbGciOiJSUzI1NiIsInR5cCIgOi…
Content-Type: application/x-www-form-urlencoded

grant_type=urn:ietf:params:oauth:grant-type:uma-ticket&audience=api
```

```http
HTTP/1.1 200 OK
Cache-Control: no-store
Pragma: no-cache
Content-Type: application/json

{
  "upgraded": false,
  "access_token": "eyJhbGciOiJSUzI1NiIsInR5cCIgOi…",
  "expires_in": 600,
  "refresh_expires_in": 1800,
  "refresh_token": "eyJhbGciOiJIUzUxMiIsInR5cCIgOi…",
  "token_type": "Bearer",
  "not-before-policy": 0
}
```

→ `200`, token for `azp: c-secret`: no
client credential in the request. The `Authorization` header can carry Basic credentials or a bearer
token, not both, so `client_secret_basic` can't be used in this form. A client that wants to
authenticate *and* act for a user sends the user's token as `subject_token` (or `claim_token`) in
the form instead (`PermissionGrantType`) **(code)**.

### 4.11 `pre-authorized_code` (OpenID4VCI 1.0)

Normally a wallet gets the code from a credential offer (QR code or link) and redeems it. No offer
was created here, so every request uses `pre-authorized_code=not-a-real-code`. What the responses
show is the **order** of the checks:

```http
POST /realms/lab/protocol/openid-connect/token HTTP/1.1
Host: localhost:8443
Content-Type: application/x-www-form-urlencoded

grant_type=urn:ietf:params:oauth:grant-type:pre-authorized_code&pre-authorized_code=not-a-real-code&client_id=c-secret&client_secret=secret-for-basic-and-post-0123456789
```

```http
HTTP/1.1 400 Bad Request
Cache-Control: no-store
Pragma: no-cache
Content-Type: application/json

{
  "error": "invalid_request",
  "error_description": "Pre-authorized code failed handler verification (invalid_code)"
}
```

**client_secret_basic** → `400` `invalid_request`: Pre-authorized code failed handler verification (invalid_code)

```http
POST /realms/lab/protocol/openid-connect/token HTTP/1.1
Host: localhost:8443
Authorization: Basic Yy1zZWNyZXQ6c2VjcmV0LWZvci1iYXNpYy1hbmQtcG9zdC0wMTIzNDU2Nzg5
Content-Type: application/x-www-form-urlencoded

grant_type=urn:ietf:params:oauth:grant-type:pre-authorized_code&pre-authorized_code=not-a-real-code
```

**client_secret_jwt** → `400` `invalid_request`: Pre-authorized code failed handler verification (invalid_code)

```http
POST /realms/lab/protocol/openid-connect/token HTTP/1.1
Host: localhost:8443
Content-Type: application/x-www-form-urlencoded

grant_type=urn:ietf:params:oauth:grant-type:pre-authorized_code&pre-authorized_code=not-a-real-code&client_id=c-secret-jwt&client_assertion_type=urn:ietf:params:oauth:client-assertion-type:jwt-bearer&client_assertion=eyJhbGciOiJIUzI1NiJ9.eyJpc3MiO…
```

**private_key_jwt** → `400` `invalid_request`: Pre-authorized code failed handler verification (invalid_code)

```http
POST /realms/lab/protocol/openid-connect/token HTTP/1.1
Host: localhost:8443
Content-Type: application/x-www-form-urlencoded

grant_type=urn:ietf:params:oauth:grant-type:pre-authorized_code&pre-authorized_code=not-a-real-code&client_id=c-pkjwt&client_assertion_type=urn:ietf:params:oauth:client-assertion-type:jwt-bearer&client_assertion=eyJraWQiOiJway0xIiwidHlwIjoiSl…
```

**tls_client_auth** (the TLS handshake carries the client certificate) → `400` `invalid_request`: Pre-authorized code failed handler verification (invalid_code)

```http
POST /realms/lab/protocol/openid-connect/token HTTP/1.1
Host: localhost:8443
Content-Type: application/x-www-form-urlencoded

grant_type=urn:ietf:params:oauth:grant-type:pre-authorized_code&pre-authorized_code=not-a-real-code&client_id=c-mtls
```

**none** → `400` `invalid_request`: Pre-authorized code failed handler verification (invalid_code)

```http
POST /realms/lab/protocol/openid-connect/token HTTP/1.1
Host: localhost:8443
Content-Type: application/x-www-form-urlencoded

grant_type=urn:ietf:params:oauth:grant-type:pre-authorized_code&pre-authorized_code=not-a-real-code&client_id=c-public
```

The same request with a **wrong** secret:

```http
POST /realms/lab/protocol/openid-connect/token HTTP/1.1
Host: localhost:8443
Authorization: Basic Yy1zZWNyZXQ6d3Jvbmc=
Content-Type: application/x-www-form-urlencoded

grant_type=urn:ietf:params:oauth:grant-type:pre-authorized_code&pre-authorized_code=not-a-real-code
```

```http
HTTP/1.1 401 Unauthorized
Cache-Control: no-store
Pragma: no-cache
Content-Type: application/json

{
  "error": "unauthorized_client",
  "error_description": "Invalid client or Invalid client credentials"
}
```

With the right credential, every method reached the code check (`invalid_code`). With a wrong
secret, the request failed **before** it, with `invalid_client`. So in this build client
authentication runs first, as for the other grants. In the 1 October source the endpoint skipped
`checkClient()` for this grant ("pre-authorized grants are not necessarily used by known clients"),
and the commit of 2 November added the check, plus a refusal when the offer was issued to another
client:

```java
// Checked before the code is marked as used, so that another client cannot invalidate the offer
if (offerState.getTargetClientId() != null && !offerState.getTargetClientId().equals(client.getClientId())) {
    var errorMessage = "Credential offer was not issued to client: " + client.getClientId();
```

With OID4VCI disabled for the realm (`verifiableCredentialsEnabled: false`), the grant answered
`403 invalid_client` "OID4VCI functionality is disabled for this realm", after client
authentication (an earlier run).

### 4.12 An extension grant Keycloak doesn't have: `saml2-bearer` (RFC 7522)

```http
POST /realms/lab/protocol/openid-connect/token HTTP/1.1
Host: localhost:8443
Content-Type: application/x-www-form-urlencoded

grant_type=urn:ietf:params:oauth:grant-type:saml2-bearer&assertion=PHNhbWw+&client_id=c-secret&client_secret=secret-for-basic-and-post-0123456789
```

```http
HTTP/1.1 400 Bad Request
Cache-Control: no-store
Pragma: no-cache
Content-Type: application/json

{
  "error": "unsupported_grant_type",
  "error_description": "Unsupported grant_type"
}
```

The grant is checked (`checkGrantType`) before client authentication, so an unknown grant fails
the same way whatever the method.

---

## 5. Implicit: no token endpoint, no client authentication (lab)

`response_type=token` on the **authorization endpoint**, for the public client `c-public`
(`implicitFlowEnabled: true`). After the login form, Keycloak redirects with the token in the URL
**fragment**:

```http
HTTP/1.1 302 Found
Cache-Control: no-store, must-revalidate, max-age=0
Location: https://app.local/cb#state=st&session_state=hQV0gwVOu3V5YA4MoQ28ZRGQ&iss=https%3A%2F%2Flocalhost%3A8443%2Frealms%2Flab&access_token=eyJhbGciOiJSUzI1NiIsInR5cCIg…
```

There's no token request, so there's nothing to put a client credential in. Any client could get
this token by using `c-public`'s `client_id` with a registered redirect URI. The token goes
through the browser (history, `Referer`, extensions). OAuth 2.1 removes the implicit grant (and the
password grant) for these reasons; the authorization code grant with PKCE replaces it, and its code
redemption is a token request that can be authenticated (§4.1) **(spec)**.

---

## 6. What "orthogonal" doesn't mean

- **Not every client may use every grant.** A grant can require a *kind* of client: confidential
  for `client_credentials` (it needs a service account), `jwt-bearer` and CIBA in Keycloak. That's
  a rule about the client, the same for all five methods (§0).
- **Each method needs the client registered for it.** The server decides which method a client
  must use (`token_endpoint_auth_method` in dynamic registration, `clientAuthenticatorType` in
  Keycloak). A client registered for `private_key_jwt` can't switch to a secret request by
  request. Orthogonal means *any method with any grant*, not *any method from any client*.
- **Public clients aren't authenticated by any method.** `client_id` alone identifies them; for the
  code grant, PKCE stands in for the missing credential.
- **The same authentication protects the other client endpoints:** device authorization (§4.5),
  CIBA backchannel authentication (§4.8), PAR, introspection, revocation.

---

## 7. FAPI 2.0: the approved combinations

FAPI 2.0 Security Profile (OpenID Foundation, **Final, 22 February 2025**) is a profile for
high-security APIs (open banking, health, government). Where plain OAuth lets any method go with
any grant, FAPI 2.0 narrows both sides.

### 7.1 What the profile requires (spec)

From §5.3.2.1 (authorization servers), §5.3.2.2 (authorization endpoint flows), §5.3.3 (clients) and
§5.4 (cryptography):

| Area | FAPI 2.0 requirement |
|---|---|
| Clients | "shall only support confidential clients" |
| Client authentication | "shall authenticate clients using one of the following methods: MTLS as specified in Section 2 of [RFC8705], or `private_key_jwt` as specified in Section 9 of [OIDC]" |
| Client assertions | "shall only accept its issuer identifier value … as a string in the `aud` claim" |
| Signing algorithms | "use PS256, ES256, or EdDSA (using the Ed25519 variant)"; "not use or accept the `none` algorithm"; RSA keys at least 2048 bits |
| Access tokens | "shall only issue sender-constrained access tokens", by MTLS (RFC 8705) or DPoP (RFC 9449) |
| Password grant | "shall reject requests using the resource owner password credentials grant" |
| Authorization endpoint | `response_type` "to be `code`"; "shall reject authorization requests sent without [RFC9126]" (PAR); "shall reject pushed authorization requests without client authentication"; PKCE "with S256"; return `iss` in the authorization response (RFC 9207) |
| Codes | "maximum lifetime of 60 seconds" |
| Refresh tokens | clients "shall support refresh tokens"; servers "shall not use refresh token rotation except in extraordinary circumstances" |
| Other grants | Note 2: "This document is structured to support a variety of grants … For example the client credentials grant or the CIBA grant. … only the authorization code flow and CIBA flows have been through a detailed security analysis" |

So the approved combinations are:

| Client authentication | Allowed |
|---|---|
| `tls_client_auth` (RFC 8705 §2.1, PKI) and `self_signed_tls_client_auth` (§2.2) | ✓ ("MTLS as specified in Section 2") |
| `private_key_jwt` | ✓, signed PS256, ES256 or EdDSA (not RS256), `aud` = the issuer as a string |
| `client_secret_basic`, `client_secret_post`, `client_secret_jwt` | ✗ |
| `none` (public clients) | ✗ |

| Grant | Under FAPI 2.0 |
|---|---|
| `authorization_code` | ✓ the main flow: PAR (client-authenticated) + PKCE S256 + `response_type=code`, `iss` checked by the client |
| `refresh_token` | ✓ (no rotation) |
| `urn:openid:params:grant-type:ciba` | ✓ named, and security-analysed (with the separate FAPI-CIBA profile for its details) |
| `client_credentials` | ✓ named in Note 2 |
| `device_code`, `token-exchange`, `jwt-bearer`, `uma-ticket`, `pre-authorized_code` | not named: allowed only within the general requirements (confidential client, approved authentication, sender-constrained tokens), and not covered by the security analysis |
| `password` | ✗ "shall reject" |
| implicit | ✗ (`response_type` must be `code`) |

Every allowed grant is still orthogonal to the two allowed methods: FAPI 2.0 removes methods and
grants from the table, it doesn't tie a method to a grant.

### 7.2 Keycloak's `fapi-2-security-profile` (code)

Keycloak implements FAPI 2.0 as a built-in **client profile** (`keycloak-default-client-profiles.json`),
switched on with a **client policy** whose condition selects the clients (here `any-client`):

```json
{"policies": [{"name": "fapi2-everyone", "enabled": true,
  "conditions": [{"condition": "any-client", "configuration": {}}],
  "profiles": ["fapi-2-security-profile"]}]}
```

The profile's executors (`PUT /admin/realms/lab/client-policies/policies` with the body above):

```text
confidential-client
secure-client-authenticator              {"allowed-client-authenticators": ["client-jwt", "client-x509"], "default-client-authenticator": "client-jwt"}
secure-client-uris
secure-signature-algorithm               {"default-algorithm": "PS256"}
secure-signature-algorithm-signed-jwt    {"require-client-assertion": false}
consent-required                         {"auto-configure": true}
full-scope-disabled                      {"auto-configure": true}
reject-implicit-grant                    {"auto-configure": "true"}
holder-of-key-enforcer                   {"auto-configure": "true"}
pkce-enforcer                            {"auto-configure": "true"}
secure-client-authentication-assertion
secure-par-content
```

`fapi-2-dpop-security-profile` is the same with `dpop-bind-enforcer` in place of
`holder-of-key-enforcer` (DPoP instead of MTLS-bound tokens). **There is no `reject-ropc-grant`
executor in any of the four FAPI 2.0 profiles**; the executor exists, but only the two OAuth 2.1
profiles use it.

### 7.3 The lab: registration (lab)

The policy was switched on with the clients from §2 already there. Updating each one (a plain
`PUT` of its own representation) and registering new ones:

| Admin API call | Result |
|---|---|
| update c-secret (client-secret) | `400` `invalid_client_metadata`: Invalid client metadata: token_endpoint_auth_method |
| update c-secret-jwt (client-secret-jwt) | `400` `invalid_client_metadata`: Invalid client metadata: token_endpoint_auth_method |
| update c-pkjwt (client-jwt) | `204`; now `tls.client.certificate.bound.access.tokens=true`, `pkce.code.challenge.method=S256`, `consentRequired=True` |
| update c-mtls (client-x509) | `204`; now `tls.client.certificate.bound.access.tokens=true`, `pkce.code.challenge.method=S256`, `consentRequired=True` |
| register new-secret (client-secret) | `400` `invalid_client_metadata`: Invalid client metadata: token_endpoint_auth_method |
| register new-secret-jwt (client-secret-jwt) | `400` `invalid_client_metadata`: Invalid client metadata: token_endpoint_auth_method |
| register new-pkjwt-rs256 (client-jwt, RS256) | `400` `invalid_request`: not allowed signature algorithm. |
| register new-pkjwt-ps256 (client-jwt, PS256) | `201` |
| register new-x509 (client-x509) | `201` |

- The secret methods can't be registered or kept: "Invalid client metadata:
  token_endpoint_auth_method".
- `private_key_jwt` needs a FAPI algorithm: RS256 was refused ("not allowed signature
  algorithm."), PS256 accepted. `c-pkjwt` got a new PS256 key, made with nimbus-jose-jwt
  (`new RSAKeyGenerator(2048).keyID("pk-ps256").algorithm(JWSAlgorithm.PS256)`), and its assertions
  are signed PS256.
- The update **auto-configured** the conformant clients: certificate-bound tokens, PKCE S256,
  consent required. `c-secret` and `c-secret-jwt` stay as they were: **legacy clients** that the
  policy can't update.

### 7.4 The lab: every grant under the policy (lab)

All requests present the client certificate in the TLS handshake (needed for certificate-bound
tokens). "bound" means the access token has `cnf.x5t#S256`, the SHA-256 thumbprint of that
certificate (RFC 8705 §3).

| grant | `private_key_jwt` (PS256) | `tls_client_auth` | legacy `client_secret_basic` | legacy `client_secret_post` | legacy `client_secret_jwt` |
|---|---|---|---|---|---|
| `authorization_code` | ✓ 200, bound | ✓ 200, bound | ✗ 400 method not allowed | ✗ 400 method not allowed | ✗ 400 method not allowed |
| `refresh_token` | ✓ 200, bound | ✓ 200, bound | — | — | — |
| `client_credentials` | ✓ 200, bound | ✓ 200, bound | ✗ 400 method not allowed | ✗ 400 method not allowed | ✗ 400 method not allowed |
| `ciba` | ✓ 200, bound | ✓ 200, bound | ✓ 200, **not bound** | ✓ 200, **not bound** | ✓ 200, **not bound** |
| `device_code` | ✓ 200, bound | ✓ 200, bound | ✓ 200, **not bound** | ✓ 200, **not bound** | ✓ 200, **not bound** |
| `token-exchange` | ✓ 200, bound | ✓ 200, bound | ✓ 200, **not bound** | ✓ 200, **not bound** | ✓ 200, **not bound** |
| `jwt-bearer` | ✓ 200, bound | ✓ 200, bound | ✓ 200, **not bound** | ✓ 200, **not bound** | ✓ 200, **not bound** |
| `uma-ticket` | ✓ 200, **not bound** | ✓ 200, **not bound** | ✗ 400 method not allowed | ✗ 400 method not allowed | ✗ 400 method not allowed |
| `password` | ✗ 400 consent required | ✗ 400 consent required | ✓ 200, **not bound** | ✓ 200, **not bound** | ✓ 200, **not bound** |

"method not allowed" is "Configured client authentication method not allowed for client"; "—" for
the legacy clients' `refresh_token` because their code redemption failed, so they had no refresh
token to use.

For the two conformant clients, every grant FAPI 2.0 names worked, and every token was bound to the
certificate. The exceptions:

- **`password`** was refused ("Client requires user consent"), but only because the profile
  switched on consent and the password grant can't ask for it. Nothing in the profile rejects the
  grant as such.
- **`uma-ticket`**: the RPT was issued **without** `cnf`.

For the **legacy** secret clients the policy is applied per event, not per client:
`authorization_code`, `refresh_token`, `client_credentials` and `uma-ticket` refused them
("Configured client authentication method not allowed for client"), while `password`,
`token-exchange`, `jwt-bearer`, CIBA and the device grant issued them **unbound** tokens. A client
policy doesn't make an existing realm FAPI-conformant; clients that fail the update have to be
re-created or migrated.

### 7.5 The authorization code flow under FAPI 2.0 (lab)

**Without PAR**, the classic `GET /auth?client_id=…&response_type=code&…` is refused before login:
`400`, page message "PAR request_uri not included." (implicit, `response_type=token`, got the same
message; PAR is checked first).

**With PAR**, for `c-pkjwt` (`private_key_jwt`, PS256). The client pushes the whole authorization
request to the server, authenticated like a token request:

```http
POST /realms/lab/protocol/openid-connect/ext/par/request HTTP/1.1
Host: localhost:8443
Content-Type: application/x-www-form-urlencoded

client_id=c-pkjwt&client_assertion_type=urn:ietf:params:oauth:client-assertion-type:jwt-bearer&client_assertion=eyJraWQiOiJway1wczI1NiIsInR5cC…&response_type=code&redirect_uri=https://app.local/cb&scope=openid&state=st&code_challenge=Ccj4kcsy-k29sVULq9Ja8AnpF3Tw6gekJRvYEb8Q92A&code_challenge_method=S256
```

```http
HTTP/1.1 201 Created
Content-Type: application/json

{
  "request_uri": "urn:ietf:params:oauth:request_uri:901fec43-5f3f-5569-6b96-04aefc5f3aa1",
  "expires_in": 60
}
```

```json
// client_assertion, decoded: header, then payload
{"kid": "pk-ps256", "typ": "JWT", "alg": "PS256"}
{
  "iss": "c-pkjwt",
  "sub": "c-pkjwt",
  "aud": "https://localhost:8443/realms/lab",
  "exp": 1795442513,
  "iat": 1795442453,
  "jti": "858e0840-b4bd-4738-b421-1f49570d0b9a"
}
```

The browser then gets only `client_id` and `request_uri`: `GET /auth?client_id=c-pkjwt&request_uri=urn:ietf:params:oauth:request_uri:…`.
After login and consent the redirect carries `code`, `state`, `session_state` and **`iss`**
(RFC 9207, so the client can detect a mix-up between authorization servers). The code is redeemed
with the PKCE verifier and the client certificate:

```http
POST /realms/lab/protocol/openid-connect/token HTTP/1.1
Host: localhost:8443
Content-Type: application/x-www-form-urlencoded

grant_type=authorization_code&code=1084afa3-b467-a514-7cf5-a4f73c…&redirect_uri=https://app.local/cb&code_verifier=vpc1AlCC2kAEaJHqiE3Xchxuhl6RCTcSlH1tvBgAIvc&client_id=c-pkjwt&client_assertion_type=urn:ietf:params:oauth:client-assertion-type:jwt-bearer&client_assertion=eyJraWQiOiJway1wczI1NiIsInR5cC…
```

```http
HTTP/1.1 200 OK
Cache-Control: no-store
Pragma: no-cache
Content-Type: application/json

{
  "access_token": "eyJhbGciOiJQUzI1NiIsInR5cCIgOi…",
  "expires_in": 600,
  "refresh_expires_in": 1800,
  "refresh_token": "eyJhbGciOiJIUzUxMiIsInR5cCIgOi…",
  "token_type": "Bearer",
  "id_token": "eyJhbGciOiJQUzI1NiIsInR5cCIgOi…",
  "not-before-policy": 0,
  "session_state": "foLAiBVDvlI6JTSOiNZqIg-d",
  "scope": "openid email profile"
}
```

```json
// the access token's payload (selected claims)
{
  "azp": "c-pkjwt",
  "preferred_username": "alice",
  "cnf": {
    "x5t#S256": "ZxNy0xviR0yOI8Fcscuj2Zn2JPMgesCX8J0ir-nd8g0"
  }
}
```

`cnf.x5t#S256` binds the token to the TLS certificate the client presented: a resource server that
checks it (RFC 8705 §3) refuses the token from any connection without that certificate. Here the
client authenticated with `private_key_jwt` and bound the token with MTLS: two separate uses of
TLS, as FAPI 2.0 allows.

A legacy client in the same flow got through PAR (`201`) and the login, and was refused at the
token endpoint:

```http
POST /realms/lab/protocol/openid-connect/token HTTP/1.1
Host: localhost:8443
Authorization: Basic Yy1zZWNyZXQ6c2VjcmV0LWZvci1iYXNpYy1hbmQtcG9zdC0wMTIzNDU2Nzg5
Content-Type: application/x-www-form-urlencoded

grant_type=authorization_code&code=25172ab1-dc5c-4c4e-4bc0-a03d92…&redirect_uri=https://app.local/cb&code_verifier=WQakDv0aLelsWVml7V68Xt-9FwMtBzjemj4hWAAqBcM
```

```http
HTTP/1.1 400 Bad Request
Cache-Control: no-store
Pragma: no-cache
Content-Type: application/json

{
  "error": "invalid_grant",
  "error_description": "Configured client authentication method not allowed for client"
}
```

**A Keycloak detail found here:** `secure-par-content` requires every query parameter on the
authorization request (except `request_uri`) to have been in the PAR body. With
`client_secret_basic`, `client_id` travels in the `Authorization` header, so it has to be repeated
in the PAR body; otherwise the authorization request fails with "PAR request did not include query
parameter".

## Related notes

- [oauth-client-authentication.md](oauth-client-authentication.md): each authentication method in detail, and binding tokens to keys
- [keycloak-on-kubernetes.md](keycloak-on-kubernetes.md): running Keycloak
- [azure-identity-flows.md](azure-identity-flows.md): the same grants (code, client credentials, on-behalf-of) in Entra ID
- [irsa-walkthrough.md](irsa-walkthrough.md): an RFC 7523-style exchange outside OAuth's token endpoint (STS `AssumeRoleWithWebIdentity`)
