package vn.vibefix.shared.internal.sandbox;

import java.util.UUID;

import org.springframework.boot.health.autoconfigure.contributor.ConditionalOnEnabledHealthIndicator;
import org.springframework.boot.health.contributor.AbstractHealthIndicator;
import org.springframework.boot.health.contributor.Health;
import org.springframework.stereotype.Component;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import vn.vibefix.shared.SandboxJobs;

/**
 * {@code /actuator/health/sandbox} (AD-32): sends a {@code HEALTH} job with a fresh nonce. UP only
 * when the job comes back with that nonce, zero secret files and {@code datasourceConfigured}
 * {@code false}; anything else, or an unreachable sandbox, is DOWN. Details are never shown. It
 * checks nothing else about isolation: deploy/smoke.sh asserts the network, mounts and limits.
 * <p>
 * Disabled with {@code management.health.sandbox.enabled=false} (integration tests run no sandbox).
 */
@Component
@ConditionalOnEnabledHealthIndicator("sandbox")
class SandboxHealthIndicator extends AbstractHealthIndicator {

	private final SandboxJobs jobs;

	SandboxHealthIndicator(SandboxJobs jobs) {
		super("Sandbox health check failed");
		this.jobs = jobs;
	}

	@Override
	protected void doHealthCheck(Health.Builder builder) {
		String nonce = UUID.randomUUID().toString();
		JsonNode output = this.jobs
			.run(HealthJobHandler.TYPE, JsonNodeFactory.instance.objectNode().put(HealthJobHandler.NONCE, nonce))
			.output();
		boolean echoed = nonce.equals(output.path(HealthJobHandler.NONCE).asString(""));
		boolean noSecrets = output.path(HealthJobHandler.SECRET_FILES).asLong(-1) == 0;
		JsonNode datasource = output.path(HealthJobHandler.DATASOURCE_CONFIGURED);
		boolean noDataSource = datasource.isBoolean() && !datasource.booleanValue();
		if (echoed && noSecrets && noDataSource) {
			builder.up();
		}
		else {
			builder.down()
				.withDetail("nonceEchoed", echoed)
				.withDetail("noSecretFiles", noSecrets)
				.withDetail("noDatasource", noDataSource);
		}
	}

}
