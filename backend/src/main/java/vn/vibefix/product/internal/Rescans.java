package vn.vibefix.product.internal;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import vn.vibefix.product.internal.Products.Product;
import vn.vibefix.product.internal.Products.ProductProperties;
import vn.vibefix.scanning.ScanPurpose;
import vn.vibefix.scanning.ScanTarget;
import vn.vibefix.scanning.Scans;
import vn.vibefix.scanning.ShaRange;
import vn.vibefix.shared.ApiException;
import vn.vibefix.shared.ErrorCode;
import vn.vibefix.shared.RateLimitedException;

/**
 * CUS-4 "Kiểm tra lại" (FR-8, FR-65, AD-34): both scans again over the whole history, at most
 * {@code vibefix.product.rescans-per-day} a Vietnam day per Product, counting the clean-repository
 * candidates of the same day, and never while a scan is still waiting, running, or retrying.
 */
@Component
class Rescans {

	private final Products products;

	private final Scans scans;

	private final ProductProperties properties;

	private final Clock clock;

	Rescans(Products products, Scans scans, ProductProperties properties, Clock clock) {
		this.products = products;
		this.scans = scans;
		this.properties = properties;
		this.clock = clock;
	}

	/**
	 * @return the new scan run
	 * @throws ApiException {@code NOT_FOUND} for a Product that is not the Customer's;
	 * {@code CONFLICT SCAN_IN_PROGRESS}; {@code RATE_LIMITED SCAN_LIMIT}
	 */
	@Transactional
	UUID request(UUID customerId, UUID productId) {
		Product product = this.products.find(productId)
			.filter(found -> found.customerId().equals(customerId))
			.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
		boolean busy = this.scans.latest(productId)
			.map(scan -> scan.status().inProgress() || scan.status() == vn.vibefix.scanning.ScanStatus.RETRYING)
			.orElse(false);
		if (busy) {
			throw new ApiException(ErrorCode.CONFLICT, ProductReason.SCAN_IN_PROGRESS);
		}
		requireAllowance(productId);
		return this.scans.requestScan(new ScanTarget(productId, customerId, product.name(), product.repositoryFullName(),
				product.repositoryId()), ShaRange.wholeHistory(), ScanPurpose.RESCAN);
	}

	/**
	 * Refuses past the day's allowance of on-demand scans: re-scans and clean-repository candidates.
	 * @throws RateLimitedException {@code SCAN_LIMIT}, retrying at the next Vietnam midnight
	 */
	void requireAllowance(UUID productId) {
		Instant now = this.clock.instant();
		Instant today = LocalDate.ofInstant(now, Products.VIETNAM).atStartOfDay(Products.VIETNAM).toInstant();
		int used = this.scans.requestedSince(productId, today,
				EnumSet.of(ScanPurpose.RESCAN, ScanPurpose.CLEAN_REPOSITORY));
		if (used >= this.properties.rescansPerDay()) {
			throw new RateLimitedException(
					Duration.between(now, today.atZone(Products.VIETNAM).plusDays(1).toInstant()), ProductReason.SCAN_LIMIT);
		}
	}

}
