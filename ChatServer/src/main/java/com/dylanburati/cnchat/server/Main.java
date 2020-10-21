package com.dylanburati.cnchat.server;

import java.io.*;
import java.text.SimpleDateFormat;
import java.util.*;

public class Main {
    public static void main(String[] args) throws IOException {
        SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        System.out.format("\nCNChat started at %s\n\n", dateFormat.format(new Date()));

        String dataDirectory = null;
        final String prodDataDirectory = "/var/lib/cnchat/data/";
        try {
            if (new File(prodDataDirectory).isDirectory()) {
                dataDirectory = prodDataDirectory;
            }
        } catch (SecurityException ignored) {
        }

        if (dataDirectory == null) {
            dataDirectory = new File(Main.class.getProtectionDomain().getCodeSource().getLocation().getFile())
                    .getParent() + System.getProperty("file.separator");
        }
        String preferenceStorePath = dataDirectory + "user_preferences.json";
        System.out.println("Preference store path: " + preferenceStorePath);

        new ChatServer(preferenceStorePath);
    }
}
