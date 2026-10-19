package vn.vibefix.shared.internal.sandbox;

import java.util.HashMap;
import java.util.Map;

import org.springframework.beans.factory.config.AutowireCapableBeanFactory;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.core.type.filter.AssignableTypeFilter;
import org.springframework.stereotype.Component;
import org.springframework.util.ClassUtils;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import vn.vibefix.shared.SandboxJobException;
import vn.vibefix.shared.SandboxJobHandler;
import vn.vibefix.shared.SandboxJobResult;
import vn.vibefix.shared.SandboxJobs;

/**
 * The dev stack's stand-in for the sandbox worker (Dev-first rule, epics.md): with
 * {@value #PROPERTY}{@code =true}, which {@code application-dev.yaml} sets, jobs run inside the
 * application instead of over HTTP. It finds every {@link SandboxJobHandler} under {@code vn.vibefix}
 * as the worker does, creates each with the bean factory, and gives it a copy of the input; refusals
 * and failures surface as {@link SandboxJobException}, as from {@link HttpSandboxJobs}.
 * <p>
 * It isolates nothing: the handler runs with the application's secrets and database. So it refuses
 * to start under {@code prod}, and Story 18.2 brings the worker back to the dev stack.
 */
@Component
@Primary
@ConditionalOnProperty(name = InProcessSandboxJobs.PROPERTY, havingValue = "true")
class InProcessSandboxJobs implements SandboxJobs {

	static final String PROPERTY = "vibefix.sandbox.in-process";

	private final Map<String, SandboxJobHandler> handlers;

	InProcessSandboxJobs(Environment environment, AutowireCapableBeanFactory beans) {
		if (environment.acceptsProfiles(Profiles.of("prod"))) {
			throw new IllegalStateException(PROPERTY + " runs untrusted content inside the application and is"
					+ " refused under prod; refusing to start");
		}
		ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
		scanner.addIncludeFilter(new AssignableTypeFilter(SandboxJobHandler.class));
		Map<String, SandboxJobHandler> byType = new HashMap<>();
		for (BeanDefinition candidate : scanner.findCandidateComponents("vn.vibefix")) {
			Class<?> type = ClassUtils.resolveClassName(candidate.getBeanClassName(),
					InProcessSandboxJobs.class.getClassLoader());
			SandboxJobHandler handler = (SandboxJobHandler) beans.createBean(type);
			SandboxJobHandler previous = byType.putIfAbsent(handler.type(), handler);
			if (previous != null) {
				throw new IllegalStateException("Sandbox job type " + handler.type() + " has two handlers: "
						+ previous.getClass().getName() + " and " + handler.getClass().getName());
			}
		}
		this.handlers = Map.copyOf(byType);
	}

	@Override
	public SandboxJobResult run(String type, JsonNode input) {
		SandboxJobHandler handler = this.handlers.get(type);
		if (handler == null) {
			throw new SandboxJobException(type, "no handler runs this type", null);
		}
		JsonNode copy = input == null || input.isNull() ? JsonNodeFactory.instance.objectNode() : input.deepCopy();
		JsonNode output;
		try {
			output = handler.handle(copy);
		}
		catch (IllegalArgumentException ex) {
			throw new SandboxJobException(type, "the job refused its input", ex);
		}
		catch (RuntimeException ex) {
			throw new SandboxJobException(type, "the job failed", ex);
		}
		if (output == null || output.isNull()) {
			throw new SandboxJobException(type, "the job returned no output", null);
		}
		return new SandboxJobResult(type, output.deepCopy());
	}

}
