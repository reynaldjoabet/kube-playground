# Azure identity and access: users, apps, and who grants what (lab)

Who signs in, who gets a token, who grants a permission to whom, and what Azure checks when a call
arrives. Each flow is run with the Azure SDK for Java against floci-az, a local Azure emulator, and
shown request by request. Labels:

- **(lab)**: run here, output copied as printed;
- **(code)**: read in the SDK source of the version named;
- **(docs)**: Microsoft's documentation, not testable in floci-az;
- **(not tested)**: an inference.

The objects themselves (users, app registrations, service principals, managed identities, the three
permission systems) are described in [azure.md §4](azure.md#4-identity). Earlier floci-az checks are
in [azure-local-lab.md](azure-local-lab.md) §1, §5, §10–12. This file is about the flows between
them.

---

## 0. The short answers

**"Does a user sign in and then give a service principal permission, with the user's details, to
make the calls?"** No. Those are two separate things, and neither hands the user's details to the
service principal:

1. **A user signing in through an app gets a token for the user** (§3). The token's `oid` is the
   user, `scp` lists what the app may do *for* the user, and `azp` names the app. Calls made with it
   are the user's calls: Azure checks the **user's** role assignments (§5). The app never sees the
   password; with the authorization code flow it only ever holds the user's tokens.
2. **Granting is a separate API call by someone allowed to grant** (§4). An admin (here Alice) calls
   ARM with **her own** token and creates a role assignment: *this principal* (the service
   principal's object id) gets *this role* at *this scope*. ARM records who did it (`createdBy` =
   Alice's `oid`). Nothing about Alice goes to the service principal.
3. **After that the service principal signs in as itself** (§6) with its own secret, certificate or
   federated token. Its token's `oid` is the service principal, there is no user in it, and Azure
   checks the **service principal's** role assignments. The admin who granted the role isn't
   involved any more.

The one flow where an app passes a user's identity on is **on-behalf-of** (§8): an API that got a
user's token swaps it for a token to call the next API, still as that user, not as itself.

**"What happens when an app uses a service principal?"** The app proves it is the app (client ID +
secret, certificate, or federated assertion; or the platform for a managed identity), Entra ID
returns a token whose `oid` is the service principal's object id and whose `idtyp` is `app`, and
every Azure service decides access by looking up role assignments for that `oid` at the target's
scope (§6, §7, §10).

```text
 ┌─────────────────┐  ┌────────────┐      ┌──────────┐    ┌────────────────┐   ┌───────────────┐
 │ Alice (browser) │  │ orders-web │      │ Entra ID │    │ ARM, Key Vault │   │ orders-worker │
 └───────┬─────────┘  └─────┬──────┘      └────┬─────┘    └───────┬────────┘   └──────┬────────┘
         │                  │                  │                  │                   │
         │ 1 sign in        │                  │                  │                   │
         │─────────────────►│                  │                  │                   │
         │                  │                  │                  │                   │
         │                  │ 2 code+secret    │                  │                   │
         │                  │─────────────────►│                  │                   │
         │                  │                  │                  │                   │
         │                  │ 3 Alice's token  │                  │                   │
         │                  │◄─────────────────│                  │                   │
         │                  │                  │                  │                   │
         │                  │ 4 assign role    │                  │                   │
         │                  │──────────────────┼─────────────────►│                   │
         │                  │                  │                  │                   │
         │                  │                  │                  │ 5 stored:         │
         │                  │                  │                  │   principal =     │
         │                  │                  │                  │   worker oid,     │
         │                  │                  │                  │   createdBy =     │
         │                  │                  │                  │   Alice oid       │
         │                  │                  │                  │                   │
         │                  │                  │ 6 id+secret      │                   │
         │                  │                  │◄─────────────────┼───────────────────│
         │                  │                  │                  │                   │
         │                  │                  │ 7 worker token   │                   │
         │                  │                  │──────────────────┼──────────────────►│
         │                  │                  │                  │                   │
         │                  │                  │                  │ 8 GET secret      │
         │                  │                  │                  │◄──────────────────│
         │                  │                  │                  │                   │
         │                  │                  │                  │ 9 roles of        │
         │                  │                  │                  │   token oid?      │
         │                  │                  │                  │                   │
```

| # | What | Whose token | Section |
|---|---|---|---|
| 1–3 | Alice signs in through `orders-web`; the app redeems the code with its secret and gets **Alice's** token | Alice's (`oid` = Alice, `azp` = orders-web) | §3 |
| 4–5 | `orders-web`, as Alice, creates a role assignment for `orders-worker`'s service principal | Alice's | §4 |
| 6–7 | `orders-worker` signs in as itself | the worker's (`oid` = its service principal, `idtyp: app`) | §6 |
| 8–9 | `orders-worker` reads a secret; Key Vault checks the role assignments of the token's `oid` | the worker's | §6, §10 |

---

## 1. The cast

| Who | Kind | Ids in the lab | Credential |
|---|---|---|---|
| Alice | user, the admin | object id `11111111-aaaa-…-0001`, `alice@contoso.test` | password |
| Bob | user, member of `orders-readers` | object id `22222222-bbbb-…-0002`, `bob@contoso.test` | password |
| `orders-readers` | security group | `cccccccc-…-000c` | — |
| `orders-web` | app registration + service principal: a web app that signs users in | appId (client id) `a1111111-…-000a` | a client secret |
| `orders-worker` | app registration + service principal: a daemon that acts as itself | appId `b1111111-…-000b`; its tokens' `oid` `b0000000-…-000b` | a client secret |
| `orders-id` | user-assigned managed identity | created in §4; `clientId` and `principalId` assigned by floci | none you can see |

Three ids per app, and which one goes where (from [azure.md §4](azure.md#where-the-credential-lives-vs-who-signs-in)):

| Id | Used for |
|---|---|
| **appId / client id** | signing in (`client_id=`), `azp`/`appid` in tokens |
| **service principal object id** | role assignments (`principalId`), `oid` in the app's own tokens |
| application object id | managing the app registration in Graph; never in tokens or role assignments |

In Azure the service principal's object id and the application object id are different values. In
this floci-az build the worker's tokens carry the **application object id** as `oid`
(`b0000000-…-000b`, set in the seed file), so that's the value used as `principalId` below.

---

## 2. Lab setup

**floci-az `nightly-11222026`** (revision `0f6d082`), a separate container next to the existing
one, with the strict switches on:

```console
$ docker run -d --name floci-az-idlab -p 127.0.0.1:4588:4577 -v $PWD/seed.json:/seed/seed.json:ro \
    -e FLOCI_AZ_SERVICES_ENTRA_SEED_FILE=/seed/seed.json \
    -e FLOCI_AZ_SERVICES_ENTRA_VALIDATE_CLIENT_CREDENTIALS=true \
    -e FLOCI_AZ_SERVICES_ENTRA_VALIDATE_USER_CREDENTIALS=true \
    -e FLOCI_AZ_SERVICES_ENTRA_VALIDATE_TOKENS=false \
    -e FLOCI_AZ_TLS_ENABLED=true floci/floci-az:nightly
```

| Switch | Effect |
|---|---|
| `VALIDATE_CLIENT_CREDENTIALS` | unknown `client_id` → `AADSTS700016`; wrong secret → `AADSTS7000215` (§6.4) |
| `VALIDATE_USER_CREDENTIALS` | wrong password → `AADSTS50126` |
| `VALIDATE_TOKENS` | ARM checks the bearer token's signature, issuer and audience. **On** for §10.1, **off** for the Java runs, because floci-az gives MSAL's user tokens `aud` = the client id (§11) and ARM would reject them |
| `TLS_ENABLED` | HTTPS on the same port; MSAL for Java only accepts an `https` authority |

What 0.16.0 lacked and this build has: `Microsoft.Authorization` (role definitions and role
assignments), and a startup **seed file** for users, groups and app registrations with real
passwords and secrets:

```json
{
  "applications": [
    {"id": "a0000000-0000-0000-0000-00000000000a", "appId": "a1111111-0000-0000-0000-00000000000a",
     "displayName": "orders-web", "clientSecret": "orders-web-secret-dev-only"},
    {"id": "b0000000-0000-0000-0000-00000000000b", "appId": "b1111111-0000-0000-0000-00000000000b",
     "displayName": "orders-worker", "clientSecret": "orders-worker-secret-dev-only"}
  ],
  "users": [
    {"id": "11111111-aaaa-0000-0000-000000000001", "userPrincipalName": "alice@contoso.test", "displayName": "Alice (admin)",
     "accountEnabled": true, "passwordProfile": {"password": "alice-dev-only-Pa55", "forceChangePasswordNextSignIn": false}},
    {"id": "22222222-bbbb-0000-0000-000000000002", "userPrincipalName": "bob@contoso.test", "displayName": "Bob",
     "accountEnabled": true, "passwordProfile": {"password": "bob-dev-only-Pa55", "forceChangePasswordNextSignIn": false}}
  ],
  "groups": [
    {"id": "cccccccc-0000-0000-0000-00000000000c", "displayName": "orders-readers", "securityEnabled": true,
     "members": ["22222222-bbbb-0000-0000-000000000002"]}
  ]
}
```

**What floci-az does and doesn't check** (its docs, and §10–11 here):

| | floci-az nightly |
|---|---|
| user passwords, client secrets | checked (with the switches above) |
| token signature, issuer, audience at ARM | checked with `VALIDATE_TOKENS` |
| role assignments | **stored, not enforced**: "an assignment grants nothing and its absence denies nothing" |
| on-behalf-of grant | not implemented (`AADSTS70003`, §8) |
| token checks at Key Vault and other data planes | none: any bearer token is accepted |

So the lab shows authentication and the grants exactly, and authorization only as the data Azure
would evaluate. The evaluation itself is from Microsoft's docs (§10).

**The client:** azure-identity **1.19.0**, MSAL4J **1.26.0**, azure-core 1.61.0 with the JDK
`HttpClient` transport (azure-core-http-jdk-httpclient 1.0.4), Java 25. All traffic goes through a
small logging HTTPS proxy (`https://127.0.0.1:4578` → floci-az on `:4588`) that prints each exchange
as HTTP messages. In the output below:

- secrets and passwords are replaced by `<client_secret>` / `<password>`;
- tokens, codes and PKCE verifiers are cut after 30 characters (`…`), and every JWT seen is decoded
  in a `json` block after the exchange (a payload already shown isn't repeated);
- form bodies are shown URL-decoded; response JSON is indented;
- `User-Agent`, `client-request-id`, telemetry headers, `Date` and `Content-Length` are left out.

Each section quotes the part of the Java program that ran.

---

## 3. A user signs in through an app (authorization code + PKCE)

What a web app does when Alice clicks "Sign in". This is MSAL4J directly, as a Spring or servlet
app uses it (azure-identity's `AuthorizationCodeCredential` wraps the same MSAL call). **(lab)**

```java
ConfidentialClientApplication app = ConfidentialClientApplication
    .builder(WEB_APP, ClientCredentialFactory.createFromSecret(WEB_SECRET))   // orders-web's client id + secret
    .authority(HOST + "/" + TENANT + "/")
    .instanceDiscovery(false)                     // don't ask login.microsoftonline.com to validate the host
    .build();

// the browser step: redirect the user to /authorize; Entra signs them in and redirects back with ?code=
String verifier = b64url(new SecureRandom().generateSeed(32));
String challenge = b64url(MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(US_ASCII)));
Set<String> scopes = Set.of("https://management.azure.com/user_impersonation");
AuthorizationRequestUrlParameters ap = AuthorizationRequestUrlParameters
    .builder("https://app.local/callback", scopes)
    .codeChallenge(challenge).codeChallengeMethod("S256").loginHint(upn).state("st-1").nonce("n-1").build();
URI authorize = URI.create(app.getAuthorizationRequestUrl(ap).toString());
//   ... the lab follows the redirect with a plain HTTP client and reads ?code= from Location ...

// the server-side step: redeem the code, proving the app with its secret and the PKCE verifier
IAuthenticationResult alice = app.acquireToken(AuthorizationCodeParameters
    .builder(code, URI.create("https://app.local/callback"))
    .scopes(scopes).codeVerifier(verifier).build()).get();
```

```http
GET /00000000-0000-0000-0000-000000000002/oauth2/v2.0/authorize?X-AnchorMailbox=upn%3Aalice%40contoso.test&login_hint=alice%40contoso.test&scope=openid+profile+offline_access+https%3A%2F%2Fmanagement.azure.com%2Fuser_impersonation&response_type=code&redirect_uri=https%3A%2F%2Fapp.local%2Fcallback&code_challenge_method=S256&state=st-1&nonce=n-1&code_challenge=TZWFzAEKDkilzy7X64bR1qPzSzcCVvgAXWgLN4YQY4Q&client_id=a1111111-0000-0000-0000-00000000000a&response_mode=form_post HTTP/1.1
Host: 127.0.0.1:4578
```

```http
HTTP/1.1 302 Found
Location: https://app.local/callback?code=e1190891-0547-49da-b1d0-43c3865a0c91&state=st-1
```

```http
POST /00000000-0000-0000-0000-000000000002/oauth2/v2.0/token HTTP/1.1
x-client-VER: 1.26.0
x-client-SKU: MSAL.Java
Content-Type: application/x-www-form-urlencoded; charset=UTF-8
Host: 127.0.0.1:4578
Accept: */*

client_info=1&code=e1190891-0547-49da-b1d0-43c386…&redirect_uri=https://app.local/callback&grant_type=authorization_code&code_verifier=dRr6FkUAqFebAkaF7CCcqAOc_4t5iX…&scope=openid profile offline_access https://management.azure.com/user_impersonation&client_id=a1111111-0000-0000-0000-00000000000a&client_secret=<client_secret>
```

```http
HTTP/1.1 200 OK
Content-Type: application/json

{
  "token_type": "Bearer",
  "expires_in": 3599,
  "ext_expires_in": 3599,
  "scope": "https://management.azure.com/user_impersonation",
  "access_token": "eyJ0eXAiOiJKV1QiLCJhbGciOiJSUz…",
  "refresh_token": "GpF7blp6E18euS680VUmzUDy4Avz9R…",
  "id_token": "eyJ0eXAiOiJKV1QiLCJhbGciOiJSUz…",
  "client_info": "eyJ1aWQiOiIxMTExMTExMS1hYWFhLT…"
}
```

```json
// response access_token, decoded: header, then payload
{"typ": "JWT", "alg": "RS256", "kid": "TDIu9kVB_L_p0OZcY41a_6y7"}
{
  "iss": "https://127.0.0.1:4578/00000000-0000-0000-0000-000000000002/v2.0",
  "sub": "11111111-aaaa-0000-0000-000000000001",
  "aud": "a1111111-0000-0000-0000-00000000000a",
  "exp": 1795429029,
  "iat": 1795425430,
  "nbf": 1795425430,
  "oid": "11111111-aaaa-0000-0000-000000000001",
  "tid": "00000000-0000-0000-0000-000000000002",
  "ver": "2.0",
  "azp": "a1111111-0000-0000-0000-00000000000a",
  "scp": "openid profile https://management.azure.com/user_impersonation",
  "preferred_username": "alice@contoso.test",
  "uti": "CmpiZcRToFg0iVr8xNS3oQ"
}
```

```json
// response id_token, decoded: header, then payload
{"typ": "JWT", "alg": "RS256", "kid": "TDIu9kVB_L_p0OZcY41a_6y7"}
{
  "iss": "https://127.0.0.1:4578/00000000-0000-0000-0000-000000000002/v2.0",
  "sub": "11111111-aaaa-0000-0000-000000000001",
  "aud": "a1111111-0000-0000-0000-00000000000a",
  "exp": 1795429029,
  "iat": 1795425430,
  "nbf": 1795425430,
  "oid": "11111111-aaaa-0000-0000-000000000001",
  "tid": "00000000-0000-0000-0000-000000000002",
  "ver": "2.0",
  "azp": "a1111111-0000-0000-0000-00000000000a",
  "scp": "https://management.azure.com/user_impersonation",
  "nonce": "n-1",
  "name": "Alice (admin)",
  "preferred_username": "alice@contoso.test",
  "uti": "NA5-sgRPFkBDBudyiHz98Q"
}
```

What the exchange shows:

- **Two parties authenticate in one exchange.** Alice proves who she is at `/authorize` (floci-az
  has no login page and signs in the `login_hint` user; Azure would show its sign-in page, MFA and
  Conditional Access **(docs)**). The app proves who *it* is at `/token` with `client_secret`, and
  binds the code to itself with `code_verifier` (PKCE) and `redirect_uri`.
- **The token is Alice's.** `oid` and `sub` are Alice; `preferred_username` is her UPN; `scp` is the
  delegated scope `user_impersonation` ("act as the user in ARM"); `azp` is the app that asked. There
  is no `idtyp: app` and no `roles`.
- **The app never saw a password.** What it holds is Alice's access token, her ID token (`nonce`
  echoed, for the app's own sign-in) and a refresh token (because MSAL always adds
  `offline_access`, **(code)**: `AbstractMsalAuthorizationGrant.COMMON_SCOPES = openid, profile,
  offline_access`).
- **`scp` is a ceiling, not a grant.** It says the app may call ARM *as Alice*. What Alice may do in
  ARM is decided by Alice's role assignments (§10). In Azure, before any of this, a user or an admin
  must have **consented** to `orders-web` using `user_impersonation`; that consent is recorded on
  `orders-web`'s service principal **(docs)**. floci-az doesn't model consent.

---

## 4. An admin grants roles (with her own token)

Alice, signed in through `orders-web`, creates the resources and then the role assignments. Every
request carries **Alice's** token. azure-core's `BearerTokenAuthenticationPolicy` adds it. **(lab)**

```java
/** An azure-core pipeline that gets a token from `cred` for `scope` and sends it as a Bearer header. */
static HttpPipeline pipeline(TokenCredential cred, String scope) {
    return new HttpPipelineBuilder().policies(new BearerTokenAuthenticationPolicy(cred, scope)).build();
}
/** A TokenCredential that returns a token we already hold: here, Alice's from MSAL. */
static TokenCredential fixed(String token) {
    return ctx -> Mono.just(new AccessToken(token, OffsetDateTime.now().plusMinutes(50)));
}

HttpPipeline asAlice = pipeline(fixed(alice.accessToken()), "https://management.azure.com/.default");
```

`BearerTokenAuthenticationPolicy.processSync` (azure-core 1.61.0) **(code)**: it refuses plain
HTTP, sets the header, and on a `401` with a `WWW-Authenticate` challenge (e.g. a claims challenge)
gets a new token and retries once:

```java
public HttpResponse processSync(HttpPipelineCallContext context, HttpPipelineNextSyncPolicy next) {
    if (!"https".equals(context.getHttpRequest().getUrl().getProtocol())) {
        throw LOGGER.logExceptionAsError(
            new RuntimeException("token credentials require a URL using the HTTPS protocol scheme"));
    }
    HttpPipelineNextSyncPolicy nextPolicy = next.clone();

    authorizeRequestSync(context);
    HttpResponse httpResponse = next.processSync();
    String authHeader = httpResponse.getHeaderValue(HttpHeaderName.WWW_AUTHENTICATE);
    if (httpResponse.getStatusCode() == 401 && authHeader != null) {
        if (authorizeRequestOnChallengeSync(context, httpResponse)) {
            httpResponse.close();
            return nextPolicy.processSync();
        } else {
            return httpResponse;
        }
    }
    return httpResponse;
}
```

### 4.1 Creating the resources (lab)

A resource group, a Key Vault in RBAC mode (`enableRbacAuthorization: true`) and a user-assigned
managed identity. Azure returns the identity's two ids: `principalId` (its service principal's
object id: what role assignments use) and `clientId` (what code passes to get a token as it).

```http
PUT /subscriptions/00000000-0000-0000-0000-000000000001/resourceGroups/orders-rg?api-version=2021-04-01 HTTP/1.1
Host: 127.0.0.1:4578
Authorization: Bearer eyJ0eXAiOiJKV1QiLCJhbGciOiJSUz…
Content-Type: application/json

{"location":"westeurope"}
```

```http
HTTP/1.1 201 Created
Content-Type: application/json;charset=UTF-8

{
  "id": "/subscriptions/00000000-0000-0000-0000-000000000001/resourceGroups/orders-rg",
  "name": "orders-rg",
  "type": "Microsoft.Resources/resourceGroups",
  "location": "westeurope",
  "properties": {
    "provisioningState": "Succeeded"
  }
}
```

```http
PUT /subscriptions/00000000-0000-0000-0000-000000000001/resourceGroups/orders-rg/providers/Microsoft.KeyVault/vaults/orders-kv?api-version=2023-07-01 HTTP/1.1
Host: 127.0.0.1:4578
Authorization: Bearer eyJ0eXAiOiJKV1QiLCJhbGciOiJSUz…
Content-Type: application/json

{"location":"westeurope","properties":{"tenantId":"00000000-0000-0000-0000-000000000002","sku":{"family":"A","name":"standard"},"enableRbacAuthorization":true}}
```

```http
HTTP/1.1 200 OK
Content-Type: application/json;charset=UTF-8

{
  "id": "/subscriptions/00000000-0000-0000-0000-000000000001/resourceGroups/orders-rg/providers/Microsoft.KeyVault/vaults/orders-kv",
  "name": "orders-kv",
  "type": "Microsoft.KeyVault/vaults",
  "location": "westeurope",
  "properties": {
    "vaultUri": "https://orders-kv.vault.azure.net/",
    "tenantId": "00000000-0000-0000-0000-000000000002",
    "sku": {
      "family": "A",
      "name": "standard"
    },
    "provisioningState": "Succeeded",
    "enableSoftDelete": true,
    "accessPolicies": [],
    "enableRbacAuthorization": true
  }
}
```

```http
PUT /subscriptions/00000000-0000-0000-0000-000000000001/resourceGroups/orders-rg/providers/Microsoft.ManagedIdentity/userAssignedIdentities/orders-id?api-version=2023-01-31 HTTP/1.1
Host: 127.0.0.1:4578
Authorization: Bearer eyJ0eXAiOiJKV1QiLCJhbGciOiJSUz…
Content-Type: application/json

{"location":"westeurope"}
```

```http
HTTP/1.1 201 Created
Content-Type: application/json;charset=UTF-8

{
  "id": "/subscriptions/00000000-0000-0000-0000-000000000001/resourceGroups/orders-rg/providers/Microsoft.ManagedIdentity/userAssignedIdentities/orders-id",
  "name": "orders-id",
  "type": "Microsoft.ManagedIdentity/userAssignedIdentities",
  "location": "westeurope",
  "tags": {},
  "properties": {
    "tenantId": "00000000-0000-0000-0000-000000000002",
    "principalId": "9796b2da-c8fa-408b-a0fa-37bbe44991d3",
    "clientId": "7099e2ca-3234-401c-a986-d7276d6f39d2"
  }
}
```

### 4.2 Granting (lab)

A role assignment is three things: **who** (`principalId`), **what** (`roleDefinitionId`) and
**where** (the URL's scope; it applies there and to everything below). First the role's id, looked
up by name:

```java
call(asAlice, GET, HOST + "/subscriptions/" + SUB + "/providers/Microsoft.Authorization/roleDefinitions"
    + "?api-version=2022-04-01&$filter=roleName%20eq%20%27Key%20Vault%20Secrets%20User%27", null);
// then, for each grant:
call(asAlice, PUT, HOST + scope + "/providers/Microsoft.Authorization/roleAssignments/" + newGuid
    + "?api-version=2022-04-01",
    "{\"properties\":{\"roleDefinitionId\":\"" + roleId + "\",\"principalId\":\"" + principalId
    + "\",\"principalType\":\"ServicePrincipal\"}}");
```

Three grants: `Key Vault Secrets User` on the vault for `orders-worker` and for the managed identity
`orders-id`, and `Reader` on the resource group for the group `orders-readers`.

```http
GET /subscriptions/00000000-0000-0000-0000-000000000001/providers/Microsoft.Authorization/roleDefinitions?api-version=2022-04-01&$filter=roleName%20eq%20%27Key%20Vault%20Secrets%20User%27 HTTP/1.1
Host: 127.0.0.1:4578
Authorization: Bearer eyJ0eXAiOiJKV1QiLCJhbGciOiJSUz…
```

```http
HTTP/1.1 200 OK
Content-Type: application/json

{
  "value": [
    {
      "id": "/subscriptions/00000000-0000-0000-0000-000000000001/providers/Microsoft.Authorization/roleDefinitions/4633458b-17de-408a-b874-0445c86b69e6",
      "type": "Microsoft.Authorization/roleDefinitions",
      "name": "4633458b-17de-408a-b874-0445c86b69e6",
      "properties": {
        "roleName": "Key Vault Secrets User",
        "type": "BuiltInRole",
        "description": "Read secret contents. Only works for key vaults that use the 'Azure role-based access control' permission model.",
        "assignableScopes": [
          "/"
        ],
        "permissions": [
          {
            "actions": [],
            "notActions": [],
            "dataActions": [
              "Microsoft.KeyVault/vaults/secrets/getSecret/action",
              "Microsoft.KeyVault/vaults/secrets/readMetadata/action"
            ],
            "notDataActions": []
          }
        ],
        "createdOn": null,
        "updatedOn": null,
        "createdBy": null,
        "updatedBy": null
      }
    }
  ]
}
```

```http
PUT /subscriptions/00000000-0000-0000-0000-000000000001/resourceGroups/orders-rg/providers/Microsoft.KeyVault/vaults/orders-kv/providers/Microsoft.Authorization/roleAssignments/f1290186-a5d0-31ce-ab27-f4e77c0c5d68?api-version=2022-04-01 HTTP/1.1
Host: 127.0.0.1:4578
Authorization: Bearer eyJ0eXAiOiJKV1QiLCJhbGciOiJSUz…
Content-Type: application/json

{"properties":{"roleDefinitionId":"/subscriptions/00000000-0000-0000-0000-000000000001/providers/Microsoft.Authorization/roleDefinitions/4633458b-17de-408a-b874-0445c86b69e6","principalId":"b0000000-0000-0000-0000-00000000000b","principalType":"ServicePrincipal"}}
```

```http
HTTP/1.1 201 Created
Content-Type: application/json

{
  "id": "/subscriptions/00000000-0000-0000-0000-000000000001/resourceGroups/orders-rg/providers/Microsoft.KeyVault/vaults/orders-kv/providers/Microsoft.Authorization/roleAssignments/f1290186-a5d0-31ce-ab27-f4e77c0c5d68",
  "type": "Microsoft.Authorization/roleAssignments",
  "name": "f1290186-a5d0-31ce-ab27-f4e77c0c5d68",
  "properties": {
    "roleDefinitionId": "/subscriptions/00000000-0000-0000-0000-000000000001/providers/Microsoft.Authorization/roleDefinitions/4633458b-17de-408a-b874-0445c86b69e6",
    "principalId": "b0000000-0000-0000-0000-00000000000b",
    "principalType": "ServicePrincipal",
    "scope": "/subscriptions/00000000-0000-0000-0000-000000000001/resourceGroups/orders-rg/providers/Microsoft.KeyVault/vaults/orders-kv",
    "condition": null,
    "conditionVersion": null,
    "createdOn": "2026-11-23T09:17:10.970777177Z",
    "updatedOn": "2026-11-23T09:17:10.970777177Z",
    "createdBy": "11111111-aaaa-0000-0000-000000000001",
    "updatedBy": "11111111-aaaa-0000-0000-000000000001",
    "delegatedManagedIdentityResourceId": null,
    "description": null
  }
}
```

```http
PUT /subscriptions/00000000-0000-0000-0000-000000000001/resourceGroups/orders-rg/providers/Microsoft.KeyVault/vaults/orders-kv/providers/Microsoft.Authorization/roleAssignments/6f8f5771-5090-3a26-b245-3988d9a1501b?api-version=2022-04-01 HTTP/1.1
Host: 127.0.0.1:4578
Authorization: Bearer eyJ0eXAiOiJKV1QiLCJhbGciOiJSUz…
Content-Type: application/json

{"properties":{"roleDefinitionId":"/subscriptions/00000000-0000-0000-0000-000000000001/providers/Microsoft.Authorization/roleDefinitions/4633458b-17de-408a-b874-0445c86b69e6","principalId":"9796b2da-c8fa-408b-a0fa-37bbe44991d3","principalType":"ServicePrincipal"}}
```

```http
HTTP/1.1 201 Created
Content-Type: application/json

{
  "id": "/subscriptions/00000000-0000-0000-0000-000000000001/resourceGroups/orders-rg/providers/Microsoft.KeyVault/vaults/orders-kv/providers/Microsoft.Authorization/roleAssignments/6f8f5771-5090-3a26-b245-3988d9a1501b",
  "type": "Microsoft.Authorization/roleAssignments",
  "name": "6f8f5771-5090-3a26-b245-3988d9a1501b",
  "properties": {
    "roleDefinitionId": "/subscriptions/00000000-0000-0000-0000-000000000001/providers/Microsoft.Authorization/roleDefinitions/4633458b-17de-408a-b874-0445c86b69e6",
    "principalId": "9796b2da-c8fa-408b-a0fa-37bbe44991d3",
    "principalType": "ServicePrincipal",
    "scope": "/subscriptions/00000000-0000-0000-0000-000000000001/resourceGroups/orders-rg/providers/Microsoft.KeyVault/vaults/orders-kv",
    "condition": null,
    "conditionVersion": null,
    "createdOn": "2026-11-23T09:17:10.988785927Z",
    "updatedOn": "2026-11-23T09:17:10.988785927Z",
    "createdBy": "11111111-aaaa-0000-0000-000000000001",
    "updatedBy": "11111111-aaaa-0000-0000-000000000001",
    "delegatedManagedIdentityResourceId": null,
    "description": null
  }
}
```

```http
PUT /subscriptions/00000000-0000-0000-0000-000000000001/resourceGroups/orders-rg/providers/Microsoft.Authorization/roleAssignments/b2f5ff47-4366-31b6-a533-d8dc3614845d?api-version=2022-04-01 HTTP/1.1
Host: 127.0.0.1:4578
Authorization: Bearer eyJ0eXAiOiJKV1QiLCJhbGciOiJSUz…
Content-Type: application/json

{"properties":{"roleDefinitionId":"/subscriptions/00000000-0000-0000-0000-000000000001/providers/Microsoft.Authorization/roleDefinitions/acdd72a7-3385-48ef-bd42-f606fba81ae7","principalId":"cccccccc-0000-0000-0000-00000000000c","principalType":"Group"}}
```

```http
HTTP/1.1 201 Created
Content-Type: application/json

{
  "id": "/subscriptions/00000000-0000-0000-0000-000000000001/resourceGroups/orders-rg/providers/Microsoft.Authorization/roleAssignments/b2f5ff47-4366-31b6-a533-d8dc3614845d",
  "type": "Microsoft.Authorization/roleAssignments",
  "name": "b2f5ff47-4366-31b6-a533-d8dc3614845d",
  "properties": {
    "roleDefinitionId": "/subscriptions/00000000-0000-0000-0000-000000000001/providers/Microsoft.Authorization/roleDefinitions/acdd72a7-3385-48ef-bd42-f606fba81ae7",
    "principalId": "cccccccc-0000-0000-0000-00000000000c",
    "principalType": "Group",
    "scope": "/subscriptions/00000000-0000-0000-0000-000000000001/resourceGroups/orders-rg",
    "condition": null,
    "conditionVersion": null,
    "createdOn": "2026-11-23T09:17:11.006568135Z",
    "updatedOn": "2026-11-23T09:17:11.006568135Z",
    "createdBy": "11111111-aaaa-0000-0000-000000000001",
    "updatedBy": "11111111-aaaa-0000-0000-000000000001",
    "delegatedManagedIdentityResourceId": null,
    "description": null
  }
}
```

What the responses show:

- **The role is data actions only.** `Key Vault Secrets User` has `dataActions`
  `secrets/getSecret/action` and `secrets/readMetadata/action`, and no `actions`. It lets the holder
  read secret values through the vault's data plane, and gives nothing in ARM (it can't even see the
  vault resource).
- **`principalId` is an object id**, of a service principal, a user or a group (`principalType`
  says which). Not an `appId`, and not a user name.
- **`createdBy` is Alice's `oid`**, taken from the bearer token. That's the whole link between the
  admin and the grant: an audit field. The worker's later calls don't involve Alice at all.
- **Who may do this:** in Azure, creating a role assignment needs
  `Microsoft.Authorization/roleAssignments/write` at that scope: Owner, User Access Administrator or
  Role Based Access Control Administrator **(docs)**. floci-az let Alice do it because it checks no
  roles; in Azure, Bob's token would get `403 AuthorizationFailed` here.
- **Granting to a group** (`orders-readers`, `principalType: Group`) gives the role to every member,
  Bob included. Azure expands membership when it evaluates **(docs)**.

---

## 5. The user's own calls (delegated)

The app keeps calling as Alice. For a second resource (Key Vault's data plane, audience
`https://vault.azure.net`) it doesn't send Alice back to `/authorize`. It redeems the **refresh
token** for a token with a different scope. **(lab)**

```java
// the refresh token from the code redemption is in the app's token cache. acquireTokenSilently would
// use it, but its cached "environment" is the host without the port (127.0.0.1), so in this lab it
// connects to :443. Redeem the cached refresh token explicitly instead.
String cache = WEB.tokenCache().serialize();
String rt = cache.replaceAll("(?s).*\"RefreshToken\".*?\"secret\"\\s*:\\s*\"([^\"]+)\".*", "$1");
IAuthenticationResult aliceKv = WEB.acquireToken(RefreshTokenParameters.builder(
    Set.of("https://vault.azure.net/user_impersonation"), rt).build()).get();
call(pipeline(fixed(aliceKv.accessToken()), VAULT), PUT, KV + "/secrets/db-password?api-version=7.5",
    "{\"value\":\"s3cr3t\"}");
```

```http
POST /00000000-0000-0000-0000-000000000002/oauth2/v2.0/token HTTP/1.1
x-client-VER: 1.26.0
x-client-SKU: MSAL.Java
Content-Type: application/x-www-form-urlencoded; charset=UTF-8
Host: 127.0.0.1:4578
Accept: */*

refresh_token=GpF7blp6E18euS680VUmzUDy4Avz9R…&client_info=1&grant_type=refresh_token&scope=openid profile offline_access https://vault.azure.net/user_impersonation&client_id=a1111111-0000-0000-0000-00000000000a&client_secret=<client_secret>
```

```http
HTTP/1.1 200 OK
Content-Type: application/json

{
  "token_type": "Bearer",
  "expires_in": 3599,
  "ext_expires_in": 3599,
  "scope": "https://vault.azure.net/user_impersonation",
  "access_token": "eyJ0eXAiOiJKV1QiLCJhbGciOiJSUz…",
  "refresh_token": "HUwe_d0ltW6N1n7F1x1kiiEDYyiT2r…",
  "id_token": "eyJ0eXAiOiJKV1QiLCJhbGciOiJSUz…",
  "client_info": "eyJ1aWQiOiIxMTExMTExMS1hYWFhLT…"
}
```

```json
// response access_token, decoded: header, then payload
{"typ": "JWT", "alg": "RS256", "kid": "TDIu9kVB_L_p0OZcY41a_6y7"}
{
  "iss": "https://127.0.0.1:4578/00000000-0000-0000-0000-000000000002/v2.0",
  "sub": "11111111-aaaa-0000-0000-000000000001",
  "aud": "a1111111-0000-0000-0000-00000000000a",
  "exp": 1795429031,
  "iat": 1795425432,
  "nbf": 1795425432,
  "oid": "11111111-aaaa-0000-0000-000000000001",
  "tid": "00000000-0000-0000-0000-000000000002",
  "ver": "2.0",
  "azp": "a1111111-0000-0000-0000-00000000000a",
  "scp": "openid profile https://vault.azure.net/user_impersonation",
  "preferred_username": "alice@contoso.test",
  "uti": "EY9nLi6l3eSPAw56vGHW4A"
}
```

```json
// response id_token, decoded: header, then payload
{"typ": "JWT", "alg": "RS256", "kid": "TDIu9kVB_L_p0OZcY41a_6y7"}
{
  "iss": "https://127.0.0.1:4578/00000000-0000-0000-0000-000000000002/v2.0",
  "sub": "11111111-aaaa-0000-0000-000000000001",
  "aud": "a1111111-0000-0000-0000-00000000000a",
  "exp": 1795429031,
  "iat": 1795425432,
  "nbf": 1795425432,
  "oid": "11111111-aaaa-0000-0000-000000000001",
  "tid": "00000000-0000-0000-0000-000000000002",
  "ver": "2.0",
  "azp": "a1111111-0000-0000-0000-00000000000a",
  "scp": "https://vault.azure.net/user_impersonation",
  "name": "Alice (admin)",
  "preferred_username": "alice@contoso.test",
  "uti": "Qs-jB9E1QeG2xiGDxacf2A"
}
```

```http
PUT /orders-kv-keyvault/secrets/db-password?api-version=7.5 HTTP/1.1
Host: 127.0.0.1:4578
Authorization: Bearer eyJ0eXAiOiJKV1QiLCJhbGciOiJSUz…
Content-Type: application/json

{"value":"s3cr3t"}
```

```http
HTTP/1.1 200 OK
Content-Type: application/json

{
  "value": "s3cr3t",
  "id": "https://orders-kv.vault.azure.net/secrets/db-password/b894c6d2ea854c778392d61f9f2a3573",
  "attributes": {
    "enabled": true,
    "created": 1795425432,
    "updated": 1795425432,
    "recoveryLevel": "Purgeable",
    "recoverableDays": 7
  },
  "contentType": "",
  "tags": {}
}
```

- **Still Alice.** Same `oid`, new `scp` (`vault.azure.net/user_impersonation`). One sign-in, tokens
  for several APIs, each scoped to one audience.
- **In Azure, Key Vault would check Alice's data-plane role** on the vault (e.g. Key Vault Secrets
  Officer to write). Being Owner of the subscription isn't enough for secret *values* in RBAC mode:
  Owner has `actions: *` but no `dataActions` **(docs)**. floci-az's Key Vault accepts any token.

---

## 6. An app as itself: a service principal with a secret

`orders-worker` has no user. It signs in with its own client id and secret: the **client
credentials** grant. **(lab)**

```java
ClientSecretCredential worker = new ClientSecretCredentialBuilder()
    .tenantId(TENANT).clientId(WORKER_APP).clientSecret(WORKER_SECRET)
    .authorityHost(HOST).disableInstanceDiscovery().build();

call(pipeline(worker, "https://management.azure.com/.default"), GET, HOST + "/subscriptions/" + SUB
    + "/providers/Microsoft.Authorization/roleAssignments?api-version=2022-04-01"
    + "&$filter=assignedTo('" + WORKER_OID + "')", null);
call(pipeline(worker, "https://vault.azure.net/.default"), GET, KV + "/secrets/db-password?api-version=7.5", null);
```

Inside azure-identity, `ClientSecretCredential.getToken` ends in `IdentityClient` **(code, 1.19.0)**:

```java
public Mono<AccessToken> authenticateWithConfidentialClient(TokenRequestContext request) {
    return getConfidentialClientInstance(request).getValue().flatMap(confidentialClient -> Mono.fromFuture(() -> {
        ClientCredentialParameters.ClientCredentialParametersBuilder builder
            = buildConfidentialClientParameters(request);
        return confidentialClient.acquireToken(builder.build());
    })).map(MsalToken::new);
}

private ClientCredentialParameters.ClientCredentialParametersBuilder
    buildConfidentialClientParameters(TokenRequestContext request) {
    ClientCredentialParameters.ClientCredentialParametersBuilder builder
        = ClientCredentialParameters.builder(new HashSet<>(request.getScopes()))
            .tenant(IdentityUtil.resolveTenantId(tenantId, request, options));
    if (clientAssertionSupplier != null) {          // ClientAssertionCredential, WorkloadIdentityCredential
        builder.clientCredential(ClientCredentialFactory.createFromClientAssertion(clientAssertionSupplier.get()));
    } else if (clientAssertionSupplierWithHttpPipeline != null) {
        builder.clientCredential(ClientCredentialFactory
            .createFromClientAssertion(clientAssertionSupplierWithHttpPipeline.apply(getPipeline())));
    }
    if (request.isCaeEnabled() && request.getClaims() != null) {
        ClaimsRequest claimsRequest = ClaimsRequest.formatAsClaimsRequest(request.getClaims());
        builder.claims(claimsRequest);
    }
    return builder;
}
```

So a secret, a certificate and a federated token (Workload Identity) are the same MSAL call with a
different client credential. Only `client_secret=` vs `client_assertion=` changes on the wire.

### 6.1 The token, and the worker's role assignments (lab)

```http
POST /00000000-0000-0000-0000-000000000002/oauth2/v2.0/token HTTP/1.1
Host: 127.0.0.1:4578
Content-Type: application/x-www-form-urlencoded; charset=UTF-8
x-client-SKU: MSAL.Java
x-client-VER: 1.26.0

claims={"access_token":{"xms_cc":{"values":["CP1"]}}}&client_info=1&grant_type=client_credentials&scope=https://management.azure.com/.default openid profile offline_access&client_id=b1111111-0000-0000-0000-00000000000b&client_secret=<client_secret>
```

```http
HTTP/1.1 200 OK
Content-Type: application/json

{
  "token_type": "Bearer",
  "expires_in": 3599,
  "ext_expires_in": 3599,
  "access_token": "eyJ0eXAiOiJKV1QiLCJhbGciOiJSUz…"
}
```

```json
// response access_token, decoded: header, then payload
{"typ": "JWT", "alg": "RS256", "kid": "TDIu9kVB_L_p0OZcY41a_6y7"}
{
  "iss": "https://127.0.0.1:4578/00000000-0000-0000-0000-000000000002/v2.0",
  "sub": "b0000000-0000-0000-0000-00000000000b",
  "aud": "https://management.azure.com",
  "exp": 1795429033,
  "iat": 1795425434,
  "nbf": 1795425434,
  "oid": "b0000000-0000-0000-0000-00000000000b",
  "tid": "00000000-0000-0000-0000-000000000002",
  "ver": "2.0",
  "azp": "b1111111-0000-0000-0000-00000000000b",
  "idtyp": "app",
  "uti": "f1fpiBUrE3SqIlCGCb-JUw"
}
```

```http
GET /subscriptions/00000000-0000-0000-0000-000000000001/providers/Microsoft.Authorization/roleAssignments?api-version=2022-04-01&$filter=assignedTo%28%27b0000000-0000-0000-0000-00000000000b%27%29 HTTP/1.1
Host: 127.0.0.1:4578
Authorization: Bearer eyJ0eXAiOiJKV1QiLCJhbGciOiJSUz…
```

```http
HTTP/1.1 200 OK
Content-Type: application/json

{
  "value": [
    {
      "id": "/subscriptions/00000000-0000-0000-0000-000000000001/resourceGroups/orders-rg/providers/Microsoft.KeyVault/vaults/orders-kv/providers/Microsoft.Authorization/roleAssignments/f1290186-a5d0-31ce-ab27-f4e77c0c5d68",
      "type": "Microsoft.Authorization/roleAssignments",
      "name": "f1290186-a5d0-31ce-ab27-f4e77c0c5d68",
      "properties": {
        "roleDefinitionId": "/subscriptions/00000000-0000-0000-0000-000000000001/providers/Microsoft.Authorization/roleDefinitions/4633458b-17de-408a-b874-0445c86b69e6",
        "principalId": "b0000000-0000-0000-0000-00000000000b",
        "principalType": "ServicePrincipal",
        "scope": "/subscriptions/00000000-0000-0000-0000-000000000001/resourceGroups/orders-rg/providers/Microsoft.KeyVault/vaults/orders-kv",
        "condition": null,
        "conditionVersion": null,
        "createdOn": "2026-11-23T09:17:10.970777177Z",
        "updatedOn": "2026-11-23T09:17:10.970777177Z",
        "createdBy": "11111111-aaaa-0000-0000-000000000001",
        "updatedBy": "11111111-aaaa-0000-0000-000000000001",
        "delegatedManagedIdentityResourceId": null,
        "description": null
      }
    }
  ]
}
```

- **The token has no user.** `oid` is the service principal, `idtyp: app`, no `scp`, no
  `preferred_username`. `azp` is the worker's client id.
- **`aud` is the resource from `scope=…/.default`.** `/.default` means "whatever this app has been
  granted on that resource": in Azure, admin-consented app roles appear as a `roles` claim **(docs)**.
  The worker has none here.
- **The assignment Alice made is the worker's.** Listing with `assignedTo('<worker oid>')` returns
  it, `createdBy` still Alice. That's how to answer "what can this app do?": list role assignments
  for its **object id**.
- **`claims={"access_token":{"xms_cc":{"values":["CP1"]}}}`** in the request: the client says it
  can handle Continuous Access Evaluation (CAE) challenges. It was sent when the token was requested
  through `BearerTokenAuthenticationPolicy`, which enables CAE on its token requests, and not by the
  direct `getTokenSync` calls in §6.3 and §9 **(lab)**.

### 6.2 Reading the secret (lab)

```http
POST /00000000-0000-0000-0000-000000000002/oauth2/v2.0/token HTTP/1.1
Host: 127.0.0.1:4578
Content-Type: application/x-www-form-urlencoded; charset=UTF-8
x-client-SKU: MSAL.Java
x-client-VER: 1.26.0

claims={"access_token":{"xms_cc":{"values":["CP1"]}}}&client_info=1&grant_type=client_credentials&scope=openid profile offline_access https://vault.azure.net/.default&client_id=b1111111-0000-0000-0000-00000000000b&client_secret=<client_secret>
```

```http
HTTP/1.1 200 OK
Content-Type: application/json

{
  "token_type": "Bearer",
  "expires_in": 3599,
  "ext_expires_in": 3599,
  "access_token": "eyJ0eXAiOiJKV1QiLCJhbGciOiJSUz…"
}
```

```json
// response access_token, decoded: header, then payload
{"typ": "JWT", "alg": "RS256", "kid": "TDIu9kVB_L_p0OZcY41a_6y7"}
{
  "iss": "https://127.0.0.1:4578/00000000-0000-0000-0000-000000000002/v2.0",
  "sub": "b0000000-0000-0000-0000-00000000000b",
  "aud": "openid",
  "exp": 1795429034,
  "iat": 1795425435,
  "nbf": 1795425435,
  "oid": "b0000000-0000-0000-0000-00000000000b",
  "tid": "00000000-0000-0000-0000-000000000002",
  "ver": "2.0",
  "azp": "b1111111-0000-0000-0000-00000000000b",
  "idtyp": "app",
  "uti": "eKaf5GlPN5SJUudUFwLxDQ"
}
```

```http
GET /orders-kv-keyvault/secrets/db-password?api-version=7.5 HTTP/1.1
Host: 127.0.0.1:4578
Authorization: Bearer eyJ0eXAiOiJKV1QiLCJhbGciOiJSUz…
```

```http
HTTP/1.1 200 OK
Content-Type: application/json

{
  "value": "s3cr3t",
  "id": "https://orders-kv.vault.azure.net/secrets/db-password/b894c6d2ea854c778392d61f9f2a3573",
  "attributes": {
    "enabled": true,
    "created": 1795425432,
    "updated": 1795425432,
    "recoveryLevel": "Purgeable",
    "recoverableDays": 7
  },
  "contentType": "",
  "tags": {}
}
```

`aud: openid` is a floci-az shortcut, not Azure's behaviour. MSAL4J adds `openid profile
offline_access` to **every** request's `scope`, and floci-az takes the first scope as the audience.
Entra issues the token for the resource in `…/.default` (not tested here). floci-az's Key Vault
accepted it anyway.

### 6.3 A wrong secret (lab)

```http
POST /00000000-0000-0000-0000-000000000002/oauth2/v2.0/token HTTP/1.1
Host: 127.0.0.1:4578
Content-Type: application/x-www-form-urlencoded; charset=UTF-8
x-client-SKU: MSAL.Java
x-client-VER: 1.26.0

grant_type=client_credentials&client_info=1&scope=https://management.azure.com/.default openid profile offline_access&client_id=b1111111-0000-0000-0000-00000000000b&client_secret=<client_secret>
```

```http
HTTP/1.1 401 Unauthorized
Content-Type: application/json

{
  "error": "invalid_client",
  "error_description": "AADSTS7000215: Invalid client secret provided. Ensure the secret being sent in the request is the client secret value, not the client secret ID, for a secret added to app 'b1111111-0000-0000-0000-00000000000b'.\r\nTrace ID: 7ee12043-a01e-48d4-b32e-87b9555b2368\r\nCorrelation ID: 836e8d62-9ff4-4085-a1d7-c2097bf4073d\r\nTimestamp: 2026-11-23 09:17:16Z",
  "error_codes": [
    7000215
  ],
  "timestamp": "2026-11-23 09:17:16Z",
  "trace_id": "7ee12043-a01e-48d4-b32e-87b9555b2368",
  "correlation_id": "836e8d62-9ff4-4085-a1d7-c2097bf4073d",
  "error_uri": "https://login.microsoftonline.com/error?code=7000215"
}
```

The SDK raises it as an exception (from the program's output):

```text
EXCEPTION RuntimeException: java.util.concurrent.ExecutionException: com.microsoft.aad.msal4j.MsalServiceException:
AADSTS7000215: Invalid client secret provided. Ensure the secret being sent in the request is the client secret
value, not the client secret ID, for a secret added to app 'b1111111-0000-0000-0000-00000000000b'.
```

---

## 7. An app as itself: a managed identity

Same idea, no secret in the app. On App Service, Functions or Container Apps the platform sets two
environment variables, and the SDK calls a local endpoint with a header only the platform knows. On
a VM it's IMDS at `169.254.169.254` with `Metadata: true` ([azure.md §4](azure.md#seen-on-the-wire-lab)).
**(lab)**, with the variables set by hand:

```console
$ IDENTITY_ENDPOINT=https://127.0.0.1:4578/msi/token IDENTITY_HEADER=floci-az-msi-secret \
    java ... AzId managed-identity <orders-id clientId>
```

```java
ManagedIdentityCredential mi = new ManagedIdentityCredentialBuilder().clientId(miClientId).build();
call(pipeline(mi, "https://vault.azure.net/.default"), GET, KV + "/secrets/db-password?api-version=7.5", null);
```

How MSAL4J picks the endpoint (`ManagedIdentityClient.getManagedIdentitySource`, 1.26.0) **(code)**:

```java
static ManagedIdentitySourceType getManagedIdentitySource() {
    IEnvironmentVariables environmentVariables = AbstractManagedIdentitySource.getEnvironmentVariables();
    if (!StringHelper.isNullOrBlank(environmentVariables.getEnvironmentVariable(Constants.IDENTITY_ENDPOINT)) &&
            !StringHelper.isNullOrBlank(environmentVariables.getEnvironmentVariable(Constants.IDENTITY_HEADER))) {
        if (!StringHelper.isNullOrBlank(environmentVariables.getEnvironmentVariable(Constants.IDENTITY_SERVER_THUMBPRINT))) {
            return ManagedIdentitySourceType.SERVICE_FABRIC;
        } else {
            return ManagedIdentitySourceType.APP_SERVICE;
        }
    } else if (!StringHelper.isNullOrBlank(environmentVariables.getEnvironmentVariable(Constants.MSI_ENDPOINT))) {
        return ManagedIdentitySourceType.CLOUD_SHELL;
    } else if (!StringHelper.isNullOrBlank(environmentVariables.getEnvironmentVariable(Constants.IDENTITY_ENDPOINT)) &&
            !StringHelper.isNullOrBlank(environmentVariables.getEnvironmentVariable(Constants.IMDS_ENDPOINT))) {
        return ManagedIdentitySourceType.AZURE_ARC;
    } else {
        return ManagedIdentitySourceType.DEFAULT_TO_IMDS;
    }
}
```

and the App Service request (`AppServiceManagedIdentitySource.createManagedIdentityRequest`):

```java
managedIdentityRequest.baseEndpoint = msiEndpoint;                            // IDENTITY_ENDPOINT
managedIdentityRequest.method = HttpMethod.GET;
managedIdentityRequest.headers.put(SECRET_HEADER_NAME, identityHeader);       // X-IDENTITY-HEADER: IDENTITY_HEADER
managedIdentityRequest.queryParameters.put("api-version", APP_SERVICE_MSI_API_VERSION);   // 2019-08-01
managedIdentityRequest.queryParameters.put("resource", resource);
if (this.idType != null && !StringHelper.isNullOrBlank(this.userAssignedId)) {
    managedIdentityRequest.addUserAssignedIdToQuery(this.idType, this.userAssignedId);  // client_id=...
}
```

```http
GET /msi/token?api-version=2019-08-01&resource=https%3A%2F%2Fvault.azure.net&client_id=7099e2ca-3234-401c-a986-d7276d6f39d2 HTTP/1.1
Host: 127.0.0.1:4578
X-IDENTITY-HEADER: floci-az-msi-secret
```

```http
HTTP/1.1 200 OK
Content-Type: application/json

{
  "access_token": "eyJ0eXAiOiJKV1QiLCJhbGciOiJSUz…",
  "client_id": "7099e2ca-3234-401c-a986-d7276d6f39d2",
  "expires_in": "3599",
  "expires_on": "1795429036",
  "resource": "https://vault.azure.net",
  "token_type": "Bearer"
}
```

```json
// response access_token, decoded: header, then payload
{"typ": "JWT", "alg": "RS256", "kid": "TDIu9kVB_L_p0OZcY41a_6y7"}
{
  "iss": "https://127.0.0.1:4578/00000000-0000-0000-0000-000000000002/",
  "sub": "9796b2da-c8fa-408b-a0fa-37bbe44991d3",
  "aud": "https://vault.azure.net",
  "exp": 1795429036,
  "iat": 1795425437,
  "nbf": 1795425437,
  "oid": "9796b2da-c8fa-408b-a0fa-37bbe44991d3",
  "tid": "00000000-0000-0000-0000-000000000002",
  "ver": "1.0",
  "appid": "7099e2ca-3234-401c-a986-d7276d6f39d2",
  "idtyp": "app",
  "xms_mirid": "/subscriptions/00000000-0000-0000-0000-000000000001/resourceGroups/orders-rg/providers/Microsoft.ManagedIdentity/userAssignedIdentities/orders-id",
  "uti": "VwNq2yt_oLebO1thQT_GXg"
}
```

```http
GET /orders-kv-keyvault/secrets/db-password?api-version=7.5 HTTP/1.1
Host: 127.0.0.1:4578
Authorization: Bearer eyJ0eXAiOiJKV1QiLCJhbGciOiJSUz…
```

```http
HTTP/1.1 200 OK
Content-Type: application/json

{
  "value": "s3cr3t",
  "id": "https://orders-kv.vault.azure.net/secrets/db-password/b894c6d2ea854c778392d61f9f2a3573",
  "attributes": {
    "enabled": true,
    "created": 1795425432,
    "updated": 1795425432,
    "recoveryLevel": "Purgeable",
    "recoverableDays": 7
  },
  "contentType": "",
  "tags": {}
}
```

- **No secret, no Entra ID call from the app.** One `GET` to the platform's endpoint, authenticated
  by `X-IDENTITY-HEADER`, which the platform puts only into the app's environment.
- **`oid` = the identity's `principalId`** (the value Alice granted Key Vault Secrets User to in §4),
  `appid` = its `clientId`, and `xms_mirid` names the identity's resource. A v1 token: `appid`, not
  `azp`.
- **Same authorization as §6.** For Azure, a managed identity is a service principal; the role
  assignment on its `principalId` is what Key Vault would check.

---

## 8. On-behalf-of: an API calls another API as the user

When `orders-web` is an **API** that a front end calls with Alice's token, and the API in turn needs
to call Key Vault **as Alice**, it can't forward Alice's token (its `aud` is the API, not Key Vault),
and it shouldn't use its own identity (then Key Vault would see the API, not Alice). It exchanges
Alice's token for a new one: the on-behalf-of (OBO) grant. **(lab, code)**

```java
OnBehalfOfCredential obo = new OnBehalfOfCredentialBuilder()
    .tenantId(TENANT).clientId(WEB_APP).clientSecret(WEB_SECRET)
    .userAssertion(alice.accessToken())       // the token the caller sent, with aud = this API
    .authorityHost(HOST).disableInstanceDiscovery().build();
obo.getTokenSync(new TokenRequestContext().addScopes("https://vault.azure.net/user_impersonation"));
```

azure-identity builds MSAL's `OnBehalfOfParameters` (`IdentityClientBase.buildOBOFlowParameters`)
**(code)**:

```java
OnBehalfOfParameters buildOBOFlowParameters(TokenRequestContext request) {
    OnBehalfOfParameters.OnBehalfOfParametersBuilder builder
        = OnBehalfOfParameters.builder(new HashSet<>(request.getScopes()), options.getUserAssertion())
            .tenant(IdentityUtil.resolveTenantId(tenantId, request, options));
    ...
    return builder.build();
}
```

and MSAL4J turns it into the form fields (`OnBehalfOfRequest.createAuthenticationGrant`, 1.26.0):

```java
Map<String, String> params = new LinkedHashMap<>();
params.put(GrantConstants.GRANT_TYPE_PARAMETER, GrantConstants.JWT_BEARER);   // urn:ietf:params:oauth:grant-type:jwt-bearer
params.put(GrantConstants.ASSERTION_PARAMETER, parameters.userAssertion().getAssertion());
params.put("requested_token_use", "on_behalf_of");
```

The run. It starts with a fresh sign-in for Alice (the same two requests as §3), then the exchange:

```http
GET /00000000-0000-0000-0000-000000000002/oauth2/v2.0/authorize?X-AnchorMailbox=upn%3Aalice%40contoso.test&login_hint=alice%40contoso.test&scope=openid+profile+offline_access+https%3A%2F%2Fmanagement.azure.com%2Fuser_impersonation&response_type=code&redirect_uri=https%3A%2F%2Fapp.local%2Fcallback&code_challenge_method=S256&state=st-1&nonce=n-1&code_challenge=IhmjivdkAJu8V-OYyWir_VXl3gUSe6SEU-chgqYpogY&client_id=a1111111-0000-0000-0000-00000000000a&response_mode=form_post HTTP/1.1
Host: 127.0.0.1:4578
```

```http
HTTP/1.1 302 Found
Location: https://app.local/callback?code=c5f599b9-2612-4d24-9805-0e2108bf2e69&state=st-1
```

```http
POST /00000000-0000-0000-0000-000000000002/oauth2/v2.0/token HTTP/1.1
x-client-VER: 1.26.0
x-client-SKU: MSAL.Java
Content-Type: application/x-www-form-urlencoded; charset=UTF-8
Host: 127.0.0.1:4578
Accept: */*

client_info=1&code=c5f599b9-2612-4d24-9805-0e2108…&redirect_uri=https://app.local/callback&grant_type=authorization_code&code_verifier=NUYwzmF2onabhwj4P3Oz74PV826eKa…&scope=openid profile offline_access https://management.azure.com/user_impersonation&client_id=a1111111-0000-0000-0000-00000000000a&client_secret=<client_secret>
```

```http
HTTP/1.1 200 OK
Content-Type: application/json

{
  "token_type": "Bearer",
  "expires_in": 3599,
  "ext_expires_in": 3599,
  "scope": "https://management.azure.com/user_impersonation",
  "access_token": "eyJ0eXAiOiJKV1QiLCJhbGciOiJSUz…",
  "refresh_token": "OE0r4gpGcXG573r8EHIEHhDsY0QZ82…",
  "id_token": "eyJ0eXAiOiJKV1QiLCJhbGciOiJSUz…",
  "client_info": "eyJ1aWQiOiIxMTExMTExMS1hYWFhLT…"
}
```

```http
POST /00000000-0000-0000-0000-000000000002/oauth2/v2.0/token HTTP/1.1
Host: 127.0.0.1:4578
Content-Type: application/x-www-form-urlencoded; charset=UTF-8
x-client-SKU: MSAL.Java
x-client-VER: 1.26.0

client_info=1&assertion=eyJ0eXAiOiJKV1QiLCJhbGciOiJSUz…&grant_type=urn:ietf:params:oauth:grant-type:jwt-bearer&requested_token_use=on_behalf_of&scope=openid profile offline_access https://vault.azure.net/user_impersonation&client_id=a1111111-0000-0000-0000-00000000000a&client_secret=<client_secret>
```

```http
HTTP/1.1 400 Bad Request
Content-Type: application/json

{
  "error": "unsupported_grant_type",
  "error_description": "AADSTS70003: grant_type 'urn:ietf:params:oauth:grant-type:jwt-bearer' is not supported in this phase\r\nTrace ID: 6e0ed3da-2e77-476c-bc0e-0c76a87cb3a4\r\nCorrelation ID: 9f4b7e2e-b9b2-409b-b5fc-9f4634ba4c81\r\nTimestamp: 2026-11-23 09:17:19Z",
  "error_codes": [
    70003
  ],
  "timestamp": "2026-11-23 09:17:19Z",
  "trace_id": "6e0ed3da-2e77-476c-bc0e-0c76a87cb3a4",
  "correlation_id": "9f4b7e2e-b9b2-409b-b5fc-9f4634ba4c81",
  "error_uri": "https://login.microsoftonline.com/error?code=70003"
}
```

- **The request is the real one**: `grant_type=urn:ietf:params:oauth:grant-type:jwt-bearer`,
  `assertion=<Alice's token>`, `requested_token_use=on_behalf_of`, the new scope, and the **API's own
  client credentials**. Both must be valid: OBO is only for confidential clients.
- **floci-az refuses it** with `AADSTS70003` (grant not supported). In Azure, Entra checks that the
  assertion's `aud` is the calling app, and returns a token with **Alice's** `oid`, `scp` for Key
  Vault, and `azp` = the API **(docs)**. Key Vault then decides on Alice's roles, so the API can't
  read anything Alice can't.
- **Contrast with the app's own identity:** had the API used `ClientSecretCredential` (§6), Key Vault
  would see the API's service principal and its roles, regardless of who the caller was.

---

## 9. `DefaultAzureCredential`: which credential it picks

One credential class for all environments. It tries a fixed list in order and uses the first one
that returns a token. azure-identity 1.19.0, `DefaultAzureCredentialBuilder` **(code)**:

```java
// Default case: full chain (prod + dev)
addProdCredentials(credentials);
addDevCredentials(credentials);
...
private void addProdCredentials(List<TokenCredential> credentials) {
    credentials.add(new EnvironmentCredential(identityClientOptions.clone()));
    credentials.add(getWorkloadIdentityCredential());
    credentials.add(new ManagedIdentityCredential(managedIdentityClientId, managedIdentityResourceId, null,
        identityClientOptions.clone()));
}
private void addDevCredentials(List<TokenCredential> credentials) {
    credentials.add(new IntelliJCredential(tenantId, identityClientOptions.clone()));
    credentials.add(new VisualStudioCodeCredential(tenantId, identityClientOptions.clone()));
    credentials.add(new AzureCliCredential(tenantId, identityClientOptions.clone()));
    credentials.add(new AzurePowerShellCredential(tenantId, identityClientOptions.clone()));
    credentials.add(new AzureDeveloperCliCredential(tenantId, identityClientOptions.clone()));
    credentials.add(new BrokerCredential(tenantId));
}
```

`AZURE_TOKEN_CREDENTIALS=prod`, `dev` or one class name (e.g. `ManagedIdentityCredential`) limits
the chain. `EnvironmentCredential` chooses by which variables are set: `AZURE_CLIENT_SECRET` →
`ClientSecretCredential`; else `AZURE_CLIENT_CERTIFICATE_PATH` → `ClientCertificateCredential`; else
`AZURE_USERNAME` + `AZURE_PASSWORD` → `UsernamePasswordCredential`.

| Where the code runs | Credential that answers | Identity in the token |
|---|---|---|
| a developer laptop, `az login` done | `AzureCliCredential` | **the developer** (a user token) |
| CI with `AZURE_CLIENT_ID` + `AZURE_CLIENT_SECRET` | `EnvironmentCredential` | that app's service principal |
| an AKS Pod with Workload Identity | `WorkloadIdentityCredential` | the federated managed identity / app ([workload-identity.md](workload-identity.md)) |
| App Service, a VM, Container Apps | `ManagedIdentityCredential` | the resource's managed identity |

The same code therefore runs as a **user** on a laptop and as a **service principal** in Azure,
which is why a local test can pass with the developer's broad rights and fail in Azure with the
identity's narrower ones. Run with the worker's variables set **(lab)**:

```console
$ AZURE_TENANT_ID=00000000-0000-0000-0000-000000000002 AZURE_CLIENT_ID=b1111111-0000-0000-0000-00000000000b \
  AZURE_CLIENT_SECRET=<secret> AZURE_AUTHORITY_HOST=https://127.0.0.1:4578 java ... AzId default-chain
token expires 2026-11-23T10:17:20Z
```

```http
POST /00000000-0000-0000-0000-000000000002/oauth2/v2.0/token HTTP/1.1
Host: 127.0.0.1:4578
Content-Type: application/x-www-form-urlencoded; charset=UTF-8
x-client-SKU: MSAL.Java
x-client-VER: 1.26.0

grant_type=client_credentials&client_info=1&scope=https://management.azure.com/.default openid profile offline_access&client_id=b1111111-0000-0000-0000-00000000000b&client_secret=<client_secret>
```

```http
HTTP/1.1 200 OK
Content-Type: application/json

{
  "token_type": "Bearer",
  "expires_in": 3599,
  "ext_expires_in": 3599,
  "access_token": "eyJ0eXAiOiJKV1QiLCJhbGciOiJSUz…"
}
```

One request: `EnvironmentCredential` answered first, so nothing after it in the chain was tried.

---

## 10. Authorization: what Azure checks when a call arrives

### 10.1 Authentication at ARM (lab, `VALIDATE_TOKENS` on)

Before roles, ARM checks the token itself. With floci-az's token validation on:

**No token:**

```http
GET /subscriptions/00000000-0000-0000-0000-000000000001/resourcegroups?api-version=2021-04-01 HTTP/1.1
Host: 127.0.0.1:4578
Accept: */*
```

```http
HTTP/1.1 401 Unauthorized
Content-Type: application/json
WWW-Authenticate: Bearer authorization_uri="https://127.0.0.1:4578/", error="invalid_token", error_description="The authentication failed because of missing 'Authorization' header."

{
  "error": {
    "message": "The authentication failed because of missing 'Authorization' header.",
    "code": "AuthenticationFailed"
  }
}
```

**A user token from the v2 endpoint** (floci-az sets `aud` = the client id):

```http
POST /00000000-0000-0000-0000-000000000002/oauth2/v2.0/token HTTP/1.1
Host: 127.0.0.1:4578
Accept: */*
Content-Type: application/x-www-form-urlencoded

grant_type=password&client_id=a1111111-0000-0000-0000-00000000000a&username=alice@contoso.test&password=<password>&scope=https://management.azure.com/.default
```

```http
HTTP/1.1 200 OK
Content-Type: application/json

{
  "token_type": "Bearer",
  "expires_in": 3599,
  "ext_expires_in": 3599,
  "scope": "https://management.azure.com/.default",
  "access_token": "eyJ0eXAiOiJKV1QiLCJhbGciOiJSUz…"
}
```

```json
// response access_token, decoded: header, then payload
{"typ": "JWT", "alg": "RS256", "kid": "NDeSRPwMnCLYwiVnZsr8jq4Y"}
{
  "iss": "https://127.0.0.1:4578/00000000-0000-0000-0000-000000000002/v2.0",
  "sub": "11111111-aaaa-0000-0000-000000000001",
  "aud": "a1111111-0000-0000-0000-00000000000a",
  "exp": 1795428597,
  "iat": 1795424998,
  "nbf": 1795424998,
  "oid": "11111111-aaaa-0000-0000-000000000001",
  "tid": "00000000-0000-0000-0000-000000000002",
  "ver": "2.0",
  "azp": "a1111111-0000-0000-0000-00000000000a",
  "scp": "https://management.azure.com/.default",
  "preferred_username": "alice@contoso.test",
  "uti": "vuA6Ofn2NWsCFeqaqeOI0w"
}
```

```http
GET /subscriptions/00000000-0000-0000-0000-000000000001/resourcegroups?api-version=2021-04-01 HTTP/1.1
Host: 127.0.0.1:4578
Accept: */*
Authorization: Bearer eyJ0eXAiOiJKV1QiLCJhbGciOiJSUz…
```

```http
HTTP/1.1 401 Unauthorized
Content-Type: application/json
WWW-Authenticate: Bearer authorization_uri="https://127.0.0.1:4578/", error="invalid_token", error_description="The access token audience is invalid."

{
  "error": {
    "message": "The access token was issued for audience 'a1111111-0000-0000-0000-00000000000a', which this service does not accept.",
    "code": "InvalidAuthenticationTokenAudience"
  }
}
```

**A user token from the v1 endpoint** (`resource=https://management.azure.com/`, so `aud` is ARM):

```http
POST /00000000-0000-0000-0000-000000000002/oauth2/token HTTP/1.1
Host: 127.0.0.1:4578
Accept: */*
Content-Type: application/x-www-form-urlencoded

grant_type=password&client_id=a1111111-0000-0000-0000-00000000000a&username=alice@contoso.test&password=<password>&resource=https://management.azure.com/
```

```http
HTTP/1.1 200 OK
Content-Type: application/json

{
  "token_type": "Bearer",
  "expires_in": 3599,
  "ext_expires_in": 3599,
  "scope": "https://management.azure.com/",
  "access_token": "eyJ0eXAiOiJKV1QiLCJhbGciOiJSUz…"
}
```

```json
// response access_token, decoded: header, then payload
{"typ": "JWT", "alg": "RS256", "kid": "NDeSRPwMnCLYwiVnZsr8jq4Y"}
{
  "iss": "https://127.0.0.1:4578/00000000-0000-0000-0000-000000000002/",
  "sub": "11111111-aaaa-0000-0000-000000000001",
  "aud": "https://management.azure.com/",
  "exp": 1795428599,
  "iat": 1795425000,
  "nbf": 1795425000,
  "oid": "11111111-aaaa-0000-0000-000000000001",
  "tid": "00000000-0000-0000-0000-000000000002",
  "ver": "1.0",
  "appid": "a1111111-0000-0000-0000-00000000000a",
  "scp": "https://management.azure.com/",
  "upn": "alice@contoso.test",
  "unique_name": "alice@contoso.test",
  "uti": "kTKk_bVT4wRl0fbr1wXmDA"
}
```

```http
GET /subscriptions/00000000-0000-0000-0000-000000000001/resourcegroups?api-version=2021-04-01 HTTP/1.1
Host: 127.0.0.1:4578
Accept: */*
Authorization: Bearer eyJ0eXAiOiJKV1QiLCJhbGciOiJSUz…
```

```http
HTTP/1.1 200 OK
Content-Type: application/json;charset=UTF-8

{
  "value": []
}
```

**An app token** (`client_credentials`):

```http
POST /00000000-0000-0000-0000-000000000002/oauth2/v2.0/token HTTP/1.1
Host: 127.0.0.1:4578
Accept: */*
Content-Type: application/x-www-form-urlencoded

grant_type=client_credentials&client_id=b1111111-0000-0000-0000-00000000000b&client_secret=<client_secret>&scope=https://management.azure.com/.default
```

```http
HTTP/1.1 200 OK
Content-Type: application/json

{
  "token_type": "Bearer",
  "expires_in": 3599,
  "ext_expires_in": 3599,
  "access_token": "eyJ0eXAiOiJKV1QiLCJhbGciOiJSUz…"
}
```

```http
GET /subscriptions/00000000-0000-0000-0000-000000000001/resourcegroups?api-version=2021-04-01 HTTP/1.1
Host: 127.0.0.1:4578
Accept: */*
Authorization: Bearer eyJ0eXAiOiJKV1QiLCJhbGciOiJSUz…
```

```http
HTTP/1.1 200 OK
Content-Type: application/json;charset=UTF-8

{
  "value": []
}
```

So ARM accepts a token only if it's signed by Entra, from the right issuer, and **for ARM**
(`aud`). A token for Key Vault or for an app can't be replayed against ARM.

### 10.2 Roles (docs; floci-az doesn't enforce them)

After authentication, ARM (and each data plane) evaluates the token's principal against role
assignments **(docs)**:

```text
token oid ──► every role assignment whose principalId is the oid, or a group the oid is in
          ──► keep those whose scope is the target or one of its parents
          ──► union of their roles' actions (control plane) / dataActions (data plane)
          ──► minus deny assignments
          ──► is the requested operation in it?  yes → run it    no → 403 AuthorizationFailed
```

| Caller | Principal checked | Ceiling |
|---|---|---|
| a user directly (portal, `az` CLI) | the user | the user's roles |
| an app with a **delegated** token (§3–5) | the **user** (`oid`) | the user's roles, and only APIs in the token's `scp` |
| an app with an **app** token (§6, §7) | the **service principal** | its roles (and app roles in `roles` for APIs like Graph) |
| an API using **OBO** (§8) | the **user** | the user's roles |

Bob (Reader on `orders-rg` through `orders-readers`) signed in and deleted the managed identity
**(lab)**:

```http
GET /00000000-0000-0000-0000-000000000002/oauth2/v2.0/authorize?X-AnchorMailbox=upn%3Abob%40contoso.test&login_hint=bob%40contoso.test&scope=openid+profile+offline_access+https%3A%2F%2Fmanagement.azure.com%2Fuser_impersonation&response_type=code&redirect_uri=https%3A%2F%2Fapp.local%2Fcallback&code_challenge_method=S256&state=st-1&nonce=n-1&code_challenge=UjZenorFrc3jQfYpRqVG4Pf7VT-9_Pys-ro56B0AqOc&client_id=a1111111-0000-0000-0000-00000000000a&response_mode=form_post HTTP/1.1
Host: 127.0.0.1:4578
```

```http
HTTP/1.1 302 Found
Location: https://app.local/callback?code=836080ce-cb31-4fc8-9b09-abf62ec94e8b&state=st-1
```

```http
POST /00000000-0000-0000-0000-000000000002/oauth2/v2.0/token HTTP/1.1
x-client-VER: 1.26.0
x-client-SKU: MSAL.Java
Content-Type: application/x-www-form-urlencoded; charset=UTF-8
Host: 127.0.0.1:4578
Accept: */*

client_info=1&code=836080ce-cb31-4fc8-9b09-abf62e…&redirect_uri=https://app.local/callback&grant_type=authorization_code&code_verifier=m6eNoKkyYLbtx8DtOT0xnXtuAiRZBJ…&scope=openid profile offline_access https://management.azure.com/user_impersonation&client_id=a1111111-0000-0000-0000-00000000000a&client_secret=<client_secret>
```

```http
HTTP/1.1 200 OK
Content-Type: application/json

{
  "token_type": "Bearer",
  "expires_in": 3599,
  "ext_expires_in": 3599,
  "scope": "https://management.azure.com/user_impersonation",
  "access_token": "eyJ0eXAiOiJKV1QiLCJhbGciOiJSUz…",
  "refresh_token": "3MhjnPPtjWjjGzpLu7wq013kL0EJig…",
  "id_token": "eyJ0eXAiOiJKV1QiLCJhbGciOiJSUz…",
  "client_info": "eyJ1aWQiOiIyMjIyMjIyMi1iYmJiLT…"
}
```

```json
// response access_token, decoded: header, then payload
{"typ": "JWT", "alg": "RS256", "kid": "TDIu9kVB_L_p0OZcY41a_6y7"}
{
  "iss": "https://127.0.0.1:4578/00000000-0000-0000-0000-000000000002/v2.0",
  "sub": "22222222-bbbb-0000-0000-000000000002",
  "aud": "a1111111-0000-0000-0000-00000000000a",
  "exp": 1795429042,
  "iat": 1795425443,
  "nbf": 1795425443,
  "oid": "22222222-bbbb-0000-0000-000000000002",
  "tid": "00000000-0000-0000-0000-000000000002",
  "ver": "2.0",
  "azp": "a1111111-0000-0000-0000-00000000000a",
  "scp": "openid profile https://management.azure.com/user_impersonation",
  "preferred_username": "bob@contoso.test",
  "uti": "68Faqi6V4a6T1Sfcnt8ixA"
}
```

```json
// response id_token, decoded: header, then payload
{"typ": "JWT", "alg": "RS256", "kid": "TDIu9kVB_L_p0OZcY41a_6y7"}
{
  "iss": "https://127.0.0.1:4578/00000000-0000-0000-0000-000000000002/v2.0",
  "sub": "22222222-bbbb-0000-0000-000000000002",
  "aud": "a1111111-0000-0000-0000-00000000000a",
  "exp": 1795429042,
  "iat": 1795425443,
  "nbf": 1795425443,
  "oid": "22222222-bbbb-0000-0000-000000000002",
  "tid": "00000000-0000-0000-0000-000000000002",
  "ver": "2.0",
  "azp": "a1111111-0000-0000-0000-00000000000a",
  "scp": "https://management.azure.com/user_impersonation",
  "nonce": "n-1",
  "name": "Bob",
  "preferred_username": "bob@contoso.test",
  "uti": "OczuiyFoN0uzt8WCjj5-zw"
}
```

```http
GET /subscriptions/00000000-0000-0000-0000-000000000001/resourceGroups/orders-rg?api-version=2021-04-01 HTTP/1.1
Host: 127.0.0.1:4578
Authorization: Bearer eyJ0eXAiOiJKV1QiLCJhbGciOiJSUz…
```

```http
HTTP/1.1 200 OK
Content-Type: application/json;charset=UTF-8

{
  "id": "/subscriptions/00000000-0000-0000-0000-000000000001/resourceGroups/orders-rg",
  "name": "orders-rg",
  "type": "Microsoft.Resources/resourceGroups",
  "location": "westeurope",
  "properties": {
    "provisioningState": "Succeeded"
  }
}
```

```http
DELETE /subscriptions/00000000-0000-0000-0000-000000000001/resourceGroups/orders-rg/providers/Microsoft.ManagedIdentity/userAssignedIdentities/orders-id?api-version=2023-01-31 HTTP/1.1
Host: 127.0.0.1:4578
Authorization: Bearer eyJ0eXAiOiJKV1QiLCJhbGciOiJSUz…
```

```http
HTTP/1.1 200 OK
```

floci-az returned `200` for the `DELETE`: it stores role assignments but evaluates none. In Azure,
Reader has `actions: */read` only, so the `GET` succeeds and the `DELETE` is refused with
`403 AuthorizationFailed`: "The client '…' with object id '22222222-bbbb-…' does not have
authorization to perform action 'Microsoft.ManagedIdentity/userAssignedIdentities/delete' over scope
'…/orders-id' or the scope is invalid" **(docs; message shape, not captured)**.

---

## 11. The tokens side by side (lab)

| Token | `oid` | `idtyp` | `scp` / `roles` | `azp` / `appid` | `aud` |
|---|---|---|---|---|---|
| Alice through `orders-web` (§3) | Alice | — | `scp: … user_impersonation` | `azp` orders-web | orders-web's client id (floci-az; Azure: ARM) |
| Alice, refreshed for Key Vault (§5) | Alice | — | `scp: … vault.azure.net/user_impersonation` | `azp` orders-web | same floci-az shortcut |
| `orders-worker`, client credentials (§6) | worker SP | `app` | none (would be `roles`) | `azp` orders-worker | `https://management.azure.com` |
| `orders-id`, managed identity (§7) | identity's `principalId` | `app` | none | `appid` = its `clientId` | `https://vault.azure.net` |
| OBO result (§8, docs) | Alice | — | `scp` for Key Vault | `azp` = the API | Key Vault |

How to tell them apart in code that receives a token: `idtyp: app` (or no `scp`) means an app acting
as itself; a `scp` claim means delegated, and `oid` is the user.

**Where floci-az differs from Azure in this run:**

| floci-az nightly | Azure |
|---|---|
| v2 user tokens have `aud` = the client id, so ARM (with validation) rejects them | `aud` = the API in the scope |
| client-credentials token `aud` = the first scope, `openid` (MSAL adds it) | `aud` = the `/.default` resource |
| an app's token `oid` = its application object id | a separate service principal object id |
| role assignments stored, never evaluated | evaluated on every call |
| no consent, no `roles` claim | consent and app role assignments |
| OBO not implemented | supported for confidential clients |
| Key Vault accepts any token | checks `aud`, signature and data-plane roles |
| ID token carries `scp` | ID tokens have no `scp` |

## Related notes

- [azure.md §4](azure.md#4-identity): users, service principals, managed identities, the three permission systems
- [azure-local-lab.md](azure-local-lab.md): floci-az 0.16.0 service by service
- [workload-identity.md](workload-identity.md): Workload Identity, federated tokens, and the Java SDK's client assertion
- [oauth-client-authentication.md](oauth-client-authentication.md): client secrets, certificates and assertions in OAuth
