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
    private GenMap oldToIntermediary = new GenMap(), newToOld = new GenMap();
    private GenMap newToIntermediary;

    public void generate(File file, Classpath storage, Classpath storageOld) throws IOException {
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
                    addClass(writer, storage, storageOld, c, this.defaultPackage);
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

        if (newToOld != null) {
          //noinspection deprecation
            EntryTriple findEntry = newToOld.getField(c.getName(), f.getName(), f.getDescriptor());
            if (findEntry != null) {
                findEntry = oldToIntermediary.getField(findEntry);
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

    private Set<JarMethodEntry> findNames(Classpath storage, Classpath storageOld, JarClassEntry c, JarMethodEntry m, Map<String, Set<String>> names) {
        Set<JarMethodEntry> allEntries = new HashSet<>();
        findNames(storage, storageOld, c, m, names, allEntries);
        return allEntries;
    }

    private void findNames(Classpath storage, Classpath storageOld, JarClassEntry c, JarMethodEntry m, Map<String, Set<String>> names, Set<JarMethodEntry> usedMethods) {
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
                    EntryTriple oldEntry = findEntry;
                    findEntry = oldToIntermediary.getMethod(oldEntry);
                    if (findEntry != null) {
                        names.computeIfAbsent(findEntry.getName(), (s) -> new TreeSet<>()).add(getNamesListEntry(storage, cc) + suffix);
                    } else {
                        // more involved...
                        JarClassEntry oldBase = storageOld.getClass(oldEntry.getOwner());
                        if (oldBase != null) {
                            JarMethodEntry oldM = oldBase.getMethod(oldEntry.getName() + oldEntry.getDesc());
                            List<JarClassEntry> cccList = oldM.getMatchingEntries(storageOld, oldBase);

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

        for (JarClassEntry mc : ccList) {
            for (Pair<JarClassEntry, String> pair : mc.getRelatedMethods(m)) {
                findNames(storage, storageOld, pair.getLeft(), pair.getLeft().getMethod(pair.getRight()), names, usedMethods);
            }
        }
    }

    @Nullable
    private String getMethodName(Classpath storage, Classpath storageOld, JarClassEntry c, JarMethodEntry m) {
        if (!isMappedMethod(storage, c, m)) {
            return null;
        }

        if (methodNames.containsKey(m)) {
            return methodNames.get(m);
        }

        if (newToOld != null || newToIntermediary != null) {
            Map<String, Set<String>> names = new HashMap<>();
            Set<JarMethodEntry> allEntries = findNames(storage, storageOld, c, m, names);
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
        }

        return nextMethodName(storage, c, m);
    }

    private void addClass(BufferedWriter writer, Classpath storage, Classpath storageOld, JarClassEntry c, String translatedPrefix) throws IOException {
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

        if ((c.isInner() || c.isLocal()) ? !isObfuscated(c.getInnerName()) : !isObfuscated(fullName)) {
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

                if (cname == null && newToOld != null) {
                    String findName = newToOld.getClass(fullName);
                    if (findName != null) {
                        // similar to above, the names we generate follow the convention for inner classes
                        findName = oldToIntermediary.getClass(findName);
                        if (findName != null) {
                            String[] nr = fullName.split("\\$");
                            String[] or = findName.split("\\$");
                            if (or.length > 1) {
                                cname = stripLocalClassPrefix(or[or.length - 1]);
                            } else {
                                cname = stripPackageName(findName);
                                if (!cname.startsWith("C_")) {
                                    // not a name we generated, thus an unobfuscated name!
                                    // then we inherit not just the name but the package too
                                    translatedPrefix = findName.substring(0, findName.length() - cname.length());
                                }
                            }
                        }
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
            String mName = getMethodName(storage, storageOld, c, m);
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
            addClass(writer, storage, storageOld, cc, translatedPrefix + cname + "$");
        }
    }

    public void prepareRewrite(File oldMappings) throws IOException {
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
    }

    public void prepareUpdate(File oldMappings, File oldMatches, boolean invertOldMatches) throws IOException {
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
}
