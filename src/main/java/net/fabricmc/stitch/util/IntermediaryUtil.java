package net.fabricmc.stitch.util;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

import net.fabricmc.stitch.commands.GenState;
import net.fabricmc.stitch.commands.GenStateMerged;
import net.fabricmc.stitch.commands.GenStateSplit;
import net.fabricmc.stitch.representation.Classpath;
import net.fabricmc.stitch.representation.JarReader;

public class IntermediaryUtil
{
    public static void generateIntermediary(File jarFile, Collection<File> libs, File intermediaryFile, String[] options) throws IOException {
        MergedArgs args = parseOptions(new MergedArgs(), options);

        args.oldJarFile = null;
        args.oldLibs.clear();
        args.newJarFile = jarFile;
        args.newLibs.addAll(libs);
        args.oldIntermediaryFile = null;
        args.newIntermediaryFile = intermediaryFile;
        args.matchesFile = null;
        args.invertMatches = false;

        updateIntermediary(args);
    }

    public static void generateIntermediary(File clientJarFile, Collection<File> clientLibs, File serverJarFile, Collection<File> serverLibs, File intermediaryFile, File matchesFile, String[] options) throws IOException {
        generateIntermediary(clientJarFile, clientLibs, serverJarFile, serverLibs, intermediaryFile, matchesFile, false, options);
    }

    public static void generateIntermediary(File clientJarFile, Collection<File> clientLibs, File serverJarFile, Collection<File> serverLibs, File intermediaryFile, File matchesFile, boolean invertMatches, String[] options) throws IOException {
        SplitArgs args = parseOptions(new SplitArgs(), options);

        args.oldClientJarFile = null;
        args.oldClientLibs.clear();
        args.oldServerJarFile = null;
        args.newServerLibs.clear();
        args.newClientJarFile = clientJarFile;
        args.newClientLibs.addAll(clientLibs);
        args.newServerJarFile = serverJarFile;
        args.newServerLibs.addAll(serverLibs);
        args.oldIntermediaryFile = null;
        args.newIntermediaryFile = intermediaryFile;
        args.clientMatchesFile = null;
        args.serverMatchesFile = null;
        args.clientServerMatchesFile = matchesFile;
        args.invertClientMatches = false;
        args.invertServerMatches = false;
        args.invertClientServerMatches = invertMatches;

        updateIntermediary(args);
    }

    public static void updateIntermediary(File oldJarFile, Collection<File> oldLibs, File newJarFile, Collection<File> newLibs, File oldIntermediaryFile, File newIntermediaryFile, File matchesFile, String[] options) throws IOException {
        updateIntermediary(oldJarFile, oldLibs, newJarFile, newLibs, oldIntermediaryFile, newIntermediaryFile, matchesFile, false, options);
    }

    public static void updateIntermediary(File oldJarFile, Collection<File> oldLibs, File newJarFile, Collection<File> newLibs, File oldIntermediaryFile, File newIntermediaryFile, File matchesFile, boolean invertMatches, String[] options) throws IOException {
        MergedArgs args = parseOptions(new MergedArgs(), options);

        args.oldJarFile = oldJarFile;
        args.oldLibs.addAll(oldLibs);
        args.newJarFile = newJarFile;
        args.newLibs.addAll(newLibs);
        args.oldIntermediaryFile = oldIntermediaryFile;
        args.newIntermediaryFile = newIntermediaryFile;
        args.matchesFile = matchesFile;
        args.invertMatches = invertMatches;

        updateIntermediary(args);
    }

    public static void updateIntermediary(File oldJarFile, Collection<File> oldLibs, File newClientJarFile, Collection<File> newClientLibs, File newServerJarFile, Collection<File> newServerLibs, File oldIntermediaryFile, File newIntermediaryFile, File clientMatchesFile, File serverMatchesFile, File clientServerMatchesFile, String[] options) throws IOException {
        updateIntermediary(oldJarFile, oldLibs, newClientJarFile, newClientLibs, newServerJarFile, newServerLibs, oldIntermediaryFile, newIntermediaryFile, clientMatchesFile, serverMatchesFile, clientServerMatchesFile, false, false, false, options);
    }

    public static void updateIntermediary(File oldJarFile, Collection<File> oldLibs, File newClientJarFile, Collection<File> newClientLibs, File newServerJarFile, Collection<File> newServerLibs, File oldIntermediaryFile, File newIntermediaryFile, File clientMatchesFile, File serverMatchesFile, File clientServerMatchesFile, boolean invertClientMatches, boolean invertServerMatches, boolean invertClientServerMatches, String[] options) throws IOException {
        SplitArgs args = parseOptions(new SplitArgs(), options);

        args.oldClientJarFile = oldJarFile;
        args.oldClientLibs.addAll(oldLibs);
        args.oldServerJarFile = oldJarFile;
        args.newServerLibs.addAll(oldLibs);
        args.newClientJarFile = newClientJarFile;
        args.newClientLibs.addAll(newClientLibs);
        args.newServerJarFile = newServerJarFile;
        args.newServerLibs.addAll(newServerLibs);
        args.oldIntermediaryFile = oldIntermediaryFile;
        args.newIntermediaryFile = newIntermediaryFile;
        args.clientMatchesFile = clientMatchesFile;
        args.serverMatchesFile = serverMatchesFile;
        args.clientServerMatchesFile = clientServerMatchesFile;
        args.invertClientMatches = invertClientMatches;
        args.invertServerMatches = invertServerMatches;
        args.invertClientServerMatches = invertClientServerMatches;

        updateIntermediary(args);
    }

    public static void updateIntermediary(File oldClientJarFile, Collection<File> oldClientLibs, File oldServerJarFile, Collection<File> oldServerLibs, File newClientJarFile, Collection<File> newClientLibs, File newServerJarFile, Collection<File> newServerLibs, File oldIntermediaryFile, File newIntermediaryFile, File clientMatchesFile, File serverMatchesFile, File clientServerMatchesFile, String[] options) throws IOException {
        updateIntermediary(oldClientJarFile, oldClientLibs, oldServerJarFile, oldServerLibs, newClientJarFile, newClientLibs, newServerJarFile, newServerLibs, oldIntermediaryFile, newIntermediaryFile, clientMatchesFile, serverMatchesFile, clientServerMatchesFile, false, false, false, options);
    }

    public static void updateIntermediary(File oldClientJarFile, Collection<File> oldClientLibs, File oldServerJarFile, Collection<File> oldServerLibs, File newClientJarFile, Collection<File> newClientLibs, File newServerJarFile, Collection<File> newServerLibs, File oldIntermediaryFile, File newIntermediaryFile, File clientMatchesFile, File serverMatchesFile, File clientServerMatchesFile, boolean invertClientMatches, boolean invertServerMatches, boolean invertClientServerMatches, String[] options) throws IOException {
        SplitArgs args = parseOptions(new SplitArgs(), options);

        args.oldClientJarFile = oldClientJarFile;
        args.oldClientLibs.addAll(oldClientLibs);
        args.oldServerJarFile = oldServerJarFile;
        args.newServerLibs.addAll(oldServerLibs);
        args.newClientJarFile = newClientJarFile;
        args.newClientLibs.addAll(newClientLibs);
        args.newServerJarFile = newServerJarFile;
        args.newServerLibs.addAll(newServerLibs);
        args.oldIntermediaryFile = oldIntermediaryFile;
        args.newIntermediaryFile = newIntermediaryFile;
        args.clientMatchesFile = clientMatchesFile;
        args.serverMatchesFile = serverMatchesFile;
        args.clientServerMatchesFile = clientServerMatchesFile;
        args.invertClientMatches = invertClientMatches;
        args.invertServerMatches = invertServerMatches;
        args.invertClientServerMatches = invertClientServerMatches;

        updateIntermediary(args);
    }

    public static void updateIntermediary(MergedArgs args) throws IOException {
        GenStateMerged state = new GenStateMerged();

        prepareState(args, state);

        Classpath storageOld = null;
        if (args.oldJarFile != null) {
            storageOld = new Classpath(args.oldJarFile, args.oldLibs);
        }
        Classpath storageNew = new Classpath(args.newJarFile, args.newLibs);

        try {
            if (storageOld != null) {
                new JarReader(storageOld).apply();
            }
            new JarReader(storageNew).apply(args.salt.array());
        } catch (Exception e) {
            e.printStackTrace();
        }

        if (args.oldIntermediaryFile != null) {
            System.err.println("Loading remapping files...");
            state.prepareUpdate(args.oldIntermediaryFile, args.matchesFile, args.invertMatches);
        }

        System.err.println("Generating new mappings...");
        state.generate(args.newIntermediaryFile, storageNew, storageOld);
        System.err.println("Done!");
    }

    public static void updateIntermediary(SplitArgs args) throws IOException {
        GenStateSplit state = new GenStateSplit();

        prepareState(args, state);

        Classpath storageClientOld = null;
        Classpath storageServerOld = null;
        if (args.oldClientJarFile == args.oldServerJarFile) {
            if (args.oldClientJarFile != null) {
                storageClientOld = storageServerOld = new Classpath(args.oldClientJarFile, args.oldClientLibs);
            }
        } else {
            if (args.oldClientJarFile != null) {
                storageClientOld = new Classpath(args.oldClientJarFile, args.oldClientLibs);
            }
            if (args.oldServerJarFile != null) {
                storageServerOld = new Classpath(args.oldServerJarFile, args.oldServerLibs);
            }
        }
        Classpath storageClientNew = new Classpath(args.newClientJarFile, args.newClientLibs);
        Classpath storageServerNew = new Classpath(args.newServerJarFile, args.newServerLibs);

        try {
            if (storageClientOld != null) {
                new JarReader(storageClientOld).apply();
            }
            if (storageServerOld != null && storageServerOld != storageClientOld) {
                new JarReader(storageServerOld).apply();
            }
            new JarReader(storageClientNew).apply(args.salt.array());
            new JarReader(storageServerNew).apply(args.salt.array());
        } catch (Exception e) {
            e.printStackTrace();
        }

        if (args.oldIntermediaryFile != null) {
            System.err.println("Loading remapping files...");
            if (args.oldClientJarFile == args.oldServerJarFile) {
                state.prepareUpdateFromMerged(args.oldIntermediaryFile, args.clientMatchesFile, args.serverMatchesFile, args.clientServerMatchesFile, args.invertClientMatches, args.invertServerMatches, args.invertClientServerMatches);
            } else {
                state.prepareUpdateFromSplit(args.oldIntermediaryFile, args.clientMatchesFile, args.serverMatchesFile, args.clientServerMatchesFile, args.invertClientMatches, args.invertServerMatches, args.invertClientServerMatches);
            }
        }

        System.err.println("Generating new mappings...");
        state.generate(args.newIntermediaryFile, storageClientNew, storageServerNew, storageClientOld, storageServerOld);
        System.err.println("Done!");
    }

    private static void prepareState(Args args, GenState state) {
        if (args.defaultPackage != null) {
            state.setDefaultPackage(args.defaultPackage);
        }
        if (args.targetNamespace != null) {
            state.setTargetNamespace(args.targetNamespace);
        }
        if (!args.obfuscationPatterns.isEmpty()) {
            state.clearObfuscatedPatterns();
            for (String pattern : args.obfuscationPatterns) {
                state.addObfuscatedPattern(pattern);
            }
        }
        if (args.nameLength != null) {
            state.setNameLength(args.nameLength);
        }
        args.salt = ByteBuffer.allocate(256);
        if (args.clientHash != null) {
            args.salt.put(args.salt.position(), args.clientHash.getBytes(StandardCharsets.UTF_8));
        }
        if (args.serverHash != null) {
            args.salt.put(args.salt.position(), args.serverHash.getBytes(StandardCharsets.UTF_8));
        }
    }

    public static Args parseOptions(String[] options) {
        Args a = new Args();
        parseOptions(a, options);
        return a;
    }

    private static <T extends Args> T parseOptions(T args, String[] rawArgs) {
        for (int i = 0; i < rawArgs.length; i++) {
            switch (rawArgs[i].toLowerCase(Locale.ROOT)) {
            case "--default-package":
                args.defaultPackage = rawArgs[++i];
                break;
            case "-t":
            case "--target-namespace":
                args.targetNamespace = rawArgs[++i];
                break;
            case "-p":
            case "--obfuscation-pattern":
                args.obfuscationPatterns.add(rawArgs[++i]);
                break;
            case "--name-length":
                args.nameLength = Integer.parseInt(rawArgs[++i]);
                break;
            case "--client-hash":
                args.clientHash = rawArgs[++i];
                break;
            case "--server-hash":
                args.serverHash = rawArgs[++i];
                break;
            default:
                break;
            }
        }

        return args;
    }

    public static class Args {

        String defaultPackage;
        String targetNamespace;
        List<String> obfuscationPatterns = new ArrayList<>();
        Integer nameLength;
        String clientHash;
        String serverHash;
        ByteBuffer salt;

    }

    public static class MergedArgs extends Args {

        File oldJarFile;
        List<File> oldLibs = new ArrayList<>();
        File newJarFile;
        List<File> newLibs = new ArrayList<>();
        File oldIntermediaryFile;
        File newIntermediaryFile;
        File matchesFile;
        boolean invertMatches;

    }

    public static class SplitArgs extends Args {

        File oldClientJarFile;
        List<File> oldClientLibs = new ArrayList<>();
        File oldServerJarFile;
        List<File> oldServerLibs = new ArrayList<>();
        File newClientJarFile;
        List<File> newClientLibs = new ArrayList<>();
        File newServerJarFile;
        List<File> newServerLibs = new ArrayList<>();
        File oldIntermediaryFile;
        File newIntermediaryFile;
        File clientMatchesFile;
        File serverMatchesFile;
        File clientServerMatchesFile;
        boolean invertClientMatches;
        boolean invertServerMatches;
        boolean invertClientServerMatches;

    }
}
