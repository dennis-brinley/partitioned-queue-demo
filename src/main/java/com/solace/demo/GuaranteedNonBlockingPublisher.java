/*
 * Copyright 2021-2022 Solace Corporation. All rights reserved.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.solace.demo;

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.Arrays;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.solace.messaging.MessagingService;
import com.solace.messaging.PubSubPlusClientException;
import com.solace.messaging.config.SolaceProperties;
import com.solace.messaging.config.profile.ConfigurationProfile;
import com.solace.messaging.publisher.OutboundMessage;
import com.solace.messaging.publisher.OutboundMessageBuilder;
import com.solace.messaging.publisher.PersistentMessagePublisher;
import com.solace.messaging.resources.Topic;
import com.solace.messaging.util.SolaceSDTMap;
import com.solacesystems.jcsmp.JCSMPFactory;
import com.solacesystems.jcsmp.Message;
import com.solacesystems.jcsmp.SDTException;
import com.solacesystems.jcsmp.SDTMap;
import com.solacesystems.jcsmp.TextMessage;
import com.solacesystems.jcsmp.XMLMessage;

/**
 * A more performant sample that shows non-blocking
 * Guaranteed publishing with asynchronous acknowledgments.
 * It publishes messages on topics.  Receiving applications
 * should use Queues with topic subscriptions added to them.
 */
public class GuaranteedNonBlockingPublisher {
    
//    private static final String PROPERTIES_FILE = "src/main/resources/publisher.properties";
    private static final String PROPERTIES_FILE = "publisher.properties";
    private static final String SIMPLE_NAME = GuaranteedNonBlockingPublisher.class.getSimpleName();
    private static final String TOPIC_PREFIX = "pqdemo/";  // used as the topic "root"
    private static final String API = "Java";
    private static final int APPROX_MSG_RATE_PER_SEC = 10;
    private static final int PAYLOAD_SIZE = 256;
    
    private static volatile int msgSentCounter = 0;                   // num messages sent
    private static volatile boolean isShutdown = false;
    
    private static final Logger logger = LogManager.getLogger( SIMPLE_NAME );  // log4j2, but could also use SLF4J, JCL, etc.

    /** Main method. */
    public static void main(String... args) throws IOException, InterruptedException {

        // Look for arg[0] and interpret as numeric msg/sec rate of publication
        int approxMsgRatePerSecond = APPROX_MSG_RATE_PER_SEC;
        if (args.length > 0) {
            Integer i = 0;
            try {
                i = Integer.valueOf(args[0]);
                if ( i < 0 || i > 1000 ) {
                    logger.warn( "The input argument (published msgs/second) was out of bounds; using default" );
                    i = 0;
                }
            } catch ( NumberFormatException nfe ) {
                logger.warn(nfe.getMessage());
                logger.warn( "Could not convert input argument to an integer value, using default" );
            } finally {
                if ( i != 0 ) {
                    approxMsgRatePerSecond = i;
                }
            }
        }

        // get connection properties from file
        final Properties properties = new Properties();
        try {
//            properties.load(new FileInputStream(PROPERTIES_FILE));
            properties.load(GuaranteedSubscriber.class.getClassLoader().getResourceAsStream(PROPERTIES_FILE));
        } catch (FileNotFoundException fnfexc) {
            logger.warn("File not found exception reading properties file: {}", fnfexc.getMessage());
        } catch (IOException ioexc) {
            logger.warn( "IOException reading properties file: {}", ioexc.getMessage());
        }

        // ready to connect now
        final MessagingService messagingService = MessagingService.builder(ConfigurationProfile.V1)
                .fromProperties(properties)
                .build();
        messagingService.connect();  // blocking connect
        messagingService.addServiceInterruptionListener(serviceEvent -> {
            logger.warn("### SERVICE INTERRUPTION: "+serviceEvent.getCause());
            //isShutdown = true;
        });
        messagingService.addReconnectionAttemptListener(serviceEvent -> {
            logger.info("### RECONNECTING ATTEMPT: "+serviceEvent);
        });
        messagingService.addReconnectionListener(serviceEvent -> {
            logger.info("### RECONNECTED: "+serviceEvent);
        });
        
        // build the publisher object
        final PersistentMessagePublisher publisher = messagingService.createPersistentMessagePublisherBuilder()
                .onBackPressureWait(1)
                .build();
        publisher.start();

        // publisher receipt callback, can be called for ACL violations, spool over quota, nobody subscribed to a topic, etc.
        publisher.setMessagePublishReceiptListener(publishReceipt -> {
            final PubSubPlusClientException e = publishReceipt.getException();
            if (e == null) {  // no exception, ACK, broker has confirmed receipt
                OutboundMessage outboundMessage = publishReceipt.getMessage();
                logger.debug(String.format("ACK for Message %s", outboundMessage));  // good enough, the broker has it now
            } else {// not good, a NACK
                Object userContext = publishReceipt.getUserContext();  // optionally set at publish()
                if (userContext != null) {
                    logger.warn(String.format("NACK for Message %s - %s", userContext, e));
                } else {
                    OutboundMessage outboundMessage = publishReceipt.getMessage();  // which message got NACKed?
                    logger.warn(String.format("NACK for Message %s - %s", outboundMessage, e));
                }
            }
        });
        
        ScheduledExecutorService statsPrintingThread = Executors.newSingleThreadScheduledExecutor();
        statsPrintingThread.scheduleAtFixedRate(() -> {
            System.out.printf("%s %s Published msgs/s: %,d%n",API,SIMPLE_NAME,msgSentCounter);  // simple way of calculating message rates
            msgSentCounter = 0;
        }, 1, 1, TimeUnit.SECONDS);

        System.out.println(API + " " + SIMPLE_NAME + " connected, and running. Press [ENTER] to quit.");
        System.out.println("Publishing to topic '"+ TOPIC_PREFIX + API.toLowerCase() + 
                "/pers/pub/...', please ensure queue has matching subscription."); 
        byte[] payload = new byte[PAYLOAD_SIZE];  // preallocate memory, for reuse, for performance

        // loop the main thread, waiting for a quit signal

        final long baseSleepTimeBetweenPublish = 1000L / approxMsgRatePerSecond;

        while (System.in.available() == 0 && !isShutdown) {
            long publishStart = System.currentTimeMillis();
            OutboundMessageBuilder messageBuilder = messagingService.messageBuilder();
            try {
                // each loop, change the payload, less trivial
                char chosenCharacter = (char)(Math.round(msgSentCounter % 26) + 65);  // rotate through letters [A-Z]
                Arrays.fill(payload,(byte)chosenCharacter);  // fill the payload completely with that char

                // dynamic topics!!
                String locationCode = getRandomLocationCode();

                String orderNumber = getRandomOrderNumber();

                String topicString = new StringBuilder(TOPIC_PREFIX).append( locationCode + "/" ).append(String.valueOf(msgSentCounter)).toString();
                
                Properties extendedMessageProperties = new Properties();
                // extendedMessageProperties.put(
                //             XMLMessage.MessageUserPropertyConstants.QUEUE_PARTITION_KEY, 
                //             String.format( "%05d", orderNumber ) );                     // Create with partition key: orderNumber 1 - 20

                extendedMessageProperties.put(
                            XMLMessage.MessageUserPropertyConstants.QUEUE_PARTITION_KEY, 
//                            UUID.randomUUID().toString() );                         // Create with random partition key
                            orderNumber );                         // Create with random partition key

                OutboundMessage message = messageBuilder.build(payload, extendedMessageProperties);    
                publisher.publish(message,Topic.of(topicString));  // send the message
                msgSentCounter++;  // add one

                logger.info("OrderId='{}' sequence='{}' location='{}' topic='{}'", orderNumber, msgSentCounter, locationCode, topicString);
            } catch (RuntimeException e) {  // threw from publish(), only thing that is throwing here, but keep trying (unless shutdown?)
                logger.warn("### Caught while trying to publisher.publish()",e);
                isShutdown = true;  // just example, maybe look to see if recoverable
            } finally {
                try {
                    long sleepTime = baseSleepTimeBetweenPublish - (System.currentTimeMillis() - publishStart); // subtract out processing time
                    Thread.sleep( sleepTime > 0L ? sleepTime : 0L );  // do Thread.sleep(0) for max speed
                    // Note: STANDARD Edition Solace PubSub+ broker is limited to 10k msg/s max ingress
                } catch (InterruptedException e) {
                    isShutdown = true;
                }
            }
        }
        isShutdown = true;
        statsPrintingThread.shutdown();  // stop printing stats
        publisher.terminate(1500);
        Thread.sleep(1500);  // give time for the ACKs to arrive from the broker
        messagingService.disconnect();
        System.out.println("Main thread quitting.");
    }

    public static String getRandomLocationCode() {
        Integer locationId = ( int )Math.floor( Math.random() * 4 );
        switch (locationId) {
            case 0:
                return "NA";
            case 1:
                return "UK";
            case 2:
                return "EU";
            case 3:
                return "APAC";
            default:
                return "NA";
        }
    }

    public static String getRandomOrderNumber() {
        Integer orderNumber = ( ( int )Math.floor( Math.random() * 20 ) ) + 1;
        return String.format( "%05d", orderNumber );
    }
}