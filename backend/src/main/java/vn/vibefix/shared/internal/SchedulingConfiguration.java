package vn.vibefix.shared.internal;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Turns on the pollers ({@code @Scheduled} is used only for pollers, AD-7). Tests switch them off
 * with {@value #ENABLED}{@code =false} and run each poller's work directly.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnBooleanProperty(name = SchedulingConfiguration.ENABLED, matchIfMissing = true)
class SchedulingConfiguration {

	static final String ENABLED = "vibefix.scheduling.enabled";

}
