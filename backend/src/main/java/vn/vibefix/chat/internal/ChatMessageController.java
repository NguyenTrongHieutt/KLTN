package vn.vibefix.chat.internal;

import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import vn.vibefix.chat.internal.ChatAccess.MessageRead;
import vn.vibefix.chat.internal.ChatConversation.MessageView;
import vn.vibefix.shared.Access;
import vn.vibefix.shared.CurrentCaller;
import vn.vibefix.shared.Gate;

/**
 * One chat message (FR-69, AD-20): for a person of its Chat who sees it, or the Administrator whose Abuse
 * Report (or Dispute or Team Issue) grant covers it, read only and audited. Everyone else finds nothing.
 */
@RestController
@Access(Gate.SIGNED_IN)
class ChatMessageController {

	private final ChatAccess access;

	private final ChatConversation conversation;

	private final CurrentCaller callers;

	ChatMessageController(ChatAccess access, ChatConversation conversation, CurrentCaller callers) {
		this.access = access;
		this.conversation = conversation;
		this.callers = callers;
	}

	@GetMapping("/api/v1/chat-messages/{messageId}")
	MessageView message(@PathVariable UUID messageId) {
		MessageRead read = this.access.messageReader(messageId, this.callers.get());
		return this.conversation.one(read.viewer(), read.message());
	}

}
