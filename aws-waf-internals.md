# AWS WAF, Shield and CloudFront: how they work underneath

[aws-waf-placement.md](aws-waf-placement.md) covers *where* to put WAF. This note covers *what each layer actually does* to a packet or request, from the network edge down to a single WAF rule. AWS doesn't publish its implementation, so everything here is the behaviour AWS documents, plus the design posts it has published.

## 0. The whole path, layer by layer

```
 Internet
    │
    ▼
 ┌─ Shield (L3/L4) ── at every AWS point of ingress ──────────────────────────────────┐
 │  packet validation · ACLs + shapers · suspicion scoring · stateless SYN proxy       │  §1
 │  edge (CloudFront, Route 53): inline, every packet, always on                       │
 │  regional (ALB, EIP, NLB): per-minute detection against the resource's capacity     │
 └──────────────────────────────────────┬──────────────────────────────────────────────┘
                                        ▼
 ┌─ CloudFront POP ───────────────────────────────────────────────────────────────────┐
 │  TCP/QUIC + TLS terminated here; only well-formed HTTP passes                       │  §2
 │  ┌─ AWS WAF (web ACL, CLOUDFRONT scope) ─────────────────────────────────────────┐  │
 │  │  1. take the parts it may inspect (body ≤16–64 KB, headers ≤8 KB/200, …)      │  │  §4
 │  │  2. per rule: transform → match → action (terminating or not) → labels       │  │  §4–5
 │  │  3. rate counters, tokens (Challenge/CAPTCHA), managed intel (IP rep, DDoS)   │  │  §6–9
 │  │  4. no terminating match → default action                                     │  │
 │  └───────────────────────────────────────────────────────────────────────────────┘  │
 │  cache: POP → regional edge cache → Origin Shield; request collapsing               │  §2
 └──────────────────────────────────────┬──────────────────────────────────────────────┘
                                        ▼ persistent connections, X-Forwarded-For appended
 ┌─ Region: ALB (+ optional regional WAF) ─► targets ─────────────────────────────────┐
 └─────────────────────────────────────────────────────────────────────────────────────┘
```

(In the newer WAF console a web ACL is called a **protection pack**; the docs use both names.)

---

## 1. Shield: the packet layer

### 1.1 Where mitigation happens

- Shield has DDoS mitigation systems at **every point where the internet enters AWS**. When it detects an attack, it reroutes traffic through the mitigation systems **in the same location**. There are no remote "scrubbing centres" and no observable added latency. Total capacity is **more than 100 Tbps** across all Regions and edge locations.
- **At the edge (CloudFront, Route 53):** every packet is inspected by a **fully inline** system, all the time, whether or not you pay for Shield Advanced. Attacks are mitigated immediately, without waiting for detection to signal.
- **In a Region (ALB, CLB, EIP, NLB, Global Accelerator):** traffic to each resource is evaluated **every minute**. If it's elevated, Shield checks the resource's **capacity** and places a mitigation if that's exceeded:
  - for EC2 and EIPs, capacity comes from the instance type and size, and whether enhanced networking is on;
  - for an ALB or CLB, from each load balancer node;
  - for an NLB, from the underlying infrastructure.

  A mitigation has a TTL that's extended while the attack lasts. AWS says these regional mitigations **reduce** DDoS traffic but **may not eliminate it**.

### 1.2 The mitigation toolbox

| Feature | What it does |
|---|---|
| **Packet validation** | Drops packets that aren't valid for their protocol (IP, TCP headers and options, UDP, ICMP, DNS, NTP) |
| **ACLs and shapers** | An ACL matches on port, protocol, TCP flags, destination, source country, or a byte pattern in the payload. It either drops the traffic or hands it to a **shaper**, which caps its packet rate. Known attack shapes get tight limits; expected traffic gets dedicated allocations |
| **Suspicion scoring** | Every packet gets a score: lower if it looks like known-good traffic for this resource, higher if it has known-bad attributes. Under rate limiting, **the highest scores are dropped first**. This is how it handles zero-day floods without many false positives |
| **TCP SYN proxy** | Answers new connections with **SYN cookies** before passing them on. It's **stateless**: it hands connection state to the AWS service instead of staying in the middle, so the largest SYN floods can't exhaust its state. Available on CloudFront, Route 53 and Global Accelerator |
| **Rate distribution** | A regional mitigation starts with the same limit at every location (for example, 100,000 pps everywhere). Then it adjusts each location's share to match where traffic really enters, so legitimate traffic arriving unevenly isn't throttled |

### 1.3 What Shield Advanced adds to detection

- **Lower thresholds:** it mitigates at **half** the calculated capacity.
- **Exponential TTL:** resources hit often, or by short bursts, keep their mitigation longer.
- **Health-based detection:** with a Route 53 health check attached, an *unhealthy* app can be mitigated before Shield is confident it's an attack. A *healthy* app needs more confidence first.
- **L7 detection:** it builds a baseline from the web ACL's request data and needs a **statistically significant change in both volume and composition** to declare an event. It compares against historical windows, so a daily sale at the same hour doesn't trigger it. Associate the web ACL **at least 24 hours** before a planned event; detection is most accurate after **30 days**.

### 1.4 The attacks it's built for

- **UDP reflection and amplification:** the attacker spoofs the victim's IP as the source of a small UDP request to a public server, and the larger reply goes to the victim. DNS amplifies **28–54×** (a 64-byte request becomes about 3,400 bytes). Memcached can reach roughly **51,000×**: a 15-byte request can produce up to 750 KB of response. It only works where networks don't filter spoofed sources (BCP38).
- **SYN floods:** many SYNs, no final ACK, exhausting connection state. This is answered by SYN cookies and the stateless SYN proxy.
- **TCP middlebox reflection:** non-compliant firewalls and middleboxes can be tricked into reflecting TCP. It has been seen since 2022, and its amplification can exceed memcached's.
- **Application-layer (L7):** HTTP floods; **cache-busting** (random query strings so every request misses the CDN cache); WordPress XML-RPC pingback floods; DNS query floods with random subdomains; TLS handshake and renegotiation abuse. The trend is **shorter, more intense** attacks that need **fewer** requests, which is why detection now has to work in seconds (§8).

---

## 2. CloudFront: the HTTP edge as a shield

### 2.1 What it refuses to pass on

- Each request must complete a proper **TCP or QUIC** session, a **TLS handshake**, and be **well-formed HTTP**. So UDP reflection, SYN floods and malformed requests never reach the origin.
- It closes **slow-read and slow-write** attackers (Slowloris) and handles **HTTP/2 rapid reset**. It protects against **request smuggling, desync and splitting**.
- It keeps **persistent connections** to the origin, so a flood of client connections doesn't become a flood of origin connections.
- **Anycast plus DNS steering** keeps an attack near its source and away from other locations.
- **WAF on CloudFront scales with the POP's capacity**: regional WAF capacity limits don't apply.

### 2.2 The cache hierarchy is a DDoS defence

```
 viewer ─► POP (edge location) ──miss──► Regional Edge Cache ──miss──► [Origin Shield] ──► origin
              ▲ hit                          ▲ hit                         ▲ hit
   PUT/POST/PATCH/OPTIONS/DELETE and dynamic requests skip the regional cache and go POP → origin
```

- **Request collapsing:** when many viewers ask for the same uncached object at once, one request goes to the origin and the rest wait for it. It's **disabled** if the minimum TTL is 0 or cookies are forwarded.
- **Error caching:** some 4xx and 5xx responses are cached for **10 seconds by default**, so retry storms don't hammer a struggling origin.
- **The cache hit ratio decides whether a flood hurts.** `/` is statistically the **most targeted URI**. Even "dynamic" pages can usually be **micro-cached** for 1–2 seconds. The whitepaper also suggests a *split TTL*: `max-age=0` for browsers and a non-zero minimum TTL at the CDN. Forwarding *all* headers disables caching.

### 2.3 What CloudFront tells the origin about the client

- If the viewer sent no `X-Forwarded-For`, CloudFront creates one with the IP of the TCP peer.
- If the viewer **did** send one, CloudFront **appends** the real peer IP to the end: `X-Forwarded-For: <whatever the client wrote>, <real IP>`.
- An ALB or other proxy behind CloudFront appends **the CloudFront edge IP** after that. The origin sees `…, <real client IP>, <CloudFront edge IP>`.
- CloudFront forwards to custom origins over **HTTP/1.1**. It strips `X-Real-IP` and `X-Forwarded-Proto` from the viewer request.

This is why a regional WAF that takes the **first** IP in `X-Forwarded-For` reads a value the client controls (§6).

---

## 3. Route 53: shuffle sharding

Route 53's DDoS design shows the most re-used idea inside AWS edge services (Builders' Library):

- **No sharding:** one bad customer (a poison request or a flood) can cascade through every worker. The impact is 100%.
- **Plain sharding:** split 8 workers into 4 shards of 2. A problem takes out one shard, a **25%** impact.
- **Shuffle sharding:** give each customer a **random combination** of 2 of the 8 workers. There are C(8,2) = **28** possible shards, so the impact falls to about **1/28**. A neighbour that shares one worker with the victim still has its other worker. That only helps if **clients retry**.

```
 workers:      W1   W2   W3   W4   W5   W6   W7   W8
 "rainbow":    ██             ██                          ← under attack: W1 + W4 overloaded
 "rose":       ██                                  ██     ← shares W1, still served by W8
 "sunflower":            ██                  ██           ← shares nothing, unaffected
```

Route 53 at scale:

- **2,048 virtual name servers** (virtual, so they can be moved between physical hosts).
- Each hosted zone gets **4** of them: C(2048,4) ≈ **730 billion** combinations.
- No two domains share more than **2** name servers.
- An attacked domain's four servers spike, no other customer notices, and the target can be moved onto dedicated attack capacity.
- Combined with anycast, which spreads attack traffic across many edge locations, and suspicion scoring of DNS queries (§1.2).

AWS uses the same pattern in many other systems, sometimes recursively (sharding a customer's customers). The open-source **Infima** library implements it.

---

## 4. Inside WAF: what a rule actually sees

### 4.1 WAF doesn't get the whole request

The host service (CloudFront, ALB, …) forwards only part of each request to WAF:

| Component | Limit WAF can inspect |
|---|---|
| Body, JSON body | **ALB, AppSync: first 8 KB.** CloudFront, API Gateway, Cognito, App Runner, Verified Access, AgentCore Gateway: 16 KB by default, configurable up to **64 KB** |
| All headers | first **8 KB** *and* first **200** headers, whichever comes first. A single named header is inspected in full |
| Cookies | first **8 KB** and first **200** cookies |

When WAF allows a request, **the whole request** goes to the backend, including the uninspected bytes. Every rule that reads one of these parts has an **oversize handling** setting:

- **Continue** (the API default): inspect only what's within the limit. Anything after byte 8 KB (or 16 KB) is invisible to the rule.
- **Match:** treat an oversize part as matching. With action Block, this blocks oversize requests.
- **No match:** treat it as not matching and move on.

So an attacker can pad a body with 8 KB of junk and put the payload after it. On an ALB, a rule set to *Continue* never sees the payload. AWS's advice: explicitly allow the few endpoints that need big bodies, then put a **"size > limit → Block"** rule *before* the inspection rules (including managed rule groups you can't edit). The Core rule set's `SizeRestrictions_BODY` does a version of this, which is why it blocks uploads.

### 4.2 Text transformations: normalise before matching

Before a string or regex match, a rule can rewrite the inspected component. Each transformation costs **10 WCU**; they apply in the order you list them. For a JSON body, they run **after** the JSON is parsed. Examples:

- `URL_DECODE`, `HTML_ENTITY_DECODE`, `BASE64_DECODE` and `BASE64_DECODE_EXT` (a lenient version that ignores invalid characters);
- `LOWERCASE`, `COMPRESS_WHITE_SPACE`;
- `CMD_LINE`: deletes `\ " ' ^`, removes spaces before `/ (`, turns `, ;` into spaces, collapses spaces and lowercases. So `"C^m^D" /c …` and `cmd /c …` look the same.
- `CMD_LINE_UNIX`: the same idea for shell syntax.

This is how a signature WAF beats encoding tricks: **decode and normalise, then match**. The built-in **SQLi** and **XSS** statements are detectors, not regexes you write. SQLi has a sensitivity of **LOW** (20 WCU) or **HIGH** (30 WCU, recommended, more false positives). Inspecting the JSON body doubles the cost.

### 4.3 Evaluation: first terminating match wins

```
 request ─► rule (lowest priority number)
              │ no match ─────────────────────────────────────────► next rule
              │ match:
              │   Count ......................... add labels/headers ─► next rule
              │   Challenge/CAPTCHA + valid token  like Count .......► next rule
              │   Challenge/CAPTCHA + no/bad token ─► STOP: 202 challenge / 405 CAPTCHA
              │   Block ......................... STOP: 403 (or custom response)
              │   Allow ......................... STOP: forward to origin
              ▼
         … all rules done, nothing terminated ─► web ACL DEFAULT action (Allow or Block)
```

- **Priority** is an integer, unique within its container, and needn't be consecutive. A **rule group** is evaluated at its own priority slot, with its rules in their own internal order. Rule1 (priority 0), then group A's rules, then Rule2 (priority 200), then group B's rules.
- **Allow and Block are terminating.** Count isn't. Challenge and CAPTCHA are *conditionally* terminating, depending on the token (§7).
- **Labels** are metadata a matching rule attaches to the request. They last until the evaluation ends, and **only later rules can read them**. Every AWS managed rule adds labels. This is how you make exceptions to rules you can't edit: override the managed rule to **Count**, then add your own rule *after* it that matches the label and decides.
- **A terminated request skips everything after it.** That means later **paid** rule groups (Bot Control, Fraud Control) aren't billed for it, and their labels never appear in the logs.
- **Default action:** with *Allow*, you write rules that block bad traffic; with *Block*, you write rules that let good traffic in.

### 4.4 WCU: a configuration-time cost model

**Web ACL capacity units** measure how expensive your rules are to run. They're checked when you **save** the configuration, and they **don't change how requests are inspected**.

- **Limits:** a web ACL can use at most **5,000 WCU**; the base price includes **1,500**, and tiered fees apply above that. A rule group can use at most 5,000 too.
- **Rule groups:** a group's capacity is **fixed when it's created**. A web ACL is always charged that fixed number, however much the rules inside actually use.
- **Shared work:** WAF can share work between rules, for example applying the same transformation to the same component once. That's why actual use can be *less* than the sum of the rules. `CheckCapacity` in the API tells you the number before you save.
- **Example costs:**
  - text transformation: 10 each;
  - rate-based rule: 2, plus 30 for each custom aggregation key;
  - SQLi: 20 (low sensitivity) or 30 (high);
  - Anti-DDoS rule group: 50;
  - Amazon IP reputation list: 25;
  - Anonymous IP list: 50.

---

## 5. Rate-based rules: an estimate, not a counter

- **Settings:**
  - **Evaluation window:** 60, 120, **300** (default) or 600 seconds. It's how far back WAF looks, **not** how often it checks; checks run frequently on their own schedule.
  - **Limit:** at least 10.
  - **Aggregation key:** IP, forwarded IP, or custom keys (header, URI, query, label, JA3/JA4, …). The limit applies **per key value**.
  - **Action:** anything except Allow.
  - **Scope-down statement:** limits which requests are counted. It's required with "count all".
- **How the rate is calculated:** WAF *estimates* it with an algorithm that **weights recent requests more heavily**, so limiting happens *near* the limit, not exactly at it.
- **Delay:** traffic can go over the limit for **up to several minutes** before WAF reacts, and limiting can continue for a while after traffic drops. Usually the delay is under **30 seconds**. (The test loop in the placement note expects this.)
- **Changing a setting resets the counters**, which can pause limiting for up to a minute.
- **Each use is a separate counter.** The same rule in two web ACLs, or one rule group used in two places, counts separately.
- **Layers from the whitepaper:**
  1. a blanket rule (for example 500 requests per 5 minutes per IP);
  2. stricter rules scoped with scope-down statements:
     - pages without a file extension, which includes `/`;
     - `POST` requests;
     - `/login`, `/signup`, `/forgotpassword`;
     - countries you don't serve;
  3. a **Host + URI + query + IP** key, because botnets hammer one URL;
  4. a **JA3/JA4** key (a hash of the TLS Client Hello) to catch automation that rotates IPs but keeps its TLS stack.

  Rate-based rules in Block mode are **required** for Shield Advanced cost-protection claims.

---

## 6. Forwarded IP: exact semantics

These rule types can use an IP from a header instead of the connection: IP set match, geo match, ASN match and rate-based rules.

| Situation | What WAF does |
|---|---|
| Header **missing** | The rule **isn't applied at all**: no action, no fallback. The statement counts as "No match", and a NOT around it turns that into "Match" |
| Header present but the IP is **malformed or invalid** | The **fallback** you configured: MATCH or NO_MATCH |
| Geo, ASN or rate-based with a list `a, b, c` | Uses **`a`, the first address**, which is the one the client wrote |
| IP set match | You choose **first, last or any**. "Any" checks up to 10 addresses; if there are more, the **last 10** |
| Separator isn't a comma | Treated as malformed |
| Rate-based rule with a scope-down | The scope-down **doesn't inherit** the forwarded-IP config; set it on each statement |

- **CDN auto-detection:** Bot Control and Anti-DDoS recognise requests from **CloudFront, Akamai, Cloudflare and Fastly** IP ranges, and read the client IP from that CDN's own header (for example `Fastly-Client-IP`). **Your own** IP, geo, ASN and rate rules don't; they still need forwarded IP configured.
- **"Block invalid headers" pattern:** action Block plus fallback MATCH blocks any request whose header doesn't contain a valid IP.

So on an ALB behind CloudFront, the client controls the first IP in the header, and the real client IP is second to last, before the CloudFront edge IP the ALB appends (§2.3). An IP set rule can use **last** if you know exactly how many hops there are. Geo, ASN and rate-based rules can't. That's the technical reason the placement note says to keep IP-based rules at the edge.

---

## 7. Tokens, Challenge and CAPTCHA

- A **token** is an **encrypted cookie, `aws-waf-token`**, that identifies one client session. It's sometimes called a fingerprint. It holds:
  - the time of the last solved **challenge**, and of the last solved **CAPTCHA** (if you use CAPTCHA);
  - signals gathered by **probing the browser** (signs of automation, inconsistent settings);
  - with the client SDKs, passively collected **interaction** data: mouse movement, key presses, form use.

  AWS doesn't publish the exact contents or the encryption. The design goal: almost no cost for a real browser, but expensive across a botnet. It also separates sessions that share **one IP**.
- **Challenge** sends a JavaScript **proof-of-work** interstitial. **CAPTCHA** first runs the challenge if there's no token yet, then shows a puzzle. After success, the script updates the token and **re-sends the original request**.
- **The responses:**
  - Challenge: HTTP **202** with `x-amzn-waf-action: challenge`;
  - CAPTCHA: HTTP **405** with `x-amzn-waf-action: captcha`;
  - the interstitial page is only included when the request's `Accept` header contains `text/html`.

  No CORS headers are sent, so JavaScript on another domain can't read `x-amzn-waf-action`.
- **Only browsers can get a token**, through an HTML `GET`. API clients need an SDK integration. **Paths that return JSON can't be challenged.** This is why the Anti-DDoS rule group only challenges *challengeable* requests (§8). AWS also recommends using Challenge **during attacks**, not all the time: it shuts out clients without JavaScript, old browsers and some accessibility tools.
- **Token states seen by later rules:**
  - `awswaf:managed:token:accepted`;
  - `…:rejected:` followed by `not_solved`, `expired`, `domain_mismatch` or `invalid`;
  - `…:absent`;
  - plus a session ID label and a **browser fingerprint** label that stays the same when the client gets new tokens.
- **Bot Control (targeted level)** uses tokens to:
  - limit how many requests a client can send without a token;
  - track sessions;
  - flag a token **obtained from one kind of IP and used from another**.

---

## 8. Anti-DDoS managed rule group: baseline, labels, then actions

1. It learns a **baseline** of the resource's normal traffic, and detects a big deviation **within seconds**.
2. During an event it labels **every** request to that resource `anti-ddos:event-detected`. Requests from suspected attack sources also get `ddos-request` plus a confidence label: `low-`, `medium-` or `high-suspicion-ddos-request`.
3. Requests whose path isn't in the exemption list get `challengeable-request`, and **only `GET` requests can**.
   - The exemption regexes are matched against **host + normalised path**, so `^/api/` won't match; the path is normalised, so `/api/` becomes `/api`.
4. Its three rules, in order:
   - `ChallengeAllDuringEvent`: *event-detected AND challengeable → Challenge*.
   - `ChallengeDDoSRequests`: *suspicion ≥ the configured sensitivity AND challengeable → Challenge*. This one is only evaluated if you override the first rule to Count.
   - `DDoSRequests`: *suspicion ≥ the block sensitivity → Block*. The default blocks high suspicion only.

To customise it, either leave it at its defaults and add **stricter rules after it**, or switch its rules to **Count** and write your own on the labels. AWS's examples:

- block medium and high suspicion **outside your main countries**;
- during an event, add a **much lower rate limit** on expensive URLs (10 requests per 10 minutes), scoped to the `event-detected` label.

Its cost is 50 WCU, and it's billed separately.

---

## 9. Where the managed IP lists come from: MadPot

- **What it is:** an Amazon system of **decoy servers (honeypots)** that look like huge numbers of ordinary targets. It sees **more than 100 million** probes and interactions a day, of which about **500,000** are confirmed malicious.
- **How fast attackers arrive:** a new sensor is found by internet scanners in about **90 seconds**. Exploit attempts follow about **3 minutes** later, on average.
- **What it does:**
  - runs captured malware in a sandbox and links the results into threat patterns;
  - cuts attackers' resources off the AWS network;
  - asks hosting providers and registrars to take down command-and-control servers, often within 48 to 72 hours.
- **Where the results go:** WAF, Shield, Network Firewall, Route 53 Resolver DNS Firewall and GuardDuty.

That intelligence becomes the **Amazon IP reputation list** rule group (25 WCU):

| Rule | Default action |
|---|---|
| `AWSManagedIPReputationList`: IPs actively doing malicious things (sources include MadPot) | Block |
| `AWSManagedReconnaissanceList`: IPs scanning AWS resources | Block |
| `AWSManagedIPDDoSList`: IPs actively taking part in DDoS | **Count** (the whitepaper suggests Block during an event) |

The **Anonymous IP list** (50 WCU) covers VPNs, proxies, Tor, and hosting or cloud providers (excluding AWS). Both lists use the **connection IP**, so behind a proxy they see the proxy. Neither has versions or update notifications.

---

## 10. How signature WAFs work in general: OWASP CRS

AWS's Core rule set isn't OWASP CRS, but CRS documents openly what a signature WAF does:

- **Anomaly scoring:** detection is separated from blocking.
  - Every matching rule *adds* to a request score, for example a critical match adds 5.
  - Only after **all** request rules have run is the total compared with a threshold.
  - Responses are scored separately.

  Many weak signals together can block a request, and one weak signal on its own doesn't.
- **Paranoia levels 1–4:** each level *adds* stricter rules. PL1 rarely produces false positives and suits everyone. PL4 is for the "crown jewels" and expects weeks of writing exclusions.
- **AWS WAF is different:** it's **first terminating match**. Each rule decides on its own. You can build CRS-style scoring with **Count plus labels**: have several rules add labels, then one later rule blocks on a combination of them (§4.3).

---

## 11. Rule order: the two AWS recommendations differ

| | DDoS resiliency whitepaper | AWS WAF best-practices guide (aws.github.io) |
|---|---|---|
| 1 | IP allow list | **Anti-DDoS rule group** |
| 2 | IP block list | IP allow list |
| 3 | Geo | IP block list |
| 4 | Anti-DDoS rule group | Geo |
| 5 | Amazon IP reputation | Rate-based (blanket) |
| 6 | Rate-based (blanket) | Rate-based (scoped) |
| 7 | Rate-based (scoped: API, URI, JA3/JA4) | IP reputation and anonymous |
| 8 | Core rule set | Baseline managed rules (Core rule set, Known bad inputs), then use-case rules |
| 9 | Bot Control | False-positive exceptions and app rules, then **paid** rule groups last |

The guide puts Anti-DDoS **first**, so an IP on your allow list can still be challenged or blocked during an attack. Its reasoning:

- Allow and Block stop evaluation (§4.3). An early Allow skips **everything** after it, so use allow lists sparingly.
- Put broad, cheap rules first and narrow or per-request-billed rules last.
- A rule that reads a label must come **after** the rule that adds it.

---

## 12. One packet-level detail for ALBs: connection tracking

A security group that **tracks** connections keeps a state entry for every flow, and a flood can fill the table and block new connections. Untracked flows don't use the table. A flow is **untracked** when:

- inbound allows the listener port from `0.0.0.0/0` (or `::/0`), **and**
- outbound allows **all ports** to `0.0.0.0/0`.

The whitepaper recommends this for public ALBs and CLBs, so they keep scaling during an attack, and SG-to-SG rules for the targets behind them. It's the same conntrack-table exhaustion problem as on Linux, covered in [conntrack.md](conntrack.md).

---

## Related notes

- [aws-waf-placement.md](aws-waf-placement.md): where to put WAF (edge vs regional), with the patterns ①–⑨
- [conntrack.md](conntrack.md): connection tracking and why its table runs out
- [ingress-and-gateway-api.md](ingress-and-gateway-api.md): what happens after the ALB, inside the cluster

## Sources

- AWS WAF Developer Guide:
  - [Setting rule priority](https://docs.aws.amazon.com/waf/latest/developerguide/web-acl-processing-order.html), [rule actions](https://docs.aws.amazon.com/waf/latest/developerguide/waf-rule-action.html), [default action](https://docs.aws.amazon.com/waf/latest/developerguide/web-acl-default-action.html)
  - [Web ACL capacity units](https://docs.aws.amazon.com/waf/latest/developerguide/aws-waf-capacity-units.html)
  - [Oversize request components](https://docs.aws.amazon.com/waf/latest/developerguide/waf-oversize-request-components.html), [text transformations](https://docs.aws.amazon.com/waf/latest/developerguide/waf-rule-statement-transformation.html), [SQLi statement](https://docs.aws.amazon.com/waf/latest/developerguide/waf-rule-statement-type-sqli-match.html)
  - [Rate-based rules](https://docs.aws.amazon.com/waf/latest/developerguide/waf-rule-statement-type-rate-based.html), [high-level settings](https://docs.aws.amazon.com/waf/latest/developerguide/waf-rule-statement-type-rate-based-high-level-settings.html), [caveats](https://docs.aws.amazon.com/waf/latest/developerguide/waf-rule-statement-type-rate-based-caveats.html)
  - [Forwarded IP addresses](https://docs.aws.amazon.com/waf/latest/developerguide/waf-rule-statement-forwarded-ip-address.html), [labels](https://docs.aws.amazon.com/waf/latest/developerguide/waf-labels.html)
  - [Tokens](https://docs.aws.amazon.com/waf/latest/developerguide/waf-tokens.html), [token characteristics](https://docs.aws.amazon.com/waf/latest/developerguide/waf-tokens-details.html), [token usage](https://docs.aws.amazon.com/waf/latest/developerguide/waf-tokens-usage.html), [CAPTCHA and Challenge behaviour](https://docs.aws.amazon.com/waf/latest/developerguide/waf-captcha-and-challenge-actions.html), [Bot Control](https://docs.aws.amazon.com/waf/latest/developerguide/waf-bot-control.html)
  - [Anti-DDoS rule group](https://docs.aws.amazon.com/waf/latest/developerguide/aws-managed-rule-groups-anti-ddos.html), [IP reputation rule groups](https://docs.aws.amazon.com/waf/latest/developerguide/aws-managed-rule-groups-ip-rep.html)
  - Shield: [how Shield mitigates events](https://docs.aws.amazon.com/waf/latest/developerguide/ddos-event-mitigation.html), [mitigation features](https://docs.aws.amazon.com/waf/latest/developerguide/ddos-event-mitigation-features.html), [logic for CloudFront and Route 53](https://docs.aws.amazon.com/waf/latest/developerguide/ddos-event-mitigation-logic-continuous-inspection.html), [logic for Regions](https://docs.aws.amazon.com/waf/latest/developerguide/ddos-event-mitigation-logic-regions.html), [L3/L4 detection](https://docs.aws.amazon.com/waf/latest/developerguide/ddos-event-detection-infrastructure.html), [L7 detection](https://docs.aws.amazon.com/waf/latest/developerguide/ddos-event-detection-application.html)
- [AWS Best Practices for DDoS Resiliency](https://docs.aws.amazon.com/whitepapers/latest/aws-best-practices-ddos-resiliency/aws-best-practices-ddos-resiliency.html): attack types, CloudFront, AWS WAF, rate-based rules, Challenge, protecting the origin, Elastic Load Balancing
- CloudFront Developer Guide: [How CloudFront delivers content](https://docs.aws.amazon.com/AmazonCloudFront/latest/DeveloperGuide/HowCloudFrontWorks.html), [request and response behavior for custom origins](https://docs.aws.amazon.com/AmazonCloudFront/latest/DeveloperGuide/RequestAndResponseBehaviorCustomOrigin.html)
- Amazon Builders' Library: [Workload isolation using shuffle-sharding](https://aws.amazon.com/builders-library/workload-isolation-using-shuffle-sharding/) (Colm MacCárthaigh)
- AWS Security Blog: [How AWS threat intelligence deters threat actors](https://aws.amazon.com/blogs/security/how-aws-threat-intelligence-deters-threat-actors/) (MadPot); [Customizing your response to L7 DDoS with the Anti-DDoS AMR](https://aws.amazon.com/blogs/security/how-to-customize-your-response-to-layer-7-ddos-attacks-using-aws-waf-anti-ddos-amr/)
- [AWS Security Services Best Practices: Recommended WAF rule order](https://aws.github.io/aws-security-services-best-practices/guides/waf/recommended-waf-rule-order/docs/)
- OWASP CRS: [Anomaly scoring](https://coreruleset.org/docs/2-how-crs-works/2-1-anomaly_scoring/), [Paranoia levels](https://coreruleset.org/docs/2-how-crs-works/2-2-paranoia_levels/)
