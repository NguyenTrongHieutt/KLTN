package vn.vibefix.chat.internal;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import vn.vibefix.chat.internal.ChatAccess.Viewer;
import vn.vibefix.chat.internal.Chats.Attachment;
import vn.vibefix.chat.internal.Chats.Message;
import vn.vibefix.chat.internal.Chats.Page;
import vn.vibefix.chat.internal.SensitiveContent.Prepared;
import vn.vibefix.chat.internal.SensitiveContent.Resolution;
import vn.vibefix.files.FileKind;
import vn.vibefix.files.FileUpload;
import vn.vibefix.files.PreparedFile;
import vn.vibefix.files.StoredFiles;
import vn.vibefix.identity.CustomerNames;
import vn.vibefix.identity.MentorDirectory;
import vn.vibefix.identity.MentorDirectory.MentorCard;
import vn.vibefix.scanning.SensitiveText;
import vn.vibefix.shared.Access;
import vn.vibefix.shared.ApiException;
import vn.vibefix.shared.Caller;
import vn.vibefix.shared.CurrentCaller;
import vn.vibefix.shared.ErrorCode;
import vn.vibefix.shared.Gate;

/**
 * JOB-4 "Trò chuyện" (FR-46, FR-47, UX-DR25, UX-DR40): the Job's Customer and its Mentor read the
 * newest 50 messages and older pages, write plain text or one attachment after the FR-47 check, and
 * mark what they have read. Everyone else finds nothing; a granted Administrator only reads, audited.
 */
@RestController
@Access(Gate.SIGNED_IN)
class JobChatController {

	/** What an attachment may be (AD-21): images, PDFs, and plain text, at most 10 MB each. */
	static final Set<FileKind> ATTACHMENT_KINDS = Set.of(FileKind.IMAGE, FileKind.PDF, FileKind.TEXT);

	/**
	 * A message to send.
	 * @param sensitive {@code CHECK} first; {@code MASK} or {@code SEND} after the warning
	 */
	record SendRequest(@NotNull @Size(max = SensitiveText.MAX_LENGTH) String body, @Nullable Resolution sensitive) {
	}

	/** The Chat as a tab shows it: whether the other side wrote since the viewer last read. */
	record ChatView(UUID chatId, boolean unread) {
	}

	record AttachmentView(UUID fileId, String name, String mediaType, long size) {

		static @Nullable AttachmentView of(@Nullable Attachment attachment) {
			return attachment == null ? null
					: new AttachmentView(attachment.fileId(), attachment.name(), attachment.mediaType(), attachment.size());
		}

	}

	/**
	 * One message, plain text.
	 * @param mine written by the viewer
	 * @param maskedCredentials how many likely credentials "Che và gửi" masked
	 */
	record MessageView(UUID id, String author, boolean mine, String body, int maskedCredentials, int maskedPersonal,
			@Nullable AttachmentView attachment, Instant sentAt) {
	}

	/** Messages, oldest first, and whether older ones remain for "Xem tin cũ hơn". */
	record MessagePageView(List<MessageView> items, boolean hasOlder) {
	}

	private final Chats chats;

	private final ChatAccess access;

	private final SensitiveContent sensitive;

	private final StoredFiles files;

	private final CustomerNames customers;

	private final MentorDirectory mentors;

	private final CurrentCaller callers;

	JobChatController(Chats chats, ChatAccess access, SensitiveContent sensitive, StoredFiles files,
			CustomerNames customers, MentorDirectory mentors, CurrentCaller callers) {
		this.chats = chats;
		this.access = access;
		this.sensitive = sensitive;
		this.files = files;
		this.customers = customers;
		this.mentors = mentors;
		this.callers = callers;
	}

	@GetMapping("/api/v1/jobs/{jobId}/chat")
	ChatView chat(@PathVariable UUID jobId) {
		Viewer viewer = this.access.participant(jobId, this.callers.get());
		return new ChatView(viewer.chat().id(), this.chats.unread(viewer.chat().id(), viewer.accountId()));
	}

	/** The newest 50 messages, or the 50 before {@code before} (UX-DR25). */
	@GetMapping("/api/v1/jobs/{jobId}/chat/messages")
	MessagePageView messages(@PathVariable UUID jobId, @RequestParam(required = false) @Nullable UUID before) {
		Viewer viewer = this.access.reader(jobId, this.callers.get());
		Page page = this.chats.messages(viewer.chat().id(), before);
		Map<UUID, String> names = names(viewer);
		return new MessagePageView(page.items().stream().map(message -> view(message, viewer, names)).toList(),
				page.hasOlder());
	}

	/** Sends plain text after the FR-47 check (AD-18). */
	@PostMapping("/api/v1/jobs/{jobId}/chat/messages")
	@ResponseStatus(HttpStatus.CREATED)
	MessageView send(@PathVariable UUID jobId, @Valid @RequestBody SendRequest body) {
		Viewer viewer = this.access.participant(jobId, this.callers.get());
		String text = body.body().strip();
		if (text.isEmpty()) {
			throw new ApiException(ErrorCode.VALIDATION, ChatReason.MESSAGE_EMPTY);
		}
		Prepared prepared = this.sensitive.prepare(text, resolution(body.sensitive()));
		Message message = this.chats.post(viewer.chat(), viewer.accountId(), prepared, null);
		return view(message, viewer, names(viewer));
	}

	/** Sends one attachment with an optional caption, both checked first (FR-46, NFR-3, AD-21). */
	@PostMapping(path = "/api/v1/jobs/{jobId}/chat/attachments", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	@ResponseStatus(HttpStatus.CREATED)
	MessageView attach(@PathVariable UUID jobId, @RequestPart("file") MultipartFile file,
			@RequestParam(defaultValue = "") @Size(max = SensitiveText.MAX_LENGTH) String body,
			@RequestParam(required = false) @Nullable Resolution sensitive) {
		Caller caller = this.callers.get();
		Viewer viewer = this.access.participant(jobId, caller);
		Prepared prepared = this.sensitive.prepare(body.strip(), resolution(sensitive));
		byte[] content;
		try {
			content = file.getBytes();
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
		String name = file.getOriginalFilename() == null ? "" : file.getOriginalFilename();
		PreparedFile checked = this.files.prepare(new FileUpload(Chats.ATTACHMENT_PURPOSE, viewer.accountId(), name,
				content, ATTACHMENT_KINDS));
		Message message = this.chats.post(viewer.chat(), viewer.accountId(), prepared, checked);
		return view(message, viewer, names(viewer));
	}

	/** The viewer has read the Chat up to now; the unread dot goes. */
	@PostMapping("/api/v1/jobs/{jobId}/chat/read")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	void read(@PathVariable UUID jobId) {
		Viewer viewer = this.access.participant(jobId, this.callers.get());
		this.chats.markRead(viewer.chat().id(), viewer.accountId());
	}

	private Map<UUID, String> names(Viewer viewer) {
		Map<UUID, String> names = new HashMap<>();
		names.put(viewer.customerId(), this.customers.displayName(viewer.customerId()));
		this.mentors.cards(List.of(viewer.mentorId())).stream().findFirst().map(MentorCard::displayName)
			.ifPresent(name -> names.put(viewer.mentorId(), name));
		return names;
	}

	private static MessageView view(Message message, Viewer viewer, Map<UUID, String> names) {
		return new MessageView(message.id(), names.getOrDefault(message.authorId(), ""),
				viewer.participant() && message.authorId().equals(viewer.accountId()), message.body(),
				message.maskedCredentials(), message.maskedPersonal(), AttachmentView.of(message.attachment()),
				message.sentAt());
	}

	private static Resolution resolution(@Nullable Resolution resolution) {
		return resolution == null ? Resolution.CHECK : resolution;
	}

}
