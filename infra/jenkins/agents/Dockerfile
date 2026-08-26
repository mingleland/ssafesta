ARG JENKINS_INBOUND_AGENT_IMAGE
ARG DOCKER_CLI_IMAGE

FROM ${DOCKER_CLI_IMAGE} AS docker-cli
FROM ${JENKINS_INBOUND_AGENT_IMAGE}

USER root
COPY --from=docker-cli /usr/local/bin/docker /usr/local/bin/docker
COPY --from=docker-cli /usr/local/libexec/docker/cli-plugins/docker-compose /usr/local/libexec/docker/cli-plugins/docker-compose
USER jenkins
