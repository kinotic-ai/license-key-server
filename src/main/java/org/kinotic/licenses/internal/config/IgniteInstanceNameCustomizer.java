package org.kinotic.licenses.internal.config;

import org.apache.ignite.configuration.IgniteConfiguration;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.stereotype.Component;

/**
 * Names the Ignite instance after this server, so its logs, metrics and work directory never read as a Kinotic
 * OS node's.
 */
@Component
public class IgniteInstanceNameCustomizer implements BeanPostProcessor {

    private static final String INSTANCE_NAME = "license-key-server";

    @Override
    public Object postProcessBeforeInitialization(Object bean, String beanName) {
        if (bean instanceof IgniteConfiguration configuration) {
            configuration.setIgniteInstanceName(INSTANCE_NAME);
        }
        return bean;
    }
}
