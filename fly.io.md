Agent / WireGuard — agent/ + wg/tunnel.go. A long-lived local daemon that owns a userspace WireGuard tunnel into your org's 6PN, so `flyctl ssh`, f`lyctl proxy`, log streaming, and remote-builder traffic can reach private IPs without re-establishing the tunnel each invocation

`Apps` live inside an Organization, which owns billing, members, and a per-org WireGuard mesh.

`flyctl` runs a long-lived background daemon — the agent.

- Client side: agent/client.go, talking over a Unix socket (`~/.fly/fly-agent.sock`, see `agent/agent.go:10`) or named pipe on Windows.
- Server side: `agent/server/server.go`. server.Run binds the socket and maintains a map of WireGuard tunnels (`tunnels: make(map[tunnelKey]*wg.Tunnel`), `server.go:64`), one per org/region. 

flyctl needs to reach private resources inside your Fly org's IPv6 network (*.internal DNS, Machine private addresses, SSH, Postgres, remote Docker builders). It does this with a fully userspace WireGuard implementation — no kernel module, no wg0 interface, no root. Everything lives in two packages:
- `wg/` — the actual WireGuard tunnel (data plane).
- `internal/wireguard/` — peer provisioning + on-disk state (control plane).
- `agent/server/` — the long-lived daemon that owns tunnels and serves them to CLI commands.

```go
func C25519pair() (string, string) {
	var private [32]byte
	_, err := rand.Read(private[:])
	if err != nil {
		panic(fmt.Sprintf("reading from random: %s", err))
	}

	public, err := curve25519.X25519(private[:], curve25519.Basepoint)
	if err != nil {
		panic(fmt.Sprintf("can't mult: %s", err))
	}

	return base64.StdEncoding.EncodeToString(public),
		base64.StdEncoding.EncodeToString(private[:])
}
```
The private key never leaves your machine. flyctl then registers the public key with Fly via the GraphQL API
`data, err := apiClient.CreateWireGuardPeer(ctx, org.ID, regionCode, name, pubkey, network)`


The server responds with a `CreatedWireGuardPeer` containing the gateway's public key (`Pubkey`), the gateway endpoint IP (`Endpointip`), and the IPv6 address assigned to you (`Peerip`). The peer is named `<host>-<email>-<ULID>` so it's identifiable in the dashboard (wg.go:31). If no region is given, it picks the closest gateway region (`ClosestWireguardGatewayRegion`, wg.go:93)

```go
var cleanDNSPattern = regexp.MustCompile(`[^a-zA-Z0-9\\-]`)

type WebClient interface {
	ValidateWireGuardPeers(ctx context.Context, peerIPs []string) (invalid []string, err error)
}

func generatePeerName(ctx context.Context, apiClient flyutil.Client) (string, error) {
	user, err := apiClient.GetCurrentUser(ctx)
	if err != nil {
		return "", err
	}
	emailSlug := cleanDNSPattern.ReplaceAllString(user.Email, "-")

	host, err := os.Hostname()
	if err != nil {
		return "", err
	}
	hostSlug := cleanDNSPattern.ReplaceAllString(strings.Split(host, ".")[0], "-")

	name := fmt.Sprintf("%s-%s-%s", hostSlug, emailSlug, ulid.Make())

	return name, nil
}

func StateForOrg(ctx context.Context, apiClient flyutil.Client, org *fly.Organization, regionCode string, name string, reestablish bool, network string) (*wg.WireGuardState, error) {
	state, err := getWireGuardStateForOrg(org.Slug, network)
	if err != nil {
		return nil, err
	}
	if state != nil && !reestablish && (regionCode == "" || state.Region == regionCode) {
		return state, nil
	}

	terminal.Debugf("Can't find matching WireGuard configuration; creating new one\n")

	stateb, err := Create(apiClient, org, regionCode, name, network, "interactive")
	if err != nil {
		return nil, err
	}

	if err := setWireGuardStateForOrg(ctx, org.Slug, network, stateb); err != nil {
		return nil, err
	}

	return stateb, nil
}

func Create(apiClient flyutil.Client, org *fly.Organization, regionCode, name, network string, namePrefix string) (*wg.WireGuardState, error) {
	ctx := context.TODO()
	var (
		err error
		rx  = regexp.MustCompile(`^[a-zA-Z0-9\\-]+$`)
	)

	if name == "" {
		n, err := generatePeerName(ctx, apiClient)
		if err != nil {
			return nil, err
		}

		name = fmt.Sprintf("%s-%s", namePrefix, n)
	}

	if regionCode == "" {
		regionCode = os.Getenv("FLYCTL_WG_REGION")
	}

	if regionCode == "" {
		region, err := apiClient.ClosestWireguardGatewayRegion(ctx)
		if err != nil {
			return nil, err
		}
		regionCode = region.Code
	}

	if !rx.MatchString(name) {
		return nil, errors.New("name must consist solely of letters, numbers, and the dash character")
	}

	fmt.Printf("Creating WireGuard peer \"%s\" in region \"%s\" for organization %s\n", name, regionCode, org.Slug)

	pubkey, privatekey := C25519pair()

	data, err := apiClient.CreateWireGuardPeer(ctx, org.ID, regionCode, name, pubkey, network)
	if err != nil {
		return nil, err
	}

	return &wg.WireGuardState{
		Name:         name,
		Region:       regionCode,
		Org:          org.Slug,
		LocalPublic:  pubkey,
		LocalPrivate: privatekey,
		Peer:         *data,
	}, nil
}

func C25519pair() (string, string) {
	var private [32]byte
	_, err := rand.Read(private[:])
	if err != nil {
		panic(fmt.Sprintf("reading from random: %s", err))
	}

	public, err := curve25519.X25519(private[:], curve25519.Basepoint)
	if err != nil {
		panic(fmt.Sprintf("can't mult: %s", err))
	}

	return base64.StdEncoding.EncodeToString(public),
		base64.StdEncoding.EncodeToString(private[:])
}

func GetWireGuardState() (wg.States, error) {
	states := wg.States{}

	if err := viper.UnmarshalKey(flyctl.ConfigWireGuardState, &states); err != nil {
		return nil, errors.Wrap(err, "invalid wireguard state")
	}

	return states, nil
}

func getWireGuardStateForOrg(orgSlug string, network string) (*wg.WireGuardState, error) {
	states, err := GetWireGuardState()
	if err != nil {
		return nil, err
	}

	sk := orgSlug
	if network != "" {
		sk = fmt.Sprintf("%s-%s", orgSlug, network)
	}

	return states[sk], nil
}

func setWireGuardState(ctx context.Context, s wg.States) error {
	viper.Set(flyctl.ConfigWireGuardState, s)
	configPath := state.ConfigFile(ctx)
	if err := config.SetWireGuardState(configPath, s); err != nil {
		return errors.Wrap(err, "error saving config file")
	}

	return nil
}

func setWireGuardStateForOrg(ctx context.Context, orgSlug, network string, s *wg.WireGuardState) error {
	states, err := GetWireGuardState()
	if err != nil {
		return err
	}

	sk := orgSlug
	if network != "" {
		sk = fmt.Sprintf("%s-%s", orgSlug, network)
	}

	states[sk] = s

	return setWireGuardState(ctx, states)
}

func PruneInvalidPeers(ctx context.Context, apiClient WebClient) error {
	state, err := GetWireGuardState()
	if err != nil {
		return nil
	}

	peerIPs := make([]string, 0, len(state))
	for _, peer := range state {
		peerIPs = append(peerIPs, peer.Peer.Peerip)
	}

	invalidPeerIPs, err := apiClient.ValidateWireGuardPeers(ctx, peerIPs)
	if err != nil {
		return err
	}

	for _, invalidPeerIP := range invalidPeerIPs {
		for orgSlug, peer := range state {
			if peer.Peer.Peerip == invalidPeerIP {
				terminal.Debugf("removing invalid peer %s for organization %s", invalidPeerIP, orgSlug)
				delete(state, orgSlug)
			}
		}
	}

	return setWireGuardState(ctx, state)
}
```

That combined local-keys + server-response bundle is the `WireGuardState`, persisted to flyctl's config keyed by org slug (and network name), so subsequent runs reuse the same peer instead of creating a new one each time 

Cryptokey Routing — the one concept that matters: AllowedIPs
This is the heart of WireGuard and the thing people misconfigure. Each peer entry has an AllowedIPs list, and it serves two directions at once:
- Outbound (routing): "To send a packet to one of these IPs, encrypt it for this peer." It's a routing table. Longest-prefix match decides which peer a packet goes to.
- Inbound (access control): "Only accept a decrypted packet from this peer if its source IP falls within these ranges." Anything else is dropped.

```go
type WireGuardState struct {
	Org          string                   `json:"org"`
	Name         string                   `json:"name"`
	Region       string                   `json:"region"`
	LocalPublic  string                   `json:"localprivate"`
	LocalPrivate string                   `json:"localpublic"`
	DNS          string                   `json:"dns"`
	Peer         fly.CreatedWireGuardPeer `json:"peer"`
}

type States map[string]*WireGuardState

// BUG(tqbf): Obviously all this needs to go, and I should just
// make my code conform to the marshal/unmarshal protocol wireguard-go
// uses, but in the service of landing this feature, I'm just going
// to apply a layer of spackle for now.
func (s *WireGuardState) TunnelConfig() *Config {
	skey := PrivateKey{}
	if err := skey.UnmarshalText([]byte(s.LocalPrivate)); err != nil {
		panic(fmt.Sprintf("martian local private key: %s", err))
	}

	pkey := PublicKey{}
	if err := pkey.UnmarshalText([]byte(s.Peer.Pubkey)); err != nil {
		panic(fmt.Sprintf("martian local public key: %s", err))
	}

	_, lnet, err := net.ParseCIDR(fmt.Sprintf("%s/120", s.Peer.Peerip))
	if err != nil {
		panic(fmt.Sprintf("martian local public: %s/120: %s", s.Peer.Peerip, err))
	}

	raddr := net.ParseIP(s.Peer.Peerip).To16()
	for i := 6; i < 16; i++ {
		raddr[i] = 0
	}

	// BUG(tqbf): for now, we never manage tunnels for different
	// organizations, and while this comment is eating more space
	// than the code I'd need to do this right, it's more fun to
	// type, so we just hardcode.
	_, rnet, _ := net.ParseCIDR(fmt.Sprintf("%s/48", raddr))

	raddr[15] = 3
	dns := net.ParseIP(raddr.String())

	// BUG(tqbf): I think this dance just because these needed to
	// parse for Ben's TOML code.
	wgl := IPNet(*lnet)
	wgr := IPNet(*rnet)

	var wgLogLevel int
	switch terminal.GetLogLevel() {
	case logger.Debug:
		wgLogLevel = device.LogLevelVerbose
	case logger.Info | logger.Warn | logger.Error:
		wgLogLevel = device.LogLevelError
	}

	return &Config{
		LocalPrivateKey: skey,
		LocalNetwork:    &wgl,
		RemotePublicKey: pkey,
		RemoteNetwork:   &wgr,
		Endpoint:        net.JoinHostPort(s.Peer.Endpointip, "51820"),
		DNS:             dns,
		LogLevel:        wgLogLevel,
	}
}
```
So in the Fly code, recall `TunnelConfig()` derived `allowed_ip = <org /48> (wg/state.go:54)`. That single line means: all traffic to the org's `fdaa:.../48` block goes through this tunnel to the Fly gateway, and only packets sourced from that block are accepted back. `0.0.0.0/0`, `::/0` as AllowedIPs is the classic "route ALL traffic through the VPN" full-tunnel config

