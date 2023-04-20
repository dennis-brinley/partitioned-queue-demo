## Dockerfile for solace-consumer
## Image to configure and exec solace-consumer against ps+ broker to showcase
## KEDA managed scalability with Solace partitioned queues

FROM openjdk:11.0.16-jdk

RUN mkdir -p /opt/partitioned-queue-demo
WORKDIR /opt/partitioned-queue-demo

COPY target/partitioned-queue-0.1.0-jar-with-dependencies.jar ./partitioned-queue-demo-0.1.0.jar

CMD ["java", "-cp", "partitioned-queue-demo-0.1.0.jar", "com.solace.demo.SolaceConsumer"]

####   BUILD:
####
####     docker build -t solace-consumer:latest --file Dockerfile .
####
####     export CR_PAT=$(cat ~/.ghp_pat)
####     echo $CR_PAT | docker login ghcr.io -u USERNAME --password-stdin
