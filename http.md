# Http
## HTTP/1.1 and its limitations
```sh
Problem 1: Head-of-line blocking
  
  One connection can only handle one request at a time.
  Request 2 waits until Request 1 finishes.

  Client                          Server
    │── GET /index.html ──────────→│
    │                              │ (processing...)
    │←──────── 200 OK ──────────── │
    │── GET /style.css ───────────→│  ← had to wait
    │                              │ (processing...)
    │←──────── 200 OK ──────────── │
    │── GET /app.js ──────────────→│  ← had to wait
    │                              │
    Total: sequential, slow


Problem 2: Multiple connections needed

  Workaround: open 6 parallel TCP connections (browser default)
  
  conn 1: GET /index.html
  conn 2: GET /style.css
  conn 3: GET /app.js
  conn 4: GET /image1.png
  conn 5: GET /image2.png
  conn 6: GET /image3.png
  conn 7: GET /image4.png  ← has to wait for a free connection

  Each connection = TCP handshake + TLS handshake = overhead


Problem 3: Redundant headers

  Every request sends the FULL headers again:

  GET /page1
  Host: example.com
  User-Agent: Mozilla/5.0 (Macintosh; Intel...)
  Accept: text/html,application/xhtml+xml...
  Accept-Language: en-US,en;q=0.9
  Cookie: session=abc123; preferences=dark...
  (500+ bytes of headers)

  GET /page2
  Host: example.com                              ← same
  User-Agent: Mozilla/5.0 (Macintosh; Intel...)  ← same
  Accept: text/html,application/xhtml+xml...     ← same
  Accept-Language: en-US,en;q=0.9                ← same
  Cookie: session=abc123; preferences=dark...    ← same
  (500+ bytes repeated for every request)


Problem 4: No server push

  Server can't send data the client didn't ask for.
  Client loads HTML → sees it needs CSS → makes another request → waits.
```  
```sh
HTTP/1.1: one request at a time per TCP connection

Browser needs: index.html, style.css, app.js, logo.png

Connection 1:  GET index.html → wait → response → GET style.css → wait → response
Connection 2:  GET app.js → wait → response → GET logo.png → wait → response
```
- `Head-of-line blocking`: each connection handles one request at a time. The second request waits for the first to finish.
- `Multiple connections`: browsers open 6-8 TCP connections per host to work around the blocking. Each connection = separate TCP handshake, TLS handshake, slow start. Wasteful.
## HTTP/2 and how it solves these problems
```sh
Solution 1: Multiplexing (fixes head-of-line blocking)

  ONE connection, many streams running simultaneously:

  Client                          Server
    │══ stream 1: GET /index.html ═══→│
    │══ stream 2: GET /style.css ════→│  ← all sent at the same time
    │══ stream 3: GET /app.js ═══════→│
    │                                 │
    │←═══ stream 2: 200 style.css ════│  ← responses come back
    │←═══ stream 1: 200 index.html ══ │     in any order
    │←═══ stream 3: 200 app.js ══════ │

  No waiting. No extra connections.


Solution 2: One connection (fixes connection overhead)

  HTTP/1.1:  6 connections × (TCP handshake + TLS handshake) = slow start
  HTTP/2:    1 connection  × (TCP handshake + TLS handshake) = one-time cost

  All requests flow through one connection as separate streams.


Solution 3: Header compression (HPACK)

  First request sends full headers.
  Subsequent requests send only the DIFF:

  Request 1: Host: example.com, User-Agent: ..., Cookie: ...  (500 bytes)
  Request 2: just the index "same as before, but path=/page2"  (10 bytes)

  Both sides maintain a header table. Repeated headers are sent
  as tiny index numbers instead of full strings.


Solution 4: Server push

  Server sends resources before the client asks:

  Client: GET /index.html
  Server: here's index.html
          AND here's style.css (I know you'll need it)
          AND here's app.js (you'll need this too)

  (Rarely used in practice, being removed in HTTP/3)

Solution 5: Stream prioritization

  Client tells server which streams matter most:
  "Send the CSS before the images — I need CSS to render the page"
```  
```sh
kubelet, kube-proxy, Argo CD, every controller
  → all maintain watches to the API server
  → each watch is a long-lived stream

HTTP/1.1: 50 watches = 50 TCP connections to apiserver
          API server drowning in connections

HTTP/2:   50 watches = 50 streams in 1 TCP connection
          API server handles it easily
```
The Kubernetes API server uses HTTP/2 by default. Without it, a large cluster with hundreds of controllers all watching multiple resources would exhaust the API server's connection limits.  
```sh
HTTP/2: all requests on ONE connection, interleaved

Connection 1:
  Stream 1: GET index.html  ─────█████──────────────────
  Stream 2: GET style.css   ──────────█████──────────────
  Stream 3: GET app.js      ───█████────────█████────────
  Stream 4: GET logo.png    ────────████─────────████────
                             frames interleaved on the wire
```
### Binary framing 
```sh
HTTP/1.1 (text):               HTTP/2 (binary frames):
GET /index.html HTTP/1.1\r\n   ┌──────────────────────┐
Host: example.com\r\n          │ Frame: HEADERS       │
Accept: text/html\r\n          │  Stream ID: 1        │
\r\n                           │  :method GET         │
                               │  :path /index.html   │
                               └──────────────────────┘
```
Binary is faster to parse, less error-prone, and enables framing (each chunk is a discrete frame with a type, length, stream ID).

| Problem | HTTP/1.1 | HTTP/2 |
|---|---|---|
| Requests per connection | 1 at a time | Unlimited (multiplexed) |
| Connections needed | 6-8 per host | 1 |
| Header format | Text, repeated | Binary, compressed (HPACK) |
| Head-of-line blocking | Yes (HTTP level) | No at HTTP level, still yes at TCP level |
| Prioritization | None | Stream weights/dependencies |
| Server push | None | Supported |
| Protocol | Text-based | Binary framing |

## HTTP/3 and QUIC
### The remaining problem → HTTP/3
HTTP/2 fixed head-of-line blocking at the HTTP layer, but TCP still has it: if one TCP packet is lost, all streams stall waiting for retransmission.

```sh
HTTP/2 over TCP:
  Stream 1: frame frame frame
  Stream 2: frame frame [LOST PACKET] frame frame
  Stream 3: frame frame frame
                         ↑ TCP stalls ALL streams until this packet is retransmitted
```
HTTP/3 solves this by replacing TCP with QUIC (UDP-based), where each stream has independent loss recovery.

1. TCP head-of-line blocking (the big one)
```sh
HTTP/2 over TCP:  one lost packet stalls ALL streams
HTTP/3 over QUIC: one lost packet stalls only THAT stream
```
2.  Connection establishment latency
```sh
HTTP/2 (TCP + TLS 1.3):
  Client → Server: TCP SYN                    ┐
  Server → Client: TCP SYN-ACK                │ 1 RTT (TCP)
  Client → Server: TCP ACK + TLS ClientHello  ┘
  Server → Client: TLS ServerHello             ┐ 1 RTT (TLS)
  Client → Server: TLS Finished               ┘
  Total: 2 RTTs before first HTTP request

HTTP/3 (QUIC):
  Client → Server: QUIC Initial (crypto + request) ┐ 1 RTT total
  Server → Client: QUIC Handshake + response       ┘
  Total: 1 RTT — transport + TLS combined

  Resumed connection (0-RTT):
  Client → Server: QUIC 0-RTT (request in first packet!)
  Total: 0 RTTs — data sent immediately
```
3. Connection migration  
```sh
HTTP/2 over TCP: connection = (src IP, src port, dst IP, dst port)
  Phone switches from WiFi to cellular → IP changes → connection dies → reconnect

HTTP/3 over QUIC: connection = Connection ID (independent of IP)
  Phone switches from WiFi to cellular → IP changes → same Connection ID → no interruption
```
4. Ossification resistance
TCP is so old that middleboxes (firewalls, NATs, load balancers) inspect and sometimes modify TCP headers. This makes it nearly impossible to evolve TCP — any new TCP option gets mangled or dropped.

QUIC runs over UDP and encrypts almost everything (including most header fields), so middleboxes can't interfere with protocol evolution.

5. Always encrypted
```sh
HTTP/2: TLS is optional (in theory), TCP headers visible to the network
HTTP/3: encryption is mandatory, even packet numbers are encrypted
```

## Database
A connection IS a session with its own:
- Transaction isolation level
- Temporary tables
- Prepared statements
- Session variables (SET search_path, SET timezone)

For simple, non-transactional reads (autocommit single queries), multiplexing actually works:
```sh
One HTTP/2 connection:
  Stream 1 → SELECT * FROM users WHERE id=1
  Stream 2 → SELECT * FROM products WHERE id=42
  Stream 3 → SELECT count(*) FROM orders
```
some modern databases/drivers are doing exactly this:

| Database/Service | Protocol | HTTP-based? |
|---|---|---|
| CockroachDB (inter-node) | gRPC = HTTP/2 | Yes |
| PlanetScale (serverless) | HTTP REST API | Yes |
| Neon (serverless Postgres) | HTTP endpoint | Yes |
| Turso (libSQL) | HTTP + WebSockets | Yes |
| DynamoDB | HTTP/1.1 REST | Yes |
| Firestore | gRPC = HTTP/2 | Yes |
| Traditional Postgres/MySQL | Custom binary wire protocol over TCP | No |

`Prepared statement caching is per-connection`

`HTTP/1.1 can do chunked streaming too, but each watch needs its own TCP connection. Argo CD watches many resource types:`
```sh
Watch Deployments    → 1 stream
Watch Services       → 1 stream
Watch ConfigMaps     → 1 stream
Watch Secrets        → 1 stream
Watch Ingresses      → 1 stream
... (dozens more)
```
```sh
HTTP/1.1:
  ┌──────────┐    conn 1 (watch pods)       ┌────────────┐
  │          │────────────────────────────→ │            │
  │  Argo CD │    conn 2 (watch deploys)    │ API Server │
  │          │────────────────────────────→ │            │
  │          │    conn 3 (watch secrets)    │            │
  │          │────────────────────────────→ │            │
  └──────────┘    ... 20 connections        └────────────┘

HTTP/2:
  ┌──────────┐    1 connection, many streams ┌────────────┐
  │          │═══════════════════════════════│            │
  │  Argo CD │  stream 1: watch pods         │ API Server │
  │          │  stream 2: watch deploys      │            │
  │          │  stream 3: watch secrets      │            │
  │          │  ... all multiplexed          └────────────┘
```  