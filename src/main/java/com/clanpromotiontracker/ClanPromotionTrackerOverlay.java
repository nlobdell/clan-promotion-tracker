package com.clanpromotiontracker;

import net.runelite.api.Client;
import net.runelite.api.widgets.Widget;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.OverlayPriority;

import javax.inject.Inject;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

final class ClanPromotionTrackerOverlay extends Overlay
{
	private static final int CLAN_SETTINGS_GROUP_ID = 693;
	private static final int CLAN_MEMBER_PANEL_CHILD = 9;
	private static final int CLAN_MEMBER_NAME_CHILD = 10;
	private static final int ROW_Y_TOLERANCE = 15;

	private final Client client;
	private final ClanPromotionTrackerPlugin plugin;
	private final ClanPromotionTrackerConfig config;

	@Inject
	ClanPromotionTrackerOverlay(Client client, ClanPromotionTrackerPlugin plugin, ClanPromotionTrackerConfig config)
	{
		super(plugin);
		this.client = client;
		this.plugin = plugin;
		this.config = config;

		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ABOVE_WIDGETS);
		setPriority(OverlayPriority.HIGH);
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		if (!config.highlightMemberList())
		{
			return null;
		}

		Map<String, String> actionableTargetRanks = plugin.getActionableTargetRanks();
		if (actionableTargetRanks.isEmpty())
		{
			return null;
		}
		Map<String, String> actionableCurrentRanks = plugin.getActionableCurrentRanks();

		Widget root = client.getWidget(CLAN_SETTINGS_GROUP_ID, 0);
		if (root == null || root.isHidden())
		{
			return null;
		}

		Widget memberPanel = client.getWidget(CLAN_SETTINGS_GROUP_ID, CLAN_MEMBER_PANEL_CHILD);
		Widget nameColumn = client.getWidget(CLAN_SETTINGS_GROUP_ID, CLAN_MEMBER_NAME_CHILD);
		if (memberPanel == null || nameColumn == null || memberPanel.isHidden() || nameColumn.isHidden())
		{
			return null;
		}

		Rectangle viewport = memberPanel.getBounds();
		if (viewport == null || viewport.width <= 0 || viewport.height <= 0)
		{
			return null;
		}

		List<Widget> allTextWidgets = new ArrayList<>();
		collectAllTextWidgets(memberPanel, allTextWidgets, newIdentitySet());

		List<Widget> nameWidgets = new ArrayList<>();
		collectAllTextWidgets(nameColumn, nameWidgets, newIdentitySet());

		Widget[] rowWidgets = allTextWidgets.toArray(new Widget[0]);
		for (Widget nameWidget : nameWidgets)
		{
			String cleanName = extractCleanText(nameWidget.getText());
			if (cleanName == null || cleanName.isEmpty())
			{
				continue;
			}

			Rectangle nameBounds = nameWidget.getBounds();
			if (nameBounds == null || !viewport.intersects(nameBounds))
			{
				continue;
			}

			String normalizedName = WiseOldManClient.normalizeName(cleanName);
			String targetRank = actionableTargetRanks.get(normalizedName);
			if (targetRank == null || targetRank.isBlank())
			{
				continue;
			}

			String currentRank = findCurrentRankForPlayer(rowWidgets, nameWidget);
			String knownCurrentRank = actionableCurrentRanks.get(normalizedName);
			if (knownCurrentRank != null && knownCurrentRank.equalsIgnoreCase(targetRank))
			{
				continue;
			}

			if (knownCurrentRank == null && currentRank != null && !currentRank.isBlank() && currentRank.equalsIgnoreCase(targetRank))
			{
				continue;
			}

			renderHighlight(graphics, nameWidget, targetRank);
		}

		return null;
	}

	private Set<Widget> newIdentitySet()
	{
		return Collections.newSetFromMap(new IdentityHashMap<>());
	}

	private void collectAllTextWidgets(Widget root, List<Widget> target, Set<Widget> visited)
	{
		if (root == null || visited.contains(root))
		{
			return;
		}

		visited.add(root);
		if (root.getText() != null && !root.getText().isEmpty())
		{
			target.add(root);
		}

		Widget[] dynamicChildren = root.getDynamicChildren();
		if (dynamicChildren != null)
		{
			for (Widget child : dynamicChildren)
			{
				collectAllTextWidgets(child, target, visited);
			}
		}

		Widget[] staticChildren = root.getStaticChildren();
		if (staticChildren != null)
		{
			for (Widget child : staticChildren)
			{
				collectAllTextWidgets(child, target, visited);
			}
		}

		Widget[] nestedChildren = root.getNestedChildren();
		if (nestedChildren != null)
		{
			for (Widget child : nestedChildren)
			{
				collectAllTextWidgets(child, target, visited);
			}
		}
	}

	private String findCurrentRankForPlayer(Widget[] rowWidgets, Widget nameWidget)
	{
		Rectangle nameBounds = nameWidget.getBounds();
		if (nameBounds == null)
		{
			return null;
		}

		String normalizedName = WiseOldManClient.normalizeName(extractCleanText(nameWidget.getText()));
		int nameRightEdge = nameBounds.x + nameBounds.width;
		Widget bestMatch = null;
		int bestDelta = Integer.MAX_VALUE;

		for (Widget widget : rowWidgets)
		{
			if (widget == null || widget == nameWidget)
			{
				continue;
			}

			Rectangle bounds = widget.getBounds();
			if (bounds == null || Math.abs(bounds.y - nameBounds.y) > ROW_Y_TOLERANCE || bounds.x < nameRightEdge)
			{
				continue;
			}

			String text = extractCleanText(widget.getText());
			if (text == null || text.isBlank())
			{
				continue;
			}

			if (WiseOldManClient.normalizeName(text).equals(normalizedName) || !looksLikeRankText(text))
			{
				continue;
			}

			int delta = bounds.x - nameRightEdge;
			if (delta < bestDelta)
			{
				bestMatch = widget;
				bestDelta = delta;
			}
		}

		return bestMatch == null ? null : extractCleanText(bestMatch.getText());
	}

	private boolean looksLikeRankText(String text)
	{
		String lower = text.trim().toLowerCase(Locale.ENGLISH);
		if (lower.isEmpty() || lower.length() > 20)
		{
			return false;
		}

		if (lower.matches("^w\\s*\\d+$") || lower.matches("^world\\s*\\d+$"))
		{
			return false;
		}

		if (lower.matches("^\\d+$") || lower.matches("^\\d{1,2}:\\d{2}.*$"))
		{
			return false;
		}

		return lower.matches(".*[a-z].*") && text.matches("^[A-Za-z][A-Za-z0-9 '\\-]{0,19}$");
	}

	private void renderHighlight(Graphics2D graphics, Widget widget, String targetRank)
	{
		Rectangle bounds = widget.getBounds();
		if (bounds == null || bounds.width <= 0 || bounds.height <= 0)
		{
			return;
		}

		Color highlight = getRankColor(targetRank);
		graphics.setColor(new Color(highlight.getRed(), highlight.getGreen(), highlight.getBlue(), 60));
		graphics.fillRect(bounds.x, bounds.y, bounds.width, bounds.height);

		graphics.setColor(highlight);
		graphics.drawRect(bounds.x, bounds.y, bounds.width, bounds.height);

		String abbreviation = getRankAbbreviation(targetRank);
		FontMetrics fontMetrics = graphics.getFontMetrics();
		int textWidth = fontMetrics.stringWidth(abbreviation);
		int badgeWidth = textWidth + 6;
		int badgeX = bounds.x + bounds.width - badgeWidth;

		graphics.setColor(Color.BLACK);
		graphics.fillRect(badgeX, bounds.y, badgeWidth, bounds.height);

		graphics.setColor(highlight);
		graphics.drawString(abbreviation, badgeX + 3, bounds.y + bounds.height - 3);
	}

	private Color getRankColor(String rank)
	{
		Color custom = getCustomRankColor(rank);
		if (custom != null)
		{
			return custom;
		}

		switch (rank.toLowerCase(Locale.ENGLISH))
		{
			case "thief":
				return config.thiefColor();
			case "recruit":
				return config.recruitColor();
			case "corporal":
				return config.corporalColor();
			case "sergeant":
				return config.sergeantColor();
			case "lieutenant":
				return config.lieutenantColor();
			case "captain":
				return config.captainColor();
			case "general":
				return config.generalColor();
			case "officer":
				return config.officerColor();
			case "commander":
				return config.commanderColor();
			case "colonel":
				return config.colonelColor();
			case "brigadier":
				return config.brigadierColor();
			case "admiral":
				return config.admiralColor();
			case "marshal":
				return config.marshalColor();
			default:
				return config.defaultHighlightColor();
		}
	}

	private Color getCustomRankColor(String rank)
	{
		String customRankColors = config.customRankColors();
		if (customRankColors == null || customRankColors.isBlank())
		{
			return null;
		}

		String[] pairs = customRankColors.split(",");
		for (String pair : pairs)
		{
			String[] parts = pair.split(":");
			if (parts.length != 2)
			{
				continue;
			}

			if (parts[0].trim().equalsIgnoreCase(rank))
			{
				try
				{
					return Color.decode(parts[1].trim());
				}
				catch (NumberFormatException ignored)
				{
					return null;
				}
			}
		}

		return null;
	}

	private String getRankAbbreviation(String rank)
	{
		switch (rank)
		{
			case "Lieutenant":
				return "LT";
			case "Commander":
				return "CMD";
			case "Brigadier":
				return "BRG";
			case "Recruit":
				return "RCT";
			case "Corporal":
				return "CPL";
			case "Sergeant":
				return "SGT";
			case "Captain":
				return "CPT";
			case "General":
				return "GEN";
			case "Officer":
				return "OFF";
			case "Colonel":
				return "COL";
			case "Admiral":
				return "ADM";
			case "Marshal":
				return "MSH";
			case "Thief":
				return "THF";
			default:
				return rank.length() <= 3 ? rank.toUpperCase(Locale.ENGLISH) : rank.substring(0, 3).toUpperCase(Locale.ENGLISH);
		}
	}

	private String extractCleanText(String widgetText)
	{
		if (widgetText == null)
		{
			return null;
		}

		return widgetText
			.replaceAll("<col=[^>]*>", "")
			.replaceAll("</col>", "")
			.replaceAll("<img=[^>]*>", "")
			.trim();
	}
}
