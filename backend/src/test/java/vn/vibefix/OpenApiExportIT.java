package vn.vibefix;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import vn.vibefix.support.IntegrationTest;

/**
 * Writes the springdoc OpenAPI document to {@code frontend/src/api/openapi.json} (AD-23), from
 * which the frontend build generates its typed client. The file is committed. When the document
 * differs from the committed file, this test rewrites the file and fails, so a stale contract never
 * passes {@code verify}; commit the regenerated file and run again.
 */
class OpenApiExportIT extends IntegrationTest {

	static final Path OPENAPI_JSON = Path.of("..", "frontend", "src", "api", "openapi.json");

	@Autowired
	private MockMvc mvc;

	@Test
	void exportsTheApiContractForTheFrontend() throws Exception {
		String body = this.mvc.perform(get("/v3/api-docs"))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString(StandardCharsets.UTF_8);
		JsonMapper mapper = JsonMapper.builder().build();
		JsonNode document = mapper.readTree(body);

		assertThat(document.path("paths").has("/api/v1/environment")).isTrue();
		assertThat(document.at("/components/schemas/EnvironmentResponse/required").toString())
			.as("record components are required unless @Nullable")
			.isEqualTo("[\"name\"]");

		assertThat(OPENAPI_JSON.getParent()).as("frontend/src/api must exist").isDirectory();
		String pretty = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(document).replace("\r\n", "\n")
				+ "\n";
		String committed = Files.exists(OPENAPI_JSON)
				? Files.readString(OPENAPI_JSON, StandardCharsets.UTF_8).replace("\r\n", "\n") : "";
		Files.writeString(OPENAPI_JSON, pretty, StandardCharsets.UTF_8);
		assertThat(pretty)
			.withFailMessage("frontend/src/api/openapi.json was stale and has been regenerated; commit it and run again")
			.isEqualTo(committed);
	}

}
