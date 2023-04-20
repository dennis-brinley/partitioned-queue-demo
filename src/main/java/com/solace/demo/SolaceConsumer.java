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

import com.solacesystems.jcsmp.BytesXMLMessage;
import com.solacesystems.jcsmp.ConsumerFlowProperties;
import com.solacesystems.jcsmp.FlowEventArgs;
import com.solacesystems.jcsmp.FlowEventHandler;
import com.solacesystems.jcsmp.FlowReceiver;
import com.solacesystems.jcsmp.JCSMPChannelProperties;
import com.solacesystems.jcsmp.JCSMPErrorResponseException;
import com.solacesystems.jcsmp.JCSMPException;
import com.solacesystems.jcsmp.JCSMPFactory;
import com.solacesystems.jcsmp.JCSMPProperties;
import com.solacesystems.jcsmp.JCSMPSession;
import com.solacesystems.jcsmp.JCSMPTransportException;
import com.solacesystems.jcsmp.OperationNotSupportedException;
import com.solacesystems.jcsmp.Queue;
import com.solacesystems.jcsmp.XMLMessageListener;

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.Properties;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Class defining Solace Consumer
 * Binds to a single queue and reads messages at a defined rate
 */
public class SolaceConsumer {
 
    private static final String PROPERTIES_FILE = "consumer.properties";
    private static final String SAMPLE_NAME = SolaceConsumer.class.getSimpleName();
    private static final String DEFAULT_QUEUE_NAME = "partitioned-queue-1";
    private static final String DEFAULT_MSG_VPN = "default";
    private static final String API = "JCSMP";
    
    private static volatile int        msgRecvCounter = 0;                 // num messages received
    private static final int           DEFAULT_MSG_CONSUME_PER_SECOND = 2;
    private static volatile boolean    hasDetectedRedelivery = false;  // detected any messages being redelivered?
    private static volatile boolean    isShutdown = false;             // are we done?
    private static FlowReceiver        flowQueueReceiver;
    private static volatile long       msgConsumePerSecond = DEFAULT_MSG_CONSUME_PER_SECOND;
    private static volatile String     queueName = DEFAULT_QUEUE_NAME;

    // remember to add log4j2.xml to your classpath
    private static final Logger logger = LogManager.getLogger( SAMPLE_NAME );  // log4j2, but could also use SLF4J, JCL, etc.

     /** This is the main app.  Use this type of app for receiving Guaranteed messages (e.g. via a queue endpoint). */
    public static void main(String... args) throws JCSMPException, InterruptedException, IOException {

        // Read generic properties file, which cannot be loaded directly into JCSMP properties lists
        final Properties properties = new Properties();
        try {
            properties.load(new FileInputStream(System.getProperty("user.dir") + "/config/" + PROPERTIES_FILE));
        } catch (FileNotFoundException fnfexc) {
            logger.warn("File not found exception reading properties file: {}", fnfexc.getMessage());
            logger.warn("attempting to read config resource from class loader");
            try {
                properties.load(SolaceConsumer.class.getClassLoader().getResourceAsStream(PROPERTIES_FILE));
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

        queueName = properties.getProperty("queue.name", DEFAULT_QUEUE_NAME);
        final String msgVpn = properties.getProperty("vpn_name", DEFAULT_MSG_VPN);
        final String sMsgConsumePerSecond = properties.getProperty("consume.msg.rate", "0");
        try {
            msgConsumePerSecond = ( long )Integer.parseInt(sMsgConsumePerSecond);
        } catch ( NumberFormatException nfe ) {
            logger.warn( "Could not parse message rate [consume.msg.rate] from properties, using default={} msgs/second", DEFAULT_MSG_CONSUME_PER_SECOND );
        } finally {
            if ( msgConsumePerSecond < 1L || msgConsumePerSecond > 1000L ) {
                msgConsumePerSecond = DEFAULT_MSG_CONSUME_PER_SECOND;
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
        flow_prop.setAckMode(JCSMPProperties.SUPPORTED_MESSAGE_ACK_AUTO);  // best practice
        flow_prop.setActiveFlowIndication(true);
        Integer winSz = 10;
        try {
            String winSzString = properties.getProperty(JCSMPProperties.SUB_ACK_WINDOW_SIZE, "10");
            winSz = Integer.parseInt(winSzString);
        } catch (NumberFormatException nfe) { }
        flow_prop.setTransportWindowSize(winSz);

        System.out.printf("Attempting to bind to queue '%s' on the broker.%n", queueName);
        try {
            // see bottom of file for QueueFlowListener class, which receives the messages from the queue
            flowQueueReceiver = session.createFlow(new QueueFlowListener(), flow_prop, null, new FlowEventHandler() {
                @Override
                public void handleEvent(Object source, FlowEventArgs event) {
                    // Flow events are usually: active, reconnecting (i.e. unbound), reconnected, active
                    logger.info("### Received a Flow event: " + event);
                    // try disabling and re-enabling the queue to see in action
                }
            });        } catch (OperationNotSupportedException e) {  // not allowed to do this
            throw e;
        } catch (JCSMPErrorResponseException e) {  // something else went wrong: queue not exist, queue shutdown, etc.
            logger.error(e);
            System.err.printf("%n*** Could not establish a connection to queue '%s': %s%n", queueName, e.getMessage());
            System.err.println("Exiting.");
            return;
        }

        flowQueueReceiver.start();
         // async queue receive working now, so time to wait until done...
        System.out.println(SAMPLE_NAME + " connected, and running. Press [ENTER] to quit.");
        logger.info( "Ready to read messages from broker msgvpn='{}' queueName='{}'", msgVpn, queueName );
         
        while (System.in.available() == 0 && !isShutdown) {
            Thread.sleep(1000);  // wait 1 second
            System.out.printf("%s %s Received msgs/s: %,d%n",API,SAMPLE_NAME,msgRecvCounter);  // simple way of calculating message rates
            msgRecvCounter = 0;
            if (hasDetectedRedelivery) {  // try shutting -> enabling the queue on the broker to see this
                System.out.println("*** Redelivery detected ***");
                hasDetectedRedelivery = false;  // only show the error once per second
            }
        }
        isShutdown = true;
        flowQueueReceiver.stop();
        Thread.sleep(1000);
        session.closeSession();  // will also close consumer object
        System.out.println("Main thread quitting.");
    }

    /** Very simple static inner class, used for receives messages from Queue Flows. **/
    private static class QueueFlowListener implements XMLMessageListener {

        @Override
        public void onReceive(BytesXMLMessage msg) {
            msgRecvCounter++;
            if (msg.getRedelivered()) {  // useful check
                // this is the broker telling the consumer that this message has been sent and not ACKed before.
                // this can happen if an exception is thrown, or the broker restarts, or the netowrk disconnects
                // perhaps an error in processing? Should do extra checks to avoid duplicate processing
                hasDetectedRedelivery = true;
            }
            // Messages are removed from the broker queue when the ACK is received.
            // Therefore, DO NOT ACK until all processing/storing of this message is complete.
            // NOTE that messages can be acknowledged from a different thread.
            msg.ackMessage();  // ACKs are asynchronous
            try {
                Thread.sleep( 1000L / msgConsumePerSecond );
            } catch ( InterruptedException iexc ) {
                isShutdown = true;
            }
        }

        @Override
        public void onException(JCSMPException e) {
            logger.warn("### Queue " + queueName + " Flow handler received exception.  Stopping!!", e);
            if (e instanceof JCSMPTransportException) {  // all reconnect attempts failed
                isShutdown = true;  // let's quit; or, could initiate a new connection attempt
            } else {
                // Generally unrecoverable exception, probably need to recreate and restart the flow
                flowQueueReceiver.close();
                // add logic in main thread to restart FlowReceiver, or can exit the program
            }
        }
    }
}