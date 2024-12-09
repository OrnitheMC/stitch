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

import net.fabricmc.stitch.representation.*;
import net.fabricmc.stitch.util.StitchUtil;

import java.math.BigInteger;
import java.util.*;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

public class GenState
{
    final Map<AbstractJarEntry, String> values = new IdentityHashMap<>();
    final Scanner scanner = new Scanner(System.in);
    final List<Pattern> obfuscatedPatterns = new ArrayList<>();
    String defaultPackage = "net/minecraft/";
    String targetNamespace = "intermediary";
    int nameLength = 6;

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

    String next(AbstractJarEntry entry, String prefix) {
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

    String nextMethodName(Classpath storage, JarClassEntry c, JarMethodEntry m) {
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

    void findEquivalentMethods(Classpath storage, JarClassEntry c, String key, Collection<JarMethodEntry> methods) {
        findSourceMethod(storage, c, key, methods);

        for (JarClassEntry cs : c.getSubclasses(storage)) {
            findEquivalentMethods(storage, cs, key, methods);
        }
        for (JarClassEntry ci : c.getImplementers(storage)) {
            findEquivalentMethods(storage, ci, key, methods);
        }
    }

    boolean findSourceMethod(Classpath storage, JarClassEntry c, String key, Collection<JarMethodEntry> methods) {
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
