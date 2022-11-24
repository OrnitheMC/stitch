package net.fabricmc.stitch.util;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

import net.fabricmc.stitch.commands.GenState;
import net.fabricmc.stitch.representation.JarReader;
import net.fabricmc.stitch.representation.JarRootEntry;

public class CalamusUtil
{
    public static void generateCalamus(File jarFile, File calamusFile, String[] args) throws IOException {
        GenState state = new GenState();
        boolean clearedPatterns = false;
        ByteBuffer salt = ByteBuffer.allocate(256);

        if (args != null) {
            for (int i = 2; i < args.length; i++) {
                switch (args[i].toLowerCase(Locale.ROOT)) {
                    case "-t":
                    case "--target-namespace":
                        state.setTargetNamespace(args[i + 1]);
                        i++;
                        break;
                    case "-p":
                    case "--obfuscation-pattern":
                        if (!clearedPatterns)
                            state.clearObfuscatedPatterns();
                        clearedPatterns = true;

                        state.addObfuscatedPattern(args[i + 1]);
                        i++;
                        break;
                    case "--client-hash":
                    case "--server-hash":
                        salt.put(args[i + 1].getBytes(StandardCharsets.UTF_8));
                        i++;
                        break;
                }
            }
        }

        JarRootEntry jarEntry = new JarRootEntry(jarFile);
        try {
            new JarReader(jarEntry).apply(salt.array());
        } catch (IOException e) {
            e.printStackTrace();
        }

        System.err.println("Generating new mappings...");
        state.generate(calamusFile, jarEntry, null);
        System.err.println("Done!");
    }

    public static void updateCalamus(File oldJarFile, File newJarFile, File oldCalamusFile, File newCalamusFile, File matchesFile, String[] args) throws IOException {
        GenState state = new GenState();
        boolean clearedPatterns = false;
        ByteBuffer salt = ByteBuffer.allocate(256);

        for (int i = 5; i < args.length; i++) {
            switch (args[i].toLowerCase(Locale.ROOT)) {
                case "-t":
                case "--target-namespace":
                    state.setTargetNamespace(args[i + 1]);
                    i++;
                    break;
                case "-p":
                case "--obfuscation-pattern":
                    if (!clearedPatterns)
                        state.clearObfuscatedPatterns();
                    clearedPatterns = true;

                    state.addObfuscatedPattern(args[i + 1]);
                    i++;
                    break;
                case "--client-hash":
                case "--server-hash":
                    salt.put(args[i + 1].getBytes(StandardCharsets.UTF_8));
                    i++;
                    break;
            }
        }

        JarRootEntry jarOld = new JarRootEntry(oldJarFile);
        try {
            new JarReader(jarOld).apply(salt.array());
        } catch (IOException e) {
            e.printStackTrace();
        }

        JarRootEntry jarNew = new JarRootEntry(newJarFile);
        try {
            new JarReader(jarNew).apply(salt.array());
        } catch (IOException e) {
            e.printStackTrace();
        }

        System.err.println("Loading remapping files...");
        state.prepareUpdate(oldCalamusFile, matchesFile);

        System.err.println("Generating new mappings...");
        state.generate(newCalamusFile, jarNew, jarOld);
        System.err.println("Done!");
    }
}
