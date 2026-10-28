package com.kartikey.p2pchat.conf.media.device;

import java.util.ArrayList;
import java.util.List;
import javax.sound.sampled.AudioSystem;

/** Best-effort device enumeration for pickers; never throws. */
public final class Devices {

    private Devices() {}

    /** Names of the audio mixers the JVM can see (inputs and outputs). */
    public static List<String> listAudio() {
        List<String> names = new ArrayList<>();
        try {
            for (var info : AudioSystem.getMixerInfo()) {
                names.add(info.getName());
            }
        } catch (RuntimeException | Error e) {
            // no audio subsystem
        }
        return names;
    }
}
