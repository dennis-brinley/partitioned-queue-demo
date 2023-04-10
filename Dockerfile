## Dockerfile for guaranteed-subscriber
## Image to configure and exec guaranteed-subscriber against ps+ broker to showcase
## KEDA managed scalability with Solace partitioned queues

FROM openjdk:11.0.16-jdk

RUN mkdir -p /opt/partitioned-queue-demo
WORKDIR /opt/partitioned-queue-demo

COPY target/partitioned-queue-0.1.0-jar-with-dependencies.jar ./

CMD ["java", "-cp", "partitioned-queue-0.1.0-jar-with-dependencies.jar", "com.solace.demo.GuaranteedSubscriberEH"]

####   BUILD:
####
####     docker build -t guaranteed-subscriber:latest --file Dockerfile .
####
####     export CR_PAT=$(cat ~/.ghp_pat)
####     echo $CR_PAT | docker login ghcr.io -u USERNAME --password-stdin
