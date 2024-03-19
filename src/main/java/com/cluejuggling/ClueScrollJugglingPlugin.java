package com.cluejuggling;

import com.cluejuggling.GroundItem.GroundItemKey;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.google.inject.Provides;
import java.awt.Color;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import javax.inject.Inject;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.ItemComposition;
import net.runelite.api.ItemID;
import net.runelite.api.Tile;
import net.runelite.api.TileItem;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.ItemDespawned;
import net.runelite.api.events.ItemSpawned;
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
import net.runelite.client.ui.overlay.infobox.InfoBoxManager;
import net.runelite.client.ui.overlay.infobox.Timer;

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
	private Timer combinedTimer = null;

	@Data
	public static final class DroppedClue
	{
		public DroppedClue(Instant startTime, int timeRemaining, GroundItemKey groundItemKey, boolean droppedByPlayer) {
			this.startTime = startTime;
			this.timeRemaining = timeRemaining;
			this.groundItemKey = groundItemKey;
			this.droppedByPlayer = droppedByPlayer;
		}

		transient Instant startTime;
		int timeRemaining;
		final GroundItemKey groundItemKey;
		boolean notified = false;
		transient boolean invalidTimer = false;
		boolean droppedByPlayer;

		transient Timer infobox = null; // if it exists

		public Duration getDuration(int dropTime)
		{
			return Duration.between(Instant.now(), startTime.plus(Duration.ofSeconds(timeRemaining)))
				.plus(Duration.ofMinutes(droppedByPlayer ? dropTime - 60 : 0));
		}
	}

	private void onLogin() {
//		System.out.println("login " + Thread.currentThread().getName());

		Boolean timesAreAccurate = configManager.getRSProfileConfiguration(CONFIG_GROUP, "timesAreAccurate", Boolean.class);
		if (timesAreAccurate == null) timesAreAccurate = false;
		configManager.setRSProfileConfiguration(CONFIG_GROUP, "timesAreAccurate", false);

		droppedClues = gson.fromJson(configManager.getRSProfileConfiguration(CONFIG_GROUP, "clueData"), new TypeToken<List<DroppedClue>>(){}.getType());
//		System.out.println("   dropped clues is " + droppedClues);
		if (droppedClues == null) droppedClues = new ArrayList<>();
		// Unfortunately, varc 526 (play time) is not sent unless the tab that shows it is selected.
		for (DroppedClue droppedClue : droppedClues)
		{
			droppedClue.startTime = Instant.now();
			if (!timesAreAccurate) droppedClue.invalidTimer = true;
			addInfobox(droppedClue);
		}
	}

	private void onLogout() {
//		System.out.println("logout " + Thread.currentThread().getName());
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

	private int lastGameState = 0;
	@Subscribe
	public void onGameStateChanged(GameStateChanged e) {
//		System.out.println("game state changed from " + lastGameState + " to " + e.getGameState());
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
		});
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged e) {
		if (e.getGroup().equals(CONFIG_GROUP) && e.getKey().equals("combineTimers")) {
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
		}
	}

	@Provides
	public ClueScrollJugginglingConfig getConfig(ConfigManager configManager) {
		return configManager.getConfig(ClueScrollJugginglingConfig.class);
	}

	@Subscribe
	public void onItemSpawned(ItemSpawned itemSpawned)
	{
		TileItem item = itemSpawned.getItem();
		ItemComposition itemComposition = itemManager.getItemComposition(item.getId());
		ClueTier clueTier = ClueTier.getClueTier(itemComposition.getName());
		if (clueTier == null || !clueTier.showTimers(config)) return;
		Tile tile = itemSpawned.getTile();

		GroundItemKey groundItemKey = new GroundItemKey(item.getId(), tile.getWorldLocation());

		if (getDroppedClue(groundItemKey) == null) {
			GroundItem groundItem = groundItemPluginStuff.buildGroundItem(tile, item);
			Instant instant = groundItemPluginStuff.calculateDespawnTime(groundItem);
			if (instant == null) return;
			Duration between = Duration.between(Instant.now(), instant);
			DroppedClue droppedClue = new DroppedClue(Instant.now(), (int) between.getSeconds(), groundItemKey, groundItem.getLootType() == LootType.DROPPED);
			droppedClues.add(droppedClue);
			saveDroppedClues();
			addInfobox(droppedClue);
		}
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
		if (config.combineTimers() && droppedClues.size() > 1) {
			if (combinedTimer == null) {
				for (DroppedClue clue : droppedClues)
				{
					infoBoxManager.removeInfoBox(clue.infobox);
				}
				combinedTimer = new Timer(droppedClue.timeRemaining, ChronoUnit.SECONDS, itemManager.getImage(23814), this) {
					@Override
					public Color getTextColor()
					{
						for (DroppedClue clue : droppedClues)
						{
							if (clue.invalidTimer || clue.notified) return Color.RED;
						}
						return super.getTextColor();
					}

					@Override
					public String getText() {
						if (droppedClues.isEmpty()) return "none";

						DroppedClue lowestTimeClue = null;
						int lowestTime = Integer.MAX_VALUE;
						for (DroppedClue clue : droppedClues)
						{
							if (clue.invalidTimer) return "?";
							int time = (int) clue.getDuration(config.hourDropTimer()).getSeconds();
							if (time < lowestTime) {
								lowestTime = time;
								lowestTimeClue = clue;
							}
						}

						Duration timeLeft = lowestTimeClue.getDuration(config.hourDropTimer());
						long remainingMinutes = timeLeft.toMinutes();
						return remainingMinutes >= 10
							? String.format("%dm", remainingMinutes)
							: formatWithSeconds(timeLeft);
					}
				};
				infoBoxManager.addInfoBox(combinedTimer);
			}
		} else {
			Timer timer = new Timer(droppedClue.timeRemaining, ChronoUnit.SECONDS, itemManager.getImage(droppedClue.groundItemKey.getItemId()), this)
			{
				@Override
				public Color getTextColor()
				{
					return (droppedClue.invalidTimer || droppedClue.notified)
						? Color.RED
						: super.getTextColor();
				}

				@Override
				public String getText()
				{
					if (droppedClue.invalidTimer) return "?";
					Duration timeLeft = droppedClue.getDuration(config.hourDropTimer());
					long remainingMinutes = timeLeft.toMinutes();
					return remainingMinutes >= 10
						? String.format("%dm", remainingMinutes)
						: formatWithSeconds(timeLeft);
				}
			};
			infoBoxManager.addInfoBox(timer);
			droppedClue.infobox = timer;
		}
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
	public void onItemDespawned(ItemDespawned itemDespawned)
	{
		TileItem item = itemDespawned.getItem();
		ItemComposition itemComposition = itemManager.getItemComposition(item.getId());
		if (!itemComposition.getName().toLowerCase().startsWith("clue scroll")) return;
		Tile tile = itemDespawned.getTile();

		GroundItemKey groundItemKey = new GroundItemKey(item.getId(), tile.getWorldLocation());

		DroppedClue droppedClue = getDroppedClue(groundItemKey);
		if (droppedClue != null) {
			droppedClues.remove(droppedClue);
			saveDroppedClues();
			if (droppedClue.infobox != null) {
				infoBoxManager.removeInfoBox(droppedClue.infobox);
			}
			if (combinedTimer != null && droppedClues.size() <= 1)
			{
				infoBoxManager.removeInfoBox(combinedTimer);
				combinedTimer = null;
				if (droppedClues.size() == 1) {
					addInfobox(droppedClues.get(0));
				}
			}
		}
	}

	@Subscribe
	public void onGameTick(GameTick gameTick) {
		if (!droppedClues.isEmpty() && showNotifications())
		{
			for (DroppedClue droppedClue : droppedClues)
			{
				if (!droppedClue.notified && droppedClue.getDuration(config.hourDropTimer()).compareTo(Duration.ofSeconds(config.notificationTime())) < 0)
				{
					notifier.notify("Your clue scroll is about to disappear!");
					droppedClue.notified = true;
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

	@RequiredArgsConstructor
	enum ClueTier
	{
		BEGINNER(config -> config.beginnerTimers()),
		EASY(config -> config.easyTimers()),
		MEDIUM(config -> config.mediumTimers()),
		HARD(config -> config.hardTimers()),
		ELITE(config -> config.eliteTimers()),
		MASTER(config -> config.masterTimers())
		;

		private final Predicate<ClueScrollJugginglingConfig> showTimer;

		public static ClueTier getClueTier(String clueName)
		{
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
}
