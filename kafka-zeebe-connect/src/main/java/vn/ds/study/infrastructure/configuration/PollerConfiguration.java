/*
 * Class: PollerConfiguration
 *
 * Created on Sep 1, 2021
 *
 * (c) Copyright Swiss Post Solutions Ltd, unpublished work
 * All use, disclosure, and/or reproduction of this material is prohibited
 * unless authorized in writing.  All Rights Reserved.
 * Rights in this program belong to:
 * Swiss Post Solution.
 * Floor 4-5-8, ICT Tower, Quang Trung Software City
 */
package vn.ds.study.infrastructure.configuration;

import java.time.Duration;
import java.util.Map;

import javax.annotation.PostConstruct;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.stream.binder.kafka.properties.KafkaBinderConfigurationProperties;
import org.springframework.cloud.stream.binder.kafka.properties.KafkaExtendedBindingProperties;
import org.springframework.cloud.stream.binding.AbstractBindingTargetFactory;
import org.springframework.cloud.stream.binding.BindingService;
import org.springframework.cloud.stream.function.StreamBridge;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageHandler;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.camunda.zeebe.client.ZeebeClient;
import io.camunda.zeebe.client.api.response.ActivatedJob;
import io.camunda.zeebe.client.api.worker.JobClient;
import io.camunda.zeebe.client.api.worker.JobHandler;
import io.camunda.zeebe.client.api.worker.JobWorker;
import io.camunda.zeebe.client.api.worker.JobWorkerBuilderStep1.JobWorkerBuilderStep3;
import io.camunda.zeebe.client.impl.ZeebeClientBuilderImpl;
import io.camunda.zeebe.client.impl.command.ArgumentUtil;
import vn.ds.study.application.builder.KafkaConsumerBuilder;
import vn.ds.study.application.builder.KafkaConsumerManager;
import vn.ds.study.application.handler.ConsumerMessageHandler;
import vn.ds.study.infrastructure.persistence.JobRepository;
import vn.ds.study.infrastructure.properties.KafkaTopicProperties;
import vn.ds.study.infrastructure.properties.PollerProperties;
import vn.ds.study.model.JobInfo;

@Configuration
@EnableConfigurationProperties(value = { PollerProperties.class, KafkaBinderConfigurationProperties.class, KafkaExtendedBindingProperties.class})
public class PollerConfiguration {
    
    private static final Logger LOGGER = LoggerFactory.getLogger(PollerConfiguration.class);

    private PollerProperties pollerProperties;

    private JobRepository jobRepository;

    private KafkaConsumerManager kafkaConsumerManager;
    
    private ZeebeClient zeebeClient;
    
    private ZeebeClientBuilderImpl zeebeClientBuilder;
    
    private StreamBridge streamBridge;
    
    private ObjectMapper objectMapper;
    
    private AbstractBindingTargetFactory<? extends MessageChannel> targetFactory;
    
    private BindingService bindingService;
    
    private KafkaTopicProperties consumerTopicProperties;
    
    private KafkaTopicProperties producerTopicProperties;

    public PollerConfiguration(PollerProperties pollerProperties, JobRepository jobRepository,
            KafkaConsumerManager kafkaConsumerManager, ZeebeClientBuilderImpl zeebeClientBuilder,
            StreamBridge streamBridge, ObjectMapper objectMapper,
            AbstractBindingTargetFactory<? extends MessageChannel> targetFactory, BindingService bindingService,
            KafkaTopicProperties consumerTopicProperties, KafkaTopicProperties producerTopicProperties) {
        super();
        this.pollerProperties = pollerProperties;
        this.jobRepository = jobRepository;
        this.kafkaConsumerManager = kafkaConsumerManager;
        this.zeebeClientBuilder = zeebeClientBuilder;
        this.streamBridge = streamBridge;
        this.objectMapper = objectMapper;
        this.targetFactory = targetFactory;
        this.bindingService = bindingService;
        this.consumerTopicProperties = consumerTopicProperties;
        this.producerTopicProperties = producerTopicProperties;
        this.zeebeClient = this.zeebeClientBuilder.build();
    }

    @PostConstruct
    private void initializePoller() {
        ArgumentUtil.ensureNotNullNorEmpty("jobType", pollerProperties.getJobType());
        ArgumentUtil.ensureNotNullNorEmpty("correlationKey", pollerProperties.getCorrelationKey());
        ArgumentUtil.ensureNotNullNorEmpty("consumerProperties.topic.suffix", consumerTopicProperties.getSuffix());
        ArgumentUtil.ensureNotNullNorEmpty("producerProperties.topic.suffix", producerTopicProperties.getSuffix());

        int numberOfThread = this.zeebeClientBuilder.getNumJobWorkerExecutionThreads();
                
        for (int thread = 0; thread < numberOfThread; thread++) {
            createJobWorker(this.pollerProperties.getJobType(), new KafkaConnectJobHandler());
        }
    }
    
    private void createJobWorker(final String jobType, final JobHandler jobHandler) {

        final String name = this.zeebeClientBuilder.getDefaultJobWorkerName();
        final int maxJobsActive = this.zeebeClientBuilder.getDefaultJobWorkerMaxJobsActive();
        final Duration timeout = this.zeebeClientBuilder.getDefaultJobTimeout();
        final Duration pollInterval = this.zeebeClientBuilder.getDefaultJobPollInterval();
        final Duration requestTimeout = this.zeebeClientBuilder.getDefaultRequestTimeout();
        
        final JobWorkerBuilderStep3 builder = this.zeebeClient
                .newWorker()
                .jobType(jobType)
                .handler(jobHandler)
                .name(name)
                .maxJobsActive(maxJobsActive)
                .timeout(timeout)
                .pollInterval(pollInterval)
                .requestTimeout(requestTimeout);
        final JobWorker jobWorker = builder.open();

        LOGGER.info("Register job worker: {}", jobWorker);
    }
    
    class KafkaConnectJobHandler implements JobHandler {

        @Override
        public void handle(final JobClient client, final ActivatedJob job) throws Exception {
            final Map<String, Object> variablesAsMap = job.getVariablesAsMap();

            final String correlationKey = (String) variablesAsMap.get(pollerProperties.getCorrelationKey());
            final String topicPrefix = job.getElementId();
            final String producerTopicSuffix = producerTopicProperties.getSuffix();
            final String consumerTopicSuffix = consumerTopicProperties.getSuffix();
            final String topicName = new StringBuilder().append(topicPrefix).append(producerTopicSuffix).toString();
            final String consumerName = topicPrefix;
            
            jobRepository.addJob(JobInfo.from(correlationKey, job.getProcessInstanceKey(), job.getKey(), job));
            
            if(!kafkaConsumerManager.findAndAddConsumerIfAbsent(consumerName)) {
                try {
                    final MessageHandler messageHandler = new ConsumerMessageHandler(jobRepository, objectMapper,
                        zeebeClient, pollerProperties.getCorrelationKey());
                    KafkaConsumerBuilder.prepare(targetFactory, bindingService,
                        messageHandler, topicPrefix).setTopicSuffix(consumerTopicSuffix).build();
                } catch (Exception e) {
                    LOGGER.error("Error while building the consumer {}. Detail: ", topicPrefix, e);
                    kafkaConsumerManager.removeConsumer(consumerName);
                    bindingService.unbindConsumers(consumerName);
                    LOGGER.debug("Error while building the consumer. So remove the consumer {}", topicPrefix);
                }
            }
            streamBridge.send(topicName, variablesAsMap);
        }
    }
}