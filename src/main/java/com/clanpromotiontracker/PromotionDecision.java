package com.clanpromotiontracker;

final class PromotionDecision
{
	private final int monthsInClan;
	private final String nextRankCandidate;
	private final PromotionStatus status;

	PromotionDecision(int monthsInClan, String nextRankCandidate, PromotionStatus status)
	{
		this.monthsInClan = monthsInClan;
		this.nextRankCandidate = nextRankCandidate;
		this.status = status;
	}

	int getMonthsInClan()
	{
		return monthsInClan;
	}

	String getNextRankCandidate()
	{
		return nextRankCandidate;
	}

	PromotionStatus getStatus()
	{
		return status;
	}
}
