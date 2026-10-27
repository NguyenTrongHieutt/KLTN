package vn.vibefix.chat.internal;

import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import vn.vibefix.chat.internal.ChatAccess.Viewer;
import vn.vibefix.chat.internal.ChatConversation.MessagePageView;
import vn.vibefix.chat.internal.ChatConversation.MessageView;
import vn.vibefix.chat.internal.ChatConversation.SendRequest;
import vn.vibefix.chat.internal.Chats.Kind;
import vn.vibefix.chat.internal.SensitiveContent.Resolution;
import vn.vibefix.scanning.SensitiveText;
import vn.vibefix.shared.Access;
import vn.vibefix.shared.CurrentCaller;
import vn.vibefix.shared.Gate;

/**
 * JOB-8 "Trao đổi nhóm" (FR-77, FR-47, UX-DR25, UX-DR40, UX-DR80): the Job's Mentor and its Crew. The
 * Mentor and {@code ACTIVE} members read every message and write while the Chat is open; a
 * {@code FORMER} member reads only the messages inside their windows (AD-41). The
 * Customer and everyone else find nothing; an Administrator only reads, under a grant naming the
 * Crew Chat, audited. The tab finds the Chat by its Job, and the messages are addressed by the Chat.
 */
@RestController
@Access(Gate.SIGNED_IN)
class CrewChatController {

	/**
	 * The Crew Chat as JOB-8 and the tab show it.
	 * @param title the Job's title
	 * @param unread whether someone else wrote since the viewer last read; never for a former member
	 * @param readOnly closed by J9 or J10, or the viewer only reads it
	 * @param closed closed by J9 or J10 (FR-77)
	 */
	record CrewChatView(UUID chatId, String title, boolean unread, boolean readOnly, boolean closed) {
	}

	private final Chats chats;

	private final ChatAccess access;

	private final ChatConversation conversation;

	private final CurrentCaller callers;

	CrewChatController(Chats chats, ChatAccess access, ChatConversation conversation, CurrentCaller callers) {
		this.chats = chats;
		this.access = access;
		this.conversation = conversation;
		this.callers = callers;
	}

	@GetMapping("/api/v1/jobs/{jobId}/crew-chat")
	CrewChatView crewChat(@PathVariable UUID jobId) {
		Viewer viewer = this.access.participant(jobId, Kind.CREW, this.callers.get());
		boolean unread = viewer.windows() == null && this.chats.unread(viewer.chat().id(), viewer.accountId());
		return new CrewChatView(viewer.chat().id(), viewer.jobTitle(), unread, viewer.readOnly(), viewer.chat().closed());
	}

	/**
	 * The same page by the Job, for the Team Evidence Package (AD-20, AD-40): an escalated Team Issue's
	 * Administrator reads it under their grant, audited, without knowing the Chat's ID.
	 */
	@GetMapping("/api/v1/boards/{boardId}/crew-chat/messages")
	MessagePageView crewMessagesOfJob(@PathVariable("boardId") UUID jobId,
			@RequestParam(required = false) @Nullable UUID before) {
		return this.conversation.page(this.access.reader(jobId, Kind.CREW, this.callers.get()), before);
	}

	/** The newest 50 messages the viewer sees, or the 50 before {@code before} (UX-DR25). */
	@GetMapping("/api/v1/crew-chats/{crewChatId}/messages")
	MessagePageView crewMessages(@PathVariable UUID crewChatId, @RequestParam(required = false) @Nullable UUID before) {
		return this.conversation.page(this.access.crewReader(crewChatId, this.callers.get()), before);
	}

	/** Sends plain text after the FR-47 check (AD-18). */
	@PostMapping("/api/v1/crew-chats/{crewChatId}/messages")
	@ResponseStatus(HttpStatus.CREATED)
	MessageView sendCrewMessage(@PathVariable UUID crewChatId, @Valid @RequestBody SendRequest body) {
		Viewer viewer = this.access.crewParticipant(crewChatId, this.callers.get());
		return this.conversation.send(viewer, body.body(), body.sensitive());
	}

	/** Sends one attachment with an optional caption, both checked first (NFR-3, AD-21). */
	@PostMapping(path = "/api/v1/crew-chats/{crewChatId}/attachments", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	@ResponseStatus(HttpStatus.CREATED)
	MessageView attachCrewFile(@PathVariable UUID crewChatId, @RequestPart("file") MultipartFile file,
			@RequestParam(defaultValue = "") @Size(max = SensitiveText.MAX_LENGTH) String body,
			@RequestParam(required = false) @Nullable Resolution sensitive) {
		Viewer viewer = this.access.crewParticipant(crewChatId, this.callers.get());
		return this.conversation.attach(viewer, file, body, sensitive);
	}

	/** The viewer has read the Crew Chat up to now; the unread dot goes. */
	@PostMapping("/api/v1/crew-chats/{crewChatId}/read")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	void readCrewChat(@PathVariable UUID crewChatId) {
		Viewer viewer = this.access.crewParticipant(crewChatId, this.callers.get());
		this.chats.markRead(viewer.chat().id(), viewer.accountId());
	}

}
