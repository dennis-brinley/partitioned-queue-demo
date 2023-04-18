# partitioned-queue-demo
Code to demonstrate scalability using KEDA and Solace partitioned queues

## Key Concepts: How KEDA Works
**KEDA = Kubernetes Event-Driven Autoscaler**

KEDA is a Kubernetes operator that can be used to monitor external metrics and scale applications by creating and destroying pods based on the current value of those metrics. For Solace PubSub+, KEDA can monitor metrics of a queue and then apply scaling decisions based upon the current values. The diagram below depicts KEDA monitoring a Solace PubSub+ broker for the current queue depth (undelivered message count). Based upon the current backlog, KEDA scales a consumer deployment.

![KEDA with Solace PubSub Broker](images/KEDA-messageCountTarget.jpg)

Scaling itself is actually done by the Kubernetes Horizontal Pod Autoscaler (HPA). KEDA provides a mechanism whereby resource specific metrics can be utilized for scaling decisions instead of the inherent CPU and Memory utilization that is available to HPA by default.

> **Note:** There should never by an HPA scaler defined for a deployment if using KEDA. KEDA configures HPA to use the identified metrics. Using both mechanisms concurrently will produce unpredictable results.

### Metrics and Scalers
KEDA provides the ability to monitor metrics from external systems and make run-time scaling decisions based upon those metrics. The ability to monitor specific kinds of resources (Solace PubSub+, Rabbit MQ, Amazon SQS, etc.) is provided by KEDA **scalers** contributed by the open-source community. [KEDA Scalers](https://keda.sh/docs/2.10/scalers/) Whatever metrics are sensible for a particular scaler can be defined by the community, as well as the mechanism for identifying the endpoint and providing the necessary credentials.

HPA scales Kubernetes workloads according to the following computation:
> desiredReplicas = ceil[currentReplicas * ( currentMetricValue / desiredMetricValue )]

Where `currentMetricValue` is the average value observerd across all instances. As the `currentMetricValue` fluctuates, HPA computes the number of desiredReplicas and scales the deployment accordingly.

### Solace Scaler
[Solace PubSub+ Event Broker](https://keda.sh/docs/2.10/scalers/solace-pub-sub/)

The Solace scaler defines the interface between KEDA and PubSub+ brokers. The interface connects to the SEMPv2 API and polls for metrics of a desired queue. 

**The queue metrics available for scaling are:**
- messageCountTarget - scale based on the number of underlivered messages in the queue. `collections.msgs.count`
- messageReceiveRateTarget - scale based on the one minute average rate of message delivery to the queue: `data.averageRxMsgRate`
- messageSpoolUsageTarget - scale based on the spool utilization attributed to the queue: `data.msgSpoolUsage`

> &#128161; **Target** metric is the desired average value to maintain for each replica/pod.

> &#128161; Multiple metric values can be specified for a particular deployment scaler. At each observation, the metric that computes the highest number of desired pods "wins"

> &#128161; **messageCountTarget** provides good reactivity to increasing workload. &#128293; By itself, this metric can introduce ***flapping***: the continuous creation and destruction of replicas, which can cause inconsistent performance. Achieving a steady state of consumers under continuous load is difficult to achieve using this metric alone.

> &#128161; **messageReceiveRateTarget** - useful to achieve good application performance based on the rate of ingress. Using this metric is advised to minimize **flapping** and to maintain performant applications. &#128293; By itself, this metric will not help scale up an application based on backlog. If ingress is very low, but a large backlog exists, the application will not scale up using this metric by itself.

> &#128161;&#128161; **Important** For best results, both **messageCountTarget** and **messageReceiveRateTarget** should be specified to configure a Solace Scaler.

## Jar File
Jar file with dependencies can be called with different class paths.

**Publisher App**
- Uses Java API (referencing JCSMP opaquely)
- Configure using ```resources/publisher.properties```
- Class: GuaranteedBlockingPublisher
- Class: GuaranteedNonBlockingPublisher

**Consumer App**
- Called in Deployment container
- Uses JCSMP API directly
- Configure using ```resources/consumer.properties```
- GuaranteedSubscriber - loops in main thread, calling receive() method
- GuaranteedSubscriberEH (Default) - Creates queue flow receiver flow and calls start() method; read in event handler

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

```crd/secret-auth.yaml``` will create secret + triggerauthorization (KEDA object type) used by the scaled object.

```crd/pq-scaler.yaml``` will create a consumer scaler using KEDA API in Kubernetes. KEDA must be installed to the cluster. The scaler will create and destroy pods as defined by the metrics in the scaler configuration.

```bash
kubectl apply -f crd/pq-scaler.yaml
```

> &#128161; **Note:** The KEDA ScaledObject and TriggerAuthentication CRD definitions must be created in the same Kubernetes namespace as the scaled deployment.

## Burn it down

```bash
kubectl delete -f crd/pq-scaler.yaml

kubectl delete -f crd/solace-consumer.yaml
```
