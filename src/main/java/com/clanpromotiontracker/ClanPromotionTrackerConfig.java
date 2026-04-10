package com.clanpromotiontracker;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;

import java.awt.Color;

@ConfigGroup("clanpromotiontracker")
public interface ClanPromotionTrackerConfig extends Config
{
	@ConfigSection(
		name = "Wise Old Man",
		description = "Configure Wise Old Man integration",
		position = 0
	)
	String womSection = "wom";

	@ConfigSection(
		name = "Filters",
		description = "Control which members appear in the sidebar and overlay",
		position = 1,
		closedByDefault = true
	)
	String filterSection = "filters";

	@ConfigSection(
		name = "Display",
		description = "Sidebar, overlay, and notification behavior",
		position = 2,
		closedByDefault = true
	)
	String displaySection = "display";

	@ConfigSection(
		name = "Advanced Rank Ladder",
		description = "Override the built-in Railway app ladder",
		position = 3,
		closedByDefault = true
	)
	String ladderSection = "ladder";

	@ConfigSection(
		name = "Rank Colors",
		description = "Customize overlay colors per target rank",
		position = 4,
		closedByDefault = true
	)
	String rankColorSection = "rankColors";

	@ConfigItem(
		keyName = "womGroupId",
		name = "Wise Old Man group ID",
		description = "Numeric Wise Old Man group ID used to match your clan roster",
		position = 0,
		section = womSection,
		warning = "This plugin contacts wiseoldman.net to fetch XP and snapshot history."
	)
	default int womGroupId()
	{
		return 0;
	}

	@ConfigItem(
		keyName = "womApiKey",
		name = "Wise Old Man API key",
		description = "Optional API key for higher Wise Old Man rate limits. Leave blank to use the public unauthenticated limit.",
		position = 1,
		section = womSection
	)
	default String womApiKey()
	{
		return "";
	}

	@ConfigItem(
		keyName = "autoRefreshEnabled",
		name = "Auto refresh enabled",
		description = "Periodically refresh Wise Old Man data in the background",
		position = 2,
		section = womSection
	)
	default boolean autoRefreshEnabled()
	{
		return false;
	}

	@ConfigItem(
		keyName = "refreshIntervalMinutes",
		name = "Refresh interval (minutes)",
		description = "How often background refreshes should run",
		position = 3,
		section = womSection
	)
	default int refreshIntervalMinutes()
	{
		return 15;
	}

	@ConfigItem(
		keyName = "eligibleCurrentRanks",
		name = "Eligible current ranks",
		description = "Optional allow-list of current ranks to display or highlight. Separate values with commas or new lines.",
		position = 0,
		section = filterSection
	)
	default String eligibleCurrentRanks()
	{
		return "";
	}

	@ConfigItem(
		keyName = "ignoredCurrentRanks",
		name = "Ignored current ranks",
		description = "Hide members whose current rank matches one of these values",
		position = 1,
		section = filterSection
	)
	default String ignoredCurrentRanks()
	{
		return "";
	}

	@ConfigItem(
		keyName = "ignoredTargetRanks",
		name = "Ignored target ranks",
		description = "Hide members targeting one of these ranks",
		position = 2,
		section = filterSection
	)
	default String ignoredTargetRanks()
	{
		return "";
	}

	@ConfigItem(
		keyName = "ignoredUsers",
		name = "Ignored users",
		description = "Hide or suppress specific members",
		position = 3,
		section = filterSection
	)
	default String ignoredUsers()
	{
		return "";
	}

	@ConfigItem(
		keyName = "muteNotifications",
		name = "Mute notifications",
		description = "Suppress desktop notifications for newly actionable promotion rows",
		position = 0,
		section = displaySection
	)
	default boolean muteNotifications()
	{
		return true;
	}

	@ConfigItem(
		keyName = "highlightMemberList",
		name = "Highlight clan member list",
		description = "Highlight ready promotion rows inside the clan member list widget",
		position = 1,
		section = displaySection
	)
	default boolean highlightMemberList()
	{
		return true;
	}

	@ConfigItem(
		keyName = "maxDisplayedInPanel",
		name = "Max displayed in panel",
		description = "Maximum number of rows to display in the sidebar (0 for all)",
		position = 2,
		section = displaySection
	)
	default int maxDisplayedInPanel()
	{
		return 50;
	}

	@ConfigItem(
		keyName = "useCustomRankLadder",
		name = "Use custom rank ladder",
		description = "Override the default Railway app ladder",
		position = 0,
		section = ladderSection
	)
	default boolean useCustomRankLadder()
	{
		return false;
	}

	@ConfigItem(
		keyName = "recommendRankSkips",
		name = "Recommend rank skips",
		description = "When enabled, recommend the highest currently-qualified rank instead of only the immediate next rank",
		position = 1,
		section = ladderSection
	)
	default boolean recommendRankSkips()
	{
		return false;
	}

	@ConfigItem(
		keyName = "customRankLadder",
		name = "Custom rank ladder",
		description = "One rule per line: Rank|MonthsRequired|XpRequiredMillions",
		position = 2,
		section = ladderSection
	)
	default String customRankLadder()
	{
		return String.join("\n",
			"# Rank|MonthsRequired|XpRequiredMillions",
			"Thief|0|0",
			"Recruit|1|0.1",
			"Corporal|2|0.5",
			"Sergeant|3|1",
			"Lieutenant|4|2",
			"Captain|5|3",
			"General|6|8",
			"Officer|9|12",
			"Commander|12|20",
			"Colonel|15|25",
			"Brigadier|17|30",
			"Admiral|20|50",
			"Marshal|24|75"
		);
	}

	@ConfigItem(
		keyName = "defaultHighlightColor",
		name = "Default highlight color",
		description = "Fallback color for ranks without a dedicated color",
		position = 0,
		section = rankColorSection
	)
	default Color defaultHighlightColor()
	{
		return new Color(255, 200, 0);
	}

	@ConfigItem(keyName = "thiefColor", name = "Thief", description = "Target rank color", position = 1, section = rankColorSection)
	default Color thiefColor() { return new Color(120, 144, 156); }

	@ConfigItem(keyName = "recruitColor", name = "Recruit", description = "Target rank color", position = 2, section = rankColorSection)
	default Color recruitColor() { return new Color(144, 238, 144); }

	@ConfigItem(keyName = "corporalColor", name = "Corporal", description = "Target rank color", position = 3, section = rankColorSection)
	default Color corporalColor() { return new Color(100, 149, 237); }

	@ConfigItem(keyName = "sergeantColor", name = "Sergeant", description = "Target rank color", position = 4, section = rankColorSection)
	default Color sergeantColor() { return new Color(255, 165, 0); }

	@ConfigItem(keyName = "lieutenantColor", name = "Lieutenant", description = "Target rank color", position = 5, section = rankColorSection)
	default Color lieutenantColor() { return new Color(255, 215, 0); }

	@ConfigItem(keyName = "captainColor", name = "Captain", description = "Target rank color", position = 6, section = rankColorSection)
	default Color captainColor() { return new Color(255, 99, 71); }

	@ConfigItem(keyName = "generalColor", name = "General", description = "Target rank color", position = 7, section = rankColorSection)
	default Color generalColor() { return new Color(255, 87, 87); }

	@ConfigItem(keyName = "officerColor", name = "Officer", description = "Target rank color", position = 8, section = rankColorSection)
	default Color officerColor() { return new Color(171, 71, 188); }

	@ConfigItem(keyName = "commanderColor", name = "Commander", description = "Target rank color", position = 9, section = rankColorSection)
	default Color commanderColor() { return new Color(79, 195, 247); }

	@ConfigItem(keyName = "colonelColor", name = "Colonel", description = "Target rank color", position = 10, section = rankColorSection)
	default Color colonelColor() { return new Color(129, 199, 132); }

	@ConfigItem(keyName = "brigadierColor", name = "Brigadier", description = "Target rank color", position = 11, section = rankColorSection)
	default Color brigadierColor() { return new Color(255, 183, 77); }

	@ConfigItem(keyName = "admiralColor", name = "Admiral", description = "Target rank color", position = 12, section = rankColorSection)
	default Color admiralColor() { return new Color(77, 182, 172); }

	@ConfigItem(keyName = "marshalColor", name = "Marshal", description = "Target rank color", position = 13, section = rankColorSection)
	default Color marshalColor() { return new Color(239, 83, 80); }

	@ConfigItem(
		keyName = "customRankColors",
		name = "Custom rank colors",
		description = "Optional overrides in the format RankName:#HEXCOLOR,RankName2:#HEXCOLOR2",
		position = 14,
		section = rankColorSection
	)
	default String customRankColors()
	{
		return "";
	}
}
