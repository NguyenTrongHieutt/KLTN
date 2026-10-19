package vn.vibefix.files.internal;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.servlet.http.HttpServletResponse;

import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import vn.vibefix.files.FileReadPolicy;
import vn.vibefix.files.StoredFile;
import vn.vibefix.shared.Access;
import vn.vibefix.shared.ApiException;
import vn.vibefix.shared.CurrentCaller;
import vn.vibefix.shared.ErrorCode;
import vn.vibefix.shared.Gate;

/**
 * The only way to a stored file's bytes (AD-20, AD-21): {@code files} asks the owning module's
 * {@link FileReadPolicy} first. An unknown or deleted file, a purpose without a policy, and a refusal
 * all answer {@code 404 NOT_FOUND}, so a caller cannot tell them apart. The bytes are an attachment
 * with {@code nosniff} and {@code Content-Security-Policy: sandbox}, never shown as a page of vibefix.
 */
@RestController
@Access(Gate.SIGNED_IN)
class FileController {

	static final String PATH = "/api/v1/files/{fileId}";

	private final JdbcStoredFiles files;

	private final Map<String, FileReadPolicy> policies;

	private final CurrentCaller caller;

	FileController(JdbcStoredFiles files, List<FileReadPolicy> policies, CurrentCaller caller) {
		this.files = files;
		this.caller = caller;
		Map<String, FileReadPolicy> byPurpose = new HashMap<>();
		for (FileReadPolicy policy : policies) {
			FileReadPolicy previous = byPurpose.putIfAbsent(policy.purpose(), policy);
			if (previous != null) {
				throw new IllegalStateException("File purpose " + policy.purpose() + " has two read policies: "
						+ previous.getClass().getName() + " and " + policy.getClass().getName());
			}
		}
		this.policies = Map.copyOf(byPurpose);
	}

	@GetMapping(PATH)
	ResponseEntity<Resource> download(@PathVariable UUID fileId, HttpServletResponse response) {
		StoredFile file = this.files.find(fileId).orElseThrow(FileController::notFound);
		FileReadPolicy policy = this.policies.get(file.purpose());
		if (policy == null || !this.files.exists(fileId) || !policy.mayRead(fileId, this.caller.get())) {
			throw notFound();
		}
		// Replaces the API's policy that SecurityHeadersFilter set: the bytes run nothing, anywhere.
		response.setHeader("Content-Security-Policy", "sandbox");
		return ResponseEntity.ok()
			.contentType(MediaType.parseMediaType(file.mediaType()))
			.contentLength(file.size())
			.header(HttpHeaders.CONTENT_DISPOSITION, FileNames.contentDisposition(file.fileName()))
			.header("X-Content-Type-Options", "nosniff")
			.body(new InputStreamResource(this.files.open(fileId)));
	}

	private static ApiException notFound() {
		return new ApiException(ErrorCode.NOT_FOUND);
	}

}
