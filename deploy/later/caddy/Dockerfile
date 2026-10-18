# vibefix-web: Caddy serving the SPA and proxying /api and /webhooks to app (AD-26).
# Base images are pinned by tag and digest. Build context: the repository root
# (docker build -f deploy/caddy/Dockerfile .); the root .dockerignore keeps it to these files.

# The SPA bundle is the same for every platform, so it is built once on the build machine's platform.
FROM --platform=$BUILDPLATFORM node:24.22.0-bookworm-slim@sha256:a5f4366e834695d5f363b920c104953a535d3a9864d1bdf74566a0ebe01daee5 AS build
WORKDIR /frontend
COPY frontend/package.json frontend/package-lock.json frontend/.npmrc ./
RUN --mount=type=cache,target=/root/.npm npm ci --no-audit --no-fund
COPY frontend/ ./
RUN npm run build

FROM caddy:2.11.7@sha256:685ae3d7c34d9b5f8caeddc5d14051db7bbd88ff1f5a2fa0c16bd8ca328fb7af
ENV TZ=UTC
COPY deploy/caddy/Caddyfile /etc/caddy/Caddyfile
COPY --from=build /frontend/dist/ /srv/
