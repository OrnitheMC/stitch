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

import net.fabricmc.stitch.util.Pair;

import java.util.*;
import java.util.stream.Collectors;

public class JarClassEntry extends AbstractJarEntry
{
    private final JarRootEntry jar;

    final Map<String, JarClassEntry> innerClasses;
    final Map<String, JarFieldEntry> fields;
    final Map<String, JarMethodEntry> methods;
    final Map<String, Set<Pair<JarClassEntry, String>>> relatedMethods;
    final Map<String, JarClassEntry> siblings;
    /** outer class for inner classes */
    String declaringClass;
    /** outer class for anonymous and local classes */
    String enclosingClass;
    String enclosingMethodName;
    String enclosingMethodDescriptor;
    String innerName;
    int innerAccess;
    String signature;
    String superclass;
    List<String> interfaces;
    List<String> subclasses;
    List<String> implementers;

    protected JarClassEntry(String name, JarRootEntry parentJar) {
        super(name, "");

        this.jar = parentJar;

        this.innerClasses = new TreeMap<>(Comparator.naturalOrder());
        this.fields = new TreeMap<>(Comparator.naturalOrder());
        this.methods = new TreeMap<>(Comparator.naturalOrder());
        this.relatedMethods = new HashMap<>();
        this.siblings = new TreeMap<>(Comparator.naturalOrder());

        this.subclasses = new ArrayList<>();
        this.implementers = new ArrayList<>();
    }

    protected void populate(ClassEntryPopulator populator) {
        this.setAccess(populator.access);
        if (populator.nested) {
            this.declaringClass = populator.declaringClassName;
            this.enclosingClass = populator.enclosingClassName;
            this.enclosingMethodName = populator.enclosingMethodName;
            this.enclosingMethodDescriptor = populator.enclosingMethodDescriptor;
            this.innerName = populator.innerName;
            this.innerAccess = populator.innerAccess;
        }
        this.signature = populator.signature;
        this.superclass = populator.superclass;
        this.interfaces = Arrays.asList(populator.interfaces);
    }

    protected void populateParents(Classpath storage) {
        // java/lang/Object does not have a super class
        if (superclass != null) {
            JarClassEntry superEntry = storage.findClass(superclass);
            if (superEntry != null) {
                superEntry.subclasses.add(name);
                if (superEntry.jar != storage.getJar()) {
                    superEntry.populateParents(storage);
                }
            }
        }

        for (int i = 0; i < interfaces.size(); i++) {
            JarClassEntry itf = storage.findClass(interfaces.get(i));
            if (itf != null) {
                itf.implementers.add(name);
                if (itf.jar != storage.getJar()) {
                    itf.populateParents(storage);
                }
            }
        }
    }

    protected void populateInnerClasses(JarRootEntry storage) {
        JarClassEntry declaringEntry = getDeclaringClass(storage);
        if (declaringEntry != null) {
            declaringEntry.innerClasses.put(name, this);
        }
        JarClassEntry enclosingEntry = getEnclosingClass(storage);
        if (enclosingEntry != null) {
            enclosingEntry.innerClasses.put(name, this);
        }
    }

    protected void populateSiblings(JarRootEntry storage) {
        String packageName = getPackageName();

        for (JarClassEntry classEntry : storage.getClasses()) {
            if (classEntry.getPackageName().equals(packageName)) {
                classEntry.siblings.put(name, this);
            }
        }
    }

    // unstable
    public Collection<Pair<JarClassEntry, String>> getRelatedMethods(JarMethodEntry m) {
        //noinspection unchecked
        return relatedMethods.getOrDefault(m.getKey(), Collections.EMPTY_SET);
    }

    public String getSignature() {
        return signature;
    }

    public String getPackageName() {
        return name.substring(0, name.lastIndexOf('/') + 1);
    }

    public String getDeclaringClassName() {
        return declaringClass;
    }

    public JarClassEntry getDeclaringClass(Classpath storage) {
        return hasDeclaringClass() ? storage.getClass(declaringClass) : null;
    }

    public JarClassEntry getDeclaringClass(JarRootEntry storage) {
        return hasDeclaringClass() ? storage.getClass(declaringClass, null) : null;
    }

    public String getEnclosingClassName() {
        return enclosingClass;
    }

    public JarClassEntry getEnclosingClass(Classpath storage) {
        return hasEnclosingClass() ? storage.getClass(enclosingClass) : null;
    }

    public JarClassEntry getEnclosingClass(JarRootEntry storage) {
        return hasEnclosingClass() ? storage.getClass(enclosingClass, null) : null;
    }

    public String getEnclosingMethodName() {
        return enclosingMethodName;
    }

    public String getEnclosingMethodDescriptor() {
        return enclosingMethodDescriptor;
    }

    public JarMethodEntry getEnclosingMethod(JarRootEntry storage) {
        return hasEnclosingClass() && hasEnclosingMethod() ? getEnclosingClass(storage).getMethod(enclosingMethodName + enclosingMethodDescriptor) : null;
    }

    public String getInnerName() {
        return innerName;
    }

    public int getInnerAccess() {
        return innerAccess;
    }

    public String getSuperClassName() {
        return superclass;
    }

    public JarClassEntry getSuperClass(Classpath storage) {
        return storage.getClass(superclass);
    }

    public List<String> getInterfaceNames() {
        return Collections.unmodifiableList(interfaces);
    }

    public List<JarClassEntry> getInterfaces(Classpath storage) {
        return toClassEntryList(storage, interfaces);
    }

    @Override
    public void hash(byte[] parentHash) {
        super.hash(parentHash);

        for (JarFieldEntry fieldEntry : fields.values()) {
            fieldEntry.hash(hash);
        }
        for (JarMethodEntry methodEntry : methods.values()) {
            methodEntry.hash(hash);
        }
    }

    public List<String> getSubclassNames() {
        return Collections.unmodifiableList(subclasses);
    }

    public List<JarClassEntry> getSubclasses(Classpath storage) {
        return toClassEntryList(storage, subclasses);
    }

    public List<String> getImplementerNames() {
        return Collections.unmodifiableList(implementers);
    }

    public List<JarClassEntry> getImplementers(Classpath storage) {
        return toClassEntryList(storage, implementers);
    }

    private List<JarClassEntry> toClassEntryList(Classpath storage, List<String> stringList) {
        if (stringList == null) {
            return Collections.emptyList();
        }

        return stringList.stream()
              .map(storage::getClass)
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

    public JarClassEntry getSibling(String name) {
        return siblings.get(name);
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

    public Collection<JarClassEntry> getSiblings() {
        return siblings.values();
    }

    public boolean isInterface() {
        return Access.isInterface(getAccess());
    }

    public boolean hasDeclaringClass() {
        return declaringClass != null;
    }

    public boolean hasEnclosingClass() {
        return enclosingClass != null;
    }

    public boolean hasEnclosingMethod() {
        return enclosingMethodName != null;
    }

    public boolean isAnonymous() {
        return hasEnclosingClass() && innerName == null;
    }

    public boolean isInner() {
        return hasDeclaringClass() && innerName != null;
    }

    public boolean isLocal() {
        return hasEnclosingClass() && innerName != null;
    }

    @Override
    public boolean isSerializable(Classpath storage) {
        JarClassEntry superClass = getSuperClass(storage);
        
        if (superClass == null) {
            return false;
        }
        if (interfaces.contains("java/io/Serializable")) {
            return true;
        }

        return superClass.isSerializable(storage);
    }

    public static final class ClassEntryPopulator
    {
        public int access;
        public String name;
        public boolean nested;
        public String declaringClassName;
        public String enclosingClassName;
        public String enclosingMethodName;
        public String enclosingMethodDescriptor;
        public String innerName;
        public int innerAccess;
        public String signature;
        public String superclass;
        public String[] interfaces;
    }
}
