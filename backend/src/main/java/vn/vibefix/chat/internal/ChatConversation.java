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
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

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
import vn.vibefix.identity.StudentDirectory;
import vn.vibefix.identity.StudentDirectory.StudentCard;
import vn.vibefix.scanning.SensitiveText;
import vn.vibefix.shared.ApiException;
import vn.vibefix.shared.ErrorCode;

/**
 * What both Chats do with messages (FR-46, FR-47, FR-77, UX-DR25, UX-DR40): page them newest first,
 * 50 at a time and only inside the viewer's windows, and store plain text or one attachment after the
 * FR-47 check. Authors are named as the viewer may see them: the Customer by their display name, the
 * Mentor by their directory name, a Crew member by their shown name or {@code @login} (FR-51).
 */
@Component
class ChatConversation {

	/** What an attachment may be (AD-21): images, PDFs, and plain text, at most 10 MB each. */
	static final Set<FileKind> ATTACHMENT_KINDS = Set.of(FileKind.IMAGE, FileKind.PDF, FileKind.TEXT);

	/**
	 * A message to send.
	 * @param sensitive {@code CHECK} first; {@code MASK} or {@code SEND} after the warning
	 */
	record SendRequest(@NotNull @Size(max = SensitiveText.MAX_LENGTH) String body, @Nullable Resolution sensitive) {
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

	private final SensitiveContent sensitive;

	private final StoredFiles files;

	private final CustomerNames customers;

	private final MentorDirectory mentors;

	private final StudentDirectory students;

	ChatConversation(Chats chats, SensitiveContent sensitive, StoredFiles files, CustomerNames customers,
			MentorDirectory mentors, StudentDirectory students) {
		this.chats = chats;
		this.sensitive = sensitive;
		this.files = files;
		this.customers = customers;
		this.mentors = mentors;
		this.students = students;
	}

	/** The newest 50 messages the viewer sees, or the 50 before {@code before} (UX-DR25). */
	MessagePageView page(Viewer viewer, @Nullable UUID before) {
		Page page = this.chats.messages(viewer.chat().id(), before, viewer.windows());
		Map<UUID, String> names = names(viewer);
		return new MessagePageView(page.items().stream().map(message -> view(message, viewer, names)).toList(),
				page.hasOlder());
	}

	/** One message, as the viewer reads it (FR-69). */
	MessageView one(Viewer viewer, Message message) {
		return view(message, viewer, names(viewer));
	}

	/** Sends plain text after the FR-47 check (AD-18). */
	MessageView send(Viewer viewer, String body, @Nullable Resolution resolution) {
		ChatAccess.writing(viewer);
		String text = body.strip();
		if (text.isEmpty()) {
			throw new ApiException(ErrorCode.VALIDATION, ChatReason.MESSAGE_EMPTY);
		}
		Prepared prepared = this.sensitive.prepare(text, resolution(resolution));
		return view(this.chats.post(viewer.chat(), viewer.accountId(), prepared, null), viewer, names(viewer));
	}

	/** Sends one attachment with an optional caption, both checked first (FR-46, NFR-3, AD-21). */
	MessageView attach(Viewer viewer, MultipartFile file, String body, @Nullable Resolution resolution) {
		ChatAccess.writing(viewer);
		Prepared prepared = this.sensitive.prepare(body.strip(), resolution(resolution));
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
		return view(this.chats.post(viewer.chat(), viewer.accountId(), prepared, checked), viewer, names(viewer));
	}

	private Map<UUID, String> names(Viewer viewer) {
		Map<UUID, String> names = new HashMap<>();
		if (viewer.customerId() != null) {
			names.put(viewer.customerId(), this.customers.displayName(viewer.customerId()));
		}
		this.mentors.cards(List.of(viewer.mentorId())).stream().findFirst().map(MentorCard::displayName)
			.ifPresent(name -> names.put(viewer.mentorId(), name));
		if (!viewer.crew().isEmpty()) {
			for (StudentCard card : this.students.cards(viewer.crew())) {
				names.put(card.accountId(), card.name() != null ? card.name() : "@" + card.githubLogin());
			}
		}
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
