/*
 * Copyright (c) 2016, 2017, 2018, 2019 FabricMC
 * Modifications copyright (c) 2022 OrnitheMC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.fabricmc.stitch.commands;

import net.fabricmc.mappings.EntryTriple;
import net.fabricmc.mappings.MappingsProvider;
import net.fabricmc.stitch.representation.*;
import net.fabricmc.stitch.util.MatcherUtil;
import net.fabricmc.stitch.util.Pair;
import net.fabricmc.stitch.util.StitchUtil;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.objectweb.asm.Opcodes;

import java.io.*;
import java.math.BigInteger;
import java.util.*;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

public class GenState
{
    private final Map<AbstractJarEntry, String> values = new IdentityHashMap<>();
    private final Scanner scanner = new Scanner(System.in);
    private final List<Pattern> obfuscatedPatterns = new ArrayList<>();
    private final Map<JarMethodEntry, String> methodNames = new IdentityHashMap<>();
    private boolean merged;
    private GenMap oldToIntermediary = new GenMap(), otherToIntermediary = new GenMap(), newToOld = new GenMap(), newToOther = new GenMap();
    private GenMap newToIntermediary;
    private String defaultPackage = "net/minecraft/";
    private String targetNamespace = "intermediary";
    private int nameLength = 6;

    public GenState() {
        this.obfuscatedPatterns.add(Pattern.compile("^[^A-Z]*$")); // Default ofbfuscation. Obfuscated names are all lowercase
    }

    public static boolean isMappedClass(JarClassEntry c) {
        // if an anonymous class does not follow the convention
        // for inner class names, we give it a new new anyway
        return !c.isAnonymous() || !c.getName().startsWith(c.getEnclosingClassName() + "$");
    }

    public static boolean isMappedField(JarFieldEntry f) {
        return isUnmappedFieldName(f.getName());
    }

    public static boolean isUnmappedFieldName(String name) {
        return true; // make sure even unobfuscated fields are given names
    }

    public static boolean isMappedMethod(JarRootEntry storage, JarClassEntry c, JarMethodEntry m) {
        return isMappedMethodName(m.getName()) && m.isSource(storage, c);
    }

    public static boolean isMappedMethodName(String name) {
        return name.charAt(0) != '<' && !"main".equals(name); // make sure only constructors and main methods are not remapped
    }

    public boolean isObfuscated(JarClassEntry c) {
        return isObfuscated(c.getName());
    }

    public boolean isObfuscated(String name) {
        return this.obfuscatedPatterns.stream().anyMatch(p -> p.matcher(name).matches());
    }

    public void setWriteAll() {
    }

    public String next(AbstractJarEntry entry, String prefix) {
        return prefix + "_" + values.computeIfAbsent(entry, (e) -> {
            BigInteger bigInt = new BigInteger(e.getHash());
            StringBuilder builder = new StringBuilder();

            for (int i = 0; i < nameLength; i++) {
                int digit = bigInt.mod(BigInteger.valueOf(10)).intValue();
                bigInt = bigInt.divide(BigInteger.valueOf(10));

                builder.insert(0, (char) ('0' + digit));
            }

            return builder.toString();
        });
    }

    public void setDefaultPackage(final String defaultPackage) {
        if (defaultPackage.lastIndexOf("/") != (defaultPackage.length() - 1))
            this.defaultPackage = defaultPackage + "/";
        else
            this.defaultPackage = defaultPackage;
    }

    public void setTargetNamespace(final String namespace) {
        this.targetNamespace = namespace;
    }

    public void clearObfuscatedPatterns() {
        this.obfuscatedPatterns.clear();
    }

    public void addObfuscatedPattern(String regex) throws PatternSyntaxException {
        this.obfuscatedPatterns.add(Pattern.compile(regex));
    }

    public void setNameLength(int length) {
        if (length < 2) {
            throw new IllegalArgumentException("name length cannot be less than 2!");
        }

        this.nameLength = length;
    }

    public void generate(File file, JarRootEntry jarEntry, JarRootEntry jarOld, JarRootEntry jarOther) throws IOException {
        if (file.exists()) {
            System.err.println("Target file exists - loading...");
            newToIntermediary = new GenMap();
            try (FileInputStream inputStream = new FileInputStream(file)) {
                //noinspection deprecation
                newToIntermediary.load(
                      MappingsProvider.readTinyMappings(inputStream),
                      "official",
                      targetNamespace
                );
            }
        }

        try (FileWriter fileWriter = new FileWriter(file)) {
            try (BufferedWriter writer = new BufferedWriter(fileWriter)) {
                writer.write(String.format("v1\tofficial\t%s\n", targetNamespace));

                for (JarClassEntry c : jarEntry.getClasses()) {
                    addClass(writer, c, jarOld, jarOther, jarEntry, this.defaultPackage);
                }
            }
        }
    }

    @Nullable
    private String getFieldName(JarClassEntry c, JarFieldEntry f) {
        if (!isMappedField(f)) {
            return null;
        }

        if (newToIntermediary != null) {
            //noinspection deprecation
            EntryTriple findEntry = newToIntermediary.getField(c.getName(), f.getName(), f.getDescriptor());
            if (findEntry != null) {
                if (findEntry.getName().contains("f_")) {
                    return findEntry.getName();
                } else {
                    String newName = next(f, "f");
                    System.out.println(findEntry.getName() + " is now " + newName);
                    return newName;
                }
            }
        }

        String oldName = inheritFieldName(c, f, newToOld, oldToIntermediary);
        String otherName = merged ? null : inheritFieldName(c, f, newToOther, otherToIntermediary);
        boolean oldInvalid = false;

        if (oldName != null && !oldName.contains("f_")) {
            oldInvalid = true;
        }
        if (!oldInvalid && oldName != null && otherName != null && !oldName.equals(otherName)) {
            throw new IllegalStateException("illegal name inheritance (old, other) -> new: (" + oldName + ", " + otherName + ")");
        }
        if (!oldInvalid && oldName != null) {
            return oldName;
        }
        if (otherName != null) {
            return otherName;
        }

        String name = next(f, "f");

        if (oldInvalid) {
            System.out.println(oldName + " is now " + name);
        }

        return name;
    }

    private String inheritFieldName(JarClassEntry c, JarFieldEntry f, GenMap matchedJar, GenMap matchedIntermediary) {
        //noinspection deprecation
        EntryTriple findEntry = matchedJar.getField(c.getName(), f.getName(), f.getDescriptor());
        if (findEntry != null) {
            findEntry = matchedIntermediary.getField(findEntry);
            if (findEntry != null) {
                return findEntry.getName();
            }
        }

        return null;
    }

    private String getPropagation(JarRootEntry storage, JarClassEntry classEntry) {
        if (classEntry == null) {
            return "";
        }

        StringBuilder builder = new StringBuilder(classEntry.getName());
        List<String> strings = new ArrayList<>();
        String scs = getPropagation(storage, classEntry.getSuperClass(storage));
        if (!scs.isEmpty()) {
            strings.add(scs);
        }

        for (JarClassEntry ce : classEntry.getInterfaces(storage)) {
            scs = getPropagation(storage, ce);
            if (!scs.isEmpty()) {
                strings.add(scs);
            }
        }

        if (!strings.isEmpty()) {
            builder.append("<-");
            if (strings.size() == 1) {
                builder.append(strings.get(0));
            } else {
                builder.append("[");
                builder.append(StitchUtil.join(",", strings));
                builder.append("]");
            }
        }

        return builder.toString();
    }

    private String getNamesListEntry(JarRootEntry storage, JarClassEntry classEntry) {
        StringBuilder builder = new StringBuilder(getPropagation(storage, classEntry));
        if (classEntry.isInterface()) {
            builder.append("(itf)");
        }

        return builder.toString();
    }

    private Set<JarMethodEntry> findNames(JarRootEntry matchedStorage, JarRootEntry storage, JarClassEntry c, JarMethodEntry m, Map<String, Set<String>> names, GenMap matchedJar, GenMap matchedIntermediary) {
        Set<JarMethodEntry> allEntries = new HashSet<>();
        findNames(matchedStorage, storage, c, m, names, allEntries, matchedJar, matchedIntermediary);
        return allEntries;
    }

    private void findNames(JarRootEntry matchedStorage, JarRootEntry storage, JarClassEntry c, JarMethodEntry m, Map<String, Set<String>> names, Set<JarMethodEntry> usedMethods, GenMap matchedJar, GenMap matchedIntermediary) {
        if (!usedMethods.add(m)) {
            return;
        }

        String suffix = "." + m.getName() + m.getDescriptor();

        if ((m.getAccess() & Opcodes.ACC_BRIDGE) != 0) {
            suffix += "(bridge)";
        }

        List<JarClassEntry> ccList = m.getMatchingEntries(storage, c);

        for (JarClassEntry cc : ccList) {
            EntryTriple findEntry = null;
            if (newToIntermediary != null) {
                findEntry = newToIntermediary.getMethod(cc.getName(), m.getName(), m.getDescriptor());
                if (findEntry != null) {
                    names.computeIfAbsent(findEntry.getName(), (s) -> new TreeSet<>()).add(getNamesListEntry(storage, cc) + suffix);
                }
            }

            if (findEntry == null && matchedJar != null) {
                findEntry = matchedJar.getMethod(cc.getName(), m.getName(), m.getDescriptor());
                if (findEntry != null) {
                    EntryTriple matchedEntry = findEntry;
                    findEntry = matchedIntermediary.getMethod(matchedEntry);
                    if (findEntry != null) {
                        names.computeIfAbsent(findEntry.getName(), (s) -> new TreeSet<>()).add(getNamesListEntry(storage, cc) + suffix);
                    } else {
                        // more involved...
                        JarClassEntry matchedBase = matchedStorage.getClass(matchedEntry.getOwner(), null);
                        if (matchedBase != null) {
                            JarMethodEntry matchedM = matchedBase.getMethod(matchedEntry.getName() + matchedEntry.getDesc());
                            List<JarClassEntry> cccList = matchedM.getMatchingEntries(matchedStorage, matchedBase);

                            for (JarClassEntry ccc : cccList) {
                                findEntry = matchedIntermediary.getMethod(ccc.getName(), matchedM.getName(), matchedM.getDescriptor());
                                if (findEntry != null) {
                                    names.computeIfAbsent(findEntry.getName(), (s) -> new TreeSet<>()).add(getNamesListEntry(matchedStorage, ccc) + suffix);
                                }
                            }
                        }
                    }
                }
            }
        }

        for (JarClassEntry mc : ccList) {
            for (Pair<JarClassEntry, String> pair : mc.getRelatedMethods(m)) {
                findNames(matchedStorage, storage, pair.getLeft(), pair.getLeft().getMethod(pair.getRight()), names, usedMethods, matchedJar, matchedIntermediary);
            }
        }
    }

    private String inheritMethodName(JarRootEntry matchedStorage, JarRootEntry storage, JarClassEntry c, JarMethodEntry m, boolean side, GenMap matchedJar, GenMap matchedIntermediary) {
        Map<String, Set<String>> names = new HashMap<>();
        Set<JarMethodEntry> allEntries = findNames(matchedStorage, storage, c, m, names, matchedJar, matchedIntermediary);
        for (JarMethodEntry mm : allEntries) {
            if (methodNames.containsKey(mm)) {
                return methodNames.get(mm);
            }
        }

        if (names.size() > 1) {
            if (side) {
                List<String> nameList = new ArrayList<>(names.keySet());
                Collections.sort(nameList);

                String message = "method conflict between the two halves of the jar!";

                for (int i = 0; i < nameList.size(); i++) {
                    message += "\n " + i + ")" + nameList.get(i) + " <- " + StitchUtil.join(", ", names.get(nameList.get(i)));
                }

                throw new RuntimeException(message);
            }

            System.out.println("Conflict detected - matched same target name!");
            List<String> nameList = new ArrayList<>(names.keySet());
            Collections.sort(nameList);

            for (int i = 0; i < nameList.size(); i++) {
                String s = nameList.get(i);
                System.out.println((i+1) + ") " + s + " <- " + StitchUtil.join(", ", names.get(s)));
            }

            boolean interactive = true;
            if (!interactive) {
                throw new RuntimeException("Conflict detected!");
            }

            while (true) {
                String cmd = scanner.nextLine();
                int i;
                try {
                    i = Integer.parseInt(cmd);
                } catch (NumberFormatException e) {
                    e.printStackTrace();
                    continue;
                }

                if (i >= 1 && i <= nameList.size()) {
                    for (JarMethodEntry mm : allEntries) {
                        methodNames.put(mm, nameList.get(i - 1));
                    }
                    System.out.println("OK!");
                    return nameList.get(i - 1);
                }
            }
        } else if (names.size() == 1) {
            String s = names.keySet().iterator().next();
            for (JarMethodEntry mm : allEntries) {
                methodNames.put(mm, s);
            }
            return s;
        }

        return null;
    }

    @Nullable
    private String getMethodName(JarRootEntry storageOld, JarRootEntry storageOther, JarRootEntry storage, JarClassEntry c, JarMethodEntry m) {
        if (!isMappedMethod(storage, c, m)) {
            return null;
        }

        if (methodNames.containsKey(m)) {
            return methodNames.get(m);
        }

        String oldName = inheritMethodName(storageOld, storage, c, m, false, newToOld, oldToIntermediary);
        String otherName = merged ? null : inheritMethodName(storageOther, storage, c, m, true, newToOther, otherToIntermediary);
        boolean oldInvalid = false;

        if (oldName != null && !oldName.contains("m_")) {
            oldInvalid = true;
        }
        if (!oldInvalid && oldName != null && otherName != null && !oldName.equals(otherName)) {
            throw new IllegalStateException("illegal name inheritance (old, other) -> new: (" + oldName + ", " + otherName + ")");
        }
        if (!oldInvalid && oldName != null) {
            return oldName;
        }
        if (otherName != null) {
            return otherName;
        }

        String name = nextMethodName(storage, c, m);

        if (oldInvalid) {
            System.out.println(oldName + " is now " + name);
        }

        return name;
    }

    private String nextMethodName(JarRootEntry storage, JarClassEntry c, JarMethodEntry m) {
        String key = m.getName() + m.getDescriptor();
        Set<JarMethodEntry> ms = new TreeSet<>((m1, m2) -> {
            return m1.getParentName().compareTo(m2.getParentName());
        });

        findSourceMethod(storage, c, key, ms);

        if (ms.isEmpty()) {
            // method is most likely private or static
            return next(m, "m");
        }

        Iterator<JarMethodEntry> it = ms.iterator();
        JarMethodEntry pm = it.next();

        String name = next(pm, "m");
        String suf = name.substring(2);

        while (it.hasNext()) {
            values.put(it.next(), suf);
        }

        return name;
    }

    private void findEquivalentMethods(JarRootEntry storage, JarClassEntry c, String key, Collection<JarMethodEntry> methods) {
        findSourceMethod(storage, c, key, methods);

        for (JarClassEntry cs : c.getSubclasses(storage)) {
            findEquivalentMethods(storage, cs, key, methods);
        }
        for (JarClassEntry ci : c.getImplementers(storage)) {
            findEquivalentMethods(storage, ci, key, methods);
        }
    }

    private boolean findSourceMethod(JarRootEntry storage, JarClassEntry c, String key, Collection<JarMethodEntry> methods) {
        JarMethodEntry m = c.getMethod(key);

        boolean hasMethod = false;
        boolean parentsAdded = false;

        if (m != null) {
            if (Access.isPrivateOrStatic(m.getAccess())) {
                return false;
            }
            if (methods.contains(m)) {
                return true;
            }

            hasMethod = true;
        }

        JarClassEntry sc = c.getSuperClass(storage);

        if (sc != null) {
            parentsAdded = findSourceMethod(storage, sc, key, methods) | parentsAdded;
        }

        for (JarClassEntry ic : c.getInterfaces(storage)) {
            parentsAdded = findSourceMethod(storage, ic, key, methods) | parentsAdded;
        }

        if (!parentsAdded && hasMethod) {
            if (methods.add(m)) {
                findEquivalentMethods(storage, c, key, methods);
            }
        }

        return parentsAdded || hasMethod;
    }

    private void addClass(BufferedWriter writer, JarClassEntry c, JarRootEntry storageOld, JarRootEntry storageOther, JarRootEntry storage, String translatedPrefix) throws IOException {
        String fullName = c.getName();
        String cname = "";
        // Typically inner class names are of the form com/example/Example$InnerName
        // but this is not required by the JVM spec! However, for the names we generate
        // we do follow this convention.
        if (c.isLocal()) {
            String enclName = c.getEnclosingClassName();
            String innerName = c.getInnerName();
            // typically, local classes' full names have a number prefix
            // before the inner name part - this is useful for allowing
            // multiple local classes to have the same inner name, so we add it
            if (fullName.startsWith(enclName + "$") && fullName.endsWith(innerName)) {
                translatedPrefix += fullName.substring(enclName.length() + 1, fullName.length() - innerName.length());
            }
        }
        String prefixSaved = translatedPrefix;

        if (!isObfuscated(fullName)) {
            translatedPrefix = fullName;
        } else {
            if (!isMappedClass(c)) {
                if (c.isAnonymous()) {
                    // anonymous classes are only unmapped if their name
                    // follows the standard $ convention
                    cname = fullName.substring(c.getEnclosingClassName().length() + 1);
                } else {
                    // throw exception in case the impl of isMappedClass
                    // changes but we forget to deal with it here
                    throw new IllegalStateException("don't know what to do with class " + fullName);
                }
            } else {
                cname = null;

                if (newToIntermediary != null) {
                    String findName = newToIntermediary.getClass(fullName);
                    if (findName != null) {
                        // the names we generate follow the standard convention for inner class names,
                        // so we can safely find the inner name like this
                        String[] r = findName.split("\\$");
                        if (r.length > 1) {
                            cname = stripLocalClassPrefix(r[r.length - 1]);
                        } else {
                            cname = stripPackageName(findName);
                        }
                    }
                }

                if (cname == null) {
                    String oldName = inheritClassName(fullName, newToOld, oldToIntermediary);
                    String otherName = merged ? null : inheritClassName(fullName, newToOther, otherToIntermediary);

                    if (oldName != null && otherName != null && !oldName.equals(otherName)) {
                        throw new IllegalStateException("illegal name inheritance (old, other) -> new: (" + oldName + ", " + otherName + ")");
                    }
                    if (oldName != null) {
                        cname = oldName;
                    }
                    if (otherName != null) {
                        cname = otherName;
                    }
                }

                if (cname == null) {
                    cname = next(c, "C");
                }
            }
        }

        writer.write("CLASS\t" + c.getName() + "\t" + translatedPrefix + cname + "\n");

        for (JarFieldEntry f : c.getFields()) {
            String fName = getFieldName(c, f);
            if (fName == null) {
                fName = f.getName();
            }

            if (fName != null) {
                writer.write("FIELD\t" + fullName
                      + "\t" + f.getDescriptor()
                      + "\t" + f.getName()
                      + "\t" + fName + "\n");
            }
        }

        for (JarMethodEntry m : c.getMethods()) {
            String mName = getMethodName(storageOld, storageOther, storage, c, m);
            if (mName == null) {
                if (!m.getName().startsWith("<") && m.isSource(storage, c)) {
                    mName = m.getName();
                }
            }

            if (mName != null) {
                writer.write("METHOD\t" + fullName
                      + "\t" + m.getDescriptor()
                      + "\t" + m.getName()
                      + "\t" + mName + "\n");
            }
        }

        for (JarClassEntry cc : c.getInnerClasses()) {
            addClass(writer, cc, storageOld, storageOther, storage, translatedPrefix + cname + "$");
        }
    }

    private String inheritClassName(String fullName, GenMap matchedJar, GenMap matchedIntermediary) {
        String findName = matchedJar.getClass(fullName);
        if (findName != null) {
            // similar to above, the names we generate follow the convention for inner classes
            findName = matchedIntermediary.getClass(findName);
            if (findName != null) {
                String[] nr = fullName.split("\\$");
                String[] or = findName.split("\\$");
                if (or.length == nr.length) {
                    if (nr.length > 1) {
                        return stripLocalClassPrefix(or[or.length - 1]);
                    } else {
                        return stripPackageName(findName);
                    }
                } else {
                    // nesting level changed; matching name is not necessary
                }
            }
        }

        return null;
    }

    private String stripLocalClassPrefix(String innerName) {
        int localStart = 0;

        // local class names start with a number prefix
        while (localStart < innerName.length() && Character.isDigit(innerName.charAt(localStart))) {
            localStart++;
        }
        // if entire inner name is a number, this class is anonymous, not local
        if (localStart == innerName.length()) {
            localStart = 0;
        }

        return innerName.substring(localStart);
    }

    private String stripPackageName(String className) {
        return className.substring(className.lastIndexOf('/') + 1);
    }

    public void prepareRewrite(File oldMappings) throws IOException {
        oldToIntermediary = new GenMap();
        otherToIntermediary = null;
        newToOld = new GenMap();
        newToOther = null;

        try (FileInputStream inputStream = new FileInputStream(oldMappings)) {
            //noinspection deprecation
            oldToIntermediary.load(
                  MappingsProvider.readTinyMappings(inputStream),
                  "official",
                  targetNamespace
            );
        }
    }

    public void prepareUpdate(File oldMappings, File otherMappings, File oldMatches, File otherMatches, boolean invertOldMatches, boolean invertOtherMatches) throws IOException {
        merged = true;
        oldToIntermediary = null;
        otherToIntermediary = null;
        newToOld = null;
        newToOther = null;

        if (oldMappings != null) {
            oldToIntermediary = new GenMap();
            newToOld = new GenMap();

            try (FileInputStream inputStream = new FileInputStream(oldMappings)) {
                //noinspection deprecation
                oldToIntermediary.load(
                    MappingsProvider.readTinyMappings(inputStream),
                    "official",
                    targetNamespace
                );
            }

            try (FileReader fileReader = new FileReader(oldMatches)) {
                try (BufferedReader reader = new BufferedReader(fileReader)) {
                    MatcherUtil.read(reader, !invertOldMatches, newToOld::addClass, newToOld::addField, newToOld::addMethod);
                }
            }
        }
        if (otherMappings != null) {
            merged = false;
            otherToIntermediary = new GenMap();
            newToOther = new GenMap();

            try (FileInputStream inputStream = new FileInputStream(otherMappings)) {
                //noinspection deprecation
                otherToIntermediary.load(
                    MappingsProvider.readTinyMappings(inputStream),
                    "official",
                    targetNamespace
                );
            }

            try (FileReader fileReader = new FileReader(otherMatches)) {
                try (BufferedReader reader = new BufferedReader(fileReader)) {
                    MatcherUtil.read(reader, !invertOtherMatches, newToOther::addClass, newToOther::addField, newToOther::addMethod);
                }
            }
        }
    }
}
