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
    private GenMap oldToCalamus, newToOld;
    private GenMap newToCalamus;
    private String targetNamespace = "net/minecraft/";

    public GenState() {
        this.obfuscatedPatterns.add(Pattern.compile("^[^/]*$")); // Default ofbfuscation. Minecraft classes without a package are obfuscated.
    }

    public static boolean isMappedClass(JarClassEntry c) {
        return !c.isAnonymous();
    }

    public static boolean isMappedField(JarFieldEntry f) {
        return isUnmappedFieldName(f.getName());
    }

    public static boolean isUnmappedFieldName(String name) {
        return name.length() <= 2 || (name.length() == 3 && name.charAt(2) == '_');
    }

    public static boolean isMappedMethod(JarRootEntry storage, JarClassEntry c, JarMethodEntry m) {
        return isUnmappedMethodName(m.getName()) && m.isSource(storage, c);
    }

    public static boolean isUnmappedMethodName(String name) {
        return (name.length() <= 2 || (name.length() == 3 && name.charAt(2) == '_')) && name.charAt(0) != '<';
    }

    public void setWriteAll() {
    }

    public String next(AbstractJarEntry entry, String name) {
        return name + "_" + values.computeIfAbsent(entry, (e) -> {
            BigInteger bigInt = new BigInteger(e.getHash());
            StringBuilder builder = new StringBuilder();

            for (int i = 0; i < 7; i++) {
                int digit = bigInt.mod(BigInteger.valueOf(26)).intValue();
                bigInt = bigInt.divide(BigInteger.valueOf(26));

                builder.insert(0, (char) ('a' + digit));
            }
            for (int i = 0; i < 2; i++) {
                int digit = bigInt.mod(BigInteger.valueOf(10)).intValue();
                bigInt = bigInt.divide(BigInteger.valueOf(10));

                builder.insert(0, (char) ('0' + digit));
            }

            return builder.toString();
        });
    }

    public void setTargetNamespace(final String namespace) {
        if (namespace.lastIndexOf("/") != (namespace.length() - 1))
            this.targetNamespace = namespace + "/";
        else
            this.targetNamespace = namespace;
    }

    public void clearObfuscatedPatterns() {
        this.obfuscatedPatterns.clear();
    }

    public void addObfuscatedPattern(String regex) throws PatternSyntaxException {
        this.obfuscatedPatterns.add(Pattern.compile(regex));
    }

    public void generate(File file, JarRootEntry jarEntry, JarRootEntry jarOld) throws IOException {
        if (file.exists()) {
            System.err.println("Target file exists - loading...");
            newToCalamus = new GenMap();
            try (FileInputStream inputStream = new FileInputStream(file)) {
                //noinspection deprecation
                newToCalamus.load(
                      MappingsProvider.readTinyMappings(inputStream),
                      "official",
                      "calamus"
                );
            }
        }

        try (FileWriter fileWriter = new FileWriter(file)) {
            try (BufferedWriter writer = new BufferedWriter(fileWriter)) {
                writer.write("v1\tofficial\tcalamus\n");

                for (JarClassEntry c : jarEntry.getClasses()) {
                    addClass(writer, c, jarOld, jarEntry, this.targetNamespace);
                }
            }
        }
    }

    @Nullable
    private String getFieldName(JarClassEntry c, JarFieldEntry f) {
        if (!isMappedField(f)) {
            return null;
        }

        if (newToCalamus != null) {
            //noinspection deprecation
            EntryTriple findEntry = newToCalamus.getField(c.getFullyQualifiedName(), f.getName(), f.getDescriptor());
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

        if (newToOld != null) {
            //noinspection deprecation
            EntryTriple findEntry = newToOld.getField(c.getFullyQualifiedName(), f.getName(), f.getDescriptor());
            if (findEntry != null) {
                findEntry = oldToCalamus.getField(findEntry);
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
        }

        return next(f, "f");
    }

    private String getPropagation(JarRootEntry storage, JarClassEntry classEntry) {
        if (classEntry == null) {
            return "";
        }

        StringBuilder builder = new StringBuilder(classEntry.getFullyQualifiedName());
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

    private Set<JarMethodEntry> findNames(JarRootEntry storageOld, JarRootEntry storageNew, JarClassEntry c, JarMethodEntry m, Map<String, Set<String>> names) {
        Set<JarMethodEntry> allEntries = new HashSet<>();
        findNames(storageOld, storageNew, c, m, names, allEntries);
        return allEntries;
    }

    private void findNames(JarRootEntry storageOld, JarRootEntry storageNew, JarClassEntry c, JarMethodEntry m, Map<String, Set<String>> names, Set<JarMethodEntry> usedMethods) {
        if (!usedMethods.add(m)) {
            return;
        }

        String suffix = "." + m.getName() + m.getDescriptor();

        if ((m.getAccess() & Opcodes.ACC_BRIDGE) != 0) {
            suffix += "(bridge)";
        }

        List<JarClassEntry> ccList = m.getMatchingEntries(storageNew, c);

        for (JarClassEntry cc : ccList) {
            //noinspection deprecation
            EntryTriple findEntry = null;
            if (newToCalamus != null) {
                findEntry = newToCalamus.getMethod(cc.getFullyQualifiedName(), m.getName(), m.getDescriptor());
                if (findEntry != null) {
                    names.computeIfAbsent(findEntry.getName(), (s) -> new TreeSet<>()).add(getNamesListEntry(storageNew, cc) + suffix);
                }
            }

            if (findEntry == null && newToOld != null) {
                findEntry = newToOld.getMethod(cc.getFullyQualifiedName(), m.getName(), m.getDescriptor());
                if (findEntry != null) {
                    //noinspection deprecation
                    EntryTriple newToOldEntry = findEntry;
                    findEntry = oldToCalamus.getMethod(newToOldEntry);
                    if (findEntry != null) {
                        names.computeIfAbsent(findEntry.getName(), (s) -> new TreeSet<>()).add(getNamesListEntry(storageNew, cc) + suffix);
                    } else {
                        // more involved...
                        JarClassEntry oldBase = storageOld.getClass(newToOldEntry.getOwner(), null, false);
                        if (oldBase != null) {
                            JarMethodEntry oldM = oldBase.getMethod(newToOldEntry.getName() + newToOldEntry.getDesc());
                            List<JarClassEntry> cccList = oldM.getMatchingEntries(storageOld, oldBase);

                            for (JarClassEntry ccc : cccList) {
                                findEntry = oldToCalamus.getMethod(ccc.getFullyQualifiedName(), oldM.getName(), oldM.getDescriptor());
                                if (findEntry != null) {
                                    names.computeIfAbsent(findEntry.getName(), (s) -> new TreeSet<>()).add(getNamesListEntry(storageOld, ccc) + suffix);
                                }
                            }
                        }
                    }
                }
            }
        }

        for (JarClassEntry mc : ccList) {
            for (Pair<JarClassEntry, String> pair : mc.getRelatedMethods(m)) {
                findNames(storageOld, storageNew, pair.getLeft(), pair.getLeft().getMethod(pair.getRight()), names, usedMethods);
            }
        }
    }

    @Nullable
    private String getMethodName(JarRootEntry storageOld, JarRootEntry storageNew, JarClassEntry c, JarMethodEntry m) {
        if (!isMappedMethod(storageNew, c, m)) {
            return null;
        }

        if (methodNames.containsKey(m)) {
            return methodNames.get(m);
        }

        if (newToOld != null || newToCalamus != null) {
            Map<String, Set<String>> names = new HashMap<>();
            Set<JarMethodEntry> allEntries = findNames(storageOld, storageNew, c, m, names);
            for (JarMethodEntry mm : allEntries) {
                if (methodNames.containsKey(mm)) {
                    return methodNames.get(mm);
                }
            }

            if (names.size() > 1) {
                System.out.println("Conflict detected - matched same target name!");
                List<String> nameList = new ArrayList<>(names.keySet());
                Collections.sort(nameList);

                for (int i = 0; i < nameList.size(); i++) {
                    String s = nameList.get(i);
                    System.out.println((i + 1) + ") " + s + " <- " + StitchUtil.join(", ", names.get(s)));
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
                if (s.contains("m_")) {
                    return s;
                } else {
                    String newName = nextMethodName(storageNew, c, m);
                    System.out.println(s + " is now " + newName);
                    return newName;
                }
            }
        }

        return nextMethodName(storageNew, c, m);
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

    private void addClass(BufferedWriter writer, JarClassEntry c, JarRootEntry storageOld, JarRootEntry storage, String translatedPrefix) throws IOException {
        String className = c.getName();
        String cname = "";
        String localName = stripLocalClassPrefix(className);
        translatedPrefix += className.substring(0, localName.length());
        String prefixSaved = translatedPrefix;

        if (this.obfuscatedPatterns.stream().noneMatch(p -> p.matcher(className).matches())) {
            translatedPrefix = c.getFullyQualifiedName();
        } else {
            if (!isMappedClass(c)) {
                cname = c.getName();
            } else {
                cname = null;

                if (newToCalamus != null) {
                    String findName = newToCalamus.getClass(c.getFullyQualifiedName());
                    if (findName != null) {
                        String[] r = findName.split("\\$");
                        cname = stripLocalClassPrefix(r[r.length - 1]);
                        if (r.length == 1) {
                            translatedPrefix = "";
                        }
                    }
                }

                if (cname == null && newToOld != null) {
                    String fullName = c.getFullyQualifiedName();
                    String findName = newToOld.getClass(fullName);
                    if (findName != null) {
                        findName = oldToCalamus.getClass(findName);
                        if (findName != null) {
                            String[] nr = fullName.split("\\$");
                            String[] or = findName.split("\\$");
                            if (or.length == 1) {
                                if (nr.length > 1) {
                                    // nesting level changed; respect new nesting hierarchy
                                    // old name not nested; remove package name
                                    cname = findName.substring(findName.lastIndexOf('/') + 1);
                                } else {
                                    cname = findName;
                                    translatedPrefix = "";
                                }
                            } else {
                                cname = stripLocalClassPrefix(or[or.length - 1]);
                            }
                        }
                    }
                }

                if (cname != null && !cname.contains("C_")) {
                    String newName = next(c, "C");
                    System.out.println(cname + " is now " + newName);
                    cname = newName;
                    translatedPrefix = prefixSaved;
                }

                if (cname == null) {
                    cname = next(c, "C");
                }
            }
        }

        writer.write("CLASS\t" + c.getFullyQualifiedName() + "\t" + translatedPrefix + cname + "\n");

        for (JarFieldEntry f : c.getFields()) {
            String fName = getFieldName(c, f);
            if (fName == null) {
                fName = f.getName();
            }

            if (fName != null) {
                writer.write("FIELD\t" + c.getFullyQualifiedName()
                      + "\t" + f.getDescriptor()
                      + "\t" + f.getName()
                      + "\t" + fName + "\n");
            }
        }

        for (JarMethodEntry m : c.getMethods()) {
            String mName = getMethodName(storageOld, storage, c, m);
            if (mName == null) {
                if (!m.getName().startsWith("<") && m.isSource(storage, c)) {
                    mName = m.getName();
                }
            }

            if (mName != null) {
                writer.write("METHOD\t" + c.getFullyQualifiedName()
                      + "\t" + m.getDescriptor()
                      + "\t" + m.getName()
                      + "\t" + mName + "\n");
            }
        }

        for (JarClassEntry cc : c.getInnerClasses()) {
            addClass(writer, cc, storageOld, storage, translatedPrefix + cname + "$");
        }
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

    public void prepareRewrite(File oldMappings) throws IOException {
        oldToCalamus = new GenMap();
        newToOld = new GenMap.Dummy();

        try (FileInputStream inputStream = new FileInputStream(oldMappings)) {
            //noinspection deprecation
            oldToCalamus.load(
                  MappingsProvider.readTinyMappings(inputStream),
                  "official",
                  "calamus"
            );
        }
    }

    public void prepareUpdate(File oldMappings, File matches) throws IOException {
        oldToCalamus = new GenMap();
        newToOld = new GenMap();

        try (FileInputStream inputStream = new FileInputStream(oldMappings)) {
            //noinspection deprecation
            oldToCalamus.load(
                  MappingsProvider.readTinyMappings(inputStream),
                  "official",
                  "calamus"
            );
        }

        try (FileReader fileReader = new FileReader(matches)) {
            try (BufferedReader reader = new BufferedReader(fileReader)) {
                MatcherUtil.read(reader, true, newToOld::addClass, newToOld::addField, newToOld::addMethod);
            }
        }
    }
}
