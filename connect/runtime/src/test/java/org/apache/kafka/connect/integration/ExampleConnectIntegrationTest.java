/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.kafka.connect.integration;

import org.apache.kafka.connect.runtime.rest.entities.ConnectorStateInfo;
import org.apache.kafka.connect.storage.StringConverter;
import org.apache.kafka.connect.util.clusters.EmbeddedConnectCluster;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

import static org.apache.kafka.connect.runtime.ConnectorConfig.CONNECTOR_CLASS_CONFIG;
import static org.apache.kafka.connect.runtime.ConnectorConfig.KEY_CONVERTER_CLASS_CONFIG;
import static org.apache.kafka.connect.runtime.ConnectorConfig.TASKS_MAX_CONFIG;
import static org.apache.kafka.connect.runtime.ConnectorConfig.VALUE_CONVERTER_CLASS_CONFIG;
import static org.apache.kafka.connect.runtime.SinkConnectorConfig.TOPICS_CONFIG;
import static org.apache.kafka.connect.runtime.TopicCreationConfig.DEFAULT_TOPIC_CREATION_PREFIX;
import static org.apache.kafka.connect.runtime.TopicCreationConfig.PARTITIONS_CONFIG;
import static org.apache.kafka.connect.runtime.TopicCreationConfig.REPLICATION_FACTOR_CONFIG;
import static org.apache.kafka.connect.runtime.WorkerConfig.OFFSET_COMMIT_INTERVAL_MS_CONFIG;
import static org.apache.kafka.test.TestUtils.waitForCondition;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An example integration test that demonstrates how to setup an integration test for Connect.
 * <p></p>
 * The following test configures and executes up a sink connector pipeline in a worker, produces messages into
 * the source topic-partitions, and demonstrates how to check the overall behavior of the pipeline.
 */
@Tag("integration")
public class ExampleConnectIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(ExampleConnectIntegrationTest.class);

    private static final int NUM_RECORDS_PRODUCED = 2000;
    private static final int NUM_TOPIC_PARTITIONS = 3;
    private static final long RECORD_TRANSFER_DURATION_MS = TimeUnit.SECONDS.toMillis(30);
    private static final long CONNECTOR_SETUP_DURATION_MS = TimeUnit.SECONDS.toMillis(60);
    private static final int NUM_TASKS = 3;
    private static final int NUM_WORKERS = 3;
    private static final String CONNECTOR_NAME = "simple-conn";
    private static final String SINK_CONNECTOR_CLASS_NAME = TestableSinkConnector.class.getSimpleName();
    private static final String SOURCE_CONNECTOR_CLASS_NAME = TestableSourceConnector.class.getSimpleName();

    private EmbeddedConnectCluster connect;
    private ConnectorHandle connectorHandle;

    @BeforeEach
    public void setup() {
        // setup Connect worker properties
        Map<String, String> exampleWorkerProps = new HashMap<>();
        exampleWorkerProps.put(OFFSET_COMMIT_INTERVAL_MS_CONFIG, String.valueOf(5_000));

        // setup Kafka broker properties
        Properties exampleBrokerProps = new Properties();
        exampleBrokerProps.put("auto.create.topics.enable", "false");

        // build a Connect cluster backed by a Kafka KRaft cluster
        connect = new EmbeddedConnectCluster.Builder()
                .name("connect-cluster")
                .numWorkers(NUM_WORKERS)
                .numBrokers(1)
                .workerProps(exampleWorkerProps)
                .brokerProps(exampleBrokerProps)
                .build();

        // start the clusters
        connect.start();

        // get a handle to the connector
        connectorHandle = RuntimeHandles.get().connectorHandle(CONNECTOR_NAME);
    }

    @AfterEach
    public void close() {
        // delete connector handle
        RuntimeHandles.get().deleteConnector(CONNECTOR_NAME);

        // stop the Connect cluster and its backing Kafka cluster.
        connect.stop();
    }

    /**
     * Simple test case to configure and execute an embedded Connect cluster. The test will produce and consume
     * records, and start up a sink connector which will consume these records.
     */
    @Test
    public void testSinkConnector() throws Exception {
        // create test topic
        connect.kafka().createTopic("test-topic", NUM_TOPIC_PARTITIONS);

        // setup up props for the sink connector
        Map<String, String> props = new HashMap<>();
        props.put(CONNECTOR_CLASS_CONFIG, SINK_CONNECTOR_CLASS_NAME);
        props.put(TASKS_MAX_CONFIG, String.valueOf(NUM_TASKS));
        props.put(TOPICS_CONFIG, "test-topic");
        props.put(KEY_CONVERTER_CLASS_CONFIG, StringConverter.class.getName());
        props.put(VALUE_CONVERTER_CLASS_CONFIG, StringConverter.class.getName());

        // expect all records to be consumed by the connector
        connectorHandle.expectedRecords(NUM_RECORDS_PRODUCED);

        // expect all records to be consumed by the connector
        connectorHandle.expectedCommits(NUM_RECORDS_PRODUCED);

        // validate the intended connector configuration, a config that errors
        connect.assertions().assertExactlyNumErrorsOnConnectorConfigValidation(SINK_CONNECTOR_CLASS_NAME, props, 1,
            "Validating connector configuration produced an unexpected number or errors.");

        // add missing configuration to make the config valid
        props.put("name", CONNECTOR_NAME);

        // validate the intended connector configuration, a valid config
        connect.assertions().assertExactlyNumErrorsOnConnectorConfigValidation(SINK_CONNECTOR_CLASS_NAME, props, 0,
            "Validating connector configuration produced an unexpected number or errors.");

        // start a sink connector
        connect.configureConnector(CONNECTOR_NAME, props);

        waitForCondition(this::checkForPartitionAssignment,
                CONNECTOR_SETUP_DURATION_MS,
                "Connector tasks were not assigned a partition each.");

        // produce some messages into source topic partitions
        for (int i = 0; i < NUM_RECORDS_PRODUCED; i++) {
            connect.kafka().produce("test-topic", i % NUM_TOPIC_PARTITIONS, "key", "simple-message-value-" + i);
        }

        // consume all records from the source topic or fail, to ensure that they were correctly produced.
        assertEquals(NUM_RECORDS_PRODUCED,
                connect.kafka().consume(NUM_RECORDS_PRODUCED, RECORD_TRANSFER_DURATION_MS, "test-topic").count(),
                "Unexpected number of records consumed");

        // wait for the connector tasks to consume all records.
        connectorHandle.awaitRecords(RECORD_TRANSFER_DURATION_MS);

        // wait for the connector tasks to commit all records.
        connectorHandle.awaitCommits(RECORD_TRANSFER_DURATION_MS);

        // delete connector
        connect.deleteConnector(CONNECTOR_NAME);
    }

    /**
     * Simple test case to configure and execute an embedded Connect cluster. The test will produce and consume
     * records, and start up a sink connector which will consume these records.
     */
    @Test
    public void testSourceConnector() throws Exception {
        // create test topic
        connect.kafka().createTopic("test-topic", NUM_TOPIC_PARTITIONS);

        // setup up props for the source connector
        Map<String, String> props = new HashMap<>();
        props.put(CONNECTOR_CLASS_CONFIG, SOURCE_CONNECTOR_CLASS_NAME);
        props.put(TASKS_MAX_CONFIG, String.valueOf(NUM_TASKS));
        props.put("topic", "test-topic");
        props.put("throughput", String.valueOf(500));
        props.put(KEY_CONVERTER_CLASS_CONFIG, StringConverter.class.getName());
        props.put(VALUE_CONVERTER_CLASS_CONFIG, StringConverter.class.getName());
        props.put(DEFAULT_TOPIC_CREATION_PREFIX + REPLICATION_FACTOR_CONFIG, String.valueOf(1));
        props.put(DEFAULT_TOPIC_CREATION_PREFIX + PARTITIONS_CONFIG, String.valueOf(1));

        // expect all records to be produced by the connector
        connectorHandle.expectedRecords(NUM_RECORDS_PRODUCED);

        // expect all records to be produced by the connector
        connectorHandle.expectedCommits(NUM_RECORDS_PRODUCED);

        // validate the intended connector configuration, a config that errors
        connect.assertions().assertExactlyNumErrorsOnConnectorConfigValidation(SOURCE_CONNECTOR_CLASS_NAME, props, 1,
            "Validating connector configuration produced an unexpected number or errors.");

        // add missing configuration to make the config valid
        props.put("name", CONNECTOR_NAME);

        // validate the intended connector configuration, a valid config
        connect.assertions().assertExactlyNumErrorsOnConnectorConfigValidation(SOURCE_CONNECTOR_CLASS_NAME, props, 0,
            "Validating connector configuration produced an unexpected number or errors.");

        // start a source connector
        connect.configureConnector(CONNECTOR_NAME, props);

        // wait for the connector tasks to produce enough records
        connectorHandle.awaitRecords(RECORD_TRANSFER_DURATION_MS);

        // wait for the connector tasks to commit enough records
        connectorHandle.awaitCommits(RECORD_TRANSFER_DURATION_MS);

        // consume all records from the source topic or fail, to ensure that they were correctly produced
        int recordNum = connect.kafka().consume(NUM_RECORDS_PRODUCED, RECORD_TRANSFER_DURATION_MS, "test-topic").count();
        assertTrue(recordNum >= NUM_RECORDS_PRODUCED,
                "Not enough records produced by source connector. Expected at least: " + NUM_RECORDS_PRODUCED + " + but got " + recordNum);

        // delete connector
        connect.deleteConnector(CONNECTOR_NAME);
    }

    /**
     * Check if a partition was assigned to each task. This method swallows exceptions since it is invoked from a
     * {@link org.apache.kafka.test.TestUtils#waitForCondition} that will throw an error if this method continued
     * to return false after the specified duration has elapsed.
     *
     * @return true if each task was assigned a partition each, false if this was not true or an error occurred when
     * executing this operation.
     */
    private boolean checkForPartitionAssignment() {
        try {
            ConnectorStateInfo info = connect.connectorStatus(CONNECTOR_NAME);
            return info != null && info.tasks().size() == NUM_TASKS
                    && connectorHandle.tasks().stream().allMatch(th -> th.numPartitionsAssigned() == 1);
        } catch (Exception e) {
            // Log the exception and return that the partitions were not assigned
            log.error("Could not check connector state info.", e);
            return false;
        }
    }
}
