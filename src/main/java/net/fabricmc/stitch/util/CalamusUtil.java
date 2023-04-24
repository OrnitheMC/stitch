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

public class CalamusUtil
{
    public static void generateCalamus(File jarFile, File calamusFile, String[] options) throws IOException {
        Args args = parseOptions(options);

        args.newJarFile = jarFile;
        args.newCalamusFile = calamusFile;

        generateCalamus(args);
    }

    public static void generateCalamus(Args args) throws IOException {
        args.oldJarFiles.clear();
        args.oldCalamusFiles.clear();
        args.matchesFiles.clear();

        updateCalamus(args);
    }

    public static void updateCalamus(List<File> oldJarFiles, File newJarFile, List<File> oldCalamusFiles, File newCalamusFile, List<File> matchesFiles, String[] options) throws IOException {
        Args args = parseOptions(options);

        args.oldJarFiles = oldJarFiles;
        args.newJarFile = newJarFile;
        args.oldCalamusFiles = oldCalamusFiles;
        args.newCalamusFile = newCalamusFile;
        args.matchesFiles = matchesFiles;

        updateCalamus(args);
    }

    public static void updateCalamus(Args args) throws IOException {
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

        if (!args.oldCalamusFiles.isEmpty()) {
            System.err.println("Loading remapping files...");
            state.prepareUpdate(args.oldCalamusFiles, args.matchesFiles);
        }

        System.err.println("Generating new mappings...");
        state.generate(args.newCalamusFile, jarNew, jarsOld);
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

        oldFilesCount /= 3;
        int index = 0;

        for (int i = 0; i < oldFilesCount; i++) {
            args.oldJarFiles.add(new File(rawArgs[index++]));
        }
        args.newJarFile = new File(rawArgs[index++]);
        for (int i = 0; i < oldFilesCount; i++) {
            args.oldCalamusFiles.add(new File(rawArgs[index++]));
        }
        args.newCalamusFile = new File(rawArgs[index++]);
        for (int i = 0; i < oldFilesCount; i++) {
            args.matchesFiles.add(new File(rawArgs[index++]));
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
        private List<File> oldCalamusFiles = new ArrayList<>();
        private File newCalamusFile;
        private List<File> matchesFiles = new ArrayList<>();

        private String defaultPackage;
        private String targetNamespace;
        private List<String> obfuscationPatterns = new ArrayList<>();
        private Integer nameLength;
        private String clientHash;
        private String serverHash;

    }
}
