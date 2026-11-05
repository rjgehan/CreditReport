# Deploying Finance

Every push to `main` runs the tests and publishes `ghcr.io/rjgehan/creditreport:latest`
(amd64 and arm64). The server runs that image on CasaOS, Watchtower keeps it current,
and Tailscale is the only way in.

## 1. The server

On the CasaOS box, over SSH:

```sh
# Data folder, owned by the user the container runs as
sudo mkdir -p /DATA/AppData/finance
sudo chown -R 10001:10001 /DATA/AppData/finance

# The key that encrypts the bank connection. Save it in your password manager.
openssl rand -base64 32
```

## 2. CasaOS

CasaOS, **+**, **Custom Install**, **Import**, then paste `deploy/casaos/docker-compose.yml`.
Fill in the two `CHANGE_ME` values (password and encryption key), then install.

The container listens only on `127.0.0.1:8095`, so nothing on the LAN or internet can reach it.

If no Watchtower is running on the server yet, install the one from Home-Dashboard's
`casaos/watchtower-compose.yml`. It updates every container labelled
`com.centurylinklabs.watchtower.enable=true`, which this one is.

## 3. Tailscale

The server must be on your tailnet (`tailscale status` shows it). If it isn't:

```sh
curl -fsSL https://tailscale.com/install.sh | sh
sudo tailscale up
```

In the Tailscale admin console, under **DNS**, turn on **MagicDNS** and **HTTPS Certificates**.
Then publish the app to your tailnet only:

```sh
sudo tailscale serve --bg --https=443 http://127.0.0.1:8095
tailscale serve status
```

If another app already uses 443 through `tailscale serve`, use `--https=8443` instead and add
`:8443` to the address. The app is now at:

```
https://<server-name>.<your-tailnet>.ts.net
```

On the iPhone, with Tailscale connected, open that address in Safari, sign in once, and use
**Share**, **Add to Home Screen**. The sign-in lasts a year.

## 4. Connecting accounts

- **Banks and cards (SimpleFIN Bridge, about $15/year):** sign up at bridge.simplefin.org,
  connect SoFi and Chase there, then **Apps, New app connection** to get a setup token. In the
  app: **Accounts**, **Connect a bank**, paste the token. Leave Webull and PCS out of the bridge.
- **Webull** and **PCS:** **Add by hand** (types Brokerage and Retirement). Add Webull's positions;
  on PCS, add a recurring contribution from your latest statement.
- **Anything else:** download a QFX/OFX or CSV from the bank and use **Import statement**.

## 5. Claude (optional)

Claude can answer questions about your money from your own Claude subscription: no API key. It
connects to the app's MCP server (`/mcp`) and signs in the way it does with any connector, and you
approve it on a consent screen behind your normal sign-in. It reads transactions and what the app
works out from them; it never sees balances, net worth or investments, and it can't change anything.
**Accounts, More, Claude** lists what's connected and disconnects it.

**Claude Code only, nothing public.** On a computer on your tailnet:

```sh
claude mcp add --transport http --scope user finance https://<server-name>.<your-tailnet>.ts.net/mcp
```

then `/mcp` in Claude Code, pick `finance` and sign in.

**Claude everywhere (claude.ai, the iPhone and desktop apps).** Anthropic's servers make the
calls, so a few paths need a public address. This uses a Cloudflare Tunnel, like the stocks app:

1. On the finance app (CasaOS, its settings), set `APP_URL` to the address you open it at over
   Tailscale and `APP_PUBLIC_URL` to the public one, e.g. `https://finance.<your-domain>`.
2. Cloudflare Zero Trust, **Networks, Tunnels, Create a tunnel, Cloudflared**, name it `finance`
   and copy its token. Import `deploy/casaos/finance-tunnel.yml` in CasaOS with that token.
3. On the tunnel's **Public hostname** tab add `finance.<your-domain>`, type **HTTP**, URL
   `127.0.0.1:8095`, and **Path**:

   ```
   ^/(mcp|oauth2/(register|token)|\.well-known/oauth-(protected-resource|authorization-server)(/mcp)?)$
   ```

   Anything else on that hostname gets the tunnel's 404: the app, its sign-in and the consent
   screen stay on Tailscale.
4. For that hostname: no challenge or Bot Fight Mode, and caching bypassed. To lock it to Anthropic,
   add a WAF custom rule that blocks requests to it unless the source IP is in `160.79.104.0/21`.
5. claude.ai, **Customize, Connectors, Add custom connector**: name `Finance`, URL
   `https://finance.<your-domain>/mcp`. If asked how to register, pick **Register automatically**;
   leave request headers empty. Click **Connect**, tap **Allow** in the page that opens (you need to
   be on Tailscale for this), and turn Finance on in a chat under **+, Connectors**.

## Backups

Everything lives in `/DATA/AppData/finance` (`finance.db` and the logo cache). Back up that
folder, and keep `APP_ENCRYPTION_KEY` somewhere safe; without it the stored SimpleFIN connection
can't be read and you'd paste a new setup token.
