# nftables: why it replaced iptables, and how it works

`nftables` is a rewrite of `iptables` by the same authors (the netfilter
project). It uses the same kernel subsystem (netfilter), the same hooks
(`PREROUTING`, `INPUT`, `FORWARD`, `OUTPUT`, `POSTROUTING`) and the same
connection tracking, but with a new rule engine inside the kernel and one
userspace tool, `nft`.

The outputs below are real, and anything described but not measured is
marked. The examples use the same client → router → server network as
[iptables.md](iptables.md), with all rules on the router:

```
 client 10.0.1.2 ── 10.0.1.1 [ router ] 10.0.2.1 ── 10.0.2.2 server
```

Related: [iptables.md](iptables.md), [conntrack.md](conntrack.md),
[kubernetes-services.md](kubernetes-services.md). Kubernetes source references
are to `kubernetes/kubernetes` master. The lab (three network namespaces and
a server on port 8000 that reports who called it) is built as shown in
[iptables.md](iptables.md) §0.2. All `nft` commands run on the router
(`ip netns exec router nft ...`); the lab's `nft` is version 1.0.2.

## 0. Terms and a first ruleset

### 0.1 Terms

| Term | Meaning |
|---|---|
| **netfilter, hook** | The kernel's packet-filtering framework, and the fixed points in the packet path where it runs rules ([iptables.md](iptables.md) §1, §3 here) |
| **Family** | Which kind of packets a table sees: `ip` (IPv4), `ip6` (IPv6), `inet` (both), `arp`, `bridge`, `netdev` (§3) |
| **ARP** | The protocol that finds the hardware (MAC) address for an IP address on a local network |
| **Bridge** | A virtual switch inside Linux, joining several interfaces into one network |
| **Table** | A named container for chains, sets and maps. It decides nothing about packets by itself (§3.1) |
| **Base chain** | A chain attached to a hook, with a type and priority. Packets enter the ruleset only through base chains |
| **Regular chain** | A chain with no hook, reached only by `jump`/`goto` from another chain |
| **Rule** | Matches, then statements, then optionally a verdict (§4) |
| **Verdict** | What happens to the packet next: `accept`, `drop`, `jump`, `goto`, `return`, `continue` |
| **Policy** | A base chain's verdict for packets that reach its end |
| **Handle** | A number nft gives each rule (and table and chain), used to delete or insert at a position. Unlike iptables' line numbers, handles never change |
| **Set / map / verdict map** | A collection of values to match against / a lookup from a key to a value / a lookup from a key to a verdict (§5) |
| **Bytecode, register** | The small instructions the kernel actually runs for a rule, and the scratch space they work in (§2) |
| **Transaction** | A batch of changes the kernel applies all at once or not at all (§2.4) |

### 0.2 A first ruleset, step by step

Create a table, a base chain on the `forward` hook and two rules:

```
$ nft add table inet quick
$ nft add chain inet quick fwd_chain '{ type filter hook forward priority 0; policy accept; }'
$ nft add rule inet quick fwd_chain tcp dport 8000 counter accept comment '"web"'
$ nft add rule inet quick fwd_chain ip saddr 10.0.1.2 icmp type echo-request counter drop comment '"no ping from client"'
```

The chain is called `fwd_chain` because `fwd` is a reserved word (§12).
Test from the client:

```
$ ip netns exec client curl -s 10.0.2.2:8000
seen-from=10.0.1.2:48026
$ ip netns exec client ping -c2 -W1 -q 10.0.2.2
2 packets transmitted, 0 received, 100% packet loss
```

List it with handles (`-a`) and counters:

```
$ nft -a list table inet quick
table inet quick { # handle 8
	chain fwd_chain { # handle 1
		type filter hook forward priority filter; policy accept;
		tcp dport 8000 counter packets 7 bytes 449 accept comment "web" # handle 2
		ip saddr 10.0.1.2 icmp type echo-request counter packets 2 bytes 168 drop comment "no ping from client" # handle 3
	}
}
```

- `priority 0` is shown as `priority filter`, its name (§3.2).
- The web rule counted 7 packets, all sent by the client: its handshake,
  request, acknowledgements and close. The server's replies have *source*
  port 8000, not destination port 8000, so they don't match.
- The ping rule counted both echo requests, and dropped them.

Delete a rule by its handle, then save, delete and reload the table:

```
$ nft delete rule inet quick fwd_chain handle 3
$ nft list table inet quick > quick.nft          # the listing is valid input for nft -f
$ nft delete table inet quick                     # removes its chains and rules too
$ nft -f quick.nft                                # load it back, atomically
$ nft list tables
table ip filter
table ip mangle
table ip nat
table ip raw
table ip security
table inet quick
```

The `ip filter`, `ip nat` and other `ip` tables weren't created here. They
are left over from the iptables experiments: iptables-nft creates them (with
empty built-in chains) the first time an `iptables` command runs, and
nothing deletes them afterwards. That's normal on any host where both tools
are used, and `nft list ruleset` shows them.

### 0.3 Hooks, chains and tables in one picture

**Hooks are places, chains are rule lists, tables are folders.**

The **hooks** are five fixed checkpoints in the kernel's packet path. The
kernel defines them; iptables and nftables only attach rules to them, so they
are the same for both tools:

```
                       routing decision:
 packet in             "is this for me?"
 ───────► prerouting ───────┬──────── no ────► forward ────┐
                            │ yes                          │
                            ▼                              ▼
                          input                       postrouting ───► packet out
                            │                              ▲
                            ▼                              │
                     local process ──► output ─────────────┘
```

A packet passes only the hooks on its own path:

| Traffic | Hooks it passes |
|---|---|
| Arriving for this host | prerouting → input |
| Passing through (a router, Pod traffic on a node) | prerouting → forward → postrouting |
| Sent by this host | output → postrouting |

The position decides what each hook is for. DNAT goes in prerouting, because
the destination must change before the routing decision; SNAT and masquerade
go in postrouting, after the outgoing interface is known.

A **chain** is an ordered list of rules. A *base chain* is attached to one
hook and states three things; a *regular chain* has no hook and runs only
when another chain jumps to it (§3.2):

```
type filter hook forward priority 0; policy accept;
 │           │            │          └─ what happens to packets no rule decided
 │           │            └─ order among chains on the same hook (lower runs first)
 │           └─ which checkpoint
 └─ what the chain may do: filter, nat, route
```

Several base chains can share a hook; they run in priority order. `accept`
ends only the current chain, and the packet still goes on to the next base
chain on that hook, while `drop` is final (§7).

A **table** holds chains, sets and maps. It decides nothing about packets:
`hook input` attaches a chain to the input hook, whatever the chain or table
is called. The table's job is ownership and scope (§3.1).

**Why this is confusing coming from iptables.** In iptables the tables have a
fixed job and priority, the built-in chains are named after the hooks, and
each table has only some of them. An iptables "chain" is one cell of this
grid:

| table ↓ / hook → | PREROUTING | INPUT | FORWARD | OUTPUT | POSTROUTING |
|---|:-:|:-:|:-:|:-:|:-:|
| `raw` (−300) | ✓ | | | ✓ | |
| `mangle` (−150) | ✓ | ✓ | ✓ | ✓ | ✓ |
| `nat` (dstnat −100, srcnat 100) | ✓ | ✓ | | ✓ | ✓ |
| `filter` (0) | | ✓ | ✓ | ✓ | |
| `security` (50, SELinux marks) | | ✓ | ✓ | ✓ | |

So `iptables -t nat -A PREROUTING` means "the base chain of type nat, on the
prerouting hook, at priority −100". nftables removes the grid: you create only
the cells you need and spell out the three attributes yourself.

| iptables | nftables base chain |
|---|---|
| `-t filter -A INPUT` | `type filter hook input priority 0;` |
| `-t nat -A PREROUTING` | `type nat hook prerouting priority -100;` |
| `-t nat -A POSTROUTING` | `type nat hook postrouting priority 100;` |
| `-t raw -A PREROUTING` | `type filter hook prerouting priority -300;` |

The hook decides *where*, the priority *when* on that hook, the type *what it
may do*, and the table *who owns it*.

---

## 1. Why it was needed

iptables grew one feature at a time from 1998 onwards. By the 2010s its
design limits were the problem.

**Four tools and four copies of the code.** `iptables` (IPv4), `ip6tables`
(IPv6), `arptables` (ARP) and `ebtables` (bridges) each had their own kernel code and userspace tool. For IPv4 and IPv6 that meant two separate firewalls. A **dual-stack** host, one with both an IPv4 and an IPv6 address (the normal case today), needed every rule written twice, once per tool:

```bash
iptables  -A INPUT -p tcp --dport 22 -s 10.0.0.0/8 -j ACCEPT
ip6tables -A INPUT -p tcp --dport 22 -s fd00::/8   -j ACCEPT
```

Forgetting the second copy was a common way to leave a host open: firewalled
on IPv4 with `iptables`, but wide open on IPv6.

`nftables` has one command, `nft`, and the `inet` family puts `IPv4` and `IPv6` in
one table. A rule that doesn't mention addresses covers both:

```
table inet fw {
  chain input { type filter hook input priority 0; policy drop;
    tcp dport 22 accept                  # IPv4 and IPv6
  }
}
```

Rules that name addresses still need one line per family (`ip saddr` for IPv4,
`ip6 saddr` for IPv6), but both sit in the same table and chain. You can't end
up with one protocol filtered and the other forgotten.

**Every match was its own kernel module.** In `iptables`, `-m multiport`,
`-m conntrack`, `-m limit` and so on are each a separate kernel extension
with its own parsing and matching code. A new kind of match meant new kernel
code. `nftables` has no match modules. The kernel runs a small virtual machine,
and `nft` compiles each rule into its instructions (§2).

**Updating rules was expensive.** The legacy `iptables` interface swaps the
whole table on every change: userspace downloads it, edits it and uploads it
again. That's why one `iptables -A` gets slower as the table grows, and why
agents like kube-proxy and Docker compete for a lock
([iptables.md](iptables.md) §8, §10). `nftables` updates are incremental, and a
whole batch of changes applies atomically (§2.4).

**No sets or maps.** iptables can only list rules, checked one by one.
Matching thousands of addresses needed `ipset`, a separate tool and kernel
subsystem. `nftables` builds in sets, maps and verdict maps. With them, finding
the right rule takes one lookup instead of a walk (§5, §8).

**Fixed tables and chains.** iptables comes with `filter`, `nat`, `mangle`
and `raw`, whether you need them or not. `nftables` starts empty: you create
only the tables and chains you need, give them any names, and choose their
hooks and priorities.

| | iptables | nftables |
|---|---|---|
| Hooks | Same 5 netfilter hooks | Same 5 netfilter hooks (plus `ingress`/`egress` on network devices, §3) |
| Tables and chains | Fixed (`filter`, `nat`, `mangle`, `raw`, `security`) | User-defined |
| Rule engine | Hard-coded match and target modules in the kernel | Bytecode run by a virtual machine in the kernel (§2.2) |
| Families | Separate tool per family | One tool; `inet` covers IPv4 and IPv6 |
| Updates | Whole table replaced (legacy) | Incremental, and atomic per batch (§2.4) |
| Lists of addresses or ports | One rule each, or `ipset` | Native sets, maps, verdict maps (§5) |
| Lookup cost | Linear | Linear for plain rules; one lookup with sets and maps (§8) |
| Userspace tool | `iptables`, `ip6tables`, `arptables`, `ebtables` | `nft` |

Today's `iptables` command (`iptables-nft`) is a compatibility layer that
writes nftables rules from iptables syntax ([iptables.md](iptables.md) §8).
So most "iptables" systems already run on the nftables engine, just without
its features.

## 2. How it works

### 2.1 Rules compile to bytecode

`nft --debug=netlink` prints what is sent to the kernel. One rule:

```
$ nft --debug=netlink add rule inet demo forward tcp dport 8000 ct state new counter accept
  [ meta load l4proto => reg 1 ]                       # load the transport protocol into register 1
  [ cmp eq reg 1 0x00000006 ]                          # is it 6 (TCP)?
  [ payload load 2b @ transport header + 2 => reg 1 ]  # load 2 bytes at offset 2: the destination port
  [ cmp eq reg 1 0x0000401f ]                          # is it 8000 (0x1f40, network byte order)?
  [ ct load state => reg 1 ]                           # load the conntrack state
  [ bitwise reg 1 = ( reg 1 & 0x00000008 ) ^ 0x00000000 ]
  [ cmp neq reg 1 0x00000000 ]                         # is the NEW bit set?
  [ counter pkts 0 bytes 0 ]
  [ immediate reg 0 accept ]                           # verdict
```

The kernel has no idea what "a destination port" is. It only knows
*load bytes from this offset, compare, branch*. `tcp dport`, `ip saddr` and
`ct state` are vocabulary in the `nft` tool, which turns each into these
instructions. That's why new matches don't need kernel changes.

Lists in braces become **anonymous sets**, and each one becomes a single
`lookup` instruction:

```
$ nft --debug=netlink add rule inet demo forward ip saddr { 10.0.1.2, 10.0.1.3 } tcp dport { 22, 80, 443 } drop
__set%d demo 3 size 2
	element 0201000a  : 0 [end]	element 0301000a  : 0 [end]
__set%d demo 3 size 3
	element 00001600  : 0 [end]	element 00005000  : 0 [end]	element 0000bb01  : 0 [end]
inet demo forward
  [ payload load 4b @ network header + 12 => reg 1 ]   # source address
  [ lookup reg 1 set __set%d ]
  [ payload load 2b @ transport header + 2 => reg 1 ]  # destination port
  [ lookup reg 1 set __set%d ]
  [ immediate reg 0 drop ]
```

### 2.2 What "virtual machine" means here

It's not a virtual machine in the KVM or VirtualBox sense: no guest operating
system, no emulated hardware. It's an **interpreter**. The kernel holds each
rule as a short list of generic instructions and runs them for every packet
that reaches the chain, the way the JVM runs bytecode, or the way classic BPF
runs `tcpdump` filters inside the kernel.

**The pieces:**

- **Instructions are called expressions.** Each one is a `struct nft_expr`
  whose `ops->eval` function does one small job:

  | Expression | Job | In the sample rule |
  |---|---|---|
  | `meta` | Load packet metadata (protocol, interfaces, mark, length) into a register | `meta load l4proto` |
  | `payload` | Copy bytes from a header at an offset into a register | `payload load 2b @ transport header + 2` |
  | `ct` | Load a conntrack field (state, mark, status) | `ct load state` |
  | `cmp` | Compare a register with a constant | `cmp eq`, `cmp neq` |
  | `bitwise` | Mask and XOR a register | `bitwise ... & 0x08` |
  | `lookup` | Look a register up in a set or map | the `{ ... }` lists in §2.1 |
  | `counter`, `log`, `nat`, `limit`, ... | Statements: act on the packet | `counter` |
  | `immediate` | Write a constant, typically a verdict, into a register | `immediate reg 0 accept` |

- **There's a small register file,** one per packet evaluation, on the stack:

  ```c
  /* include/net/netfilter/nf_tables.h */
  #define NFT_REG32_NUM		20

  struct nft_regs {
  	union {
  		u32			data[NFT_REG32_NUM];
  		struct nft_verdict	verdict;
  	};
  };
  ```

  That's 80 bytes. The verdict shares its first 16 bytes. Data registers are
  addressed either as four 16-byte registers (`NFT_REG_1`–`NFT_REG_4`, the
  `reg 1` in debug output) or as sixteen 4-byte ones
  (`NFT_REG32_00`–`NFT_REG32_15`). `nft_parse_register` maps them onto the
  array: `reg 1` is `data[4..7]`, and `reg 0` is the verdict.

- **Every instruction leaves a verdict code.** Most leave `NFT_CONTINUE`
  ("carry on with the next instruction"). A comparison that fails writes
  `NFT_BREAK`. This is the end of `nft_cmp_eval` (`net/netfilter/nft_cmp.c`):

  ```c
  mismatch:
  	regs->verdict.code = NFT_BREAK;
  ```

  `NFT_BREAK` means "this rule doesn't match". There's no "true or false"
  result: a failed match just ends the rule.

**The loop that runs a chain** is `nft_do_chain` in
`net/netfilter/nf_tables_core.c` (trimmed):

```c
do_chain:
	blob = genbit ? chain->blob_gen_1 : chain->blob_gen_0;   /* the active rule set */
	rule = (struct nft_rule_dp *)blob->data;
next_rule:
	regs.verdict.code = NFT_CONTINUE;
	for (; !rule->is_last ; rule = nft_rule_next(rule)) {      /* each rule */
		nft_rule_dp_for_each_expr(expr, last, rule) {       /* each instruction */
			if (expr->ops == &nft_cmp_fast_ops)
				nft_cmp_fast_eval(expr, &regs);
			else if (expr->ops == &nft_bitwise_fast_ops)
				nft_bitwise_fast_eval(expr, &regs);
			else if (expr->ops != &nft_payload_fast_ops ||
				 !nft_payload_fast_eval(expr, &regs, pkt))
				expr_call_ops_eval(expr, &regs, pkt);
			if (regs.verdict.code != NFT_CONTINUE)
				break;                                      /* stop this rule */
		}
		switch (regs.verdict.code) {
		case NFT_BREAK:                                     /* rule didn't match */
			regs.verdict.code = NFT_CONTINUE;
			continue;                                       /* next rule */
		case NFT_CONTINUE:                                  /* rule ran to the end, no verdict */
			nft_trace_packet(pkt, &regs.verdict, &info, rule, NFT_TRACETYPE_RULE);
			continue;                                       /* next rule */
		}
		break;                                              /* a real verdict */
	}
	switch (regs.verdict.code & NF_VERDICT_MASK) {
	case NF_ACCEPT: ...  return regs.verdict.code;           /* accept: leave this chain */
	case NF_DROP:        return NF_DROP_REASON(...);         /* drop: final */
	}
	switch (regs.verdict.code) {
	case NFT_JUMP:
		if (unlikely(stackptr >= NFT_JUMP_STACK_SIZE))      /* 16 */
			return NF_DROP_REASON(pkt->skb, ..., ELOOP);
		jumpstack[stackptr].rule = nft_rule_next(rule);     /* remember where to come back */
		stackptr++;
		fallthrough;
	case NFT_GOTO:
		chain = regs.verdict.chain;
		goto do_chain;                                      /* run the target chain */
	case NFT_CONTINUE:
	case NFT_RETURN:
		break;
	}
	if (stackptr > 0) {                                     /* end of a jumped-to chain */
		stackptr--;
		rule = jumpstack[stackptr].rule;
		goto next_rule;                                     /* resume the caller */
	}
	/* end of the base chain: apply its policy */
```

What the loop tells you:

- **Rules run in order, and each rule stops at its first failed instruction.**
  A rule costs as many instructions as it takes to fail. That's why `nft`
  puts the cheap protocol check first.
- **Two ways to "not decide".** `NFT_BREAK` (a match failed) and
  `NFT_CONTINUE` (the rule finished without a verdict, like a bare `counter`)
  both move on to the next rule. Only the second produces a trace event. That's
  why `nft monitor trace` lists rules that ran to the end, but never rules that
  failed to match (§2.3).
- **`jump` and `goto` work exactly as in iptables** ([iptables.md](iptables.md)
  §5). `jump` pushes a return point onto a 16-entry stack, and `goto` doesn't.
  Nesting jumps deeper than 16 drops the packet with `ELOOP`.
- **The common instructions are inlined.** Simple compares, bitwise operations
  and payload loads are called directly instead of through a function pointer.
  Everything else goes through `expr_call_ops_eval`. There's no JIT: unlike
  eBPF, nftables doesn't compile rules to machine code, it interprets them.
- **The active rules come in two generations.** The chain keeps
  `blob_gen_0` and `blob_gen_1`, and `genbit` picks one. A ruleset update
  builds the new generation alongside the running one and then flips the
  generation. Packets already being evaluated finish on the old rules. This is
  what makes updates atomic (§2.4).

**Why this design mattered.** In iptables every kind of match is compiled C
code in the kernel: `xt_multiport`, `xt_conntrack`, `xt_limit` and so on, each
with its own parsing and matching. Adding a match meant writing and shipping
kernel code. In nftables the kernel only knows *load, compare, mask, look up,
set verdict*. What "a TCP destination port" means lives in `nft`, which
compiles it to "2 bytes at transport offset 2". So many new features need only
a newer `nft`, not a newer kernel.

### 2.3 One rule, evaluated against real packets

The sample rule goes first in a `forward` chain, followed by a counter that
catches everything else:

```
table inet demo {
	chain forward {
		type filter hook forward priority filter; policy accept;
		tcp dport 8000 ct state new counter accept comment "the sample rule"
		counter comment "fell through"
	}
}
```

Its instructions, as `nft --debug=netlink` showed when the rule was inserted:

```
1  [ meta load l4proto => reg 1 ]
2  [ cmp eq reg 1 0x00000006 ]
3  [ payload load 2b @ transport header + 2 => reg 1 ]
4  [ cmp eq reg 1 0x0000401f ]
5  [ ct load state => reg 1 ]
6  [ bitwise reg 1 = ( reg 1 & 0x00000008 ) ^ 0x00000000 ]
7  [ cmp neq reg 1 0x00000000 ]
8  [ counter pkts 0 bytes 0 ]
9  [ immediate reg 0 accept ]
```

Four packets went from the client (`10.0.1.2`) through the router to
`10.0.2.2`. Each was built to stop at a different point. Their first bytes, as
captured on the router's inbound interface (`tcpdump -x`):

```
A  UDP to :8000            0x0000:  4500 001f b8d5 4000 4011 6af5 0a00 0102
                           0x0010:  0a00 0202 d49f 1f40 000b 1720 6869 0a
B  TCP SYN to :9000        0x0000:  4500 003c cddc 4000 4006 55dc 0a00 0102
                           0x0010:  0a00 0202 bbec 2328 049d 2907 0000 0000
C  TCP SYN to :8000        0x0000:  4500 003c 7f90 4000 4006 a428 0a00 0102
                           0x0010:  0a00 0202 ecb8 1f40 2ddc 71bb 0000 0000
D  TCP ACK, same           0x0000:  4500 0034 7f91 4000 4006 a42f 0a00 0102
   connection as C         0x0010:  0a00 0202 ecb8 1f40 2ddc 71bc 1260 0542
```

How to read them:

- **Byte 0** is `45`: IPv4, with a 5 × 4 = 20-byte header. So the transport
  header starts at byte 20 (`0x14`).
- **Byte 9** is the protocol: `11` (17, UDP) or `06` (TCP).
- **Transport bytes 0–1** are the source port and **bytes 2–3** the destination
  port. For packet B they are `bbec 2328`: source 48108, destination 9000.

The walk, one instruction at a time. A register holds bytes in packet order,
and debug output prints it as a little-endian 32-bit value: the port bytes
`1f 40` show as `0x0000401f`.

| Step | A: UDP :8000 | B: SYN :9000 | C: SYN :8000 | D: ACK :8000 |
|---|---|---|---|---|
| 1 `meta load l4proto` | reg 1 = `0x11` | reg 1 = `0x06` | reg 1 = `0x06` | reg 1 = `0x06` |
| 2 `cmp eq 6` | **fail → NFT_BREAK** | pass | pass | pass |
| 3 `payload load 2b @th+2` | – | reg 1 = bytes `23 28` (9000) | reg 1 = bytes `1f 40` (8000) | reg 1 = bytes `1f 40` |
| 4 `cmp eq 0x0000401f` | – | **fail → NFT_BREAK** | pass | pass |
| 5 `ct load state` | – | – | NEW → `0x08` | ESTABLISHED → `0x02` |
| 6 `& 0x08` | – | – | `0x08` | `0x00` |
| 7 `cmp neq 0` | – | – | pass | **fail → NFT_BREAK** |
| 8 `counter` | – | – | +1 | – |
| 9 `immediate accept` | – | – | **verdict accept** | – |
| Instructions run | 2 | 4 | 9 | 7 |
| Then | next rule: `"fell through"` counter, then policy | same | chain done: accepted | same as A |

A few details:

- **Packet A's port bytes are `1f 40` too** (8000, at the same offset). The rule
  still didn't match, because instruction 2 had already stopped it. The
  protocol check isn't just an optimisation: "byte offset 2" only means
  "TCP destination port" once you know the packet is TCP. `nft` adds that
  dependency automatically when you write `tcp dport`.
- **The state bits** come from `include/uapi/linux/netfilter/nf_conntrack_common.h`:
  `NF_CT_STATE_BIT(ctinfo) = 1 << (ctinfo + 1)`, with `IP_CT_ESTABLISHED` = 0,
  `RELATED` = 1 and `NEW` = 2. That gives ESTABLISHED `0x02`, RELATED `0x04`
  and NEW `0x08`, plus INVALID `0x01` and UNTRACKED `0x40`. `ct state new` is
  "mask with `0x08`, then check the result isn't zero".

The kernel agreed with this walk. The rule's counter showed exactly one packet:

```
tcp dport 8000 ct state new counter packets 1 bytes 60 accept comment "the sample rule"
counter packets 16 bytes 1017 comment "fell through"
```

The 16 "fell through" packets are A, B, D and the rest of C's connection, plus
the server's replies, which cross the router's `forward` chain too. The trace
(`nft monitor trace`, packets marked earlier with `meta nftrace set 1`)
showed the same decisions:

```
A  forward packet: ... ip protocol udp ... udp dport 8000
   forward rule counter packets 0 bytes 0 comment "fell through" (verdict continue)
   forward policy accept
B  forward packet: ... tcp dport 9000 tcp flags == syn
   forward rule counter packets 0 bytes 0 comment "fell through" (verdict continue)
   forward policy accept
C  forward packet: ... tcp dport 8000 tcp flags == syn
   forward rule tcp dport 8000 ct state new counter packets 0 bytes 0 accept comment "the sample rule" (verdict accept)
D  forward packet: ... tcp dport 8000 tcp flags == ack
   forward rule counter packets 0 bytes 0 comment "fell through" (verdict continue)
   forward policy accept
```

For A, B and D the sample rule doesn't appear in the trace at all. They ended
it with `NFT_BREAK`, and the loop in §2.2 emits no trace event for that. Only
C, which reached the verdict, shows the rule. (The counters in trace lines are
the values when the rule ran; C's own count lands just after.)

### 2.4 Changes are atomic transactions

`nft -f file` sends the whole file to the kernel as one transaction. Either all
of it applies, or none of it does. Here a ruleset (`v1`) is loaded, and then a
file that flushes everything, sets a `drop` policy and adds rules, but has an
error on its last line:

```
$ nft -f good.nft                 # policy accept; tcp dport 8000 accept comment "v1"
$ nft -c -f bad.nft               # -c: check only
bad.nft:5:33-46: Error: Could not process rule: No such file or directory
    tcp dport 9000 counter jump does_not_exist
$ nft -f bad.nft                  # apply anyway
bad.nft:5:33-46: Error: Could not process rule: No such file or directory

$ nft list ruleset                # after the failed load
    type filter hook forward priority filter; policy accept;
    tcp dport 8000 counter packets 0 bytes 0 accept comment "v1"
$ curl http://10.0.2.2:8000/
seen-from=10.0.1.2:58782          # traffic never stopped
```

The `flush ruleset` at the top of `bad.nft` didn't happen, the `drop` policy
didn't happen, and the `v1` ruleset stayed in place. With iptables-legacy, a
script of individual commands that fails halfway leaves a half-applied
firewall.

## 3. Families, tables and chains

```
nft add chain ip nat postrouting { type nat hook postrouting priority 100 \; }
#              │  │   │              │        │               │
#              │  │   │              │        │               └─ run order (lower runs first)
#              │  │   │              │        └─ attach to the POSTROUTING hook
#              │  │   │              └─ this chain does NAT
#              │  │   └─ chain name (arbitrary, you pick it)
#              │  └─ table name (arbitrary)
#              └─ address family (ip = IPv4)
```

**Families** decide which packets a table sees:

| Family | Sees | Available chain types |
|---|---|---|
| `ip` | IPv4 | filter, nat, route |
| `ip6` | IPv6 | filter, nat, route |
| `inet` | IPv4 and IPv6 together | filter, nat, route |
| `arp` | ARP | filter |
| `bridge` | Frames crossing a Linux bridge | filter, nat |
| `netdev` | Everything on one network device, before or after the IP stack (`ingress`, `egress` hooks) | filter |

**Hooks belong to the kernel, not to the tool.** The netfilter framework
defines the hooks; iptables and nftables are two front ends that attach rules
to them. The difference is that iptables can only reach five of them, while
nftables can reach seven:

| Hook | Where it sits | iptables | nftables |
|---|---|---|---|
| `prerouting` | After the IP layer receives the packet, before routing | ✅ `PREROUTING` | ✅ |
| `input` | Packet routed to this host | ✅ `INPUT` | ✅ |
| `forward` | Packet routed through this host | ✅ `FORWARD` | ✅ |
| `output` | Packet created locally | ✅ `OUTPUT` | ✅ |
| `postrouting` | Just before the packet leaves the IP layer | ✅ `POSTROUTING` | ✅ |
| `ingress` | Right after the NIC driver, before the IP layer (even before `prerouting`) | ❌ | ✅ `netdev` family (Linux 4.2+), `inet` (5.10+) |
| `egress` | Just before the packet goes to the driver | ❌ | ✅ `netdev` family (5.16+) |

iptables' built-in chains map one-to-one onto the five IP-layer hooks, and it
was never extended to the two newer ones; before nftables, the only way to
act there was `tc`. Because `ingress` and `egress` work per network device, a
chain on them names its interface:

```
table netdev edge {
    chain in { type filter hook ingress device eth0 priority 0; }
}
```

`ingress` is the earliest point where a packet can be dropped, before
conntrack or routing spend any work on it, which makes it the cheap place
for DDoS filtering. The `arp` and `bridge` families have hook sets of their
own, which used to need arptables and ebtables (§1).

**A table is a namespace.** It has no meaning of its own, unlike iptables'
`nat` or `filter`; the chain *type* decides what a chain can do. If you
write one firewall for one machine, a table can look like an empty box. Its
point shows up once several programs share the kernel's firewall: then the
table is the unit of **ownership, scope and cleanup**. iptables' five tables
split rules by *job*; nftables tables split them by *who owns them*.

### 3.1 What a table is for

**In iptables, tables carried real meaning.** Picking `-t nat` or `-t raw`
set four things the kernel enforced:

| What the table fixed | Example |
|---|---|
| **Which hooks** its chains sit on | `raw` has only PREROUTING and OUTPUT; `filter` has INPUT, FORWARD and OUTPUT |
| **When** it runs on that hook (priority) | `raw` at −300 runs before conntrack (−200), and `filter` at 0 runs after it |
| **Which targets** are allowed | `DNAT`/`SNAT`/`MASQUERADE` only in `nat`; `NOTRACK`/`CT` only in `raw`; `SECMARK` only in `security` |
| **How packets are processed** | `nat` sees only the first packet of each connection, and conntrack applies the result to the rest. In `mangle OUTPUT`, changing a mark re-routes the packet |

So putting DNAT in the filter table is rejected:

```
# iptables -t filter -A OUTPUT -p tcp -j DNAT --to-destination 10.0.0.1
iptables v1.8.7 (nf_tables):  RULE_APPEND failed (Invalid argument): rule in chain OUTPUT
```

DNAT isn't a valid target there. And `-t raw -j NOTRACK` works only
because `raw` runs before conntrack.

What iptables tables did not give you was ownership: there were only five,
and every program shared them ("Separate owners don't collide" below).

**nftables moved those four jobs onto each chain:**

| iptables table job | nftables equivalent |
|---|---|
| Which hooks | `hook …` on each base chain |
| When it runs | `priority …` on each base chain |
| Which actions are allowed | chain `type` (`filter`, `nat`, `route`) |
| First-packet-only, re-routing | also the chain `type`: `nat` chains see only the first packet, and `route` chains re-route |

Once all of that lived on the chain, the table had nothing left to decide
about packets. It became what iptables never had: a container you own, that
scopes your sets and maps, and that you can delete as a unit. The iptables
table means "what kind of processing"; the nftables table means "whose
rules". The rest of this section is what that container gives you.

**It holds more than chains:**

```
table inet fw {
    set blocked   { type ipv4_addr; flags interval; }    # sets
    map svc_ports { type inet_service : ipv4_addr; }      # maps
    counter ssh_hits { }                                  # named counters
    quota monthly { over 500 mbytes }                     # quotas
    limit ssh_rate { rate 5/minute }                      # rate limits
    flowtable ft { hook ingress priority 0; devices = { eth0, eth1 }; }
    chain input { type filter hook input priority 0; ... }
}
```

**It is the scope for those objects.** A rule can only use sets, maps and
named objects from its own table. Referencing another table's set fails:

```
# nft add set inet t1 blocked '{ type ipv4_addr; }'
# nft add rule inet t2 c ip saddr @blocked drop
Error: No such file or directory; did you mean set 'blocked' in table inet 't1'?
```

**Separate owners don't collide.** One machine often has several programs
writing firewall rules, each in its own table:

```
table inet firewalld { ... }     # the distro firewall
table ip   kube-proxy { ... }    # Kubernetes Services (§10)
table ip6  kube-proxy { ... }
table inet myapp { ... }         # yours
```

Each program touches only its own table, so it can rewrite its rules without
reading or merging anyone else's.

iptables has tables too, but they are fixed and shared, so they divide rules
by *job*, not by *owner*. If a program wants to filter forwarded packets,
there is exactly one place for that: the `filter` table's `FORWARD` chain.
It is a single list of rules, and every program has to put its rules into
it:

```
filter FORWARD  (one list, first match wins)
  1  -j DOCKER-USER          ← inserted by Docker
  2  -j DOCKER-FORWARD       ← inserted by Docker
  3  -j KUBE-FORWARD         ← inserted by kube-proxy
  4  -j f2b-sshd             ← inserted by fail2ban
  5  -s 203.0.113.9 -j DROP  ← yours, appended at the bottom
```

Each program can still have its own user-defined chains (`DOCKER`,
`KUBE-*`), but they are only reached through a jump in the shared built-in
chain, so everyone still meets in that one list. That causes three problems:

- **Order fights.** First match wins. If Docker's chain accepts a packet at
  line 2, your DROP at line 5 never runs. Docker added the `DOCKER-USER`
  chain precisely because users' rules kept being skipped this way.
- **Clobbering.** `iptables -F FORWARD`, or `iptables-restore` of the whole
  `filter` table, wipes everyone's jumps, not just yours
  ([iptables.md](iptables.md) §10, "Share the tables politely").
- **Coordination.** On every sync, each program has to find its own lines in
  the shared list and keep them in the right place, without disturbing the
  others.

In nftables each owner gets its own chain on the hook:

```
hook forward:
  table inet docker     chain fwd (priority 0)   ← Docker's, separate list
  table ip   kube-proxy chain fwd (priority 0)   ← kube-proxy's
  table inet myapp      chain fwd (priority 10)  ← yours
```

These are separate base chains. The kernel runs each of them in turn, and
none of them is "inside" another. That fixes all three problems:

- **No order fights for drops.** `accept` only ends the chain it is in; the
  packet still goes on to the next base chain on the hook. `drop` is final.
  So your drop applies even if Docker's chain accepted the packet (§7 shows
  this).
- **No clobbering.** Rewriting `table inet myapp` touches only your chains.
- **No coordination.** Each program owns its whole table, so it can rebuild
  it from scratch on every sync.

**Tables separate editing, not traffic.** The kernel has no idea which
program a packet "belongs to". Every packet that reaches a hook runs through
every base chain on that hook, from every table, in priority order. It only
stops early if one of them drops it. With the chains above, a forwarded
packet, say a container talking to the internet, goes through Docker's
chain, then kube-proxy's, then yours. It doesn't matter that it "is Docker
traffic".

| | Separated by table? |
|---|---|
| Who can add, change or delete the rules | Yes. Each program rewrites only its own table (and with `flags owner`, no one else can) |
| Which sets and maps a rule can use | Yes. Only its own table's |
| Which packets a chain sees | **No.** All packets on that hook |

If a chain should only handle some traffic, its rules have to match on it.
kube-proxy's chains, for example, see every forwarded packet, but their rules
only act when the destination is a Service IP (§10):

```
chain services {
    ip daddr . meta l4proto . th dport vmap @service-ips   # not a Service → no match, falls through
}
```

A packet that isn't for a Service misses the map, reaches the end of the
chain, and continues to the next base chain on the hook. kube-proxy does
this check as a single hash lookup, so it's cheap for the packets it doesn't
care about. Still, every base chain on a hook runs for every packet that
reaches it, even if none of its rules ever match, so packets pay for every
program that installed a chain there.

**It sets the family.** `table inet fw` means every chain in it sees IPv4 and
IPv6; the family is not repeated per chain.

**Whole-table operations:**

| Command | Effect |
|---|---|
| `nft list table inet myapp` | Shows just your piece of the ruleset |
| `nft flush table inet myapp` | Empties all its chains, keeps the chains, sets and objects |
| `nft delete table inet myapp` | Removes all of myapp's chains, sets and objects, nothing else |
| `table inet myapp { flags dormant; }` | Switches the whole table off without deleting it: its base chains are unhooked, its contents kept |
| `table inet myapp { flags owner; }` | The table belongs to the netlink socket that created it; the kernel deletes it when that process exits, and no other process can change it (Linux 5.12+) |

`flags owner` is meant for daemons that keep a socket open: their rules
disappear with them instead of being left behind after a crash. Loaded with
plain `nft -f`, the table is gone as soon as `nft` exits:

```
# cat o.nft
table inet t3 {
 flags owner
 chain c {
  type filter hook input priority 0
 }
}
# nft -f o.nft; echo "exit=$?"; nft list tables
exit=0
#                                    ← t3 already deleted
```

**One component can reload atomically without touching other tables.** Put
the whole table in one file that first deletes it:

```
table inet myapp          # create it if missing, so the delete can't fail
delete table inet myapp
table inet myapp {
  chain c {
    type filter hook input priority 0
    counter
  }
}
```

Running that file twice in a row succeeds both times, and `nft list table
inet myapp` shows a single fresh copy.

`nft -f` applies the file as one transaction (§2.4): there is no moment
where myapp's rules are missing, and other tables are never touched. The
first line is the classic trick for a table that may not exist yet. Newer
versions have `destroy table inet myapp`, which doesn't fail on a missing
table (Linux 6.3+ and a recent nft; nft 1.0.2 rejects it).

### 3.2 Chains and hooks

**A base chain is attached to a hook,** given a type and a priority. Chains
without `type ... hook ...` are **regular chains**, reached only by `jump` or
`goto`, just like iptables user-defined chains.

**Chains attach to hooks, tables don't.** A rule belongs to a chain and a
chain to a table, but the table is only a container; each base chain inside
it picks its own hook:

```
family (ip, ip6, inet, arp, bridge, netdev)
└── table          a namespace; no hook, no meaning of its own
    ├── set / map  shared by the rules in this table
    ├── base chain     has  type + hook + priority  → attached to a hook
    │   └── rule       expressions + statements + verdict
    └── regular chain  no hook; reached only by jump/goto from another chain
        └── rule
```

That means a single table can have chains on several hooks. The firewall in
§6 is one `inet firewall` table with three chains:

```
table inet firewall {
    chain input   { type filter hook input   priority 0; policy drop; }
    chain forward { type filter hook forward priority 0; policy drop; }
    chain output  { type filter hook output  priority 0; policy accept; }
}
```

It also goes the other way: a hook can have chains from many tables. They run
in priority order, and any of them can drop the packet (§7).

```
hook input ──► table A / chain (priority -150) ──► table B / chain (priority 0) ──► table C / chain (priority 50)
```

This is where iptables confuses the picture. There, a table like `nat` or
`filter` *seems* tied to hooks, because its built-in chains are named after
them (`nat PREROUTING`, `filter INPUT`). Even there it is the chain that sits
on the hook; iptables just creates those chains for you with fixed names and
priorities. nftables makes you declare the hook on each chain yourself.

| Type | Allowed hooks | Purpose |
|---|---|---|
| `filter` | prerouting, input, forward, output, postrouting | Accept or drop, mark, log |
| `nat` | prerouting, input, output, postrouting | Address rewriting. Only the first packet of a connection traverses it ([conntrack.md](conntrack.md) §6) |
| `route` | output | Like `filter`, but re-routes the packet if a mark or address changes |

The kernel enforces these combinations with a bitmask per chain type:

```c
// all five hooks for the filter type
static const struct nft_chain_type nft_chain_filter_ipv4 = {
    .name       = "filter",
    .hook_mask  = (1 << NF_INET_LOCAL_IN) |
                  (1 << NF_INET_LOCAL_OUT) |
                  (1 << NF_INET_FORWARD) |
                  (1 << NF_INET_PRE_ROUTING) |
                  (1 << NF_INET_POST_ROUTING),
};
// nat
static const struct nft_chain_type nft_chain_nat_ipv4 = {
    .name       = "nat",
    .hook_mask  = (1 << NF_INET_PRE_ROUTING) |
                  (1 << NF_INET_POST_ROUTING) |
                  (1 << NF_INET_LOCAL_OUT) |
                  (1 << NF_INET_LOCAL_IN),
};
// route
static const struct nft_chain_type nft_chain_route_ipv4 = {
    .name       = "route",
    .hook_mask  = (1 << NF_INET_LOCAL_OUT),
};
```

Each bit is one hook. Creating a chain on a hook outside its type's mask is
rejected.

**Priorities** order the base chains that share a hook. Lower runs first. The
named priorities match the old iptables tables:

| Name | Value | Old iptables equivalent |
|---|---|---|
| `raw` | −300 | `raw` table (before conntrack, which is at −200) |
| `mangle` | −150 | `mangle` |
| `dstnat` | −100 | `nat` PREROUTING |
| `filter` | 0 | `filter` |
| `security` | 50 | `security` (iptables-nft created its `security` chains at 150 in [iptables.md](iptables.md) §2; check with `nft list ruleset`) |
| `srcnat` | 100 | `nat` POSTROUTING |

**Only base chains have a priority.** A regular chain never runs on its own:
it runs exactly when a rule `jump`s or `goto`s to it, in the middle of
whichever chain made the jump.

**Priority is per hook, across all tables.** The kernel keeps one sorted list
per hook and runs it from lowest to highest:

```
hook prerouting (ip):
  -400  defrag                           ← kernel internal
  -300  table raw_stuff / chain pre      ← yours, priority raw
  -200  conntrack                        ← kernel internal
  -100  table nat / chain pre            ← priority dstnat
     0  table fw / chain pre             ← priority filter
```

- **Your chains are ordered against the kernel's own work, not just each
  other.** At −300 a chain runs before conntrack has seen the packet. That
  is why `notrack` only works there, and why `ct state` in such a chain
  sees nothing useful.
- **Tables don't affect the order.** Two chains from different tables on the
  same hook are sorted only by their priority numbers.

A priority can be a number, a name, or a name plus or minus an offset:

```
type filter hook input priority 0;
type filter hook input priority filter;       # same thing: filter = 0
type filter hook input priority filter - 5;   # runs just before "filter" chains
type nat    hook prerouting priority dstnat;  # -100
```

Two chains on the same hook with the same priority run in an order you
shouldn't rely on; if the order matters, give them different numbers. The
named values differ slightly in the `bridge` family (`filter` is −200 there).

**Who decides the priority.** Whoever creates the base chain: you, or a
program like kube-proxy, firewalld or Docker. The kernel fixes only the
priorities of its own steps, as constants in the source (`enum
nf_ip_hook_priorities` in `netfilter_ipv4.h`: defrag −400, conntrack −200,
and so on). The named priorities are conventions, numbers chosen to match
the old iptables order so that tools which agree on them interleave
sensibly.

kube-proxy picks its own (`pkg/proxy/nftables/proxier.go`):

```go
{filterPreroutingPreDNATChain, FilterType, PreroutingHook,  DNATPriority + "-10"},  // -110: before DNAT
{filterOutputPreDNATChain,     FilterType, OutputHook,      DNATPriority + "-10"},
{filterInputChain,             FilterType, InputHook,       FilterPriority},        // 0
{filterForwardChain,           FilterType, ForwardHook,     FilterPriority},
{filterOutputChain,            FilterType, OutputHook,      FilterPriority},
{natPreroutingChain,           NATType,    PreroutingHook,  DNATPriority},          // -100
{natOutputChain,               NATType,    OutputHook,      DNATPriority},
{natPostroutingChain,          NATType,    PostroutingHook, SNATPriority},          // 100
```

`dstnat - 10` is deliberate: the load-balancer source-range checks need the
original destination IP, so they must run before kube-proxy's own DNAT chain
at −100 rewrites it.

The kernel enforces only a few limits. A `nat` chain must run after
conntrack, because NAT is built on conntrack entries:

```
# nft add chain ip pt n '{ type nat hook prerouting priority -300; }'
Error: Could not process rule: Operation not supported
# nft add chain ip pt n2 '{ type nat hook prerouting priority -199; }'
-199 ok
```

Everything else is convention. A `filter` chain at −300 is legal, but it
runs before conntrack, so `ct state` there sees nothing useful. A wrong
priority usually doesn't fail loudly; the rules just quietly don't behave
as you meant.

**`policy`** is the verdict for packets that reach the end of a base chain,
either `accept` or `drop`.

## 4. Rules: matches, statements and verdicts

A rule is a sequence of **expressions** (matches), **statements** (actions)
and an optional **verdict**:

```
tcp dport 8000  ct state new  counter  log prefix "web "  accept
└── match ───┘  └─ match ──┘  └───── statements ──────┘  └ verdict
```

| Kind | Examples |
|---|---|
| Header fields | `ip saddr 10.0.0.0/8`, `ip6 daddr ::1`, `tcp dport 443`, `th dport 53` (any transport protocol), `tcp flags & (syn \| ack) == syn` |
| Metadata | `iifname "eth0"`, `oifname`, `meta l4proto`, `meta mark`, `meta nfproto ipv4`, `fib daddr type local` |
| Connection tracking | `ct state established,related`, `ct mark`, `ct status dnat` |
| Statements | `counter`, `log`, `limit rate 10/second`, `meta mark set 7`, `ct mark set`, `notrack`, `queue` |
| NAT statements | `dnat to 10.0.2.2:8000`, `snat to 10.0.2.1`, `masquerade`, `redirect to :3128` |
| Verdicts | `accept`, `drop`, `reject [with tcp reset]`, `jump <chain>`, `goto <chain>`, `return`, `continue` |

Verdicts behave as they do in iptables: `jump` returns, `goto` doesn't
([iptables.md](iptables.md) §5). Several *base* chains on one hook are a
different matter; see §7.

**Watch for reserved words.** `fwd` is a statement (forward to another
interface), so a chain can't be called `fwd`:

```
Error: syntax error, unexpected fwd, expecting string
add chain inet demo fwd { type filter hook forward priority 0; policy accept; }
```

Other words like `filter`, `nat` and `input` work as names. The error message
doesn't say the problem is a reserved word, so pick descriptive names.

## 5. Sets and maps

This ruleset uses every kind:

```
table ip fw {
  set blocked     { type ipv4_addr; flags interval; elements = { 192.168.0.0/16, 172.16.5.1 } }
  set allowed_svc { type ipv4_addr . inet_service; elements = { 10.0.2.2 . 8000 } }
  set recent      { type ipv4_addr; flags timeout, dynamic; timeout 10s; }

  chain forward { type filter hook forward priority 0; policy drop;
    ct state established,related accept
    ip saddr @blocked counter drop                          # interval set: CIDRs and single IPs
    ip daddr . tcp dport @allowed_svc counter accept        # concatenation: address AND port together
    tcp dport vmap { 9000 : accept, 9001 : drop }           # verdict map: value -> verdict
  }
  chain pre { type filter hook prerouting priority 0; policy accept;
    tcp flags syn add @recent { ip saddr }                  # a rule that fills a set at runtime
  }
}
```

The results of connecting from the client to each address and port:

```
10.0.2.2:8000 (in allowed_svc)    200
10.0.2.2:80   (not in the set)    blocked by policy drop
tcp 9000      (vmap accept)       connected
tcp 9001      (vmap drop)         blocked
```

| Kind | What it gives you |
|---|---|
| Set, `flags interval` | Ranges and CIDRs in one set |
| Concatenation (`a . b`) | Match several fields with one lookup: "this address *on* this port", not two separate checks |
| Verdict map (`vmap`) | Value to verdict: `accept`, `drop`, or `goto <chain>`. Used for dispatching, as in kube-proxy (§10) |
| Data map (`map`) | Value to value, e.g. `dnat to tcp dport map { 8080 : 10.0.2.2 }`: the destination port picks the new address, and the port stays 8080 |
| `flags timeout` | Each element expires on its own |
| `flags dynamic` + `add @set` / `update @set` | Rules that fill the set from traffic: rate limiting, "seen recently", port-knocking |

**A data map for port forwarding.** A map's value can be an address and a
port together, so one rule forwards many ports to different backends:

```
table ip mapdemo {
	map port_to_backend {
		type inet_service : ipv4_addr . inet_service      # key: port; value: address . port
		elements = { 8080 : 10.0.2.2 . 8000, 8081 : 10.0.1.1 . 9100 }
	}
	chain pre {
		type nat hook prerouting priority dstnat; policy accept;
		ip daddr 10.0.1.1 dnat ip to tcp dport map @port_to_backend
	}
}
```

`10.0.1.1:8081` goes to a second copy of the server script, started on the
router itself on port 9100. Then a third port is added while the rule is
live:

```
$ ip netns exec client curl -s 10.0.1.1:8080
seen-from=10.0.1.2:40252
$ ip netns exec client curl -s 10.0.1.1:8081
seen-from=10.0.1.2:56272
$ nft add element ip mapdemo port_to_backend '{ 8082 : 10.0.2.2 . 8000 }'
$ ip netns exec client curl -s 10.0.1.1:8082
seen-from=10.0.1.2:58190

$ conntrack -L -p tcp          # where each one really went (reply side)
src=10.0.1.2 dst=10.0.1.1 sport=40252 dport=8080   reply from: src=10.0.2.2 dst=10.0.1.2 sport=8000
src=10.0.1.2 dst=10.0.1.1 sport=56272 dport=8081   reply from: src=10.0.1.1 dst=10.0.1.2 sport=9100
src=10.0.1.2 dst=10.0.1.1 sport=58190 dport=8082   reply from: src=10.0.2.2 dst=10.0.1.2 sport=8000
```

A map that gives only an address (`type inet_service : ipv4_addr`) keeps the
original port. Pointing `8080 : 10.0.2.2` at a server listening on 8000
therefore fails, because the packet arrives at `10.0.2.2:8080`, where
nothing listens.

In iptables, the same needs one `DNAT` rule per port, checked in order.

**Sets change without reloading rules.** Adding one address blocked the client
at once, and the rule's counter picked it up:

```
$ nft add element ip fw blocked { 10.0.1.2 }
10.0.2.2:8000 after block        blocked
ip saddr @blocked counter packets 2 bytes 120 drop comment "interval set"
```

**The dynamic set caught more than intended:**

```
$ nft list set ip fw recent
    elements = { 10.0.1.2 expires 6s360ms, 10.0.2.2 expires 6s360ms }
```

`10.0.2.2` is the *server*. `tcp flags syn` matches any packet with the SYN
bit set, which includes the server's SYN-ACK replies. To match only connection
openers, write `tcp flags & (syn | ack) == syn`, or `ct state new`.

Rate-limiting SSH per source, the way it's usually written:

```
tcp dport 22 ct state new meter ssh-limit { ip saddr limit rate 5/minute } accept
```

`meter` is the older syntax and still loads (nft 1.0.2 accepts it unchanged).
The newer form is a named set with `flags dynamic` and
`add @ssh_seen { ip saddr limit rate 5/minute }`.

## 6. A complete small-server firewall

A typical first attempt, built command by command. It has two bugs,
explained after it, and a corrected version follows. In a shell, `\;`
escapes the semicolons so the shell passes them to nft:

```sh
# one table for IPv4 and IPv6
nft add table inet firewall

nft add chain inet firewall input   { type filter hook input priority 0 \; policy drop \; }
nft add chain inet firewall forward { type filter hook forward priority 0 \; policy drop \; }
nft add chain inet firewall output  { type filter hook output priority 0 \; policy accept \; }

# replies to our own connections first: cheapest and most common (§8)
nft add rule inet firewall input ct state established,related accept

# SSH, HTTP, HTTPS in one rule (an anonymous set)
nft add rule inet firewall input tcp dport { 22, 80, 443 } accept

# DNS over UDP and TCP in one rule: th = any transport header
nft add rule inet firewall input meta l4proto { tcp, udp } th dport 53 accept

# at most 5 new SSH connections per minute per source IP
nft add rule inet firewall input tcp dport 22 ct state new meter ssh-limit { ip saddr limit rate 5/minute } accept

# NAT for a private network behind eth0
nft add table ip nat
nft add chain ip nat postrouting { type nat hook postrouting priority 100 \; }
nft add chain ip nat prerouting  { type nat hook prerouting priority -100 \; }
nft add rule ip nat postrouting oifname "eth0" masquerade                       # SNAT outgoing traffic
nft add rule ip nat prerouting iifname "eth0" tcp dport 80 dnat to 192.168.1.50:8080   # port forward
```

Every line was checked by loading it on a real kernel.

**Order matters.** As written, the rate-limited SSH rule never takes effect:
the `{ 22, 80, 443 }` rule above it already accepts all SSH. Put the
rate-limited rule first, or take 22 out of the set.

**Missing allows.** The `input` policy is `drop` and nothing allows loopback or
ICMP. Add `iifname "lo" accept`, and accept ICMP and ICMPv6, which IPv6
neighbour discovery and path-MTU discovery need.

**The corrected version, as one file.** Loaded with `nft -f firewall.nft`,
it replaces the whole table atomically every time (the first two lines are
the reload pattern from §3.1):

```
table inet firewall
delete table inet firewall
table inet firewall {
	chain input {
		type filter hook input priority filter; policy drop;
		ct state established,related accept      # replies first: the most common case
		ct state invalid drop                    # broken or forged packets
		iifname "lo" accept                      # local processes talking to each other
		meta l4proto { icmp, ipv6-icmp } accept  # ping, errors, IPv6 neighbour discovery
		tcp dport 22 ct state new meter ssh-limit { ip saddr limit rate 5/minute } accept
		tcp dport 22 ct state new drop           # over the limit
		tcp dport { 80, 443 } accept             # 22 is no longer in this set
		meta l4proto { tcp, udp } th dport 53 accept
		counter comment "dropped by policy"      # counts what the policy is about to drop
	}
	chain forward {
		type filter hook forward priority filter; policy drop;
	}
	chain output {
		type filter hook output priority filter; policy accept;
	}
}
```

Tested in a throwaway network namespace: it loaded, loaded again over itself
without error, and `ping 127.0.0.1` worked. With the input chain emptied (so
the `drop` policy applied to everything), the same ping failed. That's
what forgetting the `lo` rule does: local services that talk to each other
over `127.0.0.1` stop working.

```
with the ruleset:              1 packets transmitted, 1 received, 0% packet loss
input chain emptied:           1 packets transmitted, 0 received, 100% packet loss
```

On iptables, the three-port rule could be one rule with `-m multiport`. The
address and protocol sets, the `inet` dual-stack table and the per-source
meter had no single-rule equivalent.

## 7. Several tables on one hook

Docker, kube-proxy, firewalld, a CNI plugin and your own firewall can each
have a base chain on the same hook. Two tables, each with a rule for port
8000:

```
table inet first {
	chain fw {
		type filter hook forward priority -10; policy accept;    # runs first
		tcp dport 8000 counter accept
	}
}
table inet second {
	chain fw {
		type filter hook forward priority 10; policy accept;     # runs second
		tcp dport 8000 counter drop
	}
}
```

```
$ ip netns exec client curl -s -m2 10.0.2.2:8000 || echo "timed out"
timed out
```

The two orders, with each table's counter (the second run swaps `accept`
and `drop`):

```
first (priority -10) ACCEPT, then second (priority 10) DROP   ->  dropped
    counter packets 2 bytes 120 accept       # first table matched and accepted...
    counter packets 2 bytes 120 drop         # ...and the second table still ran, and dropped

first (priority -10) DROP, then second (priority 10) ACCEPT   ->  dropped
    counter packets 2 bytes 120 drop
    counter packets 0 bytes 0 accept         # never reached
```

- **`accept` is final only for its own chain.** The packet then continues to
  the next base chain on the same hook, in priority order.
- **`drop` is final everywhere.** Nothing after it sees the packet.

So a table you add can never let through what another table drops. And this
holds across tools, because iptables-nft tables are nftables tables too:

```
iptables -P FORWARD DROP  +  my own nft table accepting everything  ->  dropped
```

When something is blocked and your own rules say accept, list **every** table
(`nft list ruleset`) and find the other base chains on that hook.

## 8. Performance

The same measurement as [iptables.md](iptables.md) §9: 400 pings through the
router with 20,000 non-matching addresses.

| Setup | Average round trip | Time to load |
|---|---|---|
| No ruleset | 10 µs | – |
| 20,000 separate `ip saddr X drop` rules | **222 µs** | 838 ms |
| One rule, `ip saddr @blocked drop`, set of 20,000 | 6 µs | 47 ms |
| Adding one element to that set | – | 2 ms |

For comparison, iptables with 20,000 rules measured 207 µs, and an ipset
12 µs.

**nftables isn't faster by itself.** 20,000 plain rules cost about the same as
in iptables, because rules are still checked one after another. The speedup
comes from *expressing* the policy as a set or map, which is one lookup no
matter its size. The set also loaded about 18 times faster than the equivalent
rules.

## 9. Inspecting and debugging

```bash
nft list ruleset                       # everything, all families and tables
nft -a list chain inet fw forward      # with rule handles, needed to delete or insert by position
nft delete rule inet fw forward handle 7
nft insert rule inet fw forward position 7 ip saddr 10.9.9.9 drop
nft list set ip fw recent              # live set contents, with expiry times
nft monitor                            # stream ruleset changes as they happen
nft --debug=netlink add rule ...       # show the bytecode (§2.1)
nft -c -f file.nft                     # check a file without applying it
iptables-translate -A INPUT ...        # convert an iptables rule to nft syntax
```

**Tracing.** Mark the packets you care about early, then watch them. Every
chain and every rule that decides something is reported:

```
table inet t {
  chain pre { type filter hook prerouting priority -350; policy accept;
    tcp dport 8000 tcp flags syn meta nftrace set 1
  }
  chain fw { type filter hook forward priority 0; policy accept;
    ip saddr 10.9.9.9 drop comment "not me"
    tcp dport 8000 counter accept comment "web"
  }
}
```

```
$ nft monitor trace
trace id 70047265 inet t pre packet: iif "r-c" ... ip saddr 10.0.1.2 ip daddr 10.0.2.2 ... tcp dport 8000 tcp flags == syn ...
trace id 70047265 inet t pre rule tcp dport 8000 tcp flags syn meta nftrace set 1 (verdict continue)
trace id 70047265 inet t pre policy accept
trace id 70047265 inet t fw packet: iif "r-c" oif "r-s" ... ip ttl 63 ...
trace id 70047265 inet t fw rule tcp dport 8000 counter packets 0 bytes 0 accept comment "web" (verdict accept)
```

The packet went through `pre`, then `fw`. The `"not me"` rule didn't match,
so it isn't listed, and `"web"` returned `accept`. The TTL drops from 64 to 63
between the two hooks, because the packet was routed in between. Use priority
−350 so the trace mark is set before anything else, including conntrack, sees
the packet.

## 10. kube-proxy in nftables mode

kube-proxy has had an nftables mode since Kubernetes 1.29; it became generally
available in 1.33. I switched the minikube cluster from
[kubernetes-services.md](kubernetes-services.md) to it
(`mode: "nftables"` in the `kube-proxy` ConfigMap) and compared.

```
"Using nftables Proxier"
Service traffic: 8 / 15 / 7 requests across the three pods     # still works
KUBE- rules left in iptables: 0                                # it cleans up the other mode's rules
```

Everything lives in one table, `ip kube-proxy`. Finding the Service is one map
lookup:

```
chain services {
    ip daddr . meta l4proto . th dport vmap @service-ips
    ip daddr @nodeport-ips meta l4proto . th dport vmap @service-nodeports
}

map service-ips {
    type ipv4_addr . inet_proto . inet_service : verdict
    comment "ClusterIP, ExternalIP and LoadBalancer IP traffic"
    elements = { 10.96.0.10 . udp . 53 : goto service-FY5PMXPG-kube-system/kube-dns/udp/dns,
                 10.107.59.191 . tcp . 80 : goto service-A2DTEUOW-svc-demo/web/tcp/,
                 192.168.49.200 . tcp . 80 : goto external-7LLUZVKG-svc-demo/t-extip/tcp/, ... }
}
```

Choosing an endpoint is one rule:

```
chain service-A2DTEUOW-svc-demo/web/tcp/ {
    ip daddr 10.107.59.191 ip saddr != 10.244.0.0/16 jump mark-for-masquerade
    numgen random mod 3 vmap { 0 : goto endpoint-GI5XAXSX-svc-demo/web/tcp/__10.244.120.90/8080,
                               1 : goto endpoint-UZMNKQO4-svc-demo/web/tcp/__10.244.120.91/8080,
                               2 : goto endpoint-RCQVJHEV-svc-demo/web/tcp/__10.244.120.93/8080 }
}

chain endpoint-GI5XAXSX-svc-demo/web/tcp/__10.244.120.90/8080 {
    ip saddr 10.244.120.90 jump mark-for-masquerade      # hairpin (kubernetes-services.md §3.4)
    meta l4proto tcp dnat to 10.244.120.90:8080
}
```

| iptables mode | nftables mode |
|---|---|
| `KUBE-SERVICES`: one rule per Service IP and port, walked in order | `services`: 2 rules, a single `vmap` lookup on address . protocol . port |
| `KUBE-SVC-*`: one `statistic --probability` rule per endpoint | One `numgen random mod N vmap` rule |
| `KUBE-SEP-*`: masquerade mark + DNAT | `endpoint-*`: masquerade mark + DNAT (same logic) |
| Chain names are hashes (`KUBE-SVC-IGTRU775...`) | Names include namespace, Service and endpoint |

**The same scale test as the iptables doc:** 1,500 extra Services, all
selecting the same 3 pods.

| Measurement | iptables mode | nftables mode |
|---|---|---|
| Rules a new connection's first packet walks to find its Service | 1,507 (the `KUBE-SERVICES` list) | **2** (`services` chain, one map lookup) |
| Ruleset size | 22,606 NAT rules | 32,658 lines in one table: 1,516 map elements, 1,515 Service chains, 4,536 endpoint chains |
| Full sync (kube-proxy restart) | 1.40 s | **0.64 s** |
| Partial sync, unrelated 1-endpoint change | 0.129 s | 0.276 s |
| One pod deleted that all 1,500 Services select (90 s window) | 83 syncs, 8.5 s total | 64 syncs, 18.6 s and 20.2 s (two samples), about 0.3 s each |

On this cluster nftables mode won on the data path: lookup cost stays flat
however many Services exist. It also won on full syncs. Its incremental syncs
were about twice as slow, both for small changes and when a change touched
every Service. This is one single-node minikube cluster and a few samples, so
treat it as a direction to verify on your own clusters, not a benchmark. The
data-path improvement is structural and will hold; the sync times depend on the
kube-proxy version, the kernel and how changes are batched.

**Requirements and pitfalls:**

- **Kernel 5.13 or later.** kube-proxy refuses nftables mode on older kernels
  (`pkg/proxy/nftables/supported.go`). The kernel version stands in for the
  host's `nft` version. The source warns that if kube-proxy uses `nft` 1.0.3 or
  later and the host has 0.9.8 or earlier, "the system nft will become
  completely unusable". The override is
  `KUBE_PROXY_NFTABLES_SKIP_KERNEL_VERSION_CHECK`.
- **The node may not have `nft` at all.** The minikube node had none; the `nft`
  in the kube-proxy pod was used for everything above. Install the host tools
  before you need to debug at 3 a.m.
- **Other components still use iptables.** Calico, Docker and node firewalls
  may keep writing iptables rules. Because of §7, a DROP in any of their tables
  still wins.
- **IPVS is on its way out** (deprecated, to be removed in 1.43;
  [kubernetes-services.md](kubernetes-services.md) §6.1), so nftables is the
  migration path for large clusters.

I switched kube-proxy back to iptables mode afterwards. It removed its
nftables table and Service traffic kept working.

## 11. Production practice

- **Load rules from files, not command by command.** Keep the ruleset in a file
  and apply it with `nft -f`, which is atomic (§2.4). Check it first with
  `nft -c -f`. On Debian, Ubuntu and RHEL, `/etc/nftables.conf` plus
  `systemctl enable nftables` restores it at boot.
- **Own one table; never `flush ruleset` on a shared host.** `flush ruleset`
  deletes *every* table, including Docker's, kube-proxy's and the CNI plugin's,
  and that breaks containers and Services until they resync. Replace only your
  own table, atomically:

  ```
  table inet mine
  flush table inet mine
  table inet mine {
      chain input { type filter hook input priority filter; policy accept;
          ...
      }
  }
  ```

  The empty `table inet mine` line makes sure the table exists, so the flush
  never fails on a first run. Loading this file twice left exactly one rule in
  `mine`, and a second table (`inet other`) was untouched.
- **Remember any table can drop.** Your `accept` doesn't override another
  table's `drop` (§7). Choose priorities on purpose, and use `nft monitor trace`
  when the result surprises you.
- **Put every list in a set or map.** Plain rules are as linear as iptables
  (§8). Blocklists, allowlists and port lists belong in named sets you can
  update live (`nft add element`), with `flags timeout` for entries that should
  expire.
- **Use `inet` for host firewalls.** One table covers IPv4 and IPv6, so IPv6
  isn't left open by accident. Use `ip` or `ip6` only where the protocols
  genuinely differ, as with NAT on older kernels.
- **Pin and match versions.** Old `nft` binaries can mis-display or break on
  rules written by newer ones (§10; the iptables equivalent is in
  [iptables.md](iptables.md) §8). Keep the host's `nft` current, and read
  rules with the binary that wrote them when output looks wrong.
- **Count what you care about.** Counters are opt-in (`counter`) and cost a
  little per packet. Use named counters (`counter name`) or per-rule counters
  on the rules you monitor, not on everything.
- **Don't mix in iptables-legacy.** Legacy rules live outside nftables, and
  `nft list ruleset` doesn't show them ([iptables.md](iptables.md) §8).

## 12. Reserved words

These words are keywords in nft 1.0.2 and can't be used as table, chain,
set or object names. Hook and chain-type names (`filter`, `nat`, `input`,
`forward`, ...) are not keywords and are fine as names.

```
accept ackseq add addr ah all arp auto-merge bridge cgroup chain chains
checksum code comment comp constant continue counter counters cpi cpu
create ct day dccp define delete describe device devices dnat doff dport
drop dscp dst dup dynamic ecn element elements esp ether exists expires
export exthdr fib flags flow flowtable flush frag frag-off fully-random
fwd gateway gc-interval get goto group handle hbh hdrlength hook hour
ibriport ibrname icmp icmpv6 icmpx id igmp iif iifgroup iifname iiftype
import include index inet insert interval ip ip6 ipsec jhash json jump
last-entry length limit list ll log map mark masquerade max-delay memory
meta meter mh missing monitor more-fragments mrt mss mtu name netdev
nexthdr nftrace nh notrack numgen obriport obrname offload oif oifgroup
oifname oiftype option options osf param-problem performance persistent
pkttype policy port position prefix priority protocol queue quota quotas
random redefine redirect reject rename replace reserved reserved2 reset
return rt rt0 rt2 rtclassid rule rules ruleset sack-permitted sctp
secmark set sets sid size skgid skuid snat socket spi sport srh symhash
synproxy table tables tag tcp th timeout timestamp to tproxy trace ttl
type typeof udp udplite undefine update urgptr version vlan vm vmap
window with wscale xml
```

### 12.1 Commands and scripting

`add create insert replace delete get list flush rename reset describe
monitor json include define redefine undefine` and the
object words they take (`table tables chain chains rule rules ruleset set
sets element elements counters quotas handle index position`):

```
nft add table inet cmd
nft create table inet cmd                  # like add, but fails if it exists
nft add chain inet cmd input '{ type filter hook input priority 0; }'
nft add rule inet cmd input tcp dport 22 counter accept
nft -a list chain inet cmd input           # -a shows the handles
nft insert rule inet cmd input position 3 tcp dport 2222 accept   # before handle 3
nft add rule inet cmd input index 0 tcp dport 23 drop             # after the 1st rule
nft replace rule inet cmd input handle 3 tcp dport 22 counter drop
nft delete rule inet cmd input handle 3
nft rename chain inet cmd input in2
nft add set inet cmd blocked '{ type ipv4_addr; }'
nft add element inet cmd blocked '{ 192.0.2.1 }'
nft get element inet cmd blocked '{ 192.0.2.1 }'    # is it in the set?
nft delete element inet cmd blocked '{ 192.0.2.1 }'
nft list tables / chains / sets / counters / quotas / ruleset
nft reset counters table inet cmd          # read and zero them
nft flush chain inet cmd in2               # empty a chain; also: flush table, flush ruleset
nft describe tcp dport
# payload expression, datatype inet_service (internet network service) (basetype integer), 16 bits
nft monitor trace                          # §9
nft -j list ruleset                        # JSON output
```

`position` takes a rule **handle**, not a line number.

In ruleset files:

```
include "/etc/nftables.d/*.nft"
define web_ports = { 80, 443 }
redefine web_ports = { 80, 443, 8080 }
undefine web_ports
```

### 12.2 Families, chains and their properties

`ip ip6 inet arp bridge netdev`, then `hook device devices priority policy
type typeof comment`:

```
table netdev nd {
	chain in {
		type filter hook ingress device lo priority filter; policy accept;
	}
}
table inet demo {
	comment "keyword examples"
	chain input {
		type filter hook input priority filter; policy accept;
	}
}
```

### 12.3 Sets, maps and named objects

`set map vmap element elements flags interval timeout gc-interval size
auto-merge constant dynamic expires policy memory performance counter quota
limit meter ct secmark synproxy flowtable offload name`:

```
table inet demo {
	set blocked {
		type ipv4_addr
		flags interval, timeout
		timeout 1h
		gc-interval 10m
		size 65535
		auto-merge
		elements = { 192.0.2.0/24 timeout 30m expires 10m }
	}
	set seen {
		typeof ip saddr          # type taken from an expression
		flags dynamic            # filled from the packet path
	}
	set admin_ports {
		type inet_service
		flags constant           # can't be changed after load
		elements = { 22, 2222 }
	}
	set memset {
		type ipv4_addr
		policy memory            # or: policy performance
	}
	map dispatch {
		type inet_service : verdict
		elements = { 22 : jump ssh }
	}
	counter ssh_hits {
		comment "SSH packets"
	}
	limit ssh_rate {
		rate 5/minute
	}
	quota monthly {
		over 500 mbytes
	}
	ct helper ftp_h {
		type "ftp" protocol tcp
	}
	secmark sshd {
		"system_u:object_r:ssh_server_packet_t:s0"
	}
	synproxy sp {
		mss 1460
		wscale 7
		timestamp sack-perm
	}
	flowtable ft {
		hook ingress priority filter
		devices = { lo }
		flags offload
	}
	chain ssh {
		counter name ssh_hits
		limit name ssh_rate accept
		drop
	}
	chain input {
		type filter hook input priority filter; policy accept;
		tcp dport vmap @dispatch
		ip saddr @blocked drop
		update @seen { ip saddr }
		quota name monthly drop
		meter flood size 65535 { ip saddr limit rate 100/second } counter
	}
}
```

### 12.4 Verdicts

`accept drop continue return jump goto queue reject`:

```
ct state established,related accept
ip ttl 1 drop
tcp dport 9999 log prefix "probe " level warn continue   # log, then keep evaluating
jump ssh                   # run ssh, then come back
goto ssh                   # run ssh, don't come back
return                     # back to the chain that jumped here
tcp dport 11 queue num 0-3 fanout bypass                  # hand to userspace (NFQUEUE)
tcp dport 10000 reject with tcp reset
udp dport 10001 reject with icmpx type port-unreachable
```

### 12.5 Statements: logging, marking, NAT and forwarding

`counter log prefix group with to dnat snat masquerade redirect random
fully-random persistent tproxy notrack dup fwd synproxy set numgen jhash
symhash nftrace`:

```
tcp dport 9998 log group 0 snaplen 64 queue-threshold 10   # NFLOG; level and group exclude each other
meta nftrace set 1                                         # §9
ct mark set 0x1
ct zone set 1
ct count over 20 drop
tcp dport 22 meta secmark set "sshd"
tcp dport 21 ct helper set "ftp_h"
tcp dport 12 synproxy name sp

# in a raw-priority chain, before conntrack:
tcp dport 13 notrack
flow add @ft

# in a type nat prerouting chain:
tcp dport 8080 dnat ip to 10.0.0.2:80
tcp dport 8081 redirect to :80
tcp dport 8082 dnat ip to numgen inc mod 2 map { 0 : 10.0.0.2, 1 : 10.0.0.3 }
tcp dport 8085 dnat ip to numgen random mod 2 map { 0 : 10.0.0.2, 1 : 10.0.0.3 }
tcp dport 8083 dnat ip to jhash ip saddr mod 2 map { 0 : 10.0.0.2, 1 : 10.0.0.3 }
tcp dport 8084 dnat ip to symhash mod 2 map { 0 : 10.0.0.2, 1 : 10.0.0.3 }

# in a type nat postrouting chain:
oifname "eth0" masquerade random,persistent
ip saddr 10.0.0.0/8 snat ip to 192.0.2.1 fully-random

# in a mangle-priority prerouting chain:
tcp dport 3128 tproxy ip to :3129 meta mark set 1 accept

# in an ip table (dup is not supported in inet):
tcp dport 8 dup to 10.0.0.9

# in a netdev ingress chain:
tcp dport 14 fwd to lo
```

### 12.6 Protocol headers and their fields

Header words: `ether vlan arp ip ip6 tcp udp udplite sctp dccp icmp icmpv6
icmpx igmp ah esp comp th ll nh`. Field words: `dport sport ttl dscp ecn
version length protocol frag-off id code checksum ackseq doff urgptr window
spi cpi mrt max-delay param-problem nexthdr gateway`:

```
ip version 4 ip dscp cs1 ip ecn ce counter
ip length > 1500 counter
ip frag-off & 0x1fff != 0 drop
ip protocol esp accept
ip6 nexthdr ah accept
tcp doff 5 tcp urgptr 0 tcp window 0 tcp ackseq 0 counter
tcp checksum 0 counter
udplite dport 5000 counter
dccp dport 5000 counter
sctp dport 5000 counter
th dport 53 counter                       # any transport header: TCP, UDP, SCTP...
icmp type echo-request icmp code 0 icmp id 1 counter
icmpv6 type parameter-problem icmpv6 param-problem 0 counter
icmpv6 type mld-listener-query icmpv6 max-delay 0 counter
igmp type membership-query igmp mrt 10 counter
ah spi 1 counter
esp spi 1 counter
comp cpi 1 counter
@ll,0,48 0x001122334455 counter           # raw bytes: link layer, offset 0, 48 bits
@nh,0,8 0x45 counter                      # raw bytes: network header

# in a netdev chain:
ether saddr 00:11:22:33:44:55 drop
vlan id 10 counter

# in an arp table:
arp operation request counter
```

### 12.7 IPv6 extension headers and options

`exthdr hbh dst frag rt rt0 srh mh hdrlength more-fragments reserved
reserved2 addr last-entry tag sid exists missing option options
sack-permitted mss wscale timestamp`:

```
exthdr hbh exists counter
hbh nexthdr tcp counter
hbh hdrlength 0 counter
dst nexthdr tcp counter                    # destination options header
frag more-fragments 1 counter
frag reserved 0 frag reserved2 0 counter
rt type 0 counter
rt0 addr[1] ::1 counter
srh last-entry 0 srh tag 0 srh sid[1] ::1 counter
mh type 1 counter
tcp option sack-permitted exists counter
tcp flags syn tcp option maxseg size 1460 counter
ip option rr exists counter
ip option rr missing counter
```

### 12.8 meta, ct, rt, fib, socket, osf, ipsec

`meta iif oif iifname oifname iifgroup oifgroup iiftype oiftype ibriport
obriport ibrname obrname mark pkttype cpu skuid skgid cgroup day hour
rtclassid nftrace ct rt mtu fib socket osf ipsec`:

```
meta iif lo accept
meta iifname "eth0" meta iifgroup 0 meta iiftype ether counter
meta oif lo meta oifname "lo" meta oifgroup 0 meta oiftype loopback counter
meta mark 0x1 meta pkttype host meta cpu 0 counter
meta skuid 0 meta skgid 0 counter         # owning socket's user and group (output)
meta cgroup 1 counter
meta rtclassid 1 counter
meta day "Monday" meta hour "09:00"-"17:00" counter
meta time > "2026-01-01" counter
ct original ip saddr 10.0.0.1 counter
rt ip nexthop 192.0.2.1 counter           # plain "rt nexthop" is ambiguous in inet
rt mtu 1400 counter
rt classid 1 counter
fib daddr type local counter
fib saddr . iif oif missing drop          # reverse-path filter
socket transparent 1 counter
osf name "Linux" counter                  # passive OS fingerprint
ipsec in reqid 1 ipsec in spi 1 counter

# in a bridge table:
meta ibrname "br0" meta obrname "br0" counter
meta ibriport "eth1" meta obriport "eth2" counter
```
