package vn.vibefix.github.internal;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Base64;
import java.util.HexFormat;

import org.jspecify.annotations.Nullable;

import tools.jackson.databind.JsonNode;
import vn.vibefix.github.GitHubException;
import vn.vibefix.github.internal.GitHubProperties.AcceptedBlob;

/**
 * The accepted set of published workflow files (AD-45, {@code vibefix.github.exchange.accepted-blobs}): a
 * file is accepted when its blob, with CRLF line ends read as LF, is listed for its path and its end date
 * (Vietnam time) has not passed. The dispatch checks the target's file on its default branch before any
 * dispatch, the exchange checks the file at the run's {@code workflow_sha}, and the runner's read-back
 * checks the runner's copy workflow.
 */
final class AcceptedWorkflows {

	private static final ZoneId VIETNAM = ZoneId.of("Asia/Ho_Chi_Minh");

	/** What a workflow file read through the contents API is. */
	enum Verdict {

		ACCEPTED, MISSING, MISMATCH

	}

	/**
	 * A verdict, and the file's blob as read, CRLF normalized ({@code null} when there is no file).
	 */
	record Checked(Verdict verdict, @Nullable String blob) {

		boolean accepted() {
			return this.verdict == Verdict.ACCEPTED;
		}

	}

	private final GitHubProperties.Exchange exchange;

	private final Clock clock;

	AcceptedWorkflows(GitHubProperties properties, Clock clock) {
		this.exchange = properties.exchange();
		this.clock = clock;
	}

	/**
	 * {@code GET /repos/{repository}/contents/{path}?ref=…}'s answer against the accepted set: GitHub's own blob
	 * SHA, else the content's with CRLF read as LF. Only a 404, or an answer that is no file, is a missing file.
	 * @throws GitHubException carrying GitHub's status (0 for no answer) for any other answer outside 2xx: a read
	 * that failed proves nothing either way
	 */
	Checked check(String path, GitHubHttp.Answer contents) {
		if (contents.status() == 404) {
			return new Checked(Verdict.MISSING, null);
		}
		if (!contents.ok()) {
			throw new GitHubException(contents.status(), "GitHub did not answer the read of " + path + ": HTTP "
					+ contents.status());
		}
		JsonNode body = contents.body();
		if (!"file".equals(body.path("type").asString("file")) || !body.path("sha").isString()) {
			return new Checked(Verdict.MISSING, null);
		}
		String stored = body.path("sha").asString();
		if (accepts(path, stored)) {
			return new Checked(Verdict.ACCEPTED, stored);
		}
		String normalized = normalizedBlob(body);
		if (normalized != null && accepts(path, normalized)) {
			return new Checked(Verdict.ACCEPTED, normalized);
		}
		return new Checked(Verdict.MISMATCH, normalized == null ? stored : normalized);
	}

	/** Whether {@code blob} is listed for {@code path} and not past its end date. */
	boolean accepts(String path, String blob) {
		LocalDate today = LocalDate.ofInstant(this.clock.instant(), VIETNAM);
		for (AcceptedBlob accepted : this.exchange.acceptedBlobs()) {
			if (accepted.path().equals(path) && accepted.blob().equals(blob)
					&& (accepted.endsOn() == null || !today.isAfter(accepted.endsOn()))) {
				return true;
			}
		}
		return false;
	}

	/** The blob of the base64 content GitHub sent, CRLF read as LF; none when it sent no content. */
	private static @Nullable String normalizedBlob(JsonNode body) {
		if (!"base64".equals(body.path("encoding").asString("")) || !body.path("content").isString()) {
			return null;
		}
		try {
			byte[] bytes = Base64.getMimeDecoder().decode(body.path("content").asString());
			return blobSha(new String(bytes, StandardCharsets.UTF_8));
		}
		catch (IllegalArgumentException ex) {
			return null;
		}
	}

	/**
	 * {@code git hash-object} of a workflow file, its CRLF line ends read as LF: the blob SHA GitHub reports for
	 * the file once committed with LF endings.
	 */
	static String blobSha(String content) {
		byte[] bytes = content.replace("\r\n", "\n").getBytes(StandardCharsets.UTF_8);
		try {
			MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
			sha1.update(("blob " + bytes.length + "\0").getBytes(StandardCharsets.US_ASCII));
			return HexFormat.of().formatHex(sha1.digest(bytes));
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException("SHA-1 is missing", ex);
		}
	}

}
