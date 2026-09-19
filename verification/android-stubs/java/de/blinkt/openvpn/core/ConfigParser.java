package de.blinkt.openvpn.core;
import java.io.IOException;
import java.io.Reader;
import de.blinkt.openvpn.VpnProfile;
/** Transcribed from ics-openvpn v0.7.65 core/ConfigParser.java */
public class ConfigParser {
    public void parseConfig(Reader reader) throws IOException, ConfigParseError { }
    public VpnProfile convertProfile() throws ConfigParseError, IOException { return null; }
    public static class ConfigParseError extends Exception {
        public ConfigParseError(String msg) { super(msg); }
    }
}
