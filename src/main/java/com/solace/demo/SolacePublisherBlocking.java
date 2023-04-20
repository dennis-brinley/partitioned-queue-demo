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
import com.solace.messaging.config.SolaceProperties.MessageProperties;
import com.solace.messaging.config.profile.ConfigurationProfile;
import com.solace.messaging.publisher.OutboundMessage;
import com.solace.messaging.publisher.OutboundMessageBuilder;
import com.solace.messaging.publisher.PersistentMessagePublisher;
import com.solace.messaging.resources.Topic;
import com.solacesystems.jcsmp.XMLMessage;

/**
 * A sample that shows an application that blocks on publish
 * until an acknowledgement has been received from the broker.
 * It publishes messages on topics.  Receiving applications
 * should use Queues with topic subscriptions added to them.
 */
public class SolacePublisherBlocking {
    
//    private static final String PROPERTIES_FILE = "src/main/resources/publisher.properties";
    private static final String PROPERTIES_FILE = "publisher.properties";
    private static final String SAMPLE_NAME = SolacePublisherBlocking.class.getSimpleName();
    private static final String TOPIC_PREFIX = "pqdemo/";  // used as the topic "root"
    private static final String API = "Java";
    private static final int APPROX_MSG_RATE_PER_SEC = 10;
    private static final int PAYLOAD_SIZE = 256;
    
    private static volatile int msgSentCounter = 0;                   // num messages sent
    private static volatile boolean isShutdown = false;
    
    private static final int DEFAULT_NUMBER_OF_KEYS = 20;
    private static volatile int numberOfOrders = DEFAULT_NUMBER_OF_KEYS;

    private static final Logger logger = LogManager.getLogger( SAMPLE_NAME );  // log4j2, but could also use SLF4J, JCL, etc.

    /** Main method. */
    public static void main(String... args) throws IOException {

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

        final Properties properties = new Properties();
        try {
            properties.load(new FileInputStream(System.getProperty("user.dir") + "/config/" + PROPERTIES_FILE));
        } catch (FileNotFoundException fnfexc) {
            logger.warn("File not found exception reading properties file: {}", fnfexc.getMessage());
            logger.warn("attempting to read config resource from class loader");
            try {
                properties.load(SolacePublisher.class.getClassLoader().getResourceAsStream(PROPERTIES_FILE));
            } catch (NullPointerException npexc) {
                logger.error("error reading properties file: {}; {}", PROPERTIES_FILE, npexc.getMessage());
                System.exit(-1);
            }
        } catch (IOException ioexc) {
            logger.error( "IOException reading properties file: {}", ioexc.getMessage());
            System.exit(-2);
        } catch (Exception exc) {
            logger.error( "Error reading properties file: {}", exc.getMessage() );
            System.exit(-3);
        }

        final String useRandomKeyString = properties.getProperty("use.random.key", "false");
        final boolean useRandomKey = ( useRandomKeyString.toLowerCase().contentEquals("true") ? true : false );

        final String numberOfOrdersString = properties.getProperty("number.of.unique.keys", String.valueOf(DEFAULT_NUMBER_OF_KEYS));
        try {
            numberOfOrders = Integer.parseInt(numberOfOrdersString);
            if ( numberOfOrders < 1 ) {
                numberOfOrders = DEFAULT_NUMBER_OF_KEYS;
            }
        } catch ( NumberFormatException nfe ) { } // will use default

        // https://docs.solace.com/Solace-PubSub-Messaging-APIs/API-Developer-Guide/Configuring-Connection-T.htm
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
        
        // build the publisher object, starts its own thread
        final PersistentMessagePublisher publisher = messagingService.createPersistentMessagePublisherBuilder()
                .build();
        publisher.start();
        
        ScheduledExecutorService statsPrintingThread = Executors.newSingleThreadScheduledExecutor();
        statsPrintingThread.scheduleAtFixedRate(() -> {
            System.out.printf("%s %s Published msgs/s: %,d%n",API,SAMPLE_NAME,msgSentCounter);  // simple way of calculating message rates
            msgSentCounter = 0;
        }, 1, 1, TimeUnit.SECONDS);
        
        System.out.println(API + " " + SAMPLE_NAME + " connected, and running. Press [ENTER] to quit.");
        System.out.println("Publishing to topic '"+ TOPIC_PREFIX + API.toLowerCase() + 
                "/pers/pub/...', please ensure queue has matching subscription."); 
        byte[] payload = new byte[PAYLOAD_SIZE];  // preallocate memory, for reuse, for performance
        Properties messageProps = new Properties();
        messageProps.put(MessageProperties.PERSISTENT_ACK_IMMEDIATELY, "true");  // TODO Remove when v1.1 API comes out

        // loop the main thread, waiting for a quit signal

        final long baseSleepTimeBetweenPublish = 1000L / approxMsgRatePerSecond;

        while (System.in.available() == 0 && !isShutdown) {
            long publishStart = System.currentTimeMillis();
            OutboundMessageBuilder messageBuilder = messagingService.messageBuilder().fromProperties(messageProps);
            try {
                // each loop, change the payload, less trivial
                char chosenCharacter = (char)(Math.round(msgSentCounter % 26) + 65);  // rotate through letters [A-Z]
                Arrays.fill(payload,(byte)chosenCharacter);  // fill the payload completely with that char

                String locationCode = SolacePublisher.getRandomLocationCode();

                String orderNumber = SolacePublisher.getRandomOrderNumber();

                // dynamic topics!!
                String topicString = new StringBuilder(TOPIC_PREFIX).append( locationCode + "/" ).append(String.valueOf(msgSentCounter)).toString();

                Properties extendedMessageProperties = new Properties();

                String partitionKey;
                if (useRandomKey) {
                    partitionKey = UUID.randomUUID().toString();
                } else {
                    partitionKey = orderNumber;
                }

                extendedMessageProperties.put(
                            XMLMessage.MessageUserPropertyConstants.QUEUE_PARTITION_KEY, 
                            partitionKey );

                OutboundMessage message = messageBuilder.build(payload, extendedMessageProperties);    
                            
                try {
                    // send the message
                    publisher.publishAwaitAcknowledgement(message,Topic.of(topicString),2000L);  // wait up to 2 seconds?
                    msgSentCounter++;  // add one
                    logger.info("OrderId='{}' sequence='{}' location='{}' topic='{}'", orderNumber, msgSentCounter, locationCode, topicString);
                } catch (PubSubPlusClientException e) {  // could be different types
                    logger.warn(String.format("NACK for Message %s - %s", message, e));
                } catch (InterruptedException e) {
                    // got interrupted by someone while waiting for my publish confirm?
                    logger.info("Got interrupted, probably shutting down",e);
                }
            } catch (RuntimeException e) {  // threw from send(), only thing that is throwing here, but keep trying (unless shutdown?)
                logger.warn("### Caught while trying to publisher.publish()",e);
                isShutdown = true;
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
        messagingService.disconnect();
        System.out.println("Main thread quitting.");
    }
}