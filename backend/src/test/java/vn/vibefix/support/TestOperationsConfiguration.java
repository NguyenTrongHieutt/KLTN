package vn.vibefix.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * Adds the {@link ScriptedOperationHandler} and the {@link TestNotificationCatalog} to tests that
 * boot the application.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestOperationsConfiguration {

	@Bean
	ScriptedOperationHandler scriptedOperationHandler() {
		return new ScriptedOperationHandler();
	}

	@Bean
	TestNotificationCatalog testNotificationCatalog() {
		return new TestNotificationCatalog();
	}

}
