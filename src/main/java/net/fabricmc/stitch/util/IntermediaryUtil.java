package net.fabricmc.stitch.util;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

import com.google.common.primitives.Booleans;

import net.fabricmc.stitch.commands.GenState;
import net.fabricmc.stitch.commands.GenStateMerged;
import net.fabricmc.stitch.commands.GenStateSplit;
import net.fabricmc.stitch.representation.Classpath;
import net.fabricmc.stitch.representation.JarReader;

public class IntermediaryUtil
{
    public static MergedArgsBuilder mergedOptions() {
        return new MergedArgsBuilder();
    }

    public static SplitArgsBuilder splitOptions() {
        return new SplitArgsBuilder();
    }

    public static void generateMappings(MergedArgs args) throws IOException {
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

    public static void generateMappings(SplitArgs args) throws IOException {
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
        if (args.newIntermediaryFile != null) {
            state.generate(args.newIntermediaryFile, storageClientNew, storageServerNew, storageClientOld, storageServerOld);
        } else {
            state.generate(args.newClientIntermediaryFile, args.newServerIntermediaryFile, storageClientNew, storageServerNew, storageClientOld, storageServerOld);
        }
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

    public static class Args {

        String defaultPackage;
        String targetNamespace;
        List<String> obfuscationPatterns = new ArrayList<>();
        Integer nameLength;
        String clientHash;
        String serverHash;
        ByteBuffer salt;

    }

    public static abstract class ArgsBuilder {

        abstract Args args();

        public ArgsBuilder defaultPackage(String defaultPackage) {
            args().defaultPackage = defaultPackage;
            return this;
        }

        public ArgsBuilder targetNamespace(String namespace) {
            args().targetNamespace = namespace;
            return this;
        }

        public ArgsBuilder obfuscationPatterns(String... obfuscationPatterns) {
            return obfuscationPatterns(Arrays.asList(obfuscationPatterns));
        }

        public ArgsBuilder obfuscationPatterns(Collection<String> obfuscationPatterns) {
            args().obfuscationPatterns.clear();
            args().obfuscationPatterns.addAll(obfuscationPatterns);
            return this;
        }

        public ArgsBuilder nameLength(int length) {
            args().nameLength = length;
            return this;
        }

        public ArgsBuilder clientHash(String hash) {
            args().clientHash = hash;
            return this;
        }

        public ArgsBuilder serverHash(String hash) {
            args().serverHash = hash;
            return this;
        }
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
        boolean[] invertMatches = new boolean[0];

    }

    public static class MergedArgsBuilder extends ArgsBuilder {

        private final MergedArgs args = new MergedArgs();
        private final List<Boolean> invertMatches = new ArrayList<>();

        @Override
        MergedArgs args() {
            return args;
        }

        public MergedArgsBuilder oldJarFiles(File... jars) {
            return oldJarFiles(Arrays.asList(jars));
        }

        public MergedArgsBuilder oldJarFiles(Collection<File> jars) {
            args.oldJarFiles.clear();
            args.oldJarFiles.addAll(jars);
            return this;
        }

        public MergedArgsBuilder addOldJarFile(File jar) {
            args.oldJarFiles.add(jar);
            return this;
        }

        public MergedArgsBuilder oldLibraries(List<File>... libs) {
            return oldLibraries(Arrays.asList(libs));
        }

        public MergedArgsBuilder oldLibraries(Collection<List<File>> libs) {
            args.oldLibs.clear();
            args.oldLibs.addAll(libs);
            return this;
        }

        public MergedArgsBuilder addOldLibraries(File... libs) {
            return addOldLibraries(Arrays.asList(libs));
        }

        public MergedArgsBuilder addOldLibraries(List<File> libs) {
            args.oldLibs.add(libs);
            return this;
        }

        public MergedArgsBuilder newJarFile(File jar) {
            args.newJarFile = jar;
            return this;
        }

        public MergedArgsBuilder newNests(File nests) {
            args.newNests = nests;
            return this;
        }

        public MergedArgsBuilder newLibraries(File... libs) {
            return newLibraries(Arrays.asList(libs));
        }

        public MergedArgsBuilder newLibraries(Collection<File> libs) {
            args.newLibs.clear();
            args.newLibs.addAll(libs);
            return this;
        }

        public MergedArgsBuilder oldIntermediaryFiles(File... files) {
            return oldIntermediaryFiles(Arrays.asList(files));
        }

        public MergedArgsBuilder oldIntermediaryFiles(Collection<File> files) {
            args.oldIntermediaryFiles.clear();
            args.oldIntermediaryFiles.addAll(files);
            return this;
        }

        public MergedArgsBuilder addOldIntermediaryFile(File file) {
            args.oldIntermediaryFiles.add(file);
            return this;
        }

        public MergedArgsBuilder newIntermediaryFile(File file) {
            args.newIntermediaryFile = file;
            return this;
        }

        public MergedArgsBuilder addMatchesFile(File matches, boolean inverted) {
            args.matchesFiles.add(matches);
            invertMatches.add(inverted);
            return this;
        }

        public MergedArgs build() {
            args.invertMatches = Booleans.toArray(invertMatches);
            return args;
        }
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
        File newClientIntermediaryFile;
        File newServerIntermediaryFile;
        File clientMatchesFile;
        File serverMatchesFile;
        File clientServerMatchesFile;
        boolean invertClientMatches;
        boolean invertServerMatches;
        boolean invertClientServerMatches;

    }

    public static class SplitArgsBuilder extends ArgsBuilder {

        private final SplitArgs args = new SplitArgs();

        @Override
        SplitArgs args() {
            return args;
        }

        public SplitArgsBuilder oldJarFile(File jar) {
            return oldClientJarFile(jar).oldServerJarFile(jar);
        }

        public SplitArgsBuilder oldLibraries(File... libs) {
            return oldClientLibraries(libs).oldServerLibraries(libs);
        }

        public SplitArgsBuilder oldLibraries(Collection<File> libs) {
            return oldClientLibraries(libs).oldServerLibraries(libs);
        }

        public SplitArgsBuilder oldClientJarFile(File jar) {
            args.oldClientJarFile = jar;
            return this;
        }

        public SplitArgsBuilder oldClientLibraries(File... libs) {
            return oldClientLibraries(Arrays.asList(libs));
        }

        public SplitArgsBuilder oldClientLibraries(Collection<File> libs) {
            args.oldClientLibs.clear();
            args.oldClientLibs.addAll(libs);
            return this;
        }

        public SplitArgsBuilder oldServerJarFile(File jar) {
            args.oldServerJarFile = jar;
            return this;
        }

        public SplitArgsBuilder oldServerLibraries(File... libs) {
            return oldServerLibraries(Arrays.asList(libs));
        }

        public SplitArgsBuilder oldServerLibraries(Collection<File> libs) {
            args.oldServerLibs.clear();
            args.oldServerLibs.addAll(libs);
            return this;
        }

        public SplitArgsBuilder newClientJarFile(File jar) {
            args.newClientJarFile = jar;
            return this;
        }

        public SplitArgsBuilder newClientNests(File nests) {
            args.newClientNests = nests;
            return this;
        }

        public SplitArgsBuilder newClientLibraries(File... libs) {
            return newClientLibraries(Arrays.asList(libs));
        }

        public SplitArgsBuilder newClientLibraries(Collection<File> libs) {
            args.newClientLibs.clear();
            args.newClientLibs.addAll(libs);
            return this;
        }

        public SplitArgsBuilder newServerJarFile(File jar) {
            args.newServerJarFile = jar;
            return this;
        }

        public SplitArgsBuilder newServerNests(File nests) {
            args.newServerNests = nests;
            return this;
        }

        public SplitArgsBuilder newServerLibraries(File... libs) {
            return newServerLibraries(Arrays.asList(libs));
        }

        public SplitArgsBuilder newServerLibraries(Collection<File> libs) {
            args.newServerLibs.clear();
            args.newServerLibs.addAll(libs);
            return this;
        }

        public SplitArgsBuilder oldIntermediaryFile(File file) {
            args.oldIntermediaryFile = file;
            args.oldClientIntermediaryFile = null;
            args.oldServerIntermediaryFile = null;
            return this;
        }

        public SplitArgsBuilder oldClientIntermediaryFile(File file) {
            args.oldIntermediaryFile = null;
            args.oldClientIntermediaryFile = file;
            return this;
        }

        public SplitArgsBuilder oldServerIntermediaryFile(File file) {
            args.oldIntermediaryFile = null;
            args.oldServerIntermediaryFile = file;
            return this;
        }

        public SplitArgsBuilder newIntermediaryFile(File file) {
            args.newIntermediaryFile = file;
            args.newClientIntermediaryFile = null;
            args.newServerIntermediaryFile = null;
            return this;
        }

        public SplitArgsBuilder newClientIntermediaryFile(File file) {
            args.newIntermediaryFile = null;
            args.newClientIntermediaryFile = file;
            return this;
        }

        public SplitArgsBuilder newServerIntermediaryFile(File file) {
            args.newIntermediaryFile = null;
            args.newServerIntermediaryFile = file;
            return this;
        }

        public SplitArgsBuilder clientMatchesFile(File matches, boolean inverted) {
            args.clientMatchesFile = matches;
            args.invertClientMatches = inverted;
            return this;
        }

        public SplitArgsBuilder serverMatchesFile(File matches, boolean inverted) {
            args.serverMatchesFile = matches;
            args.invertServerMatches = inverted;
            return this;
        }

        public SplitArgsBuilder clientServerMatchesFile(File matches, boolean inverted) {
            args.clientServerMatchesFile = matches;
            args.invertClientServerMatches = inverted;
            return this;
        }

        public SplitArgs build() {
            return args;
        }
    }
}
