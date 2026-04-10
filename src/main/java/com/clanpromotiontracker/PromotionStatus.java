package com.clanpromotiontracker;

enum PromotionStatus
{
	READY("Ready", true),
	NOT_READY("Not ready", false),
	XP_NOT_FETCHED("XP not fetched", false),
	UNKNOWN_RANK("Unknown rank", false),
	NO_WOM_MATCH("No WOM match", false),
	APPROXIMATE_BASELINE("Approximate baseline", true);

	private final String displayName;
	private final boolean actionable;

	PromotionStatus(String displayName, boolean actionable)
	{
		this.displayName = displayName;
		this.actionable = actionable;
	}

	String getDisplayName()
	{
		return displayName;
	}

	boolean isActionable()
	{
		return actionable;
	}
}
