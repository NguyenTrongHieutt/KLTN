package vn.vibefix.payment.internal;

import java.io.PrintStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.ExitCodeGenerator;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

import vn.vibefix.payment.internal.VnpaySandbox.Answer;
import vn.vibefix.payment.internal.VnpaySandbox.PayRequest;

/**
 * Spike S2's probe (Story 4.5, AD-14, AD-15, SG-24): under {@code dev} with {@code cli},
 * {@code --vibefix.cli.command=spike-s2 --step=…} runs one step against the environment's own VNPay
 * sandbox merchant and prints what VNPay answered, so the author can record which refunds and
 * {@code querydr} answers the merchant has:
 * <ul>
 * <li>{@code pay --amount=<VND> [--txn-ref=…]}: a signed payment URL to open in a browser;</li>
 * <li>{@code return --query=<the return URL or its query>}: the return's codes and its hash, which
 * carries the IPN's fields;</li>
 * <li>{@code query --txn-ref=… --transaction-date=yyyyMMddHHmmss}: {@code querydr};</li>
 * <li>{@code refund --txn-ref=… --transaction-date=… --transaction-no=… --amount=<VND> --type=02|03}:
 * a full ({@code 02}) or partial ({@code 03}) refund.</li>
 * </ul>
 * It never prints the hash secret or a hash. Exit code 0 when VNPay answered with a verified hash
 * (whatever its codes), 1 for a transport failure, a hash that does not verify, or a refusal, 2 for a
 * wrong invocation or a missing setting.
 */
@Component
@Profile("cli")
class SpikeS2Command implements ApplicationRunner, ExitCodeGenerator {

	static final String COMMAND = "spike-s2";

	private static final Pattern REFERENCE = Pattern.compile("[A-Za-z0-9]{1,100}");

	private static final Pattern GATEWAY_TIME = Pattern.compile("\\d{14}");

	private static final Pattern DIGITS = Pattern.compile("\\d{1,20}");

	private final VnpaySandbox sandbox;

	private final Environment environment;

	private final Clock clock;

	private final PrintStream out;

	private final PrintStream err;

	private volatile int exitCode;

	@Autowired
	SpikeS2Command(VnpaySandbox sandbox, Environment environment, Clock clock) {
		this(sandbox, environment, clock, System.out, System.err);
	}

	SpikeS2Command(VnpaySandbox sandbox, Environment environment, Clock clock, PrintStream out, PrintStream err) {
		this.sandbox = sandbox;
		this.environment = environment;
		this.clock = clock;
		this.out = out;
		this.err = err;
	}

	@Override
	public void run(ApplicationArguments args) {
		if (!COMMAND.equals(this.environment.getProperty("vibefix.cli.command"))) {
			this.exitCode = 0;
			return;
		}
		if (this.environment.acceptsProfiles(Profiles.of("prod")) || !this.environment.acceptsProfiles(Profiles.of("dev"))) {
			this.err.println("Refused: " + COMMAND + " runs on the dev stack only");
			this.exitCode = 1;
			return;
		}
		String step = single(args, "step");
		String missing = this.sandbox.missingSetting();
		if (step == null || !List.of("pay", "return", "query", "refund").contains(step)) {
			usage();
			return;
		}
		if (missing != null) {
			this.err.println("Refused: " + missing + " is not set (deploy/README.md \"Spike S2\")");
			this.exitCode = 2;
			return;
		}
		switch (step) {
			case "pay" -> pay(args);
			case "return" -> verifyReturn(args);
			case "query" -> query(args);
			default -> refund(args);
		}
	}

	@Override
	public int getExitCode() {
		return this.exitCode;
	}

	private void pay(ApplicationArguments args) {
		String amount = single(args, "amount");
		String reference = single(args, "txn-ref");
		if (amount == null || !DIGITS.matcher(amount).matches() || Long.parseLong(amount) < 5_000
				|| (reference != null && !REFERENCE.matcher(reference).matches())) {
			usage();
			return;
		}
		String txnRef = reference != null ? reference : "S2" + VnpaySandbox.TIME.format(this.clock.instant());
		PayRequest request = this.sandbox.payUrl(txnRef, Long.parseLong(amount), "Thanh toan thu spike S2 " + txnRef, ip(args),
				this.environment.getProperty("vibefix.payment.vnpay.return-url"));
		this.out.println("Spike S2 pay: open this URL in a browser and pay with the sandbox test card:");
		this.out.println(request.url());
		this.out.println("vnp_TxnRef=" + request.txnRef() + "  vnp_CreateDate=" + request.createDate() + "  vnp_ExpireDate="
				+ request.expireDate());
		this.out.println("Then run --step=return --query='<the URL VNPay sends you back to>', and keep vnp_TransactionNo"
				+ " for the refund.");
		this.exitCode = 0;
	}

	private void verifyReturn(ApplicationArguments args) {
		String query = single(args, "query");
		if (query == null || !query.contains("vnp_")) {
			usage();
			return;
		}
		print("return", this.sandbox.verifyReturn(parse(query)));
	}

	private void query(ApplicationArguments args) {
		String txnRef = single(args, "txn-ref");
		String date = single(args, "transaction-date");
		if (txnRef == null || !REFERENCE.matcher(txnRef).matches() || date == null || !GATEWAY_TIME.matcher(date).matches()) {
			usage();
			return;
		}
		print("querydr", this.sandbox.queryDr(requestId(), txnRef, date, "Truy van spike S2 " + txnRef, ip(args)));
	}

	private void refund(ApplicationArguments args) {
		String txnRef = single(args, "txn-ref");
		String date = single(args, "transaction-date");
		String number = single(args, "transaction-no");
		String amount = single(args, "amount");
		String type = single(args, "type");
		if (txnRef == null || !REFERENCE.matcher(txnRef).matches() || date == null || !GATEWAY_TIME.matcher(date).matches()
				|| number == null || !DIGITS.matcher(number).matches() || amount == null || !DIGITS.matcher(amount).matches()
				|| !("02".equals(type) || "03".equals(type))) {
			usage();
			return;
		}
		print("refund " + type, this.sandbox.refund(requestId(), txnRef, type, Long.parseLong(amount), number, date, "spike-s2",
				"Hoan tien spike S2 " + txnRef, ip(args)));
	}

	private void print(String step, Answer answer) {
		this.out.println("Spike S2 " + step + ": HTTP " + answer.status());
		answer.fields().forEach((name, value) -> this.out.println("  " + name + "=" + value));
		boolean answered = answer.status() == 200;
		this.out.println(answered ? "  hash " + (answer.hashVerified() ? "verified" : "NOT verified") : "  VNPay did not answer");
		this.exitCode = answered && answer.hashVerified() ? 0 : 1;
	}

	private void usage() {
		this.err.println("Usage: --spring.profiles.active=dev,cli --vibefix.cli.command=" + COMMAND + " --step=pay --amount=<VND>"
				+ " [--txn-ref=<letters and digits>]");
		this.err.println("   or: --step=return --query='<return URL or query>'");
		this.err.println("   or: --step=query --txn-ref=<ref> --transaction-date=<yyyyMMddHHmmss>");
		this.err.println("   or: --step=refund --txn-ref=<ref> --transaction-date=<yyyyMMddHHmmss> --transaction-no=<n>"
				+ " --amount=<VND> --type=02|03");
		this.exitCode = 2;
	}

	/** The return query as name and value, decoded; a whole URL is cut at its {@code ?}. */
	static Map<String, String> parse(String query) {
		String raw = query.contains("?") ? query.substring(query.indexOf('?') + 1) : query;
		Map<String, String> params = new LinkedHashMap<>();
		for (String pair : raw.split("&")) {
			int equals = pair.indexOf('=');
			if (equals > 0) {
				params.put(URLDecoder.decode(pair.substring(0, equals), StandardCharsets.UTF_8),
						URLDecoder.decode(pair.substring(equals + 1), StandardCharsets.UTF_8));
			}
		}
		return params;
	}

	private static String requestId() {
		return UUID.randomUUID().toString().replace("-", "");
	}

	private static String ip(ApplicationArguments args) {
		String ip = single(args, "ip");
		return ip == null ? "127.0.0.1" : ip;
	}

	private static @Nullable String single(ApplicationArguments args, String name) {
		List<String> values = args.getOptionValues(name);
		return values == null || values.size() != 1 ? null : values.getFirst();
	}

}
