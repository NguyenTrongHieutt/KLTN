package vn.vibefix.payment.internal;

import java.net.URI;
import java.nio.file.Path;

import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The environment's own VNPay sandbox merchant (AD-14).
 * @param payUrl the payment page; only {@code sandbox.vnpayment.vn}
 * @param apiUrl {@code merchant_webapi/api/transaction}, for {@code querydr} and refunds
 * @param tmnCode the merchant's terminal code
 * @param hashSecretFile the file holding the merchant's hash secret (AD-22), never the secret itself
 * @param returnUrl where the payment page sends the browser back; it only displays status
 * @param allowLocalStub tests point the URLs at a local stub; nothing else sets it
 */
@ConfigurationProperties("vibefix.payment.vnpay")
record VnpayProperties(URI payUrl, URI apiUrl, @Nullable String tmnCode, @Nullable Path hashSecretFile,
		@Nullable URI returnUrl, boolean allowLocalStub) {
}
