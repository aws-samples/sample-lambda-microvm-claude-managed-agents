# MicroVM image for Data Analyst Agent
#
# Extends the base worker with:
#   - Python 3 + pandas/numpy for data analysis
#   - NFS utilities for mounting S3 Files
#   - mount-s3files.sh helper script
#
# IMPORTANT: This image requires --additional-os-capabilities '["ALL"]' at
# image creation time to enable NFS filesystem mounts inside the container.
ARG BASE_IMAGE=public.ecr.aws/lambda/microvms:al2023-minimal
FROM ${BASE_IMAGE}

# System deps: Node.js (worker), Python (agent tools), NFS (S3 Files mount)
RUN dnf install -y --setopt=install_weak_deps=0 \
    bash unzip tar gzip nodejs npm \
    python3 python3-pip \
    nfs-utils \
    && dnf clean all

# Python data analysis libraries
RUN python3 -m pip install --no-cache-dir pandas numpy

# Directories
RUN mkdir -p /workspace /mnt/s3files /mnt/session/outputs

# NFS mount helper
COPY worker/mount-s3files.sh /usr/local/bin/mount-s3files
RUN chmod +x /usr/local/bin/mount-s3files

# Node worker
COPY worker/package.json /opt/worker/package.json
RUN cd /opt/worker && npm install --omit=dev

COPY worker/worker.mjs /opt/worker/worker.mjs

VOLUME /mnt/session/outputs

# Lifecycle hooks bind to 0.0.0.0:9000 — must match --hooks port at image creation.
ENTRYPOINT ["node", "/opt/worker/worker.mjs"]
