package com.clanpromotiontracker;

final class RankRequirement
{
	private final String name;
	private final int monthsRequired;
	private final long xpRequired;

	RankRequirement(String name, int monthsRequired, long xpRequired)
	{
		this.name = name;
		this.monthsRequired = monthsRequired;
		this.xpRequired = xpRequired;
	}

	String getName()
	{
		return name;
	}

	int getMonthsRequired()
	{
		return monthsRequired;
	}

	long getXpRequired()
	{
		return xpRequired;
	}
}
