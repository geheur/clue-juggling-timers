package com.cluejuggling;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

public class ClueScrollJugglingPluginTest
{
	public static void main(String[] args) throws Exception
	{
		ExternalPluginManager.loadBuiltin(ClueScrollJugglingPlugin.class);
		RuneLite.main(args);
	}
}