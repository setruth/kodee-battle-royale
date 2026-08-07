# Docker deployment for korilin.com/kt15

This setup targets a Linux x86_64 server with an existing HTTPS Nginx virtual host for `korilin.com`.
Docker runs PostgreSQL, Ktor, and the built Vue site. The host Nginx remains responsible for TLS and the `/kt15` route.
The backend image also installs the PulseAudio, udev, X11, D-Bus, ALSA, GBM, Pango, Cairo, ATK, NSS, and related shared
libraries required by the bundled `webrtc-java` Linux native runtime; these packages do not need to be installed
separately on the Docker host.

## 1. Server prerequisites

Install Docker Engine with the Compose plugin. Ensure DNS for `korilin.com` points to this server and HTTPS already works.

Open the WebRTC UDP range in both the cloud security group and the host firewall:

```bash
sudo ufw allow 50000:50100/udp
```

Do not expose ports `8011`, `15432`, or `18080` publicly. PostgreSQL and the frontend bind to loopback;
the Ktor HTTP connector also listens on loopback even though its container uses host networking for WebRTC.

## 2. Configure secrets

From the repository root on the server:

```bash
cp .env.example .env
openssl rand -hex 32
openssl rand -hex 32
```

Put one generated value in `POSTGRES_PASSWORD` and the other in `JWT_SECRET` inside `.env`.
Set `WEBRTC_STUN_URL` only when a reachable STUN server is available. If it is empty or WebRTC negotiation fails,
game traffic automatically falls back to WebSocket.

## 3. Build and start

```bash
docker compose up -d --build
docker compose ps
docker compose logs -f backend
```

The first build downloads Gradle, JVM, Node, Nginx, PostgreSQL, and project dependencies, so it can take several minutes.

Verify the local services before editing the public Nginx config:

```bash
curl http://127.0.0.1:8011/api/health
curl -I http://127.0.0.1:18080/
```

## 4. Connect korilin.com

Copy the `location` blocks from `deploy/korilin.com.nginx.conf` into the existing HTTPS `server {}` block for
`korilin.com`. Do not create a second `server` block with the same domain.

Then validate and reload Nginx:

```bash
sudo nginx -t
sudo systemctl reload nginx
```

Verify the public endpoint:

```bash
curl https://korilin.com/kt15/api/health
```

Open `https://korilin.com/kt15/` in a browser. The trailing slash is canonical; `/kt15` redirects to it.

## Operations

```bash
# Status and logs
docker compose ps
docker compose logs -f --tail=200

# Deploy a new commit
git pull
docker compose up -d --build

# Restart without rebuilding
docker compose restart

# Stop containers without deleting database data
docker compose down
```

The PostgreSQL database is stored in the named volume `kodee-battle-royale_postgres-data`.
Do not run `docker compose down -v` unless deleting all user and match data is intentional.
