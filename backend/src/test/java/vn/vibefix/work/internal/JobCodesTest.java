package vn.vibefix.work.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;

import org.junit.jupiter.api.Test;

/** AD-33: the Job code of a Board's keys, from the Product name and unique per Product. */
class JobCodesTest {

	@Test
	void takesTheInitialsOfTheFirstThreeWordsWithoutMarks() {
		assertThat(JobCodes.base("Sổ đặt bàn Quán Mây")).isEqualTo("SDB");
		assertThat(JobCodes.base("Đặt lịch")).isEqualTo("DL");
		assertThat(JobCodes.base("Ứng dụng 2 bàn")).isEqualTo("UD2");
	}

	@Test
	void fallsBackToTheFirstLettersOrJob() {
		assertThat(JobCodes.base("Shopee")).isEqualTo("SHO");
		assertThat(JobCodes.base("3D Shop")).isEqualTo("DSH");
		assertThat(JobCodes.base("A")).isEqualTo("JOB");
		assertThat(JobCodes.base("！？")).isEqualTo("JOB");
		assertThat(JobCodes.base("")).isEqualTo("JOB");
	}

	@Test
	void addsTheNextFreeDigitWithinAProduct() {
		assertThat(JobCodes.unique("SDB", Set.of())).isEqualTo("SDB");
		assertThat(JobCodes.unique("SDB", Set.of("SDB"))).isEqualTo("SDB2");
		assertThat(JobCodes.unique("SDB", Set.of("SDB", "SDB2"))).isEqualTo("SDB3");
	}

}
