# egress (AD-26): Squid from Ubuntu 24.04's main archive, which Canonical keeps patching. The base is
# pinned by digest; Squid comes from noble-security at build time, so CI rebuilds this image every
# week without the layer cache to pick up fixes (.github/workflows/ci.yml).
FROM ubuntu:24.04@sha256:68ee22004a9109b44973313b02ff7b1de250f1de0f5ecb628d73f579aaff33ac

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
