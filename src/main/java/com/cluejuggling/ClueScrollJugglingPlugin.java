package com.cluejuggling;

import com.google.inject.Provides;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.ItemComposition;
import net.runelite.api.Tile;
import net.runelite.api.TileItem;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.ItemDespawned;
import net.runelite.api.events.ItemSpawned;
import net.runelite.client.Notifier;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.game.ItemManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.overlay.infobox.InfoBoxManager;
import net.runelite.client.ui.overlay.infobox.Timer;

import javax.inject.Inject;
import java.awt.*;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

@PluginDescriptor(
	name = "Clue Juggling",
	description = "clue juggling timers",
	tags = {"clue", "juggling", "league", "trailblazer"}
)
@Slf4j
public class ClueScrollJugglingPlugin extends Plugin
{
	@Inject
	Client client;

	@Inject
	ItemManager itemManager;

	@Inject
	private ClueScrollJugginglingConfig config;

	@Inject
	private InfoBoxManager infoBoxManager;

	@Inject
	private EventBus eventBus;

	@Inject
	private ConfigManager configManager;

	@Inject
	private Notifier notifier;

	private GroundItemPluginStuff groundItemPluginStuff = new GroundItemPluginStuff(this);

	public Map<GroundItem.GroundItemKey, Timer> dropTimers = new HashMap<>();
	private Set<GroundItem.GroundItemKey> alreadyNotified = new HashSet<>();

	@Provides
	public ClueScrollJugginglingConfig getConfig(ConfigManager configManager) {
		return configManager.getConfig(ClueScrollJugginglingConfig.class);
	}

	@Subscribe
	public void onItemSpawned(ItemSpawned itemSpawned)
	{
		TileItem item = itemSpawned.getItem();
		ItemComposition itemComposition = itemManager.getItemComposition(item.getId());
		if (!itemComposition.getName().toLowerCase().contains("clue scroll")) return;
		Tile tile = itemSpawned.getTile();

		GroundItem.GroundItemKey groundItemKey = new GroundItem.GroundItemKey(item.getId(), tile.getWorldLocation());

		if (!dropTimers.containsKey(groundItemKey)) {
			Instant instant = groundItemPluginStuff.calculateDespawnTime(groundItemPluginStuff.buildGroundItem(tile, item));
			instant.compareTo(Instant.now());
			Duration between = Duration.between(Instant.now(), instant);
			Timer timer = new Timer(between.getSeconds(), ChronoUnit.SECONDS, itemManager.getImage(item.getId()), this) {
				@Override
				public Color getTextColor()
				{
//					Duration remaining = Duration.between(Instant.now(), this.getEndTime());
//					float stage = remaining.toMillis() / 180_000f;
//					int red1 = Color.RED.getRed();
//					int red2 = Color.WHITE.getRed();
//					int green1 = Color.RED.getGreen();
//					int green2 = Color.WHITE.getGreen();
//					int blue1 = Color.RED.getBlue();
//					int blue2 = Color.WHITE.getBlue();
//					int red = (int) (red1 + ((red2 - red1) * stage));
//					int green = (int) (green1 + ((green2 - green1) * stage));
//					int blue = (int) (blue1 + ((blue2 - blue1) * stage));
//					return new Color(red, green, blue);
					if (config.notificationTime() <= 180)
					{
						if (Duration.between(Instant.now(), this.getEndTime()).compareTo(Duration.ofSeconds(config.notificationTime())) < 0)
						{
							return Color.RED;
						}
					}
					return super.getTextColor();
				}
			};
			infoBoxManager.addInfoBox(timer);
			alreadyNotified.remove(groundItemKey);
			dropTimers.put(groundItemKey, timer);
		}
	}

	@Subscribe
	public void onItemDespawned(ItemDespawned itemDespawned)
	{
		TileItem item = itemDespawned.getItem();
		ItemComposition itemComposition = itemManager.getItemComposition(item.getId());
		if (!itemComposition.getName().toLowerCase().contains("clue scroll")) return;
		Tile tile = itemDespawned.getTile();

		GroundItem.GroundItemKey groundItemKey = new GroundItem.GroundItemKey(item.getId(), tile.getWorldLocation());

		Timer removedTimer = dropTimers.remove(groundItemKey);
		infoBoxManager.removeInfoBox(removedTimer);
	}

	@Subscribe
	public void onGameTick(GameTick gameTick) {
		for (Map.Entry<GroundItem.GroundItemKey, Timer> groundItemKeyTimerEntry : dropTimers.entrySet())
		{
			GroundItem.GroundItemKey groundItemKey = groundItemKeyTimerEntry.getKey();
			Timer timer = groundItemKeyTimerEntry.getValue();
			if (config.notificationTime() <= 180)
			{
				if (Duration.between(Instant.now(), timer.getEndTime()).compareTo(Duration.ofSeconds(config.notificationTime())) < 0 && !alreadyNotified.contains(groundItemKey))
				{
					timer.getTextColor();
					notifier.notify("Your clue scroll is about to disappear!");
					alreadyNotified.add(groundItemKey);
				}
			}
		}
	}

	@Override
	protected void startUp()
	{
		eventBus.register(groundItemPluginStuff);
		groundItemPluginStuff.startUp();
	}

	@Override
	protected void shutDown()
	{
		eventBus.unregister(groundItemPluginStuff);
	}
}
