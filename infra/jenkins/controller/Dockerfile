ARG JENKINS_IMAGE
FROM ${JENKINS_IMAGE}

COPY --chmod=0644 plugins.txt /usr/share/jenkins/ref/plugins.txt
RUN jenkins-plugin-cli --plugin-file /usr/share/jenkins/ref/plugins.txt
