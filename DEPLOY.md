# Deploying Finance

Every push to `main` runs the tests and publishes `ghcr.io/rjgehan/creditreport:latest`
(amd64 and arm64). The server runs that image on CasaOS, Watchtower keeps it current,
and Tailscale is the only way in.

## 1. Teller (free bank connections)

1. Sign up at teller.io and create an application. Note the **Application ID** (`app_…`).
2. In the dashboard, download `certificate.pem` and `private_key.pem`.
3. Keep the environment on **development**: it's free, uses real banks, and allows 100 logins.

## 2. The server

On the CasaOS box, over SSH:

```sh
# Data folder, owned by the user the container runs as
sudo mkdir -p /DATA/AppData/finance/teller
sudo chown -R 10001:10001 /DATA/AppData/finance

# Teller's certificate and key (copy them over first, e.g. with scp)
sudo cp certificate.pem private_key.pem /DATA/AppData/finance/teller/
sudo chown 10001:10001 /DATA/AppData/finance/teller/*.pem
sudo chmod 600 /DATA/AppData/finance/teller/*.pem

# The key that encrypts bank tokens. Save it in your password manager.
openssl rand -base64 32
```

## 3. CasaOS

CasaOS, **+**, **Custom Install**, **Import**, then paste `deploy/casaos/docker-compose.yml`.
Fill in the four `CHANGE_ME` values (password, encryption key, Teller application id), then install.

The container listens only on `127.0.0.1:8095`, so nothing on the LAN or internet can reach it.

If no Watchtower is running on the server yet, install the one from Home-Dashboard's
`casaos/watchtower-compose.yml`. It updates every container labelled
`com.centurylinklabs.watchtower.enable=true`, which this one is.

## 4. Tailscale

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

## 5. Connecting accounts

- **Accounts**, **Connect a bank**: SoFi, then Chase. Teller Connect opens; sign in to each bank.
- **Add by hand** for Webull (type Brokerage) and PCS (type Retirement). Add Webull's positions,
  then on PCS add a recurring contribution from your latest statement.
- Anything Teller can't reach: download a QFX/OFX or CSV from the bank and use **Import statement**.

## Backups

Everything lives in `/DATA/AppData/finance` (`finance.db` plus the Teller files). Back up that
folder, and keep `APP_ENCRYPTION_KEY` somewhere safe; without it the stored bank connections can't
be read.
