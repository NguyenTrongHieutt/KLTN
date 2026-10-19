package vn.vibefix.scanning.internal;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * SM-7's planted credentials: one realistic, fake instance of every §B6 type, each in the file and
 * line it would leak from, keyed by the rule that must find it. Every value is built at run time
 * from a fixed alphabet, so no credential-shaped literal sits in this source (the pre-commit
 * gitleaks hook scans it). None of them is a real credential.
 */
final class PlantedSecrets {

	/** One planted credential: where it sits, the text around it, and the value itself. */
	record Planted(String ruleId, String path, String text, String value) {
	}

	private static final String ALPHABET = "Q7mZ2xK9pL4vB8nR3tY6wC1dF5gH0jSaEuIoNcXbVk";

	private static final String HEX = "0123456789abcdef";

	private PlantedSecrets() {
	}

	/** Every planted credential, in rule order. */
	static Map<String, Planted> all() {
		Map<String, Planted> planted = new LinkedHashMap<>();
		put(planted, "github-pat", "scripts/deploy.sh", "ghp" + "_" + alnum(36, 1), v -> "export GH_TOKEN=" + v + "\n");
		put(planted, "github-fine-grained-pat", "src/lib/github.ts", "github" + "_pat_" + alnum(22, 2) + "_" + alnum(59, 3),
				v -> "const client = new Octokit({ auth: '" + v + "' })\n");
		put(planted, "github-oauth", "notes/oauth.txt", "gho" + "_" + alnum(36, 4), v -> "token " + v + "\n");
		put(planted, "github-app-token", "ci/app.yml", "ghs" + "_" + alnum(36, 5), v -> "installation: " + v + "\n");
		put(planted, "github-refresh-token", "ci/refresh.yml", "ghr" + "_" + alnum(76, 6), v -> "refresh: " + v + "\n");
		String keyBody = alnum(64, 7) + "\\n" + alnum(64, 8) + "\\n" + alnum(40, 9);
		put(planted, "gcp-service-account", "firebase-admin.json",
				"-----BEGIN " + "PRIVATE KEY-----\\n" + keyBody + "\\n-----END " + "PRIVATE KEY-----\\n",
				v -> "{\n  \"type\": \"service_account\",\n  \"project_id\": \"shop-gom\",\n  \"private_key_id\": \"k1\",\n"
						+ "  \"private_key\": \"" + v + "\",\n  \"client_email\": \"admin@shop-gom.iam.gserviceaccount.com\"\n}\n");
		put(planted, "private-key", "deploy/id_ed25519",
				"-----BEGIN " + "OPENSSH PRIVATE KEY-----\n" + alnum(70, 10) + "\n" + alnum(70, 11) + "\n" + alnum(30, 12)
						+ "\n-----END " + "OPENSSH PRIVATE KEY-----",
				v -> v + "\n");
		put(planted, "supabase-service-role-key", ".env", jwt("service_role", 13),
				v -> "VITE_SUPABASE_URL=https://abcdefghijklmnopqrst.supabase.co\nSUPABASE_SERVICE_ROLE_KEY=" + v + "\n");
		put(planted, "supabase-secret-key", "supabase/functions/admin.ts", "sb" + "_secret_" + alnum(32, 14),
				v -> "const admin = createClient(url, '" + v + "')\n");
		put(planted, "supabase-jwt-secret", "supabase/config.toml", alnum(40, 15),
				v -> "[auth]\nSUPABASE_JWT_SECRET = \"" + v + "\"\n");
		put(planted, "gcp-api-key", "src/firebase.ts", "AIza" + alnum(35, 16),
				v -> "export const firebaseConfig = { apiKey: \"" + v + "\", projectId: \"shop-gom\" }\n");
		put(planted, "aws-access-key-id", "infra/aws.txt", "AKIA" + upper(16, 17), v -> "id=" + v + "\n");
		put(planted, "aws-secret-access-key", "infra/credentials", base64ish(40, 18),
				v -> "[default]\naws_secret_access_key = " + v + "\n");
		put(planted, "stripe-secret-key", "server/payments.js", "sk" + "_live_" + alnum(24, 19),
				v -> "const stripe = require('stripe')('" + v + "')\n");
		put(planted, "stripe-restricted-key", "server/reports.js", "rk" + "_live_" + alnum(24, 20),
				v -> "const reporter = stripe('" + v + "')\n");
		put(planted, "vnpay-hash-secret", "server/vnpay.properties", upper(32, 21),
				v -> "vnp_TmnCode=SHOPGOM1\nvnp_HashSecret=" + v + "\n");
		put(planted, "momo-secret-key", "server/momo.js", alnum(32, 22),
				v -> "const momo = { partnerCode: 'MOMOSHOP', secretKey: '" + v + "' }\n");
		put(planted, "zalopay-key", "server/zalopay.json", alnum(32, 23),
				v -> "{ \"app_id\": 2553, \"zalopay.key1\": \"" + v + "\" }\n");
		put(planted, "openai-api-key", "src/ai.ts", "sk" + "-proj-" + alnum(40, 24) + "T3Blbk" + "FJ" + alnum(40, 25),
				v -> "const openai = new OpenAI({ apiKey: '" + v + "' })\n");
		put(planted, "anthropic-api-key", "src/claude.ts", "sk" + "-ant-api03-" + alnum(93, 26) + "AA",
				v -> "const anthropic = new Anthropic({ apiKey: '" + v + "' })\n");
		put(planted, "sendgrid-api-token", "server/mail.js",
				"SG" + "." + alnum(22, 27) + "." + alnum(43, 28), v -> "sgMail.setApiKey('" + v + "')\n");
		put(planted, "resend-api-key", "server/resend.ts", "re" + "_" + alnum(8, 29) + "_" + alnum(24, 30),
				v -> "const resend = new Resend('" + v + "')\n");
		put(planted, "twilio-api-key", "server/twilio.env", "SK" + hex(32, 31),
				v -> "# twilio\nTWILIO_API_KEY=" + v + "\n");
		put(planted, "twilio-auth-token", "server/sms.js", hex(32, 32),
				v -> "const twilioAuthToken = '" + v + "'\n");
		put(planted, "vercel-token", "scripts/vercel.sh", alnum(24, 33), v -> "VERCEL_TOKEN=" + v + " vercel deploy\n");
		put(planted, "netlify-access-token", "scripts/netlify.txt", "nfp" + "_" + alnum(36, 34), v -> "token: " + v + "\n");
		put(planted, "netlify-token", "netlify.env", alnum(43, 35), v -> "NETLIFY_AUTH_TOKEN=" + v + "\n");
		put(planted, "database-connection-string", "prisma/.env.production", alnum(20, 36),
				v -> "DATABASE_URL=\"postgresql://postgres:" + v + "@db.abcdefghijklmnopqrst.supabase.co:5432/postgres\"\n");
		put(planted, "jwt-signing-secret", "server/auth.js", alnum(48, 37),
				v -> "const jwtSecret = '" + v + "'\n");
		put(planted, "dotenv-secret", "backend/.env.local", alnum(20, 38), v -> "SMTP_PASSWORD=" + v + "\n");
		return planted;
	}

	/** A Supabase-style JWT with {@code role}. */
	static String jwt(String role, int seed) {
		Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
		String header = encoder.encodeToString("{\"alg\":\"HS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
		String payload = encoder.encodeToString(("{\"iss\":\"supabase\",\"ref\":\"abcdefghijklmnopqrst\",\"role\":\"" + role
				+ "\",\"iat\":1700000000,\"exp\":2000000000}").getBytes(StandardCharsets.UTF_8));
		return header + "." + payload + "." + alnum(43, seed);
	}

	static String alnum(int length, int seed) {
		return pick(ALPHABET, length, seed);
	}

	static String upper(int length, int seed) {
		return pick("Q7MZ2XK9PL4VB8NR3TY6WC1DF5GH0JS", length, seed);
	}

	static String hex(int length, int seed) {
		return pick(HEX, length, seed);
	}

	static String base64ish(int length, int seed) {
		return pick(ALPHABET + "/+", length, seed);
	}

	private static String pick(String alphabet, int length, int seed) {
		StringBuilder value = new StringBuilder(length);
		for (int i = 0; i < length; i++) {
			value.append(alphabet.charAt((seed * 31 + i * 17 + i * i * 7) % alphabet.length()));
		}
		return value.toString();
	}

	private static void put(Map<String, Planted> planted, String ruleId, String path, String value,
			java.util.function.Function<String, String> text) {
		planted.put(ruleId, new Planted(ruleId, path, text.apply(value), value));
	}

}
