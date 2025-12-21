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
import net.fabricmc.stitch.representation.*;
import net.fabricmc.stitch.util.StitchUtil;

import java.math.BigInteger;
import java.util.*;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

public class GenState
{
    final Scanner scanner = new Scanner(System.in);
    final List<Pattern> obfuscatedPatterns = new ArrayList<>();
    String defaultPackage = "net/minecraft/";
    String targetNamespace = "intermediary";
    int nameLength = 6;
    boolean propagateNames = false;

    public GenState() {
        this.obfuscatedPatterns.add(Pattern.compile("^[^A-Z]*$")); // Default ofbfuscation. Obfuscated names are all lowercase
    }

    public static boolean isMinecraftClass(JarClassEntry c) {
        return isMinecraftClassName(c.getName());
    }

    public static boolean isMinecraftClassName(String name) {
        return !name.matches(".*(argo|paulscode|fasterxml|jcraft|javax).*"); // match against libraries that are shaded into the jar
    }

    public static boolean isMappedClass(JarClassEntry c) {
        return true;
    }

    public boolean isMappedField(Classpath storage, JarClassEntry c, JarFieldEntry f) {
        return isMappedFieldName(f.getName()) && !isSerializable(storage, f);
    }

    public static boolean isMappedFieldName(String name) {
        return true; // make sure even unobfuscated fields are given names
    }

    public boolean isMappedMethod(Classpath storage, JarClassEntry c, JarMethodEntry m) {
        return isMappedMethodName(m.getName()) && m.isSource(storage, c) && !isEnumMethod(storage, c, m) && !isSerializable(storage, m);
    }

    public static boolean isMappedMethodName(String name) {
        return name.charAt(0) != '<' && !"main".equals(name) && !"getClientModName".equals(name) && !"getServerModName".equals(name); // make sure only constructors and main methods are not remapped
    }

    public static boolean isEnumMethod(Classpath storage, JarClassEntry c, JarMethodEntry m) {
        return Access.isEnum(c.getAccess()) &&
                ("values".equals(m.getName()) && ("()[L" + c.getName() + ";").equals(m.getDescriptor()) ||
                "valueOf".equals(m.getName()) && ("(Ljava/lang/String;)L" + c.getName() + ";").equals(m.getDescriptor()));
    }

    public boolean isObfuscated(JarClassEntry c) {
        return isObfuscated(c.getName());
    }

    public boolean isObfuscated(String name) {
        return this.obfuscatedPatterns.stream().anyMatch(p -> p.matcher(name).matches());
    }

    public boolean isSerializable(Classpath storage, AbstractJarEntry entry) {
        return storage.isSerializable() && entry.isSerializable(storage);
    }

    public void setWriteAll() {
    }

    String genName(AbstractJarEntry entry, AbstractJarEntry... entries) {
        StringBuilder builder = new StringBuilder();

        builder.append(entry.getPrefix());
        builder.append('_');

        BigInteger bigInt = new BigInteger(entry.getHash());
        for (AbstractJarEntry e : entries) {
            bigInt = bigInt.multiply(new BigInteger(e.getHash()));
        }

        for (int i = 0; i < nameLength; i++) {
            int digit = bigInt.mod(BigInteger.valueOf(10)).intValue();
            bigInt = bigInt.divide(BigInteger.valueOf(10));

            builder.insert(2, (char) ('0' + digit));
        }

        return builder.toString();
    }

    String nextName(Map<AbstractJarEntry, String> values, AbstractJarEntry entry) {
        return values.computeIfAbsent(entry, this::genName);
    }

    String nextMethodName(Map<AbstractJarEntry, String> values, Classpath storage, JarClassEntry c, JarMethodEntry m) {
        String key = m.getName() + m.getDescriptor();
        Set<JarMethodEntry> ms = new TreeSet<>((m1, m2) -> compareSourceMethods(storage, m1, m2));
        Set<JarMethodEntry> ns = propagateNames ? null : new TreeSet<>((m1, m2) -> compareSourceMethods(storage, m1, m2));

        findSourceMethod(storage, c, key, ms, ns);

        if (ms.isEmpty()) {
            // method is most likely private or static
            return nextName(values, m);
        }

        Iterator<JarMethodEntry> it = ms.iterator();
        JarMethodEntry pm = it.next();

        String name;

        if (pm.isMainJar(storage)) {
            // for methods from the main jar, do not propagate
            // names through bridge/specialized methods
            if (!propagateNames) {
                it = ns.iterator();
                pm = it.next();
            }

            name = nextName(values, pm);
        } else {
            // for methods inherited from libraries or the JDK
            // propagate names through bridge/specialized methods
            name = pm.getName();
        }

        while (it.hasNext()) {
            values.put(it.next(), name);
        }

        return name;
    }

    int compareSourceMethods(Classpath storage, JarMethodEntry m1, JarMethodEntry m2) {
        boolean main1 = m1.isMainJar(storage);
        boolean main2 = m2.isMainJar(storage);
        if (main1 == main2) {
            boolean bridge1 = (m1.getBridgeMethod() != null);
            boolean bridge2 = (m2.getBridgeMethod() != null);
            if (bridge1 == bridge2) {
                int c0 = m1.getName().compareTo(m2.getName());
                if (c0 == 0) {
                    c0 = m1.getDescriptor().compareTo(m2.getDescriptor());
                }
                if (c0 == 0) {
                    c0 = m1.getParentName().compareTo(m2.getParentName());
                }
                return c0;
            } else {
                return bridge1 ? 1 : -1;
            }
        } else {
            return main1 ? 1 : -1;
        }
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

    public void setPropagateMethodNames(boolean propagateNames) {
        this.propagateNames = propagateNames;
    }

    Set<JarMethodEntry> findNames(Classpath storage, Classpath storageOld, JarClassEntry c, JarMethodEntry m, GenMap newToOld, GenMap oldToIntermediary, Map<String, Set<String>> names) {
        Set<JarMethodEntry> allEntries = new HashSet<>();
        if (newToOld != null) {
            findNames(storage, storageOld, c, m, newToOld, oldToIntermediary, names, allEntries);
        }
        return allEntries;
    }

    private void findNames(Classpath storage, Classpath storageOld, JarClassEntry c, JarMethodEntry m, GenMap newToOld, GenMap oldToIntermediary, Map<String, Set<String>> names, Set<JarMethodEntry> usedMethods) {
        if (m == null || !usedMethods.add(m)) {
            return;
        }

        String suffix = "." + m.getName() + m.getDescriptor();

        if (m.getSpecializedMethod() != null) {
            suffix += "(bridge)";
        }

        List<JarClassEntry> matchingClasses = m.getMatchingEntries(storage, c);

        for (JarClassEntry matchingClass : matchingClasses) {
            JarMethodEntry matchingMethod = matchingClass.getMethod(m.getName() + m.getDescriptor());
            if (matchingMethod != null) {
                JarMethodEntry bridgeMethod = matchingMethod.getBridgeMethod();
                JarMethodEntry specializedMethod = matchingMethod.getSpecializedMethod();
                findNames(storage, storageOld, matchingClass, matchingMethod, newToOld, oldToIntermediary, names, usedMethods, suffix);
                if (bridgeMethod != null) {
                    findNames(storage, storageOld, matchingClass, bridgeMethod, newToOld, oldToIntermediary, names, usedMethods);
                }
                if (specializedMethod != null) {
                    findNames(storage, storageOld, matchingClass, specializedMethod, newToOld, oldToIntermediary, names, usedMethods);
                }
            }
        }
    }

    private void findNames(Classpath storage, Classpath storageOld, JarClassEntry c, JarMethodEntry m, GenMap newToOld, GenMap oldToIntermediary, Map<String, Set<String>> names, Set<JarMethodEntry> usedMethods, String suffix) {
        EntryTriple oldEntry = newToOld.getMethod(c.getName(), m.getName(), m.getDescriptor());
        if (oldEntry != null) {
            JarClassEntry oldClass = storageOld.getClass(oldEntry.getOwner());
            JarMethodEntry oldMethod = (oldClass == null) ? null : oldClass.getMethod(oldEntry.getName() + oldEntry.getDesc());
            if (oldMethod != null && !isSerializable(storageOld, oldMethod)) {
                EntryTriple intermediaryEntry = oldToIntermediary.getMethod(oldEntry);
                if (intermediaryEntry != null) {
                    names.computeIfAbsent(intermediaryEntry.getName(), (s) -> new TreeSet<>()).add(getNamesListEntry(storage, c) + suffix);
                } else {
                    if (!isMappedMethodName(oldEntry.getName())) {
                        names.computeIfAbsent(oldEntry.getName(), (s) -> new TreeSet<>()).add(getNamesListEntry(storage, c) + suffix);
                    } else {
                        // more involved...
                        JarMethodEntry bridgeMethod = oldMethod.getBridgeMethod();
                        JarMethodEntry specializedMethod = oldMethod.getSpecializedMethod();
                        findNames(storageOld, oldClass, oldMethod, oldToIntermediary, names, suffix);
                        if (bridgeMethod != null) {
                            findNames(storageOld, oldClass, bridgeMethod, oldToIntermediary, names, suffix);
                        }
                        if (specializedMethod != null) {
                            findNames(storageOld, oldClass, specializedMethod, oldToIntermediary, names, suffix);
                        }
                    }
                }
            }
        }
    }

    private void findNames(Classpath storage, JarClassEntry c, JarMethodEntry m, GenMap oldToIntermediary, Map<String, Set<String>> names, String suffix) {
        List<JarClassEntry> matchingClasses = m.getMatchingEntries(storage, c);
        for (JarClassEntry matchingClass : matchingClasses) {
            EntryTriple intermediaryEntry = oldToIntermediary.getMethod(matchingClass.getName(), m.getName(), m.getDescriptor());
            if (intermediaryEntry != null) {
                names.computeIfAbsent(intermediaryEntry.getName(), (s) -> new TreeSet<>()).add(getNamesListEntry(storage, matchingClass) + suffix);
            }
        }
    }

    String getPropagation(Classpath storage, JarClassEntry classEntry) {
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

    String getNamesListEntry(Classpath storage, JarClassEntry classEntry) {
        StringBuilder builder = new StringBuilder(getPropagation(storage, classEntry));
        if (classEntry.isInterface()) {
            builder.append("(itf)");
        }

        return builder.toString();
    }

    void findEquivalentMethods(Classpath storage, JarClassEntry c, String key, Collection<JarMethodEntry> methods, Collection<JarMethodEntry> neighbors) {
        findSourceMethod(storage, c, key, methods, neighbors);

        for (JarClassEntry cs : c.getSubclasses(storage)) {
            findEquivalentMethods(storage, cs, key, methods, neighbors);
        }
        for (JarClassEntry ci : c.getImplementers(storage)) {
            findEquivalentMethods(storage, ci, key, methods, neighbors);
        }

        JarMethodEntry m = c.getMethod(key);

        if (m != null) {
            JarMethodEntry bm = m.getBridgeMethod();
            JarMethodEntry sm = m.getSpecializedMethod();

            if (bm != null) {
                findSourceMethod(storage, c, bm.getName() + bm.getDescriptor(), methods, null);
            }
            if (sm != null) {
                findSourceMethod(storage, c, sm.getName() + sm.getDescriptor(), methods, null);
            }
        }
    }

    boolean findSourceMethod(Classpath storage, JarClassEntry c, String key, Collection<JarMethodEntry> methods, Collection<JarMethodEntry> neighbors) {
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
            parentsAdded = findSourceMethod(storage, sc, key, methods, neighbors) | parentsAdded;
        }

        for (JarClassEntry ic : c.getInterfaces(storage)) {
            parentsAdded = findSourceMethod(storage, ic, key, methods, neighbors) | parentsAdded;
        }

        if (!parentsAdded && hasMethod) {
            if (methods.add(m)) {
                if (neighbors != null) {
                    neighbors.add(m);
                }

                findEquivalentMethods(storage, c, key, methods, neighbors);
            }
        }

        return parentsAdded || hasMethod;
    }

    String stripLocalClassPrefix(String innerName) {
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

    String stripPackageName(String className) {
        return className.substring(className.lastIndexOf('/') + 1);
    }
}
