package vn.vibefix.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/** Adds the {@link ScriptedOperationHandler} to tests that boot the application. */
@TestConfiguration(proxyBeanMethods = false)
public class TestOperationsConfiguration {

	@Bean
	ScriptedOperationHandler scriptedOperationHandler() {
		return new ScriptedOperationHandler();
	}

}
