package com.mcserverhost;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import org.yaml.snakeyaml.Yaml;

public class HubConfig {

    private static final String DEFAULT_HOST = "mcsh.io";
    private static final int DEFAULT_PORT = 25577;

    private String host;
    private int port;
    private List<String> aliases;
    private boolean sendToHubOnShutdown;
    private boolean hubCommandEnabled;

    public HubConfig(File dataFolder, Logger logger) {
        this.host = DEFAULT_HOST;
        this.port = DEFAULT_PORT;
        this.aliases = new ArrayList<>();
        this.sendToHubOnShutdown = true;
        this.hubCommandEnabled = true;

        File configFile = new File(dataFolder, "hub.yml");

        if (!dataFolder.exists()) {
            dataFolder.mkdirs();
        }

        if (!configFile.exists()) {
            try (InputStream in = getClass().getResourceAsStream("/hub.yml");
                 OutputStream out = new FileOutputStream(configFile)) {
                if (in != null) {
                    byte[] buffer = new byte[1024];
                    int len;
                    while ((len = in.read(buffer)) != -1) {
                        out.write(buffer, 0, len);
                    }
                }
            } catch (Exception e) {
                logger.warning("Failed to save default hub.yml: " + e.getMessage());
            }
        }

        if (configFile.exists()) {
            try (FileInputStream fis = new FileInputStream(configFile)) {
                Yaml yaml = new Yaml();
                Map<String, Object> data = yaml.load(fis);
                if (data != null) {
                    if (data.containsKey("host")) {
                        this.host = String.valueOf(data.get("host"));
                    }
                    if (data.containsKey("port")) {
                        Object portVal = data.get("port");
                        if (portVal instanceof Number) {
                            this.port = ((Number) portVal).intValue();
                        }
                    }
                    if (data.containsKey("aliases")) {
                        Object aliasVal = data.get("aliases");
                        if (aliasVal instanceof List) {
                            for (Object a : (List<?>) aliasVal) {
                                if (a != null) {
                                    this.aliases.add(String.valueOf(a));
                                }
                            }
                        }
                    }
                    if (data.containsKey("send-to-hub-on-shutdown")) {
                        Object val = data.get("send-to-hub-on-shutdown");
                        if (val instanceof Boolean) {
                            this.sendToHubOnShutdown = (Boolean) val;
                        }
                    }
                    if (data.containsKey("hub-command-enabled")) {
                        Object val = data.get("hub-command-enabled");
                        if (val instanceof Boolean) {
                            this.hubCommandEnabled = (Boolean) val;
                        }
                    }
                }
            } catch (Exception e) {
                logger.warning("Failed to load hub.yml, using defaults: " + e.getMessage());
            }
        }
    }

    public String getHost() { return host; }
    public int getPort() { return port; }
    public List<String> getAliases() { return aliases; }
    public boolean isSendToHubOnShutdown() { return sendToHubOnShutdown; }
    public boolean isHubCommandEnabled() { return hubCommandEnabled; }

}
