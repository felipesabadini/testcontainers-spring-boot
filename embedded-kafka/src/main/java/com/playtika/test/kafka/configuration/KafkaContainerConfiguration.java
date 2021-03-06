/*
 * The MIT License (MIT)
 *
 * Copyright (c) 2020 Playtika
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package com.playtika.test.kafka.configuration;

import com.github.dockerjava.api.model.Capability;
import com.playtika.test.kafka.KafkaTopicsConfigurer;
import com.playtika.test.kafka.checks.KafkaStatusCheck;
import com.playtika.test.kafka.properties.KafkaConfigurationProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.testcontainers.containers.BindMode;
import org.testcontainers.containers.FixedHostPortGenericContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;

import java.net.URI;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;

import static com.playtika.test.common.utils.ContainerUtils.containerLogsConsumer;
import static com.playtika.test.kafka.properties.KafkaConfigurationProperties.KAFKA_BEAN_NAME;
import static java.lang.String.format;

@Slf4j
@Configuration
@ConditionalOnProperty(value = "embedded.kafka.enabled", havingValue = "true", matchIfMissing = true)
@ConditionalOnBean(ZookeeperContainerConfiguration.class)
@EnableConfigurationProperties(KafkaConfigurationProperties.class)
public class KafkaContainerConfiguration {

    public static final String KAFKA_HOST_NAME = "kafka-broker.testcontainer.docker";

    private static final String DOCKER_HOST = "DOCKER_HOST";

    @Bean
    @ConditionalOnMissingBean
    public KafkaStatusCheck kafkaStartupCheckStrategy(KafkaConfigurationProperties kafkaProperties) {
        return new KafkaStatusCheck(kafkaProperties);
    }

    @Bean(name = KAFKA_BEAN_NAME, destroyMethod = "stop")
    public GenericContainer kafka(
            GenericContainer zookeeper,
            KafkaStatusCheck kafkaStatusCheck,
            KafkaConfigurationProperties kafkaProperties,
            @Value("${embedded.zookeeper.containerZookeeperConnect}") String containerZookeeperConnect,
            ConfigurableEnvironment environment,
            Network network
    ) {

        int kafkaInternalPort = kafkaProperties.getContainerBrokerPort(); // for access from other containers
        int kafkaExternalPort = kafkaProperties.getBrokerPort();  // for access from host
        // https://docs.confluent.io/current/installation/docker/docs/configuration.html search by KAFKA_ADVERTISED_LISTENERS

        log.info("Starting kafka broker. Docker image: {}", kafkaProperties.getDockerImage());

        GenericContainer kafka = new FixedHostPortGenericContainer<>(kafkaProperties.getDockerImage())
                .withLogConsumer(containerLogsConsumer(log))
                .withCreateContainerCmdModifier(cmd -> cmd.withHostName(KAFKA_HOST_NAME))
                .withCreateContainerCmdModifier(cmd -> cmd.withCapAdd(Capability.NET_ADMIN))
                .withEnv("KAFKA_ZOOKEEPER_CONNECT", containerZookeeperConnect)
                .withEnv("KAFKA_BROKER_ID", "-1")
                //see: https://stackoverflow.com/questions/41868161/kafka-in-kubernetes-cluster-how-to-publish-consume-messages-from-outside-of-kub
                //see: https://github.com/wurstmeister/kafka-docker/blob/master/README.md
                // order matters: external then internal since kafka.client.ClientUtils.getPlaintextBrokerEndPoints take first for simple consumers
                .withEnv("KAFKA_LISTENER_SECURITY_PROTOCOL_MAP", "EXTERNAL_PLAINTEXT:PLAINTEXT,INTERNAL_PLAINTEXT:PLAINTEXT")
                .withEnv("KAFKA_ADVERTISED_LISTENERS", "EXTERNAL_PLAINTEXT://" + kafkaHost() + ":" + kafkaExternalPort + ",INTERNAL_PLAINTEXT://" + KAFKA_HOST_NAME + ":" + kafkaInternalPort)
                .withEnv("KAFKA_LISTENERS", "EXTERNAL_PLAINTEXT://0.0.0.0:" + kafkaExternalPort + ",INTERNAL_PLAINTEXT://0.0.0.0:" + kafkaInternalPort)
                .withEnv("KAFKA_INTER_BROKER_LISTENER_NAME", "INTERNAL_PLAINTEXT")
                .withEnv("KAFKA_OFFSETS_TOPIC_NUM_PARTITIONS", "1")
                .withEnv("KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR", String.valueOf(kafkaProperties.getReplicationFactor()))
                .withEnv("KAFKA_LOG_FLUSH_INTERVAL_MS", String.valueOf(kafkaProperties.getLogFlushIntervalMs()))
                .withEnv("KAFKA_REPLICA_SOCKET_TIMEOUT_MS", String.valueOf(kafkaProperties.getReplicaSocketTimeoutMs()))
                .withEnv("KAFKA_CONTROLLER_SOCKET_TIMEOUT_MS", String.valueOf(kafkaProperties.getControllerSocketTimeoutMs()))
                .withExposedPorts(kafkaInternalPort, kafkaExternalPort)
                .withFixedExposedPort(kafkaInternalPort, kafkaInternalPort)
                .withFixedExposedPort(kafkaExternalPort, kafkaExternalPort)
                .withNetwork(network)
                .withNetworkAliases(KAFKA_HOST_NAME)
                .withExtraHost(KAFKA_HOST_NAME, "127.0.0.1")
                .waitingFor(kafkaStatusCheck)
                .withStartupTimeout(kafkaProperties.getTimeoutDuration());

        KafkaConfigurationProperties.FileSystemBind fileSystemBind = kafkaProperties.getFileSystemBind();
        if (fileSystemBind.isEnabled()) {
            String currentTimestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH-mm-ss-nnnnnnnnn"));
            String dataFolder = fileSystemBind.getDataFolder();
            String kafkaData = Paths.get(dataFolder, currentTimestamp).toAbsolutePath().toString();
            log.info("Writing kafka data to: {}", kafkaData);

            kafka = kafka
                    .withFileSystemBind(kafkaData, "/var/lib/kafka/data", BindMode.READ_WRITE);
        }

        kafka.start();
        registerKafkaEnvironment(kafka, environment, kafkaProperties);
        return kafka;
    }

    private String kafkaHost() {
        final String dockerHost = System.getenv(DOCKER_HOST);

        if (dockerHost != null) {
            try {
                final String dockerHostHost = new URI(dockerHost).getHost();

                log.info("From {}={} parsed Kafka host: {}", DOCKER_HOST, dockerHost, dockerHostHost);

                return dockerHostHost;

            } catch (Exception e) {
                log.info("Failed to parse {}={}, use localhost instead: {}", DOCKER_HOST, dockerHost, e.getMessage());
            }
        }

        log.info("Use localhost as Kafka host");

        return "localhost";
    }

    private void registerKafkaEnvironment(GenericContainer kafka,
                                          ConfigurableEnvironment environment,
                                          KafkaConfigurationProperties kafkaProperties) {
        LinkedHashMap<String, Object> map = new LinkedHashMap<>();

        String host = kafka.getContainerIpAddress();
        String kafkaBrokerList = format("%s:%d", host, kafkaProperties.getBrokerPort());
        map.put("embedded.kafka.brokerList", kafkaBrokerList);

        Integer mappedPort = kafka.getMappedPort(kafkaProperties.getContainerBrokerPort());
        String kafkaBrokerListForContainers = format("%s:%d", KAFKA_HOST_NAME, mappedPort);
        map.put("embedded.kafka.containerBrokerList", kafkaBrokerListForContainers);

        MapPropertySource propertySource = new MapPropertySource("embeddedKafkaInfo", map);

        log.info("Started kafka broker. Connection details: {}", map);

        environment.getPropertySources().addFirst(propertySource);
    }

    @Bean
    public KafkaTopicsConfigurer kafkaConfigurer(
            GenericContainer zookeeper,
            GenericContainer kafka,
            KafkaConfigurationProperties kafkaProperties,
            @Value("${embedded.zookeeper.containerZookeeperConnect}") String containerZookeeperConnect
    ) {
        return new KafkaTopicsConfigurer(kafka, containerZookeeperConnect, kafkaProperties);
    }

}