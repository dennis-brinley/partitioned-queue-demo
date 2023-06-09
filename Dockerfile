## Dockerfile for solace-consumer
## Image to configure and exec solace-consumer against ps+ broker to showcase
## KEDA managed scalability with Solace partitioned queues

FROM openjdk:11.0.16-jdk

## Including ENV definitions for use with AWS ECS deployments
ENV SOLACE_HOST=localhost
ENV SOLACE_MSGVPN_NAME=default
ENV SOLACE_MSG_USER=user1
ENV SOLACE_MSG_PASSWORD=password1
ENV SOLACE_QUEUE_NAME=queue1
ENV SUB_ACK_WINDOW_SIZE=50
ENV CONSUME_MSG_RATE=10
ENV TRANSACTED_MSG_COUNT=10

RUN mkdir -p /opt/partitioned-queue-demo
WORKDIR /opt/partitioned-queue-demo

COPY target/partitioned-queue-0.1.0-jar-with-dependencies.jar ./partitioned-queue-demo-0.1.0.jar

CMD ["java", "-cp", "partitioned-queue-demo-0.1.0.jar", "com.solace.demo.SolaceConsumer" ]

####   BUILD:
####
####     docker build -t solace-consumer:latest --file Dockerfile .
####
####     export CR_PAT=$(cat ~/.ghp_pat)
####     echo $CR_PAT | docker login ghcr.io -u USERNAME --password-stdin
