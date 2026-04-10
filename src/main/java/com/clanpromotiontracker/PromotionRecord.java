package com.clanpromotiontracker;

import java.time.LocalDate;

final class PromotionRecord
{
	private final String username;
	private final String normalizedUsername;
	private final String currentRank;
	private final LocalDate joinDate;
	private final int monthsInClan;
	private final LocalDate baselineDate;
	private final Long baselineXp;
	private final Long currentXp;
	private final Long xpGained;
	private final String nextRankCandidate;
	private final PromotionStatus status;
	private final boolean approximateBaseline;

	PromotionRecord(
		String username,
		String normalizedUsername,
		String currentRank,
		LocalDate joinDate,
		int monthsInClan,
		LocalDate baselineDate,
		Long baselineXp,
		Long currentXp,
		Long xpGained,
		String nextRankCandidate,
		PromotionStatus status,
		boolean approximateBaseline)
	{
		this.username = username;
		this.normalizedUsername = normalizedUsername;
		this.currentRank = currentRank;
		this.joinDate = joinDate;
		this.monthsInClan = monthsInClan;
		this.baselineDate = baselineDate;
		this.baselineXp = baselineXp;
		this.currentXp = currentXp;
		this.xpGained = xpGained;
		this.nextRankCandidate = nextRankCandidate;
		this.status = status;
		this.approximateBaseline = approximateBaseline;
	}

	String getUsername()
	{
		return username;
	}

	String getNormalizedUsername()
	{
		return normalizedUsername;
	}

	String getCurrentRank()
	{
		return currentRank;
	}

	LocalDate getJoinDate()
	{
		return joinDate;
	}

	int getMonthsInClan()
	{
		return monthsInClan;
	}

	LocalDate getBaselineDate()
	{
		return baselineDate;
	}

	Long getBaselineXp()
	{
		return baselineXp;
	}

	Long getCurrentXp()
	{
		return currentXp;
	}

	Long getXpGained()
	{
		return xpGained;
	}

	String getNextRankCandidate()
	{
		return nextRankCandidate;
	}

	PromotionStatus getStatus()
	{
		return status;
	}

	boolean isApproximateBaseline()
	{
		return approximateBaseline;
	}

	boolean isActionable()
	{
		return status.isActionable() && nextRankCandidate != null && !nextRankCandidate.isBlank();
	}
}
