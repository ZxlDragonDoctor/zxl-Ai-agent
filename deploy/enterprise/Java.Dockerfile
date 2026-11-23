ARG JAVA_RUNTIME_IMAGE
FROM ${JAVA_RUNTIME_IMAGE}
WORKDIR /opt/agent
COPY app.jar /opt/agent/app.jar
COPY entrypoint.sh /opt/agent/entrypoint.sh
RUN chmod 0555 /opt/agent/entrypoint.sh && mkdir -p /data/conversations && chown -R 10001:10001 /data && chmod 0444 /opt/agent/app.jar
USER 10001:10001
ENTRYPOINT ["/bin/sh", "/opt/agent/entrypoint.sh"]
