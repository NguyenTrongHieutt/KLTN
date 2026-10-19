package vn.vibefix.identity.internal;

import java.io.Console;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.function.Supplier;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.ExitCodeGenerator;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * The bootstrap CLI (AD-19): under the {@code cli} profile, with {@code dev} or {@code prod},
 * {@code --vibefix.cli.command=create-first-administrator --email=… --name=…} creates the first
 * Administrator. The password is read from {@code --password-file=<path>} (a mounted secret, its
 * first line), else from a hidden console prompt typed twice, else from {@code VIBEFIX_ADMIN_PASSWORD};
 * it never goes on the command line. The TOTP secret is printed once. Exit code 0 on success, 1 when
 * refused, 2 for a wrong invocation.
 */
@Component
@Profile("cli")
class BootstrapCommand implements ApplicationRunner, ExitCodeGenerator {

	static final String COMMAND = "create-first-administrator";

	static final String PASSWORD_VARIABLE = "VIBEFIX_ADMIN_PASSWORD";

	private final AdministratorBootstrap bootstrap;

	private final Environment environment;

	private final Supplier<char[]> passwords;

	private final PrintStream out;

	private final PrintStream err;

	private volatile int exitCode = 2;

	@Autowired
	BootstrapCommand(AdministratorBootstrap bootstrap, Environment environment) {
		this(bootstrap, environment, BootstrapCommand::readPassword, System.out, System.err);
	}

	BootstrapCommand(AdministratorBootstrap bootstrap, Environment environment, Supplier<char[]> passwords,
			PrintStream out, PrintStream err) {
		this.bootstrap = bootstrap;
		this.environment = environment;
		this.passwords = passwords;
		this.out = out;
		this.err = err;
	}

	@Override
	public void run(ApplicationArguments args) {
		List<String> emails = args.getOptionValues("email");
		List<String> names = args.getOptionValues("name");
		if (!COMMAND.equals(this.environment.getProperty("vibefix.cli.command")) || emails == null
				|| emails.size() != 1 || names == null || names.size() != 1) {
			this.err.println("Usage: --spring.profiles.active=dev,cli --vibefix.cli.command=" + COMMAND
					+ " --email=<address> --name=<display name>; the password comes from the console or "
					+ PASSWORD_VARIABLE);
			this.exitCode = 2;
			return;
		}
		char[] password;
		try {
			List<String> files = args.getOptionValues("password-file");
			password = files == null ? this.passwords.get() : firstLine(Path.of(files.getFirst()));
		}
		catch (IOException | IllegalArgumentException ex) {
			this.err.println("Refused: " + ex.getMessage());
			this.exitCode = 1;
			return;
		}
		try {
			AdministratorBootstrap.Created created = this.bootstrap.createFirst(emails.getFirst(), names.getFirst(),
					password);
			this.out.println("The first Administrator exists. Add this secret to an authenticator app now;");
			this.out.println("it is shown only this once.");
			this.out.println("  Secret: " + created.secret());
			this.out.println("  URI:    " + created.otpauthUri());
			this.exitCode = 0;
		}
		catch (IllegalStateException | IllegalArgumentException ex) {
			this.err.println("Refused: " + ex.getMessage());
			this.exitCode = 1;
		}
		finally {
			Arrays.fill(password, '\0');
		}
	}

	@Override
	public int getExitCode() {
		return this.exitCode;
	}

	private static char[] firstLine(Path file) throws IOException {
		List<String> lines = Files.readAllLines(file);
		return lines.isEmpty() ? new char[0] : lines.getFirst().toCharArray();
	}

	private static char[] readPassword() {
		Console console = System.console();
		if (console != null) {
			char[] first = console.readPassword("Password for the first Administrator (12+ characters): ");
			char[] second = console.readPassword("The same password again: ");
			if (first == null || second == null || !Arrays.equals(first, second)) {
				throw new IllegalArgumentException("The two passwords differ");
			}
			Arrays.fill(second, '\0');
			return first;
		}
		String variable = System.getenv(PASSWORD_VARIABLE);
		return variable == null ? new char[0] : variable.toCharArray();
	}

}
