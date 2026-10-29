package com.kartikey.p2pchat.conf.ui;

/** What the launcher collected: host (title) or join (invite code), plus the display name. */
public record LaunchChoice(boolean host, String title, String inviteCode, String name) {}
