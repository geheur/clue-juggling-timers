/*
 * Copyright (c) 2017, Aria <aria@ar1as.space>
 * Copyright (c) 2018, Adam <Adam@sigterm.info>
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package com.cluejuggling;

import com.google.common.collect.EvictingQueue;
import net.runelite.api.InventoryID;
import net.runelite.api.Item;
import net.runelite.api.ItemComposition;
import net.runelite.api.ItemContainer;
import net.runelite.api.MenuAction;
import net.runelite.api.Tile;
import net.runelite.api.TileItem;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.widgets.WidgetInfo;
import net.runelite.client.eventbus.Subscribe;

import javax.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.util.Queue;

/**
 * Bunch of stuff I copied over from GroundItemsPlugin
 */
public class GroundItemPluginStuff
{
	private static final Duration DESPAWN_TIME_INSTANCE = Duration.ofMinutes(30);
	private static final Duration DESPAWN_TIME_LOOT = Duration.ofMinutes(2);
	private static final Duration DESPAWN_TIME_DROP = Duration.ofMinutes(60);
	private static final Duration DESPAWN_TIME_TABLE = Duration.ofMinutes(10);
	private static final int KRAKEN_REGION = 9116;
	private static final int KBD_NMZ_REGION = 9033;
	private static final int ZILYANA_REGION = 11602;
	private static final int GRAARDOR_REGION = 11347;
	private static final int KRIL_TSUTSAROTH_REGION = 11603;
	private static final int KREEARRA_REGION = 11346;
	private static final int NIGHTMARE_REGION = 15515;

	@Inject
	private ClueScrollJugglingPlugin plugin;

	public GroundItemPluginStuff(ClueScrollJugglingPlugin plugin)
	{
		this.plugin = plugin;
	}

	Instant calculateDespawnTime(GroundItem groundItem)
	{
		// We can only accurately guess despawn times for our own pvm loot, dropped items,
		// and items we placed on tables
		if (groundItem.getLootType() != LootType.PVM
			&& groundItem.getLootType() != LootType.DROPPED
			&& groundItem.getLootType() != LootType.TABLE)
		{
			return null;
		}

		// Loot appears to others after 1 minute, and despawns after 2 minutes
		// Dropped items appear to others after 1 minute, and despawns after 3 minutes
		// Items in instances never appear to anyone and despawn after 30 minutes

		Instant spawnTime = groundItem.getSpawnTime();
		if (spawnTime == null)
		{
			return null;
		}

		final Instant despawnTime;
		Instant now = Instant.now();
		if (plugin.client.isInInstancedRegion())
		{
			final int playerRegionID = WorldPoint.fromLocalInstance(plugin.client, plugin.client.getLocalPlayer().getLocalLocation()).getRegionID();
			if (playerRegionID == KRAKEN_REGION)
			{
				// Items in the Kraken instance never despawn
				return null;
			}
			else if (playerRegionID == KBD_NMZ_REGION)
			{
				// NMZ and the KBD lair uses the same region ID but NMZ uses planes 1-3 and KBD uses plane 0
				if (plugin.client.getLocalPlayer().getWorldLocation().getPlane() == 0)
				{
					// Items in the KBD instance use the standard despawn timer
					despawnTime = spawnTime.plus(groundItem.getLootType() == LootType.DROPPED
						? DESPAWN_TIME_DROP
						: DESPAWN_TIME_LOOT);
				}
				else
				{
					if (groundItem.getLootType() == LootType.DROPPED)
					{
						// Dropped items in the NMZ instance never despawn
						return null;
					}
					else
					{
						despawnTime = spawnTime.plus(DESPAWN_TIME_LOOT);
					}
				}
			}
			else if (playerRegionID == ZILYANA_REGION || playerRegionID == GRAARDOR_REGION ||
				playerRegionID == KRIL_TSUTSAROTH_REGION || playerRegionID == KREEARRA_REGION || playerRegionID == NIGHTMARE_REGION)
			{
				// GWD and Nightmare instances use the normal despawn timers
				despawnTime = spawnTime.plus(groundItem.getLootType() == LootType.DROPPED
					? DESPAWN_TIME_DROP
					: DESPAWN_TIME_LOOT);
			}
			else
			{
				despawnTime = spawnTime.plus(DESPAWN_TIME_INSTANCE);
			}
		}
		else
		{
			switch (groundItem.getLootType())
			{
				case DROPPED:
					despawnTime = spawnTime.plus(DESPAWN_TIME_DROP);
					break;
				case TABLE:
					despawnTime = spawnTime.plus(DESPAWN_TIME_TABLE);
					break;
				default:
					despawnTime = spawnTime.plus(DESPAWN_TIME_LOOT);
					break;
			}
		}

		if (now.isBefore(spawnTime) || now.isAfter(despawnTime))
		{
			// that's weird
			return null;
		}

		return despawnTime;
	}

	private final Queue<Integer> droppedItemQueue = EvictingQueue.create(16); // recently dropped items
	private int lastUsedItem;

	GroundItem buildGroundItem(final Tile tile, final TileItem item)
	{
		// Collect the data for the item
		final int itemId = item.getId();
		final ItemComposition itemComposition = plugin.itemManager.getItemComposition(itemId);
		final int realItemId = itemComposition.getNote() != -1 ? itemComposition.getLinkedNoteId() : itemId;
		final int alchPrice = itemComposition.getHaPrice();
		final boolean dropped = tile.getWorldLocation().equals(plugin.client.getLocalPlayer().getWorldLocation()) && droppedItemQueue.remove(itemId);
		final boolean table = itemId == lastUsedItem && tile.getItemLayer().getHeight() > 0;

		final GroundItem groundItem = GroundItem.builder()
			.id(itemId)
			.location(tile.getWorldLocation())
			.itemId(realItemId)
			.quantity(item.getQuantity())
			.name(itemComposition.getName())
			.haPrice(alchPrice)
			.height(tile.getItemLayer().getHeight())
			.tradeable(itemComposition.isTradeable())
			.lootType(dropped ? LootType.DROPPED : (table ? LootType.TABLE : LootType.UNKNOWN))
			.spawnTime(Instant.now())
			.stackable(itemComposition.isStackable())
			.build();

//        // Update item price in case it is coins
//        if (realItemId == COINS)
//        {
//            groundItem.setHaPrice(1);
//            groundItem.setGePrice(1);
//        }
//        else
//        {
//            groundItem.setGePrice(itemManager.getItemPrice(realItemId));
//        }

		return groundItem;
	}

	@Subscribe
	public void onMenuOptionClicked(MenuOptionClicked menuOptionClicked)
	{
		if (menuOptionClicked.isItemOp() && menuOptionClicked.getMenuOption().equals("Drop"))
		{
			int itemId = menuOptionClicked.getItemId();
			// Keep a queue of recently dropped items to better detect
			// item spawns that are drops
			droppedItemQueue.add(itemId);
		}
		else if (menuOptionClicked.getMenuAction() == MenuAction.WIDGET_TARGET_ON_GAME_OBJECT && plugin.client.getSelectedWidget().getId() == WidgetInfo.INVENTORY.getId())
		{
			lastUsedItem = plugin.client.getSelectedWidget().getItemId();
		}
	}

	public void startUp()
	{
		lastUsedItem = -1;
	}
}
