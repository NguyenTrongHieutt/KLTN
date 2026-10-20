package vn.vibefix.work.internal;

import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import vn.vibefix.scanning.SensitiveText;
import vn.vibefix.scanning.SensitiveText.Match;
import vn.vibefix.scanning.SensitiveText.Result;
import vn.vibefix.shared.ApiException;
import vn.vibefix.shared.ErrorCode;

/**
 * FR-47 for the text of the Board (AD-18): Work Item fields, submission notes, and review comments.
 * Each text is checked; with {@code CHECK} any match refuses the save with every masked match, and with
 * {@code MASK} the masked text is stored and no original kept. There is no "send anyway" on the Board.
 */
@Component
class WorkTextCheck {

	/** What the writer chose after the FR-47 warning: check first, or store the masked text. */
	enum Resolution {
		CHECK, MASK
	}

	/** One masked match, as the warning dialog lists it. */
	record MatchView(SensitiveText.Kind kind, String type, String masked) {
	}

	private final SensitiveText check;

	WorkTextCheck(SensitiveText check) {
		this.check = check;
	}

	/** {@code text} with its matches masked, each match added to {@code matches}. */
	String masked(String text, List<MatchView> matches) {
		if (text.isEmpty()) {
			return text;
		}
		Result result = this.check.check(text);
		if (!result.found()) {
			return text;
		}
		for (Match match : result.matches()) {
			matches.add(new MatchView(match.kind(), match.type(), match.masked()));
		}
		return result.maskedText();
	}

	/**
	 * @throws ApiException {@code GUARD_FAILED SENSITIVE_CONTENT} with the matches when there are any
	 * and the writer has not chosen to mask them
	 */
	static void requireResolved(List<MatchView> matches, Resolution resolution) {
		if (!matches.isEmpty() && resolution == Resolution.CHECK) {
			throw new ApiException(ErrorCode.GUARD_FAILED, WorkReason.SENSITIVE_CONTENT, Map.of("matches", matches));
		}
	}

}
