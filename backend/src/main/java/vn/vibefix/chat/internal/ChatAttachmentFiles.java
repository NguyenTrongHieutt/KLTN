package vn.vibefix.chat.internal;

import java.util.UUID;

import org.springframework.stereotype.Component;

import vn.vibefix.chat.internal.Chats.Chat;
import vn.vibefix.files.FileReadPolicy;
import vn.vibefix.shared.ApiException;
import vn.vibefix.shared.Caller;

/**
 * Who downloads a chat attachment (AD-20): whoever may read its Chat, a granted Administrator
 * included, whose download is audited like any read.
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
		Chat chat = this.chats.byAttachment(fileId).flatMap(message -> this.chats.byId(message.chatId())).orElse(null);
		if (chat == null) {
			return false;
		}
		try {
			this.access.reader(chat.jobId(), caller);
			return true;
		}
		catch (ApiException notFound) {
			return false;
		}
	}

}
