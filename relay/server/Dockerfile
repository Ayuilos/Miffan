# Miffan relay for a VPS: build the single-file binary, run it on a slim base.
#
#   docker build -t miffan-relay relay/server           (from the repository root)
#   docker run -d --name relay --restart unless-stopped -p 8787:8787 \
#     -v relay-data:/data -e ACCESS_TOKEN=... -e ADMIN_TOKEN=... miffan-relay
#
# The relay speaks plain HTTP/WebSocket; put a TLS reverse proxy (Caddy, see README.md) in front of it.

FROM oven/bun:1 AS build
WORKDIR /src
COPY package.json bun.lock ./
RUN bun install --frozen-lockfile
COPY core ./core
COPY bun ./bun
COPY tsconfig.json ./
RUN bun build --compile --minify bun/server.ts --outfile /out/miffan-relay

FROM oven/bun:1-slim
COPY --from=build /out/miffan-relay /usr/local/bin/miffan-relay
ENV HOST=0.0.0.0 \
    PORT=8787 \
    RELAY_CONFIG_FILE=/data/relay.config.json \
    RELAY_DB=/data/relay.sqlite
VOLUME /data
WORKDIR /data
EXPOSE 8787
USER bun
ENTRYPOINT ["/usr/local/bin/miffan-relay"]
