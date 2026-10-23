package vn.vibefix.chat.internal;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Component;

import vn.vibefix.chat.internal.Chats.Kind;
import vn.vibefix.shared.Caller;
import vn.vibefix.shared.ReportableContent;

/**
 * FR-69: a Job Chat or Crew Chat message is reportable by a person of its Chat who sees it. The queue reads
 * the Job's title, never the message's text.
 */
@Component
class ChatReportable implements ReportableContent {

	private final ChatAccess access;

	ChatReportable(ChatAccess access) {
		this.access = access;
	}

	@Override
	public Set<String> kinds() {
		return Set.of("JOB_CHAT_MESSAGE", "CREW_CHAT_MESSAGE");
	}

	@Override
	public Optional<Reported> reportable(String kind, UUID id, Caller caller) {
		Kind chat = switch (kind) {
			case "JOB_CHAT_MESSAGE" -> Kind.JOB;
			case "CREW_CHAT_MESSAGE" -> Kind.CREW;
			default -> null;
		};
		if (chat == null) {
			return Optional.empty();
		}
		return this.access.seenBy(id, caller)
			.filter(read -> read.viewer().chat().kind() == chat)
			.map(read -> new Reported(read.viewer().chat().jobId(), read.viewer().chat().productId(),
					read.message().authorId(), read.viewer().jobTitle()));
	}

}
