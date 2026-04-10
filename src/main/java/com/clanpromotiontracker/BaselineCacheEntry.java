package com.clanpromotiontracker;

import java.time.LocalDate;

final class BaselineCacheEntry
{
	private String baselineDate;
	private long baselineXp;
	private boolean approximate;

	BaselineCacheEntry()
	{
	}

	BaselineCacheEntry(LocalDate baselineDate, long baselineXp, boolean approximate)
	{
		this.baselineDate = baselineDate == null ? null : baselineDate.toString();
		this.baselineXp = baselineXp;
		this.approximate = approximate;
	}

	String getBaselineDate()
	{
		return baselineDate;
	}

	LocalDate getBaselineDateValue()
	{
		return baselineDate == null || baselineDate.isBlank() ? null : LocalDate.parse(baselineDate);
	}

	long getBaselineXp()
	{
		return baselineXp;
	}

	boolean isApproximate()
	{
		return approximate;
	}
}
