package vn.vibefix.github;

import java.time.LocalDate;
import java.util.UUID;

import vn.vibefix.shared.DomainEvent;

/**
 * The runner's GitHub Actions minutes this month passed the alert share of their budget (AD-46, D1, R30, Story
 * 23.8b), once per month in Vietnam time: {@code admin} alerts every Administrator
 * ({@code github.runner_minutes_80}), as it does for the daily email budget.
 * @param month the month's first day, Vietnam time
 * @param used the minutes the month's runs have billed so far, each run rounded up to the minute
 * @param budget {@code vibefix.github.runner-minutes-budget}, 2,000 on GitHub Free
 */
public record GitHubRunnerMinutes(UUID eventId, LocalDate month, int used, int budget) implements DomainEvent {
}
