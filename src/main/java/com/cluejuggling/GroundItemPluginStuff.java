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
import net.runelite.api.ItemComposition;
import net.runelite.api.MenuAction;
import net.runelite.api.Tile;
import net.runelite.api.TileItem;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.widgets.ComponentID;
import net.runelite.client.eventbus.Subscribe;

import javax.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.util.Queue;
import net.runelite.client.util.RSTimeUnit;

/**
 * Bunch of stuff I copied over from GroundItemsPlugin
 */
public class GroundItemPluginStuff
{
	private ClueScrollJugglingPlugin plugin;

	public GroundItemPluginStuff(ClueScrollJugglingPlugin plugin)
	{
		this.plugin = plugin;
	}

	Instant calculateDespawnTime(GroundItem groundItem)
	{
		Instant spawnTime = groundItem.getSpawnTime();
		if (spawnTime == null)
		{
			return null;
		}

		Instant despawnTime = spawnTime.plus(groundItem.getDespawnTime());
		if (Instant.now().isAfter(despawnTime))
		{
			// that's weird
			return null;
		}

		return despawnTime;
	}

	GroundItem buildGroundItem(final Tile tile, final TileItem item)
	{
		// Collect the data for the item
		final int itemId = item.getId();
		final ItemComposition itemComposition = plugin.itemManager.getItemComposition(itemId);
		final int realItemId = itemComposition.getNote() != -1 ? itemComposition.getLinkedNoteId() : itemId;
		final int alchPrice = itemComposition.getHaPrice();
		final int despawnTime = item.getDespawnTime() - plugin.client.getTickCount();
		final int visibleTime = item.getVisibleTime() - plugin.client.getTickCount();

		final GroundItem groundItem = GroundItem.builder()
			.id(itemId)
			.location(tile.getWorldLocation())
			.itemId(realItemId)
			.quantity(item.getQuantity())
			.name(itemComposition.getName())
			.haPrice(alchPrice)
			.height(tile.getItemLayer().getHeight())
			.tradeable(itemComposition.isTradeable())
			.ownership(item.getOwnership())
			.isPrivate(item.isPrivate())
			.spawnTime(Instant.now())
			.stackable(itemComposition.isStackable())
			.despawnTime(Duration.of(despawnTime, RSTimeUnit.GAME_TICKS))
			.visibleTime(Duration.of(visibleTime, RSTimeUnit.GAME_TICKS))
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
}
