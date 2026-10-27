package vn.vibefix.identity.internal;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import vn.vibefix.shared.Access;
import vn.vibefix.shared.ApiException;
import vn.vibefix.shared.Caller;
import vn.vibefix.shared.CurrentCaller;
import vn.vibefix.shared.ErrorCode;
import vn.vibefix.shared.Gate;

/**
 * ALL-2 "Dữ liệu của tôi" (NFR-9, NFR-11, AD-24): a Customer, a Mentor, or a Student downloads their data and
 * requests their account's deletion; the page shows the request and what keeps it waiting. Never an
 * Administrator.
 */
@RestController
@Access(Gate.SIGNED_IN)
class DataRequestController {

	/** @param jobId the Job a blocker concerns, when there is one */
	record ErasureBlockerView(String module, String reason, @Nullable UUID jobId) {
	}

	/**
	 * @param tokenKept a Customer token is kept, so vibefix revokes its GitHub grant at the erasure; otherwise the
	 * page asks the person to revoke vibefix in their GitHub settings
	 */
	record MyDataView(@Nullable Instant deletionRequestedAt, @Nullable Instant deletionCompletedAt,
			List<ErasureBlockerView> blockers, boolean tokenKept) {
	}

	private final DataRequests requests;

	private final CurrentCaller callers;

	private final Clock clock;

	DataRequestController(DataRequests requests, CurrentCaller callers, Clock clock) {
		this.requests = requests;
		this.callers = callers;
		this.clock = clock;
	}

	@GetMapping("/api/v1/account/data")
	MyDataView data() {
		return view(this.requests.state(person()));
	}

	/** "Tải dữ liệu của tôi": one JSON download, audited. */
	@GetMapping("/api/v1/account/data/export")
	ResponseEntity<byte[]> export() {
		UUID account = person();
		String name = "vibefix-du-lieu-" + LocalDate.ofInstant(this.clock.instant(), StudentVerifications.VIETNAM) + ".json";
		return ResponseEntity.ok()
			.contentType(MediaType.APPLICATION_JSON)
			.header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(name).build().toString())
			.header(HttpHeaders.CACHE_CONTROL, "no-store")
			.body(this.requests.export(account));
	}

	/** "Yêu cầu xóa tài khoản". */
	@PostMapping("/api/v1/account/deletion-request")
	@ResponseStatus(HttpStatus.CREATED)
	MyDataView requestDeletion() {
		return view(this.requests.request(person()));
	}

	private UUID person() {
		Caller caller = this.callers.get();
		if (caller.isAdministrator()) {
			throw new ApiException(ErrorCode.NOT_FOUND);
		}
		return caller.requireAccountId();
	}

	private static MyDataView view(DataRequests.State state) {
		return new MyDataView(state.requestedAt(), state.completedAt(),
				state.blockers().stream().map(blocker -> new ErasureBlockerView(blocker.module(), blocker.reason(), blocker.jobId())).toList(),
				state.tokenKept());
	}

}
