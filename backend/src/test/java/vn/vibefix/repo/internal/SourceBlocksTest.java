package vn.vibefix.repo.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;

import org.junit.jupiter.api.Test;

import vn.vibefix.product.ProductSources;

/**
 * Story 23.5: every block {@code product}'s Source checks answer reaches Setup by name, so each one is a
 * {@link JobSetups.Block}, a {@link RepoReason} the re-confirmation answers with, and a plain reason in the
 * {@code repo.job_start_blocked} notification. Story 23.5d: Setup blocks on nothing else, so a retired block (the
 * re-link of GitHub) cannot linger in Setup or its texts.
 */
class SourceBlocksTest {

	@Test
	void everySourceBlockIsASetupBlockAReasonAndHasAPlainText() {
		assertThat(ProductSources.Block.values()).allSatisfy(block -> {
			JobSetups.Block setup = JobSetups.Block.valueOf(block.name());
			assertThat(Arrays.stream(RepoReason.values()).map(Enum::name)).as(block.name()).contains(block.name());
			assertThat(JobSetups.REASONS.get(setup)).as(block.name()).isNotBlank();
		});
	}

	@Test
	void setupBlocksOnlyOnTheSourceBlocks() {
		String[] sources = Arrays.stream(ProductSources.Block.values()).map(Enum::name).toArray(String[]::new);

		assertThat(Arrays.stream(JobSetups.Block.values()).map(Enum::name)).containsExactlyInAnyOrder(sources);
		assertThat(JobSetups.REASONS.keySet().stream().map(Enum::name)).containsExactlyInAnyOrder(sources);
	}

}
