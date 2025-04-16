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
import java.util.*;

public class GenStateMerged extends GenState
{
    private final Map<JarMethodEntry, String> methodNames = new IdentityHashMap<>();
    private List<GenMap> oldToIntermediary = new ArrayList<>(), newToOld = new ArrayList<>();
    private GenMap newToIntermediary;

    public void generate(File file, Classpath storage, List<Classpath> storagesOld) throws IOException {
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

                for (JarClassEntry c : storage.getJar().getClasses()) {
                    addClass(writer, storage, storagesOld, c, this.defaultPackage);
                }
            }
        }
    }

    private String inheritFieldName(Classpath storage, Classpath storageOld, JarClassEntry c, JarFieldEntry f, GenMap newToOld, GenMap oldToIntermediary) {
        EntryTriple findEntry = newToOld.getField(c.getName(), f.getName(), f.getDescriptor());
        if (findEntry != null) {
            JarClassEntry oldClass = storageOld.getClass(findEntry.getOwner());
            JarFieldEntry oldField = (oldClass == null) ? null : oldClass.getField(findEntry.getName() + findEntry.getDesc());
            if (oldField != null && !isSerializable(storageOld, oldField)) {
                EntryTriple findIntermediaryEntry = oldToIntermediary.getField(findEntry);
                if (findIntermediaryEntry != null) {
                    return findIntermediaryEntry.getName();
                } else if (!isMappedFieldName(findEntry.getName())) {
                    return findEntry.getName();
                }
            }
        }
        return null;
    }

    @Nullable
    private String getFieldName(Classpath storage, List<Classpath> storagesOld, JarClassEntry c, JarFieldEntry f) {
        if (!isMappedField(storage, c, f)) {
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

        if (!newToOld.isEmpty()) {
            String findName = null;

            for (int i = 0; i < newToOld.size(); i++) {
                String inheritedName = inheritFieldName(storage, storagesOld.get(i), c, f, newToOld.get(i), oldToIntermediary.get(i));

                if (findName != null && inheritedName != null && !findName.equals(inheritedName)) {
                    throw new IllegalStateException("illegal field name inheritance: " + c.getName() + "." + f.getName() + " -> [" + findName + ", " + i + ": " + inheritedName + "]");
                }

                findName = inheritedName;
            }

            if (findName != null) {
                return findName;
            }
        }

        return next(f, "f");
    }

    private Set<JarMethodEntry> findNames(Classpath storage, Classpath storageOld, JarClassEntry c, JarMethodEntry m, GenMap newToOld, GenMap oldToIntermediary, Map<String, Set<String>> names) {
        Set<JarMethodEntry> allEntries = new HashSet<>();
        findNames(storage, storageOld, c, m, newToOld, oldToIntermediary, names, allEntries);
        return allEntries;
    }

    private void findNames(Classpath storage, Classpath storageOld, JarClassEntry c, JarMethodEntry m, GenMap newToOld, GenMap oldToIntermediary, Map<String, Set<String>> names, Set<JarMethodEntry> usedMethods) {
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

            if (findEntry == null && newToOld != null) {
                findEntry = newToOld.getMethod(cc.getName(), m.getName(), m.getDescriptor());
                if (findEntry != null) {
                    JarClassEntry oldClass = storageOld.getClass(findEntry.getOwner());
                    JarMethodEntry oldMethod = (oldClass == null) ? null : oldClass.getMethod(findEntry.getName() + findEntry.getDesc());
                    if (oldMethod != null && !isSerializable(storageOld, oldMethod)) {
                        EntryTriple oldEntry = findEntry;
                        findEntry = oldToIntermediary.getMethod(oldEntry);
                        if (findEntry != null) {
                            names.computeIfAbsent(findEntry.getName(), (s) -> new TreeSet<>()).add(getNamesListEntry(storage, cc) + suffix);
                        } else {
                            if (!isMappedMethodName(oldEntry.getName())) {
                                names.computeIfAbsent(oldEntry.getName(), (s) -> new TreeSet<>()).add(getNamesListEntry(storage, cc) + suffix);
                            } else {
                                // more involved...
                                JarMethodEntry oldM = oldClass.getMethod(oldEntry.getName() + oldEntry.getDesc());
                                List<JarClassEntry> cccList = oldM.getMatchingEntries(storageOld, oldClass);

                                for (JarClassEntry ccc : cccList) {
                                    findEntry = oldToIntermediary.getMethod(ccc.getName(), oldM.getName(), oldM.getDescriptor());
                                    if (findEntry != null) {
                                        names.computeIfAbsent(findEntry.getName(), (s) -> new TreeSet<>()).add(getNamesListEntry(storageOld, ccc) + suffix);
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private String inheritMethodName(Classpath storage, Classpath storagesOld, JarClassEntry c, JarMethodEntry m, GenMap newToOld, GenMap oldToIntermediary) {
        Map<String, Set<String>> names = new HashMap<>();
        Set<JarMethodEntry> allEntries = findNames(storage, storagesOld, c, m, newToOld, oldToIntermediary, names);
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
            if (s.contains("m_")) {
                return s;
            } else {
                String newName = nextMethodName(storage, c, m);
                System.out.println(s + " is now " + newName);
                return newName;
            }
        }
        return null;
    }

    @Nullable
    private String getMethodName(Classpath storage, List<Classpath> storagesOld, JarClassEntry c, JarMethodEntry m) {
        if (!isMappedMethod(storage, c, m)) {
            return null;
        }

        if (methodNames.containsKey(m)) {
            return methodNames.get(m);
        }

        if (!newToOld.isEmpty() || newToIntermediary != null) {
            String findName = null;

            for (int i = 0; i < newToOld.size(); i++) {
                String inheritedName = inheritMethodName(storage, storagesOld.get(i), c, m, newToOld.get(i), oldToIntermediary.get(i));

                if (findName != null && inheritedName != null && !findName.equals(inheritedName)) {
                    throw new IllegalStateException("illegal method name inheritance: " + c.getName() + "." + m.getName() + m.getDescriptor() + " -> [" + findName + ", " + i + ": " + inheritedName + "]");
                }

                findName = inheritedName;
            }

            if (findName != null) {
                return findName;
            }
        }

        return nextMethodName(storage, c, m);
    }

    private void addClass(BufferedWriter writer, Classpath storage, List<Classpath> storagesOld, JarClassEntry c, String translatedPrefix) throws IOException {
        if (!isMinecraftClass(c)) {
            return;
        }
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

        if (isSerializable(storage, c) || ((c.isInner() || c.isLocal()) ? !isObfuscated(c.getInnerName()) : (!c.isAnonymous() && fullName.indexOf('$') < 0 && !isObfuscated(fullName)))) {
            translatedPrefix = fullName;
        } else {
            if (!isMappedClass(c)) {
                // throw exception in case the impl of isMappedClass
                // changes but we forget to deal with it here
                throw new IllegalStateException("don't know what to do with class " + fullName);
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

                if (cname == null && !newToOld.isEmpty()) {
                    Pair<String, String> findName = null;

                    for (int i = 0; i < newToOld.size(); i++) {
                        Pair<String, String> inheritedName = inheritClassName(fullName, storage, storagesOld.get(i), c, newToOld.get(i), oldToIntermediary.get(i));

                        if (findName != null && inheritedName != null && !findName.equals(inheritedName)) {
                            throw new IllegalStateException("illegal class name inheritance: " + fullName + " -> [" + (findName.getLeft() == null ? "" : findName.getLeft()) + findName.getRight() + ", " + i + ": " + (inheritedName.getLeft() == null ? "" : inheritedName.getLeft()) + inheritedName.getRight() + "]");
                        }

                        findName = inheritedName;
                    }

                    if (findName != null) {
                        cname = findName.getRight();
                        if (findName.getLeft() != null) {
                            translatedPrefix = findName.getLeft();
                        }
                    }
                }

                if (cname == null) {
                    cname = next(c, "C");
                }
                // generating anonymous class names like this keeps them
                // more consistent across versions while keeping the
                // convention of making them a number, which makes some
                // decompilers less confused
                if (c.isAnonymous() && cname.startsWith("C_")) {
                    cname = cname.substring(2);
                }
            }
        }

        writer.write("CLASS\t" + c.getName() + "\t" + translatedPrefix + cname + "\n");

        for (JarFieldEntry f : c.getFields()) {
            String fName = getFieldName(storage, storagesOld, c, f);
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
            String mName = getMethodName(storage, storagesOld, c, m);
            if (mName == null) {
                if (m.getName().charAt(0) != '<' && m.isSource(storage, c) && !isEnumMethod(storage, c, m)) {
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
            addClass(writer, storage, storagesOld, cc, translatedPrefix + cname + "$");
        }
    }

    private Pair<String, String> inheritClassName(String fullName, Classpath stroage, Classpath storageOld, JarClassEntry c, GenMap newToOld, GenMap oldToIntermediary) {
        String packageName = null;
        String cname = null;

        String findName = newToOld.getClass(fullName);
        if (findName != null) {
            String oldName = findName;
            findName = oldToIntermediary.getClass(findName);
            if (findName != null) {
                // similar to above, the names we generate follow the convention for inner classes
                String[] nr = fullName.split("\\$");
                String[] or = findName.split("\\$");
                if (or.length > 1) {
                    cname = stripLocalClassPrefix(or[or.length - 1]);
                } else {
                    cname = stripPackageName(findName);
                    if (nr.length == 1 && !cname.startsWith("C_")) {
                        // not a name we generated, thus an unobfuscated name!
                        // then we inherit not just the name but the package too
                        packageName = findName.substring(0, findName.length() - cname.length());
                    }
                }
                JarClassEntry oldEntry = storageOld.getClass(oldName);
                if (oldEntry != null) {
                    if (oldEntry.isAnonymous() && !c.isAnonymous()) {
                        cname = "C_" + cname;
                    }
                    if (!oldEntry.isAnonymous() && c.isAnonymous()) {
                        cname = cname.substring(cname.indexOf("C_") + 2);
                    }
                    if (isSerializable(storageOld, oldEntry)) {
                        cname = null;
                    }
                }
            }
        }

        return cname == null ? null : Pair.of(packageName, cname);
    }

    public void prepareRewrite(File oldMappings) throws IOException {
        oldToIntermediary.clear();
        newToOld.clear();

        oldToIntermediary.add(new GenMap());
        newToOld.add(new GenMap());

        try (FileInputStream inputStream = new FileInputStream(oldMappings)) {
            //noinspection deprecation
            oldToIntermediary.get(0).load(
                  MappingsProvider.readTinyMappings(inputStream),
                  "official",
                  targetNamespace
            );
        }
    }

    public void prepareUpdate(List<File> oldMappings, List<File> oldMatches, boolean[] invertOldMatches) throws IOException {
        oldToIntermediary.clear();
        newToOld.clear();

        for (int i = 0; i < oldMappings.size(); i++) {
            oldToIntermediary.add(new GenMap());
            newToOld.add(new GenMap());

            try (FileInputStream inputStream = new FileInputStream(oldMappings.get(i))) {
                //noinspection deprecation
                oldToIntermediary.get(i).load(
                    MappingsProvider.readTinyMappings(inputStream),
                    "official",
                    targetNamespace
                );
            }

            try (FileReader fileReader = new FileReader(oldMatches.get(i))) {
                try (BufferedReader reader = new BufferedReader(fileReader)) {
                    MatcherUtil.read(reader, !invertOldMatches[i], newToOld.get(i)::addClass, newToOld.get(i)::addField, newToOld.get(i)::addMethod);
                }
            }
        }
    }
}
