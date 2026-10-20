package vn.vibefix.payment.internal;

import java.net.URI;
import java.util.Set;

/**
 * AD-14: the application refuses to start unless every VNPay URL is the sandbox's over HTTPS, so no
 * configuration can reach real money. Only a test may point the URLs at a local stub.
 */
final class SandboxGuard {

	static final String SANDBOX_HOST = "sandbox.vnpayment.vn";

	private static final Set<String> LOCAL = Set.of("127.0.0.1", "localhost");

	private SandboxGuard() {
	}

	/** @throws IllegalStateException naming the URL that is not the sandbox's */
	static void check(VnpayProperties properties) {
		for (URI url : new URI[] { properties.payUrl(), properties.apiUrl() }) {
			boolean sandbox = "https".equals(url.getScheme()) && SANDBOX_HOST.equals(url.getHost());
			boolean stub = properties.allowLocalStub() && LOCAL.contains(url.getHost());
			if (!sandbox && !stub) {
				throw new IllegalStateException("VNPay URL " + url + " is not " + SANDBOX_HOST
						+ " (AD-14): vibefix only ever uses the VNPay sandbox");
			}
		}
	}

}
