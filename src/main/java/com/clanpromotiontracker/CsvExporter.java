package com.clanpromotiontracker;

import java.time.LocalDate;
import java.util.List;
import java.util.StringJoiner;

final class CsvExporter
{
	private CsvExporter()
	{
	}

	static String toCsv(List<PromotionRecord> records)
	{
		StringBuilder builder = new StringBuilder();
		builder
			.append("username,current_rank,join_date,months_in_clan,baseline_date,baseline_xp,current_xp,xp_gained,next_rank_candidate,status,approximate_baseline")
			.append('\n');

		for (PromotionRecord record : records)
		{
			StringJoiner joiner = new StringJoiner(",");
			joiner.add(escape(record.getUsername()));
			joiner.add(escape(record.getCurrentRank()));
			joiner.add(escape(formatDate(record.getJoinDate())));
			joiner.add(Integer.toString(record.getMonthsInClan()));
			joiner.add(escape(formatDate(record.getBaselineDate())));
			joiner.add(formatLong(record.getBaselineXp()));
			joiner.add(formatLong(record.getCurrentXp()));
			joiner.add(formatLong(record.getXpGained()));
			joiner.add(escape(record.getNextRankCandidate()));
			joiner.add(escape(record.getStatus().getDisplayName()));
			joiner.add(Boolean.toString(record.isApproximateBaseline()));
			builder.append(joiner).append('\n');
		}

		return builder.toString();
	}

	private static String formatDate(LocalDate value)
	{
		return value == null ? "" : value.toString();
	}

	private static String formatLong(Long value)
	{
		return value == null ? "" : Long.toString(value);
	}

	private static String escape(String value)
	{
		if (value == null)
		{
			return "";
		}

		if (!value.contains(",") && !value.contains("\"") && !value.contains("\n"))
		{
			return value;
		}

		return '"' + value.replace("\"", "\"\"") + '"';
	}
}
