/*
 * Copyright (c) 2017, Tyler <https://github.com/tylerthardy>
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
package net.runelite.client.plugins.slayer;

import com.google.common.eventbus.Subscribe;
import com.google.inject.Provides;
import java.awt.image.BufferedImage;
import java.time.Instant;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.inject.Inject;
import joptsimple.internal.Strings;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.ItemID;
import static net.runelite.api.Skill.SLAYER;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.ConfigChanged;
import net.runelite.api.events.ExperienceChanged;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetInfo;
import net.runelite.client.Notifier;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.game.ItemManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.task.Schedule;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.infobox.InfoBoxManager;
import net.runelite.client.util.Text;

@PluginDescriptor(
	name = "Slayer"
)
@Slf4j
public class SlayerPlugin extends Plugin
{
	//Chat messages
	private static final Pattern CHAT_GEM_PROGRESS_MESSAGE = Pattern.compile("You're assigned to kill (.*); only (\\d*) more to go\\.");
	private static final String CHAT_GEM_COMPLETE_MESSAGE = "You need something new to hunt.";
	private static final Pattern CHAT_COMPLETE_MESSAGE = Pattern.compile("[\\d]+(?:,[\\d]+)?");
	private static final String CHAT_CANCEL_MESSAGE = "Your task has been cancelled.";
	private static final String CHAT_SUPERIOR_MESSAGE = "A superior foe has appeared...";

	//NPC messages
	private static final Pattern NPC_ASSIGN_MESSAGE = Pattern.compile(".*Your new task is to kill (\\d*) (.*)\\.");
	private static final Pattern NPC_CURRENT_MESSAGE = Pattern.compile("You're still hunting (.*), you have (\\d*) to go\\..*");

	//Reward UI
	private static final Pattern REWARD_POINTS = Pattern.compile("Reward points: (\\d*)");

	@Inject
	private Client client;

	@Inject
	private SlayerConfig config;

	@Inject
	private SlayerOverlay overlay;

	@Inject
	private InfoBoxManager infoBoxManager;

	@Inject
	private ItemManager itemManager;

	@Inject
	private Notifier notifier;

	@Inject
	private ClientThread clientThread;

	private String taskName;
	private int amount;
	private TaskCounter counter;
	private int streak;
	private int points;
	private int cachedXp;
	private Instant infoTimer;
	private boolean loginFlag;

	@Override
	protected void startUp() throws Exception
	{
		if (client.getGameState() == GameState.LOGGED_IN
			&& config.amount() != -1
			&& !config.taskName().isEmpty())
		{
			clientThread.invokeLater(() -> setTask(config.taskName(), config.amount()));
		}
	}

	@Override
	protected void shutDown() throws Exception
	{
		removeCounter();
	}

	@Provides
	SlayerConfig getConfig(ConfigManager configManager)
	{
		return configManager.getConfig(SlayerConfig.class);
	}

	@Subscribe
	public void onGameStateChange(GameStateChanged event)
	{
		switch (event.getGameState())
		{
			case HOPPING:
			case LOGGING_IN:
				cachedXp = 0;
				taskName = "";
				amount = 0;
				loginFlag = true;
				break;
			case LOGGED_IN:
				if (config.amount() != -1
					&& !config.taskName().isEmpty()
					&& loginFlag == true)
				{
					setTask(config.taskName(), config.amount());
					loginFlag = false;
				}
				break;
		}
	}

	private void save()
	{
		config.amount(amount);
		config.taskName(taskName);
		config.points(points);
		config.streak(streak);
	}

	@Schedule(
		period = 600,
		unit = ChronoUnit.MILLIS
	)
	public void scheduledChecks()
	{
		Widget NPCDialog = client.getWidget(WidgetInfo.DIALOG_NPC_TEXT);
		if (NPCDialog != null)
		{
			String NPCText = Text.removeTags(NPCDialog.getText()); //remove color and linebreaks
			Matcher mAssign = NPC_ASSIGN_MESSAGE.matcher(NPCText); //number, name
			Matcher mCurrent = NPC_CURRENT_MESSAGE.matcher(NPCText); //name, number
			boolean found1 = mAssign.find();
			boolean found2 = mCurrent.find();
			if (!found1 && !found2)
			{
				return;
			}
			String taskName = found1 ? mAssign.group(2) : mCurrent.group(1);
			int amount = Integer.parseInt(found1 ? mAssign.group(1) : mCurrent.group(2));

			setTask(taskName, amount);
		}

		Widget rewardsBarWidget = client.getWidget(WidgetInfo.SLAYER_REWARDS_TOPBAR);
		if (rewardsBarWidget != null)
		{
			for (Widget w : rewardsBarWidget.getDynamicChildren())
			{
				Matcher mPoints = REWARD_POINTS.matcher(w.getText());
				if (mPoints.find())
				{
					points = Integer.parseInt(mPoints.group(1));
					break;
				}
			}
		}

		if (infoTimer != null)
		{
			Duration timeSinceInfobox = Duration.between(infoTimer, Instant.now());
			Duration statTimeout = Duration.ofMinutes(config.statTimeout());

			if (timeSinceInfobox.compareTo(statTimeout) >= 0)
			{
				removeCounter();
			}
		}
	}

	@Subscribe
	public void onChatMessage(ChatMessage event)
	{
		if (event.getType() != ChatMessageType.SERVER)
		{
			return;
		}

		String chatMsg = Text.removeTags(event.getMessage()); //remove color and linebreaks
		if (chatMsg.endsWith("; return to a Slayer master."))
		{
			Matcher mComplete = CHAT_COMPLETE_MESSAGE.matcher(chatMsg);

			List<String> matches = new ArrayList<>();
			while (mComplete.find())
			{
				matches.add(mComplete.group(0));
			}

			switch (matches.size())
			{
				case 0:
					streak = 1;
					break;
				case 1:
					streak = Integer.parseInt(matches.get(0));
					break;
				case 3:
					streak = Integer.parseInt(matches.get(0));
					points = Integer.parseInt(matches.get(2).replaceAll(",", ""));
					break;
				default:
					log.warn("Unreachable default case for message ending in '; return to Slayer master'");
			}
			setTask("", 0);
			return;
		}

		if (chatMsg.equals(CHAT_GEM_COMPLETE_MESSAGE) || chatMsg.equals(CHAT_CANCEL_MESSAGE))
		{
			setTask("", 0);
			return;
		}

		if (config.showSuperiorNotification() && chatMsg.equals(CHAT_SUPERIOR_MESSAGE))
		{
			notifier.notify(CHAT_SUPERIOR_MESSAGE);
			return;
		}

		Matcher mProgress = CHAT_GEM_PROGRESS_MESSAGE.matcher(chatMsg);
		if (!mProgress.find())
		{
			return;
		}
		String taskName = mProgress.group(1);
		int amount = Integer.parseInt(mProgress.group(2));

		setTask(taskName, amount);
	}

	@Subscribe
	public void onExperienceChanged(ExperienceChanged event)
	{
		if (event.getSkill() != SLAYER)
		{
			return;
		}

		int slayerExp = client.getSkillExperience(SLAYER);

		if (slayerExp <= cachedXp)
		{
			return;
		}

		if (cachedXp == 0)
		{
			// this is the initial xp sent on login
			cachedXp = slayerExp;
			return;
		}

		killedOne();
		cachedXp = slayerExp;
	}

	@Subscribe
	private void onConfigChanged(ConfigChanged event)
	{
		if (!event.getGroup().equals("slayer"))
		{
			return;
		}

		if (config.showInfobox())
		{
			clientThread.invokeLater(this::addCounter);
		}
		else
		{
			removeCounter();
		}
	}

	private void killedOne()
	{
		if (amount == 0)
		{
			return;
		}

		amount--;
		config.amount(amount); // save changed value

		if (!config.showInfobox())
		{
			return;
		}

		// add and update counter, set timer
		addCounter();
		counter.setText(String.valueOf(amount));
		infoTimer = Instant.now();
	}

	private void setTask(String name, int amt)
	{
		taskName = name.toLowerCase();
		amount = amt;
		save();
		removeCounter();
		addCounter();
		infoTimer = Instant.now();
	}

	private void addCounter()
	{
		if (!config.showInfobox() || counter != null || Strings.isNullOrEmpty(taskName))
		{
			return;
		}

		Task task = Task.getTask(taskName);
		int itemSpriteId = ItemID.ENCHANTED_GEM;
		if (task != null)
		{
			itemSpriteId = task.getItemSpriteId();
		}

		BufferedImage taskImg = itemManager.getImage(itemSpriteId);
		counter = new TaskCounter(taskImg, this, amount);
		counter.setTooltip(String.format("<col=ff7700>%s</br><col=ffff00>Pts:</col> %s</br><col=ffff00>Streak:</col> %s",
			capsString(taskName), points, streak));

		infoBoxManager.addInfoBox(counter);
	}

	private void removeCounter()
	{
		if (counter == null)
		{
			return;
		}

		infoBoxManager.removeInfoBox(counter);
		counter = null;
	}

	//Getters
	@Override
	public Overlay getOverlay()
	{
		return overlay;
	}

	public String getTaskName()
	{
		return taskName;
	}

	void setTaskName(String taskName)
	{
		this.taskName = taskName;
	}

	public int getAmount()
	{
		return amount;
	}

	void setAmount(int amount)
	{
		this.amount = amount;
	}

	public int getStreak()
	{
		return streak;
	}

	void setStreak(int streak)
	{
		this.streak = streak;
	}

	public int getPoints()
	{
		return points;
	}

	void setPoints(int points)
	{
		this.points = points;
	}

	//Utils
	private String capsString(String str)
	{
		return str.substring(0, 1).toUpperCase() + str.substring(1);
	}
}
