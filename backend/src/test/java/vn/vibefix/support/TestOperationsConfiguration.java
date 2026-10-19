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
	ScriptedDeadlineHandler pausableDeadlineHandler() {
		return new ScriptedDeadlineHandler(ScriptedDeadlineHandler.PAUSABLE);
	}

	@Bean
	ScriptedDeadlineHandler plainDeadlineHandler() {
		return new ScriptedDeadlineHandler(ScriptedDeadlineHandler.PLAIN);
	}

	@Bean
	TestNotificationCatalog testNotificationCatalog() {
		return new TestNotificationCatalog();
	}

}
