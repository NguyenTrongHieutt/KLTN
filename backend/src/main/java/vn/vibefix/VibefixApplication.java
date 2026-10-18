package vn.vibefix;

import java.time.ZoneOffset;
import java.util.TimeZone;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Profile;

import vn.vibefix.shared.internal.sandbox.SandboxWorkerConfiguration;

/**
 * The vibefix application. The same image also runs the sandbox worker (AD-32): under the profile
 * {@code sandbox} this class is skipped and only {@link SandboxWorkerConfiguration} loads, with no
 * database and none of the application's beans.
 */
@SpringBootApplication
@Profile("!" + SandboxWorkerConfiguration.PROFILE)
public class VibefixApplication {

	public static void main(String[] args) {
		// Everything runs in UTC (Conventions, Time), whatever the host's time zone.
		TimeZone.setDefault(TimeZone.getTimeZone(ZoneOffset.UTC));
		SpringApplication.run(new Class<?>[] { VibefixApplication.class, SandboxWorkerConfiguration.class }, args);
	}

}
