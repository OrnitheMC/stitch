package net.fabricmc.stitch.util;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import net.fabricmc.stitch.commands.GenState;
import net.fabricmc.stitch.representation.JarReader;
import net.fabricmc.stitch.representation.JarRootEntry;

public class IntermediaryUtil
{
    public static void generateIntermediary(File jarFile, File intermediaryFile, String[] options) throws IOException {
        Args args = parseOptions(options);

        args.newJarFile = jarFile;
        args.newIntermediaryFile = intermediaryFile;

        generateIntermediary(args);
    }

    public static void generateIntermediary(Args args) throws IOException {
        args.oldJarFiles.clear();
        args.oldIntermediaryFiles.clear();
        args.matchesFiles.clear();

        updateIntermediary(args);
    }

    public static void updateIntermediary(List<File> oldJarFiles, File newJarFile, List<File> oldIntermediaryFiles, File newIntermediaryFile, List<File> matchesFiles, String[] options) throws IOException {
        boolean[] invertMatches = new boolean[matchesFiles.size()];
        for (int i = 0; i < matchesFiles.size(); i++) {
            invertMatches[i] = false;
        }

        updateIntermediary(oldJarFiles, newJarFile, oldIntermediaryFiles, newIntermediaryFile, matchesFiles, invertMatches, options);
    }

    public static void updateIntermediary(List<File> oldJarFiles, File newJarFile, List<File> oldIntermediaryFiles, File newIntermediaryFile, List<File> matchesFiles, boolean[] invertMatches, String[] options) throws IOException {
        Args args = parseOptions(options);

        args.oldJarFiles = oldJarFiles;
        args.newJarFile = newJarFile;
        args.oldIntermediaryFiles = oldIntermediaryFiles;
        args.newIntermediaryFile = newIntermediaryFile;
        args.matchesFiles = matchesFiles;
        args.invertMatches = invertMatches;

        updateIntermediary(args);
    }

    public static void updateIntermediary(Args args) throws IOException {
        GenState state = new GenState();

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
        ByteBuffer salt = ByteBuffer.allocate(256);
        if (args.clientHash != null) {
            salt.put(args.clientHash.getBytes(StandardCharsets.UTF_8));
        }
        if (args.serverHash != null) {
            salt.put(args.serverHash.getBytes(StandardCharsets.UTF_8));
        }

        List<JarRootEntry> jarsOld = new ArrayList<>();
        for (File oldJarFile : args.oldJarFiles) {
            JarRootEntry jarOld = new JarRootEntry(oldJarFile);
            jarsOld.add(jarOld);
            try {
                new JarReader(jarOld).apply(salt.array());
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        JarRootEntry jarNew = new JarRootEntry(args.newJarFile);
        try {
            new JarReader(jarNew).apply(salt.array());
        } catch (IOException e) {
            e.printStackTrace();
        }

        if (!args.oldIntermediaryFiles.isEmpty()) {
            System.err.println("Loading remapping files...");
            state.prepareUpdate(args.oldIntermediaryFiles, args.matchesFiles, args.invertMatches);
        }

        System.err.println("Generating new mappings...");
        state.generate(args.newIntermediaryFile, jarNew, jarsOld);
        System.err.println("Done!");
    }

    public static Args parseArgs(String[] rawArgs) {
        Args args = new Args();

        int oldFilesCount = 0;

        // first two args are always files
        for (int i = 2; i < rawArgs.length; i++) {
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
                // this arg is not an option, i.e. it's a file
                oldFilesCount++;
                break;
            }
        }

        // after all the files is the invert matches booleans
        // first check if they are present, since they are optional
        String lastArg = rawArgs[oldFilesCount - 1];

        if ("true".equals(lastArg) || "false".equals(lastArg)) {
            oldFilesCount /= 4;
        } else {
            oldFilesCount /= 3;
        }

        int index = 0;

        for (int i = 0; i < oldFilesCount; i++) {
            args.oldJarFiles.add(new File(rawArgs[index++]));
        }
        args.newJarFile = new File(rawArgs[index++]);
        for (int i = 0; i < oldFilesCount; i++) {
            args.oldIntermediaryFiles.add(new File(rawArgs[index++]));
        }
        args.newIntermediaryFile = new File(rawArgs[index++]);
        for (int i = 0; i < oldFilesCount; i++) {
            args.matchesFiles.add(new File(rawArgs[index++]));
        }
        if (index < oldFilesCount) {
            args.invertMatches = new boolean[args.matchesFiles.size()];
            for (int i = 0; i < oldFilesCount; i++) {
                args.invertMatches[i] = Boolean.parseBoolean(rawArgs[index++]);
            }
        }

        return args;
    }

    public static Args parseOptions(String[] options) {
        int extraLength = 2;
        String[] args = new String[options.length + extraLength];

        for (int i = 0; i < args.length; i++) {
            if (i < extraLength) {
                args[i] = "";
            } else {
                args[i] = options[i - extraLength];
            }
        }

        return parseArgs(args);
    }

    public static class Args {

        private List<File> oldJarFiles = new ArrayList<>();
        private File newJarFile;
        private List<File> oldIntermediaryFiles = new ArrayList<>();
        private File newIntermediaryFile;
        private List<File> matchesFiles = new ArrayList<>();
        private boolean[] invertMatches;

        private String defaultPackage;
        private String targetNamespace;
        private List<String> obfuscationPatterns = new ArrayList<>();
        private Integer nameLength;
        private String clientHash;
        private String serverHash;

    }
}
