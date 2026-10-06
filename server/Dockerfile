FROM rust:1-bookworm AS build
WORKDIR /build
COPY server/ ./server/
RUN cargo build --release --locked --manifest-path server/Cargo.toml --bins

FROM debian:bookworm-slim
RUN apt-get update \
    && apt-get install -y --no-install-recommends ca-certificates curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --gid 10001 blackbox \
    && useradd --uid 10001 --gid 10001 --no-create-home --shell /usr/sbin/nologin blackbox \
    && mkdir -p /var/lib/blackbox \
    && chown blackbox:blackbox /var/lib/blackbox
COPY --from=build /build/server/target/release/opnord-server /usr/local/bin/opnord-server
COPY --from=build /build/server/target/release/provision_dashboard /usr/local/bin/provision_dashboard
COPY --from=build /build/server/target/release/provision_device /usr/local/bin/provision_device
USER blackbox
ENV DASHBOARD_CREDENTIALS_FILE=/var/lib/blackbox/dashboard-login.txt \
    DEVICE_CREDENTIALS_FILE=/var/lib/blackbox/device-credentials.json
EXPOSE 8080
CMD ["opnord-server"]
