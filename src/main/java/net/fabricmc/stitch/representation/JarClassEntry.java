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

package net.fabricmc.stitch.representation;

import net.fabricmc.stitch.Main;
import net.fabricmc.stitch.util.Pair;

import java.util.*;
import java.util.stream.Collectors;

public class JarClassEntry extends AbstractJarEntry
{
    final Map<String, JarClassEntry> innerClasses;
    final Map<String, JarFieldEntry> fields;
    final Map<String, JarMethodEntry> methods;
    final Map<String, Set<Pair<JarClassEntry, String>>> relatedMethods;
    final String fullyQualifiedName;
    final String signature;
    final byte[] saltedClassHash;
    String superclass;
    List<String> interfaces;
    List<String> subclasses;
    List<String> implementers;

    protected JarClassEntry(String name, String fullyQualifiedName, ClassEntryPopulator populator, JarRootEntry parentJar) {
        super(name, "");

        this.fullyQualifiedName = fullyQualifiedName;
        this.innerClasses = new TreeMap<>(Comparator.naturalOrder());
        this.fields = new TreeMap<>(Comparator.naturalOrder());
        this.methods = new TreeMap<>(Comparator.naturalOrder());
        this.relatedMethods = new HashMap<>();

        this.setAccess(populator.access());
        this.signature = populator.signature();
        this.superclass = populator.superclass();
        this.interfaces = Arrays.asList(populator.interfaces());

        Main.MESSAGE_DIGEST.update(parentJar.getHash());
        this.saltedClassHash = Main.MESSAGE_DIGEST.digest(populator.bytecode());

        this.subclasses = new ArrayList<>();
        this.implementers = new ArrayList<>();
    }

    protected void populateParents(JarRootEntry storage) {
        JarClassEntry superEntry = getSuperClass(storage);
        if (superEntry != null) {
            superEntry.subclasses.add(fullyQualifiedName);
        }

        for (JarClassEntry itf : getInterfaces(storage)) {
            if (itf != null) {
                itf.implementers.add(fullyQualifiedName);
            }
        }
    }

    // unstable
    public Collection<Pair<JarClassEntry, String>> getRelatedMethods(JarMethodEntry m) {
        //noinspection unchecked
        return relatedMethods.getOrDefault(m.getKey(), Collections.EMPTY_SET);
    }

    public String getFullyQualifiedName() {
        return fullyQualifiedName;
    }

    public String getSignature() {
        return signature;
    }

    public String getSuperClassName() {
        return superclass;
    }

    public JarClassEntry getSuperClass(JarRootEntry storage) {
        return storage.getClass(superclass, null, false);
    }

    public List<String> getInterfaceNames() {
        return Collections.unmodifiableList(interfaces);
    }

    public List<JarClassEntry> getInterfaces(JarRootEntry storage) {
        return toClassEntryList(storage, interfaces);
    }

    @Override
    public byte[] getHash() {
        return saltedClassHash;
    }

    public List<String> getSubclassNames() {
        return Collections.unmodifiableList(subclasses);
    }

    public List<JarClassEntry> getSubclasses(JarRootEntry storage) {
        return toClassEntryList(storage, subclasses);
    }

    public List<String> getImplementerNames() {
        return Collections.unmodifiableList(implementers);
    }

    public List<JarClassEntry> getImplementers(JarRootEntry storage) {
        return toClassEntryList(storage, implementers);
    }

    private List<JarClassEntry> toClassEntryList(JarRootEntry storage, List<String> stringList) {
        if (stringList == null) {
            return Collections.emptyList();
        }

        return stringList.stream()
              .map((s) -> storage.getClass(s, null, false))
              .filter(Objects::nonNull)
              .collect(Collectors.toList());
    }

    public JarClassEntry getInnerClass(String name) {
        return innerClasses.get(name);
    }

    public JarFieldEntry getField(String name) {
        return fields.get(name);
    }

    public JarMethodEntry getMethod(String name) {
        return methods.get(name);
    }

    public Collection<JarClassEntry> getInnerClasses() {
        return innerClasses.values();
    }

    public Collection<JarFieldEntry> getFields() {
        return fields.values();
    }

    public Collection<JarMethodEntry> getMethods() {
        return methods.values();
    }

    public boolean isInterface() {
        return Access.isInterface(getAccess());
    }

    public boolean isAnonymous() {
        return getName().matches("\\d+");
    }

    @Override
    public String getKey() {
        return getFullyQualifiedName();
    }

    public static final class ClassEntryPopulator
    {
        private final int access;
        private final String signature;
        private final String superclass;
        private final String[] interfaces;
        private final byte[] bytecode;

        public ClassEntryPopulator(int access, String signature, String superclass, String[] interfaces, byte[] bytecode) {
            this.access = access;
            this.signature = signature;
            this.superclass = superclass;
            this.interfaces = interfaces;
            this.bytecode = bytecode;
        }

        public int access() {
            return access;
        }

        public String signature() {
            return signature;
        }

        public String superclass() {
            return superclass;
        }

        public String[] interfaces() {
            return interfaces;
        }

        public byte[] bytecode() {
            return bytecode;
        }
    }
}
