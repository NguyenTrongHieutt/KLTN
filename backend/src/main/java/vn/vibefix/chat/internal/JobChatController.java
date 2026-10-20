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
 * JOB-4 "Trò chuyện" (FR-46, FR-47, UX-DR25, UX-DR40): the Job's Customer and its Mentor read the
 * newest 50 messages and older pages, write plain text or one attachment after the FR-47 check while
 * the Chat is open, and mark what they have read. Everyone else finds nothing; a granted
 * Administrator only reads, audited.
 */
@RestController
@Access(Gate.SIGNED_IN)
class JobChatController {

	/**
	 * The Chat as a tab shows it.
	 * @param unread whether the other side wrote since the viewer last read
	 * @param readOnly closed by J9 or J10 (FR-46)
	 */
	record ChatView(UUID chatId, boolean unread, boolean readOnly) {
	}

	private final Chats chats;

	private final ChatAccess access;

	private final ChatConversation conversation;

	private final CurrentCaller callers;

	JobChatController(Chats chats, ChatAccess access, ChatConversation conversation, CurrentCaller callers) {
		this.chats = chats;
		this.access = access;
		this.conversation = conversation;
		this.callers = callers;
	}

	@GetMapping("/api/v1/jobs/{jobId}/chat")
	ChatView chat(@PathVariable UUID jobId) {
		Viewer viewer = this.access.participant(jobId, Kind.JOB, this.callers.get());
		return new ChatView(viewer.chat().id(), this.chats.unread(viewer.chat().id(), viewer.accountId()), viewer.readOnly());
	}

	/** The newest 50 messages, or the 50 before {@code before} (UX-DR25). */
	@GetMapping("/api/v1/jobs/{jobId}/chat/messages")
	MessagePageView messages(@PathVariable UUID jobId, @RequestParam(required = false) @Nullable UUID before) {
		return this.conversation.page(this.access.reader(jobId, Kind.JOB, this.callers.get()), before);
	}

	/** Sends plain text after the FR-47 check (AD-18). */
	@PostMapping("/api/v1/jobs/{jobId}/chat/messages")
	@ResponseStatus(HttpStatus.CREATED)
	MessageView send(@PathVariable UUID jobId, @Valid @RequestBody SendRequest body) {
		Viewer viewer = this.access.participant(jobId, Kind.JOB, this.callers.get());
		return this.conversation.send(viewer, body.body(), body.sensitive());
	}

	/** Sends one attachment with an optional caption, both checked first (FR-46, NFR-3, AD-21). */
	@PostMapping(path = "/api/v1/jobs/{jobId}/chat/attachments", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	@ResponseStatus(HttpStatus.CREATED)
	MessageView attach(@PathVariable UUID jobId, @RequestPart("file") MultipartFile file,
			@RequestParam(defaultValue = "") @Size(max = SensitiveText.MAX_LENGTH) String body,
			@RequestParam(required = false) @Nullable Resolution sensitive) {
		Viewer viewer = this.access.participant(jobId, Kind.JOB, this.callers.get());
		return this.conversation.attach(viewer, file, body, sensitive);
	}

	/** The viewer has read the Chat up to now; the unread dot goes. */
	@PostMapping("/api/v1/jobs/{jobId}/chat/read")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	void read(@PathVariable UUID jobId) {
		Viewer viewer = this.access.participant(jobId, Kind.JOB, this.callers.get());
		this.chats.markRead(viewer.chat().id(), viewer.accountId());
	}

}
