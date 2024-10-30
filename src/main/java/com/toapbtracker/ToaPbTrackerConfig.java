package com.toapbtracker;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

@ConfigGroup(ToaPbTrackerConfig.GROUP)
public interface ToaPbTrackerConfig extends Config {
    String GROUP = "toapbtracker";

    @ConfigItem(
        keyName = "greeting",
        name = "Welcome Greeting",
        description = "The message to show to the user when they login"
    )
    default String greeting() {
        return "Hello";
    }
}
