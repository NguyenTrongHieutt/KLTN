package vn.vibefix.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import vn.vibefix.delivery.internal.ScriptedPreviewChecks;

/**
 * Adds the {@link ScriptedOperationHandler}, the {@link TestNotificationCatalog}, and the
 * {@link ScriptedPreviewChecks} to tests that boot the application.
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

	/** No test reaches the internet: preview links respond unless a test scripts otherwise (Story 8.3). */
	@Bean
	@Primary
	ScriptedPreviewChecks scriptedPreviewChecks() {
		return new ScriptedPreviewChecks();
	}

}
