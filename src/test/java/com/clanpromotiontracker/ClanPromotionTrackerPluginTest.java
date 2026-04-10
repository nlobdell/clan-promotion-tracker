package com.clanpromotiontracker;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

public class ClanPromotionTrackerPluginTest
{
	public static void main(String[] args) throws Exception
	{
		ExternalPluginManager.loadBuiltin(ClanPromotionTrackerPlugin.class);
		RuneLite.main(args);
	}
}
