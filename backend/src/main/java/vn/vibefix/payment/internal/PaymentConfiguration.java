package vn.vibefix.payment.internal;

import java.time.Clock;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The VNPay sandbox merchant; creating it checks the URLs, so a non-sandbox one stops the start (AD-14). */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(VnpayProperties.class)
class PaymentConfiguration {

	@Bean
	VnpaySandbox vnpaySandbox(VnpayProperties properties, Clock clock) {
		return new VnpaySandbox(properties, clock);
	}

}
