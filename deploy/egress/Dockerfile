# egress (AD-26): Squid from Ubuntu 24.04's main archive, which Canonical keeps patching. The base is
# pinned by digest; Squid comes from noble-security at build time, so CI rebuilds this image every
# week without the layer cache to pick up fixes (.github/workflows/ci.yml).
FROM ubuntu:24.10@sha256:cdf755952ed117f6126ff4e65810bf93767d4c38f5c7185b50ec1f1078b464cc

RUN apt-get update \
	&& apt-get install -y --no-install-recommends squid \
	&& rm -rf /var/lib/apt/lists/* \
	&& install -d -o proxy -g proxy /run/squid

COPY squid.conf /etc/squid/squid.conf

USER proxy

# A configuration Squid would refuse fails the build, not the first start.
RUN squid -k parse -f /etc/squid/squid.conf

EXPOSE 3128 3129

# -N: stay in the foreground as the container's main process.
CMD ["squid", "-N", "-f", "/etc/squid/squid.conf"]
