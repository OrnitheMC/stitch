package net.fabricmc.stitch.util;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import net.fabricmc.stitch.commands.CommandCombineTiny;
import net.fabricmc.stitch.commands.GenState;
import net.fabricmc.stitch.representation.JarReader;
import net.fabricmc.stitch.representation.JarRootEntry;

public class IntermediaryUtil
{
    public static void generateIntermediary(File jarFile, File intermediaryFile, String[] options) throws IOException {
        Args args = parseOptions(options);

        args.oldJarFile = null;
        args.otherJarFile = null;
        args.newJarFile = jarFile;
        args.oldIntermediaryFile = null;
        args.otherIntermediaryFile = null;
        args.newIntermediaryFile = intermediaryFile;
        args.oldMatchesFile = null;
        args.otherMatchesFile = null;
        args.invertOldMatches = false;
        args.invertOtherMatches = false;

        updateIntermediary(args, Args.Side.MERGED);
    }

    public static void generateIntermediary(File clientJarFile, File serverJarFile, File intermediaryFile, File matchesFile, String[] options) throws IOException {
        generateIntermediary(clientJarFile, serverJarFile, intermediaryFile, matchesFile, false, options);
    }

    public static void generateIntermediary(File clientJarFile, File serverJarFile, File intermediaryFile, File matchesFile, boolean invertMatches, String[] options) throws IOException {
        File tmp = new File(intermediaryFile, "tmp");
        File clientIntermediaryFile = null;
        File serverIntermediaryFile = null;
        if (clientJarFile != null) {
            clientIntermediaryFile = new File(tmp, "client-intermediary.tiny");
        }
        if (serverJarFile != null) {
            serverIntermediaryFile = new File(tmp, "server-intermediary.tiny");
        }

        tmp.mkdirs();

        if (clientJarFile != null) {
            Args args = parseOptions(options);

            args.newJarFile = clientJarFile;
            args.newIntermediaryFile = clientIntermediaryFile;

            updateIntermediary(args, Args.Side.CLIENT);
        }
        if (serverJarFile != null) {
            Args args = parseOptions(options);

            if (clientJarFile != null) {
                args.otherJarFile = clientJarFile;
            }
            args.newJarFile = serverJarFile;
            if (clientIntermediaryFile != null) {
                args.otherIntermediaryFile = clientIntermediaryFile;
            }
            args.newIntermediaryFile = serverIntermediaryFile;
            if (matchesFile != null) {
                args.otherMatchesFile = matchesFile;
            }
            args.invertOtherMatches = invertMatches;

            updateIntermediary(args, Args.Side.SERVER);
        }

        System.err.println("Combining client and server mappings...");
        try {
            new CommandCombineTiny().run(new String[] {
                clientIntermediaryFile.getAbsolutePath(),
                serverIntermediaryFile.getAbsolutePath(),
                intermediaryFile.getAbsolutePath()
            });

            if (clientIntermediaryFile != null) {
                clientIntermediaryFile.delete();
            }
            if (serverIntermediaryFile != null) {
                serverIntermediaryFile.delete();
            }
            tmp.delete();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static void updateIntermediary(File oldJarFile, File newJarFile, File oldIntermediaryFile, File newIntermediaryFile, File matchesFile, String[] options) throws IOException {
        updateIntermediary(oldJarFile, newJarFile, oldIntermediaryFile, newIntermediaryFile, matchesFile, false, options);
    }

    public static void updateIntermediary(File oldJarFile, File newJarFile, File oldIntermediaryFile, File newIntermediaryFile, File matchesFile, boolean invertMatches, String[] options) throws IOException {
        Args args = parseOptions(options);

        args.oldJarFile = oldJarFile;
        args.otherJarFile = null;
        args.newJarFile = newJarFile;
        args.oldIntermediaryFile = oldIntermediaryFile;
        args.otherIntermediaryFile = null;
        args.newIntermediaryFile = newIntermediaryFile;
        args.oldMatchesFile = matchesFile;
        args.otherMatchesFile = null;
        args.invertOldMatches = invertMatches;
        args.invertOtherMatches = false;

        updateIntermediary(args, Args.Side.MERGED);
    }

    public static void updateIntermediary(File oldJarFile, File newClientJarFile, File newServerJarFile, File oldIntermediaryFile, File newIntermediaryFile, File clientMatchesFile, File serverMatchesFile, File clientServerMatchesFile, String[] options) throws IOException {
        updateIntermediary(oldJarFile, newClientJarFile, newServerJarFile, oldIntermediaryFile, newIntermediaryFile, clientMatchesFile, serverMatchesFile, clientServerMatchesFile, false, false, false, options);
    }

    public static void updateIntermediary(File oldJarFile, File newClientJarFile, File newServerJarFile, File oldIntermediaryFile, File newIntermediaryFile, File clientMatchesFile, File serverMatchesFile, File clientServerMatchesFile, boolean invertClientMatches, boolean invertServerMatches, boolean invertClientServerMatches, String[] options) throws IOException {
        File tmp = new File(newIntermediaryFile.getParentFile(), "tmp");
        File newClientIntermediaryFile = null;
        File newServerIntermediaryFile = null;
        if (newClientJarFile != null) {
            newClientIntermediaryFile = new File(tmp, "new-client-intermediary.tiny");
        }
        if (newServerJarFile != null) {
            newServerIntermediaryFile = new File(tmp, "new-server-intermediary.tiny");
        }

        tmp.mkdirs();

        if (newClientJarFile != null) {
            Args args = parseOptions(options);

            args.oldJarFile = oldJarFile;
            args.newJarFile = newClientJarFile;
            args.oldIntermediaryFile = oldIntermediaryFile;
            args.newIntermediaryFile = newClientIntermediaryFile;
            args.oldMatchesFile = clientMatchesFile;
            args.invertOldMatches = invertClientMatches;

            updateIntermediary(args, Args.Side.CLIENT);
        }
        if (newServerJarFile != null) {
            Args args = parseOptions(options);

            args.oldJarFile = oldJarFile;
            if (newClientJarFile != null) {
                args.otherJarFile = newClientJarFile;
            }
            args.newJarFile = newServerJarFile;
            args.oldIntermediaryFile = oldIntermediaryFile;
            if (newClientIntermediaryFile != null) {
                args.otherIntermediaryFile = newClientIntermediaryFile;
            }
            args.newIntermediaryFile = newServerIntermediaryFile;
            args.oldMatchesFile = serverMatchesFile;
            if (clientServerMatchesFile != null) {
                args.otherMatchesFile = clientServerMatchesFile;
            }
            args.invertOldMatches = invertServerMatches;
            args.invertOtherMatches = invertClientServerMatches;

            updateIntermediary(args, Args.Side.SERVER);
        }

        System.err.println("Combining client and server mappings...");
        try {
            new CommandCombineTiny().run(new String[] {
                newClientIntermediaryFile.getAbsolutePath(),
                newServerIntermediaryFile.getAbsolutePath(),
                newIntermediaryFile.getAbsolutePath()
            });

            if (newClientIntermediaryFile != null) {
                newClientIntermediaryFile.delete();
            }
            if (newServerIntermediaryFile != null) {
                newServerIntermediaryFile.delete();
            }
            tmp.delete();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static void updateIntermediary(File oldClientJarFile, File oldServerJarFile, File newClientJarFile, File newServerJarFile, File oldIntermediaryFile, File newIntermediaryFile, File clientMatchesFile, File serverMatchesFile, File clientServerMatchesFile, String[] options) throws IOException {
        updateIntermediary(oldClientJarFile, oldServerJarFile, newClientJarFile, newServerJarFile, oldIntermediaryFile, newIntermediaryFile, clientMatchesFile, serverMatchesFile, clientServerMatchesFile, false, false, false, options);
    }

    public static void updateIntermediary(File oldClientJarFile, File oldServerJarFile, File newClientJarFile, File newServerJarFile, File oldIntermediaryFile, File newIntermediaryFile, File clientMatchesFile, File serverMatchesFile, File clientServerMatchesFile, boolean invertClientMatches, boolean invertServerMatches, boolean invertClientServerMatches, String[] options) throws IOException {
        File tmp = new File(newIntermediaryFile, "tmp");
        File oldClientIntermediaryFile = null;
        File oldServerIntermediaryFile = null;
        File newClientIntermediaryFile = null;
        File newServerIntermediaryFile = null;
        if (oldClientJarFile != null) {
            oldClientIntermediaryFile = new File(tmp, "old-client-intermediary.tiny");
        }
        if (oldServerJarFile != null) {
            oldServerIntermediaryFile = new File(tmp, "old-server-intermediary.tiny");
        }
        if (newClientJarFile != null) {
            newClientIntermediaryFile = new File(tmp, "new-client-intermediary.tiny");
        }
        if (newServerJarFile != null) {
            newServerIntermediaryFile = new File(tmp, "new-server-intermediary.tiny");
        }

        tmp.mkdirs();

        if (newClientJarFile != null) {
            Args args = parseOptions(options);

            args.oldJarFile = oldClientJarFile;
            args.newJarFile = newClientJarFile;
            args.oldIntermediaryFile = oldClientIntermediaryFile;
            args.newIntermediaryFile = newClientIntermediaryFile;
            args.oldMatchesFile = clientMatchesFile;
            args.invertOldMatches = invertClientMatches;

            updateIntermediary(args, Args.Side.CLIENT);
        }
        if (newServerJarFile != null) {
            Args args = parseOptions(options);

            args.oldJarFile = oldServerJarFile;
            if (newClientJarFile != null) {
                args.otherJarFile = newClientJarFile;
            }
            args.newJarFile = newServerJarFile;
            args.oldIntermediaryFile = oldServerIntermediaryFile;
            if (newClientIntermediaryFile != null) {
                args.otherIntermediaryFile = newClientIntermediaryFile;
            }
            args.newIntermediaryFile = newServerIntermediaryFile;
            args.oldMatchesFile = serverMatchesFile;
            if (clientServerMatchesFile != null) {
                args.otherMatchesFile = clientServerMatchesFile;
            }
            args.invertOldMatches = invertServerMatches;
            args.invertOtherMatches = invertClientServerMatches;

            updateIntermediary(args, Args.Side.SERVER);
        }

        System.err.println("Combining client and server mappings...");
        try {
            new CommandCombineTiny().run(new String[] {
                newClientIntermediaryFile.getAbsolutePath(),
                newServerIntermediaryFile.getAbsolutePath(),
                newIntermediaryFile.getAbsolutePath()
            });

            if (oldClientIntermediaryFile != null) {
                oldClientIntermediaryFile.delete();
            }
            if (oldServerIntermediaryFile != null) {
                oldServerIntermediaryFile.delete();
            }
            if (newClientIntermediaryFile != null) {
                newClientIntermediaryFile.delete();
            }
            if (newServerIntermediaryFile != null) {
                newServerIntermediaryFile.delete();
            }

            tmp.delete();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static void updateIntermediary(Args args, Args.Side side) throws IOException {
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
        if (args.clientHash != null && side.isClient()) {
            salt.put(salt.position(), args.clientHash.getBytes(StandardCharsets.UTF_8));
        }
        if (args.serverHash != null && side.isServer()) {
            salt.put(salt.position(), args.serverHash.getBytes(StandardCharsets.UTF_8));
        }

        JarRootEntry jarOld = null;
        if (args.oldJarFile != null) {
            jarOld = new JarRootEntry(args.oldJarFile);
        }
        JarRootEntry jarOther = null;
        if (args.otherJarFile != null) {
            jarOther = new JarRootEntry(args.otherJarFile);
        }
        JarRootEntry jarNew = new JarRootEntry(args.newJarFile);

        try {
            if (jarOld != null) {
                new JarReader(jarOld).apply();
            }
            if (jarOther != null) {
                new JarReader(jarOther).apply();
            }
            new JarReader(jarNew).apply(salt.array());
        } catch (Exception e) {
            e.printStackTrace();
        }

        if (args.oldIntermediaryFile != null || args.otherIntermediaryFile != null) {
            System.err.println("Loading remapping files...");
            state.prepareUpdate(args.oldIntermediaryFile, args.otherIntermediaryFile, args.oldMatchesFile, args.otherMatchesFile, args.invertOldMatches, args.invertOtherMatches);
        }

        System.err.println("Generating new mappings...");
        state.generate(args.newIntermediaryFile, jarNew, jarOld, jarOther);
        System.err.println("Done!");
    }

    public static Args parseArgs(String[] rawArgs) {
        Args args = new Args();

        int fileCount = 0;

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
                fileCount++;
                break;
            }
        }

        if (fileCount == 0) {
            return args;
        }

        // after all the files is the invert matches booleans
        // first check if they are present, since they are optional
        String lastArg = rawArgs[fileCount - 1];
        boolean parseInvertMatches = "true".equals(lastArg) || "false".equals(lastArg);

        if (parseInvertMatches) {
            fileCount /= 4;
        } else {
            fileCount /= 3;
        }

        boolean splitJar = (fileCount == 3);
        int index = 0;

        args.oldJarFile = new File(rawArgs[index++]);
        if (splitJar) {
            args.otherJarFile = new File(rawArgs[index++]);
        }
        args.newJarFile = new File(rawArgs[index++]);
        args.oldIntermediaryFile = new File(rawArgs[index++]);
        if (splitJar) {
            args.otherIntermediaryFile = new File(rawArgs[index++]);
        }
        args.newIntermediaryFile = new File(rawArgs[index++]);
        args.oldMatchesFile = new File(rawArgs[index++]);
        if (splitJar) {
            args.otherMatchesFile = new File(rawArgs[index++]);
        }
        if (parseInvertMatches) {
            args.invertOldMatches = Boolean.parseBoolean(rawArgs[index++]);
            if (splitJar) {
                args.invertOtherMatches = Boolean.parseBoolean(rawArgs[index++]);
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

        private File oldJarFile;
        private File otherJarFile;
        private File newJarFile;
        private File oldIntermediaryFile;
        private File otherIntermediaryFile;
        private File newIntermediaryFile;
        private File oldMatchesFile;
        private File otherMatchesFile;
        private boolean invertOldMatches;
        private boolean invertOtherMatches;
        private Side side;

        private String defaultPackage;
        private String targetNamespace;
        private List<String> obfuscationPatterns = new ArrayList<>();
        private Integer nameLength;
        private String clientHash;
        private String serverHash;

        public enum Side {

            CLIENT, SERVER, MERGED;

            public boolean isClient() {
                return this == CLIENT || this == MERGED;
            }

            public boolean isServer() {
                return this == SERVER || this == MERGED;
            }
        }
    }
}
