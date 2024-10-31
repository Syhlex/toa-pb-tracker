package com.toapbtracker;

import lombok.Value;

@Value
public class PbInfo {
    int raidLevel;
    int teamSize;
    CompletionType completionType;
    double pbTime;
    String playerNames;
}
