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

import net.fabricmc.stitch.util.StitchUtil;

import java.util.*;

import org.objectweb.asm.Type;

public class JarMethodEntry extends AbstractJarEntry
{
    protected String desc;
    protected String signature;

    String potentialSpecializedMethod;
    JarMethodEntry bridgeMethod;
    JarMethodEntry specializedMethod;

    protected JarMethodEntry(int access, String name, String desc, String signature, String parentName) {
        super(name, parentName);
        this.setAccess(access);
        this.desc = desc;
        this.signature = signature;
    }

    public String getDescriptor() {
        return desc;
    }

    public String getSignature() {
        return signature;
    }

    void setSpecializedMethod(String specializedMethod) {
        potentialSpecializedMethod = specializedMethod;
    }

    public JarMethodEntry getSpecializedMethod() {
        return specializedMethod;
    }

    public JarMethodEntry getBridgeMethod() {
        return bridgeMethod;
    }

    void findSpecializedMethod(Classpath storage) {
        if (potentialSpecializedMethod != null) {
            JarClassEntry cls = storage.getClass(parentName);

            if (existsInSuperClasses(storage, cls, name + desc)) {
                int i = potentialSpecializedMethod.indexOf('(');
                String specializedDescriptor = potentialSpecializedMethod.substring(i);
                
                if (isBridgeMethod(storage, desc, specializedDescriptor)) {
                    specializedMethod = cls.methods.get(potentialSpecializedMethod);
                    specializedMethod.bridgeMethod = this;
                }
            }
        }
    }

    private static boolean existsInSuperClasses(Classpath storage, JarClassEntry cls, String method) {
        for (JarClassEntry superClass : cls.getSuperClasses(storage)) {
            if (superClass.methods.containsKey(method) || existsInSuperClasses(storage, superClass, method)) {
                return true;
            }
        }

        return false;
    }

    private static boolean isBridgeMethod(Classpath storage, String bridgeDescriptor, String specializedDescriptor) {
        Type bridgeType = Type.getType(bridgeDescriptor);
        Type specializedType = Type.getType(specializedDescriptor);

        Type[] bridgeArgTypes = bridgeType.getArgumentTypes();
        Type bridgeReturnType = bridgeType.getReturnType();
        Type[] specializedArgTypes = specializedType.getArgumentTypes();
        Type specializedReturnType = specializedType.getReturnType();

        if (bridgeArgTypes.length != specializedArgTypes.length) {
            return false;
        }

        for (int i = 0; i < bridgeArgTypes.length; i++) {
            if (!areTypesBridgeCompatible(storage, bridgeArgTypes[i], specializedArgTypes[i])) {
                return false;
            }
        }

        return areTypesBridgeCompatible(storage, bridgeReturnType, specializedReturnType);
    }

    private static boolean areTypesBridgeCompatible(Classpath storage, Type typeForBridge, Type typeForSpecialized) {
        if (typeForBridge.equals(typeForSpecialized)) {
            return true;
        }
        if (typeForBridge.getSort() != typeForSpecialized.getSort()) {
            return false;
        }

        switch (typeForBridge.getSort()) {
        case Type.OBJECT:
            JarClassEntry clsForBridge = storage.findClass(typeForBridge.getInternalName());
            JarClassEntry clsForSpecialized = storage.findClass(typeForSpecialized.getInternalName());

            return areClassesBridgeCompatible(storage, clsForBridge, clsForSpecialized);
        case Type.ARRAY:
            if (typeForBridge.getDimensions() != typeForSpecialized.getDimensions()) {
                return false;
            }

            return areTypesBridgeCompatible(storage, typeForBridge.getElementType(), typeForSpecialized.getElementType());
        }

        return false;
    }

    private static boolean areClassesBridgeCompatible(Classpath storage, JarClassEntry clsForBridge, JarClassEntry clsForSpecialized) {
        if (clsForBridge == clsForSpecialized) {
            return true;
        }
        if ("java/lang/Object".equals(clsForSpecialized.name)) {
            return false;
        }

        for (JarClassEntry superClsForSpecialized : clsForSpecialized.getSuperClasses(storage)) {
            if (areClassesBridgeCompatible(storage, clsForBridge, superClsForSpecialized)) {
                return true;
            }
        }

        return false;
    }

    @Override
    protected String getKey() {
        return super.getKey() + desc;
    }

    @Override
    public char getPrefix() {
        return 'm';
    }

    @Override
    public boolean isSerializable(Classpath storage) {
        if (Access.isPrivate(access)) {
            // these methods are specific to Serializable classes, but are private
            if (!("writeObject".equals(name) && "(Ljava/io/ObjectOutputStream;)V".equals(desc))
                && !("readObject".equals(name) && "(Ljava/io/ObjectInputStream;)V".equals(desc))
                && !("writeReplace".equals(name) && "()Ljava/lang/Object;".equals(desc))
                && !("readResolve".equals(name) && "()Ljava/lang/Object;".equals(desc))) {
                return false;
            }
        }

        JarClassEntry parent = storage.getClass(parentName);
        return parent != null && parent.isSerializable(storage);
    }

    @Override
    public boolean isMainJar(Classpath storage) {
        return storage.getClass(parentName).isMainJar(storage);
    }

    public boolean isSource(Classpath storage, JarClassEntry c) {
        if (Access.isPrivateOrStatic(getAccess())) {
            return true;
        }

        Set<JarClassEntry> entries = StitchUtil.newIdentityHashSet();
        entries.add(c);
        getMatchingSources(entries, storage, c, c.isOneSideOnly() ? c.side : side);
        return entries.size() == 1;
    }

    public List<JarClassEntry> getMatchingEntries(Classpath storage, JarClassEntry c) {
        if (Access.isPrivateOrStatic(getAccess())) {
            return Collections.singletonList(c);
        }

        Set<JarClassEntry> entries = StitchUtil.newIdentityHashSet();
        Set<JarClassEntry> entriesNew = StitchUtil.newIdentityHashSet();
        entries.add(c);
        int lastSize = 0;

        while (entries.size() > lastSize) {
            lastSize = entries.size();

            for (JarClassEntry cc : entries) {
                getMatchingSources(entriesNew, storage, cc, Side.ANY);
            }
            entries.addAll(entriesNew);
            entriesNew.clear();

            for (JarClassEntry cc : entries) {
                getMatchingEntries(entriesNew, storage, cc, 0);
            }
            entries.addAll(entriesNew);
            entriesNew.clear();
        }

        entries.removeIf(cc -> cc.getMethod(getKey()) == null);

        return new ArrayList<>(entries);
    }

    private void getMatchingSources(Collection<JarClassEntry> entries, Classpath storage, JarClassEntry c, Side side) {
        JarMethodEntry m = c.getMethod(getKey());
        if (m != null) {
            if ((c.isOneSideOnly() ? side.is(c.side) : side.is(m.side)) && !Access.isPrivateOrStatic(m.getAccess())) {
                entries.add(c);
            }
        }

        JarClassEntry superClass = c.getSuperClass(storage);
        if (superClass != null) {
            getMatchingSources(entries, storage, superClass, side);
        }

        for (JarClassEntry itf : c.getInterfaces(storage)) {
            getMatchingSources(entries, storage, itf, side);
        }
    }

    private void getMatchingEntries(Collection<JarClassEntry> entries, Classpath storage, JarClassEntry c, int indent) {
        entries.add(c);

        for (JarClassEntry cc : c.getSubclasses(storage)) {
            getMatchingEntries(entries, storage, cc, indent + 1);
        }

        for (JarClassEntry cc : c.getImplementers(storage)) {
            getMatchingEntries(entries, storage, cc, indent + 1);
        }
    }
}