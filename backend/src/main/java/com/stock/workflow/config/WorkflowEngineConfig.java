package com.stock.workflow.config;

import com.stock.workflow.engine.GlobalWorkflowEventListener;
import org.flowable.common.engine.api.delegate.event.FlowableEventListener;
import org.flowable.spring.SpringProcessEngineConfiguration;
import org.flowable.spring.boot.EngineConfigurationConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;

/**
 * 工作流引擎配置：向 Flowable 流程引擎注册全局事件监听器。
 */
@Configuration
public class WorkflowEngineConfig {

    @Bean
    public EngineConfigurationConfigurer<SpringProcessEngineConfiguration> workflowEventListenerConfigurer(
            GlobalWorkflowEventListener globalWorkflowEventListener) {
        return engineConfiguration -> {
            List<FlowableEventListener> listeners = engineConfiguration.getEventListeners();
            if (listeners == null) {
                listeners = new ArrayList<>();
            }
            listeners.add(globalWorkflowEventListener);
            engineConfiguration.setEventListeners(listeners);
        };
    }
}
