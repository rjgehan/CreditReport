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

## 5. Claude

Claude reads your transactions each morning and writes a short take on Home ("on track", "dining is
up"), files the transactions your rules missed, flags odd charges, and suggests rules and targets
for you to approve. On demand it answers "Can I afford it?". It runs Claude Code inside the app on
your own Claude subscription, like the stocks app's trader: no API key, and it never sees balances.

1. On your computer, run `claude setup-token` and sign in. It prints a long token.
2. In the app, **Accounts, More, Claude**: paste it and tap **Connect**.

It runs every morning at 7 (`INSIGHTS_CRON` to change it); **Refresh** on the Home card runs it now.

## Backups

Everything lives in `/DATA/AppData/finance` (`finance.db` and the logo cache). Back up that
folder, and keep `APP_ENCRYPTION_KEY` somewhere safe; without it the stored SimpleFIN connection
can't be read and you'd paste a new setup token.
