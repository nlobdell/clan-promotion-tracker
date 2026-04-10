package com.clanpromotiontracker;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

final class PromotionEvaluator
{
	private static final List<RankRequirement> DEFAULT_LADDER = Collections.unmodifiableList(Arrays.asList(
		new RankRequirement("Thief", 0, 0L),
		new RankRequirement("Recruit", 1, 100_000L),
		new RankRequirement("Corporal", 2, 500_000L),
		new RankRequirement("Sergeant", 3, 1_000_000L),
		new RankRequirement("Lieutenant", 4, 2_000_000L),
		new RankRequirement("Captain", 5, 3_000_000L),
		new RankRequirement("General", 6, 8_000_000L),
		new RankRequirement("Officer", 9, 12_000_000L),
		new RankRequirement("Commander", 12, 20_000_000L),
		new RankRequirement("Colonel", 15, 25_000_000L),
		new RankRequirement("Brigadier", 17, 30_000_000L),
		new RankRequirement("Admiral", 20, 50_000_000L),
		new RankRequirement("Marshal", 24, 75_000_000L)
	));

	private PromotionEvaluator()
	{
	}

	static List<RankRequirement> getDefaultLadder()
	{
		return DEFAULT_LADDER;
	}

	static List<RankRequirement> resolveLadder(boolean useCustomRankLadder, String customRankLadder)
	{
		if (!useCustomRankLadder)
		{
			return DEFAULT_LADDER;
		}

		List<RankRequirement> custom = parseCustomLadder(customRankLadder);
		return custom.isEmpty() ? DEFAULT_LADDER : custom;
	}

	static List<RankRequirement> parseCustomLadder(String customRankLadder)
	{
		if (customRankLadder == null || customRankLadder.isBlank())
		{
			return Collections.emptyList();
		}

		List<RankRequirement> parsed = new ArrayList<>();
		for (String line : customRankLadder.split("\\R"))
		{
			String trimmed = line.trim();
			if (trimmed.isEmpty() || trimmed.startsWith("#"))
			{
				continue;
			}

			String[] parts = trimmed.split("\\|");
			if (parts.length != 3)
			{
				return Collections.emptyList();
			}

			try
			{
				String name = parts[0].trim();
				int months = Integer.parseInt(parts[1].trim());
				long xpRequired = parseXpMillions(parts[2].trim());
				parsed.add(new RankRequirement(name, months, xpRequired));
			}
			catch (NumberFormatException ex)
			{
				return Collections.emptyList();
			}
		}

		return parsed.size() < 2 ? Collections.emptyList() : Collections.unmodifiableList(parsed);
	}

	static PromotionDecision evaluate(
		String currentRank,
		LocalDate joinDate,
		Long xpGained,
		LocalDate today,
		List<RankRequirement> ladder,
		boolean approximateBaseline)
	{
		return evaluate(currentRank, joinDate, xpGained, today, ladder, approximateBaseline, false);
	}

	static PromotionDecision evaluate(
		String currentRank,
		LocalDate joinDate,
		Long xpGained,
		LocalDate today,
		List<RankRequirement> ladder,
		boolean approximateBaseline,
		boolean recommendRankSkips)
	{
		int monthsInClan = calculateWholeMonths(joinDate, today);
		int currentRankIndex = findRankIndex(currentRank, ladder);
		if (currentRankIndex < 0)
		{
			return new PromotionDecision(monthsInClan, null, PromotionStatus.UNKNOWN_RANK);
		}

		int nextRankIndex = currentRankIndex + 1;
		if (nextRankIndex >= ladder.size())
		{
			return new PromotionDecision(monthsInClan, null, PromotionStatus.NOT_READY);
		}

		RankRequirement nextRank = ladder.get(nextRankIndex);
		if (xpGained == null)
		{
			return new PromotionDecision(monthsInClan, nextRank.getName(), PromotionStatus.XP_NOT_FETCHED);
		}

		int targetRankIndex = nextRankIndex;
		if (recommendRankSkips)
		{
			for (int index = nextRankIndex; index < ladder.size(); index++)
			{
				RankRequirement candidate = ladder.get(index);
				if (isRankReady(monthsInClan, xpGained, candidate))
				{
					targetRankIndex = index;
				}
			}
		}

		RankRequirement targetRank = ladder.get(targetRankIndex);
		boolean ready = isRankReady(monthsInClan, xpGained, targetRank);
		if (!ready)
		{
			return new PromotionDecision(monthsInClan, nextRank.getName(), PromotionStatus.NOT_READY);
		}

		PromotionStatus status = approximateBaseline ? PromotionStatus.APPROXIMATE_BASELINE : PromotionStatus.READY;
		return new PromotionDecision(monthsInClan, targetRank.getName(), status);
	}

	private static boolean isRankReady(int monthsInClan, long xpGained, RankRequirement rank)
	{
		return monthsInClan >= rank.getMonthsRequired() && xpGained >= rank.getXpRequired();
	}

	static int calculateWholeMonths(LocalDate joinDate, LocalDate today)
	{
		if (joinDate == null || today == null)
		{
			return 0;
		}

		int months = (today.getYear() - joinDate.getYear()) * 12 + (today.getMonthValue() - joinDate.getMonthValue());
		return Math.max(0, months);
	}

	static int findRankIndex(String currentRank, List<RankRequirement> ladder)
	{
		if (currentRank == null || currentRank.isBlank())
		{
			return -1;
		}

		for (int i = 0; i < ladder.size(); i++)
		{
			if (ladder.get(i).getName().equalsIgnoreCase(currentRank.trim()))
			{
				return i;
			}
		}

		return -1;
	}

	static String formatXp(Long xp)
	{
		if (xp == null)
		{
			return "-";
		}

		return String.format(Locale.ENGLISH, "%.2fM", xp / 1_000_000d);
	}

	private static long parseXpMillions(String rawValue)
	{
		return new BigDecimal(rawValue)
			.multiply(BigDecimal.valueOf(1_000_000L))
			.setScale(0, RoundingMode.UNNECESSARY)
			.longValueExact();
	}
}
