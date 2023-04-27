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
    private final List<GenMap> oldToCalamus = new ArrayList<>(), newToOld = new ArrayList<>();
    private GenMap newToCalamus;
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

    public void generate(File file, JarRootEntry jarEntry, List<JarRootEntry> jarsOld) throws IOException {
        if (file.exists()) {
            System.err.println("Target file exists - loading...");
            newToCalamus = new GenMap();
            try (FileInputStream inputStream = new FileInputStream(file)) {
                //noinspection deprecation
                newToCalamus.load(
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
                    addClass(writer, c, jarsOld, jarEntry, getTargetPackage(c));
                }
            }
        }
    }

    private String getTargetPackage(JarClassEntry c) {
        String name = c.getName();
        int idx = name.lastIndexOf('/');

        if (idx > 0) {
            // class is not in default package (i.e. no package)
            // so to avoid illegal access errors keep it there
            return name.substring(0, idx + 1);
        }

        return this.defaultPackage;
    }

    @Nullable
    private String getFieldName(JarClassEntry c, JarFieldEntry f) {
        if (!isMappedField(f)) {
            return null;
        }

        if (newToCalamus != null) {
            //noinspection deprecation
            EntryTriple findEntry = newToCalamus.getField(c.getName(), f.getName(), f.getDescriptor());
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

        if (!newToOld.isEmpty()) {
            for (int i = 0; i < newToOld.size(); i++) {
                //noinspection deprecation
                EntryTriple findEntry = newToOld.get(i).getField(c.getName(), f.getName(), f.getDescriptor());
                if (findEntry != null) {
                    findEntry = oldToCalamus.get(i).getField(findEntry);
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
        }

        return next(f, "f");
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

    private Set<JarMethodEntry> findNames(List<JarRootEntry> storagesOld, JarRootEntry storageNew, JarClassEntry c, JarMethodEntry m, Map<String, Set<String>> names) {
        Set<JarMethodEntry> allEntries = new HashSet<>();
        findNames(storagesOld, storageNew, c, m, names, allEntries);
        return allEntries;
    }

    private void findNames(List<JarRootEntry> storagesOld, JarRootEntry storageNew, JarClassEntry c, JarMethodEntry m, Map<String, Set<String>> names, Set<JarMethodEntry> usedMethods) {
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
                findEntry = newToCalamus.getMethod(cc.getName(), m.getName(), m.getDescriptor());
                if (findEntry != null) {
                    names.computeIfAbsent(findEntry.getName(), (s) -> new TreeSet<>()).add(getNamesListEntry(storageNew, cc) + suffix);
                }
            }

            if (findEntry == null && !newToOld.isEmpty()) {
                for (int i = 0; i < newToOld.size(); i++) {
                    findEntry = newToOld.get(i).getMethod(cc.getName(), m.getName(), m.getDescriptor());
                    if (findEntry != null) {
                        //noinspection deprecation
                        EntryTriple newToOldEntry = findEntry;
                        findEntry = oldToCalamus.get(i).getMethod(newToOldEntry);
                        if (findEntry != null) {
                            names.computeIfAbsent(findEntry.getName(), (s) -> new TreeSet<>()).add(getNamesListEntry(storageNew, cc) + suffix);
                        } else {
                            // more involved...
                            JarClassEntry oldBase = storagesOld.get(i).getClass(newToOldEntry.getOwner(), null, false);
                            if (oldBase != null) {
                                JarMethodEntry oldM = oldBase.getMethod(newToOldEntry.getName() + newToOldEntry.getDesc());
                                List<JarClassEntry> cccList = oldM.getMatchingEntries(storagesOld.get(i), oldBase);
                                
                                for (JarClassEntry ccc : cccList) {
                                    findEntry = oldToCalamus.get(i).getMethod(ccc.getName(), oldM.getName(), oldM.getDescriptor());
                                    if (findEntry != null) {
                                        names.computeIfAbsent(findEntry.getName(), (s) -> new TreeSet<>()).add(getNamesListEntry(storagesOld.get(i), ccc) + suffix);
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        for (JarClassEntry mc : ccList) {
            for (Pair<JarClassEntry, String> pair : mc.getRelatedMethods(m)) {
                findNames(storagesOld, storageNew, pair.getLeft(), pair.getLeft().getMethod(pair.getRight()), names, usedMethods);
            }
        }
    }

    @Nullable
    private String getMethodName(List<JarRootEntry> storagesOld, JarRootEntry storageNew, JarClassEntry c, JarMethodEntry m) {
        if (!isMappedMethod(storageNew, c, m)) {
            return null;
        }

        if (methodNames.containsKey(m)) {
            return methodNames.get(m);
        }

        if (!newToOld.isEmpty() || newToCalamus != null) {
            Map<String, Set<String>> names = new HashMap<>();
            Set<JarMethodEntry> allEntries = findNames(storagesOld, storageNew, c, m, names);
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

    private void addClass(BufferedWriter writer, JarClassEntry c, List<JarRootEntry> storagesOld, JarRootEntry storage, String translatedPrefix) throws IOException {
        String fullName = c.getName();
        String cname = "";
        String enclName = c.getEnclosingClassName();
        String innerName = c.getInnerName();
        if (c.hasEnclosingClass()) {
            // Typically inner class names are of the form com/example/Example$InnerName
            // but this is not required by the JVM spec! However, for the names we generate
            // we do follow this convention.
            // check if the obfuscated name follows the convention
            if (fullName.startsWith(enclName + "$")) {
                if (innerName == null) {
                     // class is anonymous
                } else {
                     // class is inner or local
                    if (fullName.endsWith(innerName)) {
                        // local classes typically have a number prefix before the inner name
                        translatedPrefix += fullName.substring(enclName.length() + 1, fullName.length() - innerName.length());
                    }
                }
            }
        }
        String prefixSaved = translatedPrefix;

        if (this.obfuscatedPatterns.stream().noneMatch(p -> p.matcher(fullName).matches())) {
            translatedPrefix = fullName;
        } else {
            if (!isMappedClass(c)) {
                cname = fullName.substring(enclName.length() + 1);
            } else {
                cname = null;

                if (newToCalamus != null) {
                    String findName = newToCalamus.getClass(c.getName());
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

                if (cname == null && !newToOld.isEmpty()) {
                    for (int i = 0; i < newToOld.size(); i++) {
                        String findName = newToOld.get(i).getClass(fullName);
                        if (findName != null) {
                            // similar to above, the names we generate follow the convention for inner classes
                            findName = oldToCalamus.get(i).getClass(findName);
                            if (findName != null) {
                                String[] nr = fullName.split("\\$");
                                String[] or = findName.split("\\$");
                                if (or.length == nr.length) {
                                    if (nr.length > 1) {
                                        cname = stripLocalClassPrefix(or[or.length - 1]);
                                    } else {
                                        cname = stripPackageName(findName);
                                    }
                                    break;
                                } else {
                                    // nesting level changed; matching name is not necessary
                                }
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

        writer.write("CLASS\t" + c.getName() + "\t" + translatedPrefix + cname + "\n");

        for (JarFieldEntry f : c.getFields()) {
            String fName = getFieldName(c, f);
            if (fName == null) {
                fName = f.getName();
            }

            if (fName != null) {
                writer.write("FIELD\t" + c.getName()
                      + "\t" + f.getDescriptor()
                      + "\t" + f.getName()
                      + "\t" + fName + "\n");
            }
        }

        for (JarMethodEntry m : c.getMethods()) {
            String mName = getMethodName(storagesOld, storage, c, m);
            if (mName == null) {
                if (!m.getName().startsWith("<") && m.isSource(storage, c)) {
                    mName = m.getName();
                }
            }

            if (mName != null) {
                writer.write("METHOD\t" + c.getName()
                      + "\t" + m.getDescriptor()
                      + "\t" + m.getName()
                      + "\t" + mName + "\n");
            }
        }

        for (JarClassEntry cc : c.getInnerClasses()) {
            addClass(writer, cc, storagesOld, storage, translatedPrefix + cname + "$");
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

    private String stripPackageName(String className) {
        return className.substring(className.lastIndexOf('/') + 1);
    }

    public void prepareRewrite(File oldMappings) throws IOException {
        GenMap oldToCalamus = new GenMap();
        GenMap newToOld = new GenMap.Dummy();

        try (FileInputStream inputStream = new FileInputStream(oldMappings)) {
            //noinspection deprecation
            oldToCalamus.load(
                  MappingsProvider.readTinyMappings(inputStream),
                  "official",
                  targetNamespace
            );
        }

        this.oldToCalamus.add(oldToCalamus);
        this.newToOld.add(newToOld);
    }

    public void prepareUpdate(List<File> oldMappings, List<File> matches) throws IOException {
        this.oldToCalamus.clear();
        this.newToOld.clear();

        for (int i = 0; i < oldMappings.size(); i++) {
            GenMap oldToCalamus = new GenMap();
            GenMap newToOld = new GenMap();

            try (FileInputStream inputStream = new FileInputStream(oldMappings.get(i))) {
                //noinspection deprecation
                oldToCalamus.load(
                    MappingsProvider.readTinyMappings(inputStream),
                    "official",
                    targetNamespace
                );
            }

            try (FileReader fileReader = new FileReader(matches.get(i))) {
                try (BufferedReader reader = new BufferedReader(fileReader)) {
                    MatcherUtil.read(reader, true, newToOld::addClass, newToOld::addField, newToOld::addMethod);
                }
            }

            this.oldToCalamus.add(oldToCalamus);
            this.newToOld.add(newToOld);
        }
    }
}
