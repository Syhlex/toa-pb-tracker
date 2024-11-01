package com.toapbtracker;

import com.google.gson.Gson;
import com.google.inject.Provides;

import javax.inject.Inject;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.ScriptPostFired;
import net.runelite.api.events.WidgetLoaded;
import net.runelite.api.widgets.*;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.chat.ChatColorType;
import net.runelite.client.chat.ChatMessageBuilder;
import net.runelite.client.chat.ChatMessageManager;
import net.runelite.client.chat.QueuedMessage;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;

import java.awt.*;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@PluginDescriptor(
    name = "ToA PB Tracker"
)
public class ToaPbTrackerPlugin extends Plugin {
    @Inject
    private Client client;

    @Inject
    private ClientThread clientThread;

    @Inject
    private ToaPbTrackerConfig config;

    @Inject
    private ConfigManager configManager;

    @Inject
    private Gson gson;

    @Inject
    private ChatMessageManager chatMessageManager;

    private static final Pattern CHALLENGE_DURATION = Pattern.compile("(?i)challenge completion time: <col=[0-9a-f]{6}>(?<time>[0-9:]+(?:\\.[0-9]+)?)</col>\\. Personal best: (?:<col=ff0000>)?(?<pb>[0-9:]+(?:\\.[0-9]+)?)");
    private static final Pattern CHALLENGE_DURATION_NEW_PB = Pattern.compile("(?i)challenge completion time: <col=[0-9a-f]{6}>(?<pb>[0-9:]+(?:\\.[0-9]+)?)</col> \\(new personal best\\)");
    private static final Pattern TOTAL_DURATION = Pattern.compile("(?i)total completion time: <col=[0-9a-f]{6}>(?<time>[0-9:]+(?:\\.[0-9]+)?)</col>\\. Personal best: (?:<col=ff0000>)?(?<pb>[0-9:]+(?:\\.[0-9]+)?)");
    private static final Pattern TOTAL_DURATION_NEW_PB = Pattern.compile("(?i)total completion time: <col=[0-9a-f]{6}>(?<pb>[0-9:]+(?:\\.[0-9]+)?)</col> \\(new personal best\\)");
    private static final Pattern KILLCOUNT_PATTERN = Pattern.compile("Your completed (?<boss>.+?) count is: <col=[0-9a-f]{6}>(?<kc>[0-9,]+)</col>");

    private double challengeTimeInSeconds;
    private double totalTimeInSeconds;

    private static final int RAID_PARTY_INTERFACE_GROUP_ID = 774;
    private static final int INVOCATIONS_SIDEPANEL = 50724921;
    private static final int TOA_PARTY_WIDGET_SCRIPT_ID = 6617;

    private Widget pbInfoPanel;

    @Provides
    ToaPbTrackerConfig provideConfig(ConfigManager configManager) {
        return configManager.getConfig(ToaPbTrackerConfig.class);
    }

    @Override
    protected void startUp() throws Exception {
        clientThread.invokeLater(this::createPbInfoPanel);
    }

    @Override
    protected void shutDown() throws Exception {
        clientThread.invokeLater(() -> {
            pbInfoPanel.setHidden(true);
            pbInfoPanel.revalidate();
            pbInfoPanel = null;
        });
    }

    @Subscribe
    public void onScriptPostFired(ScriptPostFired e) {
        if (e.getScriptId() != TOA_PARTY_WIDGET_SCRIPT_ID || pbInfoPanel == null) {
            return;
        }

        Widget raidLevelWidget = client.getWidget(RAID_PARTY_INTERFACE_GROUP_ID, 90);
        if (raidLevelWidget == null) {
            return;
        }

        int raidLevel = Integer.parseInt(raidLevelWidget.getText());

        Widget membersTab = client.getWidget(RAID_PARTY_INTERFACE_GROUP_ID, 6);
        if (membersTab == null) {
            return;
        }

        Widget membersTabText = membersTab.getChild(3);
        if (membersTabText == null) {
            return;
        }

        int teamSize = Integer.parseInt(membersTabText.getText().substring(9, 10)); // Gets n from "Members (n)"

        PbInfo challengePbInfo = getPbFromConfig(raidLevel, teamSize, CompletionType.CHALLENGE);
        double challengePb = challengePbInfo == null ? -1 : challengePbInfo.getPbTime();
        String challengePbTimeString = challengePb == -1 ? "N/A" : secondsToTimeString(challengePb);

        PbInfo totalPbInfo = getPbFromConfig(raidLevel, teamSize, CompletionType.TOTAL);
        double totalPb = totalPbInfo == null ? -1 : totalPbInfo.getPbTime();
        String totalPbTimeString = totalPb == -1 ? "N/A" : secondsToTimeString(totalPb);

        pbInfoPanel.setText(
            "Raid level: <col=ffffff>" + raidLevel + "</col><br>" +
                "Team size: <col=ffffff>" + teamSize + "</col><br>" +
                "Challenge PB: <col=ffffff>" + challengePbTimeString + "</col><br>" +
                "Total PB: <col=ffffff>" + totalPbTimeString + "</col>"
        );
        pbInfoPanel.revalidate();
    }

    private void createPbInfoPanel() {
        Widget invocationsSidepanel = client.getWidget(INVOCATIONS_SIDEPANEL);
        if (invocationsSidepanel == null) {
            return;
        }

        if (pbInfoPanel != null) {
            pbInfoPanel.setHidden(false);
            pbInfoPanel.revalidate();

            final Widget[] children = invocationsSidepanel.getDynamicChildren();
            for (Widget child : children) {
                if (child.equals(pbInfoPanel)) {
                    return;
                }
            }
        }

        pbInfoPanel = invocationsSidepanel.createChild(2, WidgetType.TEXT);
        pbInfoPanel.setWidthMode(WidgetSizeMode.MINUS);
        pbInfoPanel.setOriginalHeight(112);
        pbInfoPanel.setOriginalY(270);
        pbInfoPanel.setXTextAlignment(WidgetTextAlignment.CENTER);
        pbInfoPanel.setYTextAlignment(WidgetTextAlignment.CENTER);
        pbInfoPanel.setTextShadowed(true);
        pbInfoPanel.setLineHeight(15);
        pbInfoPanel.setFontId(FontID.PLAIN_12);
        pbInfoPanel.setTextColor(0xff981f);
        pbInfoPanel.revalidate();
    }

    @Subscribe
    public void onWidgetLoaded(WidgetLoaded widgetLoaded) {
        if (widgetLoaded.getGroupId() != RAID_PARTY_INTERFACE_GROUP_ID) {
            return;
        }

        createPbInfoPanel();
    }

    @Subscribe
    public void onChatMessage(ChatMessage chatMessage) {
        if (chatMessage.getType() != ChatMessageType.GAMEMESSAGE) {
            return;
        }

        String message = chatMessage.getMessage();

        Matcher matcher = CHALLENGE_DURATION.matcher(message);
        if (matcher.find()) {
            String time = matcher.group("time");
            challengeTimeInSeconds = timeStringToSeconds(time);
        }

        matcher = CHALLENGE_DURATION_NEW_PB.matcher(message);
        if (matcher.find()) {
            String pb = matcher.group("pb");
            challengeTimeInSeconds = timeStringToSeconds(pb);
        }

        matcher = TOTAL_DURATION.matcher(message);
        if (matcher.find()) {
            String time = matcher.group("time");
            totalTimeInSeconds = timeStringToSeconds(time);
        }

        matcher = TOTAL_DURATION_NEW_PB.matcher(message);
        if (matcher.find()) {
            String pb = matcher.group("pb");
            totalTimeInSeconds = timeStringToSeconds(pb);
        }

        matcher = KILLCOUNT_PATTERN.matcher(message);
        if (matcher.find()) {
            final String boss = matcher.group("boss");
            if (boss.contains("Tombs of Amascut")) {
                int raidLevel = getRaidLevel();
                int teamSize = getTeamSize();
                String playerNames = getPlayerNames(teamSize);

                PbInfo savedChallengePbInfo = getPbFromConfig(raidLevel, teamSize, CompletionType.CHALLENGE);
                double savedChallengePb = savedChallengePbInfo == null ? -1 : savedChallengePbInfo.getPbTime();
                PbInfo savedTotalPbInfo = getPbFromConfig(raidLevel, teamSize, CompletionType.TOTAL);
                double savedTotalPb = savedTotalPbInfo == null ? -1 : savedTotalPbInfo.getPbTime();

                if (savedChallengePb == -1 || challengeTimeInSeconds < savedChallengePb) {
                    updatePbInConfig(new PbInfo(raidLevel, teamSize, CompletionType.CHALLENGE, challengeTimeInSeconds, playerNames));
                    sendNewPbMessage(raidLevel, teamSize, CompletionType.CHALLENGE, challengeTimeInSeconds);
                }

                if (savedTotalPb == -1 || totalTimeInSeconds < savedTotalPb) {
                    updatePbInConfig(new PbInfo(raidLevel, teamSize, CompletionType.TOTAL, totalTimeInSeconds, playerNames));
                    sendNewPbMessage(raidLevel, teamSize, CompletionType.TOTAL, totalTimeInSeconds);
                }
            }
        }
    }

    private int getRaidLevel() {
        return client.getVarbitValue(Varbits.TOA_RAID_LEVEL);
    }

    private int getTeamSize() {
        return Math.min(client.getVarbitValue(Varbits.TOA_MEMBER_0_HEALTH), 1) +
            Math.min(client.getVarbitValue(Varbits.TOA_MEMBER_1_HEALTH), 1) +
            Math.min(client.getVarbitValue(Varbits.TOA_MEMBER_2_HEALTH), 1) +
            Math.min(client.getVarbitValue(Varbits.TOA_MEMBER_3_HEALTH), 1) +
            Math.min(client.getVarbitValue(Varbits.TOA_MEMBER_4_HEALTH), 1) +
            Math.min(client.getVarbitValue(Varbits.TOA_MEMBER_5_HEALTH), 1) +
            Math.min(client.getVarbitValue(Varbits.TOA_MEMBER_6_HEALTH), 1) +
            Math.min(client.getVarbitValue(Varbits.TOA_MEMBER_7_HEALTH), 1);
    }

    private String getPlayerNames(int teamSize) {
        List<String> playerNames = new ArrayList<>();
        for (int i = 0; i < teamSize; i++) {
            String playerName = client.getVarcStrValue(1099 + i);
            if (playerName != null) {
                playerNames.add(playerName);
            }
        }
        return String.join(", ", playerNames);
    }

    private String getConfigKey(int raidLevel, int teamSize, CompletionType completionType) {
        return raidLevel + "-" + teamSize + "-" + completionType;
    }

    private PbInfo getPbFromConfig(int raidLevel, int teamSize, CompletionType completionType) {
        String key = getConfigKey(raidLevel, teamSize, completionType);
        String pbInfoSerialized = configManager.getRSProfileConfiguration(ToaPbTrackerConfig.GROUP, key);
        return gson.fromJson(pbInfoSerialized, PbInfo.class);
    }

    private void updatePbInConfig(PbInfo pbInfo) {
        String key = getConfigKey(pbInfo.getRaidLevel(), pbInfo.getTeamSize(), pbInfo.getCompletionType());
        configManager.setRSProfileConfiguration(ToaPbTrackerConfig.GROUP, key, gson.toJson(pbInfo));
    }

    private double timeStringToSeconds(String timeString) {
        String[] s = timeString.split(":");
        if (s.length == 2) // mm:ss
        {
            return Integer.parseInt(s[0]) * 60 + Double.parseDouble(s[1]);
        } else if (s.length == 3) // h:mm:ss
        {
            return Integer.parseInt(s[0]) * 60 * 60 + Integer.parseInt(s[1]) * 60 + Double.parseDouble(s[2]);
        }
        return Double.parseDouble(timeString);
    }

    private String secondsToTimeString(double seconds) {
        int hours = (int) (Math.floor(seconds) / 3600);
        int minutes = (int) (Math.floor(seconds / 60) % 60);
        seconds = seconds % 60;

        String timeString = hours > 0 ? String.format("%d:%02d:", hours, minutes) : String.format("%d:", minutes);

        // If the seconds is an integer, it is ambiguous if the pb is a precise
        // pb or not. So we always show it without the trailing .00.
        return timeString + (Math.floor(seconds) == seconds ? String.format("%02d", (int) seconds) : String.format("%05.2f", seconds));
    }

    private void sendNewPbMessage(int raidLevel, int teamSize, CompletionType completionType, double pb) {
        String newPbMessage = new ChatMessageBuilder()
            .append(ChatColorType.NORMAL)
            .append(completionType == CompletionType.CHALLENGE ? "Challenge" : "Total")
            .append(" pb for raid level ")
            .append(Color.BLUE, String.valueOf(raidLevel))
            .append(ChatColorType.NORMAL)
            .append(", team size ")
            .append(Color.BLUE, String.valueOf(teamSize))
            .append(ChatColorType.NORMAL)
            .append(" updated: ")
            .append(ChatColorType.HIGHLIGHT)
            .append(secondsToTimeString(pb))
            .build();

        chatMessageManager.queue(QueuedMessage.builder()
            .type(ChatMessageType.CONSOLE)
            .sender("ToA PB Tracker")
            .runeLiteFormattedMessage(newPbMessage)
            .build());
    }
}
