# Docker deployment for korilin.com/kt15

This setup targets a Linux x86_64 server with an existing HTTPS Nginx virtual host for `korilin.com`.
Docker runs PostgreSQL, Ktor, and the built Vue site. The host Nginx remains responsible for TLS and the `/kt15` route.
The backend image also installs the PulseAudio, udev, X11, D-Bus, ALSA, GBM, Pango, Cairo, ATK, NSS, and related shared
libraries required by the bundled `webrtc-java` Linux native runtime; these packages do not need to be installed
separately on the Docker host.

All Docker Hub base images use the explicit `m.daocloud.io/docker.io/library/` prefix by default so deployment on
mainland China servers does not fall back to `registry-1.docker.io`. Set `DOCKER_HUB_PREFIX=` in `.env` to use
Docker Hub directly in another region.

Gradle plugins and Maven dependencies use Alibaba Cloud mirrors exclusively. This prevents Gradle from querying
unreachable overseas repositories for dynamic dependency metadata on mainland China servers. Docker BuildKit persists
only Gradle's dependency cache across builds; temporary Gradle files remain isolated to each build, and concurrent builds
serialize access to the dependency cache.

## 1. Server prerequisites

Install Docker Engine with the Compose plugin. Ensure DNS for `korilin.com` points to this server and HTTPS already works.

Open the WebRTC UDP range in both the cloud security group and the host firewall:

```bash
sudo ufw allow 50000:50100/udp
```

Do not expose ports `8011`, `15432`, or `18080` publicly. PostgreSQL and the frontend bind to loopback;
the Ktor HTTP connector also listens on loopback even though its container uses host networking for WebRTC.

## 2. Configure secrets

The deployment helper generates `.env` with random PostgreSQL and JWT secrets, builds the images, starts the
services, waits for Ktor, runs health checks, and prints the relevant deployment information:

```bash
chmod +x deploy.sh
./deploy.sh deploy
```

It preserves an existing `.env` and never prints either secret. To inspect non-sensitive deployment settings or
the host Nginx configuration later, run `./deploy.sh info` or `./deploy.sh nginx`.

Set `WEBRTC_STUN_URL` in `.env` only when a reachable STUN server is available. If it is empty or WebRTC
negotiation fails, game traffic automatically falls back to WebSocket.

## 3. Check status and logs

```bash
./deploy.sh status
./deploy.sh logs backend
```

The first build downloads Gradle, JVM, Node, Nginx, PostgreSQL, and project dependencies, so it can take several minutes.
Gradle uses plain detailed output during the image build, making slow dependency downloads visible in `deploy.log`.

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
./deploy.sh status
./deploy.sh logs

# Deploy a new commit
git pull
./deploy.sh up

# Restart without rebuilding
./deploy.sh restart

# Stop containers without deleting database data
./deploy.sh down
```

The PostgreSQL database is stored in the named volume `kodee-battle-royale_postgres-data`.
Do not run `docker compose down -v` unless deleting all user and match data is intentional.

## Offline image bundle

When the server cannot reliably access Docker or Maven registries, build all Linux x86_64 images on a development
computer with Docker Desktop:

```bash
./deploy.sh package
```

This creates `dist/kodee-battle-royale-linux-amd64.tar.gz`, containing the PostgreSQL, backend, and frontend runtime
images. The first local build still downloads the required base images and dependencies, but subsequent builds reuse
Docker and Gradle caches. Upload the bundle after pulling the latest deployment scripts on the server:

```bash
scp dist/kodee-battle-royale-linux-amd64.tar.gz root@SERVER_IP:/home/korilin/github/kodee-battle-royale/
```

Then start it on the server without pulling images or compiling code:

```bash
cd /home/korilin/github/kodee-battle-royale
git pull
./deploy.sh offline ./kodee-battle-royale-linux-amd64.tar.gz
```

The offline command preserves an existing `.env`, creates one with random secrets when missing, loads all three images,
and starts Compose with registry pulls and image builds disabled.
