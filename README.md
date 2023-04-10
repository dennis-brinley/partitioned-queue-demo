# partitioned-queue-demo
Code to demonstrate scalability using KEDA and Solace partitioned queues

## Jar File
Jar file with dependencies can be called with different class paths.

**Use Java API (referencing JCSMP transparently)**
- Configure using ```resources/publisher.properties```
- GuaranteedBlockingPublisher
- GuaranteedNonBlockingPublisher

**Use JCSMP API directly**
- Configure using ```resources/consumer.properties```
- GuaranteedSubscriber - loops in main thread, calling receive() method
- GuaranteedSubscriberEH - Creates queue flow receiver flow and calls start() method; read in event handler

### Build
```mvn clean package```

## Run Publisher

Run the publisher at a rate of 45 messages per second:
```bash
java -cp target/partitioned-queue-0.1.0-standalone.jar com.solace.demo.GuaranteedNonBlockingPublisher 45
```

## Kubernetes CRDs

### Solace Consumer

Create the solace consumer deployment using ```crd/solace-consumer.yaml```. This will create a simple deployment, initially with 1 pod. The image must be visible to the Kubernetes cluster.

#### Build the solace consumer image

Use the supplied docker file. If using **minikube**, first set up the minikube environment so that the created image is available to the kubernetes instance:

```bash
eval $(minikube -p minikube docker-env)
```

Then build the consumer on the command line:
```
docker build -t guaranteed-subscriber:latest --file Dockerfile .
```

#### Create the consumer deployment

```bash
kubectl apply -f crd/solace-consumer.yaml
```

### KEDA Scaler

```crd/pq-scaler.yaml``` will create a consumer scaler using KEDA API in Kubernetes. KEDA must be installed to the cluster. The scaler will create and destroy pods as defined by the metrics in the scaler configuration.

```bash
kubectl apply -f crd/pq-scaler.yaml
```

## Burn it down

```bash
kubectl delete -f crd/pq-scaler.yaml

kubectl delete -f crd/solace-consumer.yaml
```
