package com.clanpromotiontracker;

import java.text.NumberFormat;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;

final class PrettyExporter
{
	private static final DateTimeFormatter TIMESTAMP_FORMAT =
		DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z", Locale.ENGLISH);

	private PrettyExporter()
	{
	}

	static String toMarkdown(List<PromotionRecord> records)
	{
		return toMarkdown(records, ZonedDateTime.now());
	}

	static String toMarkdown(List<PromotionRecord> records, ZonedDateTime generatedAt)
	{
		List<PromotionRecord> safeRecords = records == null ? Collections.emptyList() : records;
		ZonedDateTime timestamp = generatedAt == null ? ZonedDateTime.now() : generatedAt;

		long readyCount = safeRecords.stream().filter(record -> record.getStatus() == PromotionStatus.READY).count();
		long approximateCount = safeRecords.stream().filter(record -> record.getStatus() == PromotionStatus.APPROXIMATE_BASELINE).count();
		long notReadyCount = safeRecords.stream().filter(record -> record.getStatus() == PromotionStatus.NOT_READY).count();
		long xpMissingCount = safeRecords.stream().filter(record -> record.getStatus() == PromotionStatus.XP_NOT_FETCHED).count();
		long unknownRankCount = safeRecords.stream().filter(record -> record.getStatus() == PromotionStatus.UNKNOWN_RANK).count();
		long noWomMatchCount = safeRecords.stream().filter(record -> record.getStatus() == PromotionStatus.NO_WOM_MATCH).count();

		StringBuilder builder = new StringBuilder(8_192);
		builder.append("# Clan Promotion Tracker Report\n\n");
		builder.append("Generated: ").append(TIMESTAMP_FORMAT.format(timestamp)).append('\n');
		builder.append("Members: ").append(safeRecords.size()).append('\n');
		builder.append("Ready: ").append(readyCount).append('\n');
		builder.append("Approximate baseline: ").append(approximateCount).append('\n');
		builder.append("Not ready: ").append(notReadyCount).append('\n');
		builder.append("XP not fetched: ").append(xpMissingCount).append('\n');
		builder.append("Unknown rank: ").append(unknownRankCount).append('\n');
		builder.append("Not in WOM group: ").append(noWomMatchCount).append("\n\n");

		appendSection(builder, "Promotion Recommendations", safeRecords, PromotionRecord::isActionable);
		appendSection(builder, "Not Ready", safeRecords, record -> record.getStatus() == PromotionStatus.NOT_READY);
		appendSection(builder, "XP Not Fetched", safeRecords, record -> record.getStatus() == PromotionStatus.XP_NOT_FETCHED);
		appendSection(builder, "Unknown Rank", safeRecords, record -> record.getStatus() == PromotionStatus.UNKNOWN_RANK);
		appendSection(builder, "Not In WOM Group", safeRecords, record -> record.getStatus() == PromotionStatus.NO_WOM_MATCH);
		return builder.toString();
	}

	private static void appendSection(
		StringBuilder builder,
		String title,
		List<PromotionRecord> records,
		Predicate<PromotionRecord> filter)
	{
		builder.append("## ").append(title).append("\n\n");
		builder.append("| Username | Current Rank | Next Rank | Months | XP Gained | Join Date | Baseline | Current XP | Status |\n");
		builder.append("| --- | --- | --- | ---: | ---: | --- | --- | ---: | --- |\n");

		boolean wroteRows = false;
		for (PromotionRecord record : records)
		{
			if (!filter.test(record))
			{
				continue;
			}

			builder.append("| ")
				.append(cell(record.getUsername())).append(" | ")
				.append(cell(record.getCurrentRank())).append(" | ")
				.append(cell(orDash(record.getNextRankCandidate()))).append(" | ")
				.append(record.getMonthsInClan()).append(" | ")
				.append(cell(formatXp(record.getXpGained()))).append(" | ")
				.append(cell(orDash(record.getJoinDate() == null ? null : record.getJoinDate().toString()))).append(" | ")
				.append(cell(formatBaseline(record))).append(" | ")
				.append(cell(formatXp(record.getCurrentXp()))).append(" | ")
				.append(cell(record.getStatus().getDisplayName())).append(" |\n");
			wroteRows = true;
		}

		if (!wroteRows)
		{
			builder.append("| - | - | - | - | - | - | - | - | No members |\n");
		}

		builder.append('\n');
	}

	private static String formatBaseline(PromotionRecord record)
	{
		if (record.getBaselineDate() == null)
		{
			return "-";
		}

		String suffix = record.isApproximateBaseline() ? " (approx)" : "";
		return record.getBaselineDate() + suffix + ", XP " + formatXp(record.getBaselineXp());
	}

	private static String formatXp(Long value)
	{
		if (value == null)
		{
			return "-";
		}

		NumberFormat formatter = NumberFormat.getIntegerInstance(Locale.ENGLISH);
		formatter.setGroupingUsed(true);
		String full = formatter.format(value);
		String compact = PromotionEvaluator.formatXp(value);
		return full.equals(compact) ? full : full + " (" + compact + ")";
	}

	private static String orDash(String value)
	{
		return value == null || value.isBlank() ? "-" : value;
	}

	private static String cell(String value)
	{
		if (value == null || value.isBlank())
		{
			return "-";
		}

		return value.trim()
			.replace("|", "\\|")
			.replace('\n', ' ')
			.replace('\r', ' ');
	}
}
