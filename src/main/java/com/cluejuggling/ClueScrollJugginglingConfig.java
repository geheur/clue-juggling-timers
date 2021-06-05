package com.cluejuggling;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

@ConfigGroup("cluescrolljuggling")
public interface ClueScrollJugginglingConfig extends Config
{
	@ConfigItem(
		keyName = "notificationTime",
		name = "Notification at",
		description = "Time remaining (seconds) on despawn timer to send notification. Set to 181 or greater to disable the notification.",
		position = 0
	)
	default int notificationTime()
	{
		return 30;
	}
}
