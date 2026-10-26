package vn.vibefix.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.env.MockEnvironment;

import vn.vibefix.VibefixApplication;
import vn.vibefix.support.IntegrationTest;

/** FR-2, AD-19, SG-10: the bootstrap CLI creates the first Administrator once, audited. */
class AdministratorBootstrapIT extends IntegrationTest {

	private static final char[] PASSWORD = "dung-mat-khau-dai-12".toCharArray();

	@Autowired
	private AdministratorBootstrap bootstrap;

	@Autowired
	private JdbcClient jdbc;

	@Autowired
	private TokenCipher cipher;

	@Autowired
	private IdentityPersonalData personalData;

	@Test
	void theFirstAdministratorGetsAnArgon2PasswordAndASealedSecretShownOnce() {
		AdministratorBootstrap.Created created = this.bootstrap.createFirst(" Ha.Phuong@Vibefix.VN ", "Hà Phương",
				PASSWORD.clone());

		Map<String, Object> account = this.jdbc.sql("SELECT * FROM identity.account WHERE id = :id")
			.param("id", created.accountId())
			.query()
			.singleRow();
		assertThat(account).containsEntry("role", "ADMINISTRATOR").containsEntry("github_user_id", null);
		Map<String, Object> credential = this.jdbc.sql("SELECT * FROM identity.administrator_credential")
			.query()
			.singleRow();
		assertThat(credential).containsEntry("email", "ha.phuong@vibefix.vn").containsEntry("display_name", "Hà Phương");
		assertThat((String) credential.get("password_hash")).startsWith("$argon2id$");
		assertThat(AdminSignIns.PASSWORDS.matches(new String(PASSWORD), (String) credential.get("password_hash"))).isTrue();
		assertThat(credential.values().toString()).doesNotContain(created.secret());
		TokenCipher.Sealed sealed = new TokenCipher.Sealed((Integer) credential.get("totp_key_version"),
				(byte[]) credential.get("totp_nonce"), (byte[]) credential.get("totp_ciphertext"));
		assertThat(this.cipher.open(sealed, AdministratorCredentials.totpAssociatedData(created.accountId())))
			.isEqualTo(created.secret());
		assertThat(created.otpauthUri()).contains("secret=" + created.secret()).contains("issuer=vibefix");
		assertThat(this.jdbc.sql("""
				SELECT actor_type FROM audit.audit_entry WHERE action = 'identity.administrator_bootstrapped'
				""").query(String.class).single()).isEqualTo("SYSTEM");
	}

	@Test
	void anExportHoldsTheEmailAndNameButNeverTheSecretsAndAnErasureDeletesTheCredential() {
		AdministratorBootstrap.Created created = this.bootstrap.createFirst("ha.phuong@vibefix.vn", "Hà Phương",
				PASSWORD.clone());

		String export = this.personalData.export(created.accountId()).orElseThrow().toString();
		assertThat(export).contains("ha.phuong@vibefix.vn").contains("Hà Phương").doesNotContain("argon2")
			.doesNotContain(created.secret());

		this.personalData.eraseAccount(created.accountId());
		assertThat(this.jdbc.sql("SELECT count(*) FROM identity.administrator_credential").query(Long.class).single())
			.isZero();
	}

	@Test
	void itRefusesOnceAnyAdministratorExists() {
		IdentityTestSupport.insertAccount(this.jdbc, vn.vibefix.shared.Role.ADMINISTRATOR, null);

		assertThatThrownBy(() -> this.bootstrap.createFirst("lam@vibefix.vn", "Lâm", PASSWORD.clone()))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("exists already");
		assertThat(this.jdbc.sql("SELECT count(*) FROM identity.administrator_credential").query(Long.class).single())
			.isZero();
	}

	@Test
	void itChecksTheEmailTheNameAndThePasswordLength() {
		assertThatThrownBy(() -> this.bootstrap.createFirst("not-an-email", "Lâm", PASSWORD.clone()))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> this.bootstrap.createFirst("lam@vibefix.vn", " ", PASSWORD.clone()))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> this.bootstrap.createFirst("lam@vibefix.vn", "Lâm", "ngan-qua".toCharArray()))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("12");
	}

	@Test
	void theCommandPrintsTheSecretOnceAndAnswersWithItsExitCode() {
		Environment environment = new MockEnvironment().withProperty("vibefix.cli.command",
				BootstrapCommand.COMMAND);
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		ByteArrayOutputStream err = new ByteArrayOutputStream();
		BootstrapCommand command = new BootstrapCommand(this.bootstrap, environment, PASSWORD::clone,
				new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(err, true, StandardCharsets.UTF_8));

		command.run(new DefaultApplicationArguments("--email=ha.phuong@vibefix.vn", "--name=Hà Phương"));
		assertThat(command.getExitCode()).isZero();
		assertThat(out.toString(StandardCharsets.UTF_8)).contains("Secret: ").contains("otpauth://totp/");

		command.run(new DefaultApplicationArguments("--email=lam@vibefix.vn", "--name=Lâm"));
		assertThat(command.getExitCode()).as("a second Administrator").isOne();
		assertThat(err.toString(StandardCharsets.UTF_8)).contains("Refused: ");

		command.run(new DefaultApplicationArguments("--email=lam@vibefix.vn"));
		assertThat(command.getExitCode()).as("no --name").isEqualTo(2);
		BootstrapCommand other = new BootstrapCommand(this.bootstrap, new MockEnvironment(), PASSWORD::clone,
				System.out, System.err);
		other.run(new DefaultApplicationArguments("--email=lam@vibefix.vn", "--name=Lâm"));
		assertThat(other.getExitCode()).as("no command").isEqualTo(2);
	}

	/** The real entry point: profiles dev and cli, no web server, the password from a file. */
	@Test
	void theCliProfileRunsTheCommandWithoutAWebServerAndExits(@TempDir Path directory) throws Exception {
		String db = database.createScratchDatabase();
		Path passwordFile = Files.writeString(directory.resolve("admin-password"), new String(PASSWORD) + "\n");
		String[] args = concat(database.applicationArguments(db), "--vibefix.cli.command=" + BootstrapCommand.COMMAND,
				"--email=ha.phuong@vibefix.vn", "--name=Hà Phương", "--password-file=" + passwordFile);

		try (ConfigurableApplicationContext context = new SpringApplicationBuilder(VibefixApplication.class)
			.profiles("dev", VibefixApplication.CLI_PROFILE)
			.properties("management.health.sandbox.enabled=false")
			.run(args)) {
			assertThat(context).isNotInstanceOf(org.springframework.web.context.WebApplicationContext.class);
			assertThat(context.getEnvironment().getProperty("vibefix.scheduling.enabled")).isEqualTo("false");
			assertThat(SpringApplication.exit(context)).isZero();
		}
		try (var connection = java.sql.DriverManager.getConnection(database.jdbcUrl(db),
				vn.vibefix.support.PostgresTestDatabase.MIGRATOR, database.migratorPassword());
				var statement = connection.createStatement();
				var rows = statement.executeQuery("SELECT email FROM identity.administrator_credential")) {
			assertThat(rows.next()).isTrue();
			assertThat(rows.getString(1)).isEqualTo("ha.phuong@vibefix.vn");
		}
	}

	private static String[] concat(String[] first, String... more) {
		String[] all = java.util.Arrays.copyOf(first, first.length + more.length);
		System.arraycopy(more, 0, all, first.length, more.length);
		return all;
	}

}
