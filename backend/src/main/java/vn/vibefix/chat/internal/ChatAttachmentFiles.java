package vn.vibefix.chat.internal;

import java.util.UUID;

import org.springframework.stereotype.Component;

import vn.vibefix.chat.internal.ChatAccess.Viewer;
import vn.vibefix.chat.internal.Chats.Chat;
import vn.vibefix.chat.internal.Chats.Message;
import vn.vibefix.files.FileReadPolicy;
import vn.vibefix.shared.ApiException;
import vn.vibefix.shared.Caller;

/**
 * Who downloads a chat attachment (AD-20, AD-41): whoever may read its message, a granted
 * Administrator included, whose download is audited like any read; a former Crew member reaches only
 * the files sent inside their windows.
 */
@Component
class ChatAttachmentFiles implements FileReadPolicy {

	private final Chats chats;

	private final ChatAccess access;

	ChatAttachmentFiles(Chats chats, ChatAccess access) {
		this.chats = chats;
		this.access = access;
	}

	@Override
	public String purpose() {
		return Chats.ATTACHMENT_PURPOSE;
	}

	@Override
	public boolean mayRead(UUID fileId, Caller caller) {
		Message message = this.chats.byAttachment(fileId).orElse(null);
		Chat chat = message == null ? null : this.chats.byId(message.chatId()).orElse(null);
		if (message == null || chat == null) {
			return false;
		}
		try {
			Viewer viewer = this.access.reader(chat.jobId(), chat.kind(), caller);
			return viewer.sees(message.sentAt());
		}
		catch (ApiException notFound) {
			return false;
		}
	}

}
