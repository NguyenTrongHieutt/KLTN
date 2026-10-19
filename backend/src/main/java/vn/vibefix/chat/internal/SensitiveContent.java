package vn.vibefix.chat.internal;

import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import vn.vibefix.scanning.SensitiveText;
import vn.vibefix.scanning.SensitiveText.Kind;
import vn.vibefix.scanning.SensitiveText.Result;
import vn.vibefix.shared.ApiException;
import vn.vibefix.shared.ErrorCode;

/**
 * FR-47's warning before a chat message is stored, for the Job Chat and the Crew Chat alike: with
 * {@code CHECK}, a likely credential or bulk personal data refuses the message with the masked
 * matches; the sender then masks it ("Che và gửi"), edits it, or sends it as written ("Vẫn gửi").
 */
@Component
class SensitiveContent {

	/** What the sender chose. */
	enum Resolution {

		/** Check first; refuse with the matches when there are any. */
		CHECK,

		/** Store the masked text and keep no original. */
		MASK,

		/** Store the text as written, marked as sent despite the warning. */
		SEND

	}

	/** The text to store and what was masked or overridden. */
	record Prepared(String body, int maskedCredentials, int maskedPersonal, boolean sentDespiteWarning) {
	}

	/** One masked match, as the warning dialog lists it. */
	record MatchView(Kind kind, String type, String masked) {
	}

	private final SensitiveText check;

	SensitiveContent(SensitiveText check) {
		this.check = check;
	}

	/**
	 * @throws ApiException {@code GUARD_FAILED SENSITIVE_CONTENT} with {@code matches} under
	 * {@code CHECK} when the check finds something; nothing is stored
	 */
	Prepared prepare(String body, Resolution resolution) {
		if (body.isEmpty()) {
			return new Prepared(body, 0, 0, false);
		}
		Result result = this.check.check(body);
		if (!result.found()) {
			return new Prepared(body, 0, 0, false);
		}
		return switch (resolution) {
			case CHECK -> {
				List<MatchView> matches = result.matches()
					.stream()
					.map(match -> new MatchView(match.kind(), match.type(), match.masked()))
					.toList();
				throw new ApiException(ErrorCode.GUARD_FAILED, ChatReason.SENSITIVE_CONTENT, Map.of("matches", matches));
			}
			case MASK -> new Prepared(result.maskedText(), (int) result.count(Kind.CREDENTIAL),
					(int) result.count(Kind.PERSONAL_DATA), false);
			case SEND -> new Prepared(body, 0, 0, true);
		};
	}

}
