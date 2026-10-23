package vn.vibefix.admin.internal;

import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import vn.vibefix.shared.Access;
import vn.vibefix.shared.CurrentCaller;
import vn.vibefix.shared.Gate;

/**
 * "Báo cáo vi phạm" (FR-69, AD-34, UX-DR64): any signed-in user but an Administrator reports content or a user
 * they can see, with a reason, at most 10 a day; the SPA then reads "Quản trị viên sẽ xem xét và báo kết quả
 * cho bạn."
 */
@RestController
@Access(Gate.SIGNED_IN)
class AbuseReportController {

	/**
	 * @param kind {@code PRODUCT}, {@code JOB_POST}, {@code PAGE}, {@code RATING}, {@code JOB_CHAT_MESSAGE},
	 * {@code CREW_CHAT_MESSAGE}, {@code WORK_ITEM_NOTE}, or {@code USER}
	 * @param targetId the item; for a user, their account
	 * @param targetLogin for a user only, in place of {@code targetId}: the GitHub username their public profile
	 * shows, since a Student's profile never shows the account (PUB-4)
	 */
	record AbuseReportRequest(@NotBlank String kind, @Nullable UUID targetId, @Nullable @Size(max = 39) String targetLogin,
			@NotBlank @Size(max = 500) String reason) {
	}

	record AbuseReportFiledView(UUID id) {
	}

	private final AbuseReports reports;

	private final CurrentCaller callers;

	AbuseReportController(AbuseReports reports, CurrentCaller callers) {
		this.reports = reports;
		this.callers = callers;
	}

	@PostMapping("/api/v1/abuse-reports")
	@ResponseStatus(HttpStatus.CREATED)
	AbuseReportFiledView file(@Valid @RequestBody AbuseReportRequest body) {
		return new AbuseReportFiledView(
				this.reports.file(this.callers.get(), body.kind(), body.targetId(), body.targetLogin(), body.reason()));
	}

}
