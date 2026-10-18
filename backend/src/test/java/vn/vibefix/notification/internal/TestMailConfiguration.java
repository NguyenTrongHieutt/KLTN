package vn.vibefix.notification.internal;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/** Puts the {@link RecordingMailSender} in place of SMTP in tests that boot the application. */
@TestConfiguration(proxyBeanMethods = false)
public class TestMailConfiguration {

	@Bean
	@Primary
	RecordingMailSender recordingMailSender() {
		return new RecordingMailSender();
	}

}
