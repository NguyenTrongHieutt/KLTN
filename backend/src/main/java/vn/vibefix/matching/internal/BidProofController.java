package vn.vibefix.matching.internal;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import vn.vibefix.files.PreparedFile;
import vn.vibefix.identity.ActiveAccount;
import vn.vibefix.identity.GitHubGate;
import vn.vibefix.job.JobPosts.JobPostState;
import vn.vibefix.matching.internal.BidProofs.Upload;
import vn.vibefix.shared.Access;
import vn.vibefix.shared.ApiException;
import vn.vibefix.shared.Caller;
import vn.vibefix.shared.CurrentCaller;
import vn.vibefix.shared.ErrorCode;
import vn.vibefix.shared.Gate;

/**
 * FND-1's proof files (FR-13, AD-21, NFR-3): before "Gửi chào giá", a user with GitHub linked uploads an image or a
 * PDF for their bid on an Open Job Post they do not own and have not bid on (AD-19 new-work command), at most five
 * waiting at once; the uploader lists and removes their own waiting uploads, and the bid attaches them. Nobody else
 * finds them, and an Administrator, who holds no role in any Job, finds nothing (SG-31).
 */
@RestController
@Access(Gate.SIGNED_IN)
class BidProofController {

	/** An upload waiting for the user's bid, with its download (an attachment, AD-21). */
	record BidProofUploadView(UUID fileId, String fileName, long sizeBytes, Instant addedAt, String fileUrl) {
	}

	private final BidProofs proofs;

	private final Applications applications;

	private final GitHubGate gitHub;

	private final ActiveAccount gate;

	private final CurrentCaller callers;

	BidProofController(BidProofs proofs, Applications applications, GitHubGate gitHub, ActiveAccount gate,
			CurrentCaller callers) {
		this.proofs = proofs;
		this.applications = applications;
		this.gitHub = gitHub;
		this.gate = gate;
		this.callers = callers;
	}

	/** "Thêm tệp": an image or a PDF of at most the upload limit, checked before anything is kept (AD-21). */
	@PostMapping(path = "/api/v1/mentor/job-posts/{postId}/proof-files", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	@ResponseStatus(HttpStatus.CREATED)
	BidProofUploadView upload(@PathVariable UUID postId, @RequestPart("file") MultipartFile file) {
		UUID bidder = bidder();
		this.gitHub.requireGitHubLinked(bidder);
		JobPostState post = this.applications.requireBiddable(bidder, postId);
		byte[] content;
		try {
			content = file.getBytes();
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
		PreparedFile prepared = this.proofs.prepare(bidder,
				file.getOriginalFilename() == null ? "" : file.getOriginalFilename(), content);
		return view(this.proofs.add(bidder, post.jobId(), post.productId(), prepared));
	}

	/** FND-1: the user's uploads waiting for their bid on the Job, oldest first. */
	@GetMapping("/api/v1/mentor/job-posts/{postId}/proof-files")
	List<BidProofUploadView> pending(@PathVariable UUID postId) {
		UUID bidder = bidder();
		this.gitHub.requireGitHubLinked(bidder);
		return this.proofs.pending(postId, bidder).stream().map(BidProofController::view).toList();
	}

	/** Removes an upload no bid carries yet, and its file. */
	@DeleteMapping("/api/v1/mentor/job-posts/{postId}/proof-files/{fileId}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	void remove(@PathVariable UUID postId, @PathVariable UUID fileId) {
		UUID bidder = bidder();
		this.gate.requireActiveAccount(bidder);
		this.proofs.remove(bidder, postId, fileId);
	}

	private static BidProofUploadView view(Upload upload) {
		return new BidProofUploadView(upload.fileId(), upload.fileName(), upload.size(), upload.addedAt(),
				"/api/v1/files/" + upload.fileId());
	}

	/** A person's account: an Administrator holds no role in any Job, so it finds nothing (SG-31). */
	private UUID bidder() {
		Caller caller = this.callers.get();
		if (!caller.isUser()) {
			throw new ApiException(ErrorCode.NOT_FOUND);
		}
		return caller.requireAccountId();
	}

}
