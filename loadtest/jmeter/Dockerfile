# Apache JMeter for the throughput test, downloaded from the official Apache archive and checked
# against the published SHA-512 (no third-party JMeter image).
FROM eclipse-temurin:21-jre

ARG JMETER_VERSION=5.6.3
RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/* \
 && cd /tmp \
 && curl -fsSLO https://archive.apache.org/dist/jmeter/binaries/apache-jmeter-${JMETER_VERSION}.tgz \
 && curl -fsSLO https://archive.apache.org/dist/jmeter/binaries/apache-jmeter-${JMETER_VERSION}.tgz.sha512 \
 && echo "$(cut -d' ' -f1 apache-jmeter-${JMETER_VERSION}.tgz.sha512)  apache-jmeter-${JMETER_VERSION}.tgz" | sha512sum -c - \
 && tar -xzf apache-jmeter-${JMETER_VERSION}.tgz -C /opt \
 && rm apache-jmeter-${JMETER_VERSION}.tgz*

ENV PATH=/opt/apache-jmeter-${JMETER_VERSION}/bin:$PATH
WORKDIR /jmeter
# JMeter will not create the report folder's parent, so make sure out/jmeter exists first.
ENTRYPOINT ["sh", "-c", "mkdir -p out/jmeter && exec jmeter \"$@\"", "jmeter"]
