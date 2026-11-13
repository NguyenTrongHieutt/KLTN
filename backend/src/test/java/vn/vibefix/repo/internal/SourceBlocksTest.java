package vn.vibefix.repo.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

import vn.vibefix.product.ProductSources;

/**
 * Story 23.5: every block {@code product}'s Source checks answer reaches Setup by name, so each one is a
 * {@link JobSetups.Block}, a {@link RepoReason} the re-confirmation answers with, and a plain reason in the
 * {@code repo.job_start_blocked} notification. Story 23.6b: Setup's own blocks are the copy run's on the Customer's
 * Actions ({@code EXCHANGE_REFUSED}, {@code RUN_FAILED}, {@code ACTIONS_QUOTA}), each with its plain reason too, so the
 * product blocks are a subset of the Setup blocks and a retired block cannot linger in Setup or its texts.
 */
class SourceBlocksTest {

	/** The copy run's blocks, Setup's own (Story 23.6b). */
	static final List<String> RUN_BLOCKS = List.of("EXCHANGE_REFUSED", "RUN_FAILED", "ACTIONS_QUOTA");

	@Test
	void everySourceBlockIsASetupBlockAReasonAndHasAPlainText() {
		assertThat(ProductSources.Block.values()).allSatisfy(block -> {
			JobSetups.Block setup = JobSetups.Block.valueOf(block.name());
			assertThat(Arrays.stream(RepoReason.values()).map(Enum::name)).as(block.name()).contains(block.name());
			assertThat(JobSetups.REASONS.get(setup)).as(block.name()).isNotBlank();
		});
	}

	@Test
	void theProductBlocksAreASubsetOfTheSetupBlocksAndTheRestAreTheRunsOwn() {
		List<String> sources = Arrays.stream(ProductSources.Block.values()).map(Enum::name).toList();
		List<String> setup = Arrays.stream(JobSetups.Block.values()).map(Enum::name).toList();

		assertThat(setup).containsAll(sources);
		assertThat(setup.stream().filter(block -> !sources.contains(block))).containsExactlyInAnyOrderElementsOf(RUN_BLOCKS);
		assertThat(JobSetups.REASONS.keySet().stream().map(Enum::name)).containsExactlyInAnyOrderElementsOf(setup);
		assertThat(JobSetups.REASONS.values()).allSatisfy(text -> assertThat(text).isNotBlank());
	}

	@Test
	void anExchangeRefusalTheCustomerFixesNamesItsOwnReasonAndAnyOtherTheGeneralOne() {
		assertThat(JobSetups.reason(JobSetups.Block.EXCHANGE_REFUSED, "blob")).isEqualTo(JobSetups.EXCHANGE_REASONS.get("blob"))
			.isNotEqualTo(JobSetups.REASONS.get(JobSetups.Block.EXCHANGE_REFUSED));
		assertThat(JobSetups.reason(JobSetups.Block.EXCHANGE_REFUSED, "run"))
			.isEqualTo(JobSetups.REASONS.get(JobSetups.Block.EXCHANGE_REFUSED));
		assertThat(JobSetups.reason(JobSetups.Block.RUN_FAILED, "blob")).isEqualTo(JobSetups.REASONS.get(JobSetups.Block.RUN_FAILED));
	}

}
