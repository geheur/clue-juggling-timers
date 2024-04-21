package com.cluejuggling;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.Range;
import net.runelite.client.config.Units;

@ConfigGroup(ClueScrollJugglingPlugin.CONFIG_GROUP)
public interface ClueScrollJugginglingConfig extends Config
{
	@ConfigItem(
		keyName = "notificationTime",
		name = "Notification at (s)",
		description = "Time remaining (seconds) on despawn timer to send notification. Set to 0 to disable the notification.",
		position = 0
	)
	default int notificationTime()
	{
		return 30;
	}

	@ConfigItem(
		keyName = "beginnerTimers",
		name = "Beginner timer",
		description = "Create timers for beginner clues on the ground.",
		position = 1
	)
	default boolean beginnerTimers()
	{
		return true;
	}

	@ConfigItem(
		keyName = "easyTimers",
		name = "Easy timer",
		description = "Create timers for easy clues on the ground.",
		position = 2
	)
	default boolean easyTimers()
	{
		return true;
	}

	@ConfigItem(
		keyName = "mediumTimers",
		name = "Medium timer",
		description = "Create timers for medium clues on the ground.",
		position = 3
	)
	default boolean mediumTimers()
	{
		return true;
	}

	@ConfigItem(
		keyName = "hardTimers",
		name = "Hard timer",
		description = "Create timers for hard clues on the ground.",
		position = 4
	)
	default boolean hardTimers()
	{
		return true;
	}

	@ConfigItem(
		keyName = "eliteTimers",
		name = "Elite timer",
		description = "Create timers for elite clues on the ground.",
		position = 5
	)
	default boolean eliteTimers()
	{
		return true;
	}

	@ConfigItem(
		keyName = "masterTimers",
		name = "Master timer",
		description = "Create timers for master clues on the ground.",
		position = 6
	)
	default boolean masterTimers()
	{
		return true;
	}

	@ConfigItem(
		keyName = "hourDropTimer",
		name = "Clue drop time",
		description = "It is 60 minutes, but you might want to use a lower number due to inaccuracies in this plugin's tracking.",
		hidden = true,
		position = 7
	)
	default int hourDropTimer()
	{
		return 59;
	}

	@ConfigItem(
		keyName = "dropTimerReduction",
		name = "Drop timer multiplier",
		description = "Due to inaccuracies in this plugin's tracking, you might want to set something below 100.",
		position = 7
	)
	@Units(Units.PERCENT)
	@Range(max=100)
	default int dropTimerReduction()
	{
		int previousValue = hourDropTimer();
		return (int) Math.floor(previousValue / 60.0 * 100);
	}

	@ConfigItem(
		keyName = "combineTimers",
		name = "Combine infoboxes",
		description = "Show only 1 infobox, with the lowest time remaining.",
		position = 8
	)
	default boolean combineTimers()
	{
		return false;
	}

	@ConfigItem(
		keyName = "extraItems",
		name = "Track more items (id or name)",
		description = "comma separated, * wildcard supported",
		position = 9
	)
	default String extraItems()
	{
		return "";
	}

	@ConfigItem(
		keyName = "hidden",
		name = "<html>Shift-right-click the infoboxes for more options.<br>You can use the ::clearclues command to clear all infoboxes.</html>",
		description = "",
		position = 10
	)
	default void shiftRightClickInfo()
	{
	}
}
