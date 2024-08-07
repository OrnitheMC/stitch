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
        generateIntermediary(jarFile, null, libs, intermediaryFile, options);
    }

    public static void generateIntermediary(File jarFile, File nests, Collection<File> libs, File intermediaryFile, String[] options) throws IOException {
        MergedArgs args = parseOptions(new MergedArgs(), options);

        args.oldJarFiles.clear();
        args.oldLibs.clear();
        args.newJarFile = jarFile;
        args.newNests = nests;
        args.newLibs.addAll(libs);
        args.oldIntermediaryFiles.clear();
        args.newIntermediaryFile = intermediaryFile;
        args.matchesFiles.clear();
        args.invertMatches = new boolean[0];

        updateIntermediary(args);
    }

    public static void generateIntermediary(File clientJarFile, Collection<File> clientLibs, File serverJarFile, Collection<File> serverLibs, File intermediaryFile, File matchesFile, String[] options) throws IOException {
        generateIntermediary(clientJarFile, clientLibs, serverJarFile, serverLibs, intermediaryFile, matchesFile, false, options);
    }

    public static void generateIntermediary(File clientJarFile, Collection<File> clientLibs, File serverJarFile, Collection<File> serverLibs, File intermediaryFile, File matchesFile, boolean invertMatches, String[] options) throws IOException {
        generateIntermediary(clientJarFile, null, clientLibs, serverJarFile, null, serverLibs, intermediaryFile, matchesFile, invertMatches, options);
    }

    public static void generateIntermediary(File clientJarFile, File clientNests, Collection<File> clientLibs, File serverJarFile, File serverNests, Collection<File> serverLibs, File intermediaryFile, File matchesFile, String[] options) throws IOException {
        generateIntermediary(clientJarFile, clientNests, clientLibs, serverJarFile, serverNests, serverLibs, intermediaryFile, matchesFile, false, options);
    }

    public static void generateIntermediary(File clientJarFile, File clientNests, Collection<File> clientLibs, File serverJarFile, File serverNests, Collection<File> serverLibs, File intermediaryFile, File matchesFile, boolean invertMatches, String[] options) throws IOException {
        SplitArgs args = parseOptions(new SplitArgs(), options);

        args.oldClientJarFile = null;
        args.oldClientLibs.clear();
        args.oldServerJarFile = null;
        args.oldServerLibs.clear();
        args.newClientJarFile = clientJarFile;
        args.newClientNests = clientNests;
        args.newClientLibs.addAll(clientLibs);
        args.newServerJarFile = serverJarFile;
        args.newServerNests = serverNests;
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

    public static void updateIntermediary(Collection<File> oldJarFiles, Collection<List<File>> oldLibs, File newJarFile, Collection<File> newLibs, Collection<File> oldIntermediaryFiles, File newIntermediaryFile, Collection<File> matchesFiles, String[] options) throws IOException {
        updateIntermediary(oldJarFiles, oldLibs, newJarFile, newLibs, oldIntermediaryFiles, newIntermediaryFile, matchesFiles, new boolean[matchesFiles.size()], options);
    }

    public static void updateIntermediary(Collection<File> oldJarFiles, Collection<List<File>> oldLibs, File newJarFile, Collection<File> newLibs, Collection<File> oldIntermediaryFiles, File newIntermediaryFile, Collection<File> matchesFiles, boolean[] invertMatches, String[] options) throws IOException {
        updateIntermediary(oldJarFiles, oldLibs, newJarFile, null, newLibs, oldIntermediaryFiles, newIntermediaryFile, matchesFiles, invertMatches, options);
    }

    public static void updateIntermediary(Collection<File> oldJarFiles, Collection<List<File>> oldLibs, File newJarFile, File newNests, Collection<File> newLibs, Collection<File> oldIntermediaryFiles, File newIntermediaryFile, Collection<File> matchesFiles, String[] options) throws IOException {
        updateIntermediary(oldJarFiles, oldLibs, newJarFile, newNests, newLibs, oldIntermediaryFiles, newIntermediaryFile, matchesFiles, new boolean[matchesFiles.size()], options);
    }

    public static void updateIntermediary(Collection<File> oldJarFiles, Collection<List<File>> oldLibs, File newJarFile, File newNests, Collection<File> newLibs, Collection<File> oldIntermediaryFiles, File newIntermediaryFile, Collection<File> matchesFiles, boolean[] invertMatches, String[] options) throws IOException {
        MergedArgs args = parseOptions(new MergedArgs(), options);

        args.oldJarFiles.addAll(oldJarFiles);
        args.oldLibs.addAll(oldLibs);
        args.newJarFile = newJarFile;
        args.newNests = newNests;
        args.newLibs.addAll(newLibs);
        args.oldIntermediaryFiles.addAll(oldIntermediaryFiles);
        args.newIntermediaryFile = newIntermediaryFile;
        args.matchesFiles.addAll(matchesFiles);
        args.invertMatches = invertMatches;

        updateIntermediary(args);
    }

    public static void updateIntermediary(File oldJarFile, Collection<File> oldLibs, File newClientJarFile, Collection<File> newClientLibs, File newServerJarFile, Collection<File> newServerLibs, File oldIntermediaryFile, File newIntermediaryFile, File clientMatchesFile, File serverMatchesFile, File clientServerMatchesFile, String[] options) throws IOException {
        updateIntermediary(oldJarFile, oldLibs, newClientJarFile, newClientLibs, newServerJarFile, newServerLibs, oldIntermediaryFile, newIntermediaryFile, clientMatchesFile, serverMatchesFile, clientServerMatchesFile, false, false, false, options);
    }

    public static void updateIntermediary(File oldJarFile, Collection<File> oldLibs, File newClientJarFile, Collection<File> newClientLibs, File newServerJarFile, Collection<File> newServerLibs, File oldIntermediaryFile, File newIntermediaryFile, File clientMatchesFile, File serverMatchesFile, File clientServerMatchesFile, boolean invertClientMatches, boolean invertServerMatches, boolean invertClientServerMatches, String[] options) throws IOException {
        updateIntermediary(oldJarFile, oldLibs, newClientJarFile, null, newClientLibs, newServerJarFile, null, newServerLibs, oldIntermediaryFile, newIntermediaryFile, clientMatchesFile, serverMatchesFile, clientServerMatchesFile, invertClientMatches, invertServerMatches, invertClientServerMatches, options);
    }

    public static void updateIntermediary(File oldJarFile, Collection<File> oldLibs, File newClientJarFile, File newClientNests, Collection<File> newClientLibs, File newServerJarFile, File newServerNests, Collection<File> newServerLibs, File oldIntermediaryFile, File newIntermediaryFile, File clientMatchesFile, File serverMatchesFile, File clientServerMatchesFile, String[] options) throws IOException {
        updateIntermediary(oldJarFile, oldLibs, newClientJarFile, newClientNests, newClientLibs, newServerJarFile, newServerNests, newServerLibs, oldIntermediaryFile, newIntermediaryFile, clientMatchesFile, serverMatchesFile, clientServerMatchesFile, false, false, false, options);
    }

    public static void updateIntermediary(File oldJarFile, Collection<File> oldLibs, File newClientJarFile, File newClientNests, Collection<File> newClientLibs, File newServerJarFile, File newServerNests, Collection<File> newServerLibs, File oldIntermediaryFile, File newIntermediaryFile, File clientMatchesFile, File serverMatchesFile, File clientServerMatchesFile, boolean invertClientMatches, boolean invertServerMatches, boolean invertClientServerMatches, String[] options) throws IOException {
        SplitArgs args = parseOptions(new SplitArgs(), options);

        args.oldClientJarFile = oldJarFile;
        args.oldClientLibs.addAll(oldLibs);
        args.oldServerJarFile = oldJarFile;
        args.oldServerLibs.addAll(oldLibs);
        args.newClientJarFile = newClientJarFile;
        args.newClientNests = newClientNests;
        args.newClientLibs.addAll(newClientLibs);
        args.newServerJarFile = newServerJarFile;
        args.newServerNests = newServerNests;
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
        updateIntermediary(oldClientJarFile, oldClientLibs, oldServerJarFile, oldServerLibs, newClientJarFile, null, newClientLibs, newServerJarFile, null, newServerLibs, oldIntermediaryFile, newIntermediaryFile, clientMatchesFile, serverMatchesFile, clientServerMatchesFile, invertClientMatches, invertServerMatches, invertClientServerMatches, options);
    }

    public static void updateIntermediary(File oldClientJarFile, Collection<File> oldClientLibs, File oldServerJarFile, Collection<File> oldServerLibs, File newClientJarFile, File newClientNests, Collection<File> newClientLibs, File newServerJarFile, File newServerNests, Collection<File> newServerLibs, File oldIntermediaryFile, File newIntermediaryFile, File clientMatchesFile, File serverMatchesFile, File clientServerMatchesFile, String[] options) throws IOException {
        updateIntermediary(oldClientJarFile, oldClientLibs, oldServerJarFile, oldServerLibs, newClientJarFile, newClientNests, newClientLibs, newServerJarFile, newServerNests, newServerLibs, oldIntermediaryFile, newIntermediaryFile, clientMatchesFile, serverMatchesFile, clientServerMatchesFile, false, false, false, options);
    }

    public static void updateIntermediary(File oldClientJarFile, Collection<File> oldClientLibs, File oldServerJarFile, Collection<File> oldServerLibs, File newClientJarFile, File newClientNests, Collection<File> newClientLibs, File newServerJarFile, File newServerNests, Collection<File> newServerLibs, File oldIntermediaryFile, File newIntermediaryFile, File clientMatchesFile, File serverMatchesFile, File clientServerMatchesFile, boolean invertClientMatches, boolean invertServerMatches, boolean invertClientServerMatches, String[] options) throws IOException {
        SplitArgs args = parseOptions(new SplitArgs(), options);

        args.oldClientJarFile = oldClientJarFile;
        args.oldClientLibs.addAll(oldClientLibs);
        args.oldServerJarFile = oldServerJarFile;
        args.oldServerLibs.addAll(oldServerLibs);
        args.newClientJarFile = newClientJarFile;
        args.newClientNests = newClientNests;
        args.newClientLibs.addAll(newClientLibs);
        args.newServerJarFile = newServerJarFile;
        args.newServerNests = newServerNests;
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

    public static void updateIntermediary(File oldClientJarFile, Collection<File> oldClientLibs, File oldServerJarFile, Collection<File> oldServerLibs, File newClientJarFile, File newClientNests, Collection<File> newClientLibs, File newServerJarFile, File newServerNests, Collection<File> newServerLibs, File oldClientIntermediaryFile, File oldServerIntermediaryFile, File newIntermediaryFile, File clientMatchesFile, File serverMatchesFile, File clientServerMatchesFile, String[] options) throws IOException {
        updateIntermediary(oldClientJarFile, oldClientLibs, oldServerJarFile, oldServerLibs, newClientJarFile, newClientNests, newClientLibs, newServerJarFile, newServerNests, newServerLibs, oldClientIntermediaryFile, oldServerIntermediaryFile, newIntermediaryFile, clientMatchesFile, serverMatchesFile, clientServerMatchesFile, false, false, false, options);
    }

    public static void updateIntermediary(File oldClientJarFile, Collection<File> oldClientLibs, File oldServerJarFile, Collection<File> oldServerLibs, File newClientJarFile, File newClientNests, Collection<File> newClientLibs, File newServerJarFile, File newServerNests, Collection<File> newServerLibs, File oldClientIntermediaryFile, File oldServerIntermediaryFile, File newIntermediaryFile, File clientMatchesFile, File serverMatchesFile, File clientServerMatchesFile, boolean invertClientMatches, boolean invertServerMatches, boolean invertClientServerMatches, String[] options) throws IOException {
        SplitArgs args = parseOptions(new SplitArgs(), options);

        args.oldClientJarFile = oldClientJarFile;
        args.oldClientLibs.addAll(oldClientLibs);
        args.oldServerJarFile = oldServerJarFile;
        args.oldServerLibs.addAll(oldServerLibs);
        args.newClientJarFile = newClientJarFile;
        args.newClientNests = newClientNests;
        args.newClientLibs.addAll(newClientLibs);
        args.newServerJarFile = newServerJarFile;
        args.newServerNests = newServerNests;
        args.newServerLibs.addAll(newServerLibs);
        args.oldClientIntermediaryFile = oldClientIntermediaryFile;
        args.oldServerIntermediaryFile = oldServerIntermediaryFile;
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

        List<Classpath> storagesOld = new ArrayList<>();
        for (int i = 0; i < args.oldJarFiles.size(); i++) {
            storagesOld.add(new Classpath(args.oldJarFiles.get(i), args.oldLibs.get(i)));
        }
        Classpath storageNew = new Classpath(args.newJarFile, args.newNests, args.newLibs);

        try {
            for (Classpath storageOld : storagesOld) {
                new JarReader(storageOld).apply();
            }
            new JarReader(storageNew).apply(args.salt.array());
        } catch (Exception e) {
            e.printStackTrace();
        }

        if (!args.oldIntermediaryFiles.isEmpty()) {
            System.err.println("Loading remapping files...");
            state.prepareUpdate(args.oldIntermediaryFiles, args.matchesFiles, args.invertMatches);
        }

        System.err.println("Generating new mappings...");
        state.generate(args.newIntermediaryFile, storageNew, storagesOld);
        System.err.println("Done!");
    }

    public static void updateIntermediary(SplitArgs args) throws IOException {
        GenStateSplit state = new GenStateSplit();

        prepareState(args, state);

        Classpath storageClientOld = null;
        Classpath storageServerOld = null;
        Classpath storageClientNew = null;
        Classpath storageServerNew = null;
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
        if (args.newClientJarFile != null) {
            storageClientNew = new Classpath(args.newClientJarFile, args.newClientNests, args.newClientLibs);
        }
        if (args.newServerJarFile != null) {
            storageServerNew = new Classpath(args.newServerJarFile, args.newServerNests, args.newServerLibs);
        }

        try {
            if (storageClientOld != null) {
                new JarReader(storageClientOld).apply();
            }
            if (storageServerOld != null && storageServerOld != storageClientOld) {
                new JarReader(storageServerOld).apply();
            }
            if (storageClientNew != null) {
                new JarReader(storageClientNew).apply(args.salt.array());
            }
            if (storageServerNew != null) {
                new JarReader(storageServerNew).apply(args.salt.array());
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        if (args.oldIntermediaryFile != null || args.oldClientIntermediaryFile != null || args.oldServerIntermediaryFile != null) {
            System.err.println("Loading remapping files...");
            if (args.oldClientJarFile == args.oldServerJarFile) {
                state.prepareUpdateFromMerged(args.oldIntermediaryFile, args.clientMatchesFile, args.serverMatchesFile, args.clientServerMatchesFile, args.invertClientMatches, args.invertServerMatches, args.invertClientServerMatches);
            } else if (args.oldIntermediaryFile != null) {
                state.prepareUpdateFromSplit(args.oldIntermediaryFile, args.clientMatchesFile, args.serverMatchesFile, args.clientServerMatchesFile, args.invertClientMatches, args.invertServerMatches, args.invertClientServerMatches);
            } else {
                state.prepareUpdateFromSplit(args.oldClientIntermediaryFile, args.oldServerIntermediaryFile, args.clientMatchesFile, args.serverMatchesFile, args.clientServerMatchesFile, args.invertClientMatches, args.invertServerMatches, args.invertClientServerMatches);
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
            args.salt.put(args.clientHash.getBytes(StandardCharsets.UTF_8));
        }
        if (args.serverHash != null) {
            args.salt.put(args.serverHash.getBytes(StandardCharsets.UTF_8));
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

        List<File> oldJarFiles = new ArrayList<>();
        List<List<File>> oldLibs = new ArrayList<>();
        File newJarFile;
        File newNests;
        List<File> newLibs = new ArrayList<>();
        List<File> oldIntermediaryFiles = new ArrayList<>();
        File newIntermediaryFile;
        List<File> matchesFiles = new ArrayList<>();
        boolean[] invertMatches;

    }

    public static class SplitArgs extends Args {

        File oldClientJarFile;
        List<File> oldClientLibs = new ArrayList<>();
        File oldServerJarFile;
        List<File> oldServerLibs = new ArrayList<>();
        File newClientJarFile;
        File newClientNests;
        List<File> newClientLibs = new ArrayList<>();
        File newServerJarFile;
        File newServerNests;
        List<File> newServerLibs = new ArrayList<>();
        File oldIntermediaryFile;
        File oldClientIntermediaryFile;
        File oldServerIntermediaryFile;
        File newIntermediaryFile;
        File clientMatchesFile;
        File serverMatchesFile;
        File clientServerMatchesFile;
        boolean invertClientMatches;
        boolean invertServerMatches;
        boolean invertClientServerMatches;

    }
}
