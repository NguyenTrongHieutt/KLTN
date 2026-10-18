package vn.vibefix;

import java.time.ZoneOffset;
import java.util.TimeZone;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class VibefixApplication {

	public static void main(String[] args) {
		// Everything runs in UTC (Conventions, Time), whatever the host's time zone.
		TimeZone.setDefault(TimeZone.getTimeZone(ZoneOffset.UTC));
		SpringApplication.run(VibefixApplication.class, args);
	}

}
