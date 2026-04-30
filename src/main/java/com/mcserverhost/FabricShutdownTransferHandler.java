package com.mcserverhost;

import java.util.logging.Logger;

public class FabricShutdownTransferHandler {
    public static void transferAll(HubConfig hubConfig, Object server, Logger logger) {
        ForgeShutdownTransferHandler.transferAll(hubConfig, server, logger);
    }
}
