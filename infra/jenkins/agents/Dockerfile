ARG JENKINS_INBOUND_AGENT_IMAGE
ARG DOCKER_CLI_IMAGE
ARG NODE_RUNTIME_IMAGE

FROM ${DOCKER_CLI_IMAGE} AS docker-cli
FROM ${NODE_RUNTIME_IMAGE} AS node-runtime
FROM ${JENKINS_INBOUND_AGENT_IMAGE}

ARG PYTHON_JSONSCHEMA_VERSION

USER root
RUN apt-get update \
    && apt-get install -y --no-install-recommends \
        jq \
        postgresql-client \
        python-is-python3 \
        python3-pip \
        python3-venv \
        redis-tools \
        unzip \
    && rm -rf /var/lib/apt/lists/*
COPY --from=docker-cli /usr/local/bin/docker /usr/local/bin/docker
COPY --from=docker-cli /usr/local/libexec/docker/cli-plugins/docker-compose /usr/local/libexec/docker/cli-plugins/docker-compose
COPY --from=node-runtime /usr/local/bin/node /usr/local/bin/node
COPY --from=node-runtime /usr/local/lib/node_modules/npm /usr/local/lib/node_modules/npm
RUN python -m venv /opt/jenkins-python \
    && /opt/jenkins-python/bin/pip install --no-cache-dir "jsonschema==${PYTHON_JSONSCHEMA_VERSION}" \
    && ln -s ../lib/node_modules/npm/bin/npm-cli.js /usr/local/bin/npm \
    && ln -s ../lib/node_modules/npm/bin/npx-cli.js /usr/local/bin/npx \
    && /opt/jenkins-python/bin/python -c 'import sys; assert sys.version_info >= (3, 12)' \
    && /opt/jenkins-python/bin/python -c 'import jsonschema; print(jsonschema.__version__)' \
    && python -m pip --version \
    && node -e 'const major=Number(process.versions.node.split(".")[0]); if (major < 24) process.exit(1)' \
    && npm --version \
    && javac -version \
    && jq --version \
    && psql --version \
    && redis-cli --version \
    && unzip -v >/dev/null \
    && docker compose version
ENV PATH="/opt/jenkins-python/bin:${PATH}"
USER jenkins
