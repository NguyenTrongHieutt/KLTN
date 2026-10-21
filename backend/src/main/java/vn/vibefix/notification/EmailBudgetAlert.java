package vn.vibefix.notification;

import java.time.LocalDate;
import java.util.UUID;

import vn.vibefix.shared.DomainEvent;

/**
 * Today's SMTP budget crossed its alert share (AD-34), once per UTC day: {@code admin} alerts every
 * Administrator ({@code notification.email_budget_80}).
 */
public record EmailBudgetAlert(UUID eventId, LocalDate day, int sent, int budget) implements DomainEvent {
}
