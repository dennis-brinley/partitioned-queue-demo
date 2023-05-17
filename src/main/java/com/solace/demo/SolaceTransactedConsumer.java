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

import com.solacesystems.jcsmp.ConsumerFlowProperties;
import com.solacesystems.jcsmp.EndpointProperties;
import com.solacesystems.jcsmp.FlowReceiver;
import com.solacesystems.jcsmp.JCSMPChannelProperties;
import com.solacesystems.jcsmp.JCSMPErrorResponseException;
import com.solacesystems.jcsmp.JCSMPException;
import com.solacesystems.jcsmp.JCSMPFactory;
import com.solacesystems.jcsmp.JCSMPProperties;
import com.solacesystems.jcsmp.JCSMPSession;
import com.solacesystems.jcsmp.OperationNotSupportedException;
import com.solacesystems.jcsmp.Queue;
import com.solacesystems.jcsmp.transaction.TransactedSession;

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.Properties;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
 
public class SolaceTransactedConsumer {
 
    private static final String PROPERTIES_FILE = "consumer.properties";
    private static final String SAMPLE_NAME = SolaceTransactedConsumer.class.getSimpleName();
    private static final String DEFAULT_QUEUE_NAME = "partitioned-queue-1";
    private static final String DEFAULT_MSG_VPN = "default";
    private static final String API = "JCSMP";
    
    private static final int    DEFAULT_MSG_CONSUME_PER_SECOND = 10;
    private static final int    DEFAULT_TRANSACTED_MSG_COUNT = 8;

    private static volatile int        msgRecvCounter = 0;                 // num messages received
    private static volatile boolean    hasDetectedRedelivery = false;  // detected any messages being redelivered?
    private static volatile boolean    isShutdown = false;             // are we done?
    private static FlowReceiver        flowQueueReceiver;

    // remember to add log4j2.xml to your classpath
    private static final Logger logger = LogManager.getLogger( SAMPLE_NAME );  // log4j2, but could also use SLF4J, JCL, etc.

     /** This is the main app.  Use this type of app for receiving Guaranteed messages (e.g. via a queue endpoint). */
    public static void main(String... args) throws JCSMPException, InterruptedException, IOException {

        // Read generic properties file, which cannot be loaded directly into JCSMP properties lists
        final Properties properties = new Properties();
        boolean configFromEnv = false;
        String configFile = System.getProperty("user.dir") + "/config/" + PROPERTIES_FILE;
        if ( args.length > 0 ) {
            for ( String a : args ) {
                if ( a.contentEquals( SolaceConsumer.ARG_CONFIG_FROM_ENV ) ) {
                    configFromEnv = true;
                    SolaceConsumer.getConsumerPropertiesFromEnv(properties);
                    break;
                } else if ( a.startsWith(SolaceConsumer.ARG_PROPERTIES_FILE ) && a.length() > SolaceConsumer.ARG_PROPERTIES_FILE.length() ) {
                    configFile = a.substring(SolaceConsumer.ARG_PROPERTIES_FILE.length() +1);
                    break;
                }
            }
        }

        if ( !configFromEnv ) {
        // Read generic properties file, which cannot be loaded directly into JCSMP properties lists
            try {
                logger.info("Loading Consumer Configuration from: {}", configFile);
                properties.load(new FileInputStream(configFile));
            } catch (FileNotFoundException fnfexc) {
                logger.warn("File not found exception reading properties file: {}", fnfexc.getMessage());
                logger.warn("attempting to read config resource from class loader");
                try {
                    properties.load(SolaceTransactedConsumer.class.getClassLoader().getResourceAsStream(PROPERTIES_FILE));
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
        }

        final String queueName = properties.getProperty("queue.name", DEFAULT_QUEUE_NAME);
        final String msgVpn = properties.getProperty("vpn_name", DEFAULT_MSG_VPN);
        final String sMsgConsumePerSecond = properties.getProperty("consume.msg.rate", "0");
        final String sTransactedMsgCount = properties.getProperty("transacted.msg.count", "0");
        long msgConsumePerSecond = 0;
        int  transactedMsgCount = 0;
        try {
            msgConsumePerSecond = ( long )Integer.parseInt(sMsgConsumePerSecond);
            transactedMsgCount = Integer.parseInt(sTransactedMsgCount);
        } catch ( NumberFormatException nfe ) {
            logger.warn( "Could not parse message rate [consume.msg.rate] from properties, using default={} msgs/second", DEFAULT_MSG_CONSUME_PER_SECOND );
            logger.warn( "Could not parse message rate [transacted.msg.count] from properties, using default={} msgs/second", DEFAULT_TRANSACTED_MSG_COUNT);
        } finally {
            if ( msgConsumePerSecond < 1L || msgConsumePerSecond > 1000L ) {
                msgConsumePerSecond = DEFAULT_MSG_CONSUME_PER_SECOND;
            }
            if ( transactedMsgCount < 1 || transactedMsgCount > 256 ) {
                transactedMsgCount = DEFAULT_TRANSACTED_MSG_COUNT;
            }
        }

        // Set up JCSMP properties
        final JCSMPProperties jcsmpProperties = new JCSMPProperties();
        for ( String s : properties.stringPropertyNames() ) {
            jcsmpProperties.setProperty(s, properties.getProperty(s));
        }
        // AND JCSMPChannelProperties
        JCSMPChannelProperties channelProps = new JCSMPChannelProperties();
        channelProps.setReconnectRetries(20);      // recommended settings
        channelProps.setConnectRetriesPerHost(5);  // recommended settings

        // https://docs.solace.com/Solace-PubSub-Messaging-APIs/API-Developer-Guide/Configuring-Connection-T.htm
        jcsmpProperties.setProperty(JCSMPProperties.CLIENT_CHANNEL_PROPERTIES, channelProps);

        final JCSMPSession session;
        session = JCSMPFactory.onlyInstance().createSession(jcsmpProperties);
        session.connect();

        // configure the queue API object locally
        final Queue queue = JCSMPFactory.onlyInstance().createQueue(queueName);
        // Create a Flow be able to bind to and consume messages from the Queue.
        final ConsumerFlowProperties flow_prop = new ConsumerFlowProperties();
        flow_prop.setEndpoint(queue);
//        flow_prop.setAckMode(JCSMPProperties.SUPPORTED_MESSAGE_ACK_AUTO);  // best practice
        flow_prop.setStartState(true);

        EndpointProperties endpointProperties = new EndpointProperties();
        endpointProperties.setAccessType(EndpointProperties.ACCESSTYPE_NONEXCLUSIVE);

        // flow_prop.setTransportWindowSize(10);
        Integer winSz = 100;
        try {
            String winSzString = properties.getProperty(JCSMPProperties.SUB_ACK_WINDOW_SIZE, "100");
            winSz = Integer.parseInt(winSzString);
        } catch (NumberFormatException nfe) { }
        flow_prop.setTransportWindowSize(winSz);

        final TransactedSession txSession = session.createTransactedSession();

        System.out.printf("Attempting to bind to queue '%s' on the broker.%n", queueName);
        try {
            // A simple consumer called on the main thread to facilitate message throttling
            flowQueueReceiver = txSession.createFlow(null, flow_prop, endpointProperties);
        } catch (OperationNotSupportedException e) {  // not allowed to do this
            throw e;
        } catch (JCSMPErrorResponseException e) {  // something else went wrong: queue not exist, queue shutdown, etc.
            logger.error(e);
            System.err.printf("%n*** Could not establish a connection to queue '%s': %s%n", queueName, e.getMessage());
            System.err.println("Exiting.");
            return;
        }

         // async queue receive working now, so time to wait until done...
        System.out.println(SAMPLE_NAME + " connected, and running. Press [ENTER] to quit.");
        logger.info( "Ready to read messages from broker msgvpn='{}' queueName='{}'", msgVpn, queueName );
         
        long outputTimeMark = System.currentTimeMillis();
        final long baseSleepTimeBetweenReceive = 1000L / msgConsumePerSecond;
        int txMsgCount = 0;

        while (System.in.available() == 0 && !isShutdown) {
            long receiveStart = System.currentTimeMillis();
            flowQueueReceiver.receive( 200 );     // 200ms time-out
            msgRecvCounter++;
            if ( ++txMsgCount > transactedMsgCount ) {
                txSession.commit();
                txMsgCount = 0;
            }
            long sleepTime = baseSleepTimeBetweenReceive - (System.currentTimeMillis() - receiveStart); // subtract out processing time
            Thread.sleep( sleepTime > 0L ? sleepTime : 0L );
            if ( System.currentTimeMillis() > ( outputTimeMark + 1000L ) ) {
                outputTimeMark = System.currentTimeMillis();
                logger.debug("{} {} Received msgs/s: {}", API, SAMPLE_NAME, msgRecvCounter );
                msgRecvCounter = 0;
                if (hasDetectedRedelivery) {  // try shutting -> enabling the queue on the broker to see this
                    System.out.println("*** Redelivery detected ***");
                    hasDetectedRedelivery = false;  // only show the error once per second
                }
            } 
        }
        isShutdown = true;
        flowQueueReceiver.stop();
        Thread.sleep(1000);
        session.closeSession();  // will also close consumer object
        System.out.println("Main thread quitting.");
    }
}