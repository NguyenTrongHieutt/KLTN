package vn.vibefix.scanning.internal;

import java.time.Duration;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The caps of one history scan (AD-32, SG-14, Seed limits), sent with each job so the sandbox never
 * reads them from the platform. None may exceed the seed limit, whatever the job is sent.
 * @param transferBytes the clone aborts past this many bytes fetched
 * @param commits commits walked
 * @param blobs distinct files read
 * @param decompressedBytes the total size of the files read
 * @param blobBytes a larger file is not scanned and is listed as "chưa kiểm tra được"
 * @param lineLength a longer line is scanned in overlapping pieces of this length
 * @param timeout the whole job, clone included
 */
record ScanLimits(long transferBytes, int commits, int blobs, long decompressedBytes, int blobBytes, int lineLength,
		Duration timeout) {

	private static final long SEED_TRANSFER_BYTES = 300L * 1024 * 1024;

	private static final int SEED_COMMITS = 50_000;

	private static final int SEED_BLOBS = 200_000;

	private static final long SEED_DECOMPRESSED_BYTES = 1024L * 1024 * 1024;

	private static final int SEED_BLOB_BYTES = 1024 * 1024;

	private static final int SEED_LINE_LENGTH = 4096;

	private static final Duration SEED_TIMEOUT = Duration.ofMinutes(10);

	/** The seed limits: a 250 MB repository fits the 300 MB transfer, and the scan ends within 10 minutes. */
	static final ScanLimits DEFAULT = new ScanLimits(SEED_TRANSFER_BYTES, SEED_COMMITS, SEED_BLOBS,
			SEED_DECOMPRESSED_BYTES, SEED_BLOB_BYTES, SEED_LINE_LENGTH, SEED_TIMEOUT);

	ScanLimits {
		if (transferBytes < 1 || transferBytes > SEED_TRANSFER_BYTES || commits < 1 || commits > SEED_COMMITS
				|| blobs < 1 || blobs > SEED_BLOBS || decompressedBytes < 1 || decompressedBytes > SEED_DECOMPRESSED_BYTES
				|| blobBytes < 1 || blobBytes > SEED_BLOB_BYTES || lineLength < 256 || lineLength > SEED_LINE_LENGTH
				|| timeout.isNegative() || timeout.isZero() || timeout.compareTo(SEED_TIMEOUT) > 0) {
			throw new IllegalArgumentException("Scan limits must be positive and within the seed limits");
		}
	}

	void write(ObjectNode node) {
		node.put("transferBytes", this.transferBytes);
		node.put("commits", this.commits);
		node.put("blobs", this.blobs);
		node.put("decompressedBytes", this.decompressedBytes);
		node.put("blobBytes", this.blobBytes);
		node.put("lineLength", this.lineLength);
		node.put("timeoutSeconds", this.timeout.toSeconds());
	}

	static ScanLimits read(JsonNode node) {
		if (!node.isObject()) {
			throw new IllegalArgumentException("limits must be an object");
		}
		return new ScanLimits(number(node, "transferBytes"), (int) number(node, "commits"), (int) number(node, "blobs"),
				number(node, "decompressedBytes"), (int) number(node, "blobBytes"), (int) number(node, "lineLength"),
				Duration.ofSeconds(number(node, "timeoutSeconds")));
	}

	private static long number(JsonNode node, String field) {
		JsonNode value = node.path(field);
		if (!value.isIntegralNumber()) {
			throw new IllegalArgumentException("limits." + field + " must be a whole number");
		}
		return value.longValue();
	}

}
