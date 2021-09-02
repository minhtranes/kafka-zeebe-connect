/*
 * Class: DynamicConsumerManager
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
package vn.ds.study.application.builder;

import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.cloud.stream.binder.ConsumerProperties;
import org.springframework.cloud.stream.binding.AbstractBindingTargetFactory;
import org.springframework.cloud.stream.binding.BindingService;
import org.springframework.cloud.stream.config.BindingProperties;
import org.springframework.cloud.stream.config.BindingServiceProperties;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageHandler;
import org.springframework.messaging.SubscribableChannel;
import org.springframework.util.StringUtils;

public class ConsumerBuilder {

    private static final String TOPIC_SUFFIX_DEFAULT = "-response";
    
    private static final String CONSUMER_NAME_SUFFIX_DEFAULT = "-in-0";
    
    private ConfigurableListableBeanFactory configurableListableBeanFactory;

    private AbstractBindingTargetFactory<? extends MessageChannel> abstractBindingTargetFactory;

    private BindingService bindingService;

    private MessageHandler messageHandler;
    
    private String topicPrefix;

    private ConsumerBuilder() {
    }

    private ConsumerBuilder(ConfigurableListableBeanFactory beanFactory,
            AbstractBindingTargetFactory<? extends MessageChannel> targetFactory,
            BindingService bindingService, String topicPrefix, MessageHandler messageHandler) {
        super();
        this.configurableListableBeanFactory = beanFactory;
        this.abstractBindingTargetFactory = targetFactory;
        this.bindingService = bindingService;
        this.topicPrefix = topicPrefix;
        this.messageHandler = messageHandler;
    }

    public static ConsumerBuilder2 prepare(
        ConfigurableListableBeanFactory beanFactory,
        AbstractBindingTargetFactory<? extends MessageChannel> targetFactory,
        BindingService bindingService,
        MessageHandler messageHandler,
        String topicPrefix) {

        return (new ConsumerBuilder()).new ConsumerBuilder2(beanFactory, targetFactory, bindingService, messageHandler, topicPrefix);
    }

    class ConsumerBuilder2 {

        private ConsumerBuilder consumerBuilder;

        private String topicSuffix;
        
        private String group;
        
        private String consumerNameSuffix;
        
        ConsumerBuilder2(ConfigurableListableBeanFactory beanFactory,
                AbstractBindingTargetFactory<? extends MessageChannel> abstractBindingTargetFactory,
                BindingService bindingService, MessageHandler messageHandler, String topicPrefix) {
            this.consumerBuilder = new ConsumerBuilder(beanFactory, abstractBindingTargetFactory,
                bindingService, topicPrefix, messageHandler);
        }

        public ConsumerBuilder2 setTopicSuffix(String topicSuffix) {
            this.topicSuffix = topicSuffix;
            return this;
        }

        public ConsumerBuilder2 setGroup(String group) {
            this.group = group;
            return this;
        }

        public ConsumerBuilder2 setConsumerNameSuffix(String consumerNameSuffix) {
            this.consumerNameSuffix = consumerNameSuffix;
            return this;
        }

        public void build() {
            final String consumerGroup = StringUtils.hasText(this.group) ? this.group : consumerBuilder.getTopicPrefix();
            final String suffix = StringUtils.hasText(this.topicSuffix) ? this.topicSuffix : TOPIC_SUFFIX_DEFAULT;
            final String consumerSuffix = StringUtils.hasText(this.consumerNameSuffix) ? this.consumerNameSuffix
                    : CONSUMER_NAME_SUFFIX_DEFAULT;

            final BindingService bindingService = consumerBuilder.getBindingService();
            final AbstractBindingTargetFactory<? extends MessageChannel> targetFactory = consumerBuilder.getAbstractBindingTargetFactory();
            final ConfigurableListableBeanFactory beanFactory = consumerBuilder.getConfigurableListableBeanFactory();
            final MessageHandler consumerHandler = consumerBuilder.getMessageHandler();
            final String topicPrefix = consumerBuilder.getTopicPrefix();
           
            final String topic = new StringBuilder().append(topicPrefix).append(suffix).toString();
            final String consumerName = new StringBuilder().append(topicPrefix).append(consumerSuffix).toString();

            
            final BindingProperties bindingProperties = new BindingProperties();
            bindingProperties.setConsumer(new ConsumerProperties());
            bindingProperties.setDestination(topic);
            bindingProperties.setGroup(consumerGroup);

            final BindingServiceProperties bindingServiceProperties = bindingService.getBindingServiceProperties();
            bindingServiceProperties.getBindings().put(consumerName, bindingProperties);

            SubscribableChannel channel = (SubscribableChannel) targetFactory.createInput(consumerName);
            beanFactory.registerSingleton(consumerName, channel);
            channel = (SubscribableChannel) beanFactory.initializeBean(channel, consumerName);

            bindingService.bindConsumer(channel, consumerName);

            channel.subscribe(consumerHandler);
        }
    }

    public ConfigurableListableBeanFactory getConfigurableListableBeanFactory() {
        return configurableListableBeanFactory;
    }

    public AbstractBindingTargetFactory<? extends MessageChannel> getAbstractBindingTargetFactory() {
        return abstractBindingTargetFactory;
    }

    public BindingService getBindingService() {
        return bindingService;
    }

    public MessageHandler getMessageHandler() {
        return messageHandler;
    }

    public String getTopicPrefix() {
        return topicPrefix;
    }
}