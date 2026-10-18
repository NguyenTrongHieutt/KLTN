package vn.vibefix.shared.internal.sandbox;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;

import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import tools.jackson.databind.JsonNode;
import vn.vibefix.shared.SandboxJobException;
import vn.vibefix.shared.SandboxJobRequest;
import vn.vibefix.shared.SandboxJobResult;
import vn.vibefix.shared.SandboxJobs;
import vn.vibefix.shared.internal.CorrelationIdFilter;

/**
 * The application's side of the job channel (AD-32): {@code POST} {@value SandboxJobController#PATH}
 * on the sandbox worker at {@code vibefix.sandbox.url}, with {@code vibefix.sandbox.timeout} to
 * connect and to answer. The request's correlation ID goes along, so the worker logs under it.
 */
@Component
class HttpSandboxJobs implements SandboxJobs {

	private final RestClient client;

	HttpSandboxJobs(@Value("${vibefix.sandbox.url}") URI url, @Value("${vibefix.sandbox.timeout}") Duration timeout) {
		HttpClient httpClient = HttpClient.newBuilder()
			.version(HttpClient.Version.HTTP_1_1)
			.connectTimeout(timeout)
			.followRedirects(HttpClient.Redirect.NEVER)
			.build();
		JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
		requestFactory.setReadTimeout(timeout);
		this.client = RestClient.builder().baseUrl(url).requestFactory(requestFactory).build();
	}

	@Override
	public SandboxJobResult run(String type, JsonNode input) {
		String correlationId = MDC.get(CorrelationIdFilter.MDC_KEY);
		try {
			SandboxJobResult result = this.client.post()
				.uri(SandboxJobController.PATH)
				.contentType(MediaType.APPLICATION_JSON)
				.accept(MediaType.APPLICATION_JSON)
				.headers(headers -> {
					if (correlationId != null) {
						headers.set(CorrelationIdFilter.HEADER, correlationId);
					}
				})
				.body(new SandboxJobRequest(type, input))
				.retrieve()
				.body(SandboxJobResult.class);
			if (result == null || result.output() == null) {
				throw new SandboxJobException(type, "the sandbox answered without a result", null);
			}
			return result;
		}
		catch (RestClientResponseException ex) {
			throw new SandboxJobException(type, "the sandbox answered " + ex.getStatusCode().value(), ex);
		}
		catch (RestClientException ex) {
			throw new SandboxJobException(type, "the sandbox could not be reached in time", ex);
		}
	}

}
