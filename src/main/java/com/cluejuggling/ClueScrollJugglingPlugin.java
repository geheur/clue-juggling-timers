package com.cluejuggling;

import com.cluejuggling.GroundItem.GroundItemKey;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.google.inject.Provides;
import java.awt.Color;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import javax.inject.Inject;
import lombok.Data;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.ItemComposition;
import net.runelite.api.ItemID;
import net.runelite.api.MenuAction;
import net.runelite.api.MenuEntry;
import net.runelite.api.TileItem;
import net.runelite.api.events.CommandExecuted;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.ItemDespawned;
import net.runelite.api.events.ItemSpawned;
import net.runelite.api.events.MenuOpened;
import net.runelite.client.Notifier;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ClientShutdown;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.game.ItemManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.overlay.OverlayMenuEntry;
import net.runelite.client.ui.overlay.infobox.InfoBox;
import net.runelite.client.ui.overlay.infobox.InfoBoxManager;
import net.runelite.client.util.ColorUtil;
import net.runelite.client.util.Text;
import org.apache.commons.lang3.ArrayUtils;

@PluginDescriptor(
	name = "Clue Juggling Timers",
	description = "clue despawn timers",
	tags = {"clue", "juggling", "timer", "despawn"}
)
@Slf4j
public class ClueScrollJugglingPlugin extends Plugin
{
	public static final String CONFIG_GROUP = "cluescrolljuggling";

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

	@Inject private Gson gson;

	private GroundItemPluginStuff groundItemPluginStuff = new GroundItemPluginStuff(this);

	private List<DroppedClue> droppedClues = new ArrayList<>();
	private InfoBox combinedTimer = null;

	@Data
	public static final class DroppedClue
	{
		public DroppedClue(Instant startTime, int timeRemaining, GroundItemKey groundItemKey) {
			this.startTime = startTime;
			this.timeRemaining = timeRemaining;
			this.groundItemKey = groundItemKey;
		}

		transient Instant startTime;
		int timeRemaining;
		final GroundItemKey groundItemKey;
		boolean notified = false;
		transient boolean invalidTimer = false;

		transient InfoBox infobox = null; // if it exists

		public Duration getDuration(int percentageReduction)
		{
			return Duration.between(Instant.now(), startTime.plus(Duration.ofSeconds((int) (timeRemaining * (percentageReduction / 100.0)))));
		}

		public boolean isExpired(int percentageReduction)
		{
			return getDuration(percentageReduction).toSeconds() < 0;
		}
	}

	private void onLogin() {
		log.debug("login " + Thread.currentThread().getName());

		Boolean timesAreAccurate = configManager.getRSProfileConfiguration(CONFIG_GROUP, "timesAreAccurate", Boolean.class);
		if (timesAreAccurate == null) timesAreAccurate = false;
		configManager.setRSProfileConfiguration(CONFIG_GROUP, "timesAreAccurate", false);

		if (droppedClues.size() > 0) {
			log.error("droppedClues.size() " + droppedClues.size());
		}
		droppedClues = gson.fromJson(configManager.getRSProfileConfiguration(CONFIG_GROUP, "clueData"), new TypeToken<List<DroppedClue>>(){}.getType());
		if (droppedClues == null) droppedClues = new ArrayList<>();
		log.debug("|  dropped clues is " + droppedClues.size());
		// Unfortunately, varc 526 (play time) is not sent unless the tab that shows it is selected.
		for (DroppedClue droppedClue : droppedClues)
		{
			droppedClue.startTime = Instant.now();
			if (!timesAreAccurate) droppedClue.invalidTimer = true;
			addInfobox(droppedClue);
		}
	}

	private void onLogout() {
		log.debug("logout " + Thread.currentThread().getName());
		if (droppedClues.isEmpty()) return;

		for (DroppedClue droppedClue : droppedClues)
		{
			droppedClue.timeRemaining -= Duration.between(droppedClue.startTime, Instant.now()).getSeconds();
		}
		saveDroppedClues();
		configManager.setRSProfileConfiguration(CONFIG_GROUP, "timesAreAccurate", true);
//		configManager.setRSProfileConfiguration(CONFIG_GROUP, "jagexPlayTime", client.getVarcIntValue(526));

		for (DroppedClue droppedClue : droppedClues)
		{
			infoBoxManager.removeInfoBox(droppedClue.infobox);
		}
		infoBoxManager.removeInfoBox(combinedTimer);
		combinedTimer = null;
		droppedClues.clear();
	}

	private int lastGameState = -1;
	@Subscribe
	public void onGameStateChanged(GameStateChanged e) {
		log.debug("game state changed from " + lastGameState + " to " + e.getGameState());
		if (e.getGameState() == GameState.LOADING) return;

		int LOGGED_IN_STATE = GameState.LOGGED_IN.getState();
		int state = e.getGameState().getState();
		boolean loggedOut = state < LOGGED_IN_STATE;
		boolean loggedIn = state >= LOGGED_IN_STATE;
		boolean wasLoggedOut = lastGameState < LOGGED_IN_STATE;
		boolean wasLoggedIn = lastGameState >= LOGGED_IN_STATE;
		if (wasLoggedOut && loggedIn) {
			onLogin();
		} else if (wasLoggedIn && loggedOut) {
			onLogout();
		}
		lastGameState = e.getGameState().getState();
	}

	@Inject private ClientThread clientThread;

	@Subscribe
	public void onClientShutdown(ClientShutdown e) {
//		System.out.println("client shutdown");
		clientThread.invokeLater(() -> {
			GameStateChanged gameStateChanged = new GameStateChanged();
			gameStateChanged.setGameState(GameState.LOGIN_SCREEN);
			this.onGameStateChanged(gameStateChanged);

			itemsSpawned.clear();
			itemsDespawned.clear();
		});
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged e) {
		if (!e.getGroup().equals(CONFIG_GROUP)) return;

		if (e.getKey().equals("combineTimers")) {
			clientThread.invokeLater(() -> {
				if (config.combineTimers()) {
					for (DroppedClue droppedClue : droppedClues)
					{
						infoBoxManager.removeInfoBox(droppedClue.infobox);
						addInfobox(droppedClue);
					}
				} else {
					infoBoxManager.removeInfoBox(combinedTimer);
					combinedTimer = null;
					for (DroppedClue droppedClue : droppedClues)
					{
						addInfobox(droppedClue);
					}
				}
			});
		} else if (e.getKey().equals("extraItems")) {
			updateExtraItems();
		}
	}

	Set<Integer> itemIds = new HashSet<>();
	List<MatchType> matchTypes = new ArrayList<>();
	List<String> matchStrings = new ArrayList<>();

	private void updateExtraItems()
	{
		String[] split = config.extraItems().split(",");
		itemIds.clear();
		matchTypes.clear();
		matchStrings.clear();
		for (String s : split)
		{
			s = s.trim();
			try {
				int itemId = Integer.parseInt(s);
				itemIds.add(itemId);
			} catch (NumberFormatException ex) {
				MatchType type = MatchType.getType(s);
				matchTypes.add(type);
				matchStrings.add(MatchType.prepareMatch(s, type));
			}
		}
//		System.out.println("config changed extra items " + itemIds.size() + " " + matchTypes.size());
	}

	@Provides
	public ClueScrollJugginglingConfig getConfig(ConfigManager configManager) {
		return configManager.getConfig(ClueScrollJugginglingConfig.class);
	}

	private void saveDroppedClues()
	{
//		System.out.println("saving clues: " + gson.toJson(droppedClues));
		configManager.setRSProfileConfiguration(CONFIG_GROUP, "clueData", gson.toJson(droppedClues));
	}

	private DroppedClue getDroppedClue(GroundItemKey groundItemKey)
	{
		for (DroppedClue droppedClue : droppedClues)
		{
			if (droppedClue.groundItemKey.equals(groundItemKey)) {
				return droppedClue;
			}
		}
		return null;
	}

	private void addInfobox(DroppedClue droppedClue)
	{
		removeOrphanedInfoboxes();
		if (config.combineTimers() && droppedClues.size() > 1) {
			if (combinedTimer == null) {
				log.debug("adding combined infobox");
				for (DroppedClue clue : droppedClues)
				{
					infoBoxManager.removeInfoBox(clue.infobox);
				}
				combinedTimer = new InfoBox(itemManager.getImage(23814), this) {
					@Override
					public Color getTextColor()
					{
						for (DroppedClue clue : droppedClues)
						{
							if (clue.invalidTimer || clue.notified) return Color.RED;
						}
						return Color.WHITE;
					}

					@Override
					public String getText() {
						if (droppedClues.isEmpty()) return "none";

						DroppedClue lowestTimeClue = null;
						int lowestTime = Integer.MAX_VALUE;
						for (DroppedClue clue : droppedClues)
						{
							if (clue.invalidTimer) return "?";
							int time = (int) clue.getDuration(config.dropTimerReduction()).getSeconds();
							if (time < lowestTime) {
								lowestTime = time;
								lowestTimeClue = clue;
							}
						}

						Duration timeLeft = lowestTimeClue.getDuration(config.dropTimerReduction());
						long remainingMinutes = timeLeft.toMinutes();
						return remainingMinutes >= 10
							? String.format("%dm", remainingMinutes)
							: formatWithSeconds(timeLeft);
					}
				};
				infoBoxManager.addInfoBox(combinedTimer);
				List<OverlayMenuEntry> menuEntries = combinedTimer.getMenuEntries();
				menuEntries.add(new OverlayMenuEntry(MenuAction.RUNELITE_OVERLAY, "infobox jank", "clue scroll combined"));
				combinedTimer.setMenuEntries(menuEntries);
			}
		} else {
			log.debug("adding infobox");
			InfoBox timer = new InfoBox(itemManager.getImage(droppedClue.groundItemKey.getItemId() == ItemID.CHALLENGE_SCROLL_ELITE ? ItemID.DEERSTALKER : droppedClue.groundItemKey.getItemId()), this)
			{
				@Override
				public Color getTextColor()
				{
					return droppedClue.invalidTimer || droppedClue.notified
						? Color.RED
						: Color.WHITE;
				}

				@Override
				public String getText()
				{
					if (droppedClue.invalidTimer) return "?";
					Duration timeLeft = droppedClue.getDuration(config.dropTimerReduction());
					long remainingMinutes = timeLeft.toMinutes();
					return remainingMinutes >= 10
						? String.format("%dm", remainingMinutes)
						: formatWithSeconds(timeLeft);
				}
			};
			infoBoxManager.addInfoBox(timer);
			List<OverlayMenuEntry> menuEntries = timer.getMenuEntries();
			menuEntries.add(new OverlayMenuEntry(MenuAction.RUNELITE_OVERLAY, "infobox jank", "" + droppedClue.hashCode()));
			timer.setMenuEntries(menuEntries);
			droppedClue.infobox = timer;
		}
	}

	@Subscribe
	public void onMenuOpened(MenuOpened e) {
		MenuEntry[] menuEntries = client.getMenuEntries();
		for (int i = 0; i < menuEntries.length; i++)
		{
			MenuEntry menuEntry = menuEntries[i];
			if (menuEntry.getType() == MenuAction.RUNELITE_OVERLAY && menuEntry.getOption().equals("infobox jank")) {
				if (menuEntry.getTarget().contains("clue scroll combined"))
				{
					menuEntry.setOption("Clue timers");
					menuEntry.setTarget("(" + droppedClues.size() + ")");
					for (int i1 = 0; i1 < droppedClues.size(); i1++)
					{
						DroppedClue droppedClue = droppedClues.get(i1);
						addClueMenuEntries(i, droppedClue, "clue " + i1);
					}
				} else {
					int hashCode = Integer.parseInt(Text.removeTags(menuEntry.getTarget()));
					for (DroppedClue droppedClue : droppedClues)
					{
						if (droppedClue.hashCode() == hashCode) {
							addClueMenuEntries(i, droppedClue, "clue");
							client.setMenuEntries(ArrayUtils.remove(client.getMenuEntries(), i + 2));
							break;
						}
					}
				}
				break;
			}
		}
	}

	private void addClueMenuEntries(int index, DroppedClue droppedClue, String target)
	{
		ItemComposition itemComposition = itemManager.getItemComposition(droppedClue.groundItemKey.getItemId());
		ClueTier clueTier = ClueTier.getClueTier(itemComposition);
		String name = clueTier != null ? clueTier.getColoredName() : itemComposition.getMembersName();
		client.createMenuEntry(index).setOption("| " + name).setTarget(droppedClue.invalidTimer ? "?" : formatWithSeconds(droppedClue.getDuration(config.dropTimerReduction())));
		client.createMenuEntry(index).setOption("|     Remove").setTarget(target).onClick(e1 -> {
			log.debug("manual infobox removal " + droppedClue);
			removeClue(droppedClue);
		});
//		client.createMenuEntry(i).setOption("   ").setTarget(droppedClue.groundItemKey.getLocation().toString());
	}

	private String formatWithSeconds(Duration timeLeft) {
		int seconds = (int) (timeLeft.toMillis() / 1000L);

		int minutes = (seconds % 3600) / 60;
		int secs = seconds % 60;

		return String.format("%d:%02d", minutes, secs);
	}

	private boolean showNotifications() {
		return config.notificationTime() > 0;
	}

	@Subscribe
	public void onItemSpawned(ItemSpawned itemSpawned)
	{
		TileItem item = itemSpawned.getItem();
		ItemComposition itemComposition = itemManager.getItemComposition(item.getId());
		if (!isExtraItemMatched(itemComposition)) {
			ClueTier clueTier = ClueTier.getClueTier(itemComposition);
			if (clueTier == null || !clueTier.showTimers(config)) return;
		}

		GroundItem groundItem = groundItemPluginStuff.buildGroundItem(itemSpawned.getTile(), item);
		itemsSpawned.add(groundItem);
		gameTick = client.getTickCount();
	}

	private boolean isExtraItemMatched(ItemComposition itemComposition)
	{
		if (itemIds.contains(itemComposition.getId())) {
			return true;
		}
		String membersName = Text.standardize(itemComposition.getMembersName().toLowerCase());
		for (int i = 0; i < matchTypes.size(); i++)
		{
			MatchType matchType = matchTypes.get(i);
			String matchString = matchStrings.get(i);
			if (matchType.matches(membersName, matchString)) {
				return true;
			}
		}
		return false;
	}

	@Subscribe
	public void onItemDespawned(ItemDespawned itemDespawned)
	{
		TileItem item = itemDespawned.getItem();
		ItemComposition itemComposition = itemManager.getItemComposition(item.getId());

		GroundItemKey groundItemKey = new GroundItemKey(item.getId(), itemDespawned.getTile().getWorldLocation());
		itemsDespawned.add(groundItemKey);
		gameTick = client.getTickCount();
	}

	private void removeClue(DroppedClue droppedClue)
	{
		boolean removed = droppedClues.remove(droppedClue);
		log.debug("removed clue " + removed + " " + droppedClues.size());
		saveDroppedClues();
		if (droppedClue.infobox != null) {
			infoBoxManager.removeInfoBox(droppedClue.infobox);
			log.debug("|  removed infobox");
		}
		if (combinedTimer != null && droppedClues.size() <= 1)
		{
			infoBoxManager.removeInfoBox(combinedTimer);
			log.debug("|  removed combined infobox");
			combinedTimer = null;
			if (droppedClues.size() == 1) {
				addInfobox(droppedClues.get(0));
			}
		}

		removeOrphanedInfoboxes();
	}

	private void removeOrphanedInfoboxes()
	{
		next_infobox:
		for (InfoBox infoBox : infoBoxManager.getInfoBoxes()) {
			if (infoBox.getClass().getName().contains("ClueScrollJugglingPlugin")) {
				if (infoBox == combinedTimer) continue next_infobox;
				if (combinedTimer == null) {
					for (DroppedClue droppedClue : droppedClues) {
						if (infoBox == droppedClue.infobox) continue next_infobox;
					}
				}
				infoBoxManager.removeInfoBox(infoBox);
				log.error("removed orphaned infobox");
			}
		}
		if (droppedClues.size() <= 1) {
			infoBoxManager.removeInfoBox(combinedTimer);
			combinedTimer = null;
		}
	}

	@Subscribe
	public void onGameTick(GameTick e) {
		if (client.getTickCount() == gameTick) {
			handleItemSpawns();
		}

		if (!droppedClues.isEmpty()) {
			int dropTime = config.dropTimerReduction();
			if (showNotifications())
			{
				for (DroppedClue droppedClue : droppedClues)
				{
					if (!droppedClue.notified && droppedClue.getDuration(dropTime).compareTo(Duration.ofSeconds(config.notificationTime())) < 0)
					{
						notifier.notify("Your clue scroll is about to disappear!");
						droppedClue.notified = true;
					}
				}
			}
			List<DroppedClue> toRemove = new ArrayList<>();
			for (int i = 0; i < droppedClues.size(); i++)
			{
				DroppedClue droppedClue = droppedClues.get(i);
				if (droppedClue.isExpired(dropTime)) {
					toRemove.add(droppedClue);
				}
			}
			for (DroppedClue droppedClue : toRemove)
			{
				log.debug("removing infobox due to expiry");
				removeClue(droppedClue);
			}
			if (toRemove.size() > 0) log.debug("removed " + toRemove.size());
			toRemove.clear();
		}
	}

	private int gameTick = -1;
	private final List<GroundItem> itemsSpawned = new ArrayList<>();
	private final List<GroundItemKey> itemsDespawned = new ArrayList<>();

	private void handleItemSpawns()
	{
		// It is possible for fake despawns to happen when you go up and down ladders or go near an item (like 20 tiles or so?). This is detectable by a despawn followed by a spawn. I want to skip these fake despawns.
		outer:
		for (GroundItemKey groundItemKey : itemsDespawned)
		{
			// Check if it's a real despawn by seeing if there is a spawn event in the same tick.
			int itemId = groundItemKey.getItemId();
			for (int i = 0; i < itemsSpawned.size(); i++)
			{
				GroundItem groundItem = itemsSpawned.get(i);
				if (groundItem.getId() == itemId && groundItem.getLocation().equals(groundItemKey.getLocation())) {
					log.debug(client.getTickCount() + " item despawned (fake) " + itemId + " " + itemManager.getItemComposition(itemId).getMembersName());
					itemsSpawned.remove(i);
					continue outer;
				}
			}

			// This is a real despawn.
			DroppedClue droppedClue = getDroppedClue(groundItemKey);
			log.debug(client.getTickCount() + " item despawned " + itemId + " " + itemManager.getItemComposition(itemId).getMembersName() + " " + (droppedClue != null));
			if (droppedClue != null) {
				removeClue(droppedClue);
			}
		}
		for (GroundItem groundItem : itemsSpawned)
		{
			GroundItemKey groundItemKey = new GroundItemKey(groundItem.getId(), groundItem.getLocation());
			if (getDroppedClue(groundItemKey) == null) {
				Instant instant = groundItemPluginStuff.calculateDespawnTime(groundItem);
				if (instant == null) {
					log.debug("spawned item (null instant) " + groundItemKey.getItemId() + " " + itemManager.getItemComposition(groundItemKey.getItemId()).getMembersName());
					continue;
				}
				Duration between = Duration.between(Instant.now(), instant);
				DroppedClue droppedClue = new DroppedClue(Instant.now(), (int) between.getSeconds(), groundItemKey);
				droppedClues.add(droppedClue);
				saveDroppedClues();
				log.debug("adding infobox from spawned item " + groundItemKey.getItemId() + " " + itemManager.getItemComposition(groundItemKey.getItemId()).getMembersName());
				addInfobox(droppedClue);
			} else {
				log.debug("spawned item (already tracked) " + groundItemKey.getItemId() + " " + itemManager.getItemComposition(groundItemKey.getItemId()).getMembersName());
			}
		}
		itemsSpawned.clear();
		itemsDespawned.clear();
	}

	@Override
	protected void startUp()
	{
		lastGameState = -1;
		updateExtraItems();
		clientThread.invokeLater(() -> {
			if (lastGameState == -1) lastGameState = GameState.LOGIN_SCREEN.getState();
			GameStateChanged gameStateChanged = new GameStateChanged();
			gameStateChanged.setGameState(client.getGameState());
			this.onGameStateChanged(gameStateChanged);
		});
		eventBus.register(groundItemPluginStuff);
	}

	@Override
	protected void shutDown()
	{
		clientThread.invokeLater(() -> {
			onLogout();
			configManager.setRSProfileConfiguration(CONFIG_GROUP, "timesAreAccurate", false);
		});
		eventBus.unregister(groundItemPluginStuff);
	}

	@RequiredArgsConstructor
	enum ClueTier
	{
		BEGINNER(config -> config.beginnerTimers(), ColorUtil.wrapWithColorTag("Beginner", Color.decode("#cdd19b"))),
		EASY(config -> config.easyTimers(), ColorUtil.wrapWithColorTag("Easy", Color.decode("#2d9928"))),
		MEDIUM(config -> config.mediumTimers(), ColorUtil.wrapWithColorTag("Medium", Color.decode("#48c26f"))),
		HARD(config -> config.hardTimers(), ColorUtil.wrapWithColorTag("Hard", Color.decode("#7627ab"))),
		ELITE(config -> config.eliteTimers(), ColorUtil.wrapWithColorTag("Elite", Color.decode("#9da836"))),
		MASTER(config -> config.masterTimers(), ColorUtil.wrapWithColorTag("Master", Color.decode("#96421b"))),
		ELITE_SHERLOCK_CHALLENGE(config -> config.eliteTimers(), ColorUtil.wrapWithColorTag("Elite (sherlock challenge)", Color.decode("#9da836"))),
		;

		private final Predicate<ClueScrollJugginglingConfig> showTimer;
		@Getter
		public final String coloredName;

		public static ClueTier getClueTier(ItemComposition itemComposition)
		{
			if (itemComposition.getId() == ItemID.CHALLENGE_SCROLL_ELITE) {
				return ClueTier.ELITE_SHERLOCK_CHALLENGE;
			}
			String clueName = itemComposition.getMembersName();
			return
				clueName.equals("Clue scroll (beginner)") ? ClueTier.BEGINNER :
				clueName.equals("Clue scroll (easy)") ? ClueTier.EASY :
				clueName.equals("Clue scroll (medium)") ? ClueTier.MEDIUM :
				clueName.equals("Clue scroll (hard)") ? ClueTier.HARD :
				clueName.equals("Clue scroll (elite)") ? ClueTier.ELITE :
				clueName.equals("Clue scroll (master)") ? ClueTier.MASTER :
				null
				;
		}

		public boolean showTimers(ClueScrollJugginglingConfig config)
		{
			return showTimer.test(config);
		}
	}

	@Subscribe
	public void onCommandExecuted(CommandExecuted e) {
		if (e.getCommand().equals("clearclues")) {
			log.warn("clearclues");
			for (DroppedClue droppedClue : new ArrayList<>(droppedClues))
			{
				removeClue(droppedClue);
			}
			removeOrphanedInfoboxes();
		}
	}
}
