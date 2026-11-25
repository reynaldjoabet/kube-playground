```sh
minikube start --memory=8192 --cpus=4 --disk-size=40g --vm-driver=virtualbox
helm repo add yugabytedb https://charts.yugabyte.com
helm repo update
kubectl create namespace yb-demo
helm install yb-demo yugabytedb/yugabyte --set resource.master.requests.cpu=0.5,resource.master.requests.memory=0.5Gi,resource.tserver.requests.cpu=0.5,resource.tserver.requests.memory=0.5Gi,replicas.master=1,replicas.tserver=1,Image.tag=2025.2.2.2-b11 --namespace yb-demo
```

## PostgreSQL: `FATAL: role "postgres" does not exist` (lab, Homebrew on macOS)

pgAdmin, connecting to `localhost:5432` as `postgres`:

```text
connection failed: connection to server at "127.0.0.1", port 5432 failed: FATAL: role "postgres" does not exist
Multiple connection attempts failed. All failures were:
- host: 'localhost', port: '5432', hostaddr: '::1': connection failed: ... role "postgres" does not exist
- host: 'localhost', port: '5432', hostaddr: '127.0.0.1': connection failed: ... role "postgres" does not exist
```

A server answered, so the connection itself worked. The server has no role of that name. Three
read-only checks show why.

**If `psql` gives `command not found`:** `psql` isn't on your shell's `PATH`. Homebrew's
versioned formulas such as `postgresql@19` are *keg-only*: Homebrew installs them under
`/opt/homebrew/opt/postgresql@19/` but doesn't link their commands into `/opt/homebrew/bin`. That
stops two installed versions from fighting over the name `psql`. The server still runs, because
`brew services` starts it by full path. Call `/opt/homebrew/opt/postgresql@19/bin/psql`, or add
that directory to `PATH` in `~/.zshrc`:

```sh
echo 'export PATH="/opt/homebrew/opt/postgresql@19/bin:$PATH"' >> ~/.zshrc
```

**What's listening on port 5432.** `-n` and `-P` skip name lookups:

```sh
lsof -nP -iTCP:5432 -sTCP:LISTEN
```
```text
COMMAND   PID   USER   FD   TYPE  ...  NAME
postgres 1277 harmony   7u  IPv6  ...  TCP [::1]:5432 (LISTEN)
postgres 1277 harmony   8u  IPv4  ...  TCP 127.0.0.1:5432 (LISTEN)
```

One process listens on both loopback addresses, so pgAdmin's two attempts (`::1` and `127.0.0.1`,
because `localhost` resolves to both) reached the same server. `brew services list` shows which
install it is: `postgresql@19 started harmony ~/Library/LaunchAgents/sh.brew.postgresql@19.plist`.

**Which roles exist.** `pg_roles` lists all roles; the filter hides the built-in `pg_*` ones.
`-A -t` prints unaligned rows without headers:

```sh
psql -h 127.0.0.1 -d postgres -Atc \
  "select rolname, rolsuper, rolcanlogin from pg_roles where rolname !~ '^pg_'"
```
```text
harmony|t|t
```

The only role is `harmony`, a superuser that can log in. Homebrew runs `initdb` as the macOS user, so
the superuser gets that user's name. A `postgres` role only exists where an installer creates it:
the Linux packages, the official Docker image and the EDB installer do. The `psql` above passed no
`-U`, so it logged in as `harmony`, which is why it worked while pgAdmin's `postgres` didn't.

**How the server authenticates.** `pg_hba_file_rules` is the parsed `pg_hba.conf`; reading it needs
a superuser:

```sh
psql -h 127.0.0.1 -d postgres -Atc \
  "select type, database, user_name, address, auth_method from pg_hba_file_rules"
```
```text
local|{all}|{all}||trust
host|{all}|{all}|127.0.0.1|trust
host|{all}|{all}|::1|trust
...
```

Every rule is `trust`: no password is ever checked, as long as the role exists. pgAdmin's password
prompt is only there because no password was saved. Whatever is typed, the login fails on the role
name. It also means any process on the Mac can log in as any role, which is the Homebrew default and
fine on a dev machine.

**Fixes:**

- connect as the existing role: in pgAdmin's server Properties, under Connection, set Username to
  `harmony`, with no password;
- or create the role tools expect:
  `/opt/homebrew/opt/postgresql@19/bin/createuser -s postgres`;
- or, for a Docker Postgres, publish it on another port (`-p 5433:5432`). Port 5432 is taken by
  the Homebrew server, unless it's stopped with `brew services stop postgresql@19`.
