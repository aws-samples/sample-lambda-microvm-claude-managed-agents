# MicroVM image for the Claude self-hosted sandbox worker.
#
# At IMAGE BUILD TIME the worker starts and is snapshotted running.
# At RUN TIME the /run lifecycle hook delivers the session dispatch payload;
# the worker acknowledges, handles the session, then exits.
#
# The image source (app.zip) is this Dockerfile plus the worker's runtime jars
# under worker/lib/, packaged by `./gradlew :worker:imageZip`.
ARG BASE_IMAGE=public.ecr.aws/lambda/microvms:al2023-minimal
FROM ${BASE_IMAGE}

# Worker runtime: a headless JRE, plus the tools the agent toolset shells out to
# (bash for the shell tool, unzip/tar/gzip for skill archives).
RUN dnf install -y --setopt=install_weak_deps=0 bash unzip tar gzip java-21-amazon-corretto-headless && \
    (command -v curl >/dev/null 2>&1 || dnf install -y curl) && \
    java -version && \
    dnf clean all

WORKDIR /workspace

# Install the worker.
COPY worker/lib/ /opt/worker/lib/

VOLUME /mnt/session/outputs

# Start the worker at build time so it is captured in the snapshot.
ENTRYPOINT ["java", "-XX:+ExitOnOutOfMemoryError", "-cp", "/opt/worker/lib/*", "com.example.claudemicrovm.worker.MainKt"]
