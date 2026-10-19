package vn.vibefix.identity.internal;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;

/** {@code identity}'s beans that every context needs, web or not ({@link SecurityConfiguration} is web only). */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(IdentityProperties.class)
class IdentityConfiguration {

	/** Where a signed-in security context lives: the server-side session (AD-19). */
	@Bean
	SecurityContextRepository securityContextRepository() {
		return new HttpSessionSecurityContextRepository();
	}

	@Bean
	TokenCipher tokenCipher(Environment environment, IdentityProperties properties) {
		return new TokenCipher(environment, properties.tokenKeyVersion());
	}

	@Bean
	PersonSignalKeys personSignalKeys(Environment environment, IdentityProperties properties) {
		return new PersonSignalKeys(environment, properties.personSignalKeyVersion());
	}

}
