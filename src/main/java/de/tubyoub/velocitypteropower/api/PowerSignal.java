/*
 * This file is part of VelocityPteroPower, licensed under the MIT License.
 */
package de.tubyoub.velocitypteropower.api;

public enum PowerSignal {
    START("start"),
    STOP("stop"),
    RESTART("restart"),
    KILL("kill"); // Pterodactyl also supports "kill"

    private final String apiSignal;

    /**
     * Constructs a PowerSignal enum constant.
     * @param apiSignal The string representation expected by the Panel API.
     */
    PowerSignal(String apiSignal) {
        this.apiSignal = apiSignal;
    }

    /**
     * Gets the string representation of the signal expected by the Panel API.
     * @return The API signal string (e.g., "start", "stop").
     */
    public String getApiSignal() {
        return apiSignal;
    }
}