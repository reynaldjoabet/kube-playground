## SSH
`ssh-keygen -t ed25519 -C "you@example.com"`

| Path | Purpose |
|---|---|
| `~/.ssh/id_ed25519` | Your private key (protect with passphrase) |
| `~/.ssh/id_ed25519.pub` | Your public key (share freely) |
| `~/.ssh/authorized_keys` | Server-side: public keys allowed to log in |
| `~/.ssh/known_hosts` | Client-side: remembered server host keys |
| `~/.ssh/config` | Client-side configuration |

- TCP handshake on port 22
- Protocol version exchange (SSH-2.0-OpenSSH_9.x)
- Key exchange — both sides agree on a shared secret; client verifies server's host key against known_hosts
- Encryption activated — all subsequent data is encrypted
- User authentication — client proves identity (pubkey, password, etc.)
- Channel opened — shell session, command execution, or forwarding

```sh
# ~/.ssh/config
Host myserver
    HostName 192.168.1.100
    User deploy
    Port 2222
    IdentityFile ~/.ssh/id_ed25519
    ForwardAgent no

Host *.internal.company.com
    ProxyJump bastion.company.com
    User admin
```    

## Port Forwarding (Tunneling)
### Local forwarding (-L)
Access a remote service through your local port:
```sh
ssh -L 8080:db-server:5432 user@bastion
# localhost:8080 now tunnels to db-server:5432 via bastion
```
- `SSH client` listens on `localhost:8080` (a regular TCP bind() + listen())
- App connects to `localhost:8080` — SSH client accept()s the TCP connection
- Client sends `SSH_MSG_CHANNEL_OPEN` to the server
```sh
byte      SSH_MSG_CHANNEL_OPEN (90)
string    "direct-tcpip"
uint32    sender-channel (e.g., 0)
uint32    initial-window-size (e.g., 2097152)
uint32    max-packet-size (e.g., 32768)
string    "target"           ← destination host
uint32    5432               ← destination port
string    "127.0.0.1"        ← originator IP
uint32    54321              ← originator port
```
- Server opens a TCP connection to `target:5432` on behalf of the client
- Server replies with `SSH_MSG_CHANNEL_OPEN_CONFIRMATION`
```sh
byte      SSH_MSG_CHANNEL_OPEN_CONFIRMATION (91)
uint32    recipient-channel (0)
uint32    sender-channel (e.g., 1)
uint32    initial-window-size
uint32    max-packet-size
```
- Data relay begins — each side reads from its TCP socket and sends `SSH_MSG_CHANNEL_DATA` packets
```sh
byte      SSH_MSG_CHANNEL_DATA (94)
uint32    recipient-channel
string    data
```
- The raw TCP bytes from app → `localhost:8080` are wrapped in channel data, encrypted, sent through the SSH connection, unwrapped by `sshd`, and written to the TCP socket connected to `target:5432`
- Close — when either TCP side closes, `SSH_MSG_CHANNEL_EOF` and `SSH_MSG_CHANNEL_CLOSE` are sent.

### Remote forwarding (-R)
Expose a local service to the remote side:
```sh
ssh -L 8080:db-server:5432 user@bastion
# localhost:8080 now tunnels to db-server:5432 via bastion
```
- Client sends `SSH_MSG_GLOBAL_REQUEST` asking the server to listen:
```sh
string    "tcpip-forward"
bool      want-reply (true)
string    "0.0.0.0"          ← bind address
uint32    9090               ← bind port
```

- Server calls `bind() + listen()` on port `9090`
- When something connects to `server:9090`, the server sends `SSH_MSG_CHANNEL_OPEN`:
```sh
string    "forwarded-tcpip"
...
string    "0.0.0.0"          ← connected address
uint32    9090               ← connected port
string    "10.1.2.3"         ← originator
uint32    44567
```
- Client opens a TCP connection to `localhost:3000` and relays data through the channel — same `CHANNEL_DATA` mechanism.

### Dynamic forwarding (-D) — SOCKS proxy
```sh
ssh -D 1080 user@server
# Configure browser to use SOCKS5 proxy at localhost:1080
```

### SSH Agent
Holds decrypted private keys in memory so you don't re-enter passphrases:
```sh
eval "$(ssh-agent -s)"
ssh-add ~/.ssh/id_ed25519
``` 
```sh
# sshd_config
PermitRootLogin no
PasswordAuthentication no
PubkeyAuthentication yes
MaxAuthTries 3
AllowUsers deploy admin
Port 2222                    # Non-standard port (obscurity, not security)
LoginGraceTime 30
X11Forwarding no
AllowTcpForwarding no
```

```sh
         SSH connection (1 TCP socket, stays open)
         ═══════════════════════════════════════════

App ──TCP──► ssh client ══channel══► sshd ──TCP──► target:5432
        ↑                                     ↑
   new local TCP                          new remote TCP
   (app to ssh client)                    (sshd to target)
```   
Step by step for local forwarding (`-L 8080:target:5432`):
- SSH connection already exists (handshake done, encrypted)
- App opens a new TCP connection to `localhost:8080`
- SSH client accepts it, sends `CHANNEL_OPEN` through the existing SSH connection. `SSH client is acting as a TCP server on the local side.`
- sshd receives the channel request and opens a new TCP connection to `target:5432`
- Data flows: `app ↔ local TCP ↔ ssh client ↔ [encrypted channel inside existing SSH connection] ↔ sshd ↔ remote TCP ↔ target`

`When a tunnel is used, new TCP connections are created on the far side. They are separate from the SSH connection`

Both ends have plain, unencrypted TCP connections. The encryption only exists in the middle.

```sh
  Client side                    The wire                    Server side
┌─────────┐  plain TCP  ┌────────────┐  encrypted   ┌────────────┐  plain TCP  ┌──────────┐
│   App   │────────────►│ ssh client │═════════════►│   sshd     │────────────►│  target  │
└─────────┘             └────────────┘              └────────────┘             └──────────┘
     localhost:8080          ↕                            ↕               target:5432
                     reads from local           reads from channel,
                     TCP, encrypts,             decrypts, writes to
                     sends over channel         new TCP to target
```                     
```sh
One TCP connection is established to port 22. The handshake (key exchange, authentication) happens over this single connection. This connection stays open for the entire session. Everything — shell, tunnels, SFTP — is multiplexed inside it.
```
`ssh -L 8080:target:5432 user@server`

The SSH client process does two things:
- Acts as a TCP client → connects outward to `server:22` (the SSH connection)
- Acts as a TCP server → `calls bind() + listen()` on `localhost:8080`, waiting for local apps to connect

`sshd `never calls `bind()` or `listen()`. It only calls `connect()` — and only when it receives a `CHANNEL_OPEN` through the SSH connection. There's no open port on the server that anyone can connect to. `sshd` acts purely as an outbound TCP client to the target

For `-L 8080:target:5432`, the target service (e.g., PostgreSQL on port 5432) must be running and reachable from the SSH server

```sh
You ──► localhost:8080 ──► [encrypted tunnel] ──► sshd ──► target:5432
                                                           ↑
                                                    must be running
                                                    and accepting connections
```
Note that `target` doesn't have to be the SSH server itself. It can be any host reachable from the server:
```sh
ssh -L 8080:db-server:5432 user@bastion
#              ↑
#     a different machine that bastion can reach
```              

`sshd` connects to `db-server:5432` from its own network. That's the whole point — you're using the SSH server as a jump point into a network you can't reach directly

```sh
Your laptop                     Company network
(home/coffee shop)              (behind firewall)
                                ┌──────────────────────────────┐
┌──────────┐     internet       │  bastion         db-server   │
│ you      │────────────────────│► :22              :5432      │
└──────────┘                    │  10.0.0.1         10.0.0.50  │
                                │                              │
                                │  The firewall only allows    │
                                │  inbound TCP to port 22      │
                                │  on bastion.                 │
                                └──────────────────────────────┘
```                                
- You can reach `bastion:22` from the internet (the firewall allows it)
- You cannot reach `db-server:5432` — it's on a private network (10.0.0.x), not exposed to the internet
- But `bastion` can reach `db-server:5432` because they're on the same internal network

`ssh -L 8080:10.0.0.50:5432 user@bastion` — this command tells your SSH client to connect to `bastion:22`, authenticate, and then set up a tunnel so that when you connect to `localhost:8080`, it forwards through the SSH connection to `bastion`, which then connects to `10.0.0.50:5432`.
```sh
ssh -L [bind_address:]local_port:target_host:target_port user@ssh_server

ssh -L 8080:10.0.0.50:5432 user@bastion

ssh -L 8080:target:5432 user@bastion
       │     │      │         │
       │     │      │         └─ SSH server you connect to
       │     │      └─ target port (sshd connects to this)
       │     └─ target host (sshd connects to this)
       └─ local port (ssh client listens on this)

```

`ssh -L 8080:localhost:5432 user@bastion`
This means sshd connects to `localhost:5432` — i.e., port 5432 on bastion itself.

The target host is what makes this powerful — you can reach any machine that bastion has network access to, not just bastion itself.

`sshd listens on port 22 — always`
The `target:port` in -L must be a service that is already running and accepting TCP connections.

```sh
ssh -L 8080:target:port user@bastion
                ──────
                  │
          a running service
          that sshd will
          connect to
```

```sh
bastion (10.0.0.1)                    db-server (10.0.0.50)
┌────────────────┐                    ┌────────────────┐
│                │                    │                │
│  sshd process  │                    │  PostgreSQL    │
│       │        │                    │  listening on  │
│       │        │                    │  :5432         │
│       ▼        │                    │       ▲        │
│  connect()     │                    │       │        │
│  socket()      │                    │       │        │
│       │        │                    │       │        │
│       ▼        │                    │       │        │
│  NIC (eth0)    │──── LAN cable ────►│  NIC (eth0)    │
│  10.0.0.1      │    or switch       │  10.0.0.50     │
└────────────────┘                    └────────────────┘
```
- Creates a TCP socket
- Looks up the route — kernel checks the routing table, sees `10.0.0.50` is on the local subnet
- ARP — resolves `10.0.0.50` to a MAC address (if not cached)
- SYN packet goes out the network interface, through the switch/router, to `db-server`
- TCP handshake completes — now `sshd` has a connected socket to `db-server:5432`

```sh
Client                              Server
  │                                    │
  │──── SYN (seq=100) ───────────────► │  "I want to connect.
  │                                    │   My starting sequence
  │                                    │   number is 100."
  │                                    │
  │◄─── SYN-ACK (seq=300, ack=101) ──  │  "OK, I acknowledge your 100.
  │                                    │   My starting sequence
  │                                    │   number is 300."
  │                                    │
  │──── ACK (ack=301) ───────────────► │  "I acknowledge your 300.
  │                                    │   Connection established."
  │                                    │
  │════ DATA flows both ways ═════════  │
  ```
  TCP delivers bytes in order and reliably. Sequence numbers track which bytes have been sent and received:
```sh
Client sends:   seq=101, 50 bytes of data
                seq=151, 50 bytes of data
                seq=201, 50 bytes of data

Server replies: ack=251  → "I've received everything up to byte 251"
```
If a packet is lost, the server's ACK won't advance, and the client retransmits. The initial SYN exchange sets the starting point for these numbers.

The Initial sequence numbers are random to prevent certain types of attacks (e.g., session hijacking). After the handshake, both sides have a shared understanding of the sequence space, and they use it to ensure data integrity and order.
If sequence numbers always started at 0, an attacker could easily:
- Hijack connections — predict the sequence numbers and inject packets
- Spoof packets — forge packets that the receiver accepts as legitimate
By starting at a random number (e.g., 100, 28471953, etc.), an attacker would have to guess a 32-bit random value to inject a valid packet. This makes TCP spoofing much harder.

```sh
After handshake:

Client                                    Server
  │                                          │
  │── seq=101, ack=301, data="hello" ──────► │
  │                                          │
  │◄── seq=301, ack=106, data="world" ─────  │
  │                                          │
  │── seq=106, ack=306, data="more..." ───►. │
  │                                          │
  │◄── seq=306, ack=200, data="..." ───────  │
  │                                          │
  ... every packet, until connection closes
  ```

### Ordering
Packets can arrive out of order on the network. The receiver uses sequence numbers to reassemble them correctly:
```sh
Arrives: seq=201, seq=101, seq=151
Reorder: seq=101, seq=151, seq=201  ← correct order
```
### Reliability
The ACK number tells the sender what's been received. If a packet isn't ACKed, retransmit it:
```sh
Client sends:  seq=101 (50 bytes)
Client sends:  seq=151 (50 bytes)  ← lost!
Client sends:  seq=201 (50 bytes)

Server ACKs:   ack=151  ← "I have up to 151, where's 151-200?"

Client retransmits: seq=151 (50 bytes)

Server ACKs:   ack=251  ← "OK, now I have everything"
```
```sh
Client:  SYN  seq=100
Server:  SYN-ACK  seq=300, ack=101   ← 100 + 1
Client:  ACK  ack=301                 ← 300 + 1
```
That's because SYN and FIN are phantom bytes. They consume one sequence number even though they carry no actual data. It's a design convention in TCP
```sh
On the wire (1514 bytes):
┌──────────────────────┐
│ Ethernet: 14 bytes   │  ← not counted
├──────────────────────┤
│ IP header: 20 bytes  │  ← not counted
├──────────────────────┤
│ TCP header: 20 bytes │  ← not counted
├──────────────────────┤
│ Payload: 1460 bytes  │  ← THIS is what seq numbers track
└──────────────────────┘

seq=101, 1460 bytes payload → next seq = 101 + 1460 = 1561
```